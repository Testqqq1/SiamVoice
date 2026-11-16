package com.example.tts

import android.content.Context
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import android.util.Log
import com.example.audio.AudioDecoder
import com.example.audio.DecodedPcmAudio
import com.example.engine.FallbackSynthesisException
import com.example.engine.LanguageNotSupportedException
import com.example.engine.NoCompatibleEngineException
import com.example.model.ExternalEngineInfo
import com.example.model.PoliteEndingStyle
import com.example.processor.ThaiPoliteEndingProcessor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.File
import java.util.Locale
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

sealed interface TtsManagerState {
    data object Uninitialized : TtsManagerState
    data object Initializing : TtsManagerState
    data class Ready(
        val enginePackage: String,
        val languageTag: String,
        val voiceName: String?,
        val isThaiSupported: Boolean
    ) : TtsManagerState
    data class Error(val message: String) : TtsManagerState
}

/**
 * Manages initialization, lifecycle, Thai locale configuration (th-TH),
 * direct playback, and file synthesis for the Android native TextToSpeech engine.
 * Strictly guards against selecting SiamVoice itself to prevent recursive loops.
 */
open class TextToSpeechManager(private val context: Context) {

    companion object {
        private const val TAG = "TextToSpeechManager"
        const val GOOGLE_TTS_ENGINE = "com.google.android.tts"
        val THAI_LOCALE: Locale = Locale.forLanguageTag("th-TH")
        val THAI_FALLBACK_LOCALE: Locale = Locale.forLanguageTag("th")
    }

    private var tts: TextToSpeech? = null
    private var currentEnginePackage: String? = null

    private val _state = MutableStateFlow<TtsManagerState>(TtsManagerState.Uninitialized)
    val state: StateFlow<TtsManagerState> = _state.asStateFlow()

    private val _isSpeaking = MutableStateFlow(false)
    val isSpeaking: StateFlow<Boolean> = _isSpeaking.asStateFlow()

