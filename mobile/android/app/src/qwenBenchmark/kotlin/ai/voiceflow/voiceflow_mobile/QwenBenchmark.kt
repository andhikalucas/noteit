package ai.voiceflow.voiceflow_mobile

import android.content.Context
import android.os.Build
import android.os.Debug
import android.os.SystemClock
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineQwen3AsrModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean

/** Explicit opt-in benchmark source set. Never compiled into the personal app. */
object QwenBenchmark {
    private var recognizer: OfflineRecognizer? = null
    private var loadedIdentity: String? = null
    private val cancelled = AtomicBoolean(false)
    private val expectedHashes = linkedMapOf(
        "conv_frontend.onnx" to "d22dc4423e0940e49884e903d2ea2f7e5567c14fc1aed97e4e26d6b8f208ef9e",
        "encoder.int8.onnx" to "60748d3e6744a57c9c91e1b17424a6c2990567e8adceb0783940c03ed98fa9d9",
        "decoder.int8.onnx" to "4f6885be5959ae26af3089d38ee7972c5fafbeeb1cf8d5e76eab6d8b61ca5771",
        "tokenizer/vocab.json" to "ca10d7e9fb3ed18575dd1e277a2579c16d108e32f27439684afa0e10b1440910",
        "tokenizer/merges.txt" to "8831e4f1a044471340f7c0a83d7bd71306a5b867e95fd870f74d0c5308a904d5",
        "tokenizer/tokenizer_config.json" to "4942d005604266809309cabc9f4e9cb89ce855d59b14681fdc0e1cc62ea26c4c",
    )

    @JvmStatic fun cancel() { cancelled.set(true) }

    // Synchronization intentionally waits for native decode to finish before freeing anything.
    @JvmStatic @Synchronized fun release() {
        recognizer?.release(); recognizer = null; loadedIdentity = null
    }

    @JvmStatic @Synchronized fun run(context: Context, wav: File, config: JSONObject): JSONObject {
        check(BuildConfig.DEBUG && BuildConfig.BENCHMARK_BUILD && BuildConfig.QWEN_BENCHMARK)
        check(context.packageName == "ai.voiceflow.voiceflow_mobile.benchmark")
        require(!config.optBoolean("pipeline", false) && !config.optBoolean("paced", false)) {
            "Qwen benchmark currently accepts only unpaced whole-file replay."
        }
        require(!config.optBoolean("semanticCleanup", false)) { "Evaluate Qwen recognition separately from cleanup." }
        val threads = config.optInt("threads", 4)
        require(threads in 1..8)
        val samples = PcmWavReader(wav).use { reader ->
            require(reader.sampleCount in 1..480000L) { "Qwen benchmark requires nonempty audio up to 30 seconds." }
            reader.readSamples(reader.sampleCount.toInt())!!.let { pcm -> FloatArray(pcm.size) { pcm[it] / 32768f } }
        }
        val root = File(context.filesDir, "benchmarks").canonicalFile
        val directory = File(config.optString("qwenModelDir", File(root, "qwen3-asr-int8").path)).canonicalFile
        require(directory.path.startsWith(root.path + File.separator)) { "Use model files inside the benchmark sandbox." }
        val identity = directory.path + ":$threads:" + expectedHashes.keys.joinToString(":") {
            File(directory, it).let { file -> "${file.length()}:${file.lastModified()}" }
        }
        val cold = config.optBoolean("cold", false)
        if (cold || loadedIdentity != identity || config.optBoolean("resetModelBeforeWarm", false)) release()
        cancelled.set(false)
        val initial = SystemClock.elapsedRealtimeNanos()
        var loadMs = 0.0
        if (recognizer == null) {
            expectedHashes.forEach { (name, hash) -> require(sha256(File(directory, name)) == hash) { "Qwen asset verification failed: $name" } }
            recognizer = OfflineRecognizer(config = OfflineRecognizerConfig(
                featConfig = FeatureConfig(sampleRate = 16000, featureDim = 128, dither = 0f),
                modelConfig = OfflineModelConfig(
                    qwen3Asr = OfflineQwen3AsrModelConfig(
                        convFrontend = File(directory, "conv_frontend.onnx").path,
                        encoder = File(directory, "encoder.int8.onnx").path,
                        decoder = File(directory, "decoder.int8.onnx").path,
                        tokenizer = File(directory, "tokenizer").path,
                        maxTotalLen = 512, maxNewTokens = 512, temperature = 1e-6f, topP = 0.8f, seed = 42,
                    ), numThreads = threads, provider = "cpu", debug = false,
                ), decodingMethod = "greedy_search",
            ))
            loadedIdentity = identity
            loadMs = (SystemClock.elapsedRealtimeNanos() - initial) / 1e6
        }
        if (cancelled.get() || Thread.currentThread().isInterrupted) throw DictationException("cancelled", "Benchmark cancelled.")
        val runtime = recognizer!!
        val processingStart = SystemClock.elapsedRealtimeNanos()
        val stream = runtime.createStream() // Hotword overload is not supported by this model.
        val text: String
        val recognitionMs: Double
        try {
            stream.acceptWaveform(samples, sampleRate = 16000)
            val start = SystemClock.elapsedRealtimeNanos()
            runtime.decode(stream)
            recognitionMs = (SystemClock.elapsedRealtimeNanos() - start) / 1e6
            if (cancelled.get() || Thread.currentThread().isInterrupted) throw DictationException("cancelled", "Benchmark cancelled.")
            text = runtime.getResult(stream).text.trim()
        } finally { stream.release() }
        val processingMs = (SystemClock.elapsedRealtimeNanos() - processingStart) / 1e6
        val memory = Debug.MemoryInfo().also { Debug.getMemoryInfo(it) }
        val audioMs = samples.size / 16.0
        return JSONObject().put("schemaVersion", 1).put("configuration", config)
            .put("device", "${Build.MANUFACTURER} ${Build.MODEL}").put("androidSdk", Build.VERSION.SDK_INT)
            .put("runtime", "sherpa-onnx-android").put("sherpaVersion", "1.13.7")
            .put("model", "qwen3-asr-0.6B-int8-2026-03-25").put("modelHashes", JSONObject(expectedHashes))
            .put("audioSha256", sha256(wav)).put("audioMs", audioMs)
            .put("rawText", text).put("text", text).put("loadMs", loadMs)
            .put("recognitionMs", recognitionMs).put("processingMs", processingMs)
            .put("postStopMs", processingMs + if (cold) loadMs else 0.0)
            .put("recognitionRealTimeFactor", recognitionMs / audioMs)
            .put("paced", false).put("pipeline", false).put("cold", cold)
            .put("cleanupMs", JSONObject.NULL).put("pssKbAfter", memory.totalPss)
            .put("nativeHeapBytesAfter", Debug.getNativeHeapAllocatedSize())
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val bytes = ByteArray(65536)
            while (true) { val n = input.read(bytes); if (n < 0) break; digest.update(bytes, 0, n) }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
