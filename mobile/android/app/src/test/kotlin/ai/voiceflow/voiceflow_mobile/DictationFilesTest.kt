package ai.voiceflow.voiceflow_mobile

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.ByteBuffer
import java.nio.ByteOrder

class DictationFilesTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun interruptedRecordingHeaderIsRebuiltWithoutChangingCompleteSamples() {
        val file = temporary.newFile("interrupted.wav")
        val samples = byteArrayOf(1, 2, 3, 4)
        file.writeBytes(ByteArray(44) + samples + byteArrayOf(5))
        DictationWav.repair(file)
        val bytes = file.readBytes()
        val header = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(48, bytes.size)
        assertEquals("RIFF", String(bytes.copyOfRange(0, 4)))
        assertEquals(40, header.getInt(4))
        assertEquals(16_000, header.getInt(24))
        assertEquals(4, header.getInt(40))
        assertArrayEquals(samples, bytes.copyOfRange(44, bytes.size))
    }

    @Test fun validRecordingRepairDoesNotInvalidateUploadMetadata() {
        val file = temporary.newFile("recording.wav")
        file.writeBytes(ByteArray(48))
        DictationWav.repair(file)
        file.setLastModified(1_700_000_000_000L)
        val modified = file.lastModified()
        val before = file.readBytes()
        DictationWav.repair(file)
        assertEquals(modified, file.lastModified())
        assertArrayEquals(before, file.readBytes())
    }

    @Test fun truncatedHeaderIsNotPromotedToAValidRecording() {
        val file = temporary.newFile("truncated.wav")
        file.writeBytes(byteArrayOf(1, 2, 3))
        try { DictationWav.repair(file); fail("Expected empty audio") }
        catch (e: DictationException) { assertEquals("empty_audio", e.code) }
        assertEquals(3L, file.length())
    }

    @Test fun journalReplacesCompleteStateAndConsumesTemporaryFile() {
        val file = temporary.newFile("session.json")
        DictationJournal.write(file, "{\"state\":\"recording\"}")
        DictationJournal.write(file, "{\"state\":\"delivered\"}")
        assertEquals("{\"state\":\"delivered\"}", file.readText())
        assertFalse(java.io.File(file.parentFile, "session.json.tmp").exists())
    }
}
