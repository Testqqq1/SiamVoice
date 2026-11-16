package com.example.model

enum class TtsEngineType(val displayName: String) {
    GEMINI_FLASH("Gemini Flash TTS"),
    GEMINI_FLASH_LITE("Gemini Flash-Lite TTS"),
    ANDROID_SYSTEM_TTS("Android System TTS")
}

/**
 * Intelligent Thai polite endings:
 * - AUTO_NATURAL: Automatically selects ครับ / นะครับ / ครับผม based on navigation or sentence context
 * - KRUB: ครับ
 * - NA_KRUB: นะครับ
 * - KRUB_PHOM: ครับผม
 * - OFF: Off (no modification)
 */
enum class PoliteEndingStyle(val label: String, val description: String) {
    AUTO_NATURAL("Auto Natural", "เติม ครับ / นะครับ / ครับผม อัตโนมัติตามบริบทประโยค"),
    KRUB("ครับ", "ลงท้ายด้วย ครับ สุภาพมาตรฐาน"),
    NA_KRUB("นะครับ", "ลงท้ายด้วย นะครับ นุ่มนวลเป็นกันเอง"),
    KRUB_PHOM("ครับผม", "ลงท้ายด้วย ครับผม สุภาพหนักแน่นชัดเจน"),
    OFF("Off", "ปิดการปรับแต่ง สังเคราะห์ข้อความตามต้นฉบับ");

    companion object {
        val MALE_DEFAULT: PoliteEndingStyle get() = AUTO_NATURAL
        val FORMAL_MALE: PoliteEndingStyle get() = KRUB_PHOM

        fun fromStoredName(name: String?): PoliteEndingStyle {
            return when (name?.trim()) {
                "MALE_DEFAULT", AUTO_NATURAL.name, AUTO_NATURAL.label -> AUTO_NATURAL
                KRUB.name, KRUB.label -> KRUB
                NA_KRUB.name, NA_KRUB.label -> NA_KRUB
                "FORMAL_MALE", KRUB_PHOM.name, KRUB_PHOM.label -> KRUB_PHOM
                OFF.name, OFF.label, "ปิดการปรับแต่ง" -> OFF
                else -> AUTO_NATURAL
            }
        }
    }
}

/**
 * Supported Gemini prebuilt voices for SiamVoice.
 */
enum class GeminiVoiceOption(
    val voiceName: String,
    val displayName: String,
    val thaiDescription: String
) {
    PUCK("Puck", "Puck", "โทนเสียงสดใส กระฉับกระเฉง เป็นกันเอง"),
    ORUS("Orus", "Orus", "โทนเสียงนุ่มทุ้ม สุภาพ สุขุม ฟังสบาย");

    companion object {
        fun fromVoiceName(name: String?): GeminiVoiceOption {
            return entries.firstOrNull { it.voiceName.equals(name?.trim(), ignoreCase = true) } ?: PUCK
        }
    }
}

/**
 * Thai voice personality presets for Gemini TTS.
 * Default: Friendly Companion (Puck).
 * Alternative: Calm Personal Assistant (Orus).
 */
enum class VoiceStylePreset(
    val presetName: String,
    val thaiTitle: String,
    val voiceName: String,
    val stylePrompt: String
) {
    FRIENDLY_COMPANION(
        presetName = "Friendly Companion",
        thaiTitle = "เพื่อนร่วมทางอารมณ์ดี (Friendly Companion)",
        voiceName = "Puck",
        stylePrompt = "พูดด้วยน้ำเสียงอารมณ์ดี กระตือรือร้น เป็นธรรมชาติ เหมือนเพื่อนสนิทนั่งข้างคนขับ"
    ),
    PERSONAL_ASSISTANT(
        presetName = "Calm Personal Assistant",
        thaiTitle = "ผู้ช่วยส่วนตัวสุภาพใจเย็น (Calm Personal Assistant)",
        voiceName = "Orus",
        stylePrompt = "พูดด้วยน้ำเสียงนุ่มทุ้ม สุภาพ ใจเย็น อบอุ่น เหมือนเลขาส่วนตัว"
    );

    companion object {
        fun fromVoiceOrName(value: String?): VoiceStylePreset {
            return when (value?.trim()) {
                PERSONAL_ASSISTANT.name,
                PERSONAL_ASSISTANT.presetName,
                "Personal Assistant",
                PERSONAL_ASSISTANT.voiceName -> PERSONAL_ASSISTANT
                else -> FRIENDLY_COMPANION
            }
        }
    }
}

