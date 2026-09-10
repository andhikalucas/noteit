package ai.voiceflow.voiceflow_mobile

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.ArrayDeque

class GeminiTranscriberTest {
    @get:Rule val temporary = TemporaryFolder()
    private val base = "https://generativelanguage.googleapis.com"
    private fun audio(): File = temporary.newFile("recording.wav").apply { writeBytes(ByteArray(100)) }
    private fun uploaded() = GeminiResponse(200, """{"file":{"name":"files/sample","uri":"$base/v1beta/files/sample","state":"ACTIVE"}}""")
    private fun started() = GeminiResponse(200, headers = mapOf("X-Goog-Upload-URL" to "$base/upload/session"))
    private fun result() = GeminiResponse(200, """{"status":"completed","steps":[{"type":"model_output","content":[{"type":"text","text":"Besok meeting sama Tiara."}]}]}""")

    @Test fun uploadsWavWithSmartVocabularyAndAutomaticLanguage() {
        val transport = FakeTransport(started(), uploaded(), result())
        val adapter = GeminiTranscriber("test-key", transport)
        val recording = audio()
        val result = adapter.transcribe(recording, DictationOptions(vocabulary = listOf(" Tiara ", "Tiara", "")), { false })
        assertEquals("Besok meeting sama Tiara.", result.text)
        assertNull(result.rawText)
        assertEquals(recording, transport.requests[1].file)
        val request = JSONObject(String(transport.requests[2].body!!))
        val config = request.getJSONObject("generation_config").getJSONObject("transcription_config")
        assertEquals("smart", config.getString("mode"))
        assertEquals("[\"Tiara\"]", config.getJSONArray("custom_vocabulary").toString())
        assertFalse(config.has("language_codes"))
        assertFalse(config.has("timestamp_granularities"))
        assertEquals("audio/wav", request.getJSONArray("input").getJSONObject(0).getString("mime_type"))
    }

    @Test fun retryReusesUploadAcrossAdapterInstancesAndReleaseDeletesRemote() {
        val recording = audio()
        val first = FakeTransport(started(), uploaded(), GeminiResponse(503, "secret-provider-detail"))
        assertFailure("provider_unavailable") { GeminiTranscriber("test-key", first).transcribe(recording, DictationOptions(), { false }) }
        val second = FakeTransport(GeminiResponse(200, """{"state":"ACTIVE"}"""), result(), GeminiResponse(200))
        val adapter = GeminiTranscriber("test-key", second)
        adapter.transcribe(recording, DictationOptions(), { false })
        adapter.release(recording)
        assertEquals(listOf("GET", "POST", "DELETE"), second.requests.map { it.method })
        assertTrue(recording.exists()) // Owner removes audio after durable delivery.
        assertFalse(File(recording.parentFile, "recording.wav.gemini.json").exists())
    }

    @Test fun expiredRemoteReferenceIsReuploaded() {
        val recording = audio()
        val first = FakeTransport(started(), uploaded(), result())
        GeminiTranscriber("test-key", first).transcribe(recording, DictationOptions(), { false })
        val retry = FakeTransport(GeminiResponse(404), started(), uploaded(), result())
        GeminiTranscriber("test-key", retry).transcribe(recording, DictationOptions(), { false })
        assertEquals(4, retry.requests.size)
    }

    @Test fun missingKeyAndCancellationNeverSendAudio() {
        val transport = FakeTransport()
        val recording = audio()
        assertFailure("missing_credentials") { GeminiTranscriber("", transport).transcribe(recording, DictationOptions(), { false }) }
        assertFailure("cancelled") { GeminiTranscriber("test-key", transport).transcribe(recording, DictationOptions(), { true }) }
        assertTrue(transport.requests.isEmpty())
    }

    @Test fun quotaAndEmptyResultsAreFailuresWithoutAutomaticRetry() {
        val recording = audio()
        val quota = FakeTransport(started(), uploaded(), GeminiResponse(429))
        assertFailure("quota") { GeminiTranscriber("test-key", quota).transcribe(recording, DictationOptions(), { false }) }
        assertEquals(3, quota.requests.size)
        val empty = FakeTransport(GeminiResponse(200, """{"state":"ACTIVE"}"""), GeminiResponse(200, """{"steps":[]}"""))
        assertFailure("empty_transcript") { GeminiTranscriber("test-key", empty).transcribe(recording, DictationOptions(), { false }) }
    }

