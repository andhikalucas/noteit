package ai.voiceflow.voiceflow_mobile

import org.junit.Assert.*
import org.junit.Test

class LocalCleanupTest {
    @Test fun `cleanup cannot drop protected names or negation`() {
        assertEquals("vocabulary_changed", LocalCleanupGuard.rejection("Meeting sama Tiara besok.", "Meeting sama Tara besok.", listOf("Tiara")))
        assertEquals("negation_changed", LocalCleanupGuard.rejection("Jangan kirim draft sekarang.", "Kirim draft sekarang.", emptyList()))
        assertNull(LocalCleanupGuard.rejection("um jangan kirim draft sekarang", "Jangan kirim draft sekarang.", emptyList()))
    }

    @Test fun `number correction deletion and invention are rejected`() {
        assertEquals("numbers_changed", LocalCleanupGuard.rejection("Kirim 15 maksudku 50 copies.", "Kirim 50 copies.", emptyList()))
        assertNull(LocalCleanupGuard.rejection("kirim 15 maksudku 50 copies", "Kirim 15, maksudku 50 copies.", emptyList()))
        assertEquals("number_added", LocalCleanupGuard.rejection("Kirim 15 copies.", "Kirim 50 copies.", emptyList()))
        assertEquals("numbers_changed", LocalCleanupGuard.rejection("Kirim 15 copies lalu simpan 20 copies.", "Kirim 15 copies lalu simpan copies.", emptyList()))
    }

    @Test fun `reasoning and empty responses are rejected`() {
        assertEquals("invalid_output", LocalCleanupGuard.rejection("hello", "", emptyList()))
        assertEquals("invalid_output", LocalCleanupGuard.rejection("hello", "<think>hello</think>", emptyList()))
    }

    @Test fun `real smoke translation is rejected even without protected terms`() {
        val raw = "Hello, this is a local transcription test. Please send the report tomorrow."
        val translated = "Halo, ini transkripsi test lokal. Silakan kirim laporan besok."
        assertEquals("lexical_change", LocalCleanupGuard.rejection(raw, translated, emptyList()))
    }

    @Test fun `spoken corrections stay intact while punctuation and case may change`() {
        assertEquals("lexical_change", LocalCleanupGuard.rejection(
            "um please send the report Tuesday sorry Wednesday", "Please send the report Wednesday.", emptyList()))
        assertNull(LocalCleanupGuard.rejection(
            "um please send the report Tuesday sorry Wednesday", "Please send the report Tuesday, sorry, Wednesday.", emptyList()))
        assertNull(LocalCleanupGuard.rejection(
            "eh besok meeting sama Tiara terus kirim report", "Besok meeting sama Tiara. Terus kirim report.", emptyList()))
        assertNull(LocalCleanupGuard.rejection("I don't want that", "I don’t want that.", emptyList()))
    }

    @Test fun `observed A55 omission of the entire reminder clause is rejected`() {
        val raw = "Besok aku mau meeting sama Tiara jam sepuluh. Please remind me to bring the latest report. " +
            "Eh maksudku jam sebelas bukan jam sepuluh. Jangan kirim draft yang lama. " +
            "After the meeting aku mau review budget dan discuss next steps sama tim."
        val candidate = "Besok aku mau meeting sama Tiara jam sepuluh. " +
            "Maksudku jam sebelas bukan jam sepuluh. Jangan kirim draft yang lama. " +
            "After the meeting aku mau review budget dan discuss next steps sama tim."
        assertEquals("lexical_change", LocalCleanupGuard.rejection(raw, candidate, listOf("Tiara")))
    }

    @Test fun `meaningful short clauses and trailing words cannot disappear`() {
        assertEquals("lexical_change", LocalCleanupGuard.rejection(
            "Send the report. Call me. Bring the draft.", "Send the report. Bring the draft.", emptyList()))
        assertEquals("lexical_change", LocalCleanupGuard.rejection(
            "Send the report then call me", "Send the report.", emptyList()))
        assertEquals("lexical_change", LocalCleanupGuard.rejection(
            "Call me then send the report", "Send the report.", emptyList()))
    }

    @Test fun `only the explicit hesitation tokens may be removed anywhere`() {
        assertNull(LocalCleanupGuard.rejection(
            "uh um umm erm hmm eh send um the eh report hmm", "Send the report.", emptyList()))
        assertNull(LocalCleanupGuard.rejection(
            "eh kirim report uh lalu review budget um", "Kirim report.\n\nLalu review budget.", emptyList()))
        assertNull(LocalCleanupGuard.rejection("um um send uh", "Um, send.", emptyList()))
        for (phrase in listOf("like", "jadi", "you know", "actually", "sorry", "maksudku")) {
            assertEquals(phrase, "lexical_change", LocalCleanupGuard.rejection(
                "$phrase send the report", "Send the report.", emptyList()))
        }
        // An omitted hesitation does not authorize moving or inventing one.
        assertEquals("lexical_change", LocalCleanupGuard.rejection(
            "send um the report", "Um send the report.", emptyList()))
        assertEquals("lexical_change", LocalCleanupGuard.rejection(
            "send the report", "Uh send the report.", emptyList()))
    }

