package com.example.data

import android.content.Context
import android.content.SharedPreferences
import com.example.model.GeminiModelPreference
import com.example.model.GeminiVoiceOption
import com.example.model.PoliteEndingStyle
import com.example.model.VoiceStylePreset
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class AppSettings(
    val autoFallbackEnabled: Boolean = true,
    val preferredAndroidEngine: String = "",
    val fallbackVoice: String = "th-TH",
    val fallbackSpeechRate: Float = 1.0f,
    val speechPitch: Float = 1.0f,
    val voicePreset: VoiceStylePreset = VoiceStylePreset.FRIENDLY_COMPANION,
    val geminiVoice: String = VoiceStylePreset.FRIENDLY_COMPANION.voiceName,
    val politeEndingStyle: PoliteEndingStyle = PoliteEndingStyle.AUTO_NATURAL,
    /** True once the user confirmed in the one-time dialog that they are 18 or older. Off by default. */
    val adultContentConfirmed: Boolean = false,
    /** Navigation mode: forces the clear Navigation style, low-latency path and navigation audio focus. */
    val navigationMode: Boolean = false,
    /** If Gemini audio is not ready within this budget, the Android system TTS speaks immediately. */
    val navigationLatencyBudgetMs: Long = 500L,
    /** Navigation mode keeps ครับ/ค่ะ off (shorter) unless the user opts in. */
    val navigationPoliteEnding: Boolean = false,
    val modelPreference: GeminiModelPreference = GeminiModelPreference.FLASH_PRIMARY,
    val verifiedFlashModelId: String = "gemini-3.8-flash-tts",
    val verifiedFlashLiteModelId: String = "gemini-3.8-flash-lite-tts"
)

/** Polite ending actually applied: 18+ presets override the user's choice (they switch it off). */
val AppSettings.effectivePoliteEndingStyle: PoliteEndingStyle
    get() = voicePreset.resolvePoliteEnding(politeEndingStyle)

class SiamVoicePreferences(context: Context) {
    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences("siamvoice_prefs", Context.MODE_PRIVATE)

    private val _settingsFlow = MutableStateFlow(loadSettings())
    val settingsFlow: StateFlow<AppSettings> = _settingsFlow.asStateFlow()

    companion object {
        const val KEY_ADULT_CONFIRMED = "adult_content_confirmed"
    }

    fun getSettings(): AppSettings = loadSettings()

    private fun loadSettings(): AppSettings {
        val styleName = prefs.getString("polite_style", PoliteEndingStyle.AUTO_NATURAL.name)
        val politeStyle = PoliteEndingStyle.fromStoredName(styleName)

        val adultConfirmed = prefs.getBoolean(KEY_ADULT_CONFIRMED, false)

        val savedPresetName = prefs.getString("voice_preset", VoiceStylePreset.FRIENDLY_COMPANION.name)
        // 18+ presets are never active without the age confirmation, even if stored in prefs.
        val preset = VoiceStylePreset.gate(VoiceStylePreset.fromVoiceOrName(savedPresetName), adultConfirmed)

        val savedVoiceName = prefs.getString("gemini_voice", preset.voiceName)?.trim()
        // Accept the classic Puck/Orus choices and any (permitted) preset voice; otherwise use the preset's voice.
        val resolvedVoice = GeminiVoiceOption.entries
            .firstOrNull { it.voiceName.equals(savedVoiceName, ignoreCase = true) }?.voiceName
            ?: VoiceStylePreset.entries
                .firstOrNull {
                    it.voiceName.equals(savedVoiceName, ignoreCase = true) && (!it.isAdult || adultConfirmed)
                }?.voiceName
            ?: preset.voiceName

        val modelPref = GeminiModelPreference.fromStoredName(
            prefs.getString("gemini_model_pref", GeminiModelPreference.FLASH_PRIMARY.name)
        )

        val verifiedFlash = prefs.getString("verified_flash_model_id", "gemini-3.8-flash-tts")
            ?.takeIf { it.isNotBlank() } ?: "gemini-3.8-flash-tts"
        val verifiedFlashLite = prefs.getString("verified_flash_lite_model_id", "gemini-3.8-flash-lite-tts")
            ?.takeIf { it.isNotBlank() } ?: "gemini-3.8-flash-lite-tts"

        return AppSettings(
            autoFallbackEnabled = prefs.getBoolean("auto_fallback_enabled", true),
            preferredAndroidEngine = prefs.getString("preferred_android_engine", "").orEmpty(),
            fallbackVoice = prefs.getString("fallback_voice", "th-TH").orEmpty(),
            fallbackSpeechRate = prefs.getFloat("fallback_speech_rate", 1.0f).coerceIn(0.5f, 2.0f),
            speechPitch = prefs.getFloat("speech_pitch", 1.0f).coerceIn(0.5f, 2.0f),
            voicePreset = preset,
            geminiVoice = resolvedVoice,
            politeEndingStyle = politeStyle,
            adultContentConfirmed = adultConfirmed,
            navigationMode = prefs.getBoolean("navigation_mode", false),
            navigationLatencyBudgetMs = prefs.getLong("navigation_latency_budget_ms", 500L).coerceIn(100L, 3000L),
            navigationPoliteEnding = prefs.getBoolean("navigation_polite_ending", false),
            modelPreference = modelPref,
            verifiedFlashModelId = verifiedFlash,
            verifiedFlashLiteModelId = verifiedFlashLite
        )
    }

