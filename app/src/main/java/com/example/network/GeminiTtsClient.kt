package com.example.network

import android.content.Context
import android.util.Base64
import android.util.Log
import com.example.audio.AudioDecoder
import com.example.audio.DecodedPcmAudio
import com.example.data.SecureApiKeyStore
import com.example.engine.AuthFailedException
import com.example.engine.CloudModelUnavailableException
import com.example.engine.InvalidAudioException
import com.example.engine.QuotaExhaustedException
import com.example.model.GeminiLiveVoice
import com.example.model.VerifiedModelsStatus
import com.example.model.VoiceStylePreset
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.URI
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * Transport interface for executing official Gemini API requests over HTTPS.
 */
fun interface GeminiTtsTransport {
    suspend fun executeGenerateContent(
        modelId: String,
        endpointPath: String,
        requestJson: JSONObject
    ): GeminiHttpResponse

    suspend fun executeListModels(): GeminiHttpResponse {
        return GeminiHttpResponse(
            statusCode = 200,
            body = """
                {
                  "models": [
                    {"name": "models/gemini-3.8-flash-tts", "supportedGenerationMethods": ["generateContent"]},
                    {"name": "models/gemini-3.8-flash-lite-tts", "supportedGenerationMethods": ["generateContent"]},
                    {"name": "models/gemini-2.5-flash-preview-tts", "supportedGenerationMethods": ["generateContent"]}
                  ]
                }
            """.trimIndent()
        )
    }
}

data class GeminiHttpResponse(
    val statusCode: Int,
    val body: String
)

/**
 * Official Google Gemini API HTTPS transport using a user-provided API Key (BYOK)
 * stored in Android Keystore-backed encrypted storage ([SecureApiKeyStore]).
 *
 * Security invariants:
 * 1. Never hardcodes API keys in source code, BuildConfig, resources, or APK assets.
 * 2. Never logs, displays, or exports the full API key.
 * 3. Sends requests strictly to `https://generativelanguage.googleapis.com` over HTTPS;
 *    rejects any third-party host or non-HTTPS URL.
 * 4. Passes the API key in the `x-goog-api-key` header (never in URL query parameters).
 */
class OfficialGeminiApiKeyTransport(
    context: Context,
    private val httpClient: OkHttpClient,
    private val apiKeyStore: SecureApiKeyStore = SecureApiKeyStore(context),
    private val baseUrl: String = GeminiTtsClient.OFFICIAL_GEMINI_BASE_URL
) : GeminiTtsTransport {

    override suspend fun executeGenerateContent(
        modelId: String,
        endpointPath: String,
        requestJson: JSONObject
    ): GeminiHttpResponse = withContext(Dispatchers.IO) {
        validateOfficialEndpoint(baseUrl)

        val apiKey = apiKeyStore.getApiKey()?.trim().orEmpty()
        if (apiKey.isEmpty()) {
            throw AuthFailedException(
                "ยังไม่ได้ตั้งค่า Gemini API Key กรุณาเพิ่ม API Key ในหน้าตั้งค่า หรือใช้ระบบเสียง Android TTS สำรอง"
            )
        }

        val url = "${baseUrl.trimEnd('/')}/$endpointPath"
        val body = requestJson.toString().toRequestBody("application/json; charset=utf-8".toMediaType())
        val request = Request.Builder()
            .url(url)
            .post(body)
            .header("Accept", "application/json")
            .header("x-goog-api-key", apiKey)
            .build()

        val response = try {
            httpClient.newCall(request).execute()
        } catch (e: java.net.SocketTimeoutException) {
            throw TimeoutException("การเชื่อมต่อ Gemini API หมดเวลา ($modelId)")
        } catch (e: IOException) {
            throw IOException("ไม่สามารถเชื่อมต่อเครือข่าย Gemini API ได้ ($modelId): ${e.message}", e)
        }

        response.use { resp ->
            GeminiHttpResponse(
                statusCode = resp.code,
                body = resp.body?.string().orEmpty()
            )
        }
    }

    override suspend fun executeListModels(): GeminiHttpResponse = withContext(Dispatchers.IO) {
        validateOfficialEndpoint(baseUrl)

        val apiKey = apiKeyStore.getApiKey()?.trim().orEmpty()
        if (apiKey.isEmpty()) {
            throw AuthFailedException("กรุณาบันทึก Gemini API Key ก่อนทดสอบการเชื่อมต่อ")
        }

        val url = "${baseUrl.trimEnd('/')}/v1beta/models"
        val request = Request.Builder()
            .url(url)
            .get()
            .header("Accept", "application/json")
            .header("x-goog-api-key", apiKey)
            .build()

        val response = try {
            httpClient.newCall(request).execute()
        } catch (e: java.net.SocketTimeoutException) {
            throw TimeoutException("การตรวจสอบรายการโมเดล Gemini API หมดเวลา")
        } catch (e: IOException) {
            throw IOException("ไม่สามารถเชื่อมต่อ Gemini API เพื่อตรวจสอบโมเดลได้: ${e.message}", e)
        }

        response.use { resp ->
            GeminiHttpResponse(
                statusCode = resp.code,
                body = resp.body?.string().orEmpty()
            )
        }
    }

    companion object {
        fun validateOfficialEndpoint(endpointUrl: String) {
            val uri = try {
                URI(endpointUrl.trim())
            } catch (e: Exception) {
                throw AuthFailedException("Invalid Gemini endpoint URL")
            }
            val scheme = uri.scheme?.lowercase().orEmpty()
            val host = uri.host?.lowercase().orEmpty()
            if (scheme != "https" || host != "generativelanguage.googleapis.com") {
                throw AuthFailedException(
                    "Security Violation: Gemini API Key may only be sent over HTTPS to generativelanguage.googleapis.com (rejected: $endpointUrl)"
                )
            }
        }
    }
}

