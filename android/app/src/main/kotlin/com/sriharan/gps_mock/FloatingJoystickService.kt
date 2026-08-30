package com.sriharan.gps_mock

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import kotlinx.coroutines.*
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

class FloatingJoystickService : Service() {

    private var windowManager: WindowManager? = null
    private var overlayLayout: LinearLayout? = null
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var tickerJob: Job? = null

    // Joystick movement state
    @Volatile
    private var currentAngleDegrees = 0.0
    @Volatile
    private var currentIntensity = 0.0 // 0.0 to 1.0

    private var speedIndex = 0
    private val speedPresets = listOf(
        1.4 to "🚶 5 km/h",
        4.2 to "🏃 15 km/h",
        13.8 to "🚗 50 km/h"
    )

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            stopSelf()
            return START_NOT_STICKY
        }

        startForeground(NOTIFICATION_ID, buildNotification())
        showOverlay()
        startMovementTicker()
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        tickerJob?.cancel()
        removeOverlay()
    }

    private fun showOverlay() {
        if (overlayLayout != null) return
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager

        val layoutFlag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            layoutFlag,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 100
            y = 200
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#EE1E1E2E"))
            setPadding(16, 12, 16, 16)
            elevation = 16f
        }

        // Header (drag handle + speed button + close button)
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val title = TextView(this).apply {
            text = "🕹️ GPS Joystick"
            setTextColor(Color.WHITE)
            textSize = 12f
            paint.isFakeBoldText = true
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }

        val speedBtn = Button(this).apply {
            text = speedPresets[speedIndex].second
            textSize = 10f
            setBackgroundColor(Color.parseColor("#33FFFFFF"))
            setTextColor(Color.WHITE)
            setPadding(8, 4, 8, 4)
            setOnClickListener {
                speedIndex = (speedIndex + 1) % speedPresets.size
                text = speedPresets[speedIndex].second
            }
        }

        val closeBtn = Button(this).apply {
            text = "✕"
            textSize = 12f
            setBackgroundColor(Color.TRANSPARENT)
            setTextColor(Color.WHITE)
            setOnClickListener {
                stopSelf()
            }
        }

        header.addView(title)
        header.addView(speedBtn)
        header.addView(closeBtn)

        // Make header draggable
        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f

        header.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x
                    initialY = params.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    params.x = initialX + (event.rawX - initialTouchX).toInt()
                    params.y = initialY + (event.rawY - initialTouchY).toInt()
                    windowManager?.updateViewLayout(root, params)
                    true
                }
                else -> false
            }
        }

        // Joystick Pad View
        val joystickView = JoystickPadView(this) { angle, intensity ->
            currentAngleDegrees = angle
            currentIntensity = intensity
        }

        root.addView(header)
        root.addView(joystickView)

        overlayLayout = root
        try {
            windowManager?.addView(root, params)
        } catch (e: Exception) {
            stopSelf()
        }
    }

    private fun removeOverlay() {
        if (overlayLayout != null) {
            try {
                windowManager?.removeView(overlayLayout)
            } catch (e: Exception) {}
            overlayLayout = null
        }
    }

    private fun startMovementTicker() {
        tickerJob?.cancel()
        tickerJob = scope.launch(Dispatchers.IO) {
            while (isActive) {
                if (currentIntensity > 0.05) {
                    val active = MockStateStore.getActiveCommand(this@FloatingJoystickService)
                    if (active != null) {
                        val curLat = active.optDouble("lat", 0.0)
                        val curLng = active.optDouble("lng", 0.0)
                        val speedMps = speedPresets[speedIndex].first * currentIntensity
                        val dt = 0.1 // 100ms
                        val distMeters = speedMps * dt

                        val rad = Math.toRadians(currentAngleDegrees)
                        val dLat = (distMeters * cos(rad)) / 111111.0
                        val dLng = (distMeters * sin(rad)) / (111111.0 * cos(Math.toRadians(curLat)).coerceAtLeast(0.01))

                        val newLat = curLat + dLat
                        val newLng = curLng + dLng

                        active.put("lat", newLat)
                        active.put("lng", newLng)
                        MockStateStore.setActiveCommand(this@FloatingJoystickService, active)

                        val retarget = Intent(this@FloatingJoystickService, MockingService::class.java).apply {
                            action = MockingService.ACTION_START_FIXED
                            putExtra(MockingService.EXTRA_LAT, newLat)
                            putExtra(MockingService.EXTRA_LNG, newLng)
                            putExtra(MockingService.EXTRA_ALTITUDE, active.optDouble("altitude", 10.0))
                            putExtra(MockingService.EXTRA_ACCURACY, active.optDouble("accuracy", 1.0))
                            putExtra(MockingService.EXTRA_JITTER, active.optBoolean("jitter", false))
                            putExtra(MockingService.EXTRA_JITTER_RADIUS, active.optDouble("jitterRadius", 2.0))
                            putExtra(MockingService.EXTRA_SATELLITES, active.optInt("satellites", 14))
                            putExtra(MockingService.EXTRA_SNR, active.optDouble("snr", 32.0))
                            putExtra(MockingService.EXTRA_LABEL, "Joystick: ${String.format("%.5f, %.5f", newLat, newLng)}")
                        }
                        startService(retarget)
                    }
                }
                delay(100)
            }
        }
    }

    private fun buildNotification(): Notification {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "GPS Joystick Overlay", NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
        }

        val stopIntent = PendingIntent.getService(
            this, 2,
            Intent(this, FloatingJoystickService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return androidx.core.app.NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("GPS Joystick Active")
            .setContentText("Tap Stop to close floating joystick")
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setOngoing(true)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Close Joystick", stopIntent)
            .build()
    }

    companion object {
        const val ACTION_START = "START_JOYSTICK"
        const val ACTION_STOP = "STOP_JOYSTICK"
        private const val CHANNEL_ID = "gps_joystick_channel"
        private const val NOTIFICATION_ID = 1003
    }
}

