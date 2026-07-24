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
        private const val KEY_X = "bubble_x"
        private const val KEY_Y = "bubble_y"
        private const val CHANNEL_ID = "voiceflow_bubble"
        private const val NOTIFICATION_ID = 1

        private const val COLOR_INK = 0xFF000000.toInt()
        private const val COLOR_LIME = 0xFFD6F655.toInt()
        private const val COLOR_CORAL = 0xFFFF8577.toInt()

        /** Resets a stuck listening state (e.g. recognizer never calls back). */
        private const val WATCHDOG_MS = 30_000L

        @Volatile
        var running = false
            private set
    }

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var windowManager: WindowManager
    private lateinit var speech: SpeechEngine
    private var bubble: FrameLayout? = null
    private var bubbleBg: GradientDrawable? = null
    private var params: WindowManager.LayoutParams? = null
    private var pulse: ValueAnimator? = null
    private val watchdog = Runnable { resetToIdle() }

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
            .setContentTitle("VoiceFlow bubble is on")
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

    /** Drag to move (snaps to edges, position persisted); small movement = tap. */
    private inner class DragTapListener(
        private val lp: WindowManager.LayoutParams,
        private val size: Int,
    ) : View.OnTouchListener {
        private var startX = 0
        private var startY = 0
        private var downRawX = 0f
        private var downRawY = 0f
        private val slop = dp(8)

        override fun onTouch(v: View, e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startX = lp.x
                    startY = lp.y
                    downRawX = e.rawX
                    downRawY = e.rawY
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    lp.x = startX + (e.rawX - downRawX).toInt()
                    lp.y = startY + (e.rawY - downRawY).toInt()
                    windowManager.updateViewLayout(v, lp)
                    return true
                }
                MotionEvent.ACTION_UP -> {
                    val moved = abs(e.rawX - downRawX) > slop || abs(e.rawY - downRawY) > slop
                    if (moved) {
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

    // ----- Dictation -----

    private fun onBubbleTapped() {
        if (speech.listening) {
            speech.stop() // finish utterance, results still delivered
            return
        }
        val locale = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_LOCALE, "id-ID").orEmpty()
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

    private fun deliver(text: String) {
        val inserted = try {
            InsertionService.instance?.insertIntoFocused(text) == true
        } catch (_: Exception) {
            false
        }
        if (!inserted) {
            val cm = getSystemService(ClipboardManager::class.java)
            cm.setPrimaryClip(ClipData.newPlainText("VoiceFlow", text))
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
