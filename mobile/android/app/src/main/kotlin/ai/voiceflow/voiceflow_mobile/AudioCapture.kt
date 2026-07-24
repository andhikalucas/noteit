package ai.voiceflow.voiceflow_mobile

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import kotlin.concurrent.thread

/**
 * Records 16 kHz mono PCM from the mic and accumulates it as normalized float
 * samples for whisper.cpp. Push-to-talk style: [start] then [stop] returns the
 * captured buffer. Requires RECORD_AUDIO permission (checked by the caller).
 */
class AudioCapture {
    companion object {
        const val SAMPLE_RATE = 16_000
    }

    @Volatile
    private var recording = false
    private var worker: Thread? = null
    private val samples = ArrayList<Float>(SAMPLE_RATE * 8)

    val isRecording: Boolean get() = recording

    @SuppressLint("MissingPermission")
    fun start() {
        if (recording) return
        samples.clear()
        val minBuf = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        ).coerceAtLeast(SAMPLE_RATE) // ~0.5s headroom

        val record = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            minBuf * 2,
        )
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            throw IllegalStateException("AudioRecord failed to initialize")
        }

        recording = true
        record.startRecording()
        worker = thread(name = "noteit-audio") {
            val buf = ShortArray(minBuf)
            while (recording) {
                val n = record.read(buf, 0, buf.size)
                for (i in 0 until n) {
                    // PCM16 → [-1, 1] float.
                    samples.add(buf[i] / 32768.0f)
                }
            }
            record.stop()
            record.release()
        }
    }

    /** Stops recording and returns the captured samples (16 kHz mono float). */
    fun stop(): FloatArray {
        if (!recording) return FloatArray(0)
        recording = false
        worker?.join(2_000)
        worker = null
        return samples.toFloatArray()
    }

    fun abort() {
        recording = false
        worker?.join(2_000)
        worker = null
        samples.clear()
    }
}
