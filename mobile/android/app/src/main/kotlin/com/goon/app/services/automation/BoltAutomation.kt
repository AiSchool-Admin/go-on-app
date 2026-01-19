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
 * أتمتة تطبيق بولت
 * Bolt app automation
 *
 * Flow: Home → Click "Where to?" → Enter Destination → Select → Get Price
 */
class BoltAutomation(
    service: AccessibilityService
) : BaseAutomation(service, TAG) {

    companion object {
        private const val TAG = "BoltAutomation"
    }

    // ============================================================
    // ABSTRACT PROPERTIES
    // ============================================================

    override val packageName: String = AppConstants.Packages.BOLT
    override val displayName: String = "بولت"
    override val supportsFullDeepLink: Boolean = false
    override val requiresPickupFirst: Boolean = false  // Bolt uses current location

    // ============================================================
    // STATE TRACKING
    // ============================================================

    private var whereToClicked = false
    private var destinationEntered = false

    // Trip details
    var pickupAddress: String = ""
    var destinationAddress: String = ""
    var pickupLat: Double = 0.0
    var pickupLng: Double = 0.0
    var destLat: Double = 0.0
    var destLng: Double = 0.0

    /**
     * إعادة تعيين الحالة
     */
    fun resetState() {
        whereToClicked = false
        destinationEntered = false
    }

    // ============================================================
    // FINDING PICKUP FIELD (Bolt uses current location)
    // ============================================================

    override fun findAndClickPickupField(rootNode: AccessibilityNodeInfo): AutomationResult {
        AppLogger.automation(TAG, "findAndClickPickupField - Bolt uses current location", state = "PICKUP")
        // Bolt typically uses current location as pickup
        // Just go to destination field
        return AutomationResult(true, "Bolt uses current location")
    }

    override fun enterPickupText(
        rootNode: AccessibilityNodeInfo,
        address: String,
        lat: Double,
        lng: Double
    ): AutomationResult {
        // Bolt uses current location, no need to enter pickup
        return AutomationResult(true, "Bolt uses current location")
    }

    // ============================================================
    // FINDING DESTINATION FIELD
    // ============================================================

    override fun findAndClickDestinationField(rootNode: AccessibilityNodeInfo): AutomationResult {
        AppLogger.automation(TAG, "Finding destination field", state = "FINDING_DESTINATION")

        // Look for "Where to?" button/field
        val destTexts = listOf(
            "Where to?", "Where are you going?",
            "إلى أين؟", "إلى أين تريد الذهاب",
            "Search destination", "Enter destination"
        )

        for (text in destTexts) {
            val node = findNodeWithText(rootNode, text)
            if (node != null) {
                if (smartClick(node)) {
                    AppLogger.automation(TAG, "Clicked '$text'", state = "DEST_CLICKED")
                    whereToClicked = true
                    node.recycle()
                    Thread.sleep(TimingConfig.animationWait)
                    return AutomationResult(true, "Found destination field")
                }
                node.recycle()
            }
        }

        // Try finding EditText
        val editTexts = mutableListOf<AccessibilityNodeInfo>()
        findAllEditTexts(rootNode, editTexts)

        if (editTexts.isNotEmpty()) {
            val first = editTexts.first()
            if (first.performAction(AccessibilityNodeInfo.ACTION_FOCUS) ||
                first.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                AppLogger.automation(TAG, "Focused EditText", state = "DEST_CLICKED")
                whereToClicked = true
                editTexts.forEach { try { it.recycle() } catch (e: Exception) {} }
                return AutomationResult(true, "Focused destination EditText")
            }
            editTexts.forEach { try { it.recycle() } catch (e: Exception) {} }
        }

        return AutomationResult(false, "Could not find destination", shouldRetry = true)
    }

    // ============================================================
    // ENTERING DESTINATION TEXT
    // ============================================================

    override fun enterDestinationText(
        rootNode: AccessibilityNodeInfo,
        address: String,
        lat: Double,
        lng: Double
    ): AutomationResult {
        AppLogger.automation(TAG, "Entering destination: '$address'", state = "ENTERING_DESTINATION")

        val editTexts = mutableListOf<AccessibilityNodeInfo>()
        findAllEditTexts(rootNode, editTexts)

        for (editText in editTexts) {
            clearTextField(editText)
            Thread.sleep(100)

            if (enterTextInField(editText, address)) {
                AppLogger.automation(TAG, "Entered destination", state = "DESTINATION_ENTERED")
                destinationEntered = true
                editTexts.forEach { try { it.recycle() } catch (e: Exception) {} }
                Thread.sleep(TimingConfig.textInputDelay)
                return AutomationResult(true, "Entered destination")
            }
        }

        editTexts.forEach { try { it.recycle() } catch (e: Exception) {} }

        // Fallback: focused input
        val focusedInput = findFocusedEditText(rootNode)
        if (focusedInput != null) {
            if (enterTextInField(focusedInput, address)) {
                AppLogger.automation(TAG, "Entered via focused field", state = "DESTINATION_ENTERED")
                destinationEntered = true
                focusedInput.recycle()
                Thread.sleep(TimingConfig.textInputDelay)
                return AutomationResult(true, "Entered via focused")
            }
            focusedInput.recycle()
        }

        return AutomationResult(false, "Could not enter destination", shouldRetry = true)
    }

    // ============================================================
    // SELECTING SUGGESTION
    // ============================================================

    override fun selectSuggestion(rootNode: AccessibilityNodeInfo, isForPickup: Boolean): AutomationResult {
        AppLogger.automation(TAG, "Selecting suggestion", state = "SELECTING_SUGGESTION")

        val allText = getAllTextFromNode(rootNode)

        // Look for suggestions matching destination
        val keywords = destinationAddress.split(" ").filter { it.length > 2 }

        // Find text with distance indicator (min, km)
        val suggestionTexts = allText.filter { text ->
            (text.contains("min", ignoreCase = true) ||
             text.contains("km", ignoreCase = true) ||
             text.contains("دقيقة") ||
             text.contains("كم"))
        }

        for (suggestion in suggestionTexts) {
            val matchCount = keywords.count { suggestion.contains(it, ignoreCase = true) }
            if (matchCount >= 1 || suggestionTexts.size == 1) {
                val node = findNodeWithText(rootNode, suggestion)
                if (node != null) {
                    if (smartClick(node)) {
                        AppLogger.automation(TAG, "Selected: '$suggestion'", state = "SUGGESTION_SELECTED")
                        node.recycle()
                        Thread.sleep(TimingConfig.animationWait)
                        return AutomationResult(true, "Selected suggestion")
                    }
                    node.recycle()
                }
            }
        }

        // Try clicking first clickable suggestion row
        val clickableNodes = findClickableSuggestionRows(rootNode)
        if (clickableNodes.isNotEmpty()) {
            val first = clickableNodes.first()
            if (smartClick(first)) {
                AppLogger.automation(TAG, "Clicked first suggestion row", state = "SUGGESTION_SELECTED")
                clickableNodes.forEach { try { it.recycle() } catch (e: Exception) {} }
                Thread.sleep(TimingConfig.animationWait)
                return AutomationResult(true, "Clicked first row")
            }
            clickableNodes.forEach { try { it.recycle() } catch (e: Exception) {} }
        }

        return AutomationResult(false, "Could not select suggestion", shouldRetry = true)
    }

    // ============================================================
    // INTERMEDIATE SCREENS
    // ============================================================

    override fun handleIntermediateScreens(rootNode: AccessibilityNodeInfo): AutomationResult {
        val allText = getAllTextFromNode(rootNode)

        // Check for promo dialog
        val hasPromoDialog = allText.any {
            it.contains("promo", ignoreCase = true) ||
            it.contains("discount", ignoreCase = true) ||
            it.contains("عرض") ||
            it.contains("خصم")
        }

        if (hasPromoDialog) {
            val skipTexts = listOf("Skip", "No thanks", "Close", "تخطي", "إغلاق")
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

    // ============================================================
    // PRICE EXTRACTION
    // ============================================================

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

            // Bolt vehicle types
            val vehicleTypes = listOf("Bolt", "Bolt Comfort", "Bolt XL", "Economy", "Comfort")
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

        val indicators = listOf(
            "Choose ride", "Confirm pickup",
            "Bolt", "EGP", "ج.م",
            "Request ride", "Request"
        )

        val hasIndicators = indicators.any { indicator ->
            allText.any { it.contains(indicator, ignoreCase = true) }
        }

        val hasPrice = allText.any { text ->
            PricePatterns.extractPriceForApp(text, packageName) != null
        }

        return hasIndicators && hasPrice
    }

    // ============================================================
    // HELPER METHODS
    // ============================================================

    private fun findClickableSuggestionRows(rootNode: AccessibilityNodeInfo): List<AccessibilityNodeInfo> {
        val results = mutableListOf<AccessibilityNodeInfo>()
        findClickableSuggestionRowsRecursive(rootNode, results)
        return results
    }

    private fun findClickableSuggestionRowsRecursive(
        node: AccessibilityNodeInfo,
        results: MutableList<AccessibilityNodeInfo>
    ) {
        val rect = Rect()
        node.getBoundsInScreen(rect)

        // Suggestion rows are typically full-width, clickable, in middle of screen
        val isLikelySuggestionRow = node.isClickable &&
            rect.width() > 400 &&
            rect.height() in 50..200 &&
            rect.top > 300 && rect.top < 1500

        if (isLikelySuggestionRow) {
            results.add(AccessibilityNodeInfo.obtain(node))
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            findClickableSuggestionRowsRecursive(child, results)
            child.recycle()
        }
    }
}
