package com.example.engine

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import com.example.cache.VoiceClipCache
import com.example.data.SecureApiKeyStore
import com.example.data.SiamVoicePreferences
import com.example.model.DiagnosticsInfo
import com.example.model.GeminiModelPreference
import com.example.model.SynthesisRequestData
import com.example.model.SynthesisResult
import com.example.model.TtsEngineType
import com.example.model.VoiceStylePreset
import com.example.network.GeminiTtsClient
import com.example.processor.ThaiPoliteEndingProcessor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

open class SiamVoiceTtsSynthesizer(
    private val context: Context,
    private val preferences: SiamVoicePreferences,
    private val voiceClipCache: VoiceClipCache = VoiceClipCache(context),
    private val geminiClient: GeminiTtsClient = GeminiTtsClient(context),
    private val androidFallbackEngine: AndroidSystemTtsEngine = AndroidSystemTtsEngine(context),
    private val apiKeyStore: SecureApiKeyStore = SecureApiKeyStore(context)
) {
    companion object {
        private const val TAG = "SiamVoiceSynthesizer"
    }

    val flashCircuitBreaker = CircuitBreaker("GeminiFlashTTS", failureThreshold = 2)
    val flashLiteCircuitBreaker = CircuitBreaker("GeminiFlashLiteTTS", failureThreshold = 2)

    private val _diagnostics = MutableStateFlow(
        DiagnosticsInfo(
            isOnline = isNetworkAvailable(),
            hasApiKey = apiKeyStore.hasApiKey(),
            cacheItemsCount = voiceClipCache.count()
        )
    )
    val diagnostics: StateFlow<DiagnosticsInfo> = _diagnostics.asStateFlow()

    open fun isNetworkAvailable(): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    fun resetCircuitBreakers() {
        flashCircuitBreaker.reset()
        flashLiteCircuitBreaker.reset()
        refreshDiagnosticsState()
    }

    fun refreshDiagnosticsState(lastError: String? = _diagnostics.value.lastErrorSummary) {
        _diagnostics.value = _diagnostics.value.copy(
            isOnline = isNetworkAvailable(),
            hasApiKey = apiKeyStore.hasApiKey(),
            flashCircuitBreakerState = flashCircuitBreaker.getState(),
            flashLiteCircuitBreakerState = flashLiteCircuitBreaker.getState(),
            cacheItemsCount = voiceClipCache.count(),
            lastErrorSummary = lastError
        )
    }

    /**
     * Executes synthesis following the unified priority chain:
     * 1. Gemini Flash TTS
     * 2. Gemini Flash-Lite TTS
     * 3. External Android System TTS (non-recursive fallback)
     *
     * Automatically falls back to external Android TTS when Gemini fails due to:
     * - Missing API Key
     * - No internet
     * - Quota exhaustion
     * - API errors
     * - Network timeout
     * - Model unavailability
     */
    open suspend fun synthesize(request: SynthesisRequestData): SynthesisResult = withContext(Dispatchers.IO) {
        val startTime = System.currentTimeMillis()
        val settings = preferences.getSettings()

        // 0. 18+ styles require the one-time age confirmation; otherwise fall back to the default style.
        val req = enforceAdultGate(request, settings.adultContentConfirmed)
        val preset = req.voicePreset

        // 1. Process polite Thai ending once so Gemini and Android fallback receive identical transcripts.
        //    18+ presets switch the automatic "ครับ/ค่ะ" processor off.
        val politeStyle = preset.resolvePoliteEnding(req.politeStyle)
        val processedText = ThaiPoliteEndingProcessor.process(req.text, politeStyle)
        // Style hints only apply to the Android fallback / cache key; presets without hints are unchanged.
        val baseRate = req.speechRate * settings.fallbackSpeechRate
        val effectiveRate = if (preset.speechRateHint == 1.0f) baseRate
        else (baseRate * preset.speechRateHint).coerceIn(0.5f, 2.5f)
        val effectivePitch = if (preset.pitchHint == 1.0f) req.pitch
        else (req.pitch * preset.pitchHint).coerceIn(0.5f, 2.0f)
        val voiceStyleId = preset.name

        val flashModelId = settings.verifiedFlashModelId.ifBlank { GeminiTtsClient.MODEL_FLASH }
        val flashLiteModelId = settings.verifiedFlashLiteModelId.ifBlank { GeminiTtsClient.MODEL_FLASH_LITE }

        // 2. Check Cache first (keyed by model, voice, stylePrompt, politeStyle, rate, pitch, processedText)
        val cached = voiceClipCache.get(
            modelName = flashModelId,
            voiceName = req.geminiVoice,
            text = processedText,
            stylePrompt = req.stylePrompt,
            politeStyle = politeStyle,
            speechRate = effectiveRate,
            pitch = effectivePitch,
            voiceStyle = voiceStyleId
        ) ?: voiceClipCache.get(
            modelName = flashLiteModelId,
            voiceName = req.geminiVoice,
            text = processedText,
            stylePrompt = req.stylePrompt,
            politeStyle = politeStyle,
            speechRate = effectiveRate,
            pitch = effectivePitch,
            voiceStyle = voiceStyleId
        )

        if (cached != null) {
            val totalLatency = System.currentTimeMillis() - startTime
            updateDiagnostics(
                engine = cached.engineUsed,
                latencyMs = totalLatency,
                processedText = processedText,
                lastError = null
            )
            return@withContext cached.copy(
                latencyMs = totalLatency,
                processedText = processedText,
                voiceName = req.geminiVoice,
                stylePrompt = req.stylePrompt
            )
        }

        val online = isNetworkAvailable()
        var lastException: Throwable? = null

        data class CloudStage(
            val modelId: String,
            val engineType: TtsEngineType,
            val breaker: CircuitBreaker
        )

        val orderedCloudStages = when (settings.modelPreference) {
            GeminiModelPreference.FLASH_PRIMARY -> listOf(
                CloudStage(flashModelId, TtsEngineType.GEMINI_FLASH, flashCircuitBreaker),
                CloudStage(flashLiteModelId, TtsEngineType.GEMINI_FLASH_LITE, flashLiteCircuitBreaker)
            )
            GeminiModelPreference.FLASH_LITE_PRIMARY -> listOf(
                CloudStage(flashLiteModelId, TtsEngineType.GEMINI_FLASH_LITE, flashLiteCircuitBreaker),
                CloudStage(flashModelId, TtsEngineType.GEMINI_FLASH, flashCircuitBreaker)
            )
        }

        // 3 & 4. Execute ordered Gemini Cloud TTS stages (Flash -> Flash-Lite)
        for (stage in orderedCloudStages) {
            if (online && stage.breaker.canExecute()) {
                try {
                    val pcmAudio = geminiClient.synthesize(
                        text = processedText,
                        modelId = stage.modelId,
                        voiceName = req.geminiVoice,
                        stylePrompt = req.stylePrompt
                    )
                    if (pcmAudio.pcmData.isEmpty()) {
                        throw InvalidAudioException("${stage.engineType.displayName} returned empty PCM audio")
                    }
                    stage.breaker.recordSuccess()
                    val totalLatency = System.currentTimeMillis() - startTime
                    val result = SynthesisResult(
                        audioData = pcmAudio.pcmData,
                        sampleRate = pcmAudio.sampleRate,
                        channelCount = pcmAudio.channelCount,
                        engineUsed = stage.engineType,
                        latencyMs = totalLatency,
                        wasCached = false,
                        processedText = processedText,
                        voiceName = req.geminiVoice,
                        stylePrompt = req.stylePrompt
                    )
                    voiceClipCache.put(
                        modelName = stage.modelId,
                        voiceName = req.geminiVoice,
                        text = processedText,
                        result = result,
                        stylePrompt = req.stylePrompt,
                        politeStyle = politeStyle,
                        speechRate = effectiveRate,
                        pitch = effectivePitch,
                        voiceStyle = voiceStyleId
                    )
                    updateDiagnostics(stage.engineType, totalLatency, processedText, lastError = null)
                    return@withContext result
                } catch (e: CancellationException) {
                    throw e
                } catch (t: Throwable) {
                    Log.w(TAG, "${stage.engineType.displayName} (${stage.modelId}) failed: ${t.message}", t)
                    stage.breaker.recordFailure(t)
                    lastException = t
                    if (t is AuthFailedException) {
                        // Missing or invalid API Key affects all Gemini models; skip straight to Android TTS fallback
                        break
                    }
                }
            } else {
                Log.d(TAG, "Skipping ${stage.engineType.displayName}: online=$online, breaker=${stage.breaker.getState()}")
            }
        }

        // 5. Final Fallback: External Android System TTS (receives identical processedText)
        if (settings.autoFallbackEnabled) {
            try {
                val pcmAudio = androidFallbackEngine.synthesizeToFile(
                    text = processedText,
                    preferredEnginePackage = settings.preferredAndroidEngine.ifBlank { null },
                    speechRate = effectiveRate,
                    pitch = effectivePitch
                )
                val totalLatency = System.currentTimeMillis() - startTime
                val result = SynthesisResult(
                    audioData = pcmAudio.pcmData,
                    sampleRate = pcmAudio.sampleRate,
                    channelCount = pcmAudio.channelCount,
                    engineUsed = TtsEngineType.ANDROID_SYSTEM_TTS,
                    latencyMs = totalLatency,
                    wasCached = false,
                    processedText = processedText,
                    voiceName = req.geminiVoice,
                    stylePrompt = req.stylePrompt
                )
                updateDiagnostics(
                    engine = TtsEngineType.ANDROID_SYSTEM_TTS,
                    latencyMs = totalLatency,
                    processedText = processedText,
                    lastError = lastException?.message
                )
                return@withContext result
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                Log.e(TAG, "External Android System TTS fallback also failed: ${t.message}", t)
                val cloudReason = lastException?.message ?: if (!online) "ไม่มีการเชื่อมต่ออินเทอร์เน็ต" else "ไม่ทราบสาเหตุ"
                updateDiagnostics(
                    engine = TtsEngineType.ANDROID_SYSTEM_TTS,
                    latencyMs = System.currentTimeMillis() - startTime,
                    processedText = processedText,
                    lastError = cloudReason
                )
                throw FallbackSynthesisException(
                    "ไม่สามารถสังเคราะห์เสียงได้ (Gemini: $cloudReason | Android TTS: ${t.message})"
                )
            }
        } else {
            val cloudReason = lastException?.message ?: if (!online) "ไม่มีการเชื่อมต่ออินเทอร์เน็ต" else "ไม่ทราบสาเหตุ"
            updateDiagnostics(
                engine = TtsEngineType.GEMINI_FLASH,
                latencyMs = System.currentTimeMillis() - startTime,
                processedText = processedText,
                lastError = cloudReason
            )
            throw FallbackSynthesisException(
                "Gemini TTS ล้มเหลว ($cloudReason) และปิดระบบสำรอง Android TTS ไว้"
            )
        }
    }

    /**
     * Defence in depth: even if a caller hands us an 18+ preset, it is only honoured when the user
     * confirmed they are 18 or older. Otherwise the request is downgraded to the default style.
     */
    internal fun enforceAdultGate(request: SynthesisRequestData, adultConfirmed: Boolean): SynthesisRequestData {
        if (!request.voicePreset.isAdult || adultConfirmed) return request
        val safe = VoiceStylePreset.FRIENDLY_COMPANION
        return request.copy(
            voicePreset = safe,
            geminiVoice = safe.voiceName,
            stylePrompt = safe.stylePrompt
        )
    }

    private fun updateDiagnostics(
        engine: TtsEngineType,
        latencyMs: Long,
        processedText: String,
        lastError: String?
    ) {
        _diagnostics.value = _diagnostics.value.copy(
            activeEngine = engine,
            lastLatencyMs = latencyMs,
            lastProcessedText = processedText,
            isOnline = isNetworkAvailable(),
            hasApiKey = apiKeyStore.hasApiKey(),
            flashCircuitBreakerState = flashCircuitBreaker.getState(),
            flashLiteCircuitBreakerState = flashLiteCircuitBreaker.getState(),
            cacheItemsCount = voiceClipCache.count(),
            lastErrorSummary = lastError
        )
    }

    fun clearCache() {
        voiceClipCache.clear()
        _diagnostics.value = _diagnostics.value.copy(cacheItemsCount = 0)
    }
}