    /**
     * Discovers installed TTS engines on the device while excluding SiamVoice itself.
     */
    open fun getInstalledExternalEngines(): List<ExternalEngineInfo> {
        val engines = mutableListOf<ExternalEngineInfo>()
        var tempTts: TextToSpeech? = null
        try {
            tempTts = TextToSpeech(context, null)
            val defaultEngine = tempTts.defaultEngine.orEmpty()
            val installed = tempTts.engines ?: emptyList()

            for (info in installed) {
                if (info.name.equals(context.packageName, ignoreCase = true)) {
                    continue
                }
                engines.add(
                    ExternalEngineInfo(
                        packageName = info.name,
                        label = info.label.ifEmpty { info.name },
                        supportsThai = true,
                        isSystemDefault = info.name.equals(defaultEngine, ignoreCase = true)
                    )
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error discovering installed TTS engines: ${e.message}", e)
        } finally {
            try {
                tempTts?.shutdown()
            } catch (ignored: Exception) {}
        }
        return engines
    }

    /**
     * Resolves the best non-recursive external Android TTS engine package.
     */
    open fun resolveTargetEnginePackage(preferredPackage: String? = null): String? {
        val available = getInstalledExternalEngines()
        if (available.isEmpty()) return null

        return when {
            !preferredPackage.isNullOrBlank() &&
                !preferredPackage.equals(context.packageName, ignoreCase = true) &&
                available.any { it.packageName == preferredPackage } -> preferredPackage
            available.any { it.packageName == GOOGLE_TTS_ENGINE } -> GOOGLE_TTS_ENGINE
            else -> available.first().packageName
        }
    }

    /**
     * Asynchronously initializes the Android native TextToSpeech engine with Thai (th-TH) support.
     */
    open suspend fun initialize(
        preferredEnginePackage: String? = null,
        speechRate: Float = 1.0f,
        pitch: Float = 1.0f
    ): Result<TtsManagerState.Ready> = withContext(Dispatchers.Main) {
        val targetEngine = resolveTargetEnginePackage(preferredEnginePackage)
        if (targetEngine == null) {
            val err = "No external Android TTS engine installed on device."
            _state.value = TtsManagerState.Error(err)
            return@withContext Result.failure(NoCompatibleEngineException(err))
        }

        if (targetEngine.equals(context.packageName, ignoreCase = true)) {
            val err = "Recursive invocation blocked: cannot initialize SiamVoice as fallback."
            _state.value = TtsManagerState.Error(err)
            return@withContext Result.failure(IllegalStateException(err))
        }

        // If already initialized with the same engine and ready, update rate/pitch and return
        val existingState = _state.value
        if (tts != null && currentEnginePackage == targetEngine && existingState is TtsManagerState.Ready) {
            tts?.setSpeechRate(speechRate.coerceIn(0.5f, 2.0f))
            tts?.setPitch(pitch.coerceIn(0.5f, 2.0f))
            return@withContext Result.success(existingState)
        }

        shutdown()
        _state.value = TtsManagerState.Initializing

        suspendCancellableCoroutine { continuation ->
            var engineRef: TextToSpeech? = null
            engineRef = TextToSpeech(context.applicationContext, { status ->
                if (status != TextToSpeech.SUCCESS) {
                    val msg = "Failed to initialize Android TextToSpeech engine ($targetEngine), status=$status"
                    Log.e(TAG, msg)
                    _state.value = TtsManagerState.Error(msg)
                    if (continuation.isActive) {
                        continuation.resume(Result.failure(FallbackSynthesisException(msg)))
                    }
                    return@TextToSpeech
                }

                val activeTts = engineRef ?: return@TextToSpeech
                val thaiSupported = configureThaiLanguage(activeTts, speechRate, pitch)
                if (!thaiSupported) {
                    val msg = "Selected TTS engine ($targetEngine) does not support Thai (th-TH)."
                    Log.w(TAG, msg)
                    _state.value = TtsManagerState.Error(msg)
                    try {
                        activeTts.shutdown()
                    } catch (ignored: Exception) {}
                    if (continuation.isActive) {
                        continuation.resume(Result.failure(LanguageNotSupportedException(msg)))
                    }
                    return@TextToSpeech
                }

                val selectedVoiceName = try {
                    activeTts.voice?.name
                } catch (e: Exception) {
                    null
                }

                tts = activeTts
                currentEnginePackage = targetEngine

                val readyState = TtsManagerState.Ready(
                    enginePackage = targetEngine,
                    languageTag = THAI_LOCALE.toLanguageTag(),
                    voiceName = selectedVoiceName,
                    isThaiSupported = true
                )
                _state.value = readyState

                if (continuation.isActive) {
                    continuation.resume(Result.success(readyState))
                }
            }, targetEngine)

            continuation.invokeOnCancellation {
                try {
                    engineRef?.stop()
                    engineRef?.shutdown()
                } catch (ignored: Exception) {}
            }
        }
    }

    /**
     * Configures the given TextToSpeech instance for the Thai language (th-TH)
     * and selects the highest quality local or network Thai voice available.
     */
    open fun configureThaiLanguage(
        ttsInstance: TextToSpeech,
        speechRate: Float = 1.0f,
        pitch: Float = 1.0f
    ): Boolean {
        var result = ttsInstance.setLanguage(THAI_LOCALE)
        if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
            result = ttsInstance.setLanguage(THAI_FALLBACK_LOCALE)
        }

        if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
            return false
        }

        // Attempt to select the highest quality Thai voice if voices API is populated
        try {
            val voices: Set<Voice>? = ttsInstance.voices
            val bestThaiVoice = voices
                ?.filter {
                    it.locale.language.equals("th", ignoreCase = true) ||
                        it.locale.language.equals("tha", ignoreCase = true)
                }
                ?.maxByOrNull { it.quality }
            if (bestThaiVoice != null) {
                ttsInstance.voice = bestThaiVoice
            }
        } catch (e: Exception) {
            Log.d(TAG, "Voice selection skipped: ${e.message}")
        }

        ttsInstance.setSpeechRate(speechRate.coerceIn(0.5f, 2.0f))
        ttsInstance.setPitch(pitch.coerceIn(0.5f, 2.0f))
        return true
    }

    /**
     * Checks whether Thai language is supported on the currently initialized TTS instance.
     */
    open fun isThaiLanguageSupported(): Boolean {
        val active = tts ?: return false
        val availability = active.isLanguageAvailable(THAI_LOCALE)
        return availability >= TextToSpeech.LANG_AVAILABLE
    }

