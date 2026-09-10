package ai.voiceflow.voiceflow_mobile

import java.util.Locale
import org.junit.Assert.*
import org.junit.Test

class PunctuationSpanRendererTest {
    private val labels = listOf("<NULL>", ".", ",", "?", "<ACRONYM>")
    private fun token(raw: String, begin: Int, end: Int, piece: String? = null, unknown: Boolean = false) =
        PunctuationToken(42, piece ?: raw.substring(begin, end).lowercase(Locale.ROOT),
            raw.substring(begin, end).lowercase(Locale.ROOT), begin, end, unknown)
    private fun caps(vararg positions: Int) = IntArray(16).also { array -> positions.forEach { array[it] = 1 } }
    private fun run(raw: String, tokens: List<PunctuationToken>, posts: IntArray,
        capitals: Array<IntArray> = Array(tokens.size) { caps() }) =
        PunctuationSpanRenderer.render(raw, tokens, posts, capitals, labels)

    @Test fun frozenMixedQuestionMatchesHostLabels() {
        // Frozen short-question fixture and XLM-R first-repeat labels, not a
        // synthetic target substitution. Internal subword ends remain untouched.
        val raw = "can you check apakah pintunya sudah dikunci"
        val pieces = listOf("▁can", "▁you", "▁check", "▁apakah", "▁pintu", "nya", "▁sudah", "▁di", "kun", "ci")
        val starts = listOf(0, 3, 7, 13, 20, 26, 29, 35, 38, 41)
        val ends = listOf(3, 7, 13, 20, 26, 29, 35, 38, 41, 43)
        val tokens = pieces.indices.map { token(raw, starts[it], ends[it], pieces[it]) }
        val posts = IntArray(10).also { it[9] = 3 }
        val capitals = Array(10) { caps() }.also { it[0] = caps(1) }
        val result = run(raw, tokens, posts, capitals)
        assertEquals("Can you check apakah pintunya sudah dikunci?", result.candidateText)
        assertEquals("changed_review_required", result.outcome)
        assertTrue(result.guardFlags.isEmpty())
    }

    @Test fun preservesEmojiAccentsOriginalGapsAndParagraphs() {
        val raw = "😀 café\n\n  halo"
        val tokens = listOf(token(raw, 0, 2, unknown = true), token(raw, 3, 7, "▁café"), token(raw, 11, 15, "▁halo"))
        val result = run(raw, tokens, intArrayOf(1, 1, 3), arrayOf(caps(0), caps(1), caps(1)))
        assertEquals("😀 Café.\n\n  Halo?", result.candidateText)
        assertTrue(result.skippedActions.any { it.reason == "unknown_token" })
    }

    @Test fun capitalizationIndexesAreCodepointsNotUtf16Units() {
        val letter = String(Character.toChars(0x10428)) // Deseret lowercase.
        val upper = String(Character.toChars(0x10400))
        val raw = "$letter hi"
        val tokens = listOf(token(raw, 0, 2, "▁$letter"), token(raw, 2, 5, "▁hi"))
        assertEquals("$upper Hi.", run(raw, tokens, intArrayOf(0, 1), arrayOf(caps(1), caps(1))).candidateText)
    }

    @Test fun normalizationLossUnknownAndCaseExpansionAreSkipped() {
        val raw = "ﬁle ßeta"
        val tokens = listOf(token(raw, 0, 3, "file"), token(raw, 4, 8, "▁ßeta"))
        val result = run(raw, tokens, intArrayOf(1, 0), arrayOf(caps(0), caps(1)))
        assertEquals(raw, result.candidateText)
        assertTrue(result.skippedActions.any { it.reason == "normalized_piece_surface_mismatch" })
        assertTrue(result.skippedActions.any { it.reason == "case_expansion" })
    }

    @Test fun neverChangesOriginalCapitalizationOrInternalLetters() {
        val raw = "iPhone sama"
        val tokens = listOf(token(raw, 0, 6, "▁iphone"), token(raw, 7, 11, "▁sama"))
        val result = run(raw, tokens, intArrayOf(0, 0), arrayOf(caps(1), caps(2, 3)))
        assertEquals(raw, result.candidateText)
        assertTrue(result.skippedActions.any { it.reason == "original_capitalization" })
        assertTrue(result.skippedActions.any { it.reason == "not_first_atom_letter" })
    }