    fun updateAutoFallback(enabled: Boolean) {
        prefs.edit().putBoolean("auto_fallback_enabled", enabled).commit()
        _settingsFlow.value = loadSettings()
    }

    fun updatePreferredAndroidEngine(pkg: String) {
        prefs.edit().putString("preferred_android_engine", pkg).commit()
        _settingsFlow.value = loadSettings()
    }

    fun updateFallbackSpeechRate(rate: Float) {
        prefs.edit().putFloat("fallback_speech_rate", rate.coerceIn(0.5f, 2.0f)).commit()
        _settingsFlow.value = loadSettings()
    }

    fun updateSpeechPitch(pitch: Float) {
        prefs.edit().putFloat("speech_pitch", pitch.coerceIn(0.5f, 2.0f)).commit()
        _settingsFlow.value = loadSettings()
    }

    /**
     * Selects a voice style preset. 18+ presets are rejected (returns false) until
     * [confirmAdultContent] has been called.
     */
    fun updateVoicePreset(preset: VoiceStylePreset): Boolean {
        if (preset.isAdult && !prefs.getBoolean(KEY_ADULT_CONFIRMED, false)) return false
        prefs.edit()
            .putString("voice_preset", preset.name)
            .putString("gemini_voice", preset.voiceName)
            .commit()
        _settingsFlow.value = loadSettings()
        return true
    }

    fun updateNavigationMode(enabled: Boolean) {
        prefs.edit().putBoolean("navigation_mode", enabled).commit()
        _settingsFlow.value = loadSettings()
    }

    fun updateNavigationLatencyBudget(ms: Long) {
        prefs.edit().putLong("navigation_latency_budget_ms", ms.coerceIn(100L, 3000L)).commit()
        _settingsFlow.value = loadSettings()
    }

    fun updateNavigationPoliteEnding(enabled: Boolean) {
        prefs.edit().putBoolean("navigation_polite_ending", enabled).commit()
        _settingsFlow.value = loadSettings()
    }

    /** Stores the one-time "I am 18 or older" confirmation that unlocks the 18+ presets. */
    fun confirmAdultContent() {
        prefs.edit().putBoolean(KEY_ADULT_CONFIRMED, true).commit()
        _settingsFlow.value = loadSettings()
    }

    /** Revokes the 18+ confirmation and resets any active 18+ preset to the default one. */
    fun revokeAdultContent() {
        val editor = prefs.edit().putBoolean(KEY_ADULT_CONFIRMED, false)
        if (VoiceStylePreset.fromVoiceOrName(prefs.getString("voice_preset", null)).isAdult) {
            editor.putString("voice_preset", VoiceStylePreset.FRIENDLY_COMPANION.name)
                .putString("gemini_voice", VoiceStylePreset.FRIENDLY_COMPANION.voiceName)
        }
        editor.commit()
        _settingsFlow.value = loadSettings()
    }

    fun updateGeminiVoice(voice: String) {
        val option = GeminiVoiceOption.fromVoiceName(voice)
        prefs.edit()
            .putString("gemini_voice", option.voiceName)
            .commit()
        _settingsFlow.value = loadSettings()
    }

    fun updatePoliteEndingStyle(style: PoliteEndingStyle) {
        prefs.edit().putString("polite_style", style.name).commit()
        _settingsFlow.value = loadSettings()
    }

    fun updateModelPreference(preference: GeminiModelPreference) {
        prefs.edit().putString("gemini_model_pref", preference.name).commit()
        _settingsFlow.value = loadSettings()
    }

    fun updateVerifiedModelIds(flashModelId: String, flashLiteModelId: String) {
        prefs.edit()
            .putString("verified_flash_model_id", flashModelId)
            .putString("verified_flash_lite_model_id", flashLiteModelId)
            .commit()
        _settingsFlow.value = loadSettings()
    }
}