/**
 * Preferred Gemini TTS model mode in Settings.
 * Default order is always:
 * 1. Gemini Flash TTS -> 2. Gemini Flash-Lite TTS -> 3. External Android System TTS
 */
enum class GeminiModelPreference(
    val label: String,
    val subtitle: String
) {
    FLASH_PRIMARY(
        label = "Gemini Flash TTS → Flash-Lite (แนะนำ)",
        subtitle = "1. Gemini Flash TTS → 2. Gemini Flash-Lite TTS → 3. Android TTS"
    ),
    FLASH_LITE_PRIMARY(
        label = "Gemini Flash-Lite TTS (เน้นความเร็ว)",
        subtitle = "1. Gemini Flash-Lite TTS → 2. Gemini Flash TTS → 3. Android TTS"
    );

    companion object {
        fun fromStoredName(name: String?): GeminiModelPreference {
            return entries.firstOrNull { it.name == name } ?: FLASH_PRIMARY
        }
    }
}

enum class CircuitBreakerState {
    CLOSED,    // Healthy, normal execution
    OPEN,      // Tripped due to failures, bypassing cloud
    HALF_OPEN  // Testing single request after cooldown
}

data class SynthesisRequestData(
    val text: String,
    val politeStyle: PoliteEndingStyle = PoliteEndingStyle.AUTO_NATURAL,
    val voicePreset: VoiceStylePreset = VoiceStylePreset.FRIENDLY_COMPANION,
    val geminiVoice: String = voicePreset.voiceName,
    val stylePrompt: String = voicePreset.stylePrompt,
    val speechRate: Float = 1.0f,
    val pitch: Float = 1.0f
)

data class SynthesisResult(
    val audioData: ByteArray,
    val sampleRate: Int = 24000,
    val channelCount: Int = 1,
    val engineUsed: TtsEngineType,
    val latencyMs: Long = 0,
    val wasCached: Boolean = false,
    val processedText: String = "",
    val voiceName: String = VoiceStylePreset.FRIENDLY_COMPANION.voiceName,
    val stylePrompt: String = VoiceStylePreset.FRIENDLY_COMPANION.stylePrompt
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as SynthesisResult
        if (!audioData.contentEquals(other.audioData)) return false
        if (sampleRate != other.sampleRate) return false
        if (channelCount != other.channelCount) return false
        if (engineUsed != other.engineUsed) return false
        if (processedText != other.processedText) return false
        if (voiceName != other.voiceName) return false
        if (stylePrompt != other.stylePrompt) return false
        return true
    }

    override fun hashCode(): Int {
        var result = audioData.contentHashCode()
        result = 31 * result + sampleRate
        result = 31 * result + channelCount
        result = 31 * result + engineUsed.hashCode()
        result = 31 * result + processedText.hashCode()
        result = 31 * result + voiceName.hashCode()
        result = 31 * result + stylePrompt.hashCode()
        return result
    }
}

data class ExternalEngineInfo(
    val packageName: String,
    val label: String,
    val supportsThai: Boolean = false,
    val isSystemDefault: Boolean = false
)

data class VerifiedModelsStatus(
    val isVerified: Boolean = false,
    val flashModelId: String = "gemini-3.8-flash-tts",
    val flashLiteModelId: String = "gemini-3.8-flash-lite-tts",
    val availableModels: List<String> = emptyList(),
    val statusMessage: String = "ยังไม่ได้ตรวจสอบรายการโมเดล"
)

data class DiagnosticsInfo(
    val activeEngine: TtsEngineType? = null,
    val lastLatencyMs: Long = 0,
    val lastProcessedText: String = "",
    val isOnline: Boolean = true,
    val hasApiKey: Boolean = false,
    val flashCircuitBreakerState: CircuitBreakerState = CircuitBreakerState.CLOSED,
    val flashLiteCircuitBreakerState: CircuitBreakerState = CircuitBreakerState.CLOSED,
    val cacheItemsCount: Int = 0,
    val preferredEngineName: String = "",
    val lastErrorSummary: String? = null
)

data class ThaiAudioPrompt(
    val thaiScript: String,
    val audioDescription: String,
    val recommendedVoice: String = VoiceStylePreset.FRIENDLY_COMPANION.voiceName,
    val category: String = "navigation"
)
