package com.goon.app.services

import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
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

        // ============ Full-Screen Price Fetching Overlay ============

        // Price data for each app
        data class AppPrice(
            val packageName: String,
            val appName: String,
            val price: Double?,
            val status: String, // "loading", "success", "error", "waiting"
            val eta: String? = null,
            val vehicleType: String? = null
        )

        // Show full-screen price fetching overlay
        fun showPriceFetchingOverlay(context: Context, pickup: String, destination: String, apps: List<String>) {
            instance?.showFullScreenOverlay(pickup, destination, apps)
                ?: run {
                    val intent = Intent(context, FloatingOverlayService::class.java).apply {
                        putExtra("mode", "fullscreen")
                        putExtra("pickup", pickup)
                        putExtra("destination", destination)
                        putStringArrayListExtra("apps", ArrayList(apps))
                    }
                    context.startService(intent)
                }
        }

        // Update price for specific app
        fun updateAppPrice(packageName: String, price: Double?, status: String, eta: String? = null) {
            instance?.updatePriceForApp(packageName, price, status, eta)
        }

        // Hide full-screen overlay
        fun hidePriceFetchingOverlay() {
            instance?.hideFullScreenOverlay()
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

    // Full-screen overlay for price fetching
    private var fullScreenOverlay: View? = null
    private var isFullScreenVisible = false
    private var priceCards: MutableMap<String, View> = mutableMapOf()
    private var appPrices: MutableMap<String, Companion.AppPrice> = mutableMapOf()
    private val handler = Handler(Looper.getMainLooper())
    private var pickupText: String = ""
    private var destinationText: String = ""

    override fun onCreate() {
        super.onCreate()
        instance = this
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        Log.i(TAG, "Floating Overlay Service Created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        intent?.let {
            val mode = it.getStringExtra("mode") ?: "bubble"

            when (mode) {
                "fullscreen" -> {
                    val pickup = it.getStringExtra("pickup") ?: ""
                    val destination = it.getStringExtra("destination") ?: ""
                    val apps = it.getStringArrayListExtra("apps") ?: arrayListOf()
                    showFullScreenOverlay(pickup, destination, apps)
                }
                else -> {
                    val goonPrice = it.getDoubleExtra("goonPrice", 0.0)
                    val currentAppPrice = it.getDoubleExtra("currentAppPrice", 0.0)
                    val currentAppName = it.getStringExtra("currentAppName") ?: ""
                    val savingsPercent = it.getIntExtra("savingsPercent", 0)

                    if (goonPrice > 0 && currentAppPrice > 0) {
                        showPriceOverlay(goonPrice, currentAppPrice, currentAppName, savingsPercent)
                    }
                }
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
        hideFullScreenOverlay()
        instance = null
        Log.i(TAG, "Floating Overlay Service Destroyed")
    }

    // ============================================================
    // FULL-SCREEN PRICE FETCHING OVERLAY
    // ============================================================

    /**
     * Show full-screen overlay during price fetching
     * This covers the entire screen so user doesn't see apps switching in background
     */
    fun showFullScreenOverlay(pickup: String, destination: String, apps: List<String>) {
        if (!canDrawOverlay(this)) {
            Log.w(TAG, "Overlay permission not granted")
            return
        }

        // Hide existing overlays
        hideFullScreenOverlay()

        pickupText = pickup
        destinationText = destination

        // Initialize app prices
        appPrices.clear()
        priceCards.clear()
        for (app in apps) {
            appPrices[app] = Companion.AppPrice(
                packageName = app,
                appName = getAppDisplayName(app),
                price = null,
                status = "waiting"
            )
        }

        try {
            fullScreenOverlay = createFullScreenView()

            val layoutType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            }

            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                layoutType,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT
            )

            windowManager?.addView(fullScreenOverlay, params)
            isFullScreenVisible = true
            Log.i(TAG, "Full-screen overlay shown for ${apps.size} apps")

        } catch (e: Exception) {
            Log.e(TAG, "Error showing full-screen overlay: ${e.message}")
        }
    }

    /**
     * Create the full-screen overlay view
     */
    private fun createFullScreenView(): View {
        val density = resources.displayMetrics.density

        // Root container - full screen with dark background
        val root = FrameLayout(this).apply {
            setBackgroundColor(0xF5121212.toInt()) // Dark background with slight transparency
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }

        // Main content container
        val mainContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            setPadding((16 * density).toInt(), (48 * density).toInt(), (16 * density).toInt(), (16 * density).toInt())
        }

        // Header with GO-ON logo and title
        val header = createHeader(density)
        mainContainer.addView(header)

        // Trip info (pickup → destination)
        val tripInfo = createTripInfo(density)
        mainContainer.addView(tripInfo)

        // "جاري جلب الأسعار..." text
        val loadingText = TextView(this).apply {
            text = "جاري جلب الأسعار..."
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 18f
            gravity = Gravity.CENTER
            setPadding(0, (24 * density).toInt(), 0, (16 * density).toInt())
        }
        mainContainer.addView(loadingText)

        // Scrollable container for price cards
        val scrollView = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        }

        val cardsContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            tag = "cardsContainer"
        }

        // Create price cards for each app
        for ((packageName, appPrice) in appPrices) {
            val card = createPriceCard(packageName, appPrice, density)
            cardsContainer.addView(card)
            priceCards[packageName] = card
        }

        scrollView.addView(cardsContainer)
        mainContainer.addView(scrollView)

        // Close button at bottom
        val closeButton = createCloseButton(density)
        mainContainer.addView(closeButton)

        root.addView(mainContainer)
        return root
    }

    /**
     * Create header with GO-ON branding
     */
    private fun createHeader(density: Float): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, (16 * density).toInt())

            // GO-ON Logo/Icon
            val logo = TextView(this@FloatingOverlayService).apply {
                text = "🚐"
                textSize = 32f
                setPadding(0, 0, (12 * density).toInt(), 0)
            }
            addView(logo)

            // Title
            val title = TextView(this@FloatingOverlayService).apply {
                text = "GO-ON"
                setTextColor(0xFFD69E2E.toInt()) // Gold
                textSize = 28f
                setTypeface(null, Typeface.BOLD)
            }
            addView(title)

            // Subtitle
            val subtitle = TextView(this@FloatingOverlayService).apply {
                text = "  مقارنة الأسعار"
                setTextColor(0xAAFFFFFF.toInt())
                textSize = 16f
            }
            addView(subtitle)
        }
    }

    /**
     * Create trip info display
     */
    private fun createTripInfo(density: Float): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = createRoundedBackground(0xFF1E1E1E.toInt(), (12 * density).toInt())
            setPadding((16 * density).toInt(), (12 * density).toInt(), (16 * density).toInt(), (12 * density).toInt())

            // From
            val fromRow = LinearLayout(this@FloatingOverlayService).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL

                val fromIcon = TextView(this@FloatingOverlayService).apply {
                    text = "📍"
                    textSize = 14f
                    setPadding(0, 0, (8 * density).toInt(), 0)
                }
                addView(fromIcon)

                val fromLabel = TextView(this@FloatingOverlayService).apply {
                    text = "من: "
                    setTextColor(0xAAFFFFFF.toInt())
                    textSize = 12f
                }
                addView(fromLabel)

                val fromValue = TextView(this@FloatingOverlayService).apply {
                    text = pickupText.take(40) + if (pickupText.length > 40) "..." else ""
                    setTextColor(0xFFFFFFFF.toInt())
                    textSize = 13f
                    maxLines = 1
                }
                addView(fromValue)
            }
            addView(fromRow)

            // Arrow
            val arrow = TextView(this@FloatingOverlayService).apply {
                text = "↓"
                setTextColor(0xFF4CAF50.toInt())
                textSize = 16f
                gravity = Gravity.CENTER
                setPadding((20 * density).toInt(), (4 * density).toInt(), 0, (4 * density).toInt())
            }
            addView(arrow)

            // To
            val toRow = LinearLayout(this@FloatingOverlayService).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL

                val toIcon = TextView(this@FloatingOverlayService).apply {
                    text = "🎯"
                    textSize = 14f
                    setPadding(0, 0, (8 * density).toInt(), 0)
                }
                addView(toIcon)

                val toLabel = TextView(this@FloatingOverlayService).apply {
                    text = "إلى: "
                    setTextColor(0xAAFFFFFF.toInt())
                    textSize = 12f
                }
                addView(toLabel)

                val toValue = TextView(this@FloatingOverlayService).apply {
                    text = destinationText.take(40) + if (destinationText.length > 40) "..." else ""
                    setTextColor(0xFFFFFFFF.toInt())
                    textSize = 13f
                    maxLines = 1
                }
                addView(toValue)
            }
            addView(toRow)
        }
    }

    /**
     * Create a price card for an app
     */
    private fun createPriceCard(packageName: String, appPrice: Companion.AppPrice, density: Float): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = createRoundedBackground(0xFF2A2A2A.toInt(), (12 * density).toInt())
            setPadding((16 * density).toInt(), (16 * density).toInt(), (16 * density).toInt(), (16 * density).toInt())
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(0, (8 * density).toInt(), 0, 0)
            }
            tag = "card_$packageName"

            // App icon
            val iconText = TextView(this@FloatingOverlayService).apply {
                text = getAppIcon(packageName)
                textSize = 28f
                setPadding(0, 0, (16 * density).toInt(), 0)
            }
            addView(iconText)

            // App name and status container
            val infoContainer = LinearLayout(this@FloatingOverlayService).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }

            val nameText = TextView(this@FloatingOverlayService).apply {
                text = appPrice.appName
                setTextColor(0xFFFFFFFF.toInt())
                textSize = 16f
                setTypeface(null, Typeface.BOLD)
                tag = "name_$packageName"
            }
            infoContainer.addView(nameText)

            val statusText = TextView(this@FloatingOverlayService).apply {
                text = getStatusText(appPrice.status)
                setTextColor(getStatusColor(appPrice.status))
                textSize = 12f
                tag = "status_$packageName"
            }
            infoContainer.addView(statusText)

            addView(infoContainer)

            // Price or loading indicator
            val priceContainer = LinearLayout(this@FloatingOverlayService).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.END
                tag = "priceContainer_$packageName"
            }

            if (appPrice.status == "loading" || appPrice.status == "waiting") {
                val loadingIndicator = ProgressBar(this@FloatingOverlayService).apply {
                    layoutParams = LinearLayout.LayoutParams((24 * density).toInt(), (24 * density).toInt())
                    tag = "loading_$packageName"
                }
                priceContainer.addView(loadingIndicator)
            } else if (appPrice.price != null && appPrice.price > 0) {
                val priceText = TextView(this@FloatingOverlayService).apply {
                    text = "${appPrice.price.toInt()} ج.م"
                    setTextColor(0xFF4CAF50.toInt())
                    textSize = 20f
                    setTypeface(null, Typeface.BOLD)
                    tag = "price_$packageName"
                }
                priceContainer.addView(priceText)
            } else if (appPrice.status == "error") {
                val errorText = TextView(this@FloatingOverlayService).apply {
                    text = "✗"
                    setTextColor(0xFFFF5252.toInt())
                    textSize = 24f
                }
                priceContainer.addView(errorText)
            }

            addView(priceContainer)
        }
    }

    /**
     * Create close button
     */
    private fun createCloseButton(density: Float): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, (16 * density).toInt(), 0, (16 * density).toInt())

            val button = TextView(this@FloatingOverlayService).apply {
                text = "إغلاق"
                setTextColor(0xFFFFFFFF.toInt())
                textSize = 16f
                background = createRoundedBackground(0xFF424242.toInt(), (24 * density).toInt())
                setPadding((32 * density).toInt(), (12 * density).toInt(), (32 * density).toInt(), (12 * density).toInt())
                setOnClickListener {
                    hideFullScreenOverlay()
                    openGoonApp()
                }
            }
            addView(button)
        }
    }

    /**
     * Update price for a specific app
     */
    fun updatePriceForApp(packageName: String, price: Double?, status: String, eta: String? = null) {
        handler.post {
            appPrices[packageName] = Companion.AppPrice(
                packageName = packageName,
                appName = getAppDisplayName(packageName),
                price = price,
                status = status,
                eta = eta
            )

            // Update the card UI
            val card = priceCards[packageName] ?: return@post
            val density = resources.displayMetrics.density

            // Update status text
            val statusView = card.findViewWithTag<TextView>("status_$packageName")
            statusView?.text = getStatusText(status)
            statusView?.setTextColor(getStatusColor(status))

            // Update price container
            val priceContainer = card.findViewWithTag<LinearLayout>("priceContainer_$packageName")
            priceContainer?.removeAllViews()

            if (status == "loading") {
                val loadingIndicator = ProgressBar(this).apply {
                    layoutParams = LinearLayout.LayoutParams((24 * density).toInt(), (24 * density).toInt())
                }
                priceContainer?.addView(loadingIndicator)
            } else if (price != null && price > 0) {
                val priceText = TextView(this).apply {
                    text = "${price.toInt()} ج.م"
                    setTextColor(0xFF4CAF50.toInt())
                    textSize = 20f
                    setTypeface(null, Typeface.BOLD)
                }
                priceContainer?.addView(priceText)

                // Highlight best price
                highlightBestPrice()
            } else if (status == "error") {
                val errorText = TextView(this).apply {
                    text = "✗"
                    setTextColor(0xFFFF5252.toInt())
                    textSize = 24f
                }
                priceContainer?.addView(errorText)
            }

            Log.i(TAG, "Updated price for $packageName: $price ($status)")
        }
    }

    /**
     * Highlight the best (lowest) price card
     */
    private fun highlightBestPrice() {
        val density = resources.displayMetrics.density
        var bestPackage: String? = null
        var bestPrice = Double.MAX_VALUE

        for ((pkg, appPrice) in appPrices) {
            if (appPrice.price != null && appPrice.price > 0 && appPrice.price < bestPrice) {
                bestPrice = appPrice.price
                bestPackage = pkg
            }
        }

        // Update card backgrounds
        for ((pkg, card) in priceCards) {
            if (pkg == bestPackage) {
                card.background = createRoundedBackground(0xFF1B5E20.toInt(), (12 * density).toInt()) // Dark green
            } else {
                card.background = createRoundedBackground(0xFF2A2A2A.toInt(), (12 * density).toInt())
            }
        }
    }

    /**
     * Hide full-screen overlay
     */
    fun hideFullScreenOverlay() {
        if (isFullScreenVisible && fullScreenOverlay != null) {
            try {
                windowManager?.removeView(fullScreenOverlay)
                fullScreenOverlay = null
                isFullScreenVisible = false
                priceCards.clear()
                appPrices.clear()
                Log.i(TAG, "Full-screen overlay hidden")
            } catch (e: Exception) {
                Log.e(TAG, "Error hiding full-screen overlay: ${e.message}")
            }
        }
    }

    /**
     * Create rounded background drawable
     */
    private fun createRoundedBackground(color: Int, radius: Int): GradientDrawable {
        return GradientDrawable().apply {
            setColor(color)
            cornerRadius = radius.toFloat()
        }
    }

    /**
     * Get display name for app
     */
    private fun getAppDisplayName(packageName: String): String {
        return when (packageName) {
            "com.ubercab" -> "Uber"
            "com.careem.acma" -> "Careem"
            "sinet.startup.inDriver" -> "InDriver"
            "com.didiglobal.passenger" -> "DiDi"
            "ee.mtakso.client" -> "Bolt"
            "com.yango.taxi" -> "Yango"
            else -> packageName.split(".").lastOrNull() ?: packageName
        }
    }

    /**
     * Get emoji icon for app
     */
    private fun getAppIcon(packageName: String): String {
        return when (packageName) {
            "com.ubercab" -> "🚗"
            "com.careem.acma" -> "🚕"
            "sinet.startup.inDriver" -> "🚙"
            "com.didiglobal.passenger" -> "🚖"
            "ee.mtakso.client" -> "🚐"
            "com.yango.taxi" -> "🚐"
            else -> "🚗"
        }
    }

    /**
     * Get status text in Arabic
     */
    private fun getStatusText(status: String): String {
        return when (status) {
            "loading" -> "جاري الجلب..."
            "success" -> "تم"
            "error" -> "فشل"
            "waiting" -> "في الانتظار..."
            else -> status
        }
    }

    /**
     * Get status color
     */
    private fun getStatusColor(status: String): Int {
        return when (status) {
            "loading" -> 0xFFFFB300.toInt() // Amber
            "success" -> 0xFF4CAF50.toInt() // Green
            "error" -> 0xFFFF5252.toInt()   // Red
            "waiting" -> 0xFF9E9E9E.toInt() // Grey
            else -> 0xFFFFFFFF.toInt()
        }
    }
}
