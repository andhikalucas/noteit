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
     * Preloads the whisper context so the first utterance doesn't pay the
     * model-load cost (~2-4 s from flash). Safe to call from any thread; no-op
     * when unavailable or already loaded.
     */
    fun warmUp(context: Context, size: String) {
        if (!WhisperNative.available) return
        val model = ModelStore.modelFile(context, size)
        if (!model.exists()) return
        synchronized(lock) { ensureLoaded(model) }
    }

    /**
     * Trims leading/trailing silence (energy-based, 20 ms frames, adaptive
     * noise floor) with 300 ms of context kept on each side. Every second of
     * silence costs encoder time under audio_ctx scaling, and trailing silence
     * makes whisper hallucinate repeats — this fixes both.
     */
    fun trimSilence(s: FloatArray): FloatArray {
        val frame = 320 // 20 ms @ 16 kHz
        val n = s.size / frame
        if (n < 25) return s // < 0.5 s — nothing worth trimming
        val rms = FloatArray(n)
        for (i in 0 until n) {
            var acc = 0f
            val o = i * frame
            for (j in 0 until frame) { val v = s[o + j]; acc += v * v }
            rms[i] = kotlin.math.sqrt(acc / frame)
        }
        val sorted = rms.clone().also { it.sort() }
        val noiseFloor = sorted[n / 10] // 10th percentile
        val thr = maxOf(noiseFloor * 3f, 0.008f)
        var first = 0
        while (first < n && rms[first] < thr) first++
        var last = n - 1
        while (last > first && rms[last] < thr) last--
        if (first >= last) return s // no clear speech — leave untouched
        val pad = 15 // 300 ms
        val a = maxOf(0, first - pad) * frame
        val b = minOf(s.size, (last + 1 + pad) * frame)
        return if (a == 0 && b == s.size) s else s.copyOfRange(a, b)
    }

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

        val trimmed = trimSilence(samples)
        synchronized(lock) {
            if (!ensureLoaded(model)) return null
            val text = WhisperNative.fullTranscribe(ctxPtr, threads(), trimmed, lang, prompt)
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
