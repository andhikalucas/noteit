package ai.voiceflow.voiceflow_mobile

import org.junit.Assert.*
import org.junit.Test

class LocalPauseOnlyChunkerTest {
    private fun candidate(minimumMs: Int = 6_000, pauseFloor: Int = 64) = LocalPipelineConfig(
        minimumChunkMs = minimumMs,
        maximumChunkMs = LocalPipelineConfig.BENCHMARK_MAXIMUM_MS,
        overlapMs = 0,
        silenceThresholdRms = pauseFloor,
        benchmarkPauseOnly = true,
    )

    @Test fun `production configuration and forced scheduling remain unchanged`() {
        val config = LocalPipelineConfig()
        assertFalse(config.benchmarkPauseOnly)
        assertEquals(15_000, config.maximumChunkMs)
        assertEquals(400, config.overlapMs)
        config.requireAllowedRuntime(debug = false, isolatedBenchmark = false)
        val chunks = mutableListOf<AudioChunk>()
        val chunker = LocalChunker(config, chunks::add)
        chunker.accept(ShortArray(320_000) { 500 })
        chunker.finish()
        assertEquals(listOf(AudioChunk(0, 240_000, 0, true), AudioChunk(233_600, 320_000, 400, true)), chunks)
    }

    @Test fun `candidate requires explicit bound and zero overlap`() {
        assertThrows(IllegalArgumentException::class.java) { LocalPipelineConfig(benchmarkPauseOnly = true) }
        assertThrows(IllegalArgumentException::class.java) { candidate().copy(overlapMs = 400) }
        assertThrows(IllegalArgumentException::class.java) { candidate().copy(maximumChunkMs = 30_000) }
        assertThrows(IllegalArgumentException::class.java) { candidate().copy(maximumChunkMs = 600_001) }
        assertThrows(IllegalArgumentException::class.java) { LocalPipelineConfig(maximumChunkMs = 600_000) }
    }

    @Test fun `candidate cannot run in personal or release builds`() {
        val config = candidate()
        config.requireAllowedRuntime(debug = true, isolatedBenchmark = true)
        for ((debug, isolated) in listOf(false to false, false to true, true to false)) {
            assertThrows(IllegalStateException::class.java) { config.requireAllowedRuntime(debug, isolated) }
        }
    }

    @Test fun `twenty second speech does not acquire the old forced boundary`() {
        val chunks = mutableListOf<AudioChunk>()
        val chunker = LocalChunker(candidate(), chunks::add)
        chunker.accept(ShortArray(320_000) { 500 })
        assertTrue(chunks.isEmpty())
        chunker.finish()
        assertEquals(listOf(AudioChunk(0, 320_000, 0, true)), chunks)
    }

    @Test fun `speech below at and above thirty seconds stays a single complete native input`() {
        for (sampleCount in listOf(479_984, 480_000, 480_001, 960_005)) {
            val chunks = mutableListOf<AudioChunk>()
            val chunker = LocalChunker(candidate(), chunks::add)
            chunker.accept(ShortArray(sampleCount) { 500 })
            assertTrue(chunks.isEmpty())
            chunker.finish()
            assertEquals(listOf(AudioChunk(0, sampleCount.toLong(), 0, true)), chunks)
            assertExactCoverage(chunks, sampleCount.toLong())
        }
    }

    @Test fun `pause after thirty seconds finalizes while later speech remains separate without overlap`() {
        val chunks = mutableListOf<AudioChunk>()
        val chunker = LocalChunker(candidate(), chunks::add)
        chunker.accept(ShortArray(640_000) { 500 }) // Forty seconds, no forced cut.
        assertTrue(chunks.isEmpty())
        chunker.accept(ShortArray(8_000))
        assertEquals(listOf(AudioChunk(0, 648_000, 0, true)), chunks)
        chunker.accept(ShortArray(112_005) { 500 })
        chunker.finish()
        assertEquals(AudioChunk(648_000, 760_005, 0, true), chunks.last())
        assertExactCoverage(chunks, 760_005)
    }

