package ai.voiceflow.voiceflow_mobile

import java.util.Locale

/** Conservative sentence closure; never rewrites words or adds paragraphs. */
object SentenceCasePolisher {
    data class Edit(val beginUtf16: Int, val endUtf16: Int, val original: String,
        val replacement: String, val reason: String)
    data class Result(val text: String, val edits: List<Edit>)
    private data class Span(val begin: Int, val end: Int)
    private val word = Regex("[\\p{L}\\p{M}\\p{N}_]+(?:['’][\\p{L}\\p{M}]+)*")
    private val atom = Regex("[^\\p{Z}\\t\\r\\n\\u000B\\u000C]+")
    private val quoted = Regex("""```[\s\S]*?```|`[^`]*`|"(?:\\[\s\S]|[^"\\])*"|“[^”]*”|‘[^’]*’|(?<![\p{L}\p{N}])'(?:\\[\s\S]|[^'\\])+'(?![\p{L}\p{N}])""")
    private val domain = Regex("(?:[\\p{L}\\p{N}_-]+\\.)+[\\p{L}\\p{N}_-]+")
    private val operatorLine = Regex("(?m)^.*(?:==|!=|<=|>=|:=|=>|->|\\+=|-=|\\*=|/=|=).*$")
    private val commandLine = Regex("(?im)^[ \\t]*(?:git|curl|wget|npm|npx|pip|python|python3|adb|gradle|flutter|dart|sudo|rm|mkdir|cd|ls)[ \\t]+[^\\r\\n]*$")
    private val codeCharacters = "_/@\\=<>|&+#{}[]()".toSet()
    private val abbreviations = ("dr mr mrs ms prof sr jr st vs etc e.g i.e cf approx fig no nos " +
        "inc ltd co dept est jan feb mar apr jun jul aug sep sept oct nov dec " +
        "dll dsb dst dkk sdr sdri bpk ibu jl jln kec kab kel no telp alm almarhum " +
        "pt cv ir h hj drs dra a.m p.m").split(' ').toSet()
    private val units = ("m M mm cm km µm μm nm m2 m3 m² m³ cm² cm³ mm² mm³ " +
        "g mg Mg kg µg μg ug l L ml mL dl dL s S ms mS µs μs ns min h Hz kHz MHz GHz " +
        "V mV kV A mA µA μA W mW kW MW K °C °F Pa kPa MPa J kJ N kN mol mmol µmol μmol " +
        "b B kb kB KB Mb MB Gb GB TB KiB MiB GiB").split(' ').toSet()

