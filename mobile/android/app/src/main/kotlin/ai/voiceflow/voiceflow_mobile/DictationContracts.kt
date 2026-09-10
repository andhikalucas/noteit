package ai.voiceflow.voiceflow_mobile

import java.io.File

data class AutocorrectRule(val from: String, val to: String)

data class DictationOptions(
    val engine: String = "gemini",
    val vocabulary: List<String> = emptyList(),
    val autocorrects: List<AutocorrectRule> = emptyList(),
)

data class TranscriptionResult(val text: String, val rawText: String? = null)

class DictationException(val code: String, override val message: String) : Exception(message)

interface Transcriber {
    fun transcribe(audio: File, options: DictationOptions, cancelled: () -> Boolean): TranscriptionResult
}

interface LocalAudioSession {
    fun accept(samples: ShortArray)
    fun finish(audio: File): TranscriptionResult
    fun cancel()
}

/** One pass over the original text: replacements never cascade into other rules. */
object PersonalAutocorrect {
    fun apply(text: String, rules: List<AutocorrectRule>): String {
        val valid = rules.filter { it.from.isNotBlank() && it.to.isNotBlank() }
            .distinctBy { it.from.lowercase(java.util.Locale.ROOT) }
            .sortedByDescending { it.from.length }
        if (valid.isEmpty()) return text
        val lookup = valid.associate { it.from.lowercase(java.util.Locale.ROOT) to it.to }
        val expression = valid.joinToString("|") { Regex.escape(it.from) }
        val pattern = Regex("(?<![\\p{L}\\p{N}_])(?:$expression)(?![\\p{L}\\p{N}_])", RegexOption.IGNORE_CASE)
        return pattern.replace(text) { lookup[it.value.lowercase(java.util.Locale.ROOT)] ?: it.value }
    }
}