    @Test fun numericAndUnitLiteralsCannotBeSplitOrRecased() {
        for (raw in listOf("5mg", "5 mg", "5 m", "09:30", "-5", "+8", "1.250,50")) {
            val tokens = Regex("\\S+").findAll(raw).map { token(raw, it.range.first, it.range.last + 1) }.toList()
            val result = run(raw, tokens, IntArray(tokens.size) { 1 }, Array(tokens.size) { caps(0, 1) })
            // Decimal/grouping text also matches the conservative domain span.
            assertEquals(if (raw == "1.250,50") raw else "$raw.", result.candidateText)
        }
        val raw = "jam 9 bukan"
        val tokens = listOf(token(raw, 0, 3), token(raw, 4, 5), token(raw, 6, 11))
        assertEquals("Jam 9. Bukan", run(raw, tokens, intArrayOf(0, 1, 0),
            arrayOf(caps(0), caps(), caps(0))).candidateText)
    }

    @Test fun codeUrlsAndOperatorLinesRemainExact() {
        for (raw in listOf("C++", "user_id", "team@example.com", "example.com/docs", "a != b", "`hello world`")) {
            val tokens = Regex("\\S+").findAll(raw).map { token(raw, it.range.first, it.range.last + 1) }.toList()
            val result = run(raw, tokens, IntArray(tokens.size) { 1 }, Array(tokens.size) { caps(0) })
            assertEquals(raw, result.candidateText)
        }
    }

    @Test fun repeatedTokensRetainEveryOccurrenceAndExistingPunctuation() {
        val raw = "hi hi hi."
        val tokens = listOf(token(raw, 0, 2, "▁hi"), token(raw, 2, 5, "▁hi"), token(raw, 5, 9, "▁hi."))
        assertEquals("Hi, hi, hi.", run(raw, tokens, intArrayOf(2, 2, 1), arrayOf(caps(1), caps(), caps())).candidateText)
    }

    @Test fun unsupportedLabelsAndIncompleteAtomsCannotAlterText() {
        val raw = "hello"
        val tokens = listOf(token(raw, 0, 2), token(raw, 2, 5))
        val result = run(raw, tokens, intArrayOf(1, 4))
        assertEquals(raw, result.candidateText)
        assertEquals(listOf("incomplete_atom", "unsupported_label"), result.skippedActions.map { it.reason })
    }

    @Test fun malformedMetadataAndLabelsFailBeforeRendering() {
        val raw = "hi"
        val valid = token(raw, 0, 2, "▁hi")
        for (bad in listOf(valid.copy(id = -1), valid.copy(beginUtf16 = -1), valid.copy(endUtf16 = 3),
            valid.copy(surface = "wrong"), valid.copy(piece = "a".repeat(17)))) {
            assertThrows(IllegalArgumentException::class.java) { run(raw, listOf(bad), intArrayOf(1)) }
        }
        assertThrows(IllegalArgumentException::class.java) { run(raw, listOf(valid, valid), intArrayOf(0, 0)) }
        assertThrows(IllegalArgumentException::class.java) { run(raw, listOf(valid), intArrayOf(-1)) }
        assertThrows(IllegalArgumentException::class.java) { run(raw, listOf(valid), intArrayOf(9)) }
        assertThrows(IllegalArgumentException::class.java) { run(raw, listOf(valid), intArrayOf(0), arrayOf(IntArray(15))) }
        assertThrows(IllegalArgumentException::class.java) { run(raw, listOf(valid), intArrayOf(0), arrayOf(IntArray(16) { 2 })) }
        assertThrows(IllegalArgumentException::class.java) { run("İ", emptyList(), intArrayOf()) }
        assertThrows(IllegalArgumentException::class.java) { run("\uD800", emptyList(), intArrayOf()) }
        assertThrows(IllegalArgumentException::class.java) { run("hi\u0000", emptyList(), intArrayOf()) }
        val emoji = PunctuationToken(1, "?", "?", 1, 2, false)
        assertThrows(IllegalArgumentException::class.java) { run("😀", listOf(emoji), intArrayOf(0)) }
    }
}
