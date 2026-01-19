package com.goon.app.services.fallback

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityNodeInfo
import com.goon.app.utils.AppLogger

/**
 * نتيجة تنفيذ استراتيجية الـ Fallback
 * Fallback strategy execution result
 */
data class FallbackResult(
    val success: Boolean,
    val strategyUsed: String = "",
    val message: String = "",
    val attemptsMade: Int = 0,
    val shouldContinue: Boolean = true  // Whether to try next strategy
)

/**
 * سياق الـ Fallback - معلومات عن الحالة الحالية
 * Fallback context - information about current state
 */
data class FallbackContext(
    val packageName: String,
    val currentState: String,
    val targetElement: String = "",
    val targetText: String = "",
    val previousAttempts: Int = 0,
    val lastError: String = "",
    val screenTexts: List<String> = emptyList(),
    val metadata: Map<String, Any> = emptyMap()
)

/**
 * نوع الـ Fallback
 * Fallback type
 */
enum class FallbackType {
    CLICK,          // Failed to click element
    TEXT_ENTRY,     // Failed to enter text
    NAVIGATION,     // Failed to navigate/find screen
    SUGGESTION,     // Failed to select suggestion
    PRICE_EXTRACT,  // Failed to extract price
    INTERMEDIATE,   // Stuck on intermediate screen
    RECOVERY        // General recovery from failed state
}

/**
 * الفئة الأساسية لاستراتيجيات الـ Fallback
 * Base class for fallback strategies
 *
 * Each fallback strategy implements multiple approaches to handle failures:
 * 1. Primary approach (most likely to work)
 * 2. Secondary approaches (alternatives)
 * 3. Last resort (aggressive but might have side effects)
 */
abstract class FallbackStrategy(
    protected val service: AccessibilityService,
    protected val tag: String
) {
    /**
     * نوع الـ Fallback الذي تعالجه هذه الاستراتيجية
     * The fallback type this strategy handles
     */
    abstract val type: FallbackType

    /**
     * الأولوية (أقل = أعلى أولوية)
     * Priority (lower = higher priority)
     */
    abstract val priority: Int

    /**
     * هل يمكن معالجة هذا السياق؟
     * Can this strategy handle the given context?
     */
    abstract fun canHandle(context: FallbackContext): Boolean

    /**
     * تنفيذ الاستراتيجية
     * Execute the strategy
     */
    abstract fun execute(
        rootNode: AccessibilityNodeInfo,
        context: FallbackContext
    ): FallbackResult

    /**
     * الحصول على قائمة المحاولات المتاحة
     * Get list of available attempts
     */
    abstract fun getAttemptDescriptions(): List<String>

    /**
     * تسجيل محاولة
     * Log an attempt
     */
    protected fun logAttempt(attemptName: String, success: Boolean, details: String = "") {
        val status = if (success) "✓" else "✗"
        AppLogger.d(tag, "$status Fallback attempt: $attemptName${if (details.isNotEmpty()) " - $details" else ""}")
    }

    /**
     * إنشاء نتيجة ناجحة
     * Create success result
     */
    protected fun success(strategyUsed: String, message: String = "", attempts: Int = 1): FallbackResult {
        return FallbackResult(
            success = true,
            strategyUsed = strategyUsed,
            message = message,
            attemptsMade = attempts,
            shouldContinue = false
        )
    }

    /**
     * إنشاء نتيجة فاشلة
     * Create failure result
     */
    protected fun failure(strategyUsed: String, message: String = "", attempts: Int = 1, shouldContinue: Boolean = true): FallbackResult {
        return FallbackResult(
            success = false,
            strategyUsed = strategyUsed,
            message = message,
            attemptsMade = attempts,
            shouldContinue = shouldContinue
        )
    }
}

/**
 * استراتيجية Fallback مركبة - تجمع عدة استراتيجيات
 * Composite fallback strategy - combines multiple strategies
 */
class CompositeFallbackStrategy(
    service: AccessibilityService,
    private val strategies: List<FallbackStrategy>
) : FallbackStrategy(service, "CompositeFallback") {

    override val type: FallbackType = FallbackType.RECOVERY
    override val priority: Int = 0

    override fun canHandle(context: FallbackContext): Boolean {
        return strategies.any { it.canHandle(context) }
    }

    override fun execute(rootNode: AccessibilityNodeInfo, context: FallbackContext): FallbackResult {
        var totalAttempts = 0
        val triedStrategies = mutableListOf<String>()

        // Sort by priority and try each
        val sortedStrategies = strategies
            .filter { it.canHandle(context) }
            .sortedBy { it.priority }

        for (strategy in sortedStrategies) {
            AppLogger.d(tag, "Trying fallback strategy: ${strategy::class.simpleName}")

            val result = strategy.execute(rootNode, context)
            totalAttempts += result.attemptsMade
            triedStrategies.add(result.strategyUsed)

            if (result.success) {
                return FallbackResult(
                    success = true,
                    strategyUsed = triedStrategies.joinToString(" → "),
                    message = result.message,
                    attemptsMade = totalAttempts,
                    shouldContinue = false
                )
            }

            if (!result.shouldContinue) {
                break
            }
        }

        return FallbackResult(
            success = false,
            strategyUsed = triedStrategies.joinToString(" → "),
            message = "All ${triedStrategies.size} strategies failed",
            attemptsMade = totalAttempts,
            shouldContinue = false
        )
    }

    override fun getAttemptDescriptions(): List<String> {
        return strategies.flatMap { it.getAttemptDescriptions() }
    }
}
