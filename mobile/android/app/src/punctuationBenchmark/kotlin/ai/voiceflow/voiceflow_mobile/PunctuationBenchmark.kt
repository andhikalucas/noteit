package ai.voiceflow.voiceflow_mobile

import ai.onnxruntime.OnnxJavaType
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import android.content.Context
import android.os.Build
import android.os.Debug
import android.os.PowerManager
import android.os.SystemClock
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.LongBuffer
import java.security.MessageDigest

/** Optional CPU label and source-span formatting benchmark. No production entry point. */
object PunctuationBenchmark {
    private var session: OrtSession? = null
    private var identity: String? = null
    private var tokenizer: PunctuationTokenizer? = null
    private var tokenizerIdentity: String? = null
    private val environment by lazy { OrtEnvironment.getEnvironment().also { it.setTelemetry(false) } }

    @JvmStatic @Synchronized fun release() {
        releaseTokenizer()
        session?.close()
        session = null
        identity = null
    }

    private fun releaseTokenizer() {
        tokenizer?.close()
        tokenizer = null
        tokenizerIdentity = null
    }

    private fun checkRuntime(context: Context) {
        check(BuildConfig.DEBUG && BuildConfig.BENCHMARK_BUILD && BuildConfig.PUNCTUATION_BENCHMARK)
        check(context.packageName == "ai.voiceflow.voiceflow_mobile.benchmark")
    }

    private fun prepareRuntime(context: Context, request: PunctuationRequest): JSONObject {
        checkRuntime(context)
        val root = File(context.filesDir, "models").canonicalFile
        val model = File(root, request.modelFile).canonicalFile
        require(model.parentFile == root && model.isFile && model.length() == request.spec.bytes) {
            "Use the pinned punctuation model inside benchmark files/models."
        }
        val newIdentity = "${request.spec.kind}:${model.path}:${model.length()}:${model.lastModified()}"
        if (request.cold || identity != newIdentity) release()
        val loadedThisRun = session == null
        var verificationMs = 0.0
        var loadMs = 0.0
        if (loadedThisRun) {
            val verifyStart = SystemClock.elapsedRealtimeNanos()
            require(sha256(model) == request.spec.sha) { "Punctuation model checksum mismatch." }
            verificationMs = elapsed(verifyStart)
            val loadStart = SystemClock.elapsedRealtimeNanos()
            val created = OrtSession.SessionOptions().use { options ->
                options.setIntraOpNumThreads(4)
                options.setInterOpNumThreads(1)
                options.setExecutionMode(OrtSession.SessionOptions.ExecutionMode.SEQUENTIAL)
                environment.createSession(model.path, options)
            }
            try { validateContract(created, request.spec) } catch (error: Exception) {
                created.close()
                throw error
            }
            session = created
            identity = newIdentity
            loadMs = elapsed(loadStart)
        }
        var tokenizerLoadMs = 0.0
        var tokenizerVerificationMs = 0.0
        var tokenizerLoaded = false
        if (request.tokenizeOnDevice) {
            val asset = File(root, request.spec.tokenizerFile).canonicalFile
            require(asset.parentFile == root && asset.isFile && asset.length() == request.spec.tokenizerBytes) {
                "Use the pinned tokenizer for this model inside benchmark files/models."
            }
            val key = "${request.spec.kind}:${asset.path}:${asset.length()}:${asset.lastModified()}"
            if (tokenizerIdentity != key) releaseTokenizer()
            if (tokenizer == null) {
                val verify = SystemClock.elapsedRealtimeNanos()
                require(sha256(asset) == request.spec.tokenizerSha) { "Tokenizer does not match the selected model." }
                tokenizerVerificationMs = elapsed(verify)
                val load = SystemClock.elapsedRealtimeNanos()
                val created = PunctuationTokenizer(asset)
                try {
                    require(created.bosId.toLong() == request.spec.bos && created.eosId == 2 &&
                        created.vocabularySize == request.spec.vocabulary) { "Tokenizer vocabulary/special IDs do not match the model." }
                } catch (error: Exception) { created.close(); throw error }
                tokenizer = created
                tokenizerIdentity = key
                tokenizerLoaded = true
                tokenizerLoadMs = elapsed(load)
            }
        } else releaseTokenizer()
        return JSONObject().put("loadedThisRun", loadedThisRun).put("verificationMs", verificationMs)
            .put("loadMs", loadMs).put("tokenizerLoadedThisRun", tokenizerLoaded)
            .put("tokenizerVerificationMs", tokenizerVerificationMs).put("tokenizerLoadMs", tokenizerLoadMs)
            .put("tokenizerLoadIncludesInternalVerification", request.tokenizeOnDevice)
    }

