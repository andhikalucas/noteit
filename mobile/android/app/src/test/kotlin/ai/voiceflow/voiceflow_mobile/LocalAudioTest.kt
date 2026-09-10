package ai.voiceflow.voiceflow_mobile

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream

class LocalAudioTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun `pause boundaries cover each original sample without overlap`() {
        val chunks = mutableListOf<AudioChunk>()
        val chunker = LocalChunker(LocalPipelineConfig(), chunks::add)
        chunker.accept(ShortArray(16_000 * 6) { 500 })
        chunker.accept(ShortArray(8_000))
        chunker.accept(ShortArray(12_345) { 500 })
        chunker.finish()
        assertEquals(2, chunks.size)
        assertEquals(104_000L, chunks[0].endSample)
        assertEquals(chunks[0].endSample, chunks[1].startSample)
        assertEquals(116_345L, chunks.last().endSample)
        assertTrue(chunks.all { it.hasSpeech && it.leadingOverlapMs == 0 })
    }

    @Test fun `forced boundary carries only explicit audio overlap`() {
        val chunks = mutableListOf<AudioChunk>()
        val chunker = LocalChunker(LocalPipelineConfig(), chunks::add)
        chunker.accept(ShortArray(16_000 * 20) { 500 })
        chunker.finish()
        assertEquals(2, chunks.size)
        assertEquals(240_000L, chunks[0].endSample)
        assertEquals(233_600L, chunks[1].startSample)
        assertEquals(400, chunks[1].leadingOverlapMs)
        assertEquals(320_000L, chunks.last().endSample)
    }

    @Test fun `stop exactly on forced boundary does not replay the overlap`() {
        val chunks = mutableListOf<AudioChunk>()
        val chunker = LocalChunker(LocalPipelineConfig(), chunks::add)
        chunker.accept(ShortArray(16_000 * 15) { 500 })
        chunker.finish()
        assertEquals(1, chunks.size)
    }

    @Test fun `thirty second candidate keeps 29 point 999 seconds in one final chunk`() {
        val chunks = mutableListOf<AudioChunk>()
        val chunker = LocalChunker(
            LocalPipelineConfig(minimumChunkMs = 30_000, maximumChunkMs = 30_000), chunks::add,
        )
        val samples = 479_984 // 29.999 seconds at 16 kHz.
        chunker.accept(ShortArray(samples) { 500 })
        assertTrue(chunks.isEmpty())
        chunker.finish()
        assertEquals(listOf(AudioChunk(0, samples.toLong(), 0, true)), chunks)
        assertThirtySecondCandidateCoverage(chunks, samples.toLong())
    }

    @Test fun `thirty second candidate emits exactly once when stopped on boundary`() {
        val chunks = mutableListOf<AudioChunk>()
        val chunker = LocalChunker(
            LocalPipelineConfig(minimumChunkMs = 30_000, maximumChunkMs = 30_000), chunks::add,
        )
        chunker.accept(ShortArray(480_000) { 500 })
        assertEquals(listOf(AudioChunk(0, 480_000, 0, true)), chunks)
        chunker.finish()
        assertEquals(1, chunks.size) // No empty tail or overlap-only replay.
        assertThirtySecondCandidateCoverage(chunks, 480_000)
    }

    @Test fun `thirty second candidate preserves one fresh repeated speech sample after boundary`() {
        val chunks = mutableListOf<AudioChunk>()
        val chunker = LocalChunker(
            LocalPipelineConfig(minimumChunkMs = 30_000, maximumChunkMs = 30_000), chunks::add,
        )
        // Identical speech-level samples continue through the boundary. The
        // fresh sample remains eligible even though its partial frame is 1/320.
        chunker.accept(ShortArray(480_000) { 500 })
        chunker.accept(shortArrayOf(500))
        assertEquals(1, chunks.size)
        chunker.finish()
        assertEquals(listOf(
            AudioChunk(0, 480_000, 0, true),
            AudioChunk(473_600, 480_001, 400, true),
        ), chunks)
        assertThirtySecondCandidateCoverage(chunks, 480_001)
    }

    private fun assertThirtySecondCandidateCoverage(chunks: List<AudioChunk>, sampleCount: Long) {
        assertEquals(0L, chunks.first().startSample)
        assertEquals(sampleCount, chunks.last().endSample)
        assertTrue(chunks.all { it.hasSpeech && it.endSample > it.startSample })
        assertTrue(chunks.all { it.endSample - it.startSample <= 480_000 })
        chunks.zipWithNext().forEach { (previous, next) ->
            assertEquals(previous.endSample - next.leadingOverlapMs * 16L, next.startSample)
        }
        val freshSamples = chunks.sumOf { it.endSample - it.startSample - it.leadingOverlapMs * 16L }
        assertEquals(sampleCount, freshSamples)
    }

    @Test fun `input block boundaries do not affect chunks`() {
        fun chunks(blockSize: Int): List<AudioChunk> {
            val result = mutableListOf<AudioChunk>()
            val chunker = LocalChunker(LocalPipelineConfig(), result::add)
            val input = ShortArray(16_000 * 31 + 5) { if (it in 100_000..115_000) 0 else 500 }
            var offset = 0
            while (offset < input.size) {
                val end = minOf(input.size, offset + blockSize)
                chunker.accept(input.copyOfRange(offset, end)); offset = end
            }
            chunker.finish()
            return result
        }
        assertEquals(chunks(320), chunks(777))
    }

    @Test fun `digital silence is not sent to recognition but quiet speech is`() {
        val silent = mutableListOf<AudioChunk>()
        val detector = LocalChunker(LocalPipelineConfig(), silent::add)
        detector.accept(ShortArray(16_000 * 20)); detector.finish()
        assertTrue(silent.all { !it.hasSpeech })
        val quiet = mutableListOf<AudioChunk>()
        val quietDetector = LocalChunker(LocalPipelineConfig(), quiet::add)
        quietDetector.accept(ShortArray(16_000) { 100 }); quietDetector.finish()
        assertTrue(quiet.single().hasSpeech)
    }

    @Test fun `noise floor threshold finds a pause without discarding samples`() {
        val chunks = mutableListOf<AudioChunk>()
        val detector = LocalChunker(LocalPipelineConfig(silenceMs = 300, silenceThresholdRms = 512), chunks::add)
        detector.accept(ShortArray(96_000) { 1_000 })
        detector.accept(ShortArray(4_800) { 300 })
        detector.accept(ShortArray(8_000) { 1_000 })
        detector.finish()
        assertEquals(2, chunks.size)
        assertEquals(100_800L, chunks.first().endSample)
        assertEquals(0L, chunks.first().startSample)
        assertEquals(chunks.first().endSample, chunks.last().startSample)
        assertEquals(108_800L, chunks.last().endSample)
        assertTrue(chunks.all { it.hasSpeech && it.leadingOverlapMs == 0 })
    }

    @Test fun `speech below pause threshold stays eligible and default threshold is unchanged`() {
        val quiet = mutableListOf<AudioChunk>()
        val detector = LocalChunker(LocalPipelineConfig(silenceThresholdRms = 512), quiet::add)
        detector.accept(ShortArray(112_000) { 100 }); detector.finish()
        assertTrue(quiet.all { it.hasSpeech })
        assertEquals(112_000L, quiet.sumOf { it.endSample - it.startSample })

        val defaults = mutableListOf<AudioChunk>()
        val defaultDetector = LocalChunker(LocalPipelineConfig(), defaults::add)
        defaultDetector.accept(ShortArray(112_000) { 300 }); defaultDetector.finish()
        assertEquals(1, defaults.size)
        assertTrue(defaults.single().hasSpeech)
        assertEquals(64, LocalPipelineConfig().silenceThresholdRms)
        assertThrows(IllegalArgumentException::class.java) { LocalPipelineConfig(silenceThresholdRms = 63) }
        assertThrows(IllegalArgumentException::class.java) { LocalPipelineConfig(silenceThresholdRms = 2_049) }
    }

    @Test fun `WAV reader preserves signed PCM and skips padded metadata`() {
        val file = temporary.newFile("sample.wav")
        file.writeBytes(wave(shortArrayOf(-32768, -1, 0, 32767), metadata = true))
        PcmWavReader(file).use {
            assertEquals(4L, it.sampleCount)
            assertArrayEquals(shortArrayOf(-32768, -1, 0), it.readSamples(3))
            assertArrayEquals(shortArrayOf(32767), it.readSamples(3))
            assertNull(it.readSamples(3))
        }
    }

    @Test fun `WAV reader rejects truncated and wrong-rate recordings`() {
        val file = temporary.newFile("invalid.wav")
        val data = wave(shortArrayOf(1, 2))
        file.writeBytes(data.copyOf(data.size - 1))
        assertThrows(IllegalArgumentException::class.java) { PcmWavReader(file) }
        file.writeBytes(wave(shortArrayOf(1), rate = 44_100))
        assertThrows(IllegalArgumentException::class.java) { PcmWavReader(file) }
    }

    @Test fun `WAV reader accepts afconvert extensible PCM16 without changing samples`() {
        val file = temporary.newFile("extensible.wav")
        val samples = shortArrayOf(-32768, -1234, -1, 0, 1, 32767)
        file.writeBytes(wave(samples, extensible = true))
        PcmWavReader(file).use {
            assertEquals(samples.size.toLong(), it.sampleCount)
            assertArrayEquals(samples, it.readSamples(100))
            assertNull(it.readSamples(100))
        }
    }

    @Test fun `extensible WAV rejects float nonPCM and wrong valid bit count`() {
        val file = temporary.newFile("unsupported-extensible.wav")
        for (subtype in listOf(2, 3)) {
            file.writeBytes(wave(shortArrayOf(1), extensible = true, subtype = subtype))
            assertThrows(IllegalArgumentException::class.java) { PcmWavReader(file) }
        }
        for (bits in listOf(0, 12, 24)) {
            file.writeBytes(wave(shortArrayOf(1), extensible = true, validBits = bits))
            assertThrows(IllegalArgumentException::class.java) { PcmWavReader(file) }
        }
    }

    @Test fun `extensible WAV rejects truncated or inconsistent extension`() {
        val file = temporary.newFile("truncated-extensible.wav")
        val data = wave(shortArrayOf(1), extensible = true)
        file.writeBytes(data.copyOf(55)) // Declared fmt chunk ends at byte 60.
        assertThrows(IllegalArgumentException::class.java) { PcmWavReader(file) }
        for (length in listOf(21, 23)) {
            file.writeBytes(wave(shortArrayOf(1), extensible = true, extensionSize = length))
            assertThrows(IllegalArgumentException::class.java) { PcmWavReader(file) }
        }
        file.writeBytes(data.copyOf().also { it[59] = 0 }) // Wrong PCM subtype GUID suffix.
        assertThrows(IllegalArgumentException::class.java) { PcmWavReader(file) }
    }

    private fun wave(samples: ShortArray, metadata: Boolean = false, rate: Int = 16_000,
        extensible: Boolean = false, subtype: Int = 1, validBits: Int = 16, extensionSize: Int = 22): ByteArray {
        val body = ByteArrayOutputStream()
        fun text(s: String) { body.write(s.toByteArray(Charsets.US_ASCII)) }
        fun le(value: Int, bytes: Int) { repeat(bytes) { body.write(value ushr (it * 8) and 255) } }
        text("WAVE")
        if (metadata) { text("JUNK"); le(1, 4); body.write(7); body.write(0) }
        text("fmt "); le(if (extensible) 40 else 16, 4); le(if (extensible) 0xfffe else 1, 2)
        le(1, 2); le(rate, 4); le(rate * 2, 4); le(2, 2); le(16, 2)
        if (extensible) {
            le(extensionSize, 2); le(validBits, 2); le(4, 4); le(subtype, 4)
            body.write(byteArrayOf(0, 0, 0x10, 0, 0x80.toByte(), 0, 0, 0xaa.toByte(), 0, 0x38, 0x9b.toByte(), 0x71))
        }
        text("data"); le(samples.size * 2, 4); samples.forEach { le(it.toInt(), 2) }
        val output = ByteArrayOutputStream()
        output.write("RIFF".toByteArray())
        repeat(4) { output.write(body.size() ushr (it * 8) and 255) }
        output.write(body.toByteArray())
        return output.toByteArray()
    }
}
