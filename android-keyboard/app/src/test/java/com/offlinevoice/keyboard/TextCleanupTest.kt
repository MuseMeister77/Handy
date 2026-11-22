package com.offlinevoice.keyboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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

    @Test fun spliceIntoEmptyBox() {
        val r = TextCleanup.spliceAtCursor("", -1, -1, "Hello there.")
        assertEquals("Hello there.", r.text)
        assertEquals(12, r.cursor)
    }

    @Test fun spliceAtEndAddsSpace() {
        val r = TextCleanup.spliceAtCursor("Hi team.", 8, 8, "Invoice attached.")
        assertEquals("Hi team. Invoice attached.", r.text)
        assertEquals(r.text.length, r.cursor)
    }

    @Test fun spliceInMiddleKeepsTextAfterCursor() {
        val r = TextCleanup.spliceAtCursor("Dear John, Regards", 11, 11, "Thanks for the update.")
        assertEquals("Dear John, Thanks for the update. Regards", r.text)
        assertEquals(11 + "Thanks for the update. ".length, r.cursor)
    }

    @Test fun spliceReplacesSelection() {
        val r = TextCleanup.spliceAtCursor("Send it Friday.", 8, 14, "Monday")
        assertEquals("Send it Monday.", r.text)
    }

    @Test fun spliceWithReversedOrBadSelection() {
        assertEquals("Send it Monday.", TextCleanup.spliceAtCursor("Send it Friday.", 14, 8, "Monday").text)
        assertEquals("Hi. Ok.", TextCleanup.spliceAtCursor("Hi.", 99, 99, "Ok.").text)
    }

    @Test fun dropsWhisperRepeatedSentence() {
        assertEquals(
            "Please make sure your access is set up.",
            clean(" Please make sure your access is set up. Please make sure your access is set up."),
        )
        assertEquals(
            "Okay. Thanks. Thanks for the update.",
            clean("Okay. Thanks. Thanks for the update."),
        )
    }

    @Test fun keepsShortDeliberateRepeats() {
        assertEquals("No. No. Thank you.", clean("No. No. Thank you."))
        assertEquals("Yes, yes, that works.", clean("Yes, yes, that works."))
    }

    /** WhatsApp reports its grey "Message" placeholder as the box's text. */
    @Test fun whatsAppPlaceholderIsNotText() {
        assertEquals("", TextCleanup.existingText("Message", "Message", showingHint = false))
        assertEquals("", TextCleanup.existingText("Message", null, showingHint = true))
        assertEquals("", TextCleanup.existingText(null, "Message", showingHint = false))
        assertEquals("Hi Ali", TextCleanup.existingText("Hi Ali", "Message", showingHint = false))
    }

    @Test fun whatsAppDictationIntoEmptyBox() {
        val current = TextCleanup.existingText("Message", "Message", showingHint = false)
        val r = TextCleanup.spliceAtCursor(current, -1, -1, "Thank you so much.")
        assertEquals("Thank you so much.", r.text)
    }

    @Test fun placeholderGuessWhenCursorUnknown() {
        assertTrue(TextCleanup.looksLikePlaceholder("Message"))
        assertTrue(TextCleanup.looksLikePlaceholder("Type a message"))
        assertTrue(!TextCleanup.looksLikePlaceholder("Thanks, see you."))
        assertTrue(!TextCleanup.looksLikePlaceholder("I will send it tomorrow"))
        assertTrue(!TextCleanup.looksLikePlaceholder("ok"))
    }
}
