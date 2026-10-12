package ai.voiceflow.voiceflow_mobile

import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.WindowManager
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodChannel
import org.json.JSONObject
import java.io.File
import kotlin.concurrent.thread

class MainActivity : FlutterActivity() {
    private lateinit var channel: MethodChannel
    private lateinit var coordinator: DictationCoordinator
    private val main = Handler(Looper.getMainLooper())
    private val listener: (Map<String, Any?>) -> Unit = { channel.invokeMethod("onSession", it) }

    override fun onStart() {
        super.onStart()
        if (BuildConfig.DEBUG && BuildConfig.BENCHMARK_BUILD) LocalBenchmark.onForegroundStarted()
    }

    override fun onStop() {
        if (BuildConfig.DEBUG && BuildConfig.BENCHMARK_BUILD) LocalBenchmark.onForegroundStopped()
        super.onStop()
    }

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)
        // Automated replay must stay visible between runs. Scope this to the
        // isolated debug app; never change the owner's system sleep settings.
        if (BuildConfig.DEBUG && BuildConfig.BENCHMARK_BUILD) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        coordinator = DictationCoordinator.get(this)
        channel = MethodChannel(flutterEngine.dartExecutor.binaryMessenger, "voiceflow/overlay")
        coordinator.addListener(listener)
        channel.setMethodCallHandler { call, result ->
            try {
                when (call.method) {
                    "hasOverlayPermission" -> result.success(Settings.canDrawOverlays(this))
                    "isAccessibilityEnabled" -> result.success(isAccessibilityEnabled())
                    "isBubbleRunning" -> result.success(OverlayService.running)
                    "openOverlaySettings" -> {
                        startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
                        result.success(null)
                    }
                    "openAccessibilitySettings" -> {
                        startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)); result.success(null)
                    }
                    "setBubbleEnabled" -> {
                        val enabled = call.argument<Boolean>("enabled") ?: false
                        if (enabled) {
                            if (!Settings.canDrawOverlays(this)) throw DictationException("permission", "Allow display over other apps first.")
                            val service = Intent(this, OverlayService::class.java).setAction(OverlayService.ACTION_START)
                            if (android.os.Build.VERSION.SDK_INT >= 26) startForegroundService(service) else startService(service)
                        } else stopService(Intent(this, OverlayService::class.java))
                        result.success(null)
                    }
                    "syncSettings" -> {
                        DictationSettings.save(this, call.arguments as? Map<*, *> ?: emptyMap<Any, Any>())
                        result.success(null)
                    }
                    "geminiConfigured" -> result.success(BuildConfig.GEMINI_API_KEY.isNotBlank())
                    "localModelStatus" -> result.success(mapOf("downloaded" to ModelStore.isDownloaded(this, LocalCandidatePolicy.model),
                        "label" to "Local model", "approxMb" to if (LocalCandidatePolicy.model == "medium-q8") 1851 else if (LocalCandidatePolicy.enabled) 1318 else 181,
                        "available" to WhisperEngine.available))
                    "downloadLocalModel" -> thread {
                        try {
                            LocalCandidatePolicy.requireRuntime(this)
                            ModelStore.ensure(this, LocalCandidatePolicy.model) { p ->
                                main.post { channel.invokeMethod("onWhisperProgress", p) }
                            }
                            WhisperEngine.warmUp(applicationContext, LocalCandidatePolicy.model)
                            main.post { result.success(null) }
                        } catch (e: Exception) { main.post { result.error("download", "Model download failed. Try again.", null) } }
                    }
                    "startSession" -> result.success(coordinator.start(call.argument<String>("source") ?: ""))
                    "stopSession" -> { coordinator.stop(call.argument<String>("id") ?: ""); result.success(null) }
                    "retrySession" -> { coordinator.retry(call.argument<String>("id") ?: "", call.argument<String>("engine")); result.success(null) }
                    "discardSession" -> { coordinator.discard(call.argument<String>("id") ?: ""); result.success(null) }
                    "acknowledgeSession" -> { coordinator.acknowledge(call.argument<String>("id") ?: ""); result.success(null) }
                    "pendingSessions" -> result.success(coordinator.pending(call.argument<String>("source")))
                    "runLocalBenchmark" -> {
                        if (!BuildConfig.DEBUG || !BuildConfig.APPLICATION_ID.endsWith(".benchmark"))
                            throw DictationException("disabled", "Use the separate benchmark app for replay tests.")
                        val file = File(call.argument<String>("path") ?: "").canonicalFile
                        val root = filesDir.canonicalPath + File.separator
                        if (!file.path.startsWith(root) || !file.isFile) throw DictationException("file", "Choose a WAV file in app storage.")
                        val config = JSONObject(call.argument<Map<String, Any?>>("config") ?: emptyMap<String, Any?>())
                        coordinator.beginBenchmark()
                        thread {
                            try {
                                val data = LocalBenchmark.run(applicationContext, file, config)
                                main.post { result.success(data.toString()) }
                            } catch (e: Exception) { main.post { result.error("benchmark", e.message, null) } }
                            finally { coordinator.endBenchmark() }
                        }
                    }
                    else -> result.notImplemented()
                }
            } catch (e: Exception) {
                result.error((e as? DictationException)?.code ?: "native",
                    (e as? DictationException)?.message ?: "Could not complete the action.", null)
            }
        }
    }

    override fun cleanUpFlutterEngine(flutterEngine: FlutterEngine) {
        if (::coordinator.isInitialized) coordinator.removeListener(listener)
        if (::channel.isInitialized) channel.setMethodCallHandler(null)
        super.cleanUpFlutterEngine(flutterEngine)
    }

    private fun isAccessibilityEnabled(): Boolean {
        val expected = "$packageName/${InsertionService::class.java.name}"
        val enabled = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
        return enabled.split(':').any { it.equals(expected, ignoreCase = true) }
    }
}
