package ai.voiceflow.voiceflow_mobile

import android.content.Context
import org.json.JSONObject

/** Explicit development build only; never a settings or release default. */
object LocalCandidatePolicy {
    val enabled: Boolean get() = BuildConfig.LOCAL_READABLE_MODEL.isNotEmpty()
    val model: String get() = if (enabled) BuildConfig.LOCAL_READABLE_MODEL else "small"

    internal fun allowed(debug: Boolean, benchmark: Boolean, punctuation: Boolean,
        packageName: String, model: String): Boolean = debug && benchmark && punctuation &&
        packageName == "ai.voiceflow.voiceflow_mobile.benchmark" && model in setOf("small-q8", "medium-q8")

    fun requireRuntime(context: Context) {
        check(!enabled || allowed(BuildConfig.DEBUG, BuildConfig.BENCHMARK_BUILD,
            BuildConfig.PUNCTUATION_BENCHMARK, context.packageName, model)) {
            "Readable local transcription is only available in the isolated debug candidate."
        }
    }

    fun configuration(): LocalPipelineConfig = if (!enabled) LocalPipelineConfig() else LocalPipelineConfig(
        recognition = LocalRecognitionConfig(model = model),
        maximumChunkMs = LocalPipelineConfig.BENCHMARK_MAXIMUM_MS,
        overlapMs = 0, benchmarkPauseOnly = true, readableCandidate = true,
    )

    private fun config() = JSONObject().put("modelKind", "xlmr")
        .put("modelFile", "punctuation-xlmr.onnx").put("tokenizeOnDevice", true)

    private fun invoke(context: Context, method: String, raw: String? = null): JSONObject {
        check(enabled) { "Select the isolated readable candidate explicitly." }
        requireRuntime(context)
        val adapter = Class.forName("ai.voiceflow.voiceflow_mobile.ReadableLocalBenchmark")
        return try {
            if (raw == null) adapter.getMethod(method, Context::class.java, JSONObject::class.java)
                .invoke(null, context, config()) as JSONObject
            else adapter.getMethod(method, Context::class.java, String::class.java, JSONObject::class.java)
                .invoke(null, context, raw, config()) as JSONObject
        } catch (error: java.lang.reflect.InvocationTargetException) {
            throw (error.targetException as? Exception ?: IllegalStateException("Local formatting failed.", error.targetException))
        }
    }

    /** Called on a worker, never while holding the UI/coordinator monitor. */
    fun prepare(context: Context) { invoke(context, "prepare") }

    fun finish(context: Context, result: TranscriptionResult, cancelled: () -> Boolean): TranscriptionResult {
        if (cancelled()) throw DictationException("cancelled", "Recording cancelled.")
        val raw = result.rawText ?: result.text
        val formatted = invoke(context, "format", raw)
        if (cancelled()) throw DictationException("cancelled", "Recording cancelled.")
        if (formatted.optString("outcome") == "error_raw_fallback") {
            throw DictationException("local_formatting", "Local formatting failed. Retry the saved recording.")
        }
        return TranscriptionResult(formatted.getString("text"), raw)
    }
}