/** Custom interactive Virtual Joystick Pad View */
class JoystickPadView(
    context: Context,
    private val onJoystickMove: (angleDegrees: Double, intensity: Double) -> Unit
) : View(context) {

    private val outerRadius = 130f
    private val knobRadius = 45f

    private var knobX = 0f
    private var knobY = 0f

    private val basePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#33FFFFFF")
        style = Paint.Style.FILL
    }
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#66FFFFFF")
        style = Paint.Style.STROKE
        strokeWidth = 3f
    }
    private val knobPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FF6C63FF")
        style = Paint.Style.FILL
    }

    init {
        layoutParams = ViewGroup.LayoutParams(
            (outerRadius * 2 + 40).toInt(),
            (outerRadius * 2 + 40).toInt()
        )
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        knobX = w / 2f
        knobY = h / 2f
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cx = width / 2f
        val cy = height / 2f

        canvas.drawCircle(cx, cy, outerRadius, basePaint)
        canvas.drawCircle(cx, cy, outerRadius, borderPaint)
        canvas.drawCircle(knobX, knobY, knobRadius, knobPaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val cx = width / 2f
        val cy = height / 2f

        when (event.action) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                val dx = event.x - cx
                val dy = event.y - cy
                val distance = hypot(dx, dy)
                val maxDistance = outerRadius - 15f

                if (distance > maxDistance) {
                    knobX = cx + (dx / distance) * maxDistance
                    knobY = cy + (dy / distance) * maxDistance
                } else {
                    knobX = event.x
                    knobY = event.y
                }

                val intensity = (distance / maxDistance).coerceIn(0f, 1f).toDouble()
                // Angle with 0 = North (Up), 90 = East (Right), 180 = South (Down), 270 = West (Left)
                val angleRad = atan2(dx.toDouble(), -dy.toDouble())
                var angleDeg = Math.toDegrees(angleRad)
                if (angleDeg < 0) angleDeg += 360.0

                onJoystickMove(angleDeg, intensity)
                invalidate()
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                knobX = cx
                knobY = cy
                onJoystickMove(0.0, 0.0)
                invalidate()
                return true
            }
        }
        return super.onTouchEvent(event)
    }
}
