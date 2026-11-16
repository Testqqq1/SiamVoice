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
 * Thai voice personality / response-style presets for Gemini TTS, inspired by the "personalities"
 * of modern AI assistants (default, unhinged, flirty, ...).
 *
 * Each preset carries:
 * - [voiceName]: suggested Gemini prebuilt voice,
 * - [stylePrompt]: delivery / tone directions that are sent together with the transcript to Gemini TTS,
 * - [rewriteInstruction]: how [com.example.network.GeminiPromptService] should phrase generated or
 *   rewritten scripts in this style,
 * - [speechRateHint] / [pitchHint]: multipliers applied to the Android system TTS fallback
 *   (Gemini TTS has no numeric rate/pitch, so tone is steered via [stylePrompt]),
 * - [isAdult]: 18+ presets are gated behind a one-time age confirmation
 *   (see [com.example.data.SiamVoicePreferences.confirmAdultContent]) and are never selectable before it,
 * - [politeEndingOverride]: when non-null it replaces the user's polite-ending setting
 *   (18+ presets switch the automatic "ครับ/ค่ะ" processor off).
 *
 * Default: Friendly Companion (Puck) which keeps the original behaviour of the app.
 * No preset may involve minors; adult presets are for consenting adults only and stay suggestive,
 * never explicit.
 */
