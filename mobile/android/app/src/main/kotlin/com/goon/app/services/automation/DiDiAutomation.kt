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
 * أتمتة تطبيق ديدي
 * DiDi app automation
 *
 * Flow: Home → Click "Where to?" → Enter Pickup → Select → Enter Destination → Select → Get Price
 *
 * PICKUP FIRST flow to ensure correct location is used
 */
class DiDiAutomation(
    service: AccessibilityService
) : BaseAutomation(service, TAG) {

    companion object {
        private const val TAG = "DiDiAutomation"
        private const val MAX_DESTINATION_ATTEMPTS = 15
        private const val MAX_SUGGESTION_RETRIES = 3
    }

    // ============================================================
    // ABSTRACT PROPERTIES
    // ============================================================

    override val packageName: String = AppConstants.Packages.DIDI
    override val displayName: String = "ديدي"
    override val supportsFullDeepLink: Boolean = false
    override val requiresPickupFirst: Boolean = true

    // ============================================================
    // STATE TRACKING
    // ============================================================

    private var pickupFieldClicked = false
    private var pickupEntered = false
    private var pickupPhaseComplete = false
    private var destinationClickAttempts = 0
    private var suggestionRetryCount = 0
    private var lastSelectAddressClickTime = 0L

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
        pickupFieldClicked = false
        pickupEntered = false
        pickupPhaseComplete = false
        destinationClickAttempts = 0
        suggestionRetryCount = 0
        lastSelectAddressClickTime = 0L
    }

    // ============================================================
    // FINDING PICKUP FIELD
    // ============================================================

    override fun findAndClickPickupField(rootNode: AccessibilityNodeInfo): AutomationResult {
        AppLogger.automation(TAG, "Finding pickup field (PICKUP FIRST)", state = "FINDING_PICKUP")

        val allText = getAllTextFromNode(rootNode)

        // Check for home screen "Where to?" button
        val hasWhereToButton = allText.any {
            val lower = it.lowercase()
            lower.contains("where to") || lower.contains("tap to enter")
        }

        if (hasWhereToButton) {
            AppLogger.d(TAG, "On home screen - clicking 'Where to?'")

            // Try clicking the "Where to?" element
            val whereToTexts = listOf("Where to?", "Tap to enter your destination")
            for (text in whereToTexts) {
                val node = findNodeWithText(rootNode, text)
                if (node != null) {
                    if (smartClick(node)) {
                        AppLogger.automation(TAG, "Clicked '$text'", state = "WHERE_TO_CLICKED")
                        node.recycle()
                        Thread.sleep(TimingConfig.animationWait)
                        return AutomationResult(false, "Clicked Where to", shouldRetry = true)
                    }
                    node.recycle()
                }
            }

            // Fallback: look for search-like clickable element
            if (findDiDiClickableSearchElement(rootNode)) {
                Thread.sleep(TimingConfig.animationWait)
                return AutomationResult(false, "Clicked search element", shouldRetry = true)
            }
        }

        // Look for pickup-specific field
        val pickupTexts = listOf("Pick-up location", "Pickup", "From", "نقطة الانطلاق")
        for (text in pickupTexts) {
            val node = findNodeWithText(rootNode, text)
            if (node != null) {
                if (smartClick(node)) {
                    AppLogger.automation(TAG, "Clicked '$text'", state = "PICKUP_CLICKED")
                    pickupFieldClicked = true
                    node.recycle()
                    return AutomationResult(true, "Found pickup field")
                }
                node.recycle()
            }
        }

        // Try EditText approach
        val editTexts = mutableListOf<AccessibilityNodeInfo>()
        findAllEditTexts(rootNode, editTexts)

        if (editTexts.isNotEmpty()) {
            val sortedEditTexts = editTexts.sortedBy { node ->
                val rect = Rect()
                node.getBoundsInScreen(rect)
                rect.top
            }

            if (sortedEditTexts.first().performAction(AccessibilityNodeInfo.ACTION_FOCUS)) {
                AppLogger.automation(TAG, "Focused first EditText", state = "PICKUP_CLICKED")
                pickupFieldClicked = true
                editTexts.forEach { try { it.recycle() } catch (e: Exception) {} }
                return AutomationResult(true, "Focused pickup EditText")
            }
            editTexts.forEach { try { it.recycle() } catch (e: Exception) {} }
        }

        return AutomationResult(false, "Could not find pickup field", shouldRetry = true)
    }

    // ============================================================
    // ENTERING PICKUP TEXT
    // ============================================================

    override fun enterPickupText(
        rootNode: AccessibilityNodeInfo,
        address: String,
        lat: Double,
        lng: Double
    ): AutomationResult {
        AppLogger.automation(TAG, "Entering pickup: '$address'", state = "ENTERING_PICKUP")

        val editTexts = mutableListOf<AccessibilityNodeInfo>()
        findAllEditTexts(rootNode, editTexts)

        val sortedEditTexts = editTexts.sortedBy { node ->
            val rect = Rect()
            node.getBoundsInScreen(rect)
            rect.top
        }

        for (editText in sortedEditTexts) {
            val currentText = editText.text?.toString() ?: ""

            // Skip if has destination content
            val destKeywords = destinationAddress.split(" ").filter { it.length > 3 }
            if (destKeywords.any { currentText.contains(it) }) continue

            clearTextField(editText)
            Thread.sleep(100)

            if (enterTextInField(editText, address)) {
                AppLogger.automation(TAG, "Entered pickup", state = "PICKUP_ENTERED")
                pickupEntered = true
                editTexts.forEach { try { it.recycle() } catch (e: Exception) {} }
                Thread.sleep(TimingConfig.textInputDelay)
                return AutomationResult(true, "Entered pickup")
            }
        }

        editTexts.forEach { try { it.recycle() } catch (e: Exception) {} }
        return AutomationResult(false, "Could not enter pickup", shouldRetry = true)
    }

    // ============================================================
    // FINDING DESTINATION FIELD
    // ============================================================

    override fun findAndClickDestinationField(rootNode: AccessibilityNodeInfo): AutomationResult {
        val phase = if (pickupPhaseComplete) "PICKUP_DONE" else "INITIAL"
        AppLogger.automation(TAG, "Finding destination field ($phase)", state = "FINDING_DESTINATION")

        destinationClickAttempts++
        if (destinationClickAttempts > MAX_DESTINATION_ATTEMPTS) {
            return AutomationResult(false, "Max attempts exceeded", shouldRetry = false)
        }

        val allText = getAllTextFromNode(rootNode)

        // Check if we're back on home screen
        val isOnHomeScreen = allText.any {
            val lower = it.lowercase()
            lower.contains("tap to enter your destination") ||
            (lower.contains("where to") && lower.contains("button"))
        }

        if (isOnHomeScreen) {
            AppLogger.w(TAG, "Back on home screen - need to retry from start")
            return findAndClickPickupField(rootNode)
        }

        // Destination field texts
        val destTexts = listOf("Where to?", "Destination", "Drop-off", "الوجهة", "إلى أين")
        for (text in destTexts) {
            val node = findNodeWithText(rootNode, text)
            if (node != null) {
                if (smartClick(node)) {
                    AppLogger.automation(TAG, "Clicked '$text'", state = "DEST_CLICKED")
                    node.recycle()
                    return AutomationResult(true, "Found destination field")
                }
                node.recycle()
            }
        }

        // Second EditText approach
        val editTexts = mutableListOf<AccessibilityNodeInfo>()
        findAllEditTexts(rootNode, editTexts)

        if (editTexts.size >= 2) {
            val sortedEditTexts = editTexts.sortedBy { node ->
                val rect = Rect()
                node.getBoundsInScreen(rect)
                rect.top
            }

            val destEditText = sortedEditTexts[1]
            if (destEditText.performAction(AccessibilityNodeInfo.ACTION_FOCUS)) {
                AppLogger.automation(TAG, "Focused second EditText", state = "DEST_CLICKED")
                editTexts.forEach { try { it.recycle() } catch (e: Exception) {} }
                return AutomationResult(true, "Focused destination EditText")
            }
        }
        editTexts.forEach { try { it.recycle() } catch (e: Exception) {} }

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

        // Check if still on home screen
        val allText = getAllTextFromNode(rootNode)
        val isOnHomeScreen = allText.any {
            val lower = it.lowercase()
            lower.contains("tap to enter your destination")
        }

        if (isOnHomeScreen) {
            AppLogger.w(TAG, "Still on home screen - destination click didn't work")
            return AutomationResult(false, "Still on home screen", shouldRetry = true,
                nextState = AutomationState.FINDING_DESTINATION_FIELD)
        }

        val editTexts = mutableListOf<AccessibilityNodeInfo>()
        findAllEditTexts(rootNode, editTexts)

        val sortedEditTexts = editTexts.sortedBy { node ->
            val rect = Rect()
            node.getBoundsInScreen(rect)
            rect.top
        }

        val orderedEditTexts = if (sortedEditTexts.size >= 2) {
            listOf(sortedEditTexts[1]) + sortedEditTexts.filter { it != sortedEditTexts[1] }
        } else {
            sortedEditTexts
        }

        for (editText in orderedEditTexts) {
            val currentText = editText.text?.toString() ?: ""

            // Skip pickup field
            val pickupKeywords = pickupAddress.split(" ").filter { it.length > 3 }
            if (pickupKeywords.any { currentText.contains(it) }) continue

            clearTextField(editText)
            Thread.sleep(100)

            if (enterTextInField(editText, address)) {
                AppLogger.automation(TAG, "Entered destination", state = "DESTINATION_ENTERED")
                editTexts.forEach { try { it.recycle() } catch (e: Exception) {} }
                Thread.sleep(TimingConfig.textInputDelay)
                return AutomationResult(true, "Entered destination")
            }
        }

        editTexts.forEach { try { it.recycle() } catch (e: Exception) {} }
        return AutomationResult(false, "Could not enter destination", shouldRetry = true)
    }

    // ============================================================
    // SELECTING SUGGESTION
    // ============================================================

    override fun selectSuggestion(rootNode: AccessibilityNodeInfo, isForPickup: Boolean): AutomationResult {
        val phase = if (isForPickup) "PICKUP" else "DESTINATION"
        AppLogger.automation(TAG, "Selecting $phase suggestion", state = "SELECTING_SUGGESTION")

        val allText = getAllTextFromNode(rootNode)

        // Check if we're on "Select Address" screen
        val onSelectAddress = allText.any {
            it.lowercase().contains("select address") ||
            it.lowercase().contains("pin your location")
        }

        if (onSelectAddress) {
            val result = handleSelectAddressScreen(rootNode)
            if (result.success) return result
        }

        // Check if already on price screen
        val hasPriceIndicators = allText.any {
            val lower = it.lowercase()
            lower.contains("egp") || lower.contains("ج.م") ||
            lower.contains("flex") || lower.contains("wasalny") ||
            lower.contains("choose your driver")
        }

        if (hasPriceIndicators) {
            AppLogger.automation(TAG, "Already on price screen", state = "PRICE_SCREEN")
            return AutomationResult(true, "On price screen", nextState = AutomationState.WAITING_FOR_PRICE)
        }

        // Look for suggestion matching our address
        val targetAddress = if (isForPickup) pickupAddress else destinationAddress
        val keywords = targetAddress.split(" ").filter { it.length > 2 }

        // Find suggestions with distance indicator (km/كم)
        val suggestionTexts = allText.filter { text ->
            (text.contains("km", ignoreCase = true) || text.contains("كم")) &&
            !text.contains("bookmark", ignoreCase = true)
        }

        for (suggestion in suggestionTexts) {
            val node = findNodeWithText(rootNode, suggestion)
            if (node != null) {
                if (smartClick(node)) {
                    AppLogger.automation(TAG, "Selected: '$suggestion'", state = "SUGGESTION_SELECTED")

                    if (isForPickup) {
                        pickupPhaseComplete = true
                    }

                    node.recycle()
                    Thread.sleep(TimingConfig.animationWait)
                    return AutomationResult(true, "Selected suggestion")
                }
                node.recycle()
            }
        }

        return AutomationResult(false, "Could not select suggestion", shouldRetry = true)
    }

    // ============================================================
    // INTERMEDIATE SCREENS
    // ============================================================

    override fun handleIntermediateScreens(rootNode: AccessibilityNodeInfo): AutomationResult {
        val allText = getAllTextFromNode(rootNode)

        // "Select Address" / "Pin your location" screen
        val onSelectAddress = allText.any {
            it.lowercase().contains("select address") ||
            it.lowercase().contains("اختر العنوان") ||
            it.lowercase().contains("pin your location")
        }

        if (onSelectAddress) {
            return handleSelectAddressScreen(rootNode)
        }

        return AutomationResult(false, "No intermediate screen", shouldRetry = false)
    }

    private fun handleSelectAddressScreen(rootNode: AccessibilityNodeInfo): AutomationResult {
        AppLogger.automation(TAG, "Handling 'Select Address' screen", state = "SELECT_ADDRESS")

        val currentTime = System.currentTimeMillis()
        if (currentTime - lastSelectAddressClickTime < 2000) {
            return AutomationResult(false, "Cooldown", shouldRetry = true)
        }

        // Look for "Select Address" or "Confirm" button
        val confirmTexts = listOf("Select Address", "Confirm", "تأكيد", "اختر العنوان", "OK")
        for (text in confirmTexts) {
            val node = findNodeWithText(rootNode, text)
            if (node != null) {
                if (smartClick(node)) {
                    AppLogger.automation(TAG, "Clicked '$text'", state = "ADDRESS_CONFIRMED")
                    lastSelectAddressClickTime = currentTime
                    node.recycle()
                    Thread.sleep(500)
                    return AutomationResult(true, "Confirmed address")
                }
                node.recycle()
            }
        }

        return AutomationResult(false, "Could not confirm address", shouldRetry = true)
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

            // DiDi vehicle types
            val vehicleTypes = listOf("DiDi Flex", "DiDi Express", "DiDi Wasalny", "Comfort")
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
            "Choose your driver", "Flex", "Wasalny",
            "EGP", "ج.م", "Request"
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

    private fun findDiDiClickableSearchElement(node: AccessibilityNodeInfo): Boolean {
        val text = node.text?.toString()?.lowercase() ?: ""
        val contentDesc = node.contentDescription?.toString()?.lowercase() ?: ""
        val className = node.className?.toString()?.lowercase() ?: ""

        val isSearchLike = text.contains("where") || text.contains("destination") ||
            text.contains("search") || contentDesc.contains("where") ||
            contentDesc.contains("tap to enter") ||
            className.contains("button") || className.contains("searchview")

        if (isSearchLike) {
            if (smartClick(node)) {
                return true
            }
        }

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
}