    /**
     * Synthesizes Thai speech into a temporary WAV file and decodes it into 16-bit PCM audio.
     */
    open suspend fun synthesizeToPcm(
        text: String,
        politeStyle: PoliteEndingStyle = PoliteEndingStyle.OFF,
        preferredEnginePackage: String? = null,
        speechRate: Float = 1.0f,
        pitch: Float = 1.0f,
        timeoutMs: Long = 6000L
    ): DecodedPcmAudio = withContext(Dispatchers.IO) {
        withTimeout(timeoutMs) {
            val processedText = if (politeStyle == PoliteEndingStyle.OFF) {
                text
            } else {
                ThaiPoliteEndingProcessor.process(text, politeStyle)
            }

            val initResult = initialize(preferredEnginePackage, speechRate, pitch)
            val readyState = initResult.getOrElse { throw it }
            val activeTts = tts ?: throw FallbackSynthesisException("TTS engine not available after init.")

            activeTts.setSpeechRate(speechRate.coerceIn(0.5f, 2.0f))
            activeTts.setPitch(pitch.coerceIn(0.5f, 2.0f))

            suspendCancellableCoroutine { continuation ->
                var tempFile: File? = null
                val cleanup = {
                    try {
                        tempFile?.delete()
                    } catch (ignored: Exception) {}
                }

                continuation.invokeOnCancellation {
                    cleanup()
                    try {
                        activeTts.stop()
                    } catch (ignored: Exception) {}
                }

                try {
                    tempFile = File.createTempFile("tts_mgr_", ".wav", context.cacheDir)
                    val utteranceId = "tts_mgr_${System.currentTimeMillis()}"

                    activeTts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                        override fun onStart(id: String?) {
                            if (id == utteranceId) {
                                _isSpeaking.value = true
                            }
                        }

                        override fun onDone(id: String?) {
                            if (id == utteranceId) {
                                _isSpeaking.value = false
                                try {
                                    val bytes = tempFile?.readBytes() ?: ByteArray(0)
                                    if (bytes.isEmpty()) {
                                        cleanup()
                                        if (continuation.isActive) {
                                            continuation.resumeWithException(
                                                FallbackSynthesisException("Engine ${readyState.enginePackage} synthesized 0 bytes.")
                                            )
                                        }
                                        return
                                    }
                                    val decoded = AudioDecoder.decodeToPcm(bytes, context.cacheDir, 24000)
                                    cleanup()
                                    if (continuation.isActive) {
                                        continuation.resume(decoded)
                                    }
                                } catch (e: Exception) {
                                    cleanup()
                                    if (continuation.isActive) {
                                        continuation.resumeWithException(e)
                                    }
                                }
                            }
                        }

                        @Deprecated("Deprecated in Java")
                        override fun onError(id: String?) {
                            if (id == utteranceId) {
                                _isSpeaking.value = false
                                cleanup()
                                if (continuation.isActive) {
                                    continuation.resumeWithException(
                                        FallbackSynthesisException("Engine ${readyState.enginePackage} synthesis failed.")
                                    )
                                }
                            }
                        }

                        override fun onError(id: String?, errorCode: Int) {
                            if (id == utteranceId) {
                                _isSpeaking.value = false
                                cleanup()
                                if (continuation.isActive) {
                                    continuation.resumeWithException(
                                        FallbackSynthesisException("Engine ${readyState.enginePackage} error code: $errorCode")
                                    )
                                }
                            }
                        }
                    })

                    val status = activeTts.synthesizeToFile(processedText, Bundle(), tempFile!!, utteranceId)
                    if (status != TextToSpeech.SUCCESS) {
                        _isSpeaking.value = false
                        cleanup()
                        if (continuation.isActive) {
                            continuation.resumeWithException(
                                FallbackSynthesisException("synthesizeToFile failed with status $status")
                            )
                        }
                    }
                } catch (e: Exception) {
                    _isSpeaking.value = false
                    cleanup()
                    if (continuation.isActive) {
                        continuation.resumeWithException(e)
                    }
                }
            }
        }
    }

    /**
     * Stops any ongoing speech synthesis or playback immediately.
     */
    open fun stop() {
        try {
            tts?.stop()
        } catch (e: Exception) {
            Log.w(TAG, "Error stopping TTS: ${e.message}")
        } finally {
            _isSpeaking.value = false
        }
    }

    /**
     * Releases native TextToSpeech resources.
     */
    open fun shutdown() {
        try {
            tts?.stop()
            tts?.shutdown()
        } catch (e: Exception) {
            Log.w(TAG, "Error shutting down TTS: ${e.message}")
        } finally {
            tts = null
            currentEnginePackage = null
            _isSpeaking.value = false
            _state.value = TtsManagerState.Uninitialized
        }
    }
}
