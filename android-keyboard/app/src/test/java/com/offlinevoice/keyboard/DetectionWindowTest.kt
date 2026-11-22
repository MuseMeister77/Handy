package com.offlinevoice.keyboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.math.sin

class DetectionWindowTest {
    private val rate = 16_000
    private val frame = rate / 10

    private fun speechFrame() = FloatArray(frame) { (0.3 * sin(2 * Math.PI * 220 * it / rate)).toFloat() }
    private fun silentFrame() = FloatArray(frame)

    @Test fun readyAfterFourSecondsOfSpeech() {
        val w = DetectionWindow()
        repeat(39) { assertNull(w.feed(speechFrame())) }
        val out = w.feed(speechFrame())
        assertNotNull(out)
        assertEquals(4 * rate, out!!.size)
        // Only once.
        assertNull(w.feed(speechFrame()))
        assertNull(w.flush())
    }

    @Test fun silenceBeforeSpeechIsNotCounted() {
        val w = DetectionWindow()
        repeat(30) { assertNull(w.feed(silentFrame())) } // 3 s of silence first
        repeat(37) { assertNull(w.feed(speechFrame())) }
        // 200 ms lead-in + 3.8 s of speech = 4 s.
        val out = w.feed(speechFrame())
        assertNotNull(out)
        assertEquals(4 * rate, out!!.size)
    }

    @Test fun shortDictationIsFlushedAtTheEnd() {
        val w = DetectionWindow()
        repeat(12) { w.feed(speechFrame()) }
        assertEquals(frame * 12, w.flush()!!.size)
    }

    @Test fun silenceOnlyGivesNothing() {
        val w = DetectionWindow()
        repeat(60) { assertNull(w.feed(silentFrame())) }
        assertNull(w.flush())
    }
}
