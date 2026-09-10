package ai.voiceflow.voiceflow_mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import org.json.JSONObject

class LocalRecognitionConfigTest {
    @Test fun pauseOnlyRequiresStrictFlagAndConsistentMetadata() {
        assertEquals(false, LocalBenchmark.parsePauseOnly(JSONObject()))
        assertEquals(true, LocalBenchmark.parsePauseOnly(JSONObject("{\"benchmarkPauseOnly\":true}")))
        assertEquals(true, LocalBenchmark.parsePauseOnly(JSONObject("{\"benchmarkPauseOnly\":true,\"maximumChunkMs\":600000,\"overlapMs\":0}")))
        for (json in listOf(
            "{\"benchmarkPauseOnly\":\"true\"}", "{\"benchmarkPauseOnly\":1}",
            "{\"benchmarkPauseOnly\":true,\"maximumChunkMs\":30000}",
            "{\"benchmarkPauseOnly\":true,\"maximumChunkMs\":600000.0}",
            "{\"benchmarkPauseOnly\":true,\"overlapMs\":400}",
            "{\"benchmarkPauseOnly\":true,\"overlapMs\":\"0\"}",
            "{\"benchmarkPauseOnly\":true,\"overlapMs\":null}",
        )) assertThrows(IllegalArgumentException::class.java) { LocalBenchmark.parsePauseOnly(JSONObject(json)) }
    }

    @Test fun defaultsPreserveProductionRecognition() {
        val config = LocalRecognitionConfig()
        assertEquals("small", config.model)
        assertEquals(4, config.threads)
        assertEquals("auto", config.language)
        assertEquals(true, config.scaledContext)
        assertEquals(false, config.useGpu)
        assertEquals(1, config.beamSize)
        assertNull(config.benchmarkPromptOverride)
    }

    @Test fun existingVocabularyPromptIsUnchangedByDefault() {
        val config = LocalRecognitionConfig()
        assertEquals("Tiara, proyek VoiceFlow", config.decodingPrompt("Tiara, proyek VoiceFlow"))
        assertNull(config.decodingPrompt(null))
    }

    @Test fun benchmarkCanSupplyIndependentStylePrompt() {
        val style = "Kalimat bahasa Indonesia. An English sentence."
        val config = LocalRecognitionConfig(benchmarkPromptOverride = style)
        assertEquals(style, config.decodingPrompt("Tiara"))
        assertEquals(style, config.decodingPrompt(null))
    }

    @Test fun emptyBenchmarkOverrideExplicitlyDisablesVocabularyPrompt() {
        assertEquals("", LocalRecognitionConfig(benchmarkPromptOverride = "").decodingPrompt("Tiara"))
    }

    @Test fun supportedBeamSizesAreAccepted() {
        for (beamSize in 1..5) assertEquals(beamSize, LocalRecognitionConfig(beamSize = beamSize).beamSize)
    }

    @Test(expected = IllegalArgumentException::class)
    fun zeroBeamSizeIsRejected() { LocalRecognitionConfig(beamSize = 0) }

    @Test(expected = IllegalArgumentException::class)
    fun oversizedBeamIsRejected() { LocalRecognitionConfig(beamSize = 6) }

    @Test fun promptAtLimitIsAccepted() {
        assertEquals(512, LocalRecognitionConfig(benchmarkPromptOverride = "a".repeat(512))
            .benchmarkPromptOverride!!.length)
    }

    @Test(expected = IllegalArgumentException::class)
    fun oversizedPromptIsRejected() { LocalRecognitionConfig(benchmarkPromptOverride = "a".repeat(513)) }

    @Test(expected = IllegalArgumentException::class)
    fun nullCharacterInPromptIsRejected() { LocalRecognitionConfig(benchmarkPromptOverride = "a\u0000b") }

    @Test fun absentAndNullBenchmarkBeamSizesPreserveGreedy() {
        assertEquals(1, LocalBenchmark.parseBeamSize(JSONObject()))
        assertEquals(1, LocalBenchmark.parseBeamSize(JSONObject("{\"beamSize\":null}")))
    }

    @Test fun integerBenchmarkBeamSizesAreAcceptedWithoutCoercion() {
        for (size in 1..5) {
            assertEquals(size, LocalBenchmark.parseBeamSize(JSONObject("{\"beamSize\":$size}")))
            assertEquals(size, LocalBenchmark.parseBeamSize(JSONObject().put("beamSize", size.toLong())))
        }
    }

    @Test fun nonIntegerBenchmarkBeamSizesFailRatherThanDefaultOrTruncate() {
        for (value in listOf("1.9", "1.0", "1e0", "\"5\"", "\"invalid\"", "true", "[]", "{}")) {
            assertThrows("Unexpected beam size accepted: $value", IllegalArgumentException::class.java) {
                LocalBenchmark.parseBeamSize(JSONObject("{\"beamSize\":$value}"))
            }
        }
    }

    @Test fun outOfRangeBenchmarkBeamSizesAreRejectedBeforeIntegerNarrowing() {
        for (value in listOf(0L, -1L, 6L, 4_294_967_297L, Long.MAX_VALUE)) {
            assertThrows(IllegalArgumentException::class.java) {
                LocalBenchmark.parseBeamSize(JSONObject().put("beamSize", value))
            }
        }
    }
}
