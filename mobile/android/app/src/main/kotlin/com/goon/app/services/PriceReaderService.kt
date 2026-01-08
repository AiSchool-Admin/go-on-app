package com.goon.app.services

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
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
    }

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
            Log.w(TAG, "No active window")
            return
        }

        try {
            val currentPackage = rootNode.packageName?.toString() ?: return

            // Wait for target app to be in foreground
            if (currentPackage != packageName) {
                Log.d(TAG, "Waiting for $packageName (currently: $currentPackage)")
                return
            }

            when (automationState) {
                AutomationState.WAITING_FOR_APP -> {
                    Log.i(TAG, "✓ App is active, finding destination field...")
                    automationState = AutomationState.FINDING_DESTINATION_FIELD
                }

                AutomationState.FINDING_DESTINATION_FIELD -> {
                    val found = findAndClickDestinationField(rootNode, packageName)
                    if (found) {
                        Log.i(TAG, "✓ Found destination field, entering address...")
                        automationState = AutomationState.ENTERING_DESTINATION
                    } else {
                        automationRetries++
                        if (automationRetries > MAX_RETRIES) {
                            Log.e(TAG, "✗ Failed to find destination field")
                            automationState = AutomationState.FAILED
                        }
                    }
                }

                AutomationState.ENTERING_DESTINATION -> {
                    val entered = enterDestinationText(rootNode, packageName)
                    if (entered) {
                        Log.i(TAG, "✓ Entered destination, waiting for suggestions...")
                        automationState = AutomationState.WAITING_FOR_SUGGESTIONS
                        automationRetries = 0
                    }
                }

                AutomationState.WAITING_FOR_SUGGESTIONS -> {
                    // Wait a moment then try to select
                    automationStep++
                    if (automationStep > 3) { // Wait ~2.4 seconds for suggestions
                        automationState = AutomationState.SELECTING_SUGGESTION
                        automationStep = 0
                    }
                }

                AutomationState.SELECTING_SUGGESTION -> {
                    val selected = selectFirstSuggestion(rootNode, packageName)
                    if (selected) {
                        Log.i(TAG, "✓ Selected suggestion, waiting for price...")
                        automationState = AutomationState.WAITING_FOR_PRICE
                        automationRetries = 0
                    } else {
                        automationRetries++
                        if (automationRetries > MAX_RETRIES) {
                            // Try scanning for price anyway
                            automationState = AutomationState.WAITING_FOR_PRICE
                        }
                    }
                }

                AutomationState.WAITING_FOR_PRICE -> {
                    val priceInfo = performAggressiveScan(packageName)
                    if (priceInfo != null && priceInfo.price > 0) {
                        Log.i(TAG, "✓ PRICE CAPTURED: ${priceInfo.price} EGP")
                        automationState = AutomationState.PRICE_CAPTURED
                        // Notify Flutter
                        notifyPriceCaptured(priceInfo)
                    } else {
                        automationStep++
                        if (automationStep > 10) { // Wait ~8 seconds for price
                            Log.w(TAG, "✗ Timeout waiting for price")
                            automationState = AutomationState.FAILED
                        }
                    }
                }

                else -> {}
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

        // App-specific field identifiers - EXPANDED for DiDi
        val searchTexts = when (packageName) {
            UBER_PACKAGE -> listOf("Where to?", "إلى أين؟", "Search", "بحث", "Enter destination", "Where to")
            CAREEM_PACKAGE -> listOf("Where to?", "إلى أين؟", "Search destination", "وجهتك", "Where would you like to go")
            INDRIVER_PACKAGE -> listOf("Where to?", "إلى أين؟", "To", "إلى", "Where")
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
        val focusedNode = rootNode.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        if (focusedNode != null) {
            // Clear existing text
            val args = android.os.Bundle()
            args.putCharSequence(
                AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                destinationAddress
            )
            val result = focusedNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
            focusedNode.recycle()

            if (result) {
                Log.i(TAG, "Entered destination: $destinationAddress")
                return true
            }
        }

        // Fallback: find any editable field and enter text
        return enterTextIntoAnyEditText(rootNode, destinationAddress)
    }

    private fun enterTextIntoAnyEditText(node: AccessibilityNodeInfo, text: String): Boolean {
        if (node.isEditable || node.className?.toString()?.contains("EditText") == true) {
            val args = android.os.Bundle()
            args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
            if (node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) {
                Log.i(TAG, "Entered text into EditText")
                return true
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

            // Strategy 1: Look for specific price elements by resource ID
            val priceById = findPriceByResourceId(rootNode, currentPackage)
            if (priceById != null && priceById > 0) {
                Log.i(TAG, "✓ Found price by resource ID: $priceById")
                return savePriceInfo(currentPackage, priceById, "resource_id")
            }

            // Strategy 2: Look for price by content description patterns
            val priceByDesc = findPriceByContentDescription(rootNode)
            if (priceByDesc != null && priceByDesc > 0) {
                Log.i(TAG, "✓ Found price by content description: $priceByDesc")
                return savePriceInfo(currentPackage, priceByDesc, "content_desc")
            }

            // Strategy 3: Full text scan with all patterns
            val allText = getAllTextFromNode(rootNode)
            val prices = mutableListOf<Double>()
            val priceTexts = mutableListOf<String>()

            // Debug: Log ALL texts found (limited to first 30)
            Log.d(TAG, "📝 All texts found (${allText.size} total):")
            allText.take(30).forEachIndexed { index, text ->
                Log.d(TAG, "  [$index] '$text'")
            }

            for (text in allText) {
                val price = extractPrice(text)
                if (price != null && price in 10.0..5000.0) {
                    prices.add(price)
                    priceTexts.add(text)
                    Log.i(TAG, "💰 Found potential price: $price in '$text'")
                }
            }

            if (prices.isNotEmpty()) {
                val bestPrice = selectBestPrice(prices, currentPackage)
                Log.i(TAG, "✓ Found ${prices.size} prices, best: $bestPrice EGP")
                return savePriceInfo(currentPackage, bestPrice, "text_scan", prices, priceTexts)
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
     * Find price by resource ID - App specific targeting
     */
    private fun findPriceByResourceId(rootNode: AccessibilityNodeInfo, packageName: String): Double? {
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

        for (resourceId in priceResourceIds) {
            try {
                val nodes = rootNode.findAccessibilityNodeInfosByViewId(resourceId)
                for (node in nodes) {
                    val text = node.text?.toString() ?: node.contentDescription?.toString()
                    if (text != null) {
                        val price = extractPrice(text)
                        if (price != null && price > 0) {
                            node.recycle()
                            return price
                        }
                    }
                    node.recycle()
                }
            } catch (e: Exception) {
                // Resource ID not found, continue
            }
        }

        return null
    }

    /**
     * Find price by scanning content descriptions for price keywords
     */
    private fun findPriceByContentDescription(node: AccessibilityNodeInfo): Double? {
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
                if (price != null && price > 0) return price
            }
        }

        // Recursively check children
        for (i in 0 until node.childCount) {
            try {
                val child = node.getChild(i)
                if (child != null) {
                    val childPrice = findPriceByContentDescription(child)
                    child.recycle()
                    if (childPrice != null) return childPrice
                }
            } catch (e: Exception) {}
        }

        return null
    }

    /**
     * Select the best price from candidates based on app-specific logic
     */
    private fun selectBestPrice(prices: List<Double>, packageName: String): Double {
        if (prices.isEmpty()) return 0.0
        if (prices.size == 1) return prices[0]

        // Filter to reasonable ride prices (15-1000 EGP typically)
        val reasonable = prices.filter { it in 15.0..1000.0 }
        if (reasonable.isEmpty()) return prices.minOrNull() ?: 0.0

        // ALWAYS return the LOWEST price - this is what the user wants!
        val lowestPrice = reasonable.minOrNull() ?: reasonable[0]
        Log.i(TAG, "📊 Prices found: $reasonable, selecting LOWEST: $lowestPrice")
        return lowestPrice
    }

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
     * Usually the main price is displayed prominently
     */
    private fun findBestPrice(prices: List<Double>): Double {
        if (prices.isEmpty()) return 0.0
        if (prices.size == 1) return prices[0]

        // Filter reasonable prices (15-500 EGP for typical rides)
        val reasonable = prices.filter { it in 15.0..500.0 }
        if (reasonable.isNotEmpty()) {
            // Return the median (most likely the actual price, not surge or tips)
            return reasonable.sorted()[reasonable.size / 2]
        }

        return prices.sorted()[prices.size / 2]
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
