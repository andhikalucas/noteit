package ai.voiceflow.voiceflow_mobile

import android.content.Context
import java.io.File

/**
 * High-level on-device Whisper transcription over [WhisperNative]. Loads a
 * model context lazily, reuses it across calls, and reloads when the selected
 * model file changes. Inference is serialized (whisper contexts aren't
 * thread-safe). Shared by the bubble and, via MethodChannel, in-app notes.
 */
object WhisperEngine {
    private val lock = Any()
    private var ctxPtr: Long = 0
    private var loadedPath: String? = null

    val available: Boolean get() = WhisperNative.available

    private fun threads(): Int {
        val cores = Runtime.getRuntime().availableProcessors()
        return (cores / 2).coerceIn(2, 8)
    }

    /** True once a usable model for [size] is on disk. */
    fun modelReady(context: Context, size: String): Boolean =
        ModelStore.isDownloaded(context, size)

    /**
     * Transcribes [samples] (16 kHz mono float) with the model for [size].
     * Returns text, or null if the model isn't downloaded / the engine failed.
     */
    fun transcribe(
        context: Context,
        size: String,
        samples: FloatArray,
        lang: String?,
        prompt: String?,
    ): String? {
        if (!WhisperNative.available) return null
        val model = ModelStore.modelFile(context, size)
        if (!model.exists()) return null

        synchronized(lock) {
            if (!ensureLoaded(model)) return null
            val text = WhisperNative.fullTranscribe(ctxPtr, threads(), samples, lang, prompt)
            return text?.trim()
        }
    }

    private fun ensureLoaded(model: File): Boolean {
        val path = model.absolutePath
        if (ctxPtr != 0L && loadedPath == path) return true
        if (ctxPtr != 0L) {
            WhisperNative.freeContext(ctxPtr)
            ctxPtr = 0
            loadedPath = null
        }
        val ptr = WhisperNative.initContext(path)
        if (ptr == 0L) return false
        ctxPtr = ptr
        loadedPath = path
        return true
    }

    fun release() {
        synchronized(lock) {
            if (ctxPtr != 0L) WhisperNative.freeContext(ctxPtr)
            ctxPtr = 0
            loadedPath = null
        }
    }
}