    @Test fun `stopping on a pause does not emit an empty tail`() {
        val chunks = mutableListOf<AudioChunk>()
        val chunker = LocalChunker(candidate(), chunks::add)
        chunker.accept(ShortArray(96_000) { 500 })
        chunker.accept(ShortArray(8_000))
        chunker.finish()
        chunker.finish()
        assertEquals(listOf(AudioChunk(0, 104_000, 0, true)), chunks)
    }

    @Test fun `quiet repeated speech remains eligible below a higher pause floor`() {
        val chunks = mutableListOf<AudioChunk>()
        val chunker = LocalChunker(candidate(pauseFloor = 512), chunks::add)
        chunker.accept(ShortArray(320_005) { 100 })
        chunker.finish()
        assertTrue(chunks.all { it.hasSpeech })
        assertExactCoverage(chunks, 320_005)
    }

    @Test fun `digital silence stays marked non speech without dropping its sample coverage`() {
        val chunks = mutableListOf<AudioChunk>()
        val chunker = LocalChunker(candidate(), chunks::add)
        chunker.accept(ShortArray(320_005))
        chunker.finish()
        assertTrue(chunks.all { !it.hasSpeech })
        assertExactCoverage(chunks, 320_005)
    }

    @Test fun `ingress block boundaries do not change pause-only spans`() {
        fun chunks(blockSize: Int): List<AudioChunk> {
            val input = ShortArray(800_005) { if (it in 560_000 until 568_000) 0 else 500 }
            val result = mutableListOf<AudioChunk>()
            val chunker = LocalChunker(candidate(), result::add)
            var position = 0
            while (position < input.size) {
                val end = minOf(position + blockSize, input.size)
                chunker.accept(input.copyOfRange(position, end))
                position = end
            }
            chunker.finish()
            assertExactCoverage(result, input.size.toLong())
            return result
        }
        assertEquals(chunks(320), chunks(777))
    }

    @Test fun `ten minutes of uninterrupted speech remains one bounded input`() {
        val chunks = mutableListOf<AudioChunk>()
        val chunker = LocalChunker(candidate(), chunks::add)
        val second = ShortArray(16_000) { 500 }
        repeat(600) { chunker.accept(second) }
        assertTrue(chunks.isEmpty())
        chunker.finish()
        assertEquals(listOf(AudioChunk(0, 9_600_000, 0, true)), chunks)
        assertExactCoverage(chunks, 9_600_000)
    }

    @Test fun `session bound survives pause resets and an over-limit error cannot return partial output`() {
        val chunks = mutableListOf<AudioChunk>()
        val chunker = LocalChunker(candidate(), chunks::add)
        val second = ShortArray(16_000) // Frequent silence boundaries reset chunk starts.
        repeat(600) { chunker.accept(second) }
        assertFalse(chunks.isEmpty())
        val countAtLimit = chunks.size
        val error = assertThrows(DictationException::class.java) { chunker.accept(shortArrayOf(500)) }
        assertEquals("local_benchmark_limit", error.code)
        assertSame(error, assertThrows(DictationException::class.java) { chunker.finish() })
        assertSame(error, assertThrows(DictationException::class.java) { chunker.accept(shortArrayOf()) })
        assertEquals(countAtLimit, chunks.size)
    }

    private fun assertExactCoverage(chunks: List<AudioChunk>, sampleCount: Long) {
        assertEquals(0L, chunks.first().startSample)
        assertEquals(sampleCount, chunks.last().endSample)
        assertTrue(chunks.all { it.leadingOverlapMs == 0 && it.endSample > it.startSample })
        chunks.zipWithNext().forEach { (previous, next) -> assertEquals(previous.endSample, next.startSample) }
        assertEquals(sampleCount, chunks.sumOf { it.endSample - it.startSample })
    }
}
