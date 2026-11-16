package com.example.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class PcmPlayer {
    private var currentTrack: AudioTrack? = null
    private val lock = Any()

    suspend fun play(pcmData: ByteArray, sampleRate: Int = 24000, channelCount: Int = 1) = withContext(Dispatchers.IO) {
        stop()
        if (pcmData.isEmpty()) return@withContext

        val channelConfig = if (channelCount == 2) {
            AudioFormat.CHANNEL_OUT_STEREO
        } else {
            AudioFormat.CHANNEL_OUT_MONO
        }

        val bufferSize = AudioTrack.getMinBufferSize(
            sampleRate,
            channelConfig,
            AudioFormat.ENCODING_PCM_16BIT
        ).coerceAtLeast(pcmData.size)

        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(channelConfig)
                    .build()
            )
            .setBufferSizeInBytes(bufferSize)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()

        synchronized(lock) {
            currentTrack = track
        }

        try {
            track.play()
            var offset = 0
            val chunkSize = 4096
            while (offset < pcmData.size) {
                synchronized(lock) {
                    if (currentTrack != track) return@withContext
                }
                val length = (pcmData.size - offset).coerceAtMost(chunkSize)
                val written = track.write(pcmData, offset, length)
                if (written < 0) {
                    synchronized(lock) {
                        if (currentTrack != track) return@withContext // stopped by the user
                    }
                    throw IllegalStateException("AudioTrack.write failed with code $written")
                }
                offset += written
            }

            // MODE_STREAM write() returns as soon as the data is buffered. Releasing the track right
            // away cuts the clip off (silence on short phrases), so wait until it has really played.
            val bytesPerFrame = 2 * (if (channelCount == 2) 2 else 1)
            val totalFrames = pcmData.size / bytesPerFrame
            val durationMs = totalFrames * 1000L / sampleRate
            val deadline = System.currentTimeMillis() + durationMs + 2000L
            while (System.currentTimeMillis() < deadline) {
                synchronized(lock) {
                    if (currentTrack != track) return@withContext
                }
                if (track.playbackHeadPosition >= totalFrames) break
                kotlinx.coroutines.delay(30)
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e("PcmPlayer", "Playback error: ${e.message}")
            throw e
        } finally {
            try {
                track.stop()
                track.release()
            } catch (ignored: Exception) {}
            synchronized(lock) {
                if (currentTrack == track) {
                    currentTrack = null
                }
            }
        }
    }

    fun stop() {
        synchronized(lock) {
            try {
                currentTrack?.pause()
                currentTrack?.flush()
                currentTrack?.stop()
                currentTrack?.release()
            } catch (e: Exception) {
                Log.w("PcmPlayer", "Error stopping track: ${e.message}")
            }
            currentTrack = null
        }
    }
}
