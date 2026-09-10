package ai.voiceflow.voiceflow_mobile

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class LocalBenchmarkFormattingConfigTest {
    private fun formatting() = JSONObject("""{"punctuationFormatting":{"modelKind":"xlmr","modelFile":"xlmr.onnx","tokenizeOnDevice":true}}""")

    @Test fun `normal priority must be an actual boolean and pipeline enabled`() {
        assertFalse(LocalBenchmark.parseNormalPriority(JSONObject()))
        assertFalse(LocalBenchmark.parseNormalPriority(JSONObject().put("benchmarkNormalPriority", false)))
        assertTrue(LocalBenchmark.parseNormalPriority(JSONObject().put("benchmarkNormalPriority", true)))
        for (invalid in listOf(JSONObject.NULL, "true", 1, 1.0)) {
            assertThrows(IllegalArgumentException::class.java) {
                LocalBenchmark.parseNormalPriority(JSONObject().put("benchmarkNormalPriority", invalid))
            }
        }
        for (invalid in listOf(false, "true", 1, JSONObject.NULL)) {
            assertThrows(IllegalArgumentException::class.java) {
                LocalBenchmark.parseNormalPriority(JSONObject().put("benchmarkNormalPriority", true).put("pipeline", invalid))
            }
        }
        assertThrows(IllegalArgumentException::class.java) {
            LocalBenchmark.parseNormalPriority(JSONObject().put("benchmarkNormalPriority", true).put("engine", "qwen"))
        }
    }

    @Test fun `formatting is absent by default and copied without modifying the request`() {
        assertNull(LocalBenchmark.parsePunctuationFormatting(JSONObject()))
        val config = formatting()
        val nested = LocalBenchmark.parsePunctuationFormatting(config)!!
        assertEquals("xlmr", nested.getString("modelKind"))
        nested.put("cold", true)
        assertFalse(config.getJSONObject("punctuationFormatting").has("cold"))
    }

    @Test fun `formatter rejects coerced modes and unsupported combinations`() {
        for (invalid in listOf(JSONObject.NULL, false, "xlmr", 1)) {
            assertThrows(IllegalArgumentException::class.java) {
                LocalBenchmark.parsePunctuationFormatting(JSONObject().put("punctuationFormatting", invalid))
            }
        }
        for (engine in listOf("qwen", "formatting", "punctuation")) {
            assertThrows(IllegalArgumentException::class.java) {
                LocalBenchmark.parsePunctuationFormatting(formatting().put("engine", engine))
            }
        }
        for (cleanup in listOf(true, "false", 0, JSONObject.NULL)) {
            assertThrows(IllegalArgumentException::class.java) {
                LocalBenchmark.parsePunctuationFormatting(formatting().put("semanticCleanup", cleanup))
            }
        }
    }

    @Test fun `outer cold controls preparation and nested input cannot replace recognized text`() {
        for (key in listOf("cold", "resetModelBeforeWarm", "pipeline")) {
            assertNotNull(LocalBenchmark.parsePunctuationFormatting(formatting().put(key, true)))
            assertThrows(IllegalArgumentException::class.java) {
                LocalBenchmark.parsePunctuationFormatting(formatting().put(key, "true"))
            }
        }
        for (key in listOf("cold", "cases", "rawText", "threads")) {
            val config = formatting()
            config.getJSONObject("punctuationFormatting").put(key, true)
            assertThrows(IllegalArgumentException::class.java) { LocalBenchmark.parsePunctuationFormatting(config) }
        }
    }

    @Test fun `formatting requires pinned mode and safe model basename`() {
        for ((key, value) in listOf("modelKind" to "other", "modelFile" to "../model.onnx",
            "modelFile" to 1, "tokenizeOnDevice" to "true", "tokenizeOnDevice" to false,
            "tokenizerFile" to "wrong.model")) {
            val config = formatting()
            config.getJSONObject("punctuationFormatting").put(key, value)
            assertThrows(IllegalArgumentException::class.java) { LocalBenchmark.parsePunctuationFormatting(config) }
        }
        val config = formatting()
        config.getJSONObject("punctuationFormatting").put("tokenizerFile", "sp.model")
        assertNotNull(LocalBenchmark.parsePunctuationFormatting(config))
    }

    @Test fun `formatter exception preserves exact raw text and cannot count as accepted`() {
        val raw = "API 5mg\n\nTiara jangan ubah."
        val output = LocalBenchmark.formattingFailure(raw, IllegalArgumentException("missing model"))
        assertEquals(raw, output.getString("rawText"))
        assertEquals(raw, output.getString("candidateText"))
        assertEquals(raw, output.getString("text"))
        assertEquals("error_raw_fallback", output.getString("outcome"))
        assertEquals("IllegalArgumentException", output.getString("errorType"))
        assertEquals("missing model", output.getString("error"))
        assertFalse(output.getBoolean("accepted"))
        assertTrue(output.getBoolean("reviewRequired"))
    }
}
