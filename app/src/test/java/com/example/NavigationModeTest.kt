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
import com.example.engine.NavigationClipJoiner
import com.example.engine.NavigationPhrases
import com.example.engine.NavigationPolicy
import com.example.engine.SiamVoiceTtsSynthesizer
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
class NavigationModeTest {

    private lateinit var context: Context
    private lateinit var preferences: SiamVoicePreferences
    private lateinit var cache: VoiceClipCache

    private val nav = VoiceStylePreset.NAVIGATION

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("siamvoice_prefs", Context.MODE_PRIVATE).edit().clear().commit()
        preferences = SiamVoicePreferences(context)
        cache = VoiceClipCache(context)
        cache.clear()
        cache.clearPinned()
    }

    private fun pcm(vararg v: Byte) = ByteArray(2400) { v[it % v.size] }

    private fun clip(text: String, fill: Byte) = SynthesisResult(
        audioData = pcm(fill),
        engineUsed = TtsEngineType.GEMINI_FLASH,
        processedText = text
    )

    private fun geminiJson(pcmBytes: ByteArray): String {
        val b64 = Base64.encodeToString(pcmBytes, Base64.NO_WRAP)
        return """{"candidates":[{"content":{"parts":[{"inlineData":{"mimeType":"audio/L16;codec=pcm;rate=24000","data":"$b64"}}]}}]}"""
    }

    // --- Phrase list & segmentation ----------------------------------------------------------

    @Test
    fun `phrase list covers actions exits and common distances`() {
        val all = NavigationPhrases.all()
        listOf(
            "เลี้ยวซ้าย", "เลี้ยวขวา", "ชิดซ้าย", "ชิดขวา", "ตรงไป", "กลับรถ", "เข้าวงเวียน",
            "ถึงจุดหมายแล้ว", "ทางออกที่ 1", "ทางออกที่ 8",
            "อีก 50 เมตร", "อีก 100 เมตร", "อีก 200 เมตร", "อีก 300 เมตร", "อีก 500 เมตร",
            "อีก 1 กิโลเมตร", "อีก 2 กิโลเมตร"
        ).forEach { assertTrue(it, it in all) }
        assertEquals(all.size, all.toSet().size)
    }

    @Test
    fun `segmentation splits sentences made only of known phrases`() {
        assertEquals(
            listOf("อีก 200 เมตร", "เลี้ยวซ้าย"),
            NavigationPhrases.segment("อีก 200 เมตร เลี้ยวซ้าย")
        )
        assertEquals(
            listOf("อีก 500 เมตร", "เข้าวงเวียน", "ทางออกที่ 2"),
            NavigationPhrases.segment("อีก 500 เมตร เข้าวงเวียนทางออกที่ 2")
        )
        assertEquals(listOf("ถึงจุดหมายแล้ว"), NavigationPhrases.segment("  ถึงจุดหมายแล้ว. "))
        // Thai digits, punctuation and extra spaces are normalized
        assertEquals(
            listOf("อีก 300 เมตร", "ชิดขวา"),
            NavigationPhrases.segment("อีก ๓๐๐  เมตร, ชิดขวา")
        )
        assertEquals(listOf("อีก 1.5 กิโลเมตร", "ตรงไป"), NavigationPhrases.segment("อีก 1.5 กิโลเมตร ตรงไป"))
    }

    @Test
    fun `segmentation prefers the longest phrase`() {
        assertEquals(listOf("เลี้ยวซ้ายเล็กน้อย"), NavigationPhrases.segment("เลี้ยวซ้ายเล็กน้อย"))
        assertEquals(listOf("ขับตรงไป"), NavigationPhrases.segment("ขับตรงไป"))
    }

    @Test
    fun `segmentation rejects sentences with unknown parts`() {
        assertNull(NavigationPhrases.segment("อีก 250 กิโลเมตร เลี้ยวซ้าย")) // unknown distance
        assertNull(NavigationPhrases.segment("เลี้ยวซ้ายเข้าสู่ถนนสุขุมวิท"))
        assertNull(NavigationPhrases.segment("อีก 20 เมตร เลี้ยวซ้าย")) // 20 is not 200 etc.
        assertNull(NavigationPhrases.segment("อีก 1000 เมตร เลี้ยวซ้าย"))
        assertNull(NavigationPhrases.segment("   "))
    }

    // --- Pinned cache lookup & joining -------------------------------------------------------

    @Test
    fun `pinned clips persist outside the LRU cache and survive clear`() {
        cache.putPinned(nav.voiceName, "เลี้ยวซ้าย", clip("เลี้ยวซ้าย", 1))
        assertTrue(cache.hasPinned(nav.voiceName, " เลี้ยวซ้าย. "))
        assertEquals(1, cache.pinnedCount())

        cache.clear() // normal cache clear must not drop pinned navigation voices
        val reopened = VoiceClipCache(context) // fresh instance: reads from disk
        val hit = reopened.getPinned(nav.voiceName, "เลี้ยวซ้าย")
        assertNotNull(hit)
        assertArrayEquals(pcm(1), hit!!.audioData)
        assertTrue(hit.wasCached)

        assertNull(reopened.getPinned(nav.voiceName, "เลี้ยวขวา"))
        reopened.clearPinned()
        assertEquals(0, VoiceClipCache(context).pinnedCount())
    }

    @Test
    fun `clip joiner concatenates PCM with a gap and rejects mismatching formats`() {
        val a = clip("a", 1)
        val b = clip("b", 2)
        val joined = NavigationClipJoiner.join(listOf(a, b))!!
        val gapBytes = 24000 * NavigationClipJoiner.GAP_MS / 1000 * 2
        assertEquals(a.audioData.size + gapBytes + b.audioData.size, joined.audioData.size)
        assertEquals(1.toByte(), joined.audioData.first())
        assertEquals(2.toByte(), joined.audioData.last())
        assertEquals(0.toByte(), joined.audioData[a.audioData.size + 10])

        assertNull(NavigationClipJoiner.join(listOf(a, b.copy(sampleRate = 16000))))
        assertNull(NavigationClipJoiner.join(emptyList()))
    }

    // --- Latency policy -----------------------------------------------------------------------

    @Test
    fun `latency fallback decision`() {
        assertFalse(NavigationPolicy.shouldFallbackNow(elapsedMs = 100, budgetMs = 500, cloudReady = false))
        assertTrue(NavigationPolicy.shouldFallbackNow(elapsedMs = 500, budgetMs = 500, cloudReady = false))
        assertTrue(NavigationPolicy.shouldFallbackNow(elapsedMs = 900, budgetMs = 500, cloudReady = false))
        assertFalse(NavigationPolicy.shouldFallbackNow(elapsedMs = 900, budgetMs = 500, cloudReady = true))
    }

    @Test
    fun `awaitWithin returns null on budget overrun but lets the work finish`() = runBlocking {
        val scope = CoroutineScope(Dispatchers.Default)
        val slow = scope.async { delay(400); "gemini" }
        assertNull(NavigationPolicy.awaitWithin(slow, 50))
        assertEquals("gemini", slow.await()) // still completes in the background

        val fast = scope.async { "cached" }
        assertEquals("cached", NavigationPolicy.awaitWithin(fast, 1000))
    }

    // --- Style override -----------------------------------------------------------------------

    @Test
    fun `navigation style override ignores 18+ styles and keeps polite ending off by default`() {
        preferences.confirmAdultContent()
        val request = SynthesisRequestData(
            text = "อีก 200 เมตร เลี้ยวซ้าย",
            politeStyle = PoliteEndingStyle.KRUB_PHOM,
            voicePreset = VoiceStylePreset.SEXY,
            geminiVoice = VoiceStylePreset.SEXY.voiceName,
            stylePrompt = VoiceStylePreset.SEXY.stylePrompt
        )
        val forced = NavigationPolicy.applyNavigationStyle(request, preferences.getSettings())
        assertEquals(VoiceStylePreset.NAVIGATION, forced.voicePreset)
        assertEquals(nav.voiceName, forced.geminiVoice)
        assertEquals(nav.stylePrompt, forced.stylePrompt)
        assertEquals(PoliteEndingStyle.OFF, forced.politeStyle)
        assertTrue(forced.navigationRequested)
        assertFalse(nav.isAdult)
        assertTrue(nav.stylePrompt.contains("never whisper"))

        preferences.updateNavigationPoliteEnding(true)
        val polite = NavigationPolicy.applyNavigationStyle(request, preferences.getSettings())
        assertEquals(PoliteEndingStyle.KRUB_PHOM, polite.politeStyle)
    }

    @Test
    fun `navigation is active from the setting or from the caller`() {
        assertFalse(NavigationPolicy.isActive(settingEnabled = false, callerRequested = false))
        assertTrue(NavigationPolicy.isActive(settingEnabled = true, callerRequested = false))
        assertTrue(NavigationPolicy.isActive(settingEnabled = false, callerRequested = true))
    }

    @Test
    fun `navigation caller is detected from AudioAttributes usage`() {
        val navParams = Bundle().apply {
            putParcelable(
                "audioAttributes",
                AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE).build()
            )
        }
        val mediaParams = Bundle().apply {
            putParcelable("audioAttributes", AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).build())
        }
        assertTrue(NavigationPolicy.isNavigationRequest(navParams))
        assertFalse(NavigationPolicy.isNavigationRequest(mediaParams))
        assertFalse(NavigationPolicy.isNavigationRequest(Bundle()))
        assertFalse(NavigationPolicy.isNavigationRequest(null))
        assertTrue(NavigationPolicy.isNavigationRequest(Bundle().apply { putBoolean(NavigationPolicy.EXTRA_NAVIGATION, true) }))
    }

    @Test
    fun `navigation preferences default and persist`() {
        val d = preferences.getSettings()
        assertFalse(d.navigationMode)
        assertEquals(500L, d.navigationLatencyBudgetMs)
        assertFalse(d.navigationPoliteEnding)
        preferences.updateNavigationMode(true)
        preferences.updateNavigationLatencyBudget(800)
        val s = SiamVoicePreferences(context).getSettings()
        assertTrue(s.navigationMode)
        assertEquals(800L, s.navigationLatencyBudgetMs)
    }

    // --- Pipeline -------------------------------------------------------------------------------

    private fun synthesizer(
        transport: GeminiTtsTransport,
        fallbackCalls: MutableList<String>,
        fallbackPcm: ByteArray = pcm(9)
    ) = object : SiamVoiceTtsSynthesizer(
        context = context,
        preferences = preferences,
        voiceClipCache = cache,
        geminiClient = GeminiTtsClient(context = context, transport = transport),
        androidFallbackEngine = object : AndroidSystemTtsEngine(context) {
            override suspend fun synthesizeToFile(
                text: String,
                preferredEnginePackage: String?,
                speechRate: Float,
                pitch: Float,
                timeoutMs: Long
            ): DecodedPcmAudio {
                fallbackCalls.add(text)
                return DecodedPcmAudio(fallbackPcm, 24000, 1)
            }
        }
    ) {
        override fun isNetworkAvailable(): Boolean = true
    }

    @Test
    fun `pinned phrase segments are concatenated without any network call`() = runBlocking {
        val geminiCalls = AtomicInteger()
        val fallbackCalls = mutableListOf<String>()
        val s = synthesizer({ _, _, _ ->
            geminiCalls.incrementAndGet()
            GeminiHttpResponse(500, "")
        }, fallbackCalls)
        preferences.updateNavigationMode(true)
        cache.putPinned(nav.voiceName, "อีก 200 เมตร", clip("อีก 200 เมตร", 1))
        cache.putPinned(nav.voiceName, "เลี้ยวซ้าย", clip("เลี้ยวซ้าย", 2))

        val result = s.synthesize(SynthesisRequestData(text = "อีก 200 เมตร เลี้ยวซ้าย"))
        assertTrue(result.wasCached)
        assertEquals(0, geminiCalls.get())
        assertTrue(fallbackCalls.isEmpty())
        assertEquals(1.toByte(), result.audioData.first())
        assertEquals(2.toByte(), result.audioData.last())

        // exact pinned phrase
        val single = s.synthesize(SynthesisRequestData(text = "เลี้ยวซ้าย"))
        assertArrayEquals(pcm(2), single.audioData)
    }

    @Test
    fun `slow Gemini falls back to Android TTS in navigation mode and warms the cache`() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        val geminiPcm = pcm(5)
        val fallbackCalls = mutableListOf<String>()
        val s = synthesizer({ _, _, _ ->
            gate.await()
            GeminiHttpResponse(200, geminiJson(geminiPcm))
        }, fallbackCalls)
        preferences.updateNavigationMode(true)
        preferences.updateNavigationLatencyBudget(300)

        val request = SynthesisRequestData(text = "เตรียมออกทางด่วนข้างหน้า")
        val first = s.synthesize(request)
        assertEquals(TtsEngineType.ANDROID_SYSTEM_TTS, first.engineUsed)
        assertEquals(listOf("เตรียมออกทางด่วนข้างหน้า"), fallbackCalls) // no ครับ in navigation mode
        assertArrayEquals(pcm(9), first.audioData)

        // Gemini finishes in the background and fills the cache for next time
        gate.complete(Unit)
        var second: SynthesisResult? = null
        for (i in 0 until 50) {
            delay(100)
            val r = s.synthesize(request)
            if (r.wasCached) { second = r; break }
        }
        assertNotNull("cache was not warmed", second)
        assertArrayEquals(geminiPcm, second!!.audioData)
    }

    @Test
    fun `fast Gemini within the budget is used directly`() = runBlocking {
        val fallbackCalls = mutableListOf<String>()
        val s = synthesizer({ _, _, _ -> GeminiHttpResponse(200, geminiJson(pcm(5))) }, fallbackCalls)
        preferences.updateNavigationMode(true)
        preferences.updateNavigationLatencyBudget(3000)

        val r = s.synthesize(SynthesisRequestData(text = "เตรียมเข้าซอยข้างหน้า"))
        assertEquals(TtsEngineType.GEMINI_FLASH, r.engineUsed)
        assertTrue(fallbackCalls.isEmpty())
        assertEquals(nav.voiceName, r.voiceName)
    }

    @Test
    fun `prefetch warms the cache so the next request is a hit`() = runBlocking {
        val calls = AtomicInteger()
        val fallbackCalls = mutableListOf<String>()
        val s = synthesizer({ _, _, _ ->
            calls.incrementAndGet()
            GeminiHttpResponse(200, geminiJson(pcm(5)))
        }, fallbackCalls)
        preferences.updateNavigationMode(true)

        val request = SynthesisRequestData(text = "ขับตรงไปอีกสองกิโลเมตร")
        s.prefetch(request)
        var hit: SynthesisResult? = null
        for (i in 0 until 50) {
            delay(100)
            if (calls.get() > 0) {
                hit = s.synthesize(request)
                break
            }
        }
        assertNotNull(hit)
        assertTrue(hit!!.wasCached || hit.engineUsed != TtsEngineType.ANDROID_SYSTEM_TTS)
        assertEquals(1, calls.get())
        assertTrue(s.synthesize(request).wasCached)
        assertEquals(1, calls.get())
    }

    @Test
    fun `download navigation voices pins every phrase and can resume`() = runBlocking {
        val calls = AtomicInteger()
        val s = synthesizer({ _, _, _ ->
            calls.incrementAndGet()
            GeminiHttpResponse(200, geminiJson(pcm(5)))
        }, mutableListOf())
        com.example.data.SecureApiKeyStore(context).saveApiKey("AIzaSyTestKey1234567890ABCDEF")

        val state = s.downloadNavigationVoices()
        val total = NavigationPhrases.all().size
        assertEquals(total, state.done)
        assertEquals(total, calls.get())
        assertEquals(total, cache.pinnedCount())
        assertFalse(state.running)

        // Second run downloads nothing: everything is stored already
        val again = s.downloadNavigationVoices()
        assertEquals(total, calls.get())
        assertEquals(total, again.done)
    }

    @Test
    fun `download without API key reports a Thai message and downloads nothing`() = runBlocking {
        com.example.data.SecureApiKeyStore(context).deleteApiKey()
        val calls = AtomicInteger()
        val s = synthesizer({ _, _, _ -> calls.incrementAndGet(); GeminiHttpResponse(500, "") }, mutableListOf())
        val state = s.downloadNavigationVoices()
        assertEquals(0, calls.get())
        assertTrue(state.message.contains("API Key"))
    }
}