    fun apply(raw: String): Result {
        if (raw.isBlank() || raw.length > 12000 || !validUnicode(raw)) return Result(raw, emptyList())
        val words = word.findAll(raw).map { Span(it.range.first, it.range.last + 1) }.toList()
        val atoms = atom.findAll(raw).map { Span(it.range.first, it.range.last + 1) }.toList()
        val protectedSpans = mutableListOf<Span>()
        for (pattern in listOf(quoted, operatorLine, commandLine)) {
            pattern.findAll(raw).forEach { protectedSpans.add(Span(it.range.first, it.range.last + 1)) }
        }
        for (part in atoms) {
            val text = raw.substring(part.begin, part.end)
            if (text.any { it in codeCharacters } || domain.containsMatchIn(text) ||
                text.startsWith("http:", true) || text.startsWith("https:", true)) protectedSpans.add(part)
        }
        // Quotes can span lines. An unmatched delimiter protects the entire
        // remaining source, not just the first line of an ambiguous literal.
        // Apostrophes inside contractions are not opening quotes.
        Regex("[\"“‘`]|(?<![\\p{L}\\p{N}])'").findAll(raw).forEach { match ->
            if (protectedSpans.none { match.range.first in it.begin until it.end }) {
                protectedSpans.add(Span(match.range.first, raw.length))
            }
        }
        fun protectedAt(position: Int) = protectedSpans.any { position in it.begin until it.end }
        fun containingAtom(position: Int) = atoms.firstOrNull { position in it.begin until it.end }
        fun isAbbreviation(text: String): Boolean {
            val core = text.trimEnd('.', '!', '?')
            return core.lowercase(Locale.ROOT) in abbreviations ||
                core.codePointCount(0, core.length) == 1 ||
                Regex("(?:\\p{L}\\.)+\\p{L}?\\.?").matches(text)
        }
        fun safeWord(span: Span): Boolean {
            val text = raw.substring(span.begin, span.end)
            val part = containingAtom(span.begin) ?: return false
            return !protectedAt(span.begin) && !protectedAt(span.end - 1) &&
                span.begin == part.begin && text !in units &&
                text.codePoints().noneMatch { Character.isUpperCase(it) || Character.isDigit(it) } &&
                Character.isLetter(raw.codePointAt(span.begin))
        }
        fun sentenceStart(span: Span): Boolean {
            var before = span.begin
            while (before > 0 && whitespace(raw.codePointBefore(before)))
                before -= Character.charCount(raw.codePointBefore(before))
            if (before == 0) return true
            if (before == span.begin) return false // Require separating whitespace.
            val punctuation = before - 1
            if (raw[punctuation] !in ".!?" || protectedAt(punctuation)) return false
            val previous = containingAtom(punctuation) ?: return false
            val previousText = raw.substring(previous.begin, previous.end)
            if (previousText.any { it in "\"'‘’“”`" } || previousText.endsWith("..")) return false
            if (raw[punctuation] == '.') {
                if (isAbbreviation(previousText)) return false
                val prior = words.lastOrNull { it.end <= punctuation } ?: return false
                if (prior.end != punctuation || raw.substring(prior.begin, prior.end) in units ||
                    raw.substring(prior.begin, prior.end).any { it.isDigit() }) return false
            }
            return true
        }
        val edits = mutableListOf<Edit>()
        for (span in words) {
            if (!safeWord(span) || !sentenceStart(span)) continue
            val cp = raw.codePointAt(span.begin)
            val original = String(Character.toChars(cp))
            val replacement = original.uppercase(Locale.ROOT)
            if (replacement != original && replacement.length == original.length &&
                replacement.codePointCount(0, replacement.length) == 1) {
                edits.add(Edit(span.begin, span.begin + original.length, original, replacement,
                    if (raw.substring(0, span.begin).all { whitespace(it.code) }) "text_start" else "sentence_start"))
            }
        }
        val last = atoms.lastOrNull()
        val lastWord = words.lastOrNull()
        if (last != null && lastWord != null && lastWord.end == last.end &&
            lastWord.begin == last.begin && !protectedAt(last.begin) && !protectedAt(last.end - 1)) {
            val ending = raw.substring(last.begin, last.end)
            if (ending !in units && !isAbbreviation(ending) &&
                ending.codePoints().allMatch { Character.isLetter(it) || it == '\''.code || it == '’'.code } &&
                // Acronyms and camelCase may be code/names. Ordinary sentence
                // initial capitals elsewhere do not prevent a terminal stop.
                !ending.drop(1).any { it.isUpperCase() }) {
                edits.add(Edit(last.end, last.end, "", ".", "terminal_period"))
            }
        }
        val sorted = edits.sortedBy { it.beginUtf16 }
        var previous = 0
        val result = buildString {
            for (edit in sorted) {
                check(edit.beginUtf16 >= previous && raw.substring(edit.beginUtf16, edit.endUtf16) == edit.original)
                append(raw.substring(previous, edit.beginUtf16)); append(edit.replacement)
                previous = edit.endUtf16
            }
            append(raw.substring(previous))
        }
        return Result(result, sorted)
    }

    private fun whitespace(cp: Int) = Character.isWhitespace(cp) || Character.isSpaceChar(cp) || cp == 0x85
    private fun validUnicode(raw: String): Boolean {
        var index = 0
        while (index < raw.length) {
            val cp = raw.codePointAt(index)
            val kind = Character.getType(cp)
            if (cp in 0xD800..0xDFFF || cp !in listOf(9, 10, 13) && kind in listOf(
                    Character.CONTROL.toInt(), Character.FORMAT.toInt(), Character.PRIVATE_USE.toInt(), Character.UNASSIGNED.toInt())) return false
            index += Character.charCount(cp)
        }
        return true
    }
}
