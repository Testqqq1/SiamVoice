package com.example.audio

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.util.Log
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

data class DecodedPcmAudio(
    val pcmData: ByteArray,
    val sampleRate: Int,
    val channelCount: Int
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as DecodedPcmAudio
        if (!pcmData.contentEquals(other.pcmData)) return false
        if (sampleRate != other.sampleRate) return false
        if (channelCount != other.channelCount) return false
        return true
    }

    override fun hashCode(): Int {
        var result = pcmData.contentHashCode()
        result = 31 * result + sampleRate
        result = 31 * result + channelCount
        return result
    }
}

object AudioDecoder {
    private const val TAG = "AudioDecoder"
    private val RATE_REGEX = Regex("rate=(\\d+)", RegexOption.IGNORE_CASE)

    /**
     * Extracts sample rate from a MIME type such as `audio/L16;codec=pcm;rate=24000` or `audio/pcm;rate=24000`.
     */
    fun extractSampleRateFromMimeType(mimeType: String?, defaultRate: Int = 24000): Int {
        if (mimeType.isNullOrBlank()) return defaultRate
        val match = RATE_REGEX.find(mimeType)
        val parsed = match?.groupValues?.getOrNull(1)?.toIntOrNull()
        return if (parsed != null && parsed in 8000..96000) parsed else defaultRate
    }

    /**
     * Inspects the audio bytes and decodes them into standard 16-bit linear PCM bytes.
     * Supports WAV containers, encoded streams (MP3/AAC) via MediaCodec, and raw PCM (`audio/L16`, `audio/pcm`).
     */
    fun decodeToPcm(
        audioBytes: ByteArray,
        cacheDir: File? = null,
        fallbackSampleRate: Int = 24000,
        mimeType: String? = null
    ): DecodedPcmAudio {
        val effectiveFallbackRate = extractSampleRateFromMimeType(mimeType, fallbackSampleRate)
        if (audioBytes.isEmpty()) {
            return DecodedPcmAudio(ByteArray(0), effectiveFallbackRate, 1)
        }

        // 1. Check if the audio is a WAV/RIFF file
        if (isWavHeader(audioBytes)) {
            val wavPcm = parseWav(audioBytes)
            if (wavPcm != null) {
                return wavPcm
            }
        }

        // 2. Check if it's an MP3 or AAC file by header or try MediaCodec decoding
        if (isMp3OrAac(audioBytes) && cacheDir != null) {
            val codecResult = decodeWithMediaCodec(audioBytes, cacheDir)
            if (codecResult != null) {
                return codecResult
            }
        }

        // 3. Fallback: treat as raw 16-bit linear PCM (e.g. audio/L16;codec=pcm;rate=24000)
        return DecodedPcmAudio(
            pcmData = audioBytes,
            sampleRate = effectiveFallbackRate,
            channelCount = 1
        )
    }

    private fun isWavHeader(bytes: ByteArray): Boolean {
        if (bytes.size < 12) return false
        return bytes[0] == 'R'.code.toByte() &&
                bytes[1] == 'I'.code.toByte() &&
                bytes[2] == 'F'.code.toByte() &&
                bytes[3] == 'F'.code.toByte() &&
                bytes[8] == 'W'.code.toByte() &&
                bytes[9] == 'A'.code.toByte() &&
                bytes[10] == 'V'.code.toByte() &&
                bytes[11] == 'E'.code.toByte()
    }

