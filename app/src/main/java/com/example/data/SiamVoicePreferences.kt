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
    val modelPreference: GeminiModelPreference = GeminiModelPreference.FLASH_PRIMARY,
    val verifiedFlashModelId: String = "gemini-3.8-flash-tts",
    val verifiedFlashLiteModelId: String = "gemini-3.8-flash-lite-tts"
)

class SiamVoicePreferences(context: Context) {
    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences("siamvoice_prefs", Context.MODE_PRIVATE)

    private val _settingsFlow = MutableStateFlow(loadSettings())
    val settingsFlow: StateFlow<AppSettings> = _settingsFlow.asStateFlow()

    fun getSettings(): AppSettings = loadSettings()

    private fun loadSettings(): AppSettings {
        val styleName = prefs.getString("polite_style", PoliteEndingStyle.AUTO_NATURAL.name)
        val politeStyle = PoliteEndingStyle.fromStoredName(styleName)

        val savedPresetName = prefs.getString("voice_preset", VoiceStylePreset.FRIENDLY_COMPANION.name)
        val preset = VoiceStylePreset.fromVoiceOrName(savedPresetName)

        val savedVoiceName = prefs.getString("gemini_voice", preset.voiceName)
        val voiceOption = GeminiVoiceOption.fromVoiceName(savedVoiceName)

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
            geminiVoice = voiceOption.voiceName,
            politeEndingStyle = politeStyle,
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

    fun updateVoicePreset(preset: VoiceStylePreset) {
        prefs.edit()
            .putString("voice_preset", preset.name)
            .putString("gemini_voice", preset.voiceName)
            .commit()
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
