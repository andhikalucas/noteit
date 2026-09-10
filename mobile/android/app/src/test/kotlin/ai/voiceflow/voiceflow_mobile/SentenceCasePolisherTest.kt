package ai.voiceflow.voiceflow_mobile

import org.junit.Assert.*
import org.junit.Test

class SentenceCasePolisherTest {
    @Test fun closesKnownMissingSentenceCapitalizationWithoutChangingWords() {
        val raw = "I only want to move it. please don't rename anything."
        val result = SentenceCasePolisher.apply(raw)
        assertEquals("I only want to move it. Please don't rename anything.", result.text)
        assertEquals(1, result.edits.size)
        val edit = result.edits.single()
        assertEquals(raw.indexOf("please"), edit.beginUtf16)
        assertEquals("p", edit.original)
        assertEquals("P", edit.replacement)
        assertEquals("sentence_start", edit.reason)
    }

    @Test fun handlesStartQuestionAndExclamationBoundaries() {
        assertEquals("Besok aku meeting. Please check dulu! Sudah siap? Kita mulai.",
            SentenceCasePolisher.apply("besok aku meeting. please check dulu! sudah siap? kita mulai").text)
        assertEquals("Really?! Please check.", SentenceCasePolisher.apply("really?! please check").text)
    }

    @Test fun preservesEveryGapAndExistingParagraph() {
        val raw = "  halo\tsemua.\r\n\r\nplease check\n\n"
        assertEquals("  Halo\tsemua.\r\n\r\nPlease check.\n\n", SentenceCasePolisher.apply(raw).text)
        // Blank lines alone do not synthesize a sentence boundary or paragraph.
        assertEquals("Halo\n\nplease check.", SentenceCasePolisher.apply("halo\n\nplease check").text)
    }

    @Test fun abbreviationsInitialismsAndEllipsesAreNotBlindSentenceBoundaries() {
        for ((raw, expected) in listOf(
            "Dr. smith is here." to "Dr. smith is here.",
            "ask Prof. budi first." to "Ask Prof. budi first.",
            "use e.g. local text." to "Use e.g. local text.",
            "see J. smith." to "See J. smith.",
            "the U.S. office." to "The U.S. office.",
            "contoh dll. jangan ubah." to "Contoh dll. jangan ubah.",
            "well... please wait." to "Well... please wait."
        )) assertEquals(raw, expected, SentenceCasePolisher.apply(raw).text)
    }

    @Test fun protectsExistingCapitalizationNamesAndQuotedContent() {
        for ((raw, expected) in listOf(
            "iPhone works. iPad stays." to "iPhone works. iPad stays.",
            "send to Tiara. please check." to "Send to Tiara. Please check.",
            "\"please stop. don't move\"" to "\"please stop. don't move\"",
            "'hello. please wait'" to "'hello. please wait'",
            "'hello. please wait" to "'hello. please wait",
            "say “hello. please wait”" to "Say “hello. please wait”"
        )) assertEquals(raw, expected, SentenceCasePolisher.apply(raw).text)
    }

    @Test fun codeUrlsEmailAndNumericBoundariesStayProtected() {
        for (raw in listOf("git status", "curl https://example.com", "user_id", "C++", "a != b",
            "https://example.com", "team@example.com", "`please run this`")) {
            assertEquals(raw, SentenceCasePolisher.apply(raw).text)
        }
        assertEquals("Cost 1.25. next value.", SentenceCasePolisher.apply("cost 1.25. next value.").text)
        assertEquals("Use example.com. next step.", SentenceCasePolisher.apply("use example.com. next step.").text)
        assertEquals("Meeting starts at 9", SentenceCasePolisher.apply("meeting starts at 9").text)
    }

    @Test fun curlySingleAndMultilineQuotedLiteralsAreNeverPolishedInside() {
        for (raw in listOf(
            "‘hello. world’",
            "‘hello.\nworld. please wait’",
            "\"hello.\r\nworld. please wait\"",
            "“hello.\n\nworld. please wait”",
            "'hello.\nworld. please wait'",
            "`hello.\nworld. please wait`",
            "\"hello \\\"there\\\".\nworld. please wait\"",
        )) {
            val result = SentenceCasePolisher.apply(raw)
            assertEquals(raw, result.text)
            assertTrue(raw, result.edits.isEmpty())
        }
    }

    @Test fun unmatchedQuotesProtectAllRemainingLinesWithoutTreatingContractionsAsQuotes() {
        for (raw in listOf(
            "‘hello.\nworld. please wait",
            "\"hello.\nworld. please wait",
            "'hello.\nworld. please wait",
            "`hello.\nworld. please wait",
        )) {
            val result = SentenceCasePolisher.apply(raw)
            assertEquals(raw, result.text)
            assertTrue(raw, result.edits.isEmpty())
        }
        assertEquals("Don't change it. Please wait.",
            SentenceCasePolisher.apply("don't change it. please wait").text)
    }

    @Test fun unitsAndUnsafeTerminalAtomsNeverGetChanged() {
        for (raw in listOf("5mg", "5 mg", "9 mS", "09:30", "-5", "+8", "PDF", "Dr", "U.S.", "hello!", "hello?")) {
            val result = SentenceCasePolisher.apply(raw)
            assertFalse(raw, result.edits.any { it.reason == "terminal_period" })
        }
        assertEquals("Wait. mg remains unchanged.", SentenceCasePolisher.apply("wait. mg remains unchanged.").text)
        assertEquals("Hello.", SentenceCasePolisher.apply("hello").text)
    }

    @Test fun unicodeOffsetsAreUtf16AndCaseExpansionIsSkipped() {
        val result = SentenceCasePolisher.apply("😀. please check.")
        assertEquals("😀. please check.", result.text) // Emoji boundary is ambiguous.
        assertEquals("Café. École.", SentenceCasePolisher.apply("café. école.").text)
        assertEquals("ßeta.", SentenceCasePolisher.apply("ßeta.").text)
        val letter = String(Character.toChars(0x10428))
        val upper = String(Character.toChars(0x10400))
        val astral = SentenceCasePolisher.apply("$letter. please check.")
        assertEquals("$upper. please check.", astral.text) // Single-letter abbreviation: abstain after stop.
        assertEquals(2, astral.edits.single().endUtf16)
    }

    @Test fun editsReconstructOutputFromOriginalWithoutDeletingContent() {
        val raw = "halo. please check"
        val result = SentenceCasePolisher.apply(raw)
        val reconstructed = StringBuilder(raw)
        for (edit in result.edits.asReversed()) {
            assertEquals(raw.substring(edit.beginUtf16, edit.endUtf16), edit.original)
            reconstructed.replace(edit.beginUtf16, edit.endUtf16, edit.replacement)
        }
        assertEquals(result.text, reconstructed.toString())
        assertEquals(result.text, SentenceCasePolisher.apply(result.text).text)
    }

    @Test fun invalidOrOversizedSourceReturnsUnchanged() {
        for (raw in listOf("", "   ", "hi\u0000", "\uD800", "a".repeat(12001))) {
            val result = SentenceCasePolisher.apply(raw)
            assertEquals(raw, result.text)
            assertTrue(result.edits.isEmpty())
        }
    }
}
