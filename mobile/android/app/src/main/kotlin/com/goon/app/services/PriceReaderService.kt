package com.goon.app.services

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Toast
import org.json.JSONObject
import java.util.regex.Pattern

/**
 * GO-ON Price Reader Accessibility Service - ENHANCED VERSION
 *
 * This service AGGRESSIVELY reads prices from ride-hailing apps
 * using multiple strategies:
 * 1. Event-based monitoring (passive)
 * 2. Active periodic scanning (aggressive)
 * 3. App-specific element targeting (precise)
 * 4. Full tree traversal (comprehensive)
 *
 * IMPORTANT: This requires explicit user permission in Accessibility Settings
 */
class PriceReaderService : AccessibilityService() {

    companion object {
        private const val TAG = "GO-ON-PriceReader"

        // Package names of supported apps
        const val UBER_PACKAGE = "com.ubercab"
        const val CAREEM_PACKAGE = "com.careem.acma"
        const val INDRIVER_PACKAGE = "sinet.startup.inDriver"
        const val DIDI_PACKAGE = "com.didiglobal.passenger"
        const val BOLT_PACKAGE = "ee.mtakso.client"

        // Broadcast action for price updates
        const val ACTION_PRICE_UPDATE = "com.goon.app.PRICE_UPDATE"
        const val EXTRA_PRICE_DATA = "price_data"

        // Price patterns for Egyptian Pounds - COMPREHENSIVE
        private val PRICE_PATTERNS = listOf(
            // EGP formats
            Pattern.compile("EGP\\s*(\\d+[.,]?\\d*)", Pattern.CASE_INSENSITIVE),
            Pattern.compile("(\\d+[.,]?\\d*)\\s*EGP", Pattern.CASE_INSENSITIVE),
            // ج.م formats (Egyptian Arabic)
            Pattern.compile("ج\\.?م\\.?\\s*(\\d+[.,]?\\d*)"),
            Pattern.compile("(\\d+[.,]?\\d*)\\s*ج\\.?م\\.?"),
            // جنيه (Guinee)
            Pattern.compile("جنيه\\s*(\\d+[.,]?\\d*)"),
            Pattern.compile("(\\d+[.,]?\\d*)\\s*جنيه"),
            // LE formats
            Pattern.compile("LE\\s*(\\d+[.,]?\\d*)", Pattern.CASE_INSENSITIVE),
            Pattern.compile("(\\d+[.,]?\\d*)\\s*LE", Pattern.CASE_INSENSITIVE),
            // E£ format
            Pattern.compile("E£\\s*(\\d+[.,]?\\d*)"),
            Pattern.compile("(\\d+[.,]?\\d*)\\s*E£"),
            // Price with range (e.g., "65-75")
            Pattern.compile("(\\d+)\\s*[-–]\\s*\\d+\\s*(?:EGP|ج\\.?م|جنيه|LE)?", Pattern.CASE_INSENSITIVE),
            // Price in format "Fare: 65" or "السعر: 65"
            Pattern.compile("(?:fare|price|السعر|الأجرة)[:\\s]*(\\d+)", Pattern.CASE_INSENSITIVE),
            // Standalone decimal numbers like "61.40" or "67.50" (DiDi format)
            Pattern.compile("^\\s*(\\d{2,3}[.,]\\d{1,2})\\s*$"),
            // Standalone integers 2-3 digits
            Pattern.compile("^\\s*(\\d{2,3})\\s*$")
        )

        // Singleton instance for Flutter communication
        var instance: PriceReaderService? = null
            private set

        // Latest prices from each app
        val latestPrices = mutableMapOf<String, PriceInfo>()

        // Active monitoring state
        var isActiveMonitoring = false
        var monitoringPackage: String? = null
        private var scanHandler: Handler? = null
        private var scanRunnable: Runnable? = null

        // Trip details for automation
        var pickupAddress: String = ""
        var destinationAddress: String = ""
        var pickupLat: Double = 0.0
        var pickupLng: Double = 0.0
        var destLat: Double = 0.0
        var destLng: Double = 0.0

        // User preference for sorting rides
        // Options: "lowest_price", "best_service", "fastest_arrival"
        var rideSortPreference: String = "lowest_price"
    }

    // Service ratings for "best_service" preference
    private val serviceRatings = mapOf(
        UBER_PACKAGE to 4.5,
        CAREEM_PACKAGE to 4.3,
        DIDI_PACKAGE to 4.0,
        BOLT_PACKAGE to 4.2,
        INDRIVER_PACKAGE to 3.8
    )

    data class PriceInfo(
        val appName: String,
        val packageName: String,
        val price: Double,
        val currency: String = "EGP",
        val serviceType: String = "",
        val eta: Int = 0,
        val timestamp: Long = System.currentTimeMillis(),
        val allPricesFound: List<Double> = emptyList(),
        val vehiclePrices: Map<String, Double> = emptyMap(), // Vehicle type -> Price
        val rawTexts: List<String> = emptyList()
    )

    private var isServiceActive = false
    private var lastProcessedTime = 0L
    private val processDebounce = 100L // REDUCED for faster response
    private val handler = Handler(Looper.getMainLooper())
    private var lastInDriverToastTime = 0L
    private val toastDebounce = 3000L // Show toast every 3 seconds max
    private var lastDiDiSelectAddressClickTime = 0L // Cooldown for DiDi address selection
    private var lastInDriverClickTime = 0L // Cooldown for InDriver (crashes with rapid clicks)

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        isServiceActive = true

        Log.i(TAG, "✓ GO-ON Price Reader Service Connected - ENHANCED VERSION")

        // Configure the service for MAXIMUM visibility
        val info = AccessibilityServiceInfo().apply {
            // Listen to ALL relevant events
            eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
                        AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED or
                        AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED or
                        AccessibilityEvent.TYPE_VIEW_SCROLLED or
                        AccessibilityEvent.TYPE_VIEW_FOCUSED or
                        AccessibilityEvent.TYPE_VIEW_CLICKED or
                        AccessibilityEvent.TYPES_ALL_MASK

            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC

            // Monitor ALL apps initially - we'll filter in code
            // This allows us to detect when user switches to ride apps
            packageNames = null  // null = all apps

            notificationTimeout = 10  // FAST response
            flags = AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                   AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS or
                   AccessibilityServiceInfo.FLAG_REQUEST_ENHANCED_WEB_ACCESSIBILITY or
                   AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        }

        serviceInfo = info

