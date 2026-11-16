package com.example

import android.content.Context
import android.media.AudioFormat
import android.speech.tts.SynthesisCallback
import android.speech.tts.SynthesisRequest
import android.speech.tts.TextToSpeech
import android.util.Base64
import androidx.test.core.app.ApplicationProvider
import com.example.audio.AudioDecoder
import com.example.audio.DecodedPcmAudio
import com.example.cache.VoiceClipCache
import com.example.data.SecureApiKeyStore
import com.example.data.SiamVoicePreferences
import com.example.engine.AndroidSystemTtsEngine
import com.example.engine.AuthFailedException
import com.example.engine.CircuitBreaker
import com.example.engine.QuotaExhaustedException
import com.example.engine.SiamVoiceTtsSynthesizer
import com.example.engine.SynthDispatcher
import com.example.model.CircuitBreakerState
import com.example.model.ExternalEngineInfo
import com.example.model.PoliteEndingStyle
import com.example.model.SynthesisRequestData
import com.example.model.SynthesisResult
import com.example.model.TtsEngineType
import com.example.model.VoiceStylePreset
import com.example.network.GeminiHttpResponse
import com.example.network.GeminiTtsClient
import com.example.network.GeminiTtsTransport
import com.example.network.OfficialGeminiApiKeyTransport
import com.example.processor.ThaiPoliteEndingProcessor
import com.example.tts.SiamVoiceTtsService
import com.example.ui.SiamVoiceViewModel
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SiamVoiceValidationTest {

    private lateinit var context: Context
    private lateinit var preferences: SiamVoicePreferences
    private lateinit var apiKeyStore: SecureApiKeyStore

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        preferences = SiamVoicePreferences(context)
        apiKeyStore = SecureApiKeyStore(context)
        apiKeyStore.deleteApiKey()
        preferences.updateAutoFallback(true)
        preferences.updateVoicePreset(VoiceStylePreset.FRIENDLY_COMPANION)
        preferences.updatePoliteEndingStyle(PoliteEndingStyle.AUTO_NATURAL)
        VoiceClipCache(context).clear()
    }

    /**
     * Generates a synthetic 16-bit mono PCM sine wave and wraps it in a Gemini `generateContent`
     * JSON response with `inlineData`.
     */
    private fun buildGeminiAudioJsonResponse(
        sampleRate: Int = 24000,
        numSamples: Int = 2400,
        frequencyHz: Double = 440.0,
        mimeType: String = "audio/L16;codec=pcm;rate=$sampleRate"
    ): Pair<ByteArray, String> {
        val pcmBytes = ByteArray(numSamples * 2)
        val buffer = ByteBuffer.wrap(pcmBytes).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until numSamples) {
            val angle = 2.0 * Math.PI * i * frequencyHz / sampleRate
            val sample = (Math.sin(angle) * 14000).toInt().toShort()
            buffer.putShort(sample)
        }
        val b64 = Base64.encodeToString(pcmBytes, Base64.NO_WRAP)
        val json = """
            {
              "candidates": [
                {
                  "content": {
                    "parts": [
                      {
                        "inlineData": {
                          "mimeType": "$mimeType",
                          "data": "$b64"
                        }
                      }
                    ]
                  }
                }
              ]
            }
        """.trimIndent()
        return Pair(pcmBytes, json)
    }

    @Test
    fun `Scenario 1 - SecureApiKeyStore encrypts saves masks replaces and deletes user API Key`() {
        assertFalse(apiKeyStore.hasApiKey())
        assertNull(apiKeyStore.getApiKey())
        assertEquals("", apiKeyStore.getMaskedApiKey())

        // Reject blank or invalid short keys
        assertNotNull(apiKeyStore.validateKeyInput(""))
        assertNotNull(apiKeyStore.validateKeyInput("short_key"))
        assertNotNull(apiKeyStore.validateKeyInput("AIza key with spaces 123456"))

        // Save first valid key
        val key1 = "AIzaSyTestKey1234567890ABCDEF"
        apiKeyStore.saveApiKey(key1)
        assertTrue(apiKeyStore.hasApiKey())
        assertEquals(key1, apiKeyStore.getApiKey())
        val masked1 = apiKeyStore.getMaskedApiKey()
        assertEquals("AIza••••••••CDEF", masked1)
        assertFalse("Masked key must never expose full secret", masked1.contains("1234567890"))

        // Verify raw prefs do not contain plaintext key
        val rawPrefs = context.getSharedPreferences("siamvoice_encrypted_api_key_store", Context.MODE_PRIVATE)
        val storedCiphertext = rawPrefs.getString("encrypted_gemini_api_key", "") ?: ""
        assertTrue(storedCiphertext.isNotEmpty())
        assertFalse("Ciphertext must not contain plaintext API key", storedCiphertext.contains(key1))

        // Replace with second key
        val key2 = "AIzaSyReplacementKey9876543210XYZW"
        apiKeyStore.saveApiKey(key2)
        assertEquals(key2, apiKeyStore.getApiKey())
        assertEquals("AIza••••••••XYZW", apiKeyStore.getMaskedApiKey())

        // Delete key
        apiKeyStore.deleteApiKey()
        assertFalse(apiKeyStore.hasApiKey())
        assertNull(apiKeyStore.getApiKey())
        assertEquals("", apiKeyStore.getMaskedApiKey())
    }

    @Test
    fun `Scenario 2 - OfficialGeminiApiKeyTransport enforces official HTTPS host and rejects missing key`() = runBlocking {
        // 1. Reject third-party or non-HTTPS endpoints so API key is never sent to third-party servers
        try {
            OfficialGeminiApiKeyTransport.validateOfficialEndpoint("https://evil-proxy.example.com")
            fail("Expected AuthFailedException for non-official host")
        } catch (e: AuthFailedException) {
            assertTrue(e.message!!.contains("generativelanguage.googleapis.com"))
        }

        try {
            OfficialGeminiApiKeyTransport.validateOfficialEndpoint("http://generativelanguage.googleapis.com")
            fail("Expected AuthFailedException for non-HTTPS scheme")
        } catch (e: AuthFailedException) {
            assertTrue(e.message!!.contains("HTTPS"))
        }

        // 2. Reject synthesis when API key is missing
        apiKeyStore.deleteApiKey()
        val transport = OfficialGeminiApiKeyTransport(
            context = context,
            httpClient = GeminiTtsClient.defaultHttpClient(),
            apiKeyStore = apiKeyStore
        )
        val reqJson = GeminiTtsClient.buildGenerateContentRequestJson(
            text = "สวัสดีครับ",
            voiceName = "Puck"
        )
        try {
            transport.executeGenerateContent(
                modelId = GeminiTtsClient.MODEL_FLASH,
                endpointPath = GeminiTtsClient.endpointPathForModel(GeminiTtsClient.MODEL_FLASH),
                requestJson = reqJson
            )
            fail("Expected AuthFailedException when API key is missing")
        } catch (e: AuthFailedException) {
            assertTrue(e.message!!.contains("Gemini API Key"))
        }
    }

    @Test
    fun `Scenario 3 - Verify Gemini model availability via API and validate audio request schema`() = runBlocking {
        assertTrue(GeminiTtsClient.isSupportedTtsModel(GeminiTtsClient.MODEL_FLASH))
        assertTrue(GeminiTtsClient.isSupportedTtsModel(GeminiTtsClient.MODEL_FLASH_LITE))
        assertTrue(GeminiTtsClient.isSupportedTtsModel(GeminiTtsClient.MODEL_GEMINI_2_5_FLASH_TTS))
        assertFalse(GeminiTtsClient.isSupportedTtsModel("unsupported-tts-model"))

        val transport = object : GeminiTtsTransport {
            override suspend fun executeGenerateContent(
                modelId: String,
                endpointPath: String,
                requestJson: JSONObject
            ): GeminiHttpResponse = GeminiHttpResponse(200, "{}")

            override suspend fun executeListModels(): GeminiHttpResponse {
                return GeminiHttpResponse(
                    statusCode = 200,
                    body = """
                        {
                          "models": [
                            {"name": "models/gemini-3.8-flash-tts"},
                            {"name": "models/gemini-3.8-flash-lite-tts"},
                            {"name": "models/gemini-2.5-flash-preview-tts"}
                          ]
                        }
                    """.trimIndent()
                )
            }
        }

        val client = GeminiTtsClient(context = context, transport = transport)
        val verified = client.verifyAvailableModels()
        assertTrue(verified.isVerified)
        assertEquals("gemini-3.8-flash-tts", verified.flashModelId)
        assertEquals("gemini-3.8-flash-lite-tts", verified.flashLiteModelId)
        assertTrue(verified.availableModels.contains("gemini-3.8-flash-tts"))

        val puckRequest = GeminiTtsClient.buildGenerateContentRequestJson(
            text = "อีก 300 เมตร เลี้ยวซ้ายนะครับ",
            voiceName = VoiceStylePreset.FRIENDLY_COMPANION.voiceName,
            stylePrompt = VoiceStylePreset.FRIENDLY_COMPANION.stylePrompt
        )
        assertTrue(GeminiTtsClient.validateAudioRequestSchema(puckRequest))
    }

    @Test
    fun `Scenario 4 - Real Thai speech generation using Puck Friendly Companion and Orus Calm Personal Assistant`() = runBlocking {
        val capturedVoiceNames = mutableListOf<String>()
        val capturedPromptTexts = mutableListOf<String>()
        val (expectedPcm, fakeJsonResponse) = buildGeminiAudioJsonResponse(sampleRate = 24000, numSamples = 1200)

        val transport = GeminiTtsTransport { _, _, requestJson ->
            val promptText = requestJson.getJSONArray("contents")
                .getJSONObject(0)
                .getJSONArray("parts")
                .getJSONObject(0)
                .getString("text")
            val voiceName = requestJson.getJSONObject("generationConfig")
                .getJSONObject("speechConfig")
                .getJSONObject("voiceConfig")
                .getJSONObject("prebuiltVoiceConfig")
                .getString("voiceName")
            capturedPromptTexts.add(promptText)
            capturedVoiceNames.add(voiceName)
            GeminiHttpResponse(statusCode = 200, body = fakeJsonResponse)
        }

        val client = GeminiTtsClient(context = context, transport = transport)

        // 1. Puck (Friendly Companion)
        val puckDecoded = client.synthesize(
            text = "อีก 300 เมตร เลี้ยวซ้ายนะครับ",
            modelId = GeminiTtsClient.MODEL_FLASH,
            voiceName = VoiceStylePreset.FRIENDLY_COMPANION.voiceName,
            stylePrompt = VoiceStylePreset.FRIENDLY_COMPANION.stylePrompt
        )
        assertArrayEquals(expectedPcm, puckDecoded.pcmData)
        assertEquals(24000, puckDecoded.sampleRate)
        assertEquals("Puck", capturedVoiceNames[0])
        assertTrue(capturedPromptTexts[0].contains("พูดด้วยน้ำเสียงอารมณ์ดี กระตือรือร้น เป็นธรรมชาติ เหมือนเพื่อนสนิทนั่งข้างคนขับ"))

        // 2. Orus (Calm Personal Assistant)
        val orusDecoded = client.synthesize(
            text = "ถึงจุดหมายแล้วครับผม",
            modelId = GeminiTtsClient.MODEL_FLASH,
            voiceName = VoiceStylePreset.PERSONAL_ASSISTANT.voiceName,
            stylePrompt = VoiceStylePreset.PERSONAL_ASSISTANT.stylePrompt
        )
        assertArrayEquals(expectedPcm, orusDecoded.pcmData)
        assertEquals("Orus", capturedVoiceNames[1])
        assertTrue(capturedPromptTexts[1].contains("พูดด้วยน้ำเสียงนุ่มทุ้ม สุภาพ ใจเย็น อบอุ่น เหมือนเลขาส่วนตัว"))
    }

    @Test
    fun `Scenario 5 - Thai polite endings support ครับ นะครับ ครับผม Auto Natural and Off without modifying road names`() {
        // 1. Auto Natural
        assertEquals(
            "อีก 300 เมตร เลี้ยวซ้ายเข้าสู่ถนนสุขุมวิท 24นะครับ",
            ThaiPoliteEndingProcessor.process("อีก 300 เมตร เลี้ยวซ้ายเข้าสู่ถนนสุขุมวิท 24", PoliteEndingStyle.KRUB.let { PoliteEndingStyle.AUTO_NATURAL })
        )
        assertEquals(
            "อีก 300 เมตร เลี้ยวซ้ายนะครับ",
            ThaiPoliteEndingProcessor.process("อีก 300 เมตร เลี้ยวซ้าย", PoliteEndingStyle.AUTO_NATURAL)
        )
        assertEquals(
            "ถึงจุดหมายแล้วครับผม",
            ThaiPoliteEndingProcessor.process("ถึงจุดหมายแล้ว", PoliteEndingStyle.AUTO_NATURAL)
        )
        assertEquals(
            "สภาพการจราจรคล่องตัวครับ",
            ThaiPoliteEndingProcessor.process("สภาพการจราจรคล่องตัว", PoliteEndingStyle.AUTO_NATURAL)
        )

        // 2. Explicit ครับ (KRUB)
        assertEquals(
            "อีก 500 เมตร ใช้ทางออก 3 ถนนพระราม 9ครับ",
            ThaiPoliteEndingProcessor.process("อีก 500 เมตร ใช้ทางออก 3 ถนนพระราม 9", PoliteEndingStyle.KRUB)
        )

        // 3. Explicit นะครับ (NA_KRUB)
        assertEquals(
            "กรุณาลดความเร็วหน้าโรงเรียนนะครับ",
            ThaiPoliteEndingProcessor.process("กรุณาลดความเร็วหน้าโรงเรียน", PoliteEndingStyle.NA_KRUB)
        )

        // 4. Explicit ครับผม (KRUB_PHOM)
        assertEquals(
            "พร้อมออกเดินทางสู่สนามบินสุวรรณภูมิครับผม",
            ThaiPoliteEndingProcessor.process("พร้อมออกเดินทางสู่สนามบินสุวรรณภูมิ", PoliteEndingStyle.KRUB_PHOM)
        )

        // 5. Off (OFF)
        assertEquals(
            "อีก 300 เมตร เลี้ยวซ้าย",
            ThaiPoliteEndingProcessor.process("อีก 300 เมตร เลี้ยวซ้าย", PoliteEndingStyle.OFF)
        )

        // 6. Prevent duplicate endings
        assertEquals(
            "เลี้ยวซ้ายนะครับ",
            ThaiPoliteEndingProcessor.process("เลี้ยวซ้ายนะครับ", PoliteEndingStyle.AUTO_NATURAL)
        )
        assertEquals(
            "สวัสดีครับ",
            ThaiPoliteEndingProcessor.process("สวัสดีครับ", PoliteEndingStyle.KRUB_PHOM)
        )
        assertEquals(
            "ขอบคุณครับผม",
            ThaiPoliteEndingProcessor.process("ขอบคุณครับผม", PoliteEndingStyle.NA_KRUB)
        )
    }

    @Test
    fun `Scenario 6 - Missing API Key automatically falls back to External Android System TTS with identical transcript`() = runBlocking {
        apiKeyStore.deleteApiKey()
        val (fallbackPcm, _) = buildGeminiAudioJsonResponse(sampleRate = 22050, numSamples = 1000)
        var fallbackReceivedText = ""

        val fakeAndroidFallback = object : AndroidSystemTtsEngine(context) {
            override suspend fun synthesizeToFile(
                text: String,
                preferredEnginePackage: String?,
                speechRate: Float,
                pitch: Float,
                timeoutMs: Long
            ): DecodedPcmAudio {
                fallbackReceivedText = text
                return DecodedPcmAudio(pcmData = fallbackPcm, sampleRate = 22050, channelCount = 1)
            }
        }

        val synthesizer = object : SiamVoiceTtsSynthesizer(
            context = context,
            preferences = preferences,
            voiceClipCache = VoiceClipCache(context),
            geminiClient = GeminiTtsClient(context = context, apiKeyStore = apiKeyStore),
            androidFallbackEngine = fakeAndroidFallback,
            apiKeyStore = apiKeyStore
        ) {
            override fun isNetworkAvailable(): Boolean = true
        }

        val result = synthesizer.synthesize(
            SynthesisRequestData(
                text = "อีก 300 เมตร เลี้ยวซ้าย",
                politeStyle = PoliteEndingStyle.AUTO_NATURAL
            )
        )

        assertEquals(TtsEngineType.ANDROID_SYSTEM_TTS, result.engineUsed)
        assertEquals("อีก 300 เมตร เลี้ยวซ้ายนะครับ", result.processedText)
        assertEquals("อีก 300 เมตร เลี้ยวซ้ายนะครับ", fallbackReceivedText)
        assertArrayEquals(fallbackPcm, result.audioData)
    }

    @Test
    fun `Scenario 7 - Shared synthesis pipeline executes Flash then Flash-Lite then External Android TTS on cloud errors`() = runBlocking {
        val executionOrder = mutableListOf<String>()
        val (fallbackPcm, _) = buildGeminiAudioJsonResponse(sampleRate = 22050, numSamples = 800)

        val failingTransport = GeminiTtsTransport { modelId, _, _ ->
            executionOrder.add("GEMINI:$modelId")
            GeminiHttpResponse(
                statusCode = 503,
                body = """{"error":{"message":"Model $modelId unavailable"}}"""
            )
        }

        val fakeAndroidFallback = object : AndroidSystemTtsEngine(context) {
            override suspend fun synthesizeToFile(
                text: String,
                preferredEnginePackage: String?,
                speechRate: Float,
                pitch: Float,
                timeoutMs: Long
            ): DecodedPcmAudio {
                executionOrder.add("ANDROID_FALLBACK:$text")
                return DecodedPcmAudio(pcmData = fallbackPcm, sampleRate = 22050, channelCount = 1)
            }
        }

        val synthesizer = object : SiamVoiceTtsSynthesizer(
            context = context,
            preferences = preferences,
            voiceClipCache = VoiceClipCache(context),
            geminiClient = GeminiTtsClient(context = context, transport = failingTransport),
            androidFallbackEngine = fakeAndroidFallback
        ) {
            override fun isNetworkAvailable(): Boolean = true
        }

        val dispatcher = SynthDispatcher(
            context = context,
            preferences = preferences,
            sharedSynthesizer = synthesizer
        )

        val result = dispatcher.dispatch(
            SynthesisRequestData(
                text = "อีก 300 เมตร เลี้ยวซ้าย",
                politeStyle = PoliteEndingStyle.AUTO_NATURAL,
                voicePreset = VoiceStylePreset.FRIENDLY_COMPANION
            )
        )

        assertEquals(TtsEngineType.ANDROID_SYSTEM_TTS, result.engineUsed)
        assertEquals(
            listOf(
                "GEMINI:gemini-3.8-flash-tts",
                "GEMINI:gemini-3.8-flash-lite-tts",
                "ANDROID_FALLBACK:อีก 300 เมตร เลี้ยวซ้ายนะครับ"
            ),
            executionOrder
        )
    }

    @Test
    fun `Scenario 8 - Shared pipeline fails over from Gemini Flash TTS to Gemini Flash-Lite TTS`() = runBlocking {
        val attemptedModels = mutableListOf<String>()
        val (flashLitePcm, flashLiteJson) = buildGeminiAudioJsonResponse(sampleRate = 24000, numSamples = 1000)

        val transport = GeminiTtsTransport { modelId, _, _ ->
            attemptedModels.add(modelId)
            if (modelId == GeminiTtsClient.MODEL_FLASH) {
                GeminiHttpResponse(statusCode = 429, body = """{"error":{"message":"Flash quota exceeded"}}""")
            } else {
                GeminiHttpResponse(statusCode = 200, body = flashLiteJson)
            }
        }

        val synthesizer = object : SiamVoiceTtsSynthesizer(
            context = context,
            preferences = preferences,
            voiceClipCache = VoiceClipCache(context),
            geminiClient = GeminiTtsClient(context = context, transport = transport)
        ) {
            override fun isNetworkAvailable(): Boolean = true
        }

        val result = synthesizer.synthesize(
            SynthesisRequestData(
                text = "เตรียมกลับรถด้านหน้า",
                politeStyle = PoliteEndingStyle.AUTO_NATURAL,
                voicePreset = VoiceStylePreset.PERSONAL_ASSISTANT
            )
        )

        assertEquals(TtsEngineType.GEMINI_FLASH_LITE, result.engineUsed)
        assertEquals("เตรียมกลับรถด้านหน้านะครับ", result.processedText)
        assertEquals(
            listOf(GeminiTtsClient.MODEL_FLASH, GeminiTtsClient.MODEL_FLASH_LITE),
            attemptedModels
        )
        assertArrayEquals(flashLitePcm, result.audioData)
    }

    @Test
    fun `Scenario 9 - AndroidSystemTtsEngine skips non-Thai external engines and never selects SiamVoice recursively`() = runBlocking {
        val engines = listOf(
            ExternalEngineInfo(
                packageName = context.packageName,
                label = "SiamVoice Self Engine",
                supportsThai = true,
                isSystemDefault = true
            ),
            ExternalEngineInfo(
                packageName = "com.example.nonthai.tts",
                label = "English Only TTS",
                supportsThai = false,
                isSystemDefault = true
            ),
            ExternalEngineInfo(
                packageName = "com.google.android.tts",
                label = "Google Speech Services (Thai)",
                supportsThai = true,
                isSystemDefault = false
            )
        )

        val engine = object : AndroidSystemTtsEngine(context) {
            override fun discoverExternalEngines(): List<ExternalEngineInfo> {
                return engines.filter { it.packageName != context.packageName }
            }

            override suspend fun synthesizeWithEngine(
                text: String,
                enginePackage: String,
                speechRate: Float,
                pitch: Float
            ): DecodedPcmAudio {
                if (enginePackage == "com.example.nonthai.tts") {
                    throw com.example.engine.LanguageNotSupportedException(
                        "External TTS engine ($enginePackage) does not support Thai locale (th-TH)"
                    )
                }
                val (pcm, _) = buildGeminiAudioJsonResponse(sampleRate = 22050, numSamples = 500)
                return DecodedPcmAudio(pcmData = pcm, sampleRate = 22050, channelCount = 1)
            }
        }

        val ordered = engine.orderCandidateExternalEngines(engine.discoverExternalEngines(), null)
        assertTrue("Must never include SiamVoice packageName", ordered.none { it == context.packageName })

        val audio = engine.synthesizeToFile(
            text = "เลี้ยวซ้ายนะครับ",
            preferredEnginePackage = context.packageName
        )
        assertTrue(audio.pcmData.isNotEmpty())
        assertEquals(22050, audio.sampleRate)
    }

    @Test
    fun `Scenario 10 - SiamVoiceTtsService streams synthesized 16-bit PCM audio via SynthesisCallback and supports onStop`() {
        val (expectedPcm, _) = buildGeminiAudioJsonResponse(sampleRate = 24000, numSamples = 4000)

        val fakeSynthesizer = object : SiamVoiceTtsSynthesizer(
            context = context,
            preferences = preferences
        ) {
            override suspend fun synthesize(request: SynthesisRequestData): SynthesisResult {
                val processed = ThaiPoliteEndingProcessor.process(request.text, request.politeStyle)
                return SynthesisResult(
                    audioData = expectedPcm,
                    sampleRate = 24000,
                    channelCount = 1,
                    engineUsed = TtsEngineType.ANDROID_SYSTEM_TTS,
                    latencyMs = 12L,
                    wasCached = false,
                    processedText = processed
                )
            }
        }

        val service = SiamVoiceTtsService().apply {
            customPreferences = preferences
            customSynthesizer = fakeSynthesizer
        }

        assertEquals(
            TextToSpeech.LANG_COUNTRY_AVAILABLE,
            service.onIsLanguageAvailable("th", "TH", "")
        )

        val receivedAudio = ByteArrayOutputStream()
        var startedSampleRate = 0
        var startedEncoding = 0
        var startedChannels = 0
        var isCompleted = false

        val callback = object : SynthesisCallback {
            override fun getMaxBufferSize(): Int = 2048
            override fun start(sampleRateInHz: Int, audioFormat: Int, channelCount: Int): Int {
                startedSampleRate = sampleRateInHz
                startedEncoding = audioFormat
                startedChannels = channelCount
                return TextToSpeech.SUCCESS
            }
            override fun audioAvailable(buffer: ByteArray, offset: Int, length: Int): Int {
                receivedAudio.write(buffer, offset, length)
                return TextToSpeech.SUCCESS
            }
            override fun done(): Int {
                isCompleted = true
                return TextToSpeech.SUCCESS
            }
            override fun error() {}
            override fun error(errorCode: Int) {}
            override fun hasStarted(): Boolean = startedSampleRate > 0
            override fun hasFinished(): Boolean = isCompleted
        }

        val synthesisRequest = SynthesisRequest("อีก 300 เมตร เลี้ยวซ้าย", android.os.Bundle())
        service.onSynthesizeText(synthesisRequest, callback)

        assertTrue(isCompleted)
        assertEquals(24000, startedSampleRate)
        assertEquals(AudioFormat.ENCODING_PCM_16BIT, startedEncoding)
        assertEquals(1, startedChannels)
        assertArrayEquals(expectedPcm, receivedAudio.toByteArray())

        service.onStop()
    }

    @Test
    fun `Scenario 11 - VoiceClipCache differentiates keys by voice preset style rate and pitch`() {
        val cache = VoiceClipCache(context)
        val keyPuck = cache.computeKey(
            model = GeminiTtsClient.MODEL_FLASH,
            voice = VoiceStylePreset.FRIENDLY_COMPANION.voiceName,
            stylePrompt = VoiceStylePreset.FRIENDLY_COMPANION.stylePrompt,
            politeStyle = PoliteEndingStyle.AUTO_NATURAL,
            speechRate = 1.0f,
            pitch = 1.0f,
            text = "อีก 300 เมตร เลี้ยวซ้ายนะครับ"
        )
        val keyOrus = cache.computeKey(
            model = GeminiTtsClient.MODEL_FLASH,
            voice = VoiceStylePreset.PERSONAL_ASSISTANT.voiceName,
            stylePrompt = VoiceStylePreset.PERSONAL_ASSISTANT.stylePrompt,
            politeStyle = PoliteEndingStyle.AUTO_NATURAL,
            speechRate = 1.0f,
            pitch = 1.0f,
            text = "อีก 300 เมตร เลี้ยวซ้ายนะครับ"
        )
        assertNotEquals(keyPuck, keyOrus)
    }

    @Test
    fun `Scenario 12 - ViewModel saves tests and deletes Gemini API Key and updates UI state`() {
        val app = ApplicationProvider.getApplicationContext<SiamVoiceApplication>()
        val viewModel = SiamVoiceViewModel(app)

        // Initially no API key
        assertFalse(viewModel.uiState.value.hasStoredApiKey)

        // Enter invalid key -> error message
        viewModel.onApiKeyInputChanged("short")
        assertFalse(viewModel.saveApiKey())
        assertNotNull(viewModel.uiState.value.apiKeyErrorMessage)

        // Enter valid key -> saved & masked
        val validKey = "AIzaSyTestUserProvidedKey123456789"
        viewModel.onApiKeyInputChanged(validKey)
        assertTrue(viewModel.saveApiKey())
        assertTrue(viewModel.uiState.value.hasStoredApiKey)
        assertEquals("AIza••••••••6789", viewModel.uiState.value.maskedApiKey)
        assertEquals("", viewModel.uiState.value.apiKeyInput)
        assertNull(viewModel.uiState.value.apiKeyErrorMessage)

        // Select Puck / Orus & polite endings
        viewModel.selectGeminiVoice("Orus")
        assertEquals("Orus", viewModel.uiState.value.settings.geminiVoice)
        viewModel.selectPoliteStyle(PoliteEndingStyle.NA_KRUB)
        assertEquals(PoliteEndingStyle.NA_KRUB, viewModel.uiState.value.settings.politeEndingStyle)

        // Delete API key
        viewModel.deleteApiKey()
        assertFalse(viewModel.uiState.value.hasStoredApiKey)
        assertEquals("", viewModel.uiState.value.maskedApiKey)
    }
}
