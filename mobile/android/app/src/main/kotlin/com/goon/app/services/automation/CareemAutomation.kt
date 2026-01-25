package com.goon.app.services.automation

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import com.goon.app.services.config.AppConstants
import com.goon.app.services.config.TimingConfig
import com.goon.app.services.config.PricePatterns
import com.goon.app.services.utils.GestureHelper
import com.goon.app.services.utils.NodeFinder
import com.goon.app.utils.AppLogger

/**
 * أتمتة تطبيق كريم
 * Careem app automation
 *
 * OPTIMIZED FLOW (with deep link):
 * DropOffGeoDeeplinkActivity opens → Destination pre-searched → Click suggestion →
 * Pickup screen → Confirm pickup → Get Prices
 *
 * FALLBACK FLOW (without deep link):
 * Home → Click "سيارة" → Click Pickup → Enter Pickup → Select Pickup Suggestion →
 * Click Destination → Enter Destination → Select Destination Suggestion → Get Price
 *
 * The deep link (geo:0,0?q=LAT,LNG) opens directly to destination picker with
 * coordinates pre-searched, cutting 12 steps down to ~4 steps!
 */
class CareemAutomation(
    service: AccessibilityService
) : BaseAutomation(service, TAG) {

    companion object {
        private const val TAG = "CareemAutomation"
    }

    // ============================================================
    // ABSTRACT PROPERTIES
    // ============================================================

    override val packageName: String = AppConstants.Packages.CAREEM
    override val displayName: String = "كريم"
    override val supportsFullDeepLink: Boolean = false  // Partial support via DropOffGeoDeeplinkActivity
    override val requiresPickupFirst: Boolean = false   // Changed: Now destination first with deep link!

    // ============================================================
    // STATE TRACKING
    // ============================================================

    private var carButtonClicked = false
    private var carButtonClickAttempts = 0
    private var searchBarClicked = false
    private var searchBarClickAttempts = 0
    private var pickupFieldClicked = false
    private var pickupEntered = false
    private var pickupSuggestionClicked = false
    private var pickupClickAttempts = 0
    private var loaderFirstSeenTime = 0L
    private var useCurrentLocationAttempted = false
    private var noValidPickupSuggestionCount = 0
    private var pickupButtonFallbackAttempted = false
    private var pickupPhaseComplete = false
    private var destinationSuggestionClicked = false
    private var destinationClickAttempts = 0
    private var destinationMismatchRetries = 0
    private var positionBasedClickAttempt = 0
    private var gestureClickAttempt = 0

    // Deep link optimization state
    private var deepLinkModeDetected = false
    private var destinationPickerScreenDetected = false

    // Trip details (set by caller)
    var pickupAddress: String = ""
    var destinationAddress: String = ""
    var pickupLat: Double = 0.0
    var pickupLng: Double = 0.0
    var destLat: Double = 0.0
    var destLng: Double = 0.0

    /**
     * إعادة تعيين الحالة
     * Reset state for new automation
     */
    fun resetState() {
        carButtonClicked = false
        carButtonClickAttempts = 0
        searchBarClicked = false
        searchBarClickAttempts = 0
        pickupFieldClicked = false
        pickupEntered = false
        pickupSuggestionClicked = false
        pickupClickAttempts = 0
        loaderFirstSeenTime = 0L
        useCurrentLocationAttempted = false
        noValidPickupSuggestionCount = 0
        pickupButtonFallbackAttempted = false
        pickupPhaseComplete = false
        destinationSuggestionClicked = false
        destinationClickAttempts = 0
        destinationMismatchRetries = 0
        positionBasedClickAttempt = 0
        gestureClickAttempt = 0
        deepLinkModeDetected = false
        destinationPickerScreenDetected = false
    }

    // ============================================================
    // FINDING PICKUP FIELD
    // ============================================================

    override fun findAndClickPickupField(rootNode: AccessibilityNodeInfo): AutomationResult {
        val flowType = if (deepLinkModeDetected) "DEEP_LINK" else "NORMAL"
        AppLogger.automation(TAG, "Finding pickup field ($flowType flow)", state = "FINDING_PICKUP")

        val allText = getAllTextFromNode(rootNode)

        // DEEP LINK MODE: Check if we're on pickup confirmation screen
        // (appears after selecting destination from deep link)
        if (deepLinkModeDetected && destinationSuggestionClicked) {
            // Check for pickup confirmation screen
            val isPickupConfirmScreen = allText.any { text ->
                normalizedContains(text, "تفاصيل نقطة الإنطلاق") ||
                normalizedContains(text, "تفاصيل نقطة الانطلاق") ||
                normalizedContains(text, "Pickup details") ||
                normalizedContains(text, "تأكيد الانطلاق") ||
                normalizedContains(text, "تأكيد الإنطلاق") ||
                normalizedContains(text, "Confirm pickup")
            }

            if (isPickupConfirmScreen) {
                AppLogger.automation(TAG, "On pickup confirmation screen (deep link mode)", state = "PICKUP_CONFIRM_SCREEN")

                // Click "تأكيد الانطلاق" (Confirm pickup) button
                val confirmTexts = listOf("تأكيد الانطلاق", "تأكيد الإنطلاق", "Confirm pickup", "تأكيد")
                for (confirmText in confirmTexts) {
                    val nodes = findAllNodesWithText(rootNode, confirmText)
                    for (node in nodes) {
                        if (smartClick(node) || careemGestureClick(node)) {
                            AppLogger.automation(TAG, "Clicked '$confirmText' button", state = "PICKUP_CONFIRMED")
                            pickupSuggestionClicked = true
                            pickupPhaseComplete = true
                            nodes.forEach { try { it.recycle() } catch (e: Exception) {} }
                            Thread.sleep(TimingConfig.animationWait)
                            return AutomationResult(true, "Confirmed pickup location (deep link mode)")
                        }
                    }
                    nodes.forEach { try { it.recycle() } catch (e: Exception) {} }
                }

                pickupClickAttempts++
                return AutomationResult(false, "Could not click confirm pickup", shouldRetry = pickupClickAttempts < 5)
            }
        }

        // STEP 1: Check if we're on Careem HOME screen (fallback mode)
        val isOnHomeScreen = isHomeScreen(allText)

        AppLogger.d(TAG, "Home screen detection: isOnHomeScreen=$isOnHomeScreen, carButtonClicked=$carButtonClicked")

        // If on home screen, click "سيارة" button first
        if (isOnHomeScreen && !carButtonClicked) {
            AppLogger.automation(TAG, "On HOME screen - need to click 'سيارة'", state = "CLICK_CAR_BUTTON")

            val carButtonTexts = listOf("سيارة", "سياره", "Car", "Ride")
            for (buttonText in carButtonTexts) {
                val nodes = findAllNodesWithNormalizedText(rootNode, buttonText)
                if (nodes.isNotEmpty()) {
                    AppLogger.d(TAG, "Found ${nodes.size} nodes for '$buttonText'")
                    for (node in nodes) {
                        if (smartClick(node) || careemGestureClick(node)) {
                            AppLogger.automation(TAG, "Clicked '$buttonText' button", state = "CAR_BUTTON_CLICKED")
                            carButtonClicked = true
                            carButtonClickAttempts = 0
                            nodes.forEach { try { it.recycle() } catch (e: Exception) {} }
                            Thread.sleep(TimingConfig.animationWait)
                            return AutomationResult(false, "Clicked car button, waiting for screen", shouldRetry = true)
                        }
                    }
                    nodes.forEach { try { it.recycle() } catch (e: Exception) {} }
                }
            }

            carButtonClickAttempts++
            if (carButtonClickAttempts >= 5) {
                AppLogger.w(TAG, "Max car button attempts - marking as clicked")
                carButtonClicked = true
            }
            return AutomationResult(false, "Could not click car button", shouldRetry = true)
        }

        // STEP 2: On ride booking screen - find and click PICKUP field
        AppLogger.automation(TAG, "On ride booking screen - looking for PICKUP field", state = "FIND_PICKUP_FIELD")

        // Strategy 1: Look for "pickUp" element
        val pickupNode = findNodeWithText(rootNode, "pickUp")
        if (pickupNode != null) {
            if (clickNodeOrParent(pickupNode) || careemGestureClick(pickupNode)) {
                AppLogger.automation(TAG, "Clicked 'pickUp' node", state = "PICKUP_FIELD_CLICKED")
                pickupNode.recycle()
                Thread.sleep(500)
                return AutomationResult(true, "Found and clicked pickup field")
            }
            pickupNode.recycle()
        }

        // Strategy 2: Look for pickup-related text
        val pickupTexts = listOf("البيت", "Home", "من أين", "Pickup", "من", "نقطة الانطلاق")
        for (pickupText in pickupTexts) {
            val nodes = findAllNodesWithText(rootNode, pickupText)
            if (nodes.isNotEmpty()) {
                for (node in nodes) {
                    val nodeText = node.text?.toString() ?: ""
                    val destKeywords = destinationAddress.split(" ").filter { it.length > 2 }
                    val isDestinationRelated = destKeywords.any { nodeText.contains(it) }

                    if (!isDestinationRelated) {
                        if (clickNodeOrParent(node) || careemGestureClick(node)) {
                            AppLogger.automation(TAG, "Clicked '$pickupText' node", state = "PICKUP_FIELD_CLICKED")
                            nodes.forEach { it.recycle() }
                            Thread.sleep(500)
                            return AutomationResult(true, "Found and clicked pickup field")
                        }
                    }
                }
                nodes.forEach { it.recycle() }
            }
        }

        // Strategy 3: Find EditTexts and click the TOP one
        val editTexts = mutableListOf<AccessibilityNodeInfo>()
        findAllEditTexts(rootNode, editTexts)

        if (editTexts.isNotEmpty()) {
            val sortedEditTexts = editTexts.sortedBy { node ->
                val rect = Rect()
                node.getBoundsInScreen(rect)
                rect.top
            }

            val topEditText = sortedEditTexts.first()
            if (topEditText.performAction(AccessibilityNodeInfo.ACTION_FOCUS) ||
                topEditText.performAction(AccessibilityNodeInfo.ACTION_CLICK) ||
                careemGestureClick(topEditText)) {
                AppLogger.automation(TAG, "Clicked top EditText", state = "PICKUP_FIELD_CLICKED")
                editTexts.forEach { try { it.recycle() } catch (e: Exception) {} }
                Thread.sleep(300)
                return AutomationResult(true, "Clicked top EditText as pickup field")
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
        AppLogger.automation(TAG, "Entering pickup text: '$address'", state = "ENTERING_PICKUP")

        val editTexts = mutableListOf<AccessibilityNodeInfo>()
        findAllEditTexts(rootNode, editTexts)

        // Sort by Y position - pickup field is usually at the TOP
        val sortedEditTexts = editTexts.sortedBy { node ->
            val rect = Rect()
            node.getBoundsInScreen(rect)
            rect.top
        }

        for (editText in sortedEditTexts) {
            val currentText = editText.text?.toString() ?: ""

            // Skip if this field contains destination-related text
            val destKeywords = destinationAddress.split(" ").filter { it.length > 3 }
            val hasDestination = destKeywords.any { currentText.contains(it) }

            if (hasDestination) {
                continue
            }

            // Clear existing text
            clearTextField(editText)
            Thread.sleep(100)

            // Enter pickup address
            if (enterTextInField(editText, address)) {
                AppLogger.automation(TAG, "Entered pickup address", state = "PICKUP_ENTERED")
                pickupEntered = true
                pickupFieldClicked = true
                editTexts.forEach { try { it.recycle() } catch (e: Exception) {} }
                Thread.sleep(TimingConfig.textInputDelay)
                return AutomationResult(true, "Entered pickup address")
            }
        }

        editTexts.forEach { try { it.recycle() } catch (e: Exception) {} }

        // Fallback: try focused input
        val focusedInput = findFocusedEditText(rootNode)
        if (focusedInput != null) {
            if (enterTextInField(focusedInput, address)) {
                AppLogger.automation(TAG, "Entered pickup via focused field", state = "PICKUP_ENTERED")
                pickupEntered = true
                focusedInput.recycle()
                Thread.sleep(TimingConfig.textInputDelay)
                return AutomationResult(true, "Entered pickup via focused field")
            }
            focusedInput.recycle()
        }

        return AutomationResult(false, "Could not enter pickup address", shouldRetry = true)
    }

    // ============================================================
    // FINDING DESTINATION FIELD
    // ============================================================

    override fun findAndClickDestinationField(rootNode: AccessibilityNodeInfo): AutomationResult {
        val phase = if (pickupPhaseComplete) "PICKUP_DONE" else "INITIAL"
        AppLogger.automation(TAG, "Finding destination field ($phase)", state = "FINDING_DESTINATION")

        val allText = getAllTextFromNode(rootNode)

        // DEEP LINK OPTIMIZATION: Check if we're on destination picker screen
        // (opened via DropOffGeoDeeplinkActivity with coordinates pre-searched)
        if (!deepLinkModeDetected && isDestinationPickerScreen(allText)) {
            deepLinkModeDetected = true
            destinationPickerScreenDetected = true
            AppLogger.automation(TAG, "Deep link mode detected - destination picker screen!", state = "DEEP_LINK_MODE")

            // Try to click the first destination suggestion (coordinates should already be searched)
            val suggestionResult = clickFirstDestinationSuggestion(rootNode, allText)
            if (suggestionResult.success) {
                destinationSuggestionClicked = true
                return suggestionResult
            }
        }

        // If deep link mode was detected but suggestion not clicked yet, keep trying
        if (deepLinkModeDetected && !destinationSuggestionClicked) {
            val suggestionResult = clickFirstDestinationSuggestion(rootNode, allText)
            if (suggestionResult.success) {
                destinationSuggestionClicked = true
                return suggestionResult
            }
        }

        // If on home screen, click car button first (fallback mode)
        val isOnHomeScreen = isHomeScreen(allText)
        if (isOnHomeScreen && !carButtonClicked) {
            return clickCarButton(rootNode)
        }

        // Strategy 1: Find "إلى أين؟" or destination field
        val destTexts = listOf("إلى أين؟", "Where to?", "الوجهة", "Destination", "إلى")
        for (destText in destTexts) {
            val nodes = findAllNodesWithText(rootNode, destText)
            for (node in nodes) {
                if (smartClick(node) || careemGestureClick(node)) {
                    AppLogger.automation(TAG, "Clicked '$destText'", state = "DEST_FIELD_CLICKED")
                    nodes.forEach { it.recycle() }
                    Thread.sleep(500)
                    return AutomationResult(true, "Clicked destination field")
                }
            }
            nodes.forEach { it.recycle() }
        }

        // Strategy 2: Find EditTexts and click SECOND one (destination)
        val editTexts = mutableListOf<AccessibilityNodeInfo>()
        findAllEditTexts(rootNode, editTexts)

        if (editTexts.size >= 2) {
            val sortedEditTexts = editTexts.sortedBy { node ->
                val rect = Rect()
                node.getBoundsInScreen(rect)
                rect.top
            }

            // Second EditText should be destination
            val destEditText = sortedEditTexts[1]
            if (destEditText.performAction(AccessibilityNodeInfo.ACTION_FOCUS) ||
                destEditText.performAction(AccessibilityNodeInfo.ACTION_CLICK) ||
                careemGestureClick(destEditText)) {
                AppLogger.automation(TAG, "Clicked second EditText", state = "DEST_FIELD_CLICKED")
                editTexts.forEach { try { it.recycle() } catch (e: Exception) {} }
                Thread.sleep(300)
                return AutomationResult(true, "Clicked second EditText as destination")
            }
        }
        editTexts.forEach { try { it.recycle() } catch (e: Exception) {} }

        return AutomationResult(false, "Could not find destination field", shouldRetry = true)
    }

    /**
     * Check if we're on the destination picker screen (opened via deep link)
     * This screen shows "إلى أين؟" with search field and suggestions
     */
    private fun isDestinationPickerScreen(allText: List<String>): Boolean {
        val hasDestinationHeader = allText.any { text ->
            normalizedContains(text, "إلى أين") ||
            normalizedContains(text, "Where to")
        }

        val hasSearchPrompt = allText.any { text ->
            normalizedContains(text, "أدخل وجهتك") ||
            normalizedContains(text, "Enter your destination")
        }

        // Check if coordinates are in search (from deep link)
        val hasCoordinates = allText.any { text ->
            text.matches(Regex(".*\\d+\\.\\d+.*\\d+\\.\\d+.*"))
        }

        // Not on home screen (no car button with other services)
        val notOnHomeScreen = !allText.any { text ->
            normalizedContains(text, "حوّل محلياً") ||
            normalizedContains(text, "طعام") ||
            normalizedContains(text, "Super App")
        }

        return (hasDestinationHeader || hasSearchPrompt || hasCoordinates) && notOnHomeScreen
    }

    /**
     * Click the first destination suggestion when in deep link mode
     * The coordinates are pre-searched, so we just need to click the first result
     */
    private fun clickFirstDestinationSuggestion(
        rootNode: AccessibilityNodeInfo,
        allText: List<String>
    ): AutomationResult {
        AppLogger.automation(TAG, "Clicking first destination suggestion (deep link mode)", state = "CLICK_DEST_SUGGESTION")

        // Look for address-like text (from the coordinate search results)
        val addressPatterns = allText.filter { text ->
            text.length > 10 &&
            !normalizedContains(text, "إلى أين") &&
            !normalizedContains(text, "Where to") &&
            !normalizedContains(text, "أدخل وجهتك") &&
            !normalizedContains(text, "تخطي") &&
            !normalizedContains(text, "Skip") &&
            (text.contains("شارع") || text.contains("ش.") ||
             text.contains("ميدان") || text.contains("منطقة") ||
             text.contains("حي") || text.contains("مصر") ||
             text.contains("Egypt") || text.contains("Cairo") ||
             text.contains("كم") || text.contains("km") ||
             text.contains("Mohammed") || text.contains("محمد") ||
             text.contains(",") && text.length > 15)
        }

        AppLogger.d(TAG, "Found ${addressPatterns.size} address-like suggestions")

        if (addressPatterns.isNotEmpty()) {
            for (address in addressPatterns) {
                val nodes = findAllNodesWithText(rootNode, address)
                for (node in nodes) {
                    if (careemGestureClickLong(node) || smartClick(node)) {
                        AppLogger.automation(TAG, "Clicked destination suggestion: '$address'", state = "DEST_SUGGESTION_CLICKED")
                        nodes.forEach { try { it.recycle() } catch (e: Exception) {} }
                        Thread.sleep(TimingConfig.animationWait)
                        return AutomationResult(true, "Clicked destination suggestion from deep link")
                    }
                }
                nodes.forEach { try { it.recycle() } catch (e: Exception) {} }
            }
        }

        // Fallback: Try clicking any item that looks like a suggestion (has distance indicator)
        val distancePatterns = allText.filter { text ->
            text.contains("كيلومتر") || text.contains("km") || text.contains("متر")
        }

        for (distText in distancePatterns) {
            val nodes = findAllNodesWithText(rootNode, distText)
            for (node in nodes) {
                // Click the parent which is likely the suggestion item
                if (clickNodeOrParent(node) || careemGestureClick(node)) {
                    AppLogger.automation(TAG, "Clicked suggestion by distance: '$distText'", state = "DEST_SUGGESTION_CLICKED")
                    nodes.forEach { try { it.recycle() } catch (e: Exception) {} }
                    Thread.sleep(TimingConfig.animationWait)
                    return AutomationResult(true, "Clicked destination by distance indicator")
                }
            }
            nodes.forEach { try { it.recycle() } catch (e: Exception) {} }
        }

        destinationClickAttempts++
        return AutomationResult(false, "Could not click destination suggestion", shouldRetry = destinationClickAttempts < 5)
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
        AppLogger.automation(TAG, "Entering destination text: '$address'", state = "ENTERING_DESTINATION")

        val editTexts = mutableListOf<AccessibilityNodeInfo>()
        findAllEditTexts(rootNode, editTexts)

        // Sort by Y position - destination is usually SECOND
        val sortedEditTexts = editTexts.sortedBy { node ->
            val rect = Rect()
            node.getBoundsInScreen(rect)
            rect.top
        }

        // Try the second EditText first (if available), then others
        val orderedEditTexts = if (sortedEditTexts.size >= 2) {
            listOf(sortedEditTexts[1]) + sortedEditTexts.filter { it != sortedEditTexts[1] }
        } else {
            sortedEditTexts
        }

        for (editText in orderedEditTexts) {
            val currentText = editText.text?.toString() ?: ""

            // Skip if this contains pickup keywords
            val pickupKeywords = pickupAddress.split(" ").filter { it.length > 3 }
            val hasPickup = pickupKeywords.any { currentText.contains(it) }

            if (hasPickup) continue

            // Clear and enter text
            clearTextField(editText)
            Thread.sleep(100)

            if (enterTextInField(editText, address)) {
                AppLogger.automation(TAG, "Entered destination address", state = "DESTINATION_ENTERED")
                editTexts.forEach { try { it.recycle() } catch (e: Exception) {} }
                Thread.sleep(TimingConfig.textInputDelay)
                return AutomationResult(true, "Entered destination address")
            }
        }

        editTexts.forEach { try { it.recycle() } catch (e: Exception) {} }

        // Fallback: focused input
        val focusedInput = findFocusedEditText(rootNode)
        if (focusedInput != null) {
            if (enterTextInField(focusedInput, address)) {
                AppLogger.automation(TAG, "Entered destination via focused field", state = "DESTINATION_ENTERED")
                focusedInput.recycle()
                Thread.sleep(TimingConfig.textInputDelay)
                return AutomationResult(true, "Entered destination via focused field")
            }
            focusedInput.recycle()
        }

        return AutomationResult(false, "Could not enter destination", shouldRetry = true)
    }

    // ============================================================
    // SELECTING SUGGESTION
    // ============================================================

    override fun selectSuggestion(rootNode: AccessibilityNodeInfo, isForPickup: Boolean): AutomationResult {
        val phase = if (isForPickup) "PICKUP" else "DESTINATION"
        AppLogger.automation(TAG, "Selecting $phase suggestion", state = "SELECTING_SUGGESTION")

        val targetAddress = if (isForPickup) pickupAddress else destinationAddress
        val keywords = targetAddress.split(" ").filter { it.length > 2 }

        val allText = getAllTextFromNode(rootNode)

        // Find suggestion that matches our keywords
        for (text in allText) {
            val matchCount = keywords.count { text.contains(it, ignoreCase = true) }
            if (matchCount >= 1) {
                val nodes = findAllNodesWithText(rootNode, text)
                for (node in nodes) {
                    // Use gesture click with longer duration for Careem
                    if (careemGestureClickLong(node) || smartClick(node)) {
                        AppLogger.automation(TAG, "Selected suggestion: '$text'", state = "SUGGESTION_SELECTED")

                        if (isForPickup) {
                            pickupSuggestionClicked = true
                            pickupPhaseComplete = true
                            pickupFieldClicked = false
                        } else {
                            destinationSuggestionClicked = true
                        }

                        nodes.forEach { it.recycle() }
                        Thread.sleep(TimingConfig.animationWait)
                        return AutomationResult(true, "Selected suggestion")
                    }
                }
                nodes.forEach { it.recycle() }
            }
        }

        // Try "Use my current location" fallback for pickup
        if (isForPickup && !useCurrentLocationAttempted) {
            val currentLocationResult = tryUseCurrentLocation(rootNode)
            if (currentLocationResult.success) {
                return currentLocationResult
            }
            useCurrentLocationAttempted = true
        }

        // Position-based click fallback
        positionBasedClickAttempt++
        if (positionBasedClickAttempt <= 3) {
            val positionResult = tryPositionBasedClick(rootNode)
            if (positionResult.success) {
                return positionResult
            }
        }

        return AutomationResult(false, "Could not select suggestion", shouldRetry = true)
    }

    // ============================================================
    // INTERMEDIATE SCREENS
    // ============================================================

    override fun handleIntermediateScreens(rootNode: AccessibilityNodeInfo): AutomationResult {
        val allText = getAllTextFromNode(rootNode)

        // Check for loader
        val hasLoader = allText.any {
            it.contains("جارٍ") || it.contains("Loading") || it.contains("يرجى الانتظار")
        }

        if (hasLoader) {
            if (loaderFirstSeenTime == 0L) {
                loaderFirstSeenTime = System.currentTimeMillis()
            }
            val elapsed = System.currentTimeMillis() - loaderFirstSeenTime
            if (elapsed > 10000) {
                AppLogger.w(TAG, "Loader timeout exceeded")
                loaderFirstSeenTime = 0L
                return AutomationResult(false, "Loader timeout", shouldRetry = false)
            }
            return AutomationResult(false, "Loader visible", shouldRetry = true)
        }

        loaderFirstSeenTime = 0L
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

            // Try to extract vehicle type
            val vehicleTypes = listOf("GO", "GO Plus", "GO Comfort", "Business", "MAX")
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

        // Check for price screen indicators
        val priceIndicators = listOf(
            "اختر رحلتك", "Choose your ride",
            "GO", "GO Plus", "Business",
            "EGP", "ج.م",
            "احجز الآن", "Book now"
        )

        val hasIndicators = priceIndicators.any { indicator ->
            allText.any { it.contains(indicator, ignoreCase = true) }
        }

        // Check for actual price pattern
        val hasPrice = allText.any { text ->
            PricePatterns.extractPriceForApp(text, packageName) != null
        }

        return hasIndicators && hasPrice
    }

    // ============================================================
    // HELPER METHODS
    // ============================================================

    private fun isHomeScreen(allText: List<String>): Boolean {
        val hasHomeIndicators = allText.any { text ->
            normalizedContains(text, "Get more with Careem") ||
            normalizedContains(text, "أدخل كود الخصم") ||
            normalizedContains(text, "Super App") ||
            normalizedContains(text, "Careem+") ||
            normalizedContains(text, "كريم+") ||
            normalizedContains(text, "طعام") ||
            normalizedContains(text, "Bike") ||
            normalizedContains(text, "توصيل")
        }

        val hasCarButton = allText.any { text ->
            normalizedContains(text, "سيارة") || normalizedContains(text, "سياره")
        }
        val hasOtherServices = allText.any { text ->
            normalizedContains(text, "حوّل محلياً") ||
            normalizedContains(text, "حول محليا") ||
            normalizedContains(text, "طلب المال") ||
            normalizedContains(text, "طعام")
        }

        return hasHomeIndicators || (hasCarButton && hasOtherServices)
    }

    private fun clickCarButton(rootNode: AccessibilityNodeInfo): AutomationResult {
        val carButtonTexts = listOf("سيارة", "سياره", "Car", "Ride")
        for (buttonText in carButtonTexts) {
            val nodes = findAllNodesWithNormalizedText(rootNode, buttonText)
            for (node in nodes) {
                if (smartClick(node) || careemGestureClick(node)) {
                    carButtonClicked = true
                    nodes.forEach { try { it.recycle() } catch (e: Exception) {} }
                    Thread.sleep(TimingConfig.animationWait)
                    return AutomationResult(false, "Clicked car button", shouldRetry = true)
                }
            }
            nodes.forEach { try { it.recycle() } catch (e: Exception) {} }
        }

        carButtonClickAttempts++
        if (carButtonClickAttempts >= 5) {
            carButtonClicked = true
        }
        return AutomationResult(false, "Could not click car button", shouldRetry = true)
    }

    private fun tryUseCurrentLocation(rootNode: AccessibilityNodeInfo): AutomationResult {
        val locationTexts = listOf(
            "استخدم موقعي الحالي",
            "موقعي الحالي",
            "Use my current location",
            "Current location"
        )

        for (locText in locationTexts) {
            val node = findNodeWithText(rootNode, locText)
            if (node != null) {
                if (careemGestureClick(node) || smartClick(node)) {
                    AppLogger.automation(TAG, "Clicked '$locText'", state = "USE_CURRENT_LOCATION")
                    pickupSuggestionClicked = true
                    node.recycle()
                    Thread.sleep(800)
                    return AutomationResult(true, "Used current location")
                }
                node.recycle()
            }
        }

        return AutomationResult(false, "Could not use current location", shouldRetry = false)
    }

    private fun tryPositionBasedClick(rootNode: AccessibilityNodeInfo): AutomationResult {
        // Find all visible suggestion-like items
        val allText = getAllTextFromNode(rootNode)

        // Look for text that looks like an address
        val addressPatterns = allText.filter { text ->
            text.length > 10 &&
            !text.contains("استخدم") &&
            !text.contains("Use") &&
            (text.contains("شارع") || text.contains("ش.") ||
             text.contains("ميدان") || text.contains("منطقة") ||
             text.contains("حي") || text.contains("مصر") ||
             text.contains("Egypt") || text.contains("Cairo") ||
             text.contains("كم") || text.contains("km"))
        }

        if (addressPatterns.isNotEmpty()) {
            val firstAddress = addressPatterns.first()
            val nodes = findAllNodesWithText(rootNode, firstAddress)
            for (node in nodes) {
                if (careemGestureClickLong(node)) {
                    AppLogger.automation(TAG, "Position click on: '$firstAddress'", state = "POSITION_CLICK")
                    nodes.forEach { it.recycle() }
                    Thread.sleep(500)
                    return AutomationResult(true, "Position-based click success")
                }
            }
            nodes.forEach { it.recycle() }
        }

        return AutomationResult(false, "Position-based click failed", shouldRetry = false)
    }

    /**
     * Careem-specific gesture click
     */
    private fun careemGestureClick(node: AccessibilityNodeInfo): Boolean {
        val rect = Rect()
        node.getBoundsInScreen(rect)

        if (rect.width() <= 0 || rect.height() <= 0) return false

        return clickAtPosition(rect.centerX().toFloat(), rect.centerY().toFloat(), 100)
    }

    /**
     * Careem-specific long gesture click (for suggestion selection)
     */
    private fun careemGestureClickLong(node: AccessibilityNodeInfo): Boolean {
        val rect = Rect()
        node.getBoundsInScreen(rect)

        if (rect.width() <= 0 || rect.height() <= 0) return false

        // Try multiple positions with longer duration
        val positions = listOf(
            Pair(rect.centerX().toFloat(), rect.centerY().toFloat()),
            Pair(rect.centerX().toFloat(), rect.top + rect.height() * 0.33f),
            Pair(rect.centerX().toFloat(), rect.top + rect.height() * 0.67f)
        )

        for ((x, y) in positions) {
            if (clickAtPosition(x, y, 200)) {
                return true
            }
        }

        return false
    }

    private fun clickNodeOrParent(node: AccessibilityNodeInfo): Boolean {
        if (node.isClickable) {
            if (node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                return true
            }
        }

        var parent = node.parent
        for (level in 1..5) {
            if (parent == null) break
            if (parent.isClickable) {
                if (parent.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                    parent.recycle()
                    return true
                }
            }
            val next = parent.parent
            parent.recycle()
            parent = next
        }

        return false
    }
}
