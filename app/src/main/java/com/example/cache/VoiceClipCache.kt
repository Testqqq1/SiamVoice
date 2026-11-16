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

    // Memory cache: max 50 entries
    private val memoryCache = object : LruCache<String, SynthesisResult>(50) {}

    fun get(
        modelName: String,
        voiceName: String,
        text: String,
        stylePrompt: String = VoiceStylePreset.fromVoiceOrName(voiceName).stylePrompt,
        politeStyle: PoliteEndingStyle = PoliteEndingStyle.MALE_DEFAULT,
        speechRate: Float = 1.0f,
        pitch: Float = 1.0f
    ): SynthesisResult? {
        val key = computeKey(
            model = modelName,
            voice = voiceName,
            stylePrompt = stylePrompt,
            politeStyle = politeStyle,
            speechRate = speechRate,
            pitch = pitch,
            text = text
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
        pitch: Float = 1.0f
    ) {
        val key = computeKey(
            model = modelName,
            voice = voiceName,
            stylePrompt = stylePrompt,
            politeStyle = politeStyle,
            speechRate = speechRate,
            pitch = pitch,
            text = text
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

    fun count(): Int {
        val files = cacheDir.listFiles { _, name -> name.endsWith(".pcm") }
        return files?.size ?: 0
    }

    fun clear() {
        memoryCache.evictAll()
        cacheDir.listFiles()?.forEach { it.delete() }
    }

    fun computeKey(
        model: String,
        voice: String,
        stylePrompt: String,
        politeStyle: PoliteEndingStyle = PoliteEndingStyle.MALE_DEFAULT,
        speechRate: Float = 1.0f,
        pitch: Float = 1.0f,
        text: String
    ): String {
        val rateFormatted = String.format(Locale.US, "%.2f", speechRate)
        val pitchFormatted = String.format(Locale.US, "%.2f", pitch)
        val input = "$model|$voice|$stylePrompt|${politeStyle.name}|$rateFormatted|$pitchFormatted|$text"
        val md = MessageDigest.getInstance("SHA-256")
        val bytes = md.digest(input.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }
}
