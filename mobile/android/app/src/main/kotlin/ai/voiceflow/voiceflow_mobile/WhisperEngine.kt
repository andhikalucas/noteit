package ai.voiceflow.voiceflow_mobile

import android.content.Context
import java.io.File
import android.os.SystemClock

data class LocalRecognitionConfig(
    val model: String = "small",
    val threads: Int = 4,
    val scaledContext: Boolean = true,
    val language: String = "auto",
    val useGpu: Boolean = false,
    val beamSize: Int = 1,
    // Set only by the isolated benchmark. Null preserves the normal vocabulary
    // prompt; an empty override explicitly tests decoding without a prompt.
    val benchmarkPromptOverride: String? = null,
) {
    init {
        require(threads in 1..8)
        require(beamSize in 1..5) { "Beam size must be between 1 and 5." }
        require(benchmarkPromptOverride == null ||
            (benchmarkPromptOverride.length <= 512 && '\u0000' !in benchmarkPromptOverride)) {
            "The benchmark recognition prompt must contain at most 512 characters and no null characters."
        }
    }

    fun decodingPrompt(vocabularyPrompt: String?): String? =
        benchmarkPromptOverride ?: vocabularyPrompt
}

data class LocalRecognitionResult(val text: String, val loadMs: Long, val inferenceMs: Long)

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
    private var loadedBytes: Long = 0
    private var loadedModified: Long = 0
    private var loadedGpu: Boolean = false

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
     * model-load cost. Call on a background worker; no-op
     * when unavailable or already loaded.
     */
    fun warmUp(context: Context, size: String, useGpu: Boolean = false) {
        if (!WhisperNative.available) return
        val model = ModelStore.modelFile(context, size)
        if (!model.exists()) return
        synchronized(lock) {
            // Warm-up is opportunistic. Actual dictation surfaces a verification failure.
            val verified = try { ModelStore.requireVerified(context, size) }
                catch (_: Exception) { return }
            ensureLoaded(verified, useGpu)
        }
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

        return recognize(context, trimSilence(samples),
            LocalRecognitionConfig(size, threads(), language = lang ?: "auto"), prompt,
        )?.text
    }

    /** No trimming here: sample positions must remain stable for overlap. */
    fun recognize(
        context: Context,
        samples: FloatArray,
        config: LocalRecognitionConfig,
        prompt: String?,
        dropLeadingMs: Int = 0,
        cancelled: () -> Boolean = { false },
    ): LocalRecognitionResult? {
        if (!WhisperNative.available || cancelled()) return null
        val model = ModelStore.modelFile(context, config.model)
        if (!model.exists()) return null
        synchronized(lock) {
            if (cancelled()) return null
            val start = SystemClock.elapsedRealtime()
            val verified = ModelStore.requireVerified(context, config.model)
            if (cancelled() || !ensureLoaded(verified, config.useGpu)) return null
            val loaded = SystemClock.elapsedRealtime()
            val text = WhisperNative.fullTranscribe(
                ctxPtr, config.threads, samples, config.language, config.decodingPrompt(prompt),
                config.scaledContext, dropLeadingMs, WhisperCancellation(cancelled),
                config.beamSize,
            ) ?: return null
            return LocalRecognitionResult(text.trim(), loaded - start, SystemClock.elapsedRealtime() - loaded)
        }
    }

    private fun ensureLoaded(model: File, useGpu: Boolean = false): Boolean {
        val path = model.absolutePath
        if (ctxPtr != 0L && loadedPath == path && loadedGpu == useGpu &&
            loadedBytes == model.length() && loadedModified == model.lastModified()) return true
        if (ctxPtr != 0L) {
            WhisperNative.freeContext(ctxPtr)
            ctxPtr = 0
            loadedPath = null
        }
        val ptr = WhisperNative.initContext(path, useGpu)
        if (ptr == 0L) return false
        ctxPtr = ptr
        loadedPath = path
        loadedBytes = model.length()
        loadedModified = model.lastModified()
        loadedGpu = useGpu
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
