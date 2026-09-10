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

    internal fun parseBeamSize(configuration: JSONObject): Int {
        if (!configuration.has("beamSize") || configuration.isNull("beamSize")) return 1
        val value = configuration.get("beamSize")
        // JSONObject.optInt silently coerces strings and truncates fractions.
        // A malformed experiment must fail instead of running a different arm.
        require(value is Int || value is Long) { "Beam size must be a JSON integer between 1 and 5." }
        val size = (value as Number).toLong()
        require(size in 1L..5L) { "Beam size must be between 1 and 5." }
        return size.toInt()
    }

    internal fun parsePauseOnly(configuration: JSONObject): Boolean {
        if (!configuration.has("benchmarkPauseOnly") || configuration.isNull("benchmarkPauseOnly")) return false
        val value = configuration.get("benchmarkPauseOnly")
        require(value is Boolean) { "benchmarkPauseOnly must be a JSON boolean." }
        if (value) {
            for ((key, expected) in listOf("maximumChunkMs" to 600_000, "overlapMs" to 0)) {
                if (!configuration.has(key)) continue
                val supplied = configuration.get(key)
                require((supplied is Int || supplied is Long) && (supplied as Number).toLong() == expected.toLong()) {
                    "Pause-only benchmarks require $key=$expected as a JSON integer."
                }
            }
        }
        return value
    }

    internal fun parseNormalPriority(configuration: JSONObject): Boolean {
        if (!configuration.has("benchmarkNormalPriority")) return false
        val value = configuration.get("benchmarkNormalPriority")
        require(value is Boolean) { "benchmarkNormalPriority must be a JSON boolean." }
        if (value) {
            require(configuration.optString("engine", "whisper") == "whisper") {
                "Normal worker priority requires the Whisper pipeline."
            }
            require(!configuration.has("pipeline") || configuration.get("pipeline") == true) {
                "Normal worker priority requires pipeline=true as a JSON boolean."
            }
        }
        return value
    }

    internal fun parsePunctuationFormatting(configuration: JSONObject): JSONObject? {
        if (!configuration.has("punctuationFormatting")) return null
        val nested = configuration.get("punctuationFormatting")
        require(nested is JSONObject) { "punctuationFormatting must be a JSON object." }
        require(configuration.optString("engine", "whisper") == "whisper") {
            "Combined punctuation formatting supports only Whisper."
        }
        require(!configuration.has("semanticCleanup") || configuration.get("semanticCleanup") == false) {
            "Combined punctuation formatting cannot also use semantic cleanup."
        }
        for (key in listOf("cold", "resetModelBeforeWarm", "pipeline")) {
            require(!configuration.has(key) || configuration.get(key) is Boolean) { "$key must be a JSON boolean." }
        }
        val supported = setOf("modelKind", "modelFile", "tokenizeOnDevice", "tokenizerFile")
        require(nested.keys().asSequence().all { it in supported }) {
            "Use only modelKind, modelFile, tokenizeOnDevice, and optional tokenizerFile; outer cold controls loading."
        }
        require(nested.opt("modelKind") in setOf("small", "xlmr")) { "Unsupported punctuation model." }
        val file = nested.opt("modelFile")
        require(file is String && file.matches(Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,95}\\.onnx"))) {
            "Use a punctuation model basename ending in .onnx."
        }
        require(nested.opt("tokenizeOnDevice") == true) { "Combined formatting requires tokenizeOnDevice=true." }
        if (nested.has("tokenizerFile")) {
            val expected = if (nested.getString("modelKind") == "xlmr") "sp.model" else "spe_unigram_64k_lowercase_47lang.model"
            require(nested.get("tokenizerFile") == expected) { "Use the pinned tokenizer basename for this model." }
        }
        return JSONObject(nested.toString())
    }

    internal fun parseReadableCleanup(configuration: JSONObject): Boolean {
        if (!configuration.has("readableCleanup")) return false
        val value = configuration.get("readableCleanup")
        require(value is Boolean) { "readableCleanup must be a JSON boolean." }
        require(!value || parsePunctuationFormatting(configuration) != null) {
            "Readable cleanup requires the combined punctuation formatter."
        }
        return value
    }

    private fun invokePunctuation(adapter: Class<*>, method: String, context: Context,
        config: JSONObject, raw: String? = null): JSONObject = try {
        if (raw == null) adapter.getMethod(method, Context::class.java, JSONObject::class.java)
            .invoke(null, context, config) as JSONObject
        else adapter.getMethod(method, Context::class.java, String::class.java, JSONObject::class.java)
            .invoke(null, context, raw, config) as JSONObject
    } catch (error: java.lang.reflect.InvocationTargetException) {
        throw (error.targetException as? Exception ?: IllegalStateException("Punctuation formatter failed.", error.targetException))
    }

    internal fun formattingFailure(raw: String, error: Exception): JSONObject = JSONObject()
        .put("rawText", raw).put("candidateText", raw).put("text", raw)
        .put("outcome", "error_raw_fallback").put("accepted", false).put("reviewRequired", true)
        .put("errorType", error.javaClass.simpleName).put("error", error.message ?: "Formatting failed.")
        .put("guardFlags", JSONArray().put("formatting_exception"))

    @Synchronized
    fun run(context: Context, inputWav: File, configuration: JSONObject): JSONObject {
        check(BuildConfig.DEBUG && BuildConfig.BENCHMARK_BUILD) { "Benchmarks are only available in isolated development builds." }
        val startingBackgroundEpoch = backgroundEpoch.get()
        check(foregroundVisible.get()) { "Keep the benchmark app in the foreground before starting a run." }
        val engine = configuration.optString("engine", "whisper")
        require(engine in setOf("whisper", "qwen", "formatting", "punctuation")) { "Unknown benchmark engine." }
        val normalPriority = parseNormalPriority(configuration)
        val punctuationConfig = parsePunctuationFormatting(configuration)
        val readableCleanup = parseReadableCleanup(configuration)
        val qwenAdapter = try { Class.forName("ai.voiceflow.voiceflow_mobile.QwenBenchmark") }
            catch (_: ClassNotFoundException) { null }
        val punctuationAdapter = try { Class.forName("ai.voiceflow.voiceflow_mobile.PunctuationBenchmark") }
            catch (_: ClassNotFoundException) { null }
        if (engine == "punctuation") {
            qwenAdapter?.getMethod("release")?.invoke(null)
            WhisperEngine.release()
            LocalCleanupEngine.release()
            LocalFormattingBenchmark.release()
            val adapter = punctuationAdapter ?: throw DictationException("punctuation_unavailable",
                "Build the optional punctuation benchmark runtime first.")
            val data = try {
                adapter.getMethod("run", Context::class.java, JSONObject::class.java)
                    .invoke(null, context, configuration) as JSONObject
            } catch (error: java.lang.reflect.InvocationTargetException) {
                throw (error.targetException as? Exception ?: IllegalStateException("Punctuation benchmark failed.", error.targetException))
            }
            val visible = foregroundVisible.get()
            return data.put("foregroundAtEnd", visible)
                .put("foregroundInterrupted", !visible || backgroundEpoch.get() != startingBackgroundEpoch)
        }
        if (punctuationConfig == null) punctuationAdapter?.getMethod("release")?.invoke(null)
        else require(punctuationAdapter != null) { "Build the optional punctuation benchmark runtime first." }
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
            beamSize = parseBeamSize(configuration),
            benchmarkPromptOverride = if (configuration.has("recognitionPrompt") &&
                !configuration.isNull("recognitionPrompt")) configuration.getString("recognitionPrompt") else null,
        )
        val pauseOnly = parsePauseOnly(configuration)
        val config = LocalPipelineConfig(
            recognition, configuration.optInt("minimumChunkMs", 6_000),
            configuration.optInt("maximumChunkMs", if (pauseOnly) 600_000 else 15_000),
            configuration.optInt("silenceMs", 500), configuration.optInt("overlapMs", if (pauseOnly) 0 else 400),
            configuration.optBoolean("semanticCleanup", false), configuration.optInt("cleanupContextSentences", 3),
            configuration.optLong("cleanupBudgetMs", 10_000), configuration.optInt("cleanupThreads", 4),
            configuration.optInt("silenceThresholdRms", 64),
            pauseOnly,
            normalPriority,
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
        require(!pauseOnly || pipeline) { "Pause-only scheduling requires pipeline=true." }
        require(!normalPriority || pipeline) { "Normal worker priority requires pipeline=true." }
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
        var start = SystemClock.elapsedRealtime()
        var stopped = start
        var worstIngressLag = 0L
        var warmupMs = 0L
        val chunks = JSONArray()
        val cleanupRuns = JSONArray()
        var session: PipelinedLocalSession? = null
        try {
            if (cold || resetModelBeforeWarm) WhisperEngine.release()
            if (!cold) WhisperEngine.warmUp(context, recognition.model, recognition.useGpu)
            if (config.semanticCleanup) {
                if (cold) LocalCleanupEngine.release()
                else LocalCleanupEngine.prepare(context, config.cleanupThreads)
            } else LocalCleanupEngine.release()
            var formattingPreparation: JSONObject? = null
            var formattingPreparationError: Exception? = null
            var formattingPreparationMs = 0L
            if (punctuationConfig != null) {
                if (cold || resetModelBeforeWarm) punctuationAdapter!!.getMethod("release").invoke(null)
                if (!cold) {
                    val preparationStart = SystemClock.elapsedRealtime()
                    try {
                        formattingPreparation = invokePunctuation(punctuationAdapter!!, "prepareFormatting", context, punctuationConfig)
                    } catch (error: Exception) {
                        if (Thread.currentThread().isInterrupted) throw error
                        formattingPreparationError = error
                    } finally { formattingPreparationMs = SystemClock.elapsedRealtime() - preparationStart }
                }
            }
            // Warm asset preparation is outside replay latency but inside the
            // combined memory sample. Cold formatter loading occurs after ASR.
            start = SystemClock.elapsedRealtime()
            stopped = start
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
                    .put("inferenceMs", m.inferenceMs).put("text", m.text)
                    .put("javaPriority", m.javaPriority ?: JSONObject.NULL)
                    .put("linuxNice", m.linuxNice ?: JSONObject.NULL)
                    .put("inputSampleCount", m.inputSampleCount ?: JSONObject.NULL)) }
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
                val trimmed = WhisperEngine.trimSilence(samples)
                val javaPriority = Thread.currentThread().priority
                val linuxNice = runCatching { android.os.Process.getThreadPriority(android.os.Process.myTid()) }.getOrNull()
                val recognized = WhisperEngine.recognize(context, trimmed, recognition,
                    options.vocabulary.joinToString(", ").ifBlank { null })
                    ?: throw DictationException("local_failed", "Benchmark transcription failed.")
                warmupMs = recognized.loadMs
                chunks.put(JSONObject().put("startSample", 0).put("endSample", count)
                    .put("queuedMs", stopped - start).put("startedMs", stopped - start)
                    .put("finishedMs", SystemClock.elapsedRealtime() - start)
                    .put("loadMs", recognized.loadMs).put("inferenceMs", recognized.inferenceMs)
                    .put("text", recognized.text).put("javaPriority", javaPriority)
                    .put("linuxNice", linuxNice ?: JSONObject.NULL).put("inputSampleCount", trimmed.size))
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
            val recognitionEnd = SystemClock.elapsedRealtime()
            var formatting: JSONObject? = null
            var formattingMs = 0L
            if (punctuationConfig != null) {
                val formattingRaw = result.rawText ?: result.text
                val formattingStart = SystemClock.elapsedRealtime()
                formatting = try {
                    formattingPreparationError?.let { throw it }
                    if (readableCleanup) invokePunctuation(
                        Class.forName("ai.voiceflow.voiceflow_mobile.ReadableLocalBenchmark"),
                        "format", context, punctuationConfig, formattingRaw)
                    else invokePunctuation(punctuationAdapter!!, "formatText", context, punctuationConfig, formattingRaw)
                } catch (error: Exception) {
                    if (Thread.currentThread().isInterrupted) throw error
                    formattingFailure(formattingRaw, error)
                }
                formattingMs = SystemClock.elapsedRealtime() - formattingStart
                formatting.put("accepted", false).put("reviewRequired", true)
                    .put("configuration", punctuationConfig).put("coldRequested", cold)
                    .put("warmPreparation", formattingPreparation ?: JSONObject.NULL)
                    .put("warmPreparationMs", formattingPreparationMs).put("wallMs", formattingMs)
            }
            val end = SystemClock.elapsedRealtime()
            val finalMemory = Debug.MemoryInfo(); Debug.getMemoryInfo(finalMemory)
            peakPssKb.accumulateAndGet(finalMemory.totalPss, ::maxOf)
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
                .put("beamSize", recognition.beamSize)
                .put("samplingStrategy", if (recognition.beamSize == 1) "greedy" else "beam_search")
                .put("modelFile", ModelStore.fileNameFor(recognition.model))
                .put("modelBytes", ModelStore.modelFile(context, recognition.model).length())
                .put("audioSha256", audioHash).put("audioMs", count / 16.0)
                .put("cold", cold).put("resetModelBeforeWarm", resetModelBeforeWarm)
                .put("paced", paced).put("pipeline", pipeline)
                .put("boundaryPolicy", if (!pipeline) "whole_recording" else if (pauseOnly) "pause_only_native_windows" else "forced_with_overlap")
                .put("maximumChunkMs", config.maximumChunkMs).put("overlapMs", config.overlapMs)
                .put("benchmarkNormalPriority", normalPriority)
                .put("workerJavaPriority", if (pipeline) config.workerJavaPriority else JSONObject.NULL)
                .put("elapsedMs", end - start).put("postStopMs", end - stopped)
                .put("recognitionPostStopMs", recognitionEnd - stopped)
                .put("foregroundInterrupted", foregroundInterrupted).put("foregroundAtEnd", foregroundAtEnd)
                .put("warmupMs", warmupMs).put("recognitionMs", inference)
                .put("recognitionRealTimeFactor", inference / (count / 16.0))
                .put("cleanupMs", if (config.semanticCleanup) cleanupTime else JSONObject.NULL)
                .put("semanticCleanup", if (config.semanticCleanup) LocalCleanupModel.ID else "disabled")
                .put("cleanupRuntimeRevision", if (config.semanticCleanup) LocalCleanupEngine.RUNTIME_REVISION else JSONObject.NULL)
                .put("cleanupRuns", cleanupRuns)
                .put("punctuationFormatting", formatting ?: JSONObject.NULL)
                .put("readableCleanup", readableCleanup)
                .put("formattingMs", if (punctuationConfig != null) formattingMs else JSONObject.NULL)
                .put("accepted", false).put("reviewRequired", true)
                .put("processingRealTimeFactor", (inference + cleanupTime + formattingMs) / (count / 16.0))
                .put("maxReplayLagMs", worstIngressLag).put("sampledPeakPssKb", peakPssKb.get())
                .put("memorySamplingScope", "asset_preparation_replay_recognition_and_optional_formatting")
                .put("thermalStart", thermalStart).put("thermalEnd", thermal())
                .put("rawText", result.rawText).put("text", formatting?.getString("text") ?: result.text)
                .put("candidateText", formatting?.getString("candidateText") ?: JSONObject.NULL).put("chunks", chunks)
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
