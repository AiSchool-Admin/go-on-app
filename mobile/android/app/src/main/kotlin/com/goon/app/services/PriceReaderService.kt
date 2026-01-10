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
        val rawTexts: List<String> = emptyList()
    )

    private var isServiceActive = false
    private var lastProcessedTime = 0L
    private val processDebounce = 100L // REDUCED for faster response
    private val handler = Handler(Looper.getMainLooper())
    private var lastInDriverToastTime = 0L
    private val toastDebounce = 3000L // Show toast every 3 seconds max

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
        automationState = AutomationState.WAITING_FOR_APP
        automationStep = 0
        automationRetries = 0
        monitoringPackage = packageName
        isActiveMonitoring = true

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
                    val entered = enterDestinationText(rootNode, packageName)
                    if (entered) {
                        Log.i(TAG, "🤖 ✓ Entered destination, transitioning to WAITING_FOR_SUGGESTIONS...")
                        automationState = AutomationState.WAITING_FOR_SUGGESTIONS
                        automationRetries = 0
                        automationStep = 0
                    } else {
                        Log.w(TAG, "🤖 ✗ Failed to enter destination text")
                        automationRetries++
                        if (automationRetries > 3) {
                            // Skip to suggestion selection anyway
                            Log.w(TAG, "🤖 Skipping to WAITING_FOR_SUGGESTIONS despite enter failure")
                            automationState = AutomationState.WAITING_FOR_SUGGESTIONS
                            automationRetries = 0
                        }
                    }
                }

                AutomationState.WAITING_FOR_SUGGESTIONS -> {
                    // Wait a moment then try to select
                    automationStep++
                    Log.i(TAG, "🤖 Waiting for suggestions (step $automationStep/3)...")

                    // Check for intermediate screens (InDriver might show map confirmation here)
                    if (packageName == INDRIVER_PACKAGE) {
                        if (handleInDriverIntermediateScreens(rootNode)) {
                            Log.i(TAG, "🤖 📋 Handled InDriver intermediate screen during WAITING_FOR_SUGGESTIONS")
                            return
                        }
                    }

                    if (automationStep > 3) { // Wait ~3.6 seconds for suggestions
                        Log.i(TAG, "🤖 Transitioning to SELECTING_SUGGESTION...")
                        automationState = AutomationState.SELECTING_SUGGESTION
                        automationStep = 0
                    }
                }

                AutomationState.SELECTING_SUGGESTION -> {
                    Log.i(TAG, "🤖 Selecting first suggestion...")

                    // FIRST: Check if we're on the map confirmation screen (InDriver shows this AFTER suggestion)
                    if (packageName == INDRIVER_PACKAGE) {
                        if (handleInDriverIntermediateScreens(rootNode)) {
                            Log.i(TAG, "🤖 📋 Handled InDriver intermediate screen during SELECTING_SUGGESTION")
                            // Stay in this state for next iteration
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
                    Log.i(TAG, "🤖 Waiting for price (step $automationStep/10)...")

                    // First, check for intermediate screens (like airline selection for airport)
                    Log.i(TAG, "🤖 Checking for intermediate screens...")
                    if (handleIntermediateScreens(rootNode, packageName)) {
                        Log.i(TAG, "🤖 📋 Handled intermediate screen, continuing...")
                        // Don't increment step counter, wait for next iteration
                        return
                    }
                    Log.i(TAG, "🤖 No intermediate screen handled, proceeding to price scan...")

                    val priceInfo = performAggressiveScan(packageName)
                    if (priceInfo != null && priceInfo.price > 0) {
                        Log.i(TAG, "🤖 ✓✓✓ PRICE CAPTURED: ${priceInfo.price} EGP")
                        automationState = AutomationState.PRICE_CAPTURED
                        // Notify Flutter
                        notifyPriceCaptured(priceInfo)
                    } else {
                        automationStep++
                        if (automationStep > 10) { // Wait ~12 seconds for price
                            Log.e(TAG, "🤖 ✗✗✗ FAILED: Timeout waiting for price")
                            automationState = AutomationState.FAILED
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
     */
    private fun findAndClickByPartialText(node: AccessibilityNodeInfo, keywords: List<String>): Boolean {
        val text = node.text?.toString()?.lowercase() ?: ""
        val desc = node.contentDescription?.toString()?.lowercase() ?: ""
        val hint = (if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O)
            node.hintText?.toString()?.lowercase() else null) ?: ""

        val combined = "$text $desc $hint"

        for (keyword in keywords) {
            if (combined.contains(keyword.lowercase())) {
                Log.i(TAG, "🚗 Partial match found for '$keyword' in '$combined'")

                // Try to click
                if (node.isClickable && node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                    return true
                }

                // Try parent
                val parent = node.parent
                if (parent != null && parent.isClickable) {
                    if (parent.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
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
     */
    private fun enterDestinationText(rootNode: AccessibilityNodeInfo, packageName: String): Boolean {
        Log.i(TAG, "🤖 enterDestinationText: Looking for focused input...")

        val focusedNode = rootNode.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        if (focusedNode != null) {
            val focusedClass = focusedNode.className?.toString()?.substringAfterLast(".") ?: ""
            val focusedText = focusedNode.text?.toString() ?: ""
            Log.i(TAG, "🤖 Found focused node: [$focusedClass] text='$focusedText'")

            // Clear existing text and set new text
            val args = android.os.Bundle()
            args.putCharSequence(
                AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                destinationAddress
            )
            val result = focusedNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
            focusedNode.recycle()

            if (result) {
                Log.i(TAG, "🤖 ✓ Successfully entered destination: $destinationAddress")
                return true
            } else {
                Log.w(TAG, "🤖 ✗ ACTION_SET_TEXT failed on focused node")
            }
        } else {
            Log.w(TAG, "🤖 No focused input node found")
        }

        // Fallback: find any editable field and enter text
        Log.i(TAG, "🤖 Fallback: searching for any EditText field...")
        return enterTextIntoAnyEditText(rootNode, destinationAddress)
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
     * Select the first search suggestion
     */
    private fun selectFirstSuggestion(rootNode: AccessibilityNodeInfo, packageName: String): Boolean {
        // Look for suggestion list items
        val suggestionClasses = listOf(
            "android.widget.TextView",
            "android.widget.LinearLayout",
            "android.widget.RelativeLayout",
            "android.widget.FrameLayout"
        )

        // Find RecyclerView or ListView containing suggestions
        val listNode = findSuggestionList(rootNode)
        if (listNode != null) {
            // Get first visible item
            for (i in 0 until minOf(listNode.childCount, 3)) {
                val child = listNode.getChild(i) ?: continue
                if (child.isClickable) {
                    child.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    Log.i(TAG, "Clicked first suggestion from list")
                    child.recycle()
                    listNode.recycle()
                    return true
                }
                // Try clicking child's child
                for (j in 0 until child.childCount) {
                    val grandChild = child.getChild(j) ?: continue
                    if (grandChild.isClickable) {
                        grandChild.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                        grandChild.recycle()
                        child.recycle()
                        listNode.recycle()
                        Log.i(TAG, "Clicked suggestion grandchild")
                        return true
                    }
                    grandChild.recycle()
                }
                child.recycle()
            }
            listNode.recycle()
        }

        // Fallback: find any clickable item that contains part of destination
        return clickFirstMatchingSuggestion(rootNode)
    }

    private fun findSuggestionList(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val className = node.className?.toString() ?: ""
        if (className.contains("RecyclerView") || className.contains("ListView")) {
            return AccessibilityNodeInfo.obtain(node)
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = findSuggestionList(child)
            if (found != null) {
                child.recycle()
                return found
            }
            child.recycle()
        }

        return null
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
     * - "Which airline?" for airport destinations
     * - Other promotional/info dialogs
     */
    private fun handleDiDiIntermediateScreens(rootNode: AccessibilityNodeInfo): Boolean {
        val allText = getAllTextFromNode(rootNode)
        val allTextLower = allText.map { it.lowercase() }

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
     * CRITICAL: Also handles the MAP CONFIRMATION screen with "تم" (Done) button
     */
    private fun handleInDriverIntermediateScreens(rootNode: AccessibilityNodeInfo): Boolean {
        Log.i(TAG, "🗺️ handleInDriverIntermediateScreens called")

        val allText = getAllTextFromNode(rootNode)
        val allTextLower = allText.map { it.lowercase() }

        Log.i(TAG, "🗺️ Checking for 'تم' button in ${allText.size} texts")

        // ============================================================
        // CRITICAL: Check for MAP CONFIRMATION screen with "تم" button
        // This appears after selecting a destination on InDriver
        // ============================================================
        val hasTamButton = allText.any { it == "تم" }
        val hasDoneButton = allTextLower.any { it == "done" || it == "confirm" || it == "تأكيد" }
        val hasMapConfirmation = hasTamButton || hasDoneButton

        Log.i(TAG, "🗺️ hasTamButton=$hasTamButton, hasDoneButton=$hasDoneButton, hasMapConfirmation=$hasMapConfirmation")

        // Also check if we see Google maps elements (indicates map confirmation screen)
        val hasGoogleMap = allTextLower.any { it.contains("google") }

        if (hasMapConfirmation) {
            Log.i(TAG, "🗺️ Detected InDriver MAP CONFIRMATION screen - looking for 'تم' button")

            // InDriver has anti-automation protection that blocks clicks
            // Show a toast to prompt user to manually tap the button
            val currentTime = System.currentTimeMillis()
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

            // Still try accessibility click in case it works (it might work on some devices)
            val doneTexts = listOf("تم", "Done", "Confirm", "تأكيد")
            for (doneText in doneTexts) {
                val nodes = rootNode.findAccessibilityNodeInfosByText(doneText)
                for (node in nodes) {
                    val nodeText = node.text?.toString() ?: ""
                    Log.i(TAG, "🗺️ Found '$doneText' button, attempting click...")

                    // Try simple click - may work on some devices
                    node.performAction(AccessibilityNodeInfo.ACTION_CLICK)

                    // Try clicking parent (button container)
                    node.parent?.let { parent ->
                        if (parent.isClickable) {
                            parent.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                        }
                        parent.recycle()
                    }

                    node.recycle()
                }
            }

            return true // Let next iteration check if user tapped and screen changed
        }

        // Check for permission or promo dialogs
        val hasDialog = allTextLower.any {
            it.contains("allow") ||
            it.contains("permit") ||
            it.contains("ok") ||
            it.contains("got it") ||
            it.contains("continue") ||
            it.contains("skip") ||
            it.contains("موافق") ||
            it.contains("تخطي") ||
            it.contains("متابعة") ||
            it.contains("السماح")
        }

        if (hasDialog) {
            Log.i(TAG, "📋 Detected InDriver dialog, trying to dismiss")
            val dismissTexts = listOf(
                "OK", "Got it", "Continue", "Skip", "Allow", "Accept",
                "موافق", "تخطي", "متابعة", "السماح", "قبول", "حسناً"
            )
            for (dismissText in dismissTexts) {
                val nodes = rootNode.findAccessibilityNodeInfosByText(dismissText)
                for (node in nodes) {
                    if (node.isClickable) {
                        node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                        Log.i(TAG, "✓ Dismissed InDriver dialog with: $dismissText")
                        node.recycle()
                        return true
                    }
                    // Try parent
                    val parent = node.parent
                    if (parent != null && parent.isClickable) {
                        parent.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                        Log.i(TAG, "✓ Dismissed InDriver dialog via parent: $dismissText")
                        parent.recycle()
                        node.recycle()
                        return true
                    }
                    parent?.recycle()
                    node.recycle()
                }
            }
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
                    Log.i(TAG, "🗺️ Gesture completed at ($x, $y)")
                }
                override fun onCancelled(gestureDescription: android.accessibilityservice.GestureDescription?) {
                    Log.w(TAG, "🗺️ Gesture cancelled at ($x, $y)")
                }
            }, null)

            Log.i(TAG, "🗺️ dispatchGesture result: $result")
            return result
        }
        return false
    }

    private fun notifyPriceCaptured(priceInfo: PriceInfo) {
        val intent = Intent(ACTION_PRICE_UPDATE).apply {
            putExtra(EXTRA_PRICE_DATA, priceInfoToJson(priceInfo))
            putExtra("automation_complete", true)
            putExtra("source", "automation")
        }
        sendBroadcast(intent)
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

            // Debug: Log ALL texts found (limited to first 30)
            Log.d(TAG, "📝 All texts found (${allText.size} total):")
            allText.take(30).forEachIndexed { index, text ->
                Log.d(TAG, "  [$index] '$text'")
            }

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
     * Extract prices from Uber app
     */
    private fun extractUberPrices(rootNode: AccessibilityNodeInfo) {
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
                appName = "Uber",
                packageName = UBER_PACKAGE,
                price = bestPrice,
                serviceType = detectServiceType(UBER_PACKAGE, allText),
                eta = extractETA(allText)
            )
            updatePrice(priceInfo)
            Log.d(TAG, "Uber price: $bestPrice EGP")
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
     */
    private fun extractInDriverPrices(rootNode: AccessibilityNodeInfo) {
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
        }.toString()
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
