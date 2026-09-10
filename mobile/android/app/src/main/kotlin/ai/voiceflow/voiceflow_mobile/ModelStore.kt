package ai.voiceflow.voiceflow_mobile

import android.content.Context
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/** Official Whisper bytes pinned to one immutable Hugging Face revision. */
object ModelStore {
    private const val REPO_BASE = "https://huggingface.co/ggerganov/whisper.cpp"
    const val REVISION = "5359861c739e955e79d9a303bcbc70fb988958b1"
    private data class Asset(val name: String, val bytes: Long, val sha256: String)
    private val assets = mapOf(
        "tiny" to Asset("ggml-tiny-q5_1.bin", 32152673,
            "818710568da3ca15689e31a743197b520007872ff9576237bda97bd1b469c3d7"),
        "base" to Asset("ggml-base-q5_1.bin", 59707625,
            "422f1ae452ade6f30a004d7e5c6a43195e4433bc370bf23fac9cc591f01a8898"),
        "small" to Asset("ggml-small-q5_1.bin", 190085487,
            "ae85e4a935d7a567bd102fe55afc16bb595bdb618e11b2fc7591bc08120411bb"),
        // Development benchmark candidate, not a settings choice.
        "base-q8" to Asset("ggml-base-q8_0.bin", 81768585,
            "c577b9a86e7e048a0b7eada054f4dd79a56bbfa911fbdacf900ac5b567cbb7d9"),
        "small-q8" to Asset("ggml-small-q8_0.bin", 264464607,
            "49c8fb02b65e6049d5fa6c04f81f53b867b5ec9540406812c643f177317f779f"),
        "medium-q8" to Asset("ggml-medium-q8_0.bin", 823369779,
            "42a1ffcbe4167d224232443396968db4d02d4e8e87e213d3ee2e03095dea6502"),
    )
    private data class Stamp(val bytes: Long, val modified: Long, val valid: Boolean)
    private val verified = ConcurrentHashMap<String, Stamp>()
    private val locks = ConcurrentHashMap<String, Any>()
    private fun asset(size: String) = assets[size] ?: throw IllegalArgumentException("Unknown local model: $size")
    private fun lock(file: File) = locks.getOrPut(file.absolutePath) { Any() }

    fun fileNameFor(size: String): String = asset(size).name
    fun modelsDir(context: Context): File = File(context.filesDir, "models").apply { mkdirs() }
    fun modelFile(context: Context, size: String): File = File(modelsDir(context), fileNameFor(size))

    /** Cheap presence check. Legacy files are SHA-checked on a worker before JNI loading. */
    fun isDownloaded(context: Context, size: String): Boolean {
        val file = modelFile(context, size)
        if (!file.isFile || file.length() != asset(size).bytes) return false
        val stamp = verified[file.absolutePath]
        return stamp == null || stamp.bytes != file.length() || stamp.modified != file.lastModified() || stamp.valid
    }

    /** Local-only validation; call on the model worker, never from UI status queries. */
    fun requireVerified(context: Context, size: String): File {
        val target = modelFile(context, size)
        synchronized(lock(target)) {
            if (!checkExisting(target, asset(size))) {
                throw DictationException("model_invalid", "Download the local model again in Settings.")
            }
            return target
        }
    }

    private fun checkExisting(file: File, asset: Asset): Boolean {
        val bytes = file.length()
        val modified = file.lastModified()
        val previous = verified[file.absolutePath]
        if (previous != null && previous.bytes == bytes && previous.modified == modified) return previous.valid
        val valid = ModelIntegrity.verify(file, asset.bytes, asset.sha256)
        verified[file.absolutePath] = Stamp(bytes, modified, valid)
        return valid
    }

    /** Downloads only when explicitly requested; concurrent callers share one verified install. */
    fun ensure(context: Context, size: String, onProgress: (Double) -> Unit): File {
        val target = modelFile(context, size)
        val asset = asset(size)
        synchronized(lock(target)) {
            if (checkExisting(target, asset)) { onProgress(1.0); return target }
            val temporary = File.createTempFile("${asset.name}.", ".download", target.parentFile)
            val connection = URL("$REPO_BASE/resolve/$REVISION/${asset.name}").openConnection() as HttpURLConnection
            try {
                connection.connectTimeout = 30_000
                connection.readTimeout = 60_000
                connection.setRequestProperty("User-Agent", "noteit/1.0")
                if (connection.responseCode !in 200..299) throw IllegalStateException("Model download unavailable")
                connection.inputStream.use { input ->
                    FileOutputStream(temporary).use { output ->
                        val buffer = ByteArray(64 * 1024)
                        var total = 0L
                        while (true) {
                            if (Thread.currentThread().isInterrupted) throw InterruptedException("Download cancelled")
                            val count = input.read(buffer)
                            if (count < 0) break
                            if (count == 0) continue
                            total += count
                            if (total > asset.bytes) throw IllegalStateException("Model exceeded pinned size")
                            output.write(buffer, 0, count)
                            onProgress((total.toDouble() / asset.bytes).coerceAtMost(1.0))
                        }
                        output.fd.sync()
                    }
                }
                ModelIntegrity.install(temporary, target, asset.bytes, asset.sha256)
                verified[target.absolutePath] = Stamp(target.length(), target.lastModified(), true)
                onProgress(1.0)
                return target
            } finally {
                connection.disconnect()
                temporary.delete()
            }
        }
    }
}

internal object ModelIntegrity {
    fun verify(file: File, expectedBytes: Long, expectedSha: String): Boolean {
        if (!file.isFile || file.length() != expectedBytes) return false
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                if (Thread.currentThread().isInterrupted) throw InterruptedException("Verification cancelled")
                val count = input.read(buffer)
                if (count < 0) break
                if (count > 0) digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) } == expectedSha
    }

    fun install(candidate: File, target: File, expectedBytes: Long, expectedSha: String) {
        if (!verify(candidate, expectedBytes, expectedSha)) throw IllegalStateException("Model failed pinned verification")
        // Both files share the models directory. POSIX rename replaces atomically; never delete the old model first.
        if (!candidate.renameTo(target)) throw IllegalStateException("Could not install model")
    }
}
