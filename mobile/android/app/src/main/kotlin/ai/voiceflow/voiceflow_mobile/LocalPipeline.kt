package ai.voiceflow.voiceflow_mobile

import android.content.Context
import android.os.SystemClock
import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.Future
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Development knobs are deliberately absent from ordinary settings. */
data class LocalPipelineConfig(
    val recognition: LocalRecognitionConfig = LocalRecognitionConfig(),
    val minimumChunkMs: Int = 6_000,
    val maximumChunkMs: Int = 15_000,
    val silenceMs: Int = 500,
    val overlapMs: Int = 400,
    val semanticCleanup: Boolean = false,
    val cleanupContextSentences: Int = 3,
    val cleanupBudgetMs: Long = 10_000,
    val cleanupThreads: Int = 4,
    val silenceThresholdRms: Int = 64,
) {
    init {
        require(minimumChunkMs in 500..30_000)
        require(maximumChunkMs in minimumChunkMs..30_000)
        require(silenceMs in 100..2_000)
        require(overlapMs in 0..1_000 && overlapMs < minimumChunkMs)
        require(cleanupContextSentences in 1..8 && cleanupBudgetMs in 500..120_000 && cleanupThreads in 1..8)
        require(silenceThresholdRms in 64..2_048)
    }
}

data class AudioChunk(val startSample: Long, val endSample: Long, val leadingOverlapMs: Int, val hasSpeech: Boolean)

/**
 * Lightweight 20 ms energy boundary detector, not a semantic/word detector.
 * Low energy is used to FIND boundaries; it never trims a recognized phrase.
 * Only segments with no frame above the quiet-speech floor are skipped.
 */
class LocalChunker(private val config: LocalPipelineConfig, private val emit: (AudioChunk) -> Unit) {
    private var position = 0L
    private var start = 0L
    private var leadingOverlap = 0
    private var frameEnergy = 0.0
    private var frameSamples = 0
    private var quietFrames = 0
    private var hasSpeech = false
    private var freshSamples = 0L

    fun accept(samples: ShortArray) {
        for (sample in samples) {
            frameEnergy += sample.toDouble() * sample
            frameSamples++
            position++
            freshSamples++
            if (frameSamples == 320) {
                // RMS 64/32768 (~-54 dBFS) keeps quiet speech eligible.
                val voiced = frameEnergy / frameSamples >= 64.0 * 64.0
                hasSpeech = hasSpeech || voiced
                // Presence stays conservative for quiet speech. Only the pause
                // boundary uses the configurable background-noise threshold.
                val abovePauseFloor = frameEnergy / frameSamples >=
                    config.silenceThresholdRms.toDouble() * config.silenceThresholdRms
                quietFrames = if (abovePauseFloor) 0 else quietFrames + 1
                frameSamples = 0
                frameEnergy = 0.0
                val lengthMs = (position - start) / 16
                val atPause = lengthMs >= config.minimumChunkMs && quietFrames * 20 >= config.silenceMs
                val forced = lengthMs >= config.maximumChunkMs
                if (atPause || forced) flush(forced && !atPause)
            }
        }
    }

    fun finish() {
        if (frameSamples > 0 && frameEnergy / frameSamples >= 64.0 * 64.0) hasSpeech = true
        if (freshSamples > 0) flush(false)
    }

    private fun flush(forced: Boolean) {
        emit(AudioChunk(start, position, leadingOverlap, hasSpeech))
        leadingOverlap = if (forced) config.overlapMs else 0
        start = position - leadingOverlap * 16L
        quietFrames = 0
        hasSpeech = false
        freshSamples = 0
    }
}

data class LocalChunkMeasurement(
    val startSample: Long,
    val endSample: Long,
    val queuedMs: Long,
    val startedMs: Long,
    val finishedMs: Long,
    val loadMs: Long,
    val inferenceMs: Long,
    val text: String,
)

data class LocalCleanupMeasurement(val startedMs: Long, val finishedMs: Long, val result: CleanupResult)

