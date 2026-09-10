package ai.voiceflow.voiceflow_mobile

import org.junit.Assert.*
import org.junit.Test

class LocalCorrectionResolverTest {
    private fun expect(raw: String, expected: String, reason: String? = null) {
        val result = LocalCorrectionResolver.apply(raw)
        assertEquals(expected, result.text)
        if (reason != null) assertEquals(reason, result.edits.single().reason)
        var cursor = 0
        val rebuilt = buildString {
            for (edit in result.edits) {
                assertTrue(edit.beginUtf16 >= cursor)
                assertEquals(raw.substring(edit.beginUtf16, edit.endUtf16), edit.original)
                assertEquals("", edit.replacement) // This resolver only retains original source spans.
                append(raw, cursor, edit.beginUtf16)
                append(edit.replacement)
                cursor = edit.endUtf16
            }
            append(raw, cursor, raw.length)
        }
        assertEquals(expected, rebuilt)
    }

    @Test fun `explicit negative predicate correction uses replacement source verbatim`() {
        expect("jadi gua sebenernya ga punya, eh maksudnya punya...",
            "jadi gua sebenernya punya...", "explicit_negative_predicate_repair")
        expect("aku tidak siap, eh maksudku siap berangkat", "aku siap berangkat")
        expect("kami gak punya mobil, eh maksudnya punya mobil baru", "kami punya mobil baru")
        expect("aku bukan pemilik, eh maksudku pemilik rumah ini", "aku pemilik rumah ini")
    }

    @Test fun `repeated identical subject repairs predicate while preserving prefix and sendiri`() {
        expect("jadi memang tiara sendiri yang bilang, eh sorry, tiara yang kasih tau temennya",
            "jadi memang tiara sendiri yang kasih tau temennya", "explicit_repeated_subject_predicate_repair")
        expect("ternyata Ardi Putra sendiri yang kirim, eh maaf, Ardi Putra yang terima laporan",
            "ternyata Ardi Putra sendiri yang terima laporan")
        expect("Rina yang mencatat, eh maksudnya Rina yang meninjau hasilnya", "Rina yang meninjau hasilnya")
    }

    @Test fun `repeated anchors correct values without generating names or numbers`() {
        expect("meeting kita mulai jam sembilan, eh maksudnya jam sepuluh",
            "meeting kita mulai jam sepuluh", "explicit_repeated_anchor_repair")
        expect("kirim hari Kamis, eh maksudku hari Jumat pagi", "kirim hari Jumat pagi")
        expect("pakai nomor 12, maksudku nomor 14", "pakai nomor 14")
        expect("pakai nomor dua puluh satu, eh maksudku nomor dua puluh dua", "pakai nomor dua puluh dua")
        expect("pilih warna merah, eh maksudnya warna biru", "pilih warna biru")
        expect("nama Tiara, eh maksudku nama Ardi", "nama Ardi")
    }

    @Test fun `uncued negatives and ambiguous imperative repairs are never removed`() {
        for (raw in listOf("aku ga punya", "jangan kirim laporan lama", "aku tidak siap tapi siap mencoba",
            "jangan kirim, eh maksudku kirim", "aku ga pernah punya, eh maksudnya punya",
            "aku tidak tidak siap, eh maksudnya siap", "aku ga punya, eh maksudnya punya atau nggak",
            "Tiara yang tidak kirim, eh sorry Tiara yang terima")) {
            expect(raw, raw)
        }
    }

    @Test fun `different ambiguous subjects and anchors abstain`() {
        for (raw in listOf("Tiara yang bilang, eh sorry Ardi yang kasih tau",
            "Tiara dan Ardi yang bilang, eh sorry Tiara yang kasih tau",
            "Tiara dan Ardi yang bilang, eh sorry Ardi yang kasih tau",
            "Tiara yang bilang Tiara, eh sorry Tiara yang kasih tau",
            "jam sembilan, eh maksudnya pukul sepuluh", "jam sembilan dan jam sepuluh, eh maksudnya jam sebelas",
            "jam sembilan besok, eh maksudnya jam sepuluh", "warna merah muda, eh maksudnya warna biru",
            "Tiara sendiri yang bilang, eh sorry Tiara sendiri yang kasih tau")) {
            expect(raw, raw)
        }
    }

    @Test fun `subject repair never carries an earlier negative into the new predicate`() {
        for (raw in listOf("bukan Tiara yang bilang eh sorry Tiara yang kasih tahu",
            "bukan Tiara yang bilang. Eh sorry Tiara yang kasih tahu",
            "jadi memang bukan Tiara sendiri yang bilang, eh sorry Tiara yang kasih tahu",
            "tidak cuma Tiara yang bilang, eh sorry Tiara yang kasih tahu")) expect(raw, raw)
    }

