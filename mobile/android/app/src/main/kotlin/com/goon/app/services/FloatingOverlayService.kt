package com.goon.app.services

import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.abs

/**
 * GO-ON Floating Overlay Service
 *
 * Shows a floating bubble/banner over other apps when GO-ON has a better price
 * Uses Android System Alert Window permission
 */
class FloatingOverlayService : Service() {

    companion object {
        private const val TAG = "GO-ON-Overlay"

        // Static instance for easy access
        var instance: FloatingOverlayService? = null
            private set

        // Current GO-ON best price (set by Flutter)
        var goonBestPrice: Double? = null

        // Check if overlay permission is granted
        fun canDrawOverlay(context: Context): Boolean {
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                Settings.canDrawOverlays(context)
            } else {
                true
            }
        }

        // Open overlay permission settings
        fun openOverlaySettings(context: Context) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val intent = Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    android.net.Uri.parse("package:${context.packageName}")
                )
                intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                context.startActivity(intent)
            }
        }

        // Show better price overlay
        fun showBetterPrice(
            context: Context,
            goonPrice: Double,
            currentAppPrice: Double,
            currentAppName: String,
            savingsPercent: Int
        ) {
            instance?.showPriceOverlay(goonPrice, currentAppPrice, currentAppName, savingsPercent)
                ?: run {
                    // Start service if not running
                    val intent = Intent(context, FloatingOverlayService::class.java).apply {
                        putExtra("goonPrice", goonPrice)
                        putExtra("currentAppPrice", currentAppPrice)
                        putExtra("currentAppName", currentAppName)
                        putExtra("savingsPercent", savingsPercent)
                    }
                    context.startService(intent)
                }
        }

        // Hide overlay
        fun hide() {
            instance?.hideOverlay()
        }

        // Set GO-ON best price (called from Flutter)
        fun setGoonPrice(price: Double) {
            goonBestPrice = price
        }
    }

    private var windowManager: WindowManager? = null
    private var floatingView: View? = null
    private var isOverlayVisible = false

    // For dragging the bubble
    private var initialX = 0
    private var initialY = 0
    private var initialTouchX = 0f
    private var initialTouchY = 0f

    override fun onCreate() {
        super.onCreate()
        instance = this
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        Log.i(TAG, "Floating Overlay Service Created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        intent?.let {
            val goonPrice = it.getDoubleExtra("goonPrice", 0.0)
            val currentAppPrice = it.getDoubleExtra("currentAppPrice", 0.0)
            val currentAppName = it.getStringExtra("currentAppName") ?: ""
            val savingsPercent = it.getIntExtra("savingsPercent", 0)

            if (goonPrice > 0 && currentAppPrice > 0) {
                showPriceOverlay(goonPrice, currentAppPrice, currentAppName, savingsPercent)
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    /**
     * Show the floating price comparison overlay
     */
    fun showPriceOverlay(
        goonPrice: Double,
        currentAppPrice: Double,
        currentAppName: String,
        savingsPercent: Int
    ) {
        if (!canDrawOverlay(this)) {
            Log.w(TAG, "Overlay permission not granted")
            return
        }

        // Remove existing overlay if any
        hideOverlay()

        try {
            // Create overlay view programmatically
            floatingView = createOverlayView(goonPrice, currentAppPrice, currentAppName, savingsPercent)

            // Window parameters
            val layoutType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            }

            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                layoutType,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                x = 50
                y = 200
            }

            // Add touch listener for drag
            setupTouchListener(params)

            // Add to window
            windowManager?.addView(floatingView, params)
            isOverlayVisible = true

            Log.i(TAG, "Overlay shown: GO-ON $goonPrice vs $currentAppName $currentAppPrice (save $savingsPercent%)")

            // Auto-hide after 10 seconds
            floatingView?.postDelayed({
                hideOverlay()
            }, 10000)

        } catch (e: Exception) {
            Log.e(TAG, "Error showing overlay: ${e.message}")
        }
    }

    /**
     * Create the overlay view programmatically
     */
    private fun createOverlayView(
        goonPrice: Double,
        currentAppPrice: Double,
        currentAppName: String,
        savingsPercent: Int
    ): View {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(0xFF1A365D.toInt()) // Primary blue
            setPadding(32, 24, 32, 24)

            // Rounded corners via background drawable would be better,
            // but for simplicity we use solid color
        }

        // GO-ON icon/text
        val iconText = TextView(this).apply {
            text = "🚐"
            textSize = 24f
            setPadding(0, 0, 16, 0)
        }
        container.addView(iconText)

        // Price info container
        val infoContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        // Main message
        val mainText = TextView(this).apply {
            text = "سعر أفضل في GO-ON!"
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 14f
        }
        infoContainer.addView(mainText)

        // Price comparison
        val priceText = TextView(this).apply {
            text = "${goonPrice.toInt()} ج.م بدلاً من ${currentAppPrice.toInt()} ج.م"
            setTextColor(0xFFD69E2E.toInt()) // Gold
            textSize = 16f
            setTypeface(null, android.graphics.Typeface.BOLD)
        }
        infoContainer.addView(priceText)

        // Savings
        val savingsText = TextView(this).apply {
            text = "وفّر $savingsPercent% من $currentAppName"
            setTextColor(0xFF90EE90.toInt()) // Light green
            textSize = 12f
        }
        infoContainer.addView(savingsText)

        container.addView(infoContainer)

        // Close button
        val closeBtn = TextView(this).apply {
            text = "✕"
            textSize = 18f
            setTextColor(0xFFFFFFFF.toInt())
            setPadding(24, 0, 0, 0)
            setOnClickListener { hideOverlay() }
        }
        container.addView(closeBtn)

        // Open GO-ON on tap
        container.setOnClickListener {
            openGoonApp()
            hideOverlay()
        }

        return container
    }

    /**
     * Setup touch listener for dragging the overlay
     */
    private fun setupTouchListener(params: WindowManager.LayoutParams) {
        floatingView?.setOnTouchListener { view, event ->
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
                    windowManager?.updateViewLayout(floatingView, params)
                    true
                }
                MotionEvent.ACTION_UP -> {
                    // If moved less than 10 pixels, treat as click
                    if (abs(event.rawX - initialTouchX) < 10 &&
                        abs(event.rawY - initialTouchY) < 10) {
                        view.performClick()
                    }
                    true
                }
                else -> false
            }
        }
    }

    /**
     * Hide the overlay
     */
    fun hideOverlay() {
        if (isOverlayVisible && floatingView != null) {
            try {
                windowManager?.removeView(floatingView)
                floatingView = null
                isOverlayVisible = false
                Log.i(TAG, "Overlay hidden")
            } catch (e: Exception) {
                Log.e(TAG, "Error hiding overlay: ${e.message}")
            }
        }
    }

    /**
     * Open GO-ON app
     */
    private fun openGoonApp() {
        try {
            val intent = packageManager.getLaunchIntentForPackage(packageName)
            intent?.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Error opening GO-ON: ${e.message}")
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        hideOverlay()
        instance = null
        Log.i(TAG, "Floating Overlay Service Destroyed")
    }
}