class LocalTranscriber(
    context: Context,
    private val config: LocalPipelineConfig = LocalPipelineConfig(),
) : Transcriber {
    private val context = context.applicationContext

    fun startSession(options: DictationOptions, cancelled: () -> Boolean): LocalAudioSession =
        startMeasuredSession(options, cancelled)

    internal fun startMeasuredSession(options: DictationOptions, cancelled: () -> Boolean): PipelinedLocalSession {
        if (!WhisperEngine.available) throw DictationException("local_unavailable", "Local engine unavailable.")
        if (!WhisperEngine.modelReady(context, config.recognition.model)) {
            throw DictationException("model_missing", "Download the local model in Settings.")
        }
        if (config.semanticCleanup) {
            if (!LocalCleanupNative.available) throw DictationException("cleanup_unavailable", "Offline cleanup runtime unavailable.")
            if (!LocalCleanupModel.ready(context)) throw DictationException("cleanup_model_missing", "Download the offline cleanup model first.")
        }
        return PipelinedLocalSession(context, options, config, cancelled)
    }

    override fun transcribe(audio: File, options: DictationOptions, cancelled: () -> Boolean): TranscriptionResult {
        val session = startMeasuredSession(options, cancelled)
        try {
            PcmWavReader(audio).use { reader ->
                while (true) {
                    if (cancelled()) throw DictationException("cancelled", "Recording cancelled.")
                    val block = reader.readSamples(1_600) ?: break
                    session.accept(block)
                }
            }
            return session.finish(audio)
        } catch (failure: Throwable) {
            session.cancel()
            throw failure
        }
    }
}

