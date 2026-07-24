package ai.voiceflow.voiceflow_mobile

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodChannel
import kotlin.concurrent.thread

class MainActivity : FlutterActivity() {

    private lateinit var channel: MethodChannel
    private val main = Handler(Looper.getMainLooper())
    private val capture = AudioCapture() // in-app notes Whisper recording

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)
        channel = MethodChannel(flutterEngine.dartExecutor.binaryMessenger, "voiceflow/overlay")
        channel.setMethodCallHandler { call, result ->
            when (call.method) {
                "hasOverlayPermission" ->
                    result.success(Settings.canDrawOverlays(this))

                "isAccessibilityEnabled" ->
                    result.success(isAccessibilityEnabled())

                "openOverlaySettings" -> {
                    startActivity(
                        Intent(
                            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            Uri.parse("package:$packageName")
                        )
                    )
                    result.success(null)
                }

                "openAccessibilitySettings" -> {
                    startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                    result.success(null)
                }

                "setBubbleEnabled" -> {
                    val enabled = call.argument<Boolean>("enabled") ?: false
                    savePref(OverlayService.KEY_LOCALE, call.argument<String>("locale") ?: "id-ID")
                    val svc = Intent(this, OverlayService::class.java)
                    if (enabled) {
                        svc.action = OverlayService.ACTION_START
                        startForegroundService(svc)
                    } else {
                        stopService(svc)
                    }
                    result.success(null)
                }

                "setLocale" ->
                    ok(result) { savePref(OverlayService.KEY_LOCALE, call.argument<String>("locale") ?: "id-ID") }

                // ----- Engine selection (shared with the bubble via prefs) -----

                "setEngine" ->
                    ok(result) { savePref(OverlayService.KEY_ENGINE, call.argument<String>("engine") ?: "whisper") }

                "setWhisperModel" ->
                    ok(result) { savePref(OverlayService.KEY_MODEL, call.argument<String>("model") ?: "small") }

                "whisperAvailable" ->
                    result.success(WhisperNative.available)

                "whisperModelDownloaded" ->
                    result.success(ModelStore.isDownloaded(this, call.argument<String>("model") ?: "small"))

                "downloadWhisperModel" -> {
                    val size = call.argument<String>("model") ?: "small"
                    thread {
                        try {
                            ModelStore.ensure(this, size) { p ->
                                main.post { channel.invokeMethod("onWhisperProgress", p) }
                            }
                            main.post { result.success(true) }
                        } catch (e: Exception) {
                            main.post { result.error("download_failed", e.message, null) }
                        }
                    }
                }

                // ----- In-app notes Whisper (record natively → transcribe) -----

                "whisperStartRecording" -> {
                    try {
                        capture.start()
                        result.success(true)
                    } catch (e: Exception) {
                        result.error("audio_failed", e.message, null)
                    }
                }

                "whisperStopAndTranscribe" -> {
                    val lang = call.argument<String>("lang")
                    val prompt = call.argument<String>("prompt")
                    val size = call.argument<String>("model") ?: "small"
                    val samples = capture.stop()
                    thread {
                        val text = try {
                            WhisperEngine.transcribe(this, size, samples, lang, prompt)
                        } catch (e: Exception) {
                            null
                        }
                        main.post { result.success(text) }
                    }
                }

                else -> result.notImplemented()
            }
        }
    }

    private inline fun ok(result: MethodChannel.Result, body: () -> Unit) {
        body(); result.success(null)
    }

    private fun savePref(key: String, value: String) {
        getSharedPreferences(OverlayService.PREFS, Context.MODE_PRIVATE)
            .edit().putString(key, value).apply()
    }

    private fun isAccessibilityEnabled(): Boolean {
        val expected = "$packageName/${InsertionService::class.java.name}"
        val enabled = Settings.Secure.getString(
            contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        return enabled.split(':').any { it.equals(expected, ignoreCase = true) }
    }
}
