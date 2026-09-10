package ai.voiceflow.voiceflow_mobile

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class PunctuationOnDeviceTest {
    private fun config(kind: String = "small") = JSONObject().put("modelKind", kind)
        .put("modelFile", "punctuation-$kind.onnx").put("tokenizeOnDevice", true)
        .put("cases", JSONArray().put(JSONObject().put("id", "source-case").put("rawText", "hello world")))

    private fun spec(kind: String = "small") = PunctuationRequest.parse(config(kind)).spec

    private fun encoding(count: Int, kind: String = "small"): PunctuationEncoding {
        val model = spec(kind)
        return PunctuationEncoding("a".repeat(count), "a".repeat(count),
            List(count) { PunctuationToken(10 + it % 20, "a", "a", it, it + 1, false) },
            if (kind == "small") 0 else 3, model.bos.toInt(), 2, -1, model.vocabulary)
    }

    private fun predictions(encoded: PunctuationEncoding, model: PunctuationSpec): JSONArray {
        val rows = JSONArray()
        PunctuationWindowing.windows(encoded, model).forEachIndexed { window, ids ->
            val kept = ids.drop(1).dropLast(1)
            rows.put(JSONObject().put("ids", JSONArray(kept)).put("pre", JSONArray(List(kept.size) { 0 }))
                .put("post", JSONArray(List(kept.size) { window % model.postCount }))
                .put("capitals", JSONArray(List(kept.size) { JSONArray(List(16) { false }) }))
                .put("sentences", JSONArray(List(kept.size) { false })))
        }
        return rows
    }

    @Test fun `raw mode makes windows optional and pins matching tokenizer metadata`() {
        for (kind in listOf("small", "xlmr")) {
            val parsed = PunctuationRequest.parse(config(kind))
            assertTrue(parsed.tokenizeOnDevice)
            assertTrue(parsed.cases.single().windows.isEmpty())
            assertEquals(parsed.spec.postCount, parsed.spec.postLabels.size)
            assertEquals(if (kind == "small") 1 else 2, parsed.spec.postLabels.indexOf("."))
        }
    }

    @Test fun `helpers can parse asset configuration without cases`() {
        val value = config().also { it.remove("cases") }
        assertTrue(PunctuationRequest.parse(value, parseCases = false).tokenizeOnDevice)
        assertThrows(Exception::class.java) { PunctuationRequest.parse(value) }
    }

    @Test fun `raw mode rejects coercible flags tokenizer substitution and malformed raw values`() {
        for (flag in listOf<Any>("true", 1, JSONObject.NULL)) {
            assertThrows(Exception::class.java) { PunctuationRequest.parse(config().put("tokenizeOnDevice", flag)) }
        }
        for (filename in listOf("../sp.model", "sp.model", "other.model")) {
            assertThrows(Exception::class.java) { PunctuationRequest.parse(config().put("tokenizerFile", filename)) }
        }
        assertThrows(Exception::class.java) {
            PunctuationRequest.parse(config().also { it.getJSONArray("cases").getJSONObject(0).put("rawText", true) })
        }
        assertThrows(Exception::class.java) { PunctuationRequest.parse(config().put("tokenizeOnDevice", false)) }
    }

    @Test fun `provided raw parity windows still validate special tokens and never become optional null`() {
        val value = config()
        val row = value.getJSONArray("cases").getJSONObject(0)
        row.put("windows", JSONArray().put(JSONArray(listOf(1, 42, 2))))
        assertArrayEquals(longArrayOf(1, 42, 2), PunctuationRequest.parse(value).cases.single().windows.single())
        for (bad in listOf<Any>(JSONObject.NULL, JSONArray(), JSONArray().put(JSONArray(listOf(0, 42, 2))))) {
            row.put("windows", bad)
            assertThrows(Exception::class.java) { PunctuationRequest.parse(value) }
        }
    }

    @Test fun `single exact-capacity and overflow windows retain pinned BOS EOS`() {
        for (kind in listOf("small", "xlmr")) {
            val model = spec(kind)
            val capacity = model.maximum - 2
            for (count in listOf(1, capacity, capacity + 1)) {
                val windows = PunctuationWindowing.windows(encoding(count, kind), model)
                assertEquals(if (count > capacity) 2 else 1, windows.size)
                assertTrue(windows.all { it.first() == model.bos && it.last() == 2L && it.size <= model.maximum })
                if (count > capacity) assertEquals(19, windows.last().size) // 16 overlap + one fresh + BOS/EOS.
            }
        }
    }

    @Test fun `half-overlap merge covers every repeated ID exactly once without fuzzy deletion`() {
        val model = spec()
        val encoded = encoding(300)
        val rows = predictions(encoded, model)
        val merged = PunctuationWindowing.merge(encoded, rows, model)
        assertEquals(300, merged.first.size)
        assertEquals(300, merged.second.size)
        assertTrue(merged.first.take(118).all { it == 0 })
        assertTrue(merged.first.drop(118).take(110).all { it == 1 })
        assertTrue(merged.first.drop(228).all { it == 2 })
    }

    @Test fun `last short overlap window merges without dropping final fresh token`() {
        val model = spec()
        val encoded = encoding(127)
        val merged = PunctuationWindowing.merge(encoded, predictions(encoded, model), model)
        assertEquals(127, merged.first.size)
        assertEquals(1, merged.first.last())
    }

    @Test fun `empty excessive tokens and incorrect tokenizer identity are rejected`() {
        for (bad in listOf(encoding(0), encoding(5001), encoding(1).copy(bosId = 0), encoding(1).copy(vocabularySize = 99))) {
            assertThrows(IllegalArgumentException::class.java) { PunctuationWindowing.windows(bad, spec()) }
        }
    }

    @Test fun `both model window merges cover exact boundaries through maximum token count`() {
        for (kind in listOf("small", "xlmr")) {
            val model = spec(kind)
            val capacity = model.maximum - 2
            for (count in listOf(1, capacity - 1, capacity, capacity + 1,
                2 * capacity - 16, 2 * capacity - 15, 5000)) {
                val encoded = encoding(count, kind)
                val rows = predictions(encoded, model)
                val merged = PunctuationWindowing.merge(encoded, rows, model)
                assertEquals("$kind count=$count", count, merged.first.size)
                assertEquals("$kind count=$count", count, merged.second.size)
                assertEquals((rows.length() - 1) % model.postCount, merged.first.last())
            }
        }
    }

    @Test fun `raw case and supplied aggregate parity window limits are enforced without truncation`() {
        fun cases(count: Int, windowsPerCase: Int) = JSONArray().also { rows ->
            repeat(count) { index ->
                rows.put(JSONObject().put("id", "case-$index").put("rawText", "hello")
                    .put("windows", JSONArray().also { windows ->
                        repeat(windowsPerCase) { windows.put(JSONArray(listOf(1, 42, 2))) }
                    }))
            }
        }
        assertEquals(16, PunctuationRequest.parse(config().put("cases", cases(16, 3))).cases.size)
        assertThrows(IllegalArgumentException::class.java) {
            PunctuationRequest.parse(config().put("cases", cases(17, 3)))
        }
        assertEquals(24, PunctuationRequest.parse(config().put("cases", cases(24, 1))).cases.size)
        assertThrows(IllegalArgumentException::class.java) {
            PunctuationRequest.parse(config().put("cases", cases(25, 1)))
        }
        val value = config()
        val row = value.getJSONArray("cases").getJSONObject(0)
        row.put("rawText", "a".repeat(12000))
        assertEquals(12000, PunctuationRequest.parse(value).cases.single().rawText.length)
        row.put("rawText", "a".repeat(12001))
        assertThrows(IllegalArgumentException::class.java) { PunctuationRequest.parse(value) }
    }

    @Test fun `prediction IDs and field types must match before rendering`() {
        val model = spec()
        val encoded = encoding(1)
        for (mutate in listOf<(JSONObject) -> Unit>(
            { it.getJSONArray("ids").put(0, 11) },
            { it.getJSONArray("ids").put(0, "10") },
            { it.getJSONArray("post").put(0, model.postCount) },
            { it.getJSONArray("pre").put(0, 2) },
            { it.getJSONArray("sentences").put(0, 1) },
            { it.getJSONArray("capitals").getJSONArray(0).put(0, 1) },
            { it.put("capitals", JSONArray()) },
        )) {
            val rows = predictions(encoded, model)
            mutate(rows.getJSONObject(0))
            assertThrows(Exception::class.java) { PunctuationWindowing.merge(encoded, rows, model) }
        }
        assertThrows(Exception::class.java) { PunctuationWindowing.merge(encoded, JSONArray(), model) }
    }
}