    /** Load and verify both assets before a timed ASR/formatting case. */
    @JvmStatic @Synchronized fun prepareFormatting(context: Context, config: JSONObject): JSONObject {
        val request = PunctuationRequest.parse(config, parseCases = false)
        require(request.tokenizeOnDevice) { "Formatting requires tokenizeOnDevice=true." }
        val start = SystemClock.elapsedRealtimeNanos()
        return prepareRuntime(context, request).put("preparationMs", elapsed(start))
            .put("assets", assetMetadata(request)).put("tokenizerRuntimeVersion", PunctuationTokenizer.RUNTIME_VERSION)
            .put("tokenizerRuntimeRevision", PunctuationTokenizer.RUNTIME_REVISION).put("runtimeVersion", environment.version)
            .put("tokenizerArchiveSha256", PunctuationTokenizer.RUNTIME_ARCHIVE_SHA256)
    }

    /** Reuses prepared assets; missing/replaced assets load with separate timing. */
    @JvmStatic @Synchronized fun formatText(context: Context, source: String, config: JSONObject): JSONObject {
        val request = PunctuationRequest.parse(config, parseCases = false).copy(cold = false)
        require(request.tokenizeOnDevice && source.isNotBlank() && source.length <= 12000)
        val totalStart = SystemClock.elapsedRealtimeNanos()
        val preparation = prepareRuntime(context, request)
        return formatCase(PunctuationCase("format-text", source, emptyList()), request.spec, intArrayOf(48))
            .put("preparation", preparation).put("assets", assetMetadata(request))
            .put("tokenizerRuntimeVersion", PunctuationTokenizer.RUNTIME_VERSION)
            .put("tokenizerRuntimeRevision", PunctuationTokenizer.RUNTIME_REVISION).put("runtimeVersion", environment.version)
            .put("tokenizerArchiveSha256", PunctuationTokenizer.RUNTIME_ARCHIVE_SHA256)
            .put("totalWithPreparationMs", elapsed(totalStart))
    }

