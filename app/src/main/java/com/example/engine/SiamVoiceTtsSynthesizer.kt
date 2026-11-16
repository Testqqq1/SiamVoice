package com.example.engine

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import com.example.cache.VoiceClipCache
import com.example.data.SecureApiKeyStore
import com.example.data.AppSettings
import com.example.data.SiamVoicePreferences
import com.example.model.DiagnosticsInfo
import com.example.model.GeminiModelPreference
import com.example.model.SynthesisRequestData
import com.example.model.SynthesisResult
import com.example.model.TtsEngineType
import com.example.model.VoiceStylePreset
import com.example.network.GeminiTtsClient
import com.example.model.PoliteEndingStyle
import com.example.processor.ThaiPoliteEndingProcessor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

open class SiamVoiceTtsSynthesizer(
    private val context: Context,
    private val preferences: SiamVoicePreferences,
    private val voiceClipCache: VoiceClipCache = VoiceClipCache(context),
    private val geminiClient: GeminiTtsClient = GeminiTtsClient(context),
    private val androidFallbackEngine: AndroidSystemTtsEngine = AndroidSystemTtsEngine(context),
    private val apiKeyStore: SecureApiKeyStore = SecureApiKeyStore(context)
) {
    companion object {
        private const val TAG = "SiamVoiceSynthesizer"
    }

    val flashCircuitBreaker = CircuitBreaker("GeminiFlashTTS", failureThreshold = 2)
    val flashLiteCircuitBreaker = CircuitBreaker("GeminiFlashLiteTTS", failureThreshold = 2)
    // Extra safety net: the long-available Gemini 2.5 TTS model (inline style prompt format)
    val legacyCircuitBreaker = CircuitBreaker("GeminiLegacyTTS", failureThreshold = 2)

    // Background work that outlives a single request: finishing Gemini synthesis after the navigation
    // latency budget was spent (to warm the cache), prefetching, and downloading navigation voices.
    private val backgroundScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _navDownloadState = MutableStateFlow(
        NavDownloadState(
            total = NavigationPhrases.all().size,
            done = voiceClipCache.pinnedCount(),
            message = ""
        )
    )
    val navDownloadState: StateFlow<NavDownloadState> = _navDownloadState.asStateFlow()

    private val _diagnostics = MutableStateFlow(
        DiagnosticsInfo(
            isOnline = isNetworkAvailable(),
            hasApiKey = apiKeyStore.hasApiKey(),
            cacheItemsCount = voiceClipCache.count()
        )
    )
    val diagnostics: StateFlow<DiagnosticsInfo> = _diagnostics.asStateFlow()

    open fun isNetworkAvailable(): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    fun resetCircuitBreakers() {
        legacyCircuitBreaker.reset()
        flashCircuitBreaker.reset()
        flashLiteCircuitBreaker.reset()
        refreshDiagnosticsState()
    }

    fun refreshDiagnosticsState(lastError: String? = _diagnostics.value.lastErrorSummary) {
        _diagnostics.value = _diagnostics.value.copy(
            isOnline = isNetworkAvailable(),
            hasApiKey = apiKeyStore.hasApiKey(),
            flashCircuitBreakerState = flashCircuitBreaker.getState(),
            flashLiteCircuitBreakerState = flashLiteCircuitBreaker.getState(),
            cacheItemsCount = voiceClipCache.count(),
            lastErrorSummary = lastError
        )
    }

    /** Everything a single synthesis needs, resolved once from the request + settings. */
    private class SynthContext(
        val req: SynthesisRequestData,
        val settings: AppSettings,
        val navigation: Boolean,
        val politeStyle: PoliteEndingStyle,
        val processedText: String,
        val rate: Float,
        val pitch: Float,
        val voiceStyleId: String,
        val flashModelId: String,
        val flashLiteModelId: String,
        val startTime: Long
    )

    private class CloudOutcome(val result: SynthesisResult?, val error: Throwable?)

    private fun buildContext(request: SynthesisRequestData, settings: AppSettings): SynthContext {
        val startTime = System.currentTimeMillis()
        val navigation = NavigationPolicy.isActive(settings.navigationMode, request.navigationRequested)

        // Navigation forces the clear Navigation style and ignores 18+ styles. Otherwise 18+ styles
        // require the one-time age confirmation and are downgraded to the default style without it.
        val req = if (navigation) {
            NavigationPolicy.applyNavigationStyle(request, settings)
        } else {
            enforceAdultGate(request, settings.adultContentConfirmed)
        }
        val preset = req.voicePreset

        // Process polite Thai ending once so Gemini and Android fallback receive identical transcripts.
        // 18+ presets (and navigation mode by default) switch the automatic "ครับ/ค่ะ" processor off.
        val politeStyle = preset.resolvePoliteEnding(req.politeStyle)
        val processedText = ThaiPoliteEndingProcessor.process(req.text, politeStyle)

        // Style hints only apply to the Android fallback / cache key; presets without hints are unchanged.
        val baseRate = req.speechRate * settings.fallbackSpeechRate
        val effectiveRate = if (preset.speechRateHint == 1.0f) baseRate
        else (baseRate * preset.speechRateHint).coerceIn(0.5f, 2.5f)
        val effectivePitch = if (preset.pitchHint == 1.0f) req.pitch
        else (req.pitch * preset.pitchHint).coerceIn(0.5f, 2.0f)

        return SynthContext(
            req = req,
            settings = settings,
            navigation = navigation,
            politeStyle = politeStyle,
            processedText = processedText,
            rate = effectiveRate,
            pitch = effectivePitch,
            voiceStyleId = preset.name,
            flashModelId = settings.verifiedFlashModelId.ifBlank { GeminiTtsClient.MODEL_FLASH },
            flashLiteModelId = settings.verifiedFlashLiteModelId.ifBlank { GeminiTtsClient.MODEL_FLASH_LITE },
            startTime = startTime
        )
    }

    /**
     * Executes synthesis following the unified priority chain:
     * 1. Cache (navigation mode: pinned navigation voices and cached phrase segments first)
     * 2. Gemini Flash TTS -> Gemini Flash-Lite TTS -> Gemini 2.5 TTS
     * 3. External Android System TTS (non-recursive fallback)
     *
     * In navigation mode Gemini only gets [AppSettings.navigationLatencyBudgetMs]; after that the
     * Android system TTS speaks immediately while Gemini keeps running in the background to warm
     * the cache for the next time.
     */
    open suspend fun synthesize(request: SynthesisRequestData): SynthesisResult = withContext(Dispatchers.IO) {
        val ctx = buildContext(request, preferences.getSettings())

        // Navigation: pinned full-sentence clip, or a sentence made only of pinned phrase clips
        if (ctx.navigation) {
            lookupPinnedNavigation(ctx)?.let { return@withContext it }
        }

        lookupCache(ctx)?.let { return@withContext it }

        val online = isNetworkAvailable()

        if (ctx.navigation && ctx.settings.autoFallbackEnabled) {
            return@withContext synthesizeNavigationWithBudget(ctx, online)
        }

        val outcome = runCloudStages(ctx, online)
        outcome.result?.let { return@withContext it }
        runAndroidFallbackOrThrow(ctx, outcome.error, online)
    }

    /**
     * Prefetch hook: warms the cache for upcoming text (e.g. the next turn instruction) in the
     * background without playing anything. A later request with the same text is a cache hit.
     */
    open fun prefetch(request: SynthesisRequestData) {
        backgroundScope.launch {
            try {
                val ctx = buildContext(request, preferences.getSettings())
                if (lookupCache(ctx) != null || (ctx.navigation && lookupPinnedNavigation(ctx) != null)) return@launch
                val online = isNetworkAvailable()
                if (online) runCloudStages(ctx, online)
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                Log.w(TAG, "prefetch failed: ${t.message}")
            }
        }
    }

    private fun lookupPinnedNavigation(ctx: SynthContext): SynthesisResult? {
        val voice = ctx.req.geminiVoice
        voiceClipCache.getPinned(voice, ctx.processedText)?.let {
            return finishCached(ctx, it)
        }
        val segments = NavigationPhrases.segment(ctx.processedText) ?: return null
        if (segments.size < 2) return null
        val clips = ArrayList<SynthesisResult>(segments.size)
        for (segment in segments) {
            clips.add(voiceClipCache.getPinned(voice, segment) ?: return null)
        }
        val joined = NavigationClipJoiner.join(clips) ?: return null
        return finishCached(ctx, joined)
    }

    private fun lookupCache(ctx: SynthContext): SynthesisResult? {
        val models = listOf(ctx.flashModelId, ctx.flashLiteModelId, GeminiTtsClient.MODEL_GEMINI_2_5_FLASH_TTS)
        for (model in models) {
            val cached = voiceClipCache.get(
                modelName = model,
                voiceName = ctx.req.geminiVoice,
                text = ctx.processedText,
                stylePrompt = ctx.req.stylePrompt,
                politeStyle = ctx.politeStyle,
                speechRate = ctx.rate,
                pitch = ctx.pitch,
                voiceStyle = ctx.voiceStyleId
            )
            if (cached != null) return finishCached(ctx, cached)
        }
        return null
    }

    private fun finishCached(ctx: SynthContext, cached: SynthesisResult): SynthesisResult {
        val totalLatency = System.currentTimeMillis() - ctx.startTime
        updateDiagnostics(
            engine = cached.engineUsed,
            latencyMs = totalLatency,
            processedText = ctx.processedText,
            lastError = null
        )
        return cached.copy(
            latencyMs = totalLatency,
            wasCached = true,
            processedText = ctx.processedText,
            voiceName = ctx.req.geminiVoice,
            stylePrompt = ctx.req.stylePrompt
        )
    }

    /**
     * Navigation latency budget: Gemini gets a short head start; if its audio is not ready in time the
     * Android system TTS speaks immediately and Gemini finishes in the background to warm the cache.
     */
    private suspend fun synthesizeNavigationWithBudget(ctx: SynthContext, online: Boolean): SynthesisResult {
        val cloud: Deferred<CloudOutcome> = backgroundScope.async { runCloudStages(ctx, online) }
        val budget = ctx.settings.navigationLatencyBudgetMs
        val early = NavigationPolicy.awaitWithin(cloud, budget)
        early?.result?.let { return it }

        if (early == null) {
            Log.d(TAG, "Navigation latency budget (${budget}ms) exceeded; using Android TTS now")
        }
        return try {
            runAndroidFallbackOrThrow(ctx, early?.error, online)
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            // Android TTS failed too: Gemini is the only option left, wait for it
            val late = cloud.await()
            late.result ?: throw t
        }
    }

    /**
     * Runs the ordered Gemini cloud stages (Flash -> Flash-Lite -> 2.5 safety net) and stores a
     * successful clip in the cache. Never throws except for cancellation.
     */
    private suspend fun runCloudStages(ctx: SynthContext, online: Boolean): CloudOutcome {
        var lastException: Throwable? = null

        data class CloudStage(
            val modelId: String,
            val engineType: TtsEngineType,
            val breaker: CircuitBreaker
        )

        val primaryStages = when (ctx.settings.modelPreference) {
            GeminiModelPreference.FLASH_PRIMARY -> listOf(
                CloudStage(ctx.flashModelId, TtsEngineType.GEMINI_FLASH, flashCircuitBreaker),
                CloudStage(ctx.flashLiteModelId, TtsEngineType.GEMINI_FLASH_LITE, flashLiteCircuitBreaker)
            )
            GeminiModelPreference.FLASH_LITE_PRIMARY -> listOf(
                CloudStage(ctx.flashLiteModelId, TtsEngineType.GEMINI_FLASH_LITE, flashLiteCircuitBreaker),
                CloudStage(ctx.flashModelId, TtsEngineType.GEMINI_FLASH, flashCircuitBreaker)
            )
        }

        // Add the Gemini 2.5 TTS model as a last cloud stage when it is not already configured,
        // so an account without access to the 3.8 models still gets Gemini voices.
        val orderedCloudStages = if (primaryStages.none { it.modelId == GeminiTtsClient.MODEL_GEMINI_2_5_FLASH_TTS }) {
            primaryStages + CloudStage(
                GeminiTtsClient.MODEL_GEMINI_2_5_FLASH_TTS,
                TtsEngineType.GEMINI_FLASH_LITE,
                legacyCircuitBreaker
            )
        } else {
            primaryStages
        }

        for (stage in orderedCloudStages) {
            if (online && stage.breaker.canExecute()) {
                try {
                    val pcmAudio = geminiClient.synthesize(
                        text = ctx.processedText,
                        modelId = stage.modelId,
                        voiceName = ctx.req.geminiVoice,
                        stylePrompt = ctx.req.stylePrompt
                    )
                    if (pcmAudio.pcmData.isEmpty()) {
                        throw InvalidAudioException("${stage.engineType.displayName} returned empty PCM audio")
                    }
                    stage.breaker.recordSuccess()
                    val totalLatency = System.currentTimeMillis() - ctx.startTime
                    val result = SynthesisResult(
                        audioData = pcmAudio.pcmData,
                        sampleRate = pcmAudio.sampleRate,
                        channelCount = pcmAudio.channelCount,
                        engineUsed = stage.engineType,
                        latencyMs = totalLatency,
                        wasCached = false,
                        processedText = ctx.processedText,
                        voiceName = ctx.req.geminiVoice,
                        stylePrompt = ctx.req.stylePrompt
                    )
                    voiceClipCache.put(
                        modelName = stage.modelId,
                        voiceName = ctx.req.geminiVoice,
                        text = ctx.processedText,
                        result = result,
                        stylePrompt = ctx.req.stylePrompt,
                        politeStyle = ctx.politeStyle,
                        speechRate = ctx.rate,
                        pitch = ctx.pitch,
                        voiceStyle = ctx.voiceStyleId
                    )
                    updateDiagnostics(stage.engineType, totalLatency, ctx.processedText, lastError = null)
                    return CloudOutcome(result, null)
                } catch (e: CancellationException) {
                    throw e
                } catch (t: Throwable) {
                    Log.w(TAG, "${stage.engineType.displayName} (${stage.modelId}) failed: ${t.message}", t)
                    stage.breaker.recordFailure(t)
                    lastException = t
                    if (t is AuthFailedException) {
                        // Missing or invalid API Key affects all Gemini models; skip straight to Android TTS fallback
                        break
                    }
                }
            } else {
                Log.d(TAG, "Skipping ${stage.engineType.displayName}: online=$online, breaker=${stage.breaker.getState()}")
            }
        }
        return CloudOutcome(null, lastException)
    }

    /** Final fallback: external Android System TTS (receives identical processedText). */
    private suspend fun runAndroidFallbackOrThrow(
        ctx: SynthContext,
        lastException: Throwable?,
        online: Boolean
    ): SynthesisResult {
        val startTime = ctx.startTime
        val processedText = ctx.processedText
        if (ctx.settings.autoFallbackEnabled) {
            try {
                val pcmAudio = androidFallbackEngine.synthesizeToFile(
                    text = processedText,
                    preferredEnginePackage = ctx.settings.preferredAndroidEngine.ifBlank { null },
                    speechRate = ctx.rate,
                    pitch = ctx.pitch
                )
                val totalLatency = System.currentTimeMillis() - startTime
                val result = SynthesisResult(
                    audioData = pcmAudio.pcmData,
                    sampleRate = pcmAudio.sampleRate,
                    channelCount = pcmAudio.channelCount,
                    engineUsed = TtsEngineType.ANDROID_SYSTEM_TTS,
                    latencyMs = totalLatency,
                    wasCached = false,
                    processedText = processedText,
                    voiceName = ctx.req.geminiVoice,
                    stylePrompt = ctx.req.stylePrompt
                )
                updateDiagnostics(
                    engine = TtsEngineType.ANDROID_SYSTEM_TTS,
                    latencyMs = totalLatency,
                    processedText = processedText,
                    lastError = lastException?.message
                )
                return result
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                Log.e(TAG, "External Android System TTS fallback also failed: ${t.message}", t)
                val cloudReason = lastException?.message ?: if (!online) "ไม่มีการเชื่อมต่ออินเทอร์เน็ต" else "ไม่ทราบสาเหตุ"
                updateDiagnostics(
                    engine = TtsEngineType.ANDROID_SYSTEM_TTS,
                    latencyMs = System.currentTimeMillis() - startTime,
                    processedText = processedText,
                    lastError = cloudReason
                )
                throw FallbackSynthesisException(
                    "ไม่สามารถสังเคราะห์เสียงได้ (Gemini: $cloudReason | Android TTS: ${t.message ?: t.javaClass.simpleName})"
                )
            }
        } else {
            val cloudReason = lastException?.message ?: if (!online) "ไม่มีการเชื่อมต่ออินเทอร์เน็ต" else "ไม่ทราบสาเหตุ"
            updateDiagnostics(
                engine = TtsEngineType.GEMINI_FLASH,
                latencyMs = System.currentTimeMillis() - startTime,
                processedText = processedText,
                lastError = cloudReason
            )
            throw FallbackSynthesisException(
                "Gemini TTS ล้มเหลว ($cloudReason) และปิดระบบสำรอง Android TTS ไว้"
            )
        }
    }

    /**
     * Downloads (synthesizes with Gemini in the clear Navigation style) every phrase of
     * [NavigationPhrases] that is not stored yet and pins it persistently. Already stored phrases are
     * skipped, so after a quota/network failure the action can simply be run again to resume.
     */
    open suspend fun downloadNavigationVoices(): NavDownloadState = withContext(Dispatchers.IO) {
        val phrases = NavigationPhrases.all()
        val nav = com.example.model.VoiceStylePreset.NAVIGATION
        val settings = preferences.getSettings()
        var done = phrases.count { voiceClipCache.hasPinned(nav.voiceName, it) }
        var failed = 0

        if (!apiKeyStore.hasApiKey()) {
            val s = NavDownloadState(
                running = false, total = phrases.size, done = done, failed = 0,
                message = "ยังไม่ได้ตั้งค่า Gemini API Key กรุณาเพิ่ม API Key ในหน้าตั้งค่าก่อนดาวน์โหลดเสียงนำทาง"
            )
            _navDownloadState.value = s
            return@withContext s
        }
        if (!isNetworkAvailable()) {
            val s = NavDownloadState(
                running = false, total = phrases.size, done = done, failed = 0,
                message = "ไม่มีการเชื่อมต่ออินเทอร์เน็ต"
            )
            _navDownloadState.value = s
            return@withContext s
        }

        _navDownloadState.value = NavDownloadState(true, phrases.size, done, 0, "กำลังดาวน์โหลดเสียงนำทาง...")
        var stopMessage: String? = null

        for (phrase in phrases) {
            if (voiceClipCache.hasPinned(nav.voiceName, phrase)) continue
            val request = SynthesisRequestData(
                text = phrase,
                politeStyle = PoliteEndingStyle.OFF,
                voicePreset = nav,
                geminiVoice = nav.voiceName,
                stylePrompt = nav.stylePrompt,
                navigationRequested = true
            )
            val ctx = buildContext(request, settings.copy(navigationMode = true, navigationPoliteEnding = false))
            val outcome = runCloudStages(ctx, online = true)
            val result = outcome.result
            if (result != null) {
                voiceClipCache.putPinned(nav.voiceName, phrase, result)
                done++
            } else {
                failed++
                val err = outcome.error
                if (err is AuthFailedException || err is QuotaExhaustedException) {
                    stopMessage = err.message
                    _navDownloadState.value = NavDownloadState(true, phrases.size, done, failed, "กำลังหยุด...")
                    break
                }
            }
            _navDownloadState.value = NavDownloadState(true, phrases.size, done, failed, "กำลังดาวน์โหลดเสียงนำทาง...")
        }

        val message = when {
            stopMessage != null -> "หยุดที่ $done/${phrases.size}: $stopMessage (กดดาวน์โหลดอีกครั้งเพื่อทำต่อ)"
            done >= phrases.size -> "ดาวน์โหลดเสียงนำทางครบแล้ว ($done/${phrases.size})"
            else -> "ดาวน์โหลดได้ $done/${phrases.size} (ล้มเหลว $failed) กดอีกครั้งเพื่อลองใหม่"
        }
        val finalState = NavDownloadState(false, phrases.size, done, failed, message)
        _navDownloadState.value = finalState
        finalState
    }

    fun clearNavigationVoices() {
        voiceClipCache.clearPinned()
        _navDownloadState.value = NavDownloadState(
            running = false,
            total = NavigationPhrases.all().size,
            done = 0,
            message = "ลบเสียงนำทางที่ดาวน์โหลดไว้แล้ว"
        )
    }

    /** Warms the Android system TTS engine so the navigation fallback starts without init delay. */
    open fun prewarmFallback() {
        backgroundScope.launch {
            try {
                androidFallbackEngine.prewarm(preferences.getSettings().preferredAndroidEngine.ifBlank { null })
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                Log.w(TAG, "prewarm failed: ${t.message}")
            }
        }
    }

    /**
     * Defence in depth: even if a caller hands us an 18+ preset, it is only honoured when the user
     * confirmed they are 18 or older. Otherwise the request is downgraded to the default style.
     */
    internal fun enforceAdultGate(request: SynthesisRequestData, adultConfirmed: Boolean): SynthesisRequestData {
        if (!request.voicePreset.isAdult || adultConfirmed) return request
        val safe = VoiceStylePreset.FRIENDLY_COMPANION
        return request.copy(
            voicePreset = safe,
            geminiVoice = safe.voiceName,
            stylePrompt = safe.stylePrompt
        )
    }

    private fun updateDiagnostics(
        engine: TtsEngineType,
        latencyMs: Long,
        processedText: String,
        lastError: String?
    ) {
        _diagnostics.value = _diagnostics.value.copy(
            activeEngine = engine,
            lastLatencyMs = latencyMs,
            lastProcessedText = processedText,
            isOnline = isNetworkAvailable(),
            hasApiKey = apiKeyStore.hasApiKey(),
            flashCircuitBreakerState = flashCircuitBreaker.getState(),
            flashLiteCircuitBreakerState = flashLiteCircuitBreaker.getState(),
            cacheItemsCount = voiceClipCache.count(),
            lastErrorSummary = lastError
        )
    }

    fun clearCache() {
        voiceClipCache.clear()
        _diagnostics.value = _diagnostics.value.copy(cacheItemsCount = 0)
    }
}
