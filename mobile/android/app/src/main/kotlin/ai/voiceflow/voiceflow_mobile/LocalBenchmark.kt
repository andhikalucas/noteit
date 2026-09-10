package ai.voiceflow.voiceflow_mobile

import android.content.Context
import android.os.Build
import android.os.Debug
import android.os.PowerManager
import android.os.SystemClock
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicBoolean

/** Called on a background thread by a debug-only MethodChannel endpoint. */
object LocalBenchmark {
    private val foregroundVisible = AtomicBoolean(false)
    private val backgroundEpoch = AtomicInteger(0)

    // Never take run's monitor: lifecycle callbacks must remain responsive
    // while the benchmark worker holds it for a complete replay.
    fun onForegroundStarted() { foregroundVisible.set(true) }
    fun onForegroundStopped() {
        foregroundVisible.set(false)
        backgroundEpoch.incrementAndGet()
    }

    @Synchronized
    fun run(context: Context, inputWav: File, configuration: JSONObject): JSONObject {
        check(BuildConfig.DEBUG && BuildConfig.BENCHMARK_BUILD) { "Benchmarks are only available in isolated development builds." }
        val startingBackgroundEpoch = backgroundEpoch.get()
        check(foregroundVisible.get()) { "Keep the benchmark app in the foreground before starting a run." }
        val engine = configuration.optString("engine", "whisper")
        require(engine == "whisper" || engine == "qwen" || engine == "formatting") { "Unknown benchmark engine." }
        val qwenAdapter = try { Class.forName("ai.voiceflow.voiceflow_mobile.QwenBenchmark") }
            catch (_: ClassNotFoundException) { null }
        if (engine == "formatting") {
            qwenAdapter?.getMethod("release")?.invoke(null)
            WhisperEngine.release()
            LocalCleanupEngine.release()
            val data = LocalFormattingBenchmark.run(context, configuration)
            val visible = foregroundVisible.get()
            return data.put("foregroundAtEnd", visible)
                .put("foregroundInterrupted", !visible || backgroundEpoch.get() != startingBackgroundEpoch)
        }
        LocalFormattingBenchmark.release()
        require(inputWav.isFile) { "Recording does not exist." }
        if (engine == "qwen") {
            require(!configuration.optBoolean("semanticCleanup", false)) {
                "The experimental Qwen recognizer benchmark does not include a cleanup pass."
            }
            val adapter = qwenAdapter ?: throw DictationException("qwen_unavailable",
                "Build the optional Qwen benchmark runtime first.")
            WhisperEngine.release()
            LocalCleanupEngine.release()
            val data = try {
                adapter.getMethod("run", Context::class.java, File::class.java, JSONObject::class.java)
                    .invoke(null, context, inputWav, configuration) as JSONObject
            } catch (error: java.lang.reflect.InvocationTargetException) {
                throw (error.targetException as? Exception ?: IllegalStateException("Qwen benchmark failed.", error.targetException))
            }
            val visible = foregroundVisible.get()
            return data.put("foregroundAtEnd", visible)
                .put("foregroundInterrupted", !visible || backgroundEpoch.get() != startingBackgroundEpoch)
        }
        // Optional runtime/source are absent from personal builds. Release a
        // previous Qwen experiment before measuring Whisper memory in this app.
        qwenAdapter?.getMethod("release")?.invoke(null)
        val recognition = LocalRecognitionConfig(
            model = configuration.optString("model", "small"),
            threads = configuration.optInt("threads", 4),
            scaledContext = configuration.optBoolean("scaledContext", true),
            language = configuration.optString("language", "auto"),
            useGpu = configuration.optBoolean("useGpu", false),
        )
        val config = LocalPipelineConfig(
            recognition, configuration.optInt("minimumChunkMs", 6_000),
            configuration.optInt("maximumChunkMs", 15_000),
            configuration.optInt("silenceMs", 500), configuration.optInt("overlapMs", 400),
            configuration.optBoolean("semanticCleanup", false), configuration.optInt("cleanupContextSentences", 3),
            configuration.optLong("cleanupBudgetMs", 10_000), configuration.optInt("cleanupThreads", 4),
            configuration.optInt("silenceThresholdRms", 64),
        )
        require(!recognition.useGpu || WhisperNative.vulkanCompiled()) {
            "Build with NOTEIT_WHISPER_VULKAN=ON before requesting GPU benchmarks."
        }
        val options = DictationOptions(engine = "local", vocabulary =
            configuration.optJSONArray("vocabulary")?.let { list ->
                (0 until list.length()).map { list.getString(it) }
            } ?: emptyList())
        val paced = configuration.optBoolean("paced", false)
        val pipeline = configuration.optBoolean("pipeline", true)
        val cold = configuration.optBoolean("cold", false)
        val resetModelBeforeWarm = configuration.optBoolean("resetModelBeforeWarm", false)
        if (configuration.optBoolean("downloadModel", false)) ModelStore.ensure(context, recognition.model) { }
        if (configuration.optBoolean("downloadCleanupModel", false)) LocalCleanupModel.ensure(context, {})
        if (config.semanticCleanup && !LocalCleanupEngine.ready(context)) {
            throw DictationException("cleanup_unavailable", "Prepare the offline cleanup runtime and model first.")
        }
        if (!WhisperEngine.modelReady(context, recognition.model)) {
            throw DictationException("model_missing", "Download the benchmark model first.")
        }
        if (cold || resetModelBeforeWarm) WhisperEngine.release()
        if (!cold) WhisperEngine.warmUp(context, recognition.model, recognition.useGpu)
        if (config.semanticCleanup) {
            if (cold) LocalCleanupEngine.release()
            else LocalCleanupEngine.prepare(context, config.cleanupThreads)
        } else {
            // Do not charge a previous configuration's resident cleanup model
            // to recognition-only memory measurements in randomized matrices.
            LocalCleanupEngine.release()
        }
        val count = PcmWavReader(inputWav).use { it.sampleCount }
        require(count in 1..(16_000L * 600)) { "Use a nonempty recording up to ten minutes." }
        val sha = MessageDigest.getInstance("SHA-256")
        inputWav.inputStream().use { stream ->
            val buffer = ByteArray(64 * 1024)
            while (true) { val n = stream.read(buffer); if (n < 0) break; sha.update(buffer, 0, n) }
        }
        val audioHash = sha.digest().joinToString("") { "%02x".format(it) }
        val peakPssKb = AtomicInteger(0)
        val sampler = Executors.newSingleThreadScheduledExecutor()
        sampler.scheduleAtFixedRate({
            val memory = Debug.MemoryInfo(); Debug.getMemoryInfo(memory)
            peakPssKb.accumulateAndGet(memory.totalPss, ::maxOf)
        }, 0, 250, TimeUnit.MILLISECONDS)
        val power = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        fun thermal(): Int = if (Build.VERSION.SDK_INT >= 29) power.currentThermalStatus else -1
        val thermalStart = thermal()
        val start = SystemClock.elapsedRealtime()
        var stopped = start
        var worstIngressLag = 0L
        var warmupMs = 0L
        val chunks = JSONArray()
        val cleanupRuns = JSONArray()
        var session: PipelinedLocalSession? = null
        try {
            val result: TranscriptionResult
            if (pipeline) {
                val active = LocalTranscriber(context, config).startMeasuredSession(options) { Thread.currentThread().isInterrupted }
                session = active
                var delivered = 0L
                PcmWavReader(inputWav).use { reader ->
                    while (true) {
                        val block = reader.readSamples(1_600) ?: break
                        delivered += block.size
                        if (paced) {
                            val deadline = start + delivered / 16
                            waitUntil(deadline)
                            worstIngressLag = maxOf(worstIngressLag, SystemClock.elapsedRealtime() - deadline)
                        }
                        active.accept(block)
                    }
                }
                stopped = SystemClock.elapsedRealtime()
                result = try { active.finish(inputWav) }
                catch (error: DictationException) {
                    if (error.code != "no_speech") throw error
                    TranscriptionResult("", "")
                }
                warmupMs = active.warmupMs
                active.measurements().forEach { m -> chunks.put(JSONObject()
                    .put("startSample", m.startSample).put("endSample", m.endSample)
                    .put("queuedMs", m.queuedMs - start).put("startedMs", m.startedMs - start)
                    .put("finishedMs", m.finishedMs - start).put("loadMs", m.loadMs)
                    .put("inferenceMs", m.inferenceMs).put("text", m.text)) }
                active.cleanupMeasurements().forEach { m -> cleanupRuns.put(JSONObject()
                    .put("startedMs", m.startedMs - start).put("finishedMs", m.finishedMs - start)
                    .put("loadMs", m.result.loadMs).put("inferenceMs", m.result.inferenceMs)
                    .put("accepted", m.result.accepted).put("reason", m.result.reason ?: JSONObject.NULL)
                    .put("text", m.result.text)) }
            } else {
                // Original record-then-transcribe baseline, including its trim.
                val samples = FloatArray(count.toInt())
                var offset = 0
                PcmWavReader(inputWav).use { reader ->
                    while (true) {
                        val block = reader.readSamples(1_600) ?: break
                        for (s in block) samples[offset++] = s / 32768f
                    }
                }
                if (paced) waitUntil(start + count / 16)
                stopped = SystemClock.elapsedRealtime()
                val recognized = WhisperEngine.recognize(context, WhisperEngine.trimSilence(samples), recognition,
                    options.vocabulary.joinToString(", ").ifBlank { null })
                    ?: throw DictationException("local_failed", "Benchmark transcription failed.")
                warmupMs = recognized.loadMs
                chunks.put(JSONObject().put("startSample", 0).put("endSample", count)
                    .put("queuedMs", stopped - start).put("startedMs", stopped - start)
                    .put("finishedMs", SystemClock.elapsedRealtime() - start)
                    .put("loadMs", recognized.loadMs).put("inferenceMs", recognized.inferenceMs)
                    .put("text", recognized.text))
                val cleaned = if (config.semanticCleanup) {
                    val cleanupStart = SystemClock.elapsedRealtime()
                    val value = LocalCleanupEngine.clean(context, recognized.text, options.vocabulary, { false },
                        config.cleanupThreads, config.cleanupBudgetMs)
                    cleanupRuns.put(JSONObject().put("startedMs", cleanupStart - start)
                        .put("finishedMs", SystemClock.elapsedRealtime() - start)
                        .put("loadMs", value.loadMs).put("inferenceMs", value.inferenceMs)
                        .put("accepted", value.accepted).put("reason", value.reason ?: JSONObject.NULL).put("text", value.text))
                    value.text
                } else recognized.text
                result = TranscriptionResult(cleaned, recognized.text)
            }
            val end = SystemClock.elapsedRealtime()
            val foregroundAtEnd = foregroundVisible.get()
            val foregroundInterrupted = !foregroundAtEnd || backgroundEpoch.get() != startingBackgroundEpoch
            val inference = (0 until chunks.length()).sumOf { chunks.getJSONObject(it).getLong("inferenceMs") }
            val cleanupTime = (0 until cleanupRuns.length()).sumOf {
                val row = cleanupRuns.getJSONObject(it); row.getLong("finishedMs") - row.getLong("startedMs")
            }
            return JSONObject()
                .put("schemaVersion", 1).put("configuration", configuration)
                .put("device", "${Build.MANUFACTURER} ${Build.MODEL}").put("androidSdk", Build.VERSION.SDK_INT)
                .put("whisperRevision", "080bbbe85230f624f0b52127f1ae1218247989f9")
                .put("whisperAudioContextPatch", "before-language-detection-v1")
                .put("whisperFlashPaddingPatch", "clear-before-encode-v1")
                .put("modelFile", ModelStore.fileNameFor(recognition.model))
                .put("modelBytes", ModelStore.modelFile(context, recognition.model).length())
                .put("audioSha256", audioHash).put("audioMs", count / 16.0)
                .put("cold", cold).put("resetModelBeforeWarm", resetModelBeforeWarm)
                .put("paced", paced).put("pipeline", pipeline)
                .put("elapsedMs", end - start).put("postStopMs", end - stopped)
                .put("foregroundInterrupted", foregroundInterrupted).put("foregroundAtEnd", foregroundAtEnd)
                .put("warmupMs", warmupMs).put("recognitionMs", inference)
                .put("recognitionRealTimeFactor", inference / (count / 16.0))
                .put("cleanupMs", if (config.semanticCleanup) cleanupTime else JSONObject.NULL)
                .put("semanticCleanup", if (config.semanticCleanup) LocalCleanupModel.ID else "disabled")
                .put("cleanupRuntimeRevision", if (config.semanticCleanup) LocalCleanupEngine.RUNTIME_REVISION else JSONObject.NULL)
                .put("cleanupRuns", cleanupRuns)
                .put("processingRealTimeFactor", (inference + cleanupTime) / (count / 16.0))
                .put("maxReplayLagMs", worstIngressLag).put("sampledPeakPssKb", peakPssKb.get())
                .put("thermalStart", thermalStart).put("thermalEnd", thermal())
                .put("rawText", result.rawText).put("text", result.text).put("chunks", chunks)
        } finally {
            session?.cancel()
            sampler.shutdownNow()
        }
    }

    private fun waitUntil(deadline: Long) {
        while (true) {
            val remaining = deadline - SystemClock.elapsedRealtime()
            if (remaining <= 0) return
            Thread.sleep(minOf(remaining, 100))
        }
    }
}
