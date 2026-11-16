package com.example.tts

import android.media.AudioFormat
import android.speech.tts.SynthesisCallback
import android.speech.tts.SynthesisRequest
import android.speech.tts.TextToSpeech
import android.speech.tts.TextToSpeechService
import android.util.Log
import com.example.SiamVoiceApplication
import com.example.data.SiamVoicePreferences
import com.example.engine.SiamVoiceTtsSynthesizer
import com.example.model.SynthesisRequestData
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

open class SiamVoiceTtsService : TextToSpeechService() {
    private val serviceScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var currentSynthesisJob: Job? = null

    // Optional overrides for deterministic Robolectric testing of TextToSpeechService
    var customSynthesizer: SiamVoiceTtsSynthesizer? = null
    var customPreferences: SiamVoicePreferences? = null

    companion object {
        private const val TAG = "SiamVoiceTtsService"
    }

    public override fun onIsLanguageAvailable(lang: String?, country: String?, variant: String?): Int {
        if (lang.equals("th", ignoreCase = true) || lang.equals("tha", ignoreCase = true)) {
            return if (country.isNullOrEmpty() || country.equals("TH", ignoreCase = true) || country.equals("THA", ignoreCase = true)) {
                TextToSpeech.LANG_COUNTRY_AVAILABLE
            } else {
                TextToSpeech.LANG_AVAILABLE
            }
        }
        return TextToSpeech.LANG_NOT_SUPPORTED
    }

    public override fun onGetLanguage(): Array<String> {
        return arrayOf("tha", "THA", "")
    }

    public override fun onLoadLanguage(lang: String?, country: String?, variant: String?): Int {
        return onIsLanguageAvailable(lang, country, variant)
    }

    public override fun onStop() {
        currentSynthesisJob?.cancel()
        currentSynthesisJob = null
    }

    public override fun onSynthesizeText(request: SynthesisRequest, callback: SynthesisCallback) {
        val text = request.charSequenceText?.toString().orEmpty()
        if (text.isBlank()) {
            callback.start(24000, AudioFormat.ENCODING_PCM_16BIT, 1)
            callback.done()
            return
        }

        val app = application as? SiamVoiceApplication
        val preferences = customPreferences ?: app?.preferences
        val synthesizer = customSynthesizer ?: app?.synthesizer

        if (synthesizer == null || preferences == null) {
            callback.error(TextToSpeech.ERROR_SERVICE)
            return
        }

        val settings = preferences.getSettings()
        val speechRate = ((request.speechRate / 100.0f) * settings.fallbackSpeechRate).coerceIn(0.5f, 2.5f)
        val pitch = ((request.pitch / 100.0f) * settings.speechPitch).coerceIn(0.5f, 2.0f)

        val synthesisRequestData = SynthesisRequestData(
            text = text,
            politeStyle = settings.politeEndingStyle,
            voicePreset = settings.voicePreset,
            geminiVoice = settings.geminiVoice,
            stylePrompt = settings.voicePreset.stylePrompt,
            speechRate = speechRate,
            pitch = pitch
        )

        val job = serviceScope.launch {
            try {
                // Uses the exact same shared SiamVoiceTtsSynthesizer pipeline as the Home screen:
                // 1. Gemini Flash TTS -> 2. Gemini Flash-Lite TTS -> 3. External Android TTS
                val result = synthesizer.synthesize(synthesisRequestData)
                val pcmData = result.audioData

                if (pcmData.isEmpty()) {
                    callback.error(TextToSpeech.ERROR_SYNTHESIS)
                    return@launch
                }

                val startStatus = callback.start(
                    result.sampleRate,
                    AudioFormat.ENCODING_PCM_16BIT,
                    result.channelCount
                )

                if (startStatus != TextToSpeech.SUCCESS) {
                    callback.error(TextToSpeech.ERROR_OUTPUT)
                    return@launch
                }

                val maxChunkSize = callback.maxBufferSize.coerceAtLeast(4096)
                var offset = 0
                while (offset < pcmData.size) {
                    val remaining = pcmData.size - offset
                    val len = remaining.coerceAtMost(maxChunkSize)
                    val status = callback.audioAvailable(pcmData, offset, len)
                    if (status != TextToSpeech.SUCCESS) {
                        return@launch
                    }
                    offset += len
                }

                callback.done()
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                Log.e(TAG, "onSynthesizeText failed: ${t.message}", t)
                callback.error(TextToSpeech.ERROR_SYNTHESIS)
            }
        }

        currentSynthesisJob = job
        runBlocking {
            job.join()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
    }
}