/** Audio stays on disk; the queue holds only chunk offsets, never audio arrays. */
internal class PipelinedLocalSession(
    private val context: Context,
    private val options: DictationOptions,
    private val config: LocalPipelineConfig,
    private val externalCancelled: () -> Boolean,
) : LocalAudioSession {
    private val cancelled = AtomicBoolean(false)
    private val spool = File.createTempFile("local-chunks-", ".pcm", context.cacheDir)
    private val writer = RandomAccessFile(spool, "rw")
    private val executor = ThreadPoolExecutor(
        1, 1, 0, TimeUnit.MILLISECONDS, ArrayBlockingQueue<Runnable>(128),
        { runnable -> Thread(runnable, "noteit-local").apply { priority = Thread.NORM_PRIORITY - 1 } },
    )
    private val jobs = mutableListOf<Future<*>>()
    private val transcript = StringBuilder()
    private val measurements = mutableListOf<LocalChunkMeasurement>()
    private val cleanupMeasurements = mutableListOf<LocalCleanupMeasurement>()
    private val cleanupQueue = LocalCleanupPending()
    private val cleanupWindow = LocalCleanupWindow(config.cleanupContextSentences)
    private val chunker = LocalChunker(config, ::enqueue)
    private var closed = false
    @Volatile private var failure: Throwable? = null
    @Volatile var warmupMs: Long = 0
        private set

    init {
        jobs += executor.submit {
            if (!isCancelled()) {
                val start = SystemClock.elapsedRealtime()
                WhisperEngine.warmUp(context, config.recognition.model, config.recognition.useGpu)
                warmupMs = SystemClock.elapsedRealtime() - start
            }
        }
    }

    private fun isCancelled(): Boolean = cancelled.get() || externalCancelled()

    @Synchronized
    override fun accept(samples: ShortArray) {
        if (closed || isCancelled()) throw DictationException("cancelled", "Recording cancelled.")
        failure?.let { throw it }
        val bytes = ByteArray(samples.size * 2)
        for (i in samples.indices) {
            bytes[i * 2] = samples[i].toByte()
            bytes[i * 2 + 1] = (samples[i].toInt() ushr 8).toByte()
        }
        writer.write(bytes)
        chunker.accept(samples)
    }

    private fun enqueue(chunk: AudioChunk) {
        if (!chunk.hasSpeech || chunk.endSample <= chunk.startSample) return
        val queued = SystemClock.elapsedRealtime()
        try {
            jobs += executor.submit {
                try {
                    if (isCancelled() || failure != null) return@submit
                    val start = SystemClock.elapsedRealtime()
                    val samples = readChunk(chunk)
                    val result = WhisperEngine.recognize(
                        context, samples, config.recognition,
                        options.vocabulary.filter { it.isNotBlank() }.joinToString(", ").take(2_000).ifBlank { null },
                        chunk.leadingOverlapMs, ::isCancelled,
                    ) ?: throw DictationException(
                        if (isCancelled()) "cancelled" else "local_failed",
                        if (isCancelled()) "Recording cancelled." else "Local transcription failed. Retry the recording.",
                    )
                    if (isCancelled()) return@submit
                    if (result.text.isNotBlank()) {
                        if (transcript.isNotEmpty()) transcript.append(' ')
                        transcript.append(result.text)
                    }
                    synchronized(measurements) {
                        measurements += LocalChunkMeasurement(chunk.startSample, chunk.endSample, queued, start,
                            SystemClock.elapsedRealtime(), result.loadMs, result.inferenceMs, result.text)
                    }
                    if (config.semanticCleanup && result.text.isNotBlank()) {
                        cleanupQueue.append(result.text)
                        drainCleanup(prioritizeRecognition = true)
                    }
                } catch (error: Throwable) { failure = error }
            }
        } catch (error: java.util.concurrent.RejectedExecutionException) {
            throw DictationException("local_backlog", "Local processing fell behind. Stop and retry the recording.")
        }
    }

    private fun readChunk(chunk: AudioChunk): FloatArray {
        val count = (chunk.endSample - chunk.startSample).toInt()
        val bytes = ByteArray(count * 2)
        RandomAccessFile(spool, "r").use { it.seek(chunk.startSample * 2); it.readFully(bytes) }
        return FloatArray(count) { i ->
            (((bytes[i * 2].toInt() and 255) or (bytes[i * 2 + 1].toInt() shl 8)).toShort()) / 32768f
        }
    }

    private fun drainCleanup(prioritizeRecognition: Boolean) {
        while (true) {
            if (isCancelled()) throw DictationException("cancelled", "Recording cancelled.")
            if (prioritizeRecognition && executor.queue.isNotEmpty()) return
            val remainingCharacters = (LocalCleanupEngine.MAX_INPUT_CHARACTERS - cleanupWindow.input("").length)
                .coerceAtLeast(0)
            val batch = cleanupQueue.snapshot(remainingCharacters) ?: return
            val input = cleanupWindow.input(batch.text)
            val start = SystemClock.elapsedRealtime()
            val result = LocalCleanupEngine.clean(
                context, input, options.vocabulary, ::isCancelled,
                config.cleanupThreads, config.cleanupBudgetMs,
                shouldYield = { prioritizeRecognition && executor.queue.isNotEmpty() },
            )
            synchronized(cleanupMeasurements) {
                cleanupMeasurements += LocalCleanupMeasurement(start, SystemClock.elapsedRealtime(), result)
            }
            if (result.reason == "recognition_priority") return
            // Only a completed candidate or our exact input may consume the
            // captured batch. Recognition-priority aborts retain every item.
            cleanupWindow.accept(if (result.accepted) result.text else input)
            cleanupQueue.consume(batch)
        }
    }

    override fun finish(audio: File): TranscriptionResult {
        try {
            synchronized(this) {
                if (closed || isCancelled()) throw DictationException("cancelled", "Recording cancelled.")
                chunker.finish()
                writer.close()
                closed = true
                executor.shutdown()
            }
            for (job in jobs) {
                while (true) {
                    if (isCancelled()) throw DictationException("cancelled", "Recording cancelled.")
                    try { job.get(100, TimeUnit.MILLISECONDS); break }
                    catch (_: java.util.concurrent.TimeoutException) { /* Poll cancellation. */ }
                }
            }
            failure?.let { throw it }
            if (isCancelled()) throw DictationException("cancelled", "Recording cancelled.")
            val raw = transcript.toString().trim()
            if (raw.isEmpty()) throw DictationException("no_speech", "No speech detected. Record again.")
            if (config.semanticCleanup) drainCleanup(prioritizeRecognition = false)
            // Coordinator owns the one and only autocorrect pass for both engines.
            return TranscriptionResult(if (config.semanticCleanup) cleanupWindow.text() else raw, raw)
        } finally { dispose() }
    }

    fun measurements(): List<LocalChunkMeasurement> = synchronized(measurements) { measurements.toList() }
    fun cleanupMeasurements(): List<LocalCleanupMeasurement> = synchronized(cleanupMeasurements) { cleanupMeasurements.toList() }

    override fun cancel() { cancelled.set(true); dispose() }

    private fun dispose() {
        cancelled.set(true)
        synchronized(this) {
            if (!closed) { runCatching { writer.close() }; closed = true }
        }
        executor.shutdownNow()
        // A running decoder has already loaded its bounded chunk into memory.
        // No new task reads the spool after cancellation.
        spool.delete()
    }
}

