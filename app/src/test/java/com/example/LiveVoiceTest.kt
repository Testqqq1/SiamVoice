package com.example

import android.content.Context
import android.media.AudioAttributes
import android.os.Bundle
import android.util.Base64
import androidx.test.core.app.ApplicationProvider
import com.example.audio.DecodedPcmAudio
import com.example.cache.VoiceClipCache
import com.example.data.SiamVoicePreferences
import com.example.engine.AndroidSystemTtsEngine
import com.example.engine.NavigationPolicy
import com.example.engine.SiamVoiceTtsSynthesizer
import com.example.model.GeminiLiveVoice
import com.example.model.PoliteEndingStyle
import com.example.model.SynthesisRequestData
import com.example.model.SynthesisResult
import com.example.model.TtsEngineType
import com.example.model.VoiceStylePreset
import com.example.network.GeminiHttpResponse
import com.example.network.GeminiTtsClient
import com.example.network.GeminiTtsTransport
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LiveVoiceTest {

    private lateinit var context: Context
    private lateinit var preferences: SiamVoicePreferences
    private lateinit var cache: VoiceClipCache
    private val requests = mutableListOf<org.json.JSONObject>()

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("siamvoice_prefs", Context.MODE_PRIVATE).edit().clear().commit()
        preferences = SiamVoicePreferences(context)
        cache = VoiceClipCache(context)
        cache.clear()
        cache.clearPinned()
        requests.clear()
    }

    private fun geminiJson(): String {
        val b64 = Base64.encodeToString(ByteArray(2400) { 3 }, Base64.NO_WRAP)
        return """{"candidates":[{"content":{"parts":[{"inlineData":{"mimeType":"audio/L16;codec=pcm;rate=24000","data":"$b64"}}]}}]}"""
    }

    private fun synthesizer() = object : SiamVoiceTtsSynthesizer(
        context = context,
        preferences = preferences,
        voiceClipCache = cache,
        geminiClient = GeminiTtsClient(context = context, transport = GeminiTtsTransport { _, _, json ->
            requests.add(json)
            GeminiHttpResponse(200, geminiJson())
        }),
        androidFallbackEngine = object : AndroidSystemTtsEngine(context) {
            override suspend fun synthesizeToFile(
                text: String, preferredEnginePackage: String?, speechRate: Float, pitch: Float, timeoutMs: Long
            ): DecodedPcmAudio = DecodedPcmAudio(ByteArray(2400) { 9 }, 24000, 1)
        }
    ) {
        override fun isNetworkAvailable(): Boolean = true
    }

    private fun voiceNameOf(json: org.json.JSONObject): String =
        json.getJSONObject("generationConfig").getJSONObject("speechConfig").getJSONObject("voiceConfig")
            .getJSONObject("prebuiltVoiceConfig").getString("voiceName")

    @Test
    fun `picker offers the ten Gemini app voices each mapped to a documented API voice`() {
        assertEquals(
            listOf("Orbit", "Pegasus", "Ursa", "Nova", "Vega", "Capella", "Eclipse", "Dipper", "Orion", "Lyra"),
            GeminiLiveVoice.entries.map { it.displayName }
        )
        assertEquals(30, GeminiLiveVoice.ALL_API_VOICES.size)
        GeminiLiveVoice.entries.forEach {
            assertTrue("${it.name} -> ${it.apiVoice}", it.apiVoice in GeminiLiveVoice.ALL_API_VOICES)
            assertTrue(it.apiVoice in GeminiTtsClient.SUPPORTED_VOICES)
            assertTrue("mapping is unofficial", it.isApproximation)
            assertTrue(it.styleHint.isNotBlank())
            assertTrue(it.thaiLabel.isNotBlank())
        }
        // every voice has its own API voice, so previews differ even before the style hint
        assertEquals(GeminiLiveVoice.entries.size, GeminiLiveVoice.entries.map { it.apiVoice }.toSet().size)
    }

    @Test
    fun `fromId accepts names and empty means default`() {
        assertEquals(GeminiLiveVoice.ORBIT, GeminiLiveVoice.fromId("ORBIT"))
        assertEquals(GeminiLiveVoice.VEGA, GeminiLiveVoice.fromId("vega"))
        assertNull(GeminiLiveVoice.fromId(""))
        assertNull(GeminiLiveVoice.fromId(null))
        assertNull(GeminiLiveVoice.fromId("Flare"))
    }

    @Test
    fun `selection persists`() {
        assertNull(preferences.getSettings().liveVoice)
        preferences.updateLiveVoice(GeminiLiveVoice.PEGASUS)
        assertEquals(GeminiLiveVoice.PEGASUS, SiamVoicePreferences(context).getSettings().liveVoice)
        preferences.updateLiveVoice(null)
        assertNull(SiamVoicePreferences(context).getSettings().liveVoice)
    }

    @Test
    fun `voice hint is merged into the Gemini style`() {
        val base = VoiceStylePreset.FRIENDLY_COMPANION
        val withHint = VoiceStylePreset.withVoiceHint(base.stylePrompt, GeminiLiveVoice.ORBIT)
        val style = VoiceStylePreset.geminiStyleFor(withHint)
        assertTrue(style.startsWith(base.geminiStyle))
        assertTrue(style.contains(GeminiLiveVoice.ORBIT.styleHint))
        assertEquals(base.geminiStyle, VoiceStylePreset.geminiStyleFor(VoiceStylePreset.withVoiceHint(base.stylePrompt, null)))
        // legacy inline prompt must not contain the raw marker
        assertFalse(GeminiTtsClient.buildStyledSpeechPrompt("สวัสดี", withHint).contains("#voice"))
    }

    @Test
    fun `saved live voice overrides the style voice and is used by Gemini`() = runBlocking {
        val s = synthesizer()
        preferences.updateLiveVoice(GeminiLiveVoice.PEGASUS)
        s.synthesize(SynthesisRequestData(text = "ทดสอบเสียง"))
        assertEquals(1, requests.size)
        assertEquals("Charon", voiceNameOf(requests[0]))
        assertTrue(requests[0].toString().contains(GeminiLiveVoice.PEGASUS.styleHint))
    }

    @Test
    fun `voice is part of the cache key`() = runBlocking {
        val s = synthesizer()
        val req = SynthesisRequestData(text = "ทดสอบแคชเสียง")

        preferences.updateLiveVoice(GeminiLiveVoice.ORBIT)
        assertFalse(s.synthesize(req).wasCached)
        assertTrue(s.synthesize(req).wasCached)
        assertEquals(1, requests.size)

        preferences.updateLiveVoice(GeminiLiveVoice.VEGA) // different voice -> new synthesis
        assertFalse(s.synthesize(req).wasCached)
        assertEquals(2, requests.size)
        assertEquals("Zephyr", voiceNameOf(requests[1]))

        preferences.updateLiveVoice(null) // style default voice -> yet another entry
        assertFalse(s.synthesize(req).wasCached)
        assertEquals(3, requests.size)
        assertEquals(VoiceStylePreset.FRIENDLY_COMPANION.voiceName, voiceNameOf(requests[2]))

        preferences.updateLiveVoice(GeminiLiveVoice.ORBIT) // back: cached again
        assertTrue(s.synthesize(req).wasCached)
        assertEquals(3, requests.size)
    }

    @Test
    fun `navigation mode ignores the live voice`() = runBlocking {
        val s = synthesizer()
        preferences.updateLiveVoice(GeminiLiveVoice.ORBIT)
        preferences.updateNavigationMode(true)
        preferences.updateNavigationLatencyBudget(3000)
        s.synthesize(SynthesisRequestData(text = "เตรียมเข้าซอยข้างหน้า"))
        assertEquals(VoiceStylePreset.NAVIGATION.voiceName, voiceNameOf(requests.single()))
        assertFalse(requests.single().toString().contains(GeminiLiveVoice.ORBIT.styleHint))
    }

    @Test
    fun `preview uses the requested voice even in navigation mode`() = runBlocking {
        val s = synthesizer()
        preferences.updateNavigationMode(true)
        s.synthesize(
            SynthesisRequestData(
                text = "สวัสดี นี่คือเสียงตัวอย่าง",
                liveVoice = GeminiLiveVoice.LYRA,
                previewOnly = true
            )
        )
        assertEquals("Autonoe", voiceNameOf(requests.single()))
    }
}
