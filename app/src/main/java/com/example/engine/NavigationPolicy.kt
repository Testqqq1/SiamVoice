package com.example.engine

import android.media.AudioAttributes
import android.os.Build
import android.os.Bundle
import com.example.data.AppSettings
import com.example.model.PoliteEndingStyle
import com.example.model.SynthesisRequestData
import com.example.model.VoiceStylePreset
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.withTimeoutOrNull

/** Progress of the "download navigation voices" action. */
data class NavDownloadState(
    val running: Boolean = false,
    val total: Int = 0,
    val done: Int = 0,
    val failed: Int = 0,
    val message: String = ""
)

/**
 * Pure decision logic for the low-latency navigation mode.
 */
object NavigationPolicy {
    const val DEFAULT_LATENCY_BUDGET_MS = 500L
    val LATENCY_BUDGET_OPTIONS_MS = listOf(300L, 500L, 800L, 1200L)

    /** Optional explicit extra a caller can put into the TTS request params. */
    const val EXTRA_NAVIGATION = "siamvoice.navigation"

    /** Hidden `TextToSpeech.Engine.KEY_PARAM_AUDIO_ATTRIBUTES`-style key used by the framework. */
    private const val PARAM_AUDIO_ATTRIBUTES = "audioAttributes"

    /** Navigation mode is on when the user enabled it or the calling app asked for navigation audio. */
    fun isActive(settingEnabled: Boolean, callerRequested: Boolean): Boolean =
        settingEnabled || callerRequested

    /**
     * Forces the clear Navigation style: ignores whatever style (including 18+) was requested,
     * and keeps ครับ/ค่ะ off unless the user opted in.
     */
    fun applyNavigationStyle(request: SynthesisRequestData, settings: AppSettings): SynthesisRequestData {
        val nav = VoiceStylePreset.NAVIGATION
        return request.copy(
            voicePreset = nav,
            geminiVoice = nav.voiceName,
            stylePrompt = nav.stylePrompt,
            politeStyle = if (settings.navigationPoliteEnding) request.politeStyle else PoliteEndingStyle.OFF,
            navigationRequested = true
        )
    }

    /** True when the latency budget is spent and Gemini audio is still not ready. */
    fun shouldFallbackNow(elapsedMs: Long, budgetMs: Long, cloudReady: Boolean): Boolean =
        !cloudReady && elapsedMs >= budgetMs

    /**
     * Waits for [deferred] at most [budgetMs]. Returns null when the budget is exceeded; the deferred
     * keeps running (so Gemini can still finish in the background and warm the cache).
     */
    suspend fun <T> awaitWithin(deferred: Deferred<T>, budgetMs: Long): T? =
        withTimeoutOrNull(budgetMs) { deferred.await() }

    /**
     * Best-effort detection of a navigation caller from the TTS request params: the calling app's
     * AudioAttributes usage is USAGE_ASSISTANCE_NAVIGATION_GUIDANCE, or the explicit extra is set.
     */
    fun isNavigationRequest(params: Bundle?): Boolean {
        if (params == null) return false
        return try {
            if (params.getBoolean(EXTRA_NAVIGATION, false)) return true
            val attrs: AudioAttributes? = if (Build.VERSION.SDK_INT >= 33) {
                params.getParcelable(PARAM_AUDIO_ATTRIBUTES, AudioAttributes::class.java)
            } else {
                @Suppress("DEPRECATION")
                params.getParcelable(PARAM_AUDIO_ATTRIBUTES)
            }
            attrs?.usage == AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE
        } catch (e: Exception) {
            false
        }
    }
}
