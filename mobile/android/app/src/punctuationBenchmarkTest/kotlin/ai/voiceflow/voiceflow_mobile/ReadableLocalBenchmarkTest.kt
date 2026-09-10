package ai.voiceflow.voiceflow_mobile

import java.util.concurrent.CancellationException
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ReadableLocalBenchmarkTest {
    private fun punctuation(raw: String, text: String = raw) = JSONObject()
        .put("rawText", raw).put("text", text).put("candidateText", text)
        .put("outcome", if (raw == text) "unchanged" else "changed_review_required")
        .put("guardFlags", JSONArray()).put("accepted", false).put("reviewRequired", true)

    @Test fun stageOrderAndOffsetsReferToEachImmutableInput() {
        val raw = "um, aku ga punya, eh maksudnya punya."
        var received = ""
        val result = ReadableLocalBenchmark.compose(raw, { received = it; punctuation(it) })
        assertEquals("um, aku punya.", result.getString("correctionText"))
        assertEquals(result.getString("correctionText"), result.getString("fillerInput"))
        assertEquals("aku punya.", received)
        assertEquals(received, result.getString("fillerText"))
        assertEquals(received, result.getString("sentenceInput"))
        assertEquals("Aku punya.", result.getString("text"))
        assertEquals("changed_review_required", result.getString("outcome"))
        assertFalse(result.getBoolean("accepted"))
        assertTrue(result.getBoolean("reviewRequired"))
        for ((input, output, edits) in listOf(
            Triple("correctionInput", "correctionText", "correctionEdits"),
            Triple("fillerInput", "fillerText", "fillerEdits"),
            Triple("sentenceInput", "text", "sentenceEdits"))) {
            val source = result.getString(input)
            val rebuilt = StringBuilder(source)
            val actions = result.getJSONArray(edits)
            assertEquals(1, actions.length())
            for (index in actions.length() - 1 downTo 0) {
                val edit = actions.getJSONObject(index)
                val start = edit.getInt("beginUtf16")
                val end = edit.getInt("endUtf16")
                assertEquals(source.substring(start, end), edit.getString("original"))
                rebuilt.replace(start, end, edit.getString("replacement"))
            }
            assertEquals(result.getString(output), rebuilt.toString())
        }
    }

    @Test fun explicitUserRepairExamplesComposeWithDeterministicPunctuation() {
        for ((raw, expected) in listOf(
            "jadi gua sebenernya ga punya, eh maksudnya punya..." to "Jadi gua sebenernya punya...",
            "jadi memang tiara sendiri yang bilang, eh sorry, tiara yang kasih tau temennya" to
                "Jadi memang tiara sendiri yang kasih tau temennya.",
            "Meeting kita mulai jam 9. Eh, maksudku jam 10." to "Meeting kita mulai jam 10."
        )) {
            val result = ReadableLocalBenchmark.compose(raw, { input ->
                punctuation(input, if (input.last().isLetter()) "$input." else input)
            })
            assertEquals(raw, expected, result.getString("text"))
        }
    }

    @Test fun unambiguousSentenceCaseClosesModelGapWithoutWordChanges() {
        val raw = "I only want to move it. please don't rename anything."
        val result = ReadableLocalBenchmark.compose(raw, { punctuation(it) })
        assertEquals("I only want to move it. Please don't rename anything.", result.getString("text"))
        assertEquals(0, result.getJSONArray("correctionEdits").length())
        assertEquals(0, result.getJSONArray("fillerEdits").length())
    }

    @Test fun preservesExistingParagraphsAndNeverInventsAnother() {
        val raw = "um, aku siap.\r\n\r\nplease check.\n"
        val result = ReadableLocalBenchmark.compose(raw, { punctuation(it) })
        assertEquals("Aku siap.\r\n\r\nPlease check.\n", result.getString("text"))
        assertEquals("preserve_existing_only", result.getString("paragraphs"))
    }

    @Test fun everyPunctuationFailureRestoresOriginalBeforeAllDestructiveStages() {
        val raw = "um, aku ga punya, eh maksudnya punya."
        val result = ReadableLocalBenchmark.compose(raw, { throw IllegalStateException("Model unavailable") })
        assertEquals(raw, result.getString("text"))
        assertEquals(raw, result.getString("candidateText"))
        assertEquals("error_raw_fallback", result.getString("outcome"))
        assertEquals("readable_pipeline_failure", result.getJSONArray("guardFlags").getString(0))
        assertFalse(result.has("sentenceInput"))
    }

    @Test fun rejectsMissingMalformedGuardedOrUnsuccessfulStageMetadata() {
        val mutations: List<(JSONObject) -> Unit> = listOf(
            { it.remove("guardFlags") }, { it.put("guardFlags", false) },
            { it.put("guardFlags", JSONArray().put("protected_literal_changed")) },
            { it.remove("outcome") }, { it.put("outcome", "guard_raw_fallback") },
            { it.put("outcome", "error_raw_fallback") }, { it.put("outcome", "accepted") },
            { it.put("text", 5) }, { it.remove("candidateText") }, { it.put("rawText", "another source") },
            { it.put("outcome", "changed_review_required") }
        )
        for (mutate in mutations) {
            val result = ReadableLocalBenchmark.compose("hello.", { punctuation(it).also(mutate) })
            assertEquals("hello.", result.getString("text"))
            assertEquals("error_raw_fallback", result.getString("outcome"))
        }
    }

    @Test fun independentAuditRejectsWordSymbolCaseOrParagraphRewriting() {
        for ((raw, changed) in listOf(
            "don't delete" to "delete", "5mg" to "5Mg", "iPhone" to "IPhone",
            "a != b" to "a = b", "hello world" to "hello. World extra",
            "one\n\ntwo" to "one two", "abc" to "a.bc", "hello." to "hello.."
        )) {
            val result = ReadableLocalBenchmark.compose(raw, { punctuation(it, changed) })
            assertEquals(raw, result.getString("text"))
            assertEquals("error_raw_fallback", result.getString("outcome"))
        }
    }

    @Test fun acceptsSourcePreservingPunctuationAndCapitalization() {
        val raw = "hello world\n\nplease check"
        val result = ReadableLocalBenchmark.compose(raw, { punctuation(it, "Hello, world.\n\nPlease check.") })
        assertEquals("Hello, world.\n\nPlease check.", result.getString("text"))
        assertEquals(0, result.getJSONArray("guardFlags").length())
    }

    @Test fun cancellationNeverLooksLikeRecoverableRawFallback() {
        var stopped = false
        assertThrows(CancellationException::class.java) {
            ReadableLocalBenchmark.compose("hello", { stopped = true; punctuation(it) }, cancelled = { stopped })
        }
        assertThrows(CancellationException::class.java) {
            ReadableLocalBenchmark.compose("hello", { throw CancellationException("cancel") })
        }
        try {
            assertThrows(InterruptedException::class.java) {
                ReadableLocalBenchmark.compose("hello", { throw InterruptedException("native interrupted") })
            }
            assertTrue(Thread.currentThread().isInterrupted)
        } finally { Thread.interrupted() }
    }

    @Test fun pureClockAndUnchangedCandidateNeedNoAndroidRuntime() {
        var nanos = 0L
        val result = ReadableLocalBenchmark.compose("Already done.", { punctuation(it) }, nowNanos = { nanos += 1_000_000L; nanos })
        assertEquals("unchanged", result.getString("outcome"))
        assertEquals(1.0, result.getDouble("totalWithPreparationMs"), 0.0)
        assertEquals("Already done.", result.getString("text"))
    }
}
