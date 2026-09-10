package ai.voiceflow.voiceflow_mobile

import org.junit.Assert.*
import org.junit.Test

class LocalFillerFilterTest {
    @Test fun `clear English and mixed hesitations are removed`() {
        for ((raw, expected) in listOf(
            "um, please send the report" to "please send the report",
            "Uh, saya belum kirim the report." to "saya belum kirim the report.",
            "erm I think kita tunggu" to "I think kita tunggu",
            "um saya mau check dulu" to "saya mau check dulu",
            "We can, um, send it tomorrow." to "We can, send it tomorrow.",
            "Kita tunggu, uh, sebelum deploy." to "Kita tunggu, sebelum deploy.",
        )) assertEquals(raw, expected, LocalFillerFilter.apply(raw).text)
    }

    @Test fun `correction markers and all facts remain for the correction resolver`() {
        for (raw in listOf(
            "jam sembilan eh maksudnya jam sepuluh",
            "eh bukan itu",
            "eh, sorry, Tiara yang kasih tau temennya",
            "jadi gua sebenernya ga punya, eh maksudnya punya",
            "eh maksudku bukan hari ini",
            "sorry for being late",
            "bukan cuma Tiara yang datang",
            "jadi yang tadi jangan dihapus",
            "eh itu kata seru",
            "kata eh bukan selalu filler",
            "I literally said eh maksudnya earlier",
        )) {
            val result = LocalFillerFilter.apply(raw)
            assertEquals(raw, result.text)
            assertTrue(result.edits.isEmpty())
        }
    }

    @Test fun `quoted metalinguistic name code acronym and unit contexts stay unchanged`() {
        for (raw in listOf(
            "\"hello. um, I agree\"",
            "‘uh, saya setuju’",
            "`um, I agree`",
            "(um, I agree)",
            "[uh, saya setuju]",
            "um, this word means hesitation",
            "uh, kata ini sering dipakai",
            "Um, that is my surname",
            "um, nama teman saya",
            "um, I use it as an identifier",
            "uh, this is the literal text",
            "UM, I work at that university",
            "UH, I mean the acronym",
            "um, value = 5",
            "um, visit https://example.com",
            "um, /tmp/file",
            "5 um, saya ukur lagi",
            "hum, saya tahu",
            "um_id, I use this",
            "um. I wrote it exactly",
            "um? I heard that",
        )) assertEquals(raw, raw, LocalFillerFilter.apply(raw).text)
    }

    @Test fun `uncertain hesitation position and standalone mention stay unchanged`() {
        for (raw in listOf("um", "uh,", "erm\n", "I um think so", "kita uh tunggu",
            "um datang terlambat", "Erm pergi tadi", "um bukan ukuran itu")) {
            assertEquals(raw, raw, LocalFillerFilter.apply(raw).text)
        }
    }

    @Test fun `source offsets and untouched Unicode punctuation spacing and paragraphs are exact`() {
        val raw = "😀 selesai.\n\n  um,\tI keep API 5mg +9 and café.\r\nUh, saya setuju!"
        val result = LocalFillerFilter.apply(raw)
        assertEquals("😀 selesai.\n\n  I keep API 5mg +9 and café.\r\nsaya setuju!", result.text)
        assertEquals(2, result.edits.size)
        val replay = StringBuilder(raw)
        for (edit in result.edits.asReversed()) {
            assertEquals(raw.substring(edit.beginUtf16, edit.endUtf16), edit.original)
            assertEquals("", edit.replacement)
            assertTrue(edit.reason.isNotBlank())
            assertFalse(edit.original.contains('\n'))
            assertFalse(edit.original.contains('\r'))
            replay.replace(edit.beginUtf16, edit.endUtf16, edit.replacement)
        }
        assertEquals(result.text, replay.toString())
    }

    @Test fun `adjacent repeated hesitations have nonoverlapping original spans`() {
        val raw = "um, uh, erm, I agree. um we can wait."
        val result = LocalFillerFilter.apply(raw)
        assertEquals("I agree. we can wait.", result.text)
        assertEquals(4, result.edits.size)
        for ((left, right) in result.edits.zipWithNext()) assertTrue(left.endUtf16 <= right.beginUtf16)
        assertEquals(raw.indexOf("um we"), result.edits.last().beginUtf16)
    }

    @Test fun `existing newlines are never deleted or synthesized`() {
        val raw = "um,\n\nI agree\r\n\r\nuh,\tsaya setuju"
        val result = LocalFillerFilter.apply(raw)
        assertEquals("\n\nI agree\r\n\r\nsaya setuju", result.text)
        assertEquals(raw.filter { it == '\n' || it == '\r' }, result.text.filter { it == '\n' || it == '\r' })
    }

    @Test fun `malformed Unicode control characters and oversized inputs fail closed`() {
        for (raw in listOf("um, I keep \uD800", "uh, saya \uDC00", "erm, I keep \u0000",
            "um, I keep \u0008", "um, I " + "a".repeat(12000))) {
            val result = LocalFillerFilter.apply(raw)
            assertEquals(raw, result.text)
            assertTrue(result.edits.isEmpty())
        }
    }
}