enum class VoiceStylePreset(
    val presetName: String,
    val thaiTitle: String,
    val voiceName: String,
    val stylePrompt: String,
    val thaiDescription: String = "",
    val rewriteInstruction: String = "",
    val speechRateHint: Float = 1.0f,
    val pitchHint: Float = 1.0f,
    val isAdult: Boolean = false,
    val politeEndingOverride: PoliteEndingStyle? = null,
    /** Short English delivery direction, sent as `speechMetadata.style` to Gemini 3.x TTS models. */
    val geminiStyle: String = ""
) {
    FRIENDLY_COMPANION(
        presetName = "Friendly Companion",
        thaiTitle = "เพื่อนร่วมทางอารมณ์ดี (Friendly Companion)",
        voiceName = "Puck",
        stylePrompt = "พูดด้วยน้ำเสียงอารมณ์ดี กระตือรือร้น เป็นธรรมชาติ เหมือนเพื่อนสนิทนั่งข้างคนขับ",
        thaiDescription = "เพื่อนสนิทอารมณ์ดี (ค่าเริ่มต้น)",
        geminiStyle = "cheerful, friendly and upbeat, like a close friend sitting next to the driver",
        rewriteInstruction = "เขียนด้วยน้ำเสียงเป็นกันเอง อารมณ์ดี สุภาพ เหมือนเพื่อนสนิท"
    ),
    PERSONAL_ASSISTANT(
        presetName = "Calm Personal Assistant",
        thaiTitle = "ผู้ช่วยส่วนตัวสุภาพใจเย็น (Calm Personal Assistant)",
        voiceName = "Orus",
        stylePrompt = "พูดด้วยน้ำเสียงนุ่มทุ้ม สุภาพ ใจเย็น อบอุ่น เหมือนเลขาส่วนตัว",
        thaiDescription = "สุภาพ ใจเย็น เหมือนเลขาส่วนตัว",
        geminiStyle = "calm, polite, warm and composed, like a personal secretary",
        rewriteInstruction = "เขียนด้วยน้ำเสียงสุภาพ สุขุม ใจเย็น กระชับ"
    ),
    NAVIGATION(
        presetName = "Navigation",
        thaiTitle = "เสียงนำทาง ชัดเจน (Navigation)",
        voiceName = "Kore",
        stylePrompt = "Speak like a clear, crisp navigation announcer: firm, calm and easy to hear in a noisy car, " +
            "moderate speed, short sentences, never whisper. " +
            "พูดชัดถ้อยชัดคำ หนักแน่น ใจเย็น ความเร็วปานกลาง ฟังง่ายในรถ ห้ามกระซิบ",
        thaiDescription = "ชัด กระชับ ความเร็วปานกลาง สำหรับนำทาง",
        geminiStyle = "clear, crisp and calm navigation announcer, moderate pace, never whisper",
        rewriteInstruction = "เขียนให้สั้น กระชับ ชัดเจน บอกระยะทางและทิศทางก่อน ไม่ใช้คำฟุ่มเฟือย"
    ),
    ROMANTIC(
        presetName = "Romantic",
        thaiTitle = "โรแมนติก หวานละมุน (Romantic)",
        voiceName = "Algieba",
        stylePrompt = "Speak in a warm, tender, romantic voice, soft and smooth, slightly slower, " +
            "like whispering sweet words to a loved one over dinner. " +
            "พูดด้วยน้ำเสียงอบอุ่น หวานละมุน นุ่มนวล จังหวะช้าลงเล็กน้อย เหมือนกระซิบคำหวานกับคนที่รัก",
        thaiDescription = "หวาน อบอุ่น นุ่มนวล (ทุกวัย)",
        geminiStyle = "warm, tender and romantic, soft, smooth and slightly slower",
        rewriteInstruction = "เขียนด้วยน้ำเสียงโรแมนติก หวาน อบอุ่น สุภาพ ไม่หยาบคาย",
        speechRateHint = 0.9f
    ),
    STORYTELLER(
        presetName = "Storyteller",
        thaiTitle = "นักเล่านิทาน (Storyteller)",
        voiceName = "Sulafat",
        stylePrompt = "Speak like a captivating storyteller: warm, expressive, with dramatic pauses " +
            "and rising suspense at the key moments. " +
            "พูดแบบนักเล่าเรื่อง อบอุ่น มีอารมณ์ ใส่จังหวะหยุดเว้นวรรคให้ลุ้นตามจังหวะสำคัญ",
        thaiDescription = "เล่าเรื่องมีจังหวะ ลุ้นระทึก",
        geminiStyle = "expressive storyteller, warm, with dramatic pauses and rising suspense",
        rewriteInstruction = "เขียนแบบนักเล่าเรื่อง มีจังหวะ เว้นวรรค และสร้างบรรยากาศ",
        speechRateHint = 0.95f
    ),
    CONSPIRACY(
        presetName = "Conspiracy Whisper",
        thaiTitle = "กระซิบทฤษฎีสมคบคิด (Conspiracy Whisper)",
        voiceName = "Algenib",
        stylePrompt = "Speak in a hushed, paranoid, conspiratorial whisper, as if sharing a dangerous secret. " +
            "Look over your shoulder, drop your voice at the punchlines, playful and over-the-top. " +
            "พูดกระซิบแบบลับๆ ล่อๆ หวาดระแวงอย่างตลก เหมือนเล่าความลับระดับโลก",
        thaiDescription = "กระซิบลับๆ ตลกๆ เล่นใหญ่",
        geminiStyle = "hushed, paranoid conspiratorial whisper, playful and over-the-top",
        rewriteInstruction = "เขียนแบบเล่าความลับสุดแสบ ขำๆ เว่อร์ๆ (เพื่อความบันเทิงเท่านั้น ไม่อ้างข้อมูลจริงที่เป็นเท็จ)",
        speechRateHint = 0.95f
    ),
    UNHINGED(
        presetName = "Unhinged 18+",
        thaiTitle = "ปากจัด ตัวตึง (Unhinged) 18+",
        voiceName = "Fenrir",
        stylePrompt = "Speak in an unhinged, chaotic, high-energy voice: sarcastic, cheeky, wildly " +
            "expressive, laughing and shouting at the right moments, delivering every word with attitude. " +
            "พูดแบบกวนๆ ปากจัด พลังงานสูง ประชดประชัน เล่นใหญ่ มีหัวเราะและตะโกนในจังหวะที่ใช่",
        thaiDescription = "กวน ปากจัด ประชด พลังงานเต็ม (เฉพาะผู้ใหญ่)",
        geminiStyle = "unhinged, chaotic and high-energy, sarcastic and cheeky, full of attitude",
        rewriteInstruction = "เขียนแบบกวนๆ ปากจัด ประชดประชัน อนุญาตให้ใช้คำสบถหรือคำหยาบเบาๆ ได้ " +
            "ห้ามเหยียดหรือด่าทอกลุ่มคนหรือบุคคลจริง ห้ามมีเนื้อหาเกี่ยวกับเด็กหรือผู้เยาว์",
        speechRateHint = 1.1f,
        pitchHint = 1.05f,
        isAdult = true,
        politeEndingOverride = PoliteEndingStyle.OFF
    ),
    SEXY(
        presetName = "Sexy 18+",
        thaiTitle = "เสียงกระซิบยั่วๆ (Sexy) 18+",
        voiceName = "Enceladus",
        stylePrompt = "Speak in a sultry, breathy, intimate adult voice: low and slow, " +
            "a seductive whisper close to the microphone, with soft sighs and playful pauses. " +
            "พูดด้วยน้ำเสียงกระซิบแผ่วๆ ทุ้มต่ำ จังหวะช้า ยั่วเย้าเป็นผู้ใหญ่ มีเสียงลมหายใจและเว้นจังหวะอย่างเย้ายวน",
        thaiDescription = "กระซิบแผ่ว ทุ้ม ช้า เย้ายวน (เฉพาะผู้ใหญ่)",
        geminiStyle = "sultry, breathy, slow and intimate low whisper",
        rewriteInstruction = "เขียนแบบเย้ายวน กระซิบ ชวนจินตนาการ สำหรับผู้ใหญ่ที่ยินยอมเท่านั้น " +
            "ใช้ภาษาเชิงเย้าแหย่ ไม่โจ่งแจ้งหรือลามกอนาจาร ห้ามมีเนื้อหาเกี่ยวกับเด็กหรือผู้เยาว์",
        speechRateHint = 0.85f,
        pitchHint = 0.92f,
        isAdult = true,
        politeEndingOverride = PoliteEndingStyle.OFF
    );

    /** The polite-ending style actually applied for this preset given the user's own choice. */
    fun resolvePoliteEnding(userChoice: PoliteEndingStyle): PoliteEndingStyle =
        politeEndingOverride ?: userChoice

    companion object {
        /** Presets that anyone may select without an age confirmation. */
        val allAgesPresets: List<VoiceStylePreset> get() = entries.filter { !it.isAdult }

        /** 18+ presets that require a one-time age confirmation. */
        val adultPresets: List<VoiceStylePreset> get() = entries.filter { it.isAdult }

        /**
         * Returns this preset if allowed, otherwise the default preset.
         * Used as a defence in depth wherever a preset is read from storage or a request.
         */
        fun gate(preset: VoiceStylePreset, adultConfirmed: Boolean): VoiceStylePreset =
            if (preset.isAdult && !adultConfirmed) FRIENDLY_COMPANION else preset

        /**
         * Short English style direction for Gemini 3.x TTS (`speechMetadata.style`). Resolves a preset from
         * its [stylePrompt] and falls back to a truncated copy of the given prompt for custom prompts.
         */
        fun geminiStyleFor(stylePrompt: String): String {
            // A voice hint (see GeminiLiveVoice) is appended after VOICE_HINT_MARK
            val markAt = stylePrompt.indexOf(VOICE_HINT_MARK)
            if (markAt >= 0) {
                val base = geminiStyleFor(stylePrompt.substring(0, markAt))
                val hint = stylePrompt.substring(markAt + VOICE_HINT_MARK.length).trim()
                return if (hint.isEmpty()) base else "$base, $hint".take(260)
            }
            val trimmed = stylePrompt.trim()
            entries.firstOrNull { it.stylePrompt == trimmed }?.geminiStyle?.takeIf { it.isNotBlank() }
                ?.let { return it }
            return trimmed.take(200)
        }

        /** Separator between a preset's style prompt and the optional voice hint of a [GeminiLiveVoice]. */
        const val VOICE_HINT_MARK = "\n#voice: "

        /** Appends the voice hint of [voice] (if any) to [stylePrompt]. */
        fun withVoiceHint(stylePrompt: String, voice: GeminiLiveVoice?): String =
            if (voice == null || voice.styleHint.isBlank()) stylePrompt
            else stylePrompt + VOICE_HINT_MARK + voice.styleHint

        fun fromVoiceOrName(value: String?): VoiceStylePreset {
            val v = value?.trim()
            entries.firstOrNull { it.name == v || it.presetName == v }?.let { return it }
            return when (v) {
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
    val pitch: Float = 1.0f,
    /** True when the caller (e.g. a navigation app) asked for navigation-guidance audio. */
    val navigationRequested: Boolean = false,
    /** Gemini-app-style voice chosen explicitly (e.g. for a preview). Null = use the saved setting. */
    val liveVoice: GeminiLiveVoice? = null,
    /** Preview of a voice: never treated as navigation, ignores the saved live voice when [liveVoice] is null. */
    val previewOnly: Boolean = false
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
