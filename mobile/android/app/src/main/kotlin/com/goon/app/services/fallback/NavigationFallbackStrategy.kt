package com.goon.app.services.fallback

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Path
import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import com.goon.app.services.config.AppConstants
import com.goon.app.services.config.TimingConfig
import com.goon.app.services.utils.GestureHelper
import com.goon.app.services.utils.NodeFinder
import com.goon.app.utils.AppLogger

/**
 * استراتيجية Fallback للتنقل
 * Navigation fallback strategy
 *
 * Handles navigation failures:
 * 1. Stuck on intermediate screen (dialogs, loaders, errors)
 * 2. App went back to home screen
 * 3. Wrong screen detected
 * 4. Timeout recovery
 */
class NavigationFallbackStrategy(
    service: AccessibilityService
) : FallbackStrategy(service, "NavigationFallback") {

    override val type: FallbackType = FallbackType.NAVIGATION
    override val priority: Int = 30

    override fun canHandle(context: FallbackContext): Boolean {
        return context.currentState.contains("WAITING", ignoreCase = true) ||
               context.currentState.contains("FINDING", ignoreCase = true) ||
               context.lastError.contains("timeout", ignoreCase = true) ||
               context.lastError.contains("stuck", ignoreCase = true)
    }

    override fun execute(rootNode: AccessibilityNodeInfo, context: FallbackContext): FallbackResult {
        val packageName = context.packageName
        val allText = NodeFinder.getAllText(rootNode)

        AppLogger.d("NavigationFallback: Analyzing screen for $packageName", tag)

        var attempts = 0

        // Strategy 1: Handle intermediate dialogs/popups
        attempts++
        val dialogResult = handleIntermediateDialogs(rootNode, allText)
        if (dialogResult.success) {
            return dialogResult.copy(attemptsMade = attempts)
        }

        // Strategy 2: Handle loader/progress screens
        attempts++
        val loaderResult = handleLoaderScreen(rootNode, allText)
        if (loaderResult.success) {
            return loaderResult.copy(attemptsMade = attempts)
        }

        // Strategy 3: Handle "No Results" screens
        attempts++
        val noResultsResult = handleNoResultsScreen(rootNode, allText, packageName)
        if (noResultsResult.success) {
            return noResultsResult.copy(attemptsMade = attempts)
        }

        // Strategy 4: Check if on wrong screen and try to navigate
        attempts++
        val navigationResult = attemptScreenNavigation(rootNode, allText, packageName, context)
        if (navigationResult.success) {
            return navigationResult.copy(attemptsMade = attempts)
        }

        // Strategy 5: Try pressing back if stuck
        attempts++
        val backResult = tryBackNavigation(packageName)
        if (backResult.success) {
            return backResult.copy(attemptsMade = attempts)
        }

        return failure("NavigationFailed", "All navigation strategies failed", attempts)
    }

    override fun getAttemptDescriptions(): List<String> {
        return listOf(
            "Handle intermediate dialogs (promo, permission, surge)",
            "Handle loader/progress screens",
            "Handle 'No Results' screens",
            "Navigate to correct screen",
            "Press back to recover"
        )
    }

    // ============================================================
    // NAVIGATION STRATEGIES
    // ============================================================

    /**
     * معالجة الحوارات الوسيطة
     * Handle intermediate dialogs
     */
    private fun handleIntermediateDialogs(rootNode: AccessibilityNodeInfo, allText: List<String>): FallbackResult {
        // Dismiss button patterns (Arabic and English)
        val dismissPatterns = listOf(
            // Close/Skip buttons
            "تخطي", "Skip", "إغلاق", "Close", "X", "✕",
            // Confirmation buttons
            "موافق", "OK", "حسناً", "Got it", "تم", "Done",
            // Rejection buttons
            "لا شكراً", "No thanks", "لاحقاً", "Later", "Not now",
            // Accept buttons (for surge pricing, etc.)
            "قبول", "Accept", "تأكيد", "Confirm"
        )

        for (pattern in dismissPatterns) {
            val nodes = rootNode.findAccessibilityNodeInfosByText(pattern)
            for (node in nodes) {
                if (node.isClickable || isLikelyButton(node)) {
                    logAttempt("DismissDialog", false, "Trying '$pattern'")

                    if (node.performAction(AccessibilityNodeInfo.ACTION_CLICK) ||
                        GestureHelper.clickNodeOrParent(node)) {
                        node.recycle()
                        nodes.forEach { try { it.recycle() } catch (e: Exception) {} }
                        Thread.sleep(300)
                        return success("DismissDialog", "Dismissed with '$pattern'")
                    }
                }
                node.recycle()
            }
        }

        // Try clicking small close icons (X buttons)
        val closeIcons = NodeFinder.findSmallClickableIcons(rootNode, 20, 80)
        for (icon in closeIcons) {
            val bounds = Rect()
            icon.getBoundsInScreen(bounds)

            // Close icons are usually in top-right corner
            if (bounds.right > 800 && bounds.top < 400) {
                if (GestureHelper.tapOnNode(service, icon)) {
                    closeIcons.forEach { try { it.recycle() } catch (e: Exception) {} }
                    Thread.sleep(300)
                    return success("CloseIcon", "Clicked close icon")
                }
            }
            icon.recycle()
        }

        return failure("NoDialogFound", "No dismissible dialog found")
    }

    /**
     * معالجة شاشة التحميل
     * Handle loader screen
     */
    private fun handleLoaderScreen(rootNode: AccessibilityNodeInfo, allText: List<String>): FallbackResult {
        val loaderIndicators = listOf(
            "جارٍ", "Loading", "يرجى الانتظار", "Please wait",
            "جاري التحميل", "Searching", "البحث"
        )

        val hasLoader = loaderIndicators.any { indicator ->
            allText.any { it.contains(indicator, ignoreCase = true) }
        }

        if (hasLoader) {
            logAttempt("WaitForLoader", false, "Loader detected, waiting...")
            // Just wait - loaders usually resolve themselves
            Thread.sleep(1000)
            return success("WaitForLoader", "Waited for loader")
        }

        return failure("NoLoader", "No loader detected")
    }

    /**
     * معالجة شاشة "لا توجد نتائج"
     * Handle "No Results" screen
     */
    private fun handleNoResultsScreen(rootNode: AccessibilityNodeInfo, allText: List<String>, packageName: String): FallbackResult {
        val noResultsIndicators = listOf(
            "لا توجد نتائج", "No results", "لم يتم العثور", "Not found",
            "لا توجد", "No matches"
        )

        val hasNoResults = noResultsIndicators.any { indicator ->
            allText.any { it.contains(indicator, ignoreCase = true) }
        }

        if (!hasNoResults) {
            return failure("NotNoResults", "Not on 'No Results' screen")
        }

        // Look for "Choose on map" or similar fallback options
        val mapOptions = listOf(
            "اختر على الخريطة", "Choose on map", "على الخريطة",
            "Map", "الخريطة", "حدد الموقع", "Set location"
        )

        for (option in mapOptions) {
            val nodes = rootNode.findAccessibilityNodeInfosByText(option)
            for (node in nodes) {
                if (GestureHelper.clickNodeOrParent(node)) {
                    node.recycle()
                    nodes.forEach { try { it.recycle() } catch (e: Exception) {} }
                    Thread.sleep(500)
                    return success("ChooseOnMap", "Clicked '$option'")
                }
                node.recycle()
            }
        }

        return failure("NoMapOption", "Could not find map option")
    }

    /**
     * محاولة التنقل للشاشة الصحيحة
     * Attempt to navigate to correct screen
     */
    private fun attemptScreenNavigation(
        rootNode: AccessibilityNodeInfo,
        allText: List<String>,
        packageName: String,
        context: FallbackContext
    ): FallbackResult {
        // Check if on home screen (need to click ride button)
        val homeIndicators = getHomeScreenIndicators(packageName)
        val isOnHome = homeIndicators.any { indicator ->
            allText.any { it.contains(indicator, ignoreCase = true) }
        }

        if (isOnHome) {
            logAttempt("ClickRideButton", false, "On home screen, looking for ride button")

            val rideButtons = getRideButtonTexts(packageName)
            for (buttonText in rideButtons) {
                val nodes = rootNode.findAccessibilityNodeInfosByText(buttonText)
                for (node in nodes) {
                    if (GestureHelper.smartClick(service, node)) {
                        node.recycle()
                        nodes.forEach { try { it.recycle() } catch (e: Exception) {} }
                        Thread.sleep(500)
                        return success("ClickRideButton", "Clicked '$buttonText'")
                    }
                    node.recycle()
                }
            }
        }

        // Check for "Where to?" or destination field
        val whereToTexts = listOf("Where to?", "إلى أين", "الوجهة", "Destination")
        for (text in whereToTexts) {
            val node = NodeFinder.findByText(rootNode, text)
            if (node != null) {
                if (GestureHelper.smartClick(service, node)) {
                    node.recycle()
                    Thread.sleep(300)
                    return success("ClickWhereTo", "Clicked '$text'")
                }
                node.recycle()
            }
        }

        return failure("NavigationFailed", "Could not navigate to correct screen")
    }

    /**
     * محاولة الضغط على زر الرجوع
     * Try pressing back
     */
    private fun tryBackNavigation(packageName: String): FallbackResult {
        // Use global back action
        val success = service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
        if (success) {
            Thread.sleep(500)
            return success("PressBack", "Pressed back button")
        }
        return failure("BackFailed", "Back action failed")
    }

    // ============================================================
    // HELPERS
    // ============================================================

    private fun isLikelyButton(node: AccessibilityNodeInfo): Boolean {
        val className = node.className?.toString()?.lowercase() ?: ""
        return className.contains("button") ||
               className.contains("textview") ||
               className.contains("imageview") ||
               node.isClickable
    }

    private fun getHomeScreenIndicators(packageName: String): List<String> {
        return when (packageName) {
            AppConstants.Packages.UBER -> listOf("Where to?", "Good", "morning", "afternoon", "evening")
            AppConstants.Packages.CAREEM -> listOf("Get more with Careem", "سيارة", "Super App", "كريم+")
            AppConstants.Packages.INDRIVER -> listOf("Where to", "Choose a trip", "اختر رحلة")
            AppConstants.Packages.DIDI -> listOf("Where to?", "Tap to enter", "Good")
            AppConstants.Packages.BOLT -> listOf("Where to?", "Good", "Search")
            else -> listOf("Where to?", "Home")
        }
    }

    private fun getRideButtonTexts(packageName: String): List<String> {
        return when (packageName) {
            AppConstants.Packages.CAREEM -> listOf("سيارة", "سياره", "Car", "Ride")
            AppConstants.Packages.UBER -> listOf("Where to?", "Ride", "Search")
            AppConstants.Packages.INDRIVER -> listOf("Where to", "اختر رحلة")
            AppConstants.Packages.DIDI -> listOf("Where to?", "Tap to enter")
            AppConstants.Packages.BOLT -> listOf("Where to?", "Search")
            else -> listOf("Ride", "Car", "Where to?")
        }
    }
}

