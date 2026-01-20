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

// Import centralized configuration
import com.goon.app.services.config.AppConstants
import com.goon.app.services.config.TimingConfig
import com.goon.app.services.config.PricePatterns
import com.goon.app.services.config.UITexts

// Import modular automation system (Phase 5)
import com.goon.app.services.automation.AutomationFactory
import com.goon.app.services.automation.AutomationOrchestrator
import com.goon.app.services.automation.AutomationState as ModularAutomationState
import com.goon.app.services.automation.PriceReaderAdapter
import com.goon.app.utils.AppLogger

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

        // ============================================================
        // PACKAGE NAMES
        // Canonical values are defined in AppConstants.Packages
        // These are kept as const val for backward compatibility
        // ============================================================
        const val UBER_PACKAGE = "com.ubercab"
        const val CAREEM_PACKAGE = "com.careem.acma"
        const val INDRIVER_PACKAGE = "sinet.startup.inDriver"
        const val DIDI_PACKAGE = "com.didiglobal.passenger"
        const val BOLT_PACKAGE = "ee.mtakso.client"

        // Broadcast action for price updates
        const val ACTION_PRICE_UPDATE = "com.goon.app.PRICE_UPDATE"
        const val EXTRA_PRICE_DATA = "price_data"

        // Price patterns - now using centralized PricePatterns config
        private val PRICE_PATTERNS = PricePatterns.PATTERNS

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
        // Options defined in AppConstants.Preferences: "lowest_price", "best_service", "fastest_arrival"
        var rideSortPreference: String = "lowest_price"
    }

    // ============================================================
    // NOTE: TimingConfig is now imported from config/TimingConfig.kt
    // All timing values are centralized there for easy maintenance.
    // ============================================================

    // Service ratings for "best_service" preference - now using centralized config
    private val serviceRatings = AppConstants.ServiceRatings.RATINGS

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

    // ============================================================
    // MODULAR AUTOMATION SYSTEM (Phase 5)
    // Set useModularAutomation = true to use new architecture
    // ============================================================
    private var useModularAutomation = false  // Feature flag for gradual migration
    private var modularAdapter: PriceReaderAdapter? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        isServiceActive = true

        // Calibrate timing for this device
        TimingConfig.calibrate()

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

        // Initialize modular automation adapter (Phase 5)
        modularAdapter = PriceReaderAdapter(this)
        AppLogger.i(TAG, "Modular automation system initialized (flag: $useModularAutomation)")
    }

    /**
     * تفعيل/تعطيل نظام الأتمتة الجديد
     * Enable/disable new modular automation system
     *
     * Call from Flutter:
     * ```dart
     * await methodChannel.invokeMethod('setModularAutomation', true);
     * ```
     */
    fun setModularAutomation(enabled: Boolean) {
        useModularAutomation = enabled
        AppLogger.i(TAG, "Modular automation ${if (enabled) "ENABLED" else "DISABLED"}")
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
                    scanHandler?.postDelayed(this, TimingConfig.scanInterval)
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
    private var automationStartTime = 0L  // Timestamp when automation started
    private var uberScreenReady = false  // Flag to indicate Uber ride selection screen is loaded

    // Track if InDriver destination was successfully entered (to avoid accepting default price)
    private var inDriverDestinationEntered = false

    // Track when InDriver "Done" button was clicked - need to wait for real price
    private var inDriverDoneClickedTime = 0L

    // Track InDriver confirmation clicks (3 clicks usually enough!)
    // InDriver flow after entering coordinates:
    //   1. Map confirm destination → "تم"
    //   2. Map confirm pickup → "تم"
    //   3. THEN "البحث عن عروض" prices screen appears
    // Note: If price screen is detected early, we skip remaining clicks
    private var inDriverDoneClickCount = 0

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

    // Track DiDi destination click retries (to prevent infinite loops)
    private var didiDestinationClickAttempts = 0

    // Track DiDi suggestion retry from WAITING_FOR_PRICE (to prevent infinite loops)
    private var didiSuggestionRetryCount = 0

    // Track DiDi pickup entry state (PICKUP FIRST flow - like InDriver and Careem)
    private var didiPickupEntered = false
    private var didiPickupPhaseComplete = false
    private var didiPickupFieldClicked = false

    /**
     * FULLY AUTOMATED PRICE FETCH
     * Opens app, enters destination, captures price, returns to GO-ON
     *
     * When useModularAutomation = true, uses new modular architecture (Phase 5)
     * When useModularAutomation = false, uses legacy monolithic code
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

        // ============================================================
        // MODULAR AUTOMATION PATH (Phase 5)
        // When enabled, uses new architecture with app-specific automation classes
        // ============================================================
        if (useModularAutomation && modularAdapter?.canHandlePackage(packageName) == true) {
            AppLogger.automation(TAG, "Using MODULAR automation for $packageName", state = "MODULAR_START")
            modularAdapter?.startAutomation(
                packageName,
                pickup, destination,
                pickupLatitude, pickupLongitude,
                destLatitude, destLongitude
            )
            return
        }

        // ============================================================
        // LEGACY AUTOMATION PATH (Original monolithic code)
        // ============================================================
        AppLogger.automation(TAG, "Using LEGACY automation for $packageName", state = "LEGACY_START")

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
        didiDestinationClickAttempts = 0  // Reset DiDi destination click attempts
        didiSuggestionRetryCount = 0  // Reset DiDi suggestion retry counter
        didiPickupEntered = false  // Reset DiDi pickup flag (PICKUP FIRST flow)
        didiPickupPhaseComplete = false  // Reset DiDi pickup phase complete flag
        didiPickupFieldClicked = false  // Reset DiDi pickup field click flag
        careemCarButtonClicked = false  // Reset Careem car button flag
        careemCarButtonClickAttempts = 0  // Reset Careem car button click attempts
        careemSearchBarClicked = false  // Reset Careem search bar click flag
        careemSearchBarClickAttempts = 0  // Reset search bar click attempts
        careemPickupFieldClicked = false  // Reset Careem pickup field click flag
        careemPickupEntered = false  // Reset Careem pickup flag
        careemPickupSuggestionClicked = false  // Reset pickup suggestion flag
        careemPickupClickAttempts = 0  // Reset pickup click attempts
        careemUseCurrentLocationAttempted = false  // Reset current location fallback flag
        careemNoValidPickupSuggestionCount = 0  // Reset no valid pickup count
        careemPickupButtonFallbackAttempted = false  // Reset pickUp button fallback flag
        careemPickupPhaseComplete = false  // Reset Careem pickup phase complete flag
        careemDestinationSuggestionClicked = false  // Reset destination suggestion flag
        careemDestinationClickAttempts = 0  // Reset destination click attempts
        careemDestinationMismatchRetries = 0  // Reset destination mismatch retries
        careemPositionBasedClickAttempt = 0  // Reset position-based click attempts
        careemGestureClickAttempt = 0  // Reset gesture click attempts
        careemLoaderFirstSeenTime = 0L  // Reset Careem loader time
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
                        scanHandler?.postDelayed(this, TimingConfig.scanInterval)
                    }
                }
            }
        }
        // Initial delay to let the app load
        scanHandler?.postDelayed(scanRunnable!!, TimingConfig.scanInterval)
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
            Log.i(TAG, "🤖 Retries: $automationRetries / ${TimingConfig.MAX_RETRIES}")
            Log.i(TAG, "🤖 Step: $automationStep")
            Log.i(TAG, "🤖 ========================================")

            when (automationState) {
                AutomationState.WAITING_FOR_APP -> {
                    // For InDriver, Careem, and DiDi: enter PICKUP first, then destination
                    // This ensures the custom pickup location from GO-ON is used, not the current GPS location
                    if (packageName == INDRIVER_PACKAGE) {
                        Log.i(TAG, "🤖 ✓ InDriver active, transitioning to FINDING_PICKUP_FIELD...")
                        automationState = AutomationState.FINDING_PICKUP_FIELD
                    } else if (packageName == CAREEM_PACKAGE) {
                        Log.i(TAG, "🤖 ✓ Careem active, transitioning to FINDING_PICKUP_FIELD (PICKUP FIRST flow)...")
                        automationState = AutomationState.FINDING_PICKUP_FIELD
                    } else if (packageName == DIDI_PACKAGE) {
                        Log.i(TAG, "🤖 ✓ DiDi active, transitioning to FINDING_PICKUP_FIELD (PICKUP FIRST flow)...")
                        automationState = AutomationState.FINDING_PICKUP_FIELD
                    } else {
                        Log.i(TAG, "🤖 ✓ App is active, transitioning to FINDING_DESTINATION_FIELD...")
                        automationState = AutomationState.FINDING_DESTINATION_FIELD
                    }
                }

                // ============================================================
                // InDriver, Careem, and DiDi: Enter PICKUP first
                // ============================================================
                AutomationState.FINDING_PICKUP_FIELD -> {
                    if (packageName == CAREEM_PACKAGE) {
                        Log.i(TAG, "🤖 [Careem] Searching for PICKUP field (PICKUP FIRST flow)...")

                        // For Careem: First need to click "سيارة" button on home screen
                        val found = findAndClickCareemPickupFieldFirst(rootNode)
                        if (found) {
                            Log.i(TAG, "🤖 ✓✓✓ [Careem] Found pickup field! Transitioning to ENTERING_PICKUP...")
                            automationState = AutomationState.ENTERING_PICKUP
                            automationRetries = 0
                        } else {
                            automationRetries++
                            Log.w(TAG, "🤖 ✗ [Careem] Pickup field not found (attempt $automationRetries/${TimingConfig.MAX_RETRIES})")
                            if (automationRetries > TimingConfig.MAX_RETRIES) {
                                Log.e(TAG, "🤖 ✗✗✗ [Careem] FAILED: Max retries exceeded for finding pickup field")
                                automationState = AutomationState.FAILED
                            }
                        }
                    } else if (packageName == DIDI_PACKAGE) {
                        // DiDi PICKUP FIRST flow
                        Log.i(TAG, "🚕 [DiDi] Searching for PICKUP field (PICKUP FIRST flow)...")

                        val found = findAndClickDiDiPickupField(rootNode)
                        if (found) {
                            Log.i(TAG, "🚕 ✓✓✓ [DiDi] Found pickup field! Transitioning to ENTERING_PICKUP...")
                            didiPickupFieldClicked = true
                            automationState = AutomationState.ENTERING_PICKUP
                            automationRetries = 0
                        } else {
                            automationRetries++
                            Log.w(TAG, "🚕 ✗ [DiDi] Pickup field not found (attempt $automationRetries/${TimingConfig.MAX_RETRIES})")
                            if (automationRetries > TimingConfig.MAX_RETRIES) {
                                Log.e(TAG, "🚕 ✗✗✗ [DiDi] FAILED: Max retries exceeded for finding pickup field")
                                automationState = AutomationState.FAILED
                            }
                        }
                    } else {
                        // InDriver flow
                        Log.i(TAG, "🤖 [InDriver] Searching for PICKUP field ('من')...")

                        val found = findAndClickInDriverPickupField(rootNode)
                        if (found) {
                            Log.i(TAG, "🤖 ✓✓✓ Found pickup field! Transitioning to ENTERING_PICKUP...")
                            automationState = AutomationState.ENTERING_PICKUP
                            automationRetries = 0
                        } else {
                            automationRetries++
                            Log.w(TAG, "🤖 ✗ Pickup field not found (attempt $automationRetries/${TimingConfig.MAX_RETRIES})")
                            if (automationRetries > TimingConfig.MAX_RETRIES) {
                                Log.e(TAG, "🤖 ✗✗✗ FAILED: Max retries exceeded for finding pickup field")
                                automationState = AutomationState.FAILED
                            }
                        }
                    }
                }

                AutomationState.ENTERING_PICKUP -> {
                    if (packageName == CAREEM_PACKAGE) {
                        Log.i(TAG, "🤖 [Careem] Entering pickup text: '$pickupAddress' (PICKUP FIRST flow)")

                        val entered = enterCareemPickupTextFirst(rootNode)
                        if (entered) {
                            Log.i(TAG, "🤖 ✓ [Careem] Entered pickup!")
                            careemPickupEntered = true
                            careemPickupFieldClicked = true  // Mark so selectFirstSuggestion uses pickup address
                            latestPrices.remove(packageName)  // Clear any cached price

                            // For Careem: Go to WAITING_FOR_SUGGESTIONS to wait for PICKUP suggestions
                            // After pickup suggestion is selected, we'll then enter destination
                            Log.i(TAG, "🤖 ✓ [Careem] Now going to WAITING_FOR_SUGGESTIONS for pickup...")
                            automationState = AutomationState.WAITING_FOR_SUGGESTIONS
                            automationRetries = 0
                            automationStep = 0
                        } else {
                            Log.w(TAG, "🤖 ✗ [Careem] Failed to enter pickup text")
                            automationRetries++
                            if (automationRetries > 3) {
                                Log.e(TAG, "🤖 ✗✗✗ [Careem] Pickup entry FAILED")
                                automationState = AutomationState.FAILED
                            }
                        }
                    } else if (packageName == DIDI_PACKAGE) {
                        // DiDi PICKUP FIRST flow
                        Log.i(TAG, "🚕 [DiDi] Entering pickup text: '$pickupAddress' (coords: $pickupLat, $pickupLng)")

                        val entered = enterDiDiPickupText(rootNode)
                        if (entered) {
                            Log.i(TAG, "🚕 ✓ [DiDi] Entered pickup!")
                            didiPickupEntered = true
                            latestPrices.remove(packageName)  // Clear any cached price

                            // For DiDi: Go to WAITING_FOR_SUGGESTIONS to wait for pickup suggestion selection
                            Log.i(TAG, "🚕 ✓ [DiDi] Now going to WAITING_FOR_SUGGESTIONS for pickup...")
                            automationState = AutomationState.WAITING_FOR_SUGGESTIONS
                            automationRetries = 0
                            automationStep = 0
                        } else {
                            Log.w(TAG, "🚕 ✗ [DiDi] Failed to enter pickup text")
                            automationRetries++
                            if (automationRetries > 3) {
                                Log.e(TAG, "🚕 ✗✗✗ [DiDi] Pickup entry FAILED")
                                automationState = AutomationState.FAILED
                            }
                        }
                    } else {
                        // InDriver flow
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
                    // Log additional info for Careem PICKUP FIRST flow
                    if (packageName == CAREEM_PACKAGE && careemPickupPhaseComplete) {
                        Log.i(TAG, "🤖 [Careem PICKUP FIRST] Searching for DESTINATION field (pickup already done)...")
                    } else if (packageName == DIDI_PACKAGE && didiPickupPhaseComplete) {
                        Log.i(TAG, "🚕 [DiDi PICKUP FIRST] Searching for DESTINATION field (pickup already done)...")
                    } else {
                        Log.i(TAG, "🤖 Searching for destination field...")
                    }

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

                    // For DiDi: Track overall destination click attempts
                    if (packageName == DIDI_PACKAGE) {
                        didiDestinationClickAttempts++
                        Log.i(TAG, "🚕 DiDi destination click attempt: $didiDestinationClickAttempts/${TimingConfig.DIDI_MAX_DESTINATION_ATTEMPTS}")
                        if (didiDestinationClickAttempts > TimingConfig.DIDI_MAX_DESTINATION_ATTEMPTS) {
                            Log.e(TAG, "🚕 ✗✗✗ DiDi FAILED: Max destination click attempts exceeded")
                            automationState = AutomationState.FAILED
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
                        Log.w(TAG, "🤖 ✗ Destination field not found (attempt $automationRetries/${TimingConfig.MAX_RETRIES})")
                        if (automationRetries > TimingConfig.MAX_RETRIES) {
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

                    // CRITICAL: For DiDi, check if we're still on home screen (Where to? button visible)
                    // This means the click didn't work and we need to retry
                    if (packageName == DIDI_PACKAGE) {
                        val allText = getAllTextFromNode(rootNode)
                        val isOnHomeScreen = allText.any { text ->
                            val lower = text.lowercase()
                            lower.contains("tap to enter your destination") ||
                            (lower.contains("where to") && lower.contains("button"))
                        }
                        if (isOnHomeScreen) {
                            Log.w(TAG, "🚕 DiDi still on home screen - 'Where to?' click didn't work!")
                            // IMMEDIATELY go back to try a different click strategy
                            // Don't waste time with retries - the click strategy needs to change
                            Log.i(TAG, "🚕 Going back to FINDING_DESTINATION_FIELD to try different strategy (attempt $didiDestinationClickAttempts)...")
                            automationState = AutomationState.FINDING_DESTINATION_FIELD
                            automationRetries = 0
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
                            } else if (packageName == DIDI_PACKAGE) {
                                // For DiDi: Go back to FINDING_DESTINATION_FIELD and retry clicking "Where to?"
                                Log.w(TAG, "🚕 DiDi: Failed to enter destination - retrying from FINDING_DESTINATION_FIELD")
                                automationState = AutomationState.FINDING_DESTINATION_FIELD
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
                    // For Careem PICKUP phase, wait a bit longer for suggestions to refresh
                    val waitSteps = if (packageName == CAREEM_PACKAGE && careemPickupEntered && !careemPickupPhaseComplete) {
                        5 // Wait ~6 seconds for Careem pickup suggestions
                    } else {
                        3 // Default ~3.6 seconds
                    }

                    if (automationStep > waitSteps) {
                        // For Careem PICKUP: Check if suggestions have actually refreshed
                        if (packageName == CAREEM_PACKAGE && careemPickupEntered && !careemPickupPhaseComplete) {
                            val allText = getAllTextFromNode(rootNode)
                            val pickupKeywords = pickupAddress.split(" ").filter { it.length > 2 }
                            val destKeywords = destinationAddress.split(" ").filter { it.length > 2 }

                            // Check if any suggestion contains pickup keywords (but not destination)
                            val hasSuggestionsForPickup = allText.any { text ->
                                val containsPickupKeyword = pickupKeywords.any { keyword -> text.contains(keyword, ignoreCase = true) }
                                val containsDestKeyword = destKeywords.any { keyword -> text.contains(keyword, ignoreCase = true) }
                                containsPickupKeyword && !containsDestKeyword
                            }

                            // Check if suggestions still show destination
                            val stillShowsDestination = allText.any { text ->
                                val isDistanceLine = text.matches(Regex(".*\\d+\\.?\\d*\\s*كم.*"))
                                val containsDestKeyword = destKeywords.any { keyword -> text.contains(keyword, ignoreCase = true) }
                                isDistanceLine || containsDestKeyword
                            }

                            if (!hasSuggestionsForPickup && stillShowsDestination) {
                                Log.w(TAG, "🚖 Careem pickup suggestions NOT refreshed yet (still showing destination)")
                                // Continue waiting a few more steps
                                if (automationStep <= waitSteps + 3) {
                                    Log.i(TAG, "🚖 Waiting more for Careem pickup suggestions to refresh...")
                                    return
                                }
                            }
                        }

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

                        // CRITICAL FIX: Even if handleDiDiIntermediateScreens returns false (cooldown),
                        // we should NOT call selectFirstSuggestion if we're still on "Select Address" screen.
                        // Wait for DiDi to transition after our click.
                        val allText = getAllTextFromNode(rootNode)
                        val stillOnSelectAddress = allText.any {
                            it.lowercase().contains("select address") ||
                            it.lowercase().contains("اختر العنوان") ||
                            it.lowercase().contains("pin your location")
                        }
                        if (stillOnSelectAddress) {
                            Log.i(TAG, "🤖 📋 DiDi still on 'Select Address' screen - waiting for transition...")
                            return
                        }

                        // Check if DiDi went BACK to home screen (indicates failed automation)
                        val backOnHomeScreen = allText.any {
                            val lower = it.lowercase()
                            lower.contains("tap to enter your destination") ||
                            (lower.contains("where to") && lower.contains("button"))
                        }
                        if (backOnHomeScreen) {
                            Log.w(TAG, "🚕 DiDi went back to home screen during SELECTING_SUGGESTION!")
                            Log.w(TAG, "🚕 Restarting automation from FINDING_DESTINATION_FIELD...")
                            automationState = AutomationState.FINDING_DESTINATION_FIELD
                            automationRetries = 0
                            automationStep = 0
                            didiDestinationClickAttempts = 0
                            didiSuggestionRetryCount = 0
                            return
                        }

                        // CRITICAL FIX: Check if we're already on the PRICE SCREEN
                        // If prices are visible, skip suggestion selection and go to WAITING_FOR_PRICE
                        val hasPriceIndicators = allText.any {
                            val lower = it.lowercase()
                            lower.contains("egp") ||
                            lower.contains("ج.م") ||
                            lower.contains("flex") ||
                            lower.contains("wasalny") ||
                            lower.contains("comfort") ||
                            lower.contains("dropoff") ||
                            lower.contains("choose your driver")
                        }
                        if (hasPriceIndicators) {
                            Log.i(TAG, "🤖 📋 DiDi already on price screen - skipping SELECTING_SUGGESTION")
                            automationState = AutomationState.WAITING_FOR_PRICE
                            automationRetries = 0
                            automationStep = 0
                            return
                        }
                    }

                    val selected = selectFirstSuggestion(rootNode, packageName)
                    if (selected) {
                        Log.i(TAG, "🤖 ✓ Selected suggestion")

                        // CRITICAL: For Careem PICKUP FIRST flow
                        // If we just selected PICKUP suggestion (pickup phase not complete), go to destination entry
                        if (packageName == CAREEM_PACKAGE && careemPickupFieldClicked && !careemPickupPhaseComplete) {
                            Log.i(TAG, "🚖 [Careem] PICKUP suggestion selected! Now moving to DESTINATION entry...")
                            careemPickupSuggestionClicked = true
                            careemPickupPhaseComplete = true  // Mark pickup phase as done
                            careemPickupFieldClicked = false  // Reset so next selectFirstSuggestion uses destination address
                            careemPositionBasedClickAttempt = 0  // Reset position-based clicks for destination phase
                            careemGestureClickAttempt = 0  // Reset gesture click attempts for destination phase

                            // Go to FINDING_DESTINATION_FIELD for destination entry
                            automationState = AutomationState.FINDING_DESTINATION_FIELD
                            automationRetries = 0
                            automationStep = 0
                            return
                        }

                        // CRITICAL: For DiDi PICKUP FIRST flow
                        // If we just selected PICKUP suggestion (pickup phase not complete), go to destination entry
                        if (packageName == DIDI_PACKAGE && didiPickupEntered && !didiPickupPhaseComplete) {
                            Log.i(TAG, "🚕 [DiDi] PICKUP suggestion selected! Now moving to DESTINATION entry...")
                            didiPickupPhaseComplete = true  // Mark pickup phase as done
                            didiPickupFieldClicked = false  // Reset for destination phase

                            // Go to FINDING_DESTINATION_FIELD for destination entry
                            automationState = AutomationState.FINDING_DESTINATION_FIELD
                            automationRetries = 0
                            automationStep = 0
                            return
                        }

                        // CRITICAL FIX: If this was a Careem PICKUP suggestion selection (legacy path),
                        // mark it so WAITING_FOR_PRICE doesn't try to click it again
                        // BUG FIX: Use !careemPickupPhaseComplete instead of careemPickupFieldClicked for reliable phase detection
                        if (packageName == CAREEM_PACKAGE && !careemPickupPhaseComplete) {
                            careemPickupSuggestionClicked = true
                            careemPickupFieldClicked = false  // Reset flag to prevent wrong address matching in destination phase
                            Log.i(TAG, "🚖 Marked pickup suggestion as clicked (via SELECTING_SUGGESTION state)")
                        }

                        // Mark destination suggestion as clicked when in destination phase
                        if (packageName == CAREEM_PACKAGE && careemPickupPhaseComplete) {
                            careemDestinationSuggestionClicked = true
                            Log.i(TAG, "🚖 Marked DESTINATION suggestion as clicked (pickup phase complete)")
                        }

                        Log.i(TAG, "🤖 ✓ Transitioning to WAITING_FOR_PRICE...")
                        automationState = AutomationState.WAITING_FOR_PRICE
                        automationRetries = 0
                        automationStep = 0
                    } else {
                        automationRetries++
                        Log.w(TAG, "🤖 ✗ No suggestion selected (attempt $automationRetries/${TimingConfig.MAX_RETRIES})")

                        // EARLY FALLBACK for Careem PICKUP: Try "Use my current location" button
                        // This is triggered EARLIER (at retry 3) to avoid wasting time on unrefreshed suggestions
                        if (packageName == CAREEM_PACKAGE && careemPickupEntered && !careemPickupPhaseComplete && automationRetries >= 3) {
                            if (!careemUseCurrentLocationAttempted) {
                                Log.i(TAG, "🚖 [Careem] Trying early fallback: Use current location button")
                                val currentLocationTexts = listOf(
                                    "استخدم موقعي الحالي",  // Use my current location
                                    "موقعي الحالي",         // My current location
                                    "Use my current location",
                                    "Current location"
                                )
                                for (locText in currentLocationTexts) {
                                    val locNode = findNodeWithText(rootNode, locText)
                                    if (locNode != null) {
                                        Log.i(TAG, "🚖 Found '$locText' button - clicking it!")
                                        if (careemGestureClick(locNode) || clickNodeOrParent(locNode)) {
                                            Log.i(TAG, "🚖 ✓ Clicked '$locText' - using current location as pickup!")
                                            careemUseCurrentLocationAttempted = true
                                            careemPickupSuggestionClicked = true
                                            locNode.recycle()
                                            Thread.sleep(800)
                                            // Reset retries and continue
                                            automationRetries = 0
                                            return
                                        }
                                        locNode.recycle()
                                    }
                                }
                                careemUseCurrentLocationAttempted = true
                            }
                        }

                        if (automationRetries > TimingConfig.MAX_RETRIES) {
                            // For Careem PICKUP FIRST flow: if pickup suggestion failed, try moving to destination anyway
                            if (packageName == CAREEM_PACKAGE && !careemPickupPhaseComplete) {
                                Log.w(TAG, "🚖 [Careem] Pickup suggestion selection failed - trying to proceed to destination entry anyway")
                                careemPickupPhaseComplete = true  // Mark pickup phase as done
                                careemPickupFieldClicked = false
                                careemPositionBasedClickAttempt = 0  // Reset position-based clicks for destination phase
                                careemGestureClickAttempt = 0  // Reset gesture click attempts for destination phase
                                automationState = AutomationState.FINDING_DESTINATION_FIELD
                                automationRetries = 0
                                automationStep = 0
                                return
                            }

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

                        // Check if still on "Select Address" (even after cooldown)
                        val allText = getAllTextFromNode(rootNode)
                        val stillOnSelectAddress = allText.any {
                            it.lowercase().contains("select address") ||
                            it.lowercase().contains("اختر العنوان") ||
                            it.lowercase().contains("حدد العنوان") ||  // "Select the address" in Arabic variant
                            it.lowercase().contains("pin your location")
                        }

                        // Check if "Where to?" field is visible (means we're on address entry screen)
                        val hasWhereToField = allText.any { it.lowercase() == "where to?" }

                        if (stillOnSelectAddress || hasWhereToField) {
                            Log.i(TAG, "🤖 📋 DiDi still on address selection screen")

                            // Check if there's a REAL suggestion visible (with distance indicator)
                            // Must have "km" or "كم" to be a real suggestion
                            val hasRealSuggestion = allText.any {
                                val lower = it.lowercase()
                                (lower.contains("km") && !lower.contains("bookmark")) ||
                                it.contains("كم")
                            }

                            // Only retry if we have a real suggestion AND haven't exceeded retry limit
                            if (hasRealSuggestion && didiSuggestionRetryCount < TimingConfig.DIDI_MAX_SUGGESTION_RETRIES) {
                                didiSuggestionRetryCount++
                                Log.w(TAG, "🚕 DiDi has suggestion visible - retry $didiSuggestionRetryCount/${TimingConfig.DIDI_MAX_SUGGESTION_RETRIES}")
                                automationState = AutomationState.SELECTING_SUGGESTION
                                automationRetries = 0
                                return
                            } else if (didiSuggestionRetryCount >= TimingConfig.DIDI_MAX_SUGGESTION_RETRIES) {
                                Log.w(TAG, "🚕 DiDi suggestion retry limit reached - waiting for timeout")
                            }

                            onIntermediateScreen = true
                        }

                        // Check if DiDi went BACK to home screen (indicates failed automation)
                        val backOnHomeScreen = allText.any {
                            val lower = it.lowercase()
                            lower.contains("tap to enter your destination") ||
                            (lower.contains("where to") && lower.contains("button"))
                        }
                        if (backOnHomeScreen && !stillOnSelectAddress) {
                            Log.w(TAG, "🚕 DiDi went back to home screen during WAITING_FOR_PRICE!")
                            Log.w(TAG, "🚕 Restarting automation from FINDING_DESTINATION_FIELD...")
                            automationState = AutomationState.FINDING_DESTINATION_FIELD
                            automationRetries = 0
                            automationStep = 0
                            didiDestinationClickAttempts = 0
                            didiSuggestionRetryCount = 0
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

                    // CRITICAL: For Careem, check if we're on pickup screen instead of price screen
                    if (packageName == CAREEM_PACKAGE) {
                        val allText = getAllTextFromNode(rootNode)
                        Log.i(TAG, "🚖 Careem WAITING_FOR_PRICE - checking screen type")
                        Log.i(TAG, "🚖 All text (first 20): ${allText.take(20)}")

                        // Detect if Careem is showing PICKUP search screen
                        // Indicators: "pickUp" element, "البيت" (Home), pickup suggestions
                        val isOnPickupScreen = allText.any {
                            val lower = it.lowercase()
                            lower == "pickup" || it == "pickUp" ||
                            it.contains("ادخل وجهتك") || // "Enter your destination" - still on entry screen
                            it.contains("استخدم موقعي الحالي") || // "Use my current location"
                            (it.contains("البيت") && allText.any { t -> t.contains("كم") }) // Home + distance indicators
                        }

                        // Check if we have distance indicators (كم) - means we're on suggestions screen, not price screen
                        val hasSuggestionDistances = allText.count { it.matches(Regex(".*\\d+\\.?\\d*\\s*كم.*")) } > 2

                        Log.i(TAG, "🚖 isOnPickupScreen=$isOnPickupScreen, hasSuggestionDistances=$hasSuggestionDistances")

                        // CRITICAL: Check if Careem went BACK to home screen (need to click "سيارة" again)
                        // This can happen if user presses back, or if navigation didn't work
                        // Home screen indicators: Get more with Careem, service buttons (سيارة, حوّل محلياً, طلب المال, etc.)
                        // Use normalized string matching to handle invisible Unicode characters
                        val hasCareemHomeIndicators = allText.any { text ->
                            normalizedContains(text, "Get more with Careem") ||
                            normalizedContains(text, "أدخل كود الخصم") ||
                            normalizedContains(text, "استمتع بعروض") ||
                            normalizedContains(text, "Super App") ||
                            normalizedContains(text, "Careem+") ||
                            normalizedContains(text, "كريم+") ||
                            normalizedContains(text, "طعام") ||  // Food service
                            normalizedContains(text, "Bike") ||  // Bike service
                            normalizedContains(text, "توصيل")    // Delivery service
                        }

                        // Check for service buttons with normalized matching
                        val hasCarButton = allText.any { text ->
                            normalizedContains(text, "سيارة") || normalizedContains(text, "سياره")
                        }
                        val hasOtherServiceButtons = allText.any { text ->
                            normalizedContains(text, "حوّل محلياً") ||
                            normalizedContains(text, "حول محليا") ||
                            normalizedContains(text, "طلب المال") ||
                            normalizedContains(text, "طعام") ||
                            normalizedContains(text, "Bike") ||
                            normalizedContains(text, "توصيل")
                        }
                        val hasCareemServiceButtons = hasCarButton && hasOtherServiceButtons

                        val isOnCareemHomeScreen = hasCareemHomeIndicators || hasCareemServiceButtons

                        Log.i(TAG, "🚖 isOnCareemHomeScreen=$isOnCareemHomeScreen (indicators=$hasCareemHomeIndicators, serviceButtons=$hasCareemServiceButtons)")

                        if (isOnCareemHomeScreen) {
                            Log.w(TAG, "🚖 Careem went back to HOME screen during WAITING_FOR_PRICE!")
                            Log.w(TAG, "🚖 Detected: homeIndicators=$hasCareemHomeIndicators, serviceButtons=$hasCareemServiceButtons")
                            Log.w(TAG, "🚖 Restarting automation from FINDING_DESTINATION_FIELD to click 'سيارة' button...")

                            // Reset ALL Careem-specific flags so the full flow will be attempted again
                            careemCarButtonClicked = false
                            careemCarButtonClickAttempts = 0
                            careemPickupFieldClicked = false
                            careemPickupEntered = false
                            careemPickupSuggestionClicked = false
                            careemPickupClickAttempts = 0
                            careemUseCurrentLocationAttempted = false
                            careemNoValidPickupSuggestionCount = 0
                            careemPickupButtonFallbackAttempted = false
                            careemPickupPhaseComplete = false  // Reset pickup phase for new attempt
                            careemDestinationSuggestionClicked = false  // Reset destination suggestion flag
                            careemDestinationClickAttempts = 0  // Reset destination click attempts
                            careemDestinationMismatchRetries = 0  // Reset destination mismatch retries
                            careemPositionBasedClickAttempt = 0  // Reset position-based click attempts
                            careemGestureClickAttempt = 0  // Reset gesture click attempts

                            automationState = AutomationState.FINDING_PICKUP_FIELD  // Start from pickup first (PICKUP FIRST flow)
                            automationRetries = 0
                            automationStep = 0
                            return
                        }

                        if (isOnPickupScreen || hasSuggestionDistances) {
                            // CRITICAL FIX: Check if we're in DESTINATION phase first
                            // If careemPickupPhaseComplete=true, we're past pickup and need to handle destination suggestions
                            if (careemPickupPhaseComplete && hasSuggestionDistances) {
                                Log.i(TAG, "🚖 Careem in DESTINATION phase - checking if suggestions are for destination")

                                // Check if suggestions match destination keywords
                                // NOTE: In Careem, distance indicators and suggestion names are in SEPARATE text nodes
                                // e.g., "1.7 كم" and "AM GROUP" are separate entries
                                // So we check if ANY text contains destination keywords (hasSuggestionDistances already confirmed distances exist)
                                val destKeywords = destinationAddress.split(" ").filter { it.length > 2 }
                                val suggestionsMatchDestination = destKeywords.isNotEmpty() && allText.any { text ->
                                    // Check if text contains destination keyword (case-insensitive)
                                    // Skip short texts (less than 4 chars) and UI elements
                                    val textLower = text.lowercase()
                                    text.length >= 4 &&
                                    !textLower.contains("pickup") && !textLower.contains("pickUp") &&
                                    !textLower.contains("back") && !textLower.contains("map icon") &&
                                    destKeywords.any { keyword -> textLower.contains(keyword.lowercase()) }
                                }
                                Log.i(TAG, "🚖 destKeywords=$destKeywords, suggestionsMatchDestination=$suggestionsMatchDestination")

                                if (suggestionsMatchDestination && careemDestinationClickAttempts < 5) {
                                    Log.i(TAG, "🚖 Destination suggestions visible - re-clicking destination (attempt ${careemDestinationClickAttempts + 1})")

                                    // Use selectFirstSuggestion which will use destination address when careemPickupPhaseComplete=true
                                    if (selectFirstSuggestion(rootNode, CAREEM_PACKAGE)) {
                                        careemDestinationSuggestionClicked = true
                                        careemDestinationClickAttempts++
                                        Log.i(TAG, "🚖 ✓ Re-clicked destination suggestion (total attempts: $careemDestinationClickAttempts)")
                                        return
                                    } else {
                                        careemDestinationClickAttempts++
                                        Log.w(TAG, "🚖 ✗ Failed to click destination suggestion (attempt $careemDestinationClickAttempts)")

                                        // After several failed attempts, try clicking the first suggestion directly
                                        if (careemDestinationClickAttempts >= 3) {
                                            Log.i(TAG, "🚖 Trying fallback: click first visible suggestion")
                                            if (clickFirstCareemSuggestionGesture(rootNode)) {
                                                Log.i(TAG, "🚖 ✓ Clicked first suggestion as fallback")
                                                careemDestinationSuggestionClicked = true
                                                return
                                            }
                                        }
                                    }
                                    return
                                } else if (careemDestinationClickAttempts >= 5) {
                                    Log.w(TAG, "🚖 Destination click attempts exhausted - proceeding to wait for price")
                                    // Continue waiting - don't try to handle as pickup
                                    onIntermediateScreen = true
                                } else if (!suggestionsMatchDestination) {
                                    // BUG FIX: Handle case when visible suggestions don't match destination keywords
                                    // This can happen if Careem is showing stale/cached suggestions
                                    careemDestinationMismatchRetries++
                                    Log.w(TAG, "🚖 Destination suggestions MISMATCH (retry $careemDestinationMismatchRetries/3)")

                                    when {
                                        careemDestinationMismatchRetries == 1 -> {
                                            // RETRY STRATEGY 1: Re-enter destination text
                                            // Go back to FINDING_DESTINATION_FIELD to re-enter the text
                                            Log.i(TAG, "🚖 Mismatch retry 1: Re-entering destination text...")
                                            automationState = AutomationState.FINDING_DESTINATION_FIELD
                                            automationRetries = 0
                                            automationStep = 0
                                            return
                                        }
                                        careemDestinationMismatchRetries == 2 -> {
                                            // RETRY STRATEGY 2: Try clicking first visible suggestion as fallback
                                            Log.i(TAG, "🚖 Mismatch retry 2: Trying to click first suggestion despite mismatch")
                                            if (clickFirstCareemSuggestionGesture(rootNode)) {
                                                Log.i(TAG, "🚖 ✓ Clicked first suggestion (mismatch fallback)")
                                                careemDestinationSuggestionClicked = true
                                                return
                                            }
                                            // If click failed, wait and try again
                                        }
                                        else -> {
                                            // RETRY STRATEGY 3: Give up and wait for price screen
                                            Log.w(TAG, "🚖 Destination mismatch retries exhausted - waiting for price screen")
                                            onIntermediateScreen = true
                                        }
                                    }
                                }
                                // In destination phase, skip the pickup handling below
                            }

                            // Skip pickup handling if we're in destination phase
                            if (careemPickupPhaseComplete) {
                                Log.i(TAG, "🚖 In destination phase - skipping pickup handling, waiting for price screen")
                                onIntermediateScreen = true
                            } else {
                            Log.i(TAG, "🚖 Careem is on PICKUP screen (not price screen) - need to enter pickup address")
                            Log.i(TAG, "🚖 State: pickupFieldClicked=$careemPickupFieldClicked, pickupEntered=$careemPickupEntered")

                            // Check if pickup entry is already done
                            if (!careemPickupEntered) {
                                Log.i(TAG, "🚖 Entering pickup address: $pickupAddress")

                                // Try to enter the pickup address
                                // enterCareemPickupAddress returns: 0=failed, 1=clicked field, 2=entered text
                                val result = enterCareemPickupAddress(rootNode)
                                when (result) {
                                    1 -> {
                                        // Just clicked the pickup field, wait for edit mode
                                        Log.i(TAG, "🚖 ⏳ Clicked pickup field, waiting for edit mode...")
                                        return
                                    }
                                    2 -> {
                                        // Text entered successfully
                                        careemPickupEntered = true
                                        Log.i(TAG, "🚖 ✓ Pickup address entered, waiting for suggestions...")
                                        // Go back to WAITING_FOR_SUGGESTIONS to select the pickup suggestion
                                        automationState = AutomationState.WAITING_FOR_SUGGESTIONS
                                        automationRetries = 0
                                        automationStep = 0
                                        return
                                    }
                                    else -> {
                                        Log.w(TAG, "🚖 Failed to enter pickup - trying to select first suggestion")
                                        // Try clicking first suggestion directly
                                        if (selectCareemPickupSuggestion(rootNode)) {
                                            careemPickupEntered = true
                                            careemPickupFieldClicked = false  // BUG FIX: Reset flag in legacy path
                                            Log.i(TAG, "🚖 ✓ Selected pickup suggestion directly")
                                            return
                                        }
                                    }
                                }
                            } else {
                                // Pickup was entered but we're still on pickup-related screen
                                Log.i(TAG, "🚖 Pickup entered - checking state (suggClicked=$careemPickupSuggestionClicked, attempts=$careemPickupClickAttempts)")

                                // CRITICAL CHECK: Are BOTH pickup and destination already filled?
                                // If pickup address appears in header area (not suggestions), we need to proceed
                                val pickupKeywords = pickupAddress.split(" ").filter { it.length > 2 }
                                val destKeywords = destinationAddress.split(" ").filter { it.length > 2 }

                                // CRITICAL FIX: Exclude text that exactly matches the search term (still in EditText)
                                // A properly filled address would be formatted with additional details (street, city, etc.)
                                // Not just the raw search term like "GOODWAY-TECH"
                                val pickupFilledInHeader = allText.any { text ->
                                    pickupKeywords.any { text.contains(it) } &&
                                    !text.matches(Regex(".*\\d+\\.?\\d*\\s*كم.*")) && // Not a suggestion with distance
                                    text.lowercase() != pickupAddress.lowercase() && // Not the exact search term (still in EditText)
                                    text.length > pickupAddress.length + 5 // Should be longer (has additional address details)
                                }
                                val destFilledInHeader = allText.any { text ->
                                    destKeywords.any { text.contains(it) } &&
                                    !text.matches(Regex(".*\\d+\\.?\\d*\\s*كم.*")) &&
                                    text.lowercase() != destinationAddress.lowercase() &&
                                    text.length > destinationAddress.length + 5
                                }

                                Log.i(TAG, "🚖 Both filled? pickup=$pickupFilledInHeader, dest=$destFilledInHeader")

                                // PRIORITY 1: If both addresses are filled, look for proceed button
                                if (pickupFilledInHeader && destFilledInHeader) {
                                    Log.i(TAG, "🚖 Both addresses filled - looking for proceed/search button")

                                    // Try clicking Map icon or search button to proceed
                                    val proceedTexts = listOf(
                                        "Map icon", "map", "بحث", "Search", "Done", "تم",
                                        "بحث عن أفضل سعر", "Find best price", "التالي", "Next",
                                        "تأكيد", "Confirm", "احجز", "Book"
                                    )

                                    for (proceedText in proceedTexts) {
                                        val proceedNode = findNodeWithText(rootNode, proceedText)
                                        if (proceedNode != null) {
                                            Log.i(TAG, "🚖 Found proceed button: '$proceedText'")
                                            if (careemGestureClick(proceedNode)) {
                                                Log.i(TAG, "🚖 ✓ Clicked '$proceedText' to proceed")
                                                proceedNode.recycle()
                                                Thread.sleep(1000)
                                                return
                                            }
                                            proceedNode.recycle()
                                        }
                                    }

                                    // Also try the confirm button function
                                    if (clickCareemConfirmButton(rootNode)) {
                                        Log.i(TAG, "🚖 ✓ Clicked confirm button to proceed")
                                        return
                                    }
                                }

                                // PRIORITY 2: Check if we're on map confirmation screen (has تأكيد الانطلاق)
                                val confirmTexts = listOf("تأكيد الانطلاق", "تأكيد موقع الانطلاق", "Confirm pickup")
                                val hasConfirmButton = allText.any { text ->
                                    confirmTexts.any { confirm -> text.contains(confirm) }
                                }

                                if (hasConfirmButton) {
                                    Log.i(TAG, "🚖 Confirm button visible - clicking it!")
                                    if (clickCareemConfirmButton(rootNode)) {
                                        Log.i(TAG, "🚖 ✓ Clicked confirm button - should proceed to price")
                                        careemPickupSuggestionClicked = false
                                        careemPickupClickAttempts = 0
                                        return
                                    }
                                }

                                // PRIORITY 3: If we already clicked suggestion multiple times, try different approach
                                if (careemPickupSuggestionClicked && careemPickupClickAttempts >= 3) {
                                    Log.i(TAG, "🚖 Already clicked suggestion $careemPickupClickAttempts times - trying confirm button anyway")
                                    if (clickCareemConfirmButton(rootNode)) {
                                        Log.i(TAG, "🚖 ✓ Clicked confirm button (after multiple attempts)")
                                        return
                                    }
                                    Log.i(TAG, "🚖 Resetting attempts to try again...")
                                    careemPickupClickAttempts = 0
                                }

                                // PRIORITY 4: Try selecting suggestion if on suggestion screen
                                // Note: pickupFilledInHeader can be falsely true from suggestion addresses,
                                // so also try if we haven't clicked suggestion yet AND dest is not filled
                                val needToClickSuggestion = !pickupFilledInHeader || (!careemPickupSuggestionClicked && !destFilledInHeader)
                                if (hasSuggestionDistances && needToClickSuggestion && careemPickupClickAttempts < 5) {
                                    Log.i(TAG, "🚖 On suggestion screen - trying to select suggestion (attempt ${careemPickupClickAttempts + 1}, needClick=$needToClickSuggestion)")
                                    if (selectCareemPickupSuggestion(rootNode)) {
                                        careemPickupSuggestionClicked = true
                                        careemPickupFieldClicked = false  // BUG FIX: Reset flag in legacy path
                                        careemPickupClickAttempts++
                                        Log.i(TAG, "🚖 ✓ Clicked pickup suggestion (total attempts: $careemPickupClickAttempts)")
                                        return
                                    } else {
                                        // IMPORTANT: Increment attempts even when selection fails
                                        // This prevents infinite loops when no valid pickup suggestions exist
                                        careemPickupClickAttempts++
                                        Log.w(TAG, "🚖 ✗ No valid pickup suggestion found (attempt $careemPickupClickAttempts)")

                                        // FALLBACK: Try alternative methods when suggestions don't match
                                        // This handles cases where Careem shows cached destination suggestions
                                        // instead of actual pickup suggestions
                                        careemNoValidPickupSuggestionCount++

                                        // RETRY STRATEGY: On first mismatch, try re-entering pickup text
                                        if (careemNoValidPickupSuggestionCount == 1 && careemPickupClickAttempts == 1) {
                                            Log.i(TAG, "🚖 Pickup mismatch retry 1: Re-entering pickup text...")
                                            careemPickupEntered = false  // Reset to allow re-entering
                                            careemPickupFieldClicked = false
                                            // Stay in current state to retry pickup entry
                                            return
                                        }

                                        if (careemPickupClickAttempts >= 2) {
                                            Log.i(TAG, "🚖 Trying fallback: no pickup suggestions found (consecutive failures: $careemNoValidPickupSuggestionCount)")

                                            // BEST FALLBACK: Click "استخدم موقعي الحالي" (Use my current location)
                                            // This bypasses the need for a matching suggestion
                                            if (!careemUseCurrentLocationAttempted) {
                                                Log.i(TAG, "🚖 Trying to use current location as pickup...")
                                                val currentLocationTexts = listOf(
                                                    "استخدم موقعي الحالي",  // Use my current location
                                                    "موقعي الحالي",         // My current location
                                                    "Use my current location",
                                                    "Current location",
                                                    "Use current location"
                                                )
                                                for (locText in currentLocationTexts) {
                                                    val locNode = findNodeWithText(rootNode, locText)
                                                    if (locNode != null) {
                                                        Log.i(TAG, "🚖 Found '$locText' button!")
                                                        if (careemGestureClick(locNode)) {
                                                            Log.i(TAG, "🚖 ✓ Clicked '$locText' - using current location as pickup!")
                                                            careemUseCurrentLocationAttempted = true
                                                            careemPickupSuggestionClicked = true
                                                            careemPickupFieldClicked = false  // BUG FIX: Reset flag in legacy path
                                                            locNode.recycle()
                                                            Thread.sleep(1000)
                                                            return
                                                        }
                                                        if (clickNodeOrParent(locNode)) {
                                                            Log.i(TAG, "🚖 ✓ Clicked '$locText' via parent - using current location!")
                                                            careemUseCurrentLocationAttempted = true
                                                            careemPickupSuggestionClicked = true
                                                            careemPickupFieldClicked = false  // BUG FIX: Reset flag in legacy path
                                                            locNode.recycle()
                                                            Thread.sleep(1000)
                                                            return
                                                        }
                                                        locNode.recycle()
                                                    }
                                                }
                                                careemUseCurrentLocationAttempted = true
                                                Log.i(TAG, "🚖 Current location button not found, trying other fallbacks...")
                                            }

                                            // Try clicking "pickUp" button ONCE (set location on map)
                                            // Only try this fallback once - if it didn't help, move to other fallbacks
                                            if (!careemPickupButtonFallbackAttempted) {
                                                val pickupButtonTexts = listOf("pickUp", "Map icon", "على الخريطة", "Set on map")
                                                for (buttonText in pickupButtonTexts) {
                                                    val buttonNode = findNodeWithText(rootNode, buttonText)
                                                    if (buttonNode != null) {
                                                        Log.i(TAG, "🚖 Found fallback button: '$buttonText'")
                                                        if (careemGestureClick(buttonNode)) {
                                                            Log.i(TAG, "🚖 ✓ Clicked '$buttonText' fallback button")
                                                            careemPickupButtonFallbackAttempted = true
                                                            buttonNode.recycle()
                                                            Thread.sleep(800)
                                                            return
                                                        }
                                                        buttonNode.recycle()
                                                    }
                                                }
                                                careemPickupButtonFallbackAttempted = true
                                                Log.i(TAG, "🚖 pickUp button fallback attempted, will try other fallbacks next")
                                            }

                                            // FALLBACK 2: Try clicking on map to use current GPS location
                                            // This works when Careem's search returns no results for the pickup address
                                            if (careemPickupClickAttempts >= 3 && careemNoValidPickupSuggestionCount >= 2) {
                                                Log.i(TAG, "🚖 All suggestions are destination-related - trying map click to use current location")
                                                if (clickCareemMapForPickup(rootNode)) {
                                                    Log.i(TAG, "🚖 ✓ Clicked on map - should use current GPS location as pickup")
                                                    careemPickupSuggestionClicked = true
                                                    careemPickupFieldClicked = false  // BUG FIX: Reset flag in legacy path
                                                    Thread.sleep(1000)
                                                    // After clicking map, look for confirm button
                                                    if (clickCareemConfirmButton(rootNode)) {
                                                        Log.i(TAG, "🚖 ✓ Clicked confirm after map selection")
                                                    }
                                                    return
                                                }
                                            }

                                            // FALLBACK 3: If map click failed, try any suggestion as absolute last resort
                                            if (careemPickupClickAttempts >= 4) {
                                                Log.i(TAG, "🚖 Trying absolute last resort: click first suggestion regardless of keywords...")
                                                if (clickFirstCareemSuggestionGesture(rootNode)) {
                                                    Log.i(TAG, "🚖 ✓ Clicked first suggestion as last resort")
                                                    careemPickupSuggestionClicked = true
                                                    careemPickupFieldClicked = false  // BUG FIX: Reset flag in legacy path
                                                    return
                                                }
                                            }
                                        }

                                        return
                                    }
                                }
                            }
                            } // End of else block for !careemPickupPhaseComplete

                            onIntermediateScreen = true
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
                        val bothClicksMade = inDriverDoneClickCount >= TimingConfig.INDRIVER_MAX_DONE_CLICKS
                        val doneWaitSatisfied = bothClicksMade && timeSinceDoneClick >= TimingConfig.INDRIVER_MIN_WAIT_AFTER_DONE_MS

                        if (!doneWaitSatisfied) {
                            if (!bothClicksMade) {
                                Log.i(TAG, "🤖 ⏳ InDriver clicks: $inDriverDoneClickCount/${TimingConfig.INDRIVER_MAX_DONE_CLICKS}")
                            } else {
                                Log.i(TAG, "🤖 ⏳ InDriver wait: ${timeSinceDoneClick}ms/${TimingConfig.INDRIVER_MIN_WAIT_AFTER_DONE_MS}ms")
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
                    if (isUber && elapsedTime < TimingConfig.UBER_MIN_WAIT_MS) {
                        Log.i(TAG, "🤖 ⏳ Uber: Waiting (${elapsedTime}ms < ${TimingConfig.UBER_MIN_WAIT_MS}ms)")
                        return
                    }

                    // After minimum wait, check cache (for non-InDriver apps)
                    if (cachedPrice != null && cachedPrice.price > 0 && cachedPrice.allPricesFound.size >= 2) {
                        Log.i(TAG, "🤖 ✓✓✓ PRICE FOUND: ${cachedPrice.price} EGP (${cachedPrice.allPricesFound.size} prices: ${cachedPrice.allPricesFound})")
                        automationState = AutomationState.PRICE_CAPTURED
                        autoReturnToGoOn()
                        return
                    }

                    // CRITICAL: Skip price scan if on intermediate screen (e.g., suggestion list)
                    // This prevents extracting fake "prices" from address strings like "33 - 33 Bank Masr..."
                    if (onIntermediateScreen) {
                        Log.i(TAG, "🤖 On intermediate screen - skipping price scan, waiting for actual price screen...")
                        automationStep++
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
                    // DiDi and Careem need more time, others use default timeout
                    automationStep++

                    // Determine base timeout
                    var timeoutMs = when (packageName) {
                        DIDI_PACKAGE -> TimingConfig.DIDI_TIMEOUT_MS
                        CAREEM_PACKAGE -> TimingConfig.CAREEM_TIMEOUT_MS
                        else -> TimingConfig.DEFAULT_TIMEOUT_MS
                    }

                    // For Careem: Check if loader is visible and extend timeout
                    if (packageName == CAREEM_PACKAGE) {
                        val allText = getAllTextFromNode(rootNode)
                        val hasLoader = allText.any { it.lowercase() == "loader" || it.lowercase().contains("loading") }

                        if (hasLoader) {
                            // Track when we first see the loader
                            if (careemLoaderFirstSeenTime == 0L) {
                                careemLoaderFirstSeenTime = System.currentTimeMillis()
                                Log.i(TAG, "🚖 Loader detected for the first time - extending timeout")
                            }

                            val loaderElapsed = System.currentTimeMillis() - careemLoaderFirstSeenTime
                            // Extend timeout if loader is still visible within reasonable time
                            if (loaderElapsed < TimingConfig.CAREEM_LOADER_EXTENSION_MS) {
                                timeoutMs = TimingConfig.CAREEM_TIMEOUT_MS + TimingConfig.CAREEM_LOADER_EXTENSION_MS
                                Log.i(TAG, "🚖 Loader visible (${loaderElapsed}ms) - timeout extended to ${timeoutMs}ms")
                            } else {
                                Log.w(TAG, "🚖 Loader has been visible too long (${loaderElapsed}ms) - proceeding with normal timeout")
                            }
                        }
                    }

                    if (elapsedTime > timeoutMs) { // App-specific timeout
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

        // Special handling for DiDi - needs more aggressive clicking
        if (packageName == DIDI_PACKAGE) {
            return findAndClickDiDiDestinationField(rootNode)
        }

        // Special handling for Careem
        if (packageName == CAREEM_PACKAGE) {
            return findAndClickCareemDestinationField(rootNode)
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
     * Find and click DiDi's PICKUP field for PICKUP FIRST flow
     * DiDi needs to first click "Where to?" to open the search screen,
     * then click the pickup field (usually shows current location) to edit it
     */
    private fun findAndClickDiDiPickupField(rootNode: AccessibilityNodeInfo): Boolean {
        Log.i(TAG, "🚕 ========== DIDI PICKUP FIELD SEARCH (PICKUP FIRST) ==========")
        Log.i(TAG, "🚕 Pickup to enter: $pickupAddress (coords: $pickupLat, $pickupLng)")

        // Debug: Log ALL visible text on screen (extended to 50 for DiDi debugging)
        val allText = getAllTextFromNode(rootNode)
        Log.i(TAG, "🚕 === ALL VISIBLE TEXT ON SCREEN (${allText.size} items) ===")
        allText.take(50).forEachIndexed { index, text ->
            Log.i(TAG, "🚕 [$index] '$text'")
        }
        Log.i(TAG, "🚕 === END OF VISIBLE TEXT ===")

        // PRIORITY 1: Check if we're on "Select Address" screen FIRST
        // This screen has both pickup field (top) and "Where to?" (bottom)
        val isOnSelectAddressScreen = allText.any { text ->
            val lower = text.lowercase()
            lower.contains("select address") ||
            lower.contains("اختر العنوان") ||
            lower.contains("اختار العنوان") ||
            lower.contains("选择地址")  // Chinese
        }

        if (isOnSelectAddressScreen) {
            Log.i(TAG, "🚕 [DiDi] On SELECT ADDRESS screen - looking for pickup field (top field)...")

            // On this screen, the pickup field is the TOP field (green dot)
            // It shows the current location name like "السيدة نفيسة"
            // We need to click it to edit it

            // Strategy 1: Find all EditText fields and click the TOP one (pickup)
            val editTexts = mutableListOf<AccessibilityNodeInfo>()
            findAllEditTexts(rootNode, editTexts)
            Log.i(TAG, "🚕 Found ${editTexts.size} EditText fields")

            if (editTexts.isNotEmpty()) {
                // Sort by Y position - pickup field is at the TOP
                editTexts.sortWith { a, b ->
                    val rectA = android.graphics.Rect()
                    val rectB = android.graphics.Rect()
                    a.getBoundsInScreen(rectA)
                    b.getBoundsInScreen(rectB)
                    rectA.top.compareTo(rectB.top)
                }

                // Get the TOP EditText (should be pickup field)
                val pickupField = editTexts[0]
                val rect = android.graphics.Rect()
                pickupField.getBoundsInScreen(rect)
                val currentText = pickupField.text?.toString() ?: ""
                Log.i(TAG, "🚕 Top EditText at y=${rect.top}: '$currentText'")

                // Click to focus and edit
                pickupField.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
                pickupField.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                Log.i(TAG, "🚕 ✓ Clicked top pickup field!")

                editTexts.forEach { it.recycle() }
                Thread.sleep(TimingConfig.clickDelay)
                return true
            }

            // Strategy 2: Find by location text patterns (the pickup shows location name)
            // The pickup field might show text like "السيدة نفيسة", "Your location", etc.
            // We look for clickable items that are NOT "Where to?"
            Log.i(TAG, "🚕 Strategy 2: Looking for clickable location text...")
            val clickableNodes = mutableListOf<Pair<AccessibilityNodeInfo, android.graphics.Rect>>()
            findClickableTextNodes(rootNode, clickableNodes)

            // Filter out "Where to?" and sort by Y position
            val pickupCandidates = clickableNodes.filter { (node, _) ->
                val text = (node.text?.toString() ?: "") + (node.contentDescription?.toString() ?: "")
                !text.lowercase().contains("where to") &&
                !text.contains("إلى أين") &&
                text.isNotEmpty()
            }.sortedBy { it.second.top }

            if (pickupCandidates.isNotEmpty()) {
                val (pickupNode, pickupRect) = pickupCandidates[0]
                Log.i(TAG, "🚕 Found pickup candidate at y=${pickupRect.top}")
                pickupNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                Log.i(TAG, "🚕 ✓ Clicked pickup candidate!")
                clickableNodes.forEach { it.first.recycle() }
                return true
            }

            // Strategy 3: Tap at fixed position (top input area ~15% from top)
            Log.i(TAG, "🚕 Strategy 3: Tapping at top input area...")
            try {
                val displayMetrics = resources.displayMetrics
                val screenWidth = displayMetrics.widthPixels
                val screenHeight = displayMetrics.heightPixels
                val tapX = screenWidth / 2f
                val tapY = screenHeight * 0.15f  // 15% from top (where pickup field is)
                Log.i(TAG, "🚕 Tapping at ($tapX, $tapY)")
                clickAtPositionWithDuration(tapX, tapY, 150)
                Thread.sleep(TimingConfig.clickDelay)
                return true
            } catch (e: Exception) {
                Log.e(TAG, "🚕 Tap failed: ${e.message}")
            }

            clickableNodes.forEach { it.first.recycle() }
            return false
        }

        // PRIORITY 2: Check if we're on the HOME screen (need to click "Where to?" first)
        // EXTENDED patterns: English, Arabic, Chinese (DiDi is 滴滴出行)
        val isOnHomeScreen = allText.any { text ->
            val lower = text.lowercase()
            lower.contains("tap to enter your destination") ||
            lower.contains("where to?") ||
            lower.contains("where to") ||
            lower.contains("إلى أين") ||
            lower.contains("الى اين") ||
            lower.contains("وين رايح") ||
            lower.contains("去哪儿") ||  // Chinese "Where to go"
            lower.contains("输入目的地") ||  // Chinese "Enter destination"
            lower.contains("你要去哪") ||  // Chinese "Where do you want to go"
            lower.contains("destination") ||
            lower.contains("الوجهة")
        }

        if (isOnHomeScreen) {
            Log.i(TAG, "🚕 [DiDi] On HOME screen - clicking 'Where to?' to open search...")

            // Click "Where to?" to open the search screen
            // DiDi requires ACTION_CLICK on the node, not gesture tap
            // IMPORTANT: "Tap to enter your destination" must come FIRST because "Where to?"
            // matches both the text label AND the button, but we need to click the button
            // EXTENDED: English, Arabic, Chinese patterns
            val whereToTexts = listOf(
                "Tap to enter your destination",
                "إلى أين؟", "إلى أين", "الى اين", "الى اين؟",
                "الوجهة", "وين رايح", "أين تريد الذهاب",
                "Where to?Button", "Where to?", "Where to",
                "去哪儿", "输入目的地", "你要去哪", "目的地",  // Chinese patterns
                "Destination", "Search", "بحث"
            )
            for (searchText in whereToTexts) {
                val nodes = rootNode.findAccessibilityNodeInfosByText(searchText)
                if (nodes.isNotEmpty()) {
                    val node = nodes[0]
                    val rect = android.graphics.Rect()
                    node.getBoundsInScreen(rect)
                    Log.i(TAG, "🚕 Found '$searchText' at $rect - trying multiple click strategies...")

                    // Try ALL strategies - don't return early, gesture dispatch "success" doesn't mean button responded
                    var clickAttempted = false

                    // Strategy 1: Try ACTION_CLICK directly on node
                    Log.i(TAG, "🚕 Strategy 1: ACTION_CLICK on node...")
                    if (node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                        Log.i(TAG, "🚕 ✓ ACTION_CLICK on node succeeded!")
                        clickAttempted = true
                        Thread.sleep(TimingConfig.animationWait)
                    }

                    // Strategy 2: Try clicking the parent (Button container)
                    Log.i(TAG, "🚕 Strategy 2: clicking parent...")
                    val parent = node.parent
                    if (parent != null) {
                        if (parent.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                            Log.i(TAG, "🚕 ✓ Parent click succeeded!")
                            clickAttempted = true
                            Thread.sleep(TimingConfig.animationWait)
                        }

                        // Try grandparent
                        val grandparent = parent.parent
                        if (grandparent != null) {
                            if (grandparent.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                                Log.i(TAG, "🚕 ✓ Grandparent click succeeded!")
                                clickAttempted = true
                                Thread.sleep(TimingConfig.animationWait)
                            }
                            grandparent.recycle()
                        }
                        parent.recycle()
                    }

                    // Strategy 3: Gesture tap at center (100ms)
                    Log.i(TAG, "🚕 Strategy 3: gesture tap at center (100ms)...")
                    clickAtPosition(rect.centerX().toFloat(), rect.centerY().toFloat())
                    clickAttempted = true
                    Thread.sleep(TimingConfig.animationWait)

                    // Strategy 4: Gesture tap with longer duration (200ms)
                    Log.i(TAG, "🚕 Strategy 4: long gesture tap (200ms)...")
                    clickAtPositionWithDuration(rect.centerX().toFloat(), rect.centerY().toFloat(), 200)
                    clickAttempted = true
                    Thread.sleep(TimingConfig.animationWait)

                    // Strategy 5: Try clicking slightly above center (where text might be)
                    Log.i(TAG, "🚕 Strategy 5: gesture tap at upper area...")
                    val upperY = rect.top + (rect.height() * 0.3f)
                    clickAtPositionWithDuration(rect.centerX().toFloat(), upperY, 150)
                    clickAttempted = true

                    nodes.forEach { it.recycle() }

                    // Wait for screen to change, then return false to check if we moved to search screen
                    Thread.sleep(TimingConfig.animationWait * 2)
                    Log.i(TAG, "🚕 Tried all click strategies, waiting for screen change...")
                    return false
                }
            }

            // FALLBACK: No text found but we're on home screen - tap at known position
            Log.w(TAG, "🚕 [DiDi] On HOME screen but couldn't find any 'Where to?' text - using position fallback...")
            Log.w(TAG, "🚕 [DiDi] This means DiDi's UI text doesn't match our patterns. Check the logged text above!")

            // DiDi's "Where to?" button is typically in the upper-middle area of the screen
            // Try tapping at approximately 1/3 down from top, center of screen
            try {
                val displayMetrics = resources.displayMetrics
                val screenWidth = displayMetrics.widthPixels
                val screenHeight = displayMetrics.heightPixels

                // DiDi search bar is usually at about 25-35% from top
                val tapX = screenWidth / 2f
                val tapY = screenHeight * 0.30f  // 30% from top

                Log.i(TAG, "🚕 Fallback: Tapping at ($tapX, $tapY) - center of screen, 30% down")
                clickAtPositionWithDuration(tapX, tapY, 150)
                Thread.sleep(TimingConfig.animationWait)

                // Try another tap slightly higher
                val tapY2 = screenHeight * 0.25f
                Log.i(TAG, "🚕 Fallback: Tapping at ($tapX, $tapY2) - center of screen, 25% down")
                clickAtPositionWithDuration(tapX, tapY2, 150)
                Thread.sleep(TimingConfig.animationWait)

                // Try tapping in the middle
                val tapY3 = screenHeight * 0.40f
                Log.i(TAG, "🚕 Fallback: Tapping at ($tapX, $tapY3) - center of screen, 40% down")
                clickAtPositionWithDuration(tapX, tapY3, 150)
            } catch (e: Exception) {
                Log.e(TAG, "🚕 Fallback tap failed: ${e.message}")
            }
            return false
        }

        // Check if we're on the SEARCH screen (has "Where to?" field for destination)
        // EXTENDED: English, Arabic, Chinese patterns
        val isOnSearchScreen = allText.any { text ->
            val lower = text.lowercase()
            lower.contains("where to?") ||
            lower.contains("where to") ||
            lower.contains("pickup point") ||
            lower.contains("your location") ||
            lower.contains("current location") ||
            lower.contains("موقعك") ||
            lower.contains("نقطة الاقلال") ||
            lower.contains("نقطة الإقلال") ||
            lower.contains("من أين") ||
            lower.contains("موقعك الحالي") ||
            lower.contains("当前位置") ||  // Chinese "Current location"
            lower.contains("起点") ||  // Chinese "Starting point"
            lower.contains("上车点") ||  // Chinese "Pickup point"
            lower.contains("您的位置")  // Chinese "Your location"
        }

        if (isOnSearchScreen) {
            Log.i(TAG, "🚕 [DiDi] On SEARCH screen - looking for pickup field...")

            // DiDi search screen has:
            // - Top: Pickup field (shows current location or "Your location")
            // - Bottom: Destination field (shows "Where to?")
            // We need to click the PICKUP field (top one)

            // Strategy 1: Find by "Your location" or similar text
            // EXTENDED: English, Arabic, Chinese patterns
            val pickupTexts = listOf(
                "Your location", "Pickup point", "Current location",
                "موقعك", "موقعك الحالي", "موقعي", "الموقع الحالي",
                "نقطة الاقلال", "نقطة الإقلال", "من أين", "من أين؟",
                "当前位置", "您的位置", "起点", "上车点", "出发地",  // Chinese
                "From", "من", "Start"
            )

            for (pickupText in pickupTexts) {
                val nodes = rootNode.findAccessibilityNodeInfosByText(pickupText)
                if (nodes.isNotEmpty()) {
                    val node = nodes[0]
                    val rect = android.graphics.Rect()
                    node.getBoundsInScreen(rect)
                    Log.i(TAG, "🚕 Found pickup field '$pickupText' at $rect - clicking...")

                    // Try clicking the node or its parent
                    if (smartClick(node)) {
                        Log.i(TAG, "🚕 ✓ Clicked pickup field via smartClick!")
                        nodes.forEach { it.recycle() }
                        Thread.sleep(TimingConfig.clickDelay)
                        return true
                    }

                    // Fallback: gesture tap
                    clickAtPosition(rect.centerX().toFloat(), rect.centerY().toFloat())
                    Log.i(TAG, "🚕 ✓ Clicked pickup field via gesture tap!")
                    nodes.forEach { it.recycle() }
                    Thread.sleep(TimingConfig.clickDelay)
                    return true
                }
            }

            // Strategy 2: Find the UPPER EditText field (pickup is usually above destination)
            Log.i(TAG, "🚕 Strategy 2: Finding upper EditText field...")
            val editTexts = mutableListOf<AccessibilityNodeInfo>()
            findAllEditTexts(rootNode, editTexts)

            if (editTexts.size >= 1) {
                // Sort by Y position - pickup field is usually at the TOP
                editTexts.sortWith { a, b ->
                    val rectA = android.graphics.Rect()
                    val rectB = android.graphics.Rect()
                    a.getBoundsInScreen(rectA)
                    b.getBoundsInScreen(rectB)
                    rectA.top.compareTo(rectB.top)
                }

                // Click the FIRST (topmost) EditText - should be pickup field
                val pickupEditText = editTexts[0]
                val rect = android.graphics.Rect()
                pickupEditText.getBoundsInScreen(rect)
                val currentText = pickupEditText.text?.toString() ?: ""
                Log.i(TAG, "🚕 Found upper EditText at y=${rect.top} with text='$currentText' - clicking...")

                if (pickupEditText.isClickable || pickupEditText.isFocusable) {
                    pickupEditText.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
                    pickupEditText.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    Log.i(TAG, "🚕 ✓ Clicked/focused pickup EditText!")
                    editTexts.forEach { it.recycle() }
                    return true
                }

                // Fallback: gesture tap
                clickAtPosition(rect.centerX().toFloat(), rect.centerY().toFloat())
                Log.i(TAG, "🚕 ✓ Gesture tapped pickup EditText!")
                editTexts.forEach { it.recycle() }
                return true
            }
        }

        // FALLBACK: Couldn't detect which screen we're on
        // This happens when DiDi shows unexpected UI text
        Log.w(TAG, "🚕 Could not determine DiDi screen type!")
        Log.w(TAG, "🚕 isOnHomeScreen=$isOnHomeScreen, isOnSearchScreen=$isOnSearchScreen")
        Log.w(TAG, "🚕 Using AGGRESSIVE FALLBACK - tapping at multiple positions...")

        try {
            val displayMetrics = resources.displayMetrics
            val screenWidth = displayMetrics.widthPixels
            val screenHeight = displayMetrics.heightPixels
            val tapX = screenWidth / 2f

            // Try tapping at several Y positions where DiDi typically shows inputs
            val tapPositions = listOf(0.25f, 0.30f, 0.35f, 0.40f, 0.45f)
            for (yPercent in tapPositions) {
                val tapY = screenHeight * yPercent
                Log.i(TAG, "🚕 Aggressive fallback: Tapping at ($tapX, $tapY) - ${(yPercent * 100).toInt()}% from top")
                clickAtPositionWithDuration(tapX, tapY, 150)
                Thread.sleep(200)
            }
        } catch (e: Exception) {
            Log.e(TAG, "🚕 Aggressive fallback failed: ${e.message}")
        }

        return false
    }

    /**
     * Enter pickup address text into DiDi's pickup field
     * Uses coordinates format that DiDi recognizes well
     */
    private fun enterDiDiPickupText(rootNode: AccessibilityNodeInfo): Boolean {
        Log.i(TAG, "🚕 enterDiDiPickupText: Looking for focused input...")

        // Use coordinates format for DiDi - it handles this well
        val textToEnter = "$pickupLat, $pickupLng"
        Log.i(TAG, "🚕 Using COORDINATES for DiDi pickup: $textToEnter")

        // Try to find focused input first
        val focusedNode = rootNode.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        if (focusedNode != null) {
            val focusedClass = focusedNode.className?.toString()?.substringAfterLast(".") ?: ""
            val focusedText = focusedNode.text?.toString() ?: ""
            Log.i(TAG, "🚕 Found focused node: [$focusedClass] text='$focusedText'")

            // Enter the text
            val args = android.os.Bundle()
            args.putCharSequence(
                AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                textToEnter
            )
            val result = focusedNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)

            if (result) {
                Log.i(TAG, "🚕 ✓ Successfully entered pickup: $textToEnter")
                focusedNode.recycle()
                // Wait a moment for suggestions to appear
                Thread.sleep(TimingConfig.textInputDelay * 2)
                return true
            } else {
                Log.w(TAG, "🚕 ✗ ACTION_SET_TEXT failed on focused node")
            }
            focusedNode.recycle()
        } else {
            Log.w(TAG, "🚕 No focused input node found")
        }

        // Fallback: find any editable field and enter text
        Log.i(TAG, "🚕 Fallback: searching for any EditText field...")
        return enterTextIntoAnyEditText(rootNode, textToEnter)
    }

    /**
     * Find and click DiDi's destination field on home screen
     * DiDi's "Where to?" button requires more aggressive clicking strategies
     * The button may not be directly clickable - need to use gesture tap as fallback
     */
    private fun findAndClickDiDiDestinationField(rootNode: AccessibilityNodeInfo): Boolean {
        Log.i(TAG, "🚕 ========== DIDI DESTINATION FIELD SEARCH ==========")
        Log.i(TAG, "🚕 Attempt: $didiDestinationClickAttempts/${TimingConfig.DIDI_MAX_DESTINATION_ATTEMPTS}")

        // Vary strategy based on attempt number - cycle through quickly
        // Since gesture tap doesn't work on DiDi, try each strategy just once
        val attemptStrategy = when {
            didiDestinationClickAttempts == 1 -> "gesture"      // First attempt: gesture tap
            didiDestinationClickAttempts == 2 -> "ancestors"    // Second: ACTION_CLICK on ancestors
            didiDestinationClickAttempts == 3 -> "container"    // Third: click on container by ID
            else -> {
                // Cycle through strategies: 4->gesture, 5->ancestors, 6->container, etc.
                val cycle = (didiDestinationClickAttempts - 1) % 3
                when (cycle) {
                    0 -> "gesture"
                    1 -> "ancestors"
                    else -> "container"
                }
            }
        }
        Log.i(TAG, "🚕 Using strategy: $attemptStrategy")

        // Debug: Log ALL visible text on screen (only on first attempt)
        if (didiDestinationClickAttempts <= 1) {
            Log.i(TAG, "🚕 === ALL VISIBLE TEXT ON SCREEN ===")
            logAllVisibleTextDetailed(rootNode, 0)
            Log.i(TAG, "🚕 === END OF VISIBLE TEXT ===")
        }

        // DiDi destination field texts - English, Arabic, Chinese
        val searchTexts = listOf(
            // Primary - exact match first
            "Where to?",
            "Where to",
            // Arabic
            "إلى أين",
            "إلى أين؟",
            "الوجهة",
            // Chinese
            "去哪儿",
            "输入目的地",
            // Generic
            "Destination",
            "Search",
            "Tap to enter your destination"
        )

        // Find the "Where to?" node first
        var targetNode: AccessibilityNodeInfo? = null
        var targetRect = android.graphics.Rect()
        for (searchText in searchTexts) {
            val nodes = rootNode.findAccessibilityNodeInfosByText(searchText)
            if (nodes.isNotEmpty()) {
                targetNode = nodes[0]
                targetNode.getBoundsInScreen(targetRect)
                Log.i(TAG, "🚕 Found node for '$searchText' at $targetRect")
                // Recycle other nodes
                for (i in 1 until nodes.size) {
                    nodes[i].recycle()
                }
                break
            }
        }

        if (targetNode == null) {
            Log.w(TAG, "🚕 No 'Where to?' node found!")
            return false
        }

        // STRATEGY: GESTURE TAP (attempts 1-2)
        if (attemptStrategy == "gesture") {
            Log.i(TAG, "🚕 Strategy: Gesture tap at center (${targetRect.centerX()}, ${targetRect.centerY()})")
            clickAtPosition(targetRect.centerX().toFloat(), targetRect.centerY().toFloat())
            Thread.sleep(TimingConfig.clickDelay)
            targetNode.recycle()
            return true
        }

        // STRATEGY: ACTION_CLICK ON ANCESTORS (attempts 3-5)
        if (attemptStrategy == "ancestors") {
            Log.i(TAG, "🚕 Strategy: ACTION_CLICK on ancestors")
            var current = targetNode.parent
            for (level in 1..5) {
                if (current == null) break

                val ancestorClass = current.className?.toString()?.substringAfterLast(".") ?: ""
                Log.i(TAG, "🚕   Trying ACTION_CLICK on level $level ancestor [$ancestorClass]...")

                if (current.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                    Log.i(TAG, "🚕 ✓ ACTION_CLICK succeeded on level $level!")
                    Thread.sleep(TimingConfig.animationWait)
                    current.recycle()
                    targetNode.recycle()
                    return true
                }

                val parent = current.parent
                current.recycle()
                current = parent
            }
            targetNode.recycle()
            return true // Return true to let ENTERING_DESTINATION check if it worked
        }

        // STRATEGY: CLICK ON CONTAINER BY RESOURCE ID (attempts 6+)
        Log.i(TAG, "🚕 Strategy: Click on container by resource ID")
        targetNode.recycle()

        val didiContainerIds = listOf(
            "home_entrance_view_above",
            "home_entrance_view_new",
            "xp_cell_container",
            "xp_scroll_view"
        )
        for (containerId in didiContainerIds) {
            val fullId = "com.didiglobal.passenger:id/$containerId"
            val containers = rootNode.findAccessibilityNodeInfosByViewId(fullId)
            if (containers.isNotEmpty()) {
                val container = containers[0]
                val rect = android.graphics.Rect()
                container.getBoundsInScreen(rect)

                Log.i(TAG, "🚕   Found container: $containerId at $rect")

                // Click at the "Where to?" position within this container
                val clickX = targetRect.centerX().toFloat()
                val clickY = targetRect.centerY().toFloat()
                Log.i(TAG, "🚕   Clicking at ($clickX, $clickY)")
                clickAtPosition(clickX, clickY)
                Thread.sleep(TimingConfig.animationWait)

                for (c in containers) c.recycle()
                return true
            }
        }

        return true
    }

    /**
     * Deep traversal to find DiDi clickable search/destination elements
     */
    private fun findDiDiClickableSearchElement(node: AccessibilityNodeInfo): Boolean {
        val text = node.text?.toString()?.lowercase() ?: ""
        val contentDesc = node.contentDescription?.toString()?.lowercase() ?: ""
        val className = node.className?.toString()?.lowercase() ?: ""

        // Check if this looks like a search/destination element
        val isSearchLike = text.contains("where") || text.contains("destination") ||
                           text.contains("search") || text.contains("إلى أين") ||
                           contentDesc.contains("where") || contentDesc.contains("destination") ||
                           contentDesc.contains("tap to enter") ||
                           className.contains("button") || className.contains("searchview")

        if (isSearchLike) {
            Log.i(TAG, "🚕   Found search-like element: text='${text.take(30)}', clickable=${node.isClickable}")

            // Use smartClick for better reliability
            if (smartClick(node)) {
                Log.i(TAG, "🚕 ✓✓✓ SUCCESS: SmartClick on search-like element")
                return true
            }
        }

        // Recursively check children
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            if (findDiDiClickableSearchElement(child)) {
                child.recycle()
                return true
            }
            child.recycle()
        }

        return false
    }

    // Track Careem state - need to click "سيارة" first before destination
    private var careemCarButtonClicked = false
    private var careemCarButtonClickAttempts = 0  // Track how many times we've tried clicking the car button
    private var careemSearchBarClicked = false   // Track if we clicked the search bar on map view
    private var careemSearchBarClickAttempts = 0 // Track search bar click attempts for different positions
    private var careemPickupFieldClicked = false  // Track if we clicked the pickup field to open edit mode
    private var careemPickupEntered = false       // Track if we typed the pickup address
    private var careemPickupSuggestionClicked = false  // Track if we clicked a pickup suggestion
    private var careemPickupClickAttempts = 0     // Count pickup suggestion click attempts
    private var careemLoaderFirstSeenTime = 0L   // Track when we first see the loader
    private var careemUseCurrentLocationAttempted = false  // Track if we tried "use current location" fallback
    private var careemNoValidPickupSuggestionCount = 0  // Count consecutive failures to find valid pickup suggestions
    private var careemPickupButtonFallbackAttempted = false  // Track if we tried clicking "pickUp" button fallback
    private var careemPickupPhaseComplete = false  // Track if Careem pickup entry is done (PICKUP FIRST flow)
    private var careemDestinationSuggestionClicked = false  // Track if destination suggestion was clicked
    private var careemDestinationClickAttempts = 0  // Count destination suggestion click attempts
    private var careemDestinationMismatchRetries = 0  // BUG FIX: Count retries when destination suggestions don't match
    private var careemPositionBasedClickAttempt = 0  // Track position-based click attempts for suggestions
    private var careemGestureClickAttempt = 0  // Track gesture click attempts for cycling through positions

    /**
     * Find and click Careem PICKUP field for PICKUP FIRST flow
     * This is called BEFORE destination entry to solve the suggestion refresh issue
     * Flow: Click "سيارة" → Find pickup field → Click it → Enter pickup address
     */
    private fun findAndClickCareemPickupFieldFirst(rootNode: AccessibilityNodeInfo): Boolean {
        Log.i(TAG, "🚖 ========== CAREEM PICKUP FIELD FIRST SEARCH ==========")

        // Debug: Log ALL visible text on screen
        Log.i(TAG, "🚖 === ALL VISIBLE TEXT ON CAREEM SCREEN ===")
        val allText = getAllTextFromNode(rootNode)
        allText.take(20).forEachIndexed { index, text ->
            Log.i(TAG, "🚖 [$index] '$text'")
        }
        Log.i(TAG, "🚖 === END OF VISIBLE TEXT (${allText.size} items) ===")

        // STEP 1: Check if we're on Careem HOME screen (need to click "سيارة" first)
        // Use normalized string matching to handle invisible Unicode characters (RTL marks, zero-width spaces)
        val hasCareemHomeIndicators = allText.any { text ->
            normalizedContains(text, "Get more with Careem") ||
            normalizedContains(text, "أدخل كود الخصم") ||
            normalizedContains(text, "استمتع بعروض") ||
            normalizedContains(text, "مرحبا") ||
            normalizedContains(text, "أهلا") ||
            normalizedContains(text, "Super App") ||
            normalizedContains(text, "Careem+") ||
            normalizedContains(text, "كريم+") ||
            normalizedContains(text, "طعام") ||  // Food service
            normalizedContains(text, "Bike") ||  // Bike service
            normalizedContains(text, "توصيل")    // Delivery service
        }

        // Check for service buttons: سيارة + one of (حوّل محلياً, حول محليا, طلب المال, etc.)
        val hasCarButton = allText.any { text ->
            normalizedContains(text, "سيارة") || normalizedContains(text, "سياره")
        }
        val hasOtherServiceButtons = allText.any { text ->
            normalizedContains(text, "حوّل محلياً") ||  // With diacritics
            normalizedContains(text, "حول محليا") ||   // Without diacritics
            normalizedContains(text, "طلب المال") ||
            normalizedContains(text, "طعام") ||
            normalizedContains(text, "Bike") ||
            normalizedContains(text, "توصيل")
        }
        val hasCareemServiceButtons = hasCarButton && hasOtherServiceButtons

        val isOnHomeScreen = hasCareemHomeIndicators || hasCareemServiceButtons

        // Debug: Log detection results for diagnosis
        Log.i(TAG, "🚖 [PICKUP FIRST] Home screen detection:")
        Log.i(TAG, "🚖   - hasCareemHomeIndicators=$hasCareemHomeIndicators")
        Log.i(TAG, "🚖   - hasCarButton=$hasCarButton, hasOtherServiceButtons=$hasOtherServiceButtons")
        Log.i(TAG, "🚖   - hasCareemServiceButtons=$hasCareemServiceButtons")
        Log.i(TAG, "🚖   - isOnHomeScreen=$isOnHomeScreen")
        Log.i(TAG, "🚖   - careemCarButtonClicked=$careemCarButtonClicked")
        Log.i(TAG, "🚖   - careemCarButtonClickAttempts=$careemCarButtonClickAttempts")

        // STEP 1: If on home screen, click "سيارة" button first
        if (isOnHomeScreen && !careemCarButtonClicked) {
            Log.i(TAG, "🚖 [PICKUP FIRST] Detected Careem HOME screen - need to click 'سيارة' (Car) button first")

            // Look for "سيارة" button using NORMALIZED text search
            // Android's findAccessibilityNodeInfosByText doesn't handle Unicode normalization
            // so we use our custom function that handles RTL marks and zero-width characters
            val carButtonTexts = listOf("سيارة", "سياره", "Car", "Ride")
            for (buttonText in carButtonTexts) {
                // Use normalized text search instead of Android's findAccessibilityNodeInfosByText
                val nodes = findAllNodesWithNormalizedText(rootNode, buttonText)
                if (nodes.isNotEmpty()) {
                    Log.i(TAG, "🚖 [PICKUP FIRST] Found ${nodes.size} nodes for '$buttonText' (normalized search)")
                    for (node in nodes) {
                        if (smartClick(node)) {
                            Log.i(TAG, "🚖 [PICKUP FIRST] ✓ Clicked '$buttonText' button - waiting for ride screen...")
                            careemCarButtonClicked = true
                            careemCarButtonClickAttempts = 0  // Reset attempts on success
                            nodes.forEach { try { it.recycle() } catch (e: Exception) {} }
                            Thread.sleep(TimingConfig.animationWait)
                            return false  // Return false to allow re-entry after screen loads
                        }
                        // Try gesture click
                        if (careemGestureClick(node)) {
                            Log.i(TAG, "🚖 [PICKUP FIRST] ✓ Gesture clicked '$buttonText' button")
                            careemCarButtonClicked = true
                            careemCarButtonClickAttempts = 0  // Reset attempts on success
                            nodes.forEach { try { it.recycle() } catch (e: Exception) {} }
                            Thread.sleep(TimingConfig.animationWait)
                            return false
                        }
                    }
                    nodes.forEach { try { it.recycle() } catch (e: Exception) {} }
                }
            }

            // Failed to click - increment attempts counter
            careemCarButtonClickAttempts++
            Log.w(TAG, "🚖 [PICKUP FIRST] Could not click 'سيارة' button (attempt $careemCarButtonClickAttempts/5)")

            // Only mark as clicked after 5 failed attempts to avoid infinite loop
            if (careemCarButtonClickAttempts >= 5) {
                Log.w(TAG, "🚖 [PICKUP FIRST] Max car button click attempts reached - marking as clicked to proceed")
                careemCarButtonClicked = true
            }
            return false
        }

        // STEP 2: We're on ride booking screen - find and click the PICKUP field
        Log.i(TAG, "🚖 [PICKUP FIRST] On ride booking screen - looking for PICKUP field...")

        // Strategy 1: Look for "pickUp" element
        val pickupNode = findNodeWithText(rootNode, "pickUp")
        if (pickupNode != null) {
            Log.i(TAG, "🚖 [PICKUP FIRST] Found 'pickUp' node")
            if (clickNodeOrParent(pickupNode)) {
                Log.i(TAG, "🚖 [PICKUP FIRST] ✓ Clicked 'pickUp' node")
                pickupNode.recycle()
                Thread.sleep(500)
                return true
            }
            if (careemGestureClick(pickupNode)) {
                Log.i(TAG, "🚖 [PICKUP FIRST] ✓ Gesture clicked 'pickUp' node")
                pickupNode.recycle()
                Thread.sleep(500)
                return true
            }
            pickupNode.recycle()
        }

        // Strategy 2: Look for "البيت" (Home) or pickup-related text
        val pickupTexts = listOf("البيت", "Home", "من أين", "Pickup", "من", "نقطة الانطلاق")
        for (pickupText in pickupTexts) {
            val nodes = rootNode.findAccessibilityNodeInfosByText(pickupText)
            if (nodes.isNotEmpty()) {
                Log.i(TAG, "🚖 [PICKUP FIRST] Found ${nodes.size} nodes for '$pickupText'")
                for (node in nodes) {
                    // Skip if this is inside the destination area
                    val nodeText = node.text?.toString() ?: ""
                    val destKeywords = destinationAddress.split(" ").filter { it.length > 2 }
                    val isDestinationRelated = destKeywords.any { nodeText.contains(it) }
                    if (isDestinationRelated) {
                        Log.i(TAG, "🚖 [PICKUP FIRST] Skipping - appears to be destination related")
                        continue
                    }

                    if (clickNodeOrParent(node)) {
                        Log.i(TAG, "🚖 [PICKUP FIRST] ✓ Clicked '$pickupText' node")
                        nodes.forEach { it.recycle() }
                        Thread.sleep(500)
                        return true
                    }
                    if (careemGestureClick(node)) {
                        Log.i(TAG, "🚖 [PICKUP FIRST] ✓ Gesture clicked '$pickupText' node")
                        nodes.forEach { it.recycle() }
                        Thread.sleep(500)
                        return true
                    }
                }
                nodes.forEach { it.recycle() }
            }
        }

        // Strategy 3: Find EditTexts and click the TOP one (pickup is usually at top)
        val editTexts = mutableListOf<AccessibilityNodeInfo>()
        findAllEditTexts(rootNode, editTexts)

        if (editTexts.isNotEmpty()) {
            Log.i(TAG, "🚖 [PICKUP FIRST] Found ${editTexts.size} EditText fields")

            // Sort by Y position
            val sortedEditTexts = editTexts.toMutableList()
            sortedEditTexts.sortWith { a, b ->
                val rectA = android.graphics.Rect()
                val rectB = android.graphics.Rect()
                a.getBoundsInScreen(rectA)
                b.getBoundsInScreen(rectB)
                rectA.top.compareTo(rectB.top)
            }

            // Click the FIRST (top) EditText - this should be the pickup field
            val topEditText = sortedEditTexts.first()
            val rect = android.graphics.Rect()
            topEditText.getBoundsInScreen(rect)
            val text = topEditText.text?.toString() ?: ""
            Log.i(TAG, "🚖 [PICKUP FIRST] Top EditText: y=${rect.top}, text='$text'")

            if (topEditText.performAction(AccessibilityNodeInfo.ACTION_FOCUS)) {
                Log.i(TAG, "🚖 [PICKUP FIRST] ✓ Focused top EditText")
                editTexts.forEach { try { it.recycle() } catch (e: Exception) {} }
                Thread.sleep(300)
                return true
            }
            if (topEditText.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                Log.i(TAG, "🚖 [PICKUP FIRST] ✓ Clicked top EditText")
                editTexts.forEach { try { it.recycle() } catch (e: Exception) {} }
                Thread.sleep(300)
                return true
            }
            if (careemGestureClick(topEditText)) {
                Log.i(TAG, "🚖 [PICKUP FIRST] ✓ Gesture clicked top EditText")
                editTexts.forEach { try { it.recycle() } catch (e: Exception) {} }
                Thread.sleep(300)
                return true
            }

            editTexts.forEach { try { it.recycle() } catch (e: Exception) {} }
        }

        Log.w(TAG, "🚖 [PICKUP FIRST] ✗ Could not find pickup field")
        return false
    }

    /**
     * Enter Careem pickup address text for PICKUP FIRST flow
     * Called after clickingthe pickup field in PICKUP FIRST flow
     */
    private fun enterCareemPickupTextFirst(rootNode: AccessibilityNodeInfo): Boolean {
        Log.i(TAG, "🚖 ========== CAREEM PICKUP TEXT ENTRY (PICKUP FIRST) ==========")
        Log.i(TAG, "🚖 Pickup to enter: $pickupAddress")

        // Find ALL EditText fields
        val editTexts = mutableListOf<AccessibilityNodeInfo>()
        findAllEditTexts(rootNode, editTexts)

        Log.i(TAG, "🚖 [PICKUP FIRST] Found ${editTexts.size} EditText fields")

        // Sort by Y position - pickup field is usually at the TOP
        val sortedEditTexts = editTexts.toMutableList()
        sortedEditTexts.sortWith { a, b ->
            val rectA = android.graphics.Rect()
            val rectB = android.graphics.Rect()
            a.getBoundsInScreen(rectA)
            b.getBoundsInScreen(rectB)
            rectA.top.compareTo(rectB.top)
        }

        // Log all EditTexts with their positions
        sortedEditTexts.forEachIndexed { index, et ->
            val rect = android.graphics.Rect()
            et.getBoundsInScreen(rect)
            val text = et.text?.toString() ?: ""
            val hint = et.hintText?.toString() ?: ""
            Log.i(TAG, "🚖 [PICKUP FIRST] EditText[$index]: y=${rect.top}, text='$text', hint='$hint'")
        }

        // Use the FIRST (topmost) EditText - this is the pickup field
        for (editText in sortedEditTexts) {
            val currentText = editText.text?.toString() ?: ""
            val rect = android.graphics.Rect()
            editText.getBoundsInScreen(rect)

            // Skip if this field contains destination-related text
            val destKeywords = destinationAddress.split(" ").filter { it.length > 3 }
            val hasDestination = destKeywords.any { currentText.contains(it) }

            if (hasDestination) {
                Log.i(TAG, "🚖 [PICKUP FIRST] Skipping field with destination text: '$currentText'")
                continue
            }

            Log.i(TAG, "🚖 [PICKUP FIRST] Using EditText at y=${rect.top} for pickup")

            // Clear existing text first
            val clearArgs = android.os.Bundle()
            clearArgs.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "")
            editText.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, clearArgs)
            Thread.sleep(100)

            // Enter the pickup address
            val arguments = android.os.Bundle()
            arguments.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, pickupAddress)

            if (editText.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)) {
                Log.i(TAG, "🚖 [PICKUP FIRST] ✓ Entered pickup address: $pickupAddress")
                editTexts.forEach { try { it.recycle() } catch (e: Exception) {} }
                Thread.sleep(TimingConfig.textInputDelay)
                return true
            }
        }

        editTexts.forEach { try { it.recycle() } catch (e: Exception) {} }

        // Fallback: try focused input
        val focusedInput = findFocusedEditText(rootNode)
        if (focusedInput != null) {
            Log.i(TAG, "🚖 [PICKUP FIRST] Fallback: Using focused input field")

            val arguments = android.os.Bundle()
            arguments.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, pickupAddress)

            if (focusedInput.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)) {
                Log.i(TAG, "🚖 [PICKUP FIRST] ✓ Entered pickup address via focused field: $pickupAddress")
                focusedInput.recycle()
                Thread.sleep(TimingConfig.textInputDelay)
                return true
            }
            focusedInput.recycle()
        }

        Log.w(TAG, "🚖 [PICKUP FIRST] ✗ Could not enter pickup address")
        return false
    }

    /**
     * Find and click Careem destination field
     * Careem's UI flow:
     * 1. Home screen shows service buttons: "سيارة" (Car), "حول محليا", "طلب المال"
     * 2. Must click "سيارة" first to get to ride booking screen
     * 3. Then the destination field appears
     */
    private fun findAndClickCareemDestinationField(rootNode: AccessibilityNodeInfo): Boolean {
        // Log if we're in PICKUP FIRST flow
        if (careemPickupPhaseComplete) {
            Log.i(TAG, "🚖 ========== CAREEM DESTINATION FIELD SEARCH (PICKUP FIRST - pickup already done) ==========")
        } else {
            Log.i(TAG, "🚖 ========== CAREEM DESTINATION FIELD SEARCH ==========")
        }

        // Debug: Log ALL visible text on screen
        Log.i(TAG, "🚖 === ALL VISIBLE TEXT ON CAREEM SCREEN ===")
        val allText = getAllTextFromNode(rootNode)
        allText.forEachIndexed { index, text ->
            Log.i(TAG, "🚖 [$index] '$text'")
        }
        Log.i(TAG, "🚖 === END OF VISIBLE TEXT (${allText.size} items) ===")

        // STEP 1: Check if we're on Careem HOME screen (need to click "سيارة" first)
        // Careem Super App shows various services: سيارة (Ride), طعام (Food), توصيل (Delivery), etc.
        // Use normalized string matching to handle invisible Unicode characters (RTL marks, zero-width spaces)
        val hasCareemHomeIndicators = allText.any { text ->
            normalizedContains(text, "Get more with Careem") ||
            normalizedContains(text, "أدخل كود الخصم") ||
            normalizedContains(text, "استمتع بعروض") ||
            normalizedContains(text, "مرحبا") ||
            normalizedContains(text, "أهلا") ||
            normalizedContains(text, "Super App") ||
            normalizedContains(text, "Careem+") ||
            normalizedContains(text, "كريم+") ||
            normalizedContains(text, "طعام") ||  // Food service
            normalizedContains(text, "Bike") ||  // Bike service
            normalizedContains(text, "توصيل")    // Delivery service
        }

        // Check for service buttons: سيارة + one of (حوّل محلياً, حول محليا, طلب المال, etc.)
        val hasCarButton = allText.any { text ->
            normalizedContains(text, "سيارة") || normalizedContains(text, "سياره")
        }
        val hasOtherServiceButtons = allText.any { text ->
            normalizedContains(text, "حوّل محلياً") ||  // With diacritics
            normalizedContains(text, "حول محليا") ||   // Without diacritics
            normalizedContains(text, "طلب المال") ||
            normalizedContains(text, "طعام") ||
            normalizedContains(text, "Bike") ||
            normalizedContains(text, "توصيل")
        }
        val hasCareemServiceButtons = hasCarButton && hasOtherServiceButtons

        val isOnHomeScreen = hasCareemHomeIndicators || hasCareemServiceButtons

        // Debug: Log detection results for diagnosis
        Log.i(TAG, "🚖 Home screen detection (destination flow):")
        Log.i(TAG, "🚖   - hasCareemHomeIndicators=$hasCareemHomeIndicators")
        Log.i(TAG, "🚖   - hasCarButton=$hasCarButton, hasOtherServiceButtons=$hasOtherServiceButtons")
        Log.i(TAG, "🚖   - hasCareemServiceButtons=$hasCareemServiceButtons")
        Log.i(TAG, "🚖   - isOnHomeScreen=$isOnHomeScreen")
        Log.i(TAG, "🚖   - careemCarButtonClicked=$careemCarButtonClicked")

        if (isOnHomeScreen && !careemCarButtonClicked) {
            Log.i(TAG, "🚖 Detected Careem HOME screen - need to click 'سيارة' (Car) button first")

            // Look for "سيارة" button using multiple strategies
            // Use NORMALIZED text search to handle invisible Unicode characters (RTL marks, zero-width spaces)
            val carButtonTexts = listOf("سيارة", "سياره", "Car", "Ride")

            // Strategy 1: Use NORMALIZED text search (handles Unicode issues)
            Log.i(TAG, "🚖 Strategy 1: Using normalized text search...")
            for (buttonText in carButtonTexts) {
                val nodes = findAllNodesWithNormalizedText(rootNode, buttonText)
                Log.i(TAG, "🚖 findAllNodesWithNormalizedText('$buttonText') returned ${nodes.size} nodes")
                for (node in nodes) {
                    Log.i(TAG, "🚖 Found '$buttonText' button via normalized search, attempting click...")

                    // Try clicking the node or its ancestors
                    if (smartClick(node)) {
                        Log.i(TAG, "🚖 ✓ Clicked on '$buttonText' button!")
                        careemCarButtonClicked = true
                        nodes.forEach { try { it.recycle() } catch (e: Exception) {} }
                        Thread.sleep(TimingConfig.animationWait)
                        return true // Will retry and find destination field
                    }

                    // Try gesture click directly
                    if (careemGestureClick(node)) {
                        Log.i(TAG, "🚖 ✓ Gesture clicked '$buttonText' button!")
                        careemCarButtonClicked = true
                        nodes.forEach { try { it.recycle() } catch (e: Exception) {} }
                        Thread.sleep(TimingConfig.animationWait)
                        return true
                    }

                    // Try ancestors
                    var current = node.parent
                    for (level in 1..4) {
                        if (current == null) break
                        if (smartClick(current)) {
                            Log.i(TAG, "🚖 ✓ Clicked on ancestor level $level of '$buttonText'!")
                            careemCarButtonClicked = true
                            current.recycle()
                            nodes.forEach { try { it.recycle() } catch (e: Exception) {} }
                            Thread.sleep(TimingConfig.animationWait)
                            return true
                        }
                        val parent = current.parent
                        current.recycle()
                        current = parent
                    }
                }
                nodes.forEach { try { it.recycle() } catch (e: Exception) {} }
            }

            // Strategy 2: Try gesture click on node bounds using normalized search
            Log.i(TAG, "🚖 Strategy 2: Trying gesture click on 'سيارة' node bounds...")
            for (buttonText in carButtonTexts) {
                val nodes = findAllNodesWithNormalizedText(rootNode, buttonText)
                for (node in nodes) {
                    val rect = android.graphics.Rect()
                    node.getBoundsInScreen(rect)
                    if (rect.width() > 0 && rect.height() > 0) {
                        val centerX = rect.centerX().toFloat()
                        val centerY = rect.centerY().toFloat()
                        Log.i(TAG, "🚖 Strategy 2: Found '$buttonText' at ($centerX, $centerY), using gesture click")

                        try {
                            val path = android.graphics.Path()
                            path.moveTo(centerX, centerY)

                            val gesture = android.accessibilityservice.GestureDescription.Builder()
                                .addStroke(android.accessibilityservice.GestureDescription.StrokeDescription(path, 0, 100))
                                .build()

                            dispatchGesture(gesture, null, null)
                            Log.i(TAG, "🚖 ✓ Strategy 2: Gesture click dispatched on '$buttonText' bounds")

                            careemCarButtonClicked = true
                            careemCarButtonClickAttempts++
                            nodes.forEach { try { it.recycle() } catch (e: Exception) {} }
                            Thread.sleep(TimingConfig.animationWait)
                            return true
                        } catch (e: Exception) {
                            Log.e(TAG, "🚖 Strategy 2 gesture click failed: ${e.message}")
                        }
                    }
                }
                nodes.forEach { try { it.recycle() } catch (e: Exception) {} }
            }

            // Strategy 4: Try clicking by screen position (multiple positions based on attempts)
            // Careem Super App may have "سيارة" in different places:
            // - Bottom navigation bar (old layout)
            // - Service grid in middle of screen (new Super App layout)
            // - Top service icons row
            if (careemCarButtonClickAttempts < 6) {
                Log.i(TAG, "🚖 Strategy 4: Trying gesture click on likely button position (attempt ${careemCarButtonClickAttempts + 1}/6)...")
                try {
                    val displayMetrics = resources.displayMetrics
                    val screenWidth = displayMetrics.widthPixels
                    val screenHeight = displayMetrics.heightPixels

                    // Try different positions based on attempt number
                    // Position 0: Bottom navigation bar, first item (12.5%, 92%)
                    // Position 1: Service grid top-left (20%, 35%)
                    // Position 2: Service grid top-center (50%, 35%)
                    // Position 3: Service row - first item (15%, 25%)
                    // Position 4: Service grid middle-left (20%, 45%)
                    // Position 5: Service grid top area (25%, 30%)
                    val positions = listOf(
                        Pair(0.125f, 0.92f),   // Bottom nav, first item
                        Pair(0.20f, 0.35f),    // Service grid top-left
                        Pair(0.50f, 0.35f),    // Service grid top-center
                        Pair(0.15f, 0.25f),    // Service row first item
                        Pair(0.20f, 0.45f),    // Service grid middle-left
                        Pair(0.25f, 0.30f)     // Service grid top area
                    )

                    val (xRatio, yRatio) = positions[careemCarButtonClickAttempts]
                    val targetX = (screenWidth * xRatio).toInt()
                    val targetY = (screenHeight * yRatio).toInt()

                    Log.i(TAG, "🚖 Attempting gesture click at ($targetX, $targetY) for Car button")

                    val path = android.graphics.Path()
                    path.moveTo(targetX.toFloat(), targetY.toFloat())

                    val gesture = android.accessibilityservice.GestureDescription.Builder()
                        .addStroke(android.accessibilityservice.GestureDescription.StrokeDescription(path, 0, 100))
                        .build()

                    dispatchGesture(gesture, null, null)
                    Log.i(TAG, "🚖 ✓ Strategy 4: Gesture click dispatched at position ${careemCarButtonClickAttempts + 1}")

                    careemCarButtonClickAttempts++
                    // Don't set careemCarButtonClicked = true yet - we'll verify on next iteration
                    // If still on home screen, we'll try the next position
                    Thread.sleep(TimingConfig.animationWait)
                    return true
                } catch (e: Exception) {
                    Log.e(TAG, "🚖 Strategy 4 gesture click failed: ${e.message}")
                    careemCarButtonClickAttempts++
                }
            } else {
                // We've tried all positions, mark as clicked to avoid infinite loop
                Log.w(TAG, "🚖 Exhausted all click positions - marking car button as clicked")
                careemCarButtonClicked = true
            }

            Log.w(TAG, "🚖 Could not find 'سيارة' button on home screen")
            return false
        }

        // Strategy 1: Look for common Careem destination field texts
        val careemSearchTexts = listOf(
            // English
            "Where to?", "Where to", "Search", "Enter destination",
            "Search for a place", "Where would you like to go", "Search destination",
            "Destination", "Drop-off",
            // Arabic
            "إلى أين؟", "إلى أين", "وجهتك", "أين تريد الذهاب", "بحث",
            "ابحث عن مكان", "الوجهة", "نقطة الوصول", "إلى",
            // Common UI element identifiers
            "search_destination", "destination_input", "pickup_destination"
        )

        for (searchText in careemSearchTexts) {
            val nodes = rootNode.findAccessibilityNodeInfosByText(searchText)
            if (nodes.isNotEmpty()) {
                Log.i(TAG, "🚖 Found ${nodes.size} nodes for: '$searchText'")
                for (node in nodes) {
                    val nodeText = node.text?.toString() ?: ""
                    val nodeDesc = node.contentDescription?.toString() ?: ""
                    val nodeClass = node.className?.toString()?.substringAfterLast(".") ?: ""
                    Log.i(TAG, "🚖   Node: class=$nodeClass, clickable=${node.isClickable}, text='$nodeText', desc='$nodeDesc'")

                    // Try clicking the node or its ancestors
                    if (smartClick(node)) {
                        Log.i(TAG, "🚖 ✓✓✓ SUCCESS: Clicked on '$searchText'")
                        node.recycle()
                        return true
                    }

                    // Try ancestors up to 3 levels
                    var current = node.parent
                    for (level in 1..3) {
                        if (current == null) break
                        if (current.isClickable) {
                            if (current.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                                Log.i(TAG, "🚖 ✓✓✓ SUCCESS: Clicked on ancestor level $level of '$searchText'")
                                current.recycle()
                                node.recycle()
                                return true
                            }
                        }
                        val parent = current.parent
                        current.recycle()
                        current = parent
                    }
                    node.recycle()
                }
            }
        }

        // Strategy 2: Look for EditText or input fields
        Log.i(TAG, "🚖 Strategy 2: Looking for EditText/input fields...")
        if (findAndClickCareemInputField(rootNode)) {
            return true
        }

        // Strategy 3: Look by resource ID patterns
        Log.i(TAG, "🚖 Strategy 3: Looking by resource IDs...")
        val careemResourceIds = listOf(
            "com.careem.acma:id/search_destination",
            "com.careem.acma:id/destination_input",
            "com.careem.acma:id/where_to",
            "com.careem.acma:id/pickup_destination_view",
            "com.careem.acma:id/destination_text",
            "com.careem.acma:id/search_box",
            "com.careem.acma:id/destination",
            "com.careem.acma:id/et_destination",
            "com.careem.acma:id/tv_destination"
        )

        for (resourceId in careemResourceIds) {
            val nodes = rootNode.findAccessibilityNodeInfosByViewId(resourceId)
            if (nodes.isNotEmpty()) {
                Log.i(TAG, "🚖 Found node by ID: $resourceId")
                val node = nodes[0]
                if (smartClick(node)) {
                    Log.i(TAG, "🚖 ✓✓✓ SUCCESS: Clicked on resource ID '$resourceId'")
                    nodes.forEach { it.recycle() }
                    return true
                }
                nodes.forEach { it.recycle() }
            }
        }

        // Strategy 4: Click on any view that looks like destination entry
        Log.i(TAG, "🚖 Strategy 4: Deep search for destination-like elements...")
        if (findCareemClickableDestinationElement(rootNode)) {
            return true
        }

        // Strategy 5: Detect minimal map view and tap on search bar area
        // This handles the case where Careem shows just the map with a search bar at top
        val isMinimalMapView = allText.size <= 6 &&
            allText.any { it.lowercase().contains("careem") || it.lowercase().contains("pay") } &&
            !allText.any { it.contains("سيارة") || it.contains("Where") || it.contains("إلى أين") }

        if (isMinimalMapView && careemSearchBarClickAttempts < 3) {
            Log.i(TAG, "🚖 Strategy 5: Detected minimal map view - tapping search bar area (attempt ${careemSearchBarClickAttempts + 1}/3)...")
            try {
                val displayMetrics = resources.displayMetrics
                val screenWidth = displayMetrics.widthPixels
                val screenHeight = displayMetrics.heightPixels

                // Try different Y positions for the search bar
                // Attempt 0: 16% from top (typical search bar position)
                // Attempt 1: 12% from top (higher position for some devices)
                // Attempt 2: 20% from top (lower position, card-style search)
                val yPositions = listOf(0.16f, 0.12f, 0.20f)
                val targetX = (screenWidth * 0.5f).toInt()
                val targetY = (screenHeight * yPositions[careemSearchBarClickAttempts]).toInt()

                Log.i(TAG, "🚖 Attempting gesture click at ($targetX, $targetY) for search bar")

                val path = android.graphics.Path()
                path.moveTo(targetX.toFloat(), targetY.toFloat())

                val gesture = android.accessibilityservice.GestureDescription.Builder()
                    .addStroke(android.accessibilityservice.GestureDescription.StrokeDescription(path, 0, 100))
                    .build()

                // Use null callback to avoid anonymous class compilation issues
                dispatchGesture(gesture, null, null)
                Log.i(TAG, "🚖 ✓ Gesture click dispatched on search bar position")

                careemSearchBarClickAttempts++
                careemSearchBarClicked = true
                Thread.sleep(TimingConfig.animationWait)
                return true
            } catch (e: Exception) {
                Log.e(TAG, "🚖 Strategy 5 gesture click failed: ${e.message}")
            }
        }

        Log.w(TAG, "🚖 ✗ Could not find Careem destination field")
        return false
    }

    /**
     * Find and click EditText or input fields in Careem for DESTINATION
     * This function specifically looks for the destination field,
     * skipping any field that already contains the pickup address text
     */
    private fun findAndClickCareemInputField(node: AccessibilityNodeInfo): Boolean {
        val className = node.className?.toString()?.lowercase() ?: ""
        val text = node.text?.toString()?.lowercase() ?: ""
        val originalText = node.text?.toString() ?: ""
        val hint = node.hintText?.toString()?.lowercase() ?: ""
        val desc = node.contentDescription?.toString()?.lowercase() ?: ""

        // Check if this is an input field related to destination
        val isInputField = className.contains("edittext") || className.contains("textinputedittext")
        val isDestinationRelated = text.contains("where") || text.contains("destination") ||
                                    text.contains("إلى") || text.contains("وجهة") ||
                                    hint.contains("where") || hint.contains("destination") ||
                                    hint.contains("search") || hint.contains("بحث") ||
                                    desc.contains("where") || desc.contains("destination")

        if (isInputField || isDestinationRelated) {
            // Skip if this field contains the pickup address (we're looking for destination field)
            val pickupKeywords = pickupAddress.split(" ").filter { it.length > 2 }
            val containsPickupText = pickupKeywords.any { keyword ->
                originalText.contains(keyword, ignoreCase = true)
            }

            if (containsPickupText && pickupAddress.isNotEmpty()) {
                Log.i(TAG, "🚖   Skipping input field (contains pickup text): class=$className, text='${text.take(30)}'")
                // Continue searching children for a different field
            } else {
                Log.i(TAG, "🚖   Found destination input field: class=$className, text='${text.take(30)}'")
                if (smartClick(node)) {
                    Log.i(TAG, "🚖 ✓✓✓ SUCCESS: Clicked on destination input field")
                    return true
                }
            }
        }

        // Check children
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            if (findAndClickCareemInputField(child)) {
                child.recycle()
                return true
            }
            child.recycle()
        }

        return false
    }

    /**
     * Deep search for Careem destination-like clickable elements
     */
    private fun findCareemClickableDestinationElement(node: AccessibilityNodeInfo): Boolean {
        val text = node.text?.toString()?.lowercase() ?: ""
        val desc = node.contentDescription?.toString()?.lowercase() ?: ""
        val className = node.className?.toString()?.lowercase() ?: ""

        // Keywords that indicate destination/search elements
        val destinationKeywords = listOf(
            "where", "destination", "search", "drop", "going",
            "إلى", "وجهة", "بحث", "أين"
        )

        val matchesKeyword = destinationKeywords.any { keyword ->
            text.contains(keyword) || desc.contains(keyword)
        }

        // Check if clickable and matches keywords
        if (matchesKeyword && (node.isClickable || className.contains("button") || className.contains("view"))) {
            Log.i(TAG, "🚖   Found destination-like element: text='${text.take(30)}', clickable=${node.isClickable}")
            if (smartClick(node)) {
                Log.i(TAG, "🚖 ✓✓✓ SUCCESS: Clicked on destination-like element")
                return true
            }
        }

        // Check children
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            if (findCareemClickableDestinationElement(child)) {
                child.recycle()
                return true
            }
            child.recycle()
        }

        return false
    }

    /**
     * Enter Careem pickup address
     * Called when Careem is showing the pickup/confirmation screen
     * First clicks on the pickup field to open edit mode, then enters the address
     * Returns: 0 = failed, 1 = clicked field (need to wait), 2 = entered text successfully
     */
    private fun enterCareemPickupAddress(rootNode: AccessibilityNodeInfo): Int {
        Log.i(TAG, "🚖 ========== CAREEM PICKUP ADDRESS ENTRY ==========")
        Log.i(TAG, "🚖 Pickup to enter: $pickupAddress")
        Log.i(TAG, "🚖 State: fieldClicked=$careemPickupFieldClicked, entered=$careemPickupEntered")

        // STEP 1: If we haven't clicked the field yet, try to click it
        if (!careemPickupFieldClicked) {
            Log.i(TAG, "🚖 STEP 1: Clicking pickup field to open edit mode...")
            val clicked = clickCareemPickupField(rootNode)
            if (clicked) {
                Log.i(TAG, "🚖 ✓ Clicked pickup field - waiting for edit mode...")
                careemPickupFieldClicked = true
                Thread.sleep(800) // Wait for edit mode to open
                return 1 // Clicked field, will re-enter on next cycle
            }
            Log.w(TAG, "🚖 ✗ Could not click pickup field")
            // Try entering text directly anyway
        }

        // STEP 2: We've clicked the field, now try to enter text
        Log.i(TAG, "🚖 STEP 2: Entering pickup address text...")

        // Find ALL EditText fields
        val editTexts = mutableListOf<AccessibilityNodeInfo>()
        findAllEditTexts(rootNode, editTexts)

        Log.i(TAG, "🚖 Found ${editTexts.size} EditText fields")

        // Sort by Y position - pickup field is usually at the TOP
        // Using explicit sort to avoid inline class compilation issues
        val sortedEditTexts = editTexts.toMutableList()
        sortedEditTexts.sortWith { a, b ->
            val rectA = android.graphics.Rect()
            val rectB = android.graphics.Rect()
            a.getBoundsInScreen(rectA)
            b.getBoundsInScreen(rectB)
            rectA.top.compareTo(rectB.top)
        }

        // Log all EditTexts with their positions
        sortedEditTexts.forEachIndexed { index, et ->
            val rect = android.graphics.Rect()
            et.getBoundsInScreen(rect)
            val text = et.text?.toString() ?: ""
            val hint = et.hintText?.toString() ?: ""
            Log.i(TAG, "🚖 EditText[$index]: y=${rect.top}, text='$text', hint='$hint'")
        }

        // Use the FIRST (topmost) EditText - this is the pickup field
        // Skip if it already has destination text
        for (editText in sortedEditTexts) {
            val currentText = editText.text?.toString() ?: ""
            val rect = android.graphics.Rect()
            editText.getBoundsInScreen(rect)

            // Skip if this field contains destination-related text
            val destKeywords = destinationAddress.split(" ").filter { it.length > 3 }
            val hasDestination = destKeywords.any { currentText.contains(it) }

            if (hasDestination) {
                Log.i(TAG, "🚖 Skipping field with destination text: '$currentText'")
                continue
            }

            Log.i(TAG, "🚖 Using EditText at y=${rect.top} for pickup")

            // Clear existing text first
            val clearArgs = android.os.Bundle()
            clearArgs.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "")
            editText.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, clearArgs)
            Thread.sleep(100)

            // Enter the pickup address
            val arguments = android.os.Bundle()
            arguments.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, pickupAddress)

            if (editText.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)) {
                Log.i(TAG, "🚖 ✓ Entered pickup address: $pickupAddress")

                // CRITICAL FIX: Trigger suggestion refresh by adding a space then removing it
                // ACTION_SET_TEXT doesn't always trigger text watchers in some apps
                // This forces Careem to recognize the text change and refresh suggestions
                Thread.sleep(200)

                // Add a space to trigger text change listener
                val triggerArgs = android.os.Bundle()
                triggerArgs.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "$pickupAddress ")
                editText.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, triggerArgs)
                Thread.sleep(100)

                // Remove the space (set back to original text)
                val finalArgs = android.os.Bundle()
                finalArgs.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, pickupAddress)
                editText.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, finalArgs)

                // Additional trigger: click inside the field to ensure focus
                Thread.sleep(100)
                val fieldRect = android.graphics.Rect()
                editText.getBoundsInScreen(fieldRect)
                val clickX = (fieldRect.left + fieldRect.right) / 2
                val clickY = (fieldRect.top + fieldRect.bottom) / 2

                // Use gesture to click inside the field
                val clickPath = android.graphics.Path()
                clickPath.moveTo(clickX.toFloat(), clickY.toFloat())
                val clickGesture = android.accessibilityservice.GestureDescription.Builder()
                    .addStroke(android.accessibilityservice.GestureDescription.StrokeDescription(clickPath, 0, 50))
                    .build()

                dispatchGesture(clickGesture, null, null)
                Log.i(TAG, "🚖 ✓ Clicked inside pickup field at ($clickX, $clickY) to trigger focus")

                Log.i(TAG, "🚖 ✓ Triggered suggestion refresh for pickup")

                editTexts.forEach { try { it.recycle() } catch (e: Exception) {} }
                Thread.sleep(TimingConfig.textInputDelay + 500) // Extra wait for suggestions to load
                return 2 // Text entered successfully
            }
        }

        editTexts.forEach { try { it.recycle() } catch (e: Exception) {} }

        // Fallback: try focused input
        val focusedInput = findFocusedEditText(rootNode)
        if (focusedInput != null) {
            Log.i(TAG, "🚖 Fallback: Using focused input field")

            val arguments = android.os.Bundle()
            arguments.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, pickupAddress)

            if (focusedInput.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)) {
                Log.i(TAG, "🚖 ✓ Entered pickup address via focused field: $pickupAddress")

                // CRITICAL FIX: Trigger suggestion refresh (same as above)
                Thread.sleep(200)
                val triggerArgs = android.os.Bundle()
                triggerArgs.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "$pickupAddress ")
                focusedInput.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, triggerArgs)
                Thread.sleep(100)
                val finalArgs = android.os.Bundle()
                finalArgs.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, pickupAddress)
                focusedInput.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, finalArgs)
                Log.i(TAG, "🚖 ✓ Triggered suggestion refresh for pickup (focused field)")

                focusedInput.recycle()
                Thread.sleep(TimingConfig.textInputDelay)
                return 2 // Text entered successfully
            }
            focusedInput.recycle()
        }

        Log.w(TAG, "🚖 ✗ Could not enter pickup address")
        return 0 // Failed
    }

    /**
     * Click on Careem pickup field to open editing mode
     * Searches for "pickUp", "البيت", "Home" or the pickup row and clicks it
     */
    private fun clickCareemPickupField(rootNode: AccessibilityNodeInfo): Boolean {
        Log.i(TAG, "🚖 Looking for pickup field to click...")

        // Strategy 1: Find node with "pickUp" text and click it or its parent
        val pickupNode = findNodeWithText(rootNode, "pickUp")
        if (pickupNode != null) {
            Log.i(TAG, "🚖 Found 'pickUp' node")
            // Try clicking the node or its clickable parent
            if (clickNodeOrParent(pickupNode)) {
                Log.i(TAG, "🚖 ✓ Clicked 'pickUp' node")
                pickupNode.recycle()
                return true
            }
            // Try gesture click
            if (careemGestureClick(pickupNode)) {
                Log.i(TAG, "🚖 ✓ Gesture clicked 'pickUp' node")
                pickupNode.recycle()
                return true
            }
            pickupNode.recycle()
        }

        // Strategy 2: Find "البيت" (Home) node - this is the current pickup location
        val homeNode = findNodeWithText(rootNode, "البيت")
        if (homeNode != null) {
            Log.i(TAG, "🚖 Found 'البيت' (Home) node")
            if (clickNodeOrParent(homeNode)) {
                Log.i(TAG, "🚖 ✓ Clicked 'البيت' node")
                homeNode.recycle()
                return true
            }
            if (careemGestureClick(homeNode)) {
                Log.i(TAG, "🚖 ✓ Gesture clicked 'البيت' node")
                homeNode.recycle()
                return true
            }
            homeNode.recycle()
        }

        // Strategy 3: Find any row with pickup-related content
        val allText = getAllTextFromNode(rootNode)
        Log.i(TAG, "🚖 All text on screen: $allText")

        // Try finding a ViewGroup or Row that contains pickup info
        val clickableNodes = findClickableNodesNear(rootNode, listOf("pickUp", "Pickup", "البيت", "Home", "من أين"))
        for (node in clickableNodes) {
            Log.i(TAG, "🚖 Trying to click pickup-related node...")
            if (careemGestureClick(node)) {
                Log.i(TAG, "🚖 ✓ Gesture clicked pickup-related node")
                clickableNodes.forEach { try { it.recycle() } catch (e: Exception) {} }
                return true
            }
        }
        clickableNodes.forEach { try { it.recycle() } catch (e: Exception) {} }

        Log.w(TAG, "🚖 ✗ Could not find pickup field to click")
        return false
    }

    /**
     * Click a node or find its clickable parent and click that
     */
    private fun clickNodeOrParent(node: AccessibilityNodeInfo): Boolean {
        // Try clicking the node itself
        if (node.isClickable) {
            if (node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                return true
            }
        }

        // Try finding clickable parent
        var parent = node.parent
        var depth = 0
        while (parent != null && depth < 5) {
            if (parent.isClickable) {
                val clicked = parent.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                parent.recycle()
                if (clicked) return true
            }
            val grandparent = parent.parent
            parent.recycle()
            parent = grandparent
            depth++
        }
        parent?.recycle()

        return false
    }

    /**
     * Find clickable nodes that are near elements with specified text
     */
    private fun findClickableNodesNear(root: AccessibilityNodeInfo, targetTexts: List<String>): List<AccessibilityNodeInfo> {
        val results = mutableListOf<AccessibilityNodeInfo>()

        fun searchNode(node: AccessibilityNodeInfo) {
            val nodeText = node.text?.toString() ?: ""
            val nodeDesc = node.contentDescription?.toString() ?: ""
            val combined = "$nodeText $nodeDesc"

            // If this node contains target text, add it and clickable ancestors
            if (targetTexts.any { combined.contains(it, ignoreCase = true) }) {
                if (node.isClickable) {
                    results.add(AccessibilityNodeInfo.obtain(node))
                }
                // Also check parent
                var parent = node.parent
                if (parent != null && parent.isClickable) {
                    results.add(AccessibilityNodeInfo.obtain(parent))
                    parent.recycle()
                }
            }

            // Recurse
            for (i in 0 until node.childCount) {
                val child = node.getChild(i) ?: continue
                searchNode(child)
                child.recycle()
            }
        }

        searchNode(root)
        return results
    }

    /**
     * Find a focused EditText node in the accessibility tree
     */
    private fun findFocusedEditText(rootNode: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        // Method 1: Use system focus finder
        val focusedNode = rootNode.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        if (focusedNode != null) {
            val className = focusedNode.className?.toString() ?: ""
            if (className.contains("EditText") || focusedNode.isEditable) {
                return focusedNode
            }
            focusedNode.recycle()
        }

        // Method 2: Search recursively for focused or editable node
        fun searchFocused(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
            val className = node.className?.toString() ?: ""
            if ((className.contains("EditText") || node.isEditable) && node.isFocused) {
                return AccessibilityNodeInfo.obtain(node)
            }
            for (i in 0 until node.childCount) {
                val child = node.getChild(i) ?: continue
                val found = searchFocused(child)
                if (found != null) {
                    child.recycle()
                    return found
                }
                child.recycle()
            }
            return null
        }

        return searchFocused(rootNode)
    }

    /**
     * Select first pickup suggestion in Careem
     * Clicks directly on the first suggestion's text element to avoid large parent containers
     */
    private fun selectCareemPickupSuggestion(rootNode: AccessibilityNodeInfo): Boolean {
        Log.i(TAG, "🚖 Selecting first pickup suggestion...")

        // Strategy 1: Find the first suggestion text element and click it
        val firstSuggestion = findFirstCareemSuggestionElement(rootNode)
        if (firstSuggestion != null) {
            val rect = android.graphics.Rect()
            firstSuggestion.getBoundsInScreen(rect)
            Log.i(TAG, "🚖 Found first suggestion element, bounds=${rect}")

            // IMPORTANT: For Careem, use GESTURE CLICK as PRIMARY method
            // ACTION_CLICK reports success but doesn't actually work in Careem!
            Log.i(TAG, "🚖 Using gesture click (Careem requires gesture, not ACTION_CLICK)...")
            val clicked = careemGestureClickLong(firstSuggestion)
            firstSuggestion.recycle()

            if (clicked) {
                Log.i(TAG, "🚖 ✓ Gesture click succeeded!")
                Thread.sleep(1200) // Wait longer for Careem to process
                return true
            }

            // Fallback to ACTION_CLICK only if gesture fails
            Log.i(TAG, "🚖 Gesture failed, trying ACTION_CLICK as fallback...")
            val refoundSuggestion = findFirstCareemSuggestionElement(rootNode)
            if (refoundSuggestion != null) {
                if (clickNodeOrParent(refoundSuggestion)) {
                    Log.i(TAG, "🚖 ✓ ACTION_CLICK fallback succeeded!")
                    refoundSuggestion.recycle()
                    Thread.sleep(800)
                    return true
                }
                refoundSuggestion.recycle()
            }
        }

        // Strategy 2: Fallback to collecting suggestions WITH FILTERING
        val allSuggestions = mutableListOf<Pair<AccessibilityNodeInfo, String>>()
        collectCareemSuggestions(rootNode, allSuggestions)

        if (allSuggestions.isEmpty()) {
            collectSuggestions(rootNode, allSuggestions)
        }

        Log.i(TAG, "🚖 Found ${allSuggestions.size} total suggestions (fallback)")

        // CRITICAL: Filter using UNIQUE keywords to avoid conflicts with shared words
        val pickupKeywords = pickupAddress.split(" ").filter { it.length > 2 }
        val destKeywords = destinationAddress.split(" ").filter { it.length > 2 }

        // Find keywords UNIQUE to destination (not shared with pickup)
        val destOnlyKeywords = destKeywords.filter { destKw ->
            !pickupKeywords.any { pickKw ->
                destKw.contains(pickKw) || pickKw.contains(destKw) || destKw == pickKw
            }
        }
        // Find keywords UNIQUE to pickup (not shared with destination)
        val pickupOnlyKeywords = pickupKeywords.filter { pickKw ->
            !destKeywords.any { destKw ->
                destKw.contains(pickKw) || pickKw.contains(destKw) || destKw == pickKw
            }
        }

        Log.i(TAG, "🚖 Filtering - pickup unique: $pickupOnlyKeywords, dest unique: $destOnlyKeywords")

        // First, check if ANY suggestions match pickup keywords
        val anyMatchesPickup = allSuggestions.any { (_, text) ->
            pickupKeywords.any { text.contains(it) }
        }

        // If no suggestions match pickup keywords, relax filtering (just exclude destination-only matches)
        val relaxedFiltering = !anyMatchesPickup && pickupKeywords.isNotEmpty()
        if (relaxedFiltering) {
            Log.i(TAG, "🚖 No suggestions match pickup keywords '$pickupKeywords' - using relaxed filtering")
        }

        val filteredSuggestions = allSuggestions.filter { (_, text) ->
            // SKIP if contains destination-UNIQUE keyword
            val hasDestOnlyKeyword = destOnlyKeywords.any { text.contains(it) }
            if (hasDestOnlyKeyword) {
                Log.i(TAG, "🚖 Skipping (has dest keyword): '$text'")
                return@filter false
            }

            // If using relaxed filtering, accept any non-destination suggestion
            if (relaxedFiltering) {
                Log.i(TAG, "🚖 Including (relaxed, no dest keyword): '$text'")
                return@filter true
            }

            // INCLUDE if matches any pickup keyword
            val hasAnyPickupKeyword = pickupKeywords.isEmpty() || pickupKeywords.any { text.contains(it) }
            if (!hasAnyPickupKeyword) {
                Log.i(TAG, "🚖 Skipping (no pickup keyword): '$text'")
                return@filter false
            }

            // Prefer suggestions with pickup-UNIQUE keywords
            val hasPickupOnlyKeyword = pickupOnlyKeywords.any { text.contains(it) }
            if (hasPickupOnlyKeyword) {
                Log.i(TAG, "🚖 Including (has pickup-unique keyword): '$text'")
            }

            true
        }.toMutableList()
        // Sort by pickup-unique keyword matches (best matches first)
        // Using explicit sort to avoid inline class compilation issues
        filteredSuggestions.sortWith { a, b ->
            val countB = pickupOnlyKeywords.count { b.second.contains(it) }
            val countA = pickupOnlyKeywords.count { a.second.contains(it) }
            countB.compareTo(countA)
        }

        Log.i(TAG, "🚖 After filtering: ${filteredSuggestions.size} valid pickup suggestions")

        if (filteredSuggestions.isNotEmpty()) {
            val (bestNode, bestText) = filteredSuggestions[0]
            Log.i(TAG, "🚖 Selected pickup suggestion (fallback): '$bestText'")

            // IMPORTANT: Use GESTURE CLICK as PRIMARY for Careem
            Log.i(TAG, "🚖 Using gesture click on fallback suggestion...")
            val clicked = careemGestureClickLong(bestNode)
            allSuggestions.forEach { (node, _) -> try { node.recycle() } catch (e: Exception) {} }

            if (clicked) {
                Log.i(TAG, "🚖 ✓ Gesture click on pickup suggestion succeeded!")
                Thread.sleep(1200) // Wait longer for Careem
                return true
            }
        }

        // If no filtered suggestions, recycle all
        allSuggestions.forEach { (node, _) -> try { node.recycle() } catch (e: Exception) {} }

        // POSITION-BASED FALLBACK: Click at fixed screen positions where suggestions typically appear
        Log.w(TAG, "🚖 No filtered suggestions - trying position-based fallback")
        if (careemPositionBasedClickAttempt < 3) {
            try {
                val displayMetrics = resources.displayMetrics
                val screenWidth = displayMetrics.widthPixels
                val screenHeight = displayMetrics.heightPixels

                // Try different Y positions for suggestions
                val yPositions = listOf(0.35f, 0.42f, 0.28f)
                val targetX = (screenWidth * 0.5f).toInt()
                val targetY = (screenHeight * yPositions[careemPositionBasedClickAttempt]).toInt()

                Log.i(TAG, "🚖 Position-based click attempt ${careemPositionBasedClickAttempt + 1}/3 at ($targetX, $targetY)")

                val path = android.graphics.Path()
                path.moveTo(targetX.toFloat(), targetY.toFloat())

                val gesture = android.accessibilityservice.GestureDescription.Builder()
                    .addStroke(android.accessibilityservice.GestureDescription.StrokeDescription(path, 0, 350))
                    .build()

                dispatchGesture(gesture, null, null)
                Log.i(TAG, "🚖 ✓ Position-based gesture click dispatched (selectCareemPickupSuggestion)")

                careemPositionBasedClickAttempt++
                Thread.sleep(1200)
                return true
            } catch (e: Exception) {
                Log.e(TAG, "🚖 Position-based click failed: ${e.message}")
            }
        }

        return false
    }

    /**
     * Careem gesture click with LONGER duration for better tap recognition
     */
    private fun careemGestureClickLong(node: AccessibilityNodeInfo): Boolean {
        val rect = android.graphics.Rect()
        node.getBoundsInScreen(rect)
        val centerX = rect.centerX()
        val centerY = rect.centerY()

        Log.i(TAG, "🚖 Long gesture click at ($centerX, $centerY)")

        val path = android.graphics.Path()
        path.moveTo(centerX.toFloat(), centerY.toFloat())

        // Use LONGER duration (350ms) for better tap recognition
        val gesture = android.accessibilityservice.GestureDescription.Builder()
            .addStroke(android.accessibilityservice.GestureDescription.StrokeDescription(path, 0, 350))
            .build()

        // Use null callback to avoid anonymous class compilation issues
        val result = dispatchGesture(gesture, null, null)
        Log.i(TAG, "🚖 Long gesture click dispatched, result=$result")

        Thread.sleep(1200) // Wait longer for Careem to respond
        return result
    }

    /**
     * Careem-specific fallback to click the first suggestion using gesture
     * Used when collectCareemSuggestions returns empty but suggestions are visible on screen
     * Searches for nodes with distance indicators (كم) which indicate suggestion rows
     *
     * CRITICAL: When in pickup mode (!careemPickupPhaseComplete), skip suggestions
     * that match destination keywords - these are stale suggestions from previous search
     */
    private fun clickFirstCareemSuggestionGesture(rootNode: AccessibilityNodeInfo): Boolean {
        Log.i(TAG, "🚖 clickFirstCareemSuggestionGesture: Looking for suggestion by distance indicator...")
        Log.i(TAG, "🚖 Mode: pickupPhaseComplete=$careemPickupPhaseComplete")

        // Find nodes that contain distance indicator (suggests it's a suggestion row)
        val allText = getAllTextFromNode(rootNode)
        Log.i(TAG, "🚖 All text on screen (first 15): ${allText.take(15)}")

        // CRITICAL: When in pickup mode, prepare destination keywords to skip
        // BUG FIX: Use !careemPickupPhaseComplete instead of careemPickupFieldClicked for reliable phase detection
        val destKeywords = if (!careemPickupPhaseComplete) {
            extractDestinationKeywords(destinationAddress)
        } else {
            emptyList()
        }

        // Helper function to check if text matches destination (stale suggestion)
        fun matchesDestination(text: String): Boolean {
            if (careemPickupPhaseComplete) return false  // In destination phase, don't skip destination matches
            return destKeywords.any { keyword ->
                text.lowercase().contains(keyword.lowercase())
            }
        }

        // Look for suggestion text that appears AFTER a distance indicator
        // Careem pattern: [location icon, distance (كم), place name, full address, moreVertical...]
        var foundDistance = false
        var suggestionText: String? = null

        for (text in allText) {
            // Check if this is a distance indicator
            if (text.matches(Regex("^[\\d.,>]+\\s*كم$"))) {
                foundDistance = true
                continue
            }

            // After finding distance, next substantive text might be suggestion name
            if (foundDistance && text.isNotBlank() && text.length > 3) {
                val lower = text.lowercase()
                // Skip UI elements
                if (lower == "morevertical" || lower == "location" || lower == "eg" ||
                    lower.contains("navigation") || lower.contains("back") ||
                    text == "البيت" || text.contains("pickUp")) {
                    continue
                }

                // CRITICAL: In pickup mode, skip suggestions that match destination
                if (matchesDestination(text)) {
                    Log.i(TAG, "🚖 Skipping stale destination suggestion: '$text'")
                    foundDistance = false  // Reset to look for next suggestion
                    continue
                }

                suggestionText = text
                Log.i(TAG, "🚖 Found potential suggestion after distance: '$text'")
                break
            }
        }

        // If we found a suggestion text, try to click its node
        if (suggestionText != null) {
            val node = findNodeWithText(rootNode, suggestionText)
            if (node != null) {
                Log.i(TAG, "🚖 Found node for '$suggestionText', attempting gesture click...")
                val clicked = careemGestureClick(node)
                node.recycle()
                if (clicked) {
                    Log.i(TAG, "🚖 ✓ Gesture click on suggestion succeeded!")
                    return true
                }
            }
        }

        // Alternative: Look for any address-like text (contains common address markers)
        Log.i(TAG, "🚖 Trying alternative: Looking for address-like text...")
        for (text in allText) {
            if (text.isBlank() || text.length < 10) continue

            // Skip known UI elements and the typed search text
            val lower = text.lowercase()
            if (lower.contains("google") || lower.contains("navigation") ||
                lower.contains("menu") || lower == pickupAddress.lowercase() ||
                lower == destinationAddress.lowercase()) continue

            // CRITICAL: In pickup mode, skip addresses that match destination
            if (matchesDestination(text)) {
                Log.i(TAG, "🚖 Skipping stale destination address: '$text'")
                continue
            }

            // Check if it looks like an address (has common markers)
            val isAddress = text.contains("،") || text.contains(",") ||
                           text.contains("-") || text.contains("شارع") ||
                           text.contains("مصر") || text.contains("محافظة") ||
                           text.contains("Street") || text.contains("Egypt")

            if (isAddress) {
                val node = findNodeWithText(rootNode, text)
                if (node != null) {
                    Log.i(TAG, "🚖 Found address node: '$text', attempting gesture click...")
                    val clicked = careemGestureClick(node)
                    node.recycle()
                    if (clicked) {
                        Log.i(TAG, "🚖 ✓ Gesture click on address succeeded!")
                        return true
                    }
                }
            }
        }

        Log.w(TAG, "🚖 ✗ No suggestion found for gesture click - trying position-based fallback")

        // POSITION-BASED FALLBACK: Click at fixed screen positions where suggestions typically appear
        // Careem suggestions appear below the search field, typically at 30-50% from screen top
        if (careemPositionBasedClickAttempt < 3) {
            try {
                val displayMetrics = resources.displayMetrics
                val screenWidth = displayMetrics.widthPixels
                val screenHeight = displayMetrics.heightPixels

                // Try different Y positions for suggestions
                // Attempt 0: 35% from top (first suggestion row)
                // Attempt 1: 42% from top (second suggestion row)
                // Attempt 2: 28% from top (might be closer to search field)
                val yPositions = listOf(0.35f, 0.42f, 0.28f)
                val targetX = (screenWidth * 0.5f).toInt()
                val targetY = (screenHeight * yPositions[careemPositionBasedClickAttempt]).toInt()

                Log.i(TAG, "🚖 Position-based click attempt ${careemPositionBasedClickAttempt + 1}/3 at ($targetX, $targetY)")

                val path = android.graphics.Path()
                path.moveTo(targetX.toFloat(), targetY.toFloat())

                val gesture = android.accessibilityservice.GestureDescription.Builder()
                    .addStroke(android.accessibilityservice.GestureDescription.StrokeDescription(path, 0, 350))
                    .build()

                dispatchGesture(gesture, null, null)
                Log.i(TAG, "🚖 ✓ Position-based gesture click dispatched")

                careemPositionBasedClickAttempt++
                Thread.sleep(1200)
                return true
            } catch (e: Exception) {
                Log.e(TAG, "🚖 Position-based click failed: ${e.message}")
            }
        }

        return false
    }

    /**
     * Find the first suggestion element in Careem's pickup suggestions list
     * Returns the actual text element, not the parent container
     * IMPORTANT: Only returns suggestions that match PICKUP address, not destination
     */
    private fun findFirstCareemSuggestionElement(rootNode: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val allText = getAllTextFromNode(rootNode)

        // Extract keywords from pickup and destination to validate suggestions
        val pickupKeywords = pickupAddress.split(" ").filter { it.length > 2 }
        val destKeywords = destinationAddress.split(" ").filter { it.length > 2 }

        Log.i(TAG, "🚖 Finding pickup suggestion - pickup keywords: $pickupKeywords, dest keywords: $destKeywords")

        // Find the first element that looks like an address AND matches pickup keywords
        // Skip: pickUp, البيت, distances (كم), icons, etc.
        for (text in allText) {
            if (text.isBlank() || text.length < 5) continue

            val lower = text.lowercase()
            // Skip UI elements
            if (lower == "pickup" || lower == "dropoff" ||
                lower.contains("back") || lower.contains("arrow") ||
                lower.contains("more") || lower.contains("location") ||
                lower.contains("hospital") || lower.contains("bank") ||
                lower.contains("shops") || lower.contains("forknife") ||
                text == "eg" || text == "البيت" ||
                text.contains("استخدم موقعي الحالي") || // Skip "Use my current location"
                text.contains("موقعي الحالي")) continue // Skip current location variations

            // Skip distance indicators
            if (text.matches(Regex("^[\\d.,>]+\\s*كم$"))) continue

            // Skip plus codes
            if (text.matches(Regex("^[A-Z0-9]+\\+[A-Z0-9]+.*"))) continue

            // CRITICAL: Check if this matches destination-UNIQUE keywords
            // Find keywords that are ONLY in destination, not in pickup
            val destOnlyKeywords = destKeywords.filter { destKw ->
                !pickupKeywords.any { pickKw ->
                    destKw.contains(pickKw) || pickKw.contains(destKw) || destKw == pickKw
                }
            }
            val pickupOnlyKeywords = pickupKeywords.filter { pickKw ->
                !destKeywords.any { destKw ->
                    destKw.contains(pickKw) || pickKw.contains(destKw) || destKw == pickKw
                }
            }

            Log.d(TAG, "🚖 Unique keywords - pickup: $pickupOnlyKeywords, dest: $destOnlyKeywords")

            // Skip if text contains destination-UNIQUE keywords
            val hasDestOnlyKeyword = destOnlyKeywords.any { text.contains(it) }
            if (hasDestOnlyKeyword) {
                Log.i(TAG, "🚖 Skipping - contains destination keyword: '$text'")
                continue
            }

            // Prefer texts that contain pickup-UNIQUE keywords
            val hasPickupOnlyKeyword = pickupOnlyKeywords.any { text.contains(it) }
            val hasAnyPickupKeyword = pickupKeywords.any { text.contains(it) }

            // Skip if we have pickup keywords but text doesn't match any
            if (pickupKeywords.isNotEmpty() && !hasAnyPickupKeyword) {
                Log.d(TAG, "🚖 Skipping - no pickup keyword match: '$text'")
                continue
            }

            // This might be a valid pickup address - try to find it
            val node = findNodeWithExactText(rootNode, text)
            if (node != null) {
                val rect = android.graphics.Rect()
                node.getBoundsInScreen(rect)

                // CRITICAL: Skip EditText fields - we want suggestion rows, not input fields
                // After typing in the input field, the text appears there AND in suggestions
                // We must click the suggestion row, not the input field
                val className = node.className?.toString() ?: ""
                val isEditable = node.isEditable || className.contains("EditText")
                if (isEditable) {
                    Log.i(TAG, "🚖 Skipping EditText/editable node: '$text' at ${rect}")
                    node.recycle()
                    continue
                }

                // Validate bounds - should be reasonable size (not full screen)
                val width = rect.width()
                val height = rect.height()
                if (width > 100 && width < 1200 && height > 30 && height < 300) {
                    Log.i(TAG, "🚖 Found VALID pickup suggestion: '$text' at ${rect}")
                    return node
                }
                node.recycle()
            }
        }

        Log.i(TAG, "🚖 No valid pickup suggestion found")
        return null
    }

    /**
     * Find node with exact text match (not contains)
     */
    private fun findNodeWithExactText(root: AccessibilityNodeInfo, targetText: String): AccessibilityNodeInfo? {
        fun search(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
            val nodeText = node.text?.toString() ?: ""
            if (nodeText == targetText) {
                return AccessibilityNodeInfo.obtain(node)
            }

            for (i in 0 until node.childCount) {
                val child = node.getChild(i) ?: continue
                val found = search(child)
                if (found != null) {
                    child.recycle()
                    return found
                }
                child.recycle()
            }
            return null
        }

        return search(root)
    }

    /**
     * Ultimate fallback: Click on Careem map area to use current GPS location as pickup
     * When no valid pickup suggestions exist (all are destination-related), this uses
     * the user's current location on the map as the pickup point
     */
    private fun clickCareemMapForPickup(rootNode: AccessibilityNodeInfo): Boolean {
        Log.i(TAG, "🚖 clickCareemMapForPickup: Trying to use map location as pickup...")

        // Strategy 1: Look for "خرائط Google" (Google Maps) node and click near it
        // The map is usually in the upper portion of the screen
        val mapNode = findNodeWithText(rootNode, "خرائط Google")
        if (mapNode != null) {
            val rect = android.graphics.Rect()
            mapNode.getBoundsInScreen(rect)
            Log.i(TAG, "🚖 Found Google Maps node at: $rect")

            // Click in the CENTER of the map area (not on the text itself)
            // The map is usually below/around the "خرائط Google" attribution
            val mapCenterX = rect.centerX()
            val mapCenterY = rect.bottom + 200  // Below the attribution text

            val path = android.graphics.Path()
            path.moveTo(mapCenterX.toFloat(), mapCenterY.toFloat())

            val gesture = android.accessibilityservice.GestureDescription.Builder()
                .addStroke(android.accessibilityservice.GestureDescription.StrokeDescription(path, 0, 200))
                .build()

            val result = dispatchGesture(gesture, null, null)
            mapNode.recycle()

            if (result) {
                Log.i(TAG, "🚖 ✓ Clicked on map at ($mapCenterX, $mapCenterY) to set pickup")
                Thread.sleep(800)
                return true
            }
        }

        // Strategy 2: Look for any map-related node
        val mapTexts = listOf("Map", "map", "الخريطة", "خريطة")
        for (mapText in mapTexts) {
            val node = findNodeWithText(rootNode, mapText)
            if (node != null) {
                Log.i(TAG, "🚖 Found map element: '$mapText'")
                if (careemGestureClick(node)) {
                    Log.i(TAG, "🚖 ✓ Clicked map element")
                    node.recycle()
                    Thread.sleep(800)
                    return true
                }
                node.recycle()
            }
        }

        // Strategy 3: Click on the center-upper area of the screen (where map usually is)
        // Get screen dimensions - use a reasonable default
        val displayMetrics = resources.displayMetrics
        val screenWidth = displayMetrics.widthPixels
        val screenHeight = displayMetrics.heightPixels

        // Map is usually in upper-middle portion
        val mapX = screenWidth / 2
        val mapY = screenHeight / 3  // Upper third of screen

        Log.i(TAG, "🚖 Trying blind click on map area at ($mapX, $mapY)")

        val path = android.graphics.Path()
        path.moveTo(mapX.toFloat(), mapY.toFloat())

        val gesture = android.accessibilityservice.GestureDescription.Builder()
            .addStroke(android.accessibilityservice.GestureDescription.StrokeDescription(path, 0, 200))
            .build()

        val result = dispatchGesture(gesture, null, null)
        if (result) {
            Log.i(TAG, "🚖 ✓ Dispatched blind map click")
            Thread.sleep(800)
            return true
        }

        Log.w(TAG, "🚖 ✗ Could not click on map for pickup")
        return false
    }

    /**
     * Click Careem confirm/search button to proceed to price screen
     */
    private fun clickCareemConfirmButton(rootNode: AccessibilityNodeInfo): Boolean {
        Log.i(TAG, "🚖 Looking for confirm/search button...")

        // Common confirm button texts in Careem (Arabic and English)
        val confirmTexts = listOf(
            "تأكيد الانطلاق", // "Confirm pickup" - most important
            "بحث", "Search", "تأكيد", "Confirm", "Done", "تم",
            "بحث عن أفضل سعر", "Find best price", "احجز", "Book",
            "التالي", "Next", "متابعة", "Continue"
        )

        for (text in confirmTexts) {
            val node = findNodeWithText(rootNode, text)
            if (node != null) {
                Log.i(TAG, "🚖 Found button with text: '$text'")
                if (clickNodeOrParent(node)) {
                    Log.i(TAG, "🚖 ✓ Clicked '$text' button")
                    node.recycle()
                    Thread.sleep(800)
                    return true
                }
                if (careemGestureClick(node)) {
                    Log.i(TAG, "🚖 ✓ Gesture clicked '$text' button")
                    node.recycle()
                    return true
                }
                node.recycle()
            }
        }

        // Also look for any clickable button at the bottom of the screen
        val allText = getAllTextFromNode(rootNode)
        Log.i(TAG, "🚖 All text for confirm button search: ${allText.takeLast(10)}")

        return false
    }

    /**
     * Find all EditText nodes in the tree
     */
    private fun findAllEditTexts(node: AccessibilityNodeInfo, results: MutableList<AccessibilityNodeInfo>) {
        val className = node.className?.toString() ?: ""

        if (className.contains("EditText")) {
            results.add(AccessibilityNodeInfo.obtain(node))
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            findAllEditTexts(child, results)
            child.recycle()
        }
    }

    /**
     * Find all clickable nodes with text content
     * Returns pairs of (node, bounds) for sorting by position
     */
    private fun findClickableTextNodes(
        node: AccessibilityNodeInfo,
        results: MutableList<Pair<AccessibilityNodeInfo, android.graphics.Rect>>
    ) {
        val text = node.text?.toString() ?: ""
        val desc = node.contentDescription?.toString() ?: ""

        if ((text.isNotEmpty() || desc.isNotEmpty()) && node.isClickable) {
            val rect = android.graphics.Rect()
            node.getBoundsInScreen(rect)
            results.add(Pair(AccessibilityNodeInfo.obtain(node), rect))
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            findClickableTextNodes(child, results)
            child.recycle()
        }
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
                    Thread.sleep(TimingConfig.textInputDelay)
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
                Thread.sleep(TimingConfig.clickDelay)
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
                    Thread.sleep(TimingConfig.textInputDelay)
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
                    Thread.sleep(TimingConfig.textInputDelay)
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
                    Thread.sleep(TimingConfig.clickDelay)
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
                Thread.sleep(TimingConfig.textInputDelay)
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

            // SAFETY CHECK: Make sure we're not entering destination into pickup field
            // Check if the focused field contains pickup text (which would be wrong)
            val pickupKeywords = pickupAddress.split(" ").filter { it.length > 2 }
            val containsPickupText = pickupKeywords.isNotEmpty() && pickupKeywords.any { keyword ->
                focusedText.contains(keyword, ignoreCase = true)
            }

            if (containsPickupText && pickupAddress.isNotEmpty() && packageName == CAREEM_PACKAGE) {
                Log.w(TAG, "🤖 ⚠️ WRONG FIELD: Focused field contains pickup text! Looking for destination field...")
                focusedNode.recycle()
                // Try to find and click the correct destination field
                return findAndEnterDestinationInCareem(rootNode, textToEnter)
            }

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
                    Thread.sleep(TimingConfig.clickDelay)  // Small delay before pressing Enter

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
                    Thread.sleep(TimingConfig.textInputDelay * 2)
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

    /**
     * Careem-specific: Find the DESTINATION EditText field (not pickup) and enter text
     * This is called when we detect the pickup field was accidentally focused instead of destination
     */
    private fun findAndEnterDestinationInCareem(rootNode: AccessibilityNodeInfo, destinationText: String): Boolean {
        Log.i(TAG, "🚖 findAndEnterDestinationInCareem: Looking for destination field (avoiding pickup)...")

        // Find all EditText fields
        val editTexts = mutableListOf<AccessibilityNodeInfo>()
        findAllEditTexts(rootNode, editTexts)

        Log.i(TAG, "🚖 Found ${editTexts.size} EditText fields total")

        // Sort by Y position - destination field is usually BELOW pickup field
        val sortedEditTexts = editTexts.toMutableList()
        sortedEditTexts.sortWith { a, b ->
            val rectA = android.graphics.Rect()
            val rectB = android.graphics.Rect()
            a.getBoundsInScreen(rectA)
            b.getBoundsInScreen(rectB)
            rectA.top.compareTo(rectB.top)
        }

        // Extract pickup keywords to identify which field to skip
        val pickupKeywords = pickupAddress.split(" ").filter { it.length > 2 }

        // Log all EditTexts with their positions
        sortedEditTexts.forEachIndexed { index, et ->
            val rect = android.graphics.Rect()
            et.getBoundsInScreen(rect)
            val text = et.text?.toString() ?: ""
            Log.i(TAG, "🚖 EditText[$index]: y=${rect.top}, text='$text'")
        }

        // Look for the SECOND/LOWER EditText (destination field), or an empty one
        for (editText in sortedEditTexts) {
            val currentText = editText.text?.toString() ?: ""
            val rect = android.graphics.Rect()
            editText.getBoundsInScreen(rect)

            // Check if this field contains pickup text - if so, SKIP it
            val containsPickupText = pickupKeywords.any { keyword ->
                currentText.contains(keyword, ignoreCase = true)
            }

            if (containsPickupText) {
                Log.i(TAG, "🚖 Skipping EditText at y=${rect.top} (contains pickup text): '$currentText'")
                continue
            }

            Log.i(TAG, "🚖 Found DESTINATION EditText at y=${rect.top}: '$currentText'")

            // Focus and click this field first
            editText.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
            editText.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            Thread.sleep(100)

            // Clear existing text first
            val clearArgs = android.os.Bundle()
            clearArgs.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "")
            editText.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, clearArgs)
            Thread.sleep(100)

            // Enter the destination text
            val arguments = android.os.Bundle()
            arguments.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, destinationText)

            if (editText.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)) {
                Log.i(TAG, "🚖 ✓ Entered destination text: $destinationText")
                editTexts.forEach { try { it.recycle() } catch (e: Exception) {} }
                Thread.sleep(TimingConfig.textInputDelay)
                return true
            }
        }

        editTexts.forEach { try { it.recycle() } catch (e: Exception) {} }
        Log.w(TAG, "🚖 ✗ Could not find destination field")
        return false
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
            Thread.sleep(TimingConfig.textInputDelay)

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
     * For Careem pickup selection, uses pickupAddress instead of destinationAddress
     */
    private fun selectFirstSuggestion(rootNode: AccessibilityNodeInfo, packageName: String): Boolean {
        // For Careem, if we're selecting pickup suggestions (pickup phase not complete), use pickup address
        // BUG FIX: Use careemPickupPhaseComplete instead of careemPickupFieldClicked for reliable phase detection
        val addressToMatch = if (packageName == CAREEM_PACKAGE && !careemPickupPhaseComplete) {
            Log.i(TAG, "🚖 Using PICKUP address for suggestion matching: $pickupAddress")
            pickupAddress
        } else {
            destinationAddress
        }

        // Extract keywords from the appropriate address (remove Plus Codes and numbers)
        val destKeywords = extractDestinationKeywords(addressToMatch)
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

        // Also try Careem-specific suggestion collection
        if (suggestions.isEmpty() && packageName == CAREEM_PACKAGE) {
            collectCareemSuggestions(rootNode, suggestions)
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

        // If we found a match with score > 0, click it
        if (bestMatch != null && bestScore > 0) {
            Log.i(TAG, "✓✓ SELECTED: '$bestText' (score: $bestScore)")

            // For Careem: use gesture-only click with longer delay (ACTION_CLICK doesn't work)
            val clicked = if (packageName == CAREEM_PACKAGE) {
                careemGestureClick(bestMatch)
            } else {
                smartClick(bestMatch)
            }

            // Recycle all nodes
            suggestions.forEach { (node, _) ->
                try { node.recycle() } catch (e: Exception) {}
            }
            return clicked
        }

        // Fallback: click first suggestion if no match found
        Log.w(TAG, "⚠️ No matching suggestion found, clicking first one")
        if (suggestions.isNotEmpty()) {
            // CRITICAL FIX: When in Careem PICKUP mode, don't click suggestions that match DESTINATION
            // This prevents clicking a destination suggestion when we need a pickup suggestion
            // BUG FIX: Use !careemPickupPhaseComplete instead of careemPickupFieldClicked for reliable phase detection
            if (packageName == CAREEM_PACKAGE && !careemPickupPhaseComplete) {
                val firstSuggestionText = suggestions[0].second
                val destKeywordsCheck = extractDestinationKeywords(destinationAddress)
                val matchesDestination = destKeywordsCheck.any { keyword ->
                    firstSuggestionText.lowercase().contains(keyword.lowercase())
                }

                if (matchesDestination) {
                    Log.w(TAG, "🚖 CRITICAL: First suggestion '$firstSuggestionText' matches DESTINATION, not clicking!")
                    Log.w(TAG, "🚖 This means Careem suggestions haven't refreshed after entering pickup text")
                    suggestions.forEach { (node, _) ->
                        try { node.recycle() } catch (e: Exception) {}
                    }
                    return false  // Don't click - let caller handle fallback
                }
            }

            Log.i(TAG, "⚠️ Fallback: selecting '${suggestions[0].second}'")

            // For Careem: use gesture-only click
            val clicked = if (packageName == CAREEM_PACKAGE) {
                careemGestureClick(suggestions[0].first)
            } else {
                smartClick(suggestions[0].first)
            }

            suggestions.forEach { (node, _) ->
                try { node.recycle() } catch (e: Exception) {}
            }
            return clicked
        }

        suggestions.forEach { (node, _) ->
            try { node.recycle() } catch (e: Exception) {}
        }

        // CRITICAL FIX: For DiDi, DON'T use clickFirstMatchingSuggestion fallback
        // It clicks random elements and causes DiDi to go back to home screen
        if (packageName == DIDI_PACKAGE) {
            Log.w(TAG, "⚠️ DiDi: No suggestions found, NOT clicking random elements")
            return false
        }

        // CRITICAL FIX: For Careem, use gesture-based fallback instead of ACTION_CLICK
        // Careem doesn't respond to ACTION_CLICK, only gesture taps
        if (packageName == CAREEM_PACKAGE) {
            Log.i(TAG, "🚖 No suggestions collected, trying Careem-specific fallback...")
            return clickFirstCareemSuggestionGesture(rootNode)
        }

        // Fallback: find any clickable item (only for non-DiDi/non-Careem apps)
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
        // Using explicit sort instead of sortedByDescending to avoid inline class issues
        val sortedWords = words.toMutableList()
        sortedWords.sortWith { a, b -> b.length.compareTo(a.length) }

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

        // CRITICAL: Skip EditText/editable nodes - we want suggestion rows, not input fields
        val isEditable = node.isEditable || className.contains("EditText")
        if (isEditable) {
            return // Don't collect suggestions from EditText or recurse into them
        }

        // If this is a RecyclerView or ListView, get its children
        if (className.contains("RecyclerView") || className.contains("ListView")) {
            for (i in 0 until minOf(node.childCount, 10)) {
                val child = node.getChild(i) ?: continue
                val childClassName = child.className?.toString() ?: ""
                // Skip EditText children
                if (child.isEditable || childClassName.contains("EditText")) {
                    child.recycle()
                    continue
                }
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
     * Collect suggestions specifically for Careem's destination search results
     * Careem shows suggestions with: location name, address, and distance (e.g., "3.1 كم")
     */
    private fun collectCareemSuggestions(node: AccessibilityNodeInfo, suggestions: MutableList<Pair<AccessibilityNodeInfo, String>>) {
        val className = node.className?.toString() ?: ""
        val text = node.text?.toString() ?: ""
        val desc = node.contentDescription?.toString() ?: ""

        // CRITICAL: Skip EditText/editable nodes - we want suggestion rows, not input fields
        val isEditable = node.isEditable || className.contains("EditText")
        if (isEditable) {
            return // Don't collect suggestions from EditText or recurse into them
        }

        // Skip UI elements that are not suggestions
        val isUIElement = text.lowercase().let {
            it == "pay" || it == "careem" || it.contains("navigation") ||
            it.contains("menu") || it.contains("البيت") || it.contains("home") ||
            it.contains("morevertical") || it.contains("location") ||
            it.contains("pickUp") || it.contains("dropoff")
        }

        // Skip very short text or UI elements
        if (text.isNotBlank() && text.length > 3 && !isUIElement) {
            // Look for clickable items with location-like text
            // Careem suggestions typically have: address text with street names or place names
            val isLocationText = text.contains("شارع") || // Street (Arabic)
                                text.contains("Street") || // Street
                                text.contains("Road") || // Road
                                text.contains("Avenue") || // Avenue
                                text.contains("مصر") || // Egypt (Arabic)
                                text.contains("Egypt") || // Egypt
                                text.contains("القاهرة") || // Cairo
                                text.contains("Cairo") ||
                                text.contains("الجيزة") || // Giza
                                text.contains("Giza") ||
                                text.contains("العبور") || // Al Obour
                                text.contains("Obour") ||
                                text.contains("القليوبية") || // Qalyubia
                                text.contains("محافظة") || // Governorate (Arabic)
                                text.contains("مدينة") || // City (Arabic)
                                text.contains("-") || // Address separator
                                text.contains(",") || // Address separator
                                text.contains("،") || // Arabic comma
                                text.matches(Regex(".*\\d+.*")) || // Contains numbers (street/building)
                                text.length > 20 // Long text is likely an address

            // Also detect distance indicators (كم = km)
            val isDistanceIndicator = text.matches(Regex(".*\\d+\\.?\\d*\\s*كم.*"))

            // If it's a location text and the node or parent is clickable
            if (isLocationText && !isDistanceIndicator) {
                // Try to find a clickable parent (suggestions are usually in clickable rows)
                var clickableNode: AccessibilityNodeInfo? = null
                var current: AccessibilityNodeInfo? = node

                // Check if current node is clickable
                if (node.isClickable) {
                    clickableNode = node
                } else {
                    // Search up to 4 levels for a clickable parent
                    for (level in 1..4) {
                        val parent = current?.parent
                        if (parent != null) {
                            if (parent.isClickable) {
                                // Validate the parent's bounds - it shouldn't be too large
                                // A single suggestion row is typically 60-150px tall
                                val parentRect = android.graphics.Rect()
                                parent.getBoundsInScreen(parentRect)
                                val parentHeight = parentRect.height()

                                if (parentHeight <= 200) {
                                    // Reasonable size for a suggestion row
                                    clickableNode = parent
                                    break
                                } else {
                                    // Too large (probably a container holding multiple suggestions)
                                    // Keep searching for a smaller clickable parent
                                    Log.d(TAG, "🚖 Skipping large container (height=$parentHeight) at level $level")
                                    current = parent
                                }
                            } else {
                                current = parent
                            }
                        } else {
                            break
                        }
                    }
                }

                // IMPORTANT: If no suitable clickable parent found, use the text node itself
                // Gesture clicks work on non-clickable nodes too - they just tap the coordinates
                val nodeToUse = clickableNode ?: node
                val usingTextNode = clickableNode == null

                // Get all text from the node (or combine with siblings if using text node)
                val fullText = if (usingTextNode) {
                    // For text node, try to get text from parent to include distance + name + address
                    val parentNode = node.parent
                    if (parentNode != null) {
                        val parentText = getNodeText(parentNode)
                        parentNode.recycle()
                        if (parentText.isNotBlank() && parentText.length > text.length) {
                            parentText
                        } else {
                            text
                        }
                    } else {
                        text
                    }
                } else {
                    getNodeText(nodeToUse)
                }

                if (fullText.isNotBlank() && fullText.length > 5) {
                    // Avoid duplicates
                    val alreadyAdded = suggestions.any { it.second == fullText }
                    if (!alreadyAdded) {
                        suggestions.add(Pair(AccessibilityNodeInfo.obtain(nodeToUse), fullText))
                        if (usingTextNode) {
                            Log.i(TAG, "🚖 Careem suggestion (using text node): '$fullText'")
                        } else {
                            Log.i(TAG, "🚖 Careem suggestion: '$fullText'")
                        }
                    }
                }
            }
        }

        // Recurse into children
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            collectCareemSuggestions(child, suggestions)
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

            // Check cooldown to prevent clicking too fast (2s for DiDi stability)
            val currentTime = System.currentTimeMillis()
            if (currentTime - lastDiDiSelectAddressClickTime < 2000) {
                Log.i(TAG, "📍 Waiting for cooldown (${2000 - (currentTime - lastDiDiSelectAddressClickTime)}ms remaining)")
                return false // Don't handle - let automation continue waiting
            }

            Log.i(TAG, "📍 Selecting first address card...")

            // Find the first clickable address card
            val addressCard = findFirstDiDiAddressCard(rootNode)
            if (addressCard != null) {
                val cardText = getNodeText(addressCard)
                Log.i(TAG, "📍 Found address card: '${cardText.take(50)}...'")

                // Try multiple click strategies (DiDi doesn't respond to gesture tap reliably)
                var clicked = false

                // Strategy 1: Try ACTION_CLICK on the node itself
                if (addressCard.isClickable) {
                    clicked = addressCard.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    if (clicked) {
                        Log.i(TAG, "📍 ✓ ACTION_CLICK on address card succeeded")
                    }
                }

                // Strategy 2: Try ACTION_CLICK on ancestors (even if not marked clickable)
                if (!clicked) {
                    var current: AccessibilityNodeInfo? = addressCard.parent
                    for (level in 1..5) {
                        if (current == null) break

                        Log.i(TAG, "📍 Trying ACTION_CLICK on ancestor level $level...")
                        if (current.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                            Log.i(TAG, "📍 ✓ ACTION_CLICK on ancestor level $level succeeded!")
                            clicked = true
                            current.recycle()
                            break
                        }

                        val next = current.parent
                        current.recycle()
                        current = next
                    }
                }

                // Strategy 3: Fallback to gesture tap
                if (!clicked) {
                    Log.i(TAG, "📍 Trying gesture tap as fallback...")
                    clicked = smartClick(addressCard)
                    if (clicked) {
                        Log.i(TAG, "📍 ✓ Gesture tap succeeded (maybe)")
                    }
                }

                addressCard.recycle()

                if (clicked) {
                    Log.i(TAG, "📍 ✓ Successfully clicked address card")
                    // Record click time for cooldown
                    lastDiDiSelectAddressClickTime = currentTime
                    return true
                }
            } else {
                Log.w(TAG, "📍 No address card found on Select Address screen - trying position tap fallback...")

                // FALLBACK: Tap at a fixed position where first suggestion usually appears
                // First suggestion is typically at about 45-55% from top of screen
                try {
                    val displayMetrics = resources.displayMetrics
                    val screenWidth = displayMetrics.widthPixels
                    val screenHeight = displayMetrics.heightPixels
                    val tapX = screenWidth / 2f
                    val tapY = screenHeight * 0.50f  // 50% from top

                    Log.i(TAG, "📍 Fallback: Tapping at ($tapX, $tapY) - center, 50% down")
                    clickAtPositionWithDuration(tapX, tapY, 150)
                    lastDiDiSelectAddressClickTime = currentTime
                    Thread.sleep(500)
                    return true
                } catch (e: Exception) {
                    Log.e(TAG, "📍 Fallback tap failed: ${e.message}")
                }
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
            "Back", "Add stop", "Pickup point", "Home", "Work", "Favou"
        )
        // Don't skip if text is long (might be address container)
        val shouldSkip = skipTexts.any { combinedText.contains(it, ignoreCase = true) } &&
                         combinedText.length < 80

        if (!shouldSkip && combinedText.length > 10) {
            // Check if it looks like an address card
            // Address cards have: location name AND (distance OR governorate OR postal code)
            val hasDistance = combinedText.contains("km") || combinedText.contains("كم") ||
                              combinedText.contains(" m ") || combinedText.contains("متر")
            val hasLocation = combinedText.contains("محافظة") || combinedText.contains("مصر") ||
                              combinedText.contains("العبور") || combinedText.contains("القاهرة") ||
                              combinedText.contains("الجيزة") || combinedText.contains("Egypt") ||
                              combinedText.contains("شارع") || combinedText.contains("الحي") ||
                              combinedText.contains("Governorate") || combinedText.contains("Obour") ||
                              combinedText.contains("Cairo") || combinedText.contains("Giza") ||
                              combinedText.contains("Qalyubia") || combinedText.contains("القليوبية") ||
                              combinedText.contains("Carrefour") || combinedText.contains("كارفور") ||
                              combinedText.contains("Nursery") || combinedText.contains("Stars")
            // Check for postal code pattern (6-7 digits)
            val hasPostalCode = Regex("\\d{6,7}").containsMatchIn(combinedText)
            // Check for Plus Code pattern (e.g., 5FP7+XX3)
            val hasPlusCode = Regex("[A-Z0-9]{4,}\\+[A-Z0-9]{2,}").containsMatchIn(combinedText)

            // Must have EITHER distance OR location indicator OR postal code OR plus code
            if (hasDistance || hasLocation || hasPostalCode || hasPlusCode) {
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
            if (inDriverDoneClickCount >= TimingConfig.INDRIVER_MAX_DONE_CLICKS) {
                Log.i(TAG, "🗺️ Already clicked 'تم' $inDriverDoneClickCount times (max=${TimingConfig.INDRIVER_MAX_DONE_CLICKS}) - NOT clicking again!")
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
                    Thread.sleep(TimingConfig.animationWait)
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
                        Thread.sleep(TimingConfig.animationWait)
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
                Thread.sleep(TimingConfig.animationWait)
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

            // Use null callback to avoid anonymous class compilation issues
            val result = dispatchGesture(gestureBuilder.build(), null, null)
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
        Thread.sleep(TimingConfig.clickDelay)

        // Strategy 4: Shell tap - DISABLED (requires root, causes SecurityException crashes)
        // shellTap(centerX, centerY)

        // Return true optimistically - we tried everything
        Log.i(TAG, "🎯 Attempted all click strategies")
        return true
    }

    /**
     * Special click for Careem suggestions - uses ONLY gesture tap
     * ACTION_CLICK doesn't work on Careem suggestion rows
     *
     * ENHANCED: Uses longer gesture duration (350ms) and clicks at left side
     * where the icon/interactive area typically is, not just center
     */
    private fun careemGestureClick(node: AccessibilityNodeInfo): Boolean {
        val rect = android.graphics.Rect()
        node.getBoundsInScreen(rect)
        val centerX = rect.centerX()
        val centerY = rect.centerY()

        // Click at the left side of the suggestion (where icon usually is)
        // This is often more reliable than center for Careem's Flutter UI
        val leftX = rect.left + (rect.width() * 0.15).toInt()  // 15% from left edge
        val rightX = rect.left + (rect.width() * 0.85).toInt() // 85% from left (for fallback)

        Log.i(TAG, "🚖 Careem gesture click at ($centerX, $centerY), bounds=$rect, size=${rect.width()}x${rect.height()}")

        // Validate coordinates
        if (centerX <= 0 || centerY <= 0 || rect.width() < 50 || rect.height() < 20) {
            Log.w(TAG, "🚖 Invalid bounds for Careem click: too small or invalid")
            return false
        }

        // Warn if bounds are suspiciously large (might be clicking on container)
        if (rect.height() > 250) {
            Log.w(TAG, "🚖 WARNING: Bounds height ${rect.height()} is large - might be clicking container instead of suggestion row")
        }

        // Determine click position based on attempt count
        // Alternate between: left side (icon area), center, right side
        val attemptPositions = listOf(
            Pair(leftX, centerY),   // Try left side first (icon area)
            Pair(centerX, centerY), // Then center
            Pair(rightX, centerY),  // Then right side
            Pair(centerX, rect.top + (rect.height() * 0.3).toInt()), // Upper area
            Pair(centerX, rect.top + (rect.height() * 0.7).toInt())  // Lower area
        )

        val positionIndex = careemGestureClickAttempt % attemptPositions.size
        val (clickX, clickY) = attemptPositions[positionIndex]
        careemGestureClickAttempt++

        Log.i(TAG, "🚖 Click position #$positionIndex: ($clickX, $clickY) [attempt $careemGestureClickAttempt]")

        // Use gesture tap directly (ACTION_CLICK doesn't work on Careem)
        try {
            val path = android.graphics.Path()
            path.moveTo(clickX.toFloat(), clickY.toFloat())

            // Use 350ms duration - longer tap for better recognition by Careem's Flutter gestures
            val gesture = android.accessibilityservice.GestureDescription.Builder()
                .addStroke(android.accessibilityservice.GestureDescription.StrokeDescription(path, 0, 350))
                .build()

            // Use null callback to avoid anonymous class compilation issues
            dispatchGesture(gesture, null, null)
            Log.i(TAG, "🚖 Careem gesture tap dispatched at ($clickX, $clickY) with 350ms duration")

            // Wait longer for Careem to process the tap
            Thread.sleep(1000)
            Log.i(TAG, "🚖 Careem click attempted, waiting for screen change...")
            return true
        } catch (e: Exception) {
            Log.e(TAG, "🚖 Careem gesture click failed: ${e.message}")
            return false
        }
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
                Thread.sleep(TimingConfig.animationWait) // Wait after click
                return true
            }
        }

        // Try clicking parent if it's clickable
        node.parent?.let { parent ->
            if (parent.isClickable) {
                Log.i(TAG, "🎯 Parent is clickable, trying ACTION_CLICK on parent...")
                if (parent.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                    Log.i(TAG, "🎯 ✓ Gentle click SUCCESS via parent")
                    Thread.sleep(TimingConfig.animationWait)
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
                Thread.sleep(TimingConfig.textInputDelay * 2)
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

                // CRITICAL: Skip discount values (negative prices like "-EGP 30.00")
                val trimmedText = text.trim()
                if (trimmedText.startsWith("-") || trimmedText.startsWith("−")) {
                    Log.d(TAG, "⏭️ Skipping discount/negative value: '$text'")
                    continue
                }

                // CRITICAL: Skip address-like text patterns
                // These are suggestion list items containing numbers like "33 - 33 Bank Masr..."
                // Pattern: Contains multiple dashes, country/city names, or distance indicators
                val textLower = text.lowercase()
                val isAddressPattern = (
                    // Contains "- " patterns typical of addresses (building number - street - city)
                    (text.count { it == '-' } >= 2 && (
                        textLower.contains("مصر") ||    // Egypt
                        textLower.contains("egypt") ||
                        textLower.contains("cairo") ||
                        textLower.contains("القاهرة") || // Cairo
                        textLower.contains("الجيزة") ||  // Giza
                        textLower.contains("road") ||
                        textLower.contains("street") ||
                        textLower.contains("شارع")       // Street
                    )) ||
                    // Distance-based suggestion (e.g., "2.5 كم")
                    text.matches(Regex(".*\\d+\\.?\\d*\\s*كم.*")) ||
                    // Plus code patterns (e.g., "5FHH+PQ4")
                    text.matches(Regex(".*[A-Z0-9]{4,}\\+[A-Z0-9]+.*"))
                )

                if (isAddressPattern) {
                    Log.d(TAG, "⏭️ Skipping address pattern: '$text'")
                    continue
                }

                var price = extractPrice(text)

                // Special handling for Careem format: "ج.م.‏ XX.XX"
                // The RTL mark and space may not be matched by standard patterns
                if (price == null && currentPackage == CAREEM_PACKAGE && text.contains("ج.م")) {
                    // Extract just the numeric part from Careem price format
                    val careemPricePattern = Pattern.compile("(\\d+[.,]\\d{2})")
                    val matcher = careemPricePattern.matcher(text)
                    if (matcher.find()) {
                        price = matcher.group(1)?.replace(",", ".")?.toDoubleOrNull()
                        if (price != null) {
                            Log.i(TAG, "🚖 Careem special extraction: $price from '$text'")
                        }
                    }
                }

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
                // Skip discount values (negative prices like "-EGP 30.00")
                val trimmedText = text.trim()
                if (trimmedText.startsWith("-") || trimmedText.startsWith("−")) {
                    continue
                }

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

        // Vehicle type keywords - Scooter/Moto are 2-wheelers (excluded from car prices)
        val vehicleTypes = listOf(
            "Wait & Save" to listOf("wait & save", "wait and save", "wait&save", "wait save", "انتظر ووفر"),
            "UberX Saver" to listOf("uberx saver", "saver", "موفر"),
            "UberX" to listOf("uberx", "uber x"),
            "Comfort" to listOf("comfort", "كمفورت"),
            "Scooter" to listOf("scooter", "سكوتر"),
            "Uber Moto" to listOf("moto", "موتو"),
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
            // Using explicit sort to avoid inline class compilation issues
            val pricesSorted = pricesWithIndex.toMutableList()
            pricesSorted.sortWith { a, b -> a.first.compareTo(b.first) }
            val sortedPrices = pricesSorted.map { it.second }.distinct()

            val typesSorted = vehicleTypePositions.toMutableList()
            typesSorted.sortWith { a, b -> a.first.compareTo(b.first) }
            val sortedTypes = typesSorted.map { it.second }.distinct()

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

            // Keywords that identify MOTORCYCLE/SCOOTER options (to be excluded)
            val motorcycleKeywords = listOf("moto", "scooter", "سكوتر", "موتو")

            // Filter vehiclePrices to get CAR-ONLY prices (exclude motorcycles BY NAME)
            val carPrices = vehiclePrices.filterKeys { typeName ->
                val typeNameLower = typeName.lowercase()
                motorcycleKeywords.none { keyword -> typeNameLower.contains(keyword) }
            }

            // Log what we're filtering
            val motorcyclePrices = vehiclePrices.filterKeys { typeName ->
                val typeNameLower = typeName.lowercase()
                motorcycleKeywords.any { keyword -> typeNameLower.contains(keyword) }
            }
            if (motorcyclePrices.isNotEmpty()) {
                Log.i(TAG, "🏍️ Excluded motorcycle prices: $motorcyclePrices")
            }
            Log.i(TAG, "🚗 Car prices (after excluding motorcycles): $carPrices")

            // Select best price from CAR options only
            val bestPrice = when {
                carPrices.isNotEmpty() -> {
                    // Use the minimum car price
                    carPrices.values.minOrNull() ?: uniquePrices.minOrNull() ?: 0.0
                }
                vehiclePrices.isEmpty() -> {
                    // No vehicle types detected - use all prices (likely no motorcycles on screen)
                    Log.w(TAG, "⚠️ No vehicle types detected, using all prices")
                    uniquePrices.minOrNull() ?: 0.0
                }
                else -> {
                    // All detected vehicles are motorcycles? Fallback to all prices
                    Log.w(TAG, "⚠️ All detected vehicles are motorcycles, using all prices")
                    uniquePrices.minOrNull() ?: 0.0
                }
            }

            // Log all found prices
            Log.i(TAG, "🚗 Uber prices: all=$uniquePrices, carTypes=$carPrices, BEST=$bestPrice")

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
            // Skip discount values (negative prices like "-EGP 30.00")
            val trimmedText = text.trim()
            if (trimmedText.startsWith("-") || trimmedText.startsWith("−")) {
                continue
            }

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
     * Normalize text by removing invisible Unicode characters that can break string matching.
     * These characters are common in Arabic text and RTL layouts.
     */
    private fun normalizeText(text: String): String {
        return text
            .replace("\u200B", "")   // Zero-width space
            .replace("\u200C", "")   // Zero-width non-joiner
            .replace("\u200D", "")   // Zero-width joiner
            .replace("\u200E", "")   // Left-to-right mark
            .replace("\u200F", "")   // Right-to-left mark
            .replace("\u202A", "")   // Left-to-right embedding
            .replace("\u202B", "")   // Right-to-left embedding
            .replace("\u202C", "")   // Pop directional formatting
            .replace("\u202D", "")   // Left-to-right override
            .replace("\u202E", "")   // Right-to-left override
            .replace("\u2060", "")   // Word joiner
            .replace("\uFEFF", "")   // Byte order mark
            .trim()
    }

    /**
     * Check if text contains substring with normalization applied to both sides.
     * This handles cases where invisible Unicode characters break string matching.
     */
    private fun normalizedContains(text: String, substring: String): Boolean {
        return normalizeText(text).contains(normalizeText(substring), ignoreCase = true)
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
     * Recursively find a node containing specific text (in text or contentDescription)
     * Returns the node that can be clicked, or null if not found
     */
    private fun findNodeWithText(node: AccessibilityNodeInfo, targetText: String): AccessibilityNodeInfo? {
        // Check this node's text
        val nodeText = node.text?.toString()?.trim() ?: ""
        val nodeDesc = node.contentDescription?.toString()?.trim() ?: ""

        if (nodeText.contains(targetText) || nodeDesc.contains(targetText)) {
            Log.i(TAG, "🔍 Found node with '$targetText': text='$nodeText', desc='$nodeDesc', clickable=${node.isClickable}")
            // Return a copy of this node (don't recycle it, caller will handle)
            return AccessibilityNodeInfo.obtain(node)
        }

        // Recursively search children
        for (i in 0 until node.childCount) {
            try {
                val child = node.getChild(i)
                if (child != null) {
                    val found = findNodeWithText(child, targetText)
                    if (found != null) {
                        child.recycle()
                        return found
                    }
                    child.recycle()
                }
            } catch (e: Exception) {
                // Skip problematic nodes
            }
        }

        return null
    }

    /**
     * Find a node containing specific text using NORMALIZED comparison
     * This handles invisible Unicode characters (RTL marks, zero-width spaces) that
     * Android's findAccessibilityNodeInfosByText doesn't handle properly
     */
    private fun findNodeWithNormalizedText(node: AccessibilityNodeInfo, targetText: String): AccessibilityNodeInfo? {
        // Check this node's text using normalized comparison
        val nodeText = node.text?.toString()?.trim() ?: ""
        val nodeDesc = node.contentDescription?.toString()?.trim() ?: ""

        if (normalizedContains(nodeText, targetText) || normalizedContains(nodeDesc, targetText)) {
            Log.i(TAG, "🔍 Found node with normalized '$targetText': text='$nodeText', desc='$nodeDesc', clickable=${node.isClickable}")
            // Return a copy of this node (don't recycle it, caller will handle)
            return AccessibilityNodeInfo.obtain(node)
        }

        // Recursively search children
        for (i in 0 until node.childCount) {
            try {
                val child = node.getChild(i)
                if (child != null) {
                    val found = findNodeWithNormalizedText(child, targetText)
                    if (found != null) {
                        child.recycle()
                        return found
                    }
                    child.recycle()
                }
            } catch (e: Exception) {
                // Skip problematic nodes
            }
        }

        return null
    }

    /**
     * Find ALL nodes containing specific text using NORMALIZED comparison
     * Returns a list of all matching nodes (caller must recycle them)
     */
    private fun findAllNodesWithNormalizedText(node: AccessibilityNodeInfo, targetText: String): MutableList<AccessibilityNodeInfo> {
        val results = mutableListOf<AccessibilityNodeInfo>()
        findAllNodesWithNormalizedTextRecursive(node, targetText, results)
        return results
    }

    private fun findAllNodesWithNormalizedTextRecursive(
        node: AccessibilityNodeInfo,
        targetText: String,
        results: MutableList<AccessibilityNodeInfo>
    ) {
        // Check this node's text using normalized comparison
        val nodeText = node.text?.toString()?.trim() ?: ""
        val nodeDesc = node.contentDescription?.toString()?.trim() ?: ""

        if (normalizedContains(nodeText, targetText) || normalizedContains(nodeDesc, targetText)) {
            Log.i(TAG, "🔍 Found node with normalized '$targetText': text='$nodeText', desc='$nodeDesc', clickable=${node.isClickable}")
            results.add(AccessibilityNodeInfo.obtain(node))
        }

        // Recursively search children
        for (i in 0 until node.childCount) {
            try {
                val child = node.getChild(i)
                if (child != null) {
                    findAllNodesWithNormalizedTextRecursive(child, targetText, results)
                    child.recycle()
                }
            } catch (e: Exception) {
                // Skip problematic nodes
            }
        }
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
        // CRITICAL: Block price updates for Careem during address selection screens
        // This prevents reading address numbers (like "389" from "بناء ٣٨٩") as prices
        if (priceInfo.packageName == CAREEM_PACKAGE) {
            val blockedStates = listOf(
                AutomationState.FINDING_DESTINATION_FIELD,
                AutomationState.ENTERING_DESTINATION,
                AutomationState.WAITING_FOR_SUGGESTIONS,
                AutomationState.SELECTING_SUGGESTION
            )
            if (automationState in blockedStates) {
                Log.d(TAG, "🚖 Blocked price update for Careem during ${automationState.name} (price=${priceInfo.price})")
                return
            }
            // Also block if pickup hasn't been entered yet
            if (!careemPickupEntered) {
                Log.d(TAG, "🚖 Blocked price update for Careem - pickup not entered yet (price=${priceInfo.price})")
                return
            }
        }

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