    @JvmStatic @Synchronized fun run(context: Context, config: JSONObject): JSONObject {
        checkRuntime(context)
        val request = PunctuationRequest.parse(config)
        val runStart = SystemClock.elapsedRealtimeNanos()
        val before = pss()
        val thermalBefore = thermal(context)
        val preparation = prepareRuntime(context, request)
        val afterLoad = pss()
        val rows = JSONArray()
        val remainingWindows = intArrayOf(48)
        var sampledMaxPss = maxOf(before, afterLoad)
        for (case in request.cases) {
            check(!Thread.currentThread().isInterrupted) { "Punctuation benchmark interrupted." }
            if (request.tokenizeOnDevice) {
                val caseThermal = thermal(context)
                val formatted = if (request.readableText) ReadableLocalBenchmark.format(context, case.rawText, config)
                    .put("id", case.id) else formatCase(case, request.spec, remainingWindows)
                rows.put(formatted
                    .put("thermalStatusStart", caseThermal).put("thermalStatusEnd", thermal(context))
                    .put("pssKbAfter", pss()))
                sampledMaxPss = maxOf(sampledMaxPss, pss())
                continue
            }
            val caseStart = SystemClock.elapsedRealtimeNanos()
            var inferenceMs = 0.0
            val predictions = JSONArray()
            val caseThermal = thermal(context)
            for (ids in case.windows) {
                OnnxTensor.createTensor(environment, LongBuffer.wrap(ids), longArrayOf(1, ids.size.toLong())).use { input ->
                    val start = SystemClock.elapsedRealtimeNanos()
                    session!!.run(mapOf("input_ids" to input)).use { output ->
                        val windowMs = elapsed(start)
                        inferenceMs += windowMs
                        predictions.put(prediction(output, ids, request.spec).put("inferenceMs", windowMs))
                    }
                }
                sampledMaxPss = maxOf(sampledMaxPss, pss())
            }
            rows.put(JSONObject().put("id", case.id).put("rawText", case.rawText)
                .put("predictions", predictions).put("inferenceMs", inferenceMs)
                .put("processingMs", elapsed(caseStart)).put("thermalStatusStart", caseThermal)
                .put("thermalStatusEnd", thermal(context)).put("pssKbAfter", pss()))
        }
        val result = JSONObject().put("schemaVersion", 1).put("runtime", "onnxruntime-android")
            .put("runtimeVersion", environment.version).put("provider", "CPUExecutionProvider")
            .put("threads", 4).put("interOpThreads", 1).put("batchSize", 1)
            .put("model", request.spec.kind).put("modelSha256", request.spec.sha)
            .put("modelBytes", request.spec.bytes).put("coldRequested", request.cold)
            .put("totalMs", elapsed(runStart))
            .put("pssKbBefore", before).put("pssKbAfterLoad", afterLoad)
            .put("sampledMaxPssKb", sampledMaxPss).put("pssKbAfter", pss())
            .put("nativeHeapBytesAfter", Debug.getNativeHeapAllocatedSize())
            .put("thermalStatusStart", thermalBefore).put("thermalStatusEnd", thermal(context))
            .put("device", "${Build.MANUFACTURER} ${Build.MODEL}").put("androidSdk", Build.VERSION.SDK_INT)
            .put("timingScope", if (request.readableText) "explicit_repairs_fillers_punctuation_sentence_case_excludes_asr_initial_asset_loading"
                else if (request.tokenizeOnDevice) "tokenization_labels_source_span_rendering_excludes_asr_and_asset_loading"
                else "labels_only_excludes_tokenization_rendering_and_asr")
            .put("tokenizeOnDevice", request.tokenizeOnDevice)
            .put("readableText", request.readableText)
            .put("postStopMs", JSONObject.NULL).put("audioMs", JSONObject.NULL).put("paced", false)
            .put("cases", rows)
        preparation.keys().forEach { key -> result.put(key, preparation.get(key)) }
        if (request.tokenizeOnDevice) result.put("assets", assetMetadata(request))
            .put("tokenizerRuntimeVersion", PunctuationTokenizer.RUNTIME_VERSION)
            .put("tokenizerRuntimeRevision", PunctuationTokenizer.RUNTIME_REVISION)
            .put("tokenizerArchiveSha256", PunctuationTokenizer.RUNTIME_ARCHIVE_SHA256)
        return result
    }

    private fun assetMetadata(request: PunctuationRequest) = JSONObject()
        .put("modelKind", request.spec.kind).put("modelFile", request.modelFile)
        .put("modelSha256", request.spec.sha).put("modelBytes", request.spec.bytes)
        .put("tokenizerFile", request.spec.tokenizerFile).put("tokenizerSha256", request.spec.tokenizerSha)
        .put("tokenizerBytes", request.spec.tokenizerBytes).put("configurationSha256", request.spec.configurationSha)
        .put("postLabels", JSONArray(request.spec.postLabels))

