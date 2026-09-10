package ai.voiceflow.voiceflow_mobile

import org.junit.Assert.*
import org.junit.Test

class LocalFormattingGuardTest {
    @Test fun `formatting preserves every word including hesitations and corrections`() {
        val raw = "um besok jam 9 eh maksudku jam 10 please bring the report"
        assertNull(LocalFormattingGuard.rejection(raw,
            "Um, besok jam 9, eh maksudku jam 10. Please bring the report.", emptyList()))
        assertEquals("lexical_change", LocalFormattingGuard.rejection(raw,
            "Besok jam 10. Please bring the report.", emptyList()))
        assertEquals("lexical_change", LocalFormattingGuard.rejection("kata um itu tertulis",
            "Kata itu tertulis.", emptyList()))
    }

    @Test fun `symbol signs numeric structure and technical names cannot change`() {
        for ((raw, changed) in listOf(
            "suhu -5" to "Suhu 5.", "diskon 20%" to "Diskon 20.",
            "jam 09:30" to "Jam 09 30.", "a!=b" to "a=b",
            "pakai C++" to "Pakai C.", "user_id" to "User id.",
            "team@example.com" to "Team example com.",
            "harga 1.500" to "Harga 1,500.", "tanggal 10/09/2026" to "Tanggal 10 09 2026.",
            "suhu -5 lalu 10" to "Suhu 5 lalu -10.", "x<5" to "x>5",
        )) {
            assertNotNull("$raw -> $changed", LocalFormattingGuard.rejection(raw, changed, emptyList()))
            assertNotNull("legacy: $raw -> $changed", LocalCleanupGuard.rejection(raw, changed, emptyList()))
        }
    }

    @Test fun `safe punctuation around symbols and original unicode survive`() {
        assertNull(LocalFormattingGuard.rejection("diskon 20% besok",
            "Diskon 20%. Besok.", emptyList()))
        assertNull(LocalFormattingGuard.rejection("I don't want this kirim ke team@example.com",
            "I don’t want this. Kirim ke team@example.com.", emptyList()))
        assertNull(LocalFormattingGuard.rejection("pakai C++ sekarang lalu cek user_id",
            "Pakai C++ sekarang.\n\nLalu cek user_id.", emptyList()))
        assertNull(LocalFormattingGuard.rejection("cafe\u0301 😊 besok", "Cafe\u0301 😊 besok.", emptyList()))
    }

    @Test fun `invisible controls new symbols and moved symbols are rejected`() {
        for (candidate in listOf("Send\u202e report.", "Send\u0000 report.", "Send report 😊.", "Send © report.")) {
            assertNotNull(LocalFormattingGuard.rejection("send report", candidate, emptyList()))
        }
        assertNotNull(LocalFormattingGuard.rejection("send 😊 report", "Send report 😊.", emptyList()))
        assertNotNull(LocalFormattingGuard.rejection("send report", "Send \uE000 report.", emptyList()))
    }

    @Test fun `legacy hesitation deletion cannot hide numeric or symbol changes`() {
        assertNull(LocalCleanupGuard.rejection("um suhu -5 eh besok", "Suhu -5 besok.", emptyList()))
        assertNotNull(LocalCleanupGuard.rejection("um suhu -5 eh besok", "Suhu 5 besok.", emptyList()))
    }

    @Test fun `acronyms camel case and code spelling cannot be recased`() {
        for ((raw, candidate) in listOf("visit US" to "Visit us.", "use iPhone" to "Use Iphone.",
            "pakai C++" to "Pakai c++.", "user_id" to "User_id.",
            "email Team@Example.com" to "Email team@example.com.", "open example.com/Docs" to "Open example.com/docs.")) {
            assertEquals("$raw -> $candidate", "protected_case_changed",
                LocalFormattingGuard.rejection(raw, candidate, emptyList()))
        }
        assertNull(LocalFormattingGuard.rejection("please visit US pakai iPhone",
            "Please visit US. Pakai iPhone.", emptyList()))
    }
}
