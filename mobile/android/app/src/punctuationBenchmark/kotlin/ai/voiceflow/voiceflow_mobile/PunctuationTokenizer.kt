package ai.voiceflow.voiceflow_mobile

import androidx.annotation.Keep
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest
import java.util.Locale

data class PunctuationToken(
    val id: Int, val piece: String, val surface: String,
    val beginUtf16: Int, val endUtf16: Int, val unknown: Boolean,
)

data class PunctuationEncoding(
    val source: String, val lowered: String, val pieces: List<PunctuationToken>,
    val unkId: Int, val bosId: Int, val eosId: Int, val padId: Int, val vocabularySize: Int,
)

/** Optional benchmark runtime only. All public operations serialize native ownership. */
@Keep
class PunctuationTokenizer(modelFile: File) : AutoCloseable {
    private var handle = 0L
    private val specialIds: IntArray
    val unkId: Int get() = specialIds[0]
    val bosId: Int get() = specialIds[1]
    val eosId: Int get() = specialIds[2]
    val padId: Int get() = specialIds[3]
    val vocabularySize: Int get() = specialIds[4]

    init {
        check(BuildConfig.DEBUG && BuildConfig.BENCHMARK_BUILD && BuildConfig.PUNCTUATION_BENCHMARK) {
            "SentencePiece is available only in isolated punctuation benchmarks."
        }
        require(modelFile.isFile && modelFile.length() in 1L..8_388_608L) { "Invalid tokenizer model file." }
        val model = modelFile.readBytes()
        val hash = MessageDigest.getInstance("SHA-256").digest(model).joinToString("") { "%02x".format(it) }
        require(PINNED_MODELS[hash] == model.size) { "Tokenizer model checksum or size mismatch." }
        loadRuntime()
        handle = nativeLoad(model)
        check(handle != 0L) { "SentencePiece model failed to load." }
        try {
            specialIds = nativeSpecialIds(handle)
            require(specialIds.size == 5 && specialIds[4] > 0) { "Invalid tokenizer vocabulary." }
        } catch (error: Throwable) {
            nativeFree(handle); handle = 0
            throw error
        }
    }

    @Synchronized fun encode(source: String): PunctuationEncoding {
        check(handle != 0L) { "Tokenizer is closed." }
        val lowered = PunctuationTokenizerText.lowerForOffsets(source)
        val result = PunctuationTokenizerText.decodePacket(source, lowered, nativeEncode(handle, lowered.toByteArray(Charsets.UTF_8)))
        require(result.unkId == unkId && result.bosId == bosId && result.eosId == eosId &&
            result.padId == padId && result.vocabularySize == vocabularySize) { "Tokenizer metadata changed." }
        return result
    }

    @Synchronized override fun close() {
        if (handle != 0L) { nativeFree(handle); handle = 0L }
    }

    private external fun nativeLoad(model: ByteArray): Long
    private external fun nativeSpecialIds(handle: Long): IntArray
    private external fun nativeEncode(handle: Long, textUtf8: ByteArray): ByteArray
    private external fun nativeFree(handle: Long)

    companion object {
        const val RUNTIME_VERSION = "0.2.1"
        const val RUNTIME_REVISION = "31646a467d2051eb904e0b45de3a73e91fe1c1e3"
        const val RUNTIME_ARCHIVE_SHA256 = "b4eb17e6bea5c9380ddd79d04d01d6aa5e280be8f3e3cef1f745a80e6d9451ce"
        private val PINNED_MODELS = mapOf(
            "1bc15b6e5fd80dfac9999582ce3efcad2ac1f7cf4e0e9769b329f5de9ca5af47" to 1_186_519,
            "7f944d0be93b275f62e1913fd409f378ddbba108e57fe4a9cb47e8c047f6bef1" to 5_069_059,
        )
        private var loaded = false
        @Synchronized private fun loadRuntime() {
            if (!loaded) { System.loadLibrary("noteit_sentencepiece"); loaded = true }
        }
    }
}

