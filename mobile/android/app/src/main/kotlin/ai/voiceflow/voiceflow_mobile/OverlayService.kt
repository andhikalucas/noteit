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
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.WindowInsets
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import android.widget.ScrollView
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

        @Volatile
        var running = false
            private set
    }

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var windowManager: WindowManager
    private lateinit var coordinator: DictationCoordinator
    private var session: Map<String, Any?>? = null
    private var target: InsertionService.Target? = null
    private var actionPanel: View? = null
    private val sessionListener: (Map<String, Any?>) -> Unit = { update ->
        if (update["source"] == "bubble") updateSession(update)
    }
    private var bubble: FrameLayout? = null
    private var bubbleBg: GradientDrawable? = null
    private var bubbleSize = 0
    private var closeTarget: FrameLayout? = null
    private var closeTargetBg: GradientDrawable? = null
    private var params: WindowManager.LayoutParams? = null
    private var pulse: ValueAnimator? = null
    private val watchdog = Runnable { session?.get("id")?.let { coordinator.stop(it as String) } }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        coordinator = DictationCoordinator.get(this)
        coordinator.addListener(sessionListener)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        startAsForeground()
        if (bubble == null) addBubble()
        running = true
        coordinator.pending("bubble").lastOrNull()?.let { updateSession(it) }
        if (DictationSettings.load(this).engine == "local") thread { WhisperEngine.warmUp(applicationContext, "small") }
        return START_STICKY
    }

    override fun onDestroy() {
        running = false
        handler.removeCallbacks(watchdog)
        pulse?.cancel()
        coordinator.removeListener(sessionListener)
        session?.takeIf { it["state"] == "recording" }?.get("id")?.let { coordinator.stop(it as String) }
        hideActionPanel()
        hideCloseTarget()
        bubble?.let { windowManager.removeView(it) }
        bubble = null
        super.onDestroy()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        handler.post {
            val view = bubble ?: return@post
            params?.let { clamp(it); windowManager.updateViewLayout(view, it) }
            hideCloseTarget()
            if (actionPanel != null) showActions()
        }
    }

    // ----- Foreground plumbing -----

    private fun startAsForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Floating microphone", NotificationManager.IMPORTANCE_LOW)
        )
        val tapIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        val notification: Notification = (if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, CHANNEL_ID) else Notification.Builder(this))
            .setContentTitle("Floating microphone")
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
            overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = prefs.getInt(KEY_X, 0)
            y = prefs.getInt(KEY_Y, dp(200))
        }
        clamp(lp)
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
                        hideActionPanel()
                        clamp(lp)
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
                        val bounds = usableBounds()
                        lp.x = if (lp.x + size / 2 < bounds.centerX()) bounds.left else bounds.right - size
                        clamp(lp)
                        windowManager.updateViewLayout(v, lp)
                        getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                            .putInt(KEY_X, lp.x).putInt(KEY_Y, lp.y).apply()
                    } else if (e.actionMasked == MotionEvent.ACTION_UP) {
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
        val bounds = usableBounds()
        return Pair(bounds.centerX(), bounds.bottom - dp(80))
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
            overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            val center = closeCenter()
            x = center.first - target / 2
            y = center.second - target / 2
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

    // ----- Shared dictation sessions -----

    private fun onBubbleTapped() {
        hideActionPanel()
        try {
            when (session?.get("state")) {
                "recording" -> coordinator.stop(session!!["id"] as String)
                "processing", "failed", "completed" -> showActions()
                else -> {
                    target = InsertionService.instance?.captureTarget()
                    updateSession(coordinator.start("bubble"))
                }
            }
        } catch (e: Exception) {
            flash(COLOR_CORAL)
            toast((e as? DictationException)?.message ?: "Could not start recording.")
        }
    }

    private fun updateSession(update: Map<String, Any?>) {
        if (update["state"] == "cancelled" && session != null && session!!["id"] != update["id"]) return
        session = update
        handler.removeCallbacks(watchdog)
        pulse?.cancel(); pulse = null
        bubble?.scaleX = 1f; bubble?.scaleY = 1f
        when (update["state"]) {
            "recording" -> {
                setBubbleColor(COLOR_LIME); startPulse()
                handler.postDelayed(watchdog, 10 * 60_000L)
            }
            "processing" -> setBubbleColor(COLOR_PROC)
            "failed" -> { setBubbleColor(COLOR_CORAL); showActions() }
            "completed" -> {
                hideActionPanel()
                val text = update["text"] as? String
                try {
                    if (!text.isNullOrBlank() && coordinator.beginExternalDelivery(update["id"] as String)) {
                        deliver(text)
                        clearDelivered(update["id"] as String)
                    } else { setBubbleColor(COLOR_CORAL); showActions() }
                } catch (_: Exception) {
                    // A successful field write and our journal cannot be atomic.
                    // Keep the text but never repeat an automatic insertion.
                    target = null
                    setBubbleColor(COLOR_CORAL)
                    toast("Transcript saved. Tap the bubble to copy or clear it.")
                    showActions()
                }
            }
            "cancelled" -> { hideActionPanel(); session = null; target = null; setBubbleColor(COLOR_INK) }
        }
    }

    private fun showActions() {
        hideActionPanel()
        val current = session ?: return
        val id = current["id"] as String
        val failed = current["state"] == "failed"
        val completed = current["state"] == "completed"
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(8), dp(12), dp(8))
            background = GradientDrawable().apply { setColor(0xFFF5F5F5.toInt()); cornerRadius = dp(16).toFloat() }
            addView(TextView(this@OverlayService).apply {
                text = if (failed) (current["error"] as? String ?: "Transcription failed.")
                    else if (completed) "Transcript saved. Check the field before copying again." else "Transcribing..."
                setTextColor(COLOR_INK); textSize = 14f
            })
        }
        fun action(label: String, block: () -> Unit) {
            panel.addView(Button(this).apply {
                text = label
                setOnClickListener {
                    hideActionPanel()
                    try { block() } catch (e: Exception) { toast((e as? DictationException)?.message ?: "Could not complete the action.") }
                }
            })
        }
        if (failed) {
            action("Retry") { coordinator.retry(id, null) }
            if (current["engine"] != "local") action("Use local") { coordinator.retry(id, "local") }
        }
        if (completed) {
            action("Copy") {
                coordinator.beginExternalDelivery(id)
                getSystemService(ClipboardManager::class.java).setPrimaryClip(
                    ClipData.newPlainText("noteit!", current["text"] as? String ?: ""))
                clearDelivered(id)
                toast("Copied.")
            }
            action("Clear") { clearDelivered(id) }
        } else action("Discard") { coordinator.discard(id) }
        val bounds = usableBounds()
        val width = minOf(dp(280), bounds.width())
        panel.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        val scroll = ScrollView(this).apply { addView(panel) }
        val height = minOf(panel.measuredHeight, maxOf(dp(48), bounds.height() - dp(32)))
        val lp = WindowManager.LayoutParams(width, height, overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, PixelFormat.TRANSLUCENT).apply {
            gravity = Gravity.TOP or Gravity.START
            x = bounds.left + (bounds.width() - width) / 2
            y = bounds.top + minOf(dp(64), maxOf(0, bounds.height() - height - dp(16)))
        }
        windowManager.addView(scroll, lp)
        actionPanel = scroll
    }

    private fun hideActionPanel() {
        actionPanel?.let { runCatching { windowManager.removeView(it) } }
        actionPanel = null
    }

    private fun clearDelivered(id: String) {
        coordinator.acknowledge(id)
        hideActionPanel()
        session = null; target = null; setBubbleColor(COLOR_INK)
    }

    private fun deliver(text: String) {
        val inserted = runCatching {
            target?.let { InsertionService.instance?.insertIntoTarget(it, text) } == true
        }.getOrDefault(false)
        if (!inserted) {
            getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("noteit!", text))
            toast("Copied to clipboard.")
        }
    }

    private fun overlayType(): Int = if (Build.VERSION.SDK_INT >= 26)
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else WindowManager.LayoutParams.TYPE_PHONE

    private fun usableBounds(): Rect {
        if (Build.VERSION.SDK_INT >= 30) {
            val metrics = windowManager.currentWindowMetrics
            val insets = metrics.windowInsets.getInsetsIgnoringVisibility(
                WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            return Rect(0, 0, metrics.bounds.width() - insets.left - insets.right,
                metrics.bounds.height() - insets.top - insets.bottom)
        }
        return Rect(0, 0, resources.displayMetrics.widthPixels, resources.displayMetrics.heightPixels)
    }

    private fun clamp(lp: WindowManager.LayoutParams) {
        val b = usableBounds()
        lp.x = lp.x.coerceIn(b.left, maxOf(b.left, b.right - lp.width))
        lp.y = lp.y.coerceIn(b.top, maxOf(b.top, b.bottom - lp.height))
    }

    private fun setBubbleColor(color: Int) {
        bubbleBg?.setColor(color)
    }

    private fun flash(color: Int) {
        setBubbleColor(color)
        handler.postDelayed({ if (session == null) setBubbleColor(COLOR_INK) }, 900)
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
