package com.offlinevoice.keyboard

import kotlin.math.sqrt

/**
 * Splits a live recording into pieces at natural pauses, so earlier pieces
 * can be turned into text while the person is still speaking. When they stop,
 * only the last piece is left to do, which keeps the wait short however long
 * they talked.
 *
 * Pure Kotlin (no Android), fed with short frames of 16 kHz audio.
 */
class SpeechChunker(
    private val onChunk: (FloatArray) -> Unit,
    sampleRate: Int = AudioRecorder.SAMPLE_RATE,
    minChunkSeconds: Float = 4f,
    maxChunkSeconds: Float = 20f,
    pauseSeconds: Float = 0.5f,
) {
    private val minSamples = (minChunkSeconds * sampleRate).toInt()
    private val maxSamples = (maxChunkSeconds * sampleRate).toInt()
    private val pauseSamples = (pauseSeconds * sampleRate).toInt()
    private val minSpeechSamples = sampleRate * 3 / 10 // 300 ms of voice
    /** Silence kept before and after the speech in each piece. */
    private val keepTrailingSilence = sampleRate * 3 / 10
    private val lookBackSamples = sampleRate * 3

    private val frames = ArrayList<FloatArray>()
    private val levels = ArrayList<Float>()
    private var buffered = 0
    // Start from "quiet room": recordings often begin mid-word, so the first
    // frames can't be trusted as background noise.
    private var noiseFloor = 0.002f

    /**
     * Loudness below which a frame counts as a pause. Adapts to background
     * noise, but is capped so that quiet speech never counts as a pause.
     */
    private val threshold: Float get() = (noiseFloor * 3f).coerceIn(0.006f, 0.02f)

    fun feed(frame: FloatArray) {
        if (frame.isEmpty()) return
        val level = rms(frame)
        // Track the quietest recent level as the background noise, letting it
        // drift up slowly in case the room gets noisier.
        noiseFloor = minOf(level, noiseFloor + 0.0002f)
        frames += frame
        levels += level
        buffered += frame.size

        if (buffered < minSamples) return
        val quiet = trailingQuietSamples()
        when {
            quiet >= pauseSamples -> cutInsideTrailingPause()
            buffered >= maxSamples -> cutAtQuietestRecentFrame()
        }
    }

    /**
     * Returns whatever is left once recording stops, with long trailing
     * silence trimmed, or null if it holds no speech.
     */
    fun finish(): FloatArray? {
        // Trim silence at the end, keeping a short natural tail.
        var quiet = trailingQuietSamples()
        var keep = frames.size
        while (keep > 0 && levels[keep - 1] < threshold &&
            quiet - frames[keep - 1].size >= keepTrailingSilence
        ) {
            quiet -= frames[keep - 1].size
            keep--
        }
        val rest = take(keep)
        frames.clear()
        levels.clear()
        buffered = 0
        return rest
    }

    private fun trailingQuietSamples(): Int {
        var n = 0
        for (i in frames.indices.reversed()) {
            if (levels[i] >= threshold) break
            n += frames[i].size
        }
        return n
    }

    /**
     * Cut inside the pause, keeping a short natural tail of silence. The rest
     * of the pause is trimmed as leading silence of the next piece.
     */
    private fun cutInsideTrailingPause() {
        var quietFrames = 0
        for (i in frames.indices.reversed()) {
            if (levels[i] >= threshold) break
            quietFrames++
        }
        val speechEnd = frames.size - quietFrames
        var keepFrames = 0
        var kept = 0
        while (keepFrames < quietFrames && kept + frames[speechEnd + keepFrames].size <= keepTrailingSilence) {
            kept += frames[speechEnd + keepFrames].size
            keepFrames++
        }
        emit(speechEnd + keepFrames)
    }

    /** No pause for a long time: cut at the quietest frame of the last 3 s. */
    private fun cutAtQuietestRecentFrame() {
        var best = frames.size - 1
        var seen = 0
        var i = frames.size - 1
        while (i > 0 && seen < lookBackSamples) {
            if (levels[i] < levels[best]) best = i
            seen += frames[i].size
            i--
        }
        emit(best + 1)
    }

    private fun emit(count: Int) {
        val chunk = take(count)
        repeat(count.coerceAtMost(frames.size)) {
            buffered -= frames[0].size
            frames.removeAt(0)
            levels.removeAt(0)
        }
        if (chunk != null) onChunk(chunk)
    }

    /**
     * Joins the first [count] frames, skipping long silence at the start
     * (a short lead-in is kept). Returns null if they hold no speech.
     */
    private fun take(count: Int): FloatArray? {
        val n = count.coerceIn(0, frames.size)
        val firstSpeech = (0 until n).firstOrNull { levels[it] >= threshold } ?: return null
        var start = firstSpeech
        var lead = 0
        while (start > 0 && lead + frames[start - 1].size <= keepTrailingSilence) {
            lead += frames[start - 1].size
            start--
        }
        var size = 0
        var speech = 0
        for (i in start until n) {
            size += frames[i].size
            if (levels[i] >= threshold) speech += frames[i].size
        }
        if (speech < minSpeechSamples) return null
        val out = FloatArray(size)
        var pos = 0
        for (i in start until n) {
            frames[i].copyInto(out, pos)
            pos += frames[i].size
        }
        return out
    }

    private fun rms(f: FloatArray): Float {
        var s = 0.0
        for (v in f) s += v * v
        return sqrt(s / f.size).toFloat()
    }
}