    private fun formatCase(case: PunctuationCase, spec: PunctuationSpec, remainingWindows: IntArray): JSONObject {
        check(!Thread.currentThread().isInterrupted) { "Punctuation benchmark interrupted." }
        val start = SystemClock.elapsedRealtimeNanos()
        var tokenizationMs = 0.0
        var inferenceMs = 0.0
        var renderingMs = 0.0
        var encoding: PunctuationEncoding? = null
        val predictions = JSONArray()
        val row = JSONObject().put("id", case.id).put("rawText", case.rawText)
            .put("parityExpected", case.windows.isNotEmpty()).put("parityMatched", JSONObject.NULL)
            .put("reviewRequired", true).put("accepted", false)
        try {
            val tokenizeStart = SystemClock.elapsedRealtimeNanos()
            try { encoding = tokenizer!!.encode(case.rawText) } finally { tokenizationMs = elapsed(tokenizeStart) }
            val encoded = encoding!!
            val windows = PunctuationWindowing.windows(encoded, spec)
            require(windows.size <= remainingWindows[0]) { "Aggregate formatting window budget exceeded." }
            remainingWindows[0] -= windows.size
            if (case.windows.isNotEmpty()) {
                val matched = windows.size == case.windows.size && windows.indices.all { windows[it].contentEquals(case.windows[it]) }
                row.put("parityMatched", matched)
                require(matched) { "On-device tokenizer IDs differ from supplied parity windows." }
            }
            for (ids in windows) {
                check(!Thread.currentThread().isInterrupted) { "Punctuation benchmark interrupted." }
                OnnxTensor.createTensor(environment, LongBuffer.wrap(ids), longArrayOf(1, ids.size.toLong())).use { input ->
                    val labelStart = SystemClock.elapsedRealtimeNanos()
                    var measured = false
                    try {
                        session!!.run(mapOf("input_ids" to input)).use { output ->
                            val windowMs = elapsed(labelStart)
                            inferenceMs += windowMs
                            measured = true
                            predictions.put(prediction(output, ids, spec).put("inferenceMs", windowMs))
                        }
                    } finally {
                        if (!measured) inferenceMs += elapsed(labelStart)
                    }
                }
            }
            val renderStart = SystemClock.elapsedRealtimeNanos()
            val rendered = try {
                val merged = PunctuationWindowing.merge(encoded, predictions, spec)
                PunctuationSpanRenderer.render(case.rawText, encoded.pieces, merged.first, merged.second, spec.postLabels)
            } finally { renderingMs = elapsed(renderStart) }
            row.put("candidateText", rendered.candidateText)
                .put("text", if (rendered.guardFlags.isEmpty()) rendered.candidateText else case.rawText)
                .put("outcome", if (rendered.guardFlags.isEmpty()) rendered.outcome else "guard_raw_fallback")
                .put("guardFlags", JSONArray(rendered.guardFlags))
                .put("skippedActions", JSONArray().also { skipped -> rendered.skippedActions.forEach { action ->
                    skipped.put(JSONObject().put("tokenIndex", action.tokenIndex).put("action", action.action)
                        .put("reason", action.reason).put("pieceIndex", action.pieceIndex ?: JSONObject.NULL)
                        .put("label", action.label ?: JSONObject.NULL))
                } })
        } catch (error: Exception) {
            if (Thread.currentThread().isInterrupted) throw error
            row.put("candidateText", case.rawText).put("text", case.rawText).put("outcome", "error_raw_fallback")
                .put("errorType", error.javaClass.simpleName).put("error", error.message ?: "Formatting failed.")
                .put("guardFlags", JSONArray().put("formatting_exception"))
                .put("skippedActions", JSONArray())
        }
        val processingMs = elapsed(start)
        row.put("fullFormattingMs", processingMs).put("processingMs", processingMs).put("tokenizationMs", tokenizationMs)
            .put("inferenceMs", inferenceMs).put("renderingMs", renderingMs).put("predictions", predictions)
            .put("windowCount", predictions.length()).put("overlapTokens", 16).put("halfOverlapTokens", 8)
            .put("offsetUnit", "utf16")
        encoding?.let { encoded ->
            row.put("loweredText", encoded.lowered).put("tokenCount", encoded.pieces.size)
                .put("unkId", encoded.unkId).put("bosId", encoded.bosId).put("eosId", encoded.eosId)
                .put("padId", encoded.padId).put("vocabularySize", encoded.vocabularySize)
                .put("tokens", JSONArray().also { tokens -> encoded.pieces.forEach { token ->
                    tokens.put(JSONObject().put("id", token.id).put("piece", token.piece).put("surface", token.surface)
                        .put("beginUtf16", token.beginUtf16).put("endUtf16", token.endUtf16).put("unknown", token.unknown))
                } })
        }
        return row
    }

