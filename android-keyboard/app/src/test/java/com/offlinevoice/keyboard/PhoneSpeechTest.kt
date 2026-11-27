package com.offlinevoice.keyboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneSpeechTest {
    @Test fun phrasesSeparatedByPausesBecomeSentences() {
        val joined = PhoneSpeech.join(listOf("please send the invoice to finance", "thank you"))
        assertEquals("Please send the invoice to finance. Thank you.", TextCleanup.clean(joined))
    }

    @Test fun aShortPhraseRunsOnIntoTheNext() {
        val joined = PhoneSpeech.join(listOf("I think", "we should move the meeting to Friday"))
        assertEquals("I think we should move the meeting to Friday.", TextCleanup.clean(joined))
    }

    @Test fun punctuationFromThePhoneIsKept() {
        val joined = PhoneSpeech.join(listOf("Is the report ready?", "Please check."))
        assertEquals("Is the report ready? Please check.", TextCleanup.clean(joined))
    }

    @Test fun emptyPiecesAreIgnored() {
        assertEquals("", PhoneSpeech.join(listOf(" ", "")))
    }

    @Test fun loudnessMapsIntoTheBubbleRange() {
        assertEquals(0f, PhoneSpeech.level(-2f), 0.0001f)
        assertEquals(0.25f, PhoneSpeech.level(10f), 0.0001f)
        assertEquals(0.25f, PhoneSpeech.level(40f), 0.0001f)
        assertTrue(PhoneSpeech.level(4f) in 0.05f..0.08f)
    }
}
