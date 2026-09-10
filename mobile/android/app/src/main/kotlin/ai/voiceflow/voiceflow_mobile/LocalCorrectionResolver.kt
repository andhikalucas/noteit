package ai.voiceflow.voiceflow_mobile

import java.util.Locale

/** Source-only repairs for a deliberately small set of explicit spoken corrections. */
object LocalCorrectionResolver {
    data class Edit(val beginUtf16: Int, val endUtf16: Int, val original: String,
        val replacement: String, val reason: String)
    data class Result(val text: String, val edits: List<Edit>)
    private data class Word(val value: String, val begin: Int, val end: Int)
    private val wordPattern = Regex("[\\p{L}\\p{N}]+")
    private val cuePattern = Regex("(?i)(?<![\\p{L}\\p{N}_])(?:eh[ \\t,]+(?:maksudku|maksudnya|sorry|maaf)|maksudku)(?![\\p{L}\\p{N}_])")
    private val separator = Regex("[ \\t,]*")
    private val singlePeriod = Regex("[ \\t]*\\.[ \\t]*")
    private val spaces = Regex("[ \\t]+")
    private val negative = setOf("ga", "gak", "nggak", "enggak", "tidak", "tak", "bukan", "not")
    private val protectedNegative = negative + setOf("jangan", "belum", "tanpa", "never", "no", "dont", "cannot")
    private val numbers = setOf("nol", "satu", "dua", "tiga", "empat", "lima", "enam", "tujuh", "delapan",
        "sembilan", "sepuluh", "sebelas", "belas", "puluh", "ratus", "seratus", "ribu", "seribu",
        "zero", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine", "ten", "eleven", "twelve")
    private val numericAnchors = setOf("jam", "pukul", "tanggal", "nomor", "lantai", "tahun")
    private val weekdays = setOf("senin", "selasa", "rabu", "kamis", "jumat", "sabtu", "minggu",
        "monday", "tuesday", "wednesday", "thursday", "friday", "saturday", "sunday")
    private val nounAnchors = setOf("nama", "warna", "proyek", "ruang")
    private val subjectStops = protectedNegative + setOf("yang", "dan", "atau", "karena", "kalau", "jadi", "memang", "sendiri")
    // Unquoted discussion of words/examples is not itself an instruction to
    // edit those words. Ambiguous reported or metalinguistic speech stays raw.
    private val metalinguistic = setOf("kata", "frasa", "kalimat", "contoh", "contohnya", "misalnya", "istilah",
        "kutipan", "ucapan", "literal", "word", "words", "phrase", "sentence", "example", "quote", "quoted", "literally")
    private val unsafeCharacters = setOf('"', '\'', '“', '”', '‘', '’', '`', '_', '/', '\\', '@', '=', '<', '>', '{', '}', '[', ']', ':', '(', ')', '+', '*', '|', '&')

    fun apply(raw: String): Result {
        if (raw.length > 12000 || !validUnicode(raw) || raw.any { it in unsafeCharacters }) return Result(raw, emptyList())
        val edits = mutableListOf<Edit>()
        // Whisper may put a period just before a spoken correction cue. Only
        // that one immediately adjacent boundary may be bridged, never an
        // intervening sentence, question, exclamation or paragraph boundary.
        var begin = 0
        var precedingPeriodSentence: Pair<Int, Int>? = null
        for (end in 0..raw.length) {
            if (end != raw.length && raw[end] !in ".!?\n\r") continue
            val sentence = raw.substring(begin, end)
            if (sentence.none { it in unsafeCharacters }) {
                val cues = cuePattern.findAll(sentence).toList()
                if (cues.size == 1 && !followedByCorrection(raw, end)) {
                    val cue = cues.single()
                    val cueBegin = begin + cue.range.first
                    val cueEnd = begin + cue.range.last + 1
                    val preceding = precedingPeriodSentence
                    val bridgePeriod = preceding != null && raw.substring(begin, cueBegin).all { it == ' ' || it == '\t' } &&
                        !cuePattern.containsMatchIn(raw.substring(preceding.first, preceding.second))
                    val left = words(raw, if (bridgePeriod) preceding!!.first else begin,
                        if (bridgePeriod) preceding!!.second else cueBegin)
                    val right = words(raw, cueEnd, end)
                    if (left.isNotEmpty() && right.isNotEmpty() && left.size <= 96 && right.size <= 96 &&
                        (left + right).none { it.value in metalinguistic } &&
                        (if (bridgePeriod) singlePeriod else separator).matches(raw.substring(left.last().end, cueBegin)) &&
                        separator.matches(raw.substring(cueEnd, right.first().begin))) {
                        val range = negativeRepair(raw, left, right)
                            ?: subjectRepair(raw, left, right)
                            ?: anchorRepair(raw, left, right)
                        if (range != null) {
                            val (start, stop, reason) = range
                            edits += Edit(start, stop, raw.substring(start, stop), "", reason)
                        }
                    }
                }
            }
            precedingPeriodSentence = if (end < raw.length && raw[end] == '.' && sentence.isNotBlank()) begin to end else null
            begin = end + 1
        }
        if (edits.isEmpty()) return Result(raw, emptyList())
        val text = buildString {
            var position = 0
            for (edit in edits) {
                append(raw, position, edit.beginUtf16)
                append(edit.replacement)
                position = edit.endUtf16
            }
            append(raw, position, raw.length)
        }
        return Result(text, edits)
    }

    private fun words(raw: String, begin: Int, end: Int): List<Word> =
        wordPattern.findAll(raw.substring(begin, end)).map {
            Word(it.value.lowercase(Locale.ROOT), begin + it.range.first, begin + it.range.last + 1)
        }.toList()

    private fun followedByCorrection(raw: String, end: Int): Boolean {
        if (end >= raw.length || raw[end] != '.') return false
        var position = end + 1
        while (position < raw.length && raw[position] in " \t") position++
        return cuePattern.find(raw, position)?.range?.first == position
    }

    private fun contiguous(raw: String, words: List<Word>): Boolean =
        words.zipWithNext().all { (a, b) -> spaces.matches(raw.substring(a.end, b.begin)) }

    private fun negativeRepair(raw: String, left: List<Word>, right: List<Word>): Triple<Int, Int, String>? {
        // The last negative must directly precede the entire repeated predicate.
        // A second negative, conjunction or intervening phrase is not resolved.
        val index = left.indexOfLast { it.value in negative }
        if (index < 0 || left.count { it.value in protectedNegative } != 1) return null
        if (right.any { it.value in protectedNegative || it.value in setOf("atau", "or") }) return null
        val predicate = left.drop(index + 1)
        if (predicate.size !in 1..4 || right.size < predicate.size ||
            predicate.any { it.value in subjectStops } || !contiguous(raw, left.drop(index)) ||
            !contiguous(raw, right.take(predicate.size))) return null
        if (predicate.map { it.value } != right.take(predicate.size).map { it.value }) return null
        return Triple(left[index].begin, right.first().begin, "explicit_negative_predicate_repair")
    }

    private fun subjectRepair(raw: String, left: List<Word>, right: List<Word>): Triple<Int, Int, String>? {
        // Unlike a directly repeated negative predicate, this rule cannot know
        // whether an earlier negative subject/modifier should also be replaced.
        if (left.any { it.value in protectedNegative } || right.any { it.value in protectedNegative }) return null
        val leftYang = left.indexOfLast { it.value == "yang" }
        val rightYang = right.indexOfFirst { it.value == "yang" }
        if (leftYang < 1 || rightYang !in 1..3 || rightYang == right.lastIndex ||
            left.size - leftYang - 1 !in 1..6 || left.count { it.value == "yang" } != 1) return null
        val subject = right.take(rightYang)
        if (subject.any { it.value in subjectStops }) return null
        val modifier = if (left[leftYang - 1].value == "sendiri") 1 else 0
        val subjectStart = leftYang - modifier - subject.size
        if (subjectStart < 0) return null
        if (subjectStart > 0 && left[subjectStart - 1].value in setOf("dan", "atau", "and", "or")) return null
        if (left.subList(subjectStart, subjectStart + subject.size).map { it.value } != subject.map { it.value }) return null
        // Repeated mentions of that same subject make the intended anchor unclear.
        if (left.windowed(subject.size).count { words -> words.map { it.value } == subject.map { it.value } } != 1) return null
        if (left.drop(leftYang + 1).any { it.value in protectedNegative } ||
            right.drop(rightYang + 1).any { it.value in protectedNegative }) return null
        if (!contiguous(raw, left.drop(subjectStart)) || !contiguous(raw, right.take(rightYang + 2))) return null
        return Triple(left[leftYang + 1].begin, right[rightYang + 1].begin, "explicit_repeated_subject_predicate_repair")
    }

    private fun anchorRepair(raw: String, left: List<Word>, right: List<Word>): Triple<Int, Int, String>? {
        val anchor = right.first().value
        if (anchor !in numericAnchors && anchor !in nounAnchors && anchor != "hari") return null
        val indices = left.indices.filter { left[it].value == anchor }
        if (indices.size != 1 || right.size < 2) return null
        val start = indices.single()
        val oldValue = left.drop(start + 1)
        if (oldValue.isEmpty() || oldValue.size > 4 || !contiguous(raw, left.drop(start)) ||
            !contiguous(raw, right.take(2)) || oldValue.any { it.value in protectedNegative }) return null
        fun number(value: String) = value in numbers || value.all { it.isDigit() }
        val newValue = if (anchor in numericAnchors) right.drop(1).takeWhile { number(it.value) }
            else right.subList(1, 2)
        val valid = when {
            anchor in numericAnchors -> oldValue.all { number(it.value) } && newValue.size in 1..4
            anchor == "hari" -> oldValue.size == 1 && oldValue.single().value in weekdays && right[1].value in weekdays
            else -> oldValue.size == 1 && right[1].value !in subjectStops && right[1].value !in cueWords
        }
        if (!valid || oldValue.map { it.value } == newValue.map { it.value }) return null
        return Triple(left[start].begin, right.first().begin, "explicit_repeated_anchor_repair")
    }

    private val cueWords = setOf("eh", "maksudku", "maksudnya", "sorry", "maaf")
    private fun validUnicode(raw: String): Boolean {
        var index = 0
        while (index < raw.length) {
            if (Character.isLowSurrogate(raw[index])) return false
            if (Character.isHighSurrogate(raw[index])) {
                if (index + 1 == raw.length || !Character.isLowSurrogate(raw[index + 1])) return false
                index++
            }
            index++
        }
        return true
    }
}
