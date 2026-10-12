

package ai.voiceflow.voiceflow_mobile

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/** One after-stop request, with the uploaded audio retained for an explicit retry. */
class GeminiTranscriber internal constructor(
    private val apiKey: String,
    private val transport: GeminiTransport,
    private val now: () -> Long = System::currentTimeMillis,
) : Transcriber {
    override fun transcribe(audio: File, options: DictationOptions, cancelled: () -> Boolean): TranscriptionResult {
        checkCancelled(cancelled)
        if (apiKey.isBlank()) throw DictationException("missing_credentials", "Add your Gemini key and rebuild the app.")
        if (!audio.isFile || audio.length() <= 44) throw DictationException("empty_audio", "No recording to transcribe.")
        val vocabulary = options.vocabulary.map(String::trim).filter(String::isNotEmpty).distinct()
        if (vocabulary.size > 1000) throw DictationException("vocabulary_limit", "Keep vocabulary below 1,001 entries.")
        try {
            val file = findUpload(audio, cancelled) ?: upload(audio, cancelled)
            checkCancelled(cancelled)
            val config = JSONObject().put("mode", "smart")
            if (vocabulary.isNotEmpty()) config.put("custom_vocabulary", JSONArray(vocabulary))
            val body = JSONObject()
                .put("model", "gemini-3.5-transcribe")
                .put("input", JSONArray().put(JSONObject().put("type", "audio")
                    .put("uri", file.uri).put("mime_type", "audio/wav")))
                .put("generation_config", JSONObject().put("transcription_config", config))
            val response = send("POST", "$BASE/v1beta/interactions", cancelled, json = body)
            val output = jsonResponse(response)
            if (output.optString("status").let { it.isNotEmpty() && it != "completed" }) {
                throw DictationException("provider_response", "Gemini did not finish the transcript. Retry or use local.")
            }
            val text = parseText(output)
            checkCancelled(cancelled)
            if (text.isBlank()) throw DictationException("empty_transcript", "No transcript returned. Retry or use local.")
            return TranscriptionResult(text = text)
        } catch (e: DictationException) {
            throw e
        } catch (_: SocketTimeoutException) {
            checkCancelled(cancelled)
            throw DictationException("network_timeout", "Gemini timed out. Retry or use local.")
        } catch (_: IOException) {
            checkCancelled(cancelled)
            throw DictationException("network", "Could not reach Gemini. Retry or use local.")
        } catch (_: org.json.JSONException) {
            throw DictationException("provider_response", "Gemini returned an unreadable response. Retry or use local.")
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            throw DictationException("cancelled", "Transcription cancelled.")
        }
    }

    /** Call off the UI thread after durable delivery or discard, even for a local retry. */
    fun release(audio: File) {
        val metadata = metadataFile(audio)
        val upload = readUpload(audio)
        try {
            if (upload != null && apiKey.isNotBlank()) {
                send("DELETE", "$BASE/v1beta/${upload.name}", { false })
            }
        } catch (_: Exception) {
            // Files API expires the upload after 48 hours if deletion cannot reach it.
        } finally {
            metadata.delete()
        }
    }

    private fun findUpload(audio: File, cancelled: () -> Boolean): UploadedAudio? {
        val previous = readUpload(audio) ?: return null
        if (now() - previous.createdAt >= UPLOAD_TTL_MS || previous.size != audio.length() ||
            previous.modifiedAt != audio.lastModified()) return null
        val response = transport.execute(GeminiRequest("GET", "$BASE/v1beta/${previous.name}", auth()), cancelled)
        if (response.status == 404 || response.status == 410) return null
        checkResponse(response)
        val state = jsonResponse(response).optString("state")
        if (state == "FAILED") return null
        awaitReady(previous, state, cancelled)
        return previous
    }

    private fun upload(audio: File, cancelled: () -> Boolean): UploadedAudio {
        val headers = auth() + mapOf(
            "Content-Type" to "application/json",
            "X-Goog-Upload-Protocol" to "resumable",
            "X-Goog-Upload-Command" to "start",
            "X-Goog-Upload-Header-Content-Length" to audio.length().toString(),
            "X-Goog-Upload-Header-Content-Type" to "audio/wav",
        )
        val start = transport.execute(GeminiRequest("POST", "$BASE/upload/v1beta/files", headers,
            JSONObject().put("file", JSONObject().put("display_name", "dictation")).toString().toByteArray()), cancelled)
        checkResponse(start)
        val uploadUrl = start.headers.entries.firstOrNull { it.key.equals("x-goog-upload-url", true) }?.value
            ?: throw DictationException("provider_response", "Gemini did not accept the recording upload.")
        val url = URL(uploadUrl)
        if (url.protocol != "https" || !(url.host == "generativelanguage.googleapis.com" || url.host.endsWith(".googleapis.com"))) {
            throw DictationException("provider_response", "Gemini returned an invalid upload address.")
        }
        val response = transport.execute(GeminiRequest("POST", uploadUrl, mapOf(
            "Content-Type" to "audio/wav", "X-Goog-Upload-Offset" to "0",
            "X-Goog-Upload-Command" to "upload, finalize",
        ), file = audio), cancelled)
        checkResponse(response)
        val file = jsonResponse(response).getJSONObject("file")
        val name = file.getString("name")
        val uri = file.getString("uri")
        if (!validName(name) || !uri.startsWith("$BASE/")) {
            throw DictationException("provider_response", "Gemini returned an invalid file reference.")
        }
        val uploaded = UploadedAudio(name, uri, now(), audio.length(), audio.lastModified())
        // Store before checking cancellation so discard can still release a completed upload.
        metadataFile(audio).writeText(JSONObject().put("name", name).put("uri", uri)
            .put("createdAt", uploaded.createdAt).put("size", uploaded.size)
            .put("modifiedAt", uploaded.modifiedAt).toString())
        awaitReady(uploaded, file.optString("state"), cancelled)
        return uploaded
    }

    private fun awaitReady(upload: UploadedAudio, initialState: String, cancelled: () -> Boolean) {
        var state = initialState
        var attempts = 0
        while (state == "PROCESSING" && attempts++ < 60) {
            // Poll quickly at first (short clips finish fast), then back off to 1 s.
            val waitMs = if (attempts <= 4) 250 else 1000
            repeat(waitMs / 50) { checkCancelled(cancelled); Thread.sleep(50) }
            val response = send("GET", "$BASE/v1beta/${upload.name}", cancelled)
            state = jsonResponse(response).optString("state")
        }
        checkCancelled(cancelled)
        if (state == "FAILED") throw DictationException("upload_failed", "Gemini could not read the recording.")
        if (state == "PROCESSING") throw DictationException("upload_pending", "Gemini is still preparing the recording. Retry shortly.")
    }

    private fun readUpload(audio: File): UploadedAudio? = try {
        val data = JSONObject(metadataFile(audio).readText())
        val name = data.getString("name")
        val uri = data.getString("uri")
        if (!validName(name) || !uri.startsWith("$BASE/")) null else UploadedAudio(
            name, uri, data.getLong("createdAt"), data.getLong("size"), data.getLong("modifiedAt"))
    } catch (_: Exception) { null }

    private fun metadataFile(audio: File) = File(audio.parentFile, "${audio.name}.gemini.json")
    private fun auth() = mapOf("x-goog-api-key" to apiKey)
    private fun send(method: String, url: String, cancelled: () -> Boolean, json: JSONObject? = null): GeminiResponse {
        checkCancelled(cancelled)
        return transport.execute(GeminiRequest(method, url,
            auth() + if (json != null) mapOf("Content-Type" to "application/json") else emptyMap(),
            json?.toString()?.toByteArray()), cancelled).also(::checkResponse)
    }

    private fun checkResponse(response: GeminiResponse) {
        if (response.status in 200..299) return
        // Never copy provider payloads into errors: they can contain recording text or credentials.
        val error = runCatching { JSONObject(response.body).optJSONObject("error") }.getOrNull()
        val reasons = error?.optJSONArray("details")
        val invalidKey = error?.optString("status") == "UNAUTHENTICATED" ||
            (0 until (reasons?.length() ?: 0)).any {
                reasons?.optJSONObject(it)?.optString("reason") in listOf("API_KEY_INVALID", "API_KEY_EXPIRED")
            }
        if (invalidKey) throw DictationException("authentication", "Gemini rejected the key. Check access and rebuild.")
        throw when (response.status) {
            401, 403 -> DictationException("authentication", "Gemini rejected the key. Check access and rebuild.")
            429 -> DictationException("quota", "Gemini limit reached. Retry later or use local.")
            400, 422 -> DictationException("provider_request", "Gemini rejected the transcription request.")
            else -> DictationException("provider_unavailable", "Gemini is unavailable. Retry or use local.")
        }
    }

    private fun jsonResponse(response: GeminiResponse) = JSONObject(response.body)
    private fun validName(name: String) = name.matches(VALID_NAME)
    private data class UploadedAudio(val name: String, val uri: String, val createdAt: Long, val size: Long, val modifiedAt: Long)

    companion object {
        private const val BASE = "https://generativelanguage.googleapis.com"
        private val VALID_NAME = Regex("files/[A-Za-z0-9_-]+")
        private const val UPLOAD_TTL_MS = 47L * 60 * 60 * 1000

        internal fun parseText(json: JSONObject): String {
            val direct = json.optString("output_text")
            if (direct.isNotBlank() && direct != "null") return direct.trim()
            val parts = mutableListOf<String>()
            val steps = json.optJSONArray("steps")
            if (steps != null) for (i in 0 until steps.length()) {
                val step = steps.optJSONObject(i) ?: continue
                if (step.optString("type") == "model_output") appendText(step.optJSONArray("content"), parts)
            }
            if (parts.isEmpty()) appendText(json.optJSONArray("outputs"), parts)
            return parts.joinToString("\n").trim()
        }

        private fun appendText(content: JSONArray?, parts: MutableList<String>) {
            if (content == null) return
            for (i in 0 until content.length()) {
                val part = content.optJSONObject(i) ?: continue
                if (part.optString("type") == "text" && !part.optBoolean("thought", false)) {
                    val value = part.optString("text")
                    if (value.isNotBlank() && value != "null") parts.add(value)
                }
            }
        }
    }
}