/**
 * استراتيجية Fallback للتعامل مع الشاشات الوسيطة
 * Intermediate screen fallback strategy
 *
 * Specialized for handling app-specific intermediate screens:
 * - InDriver: Map confirmation ("تم"), No results
 * - Careem: Pickup confirmation, Surge pricing
 * - DiDi: Select Address screen
 * - Uber: Surge pricing confirmation
 */
class IntermediateScreenFallbackStrategy(
    service: AccessibilityService
) : FallbackStrategy(service, "IntermediateScreenFallback") {

    override val type: FallbackType = FallbackType.INTERMEDIATE
    override val priority: Int = 5  // High priority - should be tried first

    override fun canHandle(context: FallbackContext): Boolean {
        return true // Always try to handle intermediate screens
    }

    override fun execute(rootNode: AccessibilityNodeInfo, context: FallbackContext): FallbackResult {
        val packageName = context.packageName
        val allText = NodeFinder.getAllText(rootNode)

        AppLogger.d("IntermediateScreenFallback: Checking for intermediate screens in $packageName", tag)

        return when (packageName) {
            AppConstants.Packages.INDRIVER -> handleInDriverIntermediate(rootNode, allText)
            AppConstants.Packages.CAREEM -> handleCareemIntermediate(rootNode, allText)
            AppConstants.Packages.DIDI -> handleDiDiIntermediate(rootNode, allText)
            AppConstants.Packages.UBER -> handleUberIntermediate(rootNode, allText)
            AppConstants.Packages.BOLT -> handleBoltIntermediate(rootNode, allText)
            else -> failure("UnknownApp", "No intermediate handler for $packageName")
        }
    }

    override fun getAttemptDescriptions(): List<String> {
        return listOf(
            "InDriver: Click 'تم' (Done) button",
            "InDriver: Handle 'No Results' → 'Choose on map'",
            "Careem: Handle surge pricing",
            "Careem: Click pickup confirmation",
            "DiDi: Click 'Select Address' confirmation",
            "Uber: Accept surge pricing"
        )
    }

    // ============================================================
    // APP-SPECIFIC HANDLERS
    // ============================================================

    private fun handleInDriverIntermediate(rootNode: AccessibilityNodeInfo, allText: List<String>): FallbackResult {
        // Check for price screen (we're done!)
        val onPriceScreen = allText.any {
            it.contains("البحث عن عروض") ||
            it.contains("الأجر المقترح") ||
            it.contains("Search for offers")
        }
        if (onPriceScreen) {
            return success("PriceScreen", "Already on price screen")
        }

        // Handle "تم" (Done) button
        val doneTexts = listOf("تم", "Done", "موافق", "OK")
        for (text in doneTexts) {
            val node = NodeFinder.findByText(rootNode, text)
            if (node != null) {
                if (GestureHelper.gentleClick(node)) {
                    node.recycle()
                    Thread.sleep(500)
                    return success("ClickDone", "Clicked '$text'")
                }
                node.recycle()
            }
        }

        return failure("NoIntermediate", "No intermediate screen detected")
    }

    private fun handleCareemIntermediate(rootNode: AccessibilityNodeInfo, allText: List<String>): FallbackResult {
        // Handle surge pricing
        val hasSurge = allText.any {
            it.contains("الطلب مرتفع") ||
            it.contains("أسعار أعلى") ||
            it.contains("High demand") ||
            it.contains("prices are higher")
        }

        if (hasSurge) {
            val acceptTexts = listOf("قبول", "Accept", "موافق", "OK", "تأكيد", "Confirm")
            for (text in acceptTexts) {
                val node = NodeFinder.findByText(rootNode, text)
                if (node != null && node.isClickable) {
                    if (node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                        node.recycle()
                        Thread.sleep(300)
                        return success("AcceptSurge", "Accepted surge pricing")
                    }
                    node.recycle()
                }
            }
        }

        // Handle pickup confirmation
        val confirmTexts = listOf("تأكيد الانطلاق", "Confirm pickup", "تأكيد", "Confirm")
        for (text in confirmTexts) {
            val node = NodeFinder.findByText(rootNode, text)
            if (node != null) {
                if (GestureHelper.smartClick(service, node)) {
                    node.recycle()
                    Thread.sleep(300)
                    return success("ConfirmPickup", "Clicked '$text'")
                }
                node.recycle()
            }
        }

        return failure("NoIntermediate", "No Careem intermediate screen")
    }

    private fun handleDiDiIntermediate(rootNode: AccessibilityNodeInfo, allText: List<String>): FallbackResult {
        // Handle "Select Address" screen
        val onSelectAddress = allText.any {
            it.lowercase().contains("select address") ||
            it.contains("اختر العنوان") ||
            it.lowercase().contains("pin your location")
        }

        if (onSelectAddress) {
            val confirmTexts = listOf("Select Address", "اختر العنوان", "Confirm", "تأكيد", "OK")
            for (text in confirmTexts) {
                val node = NodeFinder.findByText(rootNode, text)
                if (node != null) {
                    if (GestureHelper.smartClick(service, node)) {
                        node.recycle()
                        Thread.sleep(500)
                        return success("SelectAddress", "Confirmed address selection")
                    }
                    node.recycle()
                }
            }
        }

        return failure("NoIntermediate", "No DiDi intermediate screen")
    }

    private fun handleUberIntermediate(rootNode: AccessibilityNodeInfo, allText: List<String>): FallbackResult {
        // Handle surge pricing
        val hasSurge = allText.any {
            it.lowercase().contains("prices are higher") ||
            it.lowercase().contains("demand is high") ||
            it.contains("الأسعار أعلى")
        }

        if (hasSurge) {
            val acceptTexts = listOf("Accept", "Confirm", "Got it", "OK", "قبول", "تأكيد")
            for (text in acceptTexts) {
                val nodes = rootNode.findAccessibilityNodeInfosByText(text)
                for (node in nodes) {
                    if (node.isClickable) {
                        if (node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                            node.recycle()
                            nodes.forEach { try { it.recycle() } catch (e: Exception) {} }
                            Thread.sleep(300)
                            return success("AcceptSurge", "Accepted Uber surge")
                        }
                    }
                    node.recycle()
                }
            }
        }

        return failure("NoIntermediate", "No Uber intermediate screen")
    }

    private fun handleBoltIntermediate(rootNode: AccessibilityNodeInfo, allText: List<String>): FallbackResult {
        // Handle promo dialogs
        val hasPromo = allText.any {
            it.lowercase().contains("promo") ||
            it.lowercase().contains("discount") ||
            it.contains("عرض") ||
            it.contains("خصم")
        }

        if (hasPromo) {
            val skipTexts = listOf("Skip", "No thanks", "Close", "تخطي", "إغلاق")
            for (text in skipTexts) {
                val node = NodeFinder.findByText(rootNode, text)
                if (node != null) {
                    if (GestureHelper.smartClick(service, node)) {
                        node.recycle()
                        Thread.sleep(300)
                        return success("SkipPromo", "Skipped promo")
                    }
                    node.recycle()
                }
            }
        }

        return failure("NoIntermediate", "No Bolt intermediate screen")
    }
}
