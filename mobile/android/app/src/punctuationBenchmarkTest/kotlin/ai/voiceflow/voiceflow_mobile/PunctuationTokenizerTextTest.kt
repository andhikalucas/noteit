package ai.voiceflow.voiceflow_mobile

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.nio.charset.CharacterCodingException
import java.util.Locale

class PunctuationTokenizerTextTest {
    private data class Token(val id: Int, val begin: Int, val end: Int, val piece: String, val surface: String)

    private fun packet(tokens: List<Token>, version: Int = 1): ByteArray {
        val output = ByteArrayOutputStream()
        fun number(value: Int) { repeat(4) { output.write(value ushr (it * 8) and 255) } }
        fun text(value: String) { val bytes = value.toByteArray(Charsets.UTF_8); number(bytes.size); output.write(bytes) }
        listOf(version, 0, 1, 2, -1, 20, tokens.size).forEach(::number)
        tokens.forEach { number(it.id); number(it.begin); number(it.end); text(it.piece); text(it.surface) }
        return output.toByteArray()
    }

    @Test fun `UTF8 byte boundaries map emoji and accented text to UTF16 without splitting surrogates`() {
        assertArrayEquals(intArrayOf(0, 1, 2, 3, -1, -1, -1, 5, 6, -1, 7, 8),
            PunctuationTokenizerText.utf8BoundaryMap("hi 😀 é!"))
    }

    @Test fun `case conversion preserves source while byte lengths may change`() {
        val raw = "KÉ😀"
        assertEquals("ké😀", PunctuationTokenizerText.lowerForOffsets(raw))
        val result = PunctuationTokenizerText.decodePacket(raw, "ké😀",
            packet(listOf(Token(3, 0, 1, "k", "k"), Token(4, 1, 3, "é", "é"), Token(0, 3, 7, "😀", "😀"))))
        assertEquals(raw, result.source)
        assertEquals(listOf(0 to 1, 1 to 2, 2 to 4), result.pieces.map { it.beginUtf16 to it.endUtf16 })
    }

    @Test fun `lowercasing is locale independent and handles contextual sigma`() {
        val previous = Locale.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag("tr"))
            assertEquals("indonesia ος", PunctuationTokenizerText.lowerForOffsets("INDONESIA ΟΣ"))
        } finally { Locale.setDefault(previous) }
    }

    @Test fun `case expansion fails closed instead of shifting offsets`() {
        assertThrows(IllegalArgumentException::class.java) { PunctuationTokenizerText.lowerForOffsets("İ Tiara") }
    }

    @Test fun `malformed UTF16 is rejected rather than replaced by question marks`() {
        for (raw in listOf("\uD800", "\uDC00", "a\uD800b", "\uD800\uD800")) {
            assertThrows(IllegalArgumentException::class.java) { PunctuationTokenizerText.lowerForOffsets(raw) }
        }
    }

    @Test fun `native spans expose IDs original source offsets and exact lowercase surfaces`() {
        val raw = "Hi 😀 É!"
        val result = PunctuationTokenizerText.decodePacket(raw, "hi 😀 é!", packet(listOf(
            Token(3, 0, 2, "▁hi", "hi"), Token(0, 2, 7, "▁😀", " 😀"),
            Token(4, 7, 10, "▁é", " é"), Token(5, 10, 11, "!", "!"),
        )))
        assertEquals(raw, result.source)
        assertEquals("hi 😀 é!", result.lowered)
        assertEquals(listOf(3, 0, 4, 5), result.pieces.map { it.id })
        assertEquals(listOf(0 to 2, 2 to 5, 5 to 7, 7 to 8), result.pieces.map { it.beginUtf16 to it.endUtf16 })
        assertTrue(result.pieces[1].unknown)
        assertFalse(result.pieces[0].unknown)
        assertEquals(listOf(0, 1, 2, -1, 20), listOf(result.unkId, result.bosId, result.eosId, result.padId, result.vocabularySize))
    }

    @Test fun `normalization pieces do not replace original surface text`() {
        val result = PunctuationTokenizerText.decodePacket("ﬁ", "ﬁ", packet(listOf(Token(3, 0, 3, "▁fi", "ﬁ"))))
        assertEquals("▁fi", result.pieces.single().piece)
        assertEquals("ﬁ", result.pieces.single().surface)
        assertEquals(1, result.pieces.single().endUtf16)
    }

    @Test fun `empty artificial prefix and preserved source gaps remain representable`() {
        val result = PunctuationTokenizerText.decodePacket("  A", "  a", packet(listOf(
            Token(3, 0, 0, "▁", ""), Token(4, 2, 3, "a", "a"),
        )))
        assertEquals("  A", result.source)
        assertEquals(0, result.pieces.first().endUtf16)
        assertEquals(2, result.pieces.last().beginUtf16)
    }

    @Test fun `NUL remains an actual byte not modified UTF8`() {
        val result = PunctuationTokenizerText.decodePacket("A\u0000B", "a\u0000b",
            packet(listOf(Token(0, 0, 3, "a\u0000b", "a\u0000b"))))
        assertEquals("a\u0000b", result.pieces.single().surface)
        assertArrayEquals(intArrayOf(0, 1, 2, 3), PunctuationTokenizerText.utf8BoundaryMap("a\u0000b"))
    }

    @Test fun `byte offsets inside emoji or outside input are rejected`() {
        for ((begin, end) in listOf(0 to 1, 1 to 4, 0 to 5, -1 to 4)) {
            assertThrows(IllegalArgumentException::class.java) {
                PunctuationTokenizerText.decodePacket("😀", "😀", packet(listOf(Token(0, begin, end, "😀", "😀"))))
            }
        }
    }

    @Test fun `surface mismatch and overlapping spans are rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            PunctuationTokenizerText.decodePacket("abc", "abc", packet(listOf(Token(3, 0, 3, "abc", "abd"))))
        }
        assertThrows(IllegalArgumentException::class.java) {
            PunctuationTokenizerText.decodePacket("abc", "abc", packet(listOf(Token(3, 0, 2, "ab", "ab"), Token(3, 1, 3, "bc", "bc"))))
        }
    }

    @Test fun `invalid protocol truncation count IDs and trailing bytes are rejected`() {
        val valid = packet(listOf(Token(3, 0, 1, "a", "a")))
        val invalidCount = valid.clone().also { it[24] = 127 }
        for (bad in listOf(valid.copyOf(20), valid.copyOf(valid.size - 1), valid + byteArrayOf(0),
            invalidCount, packet(emptyList(), version = 2), packet(listOf(Token(20, 0, 1, "a", "a"))))) {
            assertThrows(IllegalArgumentException::class.java) { PunctuationTokenizerText.decodePacket("a", "a", bad) }
        }
    }

    @Test fun `malformed UTF8 token strings fail instead of decoding replacement characters`() {
        val malformed = packet(listOf(Token(3, 0, 1, "a", "a"))).also { it[44] = 0xff.toByte() }
        assertThrows(CharacterCodingException::class.java) { PunctuationTokenizerText.decodePacket("a", "a", malformed) }
    }

    @Test fun `empty text and bounded input are explicit`() {
        assertTrue(PunctuationTokenizerText.decodePacket("", "", packet(emptyList())).pieces.isEmpty())
        assertThrows(IllegalArgumentException::class.java) { PunctuationTokenizerText.lowerForOffsets("a".repeat(64_001)) }
        assertThrows(IllegalArgumentException::class.java) { PunctuationTokenizerText.decodePacket("A", "A", packet(emptyList())) }
    }
}