internal data class GeminiRequest(val method: String, val url: String, val headers: Map<String, String>,
    val body: ByteArray? = null, val file: File? = null)
internal data class GeminiResponse(val status: Int, val body: String = "", val headers: Map<String, String> = emptyMap())
internal fun interface GeminiTransport {
    fun execute(request: GeminiRequest, cancelled: () -> Boolean): GeminiResponse
}

/** Streams file bytes and bounds response memory. A watcher disconnects blocked IO on cancellation. */
internal class UrlConnectionGeminiTransport : GeminiTransport {
    override fun execute(request: GeminiRequest, cancelled: () -> Boolean): GeminiResponse {
        checkCancelled(cancelled)
        val connection = URL(request.url).openConnection() as HttpURLConnection
        val finished = AtomicBoolean(false)
        var clean = false
        val watcher = thread(name = "gemini-cancel", isDaemon = true) {
            while (!finished.get()) {
                if (cancelled()) { connection.disconnect(); return@thread }
                try { Thread.sleep(100) } catch (_: InterruptedException) { return@thread }
            }
        }
        try {
            connection.requestMethod = request.method
            connection.connectTimeout = if (request.method == "DELETE") 5_000 else 20_000
            connection.readTimeout = if (request.method == "DELETE") 5_000 else 120_000
            connection.instanceFollowRedirects = false
            request.headers.forEach { (name, value) -> connection.setRequestProperty(name, value) }
            if (request.body != null || request.file != null) {
                connection.doOutput = true
                connection.setFixedLengthStreamingMode(request.file?.length() ?: request.body!!.size.toLong())
                connection.outputStream.use { output ->
                    if (request.file != null) request.file.inputStream().use { input ->
                        val buffer = ByteArray(32 * 1024)
                        while (true) {
                            checkCancelled(cancelled)
                            val count = input.read(buffer)
                            if (count < 0) break
                            output.write(buffer, 0, count)
                        }
                    } else output.write(request.body!!)
                }
            }
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val bytes = stream?.use { input ->
                val output = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    checkCancelled(cancelled)
                    val count = input.read(buffer)
                    if (count < 0) break
                    if (output.size() + count > 4 * 1024 * 1024) throw IOException("Response too large")
                    output.write(buffer, 0, count)
                }
                output.toByteArray()
            }
            checkCancelled(cancelled)
            val headers = connection.headerFields.filterKeys { it != null }.mapValues { it.value.firstOrNull().orEmpty() }
            val response = GeminiResponse(status, bytes?.toString(Charsets.UTF_8).orEmpty(), headers)
            clean = true
            return response
        } finally {
            finished.set(true)
            watcher.interrupt()
            // Streams are drained and closed above; leaving the socket connected lets
            // HTTP keep-alive reuse it. Disconnect only after cancel or failure.
            if (!clean) connection.disconnect()
        }
    }
}

private fun checkCancelled(cancelled: () -> Boolean) {
    if (cancelled() || Thread.currentThread().isInterrupted) throw DictationException("cancelled", "Transcription cancelled.")
}