    @Test fun releaseDoesNotPreventDiscardWhenOffline() {
        val recording = audio()
        val transport = FakeTransport(started(), uploaded(), result(), GeminiResponse(503))
        val adapter = GeminiTranscriber("test-key", transport)
        adapter.transcribe(recording, DictationOptions(), { false })
        adapter.release(recording)
        assertFalse(File(recording.parentFile, "recording.wav.gemini.json").exists())
    }

    @Test fun parsingIgnoresInputAndThoughtsAndSupportsOutputs() {
        val response = JSONObject("""{"steps":[{"type":"user_input","content":[{"type":"text","text":"not output"}]},{"type":"model_output","content":[{"type":"text","text":"hidden","thought":true},{"type":"text","text":"First."},{"type":"text","text":"Second."}]}]}""")
        assertEquals("First.\nSecond.", GeminiTranscriber.parseText(response))
        assertEquals("Hello", GeminiTranscriber.parseText(JSONObject("""{"outputs":[{"type":"text","text":"Hello"}]}""")))
    }

    @Test fun untrustedUploadAddressIsRejectedBeforeSendingRecording() {
        val transport = FakeTransport(GeminiResponse(200, headers = mapOf("x-goog-upload-url" to "https://example.com/upload")))
        assertFailure("provider_response") { GeminiTranscriber("test-key", transport).transcribe(audio(), DictationOptions(), { false }) }
        assertEquals(1, transport.requests.size)
    }

    @Test fun invalidKeyReasonIsRecognizedEvenWithHttp400() {
        val transport = FakeTransport(GeminiResponse(400, """{"error":{"details":[{"reason":"API_KEY_INVALID"}]}}"""))
        assertFailure("authentication") { GeminiTranscriber("test-key", transport).transcribe(audio(), DictationOptions(), { false }) }
    }

    @Test fun partialFailedResponseDoesNotBecomeDeliveredText() {
        val transport = FakeTransport(started(), uploaded(), GeminiResponse(200, """{"status":"failed","output_text":"Partial"}"""))
        assertFailure("provider_response") { GeminiTranscriber("test-key", transport).transcribe(audio(), DictationOptions(), { false }) }
    }

    @Test fun changedRecordingNeverReusesOldAudioReference() {
        val recording = audio()
        GeminiTranscriber("test-key", FakeTransport(started(), uploaded(), result()))
            .transcribe(recording, DictationOptions(), { false })
        recording.appendBytes(byteArrayOf(1, 2))
        val replacement = FakeTransport(started(), uploaded(), result())
        GeminiTranscriber("test-key", replacement).transcribe(recording, DictationOptions(), { false })
        assertTrue(replacement.requests.first().url.endsWith("/upload/v1beta/files"))
        assertEquals(recording.length().toString(), replacement.requests.first().headers["X-Goog-Upload-Header-Content-Length"])
    }

    @Test fun vocabularyLimitIsValidatedBeforeUpload() {
        val transport = FakeTransport()
        val options = DictationOptions(vocabulary = (1..1001).map { "term$it" })
        assertFailure("vocabulary_limit") { GeminiTranscriber("test-key", transport).transcribe(audio(), options, { false }) }
        assertTrue(transport.requests.isEmpty())
    }

    private fun assertFailure(code: String, block: () -> Unit) {
        try { block(); fail("Expected $code") } catch (e: DictationException) {
            assertEquals(code, e.code)
            assertFalse(e.message.contains("secret-provider-detail"))
        }
    }

    private class FakeTransport(vararg responses: GeminiResponse) : GeminiTransport {
        val requests = mutableListOf<GeminiRequest>()
        val queue = ArrayDeque(responses.toList())
        override fun execute(request: GeminiRequest, cancelled: () -> Boolean): GeminiResponse {
            requests.add(request)
            return queue.removeFirst()
        }
    }
}
