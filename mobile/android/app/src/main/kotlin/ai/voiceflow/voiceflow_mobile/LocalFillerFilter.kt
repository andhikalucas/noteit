package ai.voiceflow.voiceflow_mobile

/**
 * Conservative spelling/context rules, not learned intent detection. Only
 * um/uh/erm are eligible; correction markers belong to the preceding repair
 * stage. Ambiguous words and contexts are deliberately retained.
 */
object LocalFillerFilter {
    data class Edit(
        val beginUtf16: Int,
        val endUtf16: Int,
        val original: String,
        val replacement: String,
        val reason: String,
    )

    data class Result(val text: String, val edits: List<Edit>)

    private val hesitation = Regex("(?<![\\p{L}\\p{N}_])(?:um|uh|erm)(?![\\p{L}\\p{N}_])", RegexOption.IGNORE_CASE)
    private val firstPerson = Regex("(?:i|we|saya|aku|gue|gua|kita|kami)(?=$|[\\s,'’])", RegexOption.IGNORE_CASE)
    private val literalContext = Regex(
        "\\b(?:word|words|term|terms|spell|spelling|letter|letters|sound|sounds|" +
            "name|named|names|nickname|surname|called|means|meaning|filler|fillers|interjection|" +
            "literal|token|identifier|variable|constant|acronym|symbol|unit|" +
            "kata|istilah|eja|huruf|bunyi|nama|namanya|panggil|sebut|sebutan|artinya|berarti|interjeksi)\\b",
        RegexOption.IGNORE_CASE,
    )

    fun apply(raw: String): Result {
        if (raw.isEmpty() || raw.length > 12000 || !validUnicode(raw)) return Result(raw, emptyList())
        val protected = protectedCharacters(raw)
        val edits = mutableListOf<Edit>()
        for (match in hesitation.findAll(raw)) {
            val begin = match.range.first
            val end = match.range.last + 1
            // Keep acronym-like spellings and any protected quotation/code.
            if (match.value == match.value.uppercase(java.util.Locale.ROOT) ||
                (begin until end).any { protected[it] }) continue
            var previous = begin - 1
            while (previous >= 0 && horizontalSpace(raw[previous])) previous--
            val atStart = previous < 0 || raw[previous] in "\n\r.!?"
            val afterComma = previous >= 0 && raw[previous] == ','
            if (!atStart && !afterComma) continue
            val comma = end < raw.length && raw[end] == ','
            // A punctuation mark other than an immediately adjacent comma is
            // not removable, and this filter never invents sentence joins.
            if (!comma && (end == raw.length || !horizontalSpace(raw[end]))) continue
            var following = end + if (comma) 1 else 0
            while (following < raw.length && raw[following].isWhitespace()) following++
            if (following == raw.length || !raw[following].isLetter() || protected[following]) continue
            if (!comma && (!atStart || firstPerson.find(raw, following)?.range?.first != following)) continue
            val context = sentenceContext(raw, begin, end)
            if (literalContext.containsMatchIn(context) || context.any { it in "=\\/{}<>" }) continue

            // Delete only the hesitation, its own trailing comma, and adjacent
            // horizontal spacing. Existing leading delimiters/newlines stay.
            var removalEnd = end + if (comma) 1 else 0
            while (removalEnd < raw.length && horizontalSpace(raw[removalEnd])) removalEnd++
            edits += Edit(begin, removalEnd, raw.substring(begin, removalEnd), "",
                if (comma) "boundary_delimited_hesitation" else "utterance_initial_first_person_hesitation")
        }
        if (edits.isEmpty()) return Result(raw, emptyList())
        val output = StringBuilder(raw.length)
        var cursor = 0
        for (edit in edits) {
            // Ranges originate exclusively from nonoverlapping source tokens.
            check(edit.beginUtf16 >= cursor && edit.endUtf16 <= raw.length)
            output.append(raw, cursor, edit.beginUtf16)
            cursor = edit.endUtf16
        }
        output.append(raw, cursor, raw.length)
        return Result(output.toString(), edits.toList())
    }

    private fun horizontalSpace(char: Char) = char == ' ' || char == '\t'

    private fun validUnicode(raw: String): Boolean {
        var position = 0
        while (position < raw.length) {
            val char = raw[position]
            if (Character.isLowSurrogate(char) || char.isISOControl() && char !in "\t\n\r") return false
            if (Character.isHighSurrogate(char)) {
                if (position + 1 == raw.length || !Character.isLowSurrogate(raw[position + 1])) return false
                position++
            }
            position++
        }
        return true
    }

    private fun sentenceContext(raw: String, begin: Int, end: Int): String {
        var left = begin
        var right = end
        while (left > 0 && raw[left - 1] !in "\n\r.!?") left--
        while (right < raw.length && raw[right] !in "\n\r.!?") right++
        return raw.substring(left, right)
    }

    private fun protectedCharacters(raw: String): BooleanArray {
        val protected = BooleanArray(raw.length)
        val pairs = mapOf('"' to '"', '\'' to '\'', '“' to '”', '‘' to '’',
            '`' to '`', '(' to ')', '[' to ']', '{' to '}')
        var position = 0
        while (position < raw.length) {
            val close = pairs[raw[position]]
            // Apostrophes inside ordinary words do not open a quotation.
            val contraction = raw[position] == '\'' && position > 0 && position + 1 < raw.length &&
                raw[position - 1].isLetter() && raw[position + 1].isLetter()
            if (close == null || contraction) { position++; continue }
            val closing = raw.indexOf(close, position + 1)
            val limit = if (closing < 0) raw.length else closing + 1
            for (index in position until limit) protected[index] = true
            position = limit
        }
        return protected
    }
}
