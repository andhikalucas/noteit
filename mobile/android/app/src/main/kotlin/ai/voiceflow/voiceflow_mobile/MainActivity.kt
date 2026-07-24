package ai.voiceflow.voiceflow_mobile

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodChannel

class MainActivity : FlutterActivity() {

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)
        MethodChannel(
            flutterEngine.dartExecutor.binaryMessenger, "voiceflow/overlay"
        ).setMethodCallHandler { call, result ->
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
                    val locale = call.argument<String>("locale") ?: "id-ID"
                    saveLocale(locale)
                    val svc = Intent(this, OverlayService::class.java)
                    if (enabled) {
                        svc.action = OverlayService.ACTION_START
                        startForegroundService(svc)
                    } else {
                        stopService(svc)
                    }
                    result.success(null)
                }

                "setLocale" -> {
                    saveLocale(call.argument<String>("locale") ?: "id-ID")
                    result.success(null)
                }

                else -> result.notImplemented()
            }
        }
    }

    private fun saveLocale(locale: String) {
        getSharedPreferences(OverlayService.PREFS, Context.MODE_PRIVATE)
            .edit().putString(OverlayService.KEY_LOCALE, locale).apply()
    }

    private fun isAccessibilityEnabled(): Boolean {
        val expected = "$packageName/${InsertionService::class.java.name}"
        val enabled = Settings.Secure.getString(
            contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        return enabled.split(':').any { it.equals(expected, ignoreCase = true) }
    }
}