/** Strict RIFF/WAVE PCM16 reader shared by retry and benchmark paths. */
class PcmWavReader(file: File) : java.io.Closeable {
    private val input = RandomAccessFile(file, "r")
    private var remaining = 0L
    val sampleCount: Long

    init {
        try {
            val header = ByteArray(12).also(input::readFully)
            require(String(header, 0, 4, Charsets.US_ASCII) == "RIFF" &&
                String(header, 8, 4, Charsets.US_ASCII) == "WAVE") { "Expected a WAV recording." }
            var formatValid = false
            var dataFound = false
            while (input.filePointer + 8 <= input.length()) {
                val tag = ByteArray(4).also(input::readFully).toString(Charsets.US_ASCII)
                val size = readUInt32()
                val start = input.filePointer
                require(size <= input.length() - start) { "Incomplete WAV recording." }
                if (tag == "fmt ") {
                    require(size >= 16)
                    val codec = readUInt16(); val channels = readUInt16(); val rate = readUInt32()
                    readUInt32(); val alignment = readUInt16(); val bits = readUInt16()
                    require((codec == 1 || codec == 0xfffe) && channels == 1 && rate == 16_000L && bits == 16 && alignment == 2) {
                        "Use 16 kHz mono PCM16 WAV recordings."
                    }
                    if (codec == 0xfffe) {
                        // WAVE_FORMAT_EXTENSIBLE wraps PCM with valid-bit count,
                        // speaker mask and a subtype GUID (e.g. macOS afconvert).
                        require(size >= 40) { "Incomplete extensible WAV format." }
                        val extensionSize = readUInt16()
                        require(extensionSize >= 22 && 18L + extensionSize <= size) {
                            "Incomplete extensible WAV format."
                        }
                        val validBits = readUInt16()
                        val channelMask = readUInt32()
                        val subtype = ByteArray(16).also(input::readFully)
                        val pcmSubtype = byteArrayOf(1, 0, 0, 0, 0, 0, 0x10, 0,
                            0x80.toByte(), 0, 0, 0xaa.toByte(), 0, 0x38, 0x9b.toByte(), 0x71)
                        require(validBits == 16 && subtype.contentEquals(pcmSubtype) &&
                            (channelMask == 0L || java.lang.Long.bitCount(channelMask) == 1)) {
                            "Use extensible mono PCM with 16 valid bits."
                        }
                    }
                    formatValid = true
                } else if (tag == "data") {
                    require(formatValid && size % 2 == 0L) { "Invalid WAV audio data." }
                    remaining = size; dataFound = true; break
                }
                input.seek(start + size + (size and 1L))
            }
            require(dataFound) { "WAV recording has no audio data." }
            sampleCount = remaining / 2
        } catch (error: Throwable) { input.close(); throw error }
    }

    fun readSamples(maximum: Int): ShortArray? {
        require(maximum > 0)
        if (remaining == 0L) return null
        val count = minOf(maximum.toLong(), remaining / 2).toInt()
        val bytes = ByteArray(count * 2).also(input::readFully)
        remaining -= bytes.size
        return ShortArray(count) { i ->
            ((bytes[i * 2].toInt() and 255) or (bytes[i * 2 + 1].toInt() shl 8)).toShort()
        }
    }

    private fun readUInt16(): Int = input.readUnsignedByte() or (input.readUnsignedByte() shl 8)
    private fun readUInt32(): Long = readUInt16().toLong() or (readUInt16().toLong() shl 16)
    override fun close() = input.close()
}
