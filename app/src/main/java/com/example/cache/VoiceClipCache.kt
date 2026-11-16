package com.example.cache

import android.content.Context
import android.util.LruCache
import com.example.model.PoliteEndingStyle
import com.example.model.SynthesisResult
import com.example.model.TtsEngineType
import com.example.model.VoiceStylePreset
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.Locale

class VoiceClipCache(context: Context) {
    private val cacheDir = File(context.cacheDir, "siamvoice_clips").apply { mkdirs() }

    // Pinned (navigation) clips live in filesDir, so the OS cache cleaner can't remove them, and in a
    // plain map, so they are never evicted by the LRU memory cache.
    private val pinnedDir = File(context.filesDir, "siamvoice_nav_pinned").apply { mkdirs() }
    private val pinnedMemory = java.util.concurrent.ConcurrentHashMap<String, SynthesisResult>()

    // Memory cache: max 50 entries
    private val memoryCache = object : LruCache<String, SynthesisResult>(50) {}

    fun get(
        modelName: String,
        voiceName: String,
        text: String,
        stylePrompt: String = VoiceStylePreset.fromVoiceOrName(voiceName).stylePrompt,
        politeStyle: PoliteEndingStyle = PoliteEndingStyle.MALE_DEFAULT,
        speechRate: Float = 1.0f,
        pitch: Float = 1.0f,
        voiceStyle: String = ""
    ): SynthesisResult? {
        val key = computeKey(
            model = modelName,
            voice = voiceName,
            stylePrompt = stylePrompt,
            politeStyle = politeStyle,
            speechRate = speechRate,
            pitch = pitch,
            text = text,
            voiceStyle = voiceStyle
        )
        // 1. Check memory cache
        memoryCache.get(key)?.let {
            return it.copy(wasCached = true)
        }

        // 2. Check disk cache
        val diskFile = File(cacheDir, "$key.pcm")
        val metaFile = File(cacheDir, "$key.meta")
        if (diskFile.exists() && diskFile.length() > 0 && metaFile.exists()) {
            try {
                val lines = metaFile.readLines()
                val sampleRate = lines.getOrNull(0)?.toIntOrNull() ?: 24000
                val channelCount = lines.getOrNull(1)?.toIntOrNull() ?: 1
                val engineName = lines.getOrNull(2) ?: TtsEngineType.GEMINI_FLASH.name
                val engineType = try {
                    TtsEngineType.valueOf(engineName)
                } catch (e: Exception) {
                    TtsEngineType.GEMINI_FLASH
                }
                val savedVoice = lines.getOrNull(3) ?: voiceName
                val savedStyle = lines.getOrNull(4) ?: stylePrompt
                val pcmBytes = diskFile.readBytes()
                val result = SynthesisResult(
                    audioData = pcmBytes,
                    sampleRate = sampleRate,
                    channelCount = channelCount,
                    engineUsed = engineType,
                    latencyMs = 5L,
                    wasCached = true,
                    processedText = text,
                    voiceName = savedVoice,
                    stylePrompt = savedStyle
                )
                memoryCache.put(key, result)
                return result
            } catch (ignored: Exception) {}
        }
        return null
    }

    fun put(
        modelName: String,
        voiceName: String,
        text: String,
        result: SynthesisResult,
        stylePrompt: String = result.stylePrompt.ifBlank { VoiceStylePreset.fromVoiceOrName(voiceName).stylePrompt },
        politeStyle: PoliteEndingStyle = PoliteEndingStyle.MALE_DEFAULT,
        speechRate: Float = 1.0f,
        pitch: Float = 1.0f,
        voiceStyle: String = ""
    ) {
        val key = computeKey(
            model = modelName,
            voice = voiceName,
            stylePrompt = stylePrompt,
            politeStyle = politeStyle,
            speechRate = speechRate,
            pitch = pitch,
            text = text,
            voiceStyle = voiceStyle
        )
        memoryCache.put(key, result)

        try {
            val diskFile = File(cacheDir, "$key.pcm")
            FileOutputStream(diskFile).use { it.write(result.audioData) }

            val metaFile = File(cacheDir, "$key.meta")
            metaFile.writeText(
                listOf(
                    result.sampleRate.toString(),
                    result.channelCount.toString(),
                    result.engineUsed.name,
                    voiceName,
                    stylePrompt.replace("\n", " "),
                    text.replace("\n", " ")
                ).joinToString("\n")
            )
        } catch (ignored: Exception) {}
    }

