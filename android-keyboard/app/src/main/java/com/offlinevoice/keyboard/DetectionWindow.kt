package com.offlinevoice.keyboard

import kotlin.math.sqrt

/**
 * Collects the first few seconds of speech in a dictation, for the language
 * check. Starts at the first sound loud enough to be speech, so a pause
 * before talking doesn't waste the window. Pure Kotlin, unit tested.
 */
class DetectionWindow(
    seconds: Float = 4f,
    sampleRate: Int = AudioRecorder.SAMPLE_RATE,
) {
    private val target = (seconds * sampleRate).toInt()
    private val leadIn = sampleRate / 5 // keep 200 ms before the first sound
    private val frames = ArrayList<FloatArray>()
    private var collected = 0
    private var heardSpeech = false
    private var done = false

    /** Returns the window once, as soon as enough speech has been collected. */
    fun feed(frame: FloatArray): FloatArray? {
        if (done || frame.isEmpty()) return null
        if (!heardSpeech) {
            if (rms(frame) < SPEECH_LEVEL) {
                // Keep only a short lead-in of the silence before speech.
                frames += frame
                collected += frame.size
                while (frames.isNotEmpty() && collected - frames[0].size >= leadIn) {
                    collected -= frames.removeAt(0).size
                }
                return null
            }
            heardSpeech = true
        }
        frames += frame
        collected += frame.size
        return if (collected >= target) take() else null
    }

    /** Recording ended early: whatever was collected, or null if no speech. */
    fun flush(): FloatArray? = if (done || !heardSpeech) null else take()

    private fun take(): FloatArray {
        done = true
        val out = FloatArray(collected)
        var pos = 0
        for (f in frames) {
            f.copyInto(out, pos)
            pos += f.size
        }
        frames.clear()
        return out
    }

    private fun rms(f: FloatArray): Float {
        var s = 0.0
        for (v in f) s += v * v
        return sqrt(s / f.size).toFloat()
    }

    companion object {
        private const val SPEECH_LEVEL = 0.01f
    }
}
