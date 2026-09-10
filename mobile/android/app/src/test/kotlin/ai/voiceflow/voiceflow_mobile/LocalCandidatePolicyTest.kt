package ai.voiceflow.voiceflow_mobile

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class LocalCandidatePolicyTest {
    @Test fun `candidate is unavailable in release personal or missing-runtime builds`() {
        val pkg = "ai.voiceflow.voiceflow_mobile.benchmark"
        assertTrue(LocalCandidatePolicy.allowed(true, true, true, pkg, "small-q8"))
        assertTrue(LocalCandidatePolicy.allowed(true, true, true, pkg, "medium-q8"))
        assertFalse(LocalCandidatePolicy.allowed(false, true, true, pkg, "small-q8"))
        assertFalse(LocalCandidatePolicy.allowed(true, false, true, pkg, "small-q8"))
        assertFalse(LocalCandidatePolicy.allowed(true, true, false, pkg, "small-q8"))
        assertFalse(LocalCandidatePolicy.allowed(true, true, true, "ai.voiceflow.voiceflow_mobile", "small-q8"))
        assertFalse(LocalCandidatePolicy.allowed(true, true, true, pkg, "small"))
    }

    @Test fun `readable cleanup cannot be silently coerced or run without its formatter`() {
        assertFalse(LocalBenchmark.parseReadableCleanup(JSONObject()))
        for (value in listOf("true", 1, JSONObject.NULL, true)) {
            assertThrows(IllegalArgumentException::class.java) {
                LocalBenchmark.parseReadableCleanup(JSONObject().put("readableCleanup", value))
            }
        }
        val config = JSONObject().put("readableCleanup", true).put("punctuationFormatting", JSONObject()
            .put("modelKind", "xlmr").put("modelFile", "punctuation-xlmr.onnx").put("tokenizeOnDevice", true))
        assertTrue(LocalBenchmark.parseReadableCleanup(config))
    }

    @Test fun `pipeline readable knob requires isolated debug runtime`() {
        val config = LocalPipelineConfig(readableCandidate = true)
        assertThrows(IllegalStateException::class.java) { config.requireAllowedRuntime(false, true) }
        assertThrows(IllegalStateException::class.java) { config.requireAllowedRuntime(true, false) }
        config.requireAllowedRuntime(true, true)
    }
}
