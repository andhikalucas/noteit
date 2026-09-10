package ai.voiceflow.voiceflow_mobile

import android.content.Context
import android.os.SystemClock
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import java.util.concurrent.CancellationException

/** One bounded candidate: explicit repairs, hesitations, pinned punctuation, sentence case. */
object ReadableLocalBenchmark {
    private fun checkRuntime(context: Context) {
        check(BuildConfig.DEBUG && BuildConfig.BENCHMARK_BUILD && BuildConfig.PUNCTUATION_BENCHMARK &&
            context.packageName == "ai.voiceflow.voiceflow_mobile.benchmark")
    }

    @JvmStatic fun prepare(context: Context, config: JSONObject): JSONObject {
        checkRuntime(context)
        return PunctuationBenchmark.prepareFormatting(context, config)
    }

    @JvmStatic fun format(context: Context, raw: String, config: JSONObject): JSONObject {
        checkRuntime(context)
        return compose(raw, { input -> PunctuationBenchmark.formatText(context, input, config) },
            nowNanos = { SystemClock.elapsedRealtimeNanos() })
    }

    /** Pure orchestration seam; native model inference remains an injected stage. */
    internal fun compose(raw: String, punctuationStage: (String) -> JSONObject,
        nowNanos: () -> Long = System::nanoTime,
        cancelled: () -> Boolean = { Thread.currentThread().isInterrupted }): JSONObject {
        val start = nowNanos()
        fun checkCancelled() { if (cancelled()) throw CancellationException("Readable formatting cancelled.") }
        val result = JSONObject().put("rawText", raw).put("accepted", false).put("reviewRequired", true)
            .put("policy", "explicit-repairs-hesitations-sentence-case-v1")
            .put("paragraphs", "preserve_existing_only")
            .put("editOffsetScope", "utf16_relative_to_each_stage_input")
        try {
            checkCancelled()
            require(raw.isNotBlank() && raw.length <= 12000) { "Expected nonempty bounded source text." }
            val correction = LocalCorrectionResolver.apply(raw)
            checkCancelled()
            val fillers = LocalFillerFilter.apply(correction.text)
            checkCancelled()
            result.put("correctionInput", raw).put("correctionText", correction.text)
                .put("correctionEdits", JSONArray().also { list -> correction.edits.forEach { e ->
                    list.put(edit(e.beginUtf16, e.endUtf16, e.original, e.replacement, e.reason))
                } })
                .put("fillerInput", correction.text).put("fillerText", fillers.text)
                .put("fillerEdits", JSONArray().also { list -> fillers.edits.forEach { e ->
                    list.put(edit(e.beginUtf16, e.endUtf16, e.original, e.replacement, e.reason))
                } })
            val punctuation = punctuationStage(fillers.text)
            checkCancelled()
            result.put("punctuation", punctuation)
            require(punctuation.opt("outcome") in setOf("unchanged", "changed_review_required") &&
                punctuation.opt("guardFlags") is JSONArray && punctuation.getJSONArray("guardFlags").length() == 0 &&
                punctuation.opt("rawText") == fillers.text && punctuation.opt("text") is String &&
                punctuation.opt("candidateText") == punctuation.opt("text")) {
                "Punctuation did not produce a valid complete candidate."
            }
            val sentenceInput = punctuation.getString("text")
            require(preservesSource(fillers.text, sentenceInput)) { "Punctuation changed source content." }
            require((punctuation.getString("outcome") == "unchanged") == (sentenceInput == fillers.text)) {
                "Punctuation outcome did not match its text."
            }
            val sentence = SentenceCasePolisher.apply(sentenceInput)
            checkCancelled()
            result.put("sentenceInput", sentenceInput)
                .put("sentenceEdits", JSONArray().also { list -> sentence.edits.forEach { e ->
                    list.put(edit(e.beginUtf16, e.endUtf16, e.original, e.replacement, e.reason))
                } })
                .put("text", sentence.text).put("candidateText", sentence.text)
                .put("outcome", if (sentence.text == raw) "unchanged" else "changed_review_required")
                .put("guardFlags", JSONArray())
        } catch (error: Exception) {
            if (error is InterruptedException) { Thread.currentThread().interrupt(); throw error }
            if (error is CancellationException || cancelled()) throw error
            result.put("text", raw).put("candidateText", raw).put("outcome", "error_raw_fallback")
                .put("errorType", error.javaClass.simpleName).put("error", error.message ?: "Formatting failed.")
                .put("guardFlags", JSONArray().put("readable_pipeline_failure"))
        }
        return result.put("totalWithPreparationMs", (nowNanos() - start) / 1_000_000.0)
    }

    /** Independent no-rewrite audit, including all original gaps/paragraphs.
     * The renderer's own guards additionally decide literal and label safety.
     */
    private fun preservesSource(raw: String, candidate: String): Boolean {
        if (candidate.length > raw.length * 2 + 1) return false
        var source = 0
        var output = 0
        val insertions = mutableSetOf<Int>()
        while (output < candidate.length) {
            if (source < raw.length && raw.codePointAt(source) == candidate.codePointAt(output)) {
                val width = Character.charCount(raw.codePointAt(source)); source += width; output += width
                continue
            }
            val cp = candidate.codePointAt(output)
            if (cp in listOf('.'.code, ','.code, '?'.code)) {
                if (source == 0 || !Character.isLetterOrDigit(raw.codePointBefore(source)) ||
                    source < raw.length && !space(raw.codePointAt(source)) || !insertions.add(source)) return false
                output++
                continue
            }
            if (source >= raw.length) return false
            val original = String(Character.toChars(raw.codePointAt(source)))
            val replacement = original.uppercase(Locale.ROOT)
            if (replacement.length != original.length || replacement.codePointCount(0, replacement.length) != 1 ||
                replacement.codePointAt(0) != cp || !Character.isLetter(raw.codePointAt(source))) return false
            var begin = source
            while (begin > 0 && !space(raw.codePointBefore(begin))) begin -= Character.charCount(raw.codePointBefore(begin))
            var end = source
            while (end < raw.length && !space(raw.codePointAt(end))) end += Character.charCount(raw.codePointAt(end))
            val atom = raw.substring(begin, end)
            if (raw.substring(begin, source).codePoints().anyMatch { Character.isLetter(it) } ||
                atom.codePoints().anyMatch { Character.isUpperCase(it) || Character.isDigit(it) } ||
                atom.any { it in "_/@\\=<>|&+#{}[]()" }) return false
            source += original.length; output += replacement.length
        }
        return source == raw.length
    }

    private fun space(cp: Int) = Character.isWhitespace(cp) || Character.isSpaceChar(cp) || cp == 0x85

    private fun edit(begin: Int, end: Int, original: String, replacement: String, reason: String) =
        JSONObject().put("beginUtf16", begin).put("endUtf16", end).put("original", original)
            .put("replacement", replacement).put("reason", reason)
}
