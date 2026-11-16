package com.example.engine

import com.example.model.SynthesisResult

/** Concatenates pinned phrase clips ("อีก 200 เมตร" + "เลี้ยวซ้าย") into one PCM clip. */
object NavigationClipJoiner {
    /** Natural-sounding gap inserted between two clips. */
    const val GAP_MS = 120

    /** Returns null when the clips can't be joined (empty list or mismatching PCM format). */
    fun join(clips: List<SynthesisResult>, gapMs: Int = GAP_MS): SynthesisResult? {
        if (clips.isEmpty()) return null
        val first = clips.first()
        if (clips.any { it.sampleRate != first.sampleRate || it.channelCount != first.channelCount }) return null

        val bytesPerFrame = 2 * first.channelCount
        val gapFrames = first.sampleRate * gapMs / 1000
        val gapBytes = ByteArray(gapFrames * bytesPerFrame)

        val total = clips.sumOf { it.audioData.size } + gapBytes.size * (clips.size - 1)
        val out = ByteArray(total)
        var offset = 0
        clips.forEachIndexed { index, clip ->
            if (index > 0) {
                System.arraycopy(gapBytes, 0, out, offset, gapBytes.size)
                offset += gapBytes.size
            }
            System.arraycopy(clip.audioData, 0, out, offset, clip.audioData.size)
            offset += clip.audioData.size
        }
        return first.copy(
            audioData = out,
            wasCached = true,
            processedText = clips.joinToString(" ") { it.processedText }
        )
    }
}
