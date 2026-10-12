package ai.voiceflow.voiceflow_mobile

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/** Streams PCM to a recoverable WAV file; memory does not grow with duration. */
class AudioCapture {
    companion object { const val SAMPLE_RATE = 16_000 }
    private val recording = AtomicBoolean(false)
    private var worker: Thread? = null
    private var recorder: AudioRecord? = null
    @Volatile private var failure: Exception? = null

    @SuppressLint("MissingPermission")
    @Synchronized
    fun start(file: File, onSamples: ((ShortArray) -> Unit)? = null, onFailure: () -> Unit = {}) {
        check(worker == null) { "Recording is already active" }
        val min = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT)
        if (min <= 0) throw DictationException("microphone", "Microphone unavailable.")
        val audio = AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(min * 2, 8192))
        if (audio.state != AudioRecord.STATE_INITIALIZED) {
            audio.release()
            throw DictationException("microphone", "Microphone unavailable.")
        }
        val output = try { RandomAccessFile(file, "rw") }
        catch (e: Exception) { audio.release(); throw e }
        try {
            output.setLength(0)
            DictationWav.writeHeader(output, 0)
            audio.startRecording()
            if (audio.recordingState != AudioRecord.RECORDSTATE_RECORDING) error("Microphone did not start")
        } catch (e: Exception) { output.close(); audio.release(); throw e }
        recorder = audio
        failure = null
        recording.set(true)
        worker = thread(name = "noteit-recording") {
            var bytes = 0L
            try {
                val buffer = ShortArray(1600)
                val encoded = ByteArray(buffer.size * 2)
                while (recording.get()) {
                    val count = audio.read(buffer, 0, buffer.size)
                    if (count < 0 && recording.get()) error("Audio read failed ($count)")
                    if (count <= 0) continue
                    for (i in 0 until count) {
                        encoded[i * 2] = (buffer[i].toInt() and 255).toByte()
                        encoded[i * 2 + 1] = (buffer[i].toInt() shr 8).toByte()
                    }
                    output.write(encoded, 0, count * 2)
                    bytes += count * 2
                    // Copy only when someone consumes samples (local pipeline), not for Gemini.
                    onSamples?.invoke(buffer.copyOf(count))
                }
            } catch (e: Exception) {
                failure = e
            } finally {
                recording.set(false)
                runCatching { audio.stop() }
                audio.release()
                try { DictationWav.writeHeader(output, bytes) } catch (e: Exception) { failure = e }
                runCatching { output.close() }
                if (failure != null) onFailure()
            }
        }
    }

    /** Call off the main thread; waits for the final PCM block and WAV header. */
    @Synchronized
    fun stop() {
        recording.set(false)
        runCatching { recorder?.stop() }
        worker?.join()
        worker = null
        recorder = null
        failure?.let { throw DictationException("recording", "Recording was interrupted. Retry the saved audio.") }
    }

    fun abort() { runCatching { stop() } }

}
