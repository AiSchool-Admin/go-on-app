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
    private val INDRIVER_MIN_WAIT_AFTER_DONE_MS = 5000L  // Wait at least 5 seconds after Done click

    // Track InDriver confirmation clicks (destination + pickup = 2 clicks needed)
    // InDriver flow: Search destination → Map confirm (تم) → Search pickup → Map confirm (تم) → Prices
    private var inDriverDoneClickCount = 0
    private val INDRIVER_MAX_DONE_CLICKS = 2  // Allow 2 clicks: one for destination, one for pickup

    // CRITICAL: Block ALL InDriver price detection until automation is complete
    // This prevents the 95 EGP default price from being cached during automation
    private var inDriverAutomationComplete = false

    enum class AutomationState {
        IDLE,
        WAITING_FOR_APP,
        FINDING_DESTINATION_FIELD,
        ENTERING_DESTINATION,
        WAITING_FOR_SUGGESTIONS,
        SELECTING_SUGGESTION,
        WAITING_FOR_PRICE,
        PRICE_CAPTURED,
        FAILED
    }

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
        inDriverDestinationEntered = false  // Reset InDriver destination flag
        inDriverDoneClickedTime = 0L  // Reset InDriver Done click time
        inDriverDoneClickCount = 0  // Reset Done click counter (2 clicks needed: destination + pickup)
        inDriverAutomationComplete = false  // CRITICAL: Block price detection until automation is complete

        // Clear any old cached prices for this app
        latestPrices.remove(packageName)

        // For Uber: deep link includes full coordinates, skip to WAITING_FOR_PRICE
        // For DiDi and others: deep links don't work reliably, go through full flow
        val hasFullDeepLink = packageName == UBER_PACKAGE
        if (hasFullDeepLink) {
            Log.i(TAG, "🤖 Deep link includes coordinates - skipping to WAITING_FOR_PRICE")
            automationState = AutomationState.WAITING_FOR_PRICE
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
                    Log.i(TAG, "🤖 ✓ App is active, transitioning to FINDING_DESTINATION_FIELD...")
                    automationState = AutomationState.FINDING_DESTINATION_FIELD
                }

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
                    // InDriver requires TWO "تم" clicks: destination + pickup
                    if (isInDriver) {
                        if (onIntermediateScreen) {
                            Log.d(TAG, "🤖 InDriver on intermediate screen - skipping price acceptance, will retry")
                            return
                        }

                        val timeSinceDoneClick = if (inDriverDoneClickedTime > 0) System.currentTimeMillis() - inDriverDoneClickedTime else Long.MAX_VALUE
                        val bothClicksMade = inDriverDoneClickCount >= INDRIVER_MAX_DONE_CLICKS
                        val doneWaitSatisfied = bothClicksMade && timeSinceDoneClick >= INDRIVER_MIN_WAIT_AFTER_DONE_MS

                        if (!doneWaitSatisfied) {
                            if (!bothClicksMade) {
                                Log.i(TAG, "🤖 ⏳ InDriver waiting for 'تم' clicks ($inDriverDoneClickCount/$INDRIVER_MAX_DONE_CLICKS done)")
                            } else {
                                Log.i(TAG, "🤖 ⏳ InDriver waiting after PICKUP click (${timeSinceDoneClick}ms < ${INDRIVER_MIN_WAIT_AFTER_DONE_MS}ms)")
                            }
                            return
                        }

                        // Both clicks made and wait time satisfied - NOW enable price detection
                        if (!inDriverAutomationComplete) {
                            Log.i(TAG, "🤖 ✓ InDriver BOTH clicks done + wait time satisfied - ENABLING price detection!")
                            inDriverAutomationComplete = true
                        }

                        // Now actively scan for price (cache was blocked, so do a fresh scan)
                        val priceInfo = performAggressiveScan(packageName)
                        if (priceInfo != null && priceInfo.price > 0) {
                            Log.i(TAG, "🤖 ✓✓✓ InDriver REAL PRICE FOUND: ${priceInfo.price} EGP")
                            automationState = AutomationState.PRICE_CAPTURED
                            notifyPriceCaptured(priceInfo)
                            autoReturnToGoOn()
                            return
                        } else {
                            Log.i(TAG, "🤖 InDriver scanning for price... (automation complete, waiting for detection)")
                            // Continue waiting for price to appear
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
     * Special handling for InDriver destination field
     * InDriver has a unique UI with destination field
     */
    private fun findAndClickInDriverDestination(rootNode: AccessibilityNodeInfo): Boolean {
        Log.i(TAG, "🚗 ========== INDRIVER AUTOMATION START ==========")
        Log.i(TAG, "🚗 Destination to enter: $destinationAddress")

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
     * For DiDi/InDriver: Uses COORDINATES instead of text address to avoid multiple results
     */
    private fun enterDestinationText(rootNode: AccessibilityNodeInfo, packageName: String): Boolean {
        Log.i(TAG, "🤖 enterDestinationText: Looking for focused input...")

        // Determine what to enter: COORDINATES for DiDi/InDriver, TEXT for others
        val textToEnter = when (packageName) {
            DIDI_PACKAGE, INDRIVER_PACKAGE -> {
                // Use coordinates format that apps recognize
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
            focusedNode.recycle()

            if (result) {
                Log.i(TAG, "🤖 ✓ Successfully entered destination: $textToEnter")
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
     */
    private fun collectInDriverSuggestions(node: AccessibilityNodeInfo, suggestions: MutableList<Pair<AccessibilityNodeInfo, String>>) {
        val className = node.className?.toString() ?: ""
        val text = node.text?.toString() ?: ""

        // InDriver shows suggestions in a different format
        // Look for clickable ViewGroups with location text
        if (node.isClickable && text.isNotBlank() && text.length > 5) {
            val isNotButton = !text.contains("تم") &&
                              !text.contains("Done") &&
                              !text.contains("بحث") &&
                              !text.contains("Search")
            if (isNotButton) {
                suggestions.add(Pair(AccessibilityNodeInfo.obtain(node), text))
                Log.d(TAG, "📍 InDriver suggestion: '$text'")
            }
        }

        // Check content description too
        val desc = node.contentDescription?.toString() ?: ""
        if (node.isClickable && desc.isNotBlank() && desc.length > 5 && text.isBlank()) {
            suggestions.add(Pair(AccessibilityNodeInfo.obtain(node), desc))
            Log.d(TAG, "📍 InDriver suggestion (desc): '$desc'")
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
        val currentTime = System.currentTimeMillis()
        if (currentTime - lastInDriverClickTime < 3000) {
            Log.i(TAG, "🗺️ InDriver cooldown active, waiting...")
            return true // Return true to prevent other actions
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
        // STEP 2: Check for MAP CONFIRMATION screen with "تم" button
        // This appears after selecting a destination on InDriver
        // ============================================================
        val hasTamButton = allText.any { it == "تم" }
        val hasDoneButton = allTextLower.any { it == "done" || it == "confirm" || it == "تأكيد" }
        val hasMapConfirmation = hasTamButton || hasDoneButton

        Log.i(TAG, "🗺️ hasTamButton=$hasTamButton, hasDoneButton=$hasDoneButton, hasMapConfirmation=$hasMapConfirmation")

        // Also check if we see Google maps elements (indicates map confirmation screen)
        val hasGoogleMap = allTextLower.any { it.contains("google") }

        if (hasMapConfirmation) {
            // InDriver has TWO map confirmation screens:
            // 1. Destination confirmation - first "تم" click
            // 2. Pickup confirmation - second "تم" click
            // We need to allow both clicks but prevent rapid double-clicking

            val timeSinceDoneClick = if (inDriverDoneClickedTime > 0) System.currentTimeMillis() - inDriverDoneClickedTime else Long.MAX_VALUE
            val minTimeBetweenClicks = 2000L  // Wait at least 2 seconds between clicks

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

            // If we clicked once for destination and now see pickup screen, allow second click
            if (inDriverDoneClickCount == 1 && !isPickupScreen && timeSinceDoneClick < 10000L) {
                // We clicked once but no pickup markers yet - might still be transitioning
                Log.i(TAG, "🗺️ Clicked once, waiting for pickup screen to appear (${timeSinceDoneClick}ms)")
                return true  // Return true to stay in handling mode and wait
            }

            val clickType = if (inDriverDoneClickCount == 0) "DESTINATION" else "PICKUP"
            Log.i(TAG, "🗺️ Detected InDriver MAP CONFIRMATION screen ($clickType) - clicking 'تم' button")

            // Try to click the "تم" button - USE GESTURE CLICK because ACTION_CLICK doesn't work reliably
            val doneTexts = listOf("تم", "Done", "Confirm", "تأكيد")
            for (doneText in doneTexts) {
                val nodes = rootNode.findAccessibilityNodeInfosByText(doneText)
                for (node in nodes) {
                    Log.i(TAG, "🗺️ Found '$doneText' button, attempting GESTURE click...")

                    // Get bounds for gesture click
                    val bounds = android.graphics.Rect()
                    node.getBoundsInScreen(bounds)
                    Log.i(TAG, "🗺️ Button bounds: ${bounds.left},${bounds.top},${bounds.right},${bounds.bottom} (${bounds.width()}x${bounds.height()})")

                    var clicked = false

                    // Strategy 1: Gesture click at center (most reliable for InDriver)
                    if (bounds.width() > 0 && bounds.height() > 0) {
                        val centerX = bounds.centerX().toFloat()
                        val centerY = bounds.centerY().toFloat()
                        Log.i(TAG, "🗺️ Strategy 1: Gesture click at ($centerX, $centerY)")

                        if (clickAtPosition(centerX, centerY)) {
                            Log.i(TAG, "🗺️ ✓✓✓ GESTURE CLICK SUCCESS at ($centerX, $centerY)")
                            clicked = true
                        }
                    }

                    // Strategy 2: If gesture failed, try gentleClick as fallback
                    if (!clicked) {
                        Log.i(TAG, "🗺️ Strategy 2: Trying gentleClick as fallback...")
                        clicked = gentleClick(node)
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

            // If gentleClick didn't work, show toast as fallback
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
     * Click at a specific screen position using accessibility gesture
     * This is more reliable than performAction for some UI elements
     */
    private fun clickAtPosition(x: Float, y: Float): Boolean {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
            val path = android.graphics.Path()
            path.moveTo(x, y)

            val gestureBuilder = android.accessibilityservice.GestureDescription.Builder()
            gestureBuilder.addStroke(
                android.accessibilityservice.GestureDescription.StrokeDescription(
                    path, 0, 100
                )
            )

            val result = dispatchGesture(gestureBuilder.build(), object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: android.accessibilityservice.GestureDescription?) {
                    Log.i(TAG, "🎯 Gesture completed at ($x, $y)")
                }
                override fun onCancelled(gestureDescription: android.accessibilityservice.GestureDescription?) {
                    Log.w(TAG, "🎯 Gesture cancelled at ($x, $y)")
                }
            }, null)

            Log.i(TAG, "🎯 dispatchGesture at ($x, $y) result: $result")
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
            val carPrices = vehiclePrices.filterKeys {
                !it.lowercase().contains("moto")
            }.values.toList()

            val bestPrice = if (carPrices.isNotEmpty()) {
                carPrices.minOrNull() ?: findBestPrice(prices)
            } else {
                findBestPrice(prices)
            }

            // Log all found prices
            Log.i(TAG, "🚗 Uber ALL prices found: $uniquePrices, best car price: $bestPrice")

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

        for (text in allText) {
            val price = extractPrice(text)
            if (price != null && price in 15.0..2000.0) {
                prices.add(price)
            }
        }

        if (prices.isNotEmpty()) {
            val bestPrice = findBestPrice(prices)
            val priceInfo = PriceInfo(
                appName = "InDriver",
                packageName = INDRIVER_PACKAGE,
                price = bestPrice,
                serviceType = "Economy",
                eta = extractETA(allText)
            )
            updatePrice(priceInfo)
            Log.d(TAG, "InDriver price: $bestPrice EGP")
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
