package com.goon.app.services

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Intent
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import org.json.JSONObject
import java.util.regex.Pattern

/**
 * GO-ON Price Reader Accessibility Service
 *
 * This service reads prices from ride-hailing apps (Uber, Careem, InDriver, DiDi, Bolt)
 * when the user is viewing price estimates.
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

        // Price patterns for Egyptian Pounds (multiple formats)
        private val PRICE_PATTERNS = listOf(
            // EGP XX or XX EGP
            Pattern.compile("EGP\\s*(\\d+[.,]?\\d*)", Pattern.CASE_INSENSITIVE),
            Pattern.compile("(\\d+[.,]?\\d*)\\s*EGP", Pattern.CASE_INSENSITIVE),
            // ج.م XX or XX ج.م
            Pattern.compile("ج\\.?م\\.?\\s*(\\d+[.,]?\\d*)"),
            Pattern.compile("(\\d+[.,]?\\d*)\\s*ج\\.?م\\.?"),
            // جنيه XX or XX جنيه
            Pattern.compile("جنيه\\s*(\\d+[.,]?\\d*)"),
            Pattern.compile("(\\d+[.,]?\\d*)\\s*جنيه"),
            // LE XX or XX LE
            Pattern.compile("LE\\s*(\\d+[.,]?\\d*)", Pattern.CASE_INSENSITIVE),
            Pattern.compile("(\\d+[.,]?\\d*)\\s*LE", Pattern.CASE_INSENSITIVE),
            // Just numbers in price context (50-500 range typically)
            Pattern.compile("^(\\d{2,3})$")
        )

        // Singleton instance for Flutter communication
        var instance: PriceReaderService? = null
            private set

        // Latest prices from each app
        val latestPrices = mutableMapOf<String, PriceInfo>()

        // Flag to indicate active scanning mode
        var isScanning = false
        var currentScanPackage: String? = null
    }

    data class PriceInfo(
        val appName: String,
        val packageName: String,
        val price: Double,
        val currency: String = "EGP",
        val serviceType: String = "",
        val eta: Int = 0,
        val timestamp: Long = System.currentTimeMillis()
    )

    private var isServiceActive = false
    private var lastProcessedTime = 0L
    private val processDebounce = 500L // ms

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        isServiceActive = true

        Log.i(TAG, "✓ GO-ON Price Reader Service Connected and Active")

        // Configure the service
        val info = AccessibilityServiceInfo().apply {
            eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
                        AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED or
                        AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED or
                        AccessibilityEvent.TYPE_VIEW_SCROLLED

            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC

            // Only monitor ride-hailing apps
            packageNames = arrayOf(
                UBER_PACKAGE,
                CAREEM_PACKAGE,
                INDRIVER_PACKAGE,
                DIDI_PACKAGE,
                BOLT_PACKAGE
            )

            notificationTimeout = 50
            flags = AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                   AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS or
                   AccessibilityServiceInfo.FLAG_REQUEST_ENHANCED_WEB_ACCESSIBILITY
        }

        serviceInfo = info
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
