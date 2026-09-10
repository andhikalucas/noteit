package ai.voiceflow.voiceflow_mobile

import android.content.Context
import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** Owns recording and delivery independently of a Flutter screen or overlay view. */
class DictationCoordinator private constructor(context: Context) {
    companion object {
        @Volatile private var instance: DictationCoordinator? = null
        fun get(context: Context): DictationCoordinator = instance ?: synchronized(this) {
            instance ?: DictationCoordinator(context.applicationContext).also { instance = it }
        }
    }

    private class Session(
        val id: String, val source: String, var options: DictationOptions,
        val audio: File, val createdAt: Long = System.currentTimeMillis(),
    ) {
        @Volatile var state = "recording"
        @Volatile var text: String? = null
        @Volatile var rawText: String? = null
        var externalDeliveryAttempted = false
        @Volatile var errorCode: String? = null
        @Volatile var error: String? = null
        val cancelled = AtomicBoolean(false)
        @Volatile var pipeline: LocalAudioSession? = null
        var revision = 0L
        fun snapshot(): Map<String, Any?> = mapOf("id" to id, "source" to source,
            "engine" to options.engine, "state" to state, "text" to text,
            "rawText" to rawText, "errorCode" to errorCode, "error" to error,
            "createdAt" to createdAt, "externalDeliveryAttempted" to externalDeliveryAttempted)
    }

