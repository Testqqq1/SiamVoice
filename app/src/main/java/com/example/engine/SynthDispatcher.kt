package com.example.engine

import android.content.Context
import com.example.audio.DecodedPcmAudio
import com.example.cache.VoiceClipCache
import com.example.data.SiamVoicePreferences
import com.example.model.PoliteEndingStyle
import com.example.model.SynthesisRequestData
import com.example.model.SynthesisResult
import com.example.model.TtsEngineType
import com.example.network.GeminiPromptService
import com.example.network.GeminiTtsClient
import com.example.tts.TextToSpeechManager
import com.example.tts.TtsManagerState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class SystemTtsEvaluation(
    val isHealthy: Boolean,
    val state: TtsManagerState,
    val reason: String,
    val recommendedRoute: TtsEngineType
)

data class DispatchEvent(
    val initialTarget: TtsEngineType,
    val finalEngineUsed: TtsEngineType,
    val failoverOccurred: Boolean,
    val failoverReason: String?,
    val processedText: String,
    val latencyMs: Long
)

/**
 * Thin facade over the single shared [SiamVoiceTtsSynthesizer] pipeline, eliminating
 * duplicate speech-routing logic between SynthDispatcher and SiamVoiceTtsSynthesizer.
 *
 * Shared strict priority order:
 * 1. Gemini Flash TTS (gemini-3.8-flash-tts)
 * 2. Gemini Flash-Lite TTS (gemini-3.8-flash-lite-tts)
 * 3. External Android TTS (non-recursive fallback)
 */
open class SynthDispatcher(
    private val context: Context,
    private val preferences: SiamVoicePreferences,
    private val ttsManager: TextToSpeechManager = TextToSpeechManager(context),
    private val geminiTtsClient: GeminiTtsClient = GeminiTtsClient(context),
    private val geminiPromptService: GeminiPromptService = GeminiPromptService(),
    private val voiceClipCache: VoiceClipCache = VoiceClipCache(context),
    private val sharedSynthesizer: SiamVoiceTtsSynthesizer? = null
) {
    // Delegate to the provided shared synthesizer or construct a single pipeline instance
    // backed by the same cache, GeminiTtsClient, and TextToSpeechManager.
    private val pipeline: SiamVoiceTtsSynthesizer = sharedSynthesizer ?: object : SiamVoiceTtsSynthesizer(
        context = context,
        preferences = preferences,
        voiceClipCache = voiceClipCache,
        geminiClient = geminiTtsClient,
        androidFallbackEngine = object : AndroidSystemTtsEngine(context) {
            override suspend fun synthesizeToFile(
                text: String,
                preferredEnginePackage: String?,
                speechRate: Float,
                pitch: Float,
                timeoutMs: Long
            ): DecodedPcmAudio {
                return ttsManager.synthesizeToPcm(
                    text = text,
                    politeStyle = PoliteEndingStyle.OFF, // already processed in pipeline
                    preferredEnginePackage = preferredEnginePackage,
                    speechRate = speechRate,
                    pitch = pitch,
                    timeoutMs = timeoutMs
                )
            }
        }
    ) {
        override fun isNetworkAvailable(): Boolean = true
    }

    val flashCircuitBreaker: CircuitBreaker
        get() = pipeline.flashCircuitBreaker

    val flashLiteCircuitBreaker: CircuitBreaker
        get() = pipeline.flashLiteCircuitBreaker

    private val _lastDispatchEvent = MutableStateFlow<DispatchEvent?>(null)
    val lastDispatchEvent: StateFlow<DispatchEvent?> = _lastDispatchEvent.asStateFlow()

    /**
     * Evaluates the operational status of the external Android system TTS fallback engine,
     * while always recommending [TtsEngineType.GEMINI_FLASH] as the primary unified route.
     */
    open fun evaluateSystemTtsStatus(): SystemTtsEvaluation {
        val currentState = ttsManager.state.value
        val externalEngines = ttsManager.getInstalledExternalEngines()

        if (externalEngines.isEmpty()) {
            return SystemTtsEvaluation(
                isHealthy = false,
                state = currentState,
                reason = "No external Android system TTS engine installed",
                recommendedRoute = TtsEngineType.GEMINI_FLASH
            )
        }

        return when (currentState) {
            is TtsManagerState.Ready -> {
                if (currentState.isThaiSupported) {
                    SystemTtsEvaluation(
                        isHealthy = true,
                        state = currentState,
                        reason = "External Android TTS fallback ready (${currentState.enginePackage})",
                        recommendedRoute = TtsEngineType.GEMINI_FLASH
                    )
                } else {
                    SystemTtsEvaluation(
                        isHealthy = false,
                        state = currentState,
                        reason = "External Android TTS does not support Thai (th-TH)",
                        recommendedRoute = TtsEngineType.GEMINI_FLASH
                    )
                }
            }
            is TtsManagerState.Error -> {
                SystemTtsEvaluation(
                    isHealthy = false,
                    state = currentState,
                    reason = currentState.message,
                    recommendedRoute = TtsEngineType.GEMINI_FLASH
                )
            }
            is TtsManagerState.Initializing, is TtsManagerState.Uninitialized -> {
                SystemTtsEvaluation(
                    isHealthy = true,
                    state = currentState,
                    reason = "External Android TTS fallback available (${externalEngines.first().packageName})",
                    recommendedRoute = TtsEngineType.GEMINI_FLASH
                )
            }
        }
    }

    /**
     * Dispatches synthesis requests directly through the single shared [SiamVoiceTtsSynthesizer] pipeline.
     */
    open suspend fun dispatch(request: SynthesisRequestData): SynthesisResult {
        val result = pipeline.synthesize(request)
        val failoverOccurred = result.engineUsed != TtsEngineType.GEMINI_FLASH
        _lastDispatchEvent.value = DispatchEvent(
            initialTarget = TtsEngineType.GEMINI_FLASH,
            finalEngineUsed = result.engineUsed,
            failoverOccurred = failoverOccurred,
            failoverReason = if (failoverOccurred) "Primary Gemini Flash unavailable; routed to ${result.engineUsed.displayName}" else null,
            processedText = result.processedText,
            latencyMs = result.latencyMs
        )
        return result
    }
}