    @Test fun `novel reordered and duplicated words cannot pass cleanup`() {
        assertEquals("lexical_change", LocalCleanupGuard.rejection("send the report tomorrow", "Send the summary tomorrow.", emptyList()))
        assertEquals("lexical_change", LocalCleanupGuard.rejection("send the report tomorrow", "Tomorrow, send the report.", emptyList()))
        assertEquals("lexical_change", LocalCleanupGuard.rejection("send the report tomorrow", "Send the report report tomorrow.", emptyList()))
        // Legitimate spelling/contraction normalization is deliberately left to
        // explicit autocorrect; cleanup falls back to the original wording.
        assertEquals("lexical_change", LocalCleanupGuard.rejection("I am ready for meeting", "I'm ready for meeting.", emptyList()))
    }

    @Test fun `cleanup tail can revise previous sentences and preserve paragraphs`() {
        val window = LocalCleanupWindow(2)
        window.accept("First sentence.\n\nSecond sentence. Third sentence.")
        assertEquals("Second sentence. Third sentence. More words.", window.input("More words."))
        window.accept("Second sentence changed. Third sentence. More words.")
        assertEquals("First sentence.\n\nSecond sentence changed. Third sentence. More words.", window.text())
    }

    @Test fun `pending cleanup coalesces the complete fitting backlog in order`() {
        val pending = LocalCleanupPending()
        pending.append("Besok meeting sama Tiara.")
        pending.append("Please bring the report.")
        pending.append("Jangan kirim draft lama.")
        val batch = pending.snapshot(6_000)!!
        assertEquals("Besok meeting sama Tiara. Please bring the report. Jangan kirim draft lama.", batch.text)
        pending.consume(batch)
        assertNull(pending.snapshot(6_000))
    }

    @Test fun `aborted cleanup keeps the whole batch for a coalesced retry`() {
        val pending = LocalCleanupPending()
        pending.append("First clause.")
        pending.append("Second clause.")
        val aborted = pending.snapshot(6_000)!!
        // A recognition-priority interruption does not consume this snapshot.
        pending.append("Third clause.")
        val retried = pending.snapshot(6_000)!!
        assertEquals("First clause. Second clause.", aborted.text)
        assertEquals("First clause. Second clause. Third clause.", retried.text)
        pending.consume(retried)
        assertNull(pending.snapshot(6_000))
    }

    @Test fun `consuming a completed batch preserves arrivals after its capture`() {
        val pending = LocalCleanupPending()
        pending.append("Same words.")
        pending.append("Same words.")
        val completed = pending.snapshot(6_000)!!
        pending.append("Same words.")
        pending.append("Later clause.")
        pending.consume(completed)
        assertEquals("Same words. Later clause.", pending.snapshot(6_000)!!.text)
        try {
            pending.consume(completed)
            fail("A stale batch must not consume newly queued text.")
        } catch (_: IllegalStateException) { }
        assertEquals("Same words. Later clause.", pending.snapshot(6_000)!!.text)
    }

    @Test fun `batch limits account for separators without dropping or splitting chunks`() {
        val pending = LocalCleanupPending()
        pending.append("one")
        pending.append("two")
        pending.append("three")
        val first = pending.snapshot(7)!!
        assertEquals("one two", first.text)
        pending.consume(first)
        val oversized = pending.snapshot(2)!!
        assertEquals("three", oversized.text)
        // Safe raw fallback commits the entire oversized item, not a prefix.
        val window = LocalCleanupWindow()
        window.accept(window.input(first.text))
        window.accept(window.input(oversized.text))
        pending.consume(oversized)
        assertEquals("one two three", window.text())
        assertNull(pending.snapshot(0))
    }

    @Test fun `retained context counts toward cleanup input size`() {
        val pending = LocalCleanupPending()
        val window = LocalCleanupWindow()
        window.accept("Context")
        pending.append("one")
        pending.append("two")
        val inputLimit = 11
        val batch = pending.snapshot(inputLimit - window.input("").length)!!
        assertEquals("Context one", window.input(batch.text))
        assertEquals(inputLimit, window.input(batch.text).length)
        pending.consume(batch)
        assertEquals("two", pending.snapshot(0)!!.text)
    }
}
