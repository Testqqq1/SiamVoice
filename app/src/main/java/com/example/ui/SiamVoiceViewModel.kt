package com.example.ui

import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.SiamVoiceApplication
import com.example.audio.PcmPlayer
import com.example.data.AppSettings
import com.example.data.SecureApiKeyStore
import com.example.data.effectivePoliteEndingStyle
import com.example.engine.DispatchEvent
import com.example.engine.SynthDispatcher
import com.example.model.DiagnosticsInfo
import com.example.model.ExternalEngineInfo
import com.example.model.GeminiModelPreference
import com.example.model.PoliteEndingStyle
import com.example.model.SynthesisRequestData
import com.example.model.SynthesisResult
import com.example.model.VerifiedModelsStatus
import com.example.model.VoiceStylePreset
import com.example.network.GeminiTtsClient
import com.example.tts.TextToSpeechManager
import com.example.tts.TtsManagerState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class UiState(
    val inputText: String = "อีก 300 เมตร เลี้ยวซ้าย",
    val isSynthesizing: Boolean = false,
    val isPlaying: Boolean = false,
    val lastResult: SynthesisResult? = null,
    val statusMessage: String? = null,
    val errorMessage: String? = null,
    val settings: AppSettings = AppSettings(),
    val diagnostics: DiagnosticsInfo = DiagnosticsInfo(),
    val availableEngines: List<ExternalEngineInfo> = emptyList(),
    val ttsManagerState: TtsManagerState = TtsManagerState.Uninitialized,
    val lastDispatchEvent: DispatchEvent? = null,
    // BYOK Gemini API Key state
    val apiKeyInput: String = "",
    val isApiKeyVisible: Boolean = false,
    val hasStoredApiKey: Boolean = false,
    val maskedApiKey: String = "",
    val isTestingConnection: Boolean = false,
    val apiKeyStatusMessage: String? = null,
    val apiKeyErrorMessage: String? = null,
    val verifiedModelsStatus: VerifiedModelsStatus = VerifiedModelsStatus(),
    // One-time 18+ age confirmation dialog; [pendingAdultPreset] is applied after the user confirms.
    val showAdultDialog: Boolean = false,
    val pendingAdultPreset: VoiceStylePreset? = null
)

class SiamVoiceViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as SiamVoiceApplication
    private val synthesizer = app.synthesizer
    private val preferences = app.preferences
    private val apiKeyStore: SecureApiKeyStore = app.apiKeyStore
    private val ttsManager: TextToSpeechManager = app.ttsManager
    private val synthDispatcher: SynthDispatcher = app.synthDispatcher
    private val pcmPlayer = PcmPlayer()

    private val _uiState = MutableStateFlow(
        UiState(
            settings = preferences.getSettings(),
            diagnostics = synthesizer.diagnostics.value,
            ttsManagerState = ttsManager.state.value,
            lastDispatchEvent = synthDispatcher.lastDispatchEvent.value,
            hasStoredApiKey = apiKeyStore.hasApiKey(),
            maskedApiKey = apiKeyStore.getMaskedApiKey(),
            verifiedModelsStatus = VerifiedModelsStatus(
                isVerified = false,
                flashModelId = preferences.getSettings().verifiedFlashModelId,
                flashLiteModelId = preferences.getSettings().verifiedFlashLiteModelId
            )
        )
    )
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    private var activePlaybackJob: Job? = null

    init {
        viewModelScope.launch {
            preferences.settingsFlow.collect { newSettings ->
                _uiState.update {
                    it.copy(
                        settings = newSettings,
                        verifiedModelsStatus = it.verifiedModelsStatus.copy(
                            flashModelId = newSettings.verifiedFlashModelId,
                            flashLiteModelId = newSettings.verifiedFlashLiteModelId
                        )
                    )
                }
            }
        }
        viewModelScope.launch {
            synthesizer.diagnostics.collect { diag ->
                _uiState.update { it.copy(diagnostics = diag) }
            }
        }
        viewModelScope.launch {
            ttsManager.state.collect { mgrState ->
                _uiState.update { it.copy(ttsManagerState = mgrState) }
            }
        }
        viewModelScope.launch {
            synthDispatcher.lastDispatchEvent.collect { event ->
                _uiState.update { it.copy(lastDispatchEvent = event) }
            }
        }
        refreshInstalledEngines()
    }

    fun onTextChanged(newText: String) {
        _uiState.update { it.copy(inputText = newText, errorMessage = null) }
    }

    fun setQuickPhrase(phrase: String) {
        _uiState.update { it.copy(inputText = phrase, errorMessage = null) }
    }

    // --- Gemini API Key (BYOK) Management ---

    fun onApiKeyInputChanged(newKeyInput: String) {
        _uiState.update {
            it.copy(
                apiKeyInput = newKeyInput,
                apiKeyErrorMessage = null,
                apiKeyStatusMessage = null
            )
        }
    }

    fun toggleApiKeyVisibility() {
        _uiState.update { it.copy(isApiKeyVisible = !it.isApiKeyVisible) }
    }

    fun saveApiKey(rawInput: String = _uiState.value.apiKeyInput): Boolean {
        val validationError = apiKeyStore.validateKeyInput(rawInput)
        if (validationError != null) {
            _uiState.update {
                it.copy(
                    apiKeyErrorMessage = validationError,
                    apiKeyStatusMessage = null
                )
            }
            return false
        }

        return try {
            val hadExistingKey = apiKeyStore.hasApiKey()
            apiKeyStore.saveApiKey(rawInput)
            synthesizer.resetCircuitBreakers()
            val actionWord = if (hadExistingKey) "เปลี่ยนและบันทึก" else "บันทึก"
            _uiState.update {
                it.copy(
                    apiKeyInput = "",
                    isApiKeyVisible = false,
                    hasStoredApiKey = apiKeyStore.hasApiKey(),
                    maskedApiKey = apiKeyStore.getMaskedApiKey(),
                    apiKeyErrorMessage = null,
                    apiKeyStatusMessage = "${actionWord} Gemini API Key ลงใน Android Keystore เรียบร้อยแล้ว"
                )
            }
            true
        } catch (e: Exception) {
            _uiState.update {
                it.copy(
                    apiKeyErrorMessage = e.localizedMessage ?: "ไม่สามารถบันทึก API Key ได้",
                    apiKeyStatusMessage = null
                )
            }
            false
        }
    }

    fun deleteApiKey() {
        apiKeyStore.deleteApiKey()
        synthesizer.refreshDiagnosticsState(lastError = null)
        _uiState.update {
            it.copy(
                apiKeyInput = "",
                isApiKeyVisible = false,
                hasStoredApiKey = false,
                maskedApiKey = "",
                apiKeyErrorMessage = null,
                apiKeyStatusMessage = "ลบ Gemini API Key ออกจากเครื่องแล้ว (ระบบจะสลับไปใช้ Android TTS สำรองอัตโนมัติ)"
            )
        }
    }

    fun testApiConnection(customClient: GeminiTtsClient? = null) {
        viewModelScope.launch {
            val pendingInput = _uiState.value.apiKeyInput.trim()
            if (pendingInput.isNotEmpty()) {
                val saved = saveApiKey(pendingInput)
                if (!saved) return@launch
            }

            if (!apiKeyStore.hasApiKey()) {
                _uiState.update {
                    it.copy(
                        apiKeyErrorMessage = "กรุณากรอกและบันทึก Gemini API Key ก่อนทดสอบการเชื่อมต่อ",
                        apiKeyStatusMessage = null
                    )
                }
                return@launch
            }

            _uiState.update {
                it.copy(
                    isTestingConnection = true,
                    apiKeyErrorMessage = null,
                    apiKeyStatusMessage = "กำลังตรวจสอบสิทธิ์และรายการโมเดล Gemini TTS..."
                )
            }

            try {
                val client = customClient ?: GeminiTtsClient(getApplication())
                val modelStatus = client.verifyAvailableModels()
                preferences.updateVerifiedModelIds(
                    flashModelId = modelStatus.flashModelId,
                    flashLiteModelId = modelStatus.flashLiteModelId
                )
                synthesizer.resetCircuitBreakers()
                _uiState.update {
                    it.copy(
                        isTestingConnection = false,
                        verifiedModelsStatus = modelStatus,
                        apiKeyErrorMessage = null,
                        apiKeyStatusMessage = modelStatus.statusMessage
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                _uiState.update {
                    it.copy(
                        isTestingConnection = false,
                        apiKeyErrorMessage = t.localizedMessage ?: "การเชื่อมต่อล้มเหลว กรุณาตรวจสอบ API Key และอินเทอร์เน็ต",
                        apiKeyStatusMessage = null
                    )
                }
            }
        }
    }

    fun openAiStudioApiKeyPage(context: Context) {
        try {
            val intent = Intent(
                Intent.ACTION_VIEW,
                Uri.parse(SecureApiKeyStore.OFFICIAL_API_KEY_PORTAL_URL)
            ).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            Log.w("SiamVoiceViewModel", "Unable to open browser: ${e.message}")
        }
    }

    // --- Voice, Personality, Polite Ending & Model Preferences ---

    /**
     * Selects a voice style. 18+ styles show the one-time age confirmation first
     * (see [confirmAdultContent]); they are never applied before the user confirms.
     */
    fun selectVoicePreset(preset: VoiceStylePreset) {
        if (!preferences.updateVoicePreset(preset)) {
            _uiState.update { it.copy(showAdultDialog = true, pendingAdultPreset = preset) }
        }
    }

    /** Opens the age confirmation dialog from the settings switch (no style is selected yet). */
    fun requestAdultContentUnlock() {
        _uiState.update { it.copy(showAdultDialog = true, pendingAdultPreset = null) }
    }

    /** User tapped "I confirm I am 18 or older": remember it and apply the style they asked for. */
    fun confirmAdultContent() {
        val pending = _uiState.value.pendingAdultPreset
        preferences.confirmAdultContent()
        if (pending != null) preferences.updateVoicePreset(pending)
        _uiState.update { it.copy(showAdultDialog = false, pendingAdultPreset = null) }
    }

    fun dismissAdultDialog() {
        _uiState.update { it.copy(showAdultDialog = false, pendingAdultPreset = null) }
    }

    /** Turns the 18+ styles off again and forgets the age confirmation. */
    fun revokeAdultContent() {
        preferences.revokeAdultContent()
    }

    fun selectGeminiVoice(voice: String) {
        preferences.updateGeminiVoice(voice)
    }

    fun selectPoliteStyle(style: PoliteEndingStyle) {
        preferences.updatePoliteEndingStyle(style)
    }

    fun selectModelPreference(preference: GeminiModelPreference) {
        preferences.updateModelPreference(preference)
    }

    fun toggleAutoFallback(enabled: Boolean) {
        preferences.updateAutoFallback(enabled)
    }

    fun selectPreferredEngine(pkg: String) {
        preferences.updatePreferredAndroidEngine(pkg)
    }

    fun updateFallbackRate(rate: Float) {
        preferences.updateFallbackSpeechRate(rate)
    }

    fun updateSpeechPitch(pitch: Float) {
        preferences.updateSpeechPitch(pitch)
    }

    fun clearCache() {
        synthesizer.clearCache()
        _uiState.update { it.copy(statusMessage = "ล้างแคชเสียงเรียบร้อยแล้ว") }
    }

    fun refreshInstalledEngines() {
        viewModelScope.launch {
            val engines = ttsManager.getInstalledExternalEngines()
            _uiState.update { it.copy(availableEngines = engines) }
        }
    }

    // --- Speech Synthesis & Playback ---

    fun speak(textToSpeak: String = _uiState.value.inputText) {
        stop()
        if (textToSpeak.isBlank()) return

        activePlaybackJob = viewModelScope.launch {
            _uiState.update {
                it.copy(
                    isSynthesizing = true,
                    errorMessage = null,
                    statusMessage = "กำลังสังเคราะห์เสียง..."
                )
            }
            try {
                val currentSettings = preferences.getSettings()
                val request = SynthesisRequestData(
                    text = textToSpeak,
                    politeStyle = currentSettings.effectivePoliteEndingStyle,
                    voicePreset = currentSettings.voicePreset,
                    geminiVoice = currentSettings.geminiVoice,
                    stylePrompt = currentSettings.voicePreset.stylePrompt,
                    speechRate = currentSettings.fallbackSpeechRate,
                    pitch = currentSettings.speechPitch
                )

                // Shared synthesis pipeline:
                // 1. Gemini Flash TTS -> 2. Gemini Flash-Lite TTS -> 3. External Android System TTS
                val result = synthesizer.synthesize(request)

                _uiState.update {
                    it.copy(
                        isSynthesizing = false,
                        isPlaying = true,
                        lastResult = result,
                        statusMessage = "เล่นเสียงผ่าน ${result.engineUsed.displayName} (${result.latencyMs}ms)" +
                                if (result.wasCached) " [แคช]" else ""
                    )
                }

                pcmPlayer.play(result.audioData, result.sampleRate, result.channelCount)

                _uiState.update { it.copy(isPlaying = false) }
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                Log.e("SiamVoiceViewModel", "Speak error: ${t.message}", t)
                _uiState.update {
                    it.copy(
                        isSynthesizing = false,
                        isPlaying = false,
                        errorMessage = t.localizedMessage ?: "เกิดข้อผิดพลาดในการสังเคราะห์เสียง"
                    )
                }
            }
        }
    }

    fun stop() {
        activePlaybackJob?.cancel()
        activePlaybackJob = null
        pcmPlayer.stop()
        ttsManager.stop()
        _uiState.update { it.copy(isSynthesizing = false, isPlaying = false) }
    }

    fun openSystemTtsSettings(context: Context) {
        try {
            val intent = Intent("com.android.settings.TTS_SETTINGS").apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            val fallbackIntent = Intent(android.provider.Settings.ACTION_SETTINGS).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(fallbackIntent)
        }
    }

    override fun onCleared() {
        super.onCleared()
        stop()
        ttsManager.shutdown()
    }
}