    private fun validateContract(runtime: OrtSession, spec: PunctuationSpec) {
        require(runtime.inputNames == setOf("input_ids"))
        require(runtime.outputNames == setOf("pre_preds", "post_preds", "cap_preds", "seg_preds"))
        val input = runtime.inputInfo.getValue("input_ids").info as? TensorInfo
            ?: error("Input must be a tensor.")
        require(input.type == OnnxJavaType.INT64 && input.shape.size == 2)
        for ((name, type) in listOf("pre_preds" to OnnxJavaType.INT64,
            "post_preds" to OnnxJavaType.INT64, "cap_preds" to OnnxJavaType.BOOL,
            "seg_preds" to OnnxJavaType.BOOL)) {
            val info = runtime.outputInfo.getValue(name).info as? TensorInfo
                ?: error("Output must be a tensor.")
            require(info.type == type && info.shape.size == if (name == "cap_preds") 3 else 2)
            if (name == "cap_preds") require(info.shape[2] == spec.capWidth.toLong())
        }
    }

    private fun prediction(output: OrtSession.Result, ids: LongArray, spec: PunctuationSpec): JSONObject {
        fun tensor(name: String, type: OnnxJavaType, shape: LongArray): OnnxTensor {
            val tensor = output.get(name).orElseThrow { IllegalArgumentException("Missing output.") } as? OnnxTensor
                ?: error("Output must be a tensor.")
            require(tensor.info.type == type && tensor.info.shape.contentEquals(shape)) { "Unexpected output rank/type." }
            return tensor
        }
        val shape = longArrayOf(1, ids.size.toLong())
        val pre = tensor("pre_preds", OnnxJavaType.INT64, shape).longBuffer
        val post = tensor("post_preds", OnnxJavaType.INT64, shape).longBuffer
        val cap = tensor("cap_preds", OnnxJavaType.BOOL, longArrayOf(1, ids.size.toLong(), spec.capWidth.toLong())).byteBuffer
        val seg = tensor("seg_preds", OnnxJavaType.BOOL, shape).byteBuffer
        val keptIds = JSONArray()
        val pres = JSONArray()
        val posts = JSONArray()
        val capitals = JSONArray()
        val sentences = JSONArray()
        for (i in ids.indices) {
            val preId = pre.get(i)
            val postId = post.get(i)
            require(preId in 0L..1L && postId in 0 until spec.postCount.toLong())
            val segment = seg.get(i).toInt()
            require(segment in 0..1)
            val caps = JSONArray()
            for (j in 0 until spec.capWidth) {
                val value = cap.get(i * spec.capWidth + j).toInt()
                require(value in 0..1)
                caps.put(value == 1)
            }
            if (i > 0 && i < ids.lastIndex) {
                keptIds.put(ids[i]); pres.put(preId); posts.put(postId)
                capitals.put(caps); sentences.put(segment == 1)
            }
        }
        return JSONObject().put("ids", keptIds).put("pre", pres).put("post", posts)
            .put("capitals", capitals).put("sentences", sentences)
    }

    private fun pss() = Debug.MemoryInfo().also { Debug.getMemoryInfo(it) }.totalPss
    private fun thermal(context: Context) = if (Build.VERSION.SDK_INT >= 29)
        (context.getSystemService(Context.POWER_SERVICE) as PowerManager).currentThermalStatus else -1
    private fun elapsed(start: Long) = (SystemClock.elapsedRealtimeNanos() - start) / 1e6
    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(65536)
            while (true) { val n = input.read(buffer); if (n < 0) break; digest.update(buffer, 0, n) }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}