    @Test fun `unquoted discussion of words and correction examples is not a correction instruction`() {
        for (raw in listOf("kata ga punya eh maksudnya punya adalah contoh koreksi",
            "ga punya eh maksudnya punya adalah contoh koreksi",
            "frasa jam sembilan eh maksudku jam sepuluh", "kalimat ga punya. Eh maksudnya punya",
            "the phrase ga punya eh maksudnya punya", "kata Tiara ga punya, eh maksudnya punya",
            "contoh Tiara sendiri yang bilang, eh sorry Tiara yang kasih tahu")) expect(raw, raw)
    }

    @Test fun `quoted literal code and malformed Unicode are unchanged`() {
        for (raw in listOf("ucapkan \"ga punya, eh maksudnya punya\"",
            "\"awal. jam sembilan eh maksudnya jam sepuluh. akhir\"",
            "`jam sembilan eh maksudnya jam sepuluh`", "x = jam sembilan eh maksudnya jam sepuluh",
            "print(jam sembilan eh maksudnya jam sepuluh)",
            "https://example.com jam sembilan eh maksudnya jam sepuluh",
            "\uD800 ga punya, eh maksudnya punya")) expect(raw, raw)
    }

    @Test fun `repairs never cross unrelated sentences or lines and chained cues abstain`() {
        for (raw in listOf("ga punya. Hari ini panas. eh maksudnya punya", "ga punya\neh maksudnya punya",
            "jam sembilan, eh maksudnya jam sepuluh, eh sorry jam sebelas",
            "aku ga punya, eh maksudnya punya maksudku tidak punya",
            "jam 9. Eh maksudku jam 10. Eh sorry jam 11.",
            "jam 9, eh maksudku jam 10. Eh sorry jam 11.")) expect(raw, raw)
    }

    @Test fun `one ASR period immediately before an anchored correction cue is removable`() {
        expect("Meeting kita mulai jam 9. Eh, maksudku jam 10.", "Meeting kita mulai jam 10.")
        expect("jadi gua sebenernya ga punya. Eh maksudnya punya...", "jadi gua sebenernya punya...")
        expect("jadi memang tiara sendiri yang bilang. Eh sorry, tiara yang kasih tau temennya.",
            "jadi memang tiara sendiri yang kasih tau temennya.")
        expect("Kita mulai jam 9.\tEh maksudnya jam 10.", "Kita mulai jam 10.")
    }

    @Test fun `period bridge never crosses questions exclamations paragraphs or unrelated context`() {
        for (raw in listOf("aku ga punya? Eh maksudnya punya", "aku ga punya! Eh maksudnya punya",
            "aku ga punya.\nEh maksudnya punya", "aku ga punya.\n\nEh maksudnya punya",
            "aku ga punya... Eh maksudnya punya", "jam 9. Tunggu dulu eh maksudku jam 10",
            "jam 9. Cuaca panas. Eh maksudku jam 10", "jam 9. Eh maksudku pukul 10",
            "Tiara yang bilang. Eh sorry Ardi yang kasih tau", "aku ga punya. Eh sorry aku mau kopi")) {
            expect(raw, raw)
        }
    }

    @Test fun `independent sentences use original UTF16 offsets and preserve surrounding characters`() {
        val raw = "😊 aku ga punya, eh maksudnya punya. Meeting jam 9, eh maksudku jam 10!"
        expect(raw, "😊 aku punya. Meeting jam 10!")
        val result = LocalCorrectionResolver.apply(raw)
        assertEquals(2, result.edits.size)
        assertEquals(raw.indexOf("ga punya"), result.edits.first().beginUtf16)
        assertEquals(raw.indexOf("jam 9"), result.edits.last().beginUtf16)
    }

    @Test fun `ordinary apologies unanchored explanations and unsupported changes remain verbatim`() {
        for (raw in listOf("maaf aku terlambat", "sorry I disagree", "eh sorry aku belum siap",
            "jadi maksudnya kita perlu bicara", "aku mau kopi, eh maksudku teh", "tiara bilang eh sorry kasih tau")) {
            expect(raw, raw)
        }
    }

    @Test fun `bounded input is never truncated`() {
        val raw = "a".repeat(12001) + " ga punya eh maksudnya punya"
        expect(raw, raw)
        assertTrue(LocalCorrectionResolver.apply("").edits.isEmpty())
    }
}
