package com.goon.app.services.automation

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import com.goon.app.services.FloatingOverlayService
import com.goon.app.services.config.AppConstants
import com.goon.app.utils.AppLogger

/**
 * محول للربط بين النظام الجديد (Modular Automation) والنظام القديم (PriceReaderService)
 * Adapter to bridge new modular automation with legacy PriceReaderService
 *
 * This adapter allows gradual migration from the monolithic PriceReaderService
 * to the new modular architecture without breaking existing functionality.
 *
 * Usage in PriceReaderService:
 * ```kotlin
 * // In PriceReaderService
 * private val adapter = PriceReaderAdapter(this)
 *
 * // To use new automation:
 * if (adapter.canHandlePackage(packageName)) {
 *     adapter.startAutomation(packageName, ...)
 * }
 *
 * // In performAutomationStep:
 * if (adapter.isActive()) {
 *     adapter.performStep(rootNode)
 *     return
 * }
 * ```
 */
class PriceReaderAdapter(
    private val service: AccessibilityService
) {
    companion object {
        private const val TAG = "PriceReaderAdapter"

        // Broadcast actions (same as PriceReaderService)
        const val ACTION_PRICE_UPDATE = "com.goon.app.PRICE_UPDATE"
        const val EXTRA_PRICE_DATA = "price_data"
    }

    // ============================================================
    // ORCHESTRATOR INTEGRATION
    // ============================================================

    private val orchestrator = AutomationOrchestrator(service)

    // Latest prices cache (mirrors PriceReaderService.latestPrices)
    val latestPrices = mutableMapOf<String, PriceData>()

    // Monitoring state
    var isActiveMonitoring: Boolean = false
        private set
    var monitoringPackage: String? = null
        private set

    init {
        // Setup callbacks
        orchestrator.onPriceExtracted = { packageName, prices ->
            handlePriceExtracted(packageName, prices)
        }

        orchestrator.onAutomationFailed = { packageName, reason ->
            handleAutomationFailed(packageName, reason)
        }

        orchestrator.onAutomationComplete = { packageName ->
            handleAutomationComplete(packageName)
        }

        orchestrator.onStateChanged = { state ->
            AppLogger.automation(TAG, "State changed: $state", state = state.name)
        }
    }

    // ============================================================
    // PUBLIC API - For PriceReaderService
    // ============================================================

    /**
     * هل يمكن معالجة هذا التطبيق بالنظام الجديد؟
     * Can this package be handled by new system?
     */
    fun canHandlePackage(packageName: String): Boolean {
        return AutomationFactory.isSupported(packageName)
    }

    /**
     * بدء الأتمتة باستخدام النظام الجديد
     * Start automation using new system
     */
    fun startAutomation(
        packageName: String,
        pickup: String,
        destination: String,
        pickupLat: Double,
        pickupLng: Double,
        destLat: Double,
        destLng: Double
    ) {
        AppLogger.automation(TAG, "Starting automation for $packageName", state = "START")

        isActiveMonitoring = true
        monitoringPackage = packageName

        // Clear old cached price
        latestPrices.remove(packageName)

        // Update overlay to show loading
        try {
            FloatingOverlayService.updateAppPrice(packageName, null, "loading", null)
        } catch (e: Exception) {
            AppLogger.w(TAG, "Could not update overlay: ${e.message}")
        }

        // Start orchestrator
        orchestrator.startAutomation(
            packageName,
            pickup, destination,
            pickupLat, pickupLng,
            destLat, destLng
        )
    }

    /**
     * إيقاف الأتمتة
     * Stop automation
     */
    fun stopAutomation() {
        AppLogger.automation(TAG, "Stopping automation", state = "STOP")
        isActiveMonitoring = false
        monitoringPackage = null
        orchestrator.stopAutomation()
    }

    /**
     * هل الأتمتة نشطة؟
     * Is automation active?
     */
    fun isActive(): Boolean {
        return orchestrator.isAutomationActive()
    }

    /**
     * تنفيذ خطوة
     * Perform step
     */
    fun performStep(rootNode: AccessibilityNodeInfo): Boolean {
        return orchestrator.performStep(rootNode)
    }

    /**
     * الحصول على الحالة الحالية
     * Get current state
     */
    fun getCurrentState(): AutomationState {
        return orchestrator.getCurrentState()
    }

    /**
     * هل يدعم التطبيق deep link كامل؟
     * Does package support full deep link?
     */
    fun supportsFullDeepLink(packageName: String): Boolean {
        return AutomationFactory.supportsFullDeepLink(packageName)
    }

    /**
     * الحصول على الحالة الأولية للتطبيق
     * Get initial state for package
     */
    fun getInitialState(packageName: String): AutomationState {
        return AutomationFactory.getInitialState(packageName)
    }

    // ============================================================
    // CALLBACKS
    // ============================================================

    private fun handlePriceExtracted(packageName: String, prices: ExtractedPrice) {
        AppLogger.price(TAG, "Price extracted: ${prices.price} EGP",
            price = prices.price, app = packageName)

        // Store in local cache
        val priceData = PriceData(
            appName = AutomationFactory.getDisplayName(packageName),
            packageName = packageName,
            price = prices.price,
            currency = prices.currency,
            serviceType = prices.serviceType,
            eta = prices.eta,
            timestamp = System.currentTimeMillis(),
            allPricesFound = prices.allPrices,
            vehiclePrices = prices.vehiclePrices,
            rawTexts = prices.rawTexts
        )
        latestPrices[packageName] = priceData

        // Update overlay
        try {
            FloatingOverlayService.updateAppPrice(
                packageName,
                prices.price,
                "success",
                null
            )
        } catch (e: Exception) {
            AppLogger.w(TAG, "Could not update overlay: ${e.message}")
        }

        // Broadcast price update
        broadcastPriceUpdate(priceData)
    }

    private fun handleAutomationFailed(packageName: String, reason: String) {
        AppLogger.e(TAG, "Automation failed: $reason")

        // Update overlay
        try {
            FloatingOverlayService.updateAppPrice(
                packageName,
                null,
                "error",
                reason
            )
        } catch (e: Exception) {
            AppLogger.w(TAG, "Could not update overlay: ${e.message}")
        }

        isActiveMonitoring = false
    }

    private fun handleAutomationComplete(packageName: String) {
        AppLogger.automation(TAG, "Automation complete for $packageName", state = "COMPLETE")

        // Auto-return to GO-ON app
        autoReturnToGoOn()

        isActiveMonitoring = false
    }

    // ============================================================
    // HELPERS
    // ============================================================

    private fun broadcastPriceUpdate(priceData: PriceData) {
        try {
            val intent = Intent(ACTION_PRICE_UPDATE).apply {
                putExtra(EXTRA_PRICE_DATA, priceData.toJson())
            }
            service.sendBroadcast(intent)
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to broadcast price: ${e.message}", e)
        }
    }

    private fun autoReturnToGoOn() {
        try {
            val intent = service.packageManager.getLaunchIntentForPackage("com.goon.app")
            intent?.let {
                it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                service.startActivity(it)
                AppLogger.d(TAG, "Returned to GO-ON app")
            }
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to return to GO-ON: ${e.message}", e)
        }
    }

    // ============================================================
    // DATA CLASS
    // ============================================================

    /**
     * بيانات السعر (متوافقة مع PriceReaderService.PriceInfo)
     * Price data (compatible with PriceReaderService.PriceInfo)
     */
    data class PriceData(
        val appName: String,
        val packageName: String,
        val price: Double,
        val currency: String = "EGP",
        val serviceType: String = "",
        val eta: Int = 0,
        val timestamp: Long = System.currentTimeMillis(),
        val allPricesFound: List<Double> = emptyList(),
        val vehiclePrices: Map<String, Double> = emptyMap(),
        val rawTexts: List<String> = emptyList()
    ) {
        fun toJson(): String {
            return """
                {
                    "appName": "$appName",
                    "packageName": "$packageName",
                    "price": $price,
                    "currency": "$currency",
                    "serviceType": "$serviceType",
                    "eta": $eta,
                    "timestamp": $timestamp,
                    "allPricesFound": [${allPricesFound.joinToString(",")}],
                    "vehiclePrices": {${vehiclePrices.entries.joinToString(",") { "\"${it.key}\": ${it.value}" }}}
                }
            """.trimIndent()
        }
    }
}