    private val app = context.applicationContext
    private val folder = File(app.filesDir, "dictation_sessions").apply { mkdirs() }
    private val sessions = ConcurrentHashMap<String, Session>()
    private val listeners = CopyOnWriteArraySet<(Map<String, Any?>) -> Unit>()
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "noteit-sessions") }
    private val cleanupWorker = Executors.newSingleThreadExecutor { r -> Thread(r, "noteit-cleanup") }
    private val capture = AudioCapture()
    private val cloud = GeminiTranscriber(app)
    private val local = LocalTranscriber(app)
    @Volatile private var active: String? = null
    @Volatile private var benchmarking = false

    init { restore() }

    fun addListener(listener: (Map<String, Any?>) -> Unit) { listeners.add(listener) }
    fun removeListener(listener: (Map<String, Any?>) -> Unit) { listeners.remove(listener) }
    fun isBusy(): Boolean = active != null || benchmarking
    @Synchronized
    fun beginBenchmark() {
        if (isBusy()) throw DictationException("busy", "Finish dictation before running a benchmark.")
        benchmarking = true
    }
    @Synchronized
    fun endBenchmark() { benchmarking = false }
    @Synchronized
    fun pending(source: String? = null): List<Map<String, Any?>> = sessions.values
        .filter { source == null || it.source == source }.sortedBy { it.createdAt }.map { it.snapshot() }

    @Synchronized
    fun start(source: String): Map<String, Any?> {
        if (isBusy()) throw DictationException("busy", "Finish the current recording or benchmark first.")
        if (source != "bubble" && !source.matches(Regex("note:[0-9]+")))
            throw DictationException("source", "Open a note before recording.")
        if (sessions.values.any { it.source == source })
            throw DictationException("pending", "Retry or discard the previous recording first.")
        val options = DictationSettings.load(app)
        validate(options)
        val id = UUID.randomUUID().toString()
        val s = Session(id, source, options, File(folder, "$id.wav"))
        sessions[id] = s
        active = id
        try {
            if (options.engine == "local") s.pipeline = local.startSession(options) { s.cancelled.get() }
            persist(s)
            capture.start(s.audio, onSamples = { s.pipeline?.accept(it) }, onFailure = {
                main.post { stop(id) }
            })
            notify(s)
            return s.snapshot()
        } catch (e: Exception) {
            s.cancelled.set(true)
            runCatching { s.pipeline?.cancel() }
            sessions.remove(id, s)
            // Audio may already have started. Keep the active slot until its worker exits.
            worker.execute {
                capture.abort()
                synchronized(this) { if (active == id) active = null }
                s.audio.delete(); metadata(s).delete()
            }
            throw if (e is DictationException) e else DictationException("microphone", "Microphone unavailable.")
        }
    }

    @Synchronized
    fun stop(id: String) {
        val s = sessions[id] ?: return
        if (s.state != "recording" || active != id) return
        s.state = "processing"
        val saved = publish(s)
        worker.execute {
            try {
                capture.stop()
                if (!saved) throw DictationException("storage", "Could not save recording state. Retry the saved audio.")
                process(s, streaming = true)
            } catch (e: Exception) { fail(s, e) }
            finally { synchronized(this) { if (active == id) active = null } }
        }
    }

    @Synchronized
    fun retry(id: String, engine: String?) {
        val s = sessions[id] ?: throw DictationException("missing", "The recording is no longer available.")
        if (isBusy() || s.state != "failed") throw DictationException("busy", "Finish the current recording or benchmark first.")
        val options = s.options.copy(engine = if (engine == "local") "local" else s.options.engine)
        validate(options)
        s.options = options
        s.cancelled.set(false)
        s.error = null; s.errorCode = null; s.state = "processing"
        active = id
        if (!publish(s)) { active = null; return }
        worker.execute {
            try { process(s, streaming = false) } catch (e: Exception) { fail(s, e) }
            finally { synchronized(this) { if (active == id) active = null } }
        }
    }

    /** Cancelling is explicit discard; completed delivery is acknowledged separately. */
    @Synchronized
    fun discard(id: String) {
        val s = sessions[id] ?: return
        s.cancelled.set(true)
        runCatching { s.pipeline?.cancel() }
        s.state = "cancelled"
        publish(s)
        worker.execute {
            if (active == id) capture.abort()
            synchronized(this) { if (active == id) active = null }
            synchronized(this) { remove(s, "cancelled") }
        }
    }

    @Synchronized
    fun acknowledge(id: String) {
        val s = sessions[id] ?: return
        if (s.state == "completed") remove(s, "delivered")
    }

    /** Journal before an external write: after a crash, require explicit copy rather than reinsert. */
    @Synchronized
    fun beginExternalDelivery(id: String): Boolean {
        val s = sessions[id] ?: return false
        if (s.source != "bubble" || s.state != "completed" || s.externalDeliveryAttempted) return false
        s.externalDeliveryAttempted = true
        try { persist(s) } catch (e: Exception) {
            s.externalDeliveryAttempted = false
            throw DictationException("storage", "Could not save delivery state. Copy or clear the transcript.")
        }
        return true
    }

    private fun validate(options: DictationOptions) {
        if (options.engine == "gemini" && BuildConfig.GEMINI_API_KEY.isBlank())
            throw DictationException("configuration", "Add the Gemini key and rebuild the app.")
        if (options.engine == "local") {
            if (!WhisperEngine.available) throw DictationException("unavailable", "Local transcription is unavailable.")
            if (!ModelStore.isDownloaded(app, "small")) throw DictationException("model_missing", "Download the local model in Settings.")
        }
    }

    private fun process(s: Session, streaming: Boolean) {
        if (s.cancelled.get()) return
        if (s.audio.length() <= 44) throw DictationException("empty_audio", "No audio was recorded. Discard and try again.")
        if (!streaming) DictationWav.repair(s.audio)
        val result = if (s.options.engine == "local") {
            if (streaming && s.pipeline != null) s.pipeline!!.finish(s.audio)
            else local.transcribe(s.audio, s.options) { s.cancelled.get() }
        } else cloud.transcribe(s.audio, s.options) { s.cancelled.get() }
        if (s.cancelled.get()) return
        val text = PersonalAutocorrect.apply(result.text.trim(), s.options.autocorrects)
        if (text.isBlank()) throw DictationException("no_speech", "No speech was detected. Retry or discard the recording.")
        synchronized(this) {
            if (s.cancelled.get() || sessions[s.id] !== s) return
            s.text = text; s.rawText = result.rawText; s.state = "completed"
            s.pipeline = null
            publish(s)
        }
    }

    @Synchronized
    private fun fail(s: Session, exception: Exception) {
        runCatching { s.pipeline?.cancel() }; s.pipeline = null
        if (s.cancelled.get() || sessions[s.id] !== s) return
        val error = exception as? DictationException
        s.errorCode = error?.code ?: "transcription"
        s.error = error?.message ?: "Transcription failed. Retry the saved recording."
        s.state = "failed"
        publish(s)
    }

    private fun metadata(s: Session) = File(folder, "${s.id}.json")
    @Synchronized
    private fun persist(s: Session) {
        val json = JSONObject(s.snapshot()).put("options", DictationSettings.encode(s.options))
        DictationJournal.write(metadata(s), json.toString())
    }

    /** Failed persistence must never prevent a microphone stop or cancellation. */
    @Synchronized
    private fun publish(s: Session): Boolean {
        if (sessions[s.id] !== s) return false
        val saved = try { persist(s); true } catch (_: Exception) {
            if (s.state != "cancelled") {
                s.state = "failed"
                s.errorCode = "storage"
                s.error = "Could not save recording state. Retry or discard the recording."
            }
            false
        }
        notify(s)
        return saved
    }

    @Synchronized
    private fun notify(s: Session) {
        val revision = ++s.revision
        val snapshot = s.snapshot()
        main.post {
            val current = synchronized(this) {
                snapshot["state"] == "cancelled" || (sessions[s.id] === s && s.revision == revision)
            }
            if (current) listeners.forEach { listener -> runCatching { listener(snapshot) } }
        }
    }

    @Synchronized
    private fun remove(s: Session, terminalState: String) {
        if (sessions[s.id] !== s) return
        val previous = s.state
        s.state = terminalState
        try { persist(s) } catch (e: Exception) {
            // Deleting metadata also prevents re-delivery when the disk is full.
            if (metadata(s).exists() && !metadata(s).delete()) {
                s.state = previous
                throw DictationException("storage", "Could not clear the saved recording. Try again.")
            }
        }
        if (!sessions.remove(s.id, s)) return
        s.revision++
        metadata(s).delete()
        cleanupWorker.execute { runCatching { cloud.release(s.audio) }; s.audio.delete() }
    }

    private fun restore() {
        folder.listFiles { f -> f.extension == "json" }?.forEach { file ->
            runCatching {
                val json = JSONObject(file.readText())
                val id = json.getString("id")
                if (file.name != "$id.json") return@runCatching
                val s = Session(id, json.getString("source"), DictationSettings.decode(json.getJSONObject("options")),
                    File(folder, "$id.wav"), json.optLong("createdAt"))
                val previous = json.optString("state")
                if (previous == "cancelled" || previous == "delivered") {
                    sessions[id] = s
                    remove(s, previous)
                    return@runCatching
                }
                s.state = if (previous == "completed") "completed" else "failed"
                s.text = json.optString("text").takeUnless { it.isBlank() || it == "null" }
                s.rawText = json.optString("rawText").takeUnless { it.isBlank() || it == "null" }
                s.externalDeliveryAttempted = json.optBoolean("externalDeliveryAttempted", false)
                if (s.state != "completed") {
                    s.errorCode = "interrupted"
                    s.error = "Recording interrupted. Retry the saved audio or discard it."
                    runCatching { DictationWav.repair(s.audio) }
                }
                sessions[id] = s
            }
        }
    }
}
