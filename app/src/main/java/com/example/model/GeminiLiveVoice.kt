package com.example.model

/**
 * Voice names from the Gemini app's voice picker ("Gemini's voice" / Gemini Live) and the Gemini API
 * prebuilt voice each of them is played with in SiamVoice.
 *
 * SOURCES
 * - Names, tone and pitch descriptions (e.g. "Orbit: Energetic • Deeper voice") are the labels Google
 *   shows in the Gemini app, as reported by Android Authority (Aug 2024), 9to5Google (Sep 2024 and
 *   27 May 2026) and Android Central. The May 2026 redesign removed the descriptions from the app and
 *   replaced Nova and Lyra with "Flare" and "Glow" (no published description, so not included).
 * - Gemini API prebuilt voices (30 names), their one-word descriptions and gender come from the Gemini
 *   Live API / Cloud Gemini-TTS documentation (Firebase AI Logic "Configuration options for the Live API",
 *   Google Cloud "Gemini-TTS" voice options).
 *
 * MAPPING = APPROXIMATION
 * Google does NOT publish a mapping between the Gemini app voice names and the API voices. The names
 * (Orbit, Vega, ...) are not valid `voiceName` values in the Gemini API / Live API, and Google has said it
 * deliberately makes no statements about gender for the app voices. Therefore every [apiVoice] below is
 * the closest API voice chosen from the DOCUMENTED characteristics only: the app's pitch label
 * (deeper / mid-range / higher) and tone label (energetic / engaged / bright / calm), matched against the
 * API's description word and gender. [styleHint] adds a short English delivery hint (sent through the
 * Gemini TTS style field) to nudge the tone. The British accent of Capella cannot be reproduced for Thai.
 * Every entry has [isApproximation] = true.
 */
enum class GeminiLiveVoice(
    val displayName: String,
    /** Label shown in the Gemini app (English), e.g. "Energetic • Deeper voice". */
    val appLabel: String,
    /** Thai version of the label for this app's UI. */
    val thaiLabel: String,
    /** Gemini API prebuilt voice used to synthesize (documented API name). */
    val apiVoice: String,
    /** Documented API description + gender of [apiVoice], for display. */
    val apiTraits: String,
    /** Short English style hint appended to the preset's style (empty = none). */
    val styleHint: String,
    val isApproximation: Boolean = true
) {
    ORBIT("Orbit", "Energetic • Deeper voice", "มีพลัง • เสียงทุ้ม", "Fenrir", "Excitable, ชาย", "energetic, deeper-pitched delivery"),
    PEGASUS("Pegasus", "Engaged • Deeper voice", "ตั้งใจฟัง เป็นกันเอง • เสียงทุ้ม", "Charon", "Informative, ชาย", "engaged, attentive, deeper-pitched delivery"),
    URSA("Ursa", "Engaged • Mid-range voice", "ตั้งใจฟัง เป็นกันเอง • เสียงกลาง", "Achird", "Friendly, ชาย", "engaged, friendly, mid-range pitch"),
    NOVA("Nova", "Calm • Mid-range voice", "สงบ นุ่มนวล • เสียงกลาง", "Vindemiatrix", "Gentle, หญิง", "calm, composed, mid-range pitch"),
    VEGA("Vega", "Bright • Higher voice", "สดใส • เสียงสูง", "Zephyr", "Bright, หญิง", "bright, higher-pitched delivery"),
    CAPELLA("Capella", "British Accent • Higher voice", "สำเนียงอังกฤษ • เสียงสูง", "Aoede", "Breezy, หญิง", "polished, crisp, higher-pitched delivery"),
    ECLIPSE("Eclipse", "Energetic • Mid-range voice", "มีพลัง • เสียงกลาง", "Laomedeia", "Upbeat, หญิง", "energetic, upbeat, mid-range pitch"),
    DIPPER("Dipper", "Engaged • Deeper voice", "ตั้งใจฟัง เป็นกันเอง • เสียงทุ้ม", "Rasalgethi", "Informative, ชาย", "engaged, warm, deeper-pitched delivery"),
    ORION("Orion", "Bright • Deeper voice", "สดใส • เสียงทุ้ม", "Puck", "Upbeat, ชาย", "bright, upbeat, deeper-pitched delivery"),
    LYRA("Lyra", "Bright • Higher voice", "สดใส • เสียงสูง", "Autonoe", "Bright, หญิง", "bright, light, higher-pitched delivery");

    /** One-line Thai description for pickers. */
    val thaiDescription: String
        get() = "$thaiLabel  (≈ API: $apiVoice – $apiTraits)"

    companion object {
        /** Persisted value for "use the voice of the selected style" is the empty string / null. */
        fun fromId(id: String?): GeminiLiveVoice? {
            val v = id?.trim().orEmpty()
            if (v.isEmpty()) return null
            return entries.firstOrNull { it.name.equals(v, ignoreCase = true) || it.displayName.equals(v, ignoreCase = true) }
        }

        /** The 30 prebuilt voices of the Gemini API / Live API (documented). */
        val ALL_API_VOICES: Set<String> = setOf(
            "Zephyr", "Puck", "Charon", "Kore", "Fenrir", "Leda", "Orus", "Aoede", "Callirrhoe", "Autonoe",
            "Enceladus", "Iapetus", "Umbriel", "Algieba", "Despina", "Erinome", "Algenib", "Rasalgethi",
            "Laomedeia", "Achernar", "Alnilam", "Schedar", "Gacrux", "Pulcherrima", "Achird",
            "Zubenelgenubi", "Vindemiatrix", "Sadachbia", "Sadaltager", "Sulafat"
        )
    }
}