class GeminiTtsClient(
    private val context: Context,
    private val httpClient: OkHttpClient = defaultHttpClient(),
    private val apiKeyStore: SecureApiKeyStore = SecureApiKeyStore(context),
    private val transport: GeminiTtsTransport = OfficialGeminiApiKeyTransport(context, httpClient, apiKeyStore)
) {
    companion object {
        private const val TAG = "GeminiTtsClient"
        const val OFFICIAL_GEMINI_BASE_URL = "https://generativelanguage.googleapis.com"

        // Primary & Secondary Gemini TTS model IDs
        const val MODEL_FLASH = "gemini-3.8-flash-tts"
        const val MODEL_FLASH_LITE = "gemini-3.8-flash-lite-tts"

        // Additional verified official Gemini TTS model identifiers
        const val MODEL_GEMINI_3_1_FLASH_TTS_PREVIEW = "gemini-3.1-flash-tts-preview"
        const val MODEL_GEMINI_2_5_FLASH_TTS = "gemini-2.5-flash-preview-tts"
        const val MODEL_GEMINI_2_5_FLASH_LITE_TTS = "gemini-2.5-flash-lite-preview-tts"
        const val MODEL_GEMINI_2_5_PRO_TTS = "gemini-2.5-pro-preview-tts"

        const val THAI_LANGUAGE_CODE = "th-TH"

        val SUPPORTED_TTS_MODELS: Set<String> = setOf(
            MODEL_FLASH,
            MODEL_FLASH_LITE,
            MODEL_GEMINI_3_1_FLASH_TTS_PREVIEW,
            MODEL_GEMINI_2_5_FLASH_TTS,
            MODEL_GEMINI_2_5_FLASH_LITE_TTS,
            MODEL_GEMINI_2_5_PRO_TTS
        )

        /** All 30 documented Gemini API prebuilt voices (needed by the Gemini-app-style voice picker). */
        val SUPPORTED_VOICES: Set<String> = GeminiLiveVoice.ALL_API_VOICES

        fun defaultHttpClient(): OkHttpClient {
            return OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(18, TimeUnit.SECONDS)
                .writeTimeout(10, TimeUnit.SECONDS)
                .build()
        }

        fun endpointPathForModel(modelId: String): String {
            val cleanModelId = modelId.removePrefix("models/").trim()
            return "v1beta/models/$cleanModelId:generateContent"
        }

        /**
         * Validates that a Gemini TTS model ID is in the supported set.
         */
        fun isSupportedTtsModel(modelId: String): Boolean {
            val cleanModelId = modelId.removePrefix("models/").trim()
            return cleanModelId in SUPPORTED_TTS_MODELS ||
                (cleanModelId.startsWith("gemini-") && cleanModelId.contains("tts", ignoreCase = true))
        }

        /**
         * Gemini 3.x TTS models treat `text` as a verbatim transcript and take delivery directions in
         * `speechMetadata.style`; instructions embedded in the text may be read aloud.
         * Older preview models (2.5 / 3.1-preview) expect the directions inline in the prompt text.
         */
        fun usesSpeechMetadataStyle(modelId: String?): Boolean {
            val id = modelId?.removePrefix("models/")?.trim().orEmpty()
            return Regex("^gemini-3\\.([2-9]|[1-9][0-9])-.*tts").containsMatchIn(id)
        }

        /**
         * Validates that a generateContent JSON payload strictly conforms to the official
         * Gemini TTS audio generation schema:
         * - contents[0].role == "user"
         * - contents[0].parts[0].text is non-blank
         * - generationConfig.responseModalities == ["AUDIO"]
         * - generationConfig.speechConfig.languageCode == "th-TH"
         * - generationConfig.speechConfig.voiceConfig.prebuiltVoiceConfig.voiceName in SUPPORTED_VOICES
         */
        fun validateAudioRequestSchema(requestJson: JSONObject): Boolean {
            val contents = requestJson.optJSONArray("contents") ?: return false
            if (contents.length() == 0) return false
            val firstContent = contents.optJSONObject(0) ?: return false
            if (firstContent.optString("role") != "user") return false

            val parts = firstContent.optJSONArray("parts") ?: return false
            if (parts.length() == 0) return false
            val text = parts.optJSONObject(0)?.optString("text", "").orEmpty()
            if (text.isBlank()) return false

            val genConfig = requestJson.optJSONObject("generationConfig") ?: return false
            val modalities = genConfig.optJSONArray("responseModalities") ?: return false
            if (modalities.length() != 1 || modalities.optString(0) != "AUDIO") return false

            val speechConfig = genConfig.optJSONObject("speechConfig") ?: return false
            if (speechConfig.optString("languageCode") != THAI_LANGUAGE_CODE) return false

            val prebuiltVoice = speechConfig
                .optJSONObject("voiceConfig")
                ?.optJSONObject("prebuiltVoiceConfig") ?: return false
            val voiceName = prebuiltVoice.optString("voiceName", "")
            return voiceName in SUPPORTED_VOICES
        }

        /**
         * Formats the native Gemini TTS speech style prompt together with the processed Thai transcript.
         */
        fun buildStyledSpeechPrompt(
            text: String,
            stylePrompt: String
        ): String {
            val cleanText = text.trim()
            val cleanStyle = stylePrompt.replace(VoiceStylePreset.VOICE_HINT_MARK, ". Voice: ").trim().ifEmpty {
                VoiceStylePreset.FRIENDLY_COMPANION.stylePrompt
            }
            return """
                [คำแนะนำสไตล์การพูด / Speech Style Metadata]
                $cleanStyle
                ออกเสียงภาษาไทยให้ชัดเจน เป็นธรรมชาติ และอ่านเฉพาะข้อความด้านล่างนี้เท่านั้น ห้ามอ่านคำแนะนำสไตล์การพูด:
                
                $cleanText
            """.trimIndent()
        }

        /**
         * Builds the official Gemini REST `generateContent` JSON payload for native AUDIO modality
         * with `speechConfig.voiceConfig.prebuiltVoiceConfig.voiceName` and `languageCode = "th-TH"`.
         */
        fun buildGenerateContentRequestJson(
            text: String,
            voiceName: String = VoiceStylePreset.FRIENDLY_COMPANION.voiceName,
            stylePrompt: String = VoiceStylePreset.FRIENDLY_COMPANION.stylePrompt,
            modelId: String? = null
        ): JSONObject {
            val resolvedVoice = if (voiceName in SUPPORTED_VOICES) {
                voiceName
            } else {
                VoiceStylePreset.fromVoiceOrName(voiceName).voiceName
            }
            val useMetadataStyle = usesSpeechMetadataStyle(modelId)
            val styledPrompt = if (useMetadataStyle) {
                text.trim()
            } else {
                buildStyledSpeechPrompt(text = text, stylePrompt = stylePrompt)
            }

            return JSONObject().apply {
                put(
                    "contents",
                    JSONArray().put(
                        JSONObject().apply {
                            put("role", "user")
                            put(
                                "parts",
                                JSONArray().put(
                                    JSONObject().apply {
                                        put("text", styledPrompt)
                                        if (useMetadataStyle) {
                                            val style = VoiceStylePreset.geminiStyleFor(stylePrompt)
                                            if (style.isNotBlank()) {
                                                put("speechMetadata", JSONObject().put("style", style))
                                            }
                                        }
                                    }
                                )
                            )
                        }
                    )
                )
                put(
                    "generationConfig",
                    JSONObject().apply {
                        put("responseModalities", JSONArray().put("AUDIO"))
                        put(
                            "speechConfig",
                            JSONObject().apply {
                                put("languageCode", THAI_LANGUAGE_CODE)
                                put(
                                    "voiceConfig",
                                    JSONObject().apply {
                                        put(
                                            "prebuiltVoiceConfig",
                                            JSONObject().apply {
                                                put("voiceName", resolvedVoice)
                                            }
                                        )
                                    }
                                )
                            }
                        )
                    }
                )
            }
        }
    }

    /**
     * Queries the official Gemini API `v1beta/models` endpoint to verify API Key validity
     * and discover available TTS models before enabling specific models.
     */
    suspend fun verifyAvailableModels(): VerifiedModelsStatus = withContext(Dispatchers.IO) {
        val response = transport.executeListModels()
        val code = response.statusCode
        val rawBody = response.body

        if (code in 200..299) {
            return@withContext parseModelsListResponse(rawBody)
        }

        val errorMsg = extractErrorMessage(rawBody).ifEmpty { "HTTP $code" }
        when (code) {
            400, 401, 403 -> throw AuthFailedException("API Key ไม่ถูกต้องหรือไม่มีสิทธิ์ใช้งาน ($code): $errorMsg")
            429 -> throw QuotaExhaustedException("โควตา Gemini API เต็ม ($code): $errorMsg")
            else -> throw CloudModelUnavailableException("ไม่สามารถตรวจสอบรายการโมเดลได้ ($code): $errorMsg")
        }
    }

    fun parseModelsListResponse(rawJson: String): VerifiedModelsStatus {
        val root = JSONObject(rawJson)
        val modelsArray = root.optJSONArray("models") ?: JSONArray()
        val discoveredIds = mutableListOf<String>()

        for (i in 0 until modelsArray.length()) {
            val obj = modelsArray.optJSONObject(i) ?: continue
            val rawName = obj.optString("name", "").removePrefix("models/").trim()
            if (rawName.isNotEmpty()) {
                discoveredIds.add(rawName)
            }
        }

        val ttsModels = discoveredIds.filter { id ->
            id in SUPPORTED_TTS_MODELS || id.contains("tts", ignoreCase = true)
        }

        val resolvedFlash = listOf(
            MODEL_FLASH,
            MODEL_GEMINI_3_1_FLASH_TTS_PREVIEW,
            MODEL_GEMINI_2_5_FLASH_TTS
        ).firstOrNull { it in ttsModels } ?: MODEL_FLASH

        val resolvedFlashLite = listOf(
            MODEL_FLASH_LITE,
            MODEL_GEMINI_2_5_FLASH_LITE_TTS,
            MODEL_GEMINI_2_5_FLASH_TTS,
            MODEL_GEMINI_2_5_PRO_TTS
        ).firstOrNull { it in ttsModels && it != resolvedFlash } ?: MODEL_FLASH_LITE

        val summary = if (ttsModels.isNotEmpty()) {
            "เชื่อมต่อสำเร็จ • พร้อมใช้งาน ($resolvedFlash → $resolvedFlashLite)"
        } else {
            "เชื่อมต่อสำเร็จ • ใช้ค่าเริ่มต้น ($resolvedFlash → $resolvedFlashLite)"
        }

        return VerifiedModelsStatus(
            isVerified = true,
            flashModelId = resolvedFlash,
            flashLiteModelId = resolvedFlashLite,
            availableModels = if (ttsModels.isNotEmpty()) ttsModels else listOf(resolvedFlash, resolvedFlashLite),
            statusMessage = summary
        )
    }

    /**
     * Synthesizes Thai speech using the specified Gemini TTS model, voice, and speech style metadata.
     */
    suspend fun synthesize(
        text: String,
        modelId: String = MODEL_FLASH,
        voiceName: String = VoiceStylePreset.FRIENDLY_COMPANION.voiceName,
        stylePrompt: String = VoiceStylePreset.FRIENDLY_COMPANION.stylePrompt
    ): DecodedPcmAudio = withContext(Dispatchers.IO) {
        val cleanModelId = modelId.removePrefix("models/").trim()
        if (!isSupportedTtsModel(cleanModelId)) {
            throw CloudModelUnavailableException("Unsupported Gemini TTS model ID: $cleanModelId")
        }

        val endpointPath = endpointPathForModel(cleanModelId)
        val requestJson = buildGenerateContentRequestJson(
            text = text,
            voiceName = voiceName,
            stylePrompt = stylePrompt,
            modelId = cleanModelId
        )

        if (!validateAudioRequestSchema(requestJson)) {
            throw IllegalArgumentException("Invalid Gemini TTS audio request schema for model $cleanModelId")
        }

        val response = try {
            transport.executeGenerateContent(
                modelId = cleanModelId,
                endpointPath = endpointPath,
                requestJson = requestJson
            )
        } catch (e: CancellationException) {
            throw e
        }

        val code = response.statusCode
        val rawBody = response.body

        if (code in 200..299) {
            if (rawBody.isBlank()) {
                throw InvalidAudioException("Empty response body from Gemini TTS ($cleanModelId)")
            }
            return@withContext parseGeminiAudioResponse(rawBody, cleanModelId)
        }

        val errorDetail = extractErrorMessage(rawBody).ifEmpty { "HTTP $code" }
        Log.w(TAG, "Gemini TTS ($cleanModelId) HTTP $code: $errorDetail")

        // Gemini answers an invalid key with HTTP 400 ("API key not valid"), not 401
        if (code == 400 && errorDetail.contains("API key", ignoreCase = true)) {
            throw AuthFailedException("Gemini API Key ไม่ถูกต้อง (400): $errorDetail")
        }

        when (code) {
            429 -> throw QuotaExhaustedException("โควตา Gemini API เต็ม (429): $errorDetail")
            401, 403 -> throw AuthFailedException("Gemini API Key ไม่ถูกต้องหรือถูกปฏิเสธ ($code): $errorDetail")
            404, 503 -> throw CloudModelUnavailableException("โมเดล Gemini TTS ($cleanModelId) ไม่พร้อมใช้งาน ($code): $errorDetail")
            408, 504 -> throw TimeoutException("Gemini TTS ($cleanModelId) หมดเวลาเชื่อมต่อ ($code): $errorDetail")
            else -> throw IOException("Gemini TTS ($cleanModelId) ขัดข้อง ($code): $errorDetail")
        }
    }

    /**
     * Parses the JSON response from `generateContent` and decodes base64 inlineData audio
     * (`audio/L16;codec=pcm;rate=24000`, `audio/pcm`, or `audio/wav`) into 16-bit PCM.
     */
    fun parseGeminiAudioResponse(jsonString: String, modelId: String): DecodedPcmAudio {
        try {
            val root = JSONObject(jsonString)
            val candidates = root.optJSONArray("candidates")
                ?: throw InvalidAudioException("No candidates in Gemini TTS response ($modelId)")
            if (candidates.length() == 0) {
                throw InvalidAudioException("Empty candidates array in Gemini TTS response ($modelId)")
            }

            val firstCandidate = candidates.getJSONObject(0)
            val content = firstCandidate.optJSONObject("content")
                ?: throw InvalidAudioException("Missing content object in Gemini TTS candidate ($modelId)")
            val parts = content.optJSONArray("parts")
                ?: throw InvalidAudioException("Missing parts array in Gemini TTS content ($modelId)")

            for (i in 0 until parts.length()) {
                val part = parts.getJSONObject(i)
                val inlineData = part.optJSONObject("inlineData") ?: part.optJSONObject("inline_data")
                if (inlineData != null) {
                    val mimeType = inlineData.optString("mimeType", inlineData.optString("mime_type", "audio/pcm"))
                    val base64Data = inlineData.optString("data", "")
                    if (base64Data.isNotEmpty()) {
                        val rawBytes = Base64.decode(base64Data, Base64.DEFAULT)
                        if (rawBytes.isEmpty()) {
                            throw InvalidAudioException("Decoded base64 audio bytes are empty ($modelId)")
                        }
                        val defaultRate = AudioDecoder.extractSampleRateFromMimeType(mimeType, 24000)
                        return AudioDecoder.decodeToPcm(
                            audioBytes = rawBytes,
                            cacheDir = context.cacheDir,
                            fallbackSampleRate = defaultRate,
                            mimeType = mimeType
                        )
                    }
                }
            }

            throw InvalidAudioException("No inlineData audio payload found in Gemini TTS response ($modelId)")
        } catch (e: InvalidAudioException) {
            throw e
        } catch (e: Exception) {
            throw InvalidAudioException("Failed to parse Gemini TTS response ($modelId): ${e.message}")
        }
    }

    private fun extractErrorMessage(errorBody: String): String {
        if (errorBody.isBlank()) return ""
        return try {
            val json = JSONObject(errorBody)
            val errObj = json.optJSONObject("error")
            errObj?.optString("message") ?: errorBody.take(160)
        } catch (e: Exception) {
            errorBody.take(160)
        }
    }
}
