package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.cache.VoiceClipCache
import com.example.data.SiamVoicePreferences
import com.example.data.effectivePoliteEndingStyle
import com.example.engine.SiamVoiceTtsSynthesizer
import com.example.model.PoliteEndingStyle
import com.example.model.SynthesisRequestData
import com.example.model.VoiceStylePreset
import com.example.network.GeminiTtsClient
import com.example.processor.ThaiPoliteEndingProcessor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Tests for voice style presets (Grok-style personalities): preset mapping, 18+ gating,
 * polite-ending override and cache-key separation.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class VoiceStylePresetTest {

    private lateinit var context: Context
    private lateinit var preferences: SiamVoicePreferences

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("siamvoice_prefs", Context.MODE_PRIVATE).edit().clear().commit()
        preferences = SiamVoicePreferences(context)
    }

    // --- Preset mapping -------------------------------------------------------------------------

    @Test
    fun `preset catalog has default, all-ages and 18+ presets`() {
        assertEquals(VoiceStylePreset.FRIENDLY_COMPANION, VoiceStylePreset.entries.first())
        assertEquals(
            setOf(VoiceStylePreset.UNHINGED, VoiceStylePreset.SEXY),
            VoiceStylePreset.adultPresets.toSet()
        )
        assertTrue(VoiceStylePreset.allAgesPresets.containsAll(
            listOf(
                VoiceStylePreset.FRIENDLY_COMPANION,
                VoiceStylePreset.PERSONAL_ASSISTANT,
                VoiceStylePreset.ROMANTIC,
                VoiceStylePreset.STORYTELLER,
                VoiceStylePreset.CONSPIRACY
            )
        ))
        assertTrue(VoiceStylePreset.allAgesPresets.none { it.isAdult })
    }

    @Test
    fun `every preset has a supported Gemini voice, a style prompt and a rewrite instruction`() {
        VoiceStylePreset.entries.forEach { preset ->
            assertTrue("${preset.name} voice", preset.voiceName in GeminiTtsClient.SUPPORTED_VOICES)
            assertTrue("${preset.name} prompt", preset.stylePrompt.isNotBlank())
            assertTrue("${preset.name} rewrite", preset.rewriteInstruction.isNotBlank())
            assertTrue("${preset.name} rate", preset.speechRateHint in 0.5f..2.0f)
            assertTrue("${preset.name} pitch", preset.pitchHint in 0.5f..2.0f)
        }
        // Style prompts must be distinct so that they steer Gemini differently.
        assertEquals(
            VoiceStylePreset.entries.size,
            VoiceStylePreset.entries.map { it.stylePrompt }.toSet().size
        )
    }

    @Test
    fun `no preset references minors`() {
        val banned = listOf("child", "kid", "minor", "teen", "underage", "school", "เด็ก", "นักเรียน", "ผู้เยาว์")
        VoiceStylePreset.entries.forEach { preset ->
            val text = (preset.stylePrompt + preset.thaiDescription).lowercase()
            banned.forEach { word -> assertFalse("${preset.name} contains '$word'", text.contains(word)) }
        }
        // Adult presets must explicitly forbid minors in the rewrite instruction.
        VoiceStylePreset.adultPresets.forEach {
            assertTrue(it.rewriteInstruction.contains("ผู้เยาว์"))
        }
    }

    @Test
    fun `default preset keeps the original behaviour`() {
        val d = VoiceStylePreset.FRIENDLY_COMPANION
        assertEquals("Puck", d.voiceName)
        assertEquals(1.0f, d.speechRateHint, 0f)
        assertEquals(1.0f, d.pitchHint, 0f)
        assertEquals(null, d.politeEndingOverride)
        assertEquals(PoliteEndingStyle.KRUB, d.resolvePoliteEnding(PoliteEndingStyle.KRUB))
    }

    @Test
    fun `fromVoiceOrName maps stored names and legacy values`() {
        VoiceStylePreset.entries.forEach {
            assertEquals(it, VoiceStylePreset.fromVoiceOrName(it.name))
            assertEquals(it, VoiceStylePreset.fromVoiceOrName(it.presetName))
        }
        assertEquals(VoiceStylePreset.PERSONAL_ASSISTANT, VoiceStylePreset.fromVoiceOrName("Orus"))
        assertEquals(VoiceStylePreset.FRIENDLY_COMPANION, VoiceStylePreset.fromVoiceOrName(null))
        assertEquals(VoiceStylePreset.FRIENDLY_COMPANION, VoiceStylePreset.fromVoiceOrName("???"))
    }

    // --- 18+ gating -------------------------------------------------------------------------------

    @Test
    fun `18+ confirmation is off by default and adult presets are rejected`() {
        assertFalse(preferences.getSettings().adultContentConfirmed)

        assertFalse(preferences.updateVoicePreset(VoiceStylePreset.UNHINGED))
        assertFalse(preferences.updateVoicePreset(VoiceStylePreset.SEXY))
        assertEquals(VoiceStylePreset.FRIENDLY_COMPANION, preferences.getSettings().voicePreset)

        // All-ages presets always work.
        assertTrue(preferences.updateVoicePreset(VoiceStylePreset.ROMANTIC))
        assertEquals(VoiceStylePreset.ROMANTIC, preferences.getSettings().voicePreset)
        assertEquals("Algieba", preferences.getSettings().geminiVoice)
    }

    @Test
    fun `after one-time confirmation adult presets can be selected and persist`() {
        preferences.confirmAdultContent()
        assertTrue(preferences.getSettings().adultContentConfirmed)

        assertTrue(preferences.updateVoicePreset(VoiceStylePreset.SEXY))
        val reloaded = SiamVoicePreferences(context).getSettings()
        assertTrue(reloaded.adultContentConfirmed)
        assertEquals(VoiceStylePreset.SEXY, reloaded.voicePreset)
        assertEquals("Enceladus", reloaded.geminiVoice)
    }

    @Test
    fun `stored adult preset is ignored when the age confirmation is missing`() {
        context.getSharedPreferences("siamvoice_prefs", Context.MODE_PRIVATE).edit()
            .putString("voice_preset", VoiceStylePreset.UNHINGED.name)
            .putString("gemini_voice", VoiceStylePreset.UNHINGED.voiceName)
            .commit()
        val settings = SiamVoicePreferences(context).getSettings()
        assertEquals(VoiceStylePreset.FRIENDLY_COMPANION, settings.voicePreset)
        assertEquals("Puck", settings.geminiVoice)
    }

    @Test
    fun `revoking confirmation resets an active adult preset`() {
        preferences.confirmAdultContent()
        preferences.updateVoicePreset(VoiceStylePreset.UNHINGED)
        preferences.revokeAdultContent()

        val settings = preferences.getSettings()
        assertFalse(settings.adultContentConfirmed)
        assertEquals(VoiceStylePreset.FRIENDLY_COMPANION, settings.voicePreset)
        assertEquals("Puck", settings.geminiVoice)
    }

    @Test
    fun `gate helper downgrades adult presets only without confirmation`() {
        assertEquals(VoiceStylePreset.FRIENDLY_COMPANION, VoiceStylePreset.gate(VoiceStylePreset.SEXY, false))
        assertEquals(VoiceStylePreset.SEXY, VoiceStylePreset.gate(VoiceStylePreset.SEXY, true))
        assertEquals(VoiceStylePreset.ROMANTIC, VoiceStylePreset.gate(VoiceStylePreset.ROMANTIC, false))
    }

    @Test
    fun `synthesizer downgrades adult requests when age is not confirmed`() {
        val synthesizer = SiamVoiceTtsSynthesizer(context = context, preferences = preferences)
        val request = SynthesisRequestData(
            text = "สวัสดี",
            voicePreset = VoiceStylePreset.SEXY,
            geminiVoice = VoiceStylePreset.SEXY.voiceName,
            stylePrompt = VoiceStylePreset.SEXY.stylePrompt
        )

        val blocked = synthesizer.enforceAdultGate(request, adultConfirmed = false)
        assertEquals(VoiceStylePreset.FRIENDLY_COMPANION, blocked.voicePreset)
        assertEquals("Puck", blocked.geminiVoice)
        assertEquals(VoiceStylePreset.FRIENDLY_COMPANION.stylePrompt, blocked.stylePrompt)

        val allowed = synthesizer.enforceAdultGate(request, adultConfirmed = true)
        assertEquals(request, allowed)
    }

    // --- Polite ending (ครับ/ค่ะ) ---------------------------------------------------------------

    @Test
    fun `adult presets switch the polite ending processor off`() {
        VoiceStylePreset.adultPresets.forEach { preset ->
            assertEquals(PoliteEndingStyle.OFF, preset.politeEndingOverride)
            PoliteEndingStyle.entries.forEach { userChoice ->
                assertEquals(PoliteEndingStyle.OFF, preset.resolvePoliteEnding(userChoice))
            }
            val spoken = ThaiPoliteEndingProcessor.process(
                "อีก 300 เมตร เลี้ยวซ้าย",
                preset.resolvePoliteEnding(PoliteEndingStyle.AUTO_NATURAL)
            )
            assertEquals("อีก 300 เมตร เลี้ยวซ้าย", spoken)
        }
    }

    @Test
    fun `all-ages presets keep the user's polite ending choice`() {
        VoiceStylePreset.allAgesPresets.forEach { preset ->
            assertEquals(PoliteEndingStyle.NA_KRUB, preset.resolvePoliteEnding(PoliteEndingStyle.NA_KRUB))
        }
        preferences.updatePoliteEndingStyle(PoliteEndingStyle.KRUB_PHOM)
        assertEquals(PoliteEndingStyle.KRUB_PHOM, preferences.getSettings().effectivePoliteEndingStyle)

        preferences.confirmAdultContent()
        preferences.updateVoicePreset(VoiceStylePreset.UNHINGED)
        val settings = preferences.getSettings()
        assertEquals(PoliteEndingStyle.KRUB_PHOM, settings.politeEndingStyle) // preference untouched
        assertEquals(PoliteEndingStyle.OFF, settings.effectivePoliteEndingStyle)
    }

    // --- Cache keys -------------------------------------------------------------------------------

    @Test
    fun `cache keys differ per voice style even when everything else is equal`() {
        val cache = VoiceClipCache(context)
        fun key(style: String) = cache.computeKey(
            model = GeminiTtsClient.MODEL_FLASH,
            voice = "Puck",
            stylePrompt = "same prompt",
            politeStyle = PoliteEndingStyle.OFF,
            speechRate = 1.0f,
            pitch = 1.0f,
            text = "สวัสดี",
            voiceStyle = style
        )
        val keys = VoiceStylePreset.entries.map { key(it.name) }
        assertEquals(keys.size, keys.toSet().size)
        assertEquals(key("SEXY"), key("SEXY"))
        assertNotEquals(key(""), key("SEXY"))
    }

    @Test
    fun `cached clips are not shared between styles`() {
        val cache = VoiceClipCache(context)
        val result = com.example.model.SynthesisResult(
            audioData = byteArrayOf(1, 2, 3, 4),
            engineUsed = com.example.model.TtsEngineType.GEMINI_FLASH,
            processedText = "สวัสดี"
        )
        cache.put(
            modelName = "m", voiceName = "Puck", text = "สวัสดี", result = result,
            stylePrompt = "p", voiceStyle = VoiceStylePreset.FRIENDLY_COMPANION.name
        )
        assertNotNull(
            cache.get("m", "Puck", "สวัสดี", stylePrompt = "p", voiceStyle = VoiceStylePreset.FRIENDLY_COMPANION.name)
        )
        assertEquals(
            null,
            cache.get("m", "Puck", "สวัสดี", stylePrompt = "p", voiceStyle = VoiceStylePreset.SEXY.name)
        )
    }
}
