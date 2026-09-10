package ai.voiceflow.voiceflow_mobile

import java.util.Locale

/** Experimental label renderer, never a word generator or automatic quality gate. */
object PunctuationSpanRenderer {
    data class SkippedAction(val tokenIndex: Int, val action: String, val reason: String,
        val pieceIndex: Int? = null, val label: String? = null)
    data class Result(val candidateText: String, val skippedActions: List<SkippedAction>,
        val outcome: String, val guardFlags: List<String> = emptyList())
    private data class Span(val start: Int, val end: Int)
    private val number = unicodePattern("(?<!\\w)(?:[$€£¥][ \\t]*)?(?:[+\\-−][ \\t]*)?\\d+(?:[.,:/-]\\d+)*(?:[ \\t]*[%‰])?")
    private const val UNIT_NUMBER = "(?<!\\w)(?:[+\\-−][ \\t]*)?\\d+(?:[.,]\\d+)*"
    private val attachedUnit = unicodePattern(UNIT_NUMBER + "°?[^\\W\\d_]+[\\w²³]*(?!\\w)")
    private val unitNames = ("m M mm cm km µm μm nm m2 m3 m² m³ cm² cm³ mm² mm³ " +
        "g mg Mg kg µg μg ug l L ml mL dl dL s S ms mS µs μs ns min h Hz kHz MHz GHz " +
        "V mV kV A mA µA μA W mW kW MW K °C °F Pa kPa MPa J kJ N kN mol mmol µmol μmol " +
        "b B kb kB KB Mb MB Gb GB TB KiB MiB GiB").split(' ')
    private val spacedUnit = unicodePattern(UNIT_NUMBER + "[ \\t\\r\\n\\u00a0]+(?:" +
        unitNames.sortedByDescending { it.length }.joinToString("|") { Regex.escape(it) } + ")(?!\\w)")
    private val exactPatterns = listOf(number, attachedUnit, spacedUnit,
        unicodePattern("```[\\s\\S]*?```|`[^`\\r\\n]+`"),
        unicodePattern("(?i)\\b(?:https?://|ftp://|www\\.)[^\\s<>`]+"),
        unicodePattern("(?<![\\w.])(?:[\\w-]+\\.)+[\\w-]+(?:/[^\\s<>`]*)?"),
        unicodePattern("(?m)^.*(?<!\\S)[\\w.]+[ \\t]+(?:==|!=|<=|>=|:=|=>|->|\\+=|-=|\\*=|/=|=).*$"))
    private val atomsPattern = unicodePattern("\\S+")
    private val codeSymbols = "_/@\\=<>|&+#{}[]()".toSet()

    // Android ICU always uses Unicode classes and does not support Java's U
    // flag. Spell out Python-like word/digit/space classes for both runtimes.
    private fun unicodePattern(value: String): Regex = Regex(value
        .replace("\\w", "[\\p{L}\\p{N}_]")
        .replace("\\W", "[^\\p{L}\\p{N}_]")
        .replace("\\d", "\\p{Nd}")
        .replace("\\s", "[\\p{Z}\\u0009-\\u000D\\u001C-\\u0020\\u0085]")
        .replace("\\S", "[^\\p{Z}\\u0009-\\u000D\\u001C-\\u0020\\u0085]"))

