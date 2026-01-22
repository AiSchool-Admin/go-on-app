package com.goon.app.services.automation

import android.accessibilityservice.AccessibilityService
import android.graphics.Rect
import android.os.Bundle
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
    private val clickCooldown = 800L  // Reduced from 1500ms - InDriver handles faster clicks now
    private var mapConfirmAttempts = 0
    private var priceScreenDetectionAttempts = 0

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
        mapConfirmAttempts = 0
        priceScreenDetectionAttempts = 0
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
        val priceScreenIndicators = listOf(
            "البحث عن عروض",
            "الأجر المقترح",
            "اطلب رحلة",
            "السعر المقترح",
            "Search for offers",
            "Suggested fare",
            "اطلب سائق",
            "Request driver",
            "عرض السعر",
            "Your offer",
            "قدم عرضك",
            "Make your offer"
        )

        val hasPriceScreen = allText.any { text ->
            priceScreenIndicators.any { indicator ->
                text.contains(indicator, ignoreCase = true)
            }
        }

        val hasPriceIndicator = allText.any {
            it.contains("EGP") ||
            it.contains("ج.م") ||
            it.contains("جنيه") ||
            it.matches(Regex(".*\\d+\\s*(جنيه|ج\\.م|EGP).*"))
        }

        val vehicleTypeIndicators = listOf(
            "رحلة", "مريحة", "درجة نارية", "بريميوم",
            "Economy", "Comfort", "Premium", "Ride",
            "سيارة", "موتوسيكل", "Motorcycle"
        )

        val hasVehicleTypes = allText.count { text ->
            vehicleTypeIndicators.any { indicator ->
                text.equals(indicator, ignoreCase = true) ||
                text.contains(indicator, ignoreCase = true)
            }
        } >= 1

        // Also check for slider or price input
        val hasSlider = allText.any {
            it.contains("اسحب") || it.contains("Slide") ||
            it.contains("حرك") || it.contains("Drag")
        }

        val isFinalPriceScreen = hasPriceScreen ||
            (hasPriceIndicator && hasVehicleTypes) ||
            (hasPriceIndicator && hasSlider)

        if (isFinalPriceScreen) {
            priceScreenDetectionAttempts++
            AppLogger.automation(TAG, "DETECTED PRICE SCREEN! (attempt #$priceScreenDetectionAttempts)", state = "PRICE_SCREEN")
            automationComplete = true
            return AutomationResult(true, "On price screen", nextState = AutomationState.WAITING_FOR_PRICE)
        }

        // ============================================================
        // STEP 1: Check for Map Confirmation screen (تأكيد الموقع على الخريطة)
        // ============================================================
        val mapConfirmIndicators = listOf(
            "تأكيد الموقع",
            "Confirm location",
            "تأكيد النقطة",
            "Confirm pickup",
            "Confirm drop-off",
            "حدد الموقع",
            "Set location",
            "تعيين الموقع"
        )

        val hasMapConfirm = allText.any { text ->
            mapConfirmIndicators.any { indicator ->
                text.contains(indicator, ignoreCase = true)
            }
        }

        // Check for confirm button on map screen
        val confirmButtons = listOf("تأكيد", "Confirm", "موافق", "OK", "تم", "Done")

        if (hasMapConfirm || (mapConfirmAttempts < 3 && allText.any { it.contains("الخريطة") || it.contains("map", ignoreCase = true) })) {
            AppLogger.automation(TAG, "Detected map confirmation screen", state = "MAP_CONFIRM")

            for (buttonText in confirmButtons) {
                val node = findNodeWithText(rootNode, buttonText)
                if (node != null) {
                    if (gentleClick(node)) {
                        mapConfirmAttempts++
                        lastClickTime = currentTime
                        AppLogger.automation(TAG, "Clicked '$buttonText' on map screen (#$mapConfirmAttempts)", state = "MAP_CONFIRMED")
                        node.recycle()
                        Thread.sleep(300)
                        return AutomationResult(true, "Confirmed map location")
                    }
                    node.recycle()
                }
            }
        }

        // ============================================================
        // STEP 2: Check for "No Results" screen
        // ============================================================
        val hasNoResults = allText.any {
            it.contains("لا توجد نتائج") ||
            it.contains("No results") ||
            it.contains("لا توجد") ||
            it.contains("لم يتم العثور") ||
            it.contains("Not found")
        }

        if (hasNoResults) {
            AppLogger.automation(TAG, "Detected 'No Results' screen", state = "NO_RESULTS")

            val chooseMapTexts = listOf(
                "اختر على الخريطة",
                "Choose on map",
                "على الخريطة",
                "الخريطة",
                "حدد على الخريطة",
                "Select on map"
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
        // STEP 3: Check for suggestions screen
        // ============================================================
        val hasSuggestions = allText.any {
            it.contains("متر") || it.contains("كم") || it.contains("km") ||
            it.contains("Egypt") || it.contains("مصر") ||
            it.contains("القاهرة") || it.contains("Cairo") ||
            it.contains("الجيزة") || it.contains("Giza")
        }

        if (hasSuggestions) {
            val result = tryClickFirstSuggestion(rootNode)
            if (result.success) {
                lastClickTime = currentTime
                return result
            }
        }

        // ============================================================
        // STEP 4: Check for "تم" (Done) button - Allow up to 5 clicks
        // ============================================================
        val hasTamButton = allText.any { it == "تم" || it == "Done" || it == "التالي" || it == "Next" }

        if (hasTamButton && doneClickCount < 5) {
            AppLogger.automation(TAG, "Found 'تم' button (click #${doneClickCount + 1})", state = "TAM_BUTTON")

            val tamNode = findNodeWithText(rootNode, "تم")
                ?: findNodeWithText(rootNode, "Done")
                ?: findNodeWithText(rootNode, "التالي")
                ?: findNodeWithText(rootNode, "Next")

            if (tamNode != null) {
                if (gentleClick(tamNode)) {
                    doneClickCount++
                    doneClickedTime = currentTime
                    lastClickTime = currentTime
                    AppLogger.automation(TAG, "Clicked 'تم' (#$doneClickCount)", state = "TAM_CLICKED")
                    tamNode.recycle()

                    // After 4 clicks, check more aggressively for price screen
                    if (doneClickCount >= 4) {
                        AppLogger.d(TAG, "$doneClickCount Done clicks - checking for price screen")
                    }

                    Thread.sleep(400)
                    return AutomationResult(true, "Clicked Done button")
                }
                tamNode.recycle()
            }
        }

        // ============================================================
        // STEP 5: Check for any clickable "Continue" or action buttons
        // ============================================================
        val actionButtons = listOf("متابعة", "Continue", "استمرار", "إرسال", "Send", "طلب", "Request")
        for (buttonText in actionButtons) {
            val node = findNodeWithText(rootNode, buttonText)
            if (node != null) {
                if (gentleClick(node)) {
                    lastClickTime = currentTime
                    AppLogger.automation(TAG, "Clicked action button '$buttonText'", state = "ACTION_CLICKED")
                    node.recycle()
                    return AutomationResult(true, "Clicked action button")
                }
                node.recycle()
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
        AppLogger.d(TAG, "Extracting prices from ${allText.size} text nodes")

        val prices = mutableListOf<Double>()
        val vehiclePrices = mutableMapOf<String, Double>()
        val rawTexts = mutableListOf<String>()

        // InDriver-specific patterns for manual extraction
        val inDriverPatterns = listOf(
            Regex("(\\d+)\\s*[-–]\\s*(\\d+)"),  // Range: "65-85" - take average
            Regex("(\\d+)\\s*ج\\.?م"),           // "65 ج.م"
            Regex("ج\\.?م\\s*(\\d+)"),           // "ج.م 65"
            Regex("(\\d{2,3})\\s*EGP"),          // "65 EGP"
            Regex("EGP\\s*(\\d{2,3})"),          // "EGP 65"
            Regex("(\\d{2,3})\\s*جنيه"),         // "65 جنيه"
            Regex("^(\\d{2,3})$")                // Standalone number "65"
        )

        for (text in allText) {
            // First try standard extraction
            var extracted = PricePatterns.extractPriceForApp(text, packageName)

            // If not found, try InDriver-specific patterns
            if (extracted == null) {
                for (pattern in inDriverPatterns) {
                    val match = pattern.find(text)
                    if (match != null) {
                        extracted = try {
                            // For range pattern, take the lower bound
                            if (match.groupValues.size > 2 && match.groupValues[2].isNotEmpty()) {
                                match.groupValues[1].toDoubleOrNull()
                            } else {
                                match.groupValues[1].toDoubleOrNull()
                            }
                        } catch (e: Exception) {
                            null
                        }
                        if (extracted != null && extracted > 0) break
                    }
                }
            }

            if (extracted != null && extracted > 0) {
                // Lower minimum to 15 EGP for short trips
                // Upper limit 5000 EGP for long trips
                if (extracted < 15 || extracted > 5000) {
                    AppLogger.d(TAG, "Skipping price $extracted (out of range 15-5000)")
                    continue
                }

                prices.add(extracted)
                rawTexts.add(text)
                AppLogger.d(TAG, "Found price: $extracted from '$text'")
            }

            // Vehicle types with expanded list
            val vehicleTypes = listOf(
                "رحلة" to "Economy",
                "مريحة" to "Comfort",
                "بريميوم" to "Premium",
                "Economy" to "Economy",
                "Comfort" to "Comfort",
                "Premium" to "Premium",
                "سيارة" to "Car",
                "موتوسيكل" to "Motorcycle",
                "درجة نارية" to "Motorcycle"
            )

            for ((arabicType, englishType) in vehicleTypes) {
                if (text.contains(arabicType, ignoreCase = true) ||
                    text.contains(englishType, ignoreCase = true)) {
                    val price = PricePatterns.extractPriceForApp(text, packageName)
                    if (price != null && price >= 15) {
                        vehiclePrices[englishType] = price
                        AppLogger.d(TAG, "Found vehicle price: $englishType = $price")
                    }
                }
            }
        }

        if (prices.isEmpty()) {
            AppLogger.d(TAG, "No prices found in InDriver")
            return null
        }

        val minPrice = prices.minOrNull() ?: return null
        AppLogger.automation(TAG, "Extracted InDriver price: $minPrice (found ${prices.size} prices)", state = "PRICE_EXTRACTED")

        return ExtractedPrice(
            price = minPrice,
            allPrices = prices.distinct().sorted(),
            vehiclePrices = vehiclePrices,
            rawTexts = rawTexts
        )
    }

    override fun isPriceScreenVisible(rootNode: AccessibilityNodeInfo): Boolean {
        val allText = getAllTextFromNode(rootNode)

        val priceIndicators = listOf(
            "البحث عن عروض", "الأجر المقترح",
            "اطلب رحلة", "Request ride",
            "Search for offers", "اطلب سائق",
            "Request driver", "عرض السعر",
            "Your offer", "قدم عرضك",
            "Make your offer", "السعر المقترح",
            "Suggested fare"
        )

        val hasIndicators = priceIndicators.any { indicator ->
            allText.any { it.contains(indicator, ignoreCase = true) }
        }

        // Lower threshold to 15 EGP
        val hasPrice = allText.any { text ->
            val price = PricePatterns.extractPriceForApp(text, packageName)
            price != null && price >= 15
        }

        // Also check for slider presence (InDriver price adjustment)
        val hasSlider = allText.any {
            it.contains("اسحب") || it.contains("Slide") ||
            it.contains("حرك") || it.contains("Drag")
        }

        return (hasIndicators && hasPrice) || (hasPrice && hasSlider)
    }

    // ============================================================
    // HELPER METHODS
    // ============================================================

    private fun tryClickFirstSuggestion(rootNode: AccessibilityNodeInfo): AutomationResult {
        val allText = getAllTextFromNode(rootNode)

        // Exclude patterns
        val excludePatterns = listOf(
            "اختر على الخريطة",
            "Choose on map",
            "لا توجد نتائج",
            "No results",
            "من",
            "إلى",
            "From",
            "To"
        )

        // Filter for real suggestions - expanded criteria
        val suggestions = allText.filter { text ->
            excludePatterns.none { pattern -> text.contains(pattern, ignoreCase = true) } &&
            !text.matches(Regex("^\\d+\\.\\d+,\\s*\\d+\\.\\d+$")) && // Skip coordinates
            text.length > 3 &&
            (text.contains("متر") || text.contains("كم") ||
             text.contains("Egypt") || text.contains("مصر") ||
             text.contains("شارع") || text.contains("ميدان") ||
             text.contains("القاهرة") || text.contains("Cairo") ||
             text.contains("الجيزة") || text.contains("Giza") ||
             text.contains("مدينة") || text.contains("City") ||
             text.contains("حي") || text.contains("District") ||
             text.contains("طريق") || text.contains("Road"))
        }

        if (suggestions.isEmpty()) {
            AppLogger.d(TAG, "No valid suggestions found")
            return AutomationResult(false, "No suggestions found", shouldRetry = true)
        }

        // Try clicking each suggestion until one works
        for (suggestion in suggestions.take(3)) {
            val node = findNodeWithText(rootNode, suggestion)
            if (node != null) {
                val rect = Rect()
                node.getBoundsInScreen(rect)

                // Try multiple click strategies
                var clicked = false

                // Strategy 1: ACTION_CLICK
                if (node.isClickable) {
                    clicked = node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                }

                // Strategy 2: Gentle click on node
                if (!clicked) {
                    clicked = gentleClick(node)
                }

                // Strategy 3: Click parent if node is not clickable
                if (!clicked) {
                    val parent = node.parent
                    if (parent != null && parent.isClickable) {
                        clicked = parent.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                        parent.recycle()
                    }
                }

                // Strategy 4: Gesture with longer duration
                if (!clicked && rect.width() > 0) {
                    clicked = clickAtPositionWithDuration(
                        rect.centerX().toFloat(),
                        rect.centerY().toFloat(),
                        200
                    )
                }

                node.recycle()

                if (clicked) {
                    AppLogger.automation(TAG, "Clicked suggestion: '$suggestion'", state = "SUGGESTION_CLICKED")
                    return AutomationResult(true, "Selected suggestion")
                }
            }
        }

        return AutomationResult(false, "Could not click any suggestion", shouldRetry = true)
    }
}
