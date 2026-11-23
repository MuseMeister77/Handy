package com.offlinevoice.keyboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SegmentedPolisherTest {
    private fun words(s: String) = s.split(Regex("\\s+")).filter { it.isNotEmpty() }

    /** Fake AI: capitalises and records what it was sent. */
    private class Fake(val failOn: String? = null) : TextPolisher {
        val calls = ArrayList<String>()
        override fun polish(text: String): PolishResult {
            calls += text
            return if (failOn != null && failOn in text) PolishResult.Failed("usage limit reached")
            else PolishResult.Ok(text.uppercase())
        }
    }

    private val sentence = "the invoice is checked by the clerk and approved by the manager before posting."

    @Test fun shortTextIsSentWhole() {
        val f = Fake()
        assertEquals(PolishResult.Ok(sentence.uppercase()), SegmentedPolisher(f, PolishStyle.LIGHT).polish(sentence))
        assertEquals(1, f.calls.size)
    }

    @Test fun longTextIsSplitAtSentencesAndNothingIsLost() {
        val text = List(40) { sentence }.joinToString(" ") // 40 x 14 = 560 words
        val f = Fake()
        val r = SegmentedPolisher(f, PolishStyle.LIGHT, maxWords = 200).polish(text) as PolishResult.Ok
        assertEquals(3, f.calls.size)
        f.calls.forEach { assertTrue(words(it).size <= 200); assertTrue(it.endsWith(".")) }
        assertEquals(words(text.uppercase()), words(r.text))
    }

    @Test fun oneHugeRunOnSentenceIsSplitAtAndOrCommas() {
        val text = List(50) { "we check the bank letter and" }.joinToString(" ") + " we are done"
        val pieces = SegmentedPolisher.split(text, 200)
        assertTrue(pieces.size >= 2)
        pieces.forEach { assertTrue(words(it).size <= 200) }
        assertEquals(words(text), pieces.flatMap { words(it) })
    }

    @Test fun aFailedPieceKeepsSpokifyTextAndIsReported() {
        val text = List(20) { sentence }.joinToString(" ") + " " + List(20) { "the vendor bank details are verified by phone." }.joinToString(" ")
        val r = SegmentedPolisher(Fake(failOn = "vendor"), PolishStyle.POLISHED, maxWords = 200).polish(text)
        assertTrue(r is PolishResult.Partial)
        r as PolishResult.Partial
        assertEquals("part of it: usage limit reached", r.reason)
        assertTrue(r.text.contains("THE INVOICE IS CHECKED"))
        assertTrue(r.text.contains("the vendor bank details are verified"))
        assertTrue(r.text.contains("\n\n"))
    }

    @Test fun allPiecesFailingIsAFailure() {
        val text = List(40) { sentence }.joinToString(" ")
        val r = SegmentedPolisher(Fake(failOn = "invoice"), PolishStyle.LIGHT).polish(text)
        assertEquals(PolishResult.Failed("usage limit reached"), r)
    }
}
