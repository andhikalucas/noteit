package ai.voiceflow.voiceflow_mobile

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Only for app-owned 16 kHz mono PCM16 recordings with a fixed 44-byte header. */
internal object DictationWav {
    fun repair(file: File) {
        if (!file.isFile || file.length() < 44) throw DictationException("empty_audio", "No saved audio is available.")
        RandomAccessFile(file, "rw").use { output ->
            val bytes = ((output.length() - 44) / 2) * 2
            val current = ByteArray(44)
            output.readFully(current)
            if (output.length() == bytes + 44 && current.contentEquals(header(bytes))) return
            output.setLength(bytes + 44) // Ignore an interrupted, incomplete PCM sample.
            writeHeader(output, bytes)
        }
    }

    fun writeHeader(file: RandomAccessFile, bytes: Long) {
        file.seek(0)
        file.write(header(bytes))
        file.fd.sync()
    }

    private fun header(bytes: Long): ByteArray {
        require(bytes in 0..(0xffffffffL - 36) && bytes % 2 == 0L)
        return ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray()); putInt((bytes + 36).toInt()); put("WAVEfmt ".toByteArray())
            putInt(16); putShort(1); putShort(1); putInt(16_000); putInt(32_000)
            putShort(2); putShort(16); put("data".toByteArray()); putInt(bytes.toInt())
        }.array()
    }
}

internal object DictationJournal {
    /** Commit a complete synced document before replacing the previous valid state. */
    fun write(target: File, text: String) {
        val pending = File(target.parentFile, "${target.name}.tmp")
        RandomAccessFile(pending, "rw").use { output ->
            output.setLength(0)
            output.write(text.toByteArray(Charsets.UTF_8))
            output.fd.sync()
        }
        if (!pending.renameTo(target)) throw DictationException("storage", "Could not save the recording state.")
    }
}
