package com.offlinevoice.keyboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sin
import kotlin.random.Random

class SpeechChunkerTest {
    private val rate = 16_000
    private val frame = rate / 10 // 100 ms, like the recorder
    private val rnd = Random(42)

    private fun speech(seconds: Double) = FloatArray((seconds * rate).toInt()) {
        (0.3 * sin(2 * Math.PI * 220 * it / rate)).toFloat()
    }

    private fun silence(seconds: Double) = FloatArray((seconds * rate).toInt()) {
        (rnd.nextFloat() - 0.5f) * 0.002f
    }

    private class Run(val chunks: List<FloatArray>, val rest: FloatArray?)

    private fun run(vararg parts: FloatArray): Run {
        val chunks = ArrayList<FloatArray>()
        val c = SpeechChunker(onChunk = { chunks += it })
        val all = parts.reduce { a, b -> a + b }
        var i = 0
        while (i < all.size) {
            val end = minOf(i + frame, all.size)
            c.feed(all.copyOfRange(i, end))
            i = end
        }
        return Run(chunks, c.finish())
    }

    private fun secs(a: FloatArray?) = (a?.size ?: 0) / rate.toDouble()

    @Test fun shortDictationIsOnePieceAtTheEnd() {
        val r = run(speech(1.5), silence(0.6), speech(1.5))
        assertEquals(0, r.chunks.size)
        assertEquals(3.6, secs(r.rest), 0.11)
    }

    @Test fun cutsAtAPauseOnceLongEnough() {
        val r = run(speech(5.0), silence(0.6), speech(3.0))
        assertEquals(1, r.chunks.size)
        // Each piece keeps up to 0.3 s of the pause as a natural edge.
        assertEquals(5.3, secs(r.chunks[0]), 0.11)
        assertEquals(3.3, secs(r.rest), 0.11)
    }

    @Test fun shortBreathsDoNotCut() {
        val r = run(speech(5.0), silence(0.2), speech(3.0))
        assertEquals(0, r.chunks.size)
    }

    @Test fun longSpeechWithoutPausesIsForcedIntoPieces() {
        val r = run(speech(45.0))
        assertTrue("expected at least 2 pieces, got ${r.chunks.size}", r.chunks.size >= 2)
        r.chunks.forEach { assertTrue(secs(it) <= 20.05) }
    }

    @Test fun longDictationLeavesOnlyTheLastBitForTheEnd() {
        // About a minute of talking in sentences with natural pauses.
        val parts = ArrayList<FloatArray>()
        repeat(10) {
            parts += speech(5.0)
            parts += silence(0.7)
        }
        parts += speech(2.0)
        val r = run(*parts.toTypedArray())
        assertTrue(r.chunks.size >= 9)
        assertTrue("left for the end: ${secs(r.rest)} s", secs(r.rest) < 3.0)
    }

    private fun loudSamples(a: FloatArray) = a.count { it > 0.1f || it < -0.1f }

    @Test fun noSpeechIsLost() {
        val input = listOf(speech(6.0), silence(0.8), speech(7.0), silence(2.5), speech(2.0), speech(30.0))
        val r = run(*input.toTypedArray())
        val inSpeech = input.sumOf { loudSamples(it) }
        val outSpeech = r.chunks.sumOf { loudSamples(it) } + (r.rest?.let { loudSamples(it) } ?: 0)
        assertEquals(inSpeech, outSpeech)
    }

    @Test fun longPausesAreNotProcessed() {
        val r = run(speech(5.0), silence(4.0), speech(2.0), silence(3.0))
        // The second sentence is already done during the pause at the end,
        // before the person lets go; the long silences are skipped.
        assertEquals(2, r.chunks.size)
        assertEquals(5.3, secs(r.chunks[0]), 0.11)
        assertEquals(2.6, secs(r.chunks[1]), 0.11) // 0.3 s lead-in + 2 s + 0.3 s tail
        assertNull(r.rest)
    }

    @Test fun silenceOnlyGivesNothing() {
        val r = run(silence(6.0))
        assertEquals(0, r.chunks.size)
        assertNull(r.rest)
    }
}