internal data class PunctuationSpec(val kind: String, val bytes: Long, val sha: String,
    val maximum: Int, val vocabulary: Int, val bos: Long, val capWidth: Int, val postCount: Int) {
    val tokenizerFile: String get() = if (kind == "small") "spe_unigram_64k_lowercase_47lang.model" else "sp.model"
    val tokenizerBytes: Long get() = if (kind == "small") 1_186_519L else 5_069_059L
    val tokenizerSha: String get() = if (kind == "small")
        "1bc15b6e5fd80dfac9999582ce3efcad2ac1f7cf4e0e9769b329f5de9ca5af47" else
        "7f944d0be93b275f62e1913fd409f378ddbba108e57fe4a9cb47e8c047f6bef1"
    val configurationSha: String get() = if (kind == "small")
        "30eb8e05fcea3865828ab73f41fbba21dd7faf127a61950a706af9156f5b84f2" else
        "fb27ec0ddc253655cd89aee3de9976a4c6b5d1b3a6a6e8fdcb511f7a522dde00"
    // Exact ordered maps from the pinned configurations, not inferred from the
    // output dimension (XLM-R inserts <ACRONYM> at index one).
    val postLabels: List<String> get() = listOf("<NULL>") +
        (if (kind == "xlmr") listOf("<ACRONYM>") else emptyList()) +
        listOf(".", ",", "?", "？", "，", "。", "、", "・", "।", "؟", "،", ";", "።", "፣", "፧")
}
internal data class PunctuationCase(val id: String, val rawText: String, val windows: List<LongArray>)
internal data class PunctuationRequest(val spec: PunctuationSpec, val modelFile: String,
    val cases: List<PunctuationCase>, val cold: Boolean, val tokenizeOnDevice: Boolean = false,
    val readableText: Boolean = false) {
    companion object {
        fun parse(config: JSONObject, parseCases: Boolean = true): PunctuationRequest {
            require(config.get("modelKind") is String && config.get("modelFile") is String)
            val kind = config.getString("modelKind")
            val spec = when (kind) {
                "small" -> PunctuationSpec(kind, 232986305L,
                    "640d91c06b7cc5b3e065c12a7097188378aad3bc11568ff1d72c4c0a2acb0df4", 128, 64000, 1, 16, 16)
                "xlmr" -> PunctuationSpec(kind, 1112481438L,
                    "c43ca686dabc237c3b06be834b9423c07580fef7e2b1a6c09976f7d60caa5d89", 256, 250002, 0, 16, 17)
                else -> error("Unsupported punctuation model.")
            }
            val filename = config.getString("modelFile")
            require(filename.matches(Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,95}\\.onnx")))
            if (config.has("cold")) require(config.get("cold") is Boolean)
            if (config.has("tokenizeOnDevice")) require(config.get("tokenizeOnDevice") is Boolean) {
                "tokenizeOnDevice must be a boolean."
            }
            val tokenizeOnDevice = config.optBoolean("tokenizeOnDevice", false)
            if (config.has("readableText")) require(config.get("readableText") is Boolean)
            val readableText = config.optBoolean("readableText", false)
            require(!readableText || tokenizeOnDevice) { "Readable text requires on-device tokenization." }
            if (config.has("tokenizerFile")) require(config.get("tokenizerFile") is String &&
                config.getString("tokenizerFile") == spec.tokenizerFile) { "Use the pinned tokenizer basename for this model." }
            if (!parseCases) return PunctuationRequest(spec, filename, emptyList(), config.optBoolean("cold", false), tokenizeOnDevice, readableText)
            val rows = config.getJSONArray("cases")
            require(rows.length() in 1..24)
            var windowCount = 0
            val names = mutableSetOf<String>()
            val cases = (0 until rows.length()).map { index ->
                val row = rows.getJSONObject(index)
                require(row.get("id") is String && row.get("rawText") is String)
                val id = row.getString("id")
                val raw = row.getString("rawText")
                require(id.matches(Regex("[A-Za-z0-9][A-Za-z0-9_.-]{0,127}")) && names.add(id))
                require(raw.isNotBlank() && raw.length <= 12000)
                require(!readableText || raw.length <= 1000 && !row.has("windows")) {
                    "Readable text screens use at most 1000 source characters per case and no frozen token windows."
                }
                val windows = if (tokenizeOnDevice && !row.has("windows")) JSONArray() else row.getJSONArray("windows")
                require(windows.length() in (if (tokenizeOnDevice && !row.has("windows")) 0 else 1)..48)
                windowCount += windows.length()
                require(windowCount <= 48)
                PunctuationCase(id, raw, (0 until windows.length()).map { window ->
                    val array = windows.getJSONArray(window)
                    require(array.length() in 3..spec.maximum)
                    LongArray(array.length()) { token ->
                        val value = array.get(token)
                        require(value is Int || value is Long) { "Token IDs must be integers." }
                        (value as Number).toLong().also { require(it in 0 until spec.vocabulary.toLong()) }
                    }.also { require(it.first() == spec.bos && it.last() == 2L) { "Expected pinned BOS/EOS." } }
                })
            }
            return PunctuationRequest(spec, filename, cases, config.optBoolean("cold", false), tokenizeOnDevice, readableText)
        }
    }
}

/** The frozen host policy: capacity maxLength-2, overlap16, keep half from each side. */
internal object PunctuationWindowing {
    fun windows(encoding: PunctuationEncoding, spec: PunctuationSpec): List<LongArray> {
        require(encoding.pieces.size in 1..5000 && spec.maximum > 18)
        require(encoding.vocabularySize == spec.vocabulary && encoding.bosId.toLong() == spec.bos && encoding.eosId == 2)
        require(encoding.pieces.all { it.id in 0 until spec.vocabulary })
        val capacity = spec.maximum - 2
        val result = mutableListOf<LongArray>()
        var start = 0
        while (start < encoding.pieces.size) {
            val end = minOf(start + capacity, encoding.pieces.size)
            result += LongArray(end - start + 2) { index ->
                when (index) { 0 -> encoding.bosId.toLong(); end - start + 1 -> encoding.eosId.toLong()
                    else -> encoding.pieces[start + index - 1].id.toLong() }
            }
            if (end == encoding.pieces.size) break
            start = end - 16
        }
        require(result.size <= 48) { "Formatting window count exceeds the benchmark limit." }
        return result
    }

    fun merge(encoding: PunctuationEncoding, predictions: JSONArray, spec: PunctuationSpec): Pair<IntArray, Array<IntArray>> {
        val windows = windows(encoding, spec)
        require(predictions.length() == windows.size) { "Prediction count differs from tokenizer windows." }
        val ids = mutableListOf<Int>()
        val posts = mutableListOf<Int>()
        val capitals = mutableListOf<IntArray>()
        for (window in windows.indices) {
            val predicted = predictions.getJSONObject(window)
            val expectedIds = windows[window].drop(1).dropLast(1)
            val tokenIds = predicted.getJSONArray("ids")
            val pre = predicted.getJSONArray("pre")
            val post = predicted.getJSONArray("post")
            val caps = predicted.getJSONArray("capitals")
            val sentences = predicted.getJSONArray("sentences")
            require(listOf(tokenIds, pre, post, caps, sentences).all { it.length() == expectedIds.size })
            val keepStart = if (window == 0) 0 else 8
            val keepEnd = expectedIds.size - if (window == windows.lastIndex) 0 else 8
            require(keepEnd > keepStart)
            for (index in expectedIds.indices) {
                fun integer(array: JSONArray): Long {
                    val value = array.get(index)
                    require(value is Int || value is Long) { "Predicted IDs must be integers." }
                    return (value as Number).toLong()
                }
                val id = integer(tokenIds)
                val postId = integer(post)
                require(id == expectedIds[index] && integer(pre) in 0L..1L && postId in 0L until spec.postCount.toLong())
                require(sentences.get(index) is Boolean)
                val values = caps.getJSONArray(index)
                require(values.length() == spec.capWidth)
                val capital = IntArray(values.length()) { offset ->
                    val value = values.get(offset)
                    require(value is Boolean) { "Capitalization output must be boolean." }
                    if (value as Boolean) 1 else 0
                }
                if (index in keepStart until keepEnd) {
                    ids += id.toInt(); posts += postId.toInt(); capitals += capital
                }
            }
        }
        require(ids == encoding.pieces.map { it.id }) { "Merged IDs do not match original tokenizer output." }
        return posts.toIntArray() to capitals.toTypedArray()
    }
}
