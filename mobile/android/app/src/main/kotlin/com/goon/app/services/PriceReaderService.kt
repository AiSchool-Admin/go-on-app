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
 * This service reads prices from ride-hailing apps (Uber, Careem, InDriver)
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

        // Price patterns (EGP - Egyptian Pounds)
        private val PRICE_PATTERN = Pattern.compile("(\\d+[.,]?\\d*)\\s*(ج\\.م|EGP|جنيه|LE)", Pattern.CASE_INSENSITIVE)
        private val PRICE_NUMBER_PATTERN = Pattern.compile("(\\d+[.,]?\\d*)")

        // Singleton instance for Flutter communication
        var instance: PriceReaderService? = null
            private set

        // Latest prices from each app
        val latestPrices = mutableMapOf<String, PriceInfo>()
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

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        isServiceActive = true

        Log.i(TAG, "GO-ON Price Reader Service Connected")

        // Configure the service
        val info = AccessibilityServiceInfo().apply {
            eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
                        AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED or
                        AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED

            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC

            // Only monitor ride-hailing apps
            packageNames = arrayOf(
                UBER_PACKAGE,
                CAREEM_PACKAGE,
                INDRIVER_PACKAGE,
                DIDI_PACKAGE
            )

            notificationTimeout = 100
            flags = AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                   AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS
        }

        serviceInfo = info
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null || !isServiceActive) return

        val packageName = event.packageName?.toString() ?: return

        // Only process supported apps
        if (!isSupportedApp(packageName)) return

        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                processAppContent(packageName, event)
            }
        }
    }

    private fun isSupportedApp(packageName: String): Boolean {
        return packageName in listOf(
            UBER_PACKAGE,
            CAREEM_PACKAGE,
            INDRIVER_PACKAGE,
            DIDI_PACKAGE
        )
    }

    private fun processAppContent(packageName: String, event: AccessibilityEvent) {
        val rootNode = rootInActiveWindow ?: return

        try {
            when (packageName) {
                UBER_PACKAGE -> extractUberPrices(rootNode)
                CAREEM_PACKAGE -> extractCareemPrices(rootNode)
                INDRIVER_PACKAGE -> extractInDriverPrices(rootNode)
                DIDI_PACKAGE -> extractDiDiPrices(rootNode)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error processing $packageName: ${e.message}")
        } finally {
            rootNode.recycle()
        }
    }

    /**
     * Extract prices from Uber app
     * Uber typically shows prices in format: "EGP XX" or "XX ج.م"
     */
    private fun extractUberPrices(rootNode: AccessibilityNodeInfo) {
        val allText = getAllTextFromNode(rootNode)

        // Look for price patterns
        for (text in allText) {
            val price = extractPrice(text)
            if (price != null && price > 0) {
                val serviceType = detectUberServiceType(allText)

                val priceInfo = PriceInfo(
                    appName = "Uber",
                    packageName = UBER_PACKAGE,
                    price = price,
                    serviceType = serviceType,
                    eta = extractETA(allText)
                )

                updatePrice(priceInfo)
                Log.d(TAG, "Uber price detected: $price EGP ($serviceType)")
            }
        }
    }

    /**
     * Extract prices from Careem app
     */
    private fun extractCareemPrices(rootNode: AccessibilityNodeInfo) {
        val allText = getAllTextFromNode(rootNode)

        for (text in allText) {
            val price = extractPrice(text)
            if (price != null && price > 0) {
                val serviceType = detectCareemServiceType(allText)

                val priceInfo = PriceInfo(
                    appName = "Careem",
                    packageName = CAREEM_PACKAGE,
                    price = price,
                    serviceType = serviceType,
                    eta = extractETA(allText)
                )

                updatePrice(priceInfo)
                Log.d(TAG, "Careem price detected: $price EGP ($serviceType)")
            }
        }
    }

    /**
     * Extract prices from InDriver app
     * InDriver shows suggested prices that users can negotiate
     */
    private fun extractInDriverPrices(rootNode: AccessibilityNodeInfo) {
        val allText = getAllTextFromNode(rootNode)

        for (text in allText) {
            val price = extractPrice(text)
            if (price != null && price > 0) {
                val priceInfo = PriceInfo(
                    appName = "InDriver",
                    packageName = INDRIVER_PACKAGE,
                    price = price,
                    serviceType = "Economy",
                    eta = extractETA(allText)
                )

                updatePrice(priceInfo)
                Log.d(TAG, "InDriver price detected: $price EGP")
            }
        }
    }

    /**
     * Extract prices from DiDi app
     */
    private fun extractDiDiPrices(rootNode: AccessibilityNodeInfo) {
        val allText = getAllTextFromNode(rootNode)

        for (text in allText) {
            val price = extractPrice(text)
            if (price != null && price > 0) {
                val priceInfo = PriceInfo(
                    appName = "DiDi",
                    packageName = DIDI_PACKAGE,
                    price = price,
                    serviceType = "Express",
                    eta = extractETA(allText)
                )

                updatePrice(priceInfo)
                Log.d(TAG, "DiDi price detected: $price EGP")
            }
        }
    }

    /**
     * Recursively get all text from accessibility node tree
     */
    private fun getAllTextFromNode(node: AccessibilityNodeInfo): List<String> {
        val texts = mutableListOf<String>()

        node.text?.toString()?.let { texts.add(it) }
        node.contentDescription?.toString()?.let { texts.add(it) }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i)
            if (child != null) {
                texts.addAll(getAllTextFromNode(child))
                child.recycle()
            }
        }

        return texts
    }

    /**
     * Extract price value from text using regex patterns
     */
    private fun extractPrice(text: String): Double? {
        // Try full pattern with currency first
        var matcher = PRICE_PATTERN.matcher(text)
        if (matcher.find()) {
            val priceStr = matcher.group(1)?.replace(",", ".") ?: return null
            return priceStr.toDoubleOrNull()
        }

        // If text looks like a price context, try number pattern
        if (text.contains("ج.م") || text.contains("EGP") ||
            text.contains("السعر") || text.contains("price", ignoreCase = true)) {
            matcher = PRICE_NUMBER_PATTERN.matcher(text)
            if (matcher.find()) {
                val priceStr = matcher.group(1)?.replace(",", ".") ?: return null
                val price = priceStr.toDoubleOrNull()
                // Reasonable price range for rides in Egypt
                if (price != null && price in 10.0..1000.0) {
                    return price
                }
            }
        }

        return null
    }

    /**
     * Detect Uber service type from screen content
     */
    private fun detectUberServiceType(texts: List<String>): String {
        val combined = texts.joinToString(" ").lowercase()
        return when {
            combined.contains("uberx") || combined.contains("uber x") -> "UberX"
            combined.contains("comfort") -> "Comfort"
            combined.contains("black") -> "Black"
            combined.contains("xl") -> "UberXL"
            else -> "UberX"
        }
    }

    /**
     * Detect Careem service type
     */
    private fun detectCareemServiceType(texts: List<String>): String {
        val combined = texts.joinToString(" ").lowercase()
        return when {
            combined.contains("go") -> "Go"
            combined.contains("business") -> "Business"
            combined.contains("plus") -> "Plus"
            else -> "Go"
        }
    }

    /**
     * Extract ETA (estimated time of arrival) in minutes
     */
    private fun extractETA(texts: List<String>): Int {
        val combined = texts.joinToString(" ")
        val etaPattern = Pattern.compile("(\\d+)\\s*(د|min|دقيقة|minutes?)", Pattern.CASE_INSENSITIVE)
        val matcher = etaPattern.matcher(combined)

        if (matcher.find()) {
            return matcher.group(1)?.toIntOrNull() ?: 0
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

        // Check if we should show floating overlay
        checkAndShowOverlay(priceInfo)
    }

    /**
     * Show floating overlay if GO-ON has a better price
     */
    private fun checkAndShowOverlay(currentAppPrice: PriceInfo) {
        // Get best independent driver price from our service
        val goonBestPrice = getGoonBestPrice()

        if (goonBestPrice != null && goonBestPrice < currentAppPrice.price) {
            val savings = currentAppPrice.price - goonBestPrice
            val savingsPercent = ((savings / currentAppPrice.price) * 100).toInt()

            // Show floating overlay
            FloatingOverlayService.showBetterPrice(
                context = this,
                goonPrice = goonBestPrice,
                currentAppPrice = currentAppPrice.price,
                currentAppName = currentAppPrice.appName,
                savingsPercent = savingsPercent
            )
        }
    }

    /**
     * Get best price from GO-ON independent drivers
     * This would be fetched from Flutter/Supabase
     */
    private fun getGoonBestPrice(): Double? {
        // This will be populated by Flutter via MethodChannel
        return FloatingOverlayService.goonBestPrice
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
     * Clear stored prices
     */
    fun clearPrices() {
        latestPrices.clear()
    }
}
