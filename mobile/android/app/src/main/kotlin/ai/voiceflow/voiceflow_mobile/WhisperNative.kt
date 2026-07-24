package ai.voiceflow.voiceflow_mobile

/**
 * Thin JNI binding over libwhisper (whisper.cpp), loaded from the app's own
 * native build. Shared by the off-app bubble (direct) and in-app notes (via
 * MethodChannel). Not thread-safe — callers serialize on WhisperEngine.
 */
object WhisperNative {
    @Volatile
    var available: Boolean = false
        private set

    init {
        available = try {
            System.loadLibrary("noteit_whisper")
            true
        } catch (t: Throwable) {
            false
        }
    }

    /** Loads a GGML model file; returns an opaque context pointer, 0 on failure. */
    external fun initContext(modelPath: String): Long

    external fun freeContext(ptr: Long)

    /**
     * Transcribes 16 kHz mono float samples. [lang] is a BCP-47-ish code
     * ("id", "en") or null/"auto" for detection; [prompt] biases decoding.
     * Returns the concatenated text, or null on failure.
     */
    external fun fullTranscribe(
        ptr: Long,
        threads: Int,
        audio: FloatArray,
        lang: String?,
        prompt: String?,
    ): String?
}
