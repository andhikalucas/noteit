package ai.voiceflow.voiceflow_mobile

import android.animation.ValueAnimator
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.Toast
import kotlin.concurrent.thread
import kotlin.math.abs

/**
 * Foreground service that draws the floating mic bubble over every app.
 * Tap → listen (bubble turns lime and pulses) → transcript is inserted into
 * the focused field via InsertionService, falling back to the clipboard.
 * Self-contained: runs without the Flutter engine.
 */
class OverlayService : Service() {

    companion object {
        const val ACTION_START = "ai.voiceflow.START"
        const val ACTION_STOP = "ai.voiceflow.STOP"
        const val PREFS = "vf_overlay"
        const val KEY_LOCALE = "locale"
        const val KEY_ENGINE = "stt_engine"   // "whisper" | "google"
        const val KEY_MODEL = "whisper_model" // "tiny" | "base" | "small"
        private const val KEY_X = "bubble_x"
        private const val KEY_Y = "bubble_y"
        private const val CHANNEL_ID = "voiceflow_bubble"
        private const val NOTIFICATION_ID = 1

        private const val COLOR_INK = 0xFF000000.toInt()
        private const val COLOR_LIME = 0xFFD6F655.toInt()
        private const val COLOR_CORAL = 0xFFFF8577.toInt()
        private const val COLOR_PROC = 0xFF888888.toInt() // transcribing

        /** Resets a stuck listening state (e.g. recognizer never calls back). */
        private const val WATCHDOG_MS = 30_000L

        /** Max Whisper recording before auto-finishing (transcribes what we have). */
        private const val WHISPER_MAX_MS = 60_000L

        @Volatile
        var running = false
            private set
    }

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var windowManager: WindowManager
    private lateinit var speech: SpeechEngine
    private val capture = AudioCapture() // Whisper path
    @Volatile private var whisperRecording = false
    @Volatile private var whisperBusy = false // transcribing — ignore taps
    private var bubble: FrameLayout? = null
    private var bubbleBg: GradientDrawable? = null
    private var bubbleSize = 0
    private var closeTarget: FrameLayout? = null
    private var closeTargetBg: GradientDrawable? = null
    private var params: WindowManager.LayoutParams? = null
    private var pulse: ValueAnimator? = null
    private val watchdog = Runnable { if (whisperRecording) finishWhisper() else resetToIdle() }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        speech = SpeechEngine(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        startAsForeground()
        if (bubble == null) addBubble()
        running = true
        return START_STICKY
    }

    override fun onDestroy() {
        running = false
        handler.removeCallbacks(watchdog)
        pulse?.cancel()
        speech.cancelAndReset()
        if (whisperRecording) capture.abort()
        hideCloseTarget()
        bubble?.let { windowManager.removeView(it) }
        bubble = null
        super.onDestroy()
    }

    // ----- Foreground plumbing -----

