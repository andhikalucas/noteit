package ai.voiceflow.voiceflow_mobile

import org.junit.Assert.*
import org.junit.Test

class LocalWorkerPriorityTest {
    @Test fun `production keeps existing below normal Java worker priority`() {
        val config = LocalPipelineConfig()
        assertFalse(config.benchmarkNormalPriority)
        assertEquals(4, config.workerJavaPriority)
        for ((debug, isolated) in listOf(false to false, false to true, true to false, true to true)) {
            config.requireAllowedRuntime(debug, isolated)
        }
    }

    @Test fun `normal priority requires an isolated debug benchmark`() {
        val config = LocalPipelineConfig(benchmarkNormalPriority = true)
        assertEquals(5, config.workerJavaPriority)
        config.requireAllowedRuntime(debug = true, isolatedBenchmark = true)
        for ((debug, isolated) in listOf(false to false, false to true, true to false)) {
            assertThrows(IllegalStateException::class.java) { config.requireAllowedRuntime(debug, isolated) }
        }
    }

    @Test fun `priority flag leaves recognition and pause scheduling configuration unchanged`() {
        val baseline = LocalPipelineConfig(
            maximumChunkMs = LocalPipelineConfig.BENCHMARK_MAXIMUM_MS,
            overlapMs = 0,
            benchmarkPauseOnly = true,
        )
        val candidate = baseline.copy(benchmarkNormalPriority = true)
        assertEquals(baseline, candidate.copy(benchmarkNormalPriority = false))
        assertEquals(baseline.recognition, candidate.recognition)
        candidate.requireAllowedRuntime(debug = true, isolatedBenchmark = true)
        for (config in listOf(baseline, candidate)) {
            val chunks = mutableListOf<AudioChunk>()
            val chunker = LocalChunker(config, chunks::add)
            chunker.accept(ShortArray(327_659) { 500 })
            chunker.finish()
            assertEquals(listOf(AudioChunk(0, 327_659, 0, true)), chunks)
        }
    }

    @Test fun `existing measurement callers do not need worker metadata`() {
        val measurement = LocalChunkMeasurement(0, 320, 1, 2, 3, 0, 1, "text")
        assertNull(measurement.javaPriority)
        assertNull(measurement.linuxNice)
        assertNull(measurement.inputSampleCount)
    }

    @Test fun `measurement retains observed priority separately from requested configuration`() {
        val measurement = LocalChunkMeasurement(
            0, 327_659, 1, 2, 3, 0, 1, "text",
            javaPriority = 5, linuxNice = 0, inputSampleCount = 327_659,
        )
        assertEquals(5, measurement.javaPriority)
        assertEquals(0, measurement.linuxNice)
        assertEquals(327_659, measurement.inputSampleCount)
    }
}
