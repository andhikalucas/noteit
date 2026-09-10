package ai.voiceflow.voiceflow_mobile

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class PunctuationRequestTest {
    private fun config(kind: String = "small") = JSONObject().put("modelKind", kind)
        .put("modelFile", "punctuation-$kind.onnx").put("cold", false)
        .put("cases", JSONArray().put(JSONObject().put("id", "mixed-01").put("rawText", "halo world")
            .put("windows", JSONArray().put(JSONArray().put(if (kind == "small") 1 else 0).put(42).put(2)))))

    private fun reject(mutate: (JSONObject) -> Unit) {
        val value = config().also(mutate)
        assertThrows(Exception::class.java) { PunctuationRequest.parse(value) }
    }

    @Test fun parsesPinnedModels() {
        for (kind in listOf("small", "xlmr")) {
            val parsed = PunctuationRequest.parse(config(kind))
            assertEquals(kind, parsed.spec.kind)
            assertEquals(16, parsed.spec.capWidth)
            assertEquals(1, parsed.cases.size)
            assertArrayEquals(longArrayOf(if (kind == "small") 1L else 0L, 42, 2), parsed.cases[0].windows[0])
        }
    }
    @Test fun rejectsPathTraversalAndUnknownModels() {
        for (name in listOf("../model.onnx", "/tmp/model.onnx", "a/model.onnx", "model.bin")) {
            reject { it.put("modelFile", name) }
        }
        reject { it.put("modelKind", "arbitrary") }
    }
    @Test fun rejectsInvalidTypesAndSpecialTokens() {
        reject { it.put("cold", "false") }
        for (value in listOf<Any>(true, "42", 42.5, -1, 64000)) {
            reject { it.getJSONArray("cases").getJSONObject(0).getJSONArray("windows").getJSONArray(0).put(1, value) }
        }
        reject { it.getJSONArray("cases").getJSONObject(0).getJSONArray("windows").getJSONArray(0).put(0, 0) }
        reject { it.getJSONArray("cases").getJSONObject(0).getJSONArray("windows").getJSONArray(0).put(2, 3) }
    }
    @Test fun rejectsEmptyOversizedAndDuplicateCases() {
        reject { it.put("cases", JSONArray()) }
        reject { it.getJSONArray("cases").getJSONObject(0).put("rawText", " ") }
        reject { it.getJSONArray("cases").getJSONObject(0).put("rawText", "a".repeat(12001)) }
        reject { it.getJSONArray("cases").put(JSONObject(it.getJSONArray("cases").getJSONObject(0).toString())) }
        reject { it.getJSONArray("cases").getJSONObject(0).put("windows", JSONArray()) }
        reject {
            val ids = JSONArray().put(1)
            repeat(127) { ids.put(42) }
            ids.put(2)
            it.getJSONArray("cases").getJSONObject(0).put("windows", JSONArray().put(ids))
        }
    }
    @Test fun boundsAggregateWindowCount() {
        reject {
            val windows = JSONArray()
            repeat(49) { windows.put(JSONArray().put(1).put(42).put(2)) }
            it.getJSONArray("cases").getJSONObject(0).put("windows", windows)
        }
    }

    @Test fun readableTextIsExplicitBoundedAndRetokenized() {
        reject { it.put("readableText", "true") }
        reject { it.put("readableText", JSONObject.NULL) }
        reject { it.put("readableText", true) }
        reject { it.put("readableText", true).put("tokenizeOnDevice", true) }
        val valid = config().put("readableText", true).put("tokenizeOnDevice", true)
        valid.getJSONArray("cases").getJSONObject(0).remove("windows")
        assertTrue(PunctuationRequest.parse(valid).readableText)
        valid.getJSONArray("cases").getJSONObject(0).put("rawText", "a".repeat(1001))
        assertThrows(Exception::class.java) { PunctuationRequest.parse(valid) }
    }
}
