package com.offlinevoice.keyboard

import org.junit.Assert.assertEquals
import org.junit.Test

class TidyTest {
    private fun clean(s: String) = TextCleanup.clean(s)

    // ---- Pauses ("...") -------------------------------------------------------

    @Test fun pauseMidSentenceJustContinues() {
        assertEquals("I think we should move the meeting.", clean(" I think... we should move the meeting."))
        assertEquals("I think we should go.", clean("I think... We should go."))
        assertEquals("Send the report to finance.", clean("Send the report… to finance."))
    }

    @Test fun pauseBeforeANewSentenceBecomesFullStop() {
        assertEquals("That's all for today. Thanks everyone.", clean("That's all for today... Thanks everyone."))
    }

    @Test fun trailingAndLeadingDotsGo() {
        assertEquals("Let me check.", clean("Let me check..."))
        assertEquals("So the plan is fine.", clean("...So the plan is fine."))
        // A spoken line break replaces the pause; no full stop is added before it.
        assertEquals("Done\nNext item.", clean("Done... new line next item."))
    }

    @Test fun piecesSplitAtAPauseAreJoinedBack() {
        assertEquals(
            "I think we should move the meeting to Friday.",
            clean(Tidy.joinPieces(listOf(" I think we should...", " Move the meeting to Friday."))),
        )
        assertEquals(
            "That's done. Oracle is next.",
            clean(Tidy.joinPieces(listOf("That's done.", "Oracle is next."))),
        )
        // Custom words and acronyms keep their capitals mid-sentence.
        assertEquals(
            "Please update Oracle and the OTC tracker.",
            clean(Tidy.joinPieces(listOf("Please update...", "Oracle and the...", "OTC tracker."), setOf("Oracle"))),
        )
    }

    // ---- Self-corrections -------------------------------------------------------

    @Test fun noWaitReplacesTheWordBefore() {
        assertEquals("Let's meet on Monday.", clean("Let's meet on Friday, no wait, Monday."))
        assertEquals("The total is 600 dollars.", clean("The total is 500, sorry, I mean 600 dollars."))
    }

    @Test fun correctionStartingWithARepeatedWordReplacesFromThere() {
        assertEquals("The call is at 4pm today.", clean("The call is at 3pm, no wait, at 4pm today."))
        assertEquals(
            "Send the report to Sarah and Mike.",
            clean("Send the report to John, no wait, to Sarah and Mike."),
        )
    }

    @Test fun namesAreReplacedWhole() {
        assertEquals("Please call Sarah Lee.", clean("Please call John Smith, no wait, Sarah Lee."))
    }

    @Test fun orRatherAndActuallyNo() {
        assertEquals("We need it by Tuesday.", clean("We need it by Monday, or rather Tuesday."))
        assertEquals("It's broken.", clean("It's fine, no actually, it's broken."))
    }

    @Test fun scratchThatDropsThePreviousSentence() {
        assertEquals("Hi John. Let's meet Monday.", clean("Hi John. Let's meet Friday. Scratch that. Let's meet Monday."))
    }

    @Test fun ordinaryNoAndSorryAreLeftAlone() {
        assertEquals("I can't make it, sorry, I'm busy.", clean("I can't make it, sorry, I'm busy."))
        assertEquals("No wait, let me check first.", clean("No wait, let me check first."))
        assertEquals("I said no, we can't.", clean("I said no, we can't."))
        assertEquals("I mean, it's fine.", clean("I mean, it's fine."))
    }

    // ---- Grammar ------------------------------------------------------------------

    @Test fun repeatedWords() {
        assertEquals("Send the file today.", clean("Send the the file today."))
        assertEquals("He had had enough.", clean("He had had enough."))
        assertEquals("That is very very good.", clean("That is very very good."))
    }

    @Test fun loneI() {
        assertEquals("Yes I think I'm right and I'll check.", clean("yes i think i'm right and i'll check"))
    }

    @Test fun aAndAn() {
        assertEquals("It's an invoice and a report.", clean("It's a invoice and an report."))
        assertEquals("It takes an hour, a user and a one-off fee.", clean("It takes a hour, an user and an one-off fee."))
        assertEquals("We need an MBA and a URL.", clean("We need an MBA and a URL."))
    }

    @Test fun missingSpaceAfterComma() {
        assertEquals("Hi, John.", clean("Hi,John"))
        assertEquals("It costs 1,000 dollars.", clean("It costs 1,000 dollars."))
    }

    @Test fun addsFinalFullStop() {
        assertEquals("Thanks for the update.", clean("Thanks for the update"))
        assertEquals("Is it done?", clean("Is it done?"))
    }

    @Test fun tidyCanBeTurnedOff() {
        val off = TextCleanup.Options(smartTidy = false)
        assertEquals("Meet on Friday, no wait, Monday", TextCleanup.clean("Meet on Friday, no wait, Monday", off))
    }

    // Real whisper.cpp output captured in testing.
    @Test fun realWhisperOutput() {
        assertEquals("Let's meet on Monday at 10.", clean(" Let's meet on Friday, no wait, Monday at 10."))
        assertEquals("The total is 600 dollars.", clean(" The total is 500, sorry, I mean 600 dollars."))
        // A piece that stopped at a pause with no punctuation at all.
        assertEquals(
            "I think we should move the meeting to Friday.",
            clean(Tidy.joinPieces(listOf(" I think", " We should move the meeting to Friday."))),
        )
    }
}