/** Pure offset/protocol validation; no native library is loaded by these helpers. */
internal object PunctuationTokenizerText {
    fun lowerForOffsets(source: String): String {
        require(source.length <= 64_000) { "Tokenizer source exceeds the benchmark limit." }
        val sourceBoundaries = utf8BoundaryMap(source).filter { it >= 0 }
        val lowered = source.lowercase(Locale.ROOT)
        require(lowered.length == source.length && utf8BoundaryMap(lowered).filter { it >= 0 } == sourceBoundaries) {
            "Lowercasing changes source offset boundaries."
        }
        return lowered
    }

    /** -1 marks byte positions inside a code point; valid entries are UTF-16 offsets. */
    fun utf8BoundaryMap(text: String): IntArray {
        val result = IntArray(text.toByteArray(Charsets.UTF_8).size + 1) { -1 }
        var utf16 = 0
        var byte = 0
        result[0] = 0
        while (utf16 < text.length) {
            val first = text[utf16]
            require(!Character.isLowSurrogate(first)) { "Unpaired UTF-16 surrogate." }
            if (Character.isHighSurrogate(first)) require(utf16 + 1 < text.length && Character.isLowSurrogate(text[utf16 + 1])) {
                "Unpaired UTF-16 surrogate."
            }
            val codePoint = Character.codePointAt(text, utf16)
            byte += when { codePoint <= 0x7f -> 1; codePoint <= 0x7ff -> 2; codePoint <= 0xffff -> 3; else -> 4 }
            utf16 += Character.charCount(codePoint)
            result[byte] = utf16
        }
        return result
    }

    fun decodePacket(source: String, lowered: String, packet: ByteArray): PunctuationEncoding {
        require(lowerForOffsets(source) == lowered) { "Tokenizer lowercase input does not match source." }
        require(packet.size in 28..8_388_608) { "Invalid tokenizer packet size." }
        val input = ByteBuffer.wrap(packet).order(ByteOrder.LITTLE_ENDIAN)
        fun number(): Int { require(input.remaining() >= 4) { "Truncated tokenizer packet." }; return input.int }
        fun text(): String {
            val count = number()
            require(count in 0..input.remaining()) { "Invalid tokenizer string length." }
            val bytes = ByteArray(count); input.get(bytes)
            return Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
        }
        require(number() == 1) { "Unsupported tokenizer packet version." }
        val unk = number(); val bos = number(); val eos = number(); val pad = number(); val vocabulary = number()
        require(vocabulary > 0 && unk in 0 until vocabulary && listOf(bos, eos, pad).all { it == -1 || it in 0 until vocabulary }) {
            "Invalid tokenizer special IDs."
        }
        val count = number()
        require(count in 0..128_000 && count <= input.remaining() / 20) { "Invalid tokenizer piece count." }
        val boundaries = utf8BoundaryMap(lowered)
        var previousEnd = 0
        val pieces = ArrayList<PunctuationToken>(count)
        repeat(count) {
            val id = number(); val begin = number(); val end = number()
            val piece = text(); val surface = text()
            require(id in 0 until vocabulary && begin >= previousEnd && end >= begin && end < boundaries.size) {
                "Invalid tokenizer piece/span."
            }
            require(boundaries[begin] >= 0 && boundaries[end] >= 0) { "Tokenizer span splits a Unicode code point." }
            val startUtf16 = boundaries[begin]; val endUtf16 = boundaries[end]
            require(lowered.substring(startUtf16, endUtf16) == surface) { "Tokenizer surface does not match source span." }
            pieces += PunctuationToken(id, piece, surface, startUtf16, endUtf16, id == unk)
            previousEnd = end
        }
        require(!input.hasRemaining()) { "Trailing tokenizer packet data." }
        return PunctuationEncoding(source, lowered, pieces, unk, bos, eos, pad, vocabulary)
    }
}
