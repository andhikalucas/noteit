package ai.voiceflow.voiceflow_mobile

import android.content.Context
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Downloads Whisper GGML models from the official ggerganov/whisper.cpp
 * Hugging Face repo — the same source the desktop app uses — verifying size
 * and SHA-256 against the repo's LFS pointer before install. Models live in
 * filesDir/models and are shared by the bubble and in-app notes.
 */
object ModelStore {
    private const val REPO_BASE = "https://huggingface.co/ggerganov/whisper.cpp"

    fun fileNameFor(size: String): String = when (size) {
        "tiny" -> "ggml-tiny.bin"
        "small" -> "ggml-small.bin"
        else -> "ggml-base.bin"
    }

    fun modelsDir(context: Context): File =
        File(context.filesDir, "models").apply { mkdirs() }

    fun modelFile(context: Context, size: String): File =
        File(modelsDir(context), fileNameFor(size))

    fun isDownloaded(context: Context, size: String): Boolean {
        val f = modelFile(context, size)
        return f.exists() && f.length() > 1_000_000
    }

    /**
     * Ensures the model exists locally, downloading + verifying if needed.
     * [onProgress] receives 0..1. Throws on network/verification failure.
     */
    fun ensure(context: Context, size: String, onProgress: (Double) -> Unit): File {
        val target = modelFile(context, size)
        if (isDownloaded(context, size)) return target

        val file = fileNameFor(size)

        // 1. LFS pointer → expected sha256 + byte size.
        val pointer = readText("$REPO_BASE/raw/main/$file")
        val expectedSha = Regex("sha256:([0-9a-f]{64})").find(pointer)?.groupValues?.get(1)
            ?: throw IllegalStateException("Could not read model checksum")
        val expectedSize = Regex("size (\\d+)").find(pointer)?.groupValues?.get(1)?.toLong()
            ?: throw IllegalStateException("Could not read model size")

        // 2. Stream the real file, hashing as we go.
        val tmp = File(target.parentFile, "$file.download")
        val digest = MessageDigest.getInstance("SHA-256")
        try {
            val conn = (URL("$REPO_BASE/resolve/main/$file").openConnection() as HttpURLConnection)
            conn.connectTimeout = 30_000
            conn.readTimeout = 60_000
            conn.setRequestProperty("User-Agent", "noteit/1.0")
            conn.inputStream.use { input ->
                tmp.outputStream().use { output ->
                    val buf = ByteArray(1 shl 16)
                    var total = 0L
                    while (true) {
                        val read = input.read(buf)
                        if (read <= 0) break
                        total += read
                        // Disk-fill guard: never exceed the declared size.
                        if (total > expectedSize)
                            throw IllegalStateException("Model exceeded declared size")
                        output.write(buf, 0, read)
                        digest.update(buf, 0, read)
                        onProgress((total.toDouble() / expectedSize).coerceAtMost(1.0))
                    }
                }
            }

            // 3. Verify before moving into place.
            val actualSha = digest.digest().joinToString("") { "%02x".format(it) }
            if (tmp.length() != expectedSize || actualSha != expectedSha)
                throw IllegalStateException("Model failed verification")

            if (target.exists()) target.delete()
            if (!tmp.renameTo(target)) throw IllegalStateException("Could not install model")
            return target
        } catch (e: Exception) {
            tmp.delete()
            throw e
        }
    }

    private fun readText(url: String): String {
        val conn = (URL(url).openConnection() as HttpURLConnection)
        conn.connectTimeout = 30_000
        conn.readTimeout = 30_000
        conn.setRequestProperty("User-Agent", "noteit/1.0")
        return conn.inputStream.use { it.readBytes().decodeToString() }
    }
}