    private fun parseWav(bytes: ByteArray): DecodedPcmAudio? {
        try {
            val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            var offset = 12 // Skip "RIFF" + size + "WAVE"
            var channels = 1
            var sampleRate = 24000

            var dataOffset = -1
            var dataSize = -1

            while (offset + 8 <= bytes.size) {
                val chunkId = String(bytes, offset, 4)
                val chunkSize = buffer.getInt(offset + 4)
                offset += 8

                if (chunkId == "fmt ") {
                    channels = buffer.getShort(offset + 2).toInt()
                    sampleRate = buffer.getInt(offset + 4)
                } else if (chunkId == "data") {
                    dataOffset = offset
                    dataSize = if (chunkSize > 0 && offset + chunkSize <= bytes.size) chunkSize else (bytes.size - offset)
                    break
                }
                offset += chunkSize
            }

            if (dataOffset != -1 && dataSize > 0) {
                val pcm = bytes.copyOfRange(dataOffset, dataOffset + dataSize)
                return DecodedPcmAudio(pcm, sampleRate, channels)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse WAV header: ${e.message}")
        }
        return null
    }

    private fun isMp3OrAac(bytes: ByteArray): Boolean {
        if (bytes.size < 3) return false
        if (bytes[0] == 'I'.code.toByte() && bytes[1] == 'D'.code.toByte() && bytes[2] == '3'.code.toByte()) {
            return true
        }
        if ((bytes[0].toInt() and 0xFF) == 0xFF && (bytes[1].toInt() and 0xE0) == 0xE0) {
            return true
        }
        return false
    }

    private fun decodeWithMediaCodec(audioBytes: ByteArray, cacheDir: File): DecodedPcmAudio? {
        var tempFile: File? = null
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            tempFile = File.createTempFile("tts_decode_", ".tmp", cacheDir)
            FileOutputStream(tempFile).use { it.write(audioBytes) }

            extractor.setDataSource(tempFile.absolutePath)
            if (extractor.trackCount == 0) return null

            extractor.selectTrack(0)
            val format = extractor.getTrackFormat(0)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: return null
            val sampleRate = if (format.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            } else 24000
            val channelCount = if (format.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            } else 1

            codec = MediaCodec.createDecoderByType(mime)
            codec.configure(format, null, null, 0)
            codec.start()

            val outputStream = ByteArrayOutputStream()
            val info = MediaCodec.BufferInfo()
            var isEos = false

            val timeoutUs = 10000L
            var attemptsWithoutOutput = 0

            while (!isEos && attemptsWithoutOutput < 50) {
                val inIndex = codec.dequeueInputBuffer(timeoutUs)
                if (inIndex >= 0) {
                    val inputBuffer = codec.getInputBuffer(inIndex)
                    if (inputBuffer != null) {
                        val sampleSize = extractor.readSampleData(inputBuffer, 0)
                        if (sampleSize < 0) {
                            codec.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            isEos = true
                        } else {
                            codec.queueInputBuffer(inIndex, 0, sampleSize, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }

                val outIndex = codec.dequeueOutputBuffer(info, timeoutUs)
                if (outIndex >= 0) {
                    attemptsWithoutOutput = 0
                    val outputBuffer = codec.getOutputBuffer(outIndex)
                    if (outputBuffer != null && info.size > 0) {
                        outputBuffer.position(info.offset)
                        outputBuffer.limit(info.offset + info.size)
                        val chunk = ByteArray(info.size)
                        outputBuffer.get(chunk)
                        outputStream.write(chunk)
                    }
                    codec.releaseOutputBuffer(outIndex, false)
                    if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        break
                    }
                } else {
                    attemptsWithoutOutput++
                }
            }

            val decodedBytes = outputStream.toByteArray()
            if (decodedBytes.isNotEmpty()) {
                return DecodedPcmAudio(decodedBytes, sampleRate, channelCount)
            }
        } catch (e: Exception) {
            Log.e(TAG, "MediaCodec decoding error: ${e.message}", e)
        } finally {
            try {
                codec?.stop()
                codec?.release()
                extractor.release()
            } catch (ignored: Exception) {}
            tempFile?.delete()
        }
        return null
    }

    /**
     * Wraps raw 16-bit PCM data in a standard 44-byte RIFF/WAV header
     */
    fun createWav(pcmData: ByteArray, sampleRate: Int = 24000, channels: Int = 1): ByteArray {
        val totalDataLen = pcmData.size + 36
        val byteRate = sampleRate * channels * 2
        val header = ByteArray(44)
        val buf = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)

        buf.put("RIFF".toByteArray())
        buf.putInt(totalDataLen)
        buf.put("WAVE".toByteArray())
        buf.put("fmt ".toByteArray())
        buf.putInt(16) // Subchunk1Size (16 for PCM)
        buf.putShort(1) // AudioFormat (1 for PCM)
        buf.putShort(channels.toShort())
        buf.putInt(sampleRate)
        buf.putInt(byteRate)
        buf.putShort((channels * 2).toShort()) // BlockAlign
        buf.putShort(16) // BitsPerSample
        buf.put("data".toByteArray())
        buf.putInt(pcmData.size)

        return header + pcmData
    }
}
