package ai.voiceflow.voiceflow_mobile

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer

/**
 * Thin wrapper around the platform SpeechRecognizer for the off-app bubble.
 * Must be used from the main thread. One utterance per [start] call.
 */
class SpeechEngine(private val context: Context) {

    interface Listener {
        fun onFinal(text: String)
        fun onError(message: String)
        fun onListeningChanged(listening: Boolean)
    }

    private var recognizer: SpeechRecognizer? = null
    var listening = false
        private set

    fun isAvailable(): Boolean = SpeechRecognizer.isRecognitionAvailable(context)

    fun start(locale: String, listener: Listener) {
        if (listening) return
        if (!isAvailable()) {
            listener.onError("Speech recognition unavailable")
            return
        }
        val r = SpeechRecognizer.createSpeechRecognizer(context)
        recognizer = r
        r.setRecognitionListener(object : RecognitionListener {
            override fun onResults(results: Bundle) {
                finish()
                val text = results
                    .getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()
                    .orEmpty()
                if (text.isBlank()) listener.onError("Didn't catch that")
                else listener.onFinal(text)
            }

            override fun onError(error: Int) {
                finish()
                when (error) {
                    SpeechRecognizer.ERROR_NO_MATCH,
                    SpeechRecognizer.ERROR_SPEECH_TIMEOUT ->
                        listener.onError("Didn't catch that")
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS ->
                        listener.onError("Microphone permission needed")
                    else -> listener.onError("Speech error ($error)")
                }
            }

            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}

            private fun finish() {
                listening = false
                listener.onListeningChanged(false)
                destroy()
            }
        })

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(
                RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
            )
            if (locale.isNotEmpty()) {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, locale)
            }
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
        }
        listening = true
        listener.onListeningChanged(true)
        r.startListening(intent)
    }

    /** User tapped again — take whatever was heard so far. */
    fun stop() {
        recognizer?.stopListening()
    }

    fun cancelAndReset() {
        listening = false
        destroy()
    }

    private fun destroy() {
        recognizer?.destroy()
        recognizer = null
    }
}
