package com.example.engine

import android.content.Context
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import com.example.audio.AudioDecoder
import com.example.audio.DecodedPcmAudio
import com.example.model.ExternalEngineInfo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.File
import java.util.Locale
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class NoCompatibleEngineException(message: String) : Exception(message)
class LanguageNotSupportedException(message: String) : Exception(message)
class FallbackSynthesisException(message: String) : Exception(message)

open class AndroidSystemTtsEngine(private val context: Context) {

    companion object {
        private const val TAG = "AndroidSystemTtsEngine"
        const val GOOGLE_TTS_PACKAGE = "com.google.android.tts"
        val THAI_LOCALE: Locale = Locale.forLanguageTag("th-TH")
        val THAI_FALLBACK_LOCALE: Locale = Locale.forLanguageTag("th")
    }

    /**
     * Discovers all external TTS engines installed on the device,
     * explicitly excluding SiamVoice itself to prevent recursive engine loops.
     */
    open fun discoverExternalEngines(): List<ExternalEngineInfo> {
        val engines = mutableListOf<ExternalEngineInfo>()
        var probeTts: TextToSpeech? = null
        try {
            probeTts = TextToSpeech(context, null)
            val defaultEngine = probeTts.defaultEngine.orEmpty()
            val installed = probeTts.engines ?: emptyList()

            for (info in installed) {
                // NEVER include SiamVoice itself
                if (info.name.equals(context.packageName, ignoreCase = true)) {
                    continue
                }

                val isDefault = info.name.equals(defaultEngine, ignoreCase = true)
                engines.add(
                    ExternalEngineInfo(
                        packageName = info.name,
                        label = info.label.ifEmpty { info.name },
                        supportsThai = true,
                        isSystemDefault = isDefault
                    )
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to discover TTS engines: ${e.message}", e)
        } finally {
            try {
                probeTts?.shutdown()
            } catch (ignored: Exception) {}
        }
        return engines
    }

    /**
     * Orders candidate external engine packages in priority order:
     * 1. User's preferred engine (if external and installed)
     * 2. Google Speech Services (`com.google.android.tts`, if installed)
     * 3. System default external engine
     * 4. Any remaining installed external engines
     * Strictly excludes `context.packageName` to prevent recursive loops.
     */
    open fun orderCandidateExternalEngines(
        availableEngines: List<ExternalEngineInfo>,
        preferredEnginePackage: String? = null
    ): List<String> {
        val safeAvailable = availableEngines.filterNot {
            it.packageName.equals(context.packageName, ignoreCase = true)
        }
        if (safeAvailable.isEmpty()) return emptyList()

        val ordered = LinkedHashSet<String>()

        if (!preferredEnginePackage.isNullOrBlank() &&
            !preferredEnginePackage.equals(context.packageName, ignoreCase = true) &&
            safeAvailable.any { it.packageName == preferredEnginePackage }
        ) {
            ordered.add(preferredEnginePackage)
        }

        if (safeAvailable.any { it.packageName == GOOGLE_TTS_PACKAGE }) {
            ordered.add(GOOGLE_TTS_PACKAGE)
        }

        safeAvailable.firstOrNull { it.isSystemDefault }?.let {
            ordered.add(it.packageName)
        }

        for (engine in safeAvailable) {
            ordered.add(engine.packageName)
        }

        return ordered.toList()
    }

    /**
     * Synthesizes audio to file by explicitly selecting an external Android TTS engine
     * that supports Thai (`th-TH`), decoding the generated WAV audio into 16-bit PCM samples.
     */
    open suspend fun synthesizeToFile(
        text: String,
        preferredEnginePackage: String? = null,
        speechRate: Float = 1.0f,
        pitch: Float = 1.0f,
        timeoutMs: Long = 6000L
    ): DecodedPcmAudio = withContext(Dispatchers.IO) {
        withTimeout(timeoutMs) {
            val availableEngines = discoverExternalEngines()
            val candidatePackages = orderCandidateExternalEngines(availableEngines, preferredEnginePackage)
            if (candidatePackages.isEmpty()) {
                throw NoCompatibleEngineException("No external Android TTS engine installed on this device.")
            }

            var lastError: Throwable? = null

            for (enginePkg in candidatePackages) {
                if (enginePkg.equals(context.packageName, ignoreCase = true)) {
                    throw IllegalStateException("Fatal: Attempted to recursively invoke SiamVoice fallback.")
                }

                try {
                    return@withTimeout synthesizeWithEngine(
                        text = text,
                        enginePackage = enginePkg,
                        speechRate = speechRate,
                        pitch = pitch
                    )
                } catch (e: CancellationException) {
                    throw e
                } catch (e: LanguageNotSupportedException) {
                    Log.w(TAG, "Candidate external engine $enginePkg does not support Thai; trying next engine...")
                    lastError = e
                } catch (e: Throwable) {
                    Log.w(TAG, "Candidate external engine $enginePkg failed (${e.message}); trying next engine...")
                    lastError = e
                }
            }

            when (val err = lastError) {
                is LanguageNotSupportedException -> throw err
                is NoCompatibleEngineException -> throw err
                else -> throw FallbackSynthesisException(
                    err?.message ?: "All external Android TTS engines failed to synthesize Thai speech."
                )
            }
        }
    }

    protected open suspend fun synthesizeWithEngine(
        text: String,
        enginePackage: String,
        speechRate: Float,
        pitch: Float
    ): DecodedPcmAudio = suspendCancellableCoroutine { continuation ->
        var ttsInstance: TextToSpeech? = null
        var tempFile: File? = null

        val cleanup = {
            try {
                tempFile?.delete()
                ttsInstance?.stop()
                ttsInstance?.shutdown()
            } catch (ignored: Exception) {}
        }

        continuation.invokeOnCancellation {
            cleanup()
        }

        ttsInstance = TextToSpeech(context, { status ->
            if (status != TextToSpeech.SUCCESS) {
                cleanup()
                if (continuation.isActive) {
                    continuation.resumeWithException(
                        FallbackSynthesisException("Failed to initialize engine $enginePackage (status=$status)")
                    )
                }
                return@TextToSpeech
            }

            try {
                val tts = ttsInstance ?: return@TextToSpeech

                // Verify Thai support (th-TH or th)
                var langResult = tts.setLanguage(THAI_LOCALE)
                if (langResult == TextToSpeech.LANG_MISSING_DATA || langResult == TextToSpeech.LANG_NOT_SUPPORTED) {
                    langResult = tts.setLanguage(THAI_FALLBACK_LOCALE)
                }

                if (langResult == TextToSpeech.LANG_MISSING_DATA || langResult == TextToSpeech.LANG_NOT_SUPPORTED) {
                    cleanup()
                    if (continuation.isActive) {
                        continuation.resumeWithException(
                            LanguageNotSupportedException(
                                "Selected engine ($enginePackage) does not support Thai (th-TH)."
                            )
                        )
                    }
                    return@TextToSpeech
                }

                tts.setSpeechRate(speechRate.coerceIn(0.5f, 2.0f))
                tts.setPitch(pitch.coerceIn(0.5f, 2.0f))

                tempFile = File.createTempFile("siam_fb_", ".wav", context.cacheDir)
                val utteranceId = "utterance_${System.currentTimeMillis()}"

                tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(id: String?) {}

                    override fun onDone(id: String?) {
                        if (id == utteranceId) {
                            try {
                                val fileBytes = tempFile?.readBytes() ?: ByteArray(0)
                                if (fileBytes.isEmpty()) {
                                    cleanup()
                                    if (continuation.isActive) {
                                        continuation.resumeWithException(
                                            FallbackSynthesisException("Fallback engine produced 0 bytes.")
                                        )
                                    }
                                    return
                                }

                                val decoded = AudioDecoder.decodeToPcm(fileBytes, context.cacheDir, 24000)
                                cleanup()
                                if (continuation.isActive) {
                                    continuation.resume(decoded)
                                }
                            } catch (e: Exception) {
                                cleanup()
                                if (continuation.isActive) {
                                    continuation.resumeWithException(
                                        FallbackSynthesisException("Error reading fallback audio: ${e.message}")
                                    )
                                }
                            }
                        }
                    }

                    @Deprecated("Deprecated in Java")
                    override fun onError(id: String?) {
                        if (id == utteranceId) {
                            cleanup()
                            if (continuation.isActive) {
                                continuation.resumeWithException(
                                    FallbackSynthesisException("Engine $enginePackage reported synthesis error.")
                                )
                            }
                        }
                    }

                    override fun onError(id: String?, errorCode: Int) {
                        if (id == utteranceId) {
                            cleanup()
                            if (continuation.isActive) {
                                continuation.resumeWithException(
                                    FallbackSynthesisException("Engine $enginePackage error code: $errorCode")
                                )
                            }
                        }
                    }
                })

                val params = Bundle()
                val ret = tts.synthesizeToFile(text, params, tempFile!!, utteranceId)
                if (ret != TextToSpeech.SUCCESS) {
                    cleanup()
                    if (continuation.isActive) {
                        continuation.resumeWithException(
                            FallbackSynthesisException("synthesizeToFile returned code $ret")
                        )
                    }
                }
            } catch (e: Exception) {
                cleanup()
                if (continuation.isActive) {
                    continuation.resumeWithException(e)
                }
            }
        }, enginePackage)
    }
}
