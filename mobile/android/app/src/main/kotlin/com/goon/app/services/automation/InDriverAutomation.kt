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
 * أتمتة تطبيق إندرايفر
 * InDriver app automation
 *
 * Flow: Map → Enter Pickup → Enter Destination → Click "تم" (multiple times) → Get Price
 *
 * IMPORTANT:
 * - InDriver requires PICKUP FIRST
 * - May show "No Results" → need to click "Choose on map"
 * - Requires multiple "تم" (Done) clicks to confirm
 * - Price detection is BLOCKED until automation completes to avoid caching default price
 */
class InDriverAutomation(
    service: AccessibilityService
) : BaseAutomation(service, TAG) {

    companion object {
        private const val TAG = "InDriverAutomation"
    }

    // ============================================================
    // ABSTRACT PROPERTIES
    // ============================================================

    override val packageName: String = AppConstants.Packages.INDRIVER
    override val displayName: String = "إندرايفر"
    override val supportsFullDeepLink: Boolean = false
    override val requiresPickupFirst: Boolean = true

    // ============================================================
    // STATE TRACKING
    // ============================================================

    private var pickupEntered = false
    private var destinationEntered = false
    private var doneClickedTime = 0L
    private var doneClickCount = 0
    private var automationComplete = false
    private var lastClickTime = 0L
    private val clickCooldown = 1500L  // InDriver crashes with rapid clicks

    // Trip details
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
        pickupEntered = false
        destinationEntered = false
        doneClickedTime = 0L
        doneClickCount = 0
        automationComplete = false
        lastClickTime = 0L
    }

    /**
     * هل اكتملت الأتمتة؟ (للتحكم في اكتشاف السعر)
     * Is automation complete? (for price detection control)
     */
    fun isAutomationComplete(): Boolean = automationComplete

    // ============================================================
    // FINDING PICKUP FIELD
    // ============================================================

    override fun findAndClickPickupField(rootNode: AccessibilityNodeInfo): AutomationResult {
        AppLogger.automation(TAG, "Finding pickup field ('من')", state = "FINDING_PICKUP")

        // Debug: Log all visible text
        logAllVisibleText(rootNode, "PICKUP_SEARCH")

        // Strategy 1: Find by resource ID "address_edittext_from"
        AppLogger.d(TAG, "Strategy 1: Looking for address_edittext_from by resource ID")
        val pickupFieldIds = listOf(
            "address_edittext_from",
            "edittext_from",
            "from_input"
        )
        val nodeById = NodeFinder.findByResourceId(rootNode, pickupFieldIds)
        if (nodeById != null) {
            if (nodeById.performAction(AccessibilityNodeInfo.ACTION_CLICK) ||
                GestureHelper.clickNodeOrParent(nodeById)) {
                AppLogger.automation(TAG, "Clicked pickup field by ID", state = "PICKUP_CLICKED")
                nodeById.recycle()
                return AutomationResult(true, "Found pickup field by ID")
            }
            nodeById.recycle()
        }

        // Strategy 2: Find "من" text
        val pickupTexts = listOf("من", "From", "نقطة الإقلال", "من أين")
        for (text in pickupTexts) {
            val nodes = findAllNodesWithText(rootNode, text)
            for (node in nodes) {
                val nodeText = node.text?.toString() ?: ""
                // Skip if contains coordinates (already filled)
                if (nodeText.contains(",") && nodeText.matches(Regex(".*\\d+\\.\\d+.*"))) {
                    node.recycle()
                    continue
                }

                if (gentleClick(node)) {
                    AppLogger.automation(TAG, "Clicked '$text' field", state = "PICKUP_CLICKED")
                    node.recycle()
                    return AutomationResult(true, "Found pickup field")
                }
                node.recycle()
            }
        }

        // Strategy 3: Find EditTexts and use the first one
        val editTexts = mutableListOf<AccessibilityNodeInfo>()
        findAllEditTexts(rootNode, editTexts)

        if (editTexts.isNotEmpty()) {
            val sortedEditTexts = editTexts.sortedBy { node ->
                val rect = Rect()
                node.getBoundsInScreen(rect)
                rect.top
            }

            val firstEditText = sortedEditTexts.first()
            val text = firstEditText.text?.toString() ?: ""

            // Skip if already has coordinates
            if (!text.contains(",") || !text.matches(Regex(".*\\d+\\.\\d+.*"))) {
                if (firstEditText.performAction(AccessibilityNodeInfo.ACTION_FOCUS)) {
                    AppLogger.automation(TAG, "Focused first EditText", state = "PICKUP_CLICKED")
                    editTexts.forEach { try { it.recycle() } catch (e: Exception) {} }
                    Thread.sleep(300)
                    return AutomationResult(true, "Found pickup EditText")
                }
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
        AppLogger.automation(TAG, "Entering pickup: '$address' (coords: $lat, $lng)", state = "ENTERING_PICKUP")

        // For InDriver, we use coordinates
        val coordsText = "$lat, $lng"

        val editTexts = mutableListOf<AccessibilityNodeInfo>()
        findAllEditTexts(rootNode, editTexts)

        val sortedEditTexts = editTexts.sortedBy { node ->
            val rect = Rect()
            node.getBoundsInScreen(rect)
            rect.top
        }

        for (editText in sortedEditTexts) {
            val currentText = editText.text?.toString() ?: ""

            // Skip if already has destination-like content
            if (currentText.contains(destinationAddress.take(10))) continue

            // AGGRESSIVE CLEAR: Multiple attempts to clear the field
            // InDriver may have cached/saved location that needs forceful clearing
            repeat(3) {
                clearTextField(editText)
                Thread.sleep(50)
            }

            // Also try selecting all and focusing
            editText.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
            Thread.sleep(50)

            // Set empty text explicitly
            val clearBundle = Bundle()
            clearBundle.putCharSequence(
                AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                ""
            )
            editText.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, clearBundle)
            Thread.sleep(100)

            if (enterTextInField(editText, coordsText)) {
                AppLogger.automation(TAG, "Entered pickup coordinates: $coordsText", state = "PICKUP_ENTERED")
                pickupEntered = true
                pickupLat = lat
                pickupLng = lng
                editTexts.forEach { try { it.recycle() } catch (e: Exception) {} }
                Thread.sleep(TimingConfig.textInputDelay)
                return AutomationResult(true, "Entered pickup")
            }
        }

        editTexts.forEach { try { it.recycle() } catch (e: Exception) {} }

        // Fallback: focused input
        val focusedInput = findFocusedEditText(rootNode)
        if (focusedInput != null) {
            // Aggressive clear for focused field too
            repeat(3) {
                clearTextField(focusedInput)
                Thread.sleep(50)
            }

            if (enterTextInField(focusedInput, coordsText)) {
                AppLogger.automation(TAG, "Entered pickup via focused field: $coordsText", state = "PICKUP_ENTERED")
                pickupEntered = true
                pickupLat = lat
                pickupLng = lng
                focusedInput.recycle()
                Thread.sleep(TimingConfig.textInputDelay)
                return AutomationResult(true, "Entered pickup via focused")
            }
            focusedInput.recycle()
        }

        return AutomationResult(false, "Could not enter pickup", shouldRetry = true)
    }

    // ============================================================
    // FINDING DESTINATION FIELD
    // ============================================================

    override fun findAndClickDestinationField(rootNode: AccessibilityNodeInfo): AutomationResult {
        AppLogger.automation(TAG, "Finding destination field ('إلى')", state = "FINDING_DESTINATION")

        // Debug
        logAllVisibleText(rootNode, "DEST_SEARCH")

        // Strategy 1: Find by resource ID "address_edittext_to"
        val destFieldIds = listOf("address_edittext_to", "edittext_to", "to_input")
        val nodeById = NodeFinder.findByResourceId(rootNode, destFieldIds)
        if (nodeById != null) {
            val text = nodeById.text?.toString() ?: ""
            // Make sure it's NOT the pickup field with coordinates
            if (!text.contains(",") || !text.matches(Regex(".*\\d+\\.\\d+.*"))) {
                if (nodeById.performAction(AccessibilityNodeInfo.ACTION_CLICK) ||
                    GestureHelper.clickNodeOrParent(nodeById)) {
                    AppLogger.automation(TAG, "Clicked destination by ID", state = "DEST_CLICKED")
                    nodeById.recycle()
                    return AutomationResult(true, "Found destination by ID")
                }
            }
            nodeById.recycle()
        }

        // Strategy 2: Find "إلى" text
        val destTexts = listOf("إلى", "To", "الوجهة", "ما الوجهة")
        for (text in destTexts) {
            val nodes = findAllNodesWithText(rootNode, text)
            for (node in nodes) {
                val nodeText = node.text?.toString() ?: ""
                // Skip if contains coordinates
                if (nodeText.contains(",") && nodeText.matches(Regex(".*\\d+\\.\\d+.*"))) {
                    node.recycle()
                    continue
                }

                if (gentleClick(node)) {
                    AppLogger.automation(TAG, "Clicked '$text' field", state = "DEST_CLICKED")
                    node.recycle()
                    return AutomationResult(true, "Found destination field")
                }
                node.recycle()
            }
        }

        // Strategy 3: Find second EditText
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
            val text = destEditText.text?.toString() ?: ""

            if (!text.contains(",") || !text.matches(Regex(".*\\d+\\.\\d+.*"))) {
                if (destEditText.performAction(AccessibilityNodeInfo.ACTION_FOCUS)) {
                    AppLogger.automation(TAG, "Focused second EditText", state = "DEST_CLICKED")
                    editTexts.forEach { try { it.recycle() } catch (e: Exception) {} }
                    Thread.sleep(300)
                    return AutomationResult(true, "Found destination EditText")
                }
            }
        }
        editTexts.forEach { try { it.recycle() } catch (e: Exception) {} }

        return AutomationResult(false, "Could not find destination field", shouldRetry = true)
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
        AppLogger.automation(TAG, "Entering destination: '$address' (coords: $lat, $lng)", state = "ENTERING_DESTINATION")

        val coordsText = "$lat, $lng"

        val editTexts = mutableListOf<AccessibilityNodeInfo>()
        findAllEditTexts(rootNode, editTexts)

        val sortedEditTexts = editTexts.sortedBy { node ->
            val rect = Rect()
            node.getBoundsInScreen(rect)
            rect.top
        }

        // Try second EditText first (destination)
        val orderedEditTexts = if (sortedEditTexts.size >= 2) {
            listOf(sortedEditTexts[1]) + sortedEditTexts.filter { it != sortedEditTexts[1] }
        } else {
            sortedEditTexts
        }

        for (editText in orderedEditTexts) {
            val currentText = editText.text?.toString() ?: ""

            // Skip pickup field
            if (currentText.contains(pickupLat.toString().take(5))) continue

            clearTextField(editText)
            Thread.sleep(100)

            if (enterTextInField(editText, coordsText)) {
                AppLogger.automation(TAG, "Entered destination coordinates", state = "DESTINATION_ENTERED")
                destinationEntered = true
                editTexts.forEach { try { it.recycle() } catch (e: Exception) {} }
                Thread.sleep(TimingConfig.textInputDelay)
                return AutomationResult(true, "Entered destination")
            }
        }

        editTexts.forEach { try { it.recycle() } catch (e: Exception) {} }

        // Fallback
        val focusedInput = findFocusedEditText(rootNode)
        if (focusedInput != null) {
            if (enterTextInField(focusedInput, coordsText)) {
                AppLogger.automation(TAG, "Entered destination via focused", state = "DESTINATION_ENTERED")
                destinationEntered = true
                focusedInput.recycle()
                Thread.sleep(TimingConfig.textInputDelay)
                return AutomationResult(true, "Entered destination via focused")
            }
            focusedInput.recycle()
        }

        return AutomationResult(false, "Could not enter destination", shouldRetry = true)
    }

    // ============================================================
    // SELECTING SUGGESTION (InDriver uses intermediate screens)
    // ============================================================

    override fun selectSuggestion(rootNode: AccessibilityNodeInfo, isForPickup: Boolean): AutomationResult {
        // InDriver doesn't have a traditional suggestion list
        // After entering coordinates, it shows intermediate screens
        // that need to be handled
        return handleIntermediateScreens(rootNode)
    }

    // ============================================================
    // INTERMEDIATE SCREENS (Critical for InDriver)
    // ============================================================

    override fun handleIntermediateScreens(rootNode: AccessibilityNodeInfo): AutomationResult {
        AppLogger.automation(TAG, "Handling intermediate screens", state = "INTERMEDIATE")

        val allText = getAllTextFromNode(rootNode)
        val currentTime = System.currentTimeMillis()

        // Cooldown check
        if (currentTime - lastClickTime < clickCooldown) {
            AppLogger.d(TAG, "InDriver cooldown active")
            return AutomationResult(false, "Cooldown", shouldRetry = true)
        }

        // ============================================================
        // STEP 0: Check if we're on PRICE SCREEN already
        // ============================================================
        val hasPriceScreen = allText.any {
            it.contains("البحث عن عروض") ||
            it.contains("الأجر المقترح") ||
            it.contains("اطلب رحلة") ||
            it.contains("السعر المقترح") ||
            it.contains("Search for offers") ||
            it.contains("Suggested fare")
        }

        val hasPriceIndicator = allText.any {
            it.contains("EGP") || it.matches(Regex(".*\\d+\\s*(جنيه|ج\\.م|EGP).*"))
        }

        val hasVehicleTypes = allText.count {
            it == "رحلة" || it == "مريحة" || it == "درجة نارية" || it == "بريميوم" ||
            it == "Economy" || it == "Comfort" || it == "Premium"
        } >= 2

        val isFinalPriceScreen = hasPriceScreen || (hasPriceIndicator && hasVehicleTypes)

        if (isFinalPriceScreen) {
            AppLogger.automation(TAG, "DETECTED PRICE SCREEN!", state = "PRICE_SCREEN")
            automationComplete = true
            return AutomationResult(true, "On price screen", nextState = AutomationState.WAITING_FOR_PRICE)
        }

        // ============================================================
        // STEP 1: Check for "No Results" screen
        // ============================================================
        val hasNoResults = allText.any {
            it.contains("لا توجد نتائج") ||
            it.contains("No results") ||
            it.contains("لا توجد")
        }

        if (hasNoResults) {
            AppLogger.automation(TAG, "Detected 'No Results' screen", state = "NO_RESULTS")

            val chooseMapTexts = listOf(
                "اختر على الخريطة",
                "Choose on map",
                "على الخريطة",
                "الخريطة"
            )

            for (chooseText in chooseMapTexts) {
                val node = findNodeWithText(rootNode, chooseText)
                if (node != null) {
                    if (gentleClick(node)) {
                        AppLogger.automation(TAG, "Clicked '$chooseText'", state = "CHOOSE_MAP")
                        lastClickTime = currentTime
                        node.recycle()
                        return AutomationResult(true, "Clicked choose on map")
                    }
                    node.recycle()
                }
            }

            return AutomationResult(false, "On No Results screen", shouldRetry = true)
        }

        // ============================================================
        // STEP 2: Check for suggestions screen
        // ============================================================
        val hasSuggestions = allText.any {
            it.contains("متر") || it.contains("كم") || it.contains("km") ||
            it.contains("Egypt") || it.contains("مصر")
        }

        if (hasSuggestions) {
            val result = tryClickFirstSuggestion(rootNode)
            if (result.success) {
                lastClickTime = currentTime
                return result
            }
        }

        // ============================================================
        // STEP 3: Check for "تم" (Done) button
        // ============================================================
        val hasTamButton = allText.any { it == "تم" || it == "Done" }

        if (hasTamButton) {
            AppLogger.automation(TAG, "Found 'تم' button (click #${doneClickCount + 1})", state = "TAM_BUTTON")

            val tamNode = findNodeWithText(rootNode, "تم")
                ?: findNodeWithText(rootNode, "Done")

            if (tamNode != null) {
                if (gentleClick(tamNode)) {
                    doneClickCount++
                    doneClickedTime = currentTime
                    lastClickTime = currentTime
                    AppLogger.automation(TAG, "Clicked 'تم' (#$doneClickCount)", state = "TAM_CLICKED")
                    tamNode.recycle()

                    // After 3 clicks, assume automation is near complete
                    if (doneClickCount >= 3) {
                        AppLogger.d(TAG, "3 Done clicks - nearing completion")
                    }

                    Thread.sleep(500)
                    return AutomationResult(true, "Clicked Done button")
                }
                tamNode.recycle()
            }
        }

        return AutomationResult(false, "No intermediate screen handled", shouldRetry = true)
    }

    // ============================================================
    // PRICE EXTRACTION
    // ============================================================

    override fun extractPrices(rootNode: AccessibilityNodeInfo): ExtractedPrice? {
        // CRITICAL: Block price detection until automation is complete
        if (!automationComplete) {
            AppLogger.d(TAG, "Price detection BLOCKED - automation not complete")
            return null
        }

        val allText = getAllTextFromNode(rootNode)

        val prices = mutableListOf<Double>()
        val vehiclePrices = mutableMapOf<String, Double>()
        val rawTexts = mutableListOf<String>()

        for (text in allText) {
            val extracted = PricePatterns.extractPriceForApp(text, packageName)
            if (extracted != null && extracted > 0) {
                // Filter out unreasonable prices (default 95 EGP)
                if (extracted < 30 || extracted > 5000) continue

                prices.add(extracted)
                rawTexts.add(text)
            }

            // Vehicle types
            val vehicleTypes = listOf("رحلة", "مريحة", "بريميوم", "Economy", "Comfort", "Premium")
            for (vehicleType in vehicleTypes) {
                if (text.contains(vehicleType, ignoreCase = true)) {
                    val price = PricePatterns.extractPriceForApp(text, packageName)
                    if (price != null && price > 30) {
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

        val priceIndicators = listOf(
            "البحث عن عروض", "الأجر المقترح",
            "اطلب رحلة", "Request ride",
            "Search for offers"
        )

        val hasIndicators = priceIndicators.any { indicator ->
            allText.any { it.contains(indicator, ignoreCase = true) }
        }

        val hasPrice = allText.any { text ->
            val price = PricePatterns.extractPriceForApp(text, packageName)
            price != null && price > 30
        }

        return hasIndicators && hasPrice
    }

    // ============================================================
    // HELPER METHODS
    // ============================================================

    private fun tryClickFirstSuggestion(rootNode: AccessibilityNodeInfo): AutomationResult {
        val allText = getAllTextFromNode(rootNode)

        // Filter for real suggestions
        val suggestions = allText.filter { text ->
            !text.contains("اختر على الخريطة") &&
            !text.contains("Choose on map") &&
            !text.contains("لا توجد نتائج") &&
            !text.matches(Regex("^\\d+\\.\\d+.*")) &&
            text.length > 5 &&
            (text.contains("متر") || text.contains("كم") ||
             text.contains("Egypt") || text.contains("مصر") ||
             text.contains("شارع") || text.contains("ميدان"))
        }

        if (suggestions.isEmpty()) {
            return AutomationResult(false, "No suggestions found", shouldRetry = true)
        }

        val firstSuggestion = suggestions.first()
        val node = findNodeWithText(rootNode, firstSuggestion)

        if (node != null) {
            val rect = Rect()
            node.getBoundsInScreen(rect)

            // Try multiple click strategies
            var clicked = false

            // Strategy 1: ACTION_CLICK
            if (node.isClickable) {
                clicked = node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            }

            // Strategy 2: Gentle click
            if (!clicked) {
                clicked = gentleClick(node)
            }

            // Strategy 3: Gesture with longer duration
            if (!clicked && rect.width() > 0) {
                clicked = clickAtPositionWithDuration(
                    rect.centerX().toFloat(),
                    rect.centerY().toFloat(),
                    200
                )
            }

            node.recycle()

            if (clicked) {
                AppLogger.automation(TAG, "Clicked suggestion: '$firstSuggestion'", state = "SUGGESTION_CLICKED")
                return AutomationResult(true, "Selected suggestion")
            }
        }

        return AutomationResult(false, "Could not click suggestion", shouldRetry = true)
    }
}
