package com.goon.app.services.automation

import android.accessibilityservice.AccessibilityService
import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import com.goon.app.services.config.AppConstants
import com.goon.app.services.config.TimingConfig
import com.goon.app.services.config.PricePatterns
import com.goon.app.services.utils.GestureHelper
import com.goon.app.services.utils.NodeFinder
import com.goon.app.utils.AppLogger

/**
 * أتمتة تطبيق أوبر
 * Uber app automation
 *
 * SPECIAL: Uber supports FULL deep link with coordinates, so we skip to WAITING_FOR_PRICE
 * The deep link format: uber://?action=setPickup&pickup=...&dropoff=...
 */
class UberAutomation(
    service: AccessibilityService
) : BaseAutomation(service, TAG) {

    companion object {
        private const val TAG = "UberAutomation"
    }

    // ============================================================
    // ABSTRACT PROPERTIES
    // ============================================================

    override val packageName: String = AppConstants.Packages.UBER
    override val displayName: String = "أوبر"
    override val supportsFullDeepLink: Boolean = true  // Uber deep links include coords!
    override val requiresPickupFirst: Boolean = false

    // ============================================================
    // STATE TRACKING
    // ============================================================

    private var screenReady = false
    private var automationStartTime = 0L

    /**
     * إعادة تعيين الحالة
     */
    fun resetState() {
        screenReady = false
        automationStartTime = System.currentTimeMillis()
    }

    // ============================================================
    // IMPLEMENTATION (minimal since deep link handles most)
    // ============================================================

    override fun findAndClickPickupField(rootNode: AccessibilityNodeInfo): AutomationResult {
        // Uber deep link includes pickup, so this is rarely called
        AppLogger.automation(TAG, "findAndClickPickupField - should not reach here with deep link", state = "PICKUP")

        val pickupTexts = listOf("Where from?", "من أين؟", "Current location", "الموقع الحالي")
        for (text in pickupTexts) {
            val node = findNodeWithText(rootNode, text)
            if (node != null) {
                if (smartClick(node)) {
                    node.recycle()
                    return AutomationResult(true, "Clicked pickup field")
                }
                node.recycle()
            }
        }
        return AutomationResult(false, "Pickup field not found", shouldRetry = true)
    }

    override fun enterPickupText(
        rootNode: AccessibilityNodeInfo,
        address: String,
        lat: Double,
        lng: Double
    ): AutomationResult {
        // Deep link handles this
        return AutomationResult(true, "Deep link handles pickup")
    }

    override fun findAndClickDestinationField(rootNode: AccessibilityNodeInfo): AutomationResult {
        // Deep link handles this
        return AutomationResult(true, "Deep link handles destination")
    }

    override fun enterDestinationText(
        rootNode: AccessibilityNodeInfo,
        address: String,
        lat: Double,
        lng: Double
    ): AutomationResult {
        // Deep link handles this
        return AutomationResult(true, "Deep link handles destination")
    }

    override fun selectSuggestion(rootNode: AccessibilityNodeInfo, isForPickup: Boolean): AutomationResult {
        // Deep link handles this
        return AutomationResult(true, "Deep link handles suggestion")
    }

    override fun handleIntermediateScreens(rootNode: AccessibilityNodeInfo): AutomationResult {
        val allText = getAllTextFromNode(rootNode)

        // Check for surge pricing confirmation
        val hasSurgeDialog = allText.any {
            val lower = it.lowercase()
            lower.contains("prices are higher") ||
            lower.contains("demand is high") ||
            lower.contains("الأسعار أعلى")
        }

        if (hasSurgeDialog) {
            AppLogger.automation(TAG, "Detected surge pricing dialog", state = "SURGE")

            val confirmTexts = listOf("Accept", "Confirm", "Got it", "OK", "قبول", "تأكيد")
            for (confirmText in confirmTexts) {
                val node = findNodeWithText(rootNode, confirmText)
                if (node != null && node.isClickable) {
                    if (node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                        AppLogger.automation(TAG, "Accepted surge pricing", state = "SURGE_ACCEPTED")
                        node.recycle()
                        return AutomationResult(true, "Accepted surge")
                    }
                    node.recycle()
                }
            }
        }

        // Check for promo dialog
        val hasPromoDialog = allText.any {
            it.contains("promo") || it.contains("عرض") || it.contains("خصم")
        }

        if (hasPromoDialog) {
            val skipTexts = listOf("Skip", "No thanks", "تخطي", "لا شكراً")
            for (skipText in skipTexts) {
                val node = findNodeWithText(rootNode, skipText)
                if (node != null) {
                    if (smartClick(node)) {
                        node.recycle()
                        return AutomationResult(true, "Skipped promo")
                    }
                    node.recycle()
                }
            }
        }

        return AutomationResult(false, "No intermediate screen", shouldRetry = false)
    }

    override fun extractPrices(rootNode: AccessibilityNodeInfo): ExtractedPrice? {
        val allText = getAllTextFromNode(rootNode)

        val prices = mutableListOf<Double>()
        val vehiclePrices = mutableMapOf<String, Double>()
        val rawTexts = mutableListOf<String>()

        for (text in allText) {
            val extracted = PricePatterns.extractPriceForApp(text, packageName)
            if (extracted != null && extracted > 0) {
                prices.add(extracted)
                rawTexts.add(text)
            }

            // Uber vehicle types
            val vehicleTypes = listOf("UberX", "Uber Comfort", "UberXL", "Black", "Green")
            for (vehicleType in vehicleTypes) {
                if (text.contains(vehicleType, ignoreCase = true)) {
                    val price = PricePatterns.extractPriceForApp(text, packageName)
                    if (price != null) {
                        vehiclePrices[vehicleType] = price
                    }
                }
            }
        }

        if (prices.isEmpty()) return null

        val minPrice = prices.minOrNull() ?: return null
        return ExtractedPrice(
            price = minPrice,
            allPrices = prices,
            vehiclePrices = vehiclePrices,
            rawTexts = rawTexts
        )
    }

    override fun isPriceScreenVisible(rootNode: AccessibilityNodeInfo): Boolean {
        val allText = getAllTextFromNode(rootNode)

        // Check screen readiness
        val elapsedTime = System.currentTimeMillis() - automationStartTime
        if (elapsedTime < 3000) {
            // Wait at least 3 seconds for screen to load
            return false
        }

        // Price screen indicators
        val indicators = listOf(
            "Choose a ride", "اختر رحلة",
            "UberX", "Uber Comfort",
            "EGP", "ج.م",
            "Request", "اطلب"
        )

        val hasIndicators = indicators.any { indicator ->
            allText.any { it.contains(indicator, ignoreCase = true) }
        }

        val hasPrice = allText.any { text ->
            PricePatterns.extractPriceForApp(text, packageName) != null
        }

        return hasIndicators && hasPrice
    }
}