        // Initialize scan handler
        scanHandler = Handler(Looper.getMainLooper())
    }

    /**
     * START ACTIVE MONITORING - Called from Flutter when user opens a ride app
     * This will aggressively scan for prices every 500ms
     */
    fun startActiveMonitoring(packageName: String) {
        Log.i(TAG, "🔍 Starting ACTIVE monitoring for: $packageName")
        isActiveMonitoring = true
        monitoringPackage = packageName

        // Stop any existing scan
        stopActiveMonitoring()

        // Create a runnable that scans every 500ms
        scanRunnable = object : Runnable {
            override fun run() {
                if (isActiveMonitoring) {
                    Log.d(TAG, "⏱ Active scan tick for $packageName")
                    performAggressiveScan(packageName)
                    scanHandler?.postDelayed(this, 500)
                }
            }
        }

        // Start scanning immediately
        scanHandler?.post(scanRunnable!!)
    }

    // ==========================================================================
    // AUTOMATION ENGINE - Automatically enter trip details and get real prices
    // ==========================================================================

    private var automationState = AutomationState.IDLE
    private var automationStep = 0
    private var automationRetries = 0
    private val MAX_RETRIES = 10  // Increased for better reliability
    private var automationStartTime = 0L  // Timestamp when automation started
    private var uberScreenReady = false  // Flag to indicate Uber ride selection screen is loaded
    private val UBER_MIN_WAIT_MS = 8000L  // Wait at least 8 seconds for Uber to load prices
    private val UBER_MIN_PRICE = 50.0  // Minimum valid price for Uber

    // Track if InDriver destination was successfully entered (to avoid accepting default price)
    private var inDriverDestinationEntered = false

    // Track when InDriver "Done" button was clicked - need to wait for real price
    private var inDriverDoneClickedTime = 0L
    private val INDRIVER_MIN_WAIT_AFTER_DONE_MS = 1500L  // OPTIMIZED: Reduced from 5000ms to 1500ms

    // Track InDriver confirmation clicks (3 clicks usually enough!)
    // InDriver flow after entering coordinates:
    //   1. Map confirm destination → "تم"
    //   2. Map confirm pickup → "تم"
    //   3. THEN "البحث عن عروض" prices screen appears
    // Note: If price screen is detected early, we skip remaining clicks
    private var inDriverDoneClickCount = 0
    private val INDRIVER_MAX_DONE_CLICKS = 3  // OPTIMIZED: Reduced from 5 to 3 (usually 2 clicks needed)

    // CRITICAL: Block ALL InDriver price detection until automation is complete
    // This prevents the 95 EGP default price from being cached during automation
    private var inDriverAutomationComplete = false

    enum class AutomationState {
        IDLE,
        WAITING_FOR_APP,
        // InDriver-specific states for entering PICKUP first
        FINDING_PICKUP_FIELD,
        ENTERING_PICKUP,
        WAITING_FOR_PICKUP_SUGGESTIONS,
        CONFIRMING_PICKUP_ON_MAP,
        // Common states
        FINDING_DESTINATION_FIELD,
        ENTERING_DESTINATION,
        WAITING_FOR_SUGGESTIONS,
        SELECTING_SUGGESTION,
        WAITING_FOR_PRICE,
        PRICE_CAPTURED,
        FAILED
    }

    // Track if InDriver pickup was successfully entered
    private var inDriverPickupEntered = false

    /**
     * FULLY AUTOMATED PRICE FETCH
     * Opens app, enters destination, captures price, returns to GO-ON
     */
    fun automateGetPrice(
        packageName: String,
        pickup: String,
        destination: String,
        pickupLatitude: Double,
        pickupLongitude: Double,
        destLatitude: Double,
        destLongitude: Double
    ) {
        Log.i(TAG, "🤖 Starting AUTOMATION for $packageName")
        Log.i(TAG, "   From: $pickup")
        Log.i(TAG, "   To: $destination")

        // Store trip details
        pickupAddress = pickup
        destinationAddress = destination
        pickupLat = pickupLatitude
        pickupLng = pickupLongitude
        destLat = destLatitude
        destLng = destLongitude

        // Reset state
        automationStep = 0
        automationRetries = 0
        monitoringPackage = packageName
        isActiveMonitoring = true
        automationStartTime = System.currentTimeMillis()  // Track when automation started
        uberScreenReady = false  // Reset screen ready flag
        inDriverPickupEntered = false  // Reset InDriver pickup flag
        inDriverDestinationEntered = false  // Reset InDriver destination flag
        inDriverDoneClickedTime = 0L  // Reset InDriver Done click time
        inDriverDoneClickCount = 0  // Reset Done click counter (3 clicks needed for InDriver)
        inDriverAutomationComplete = false  // CRITICAL: Block price detection until automation is complete

        // Clear any old cached prices for this app
        latestPrices.remove(packageName)

        // Update overlay to show loading status for this app
        FloatingOverlayService.updateAppPrice(packageName, null, "loading", null)

        // For Uber: deep link includes full coordinates, skip to WAITING_FOR_PRICE
        // For DiDi and others: deep links don't work reliably, go through full flow
        // For InDriver: start with FINDING_PICKUP_FIELD to enter BOTH pickup and destination
        val hasFullDeepLink = packageName == UBER_PACKAGE
        if (hasFullDeepLink) {
            Log.i(TAG, "🤖 Deep link includes coordinates - skipping to WAITING_FOR_PRICE")
            automationState = AutomationState.WAITING_FOR_PRICE
        } else if (packageName == INDRIVER_PACKAGE) {
            Log.i(TAG, "🤖 InDriver: Starting with PICKUP entry first (geo: link doesn't support pickup)")
            automationState = AutomationState.WAITING_FOR_APP
        } else {
            automationState = AutomationState.WAITING_FOR_APP
        }

        // Start automation loop
        startAutomationLoop(packageName)
    }

    private fun startAutomationLoop(packageName: String) {
        scanRunnable = object : Runnable {
            override fun run() {
                if (isActiveMonitoring && automationState != AutomationState.IDLE) {
                    performAutomationStep(packageName)

                    // Continue if not done
                    if (automationState != AutomationState.PRICE_CAPTURED &&
                        automationState != AutomationState.FAILED &&
                        automationState != AutomationState.IDLE) {
                        scanHandler?.postDelayed(this, 1200) // Check every 1.2 seconds - more time for UI to update
                    }
                }
            }
        }
        // Initial delay to let the app load
        scanHandler?.postDelayed(scanRunnable!!, 2000) // Wait 2 seconds before starting
    }

    private fun performAutomationStep(packageName: String) {
        val rootNode = rootInActiveWindow ?: run {
            Log.w(TAG, "🤖 No active window available")
            return
        }

        try {
            val currentPackage = rootNode.packageName?.toString() ?: return

            // Wait for target app to be in foreground
            if (currentPackage != packageName) {
                Log.d(TAG, "🤖 Waiting for $packageName (currently: $currentPackage)")
                return
            }

            Log.i(TAG, "🤖 ========================================")
            Log.i(TAG, "🤖 AUTOMATION STATE: $automationState")
            Log.i(TAG, "🤖 Package: $packageName")
            Log.i(TAG, "🤖 Retries: $automationRetries / $MAX_RETRIES")
            Log.i(TAG, "🤖 Step: $automationStep")
            Log.i(TAG, "🤖 ========================================")

            when (automationState) {
                AutomationState.WAITING_FOR_APP -> {
                    // For InDriver: enter PICKUP first, then destination
                    if (packageName == INDRIVER_PACKAGE) {
                        Log.i(TAG, "🤖 ✓ InDriver active, transitioning to FINDING_PICKUP_FIELD...")
                        automationState = AutomationState.FINDING_PICKUP_FIELD
                    } else {
                        Log.i(TAG, "🤖 ✓ App is active, transitioning to FINDING_DESTINATION_FIELD...")
                        automationState = AutomationState.FINDING_DESTINATION_FIELD
                    }
                }

                // ============================================================
                // InDriver-specific: Enter PICKUP first
                // ============================================================
                AutomationState.FINDING_PICKUP_FIELD -> {
                    Log.i(TAG, "🤖 [InDriver] Searching for PICKUP field ('من')...")

                    val found = findAndClickInDriverPickupField(rootNode)
                    if (found) {
                        Log.i(TAG, "🤖 ✓✓✓ Found pickup field! Transitioning to ENTERING_PICKUP...")
                        automationState = AutomationState.ENTERING_PICKUP
                        automationRetries = 0
                    } else {
                        automationRetries++
                        Log.w(TAG, "🤖 ✗ Pickup field not found (attempt $automationRetries/$MAX_RETRIES)")
                        if (automationRetries > MAX_RETRIES) {
                            Log.e(TAG, "🤖 ✗✗✗ FAILED: Max retries exceeded for finding pickup field")
                            automationState = AutomationState.FAILED
                        }
                    }
                }

                AutomationState.ENTERING_PICKUP -> {
                    Log.i(TAG, "🤖 [InDriver] Entering pickup text: '$pickupAddress' (coords: $pickupLat, $pickupLng)")

                    val entered = enterInDriverPickupText(rootNode)
                    if (entered) {
                        Log.i(TAG, "🤖 ✓ Entered pickup!")
                        inDriverPickupEntered = true
                        latestPrices.remove(packageName)  // Clear any cached price

                        // CRITICAL: Go DIRECTLY to destination field - don't handle suggestions yet!
                        // We need to enter BOTH pickup and destination BEFORE clicking any suggestions
                        Log.i(TAG, "🤖 ✓ Now going DIRECTLY to FINDING_DESTINATION_FIELD (skip suggestions)...")
                        automationState = AutomationState.FINDING_DESTINATION_FIELD
                        automationRetries = 0
                        automationStep = 0
                    } else {
                        Log.w(TAG, "🤖 ✗ Failed to enter pickup text")
                        automationRetries++
                        if (automationRetries > 3) {
                            Log.e(TAG, "🤖 ✗✗✗ InDriver pickup entry FAILED")
                            automationState = AutomationState.FAILED
                        }
                    }
                }

                // WAITING_FOR_PICKUP_SUGGESTIONS is now SKIPPED for InDriver
                // We enter both pickup and destination first, then handle all confirmations together
                AutomationState.WAITING_FOR_PICKUP_SUGGESTIONS -> {
                    // This state is kept for compatibility but should not be reached for InDriver
                    Log.i(TAG, "🤖 [InDriver] WAITING_FOR_PICKUP_SUGGESTIONS (legacy - should not reach here)")
                    automationState = AutomationState.FINDING_DESTINATION_FIELD
                    automationStep = 0
                }

                AutomationState.CONFIRMING_PICKUP_ON_MAP -> {
                    Log.i(TAG, "🤖 [InDriver] Confirming pickup location on map...")
                    // This is handled by handleInDriverIntermediateScreens
                    if (handleInDriverIntermediateScreens(rootNode)) {
                        Log.i(TAG, "🤖 📋 Handled pickup confirmation")
                    }
                    automationStep++
                    if (automationStep > 3) {
                        Log.i(TAG, "🤖 [InDriver] Pickup confirmed, moving to destination entry...")
                        automationState = AutomationState.FINDING_DESTINATION_FIELD
                        automationStep = 0
                    }
                }

                // ============================================================
                // Common destination entry states
                // ============================================================
                AutomationState.FINDING_DESTINATION_FIELD -> {
                    Log.i(TAG, "🤖 Searching for destination field...")

                    // CRITICAL: For InDriver, check if prices are ALREADY cached (InDriver shows prices on initial map)
                    if (packageName == INDRIVER_PACKAGE) {
                        val cachedPrice = latestPrices[packageName]
                        if (cachedPrice != null && cachedPrice.price > 0 && cachedPrice.allPricesFound.isNotEmpty()) {
                            Log.i(TAG, "🤖 ✓✓✓ InDriver PRICES ALREADY CACHED: ${cachedPrice.price} EGP (${cachedPrice.allPricesFound})")
                            Log.i(TAG, "🤖 Skipping to PRICE_CAPTURED - no need for destination automation!")
                            automationState = AutomationState.PRICE_CAPTURED
                            autoReturnToGoOn()
                            return
                        }
                    }

                    val found = findAndClickDestinationField(rootNode, packageName)
                    if (found) {
                        Log.i(TAG, "🤖 ✓✓✓ Found destination field! Transitioning to ENTERING_DESTINATION...")
                        automationState = AutomationState.ENTERING_DESTINATION
                        automationRetries = 0
                    } else {
                        automationRetries++
                        Log.w(TAG, "🤖 ✗ Destination field not found (attempt $automationRetries/$MAX_RETRIES)")
                        if (automationRetries > MAX_RETRIES) {
                            Log.e(TAG, "🤖 ✗✗✗ FAILED: Max retries exceeded for finding destination field")
                            automationState = AutomationState.FAILED
                        }
                    }
                }

                AutomationState.ENTERING_DESTINATION -> {
                    Log.i(TAG, "🤖 Entering destination text: '$destinationAddress'")

                    // CRITICAL: For InDriver, check if prices are ALREADY cached
                    if (packageName == INDRIVER_PACKAGE) {
                        val cachedPrice = latestPrices[packageName]
                        if (cachedPrice != null && cachedPrice.price > 0 && cachedPrice.allPricesFound.isNotEmpty()) {
                            Log.i(TAG, "🤖 ✓✓✓ InDriver PRICES ALREADY CACHED: ${cachedPrice.price} EGP (${cachedPrice.allPricesFound})")
                            Log.i(TAG, "🤖 Skipping to PRICE_CAPTURED - no need to continue automation!")
                            automationState = AutomationState.PRICE_CAPTURED
                            autoReturnToGoOn()
                            return
                        }
                    }

                    val entered = enterDestinationText(rootNode, packageName)
                    if (entered) {
                        Log.i(TAG, "🤖 ✓ Entered destination, transitioning to WAITING_FOR_SUGGESTIONS...")
                        if (packageName == INDRIVER_PACKAGE) {
                            inDriverDestinationEntered = true
                            // CRITICAL: Clear old cached price - we need to wait for the NEW price after destination entry
                            latestPrices.remove(packageName)
                            Log.i(TAG, "🤖 ✓ InDriver destination entry SUCCESS - cleared old price, waiting for new price")
                        }
                        automationState = AutomationState.WAITING_FOR_SUGGESTIONS
                        automationRetries = 0
                        automationStep = 0
                    } else {
                        Log.w(TAG, "🤖 ✗ Failed to enter destination text")
                        automationRetries++
                        if (automationRetries > 3) {
                            if (packageName == INDRIVER_PACKAGE) {
                                // For InDriver: Don't skip - mark as FAILED since we can't trust default prices
                                Log.e(TAG, "🤖 ✗✗✗ InDriver destination entry FAILED - cannot trust default price")
                                automationState = AutomationState.FAILED
                            } else {
                                // For other apps: Skip to suggestion selection anyway
                                Log.w(TAG, "🤖 Skipping to WAITING_FOR_SUGGESTIONS despite enter failure")
                                automationState = AutomationState.WAITING_FOR_SUGGESTIONS
                            }
                            automationRetries = 0
                        }
                    }
                }

                AutomationState.WAITING_FOR_SUGGESTIONS -> {
                    // Wait a moment then try to select
                    automationStep++
                    Log.i(TAG, "🤖 Waiting for suggestions (step $automationStep/3)...")

                    // For InDriver: Handle intermediate screens (No Results, Map Confirmation)
                    // Price detection is BLOCKED during automation, so we just handle UI navigation
                    if (packageName == INDRIVER_PACKAGE) {
                        if (handleInDriverIntermediateScreens(rootNode)) {
                            Log.i(TAG, "🤖 📋 Handled InDriver intermediate screen")
                            // Don't transition yet - stay in this state until intermediate screens are done
                        }
                        // Always go to WAITING_FOR_PRICE after some steps
                        if (automationStep > 3) {
                            Log.i(TAG, "🤖 InDriver: Going to WAITING_FOR_PRICE...")
                            automationState = AutomationState.WAITING_FOR_PRICE
                            automationStep = 0
                        }
                        return
                    }

                    // CRITICAL: DiDi may show "Select Address" screen here
                    if (packageName == DIDI_PACKAGE) {
                        if (handleDiDiIntermediateScreens(rootNode)) {
                            Log.i(TAG, "🤖 📋 Handled DiDi intermediate screen during WAITING_FOR_SUGGESTIONS")
                            // Don't return - continue to check state transition
                        }
                    }

                    // After enough time, transition to SELECTING_SUGGESTION or WAITING_FOR_PRICE
                    if (automationStep > 3) { // Wait ~3.6 seconds for suggestions
                        Log.i(TAG, "🤖 Transitioning to SELECTING_SUGGESTION...")
                        automationState = AutomationState.SELECTING_SUGGESTION
                        automationStep = 0
                    }
                }

                AutomationState.SELECTING_SUGGESTION -> {
                    Log.i(TAG, "🤖 Selecting first suggestion...")

                    // InDriver should not reach this state (handled separately)
                    // But if it does, just go to WAITING_FOR_PRICE
                    if (packageName == INDRIVER_PACKAGE) {
                        Log.i(TAG, "🤖 InDriver: Skipping SELECTING_SUGGESTION, going to WAITING_FOR_PRICE")
                        automationState = AutomationState.WAITING_FOR_PRICE
                        automationStep = 0
                        return
                    }

                    // CRITICAL: DiDi may show "Select Address" screen here
                    if (packageName == DIDI_PACKAGE) {
                        if (handleDiDiIntermediateScreens(rootNode)) {
                            Log.i(TAG, "🤖 📋 Handled DiDi intermediate screen during SELECTING_SUGGESTION")
                            return
                        }
                    }

                    val selected = selectFirstSuggestion(rootNode, packageName)
                    if (selected) {
                        Log.i(TAG, "🤖 ✓ Selected suggestion, transitioning to WAITING_FOR_PRICE...")
                        automationState = AutomationState.WAITING_FOR_PRICE
                        automationRetries = 0
                        automationStep = 0
                    } else {
                        automationRetries++
                        Log.w(TAG, "🤖 ✗ No suggestion selected (attempt $automationRetries/$MAX_RETRIES)")
                        if (automationRetries > MAX_RETRIES) {
                            // Try scanning for price anyway
                            Log.w(TAG, "🤖 Skipping to WAITING_FOR_PRICE despite selection failure")
                            automationState = AutomationState.WAITING_FOR_PRICE
                            automationStep = 0
                        }
                    }
                }

                AutomationState.WAITING_FOR_PRICE -> {
                    val elapsedTime = System.currentTimeMillis() - automationStartTime
                    val isUber = packageName == UBER_PACKAGE
                    val isInDriver = packageName == INDRIVER_PACKAGE
                    val cachedPrice = latestPrices[packageName]

                    Log.i(TAG, "🤖 Waiting for price (elapsed: ${elapsedTime}ms, cached: ${cachedPrice?.price ?: 0} EGP)...")

                    // CRITICAL: Check for intermediate screens FIRST before accepting any price
                    // DiDi may still be on "Select Address", InDriver may be on "No Results" or map confirmation
                    var onIntermediateScreen = false
                    if (packageName == DIDI_PACKAGE) {
                        if (handleDiDiIntermediateScreens(rootNode)) {
                            Log.i(TAG, "🤖 📋 Handled DiDi intermediate screen during WAITING_FOR_PRICE")
                            return
                        }
                    }
                    if (isInDriver) {
                        if (handleInDriverIntermediateScreens(rootNode)) {
                            Log.i(TAG, "🤖 📋 InDriver intermediate screen detected in WAITING_FOR_PRICE - NOT accepting price yet")
                            onIntermediateScreen = true
                            // Don't return - we need to keep trying
                        }
                    }

                    // CRITICAL: For InDriver, handle price detection specially
                    // Price detection is BLOCKED until automation is complete
                    // InDriver: Check if automation is already complete (price screen detected early)
                    if (isInDriver) {
                        // OPTIMIZATION: If price screen was already detected, skip all waiting!
                        if (inDriverAutomationComplete) {
                            Log.i(TAG, "🤖 ✓ InDriver automation already complete - scanning for price NOW!")
                            val priceInfo = performAggressiveScan(packageName)
                            if (priceInfo != null && priceInfo.price > 0) {
                                Log.i(TAG, "🤖 ✓✓✓ InDriver REAL PRICE FOUND: ${priceInfo.price} EGP")
                                automationState = AutomationState.PRICE_CAPTURED
                                notifyPriceCaptured(priceInfo)
                                autoReturnToGoOn()
                                return
                            }
                            // Price not found yet, continue scanning
                            return
                        }

                        if (onIntermediateScreen) {
                            Log.d(TAG, "🤖 InDriver on intermediate screen - will click through")
                            return
                        }

                        val timeSinceDoneClick = if (inDriverDoneClickedTime > 0) System.currentTimeMillis() - inDriverDoneClickedTime else Long.MAX_VALUE
                        val bothClicksMade = inDriverDoneClickCount >= INDRIVER_MAX_DONE_CLICKS
                        val doneWaitSatisfied = bothClicksMade && timeSinceDoneClick >= INDRIVER_MIN_WAIT_AFTER_DONE_MS

                        if (!doneWaitSatisfied) {
                            if (!bothClicksMade) {
                                Log.i(TAG, "🤖 ⏳ InDriver clicks: $inDriverDoneClickCount/$INDRIVER_MAX_DONE_CLICKS")
                            } else {
                                Log.i(TAG, "🤖 ⏳ InDriver wait: ${timeSinceDoneClick}ms/${INDRIVER_MIN_WAIT_AFTER_DONE_MS}ms")
                            }
                            return
                        }

                        // Both clicks made and wait time satisfied - NOW enable price detection
                        Log.i(TAG, "🤖 ✓ InDriver clicks + wait done - ENABLING price detection!")
                        inDriverAutomationComplete = true

                        // Now actively scan for price
                        val priceInfo = performAggressiveScan(packageName)
                        if (priceInfo != null && priceInfo.price > 0) {
                            Log.i(TAG, "🤖 ✓✓✓ InDriver REAL PRICE FOUND: ${priceInfo.price} EGP")
                            automationState = AutomationState.PRICE_CAPTURED
                            notifyPriceCaptured(priceInfo)
                            autoReturnToGoOn()
                            return
                        } else {
                            Log.i(TAG, "🤖 InDriver scanning for price...")
                            return
                        }
                    }

                    // For Uber: Wait minimum time to let prices fully load
                    if (isUber && elapsedTime < UBER_MIN_WAIT_MS) {
                        Log.i(TAG, "🤖 ⏳ Uber: Waiting (${elapsedTime}ms < ${UBER_MIN_WAIT_MS}ms)")
                        return
                    }

                    // After minimum wait, check cache (for non-InDriver apps)
                    if (cachedPrice != null && cachedPrice.price > 0 && cachedPrice.allPricesFound.size >= 2) {
                        Log.i(TAG, "🤖 ✓✓✓ PRICE FOUND: ${cachedPrice.price} EGP (${cachedPrice.allPricesFound.size} prices: ${cachedPrice.allPricesFound})")
                        automationState = AutomationState.PRICE_CAPTURED
                        autoReturnToGoOn()
                        return
                    }

                    // No valid cache, try aggressive scan
                    Log.i(TAG, "🤖 No valid cache, trying aggressive scan...")
                    val priceInfo = performAggressiveScan(packageName)
                    if (priceInfo != null && priceInfo.price > 0 && priceInfo.allPricesFound.size >= 2) {
                        Log.i(TAG, "🤖 ✓✓✓ PRICE SCANNED: ${priceInfo.price} EGP (${priceInfo.allPricesFound.size} prices)")
                        automationState = AutomationState.PRICE_CAPTURED
                        notifyPriceCaptured(priceInfo)
                        autoReturnToGoOn()
                        return
                    }

                    // Still no valid prices, check timeout
                    automationStep++
                    if (elapsedTime > 30000) { // 30 seconds total timeout
                        // Accept whatever we have if any
                        if (cachedPrice != null && cachedPrice.price > 0) {
                            Log.i(TAG, "🤖 ⏱️ Timeout - accepting available price: ${cachedPrice.price} EGP")
                            automationState = AutomationState.PRICE_CAPTURED
                            autoReturnToGoOn()
                        } else {
                            Log.e(TAG, "🤖 ✗✗✗ FAILED: Timeout - no prices found")
                            automationState = AutomationState.FAILED
                            autoReturnToGoOn()
                        }
                    }
                }

                else -> {
                    Log.d(TAG, "🤖 Unhandled state: $automationState")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Automation error: ${e.message}")
        } finally {
            try { rootNode.recycle() } catch (e: Exception) {}
        }
    }

    /**
     * Find and click on the destination/search field
     */
    private fun findAndClickDestinationField(rootNode: AccessibilityNodeInfo, packageName: String): Boolean {
        Log.i(TAG, "🔍 Searching for destination field in $packageName...")

        // Special handling for InDriver
        if (packageName == INDRIVER_PACKAGE) {
            return findAndClickInDriverDestination(rootNode)
        }

        // App-specific field identifiers - EXPANDED for DiDi
        val searchTexts = when (packageName) {
            UBER_PACKAGE -> listOf("Where to?", "إلى أين؟", "Search", "بحث", "Enter destination", "Where to")
            CAREEM_PACKAGE -> listOf("Where to?", "إلى أين؟", "Search destination", "وجهتك", "Where would you like to go")
            DIDI_PACKAGE -> listOf("Where to?", "Where to", "إلى أين", "إلى أين؟", "Destination", "Search", "输入目的地", "去哪儿")
            BOLT_PACKAGE -> listOf("Where to?", "إلى أين؟", "Search", "Enter destination", "Where to")
            else -> listOf("Where to?", "إلى أين؟", "Search", "Destination")
        }

        // Debug: Log all text visible on screen
        logAllVisibleText(rootNode, 0)

        // Strategy 1: Try to find by exact text match
        for (searchText in searchTexts) {
            val nodes = rootNode.findAccessibilityNodeInfosByText(searchText)
            Log.d(TAG, "Found ${nodes.size} nodes for text: '$searchText'")

            for (node in nodes) {
                // Log node details
                Log.d(TAG, "  Node: class=${node.className}, clickable=${node.isClickable}, enabled=${node.isEnabled}, text='${node.text}'")

                if (node.isClickable) {
                    val clicked = node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    if (clicked) {
                        Log.i(TAG, "✓ Clicked on: $searchText")
                        node.recycle()
                        return true
                    }
                }

                // Try clicking parent if node isn't clickable
                val parent = node.parent
                if (parent != null) {
                    Log.d(TAG, "  Parent: class=${parent.className}, clickable=${parent.isClickable}")
                    if (parent.isClickable) {
                        val clicked = parent.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                        if (clicked) {
                            Log.i(TAG, "✓ Clicked on parent of: $searchText")
                            parent.recycle()
                            node.recycle()
                            return true
                        }
                    }

                    // Try grandparent
                    val grandparent = parent.parent
                    if (grandparent != null && grandparent.isClickable) {
                        val clicked = grandparent.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                        if (clicked) {
                            Log.i(TAG, "✓ Clicked on grandparent of: $searchText")
                            grandparent.recycle()
                            parent.recycle()
                            node.recycle()
                            return true
                        }
                        grandparent.recycle()
                    }
                    parent.recycle()
                }
                node.recycle()
            }
        }

        // Strategy 2: Find any clickable element that looks like a search/destination field
        val clickableSearchField = findClickableSearchField(rootNode)
        if (clickableSearchField) {
            return true
        }

        // Strategy 3: Try to find EditText fields directly
        return findAndClickEditText(rootNode)
    }

    /**
     * Find and click InDriver PICKUP field ("من" - From)
     * InDriver route entry screen has two fields:
     *   - "من" (From) - for pickup location
     *   - "إلى" (To) - for destination
     * We need to click "من" first to enter pickup, then "إلى" for destination
     */
    private fun findAndClickInDriverPickupField(rootNode: AccessibilityNodeInfo): Boolean {
        Log.i(TAG, "🚗 ========== INDRIVER PICKUP FIELD SEARCH ==========")
        Log.i(TAG, "🚗 Pickup to enter: $pickupAddress (coords: $pickupLat, $pickupLng)")

        // Debug: Log ALL visible text on screen
        Log.i(TAG, "🚗 === ALL VISIBLE TEXT ON SCREEN ===")
        logAllVisibleTextDetailed(rootNode, 0)
        Log.i(TAG, "🚗 === END OF VISIBLE TEXT ===")

        // InDriver pickup field texts - Arabic and English
        // The pickup field is labeled "من" (From) in Arabic
        val pickupTexts = listOf(
            // Primary pickup field texts
            "من", "From", "من أين", "من اين", "Pickup",
            // "Enter your route" screen main text
            "أدخل مسارك", "Enter your route",
            // Current location text (might be shown as pickup)
            "الموقع الحالي", "Current location", "موقعك الحالي", "Your location",
            // Pickup-specific
            "نقطة الانطلاق", "نقطة الإقلال", "Starting point", "Pick up",
            // Where from
            "من أين تريد", "Where from"
        )

        // Strategy 1: Find by EXACT text match for "من" field
        Log.i(TAG, "🚗 Strategy 1: Searching for pickup field by exact text...")
        for (searchText in pickupTexts) {
            val nodes = rootNode.findAccessibilityNodeInfosByText(searchText)
            if (nodes.isNotEmpty()) {
                Log.i(TAG, "🚗 Found ${nodes.size} nodes for '$searchText'")
            }

            for (node in nodes) {
                val nodeText = node.text?.toString() ?: ""
                val nodeClass = node.className?.toString()?.substringAfterLast(".") ?: ""
                Log.i(TAG, "🚗   → [$nodeClass] text='$nodeText', clickable=${node.isClickable}, focusable=${node.isFocusable}")

                // Try clicking the node itself
                if (node.isClickable) {
                    Log.i(TAG, "🚗   Attempting to click pickup field node...")
                    if (node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                        Log.i(TAG, "🚗 ✓✓✓ SUCCESS: Clicked pickup field '$searchText'")
                        node.recycle()
                        return true
                    }
                }

                // Try focus + click
                if (node.isFocusable) {
                    Log.i(TAG, "🚗   Attempting to focus then click...")
                    node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
                    Thread.sleep(100)
                    if (node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                        Log.i(TAG, "🚗 ✓✓✓ SUCCESS: Focus+Click on pickup field '$searchText'")
                        node.recycle()
                        return true
                    }
                }

                // Try clicking parent (up to 5 levels)
                var current = node.parent
                for (level in 1..5) {
                    if (current == null) break
                    val parentClass = current.className?.toString()?.substringAfterLast(".") ?: ""
                    Log.i(TAG, "🚗   Parent L$level: [$parentClass] clickable=${current.isClickable}")

                    if (current.isClickable) {
                        Log.i(TAG, "🚗   Attempting to click parent L$level...")
                        if (current.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                            Log.i(TAG, "🚗 ✓✓✓ SUCCESS: Clicked parent L$level of pickup field '$searchText'")
                            current.recycle()
                            node.recycle()
                            return true
                        }
                    }
                    val next = current.parent
                    current.recycle()
                    current = next
                }
                node.recycle()
            }
        }

        // Strategy 2: Look for "أدخل مسارك" (Enter your route) screen and click on address bar
        Log.i(TAG, "🚗 Strategy 2: Looking for route entry address bar...")
        val routeEntryTexts = listOf(
            "أدخل مسارك", "Enter your route", "ما الوجهة", "What's the destination"
        )
        for (searchText in routeEntryTexts) {
            val nodes = rootNode.findAccessibilityNodeInfosByText(searchText)
            for (node in nodes) {
                // Click the address bar which is usually a sibling or parent
                var parent = node.parent
                for (level in 1..3) {
                    if (parent == null) break
                    if (parent.isClickable) {
                        if (parent.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                            Log.i(TAG, "🚗 ✓✓✓ Clicked route entry parent for: $searchText")
                            parent.recycle()
                            node.recycle()
                            return true
                        }
                    }
                    val next = parent.parent
                    parent.recycle()
                    parent = next
                }
                node.recycle()
            }
        }

        Log.e(TAG, "🚗 ✗✗✗ FAILED: Could not find pickup field in InDriver")
        return false
    }

    /**
     * Enter PICKUP coordinates/text into InDriver's focused input field
     * IMPORTANT: First clears existing "الموقع الحالي" (Current Location) by clicking X button
     */
    private fun enterInDriverPickupText(rootNode: AccessibilityNodeInfo): Boolean {
        Log.i(TAG, "🤖 enterInDriverPickupText: Entering pickup coordinates...")

        // STEP 0: Check if the pickup field already has content that needs clearing
        // If field is empty (shows "من" placeholder), skip clearing
        val pickupField = findPickupEditText(rootNode)
        val currentText = pickupField?.text?.toString() ?: ""
        pickupField?.recycle()

        val isFieldEmpty = currentText.isBlank() || currentText == "من" || currentText == "From" ||
                           currentText == "من أين" || currentText.length < 3

        if (isFieldEmpty) {
            Log.i(TAG, "🤖 Pickup field is empty ('$currentText') - no need to clear, will enter text directly")
        } else {
            // STEP 1: Clear existing pickup location by clicking "x" button
            // InDriver pre-fills "الموقع الحالي" (Current Location) which we need to clear first
            Log.i(TAG, "🤖 STEP 1: Looking for 'x' clear button to remove current location...")
            val cleared = clearInDriverPickupField(rootNode)
            if (cleared) {
                Log.i(TAG, "🤖 ✓ Cleared existing pickup location")
                // Wait a moment for UI to update after clearing
                Thread.sleep(300)
            } else {
                Log.w(TAG, "🤖 Could not find/click clear button - will try to overwrite")
            }
        }

        // STEP 2: Enter new pickup coordinates
        val textToEnter = "$pickupLat, $pickupLng"
        Log.i(TAG, "🤖 STEP 2: Entering pickup coordinates: $textToEnter")

        val focusedNode = rootNode.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        if (focusedNode != null) {
            val focusedClass = focusedNode.className?.toString()?.substringAfterLast(".") ?: ""
            val focusedText = focusedNode.text?.toString() ?: ""
            Log.i(TAG, "🤖 Found focused node: [$focusedClass] text='$focusedText'")

            // Clear existing text and set new text
            val args = android.os.Bundle()
            args.putCharSequence(
                AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                textToEnter
            )
            val result = focusedNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
            focusedNode.recycle()

            if (result) {
                Log.i(TAG, "🤖 ✓ Successfully entered pickup: $textToEnter")
                return true
            } else {
                Log.w(TAG, "🤖 ✗ ACTION_SET_TEXT failed on focused node")
            }
        } else {
            Log.w(TAG, "🤖 No focused input node found")
        }

        // Fallback: find any editable field and enter text
        Log.i(TAG, "🤖 Fallback: searching for any EditText field...")
        return enterTextIntoAnyEditText(rootNode, textToEnter)
    }

    /**
     * Find the pickup EditText field in InDriver
     */
    private fun findPickupEditText(rootNode: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        // Try to find by resource ID first
        val pickupIds = listOf(
            "sinet.startup.inDriver:id/address_edittext_from",
            "sinet.startup.indriver:id/address_edittext_from"
        )
        for (id in pickupIds) {
            val nodes = rootNode.findAccessibilityNodeInfosByViewId(id)
            if (nodes.isNotEmpty()) {
                return nodes[0]
            }
        }

        // Fallback: find EditText with "من" text
        val nodes = rootNode.findAccessibilityNodeInfosByText("من")
        for (node in nodes) {
            if (node.className?.toString()?.contains("EditText") == true) {
                return node
            }
            node.recycle()
        }
        return null
    }

    /**
     * Clear InDriver's pickup field by clicking the "x" clear button
     * This removes "الموقع الحالي" (Current Location) so we can enter custom pickup
     */
    private fun clearInDriverPickupField(rootNode: AccessibilityNodeInfo): Boolean {
        Log.i(TAG, "🗑️ Looking for clear button (x) to remove current pickup...")

        // Log all visible elements for debugging
        Log.i(TAG, "🗑️ === Searching for clear button ===")
        logAllClearButtonCandidates(rootNode, 0)

        // Strategy 0: Find by resource ID first (most reliable)
        // The clear button in InDriver has ID: etl_end_button
        val clearButtonIds = listOf(
            "sinet.startup.inDriver:id/etl_end_button",
            "sinet.startup.indriver:id/etl_end_button"
        )
        for (id in clearButtonIds) {
            val nodes = rootNode.findAccessibilityNodeInfosByViewId(id)
            for (node in nodes) {
                if (node.isClickable) {
                    val desc = node.contentDescription?.toString() ?: ""
                    Log.i(TAG, "🗑️ Found clear button by ID: desc='$desc'")
                    if (node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                        Log.i(TAG, "🗑️ ✓ Clicked clear button (etl_end_button)")
                        node.recycle()
                        return true
                    }
                }
                node.recycle()
            }
        }

        // Strategy 1: Find by content description (accessibility label)
        // IMPORTANT: Do NOT include "إغلاق" (Close) - that's the cancel button!
        val clearDescriptions = listOf(
            "مسح", "Clear", "حذف", "Delete", "إزالة", "Remove",
            "clear", "x", "×", "✕", "✖"
        )

        for (desc in clearDescriptions) {
            val nodes = rootNode.findAccessibilityNodeInfosByText(desc)
            for (node in nodes) {
                val nodeDesc = node.contentDescription?.toString() ?: ""
                val nodeText = node.text?.toString() ?: ""
                Log.i(TAG, "🗑️ Found node: text='$nodeText', desc='$nodeDesc', clickable=${node.isClickable}")

                if (node.isClickable) {
                    if (node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                        Log.i(TAG, "🗑️ ✓ Clicked clear button: '$desc'")
                        node.recycle()
                        return true
                    }
                }

                // Try clicking parent
                val parent = node.parent
                if (parent != null && parent.isClickable) {
                    if (parent.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                        Log.i(TAG, "🗑️ ✓ Clicked clear button parent: '$desc'")
                        parent.recycle()
                        node.recycle()
                        return true
                    }
                    parent.recycle()
                }
                node.recycle()
            }
        }

        // Strategy 2: Find by clicking the first small clickable icon (likely the X button)
        // In InDriver, the X is usually a small ImageView/Icon on the left of the text field
        Log.i(TAG, "🗑️ Strategy 2: Looking for small clickable icons...")
        val iconClicked = findAndClickSmallIcon(rootNode)
        if (iconClicked) {
            return true
        }

        // Strategy 3: Find by resource ID patterns
        Log.i(TAG, "🗑️ Strategy 3: Looking by resource ID patterns...")
        val found = findClearButtonByTraversal(rootNode)
        if (found) {
            return true
        }

        Log.w(TAG, "🗑️ Could not find clear button")
        return false
    }

    /**
     * Log all potential clear button candidates for debugging
     */
    private fun logAllClearButtonCandidates(node: AccessibilityNodeInfo, depth: Int) {
        if (depth > 15) return

        val className = node.className?.toString()?.substringAfterLast(".") ?: ""
        val text = node.text?.toString() ?: ""
        val desc = node.contentDescription?.toString() ?: ""
        val viewId = node.viewIdResourceName ?: ""
        val bounds = android.graphics.Rect()
        node.getBoundsInScreen(bounds)
        val size = "${bounds.width()}x${bounds.height()}"

        // Log clickable elements and small elements (potential icons)
        if (node.isClickable || bounds.width() in 20..100 && bounds.height() in 20..100) {
            val prefix = "  ".repeat(depth)
            Log.i(TAG, "🗑️ $prefix[$className] click=${node.isClickable} size=$size text='$text' desc='$desc' id='$viewId'")
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            logAllClearButtonCandidates(child, depth + 1)
            child.recycle()
        }
    }

    /**
     * Find and click a small icon button (like X clear button)
     * These are typically 24x24 to 48x48 pixels
     */
    private fun findAndClickSmallIcon(node: AccessibilityNodeInfo): Boolean {
        val className = node.className?.toString()?.lowercase() ?: ""
        val bounds = android.graphics.Rect()
        node.getBoundsInScreen(bounds)

        // Small clickable element (20-80 pixels) - likely an icon button
        val isSmallClickable = node.isClickable &&
            bounds.width() in 20..100 && bounds.height() in 20..100

        // Check if it's an Image-like component
        val isImageLike = className.contains("image") || className.contains("icon") ||
            className.contains("button") || className.contains("view")

        if (isSmallClickable && isImageLike) {
            val desc = node.contentDescription?.toString()?.lowercase() ?: ""
            val viewId = node.viewIdResourceName?.lowercase() ?: ""

            // Skip navigation/back buttons
            if (desc.contains("back") || desc.contains("رجوع") ||
                viewId.contains("back") || viewId.contains("navigation")) {
                // Skip back buttons
            } else {
                Log.i(TAG, "🗑️ Found small clickable icon: ${bounds.width()}x${bounds.height()} class=$className")

                // Try gesture click for better reliability
                val centerX = bounds.centerX().toFloat()
                val centerY = bounds.centerY().toFloat()

                if (clickAtPosition(centerX, centerY)) {
                    Log.i(TAG, "🗑️ ✓ Clicked small icon via gesture at ($centerX, $centerY)")
                    return true
                }

                // Fallback to ACTION_CLICK
                if (node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                    Log.i(TAG, "🗑️ ✓ Clicked small icon via ACTION_CLICK")
                    return true
                }
            }
        }

        // Check children
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            if (findAndClickSmallIcon(child)) {
                child.recycle()
                return true
            }
            child.recycle()
        }

        return false
    }

    /**
     * Find InDriver destination field by resource ID "address_edittext_to"
     * This is the most reliable way to find the correct field
     */
    private fun findDestinationFieldById(node: AccessibilityNodeInfo): Boolean {
        val viewId = node.viewIdResourceName?.lowercase() ?: ""
        val nodeText = node.text?.toString() ?: ""
        val className = node.className?.toString()?.substringAfterLast(".") ?: ""

        // Look for the destination EditText specifically
        if (viewId.contains("address_edittext_to") || viewId.contains("edittext_to")) {
            Log.i(TAG, "🎯 Found destination field by ID: '$viewId' text='$nodeText'")

            // Make sure it's NOT containing coordinates (pickup field)
            if (nodeText.contains(",") && nodeText.matches(Regex(".*\\d+\\.\\d+.*"))) {
                Log.i(TAG, "🎯 SKIP - contains coordinates (wrong field)")
            } else {
                // Try to click
                if (node.isClickable && node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                    Log.i(TAG, "🎯 ✓✓✓ Clicked destination field by ID!")
                    return true
                }

                // Try focus then click
                if (node.isFocusable) {
                    node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
                    Thread.sleep(100)
                    if (node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                        Log.i(TAG, "🎯 ✓✓✓ Focus+Click destination field by ID!")
                        return true
                    }
                }

                // Try parent click
                val parent = node.parent
                if (parent != null && parent.isClickable) {
                    if (parent.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                        Log.i(TAG, "🎯 ✓✓✓ Clicked destination field parent!")
                        parent.recycle()
                        return true
                    }
                    parent.recycle()
                }
            }
        }

        // Check children
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            if (findDestinationFieldById(child)) {
                child.recycle()
                return true
            }
            child.recycle()
        }

        return false
    }

    /**
     * Find InDriver destination field by position on screen
     * The destination field ("إلى") is the second input field, below pickup
     * It should be in the top portion of the screen, NOT in the suggestions list
     * CRITICAL: Must NOT click on pickup field which has coordinates!
     */
    private fun findDestinationFieldByPosition(node: AccessibilityNodeInfo): Boolean {
        val bounds = android.graphics.Rect()
        node.getBoundsInScreen(bounds)
        val className = node.className?.toString()?.lowercase() ?: ""
        val text = node.text?.toString() ?: ""

        // SKIP if this contains coordinates (it's the pickup field!)
        if (text.contains(",") && text.matches(Regex(".*\\d+\\.\\d+.*"))) {
            // This is the pickup field with coordinates - skip!
            for (i in 0 until node.childCount) {
                val child = node.getChild(i) ?: continue
                if (findDestinationFieldByPosition(child)) {
                    child.recycle()
                    return true
                }
                child.recycle()
            }
            return false
        }

        // Look for clickable/focusable elements in the top portion of screen (y < 600)
        // that could be the destination input field
        val isTopOfScreen = bounds.top in 100..600
        val isClickableOrFocusable = node.isClickable || node.isFocusable
        val isInputLike = className.contains("edit") || className.contains("text") ||
            className.contains("input") || className.contains("view")

        // Check if this looks like the destination field
        // It should be empty or contain placeholder text like "إلى"
        val isDestinationField = isTopOfScreen && isClickableOrFocusable && isInputLike &&
            (text.isEmpty() || text == "إلى" || text == "To" || text.contains("الوجهة"))

        if (isDestinationField) {
            Log.i(TAG, "🎯 Found potential destination field: y=${bounds.top}, text='$text', class=$className")

            if (node.isClickable && node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                Log.i(TAG, "🎯 ✓ Clicked destination field by position")
                return true
            }

            if (node.isFocusable) {
                node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
                if (node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                    Log.i(TAG, "🎯 ✓ Focus+Click destination field by position")
                    return true
                }
            }
        }

        // Check children
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            if (findDestinationFieldByPosition(child)) {
                child.recycle()
                return true
            }
            child.recycle()
        }

        return false
    }

    /**
     * Traverse UI tree to find clear/close button by class and position
     */
    private fun findClearButtonByTraversal(node: AccessibilityNodeInfo): Boolean {
        val className = node.className?.toString()?.lowercase() ?: ""
        val desc = node.contentDescription?.toString()?.lowercase() ?: ""
        val viewId = node.viewIdResourceName?.lowercase() ?: ""

        // Look for ImageButton/ImageView that might be a clear button
        val isClearButton = (className.contains("imagebutton") || className.contains("imageview")) &&
            (desc.contains("clear") || desc.contains("close") || desc.contains("مسح") ||
             desc.contains("delete") || desc.contains("حذف") ||
             viewId.contains("clear") || viewId.contains("close") || viewId.contains("delete"))

        if (isClearButton && node.isClickable) {
            Log.i(TAG, "🗑️ Found potential clear button: class=$className, desc=$desc, viewId=$viewId")
            if (node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                Log.i(TAG, "🗑️ ✓ Clicked clear button via traversal")
                return true
            }
        }

        // Check children
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            if (findClearButtonByTraversal(child)) {
                child.recycle()
                return true
            }
            child.recycle()
        }

        return false
    }

    /**
     * Special handling for InDriver destination field ("إلى")
     * IMPORTANT: This must find the destination INPUT FIELD, not suggestion items!
     * After entering pickup, the suggestions list appears - we need to click on "إلى" field above it
     */
    private fun findAndClickInDriverDestination(rootNode: AccessibilityNodeInfo): Boolean {
        Log.i(TAG, "🚗 ========== INDRIVER DESTINATION FIELD SEARCH ==========")
        Log.i(TAG, "🚗 Destination to enter: $destinationAddress")
        Log.i(TAG, "🚗 Pickup already entered: $inDriverPickupEntered")

        // If pickup was already entered, we need to find the "إلى" field specifically
        // CRITICAL: Must NOT click on the pickup field which already has coordinates!
        if (inDriverPickupEntered) {
            Log.i(TAG, "🚗 Looking for 'إلى' field specifically (pickup was already entered)...")

            // Strategy 0: Find by resource ID "address_edittext_to" (MOST RELIABLE!)
            Log.i(TAG, "🚗 Strategy 0: Looking for address_edittext_to by resource ID...")
            val destFieldById = findDestinationFieldById(rootNode)
            if (destFieldById) {
                return true
            }

            // Strategy A: Find "إلى" text that is near the top of the screen (input field, not suggestion)
            Log.i(TAG, "🚗 Strategy A: Looking for 'إلى' text...")
            val destinationFieldTexts = listOf("إلى", "To")
            for (searchText in destinationFieldTexts) {
                val nodes = rootNode.findAccessibilityNodeInfosByText(searchText)
                for (node in nodes) {
                    val bounds = android.graphics.Rect()
                    node.getBoundsInScreen(bounds)
                    val nodeText = node.text?.toString() ?: ""
                    val nodeClass = node.className?.toString()?.substringAfterLast(".") ?: ""
                    val viewId = node.viewIdResourceName ?: ""

                    Log.i(TAG, "🚗 Found '$searchText': [$nodeClass] y=${bounds.top} text='$nodeText' id='$viewId'")

                    // SKIP if this contains coordinates (it's the pickup field!)
                    if (nodeText.contains(",") && nodeText.matches(Regex(".*\\d+\\.\\d+.*"))) {
                        Log.i(TAG, "🚗 SKIP - this is the pickup field with coordinates!")
                        node.recycle()
                        continue
                    }

                    // The destination field should be the "إلى" text or address_edittext_to
                    val isDestinationField = viewId.contains("address_edittext_to") ||
                        nodeText == "إلى" || nodeText == "To"

                    if (isDestinationField && (node.isClickable || node.isFocusable)) {
                        Log.i(TAG, "🚗 ✓ This is the destination field!")

                        // Try to click
                        if (node.isClickable && node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                            Log.i(TAG, "🚗 ✓✓✓ Clicked destination field '$searchText'")
                            node.recycle()
                            return true
                        }

                        // Try parent click
                        var parent = node.parent
                        for (level in 1..3) {
                            if (parent == null) break
                            if (parent.isClickable) {
                                if (parent.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                                    Log.i(TAG, "🚗 ✓✓✓ Clicked destination field parent L$level")
                                    parent.recycle()
                                    node.recycle()
                                    return true
                                }
                            }
                            val next = parent.parent
                            parent.recycle()
                            parent = next
                        }
                    }
                    node.recycle()
                }
            }

            // Strategy B: Find by position (avoid fields with coordinates)
            Log.i(TAG, "🚗 Strategy B: Looking for destination field by position...")
            val foundByPosition = findDestinationFieldByPosition(rootNode)
            if (foundByPosition) {
                return true
            }
        }

        // Fall back to original logic for initial destination entry
        Log.i(TAG, "🚗 Falling back to original destination field search...")

        // Debug: Log ALL visible text on screen
        Log.i(TAG, "🚗 === ALL VISIBLE TEXT ON SCREEN ===")
        logAllVisibleTextDetailed(rootNode, 0)
        Log.i(TAG, "🚗 === END OF VISIBLE TEXT ===")

        // Strategy 0: Find InDriver's address bar by resource ID (most reliable)
        Log.i(TAG, "🚗 Strategy 0: Looking for InDriver address bar by resource ID...")
        val addressBarIds = listOf(
            "form_addresses_bar_compose",  // Main address bar in InDriver
            "form_addresses_bar",
            "addresses_bar",
            "destination_input",
            "where_to_input"
        )
        val addressBarClicked = findAndClickByResourceId(rootNode, addressBarIds)
        if (addressBarClicked) {
            Log.i(TAG, "🚗 ✓✓✓ SUCCESS via address bar resource ID")
            return true
        }

        // InDriver specific search texts - Arabic and English
        // IMPORTANT: Include the EXACT text from InDriver's UI
        val inDriverTexts = listOf(
            // EXACT text from InDriver screenshot - CRITICAL
            "ما الوجهة وما التكلفة؟", "ما الوجهة", "التكلفة",
            "What's the destination", "destination and cost",
            // Common destination texts
            "To", "إلى", "Where to?", "إلى أين؟", "إلى أين",
            // Route/destination
            "الوجهة", "Destination", "وجهتك",
            // InDriver specific
            "أدخل وجهتك", "Enter your destination", "Enter destination",
            "اختر الوجهة", "Choose destination",
            // Search
            "Search", "بحث", "ابحث", "البحث",
            // "To" field hints
            "Where are you going", "أين تذهب", "إلى أين تريد الذهاب",
            // More Arabic variations
            "أين تريد الذهاب", "الى اين", "الي اين", "وين رايح",
            // Address/location
            "العنوان", "Address", "Location", "الموقع", "المكان"
        )

        // Strategy 1: Find by EXACT text match
        Log.i(TAG, "🚗 Strategy 1: Searching by exact text match...")
        for (searchText in inDriverTexts) {
            val nodes = rootNode.findAccessibilityNodeInfosByText(searchText)
            if (nodes.isNotEmpty()) {
                Log.i(TAG, "🚗 Found ${nodes.size} nodes for '$searchText'")
            }

            for (node in nodes) {
                val nodeText = node.text?.toString() ?: ""
                val nodeClass = node.className?.toString()?.substringAfterLast(".") ?: ""
                Log.i(TAG, "🚗   → [$nodeClass] text='$nodeText', clickable=${node.isClickable}, focusable=${node.isFocusable}")

                // Try clicking the node itself
                if (node.isClickable) {
                    Log.i(TAG, "🚗   Attempting to click node...")
                    if (node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                        Log.i(TAG, "🚗 ✓✓✓ SUCCESS: Clicked on '$searchText'")
                        node.recycle()
                        return true
                    }
                }

                // Try focus + click
                if (node.isFocusable) {
                    Log.i(TAG, "🚗   Attempting to focus then click...")
                    node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
                    Thread.sleep(100)
                    if (node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                        Log.i(TAG, "🚗 ✓✓✓ SUCCESS: Focus+Click on '$searchText'")
                        node.recycle()
                        return true
                    }
                }

                // Try clicking parent (up to 5 levels)
                var current = node.parent
                for (level in 1..5) {
                    if (current == null) break
                    val parentClass = current.className?.toString()?.substringAfterLast(".") ?: ""
                    Log.i(TAG, "🚗   Parent L$level: [$parentClass] clickable=${current.isClickable}")

                    if (current.isClickable) {
                        Log.i(TAG, "🚗   Attempting to click parent L$level...")
                        if (current.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                            Log.i(TAG, "🚗 ✓✓✓ SUCCESS: Clicked parent L$level of '$searchText'")
                            current.recycle()
                            node.recycle()
                            return true
                        }
                    }
                    val next = current.parent
                    current.recycle()
                    current = next
                }
                node.recycle()
            }
        }

        // Strategy 2: Find by PARTIAL text match (substring)
        Log.i(TAG, "🚗 Strategy 2: Searching by partial text match...")
        val partialMatches = listOf("الوجهة", "وجهة", "أين", "Where", "To", "destination")
        val foundByPartial = findAndClickByPartialText(rootNode, partialMatches)
        if (foundByPartial) {
            Log.i(TAG, "🚗 ✓✓✓ SUCCESS via partial text match")
            return true
        }

        // Strategy 3: Find any clickable view that looks like a destination field
        Log.i(TAG, "🚗 Strategy 3: Searching for clickable destination-like field...")
        val found = findInDriverClickableField(rootNode)
        if (found) {
            Log.i(TAG, "🚗 ✓✓✓ SUCCESS via findInDriverClickableField")
            return true
        }

        // Strategy 4: Find EditText directly
        Log.i(TAG, "🚗 Strategy 4: Searching for EditText fields...")
        val editTextFound = findAndClickEditText(rootNode)
        if (editTextFound) {
            Log.i(TAG, "🚗 ✓✓✓ SUCCESS via EditText")
            return true
        }

        // Strategy 5: Find by View hierarchy - look for specific InDriver patterns
        Log.i(TAG, "🚗 Strategy 5: Scanning all clickable elements...")
        val clickableFound = findFirstClickableWithText(rootNode)
        if (clickableFound) {
            Log.i(TAG, "🚗 ✓✓✓ SUCCESS via first clickable with text")
            return true
        }

        Log.e(TAG, "🚗 ✗✗✗ FAILED: Could not find destination field in InDriver")
        return false
    }

    /**
     * Find and click element by partial text match
     * EXCLUDES: icons, contractor elements, images, and other false positives
     */
    private fun findAndClickByPartialText(node: AccessibilityNodeInfo, keywords: List<String>): Boolean {
        val text = node.text?.toString()?.lowercase() ?: ""
        val desc = node.contentDescription?.toString()?.lowercase() ?: ""
        val hint = (if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O)
            node.hintText?.toString()?.lowercase() else null) ?: ""
        val viewId = node.viewIdResourceName?.lowercase() ?: ""
        val className = node.className?.toString()?.lowercase() ?: ""

        // EXCLUSION LIST - skip elements that are likely NOT destination fields
        val excludePatterns = listOf(
            "icon", "image", "contractor", "avatar", "photo", "picture",
            "button", "menu", "drawer", "navigation", "back", "close",
            "rating", "star", "badge", "notification"
        )

        // Check if this element should be excluded
        val combinedForExclusion = "$desc $viewId $className"
        for (pattern in excludePatterns) {
            if (combinedForExclusion.contains(pattern)) {
                // Log exclusion for debugging
                if (text.isNotEmpty() || desc.isNotEmpty()) {
                    Log.d(TAG, "🚗 Excluding element due to '$pattern': viewId=$viewId, class=$className")
                }
                // Continue checking children, don't return false
                break
            }
        }

        // Only match against actual text content, not IDs or class names
        val textToSearch = "$text $hint"

        for (keyword in keywords) {
            if (textToSearch.contains(keyword.lowercase())) {
                Log.i(TAG, "🚗 Partial match found for '$keyword' in text='$text', hint='$hint'")

                // Extra validation: skip if viewId contains exclusion patterns
                var shouldSkip = false
                for (pattern in excludePatterns) {
                    if (viewId.contains(pattern) || className.contains(pattern)) {
                        Log.w(TAG, "🚗 Skipping match - viewId/class contains '$pattern'")
                        shouldSkip = true
                        break
                    }
                }
                if (shouldSkip) continue

                // Try to click
                if (node.isClickable && node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                    return true
                }

                // Try parent
                val parent = node.parent
                if (parent != null && parent.isClickable) {
                    val parentViewId = parent.viewIdResourceName?.lowercase() ?: ""
                    // Don't click parent if it's an excluded element
                    var parentExcluded = false
                    for (pattern in excludePatterns) {
                        if (parentViewId.contains(pattern)) {
                            parentExcluded = true
                            break
                        }
                    }
                    if (!parentExcluded && parent.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                        parent.recycle()
                        return true
                    }
                    parent.recycle()
                }
            }
        }

        // Recursively check children
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            if (findAndClickByPartialText(child, keywords)) {
                child.recycle()
                return true
            }
            child.recycle()
        }

        return false
    }

    /**
     * Find and click element by resource ID
     * Specifically for InDriver's ComposeView address bar
     */
    private fun findAndClickByResourceId(node: AccessibilityNodeInfo, resourceIds: List<String>): Boolean {
        val viewId = node.viewIdResourceName ?: ""

        // Check if this node matches any of the target resource IDs
        for (targetId in resourceIds) {
            if (viewId.contains(targetId, ignoreCase = true)) {
                Log.i(TAG, "🚗 Found element by resource ID: $viewId")

                // Try to click directly
                if (node.isClickable) {
                    Log.i(TAG, "🚗 Clicking on $viewId...")
                    if (node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                        Log.i(TAG, "🚗 ✓ Click SUCCESS on $viewId")
                        return true
                    }
                }

                // Try to find a clickable child
                for (i in 0 until node.childCount) {
                    val child = node.getChild(i) ?: continue
                    if (child.isClickable) {
                        Log.i(TAG, "🚗 Clicking on child of $viewId...")
                        if (child.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                            Log.i(TAG, "🚗 ✓ Click SUCCESS on child of $viewId")
                            child.recycle()
                            return true
                        }
                    }
                    child.recycle()
                }

                // Try gesture click on the element
                val rect = android.graphics.Rect()
                node.getBoundsInScreen(rect)
                if (rect.width() > 0 && rect.height() > 0) {
                    val centerX = rect.centerX().toFloat()
                    val centerY = rect.centerY().toFloat()
                    Log.i(TAG, "🚗 Gesture click on $viewId at ($centerX, $centerY)")

                    val path = android.graphics.Path()
                    path.moveTo(centerX, centerY)

                    val gestureBuilder = android.accessibilityservice.GestureDescription.Builder()
                    gestureBuilder.addStroke(
                        android.accessibilityservice.GestureDescription.StrokeDescription(
                            path, 0, 100
                        )
                    )

                    dispatchGesture(gestureBuilder.build(), null, null)
                    Thread.sleep(300)
                    return true
                }
            }
        }

        // Recursively check children
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            if (findAndClickByResourceId(child, resourceIds)) {
                child.recycle()
                return true
            }
            child.recycle()
        }

        return false
    }

    /**
     * Find the first clickable element that has text (likely the main input)
     */
    private fun findFirstClickableWithText(node: AccessibilityNodeInfo): Boolean {
        val text = node.text?.toString() ?: ""
        val className = node.className?.toString() ?: ""

        // Skip buttons and known non-input elements
        val isLikelyInput = !className.contains("Button") &&
                            !className.contains("Image") &&
                            (text.isNotEmpty() || node.isEditable)

        if (isLikelyInput && node.isClickable && text.length > 3) {
            Log.i(TAG, "🚗 Found clickable with text: '$text' [$className]")
            if (node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                return true
            }
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            if (findFirstClickableWithText(child)) {
                child.recycle()
                return true
            }
            child.recycle()
        }

        return false
    }

    /**
     * Find InDriver clickable destination field by traversing UI
     */
    private fun findInDriverClickableField(node: AccessibilityNodeInfo): Boolean {
        val text = node.text?.toString() ?: ""
        val textLower = text.lowercase()
        val desc = node.contentDescription?.toString()?.lowercase() ?: ""
        val hint = (if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O)
            node.hintText?.toString()?.lowercase() else null) ?: ""
        val className = node.className?.toString() ?: ""

        // EXACT match for InDriver's main destination field text
        val exactInDriverTexts = listOf(
            "ما الوجهة وما التكلفة؟",
            "ما الوجهة",
            "what's the destination"
        )

        for (exactText in exactInDriverTexts) {
            if (text.contains(exactText) || desc.contains(exactText.lowercase())) {
                Log.i(TAG, "🚗 EXACT InDriver text match: '$exactText' in node")
                // Found the exact InDriver element - try to click it or its parents
                if (node.isClickable) {
                    if (node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                        Log.i(TAG, "🚗 ✓ Clicked exact match node")
                        return true
                    }
                }
                // Try parents up to 5 levels
                var parent = node.parent
                for (level in 1..5) {
                    if (parent == null) break
                    if (parent.isClickable) {
                        if (parent.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                            Log.i(TAG, "🚗 ✓ Clicked parent L$level of exact match")
                            parent.recycle()
                            return true
                        }
                    }
                    val next = parent.parent
                    parent.recycle()
                    parent = next
                }
            }
        }

        // Check if this looks like a destination input
        val isDestinationLike = textLower.contains("to") || textLower.contains("إلى") ||
                                textLower.contains("where") || textLower.contains("أين") ||
                                textLower.contains("destination") || textLower.contains("وجهة") ||
                                textLower.contains("الوجهة") || textLower.contains("التكلفة") ||
                                desc.contains("to") || desc.contains("إلى") ||
                                desc.contains("destination") || desc.contains("وجهة") ||
                                hint.contains("to") || hint.contains("destination") ||
                                hint.contains("وجهة") || hint.contains("أين")

        // Check if it's an input-like element
        val isInputLike = className.contains("EditText") ||
                          className.contains("AutoComplete") ||
                          className.contains("SearchView") ||
                          className.contains("TextInputLayout") ||
                          node.isEditable

        if ((isDestinationLike || isInputLike) && (node.isClickable || node.isFocusable)) {
            Log.i(TAG, "🚗 🎯 Found potential field - [$className] text='$text'")
            if (node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                Log.i(TAG, "🚗 ✓ Clicked field")
                return true
            }
            if (node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)) {
                Log.i(TAG, "🚗 ✓ Focused field")
                // After focus, try click again
                Thread.sleep(100)
                if (node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                    return true
                }
            }
        }

        // Recursively check children
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            if (findInDriverClickableField(child)) {
                child.recycle()
                return true
            }
            child.recycle()
        }

        return false
    }

    /**
     * Log all visible text for debugging
     */
    private fun logAllVisibleText(node: AccessibilityNodeInfo, depth: Int) {
        if (depth > 5) return // Limit depth

        val indent = "  ".repeat(depth)
        val text = node.text?.toString() ?: ""
        val contentDesc = node.contentDescription?.toString() ?: ""
        val hint = (if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) node.hintText?.toString() else null) ?: ""

        if (text.isNotEmpty() || contentDesc.isNotEmpty() || hint.isNotEmpty()) {
            Log.d(TAG, "${indent}📝 ${node.className}: text='$text', desc='$contentDesc', hint='$hint', clickable=${node.isClickable}")
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            logAllVisibleText(child, depth + 1)
            child.recycle()
        }
    }

    /**
     * Detailed logging for InDriver debugging - logs EVERYTHING
     */
    private fun logAllVisibleTextDetailed(node: AccessibilityNodeInfo, depth: Int) {
        if (depth > 10) return // Allow deeper traversal

        val indent = "│ ".repeat(depth)
        val text = node.text?.toString() ?: ""
        val contentDesc = node.contentDescription?.toString() ?: ""
        val hint = (if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) node.hintText?.toString() else null) ?: ""
        val className = node.className?.toString()?.substringAfterLast(".") ?: "?"
        val viewId = node.viewIdResourceName ?: ""

        // Log EVERY node for detailed debugging
        val clickable = if (node.isClickable) "✓CLICK" else ""
        val focusable = if (node.isFocusable) "✓FOCUS" else ""
        val editable = if (node.isEditable) "✓EDIT" else ""
        val enabled = if (node.isEnabled) "" else "DISABLED"

        val info = StringBuilder()
        if (text.isNotEmpty()) info.append("text='$text' ")
        if (contentDesc.isNotEmpty()) info.append("desc='$contentDesc' ")
        if (hint.isNotEmpty()) info.append("hint='$hint' ")
        if (viewId.isNotEmpty()) info.append("id='$viewId' ")

        val flags = listOf(clickable, focusable, editable, enabled).filter { it.isNotEmpty() }.joinToString(" ")

        Log.i(TAG, "🚗 $indent[$className] $info $flags")

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            logAllVisibleTextDetailed(child, depth + 1)
            child.recycle()
        }
    }

    /**
     * Find any clickable element that looks like a search field
     */
    private fun findClickableSearchField(node: AccessibilityNodeInfo): Boolean {
        val text = node.text?.toString()?.lowercase() ?: ""
        val contentDesc = node.contentDescription?.toString()?.lowercase() ?: ""
        val className = node.className?.toString() ?: ""

        // Look for search-like elements
        val isSearchLike = text.contains("where") || text.contains("search") || text.contains("destination") ||
                           text.contains("أين") || text.contains("بحث") || text.contains("وجهة") ||
                           contentDesc.contains("search") || contentDesc.contains("destination") ||
                           contentDesc.contains("where")

        if (isSearchLike && node.isClickable) {
            Log.i(TAG, "✓ Found clickable search-like element: $text / $contentDesc")
            node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            return true
        }

        // Check if it's an input-like container
        if ((className.contains("EditText") || className.contains("SearchView") ||
             className.contains("AutoComplete")) && node.isEnabled) {
            Log.i(TAG, "✓ Found input field: $className")
            node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
            node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            return true
        }

        // Recursively check children
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            if (findClickableSearchField(child)) {
                child.recycle()
                return true
            }
            child.recycle()
        }

        return false
    }

    private fun findAndClickEditText(node: AccessibilityNodeInfo): Boolean {
        // Check if this is an editable field
        if (node.className?.toString()?.contains("EditText") == true ||
            node.isEditable) {
            val hint = node.hintText?.toString()?.lowercase() ?: ""
            val text = node.text?.toString()?.lowercase() ?: ""

            // Look for destination-related fields
            if (hint.contains("where") || hint.contains("destination") || hint.contains("إلى") ||
                hint.contains("وجهة") || hint.contains("search") || hint.contains("بحث") ||
                text.contains("where") || text.contains("إلى")) {

                node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
                node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                Log.i(TAG, "Found and clicked EditText field")
                return true
            }
        }

        // Recursively check children
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            if (findAndClickEditText(child)) {
                child.recycle()
                return true
            }
            child.recycle()
        }

        return false
    }

    /**
     * Enter destination text into focused field
     * For InDriver: Uses TEXT address so suggestions appear, then click first suggestion
     * For DiDi: Uses COORDINATES to avoid multiple results
     */
    private fun enterDestinationText(rootNode: AccessibilityNodeInfo, packageName: String): Boolean {
        Log.i(TAG, "🤖 enterDestinationText: Looking for focused input...")

        // Determine what to enter:
        // - InDriver: Use TEXT address so suggestions appear (user clicks first suggestion)
        // - DiDi: Use COORDINATES to avoid multiple results
        val textToEnter = when (packageName) {
            INDRIVER_PACKAGE -> {
                // Use the destination NAME so InDriver shows suggestions
                Log.i(TAG, "🤖 Using TEXT address for InDriver: $destinationAddress")
                destinationAddress
            }
            DIDI_PACKAGE -> {
                // Use coordinates format that DiDi recognizes
                val coordText = "$destLat, $destLng"
                Log.i(TAG, "🤖 Using COORDINATES for $packageName: $coordText")
                coordText
            }
            else -> {
                Log.i(TAG, "🤖 Using TEXT address for $packageName: $destinationAddress")
                destinationAddress
            }
        }

        val focusedNode = rootNode.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        if (focusedNode != null) {
            val focusedClass = focusedNode.className?.toString()?.substringAfterLast(".") ?: ""
            val focusedText = focusedNode.text?.toString() ?: ""
            Log.i(TAG, "🤖 Found focused node: [$focusedClass] text='$focusedText'")

            // Clear existing text and set new text
            val args = android.os.Bundle()
            args.putCharSequence(
                AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                textToEnter
            )
            val result = focusedNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)

            if (result) {
                Log.i(TAG, "🤖 ✓ Successfully entered destination: $textToEnter")

                // CRITICAL FOR INDRIVER: After entering destination, press Enter to submit
                // This should trigger navigation to the price screen
                if (packageName == INDRIVER_PACKAGE) {
                    Log.i(TAG, "🤖 [InDriver] Pressing ENTER to submit destination...")
                    Thread.sleep(300)  // Small delay before pressing Enter

                    // Try multiple methods to submit the form:

                    // Method 1: Click at bottom of screen where submit button might be
                    val displayMetrics = resources.displayMetrics
                    val screenWidth = displayMetrics.widthPixels.toFloat()
                    val screenHeight = displayMetrics.heightPixels.toFloat()

                    // Try clicking at several bottom positions
                    val bottomY = screenHeight * 0.95f
                    val centerX = screenWidth / 2

                    Log.i(TAG, "🤖 [InDriver] Trying bottom click at ($centerX, $bottomY)")
                    if (clickAtPosition(centerX, bottomY)) {
                        Log.i(TAG, "🤖 [InDriver] ✓ Bottom click succeeded!")
                    }

                    // Method 2: Try clicking slightly above (85% of screen)
                    Thread.sleep(200)
                    val upperY = screenHeight * 0.85f
                    Log.i(TAG, "🤖 [InDriver] Trying upper bottom click at ($centerX, $upperY)")
                    if (clickAtPosition(centerX, upperY)) {
                        Log.i(TAG, "🤖 [InDriver] ✓ Upper bottom click succeeded!")
                    }
                }

                focusedNode.recycle()
                return true
            } else {
                Log.w(TAG, "🤖 ✗ ACTION_SET_TEXT failed on focused node")
            }
            focusedNode.recycle()
        } else {
            Log.w(TAG, "🤖 No focused input node found")
        }

        // Fallback: find any editable field and enter text
        Log.i(TAG, "🤖 Fallback: searching for any EditText field...")
        return enterTextIntoAnyEditText(rootNode, textToEnter)
    }

    private fun enterTextIntoAnyEditText(node: AccessibilityNodeInfo, text: String): Boolean {
        val className = node.className?.toString()?.substringAfterLast(".") ?: ""
        val nodeText = node.text?.toString() ?: ""

        if (node.isEditable || className.contains("EditText")) {
            Log.i(TAG, "🤖 Found editable field: [$className] current text='$nodeText'")

            val args = android.os.Bundle()
            args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)

            // First try to focus the field
            node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
            Thread.sleep(100)

            if (node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) {
                Log.i(TAG, "🤖 ✓ Entered text into EditText: '$text'")
                return true
            } else {
                Log.w(TAG, "🤖 ✗ Failed to set text on editable field")
            }
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            if (enterTextIntoAnyEditText(child, text)) {
                child.recycle()
                return true
            }
            child.recycle()
        }

        return false
    }

    /**
     * Select a suggestion that MATCHES the destination, not just the first one
     */
    private fun selectFirstSuggestion(rootNode: AccessibilityNodeInfo, packageName: String): Boolean {
        // Extract keywords from destination address (remove Plus Codes and numbers)
        val destKeywords = extractDestinationKeywords(destinationAddress)
        Log.i(TAG, "🔍 Looking for suggestion matching: $destKeywords")

        // Collect all visible suggestions with their text
        val suggestions = mutableListOf<Pair<AccessibilityNodeInfo, String>>()
        collectSuggestions(rootNode, suggestions)

        // Also try InDriver-specific suggestion collection if needed
        if (suggestions.isEmpty() && packageName == INDRIVER_PACKAGE) {
            collectInDriverSuggestions(rootNode, suggestions)
        }

        // Also try DiDi-specific suggestion collection (for "Select Address" screen)
        if (suggestions.isEmpty() && packageName == DIDI_PACKAGE) {
            collectDiDiSuggestions(rootNode, suggestions)
        }

        Log.i(TAG, "📋 Found ${suggestions.size} suggestions:")
        suggestions.forEachIndexed { index, (_, text) ->
            Log.i(TAG, "   [$index] '$text'")
        }

        // Find the best matching suggestion with weighted scoring
        var bestMatch: AccessibilityNodeInfo? = null
        var bestScore = 0
        var bestText = ""

        for ((node, suggestionText) in suggestions) {
            val textLower = suggestionText.lowercase()
            var score = 0

            // Weighted scoring: longer keywords = more specific = more points
            for ((index, keyword) in destKeywords.withIndex()) {
                if (textLower.contains(keyword.lowercase())) {
                    // First keywords (longest) get more points
                    val weight = destKeywords.size - index
                    score += weight
                    Log.d(TAG, "   Match '$keyword' in '$suggestionText' (+$weight)")
                }
            }

            // Bonus: exact word match (not just contains)
            for (keyword in destKeywords) {
                val wordPattern = Regex("\\b${Regex.escape(keyword)}\\b", RegexOption.IGNORE_CASE)
                if (wordPattern.containsMatchIn(suggestionText)) {
                    score += 2 // Bonus for exact word match
                }
            }

            if (score > bestScore) {
                bestScore = score
                bestMatch = node
                bestText = suggestionText
                Log.i(TAG, "✓ Better match: '$suggestionText' (score: $score)")
            }
        }

        // If we found a match with score > 0, click it using SMART CLICK
        if (bestMatch != null && bestScore > 0) {
            Log.i(TAG, "✓✓ SELECTED: '$bestText' (score: $bestScore)")

            // Use smart click (gesture-based) for reliability
            val clicked = smartClick(bestMatch)

            // Recycle all nodes
            suggestions.forEach { (node, _) ->
                try { node.recycle() } catch (e: Exception) {}
            }
            return clicked
        }

        // Fallback: click first suggestion if no match found
        Log.w(TAG, "⚠️ No matching suggestion found, clicking first one")
        if (suggestions.isNotEmpty()) {
            Log.i(TAG, "⚠️ Fallback: selecting '${suggestions[0].second}'")

            // Use smart click (gesture-based) for reliability
            val clicked = smartClick(suggestions[0].first)

            suggestions.forEach { (node, _) ->
                try { node.recycle() } catch (e: Exception) {}
            }
            return clicked
        }

        suggestions.forEach { (node, _) ->
            try { node.recycle() } catch (e: Exception) {}
        }

        // Fallback: find any clickable item
        return clickFirstMatchingSuggestion(rootNode)
    }

    /**
     * Collect suggestions specifically for InDriver's UI
     * InDriver suggestions are rows containing: name + address + distance
     * Example: "Coffee Zone" | "مسجد عباد الرحمن..." | "14.4 كم"
     */
    private fun collectInDriverSuggestions(node: AccessibilityNodeInfo, suggestions: MutableList<Pair<AccessibilityNodeInfo, String>>) {
        val className = node.className?.toString() ?: ""
        val text = node.text?.toString() ?: ""
        val resourceId = try { node.viewIdResourceName ?: "" } catch (e: Exception) { "" }

        // SKIP: EditText fields (these are input fields, not suggestions!)
        if (className.contains("EditText")) {
            Log.d(TAG, "📍 SKIP EditText: '$text'")
            // Still recurse into children
            for (i in 0 until node.childCount) {
                val child = node.getChild(i) ?: continue
                collectInDriverSuggestions(child, suggestions)
                child.recycle()
            }
            return
        }

        // SKIP: Text that looks like coordinates (e.g., "30.123, 31.456")
        val coordPattern = Regex("^\\d+\\.\\d+,\\s*\\d+\\.\\d+$")
        if (text.isNotBlank() && coordPattern.matches(text.trim())) {
            Log.d(TAG, "📍 SKIP coordinates: '$text'")
            for (i in 0 until node.childCount) {
                val child = node.getChild(i) ?: continue
                collectInDriverSuggestions(child, suggestions)
                child.recycle()
            }
            return
        }

        // InDriver shows suggestions in a different format
        // Look for clickable ViewGroups with location text
        if (node.isClickable && text.isNotBlank() && text.length > 5) {
            val isNotButton = !text.contains("تم") &&
                              !text.contains("Done") &&
                              !text.contains("بحث") &&
                              !text.contains("Search") &&
                              !text.contains("مسح") &&  // Clear button
                              !text.contains("إغلاق")   // Close button
            if (isNotButton) {
                suggestions.add(Pair(AccessibilityNodeInfo.obtain(node), text))
                Log.d(TAG, "📍 InDriver suggestion: '$text' (class=$className)")
            }
        }

        // Check content description too
        val desc = node.contentDescription?.toString() ?: ""
        if (node.isClickable && desc.isNotBlank() && desc.length > 5 && text.isBlank()) {
            val isNotButton = !desc.contains("مسح") && !desc.contains("إغلاق")
            if (isNotButton) {
                suggestions.add(Pair(AccessibilityNodeInfo.obtain(node), desc))
                Log.d(TAG, "📍 InDriver suggestion (desc): '$desc'")
            }
        }

        // SPECIAL: Look for suggestion ROW patterns
        // InDriver suggestion rows are typically ViewGroups containing location names
        // The ROW is clickable, and children contain the name text
        if (node.isClickable && (className.contains("ViewGroup") || className.contains("LinearLayout") || className.contains("FrameLayout"))) {
            // Get combined text from children
            val childTexts = mutableListOf<String>()
            for (i in 0 until node.childCount) {
                val child = node.getChild(i) ?: continue
                val childText = child.text?.toString() ?: ""
                if (childText.isNotBlank()) {
                    childTexts.add(childText)
                }
                child.recycle()
            }
            val combinedText = childTexts.joinToString(" | ")

            // Check if this looks like a suggestion row (has distance like "كم", "متر", "km", "m")
            val hasDistance = combinedText.contains("كم") || combinedText.contains("km") ||
                              combinedText.contains("متر") || combinedText.matches(Regex(".*\\d+\\s*m\\b.*"))
            val hasLocation = combinedText.contains("مصر") || combinedText.contains("Egypt") ||
                              combinedText.contains("محافظة") || combinedText.contains("قسم") ||
                              combinedText.contains("Obour") || combinedText.contains("العبور") ||
                              combinedText.contains("Riyad") || combinedText.contains("رياض")
            val hasName = childTexts.any { it.contains("Coffee") || it.contains("Shop") || it.contains("Mall") ||
                                          it.contains("كافيه") || it.contains("مطعم") || it.length in 3..50 }

            if ((hasDistance || hasLocation) && combinedText.length > 10 && !combinedText.contains("اختر على الخريطة")) {
                // Use the first child text as the name (usually the location name)
                // Exclude distance indicators (كم, متر, km, m) and coordinates
                val name = childTexts.firstOrNull { it.length > 3 && !it.contains("كم") && !it.contains("متر") &&
                                                    !it.matches(Regex("\\d+[,.]\\d+.*")) && !it.matches(Regex("^\\d+\\s*(m|km)$")) } ?: combinedText.take(50)
                Log.d(TAG, "📍 InDriver suggestion ROW: '$name' (combined: '${combinedText.take(60)}...')")
                suggestions.add(Pair(AccessibilityNodeInfo.obtain(node), name))
                return // Don't recurse further - we found the row
            }
        }

        // Recurse
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            collectInDriverSuggestions(child, suggestions)
            child.recycle()
        }
    }

    /**
     * Collect suggestions specifically for DiDi's "Select Address" screen
     * DiDi shows address cards with combined text like:
     * "Tasty BitesTasty Bites, الحى الخامس العبور محافظة القليوبية 6361223 مصر"
     */
    private fun collectDiDiSuggestions(node: AccessibilityNodeInfo, suggestions: MutableList<Pair<AccessibilityNodeInfo, String>>) {
        // Get combined text from this node and children
        val combinedText = getNodeText(node)

        // Skip UI elements and empty nodes
        val skipTexts = listOf(
            "Pin your location", "Select Address", "Powered by",
            "Back", "Where to", "Add stop", "Pickup point", "Search"
        )
        val shouldSkip = skipTexts.any { combinedText.contains(it, ignoreCase = true) }

        if (!shouldSkip && node.isClickable && combinedText.length > 20) {
            // Check if it looks like a FULL address card (has distance + location)
            val hasDistance = combinedText.contains("km") || combinedText.contains("كم")
            val hasLocation = combinedText.contains("محافظة") || combinedText.contains("مصر") ||
                              combinedText.contains("العبور") || combinedText.contains("القاهرة")

            if (hasDistance && hasLocation) {
                Log.i(TAG, "📍 DiDi FULL address card: '${combinedText.take(60)}...'")
                suggestions.add(Pair(AccessibilityNodeInfo.obtain(node), combinedText))
                return // Don't recurse - we found the full card
            }
        }

        // Recurse into children to find address cards
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            collectDiDiSuggestions(child, suggestions)
            child.recycle()
        }
    }

    /**
     * Extract meaningful keywords from destination address
     * Removes Plus Codes, numbers, and common words
     */
    private fun extractDestinationKeywords(address: String): List<String> {
        // Remove Plus Codes (format: XXXX+XXX or longer)
        var cleaned = address.replace(Regex("[A-Z0-9]{4,8}\\+[A-Z0-9]{2,4}"), "")

        // Remove standalone numbers and coordinates
        cleaned = cleaned.replace(Regex("\\b\\d+\\.?\\d*\\b"), "")

        // Remove common non-useful words in Arabic
        val stopWords = listOf(
            "محافظة", "مدينة", "منطقة", "شارع", "طريق", "ش", "ط",
            "مصر", "Egypt", "القاهرة", "Cairo", "الجيزة", "Giza",
            "شمال", "جنوب", "شرق", "غرب", "قسم", "حي", "دائرة",
            "الكبرى", "الجديدة", "القديمة", "أول", "ثاني", "ثالث"
        )
        for (word in stopWords) {
            cleaned = cleaned.replace(word, " ", ignoreCase = true)
        }

        // Split by common separators
        val words = cleaned.split(Regex("[،,\\-\\s]+"))
            .map { it.trim() }
            .filter { it.length > 2 }
            .filter { !it.matches(Regex("[\\d.\\-]+")) }
            .filter { it.isNotBlank() }
            .distinct()

        // Prioritize: longer words first (usually more specific)
        val sortedWords = words.sortedByDescending { it.length }

        Log.i(TAG, "🔑 Keywords extracted: $sortedWords from: '$address'")
        return sortedWords
    }

    /**
     * Collect all visible suggestions from the screen
     */
    private fun collectSuggestions(node: AccessibilityNodeInfo, suggestions: MutableList<Pair<AccessibilityNodeInfo, String>>) {
        val className = node.className?.toString() ?: ""
        val text = node.text?.toString() ?: ""
        val desc = node.contentDescription?.toString() ?: ""

        // If this is a RecyclerView or ListView, get its children
        if (className.contains("RecyclerView") || className.contains("ListView")) {
            for (i in 0 until minOf(node.childCount, 10)) {
                val child = node.getChild(i) ?: continue
                val childText = getNodeText(child)
                if (childText.isNotBlank() && childText.length > 3 &&
                    !childText.lowercase().contains("where") &&
                    !childText.lowercase().contains("search") &&
                    !childText.contains("إلى أين")) {
                    suggestions.add(Pair(AccessibilityNodeInfo.obtain(child), childText))
                }
                child.recycle()
            }
            return
        }

        // Check if this node is a clickable suggestion item
        if (node.isClickable && text.isNotBlank() && text.length > 5 &&
            !text.lowercase().contains("where") &&
            !text.lowercase().contains("search") &&
            !text.contains("إلى أين") &&
            !text.contains("بحث")) {
            suggestions.add(Pair(AccessibilityNodeInfo.obtain(node), text))
        }

        // Recurse into children
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            collectSuggestions(child, suggestions)
            child.recycle()
        }
    }

    /**
     * Get combined text from a node and its children
     */
    private fun getNodeText(node: AccessibilityNodeInfo): String {
        val texts = mutableListOf<String>()
        val nodeText = node.text?.toString() ?: ""
        if (nodeText.isNotBlank()) texts.add(nodeText)

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val childText = child.text?.toString() ?: ""
            if (childText.isNotBlank()) texts.add(childText)
            child.recycle()
        }

        return texts.joinToString(" ").trim()
    }

    private fun clickFirstMatchingSuggestion(node: AccessibilityNodeInfo): Boolean {
        // Look for items that might be suggestions
        val text = node.text?.toString() ?: ""
        val desc = node.contentDescription?.toString() ?: ""

        // If this looks like a location suggestion
        if ((text.isNotEmpty() || desc.isNotEmpty()) && node.isClickable) {
            // Check if it's not just a label
            val combined = "$text $desc".lowercase()
            if (!combined.contains("where") && !combined.contains("search") &&
                !combined.contains("إلى أين") && !combined.contains("بحث") &&
                combined.length > 5) {
                node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                Log.i(TAG, "Clicked matching suggestion: $text")
                return true
            }
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            if (clickFirstMatchingSuggestion(child)) {
                child.recycle()
                return true
            }
            child.recycle()
        }

        return false
    }

    /**
     * Handle intermediate screens that appear before the price screen
     * e.g., DiDi's "Which airline?" screen for airport destinations
     * Returns true if an intermediate screen was handled
     */
    private fun handleIntermediateScreens(rootNode: AccessibilityNodeInfo, packageName: String): Boolean {
        return when (packageName) {
            DIDI_PACKAGE -> handleDiDiIntermediateScreens(rootNode)
            UBER_PACKAGE -> handleUberIntermediateScreens(rootNode)
            CAREEM_PACKAGE -> handleCareemIntermediateScreens(rootNode)
            BOLT_PACKAGE -> handleBoltIntermediateScreens(rootNode)
            INDRIVER_PACKAGE -> handleInDriverIntermediateScreens(rootNode)
            else -> false
        }
    }

    /**
     * Handle DiDi intermediate screens:
     * - "Select Address" screen with multiple address options
     * - "Which airline?" for airport destinations
     * - Other promotional/info dialogs
     */
    private fun handleDiDiIntermediateScreens(rootNode: AccessibilityNodeInfo): Boolean {
        val allText = getAllTextFromNode(rootNode)
        val allTextLower = allText.map { it.lowercase() }

        // ============================================================
        // CRITICAL: Check for "Select Address" screen
        // This appears when DiDi finds multiple addresses for a destination
        // ============================================================
        val hasSelectAddressScreen = allTextLower.any {
            it.contains("select address") ||
            it.contains("اختر العنوان") ||
            it.contains("pin your location")
        }

        if (hasSelectAddressScreen) {
            Log.i(TAG, "📍 Detected DiDi 'Select Address' screen")

            // Check cooldown to prevent clicking too fast (wait 3 seconds between clicks)
            val currentTime = System.currentTimeMillis()
            if (currentTime - lastDiDiSelectAddressClickTime < 3000) {
                Log.i(TAG, "📍 Waiting for cooldown (${3000 - (currentTime - lastDiDiSelectAddressClickTime)}ms remaining)")
                return false // Don't handle - let automation continue waiting
            }

            Log.i(TAG, "📍 Selecting first address card...")

            // Find the first clickable address card
            val addressCard = findFirstDiDiAddressCard(rootNode)
            if (addressCard != null) {
                val cardText = getNodeText(addressCard)
                Log.i(TAG, "📍 Found address card: '${cardText.take(50)}...'")

                // Use smart click (shell tap + gesture)
                if (smartClick(addressCard)) {
                    Log.i(TAG, "📍 ✓ Successfully clicked address card")
                    addressCard.recycle()

                    // Record click time for cooldown
                    lastDiDiSelectAddressClickTime = currentTime

                    // Don't change state - let caller decide
                    return true
                }
                addressCard.recycle()
            } else {
                Log.w(TAG, "📍 No address card found on Select Address screen")
            }
        }

        // Check for airline selection screen
        val hasAirlineScreen = allTextLower.any {
            it.contains("which airline") ||
            it.contains("اختر شركة الطيران") ||
            it.contains("search airlines") ||
            it.contains("بحث عن شركات الطيران")
        }

        if (hasAirlineScreen) {
            Log.i(TAG, "🛫 Detected DiDi airline selection screen")

            // Look for "Skip" button and click it
            val skipTexts = listOf("Skip", "تخطي", "skip")
            for (skipText in skipTexts) {
                val skipNodes = rootNode.findAccessibilityNodeInfosByText(skipText)
                for (node in skipNodes) {
                    if (node.isClickable) {
                        val clicked = node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                        if (clicked) {
                            Log.i(TAG, "✓ Clicked 'Skip' button on airline screen")
                            node.recycle()
                            return true
                        }
                    }
                    // Try clicking parent if node isn't directly clickable
                    val parent = node.parent
                    if (parent != null && parent.isClickable) {
                        val clicked = parent.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                        if (clicked) {
                            Log.i(TAG, "✓ Clicked parent of 'Skip' button")
                            parent.recycle()
                            node.recycle()
                            return true
                        }
                        parent.recycle()
                    }
                    node.recycle()
                }
            }

            // Fallback: Try to find and click any clickable "Skip" element by traversal
            if (findAndClickSkipButton(rootNode)) {
                return true
            }
        }

        // Check for promotional/popup dialogs and dismiss them
        val hasPromoDialog = allTextLower.any {
            it.contains("got it") ||
            it.contains("dismiss") ||
            it.contains("close") ||
            it.contains("no thanks") ||
            it.contains("لا شكراً") ||
            it.contains("إغلاق")
        }

        if (hasPromoDialog) {
            Log.i(TAG, "📢 Detected DiDi promotional dialog")
            val dismissTexts = listOf("Got it", "Dismiss", "Close", "No thanks", "OK", "لا شكراً", "إغلاق", "حسناً")
            for (dismissText in dismissTexts) {
                val nodes = rootNode.findAccessibilityNodeInfosByText(dismissText)
                for (node in nodes) {
                    if (node.isClickable) {
                        node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                        Log.i(TAG, "✓ Dismissed dialog with: $dismissText")
                        node.recycle()
                        return true
                    }
                    node.recycle()
                }
            }
        }

        return false
    }

    /**
     * Find the first clickable address card on DiDi's "Select Address" screen
     * Address cards contain: place name, full address, distance (km)
     * Returns the CLICKABLE node (either the card itself or its clickable ancestor)
     */
    private fun findFirstDiDiAddressCard(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val combinedText = getNodeText(node)

        // Skip UI elements that are not address cards
        val skipTexts = listOf(
            "Pin your location", "Select Address", "Powered by",
            "Back", "Where to", "Add stop", "Pickup point", "Search"
        )
        val shouldSkip = skipTexts.any { combinedText.contains(it, ignoreCase = true) }

        if (!shouldSkip && combinedText.length > 15) {
            // Check if it looks like an address card
            // Address cards have: location name AND (distance OR governorate)
            val hasDistance = combinedText.contains("km") || combinedText.contains("كم") || combinedText.contains("m")
            val hasLocation = combinedText.contains("محافظة") || combinedText.contains("مصر") ||
                              combinedText.contains("العبور") || combinedText.contains("القاهرة") ||
                              combinedText.contains("الجيزة") || combinedText.contains("Egypt") ||
                              combinedText.contains("شارع") || combinedText.contains("الحي")

            // Must have EITHER distance OR location indicator (more flexible)
            if (hasDistance || hasLocation) {
                // Get bounds to verify it's a visible card (not tiny or off-screen)
                val rect = android.graphics.Rect()
                node.getBoundsInScreen(rect)

                // Valid card should have reasonable size (at least 100x40 pixels)
                if (rect.width() > 100 && rect.height() > 40) {
                    Log.i(TAG, "📍 Found DiDi address text: '${combinedText.take(50)}...' bounds=$rect clickable=${node.isClickable}")

                    // If this node is clickable, return it
                    if (node.isClickable) {
                        Log.i(TAG, "📍 ✓ Node is clickable, returning it")
                        return AccessibilityNodeInfo.obtain(node)
                    }

                    // Otherwise, find the clickable ancestor (up to 5 levels)
                    var current: AccessibilityNodeInfo? = node.parent
                    for (level in 1..5) {
                        if (current == null) break

                        val parentRect = android.graphics.Rect()
                        current.getBoundsInScreen(parentRect)
                        Log.i(TAG, "📍 Checking parent L$level: clickable=${current.isClickable} bounds=$parentRect")

                        if (current.isClickable) {
                            Log.i(TAG, "📍 ✓ Found clickable parent at level $level")
                            return AccessibilityNodeInfo.obtain(current)
                        }

                        val next = current.parent
                        current.recycle()
                        current = next
                    }

                    // If no clickable ancestor found, return the original node anyway
                    // (smartClick will try shell tap which doesn't need clickable=true)
                    Log.i(TAG, "📍 ⚠️ No clickable ancestor found, returning original node for shell tap")
                    return AccessibilityNodeInfo.obtain(node)
                }
            }
        }

        // Recurse into children
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = findFirstDiDiAddressCard(child)
            if (found != null) {
                child.recycle()
                return found
            }
            child.recycle()
        }

        return null
    }

    /**
     * Find and click Skip button by recursive traversal
     */
    private fun findAndClickSkipButton(node: AccessibilityNodeInfo): Boolean {
        val text = node.text?.toString()?.lowercase() ?: ""
        val desc = node.contentDescription?.toString()?.lowercase() ?: ""

        if ((text == "skip" || text == "تخطي" || desc == "skip" || desc == "تخطي") && node.isClickable) {
            val clicked = node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            if (clicked) {
                Log.i(TAG, "✓ Found and clicked Skip button via traversal")
                return true
            }
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            if (findAndClickSkipButton(child)) {
                child.recycle()
                return true
            }
            child.recycle()
        }

        return false
    }

    /**
     * Handle Uber intermediate screens
     */
    private fun handleUberIntermediateScreens(rootNode: AccessibilityNodeInfo): Boolean {
        val allText = getAllTextFromNode(rootNode)
        val allTextLower = allText.map { it.lowercase() }

        // Check for surge pricing confirmation, promo dialogs, etc.
        val hasSurgeDialog = allTextLower.any {
            it.contains("prices are higher") ||
            it.contains("demand is high") ||
            it.contains("الأسعار أعلى")
        }

        if (hasSurgeDialog) {
            Log.i(TAG, "⚡ Detected Uber surge pricing dialog")
            // Look for accept/confirm button
            val confirmTexts = listOf("Accept", "Confirm", "Got it", "OK", "قبول", "تأكيد")
            for (confirmText in confirmTexts) {
                val nodes = rootNode.findAccessibilityNodeInfosByText(confirmText)
                for (node in nodes) {
                    if (node.isClickable) {
                        node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                        Log.i(TAG, "✓ Accepted surge pricing")
                        node.recycle()
                        return true
                    }
                    node.recycle()
                }
            }
        }

        return false
    }

    /**
     * Handle Careem intermediate screens
     */
    private fun handleCareemIntermediateScreens(rootNode: AccessibilityNodeInfo): Boolean {
        // Similar logic for Careem dialogs
        return false
    }

    /**
     * Handle Bolt intermediate screens
     */
    private fun handleBoltIntermediateScreens(rootNode: AccessibilityNodeInfo): Boolean {
        // Similar logic for Bolt dialogs
        return false
    }

    /**
     * Handle InDriver intermediate screens
     * InDriver may show: permission dialogs, promo screens, safety tips
     * CRITICAL: Also handles:
     * 1. "لا توجد نتائج" (No results) → Click "اختر على الخريطة" (Choose on map)
     * 2. MAP CONFIRMATION screen with "تم" (Done) button
     */
    private fun handleInDriverIntermediateScreens(rootNode: AccessibilityNodeInfo): Boolean {
        Log.i(TAG, "🗺️ handleInDriverIntermediateScreens called")

        val allText = getAllTextFromNode(rootNode)
        val allTextLower = allText.map { it.lowercase() }

        // DEBUG: Log all visible text on screen
        Log.i(TAG, "🗺️ DEBUG: All visible text on InDriver screen:")
        allText.take(20).forEach { text ->
            Log.i(TAG, "🗺️   - '$text'")
        }

        // Cooldown to prevent clicking too fast (InDriver crashes with rapid interactions)
        // Reduced from 3000 to 1500ms to allow faster retries
        val currentTime = System.currentTimeMillis()
        if (currentTime - lastInDriverClickTime < 1500) {
            Log.i(TAG, "🗺️ InDriver cooldown active, waiting...")
            return true // Return true to prevent other actions
        }

        // ============================================================
        // STEP 0: Check if we're on the PRICE SCREEN (البحث عن عروض)
        // If InDriver auto-filled pickup & destination, it goes directly here!
        // No need to click "تم" at all!
        // ============================================================
        val hasPriceScreen = allText.any {
            it.contains("البحث عن عروض") ||
            it.contains("الأجر المقترح") ||
            it.contains("اطلب رحلة") ||
            it.contains("السعر المقترح") ||
            it.contains("عرض السعر") ||
            it.contains("اختر سيارة") ||
            it.contains("Search for offers") ||
            it.contains("Suggested fare") ||
            it.contains("Request ride")
        }

        // Also check for price indicators (EGP with numbers)
        val hasPriceIndicator = allText.any {
            it.contains("EGP") || it.matches(Regex(".*\\d+\\s*(جنيه|ج\\.م|EGP).*"))
        }

        // Check for vehicle type selection (indicates price screen)
        val hasVehicleTypes = allText.count {
            it == "رحلة" || it == "مريحة" || it == "درجة نارية" || it == "بريميوم" ||
            it == "اقتصادي" || it == "Economy" || it == "Comfort" || it == "Premium"
        } >= 2

        // Check if "تم" button is present - if so, we're still on intermediate screen
        val hasTamButtonOnScreen = allText.any { it == "تم" || it == "Done" }

        // Log all text for debugging when we think we might be on price screen
        if (hasPriceScreen || hasPriceIndicator || hasVehicleTypes) {
            Log.i(TAG, "🗺️ Screen analysis: hasPriceScreen=$hasPriceScreen, hasPriceIndicator=$hasPriceIndicator, hasVehicleTypes=$hasVehicleTypes, hasTam=$hasTamButtonOnScreen")
            Log.i(TAG, "🗺️ All text (first 30): ${allText.take(30)}")
        }

        // FIXED: Check for FINAL PRICE SCREEN first, REGARDLESS of whether "تم" is visible!
        // The price screen has "البحث عن عروض" button OR (prices + vehicle types)
        // Even if there's a "تم" somewhere on screen, if we see the price screen indicators, we're done!
        val isFinalPriceScreen = hasPriceScreen || (hasPriceIndicator && hasVehicleTypes)

        if (isFinalPriceScreen) {
            Log.i(TAG, "🗺️ ✓✓✓ DETECTED PRICE SCREEN! (البحث عن عروض / الأجر المقترح)")
            Log.i(TAG, "🗺️ hasPriceScreen=$hasPriceScreen, hasPriceIndicator=$hasPriceIndicator, hasVehicleTypes=$hasVehicleTypes")
            Log.i(TAG, "🗺️ Note: hasTam=$hasTamButtonOnScreen (ignored - we're on final screen)")

            // Enable price detection NOW - we're on the real price screen!
            if (!inDriverAutomationComplete) {
                Log.i(TAG, "🗺️ ✓ ENABLING price detection - on price screen!")
                inDriverAutomationComplete = true
            }

            // NOT an intermediate screen - return false to allow price extraction
            return false
        }

        // Only treat as intermediate "تم" screen if NOT on price screen
        if (hasTamButtonOnScreen) {
            Log.i(TAG, "🗺️ 'تم' button detected on non-price screen - need to click through")
            // Don't return false here - let the code below handle clicking "تم"
        }

        // ============================================================
        // STEP 1: Check for "No Results" screen - need to click "Choose on map"
        // This appears when InDriver can't find the coordinates as an address
        // ============================================================
        val hasNoResults = allText.any {
            it.contains("لا توجد نتائج") ||
            it.contains("No results") ||
            it.contains("لا توجد") ||
            it.contains("نتائج") // partial match
        }

        if (hasNoResults) {
            Log.i(TAG, "🗺️ ✓ Detected InDriver 'No Results' screen - looking for 'Choose on map' option")

            // Look for "اختر على الخريطة" (Choose on map) button - try many variations
            val chooseMapTexts = listOf(
                "اختر على الخريطة",
                "Choose on map",
                "على الخريطة",
                "الخريطة",
                "اختر",
                "choose",
                "map"
            )
            for (chooseText in chooseMapTexts) {
                val nodes = rootNode.findAccessibilityNodeInfosByText(chooseText)
                Log.i(TAG, "🗺️ Searching for '$chooseText' - found ${nodes.size} nodes")
                for (node in nodes) {
                    val nodeText = node.text?.toString() ?: ""
                    val nodeDesc = node.contentDescription?.toString() ?: ""
                    Log.i(TAG, "🗺️ Found node: text='$nodeText', desc='$nodeDesc', clickable=${node.isClickable}")

                    // Use gentle click for InDriver - just ACTION_CLICK, no gestures
                    if (gentleClick(node)) {
                        Log.i(TAG, "🗺️ ✓ Clicked '$chooseText' option - transitioning to map screen")
                        lastInDriverClickTime = currentTime
                        // CRITICAL: Clear cached price again - we clicked "Choose on map"
                        // so any price shown before is NOT the real trip price
                        latestPrices.remove(INDRIVER_PACKAGE)
                        Log.i(TAG, "🗺️ ✓ Cleared cached price after 'Choose on map' click")
                        node.recycle()
                        return true
                    }
                    node.recycle()
                }
            }

            // Even if we couldn't click, return true to indicate we're handling "No Results" screen
            // This prevents the automation from trying to click non-existent suggestions
            Log.w(TAG, "🗺️ Could not find/click 'Choose on map' option - will retry")
            return true // Return true to stay in handling mode
        }

        // ============================================================
        // STEP 1.5: Check for SUGGESTIONS SCREEN after entering destination text
        // When we enter a text address, InDriver shows suggestions:
        //   - First item: "اختر على الخريطة" (Choose on map) - SKIP THIS
        //   - Second item: First actual suggestion - CLICK THIS
        // Clicking on a suggestion will auto-navigate to the price screen!
        // ============================================================
        val hasChooseOnMapOption = allText.any {
            it.contains("اختر على الخريطة") || it.contains("Choose on map")
        }
        val hasDestinationField = allText.any {
            it.contains("إلى أين") || it.contains("Where to") || it.contains("الى")
        }
        // Check if we see suggestions by looking for typical location patterns
        // IMPORTANT: Include DISTANCE INDICATORS (متر, كم) which appear with pickup suggestions!
        val hasSuggestionItems = allText.any {
            // Destination-type patterns (locations, areas)
            ((it.contains("Egypt") || it.contains("مصر") || it.contains("KALIOBEYA") ||
             it.contains("محافظة") || it.contains("القاهرة") || it.contains("العبور") ||
             it.contains("الحي") || it.contains("Mall") || it.contains("كارفور") ||
             it.contains("شارع") || it.contains("ميدان") || it.contains("حي") ||
             // PICKUP SUGGESTION indicators - distance markers!
             it.contains("متر") || it.contains("كم") || it.contains("km") || it.contains(" m ") ||
             // Common areas that might appear
             it.contains("Obour") || it.contains("Nasr") || it.contains("Heliopolis") ||
             it.contains("Maadi") || it.contains("Zamalek") || it.contains("Mohandessin") ||
             it.contains("مدينة") || it.contains("منطقة") || it.contains("مركز") ||
             // When pickup suggestions appear, might see these
             it.contains("Riyad") || it.contains("رياض") || it.contains("Abdel"))) &&
            !it.contains("اختر على الخريطة") && !it.contains("Choose on map")
        }

        // Also check for PICKUP field focus (indicates we need to click pickup suggestion)
        val hasPickupFieldFocus = allText.any {
            it.contains("من أين") || it.contains("From where") || it.contains("نقطة الإقلال")
        }

        // SPECIAL: Detect pickup suggestions screen by looking for distance patterns with location names
        // This handles the case where InDriver shows pickup suggestions AFTER destination was clicked
        val hasPickupSuggestionsWithDistance = allText.any { it.contains("متر") || it.contains("كم") } &&
            allText.any { it.contains("Abdel") || it.contains("Obour") || it.contains("العبور") ||
                          it.contains("Riyad") || it.contains("رياض") || it.contains("Nasr") ||
                          it.contains("Maadi") || it.contains("معادي") || it.contains("Heliopolis") }

        Log.i(TAG, "🗺️ SUGGESTIONS CHECK: hasChooseOnMap=$hasChooseOnMapOption, hasDestField=$hasDestinationField, hasSuggestions=$hasSuggestionItems, hasPickupFocus=$hasPickupFieldFocus, hasPickupWithDist=$hasPickupSuggestionsWithDistance")

        // If we see suggestions (either with "Choose on map" for destination, OR pickup suggestions with distance markers)
        // IMPORTANT: Pickup suggestions after destination click may NOT have "Choose on map"!
        val shouldClickSuggestion = (hasChooseOnMapOption && hasSuggestionItems && !hasNoResults) ||
                                     (hasPickupFieldFocus && hasSuggestionItems && !hasNoResults) ||
                                     (hasPickupSuggestionsWithDistance && !hasNoResults)  // NEW: Pickup with distance

        if (shouldClickSuggestion) {
            val suggestionType = if (hasPickupFieldFocus || hasPickupSuggestionsWithDistance) "PICKUP" else "DESTINATION"
            Log.i(TAG, "🗺️ ✓ Detected $suggestionType SUGGESTIONS SCREEN - looking for first real suggestion to click")

            // Collect all suggestions from the screen
            val suggestions = mutableListOf<Pair<AccessibilityNodeInfo, String>>()
            collectInDriverSuggestions(rootNode, suggestions)

            Log.i(TAG, "🗺️ Found ${suggestions.size} total suggestions:")
            suggestions.forEachIndexed { index, (_, text) ->
                Log.i(TAG, "🗺️   [$index] '$text'")
            }

            // Filter out "اختر على الخريطة", coordinates, and similar options
            val coordPattern = Regex("^\\d+\\.\\d+,\\s*\\d+\\.\\d+$")
            var realSuggestions = suggestions.filter { (_, text) ->
                !text.contains("اختر على الخريطة") &&
                !text.contains("Choose on map") &&
                !text.contains("على الخريطة") &&
                !text.contains("لا توجد نتائج") &&
                !text.contains("No results") &&
                !coordPattern.matches(text.trim()) &&  // Exclude coordinates like "30.123, 31.456"
                !text.matches(Regex("^\\d+\\.\\d+.*")) &&  // Exclude text starting with decimals
                text.length > 5  // Skip very short texts
            }

            // FALLBACK: If no suggestions collected, search for known patterns from visible text
            if (realSuggestions.isEmpty()) {
                Log.i(TAG, "🗺️ No suggestions from collectInDriverSuggestions - trying TEXT SEARCH fallback")

                // Look for suggestion names we saw in allText
                // Include BOTH destination patterns (Coffee, Mall) AND pickup patterns (distance indicators)
                val potentialSuggestions = allText.filter { text ->
                    (text.contains("Coffee") || text.contains("Shop") || text.contains("Mall") ||
                     text.contains("كافيه") || text.contains("مطعم") || text.contains("Restaurant") ||
                     // Pickup suggestion patterns - distance indicators
                     text.contains("متر") || text.contains("كم") || text.contains("km") ||
                     // Common location patterns
                     text.contains("Obour") || text.contains("العبور") || text.contains("Nasr") ||
                     text.contains("Heliopolis") || text.contains("مصر") || text.contains("Cairo") ||
                     text.contains("Riyad") || text.contains("رياض") || text.contains("شارع") ||
                     text.contains("ميدان") || text.contains("مدينة") || text.contains("منطقة")) &&
                    !text.contains("اختر") && !text.contains("Choose") &&
                    text.length > 5 && text.length < 100
                }

                Log.i(TAG, "🗺️ Potential suggestions from visible text: $potentialSuggestions")

                for (potentialText in potentialSuggestions) {
                    val nodes = rootNode.findAccessibilityNodeInfosByText(potentialText)
                    Log.i(TAG, "🗺️ Searching for '$potentialText' - found ${nodes.size} nodes")

                    for (node in nodes) {
                        val nodeText = node.text?.toString() ?: ""
                        val nodeClass = node.className?.toString() ?: ""
                        Log.i(TAG, "🗺️   Node: text='$nodeText', class='$nodeClass', clickable=${node.isClickable}")

                        // Try to find clickable parent if node itself isn't clickable
                        var clickableNode: AccessibilityNodeInfo? = null
                        if (node.isClickable) {
                            clickableNode = AccessibilityNodeInfo.obtain(node)
                        } else {
                            // Look for clickable parent
                            var parent = node.parent
                            var depth = 0
                            while (parent != null && depth < 5) {
                                if (parent.isClickable) {
                                    clickableNode = AccessibilityNodeInfo.obtain(parent)
                                    Log.i(TAG, "🗺️   Found clickable parent at depth $depth")
                                    break
                                }
                                val nextParent = parent.parent
                                parent.recycle()
                                parent = nextParent
                                depth++
                            }
                            parent?.recycle()
                        }

                        if (clickableNode != null) {
                            suggestions.add(Pair(clickableNode, potentialText))
                            Log.i(TAG, "🗺️ ✓ Added suggestion via TEXT SEARCH: '$potentialText'")
                        }
                        node.recycle()
                    }
                }

                // Re-filter after adding text-search results
                realSuggestions = suggestions.filter { (_, text) ->
                    !text.contains("اختر على الخريطة") &&
                    !text.contains("Choose on map") &&
                    !coordPattern.matches(text.trim()) &&
                    !text.matches(Regex("^\\d+\\.\\d+.*")) &&
                    text.length > 5
                }
            }

            Log.i(TAG, "🗺️ Found ${realSuggestions.size} REAL suggestions (excluding 'Choose on map'):")
            realSuggestions.forEachIndexed { index, (_, text) ->
                Log.i(TAG, "🗺️   [$index] '$text'")
            }

            // Click the FIRST real suggestion
            if (realSuggestions.isNotEmpty()) {
                val (node, text) = realSuggestions[0]
                Log.i(TAG, "🗺️ ✓✓✓ CLICKING FIRST SUGGESTION: '$text'")

                // Get bounds for smart click
                val bounds = android.graphics.Rect()
                node.getBoundsInScreen(bounds)
                Log.i(TAG, "🗺️ Suggestion bounds: ${bounds.left},${bounds.top},${bounds.right},${bounds.bottom}")

                var clicked = false

                // Strategy 1: Try ACTION_CLICK on the node directly (most reliable for accessibility)
                Log.i(TAG, "🗺️ Strategy 1: Trying ACTION_CLICK on suggestion node...")
                if (node.isClickable) {
                    clicked = node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    Log.i(TAG, "🗺️ ACTION_CLICK result: $clicked")
                }

                // Strategy 2: Try clicking on clickable CHILDREN of the suggestion row
                if (!clicked) {
                    Log.i(TAG, "🗺️ Strategy 2: Looking for clickable children...")
                    for (i in 0 until node.childCount) {
                        val child = node.getChild(i) ?: continue
                        if (child.isClickable) {
                            Log.i(TAG, "🗺️ Found clickable child at index $i, clicking...")
                            clicked = child.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                            child.recycle()
                            if (clicked) {
                                Log.i(TAG, "🗺️ Child click succeeded!")
                                break
                            }
                        }
                        child.recycle()
                    }
                }

                // Strategy 3: Try gentleClick (works by finding clickable parent/child)
                if (!clicked) {
                    Log.i(TAG, "🗺️ Strategy 3: Trying gentleClick...")
                    clicked = gentleClick(node)
                    Log.i(TAG, "🗺️ gentleClick result: $clicked")
                }

                // Strategy 4: Try gesture click at center of bounds
                if (!clicked && bounds.width() > 0 && bounds.height() > 0) {
                    val centerX = bounds.centerX().toFloat()
                    val centerY = bounds.centerY().toFloat()
                    Log.i(TAG, "🗺️ Strategy 4: Trying gesture click at ($centerX, $centerY)")
                    clicked = clickAtPosition(centerX, centerY)
                    Log.i(TAG, "🗺️ Gesture click result: $clicked")
                }

                // Strategy 5: Try gesture click at different Y positions with LONGER duration (200ms)
                // InDriver may need a longer tap duration to register the click
                if (!clicked || true) { // Always try this as extra measure
                    Log.i(TAG, "🗺️ Strategy 5: Trying LONGER TAP (200ms) at multiple positions...")
                    val centerX = bounds.centerX().toFloat()
                    // Try clicking at 1/3, 1/2, and 2/3 of the row height with longer duration
                    val positions = listOf(
                        bounds.top + bounds.height() * 0.33f,
                        bounds.top + bounds.height() * 0.5f,
                        bounds.top + bounds.height() * 0.67f
                    )
                    for (yPos in positions) {
                        Log.i(TAG, "🗺️ Long tap at ($centerX, $yPos) with 200ms duration...")
                        if (clickAtPositionWithDuration(centerX, yPos, 200)) {
                            clicked = true
                            Log.i(TAG, "🗺️ Long tap at Y=$yPos succeeded!")
                            break
                        }
                    }
                }

                // Strategy 6: Try even LONGER tap (300ms) at center if nothing else worked
                if (!clicked) {
                    Log.i(TAG, "🗺️ Strategy 6: Trying EXTRA LONG TAP (300ms)...")
                    val centerX = bounds.centerX().toFloat()
                    val centerY = bounds.centerY().toFloat()
                    clicked = clickAtPositionWithDuration(centerX, centerY, 300)
                    Log.i(TAG, "🗺️ Extra long tap result: $clicked")
                }

                // Recycle all nodes
                suggestions.forEach { (n, _) ->
                    try { n.recycle() } catch (e: Exception) {}
                }

                if (clicked) {
                    Log.i(TAG, "🗺️ ✓✓✓ CLICKED SUGGESTION '$text' - should navigate to price screen!")
                    lastInDriverClickTime = currentTime
                    return true
                } else {
                    Log.w(TAG, "🗺️ ✗ Could not click suggestion '$text' - trying keyboard Done as fallback...")

                    // FALLBACK: Try pressing keyboard "Done" key instead of clicking suggestion
                    if (pressKeyboardDone()) {
                        Log.i(TAG, "🗺️ ✓✓✓ KEYBOARD DONE pressed - should navigate to price screen!")
                        lastInDriverClickTime = currentTime
                        return true
                    }
                }
            } else {
                Log.w(TAG, "🗺️ No real suggestions found - trying keyboard Done...")
                // Recycle nodes anyway
                suggestions.forEach { (n, _) ->
                    try { n.recycle() } catch (e: Exception) {}
                }

                // FALLBACK: Try pressing keyboard "Done" key
                if (pressKeyboardDone()) {
                    Log.i(TAG, "🗺️ ✓✓✓ KEYBOARD DONE pressed (no suggestions) - should navigate to price screen!")
                    lastInDriverClickTime = currentTime
                    return true
                }
            }
        }

        // ============================================================
        // STEP 2: Check for MAP CONFIRMATION screen with "تم" or "تطبيق" button
        // InDriver has 3 confirmation screens:
        //   1. Destination map → "تم"
        //   2. Pickup map → "تم"
        //   3. Final pickup confirmation → "تطبيق" (or "تأكيد")
        // ============================================================
        val hasTamButton = allText.any { it == "تم" }
        val hasTatbeeqButton = allText.any { it == "تطبيق" || it.contains("تطبيق") }  // "Apply" button
        val hasConfirmButton = allText.any { it == "تأكيد" || it.contains("تأكيد نقطة") }  // "Confirm" button
        val hasDoneButton = allTextLower.any { it == "done" || it == "confirm" || it == "apply" }
        val hasMapConfirmation = hasTamButton || hasTatbeeqButton || hasConfirmButton || hasDoneButton

        // Check for final confirmation screen "تأكيد نقطة الإقلال"
        val isFinalConfirmScreen = allText.any {
            it.contains("تأكيد نقطة الإقلال") || it.contains("تأكيد نقطة الانطلاق") ||
            it.contains("Confirm pickup") || it.contains("confirm pickup")
        }

        Log.i(TAG, "🗺️ hasTamButton=$hasTamButton, hasTatbeeqButton=$hasTatbeeqButton, hasConfirmButton=$hasConfirmButton, isFinalConfirm=$isFinalConfirmScreen")

        // Also check if we see Google maps elements (indicates map confirmation screen)
        val hasGoogleMap = allTextLower.any { it.contains("google") }

        if (hasMapConfirmation) {
            // InDriver has TWO map confirmation screens:
            // 1. Destination confirmation - first "تم" click
            // 2. Pickup confirmation - second "تم" click
            // We need to allow both clicks but prevent rapid double-clicking

            val timeSinceDoneClick = if (inDriverDoneClickedTime > 0) System.currentTimeMillis() - inDriverDoneClickedTime else Long.MAX_VALUE
            val minTimeBetweenClicks = 800L  // OPTIMIZED: Reduced from 2000ms to 800ms for faster navigation

            // Check for pickup screen markers (indicates we need second click)
            val isPickupScreen = allText.any {
                it.contains("نقطة الإقلال") || it.contains("من أين") || it.contains("Pickup")
            }

            // Check for destination screen markers
            val isDestinationScreen = allText.any {
                it.contains("الوجهة") || it.contains("إلى أين") || it.contains("Where to")
            }

            Log.i(TAG, "🗺️ Map confirmation: clickCount=$inDriverDoneClickCount, isPickup=$isPickupScreen, isDest=$isDestinationScreen, timeSinceClick=${timeSinceDoneClick}ms")

            // Block if we've already made max clicks
            if (inDriverDoneClickCount >= INDRIVER_MAX_DONE_CLICKS) {
                Log.i(TAG, "🗺️ Already clicked 'تم' $inDriverDoneClickCount times (max=$INDRIVER_MAX_DONE_CLICKS) - NOT clicking again!")
                return false
            }

            // Block rapid double-clicking (wait at least 2 seconds between clicks)
            if (inDriverDoneClickedTime > 0 && timeSinceDoneClick < minTimeBetweenClicks) {
                Log.i(TAG, "🗺️ Too soon since last click (${timeSinceDoneClick}ms < ${minTimeBetweenClicks}ms) - waiting...")
                return true  // Return true to stay in handling mode
            }

            // REMOVED: Don't block waiting for pickup screen markers - just click "تم" when we see it
            // After entering both pickup and destination, InDriver may show multiple "تم" screens
            // Just click through them all to get to "البحث عن عروض"

            val clickType = when (inDriverDoneClickCount) {
                0 -> "DESTINATION"
                1 -> "PICKUP"
                else -> "CONFIRM_$inDriverDoneClickCount"
            }
            Log.i(TAG, "🗺️ Detected InDriver MAP CONFIRMATION screen ($clickType) - clicking 'تم' button")

            // Try to click the "تم" button - USE GESTURE CLICK because ACTION_CLICK doesn't work reliably
            // Include "تطبيق" (Apply) for the final confirmation screen
            val doneTexts = listOf("تم", "تطبيق", "Done", "Confirm", "تأكيد", "Apply")
            for (doneText in doneTexts) {
                val nodes = rootNode.findAccessibilityNodeInfosByText(doneText)
                for (node in nodes) {
                    Log.i(TAG, "🗺️ Found '$doneText' button, attempting GESTURE click...")

                    // Get bounds for gesture click
                    val bounds = android.graphics.Rect()
                    node.getBoundsInScreen(bounds)
                    Log.i(TAG, "🗺️ Button bounds: ${bounds.left},${bounds.top},${bounds.right},${bounds.bottom} (${bounds.width()}x${bounds.height()})")

                    var clicked = false

                    // Check if bounds are VALID (positive width and height)
                    val boundsValid = bounds.width() > 0 && bounds.height() > 0

                    // Strategy 1: Gesture click at center (most reliable for InDriver)
                    if (boundsValid) {
                        val centerX = bounds.centerX().toFloat()
                        val centerY = bounds.centerY().toFloat()
                        Log.i(TAG, "🗺️ Strategy 1: Gesture click at ($centerX, $centerY)")

                        if (clickAtPosition(centerX, centerY)) {
                            Log.i(TAG, "🗺️ ✓✓✓ GESTURE CLICK SUCCESS at ($centerX, $centerY)")
                            clicked = true
                        }
                    } else {
                        Log.w(TAG, "🗺️ Strategy 1 SKIPPED: Invalid bounds (width=${bounds.width()}, height=${bounds.height()})")
                    }

                    // Strategy 2: If gesture failed BUT bounds are valid, try gentleClick
                    // SKIP gentleClick when bounds are invalid - it won't work reliably!
                    if (!clicked && boundsValid) {
                        Log.i(TAG, "🗺️ Strategy 2: Trying gentleClick (bounds valid)...")
                        clicked = gentleClick(node)
                    } else if (!clicked && !boundsValid) {
                        Log.w(TAG, "🗺️ Strategy 2 SKIPPED: Bounds invalid, gentleClick won't work!")
                    }

                    // Strategy 3: Use HARDCODED screen position when bounds are invalid
                    // The "تم" button is typically at the bottom center of the screen
                    if (!clicked && !boundsValid) {
                        Log.i(TAG, "🗺️ Strategy 3: Using HARDCODED bottom button position...")
                        val displayMetrics = resources.displayMetrics
                        val screenWidth = displayMetrics.widthPixels.toFloat()
                        val screenHeight = displayMetrics.heightPixels.toFloat()
                        // Button is at bottom center - try 85% down the screen
                        val hardcodedX = screenWidth / 2
                        val hardcodedY = screenHeight * 0.85f
                        Log.i(TAG, "🗺️ Hardcoded click at ($hardcodedX, $hardcodedY) - screen size: ${screenWidth}x${screenHeight}")

                        if (clickAtPosition(hardcodedX, hardcodedY)) {
                            Log.i(TAG, "🗺️ ✓✓✓ HARDCODED CLICK SUCCESS!")
                            clicked = true
                        }
                    }

                    // Strategy 4: Try slightly lower position (90% down)
                    if (!clicked) {
                        Log.i(TAG, "🗺️ Strategy 4: Trying lower position (90%)...")
                        val displayMetrics = resources.displayMetrics
                        val screenWidth = displayMetrics.widthPixels.toFloat()
                        val screenHeight = displayMetrics.heightPixels.toFloat()
                        val hardcodedX = screenWidth / 2
                        val hardcodedY = screenHeight * 0.90f

                        if (clickAtPosition(hardcodedX, hardcodedY)) {
                            Log.i(TAG, "🗺️ ✓✓✓ LOWER POSITION CLICK SUCCESS!")
                            clicked = true
                        }
                    }

                    if (clicked) {
                        inDriverDoneClickCount++
                        val clickTypeMsg = if (inDriverDoneClickCount == 1) "DESTINATION" else "PICKUP"
                        Log.i(TAG, "🗺️ ✓ Clicked '$doneText' button for $clickTypeMsg (click #$inDriverDoneClickCount)")
                        lastInDriverClickTime = currentTime
                        // CRITICAL: Clear cached price after clicking Done - wait for real trip price
                        latestPrices.remove(INDRIVER_PACKAGE)
                        // CRITICAL: Set timestamp - must wait INDRIVER_MIN_WAIT_AFTER_DONE_MS before accepting price
                        inDriverDoneClickedTime = System.currentTimeMillis()
                        Log.i(TAG, "🗺️ ✓ Cleared cached price after '$clickTypeMsg' click - waiting for screen transition")
                        node.recycle()
                        return true
                    }
                    node.recycle()
                }
            }

            // If all strategies failed, try clicking at the bottom of screen anyway
            // This is a last resort when the button can't be found
            Log.i(TAG, "🗺️ All button click strategies failed, trying direct bottom click...")
            val displayMetrics = resources.displayMetrics
            val screenWidth = displayMetrics.widthPixels.toFloat()
            val screenHeight = displayMetrics.heightPixels.toFloat()

            // Try multiple positions at the bottom
            val bottomPositions = listOf(0.85f, 0.88f, 0.90f, 0.92f)
            for (yPercent in bottomPositions) {
                val x = screenWidth / 2
                val y = screenHeight * yPercent
                Log.i(TAG, "🗺️ Trying bottom position: ($x, $y) - ${(yPercent * 100).toInt()}%")
                if (clickAtPosition(x, y)) {
                    inDriverDoneClickCount++
                    Log.i(TAG, "🗺️ ✓ Bottom click at ${(yPercent * 100).toInt()}% worked! (click #$inDriverDoneClickCount)")
                    lastInDriverClickTime = currentTime
                    latestPrices.remove(INDRIVER_PACKAGE)
                    inDriverDoneClickedTime = System.currentTimeMillis()
                    return true
                }
            }

            // If nothing worked, show toast as fallback
            if (currentTime - lastInDriverToastTime > toastDebounce) {
                lastInDriverToastTime = currentTime
                handler.post {
                    Toast.makeText(
                        this@PriceReaderService,
                        "اضغط على زر 'تم' لتأكيد الوجهة 👆",
                        Toast.LENGTH_LONG
                    ).show()
                }
                Log.i(TAG, "🗺️ Showed toast prompting user to tap 'تم' button")
            }
            return true // Return true to indicate we're handling this screen
        }

        return false
    }

    /**
     * Find and click the Done/تم button by traversing the UI tree
     * Looks for buttons that are likely the confirmation button
     * Uses multiple strategies: ACTION_CLICK, parent click, and gesture-based click
     */
    private fun findAndClickDoneButton(node: AccessibilityNodeInfo): Boolean {
        val text = node.text?.toString() ?: ""
        val desc = node.contentDescription?.toString() ?: ""
        val className = node.className?.toString() ?: ""

        // Check if this is likely the Done button
        val isDoneButton = text == "تم" || text.equals("Done", ignoreCase = true) ||
                           text == "تأكيد" || text.equals("Confirm", ignoreCase = true) ||
                           desc == "تم" || desc.equals("Done", ignoreCase = true)

        // Also check for Button class with these texts
        val isButton = className.contains("Button") || className.contains("TextView")

        if (isDoneButton) {
            Log.i(TAG, "🗺️ [findAndClickDoneButton] Found: [$className] text='$text' clickable=${node.isClickable}")

            // Try refresh() to get updated bounds
            node.refresh()

            // Strategy 1: GESTURE CLICK FIRST - more reliable for custom UI
            val bounds = android.graphics.Rect()
            node.getBoundsInScreen(bounds)
            Log.i(TAG, "🗺️ [findAndClickDoneButton] Bounds: ${bounds.left},${bounds.top},${bounds.right},${bounds.bottom} (${bounds.width()}x${bounds.height()})")

            if (bounds.width() > 0 && bounds.height() > 0) {
                val centerX = bounds.centerX().toFloat()
                val centerY = bounds.centerY().toFloat()
                Log.i(TAG, "🗺️ [findAndClickDoneButton] Strategy 1: Gesture click at ($centerX, $centerY)")

                if (clickAtPosition(centerX, centerY)) {
                    Log.i(TAG, "🗺️ ✓✓✓ SUCCESS: Clicked Done button via GESTURE!")
                    Thread.sleep(500)
                    return true
                }
            } else {
                Log.w(TAG, "🗺️ [findAndClickDoneButton] Bounds are EMPTY - trying parents")
            }

            // Strategy 2: Try parent bounds (even if node bounds are empty)
            var parent = node.parent
            for (level in 1..4) {
                if (parent == null) break
                val parentClass = parent.className?.toString()?.substringAfterLast(".") ?: ""
                parent.refresh()

                val parentBounds = android.graphics.Rect()
                parent.getBoundsInScreen(parentBounds)
                Log.i(TAG, "🗺️ [findAndClickDoneButton] Parent L$level [$parentClass] bounds: ${parentBounds.width()}x${parentBounds.height()}")

                if (parentBounds.width() > 0 && parentBounds.height() > 0) {
                    val px = parentBounds.centerX().toFloat()
                    val py = parentBounds.centerY().toFloat()
                    Log.i(TAG, "🗺️ [findAndClickDoneButton] Strategy 2: Parent L$level gesture at ($px, $py)")

                    if (clickAtPosition(px, py)) {
                        Log.i(TAG, "🗺️ ✓✓✓ SUCCESS: Clicked parent L$level via gesture!")
                        parent.recycle()
                        Thread.sleep(500)
                        return true
                    }
                }

                val next = parent.parent
                parent.recycle()
                parent = next
            }

            // Strategy 3: performAction click (often doesn't work but try anyway)
            if (node.isClickable) {
                Log.i(TAG, "🗺️ [findAndClickDoneButton] Strategy 3: performAction click")
                node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                // Don't return true - performAction often returns true but doesn't actually click
            }

            // Strategy 4: Hardcoded screen position
            Log.i(TAG, "🗺️ [findAndClickDoneButton] Strategy 4: Hardcoded position")
            val displayMetrics = resources.displayMetrics
            val screenWidth = displayMetrics.widthPixels.toFloat()
            val screenHeight = displayMetrics.heightPixels.toFloat()
            val hardcodedX = screenWidth / 2
            val hardcodedY = screenHeight * 0.85f
            Log.i(TAG, "🗺️ [findAndClickDoneButton] Hardcoded click at ($hardcodedX, $hardcodedY)")

            if (clickAtPosition(hardcodedX, hardcodedY)) {
                Log.i(TAG, "🗺️ ✓✓✓ SUCCESS: Clicked via HARDCODED position!")
                Thread.sleep(500)
                return true
            }
        }

        // Recursively check children
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            if (findAndClickDoneButton(child)) {
                child.recycle()
                return true
            }
            child.recycle()
        }

        return false
    }

    /**
     * Simulate pressing the keyboard "Done" key
     * This triggers the IME_ACTION_DONE which InDriver uses to navigate to price screen
     */
    private fun pressKeyboardDone(): Boolean {
        Log.i(TAG, "⌨️ Attempting to press keyboard Done key...")

        // Method 1: Try clicking the "Done" button in the keyboard if visible
        // Look for text like "Done", "تم", "Go", etc. in the lower part of screen
        val rootNode = rootInActiveWindow ?: return false

        // Try to find keyboard Done button
        val doneTexts = listOf("Done", "تم", "Go", "Search", "Enter", "بحث")
        for (doneText in doneTexts) {
            val nodes = rootNode.findAccessibilityNodeInfosByText(doneText)
            for (node in nodes) {
                val bounds = android.graphics.Rect()
                node.getBoundsInScreen(bounds)

                // Keyboard buttons are usually at the bottom of the screen (y > 1500)
                if (bounds.top > 1200 && node.isClickable) {
                    Log.i(TAG, "⌨️ Found keyboard '$doneText' button at ${bounds.left},${bounds.top}")
                    if (node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                        Log.i(TAG, "⌨️ ✓ Clicked keyboard Done button!")
                        node.recycle()
                        rootNode.recycle()
                        return true
                    }
                }

                // Try gesture click on the button
                if (bounds.top > 1200 && bounds.width() > 0 && bounds.height() > 0) {
                    val x = (bounds.left + bounds.right) / 2f
                    val y = (bounds.top + bounds.bottom) / 2f
                    Log.i(TAG, "⌨️ Trying gesture click on '$doneText' at ($x, $y)")
                    if (clickAtPositionWithDuration(x, y, 150)) {
                        Log.i(TAG, "⌨️ ✓ Gesture clicked keyboard Done!")
                        node.recycle()
                        rootNode.recycle()
                        return true
                    }
                }
                node.recycle()
            }
        }

        // Method 2: Find focused EditText and perform IME action
        val focusedNode = rootNode.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        if (focusedNode != null) {
            Log.i(TAG, "⌨️ Found focused input: ${focusedNode.className}")

            // Try ACTION_NEXT which often triggers Done behavior
            if (focusedNode.performAction(AccessibilityNodeInfo.ACTION_NEXT_AT_MOVEMENT_GRANULARITY)) {
                Log.i(TAG, "⌨️ ACTION_NEXT succeeded")
            }

            // For API 30+, try IME action
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                val imeEnterAction = AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER
                if (focusedNode.performAction(imeEnterAction.id)) {
                    Log.i(TAG, "⌨️ ✓ ACTION_IME_ENTER succeeded!")
                    focusedNode.recycle()
                    rootNode.recycle()
                    return true
                }
            }
            focusedNode.recycle()
        }

        // Method 3: Try to find and click "البحث عن عروض" or similar buttons
        val searchTexts = listOf("البحث عن عروض", "بحث", "Search", "Find offers")
        for (searchText in searchTexts) {
            val nodes = rootNode.findAccessibilityNodeInfosByText(searchText)
            for (node in nodes) {
                if (node.isClickable) {
                    Log.i(TAG, "⌨️ Found '$searchText' button, clicking...")
                    if (node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                        Log.i(TAG, "⌨️ ✓ Clicked '$searchText' button!")
                        node.recycle()
                        rootNode.recycle()
                        return true
                    }
                }
                node.recycle()
            }
        }

        rootNode.recycle()
        Log.w(TAG, "⌨️ Could not find keyboard Done button")
        return false
    }

    /**
     * Click at a specific screen position using accessibility gesture
     * This is more reliable than performAction for some UI elements
     */
    private fun clickAtPosition(x: Float, y: Float): Boolean {
        return clickAtPositionWithDuration(x, y, 100)
    }

    /**
     * Click at position with configurable duration
     * Longer durations (200-300ms) may work better for some apps like InDriver
     */
    private fun clickAtPositionWithDuration(x: Float, y: Float, durationMs: Long): Boolean {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
            val path = android.graphics.Path()
            path.moveTo(x, y)

            val gestureBuilder = android.accessibilityservice.GestureDescription.Builder()
            gestureBuilder.addStroke(
                android.accessibilityservice.GestureDescription.StrokeDescription(
                    path, 0, durationMs
                )
            )

            val result = dispatchGesture(gestureBuilder.build(), object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: android.accessibilityservice.GestureDescription?) {
                    Log.i(TAG, "🎯 Gesture completed at ($x, $y) duration=${durationMs}ms")
                }
                override fun onCancelled(gestureDescription: android.accessibilityservice.GestureDescription?) {
                    Log.w(TAG, "🎯 Gesture cancelled at ($x, $y)")
                }
            }, null)

            Log.i(TAG, "🎯 dispatchGesture at ($x, $y) duration=${durationMs}ms result: $result")
            return result
        }
        return false
    }

    /**
     * UNIFIED CLICK MECHANISM: Click a node using gesture tap (more reliable than ACTION_CLICK)
     * Gets the node's screen bounds and taps the center
     */
    private fun clickNodeByGesture(node: AccessibilityNodeInfo): Boolean {
        val rect = android.graphics.Rect()
        node.getBoundsInScreen(rect)

        // Calculate center point
        val centerX = rect.centerX().toFloat()
        val centerY = rect.centerY().toFloat()

        // Validate coordinates
        if (centerX <= 0 || centerY <= 0 || rect.width() <= 0 || rect.height() <= 0) {
            Log.w(TAG, "🎯 Invalid bounds for gesture tap: $rect")
            return false
        }

        Log.i(TAG, "🎯 Gesture tap on node at ($centerX, $centerY) - bounds: $rect")
        return clickAtPosition(centerX, centerY)
    }

    /**
     * SMART CLICK: Try multiple click strategies in order of reliability
     * For CLICKABLE nodes: ACTION_CLICK first (more reliable for apps that block gestures)
     * For non-clickable: Try gesture tap with delay to verify
     */
    private fun smartClick(node: AccessibilityNodeInfo): Boolean {
        val rect = android.graphics.Rect()
        node.getBoundsInScreen(rect)
        val centerX = rect.centerX()
        val centerY = rect.centerY()

        // Validate coordinates
        if (centerX <= 0 || centerY <= 0) {
            Log.w(TAG, "🎯 Invalid coordinates for click: ($centerX, $centerY)")
            return false
        }

        // Strategy 1: If node is CLICKABLE, try ACTION_CLICK FIRST
        // This is more reliable for apps like DiDi that block gestures
        if (node.isClickable) {
            Log.i(TAG, "🎯 Node is clickable (bounds=$rect), trying ACTION_CLICK...")
            if (node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                Log.i(TAG, "🎯 ✓ Smart click SUCCESS via ACTION_CLICK")
                return true
            }
            Log.w(TAG, "🎯 ACTION_CLICK returned false on clickable node")
        }

        // Strategy 2: Try clicking parent if it's clickable
        node.parent?.let { parent ->
            if (parent.isClickable) {
                Log.i(TAG, "🎯 Parent is clickable, trying ACTION_CLICK on parent...")
                if (parent.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                    Log.i(TAG, "🎯 ✓ Smart click SUCCESS via parent ACTION_CLICK")
                    parent.recycle()
                    return true
                }
            }
            parent.recycle()
        }

        // Strategy 3: Gesture tap (note: dispatchGesture returns true even if app ignores it)
        Log.i(TAG, "🎯 Trying gesture tap at ($centerX, $centerY)...")
        clickNodeByGesture(node)
        // Wait a bit and assume it might have worked
        Thread.sleep(300)

        // Strategy 4: Shell tap - DISABLED (requires root, causes SecurityException crashes)
        // shellTap(centerX, centerY)

        // Return true optimistically - we tried everything
        Log.i(TAG, "🎯 Attempted all click strategies")
        return true
    }

    /**
     * Gentle click for apps that crash with aggressive interactions (like InDriver)
     * Only uses simple ACTION_CLICK, no gestures or shell commands
     */
    private fun gentleClick(node: AccessibilityNodeInfo): Boolean {
        Log.i(TAG, "🎯 Gentle click on node...")

        // Try ACTION_CLICK on the node itself
        if (node.isClickable) {
            Log.i(TAG, "🎯 Node is clickable, trying ACTION_CLICK...")
            if (node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                Log.i(TAG, "🎯 ✓ Gentle click SUCCESS")
                Thread.sleep(500) // Wait after click
                return true
            }
        }

        // Try clicking parent if it's clickable
        node.parent?.let { parent ->
            if (parent.isClickable) {
                Log.i(TAG, "🎯 Parent is clickable, trying ACTION_CLICK on parent...")
                if (parent.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                    Log.i(TAG, "🎯 ✓ Gentle click SUCCESS via parent")
                    Thread.sleep(500)
                    parent.recycle()
                    return true
                }
            }
            parent.recycle()
        }

        Log.w(TAG, "🎯 Gentle click failed")
        return false
    }

    /**
     * Execute shell tap command - bypasses accessibility service limitations
     * Uses 'input tap x y' command which directly injects input events
     */
    private fun shellTap(x: Int, y: Int): Boolean {
        return try {
            Log.i(TAG, "🖱️ Shell tap at ($x, $y)")
            val process = Runtime.getRuntime().exec(arrayOf("input", "tap", x.toString(), y.toString()))
            val exitCode = process.waitFor()
            if (exitCode == 0) {
                Log.i(TAG, "🖱️ ✓ Shell tap SUCCESS")
                // Give the app time to process the tap
                Thread.sleep(200)
                true
            } else {
                Log.w(TAG, "🖱️ ✗ Shell tap failed with exit code: $exitCode")
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "🖱️ ✗ Shell tap error: ${e.message}")
            false
        }
    }

    private fun notifyPriceCaptured(priceInfo: PriceInfo) {
        val intent = Intent(ACTION_PRICE_UPDATE).apply {
            putExtra(EXTRA_PRICE_DATA, priceInfoToJson(priceInfo))
            putExtra("automation_complete", true)
            putExtra("source", "automation")
        }
        sendBroadcast(intent)
    }

    /**
     * AUTO-RETURN: Automatically return to GO-ON after capturing price
     * Uses performGlobalAction to press HOME then opens GO-ON
     */
    private fun autoReturnToGoOn() {
        Log.i(TAG, "🏠 AUTO-RETURN: Going back to GO-ON...")

        // Stop monitoring first
        stopActiveMonitoring()

        // Small delay to ensure price is saved
        handler.postDelayed({
            try {
                // Method 1: Open GO-ON directly via intent
                val goOnIntent = packageManager.getLaunchIntentForPackage("com.goon.app")
                if (goOnIntent != null) {
                    goOnIntent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                                       Intent.FLAG_ACTIVITY_CLEAR_TOP or
                                       Intent.FLAG_ACTIVITY_SINGLE_TOP
                    startActivity(goOnIntent)
                    Log.i(TAG, "🏠 ✓ Returned to GO-ON via intent")
                } else {
                    // Method 2: Press HOME button
                    performGlobalAction(GLOBAL_ACTION_HOME)
                    Log.i(TAG, "🏠 ✓ Pressed HOME button")
                }
            } catch (e: Exception) {
                Log.e(TAG, "🏠 ✗ Failed to return to GO-ON: ${e.message}")
                // Fallback: Press BACK multiple times
                performGlobalAction(GLOBAL_ACTION_BACK)
                handler.postDelayed({
                    performGlobalAction(GLOBAL_ACTION_BACK)
                }, 300)
            }
        }, 500) // 500ms delay
    }

    fun getAutomationState(): String = automationState.name

    fun isAutomationComplete(): Boolean =
        automationState == AutomationState.PRICE_CAPTURED ||
        automationState == AutomationState.FAILED

    fun resetAutomation() {
        automationState = AutomationState.IDLE
        automationStep = 0
        automationRetries = 0
        isActiveMonitoring = false
        monitoringPackage = null
    }

    /**
     * STOP ACTIVE MONITORING - Called when user returns to GO-ON
     */
    fun stopActiveMonitoring() {
        Log.i(TAG, "⏹ Stopping active monitoring")
        isActiveMonitoring = false
        monitoringPackage = null
        scanRunnable?.let { scanHandler?.removeCallbacks(it) }
        scanRunnable = null
    }

    /**
     * AGGRESSIVE SCAN - The core price extraction function
     * ALWAYS collects ALL prices from ALL strategies and returns the LOWEST
     */
    private fun performAggressiveScan(targetPackage: String): PriceInfo? {
        val rootNode = rootInActiveWindow ?: run {
            Log.w(TAG, "No active window available")
            return null
        }

        try {
            val currentPackage = rootNode.packageName?.toString() ?: return null

            // Check if we're in the target app
            if (currentPackage != targetPackage) {
                Log.d(TAG, "Current app ($currentPackage) != target ($targetPackage)")
                return null
            }

            Log.d(TAG, "🔍 Aggressive scanning $currentPackage...")

            // Collect ALL prices from ALL strategies
            val allPrices = mutableListOf<Double>()
            val priceTexts = mutableListOf<String>()

            // Strategy 1: Look for specific price elements by resource ID
            val pricesByResourceId = findAllPricesByResourceId(rootNode, currentPackage)
            allPrices.addAll(pricesByResourceId)
            Log.d(TAG, "📍 Strategy 1 (Resource ID): found ${pricesByResourceId.size} prices: $pricesByResourceId")

            // Strategy 2: Look for price by content description patterns
            val pricesByContentDesc = findAllPricesByContentDescription(rootNode)
            allPrices.addAll(pricesByContentDesc)
            Log.d(TAG, "📍 Strategy 2 (Content Desc): found ${pricesByContentDesc.size} prices: $pricesByContentDesc")

            // Strategy 3: Full text scan with all patterns (most comprehensive)
            val allText = getAllTextFromNode(rootNode)

            // Log ALL texts found (limited to first 30)
            Log.i(TAG, "📝 ====== ALL TEXTS FROM SCREEN (${allText.size} total) ======")
            allText.take(30).forEachIndexed { index, text ->
                Log.i(TAG, "📝 [$index] '$text'")
            }
            Log.i(TAG, "📝 ====== END SCREEN TEXT ======")

            // Check if screen has motorcycle options - we want to exclude them
            val allTextLower = allText.map { it.lowercase() }
            val hasMotorcycleOption = allTextLower.any {
                it.contains("دراجة نارية") || it.contains("موتوسيكل") ||
                it.contains("motorcycle") || it.contains("moto") ||
                it.contains("bike") || it.contains("scooter")
            }
            if (hasMotorcycleOption) {
                Log.i(TAG, "🏍️ Detected motorcycle option on screen - will filter lowest price")
            }

            for (text in allText) {
                // CRITICAL: Skip resource IDs - they contain UUIDs with numbers that look like prices
                // Example: "sinet.startup.inDriver:id/46a5faad-8e95-41a6-83a9-0a68ec2a5e38" contains "95"
                if (text.contains(":id/") || text.contains("_id/")) {
                    Log.d(TAG, "⏭️ Skipping resource ID: '$text'")
                    continue
                }

                val price = extractPrice(text)
                if (price != null && price in 10.0..5000.0) {
                    allPrices.add(price)
                    priceTexts.add(text)
                    Log.i(TAG, "💰 Strategy 3 (Text): Found price: $price in '$text'")
                }
            }

            // Remove duplicates
            var uniquePrices = allPrices.distinct().toMutableList()
            Log.i(TAG, "📊 ALL prices found (unique): $uniquePrices")

            // If motorcycle option exists, filter out the lowest price (motorcycle is usually cheapest)
            if (hasMotorcycleOption && uniquePrices.size > 1) {
                val motorcyclePrice = uniquePrices.minOrNull()
                if (motorcyclePrice != null) {
                    Log.i(TAG, "🏍️ Filtering out motorcycle price: $motorcyclePrice EGP")
                    uniquePrices = uniquePrices.filter { it != motorcyclePrice }.toMutableList()
                    Log.i(TAG, "📊 Prices after motorcycle filter: $uniquePrices")
                }
            }

            if (uniquePrices.isNotEmpty()) {
                // Select price based on user preference
                val selectedPrice = selectPriceByPreference(uniquePrices)

                Log.i(TAG, "✅ SELECTED PRICE: $selectedPrice EGP (preference: $rideSortPreference, from ${uniquePrices.size} candidates)")
                return savePriceInfo(currentPackage, selectedPrice, "combined_scan", uniquePrices, priceTexts)
            }

            Log.w(TAG, "No prices found in $currentPackage (scanned ${allText.size} elements)")
            return null

        } catch (e: Exception) {
            Log.e(TAG, "Error in aggressive scan: ${e.message}")
            return null
        } finally {
            try { rootNode.recycle() } catch (e: Exception) {}
        }
    }

    /**
     * Find ALL prices by resource ID - returns list of all prices found
     */
    private fun findAllPricesByResourceId(rootNode: AccessibilityNodeInfo, packageName: String): List<Double> {
        val priceResourceIds = when (packageName) {
            UBER_PACKAGE -> listOf(
                "com.ubercab:id/fare_estimate_text",
                "com.ubercab:id/price_text",
                "com.ubercab:id/trip_price",
                "com.ubercab:id/fare_text",
                "com.ubercab:id/estimate_fare",
                "com.ubercab:id/upfront_fare"
            )
            CAREEM_PACKAGE -> listOf(
                "com.careem.acma:id/price_text",
                "com.careem.acma:id/fare_amount",
                "com.careem.acma:id/ride_price",
                "com.careem.acma:id/total_price"
            )
            INDRIVER_PACKAGE -> listOf(
                "sinet.startup.inDriver:id/price",
                "sinet.startup.inDriver:id/offer_price",
                "sinet.startup.inDriver:id/ride_price"
            )
            DIDI_PACKAGE -> listOf(
                "com.didiglobal.passenger:id/price",
                "com.didiglobal.passenger:id/fare_text",
                "com.didiglobal.passenger:id/estimated_price"
            )
            BOLT_PACKAGE -> listOf(
                "ee.mtakso.client:id/price",
                "ee.mtakso.client:id/fare",
                "ee.mtakso.client:id/ride_price"
            )
            else -> emptyList()
        }

        val allPrices = mutableListOf<Double>()

        for (resourceId in priceResourceIds) {
            try {
                val nodes = rootNode.findAccessibilityNodeInfosByViewId(resourceId)
                for (node in nodes) {
                    val text = node.text?.toString() ?: node.contentDescription?.toString()
                    if (text != null) {
                        val price = extractPrice(text)
                        if (price != null && price > 0) {
                            allPrices.add(price)
                            Log.d(TAG, "📍 Resource ID '$resourceId': $price")
                        }
                    }
                    node.recycle()
                }
            } catch (e: Exception) {
                // Resource ID not found, continue
            }
        }

        return allPrices
    }

    /**
     * Find ALL prices by scanning content descriptions for price keywords
     * Returns list of all prices found
     */
    private fun findAllPricesByContentDescription(node: AccessibilityNodeInfo): List<Double> {
        val allPrices = mutableListOf<Double>()
        collectPricesFromContentDescription(node, allPrices)
        return allPrices
    }

    /**
     * Helper to collect all prices from content descriptions recursively
     */
    private fun collectPricesFromContentDescription(node: AccessibilityNodeInfo, prices: MutableList<Double>) {
        val desc = node.contentDescription?.toString()
        if (desc != null) {
            // Look for price-related content descriptions
            if (desc.contains("price", ignoreCase = true) ||
                desc.contains("fare", ignoreCase = true) ||
                desc.contains("سعر") ||
                desc.contains("أجرة") ||
                desc.contains("EGP", ignoreCase = true) ||
                desc.contains("ج.م")) {
                val price = extractPrice(desc)
                if (price != null && price > 0) {
                    prices.add(price)
                    Log.d(TAG, "📍 Found price by content desc: $price in '$desc'")
                }
            }
        }

        // Recursively check children
        for (i in 0 until node.childCount) {
            try {
                val child = node.getChild(i)
                if (child != null) {
                    collectPricesFromContentDescription(child, prices)
                    child.recycle()
                }
            } catch (e: Exception) {}
        }
    }

    /**
     * Select price based on user preference
     * - lowest_price: أقل سعر → select minimum
     * - best_service: أفضل خدمة → select premium/comfort tier (higher price = better service)
     * - fastest_arrival: أسرع وصول → select economy tier (more drivers available)
     */
    private fun selectPriceByPreference(prices: List<Double>): Double {
        if (prices.isEmpty()) return 0.0
        if (prices.size == 1) return prices[0]

        // Filter to reasonable ride prices (15-1000 EGP)
        val reasonable = prices.filter { it in 15.0..1000.0 }
        if (reasonable.isEmpty()) return prices.minOrNull() ?: 0.0

        val selectedPrice = when (rideSortPreference) {
            "lowest_price" -> {
                // أقل سعر - select the cheapest option
                reasonable.minOrNull() ?: reasonable[0]
            }
            "best_service" -> {
                // أفضل خدمة - select premium tier (higher price = better service)
                // Usually: Comfort > Standard > Economy
                reasonable.maxOrNull() ?: reasonable[0]
            }
            "fastest_arrival" -> {
                // أسرع وصول - select economy tier (more drivers = faster pickup)
                reasonable.minOrNull() ?: reasonable[0]
            }
            else -> {
                reasonable.minOrNull() ?: reasonable[0]
            }
        }

        Log.i(TAG, "📊 selectPriceByPreference: prices=$reasonable, preference=$rideSortPreference, selected=$selectedPrice")
        return selectedPrice
    }

    /**
     * Set the user's ride sorting preference
     * Called from Flutter via MethodChannel
     */
    fun setRideSortPreference(preference: String) {
        rideSortPreference = preference
        Log.i(TAG, "⚙️ Ride sort preference set to: $preference")
    }

    /**
     * Get the current ride sorting preference
     */
    fun getRideSortPreference(): String = rideSortPreference

    /**
     * Save and broadcast price info
     */
    private fun savePriceInfo(
        packageName: String,
        price: Double,
        source: String,
        allPrices: List<Double> = listOf(price),
        rawTexts: List<String> = emptyList()
    ): PriceInfo {
        val appName = getAppName(packageName)
        val priceInfo = PriceInfo(
            appName = appName,
            packageName = packageName,
            price = price,
            allPricesFound = allPrices,
            rawTexts = rawTexts
        )

        latestPrices[packageName] = priceInfo

        // Broadcast to app
        val intent = Intent(ACTION_PRICE_UPDATE).apply {
            putExtra(EXTRA_PRICE_DATA, priceInfoToJson(priceInfo))
            putExtra("source", source)
        }
        sendBroadcast(intent)

        Log.i(TAG, "✅ PRICE CAPTURED: $appName = $price EGP (via $source)")
        return priceInfo
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null || !isServiceActive) return

        val packageName = event.packageName?.toString() ?: return

        // Only process supported apps
        if (!isSupportedApp(packageName)) return

        // Debounce to avoid processing too frequently
        val currentTime = System.currentTimeMillis()
        if (currentTime - lastProcessedTime < processDebounce) return
        lastProcessedTime = currentTime

        // Log event for debugging
        Log.d(TAG, "Event from $packageName: ${event.eventType}")

        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
            AccessibilityEvent.TYPE_VIEW_SCROLLED -> {
                processAppContent(packageName)
            }
        }
    }

    private fun isSupportedApp(packageName: String): Boolean {
        return packageName in listOf(
            UBER_PACKAGE,
            CAREEM_PACKAGE,
            INDRIVER_PACKAGE,
            DIDI_PACKAGE,
            BOLT_PACKAGE
        )
    }

    private fun processAppContent(packageName: String) {
        val rootNode = rootInActiveWindow ?: return

        try {
            Log.d(TAG, "Processing content from: $packageName")

            when (packageName) {
                UBER_PACKAGE -> extractUberPrices(rootNode)
                CAREEM_PACKAGE -> extractCareemPrices(rootNode)
                INDRIVER_PACKAGE -> extractInDriverPrices(rootNode)
                DIDI_PACKAGE -> extractDiDiPrices(rootNode)
                BOLT_PACKAGE -> extractBoltPrices(rootNode)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error processing $packageName: ${e.message}")
        } finally {
            rootNode.recycle()
        }
    }

    /**
     * Actively scan the current app for prices (called from Flutter)
     */
    fun scanCurrentApp(): PriceInfo? {
        val rootNode = rootInActiveWindow ?: return null

        try {
            val packageName = rootNode.packageName?.toString() ?: return null
            Log.i(TAG, "Active scan requested for: $packageName")

            // Get all visible text
            val allText = getAllTextFromNode(rootNode)
            Log.d(TAG, "Found ${allText.size} text elements")

            // Log some text for debugging
            allText.take(20).forEach { text ->
                Log.d(TAG, "Text: $text")
            }

            // Find all prices
            val prices = mutableListOf<Double>()
            for (text in allText) {
                val price = extractPrice(text)
                if (price != null && price in 15.0..2000.0) {
                    prices.add(price)
                    Log.d(TAG, "Found price: $price in text: $text")
                }
            }

            if (prices.isNotEmpty()) {
                // Get the most likely price (usually the prominent one, or median)
                val bestPrice = findBestPrice(prices)
                val appName = getAppName(packageName)

                val priceInfo = PriceInfo(
                    appName = appName,
                    packageName = packageName,
                    price = bestPrice,
                    serviceType = detectServiceType(packageName, allText),
                    eta = extractETA(allText)
                )

                updatePrice(priceInfo)
                Log.i(TAG, "✓ Price captured from $appName: $bestPrice EGP")
                return priceInfo
            }

            Log.w(TAG, "No prices found in $packageName")
            return null

        } catch (e: Exception) {
            Log.e(TAG, "Error in active scan: ${e.message}")
            return null
        } finally {
            rootNode.recycle()
        }
    }

    /**
     * Find the best price from a list of detected prices
     * ALWAYS returns the LOWEST reasonable price - this is what users want!
     */
    private fun findBestPrice(prices: List<Double>): Double {
        if (prices.isEmpty()) return 0.0
        if (prices.size == 1) return prices[0]

        // Filter reasonable prices (15-1000 EGP for typical rides)
        val reasonable = prices.filter { it in 15.0..1000.0 }
        if (reasonable.isNotEmpty()) {
            // ALWAYS return the LOWEST price
            val lowestPrice = reasonable.minOrNull() ?: reasonable[0]
            Log.i(TAG, "📊 findBestPrice: prices=$reasonable, selecting LOWEST: $lowestPrice")
            return lowestPrice
        }

        return prices.minOrNull() ?: 0.0
    }

    private fun getAppName(packageName: String): String {
        return when (packageName) {
            UBER_PACKAGE -> "Uber"
            CAREEM_PACKAGE -> "Careem"
            INDRIVER_PACKAGE -> "InDriver"
            DIDI_PACKAGE -> "DiDi"
            BOLT_PACKAGE -> "Bolt"
            else -> "Unknown"
        }
    }

    private fun detectServiceType(packageName: String, texts: List<String>): String {
        val combined = texts.joinToString(" ").lowercase()
        return when (packageName) {
            UBER_PACKAGE -> when {
                combined.contains("uberx") || combined.contains("uber x") -> "UberX"
                combined.contains("comfort") -> "Comfort"
                combined.contains("black") -> "Black"
                combined.contains("xl") -> "UberXL"
                else -> "UberX"
            }
            CAREEM_PACKAGE -> when {
                combined.contains("go") -> "Go"
                combined.contains("business") -> "Business"
                combined.contains("plus") -> "Plus"
                else -> "Go"
            }
            BOLT_PACKAGE -> when {
                combined.contains("bolt") && combined.contains("xl") -> "Bolt XL"
                combined.contains("comfort") -> "Comfort"
                combined.contains("lite") -> "Lite"
                else -> "Bolt"
            }
            DIDI_PACKAGE -> "Express"
            INDRIVER_PACKAGE -> "Economy"
            else -> ""
        }
    }

    /**
     * Extract prices from Uber app - STRICT extraction with EGP pattern only
     */
    private fun extractUberPrices(rootNode: AccessibilityNodeInfo) {
        val allText = getAllTextFromNode(rootNode)
        val prices = mutableListOf<Double>()
        val vehiclePrices = mutableMapOf<String, Double>()

        // LOG ALL TEXT for debugging
        Log.i(TAG, "📱 ====== UBER SCREEN TEXT (${allText.size} elements) ======")
        for ((index, text) in allText.withIndex()) {
            if (text.isNotBlank()) {
                Log.i(TAG, "📱 [$index] '$text'")
            }
        }
        Log.i(TAG, "📱 ====== END UBER SCREEN TEXT ======")

        // STRICT Uber price pattern - ONLY match "EGP XX.XX" format
        val uberPricePattern = Pattern.compile("EGP\\s*(\\d+[.,]?\\d*)", Pattern.CASE_INSENSITIVE)

        // Extract ONLY prices with EGP prefix
        val pricesWithIndex = mutableListOf<Pair<Int, Double>>()
        for ((index, text) in allText.withIndex()) {
            val matcher = uberPricePattern.matcher(text)
            if (matcher.find()) {
                val priceStr = matcher.group(1)?.replace(",", ".") ?: continue
                val price = priceStr.toDoubleOrNull()
                if (price != null && price in 30.0..1000.0) {
                    prices.add(price)
                    pricesWithIndex.add(index to price)
                    Log.i(TAG, "💰 Found Uber price: $price EGP from text: '$text'")
                }
            }
        }

        // Vehicle type keywords
        val vehicleTypes = listOf(
            "UberX Saver" to listOf("uberx saver", "saver"),
            "Wait & Save" to listOf("wait & save", "wait and save"),
            "UberX" to listOf("uberx"),
            "Comfort" to listOf("comfort"),
            "Uber Moto" to listOf("moto"),
            "UberXL" to listOf("uberxl", "xl"),
            "Black" to listOf("black")
        )

        // Find vehicle types and their positions
        val vehicleTypePositions = mutableListOf<Triple<Int, String, List<String>>>()
        for ((index, text) in allText.withIndex()) {
            val textLower = text.lowercase()
            for ((typeName, keywords) in vehicleTypes) {
                if (keywords.any { textLower.contains(it) }) {
                    vehicleTypePositions.add(Triple(index, typeName, keywords))
                    break // Only match first type for this text
                }
            }
        }

        // Associate prices with vehicle types by proximity
        // Each vehicle type gets the price found closest after it (within 5 positions)
        for ((typeIndex, typeName, _) in vehicleTypePositions) {
            // Find the closest price after this vehicle type
            var closestPrice: Double? = null
            var minDistance = Int.MAX_VALUE

            for ((priceIndex, price) in pricesWithIndex) {
                val distance = priceIndex - typeIndex
                if (distance > 0 && distance < 10 && distance < minDistance) {
                    // Price is after vehicle type and within reasonable distance
                    minDistance = distance
                    closestPrice = price
                }
            }

            if (closestPrice != null && !vehiclePrices.containsKey(typeName)) {
                vehiclePrices[typeName] = closestPrice
                Log.d(TAG, "🚗 Vehicle type: $typeName -> ${closestPrice} EGP")
            }
        }

        // If we couldn't match any, try alternative: assign prices to types in order
        if (vehiclePrices.isEmpty() && vehicleTypePositions.isNotEmpty() && pricesWithIndex.isNotEmpty()) {
            val sortedPrices = pricesWithIndex.sortedBy { it.first }.map { it.second }.distinct()
            val sortedTypes = vehicleTypePositions.sortedBy { it.first }.map { it.second }.distinct()

            for ((index, typeName) in sortedTypes.withIndex()) {
                if (index < sortedPrices.size) {
                    vehiclePrices[typeName] = sortedPrices[index]
                    Log.d(TAG, "🚗 Vehicle type (ordered): $typeName -> ${sortedPrices[index]} EGP")
                }
            }
        }

        // Log all found vehicle prices
        if (vehiclePrices.isNotEmpty()) {
            Log.i(TAG, "🚗 Uber vehicle prices: $vehiclePrices")
        }

        if (prices.isNotEmpty()) {
            val uniquePrices = prices.distinct().sorted()

            // Best price for cars only (exclude Moto which is typically the lowest)
            // Moto prices are usually < 40 EGP, car prices are usually >= 50 EGP
            val carPrices = vehiclePrices.filterKeys {
                !it.lowercase().contains("moto")
            }.values.filter { it >= 40.0 }.toList()

            // Also filter out Moto-like prices from the general list
            // Moto is typically much cheaper than cars (< 40 EGP)
            val carOnlyPrices = uniquePrices.filter { it >= 40.0 }

            val bestPrice = when {
                carPrices.isNotEmpty() -> {
                    // Use identified car prices
                    carPrices.minOrNull() ?: findBestPrice(carOnlyPrices)
                }
                carOnlyPrices.isNotEmpty() -> {
                    // Use car-range prices (>= 40 EGP)
                    carOnlyPrices.minOrNull() ?: findBestPrice(prices)
                }
                else -> {
                    // Fallback to all prices
                    findBestPrice(prices)
                }
            }

            // Log all found prices
            Log.i(TAG, "🚗 Uber prices: all=$uniquePrices, cars=$carPrices, carRange=$carOnlyPrices, BEST=$bestPrice")

            val priceInfo = PriceInfo(
                appName = "Uber",
                packageName = UBER_PACKAGE,
                price = bestPrice,
                serviceType = detectServiceType(UBER_PACKAGE, allText),
                eta = extractETA(allText),
                allPricesFound = uniquePrices,
                vehiclePrices = vehiclePrices,
                rawTexts = allText.take(50) // Store first 50 texts for debugging
            )
            updatePrice(priceInfo)
            Log.d(TAG, "Uber best car price: $bestPrice EGP, all: $uniquePrices")
        }
    }

    /**
     * Extract prices from Careem app
     */
    private fun extractCareemPrices(rootNode: AccessibilityNodeInfo) {
        val allText = getAllTextFromNode(rootNode)
        val prices = mutableListOf<Double>()

        for (text in allText) {
            val price = extractPrice(text)
            if (price != null && price in 15.0..2000.0) {
                prices.add(price)
            }
        }

        if (prices.isNotEmpty()) {
            val bestPrice = findBestPrice(prices)
            val priceInfo = PriceInfo(
                appName = "Careem",
                packageName = CAREEM_PACKAGE,
                price = bestPrice,
                serviceType = detectServiceType(CAREEM_PACKAGE, allText),
                eta = extractETA(allText)
            )
            updatePrice(priceInfo)
            Log.d(TAG, "Careem price: $bestPrice EGP")
        }
    }

    /**
     * Extract prices from InDriver app
     * CRITICAL: During automation, prices are BLOCKED until automation is complete
     * This prevents the 95 EGP default price from being cached
     *
     * Priority extraction:
     * 1. "الأجر المقترح" (Suggested fare) - the main price to extract
     * 2. Other prices on screen as fallback
     */
    private fun extractInDriverPrices(rootNode: AccessibilityNodeInfo) {
        // CRITICAL: Block ALL price detection during active InDriver automation
        // until we confirm we're on the final price screen
        if (isActiveMonitoring && monitoringPackage == INDRIVER_PACKAGE && !inDriverAutomationComplete) {
            Log.d(TAG, "InDriver price detection BLOCKED - automation not complete")
            return
        }

        val allText = getAllTextFromNode(rootNode)
        val prices = mutableListOf<Double>()
        var suggestedFare: Double? = null

        // InDriver-specific price blacklist:
        // - 95.0 = Default price shown on home screen (NOT a real trip price!)
        // - < 25 = Motorcycle/درجة نارية prices (we want car prices only)
        val inDriverBlacklist = setOf(95.0)
        val minCarPrice = 25.0  // Prices below this are motorcycle

        // PRIORITY 1: Look for "الأجر المقترح" (Suggested fare) specifically
        // This is the main price shown on the "البحث عن عروض" screen
        for (text in allText) {
            if (text.contains("الأجر المقترح") || text.contains("السعر المقترح") ||
                text.contains("Suggested fare") || text.contains("Suggested price")) {
                // Try to extract price from this text
                val price = extractPrice(text)
                if (price != null && price > minCarPrice && price !in inDriverBlacklist) {
                    suggestedFare = price
                    Log.i(TAG, "InDriver: Found suggested fare (الأجر المقترح): $price EGP")
                    break
                }
            }
        }

        // PRIORITY 2: Extract all other prices as fallback
        for (text in allText) {
            val price = extractPrice(text)
            if (price != null && price in 15.0..2000.0) {
                // Filter out InDriver-specific invalid prices
                if (price in inDriverBlacklist) {
                    Log.d(TAG, "InDriver: Ignoring blacklisted price $price (home screen default)")
                    continue
                }
                if (price < minCarPrice) {
                    Log.d(TAG, "InDriver: Ignoring motorcycle price $price (< $minCarPrice)")
                    continue
                }
                prices.add(price)
            }
        }

        // Use suggested fare if found, otherwise use best from all prices
        val bestPrice = suggestedFare ?: if (prices.isNotEmpty()) findBestPrice(prices) else null

        if (bestPrice != null) {
            val priceSource = if (suggestedFare != null) "الأجر المقترح" else "extracted"
            Log.i(TAG, "InDriver prices: $prices -> best: $bestPrice EGP (source: $priceSource)")
            val priceInfo = PriceInfo(
                appName = "InDriver",
                packageName = INDRIVER_PACKAGE,
                price = bestPrice,
                serviceType = "Economy",
                eta = extractETA(allText)
            )
            updatePrice(priceInfo)
        } else {
            Log.d(TAG, "InDriver: No valid car prices found after filtering")
        }
    }

    /**
     * Extract prices from DiDi app
     */
    private fun extractDiDiPrices(rootNode: AccessibilityNodeInfo) {
        val allText = getAllTextFromNode(rootNode)
        val prices = mutableListOf<Double>()

        for (text in allText) {
            val price = extractPrice(text)
            if (price != null && price in 15.0..2000.0) {
                prices.add(price)
            }
        }

        if (prices.isNotEmpty()) {
            val bestPrice = findBestPrice(prices)
            val priceInfo = PriceInfo(
                appName = "DiDi",
                packageName = DIDI_PACKAGE,
                price = bestPrice,
                serviceType = "Express",
                eta = extractETA(allText)
            )
            updatePrice(priceInfo)
            Log.d(TAG, "DiDi price: $bestPrice EGP")
        }
    }

    /**
     * Extract prices from Bolt app
     */
    private fun extractBoltPrices(rootNode: AccessibilityNodeInfo) {
        val allText = getAllTextFromNode(rootNode)
        val prices = mutableListOf<Double>()

        for (text in allText) {
            val price = extractPrice(text)
            if (price != null && price in 15.0..2000.0) {
                prices.add(price)
            }
        }

        if (prices.isNotEmpty()) {
            val bestPrice = findBestPrice(prices)
            val priceInfo = PriceInfo(
                appName = "Bolt",
                packageName = BOLT_PACKAGE,
                price = bestPrice,
                serviceType = detectServiceType(BOLT_PACKAGE, allText),
                eta = extractETA(allText)
            )
            updatePrice(priceInfo)
            Log.d(TAG, "Bolt price: $bestPrice EGP")
        }
    }

    /**
     * Recursively get all text from accessibility node tree
     */
    private fun getAllTextFromNode(node: AccessibilityNodeInfo): List<String> {
        val texts = mutableListOf<String>()

        // Get text content
        node.text?.toString()?.trim()?.let {
            if (it.isNotEmpty()) texts.add(it)
        }

        // Get content description
        node.contentDescription?.toString()?.trim()?.let {
            if (it.isNotEmpty()) texts.add(it)
        }

        // Recursively get children
        for (i in 0 until node.childCount) {
            try {
                val child = node.getChild(i)
                if (child != null) {
                    texts.addAll(getAllTextFromNode(child))
                    child.recycle()
                }
            } catch (e: Exception) {
                // Skip problematic nodes
            }
        }

        return texts
    }

    /**
     * Extract price value from text using multiple regex patterns
     */
    private fun extractPrice(text: String): Double? {
        val cleanText = text.trim()
        if (cleanText.isEmpty()) return null

        // Try each pattern
        for (pattern in PRICE_PATTERNS) {
            val matcher = pattern.matcher(cleanText)
            if (matcher.find()) {
                val priceStr = matcher.group(1)?.replace(",", ".")?.replace(" ", "") ?: continue
                val price = priceStr.toDoubleOrNull()
                if (price != null && price > 0) {
                    return price
                }
            }
        }

        // Special case: check if it's just a number that could be a price
        val numericPattern = Pattern.compile("^(\\d+)$")
        val numMatcher = numericPattern.matcher(cleanText)
        if (numMatcher.find()) {
            val num = cleanText.toDoubleOrNull()
            // Only return if it's in reasonable ride price range
            if (num != null && num in 20.0..500.0) {
                return num
            }
        }

        return null
    }

    /**
     * Extract ETA (estimated time of arrival) in minutes
     */
    private fun extractETA(texts: List<String>): Int {
        val combined = texts.joinToString(" ")

        // Pattern for minutes in Arabic and English
        val patterns = listOf(
            Pattern.compile("(\\d+)\\s*د(?:قيقة|قائق)?", Pattern.CASE_INSENSITIVE),
            Pattern.compile("(\\d+)\\s*min(?:ute)?s?", Pattern.CASE_INSENSITIVE),
            Pattern.compile("(\\d+)\\s*دقيقة"),
            Pattern.compile("في\\s*(\\d+)\\s*د"),
            Pattern.compile("arrives?\\s*in\\s*(\\d+)", Pattern.CASE_INSENSITIVE)
        )

        for (pattern in patterns) {
            val matcher = pattern.matcher(combined)
            if (matcher.find()) {
                val eta = matcher.group(1)?.toIntOrNull()
                if (eta != null && eta in 1..60) {
                    return eta
                }
            }
        }
        return 0
    }

    /**
     * Update price and broadcast to app
     */
    private fun updatePrice(priceInfo: PriceInfo) {
        // Store latest price
        latestPrices[priceInfo.packageName] = priceInfo

        // Broadcast to Flutter
        val intent = Intent(ACTION_PRICE_UPDATE).apply {
            putExtra(EXTRA_PRICE_DATA, priceInfoToJson(priceInfo))
        }
        sendBroadcast(intent)

        // Update floating overlay if visible
        FloatingOverlayService.updateAppPrice(
            priceInfo.packageName,
            priceInfo.price,
            if (priceInfo.price > 0) "success" else "error",
            if (priceInfo.eta > 0) "${priceInfo.eta} دقيقة" else null
        )

        Log.i(TAG, "✓ Price updated: ${priceInfo.appName} = ${priceInfo.price} EGP")
    }

    private fun priceInfoToJson(priceInfo: PriceInfo): String {
        return JSONObject().apply {
            put("appName", priceInfo.appName)
            put("packageName", priceInfo.packageName)
            put("price", priceInfo.price)
            put("currency", priceInfo.currency)
            put("serviceType", priceInfo.serviceType)
            put("eta", priceInfo.eta)
            put("timestamp", priceInfo.timestamp)
            put("allPricesFound", org.json.JSONArray(priceInfo.allPricesFound))
            // Convert vehiclePrices map to JSONObject
            val vehiclePricesJson = JSONObject()
            priceInfo.vehiclePrices.forEach { (type, price) ->
                vehiclePricesJson.put(type, price)
            }
            put("vehiclePrices", vehiclePricesJson)
        }.toString()
    }

    /**
     * Get all prices found for a specific app (not just the best one)
     */
    fun getAllPricesForApp(packageName: String): List<Double> {
        return latestPrices[packageName]?.allPricesFound ?: emptyList()
    }

    /**
     * Get full price info for a specific app (including vehicle types)
     */
    fun getPriceInfoForApp(packageName: String): PriceInfo? {
        return latestPrices[packageName]
    }

    override fun onInterrupt() {
        Log.w(TAG, "GO-ON Price Reader Service Interrupted")
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
        isServiceActive = false
        Log.i(TAG, "GO-ON Price Reader Service Destroyed")
    }

    /**
     * Get all current prices as JSON for Flutter
     */
    fun getAllPricesJson(): String {
        val pricesArray = org.json.JSONArray()
        latestPrices.values.forEach { priceInfo ->
            pricesArray.put(JSONObject(priceInfoToJson(priceInfo)))
        }
        return pricesArray.toString()
    }

    /**
     * Get price for specific app
     */
    fun getPriceForApp(packageName: String): Double? {
        return latestPrices[packageName]?.price
    }

    /**
     * Clear stored prices
     */
    fun clearPrices() {
        latestPrices.clear()
        Log.d(TAG, "Prices cleared")
    }

    /**
     * Check if service is running and active
     */
    fun isActive(): Boolean = isServiceActive
}
