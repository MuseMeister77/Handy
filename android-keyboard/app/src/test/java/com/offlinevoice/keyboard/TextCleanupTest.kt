package com.offlinevoice.keyboard

import org.junit.Assert.assertEquals
import org.junit.Test

class TextCleanupTest {

    private fun clean(s: String, fillers: Boolean = true, punct: Boolean = true) =
        TextCleanup.clean(s, TextCleanup.Options(fillers, punct))

    @Test fun trimsWhisperLeadingSpace() {
        assertEquals("Hello world.", clean(" Hello world."))
    }

    @Test fun removesFillers() {
        assertEquals("So I think we should go.", clean(" Um, so I think, uh, we should go."))
        assertEquals("Let me check.", clean("Hmm. Let me check."))
        assertEquals("The invoice is late.", clean("The, er, invoice is late."))
        assertEquals("We left. Then we ate.", clean("We left um. Then we ate."))
        assertEquals("", clean(" Um."))
    }

    @Test fun keepsFillersWhenDisabled() {
        assertEquals("Um, hello.", clean("Um, hello.", fillers = false))
    }

    @Test fun doesNotEatWordsContainingFillerLetters() {
        assertEquals("Ummah and Erik met the umpire.", clean("Ummah and Erik met the umpire."))
        assertEquals("The error was here.", clean("The error was here."))
    }

    @Test fun removesAnnotations() {
        assertEquals("", clean("[BLANK_AUDIO]"))
        assertEquals("Thanks.", clean("(upbeat music) Thanks."))
        assertEquals("Hi there.", clean("*laughs* Hi there."))
    }

    @Test fun spokenComma() {
        assertEquals("Dear team, please review.", clean("Dear team comma please review."))
        assertEquals("Dear team, please review.", clean("Dear team, comma, please review."))
    }

    @Test fun spokenFullStopAndQuestionMark() {
        assertEquals("Invoice received. Is it approved?", clean("Invoice received full stop is it approved question mark"))
    }

    @Test fun newLineAndParagraph() {
        assertEquals("Hi John,\nThanks for that.", clean("Hi John comma new line thanks for that."))
        assertEquals("First point.\n\nSecond point.", clean("First point. New paragraph. Second point."))
    }

    @Test fun periodIsNotACommand() {
        assertEquals(
            "Close the reporting period on Friday.",
            clean("Close the reporting period on Friday."),
        )
    }

    @Test fun spokenPunctuationCanBeDisabled() {
        assertEquals("Add a comma here.", clean("Add a comma here.", punct = false))
    }

    /** Real whisper.cpp output from the on-device engine, captured in testing. */
    @Test fun realWhisperOutput() {
        assertEquals(
            "Please send the file to the receivables team. By Friday.\n\nThanks for your help.",
            clean(" Please send the file to the receivables team. Uh, By Friday. New paragraph. Thanks for your help."),
        )
    }

    @Test fun fitAtStartOfField() {
        assertEquals("Hello.", TextCleanup.fitToContext("Hello.", ""))
        assertEquals("Hello.", TextCleanup.fitToContext("Hello.", null))
    }

    @Test fun fitAfterSentenceAddsSpace() {
        assertEquals(" Next one.", TextCleanup.fitToContext("Next one.", "Done."))
        assertEquals("Next one.", TextCleanup.fitToContext("Next one.", "Done. "))
    }

    @Test fun fitMidSentenceLowercasesCommonWord() {
        assertEquals(" and then we left.", TextCleanup.fitToContext("And then we left.", "We ate"))
        assertEquals(" the vendor file.", TextCleanup.fitToContext("The vendor file.", "Please send me,"))
    }

    @Test fun fitMidSentenceKeepsNamesAndI() {
        assertEquals(" Oracle is down.", TextCleanup.fitToContext("Oracle is down.", "I think"))
        assertEquals(" I agree.", TextCleanup.fitToContext("I agree.", "Yes"))
        assertEquals(" OTC is fine.", TextCleanup.fitToContext("OTC is fine.", "The"))
    }

    @Test fun fitAfterNewLine() {
        assertEquals("Thanks.", TextCleanup.fitToContext("Thanks.", "Hi,\n"))
    }
}