    /** Key of a pinned navigation clip: independent of model/rate so any Gemini model can fill it. */
    fun pinnedKey(voiceName: String, text: String): String {
        val input = "NAV|$voiceName|${com.example.engine.NavigationPhrases.normalize(text)}"
        return MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }

    fun hasPinned(voiceName: String, text: String): Boolean {
        val key = pinnedKey(voiceName, text)
        return pinnedMemory.containsKey(key) || (File(pinnedDir, "$key.pcm").length() > 0 && File(pinnedDir, "$key.meta").exists())
    }

    fun getPinned(voiceName: String, text: String): SynthesisResult? {
        val key = pinnedKey(voiceName, text)
        pinnedMemory[key]?.let { return it.copy(wasCached = true) }
        val pcm = File(pinnedDir, "$key.pcm")
        val meta = File(pinnedDir, "$key.meta")
        if (!pcm.exists() || pcm.length() == 0L || !meta.exists()) return null
        return try {
            val lines = meta.readLines()
            val result = SynthesisResult(
                audioData = pcm.readBytes(),
                sampleRate = lines.getOrNull(0)?.toIntOrNull() ?: 24000,
                channelCount = lines.getOrNull(1)?.toIntOrNull() ?: 1,
                engineUsed = TtsEngineType.GEMINI_FLASH,
                latencyMs = 2L,
                wasCached = true,
                processedText = text,
                voiceName = voiceName
            )
            pinnedMemory[key] = result
            result
        } catch (e: Exception) {
            null
        }
    }

    fun putPinned(voiceName: String, text: String, result: SynthesisResult) {
        val key = pinnedKey(voiceName, text)
        pinnedMemory[key] = result
        try {
            FileOutputStream(File(pinnedDir, "$key.pcm")).use { it.write(result.audioData) }
            File(pinnedDir, "$key.meta").writeText(
                listOf(
                    result.sampleRate.toString(),
                    result.channelCount.toString(),
                    com.example.engine.NavigationPhrases.normalize(text)
                ).joinToString("\n")
            )
        } catch (ignored: Exception) {}
    }

    fun pinnedCount(): Int = pinnedDir.listFiles { _, name -> name.endsWith(".pcm") }?.size ?: 0

    fun clearPinned() {
        pinnedMemory.clear()
        pinnedDir.listFiles()?.forEach { it.delete() }
    }

    fun count(): Int {
        val files = cacheDir.listFiles { _, name -> name.endsWith(".pcm") }
        return files?.size ?: 0
    }

    fun clear() {
        memoryCache.evictAll()
        cacheDir.listFiles()?.forEach { it.delete() }
    }

    /**
     * Cache key over everything that changes the audio. [voiceStyle] is the id of the
     * [VoiceStylePreset] (e.g. `SEXY`) so clips rendered in different styles never mix, even if two
     * presets would share a voice or prompt text.
     */
    fun computeKey(
        model: String,
        voice: String,
        stylePrompt: String,
        politeStyle: PoliteEndingStyle = PoliteEndingStyle.MALE_DEFAULT,
        speechRate: Float = 1.0f,
        pitch: Float = 1.0f,
        text: String,
        voiceStyle: String = ""
    ): String {
        val rateFormatted = String.format(Locale.US, "%.2f", speechRate)
        val pitchFormatted = String.format(Locale.US, "%.2f", pitch)
        val input = "$model|$voice|$stylePrompt|${politeStyle.name}|$rateFormatted|$pitchFormatted|$voiceStyle|$text"
        val md = MessageDigest.getInstance("SHA-256")
        val bytes = md.digest(input.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }
}
