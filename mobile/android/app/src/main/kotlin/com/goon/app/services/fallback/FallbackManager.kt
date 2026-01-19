package com.goon.app.services.fallback

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityNodeInfo
import com.goon.app.services.utils.NodeFinder
import com.goon.app.utils.AppLogger

/**
 * مدير استراتيجيات الـ Fallback
 * Fallback strategies manager
 *
 * Coordinates fallback strategy execution:
 * 1. Receives failure context from automation
 * 2. Selects appropriate strategies based on failure type
 * 3. Executes strategies in priority order
 * 4. Reports results back to automation
 *
 * Usage:
 * ```kotlin
 * val manager = FallbackManager(service)
 *
 * // When automation fails:
 * val context = FallbackContext(
 *     packageName = "com.careem.acma",
 *     currentState = "SELECTING_SUGGESTION",
 *     targetText = "Cairo, Egypt",
 *     lastError = "Click failed"
 * )
 *
 * val result = manager.handleFailure(rootNode, context, FallbackType.CLICK)
 * if (result.success) {
 *     // Continue automation
 * } else {
 *     // All fallbacks failed
 * }
 * ```
 */
class FallbackManager(
    private val service: AccessibilityService
) {
    companion object {
        private const val TAG = "FallbackManager"
    }

    // ============================================================
    // STRATEGIES
    // ============================================================

    private val strategies: Map<FallbackType, List<FallbackStrategy>> by lazy {
        mapOf(
            FallbackType.CLICK to listOf(
                ClickFallbackStrategy(service),
                SuggestionClickFallbackStrategy(service)
            ),
            FallbackType.TEXT_ENTRY to listOf(
                TextEntryFallbackStrategy(service),
                CoordinatesEntryFallbackStrategy(service)
            ),
            FallbackType.NAVIGATION to listOf(
                NavigationFallbackStrategy(service)
            ),
            FallbackType.SUGGESTION to listOf(
                SuggestionClickFallbackStrategy(service),
                ClickFallbackStrategy(service)
            ),
            FallbackType.INTERMEDIATE to listOf(
                IntermediateScreenFallbackStrategy(service),
                NavigationFallbackStrategy(service)
            ),
            FallbackType.RECOVERY to listOf(
                IntermediateScreenFallbackStrategy(service),
                NavigationFallbackStrategy(service),
                ClickFallbackStrategy(service),
                TextEntryFallbackStrategy(service)
            )
        )
    }

    // Track fallback attempts per session
    private val sessionAttempts = mutableMapOf<String, Int>()
    private var maxAttemptsPerType = 3

    // ============================================================
    // PUBLIC API
    // ============================================================

    /**
     * معالجة فشل الأتمتة
     * Handle automation failure
     *
     * @param rootNode Current accessibility root node
     * @param context Failure context with details
     * @param type Type of failure
     * @return FallbackResult indicating success/failure
     */
    fun handleFailure(
        rootNode: AccessibilityNodeInfo,
        context: FallbackContext,
        type: FallbackType
    ): FallbackResult {
        val sessionKey = "${context.packageName}:${type.name}"
        val attempts = sessionAttempts.getOrDefault(sessionKey, 0)

        if (attempts >= maxAttemptsPerType) {
            AppLogger.w("Max fallback attempts reached for $sessionKey", TAG)
            return FallbackResult(
                success = false,
                strategyUsed = "MaxAttemptsReached",
                message = "Exceeded max $maxAttemptsPerType attempts for $type",
                attemptsMade = attempts,
                shouldContinue = false
            )
        }

        AppLogger.d("Handling $type failure for ${context.packageName} (attempt ${attempts + 1})", TAG)

        // Get strategies for this type
        val typeStrategies = strategies[type] ?: emptyList()
        if (typeStrategies.isEmpty()) {
            return FallbackResult(
                success = false,
                strategyUsed = "NoStrategy",
                message = "No strategies available for $type",
                attemptsMade = 0,
                shouldContinue = false
            )
        }

        // Try intermediate screen handler FIRST (high priority)
        if (type != FallbackType.INTERMEDIATE) {
            val intermediateStrategy = IntermediateScreenFallbackStrategy(service)
            if (intermediateStrategy.canHandle(context)) {
                val result = intermediateStrategy.execute(rootNode, context)
                if (result.success) {
                    AppLogger.d("Intermediate screen handled successfully", TAG)
                    return result
                }
            }
        }

        // Try each strategy in order
        for (strategy in typeStrategies.sortedBy { it.priority }) {
            if (!strategy.canHandle(context)) continue

            AppLogger.d("Trying strategy: ${strategy::class.simpleName}", TAG)

            val result = strategy.execute(rootNode, context)
            sessionAttempts[sessionKey] = attempts + 1

            if (result.success) {
                AppLogger.d("Strategy ${strategy::class.simpleName} succeeded", TAG)
                return result
            }

            if (!result.shouldContinue) {
                AppLogger.d("Strategy ${strategy::class.simpleName} says stop", TAG)
                return result
            }
        }

        sessionAttempts[sessionKey] = attempts + 1
        return FallbackResult(
            success = false,
            strategyUsed = "AllFailed",
            message = "All ${typeStrategies.size} strategies failed for $type",
            attemptsMade = typeStrategies.size,
            shouldContinue = false
        )
    }

    /**
     * معالجة فشل النقر
     * Handle click failure
     */
    fun handleClickFailure(
        rootNode: AccessibilityNodeInfo,
        packageName: String,
        targetText: String = "",
        targetElement: String = ""
    ): FallbackResult {
        val context = FallbackContext(
            packageName = packageName,
            currentState = "CLICK",
            targetText = targetText,
            targetElement = targetElement,
            screenTexts = NodeFinder.getAllText(rootNode)
        )
        return handleFailure(rootNode, context, FallbackType.CLICK)
    }

    /**
     * معالجة فشل إدخال النص
     * Handle text entry failure
     */
    fun handleTextEntryFailure(
        rootNode: AccessibilityNodeInfo,
        packageName: String,
        textToEnter: String,
        lat: Double? = null,
        lng: Double? = null
    ): FallbackResult {
        val metadata = mutableMapOf<String, Any>()
        if (lat != null) metadata["lat"] = lat
        if (lng != null) metadata["lng"] = lng

        val context = FallbackContext(
            packageName = packageName,
            currentState = "TEXT_ENTRY",
            targetText = textToEnter,
            metadata = metadata,
            screenTexts = NodeFinder.getAllText(rootNode)
        )
        return handleFailure(rootNode, context, FallbackType.TEXT_ENTRY)
    }

    /**
     * معالجة فشل اختيار الاقتراح
     * Handle suggestion selection failure
     */
    fun handleSuggestionFailure(
        rootNode: AccessibilityNodeInfo,
        packageName: String,
        targetAddress: String
    ): FallbackResult {
        val context = FallbackContext(
            packageName = packageName,
            currentState = "SELECTING_SUGGESTION",
            targetText = targetAddress,
            screenTexts = NodeFinder.getAllText(rootNode)
        )
        return handleFailure(rootNode, context, FallbackType.SUGGESTION)
    }

    /**
     * معالجة الشاشات الوسيطة
     * Handle intermediate screens
     */
    fun handleIntermediateScreen(
        rootNode: AccessibilityNodeInfo,
        packageName: String,
        currentState: String
    ): FallbackResult {
        val context = FallbackContext(
            packageName = packageName,
            currentState = currentState,
            screenTexts = NodeFinder.getAllText(rootNode)
        )
        return handleFailure(rootNode, context, FallbackType.INTERMEDIATE)
    }

    /**
     * معالجة فشل التنقل
     * Handle navigation failure
     */
    fun handleNavigationFailure(
        rootNode: AccessibilityNodeInfo,
        packageName: String,
        currentState: String,
        lastError: String = ""
    ): FallbackResult {
        val context = FallbackContext(
            packageName = packageName,
            currentState = currentState,
            lastError = lastError,
            screenTexts = NodeFinder.getAllText(rootNode)
        )
        return handleFailure(rootNode, context, FallbackType.NAVIGATION)
    }

    /**
     * محاولة استعادة من حالة فشل عامة
     * Attempt recovery from general failure state
     */
    fun attemptRecovery(
        rootNode: AccessibilityNodeInfo,
        packageName: String,
        currentState: String,
        lastError: String = ""
    ): FallbackResult {
        val context = FallbackContext(
            packageName = packageName,
            currentState = currentState,
            lastError = lastError,
            screenTexts = NodeFinder.getAllText(rootNode)
        )
        return handleFailure(rootNode, context, FallbackType.RECOVERY)
    }

    /**
     * إعادة تعيين عداد المحاولات
     * Reset attempt counters
     */
    fun resetAttempts() {
        sessionAttempts.clear()
        AppLogger.d("Fallback attempts reset", TAG)
    }

    /**
     * إعادة تعيين عداد المحاولات لتطبيق معين
     * Reset attempt counters for specific package
     */
    fun resetAttemptsForPackage(packageName: String) {
        sessionAttempts.keys.filter { it.startsWith(packageName) }.forEach {
            sessionAttempts.remove(it)
        }
        AppLogger.d("Fallback attempts reset for $packageName", TAG)
    }

    /**
     * تعيين الحد الأقصى للمحاولات
     * Set maximum attempts per fallback type
     */
    fun setMaxAttempts(max: Int) {
        maxAttemptsPerType = max
    }

    /**
     * الحصول على إحصائيات المحاولات
     * Get attempt statistics
     */
    fun getAttemptStats(): Map<String, Int> {
        return sessionAttempts.toMap()
    }
}
