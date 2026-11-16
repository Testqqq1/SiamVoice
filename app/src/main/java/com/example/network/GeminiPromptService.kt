package com.example.network

import android.content.Context
import android.util.Log
import com.example.data.SecureApiKeyStore
import com.example.engine.AuthFailedException
import com.example.model.PoliteEndingStyle
import com.example.model.ThaiAudioPrompt
import com.example.model.VoiceStylePreset
import com.example.processor.ThaiPoliteEndingProcessor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Service that interacts with the official Google Gemini API over HTTPS using the user's
 * Keystore-backed API key to generate natural-sounding Thai audio descriptions and prompts.
 */
open class GeminiPromptService(
    private val context: Context? = null,
    private val httpClient: OkHttpClient = defaultHttpClient()
) {
    companion object {
        private const val TAG = "GeminiPromptService"
        const val MODEL_FLASH = "gemini-2.5-flash"
        const val MODEL_FLASH_LITE = "gemini-2.5-flash-lite"

        private const val SYSTEM_INSTRUCTION = """
คุณคือผู้เชี่ยวชาญด้านการออกแบบสคริปต์เสียงภาษาไทยและคำบรรยายเสียง (Thai Audio Description & Voice Prompt Specialist) สำหรับแอปพลิเคชัน SiamVoice TTS
หน้าที่ของคุณคือสร้างประโยคภาษาไทยที่ฟังดูเป็นธรรมชาติ ออกเสียงง่าย จังหวะจะโคนชัดเจน สุภาพ และเป็นมิตร เหมาะสำหรับการสังเคราะห์เสียงพูด (Text-to-Speech) และระบบนำทางอัจฉริยะ
- ใช้ถ้อยคำที่เป็นธรรมชาติเหมือนคนไทยพูดจริง ไม่แปลตรงตัวหรือแข็งกระด้าง
- รักษาข้อมูลสำคัญ เช่น ระยะทาง ทิศทาง หรือชื่อสถานที่ให้ครบถ้วนชัดเจน
- ระบุคำแนะนำโทนเสียงและการถ่ายทอดอารมณ์ (audioDescription) ที่เหมาะสมกับบริบท
"""

        fun defaultHttpClient(): OkHttpClient {
            return OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(15, TimeUnit.SECONDS)
                .writeTimeout(10, TimeUnit.SECONDS)
                .build()
        }
    }

    open suspend fun generateThaiAudioPrompt(
        topicOrContext: String,
        politeStyle: PoliteEndingStyle = PoliteEndingStyle.AUTO_NATURAL
    ): ThaiAudioPrompt = withContext(Dispatchers.IO) {
        val cleanContext = topicOrContext.trim().ifEmpty {
            "การแจ้งเตือนเส้นทางและการเดินทางด้วยความระมัดระวัง"
        }

        val prompt = buildString {
            append("สร้างประโยคสคริปต์เสียงพูดภาษาไทยที่เป็นธรรมชาติและคำบรรยายลักษณะเสียง (Audio Description) สำหรับหัวข้อหรือบริบทต่อไปนี้: ")
            append("\"$cleanContext\"\n")
            append("สไตล์คำลงท้ายที่ต้องการ: ${politeStyle.label} (${politeStyle.description})")
        }

        try {
            withTimeout(25_000L) {
                try {
                    requestStructuredPromptViaHttps(prompt, MODEL_FLASH, politeStyle)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "Primary model $MODEL_FLASH failed (${e.message}), trying $MODEL_FLASH_LITE")
                    requestStructuredPromptViaHttps(prompt, MODEL_FLASH_LITE, politeStyle)
                }
            }
        } catch (e: CancellationException) {
            throw e
        }
    }

    open suspend fun enhanceTextForSpeech(
        rawText: String,
        politeStyle: PoliteEndingStyle = PoliteEndingStyle.AUTO_NATURAL
    ): ThaiAudioPrompt = withContext(Dispatchers.IO) {
        val trimmed = rawText.trim()
        if (trimmed.isEmpty()) {
            throw IllegalArgumentException("Input text cannot be blank.")
        }

        val prompt = """
ปรับปรุงข้อความต่อไปนี้ให้เป็นสคริปต์เสียงพูดภาษาไทยที่ฟังเป็นธรรมชาติ ลื่นหู และเหมาะกับการอ่านออกเสียง (TTS) โดยห้ามตัดทอนตัวเลขหรือทิศทางสำคัญ:
ข้อความต้นฉบับ: "$trimmed"
สไตล์ความสุภาพ: ${politeStyle.label}
""".trimIndent()

        generateThaiAudioPrompt(prompt, politeStyle)
    }

    protected open suspend fun requestStructuredPromptViaHttps(
        userPrompt: String,
        modelId: String,
        politeStyle: PoliteEndingStyle
    ): ThaiAudioPrompt {
        val ctx = context ?: throw AuthFailedException("Context required to read encrypted Gemini API Key")
        val apiKey = SecureApiKeyStore(ctx).getApiKey()?.trim().orEmpty()
        if (apiKey.isEmpty()) {
            throw AuthFailedException("ยังไม่ได้ตั้งค่า Gemini API Key")
        }

        val requestPayload = JSONObject().apply {
            put(
                "systemInstruction",
                JSONObject().apply {
                    put("parts", JSONArray().put(JSONObject().put("text", SYSTEM_INSTRUCTION)))
                }
            )
            put(
                "contents",
                JSONArray().put(
                    JSONObject().apply {
                        put("role", "user")
                        put("parts", JSONArray().put(JSONObject().put("text", userPrompt)))
                    }
                )
            )
            put(
                "generationConfig",
                JSONObject().apply {
                    put("responseMimeType", "application/json")
                    put("temperature", 0.7)
                }
            )
        }

        val url = "${GeminiTtsClient.OFFICIAL_GEMINI_BASE_URL}/v1beta/models/$modelId:generateContent"
        val request = Request.Builder()
            .url(url)
            .post(requestPayload.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
            .header("Accept", "application/json")
            .header("x-goog-api-key", apiKey)
            .build()

        val response = httpClient.newCall(request).execute()
        response.use { resp ->
            val rawBody = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                throw IOException("Gemini API request failed (${resp.code})")
            }
            return parseStructuredPromptResponse(rawBody, politeStyle)
        }
    }

    fun parseStructuredPromptResponse(
        rawApiResponseJson: String,
        politeStyle: PoliteEndingStyle = PoliteEndingStyle.AUTO_NATURAL
    ): ThaiAudioPrompt {
        val root = JSONObject(rawApiResponseJson)
        if (root.has("thaiScript")) {
            return parseStructuredJsonObject(root, politeStyle)
        }

        val candidates = root.optJSONArray("candidates")
            ?: throw IOException("No candidates in Gemini response")
        if (candidates.length() == 0) {
            throw IOException("Empty candidates list in Gemini response")
        }

        val parts = candidates.getJSONObject(0)
            .optJSONObject("content")
            ?.optJSONArray("parts")
            ?: throw IOException("Missing content parts in Gemini response")

        if (parts.length() == 0) {
            throw IOException("Empty parts array in Gemini response")
        }

        val structuredText = parts.getJSONObject(0).optString("text", "").trim()
        if (structuredText.isEmpty()) {
            throw IOException("Gemini returned empty structured JSON text")
        }

        return parseStructuredJsonObject(JSONObject(structuredText), politeStyle)
    }

    private fun parseStructuredJsonObject(
        itemJson: JSONObject,
        politeStyle: PoliteEndingStyle
    ): ThaiAudioPrompt {
        val rawScript = itemJson.optString("thaiScript", "").trim()
        if (rawScript.isEmpty()) {
            throw IOException("Generated thaiScript field was empty")
        }

        val processedScript = ThaiPoliteEndingProcessor.process(rawScript, politeStyle)
        val description = itemJson.optString(
            "audioDescription",
            VoiceStylePreset.FRIENDLY_COMPANION.stylePrompt
        ).trim()
        val voice = itemJson.optString("recommendedVoice", "Puck").trim().ifEmpty { "Puck" }
        val category = itemJson.optString("category", "navigation").trim().ifEmpty { "navigation" }

        return ThaiAudioPrompt(
            thaiScript = processedScript,
            audioDescription = description,
            recommendedVoice = voice,
            category = category
        )
    }
}
