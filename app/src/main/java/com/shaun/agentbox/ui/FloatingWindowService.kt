package com.shaun.agentbox.ui

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.IBinder
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import androidx.core.app.NotificationCompat
import com.shaun.agentbox.mcp.McpService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.abs

class FloatingWindowService : Service() {
    companion object {
        var isRunning = false
            private set

        const val ACTION_OPTIONS_CHANGED = "com.shaun.agentbox.ui.FloatingWindowService.OPTIONS_CHANGED"
        private const val STEALTH_SIZE_PX = 1
        private const val STEALTH_ALPHA = 0.05f
        private const val STEALTH_CORNER_OFFSET_PX = 1
    }

    private lateinit var windowManager: WindowManager
    private lateinit var floatingView: FrameLayout
    private lateinit var statusText: TextView
    private lateinit var layoutParams: WindowManager.LayoutParams
    private lateinit var config: FloatingFeatureConfig
    private var currentOptions = FloatingFeatureOptions()
    private val scope = CoroutineScope(Dispatchers.Main + Job())

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        config = FloatingFeatureConfig(this)
        currentOptions = config.load()
        createNotificationChannel()
        val notification = NotificationCompat.Builder(this, "floating_window")
            .setContentTitle("AgentBox Floating Control")
            .setContentText("Tap the floating control to start or stop MCP Service")
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setOngoing(true)
            .build()
        startForeground(2, notification)

        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager

        val density = resources.displayMetrics.density
        val size = initialWidthPx(currentOptions, density)
        val height = initialHeightPx(currentOptions, density)

        statusText = TextView(this).apply {
            text = "MCP"
            textSize = 11f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            includeFontPadding = false
            visibility = if (currentOptions.circularFloatingWindow) View.GONE else View.VISIBLE
        }

        floatingView = FrameLayout(this).apply {
            addView(
                statusText,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )
            )
            elevation = if (currentOptions.transparentFloatingWindow) 0f else 8f * density
            alpha = if (currentOptions.transparentFloatingWindow) STEALTH_ALPHA else 1f
        }

        layoutParams = WindowManager.LayoutParams(
            size.toInt(), height.toInt(),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            applyInitialPosition(currentOptions)
        }

        applyAppearance(currentOptions, updateLayout = false)
        windowManager.addView(floatingView, layoutParams)

        setupTouchListener()

        scope.launch {
            while (isActive) {
                val latest = config.load()
                if (latest != currentOptions) {
                    currentOptions = latest
                    applyAppearance(latest, updateLayout = true)
                }
                updateState()
                delay(500)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_OPTIONS_CHANGED && ::floatingView.isInitialized) {
            currentOptions = config.load()
            applyAppearance(currentOptions, updateLayout = true)
            updateState()
        }
        return START_STICKY
    }

    private fun applyAppearance(options: FloatingFeatureOptions, updateLayout: Boolean) {
        val density = resources.displayMetrics.density
        layoutParams.width = initialWidthPx(options, density).toInt()
        layoutParams.height = initialHeightPx(options, density).toInt()
        if (options.transparentFloatingWindow) {
            layoutParams.gravity = Gravity.TOP or Gravity.END
            layoutParams.x = -STEALTH_CORNER_OFFSET_PX
            layoutParams.y = -STEALTH_CORNER_OFFSET_PX
        } else {
            val normalGravity = Gravity.TOP or Gravity.START
            if (layoutParams.gravity != normalGravity) {
                layoutParams.gravity = normalGravity
                layoutParams.x = 0
                layoutParams.y = 300
            }
        }
        statusText.visibility = if (options.circularFloatingWindow || options.transparentFloatingWindow) View.GONE else View.VISIBLE
        floatingView.alpha = if (options.transparentFloatingWindow) STEALTH_ALPHA else 1f
        floatingView.elevation = if (options.transparentFloatingWindow) 0f else 8f * density
        floatingView.background = GradientDrawable().apply {
            shape = if (options.circularFloatingWindow || options.transparentFloatingWindow) GradientDrawable.OVAL else GradientDrawable.RECTANGLE
            cornerRadius = if (options.circularFloatingWindow || options.transparentFloatingWindow) 999f * density else 18f * density
            setStroke(
                (1 * density).toInt().coerceAtLeast(1),
                Color.parseColor(if (options.transparentFloatingWindow) "#12FFFFFF" else "#99FFFFFF")
            )
        }
        updateState()
        if (updateLayout) {
            windowManager.updateViewLayout(floatingView, layoutParams)
        }
    }

    private fun initialWidthPx(options: FloatingFeatureOptions, density: Float): Float {
        return if (options.transparentFloatingWindow) {
            // Minimum visual anchor: one physical pixel, then pushed into the rounded corner.
            STEALTH_SIZE_PX.toFloat()
        } else {
            (if (options.circularFloatingWindow) 54 else 118) * density
        }
    }

    private fun initialHeightPx(options: FloatingFeatureOptions, density: Float): Float {
        return if (options.transparentFloatingWindow) {
            STEALTH_SIZE_PX.toFloat()
        } else {
            (if (options.circularFloatingWindow) 54 else 42) * density
        }
    }

    private fun WindowManager.LayoutParams.applyInitialPosition(options: FloatingFeatureOptions) {
        if (options.transparentFloatingWindow) {
            gravity = Gravity.TOP or Gravity.END
            x = -STEALTH_CORNER_OFFSET_PX
            y = -STEALTH_CORNER_OFFSET_PX
        } else {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = 300
        }
    }

    private fun updateState() {
        if (!::floatingView.isInitialized) return
        val isMcpRunning = McpService.isRunning
        val bg = floatingView.background as? GradientDrawable ?: return
        val color = when {
            isMcpRunning && currentOptions.transparentFloatingWindow -> "#1800D26A"
            isMcpRunning -> "#D000C853"
            currentOptions.transparentFloatingWindow -> "#12607D8B"
            else -> "#B0607D8B"
        }
        bg.setColor(Color.parseColor(color))
        statusText.text = if (isMcpRunning) "MCP ON" else "MCP OFF"
    }

    private fun setupTouchListener() {
        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f
        var isClick = false

        floatingView.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = layoutParams.x
                    initialY = layoutParams.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    isClick = true
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - initialTouchX
                    val dy = event.rawY - initialTouchY
                    if (abs(dx) > 10 || abs(dy) > 10) {
                        isClick = false
                    }
                    layoutParams.x = initialX + dx.toInt()
                    layoutParams.y = initialY + dy.toInt()
                    windowManager.updateViewLayout(floatingView, layoutParams)
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (isClick) {
                        toggleMcpService()
                    }
                    true
                }
                else -> false
            }
        }
    }

    private fun toggleMcpService() {
        val intent = Intent(this, McpService::class.java)
        if (McpService.isRunning) {
            stopService(intent)
        } else {
            startForegroundService(intent)
        }
        updateState()
    }

    override fun onDestroy() {
        super.onDestroy()
        isRunning = false
        if (::floatingView.isInitialized) {
            windowManager.removeView(floatingView)
        }
        scope.coroutineContext[Job]?.cancel()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel("floating_window", "Floating Control", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
        }
    }

}