    private fun startAsForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID, "Floating bubble", NotificationManager.IMPORTANCE_MIN
            )
        )
        val tapIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        val notification: Notification = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("noteit! bubble is on")
            .setSmallIcon(R.drawable.ic_mic)
            .setContentIntent(tapIntent)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID, notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    // ----- Bubble view -----

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun addBubble() {
        val size = dp(56)
        bubbleSize = size
        val bg = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(COLOR_INK)
        }
        bubbleBg = bg

        val icon = ImageView(this).apply {
            setImageResource(R.drawable.ic_mic)
            val pad = dp(14)
            setPadding(pad, pad, pad, pad)
        }
        val layout = FrameLayout(this).apply {
            background = bg
            elevation = dp(4).toFloat()
            addView(icon, FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
        }

        val prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val lp = WindowManager.LayoutParams(
            size, size,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = prefs.getInt(KEY_X, 0)
            y = prefs.getInt(KEY_Y, dp(200))
        }
        params = lp
        layout.setOnTouchListener(DragTapListener(lp, size))
        windowManager.addView(layout, lp)
        bubble = layout
    }

    /**
     * Drag to move (snaps to edges, position persisted); a small movement is a
     * tap. Dragging onto the bottom-center close target dismisses the bubble.
     */
    private inner class DragTapListener(
        private val lp: WindowManager.LayoutParams,
        private val size: Int,
    ) : View.OnTouchListener {
        private var startX = 0
        private var startY = 0
        private var downRawX = 0f
        private var downRawY = 0f
        private var dragging = false
        private val slop = dp(8)

        override fun onTouch(v: View, e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startX = lp.x
                    startY = lp.y
                    downRawX = e.rawX
                    downRawY = e.rawY
                    dragging = false
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    lp.x = startX + (e.rawX - downRawX).toInt()
                    lp.y = startY + (e.rawY - downRawY).toInt()
                    if (!dragging &&
                        (abs(e.rawX - downRawX) > slop || abs(e.rawY - downRawY) > slop)
                    ) {
                        dragging = true
                        showCloseTarget()
                    }
                    if (dragging) {
                        windowManager.updateViewLayout(v, lp)
                        highlightCloseTarget(isOverClose(lp))
                    }
                    return true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (dragging) {
                        val overClose = isOverClose(lp)
                        hideCloseTarget()
                        if (overClose) {
                            closeBubble()
                            return true
                        }
                        // Snap to the nearest side edge; keep the vertical position.
                        val screenWidth = resources.displayMetrics.widthPixels
                        lp.x = if (lp.x + size / 2 < screenWidth / 2) 0 else screenWidth - size
                        windowManager.updateViewLayout(v, lp)
                        getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                            .putInt(KEY_X, lp.x).putInt(KEY_Y, lp.y).apply()
                    } else {
                        onBubbleTapped()
                    }
                    return true
                }
            }
            return false
        }
    }

    // ----- Drag-to-close target (bottom center) -----

    /** Center of the close target, in TOP|START overlay coordinates. */
    private fun closeCenter(): Pair<Int, Int> {
        val w = resources.displayMetrics.widthPixels
        val h = resources.displayMetrics.heightPixels
        return Pair(w / 2, h - dp(96))
    }

    private fun isOverClose(lp: WindowManager.LayoutParams): Boolean {
        val (cx, cy) = closeCenter()
        val bx = lp.x + bubbleSize / 2
        val by = lp.y + bubbleSize / 2
        val r = dp(72)
        val dx = bx - cx
        val dy = by - cy
        return dx * dx + dy * dy < r * r
    }

    private fun showCloseTarget() {
        if (closeTarget != null) return
        val target = dp(64)
        val bg = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(0xCC000000.toInt()) // translucent black
        }
        closeTargetBg = bg
        val icon = ImageView(this).apply {
            setImageResource(R.drawable.ic_close)
            val pad = dp(16)
            setPadding(pad, pad, pad, pad)
        }
        val layout = FrameLayout(this).apply {
            background = bg
            elevation = dp(4).toFloat()
            addView(icon, FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
        }
        val lp = WindowManager.LayoutParams(
            target, target,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            y = dp(64) // margin above the bottom edge
        }
        windowManager.addView(layout, lp)
        closeTarget = layout
    }

    private fun highlightCloseTarget(active: Boolean) {
        closeTargetBg?.setColor(if (active) COLOR_CORAL else 0xCC000000.toInt())
        val s = if (active) 1.25f else 1f
        closeTarget?.scaleX = s
        closeTarget?.scaleY = s
    }

    private fun hideCloseTarget() {
        closeTarget?.let { windowManager.removeView(it) }
        closeTarget = null
        closeTargetBg = null
    }

    /** Dismiss the bubble and persist it off so it doesn't reappear. */
    private fun closeBubble() {
        // Keep the Flutter Settings toggle in sync (shared_preferences legacy store).
        try {
            getSharedPreferences("FlutterSharedPreferences", Context.MODE_PRIVATE)
                .edit().putBoolean("flutter.bubble_enabled", false).apply()
        } catch (_: Exception) { /* best effort */ }
        stopSelf()
    }

    // ----- Dictation -----

    private fun prefs() = getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun onBubbleTapped() {
        if (prefs().getString(KEY_ENGINE, "whisper") == "whisper") onTapWhisper()
        else onTapGoogle()
    }

    // ----- Google (SpeechRecognizer, streaming) -----

    private fun onTapGoogle() {
        if (speech.listening) {
            speech.stop() // finish utterance, results still delivered
            return
        }
        val locale = prefs().getString(KEY_LOCALE, "id-ID").orEmpty()
        speech.start(locale, object : SpeechEngine.Listener {
            override fun onFinal(text: String) = deliver(text)

            override fun onError(message: String) {
                flash(COLOR_CORAL)
                toast(message)
            }

            override fun onListeningChanged(listening: Boolean) {
                handler.removeCallbacks(watchdog)
                if (listening) {
                    setBubbleColor(COLOR_LIME)
                    startPulse()
                    handler.postDelayed(watchdog, WATCHDOG_MS)
                } else {
                    resetToIdle()
                }
            }
        })
    }

    // ----- Whisper (on-device, record-then-transcribe) -----

    private fun onTapWhisper() {
        if (whisperBusy) return
        if (whisperRecording) {
            finishWhisper()
            return
        }
        if (!WhisperNative.available) {
            flash(COLOR_CORAL); toast("Speech engine unavailable"); return
        }
        val size = prefs().getString(KEY_MODEL, "small").orEmpty()
        if (!ModelStore.isDownloaded(this, size)) {
            flash(COLOR_CORAL); toast("Download the model in noteit! settings"); return
        }
        try {
            capture.start()
        } catch (e: Exception) {
            flash(COLOR_CORAL); toast("Microphone unavailable"); return
        }
        whisperRecording = true
        setBubbleColor(COLOR_LIME)
        startPulse()
        handler.postDelayed(watchdog, WHISPER_MAX_MS)
    }

    private fun finishWhisper() {
        handler.removeCallbacks(watchdog)
        whisperRecording = false
        whisperBusy = true
        pulse?.cancel(); pulse = null
        bubble?.scaleX = 1f; bubble?.scaleY = 1f
        setBubbleColor(COLOR_PROC) // "working" while transcribing

        val size = prefs().getString(KEY_MODEL, "small").orEmpty()
        val lang = whisperLang(prefs().getString(KEY_LOCALE, "id-ID").orEmpty())
        val samples = capture.stop()
        thread {
            val text = try {
                WhisperEngine.transcribe(this, size, samples, lang, null)
            } catch (e: Exception) {
                null
            }
            handler.post {
                whisperBusy = false
                setBubbleColor(COLOR_INK)
                if (text.isNullOrBlank()) {
                    flash(COLOR_CORAL); toast("Didn't catch that")
                } else {
                    deliver(text)
                }
            }
        }
    }

    /** Whisper wants a language, not a region: "id-ID" → "id", "" → auto. */
    private fun whisperLang(locale: String): String? {
        val l = locale.trim()
        if (l.isEmpty()) return null
        return l.substringBefore('-').lowercase()
    }

    private fun deliver(text: String) {
        val inserted = try {
            InsertionService.instance?.insertIntoFocused(text) == true
        } catch (_: Exception) {
            false
        }
        if (!inserted) {
            val cm = getSystemService(ClipboardManager::class.java)
            cm.setPrimaryClip(ClipData.newPlainText("noteit!", text))
            toast("Copied — long-press to paste")
        }
    }

    private fun resetToIdle() {
        handler.removeCallbacks(watchdog)
        pulse?.cancel()
        pulse = null
        bubble?.scaleX = 1f
        bubble?.scaleY = 1f
        if (speech.listening) speech.cancelAndReset()
        if (whisperRecording) {
            whisperRecording = false
            capture.abort()
        }
        setBubbleColor(COLOR_INK)
    }

    private fun setBubbleColor(color: Int) {
        bubbleBg?.setColor(color)
    }

    private fun flash(color: Int) {
        setBubbleColor(color)
        handler.postDelayed({ if (!speech.listening) setBubbleColor(COLOR_INK) }, 900)
    }

    private fun startPulse() {
        pulse?.cancel()
        pulse = ValueAnimator.ofFloat(1f, 1.15f, 1f).apply {
            duration = 900
            repeatCount = ValueAnimator.INFINITE
            addUpdateListener {
                val s = it.animatedValue as Float
                bubble?.scaleX = s
                bubble?.scaleY = s
            }
            start()
        }
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }
}