    fun render(raw: String, tokens: List<PunctuationToken>, postIds: IntArray,
        capitals: Array<IntArray>, postLabels: List<String>): Result {
        require(raw.isNotBlank() && raw.length <= 12000) { "Expected nonempty bounded source text." }
        validateUnicode(raw)
        val lowered = raw.lowercase(Locale.ROOT)
        require(lowered.length == raw.length) { "Lowercasing changes source offsets." }
        require(tokens.size == postIds.size && tokens.size == capitals.size && tokens.size <= 5000)
        require(postLabels.isNotEmpty() && postLabels.size <= 64)
        var previousEnd = 0
        for ((index, token) in tokens.withIndex()) {
            require(token.id >= 0 && token.beginUtf16 >= previousEnd &&
                token.endUtf16 >= token.beginUtf16 && token.endUtf16 <= raw.length)
            require(boundary(raw, token.beginUtf16) && boundary(raw, token.endUtf16) &&
                boundary(lowered, token.beginUtf16) && boundary(lowered, token.endUtf16))
            validateUnicode(token.piece, allowControls = true)
            require(token.surface == lowered.substring(token.beginUtf16, token.endUtf16))
            require(postIds[index] in postLabels.indices)
            require(capitals[index].size == 16 && capitals[index].all { it == 0 || it == 1 })
            if (!token.unknown) require(token.piece.codePointCount(0, token.piece.length) <= 16)
            previousEnd = token.endUtf16
        }
        val atoms = spans(atomsPattern, raw)
        val numeric = (spans(number, raw) + spans(attachedUnit, raw) + spans(spacedUnit, raw)).toSet()
        val protectedSpans = exactPatterns.flatMap { spans(it, raw) }.toMutableSet()
        atoms.filterTo(protectedSpans) { atom -> raw.substring(atom.start, atom.end).any { it in codeSymbols } }
        val atomProtected = atoms.map { atom -> protectedSpans.any { overlaps(it, atom) } }
        val firstLetters = atoms.map { atom -> positions(raw, atom.start, atom.end).firstOrNull { Character.isLetter(raw.codePointAt(it)) } }
        // The Python independent guard rejects changing any token with existing
        // capitals. Apply that rule before editing (e.g. keep iPhone, not IPhone).
        val existingCaps = atoms.map { atom -> positions(raw, atom.start, atom.end).any { Character.isUpperCase(raw.codePointAt(it)) } }
        val uppercase = mutableMapOf<Int, String>()
        val insertions = mutableMapOf<Int, String>()
        val skipped = mutableListOf<SkippedAction>()
        fun skip(index: Int, action: String, reason: String, piece: Int? = null, label: String? = null) {
            skipped.add(SkippedAction(index, action, reason, piece, label))
        }
        fun atomAt(position: Int) = atoms.indexOfFirst { position >= it.start && position < it.end }
        for ((index, token) in tokens.withIndex()) {
            if (token.unknown) { skip(index, "token", "unknown_token"); continue }
            if (token.beginUtf16 == token.endUtf16) { skip(index, "token", "empty_span"); continue }
            val mapped = piecePositions(token, raw)
            if (mapped == null) { skip(index, "token", "normalized_piece_surface_mismatch"); continue }
            for ((pieceIndex, position) in mapped.withIndex()) {
                if (capitals[index][pieceIndex] == 0) continue
                val atom = if (position == null) -1 else atomAt(position)
                when {
                    position == null || atom < 0 || !Character.isLetter(raw.codePointAt(position)) ->
                        skip(index, "capitalization", "not_an_atom_letter", pieceIndex)
                    atomProtected[atom] -> skip(index, "capitalization", "protected_span", pieceIndex)
                    position != firstLetters[atom] -> skip(index, "capitalization", "not_first_atom_letter", pieceIndex)
                    existingCaps[atom] -> skip(index, "capitalization", "original_capitalization", pieceIndex)
                    else -> {
                        val original = String(Character.toChars(raw.codePointAt(position)))
                        val replacement = original.uppercase(Locale.ROOT)
                        if (replacement.codePointCount(0, replacement.length) != 1 || replacement.length != original.length)
                            skip(index, "capitalization", "case_expansion", pieceIndex)
                        else if (replacement != original) uppercase[position] = replacement
                    }
                }
            }
            val post = postLabels[postIds[index]]
            if (post in setOf("<NULL>", "None")) continue
            if (post !in setOf(".", ",", "?")) { skip(index, "punctuation", "unsupported_label", label = post); continue }
            val last = raw.offsetByCodePoints(token.endUtf16, -1)
            val atom = atomAt(last)
            val blocked = atom >= 0 && protectedSpans.any { span -> overlaps(span, atoms[atom]) &&
                (span !in numeric || token.endUtf16 > span.start && token.endUtf16 < span.end) }
            val reason = when {
                atom < 0 -> "not_an_atom_end"
                token.endUtf16 != atoms[atom].end -> "incomplete_atom"
                blocked -> "protected_span"
                raw.codePointAt(last) in ".,!?;:…".map { it.code } -> "existing_punctuation"
                !Character.isLetterOrDigit(raw.codePointAt(last)) -> "unsafe_closing_tail"
                insertions.containsKey(token.endUtf16) -> "duplicate_atom_action"
                else -> null
            }
            if (reason != null) skip(index, "punctuation", reason, label = post)
            else insertions[token.endUtf16] = post
        }
        val candidate = buildString {
            var position = 0
            while (position < raw.length) {
                val width = Character.charCount(raw.codePointAt(position))
                append(uppercase[position] ?: raw.substring(position, position + width))
                position += width
                insertions[position]?.let { append(it) }
            }
        }
        // Every untouched source span is copied in order, including whitespace,
        // code, units and existing paragraphs. No decode/trim/replace is allowed.
        return Result(candidate, skipped, if (candidate == raw) "unchanged" else "changed_review_required")
    }

    private fun spans(pattern: Regex, raw: String) = pattern.findAll(raw).map {
        Span(it.range.first, it.range.last + 1)
    }.toList()
    private fun overlaps(a: Span, b: Span) = a.start < b.end && a.end > b.start
    private fun boundary(text: String, position: Int) = position in 0..text.length &&
        !(position > 0 && position < text.length && Character.isHighSurrogate(text[position - 1]) && Character.isLowSurrogate(text[position]))
    private fun positions(text: String, start: Int, end: Int): List<Int> {
        val result = mutableListOf<Int>()
        var position = start
        while (position < end) { result.add(position); position += Character.charCount(text.codePointAt(position)) }
        return result
    }
    private fun piecePositions(token: PunctuationToken, raw: String): List<Int?>? {
        val expanded = token.piece.replace('▁', ' ')
        val mapped: List<Int?> = positions(raw, token.beginUtf16, token.endUtf16)
        if (expanded == token.surface) return mapped
        val boundarySpace = token.beginUtf16 == 0 || raw.codePointBefore(token.beginUtf16).let {
            Character.isWhitespace(it) || Character.isSpaceChar(it)
        }
        if (token.piece.startsWith('▁') && expanded.substring(1) == token.surface && boundarySpace)
            return listOf(null) + mapped
        return null
    }
    private fun validateUnicode(text: String, allowControls: Boolean = false) {
        var position = 0
        while (position < text.length) {
            val cp = text.codePointAt(position)
            require(cp !in 0xD800..0xDFFF) { "Unpaired UTF16 surrogate." }
            if (!allowControls) {
                val category = Character.getType(cp)
                require(cp in listOf(9, 10, 13) || category !in listOf(
                    Character.CONTROL.toInt(), Character.FORMAT.toInt(), Character.PRIVATE_USE.toInt(), Character.UNASSIGNED.toInt())) {
                    "Invalid source control character."
                }
            }
            position += Character.charCount(cp)
        }
    }
}
