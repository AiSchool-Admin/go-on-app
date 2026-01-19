package com.goon.app.services.fallback

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import com.goon.app.services.config.TimingConfig
import com.goon.app.services.utils.GestureHelper
import com.goon.app.services.utils.NodeFinder
import com.goon.app.utils.AppLogger

/**
 * استراتيجية Fallback للنقر
 * Click fallback strategy
 *
 * Handles click failures with multiple approaches:
 * 1. ACTION_CLICK on node
 * 2. ACTION_CLICK on parent nodes
 * 3. Focus then click
 * 4. Gesture tap at center
 * 5. Gesture tap at multiple positions
 * 6. Long tap (for resistant elements)
 * 7. Double tap
 * 8. Tap with delay
 */
class ClickFallbackStrategy(
    service: AccessibilityService
) : FallbackStrategy(service, "ClickFallback") {

    override val type: FallbackType = FallbackType.CLICK
    override val priority: Int = 10

    // Track which strategies have been tried for this session
    private var attemptIndex = 0

    override fun canHandle(context: FallbackContext): Boolean {
        return context.targetElement.isNotEmpty() ||
               context.targetText.isNotEmpty()
    }

    override fun execute(rootNode: AccessibilityNodeInfo, context: FallbackContext): FallbackResult {
        AppLogger.d("ClickFallback: Executing for target='${context.targetText}' element='${context.targetElement}'", tag)

        // Find the target node
        val targetNode = findTargetNode(rootNode, context)
        if (targetNode == null) {
            return failure("NodeNotFound", "Could not find target node")
        }

        try {
            // Get node bounds for gesture-based strategies
            val bounds = Rect()
            targetNode.getBoundsInScreen(bounds)

            // Define all click strategies
            val strategies = listOf(
                ClickAttempt("ACTION_CLICK") { attemptActionClick(targetNode) },
                ClickAttempt("PARENT_CLICK_L1") { attemptParentClick(targetNode, 1) },
                ClickAttempt("PARENT_CLICK_L2") { attemptParentClick(targetNode, 2) },
                ClickAttempt("PARENT_CLICK_L3") { attemptParentClick(targetNode, 3) },
                ClickAttempt("FOCUS_THEN_CLICK") { attemptFocusThenClick(targetNode) },
                ClickAttempt("GESTURE_CENTER") { attemptGestureClick(bounds, 100) },
                ClickAttempt("GESTURE_UPPER") { attemptGestureClickAt(bounds, 0.5f, 0.3f, 100) },
                ClickAttempt("GESTURE_LOWER") { attemptGestureClickAt(bounds, 0.5f, 0.7f, 100) },
                ClickAttempt("LONG_TAP_200") { attemptGestureClick(bounds, 200) },
                ClickAttempt("LONG_TAP_300") { attemptGestureClick(bounds, 300) },
                ClickAttempt("MULTI_POSITION") { attemptMultiPositionClick(bounds) },
                ClickAttempt("DOUBLE_TAP") { attemptDoubleTap(bounds) },
                ClickAttempt("TAP_WITH_DELAY") { attemptTapWithDelay(bounds) }
            )

            // Start from where we left off (or beginning if reset)
            val startIndex = if (context.previousAttempts > 0) {
                minOf(context.previousAttempts, strategies.size - 1)
            } else {
                0
            }

            var attempts = 0
            for (i in startIndex until strategies.size) {
                val strategy = strategies[i]
                attempts++

                logAttempt(strategy.name, false, "Trying...")

                val success = strategy.action()
                if (success) {
                    logAttempt(strategy.name, true)
                    return success(strategy.name, "Click succeeded", attempts)
                }

                // Small delay between attempts
                Thread.sleep(50)
            }

            return failure("AllStrategiesFailed", "Tried $attempts strategies", attempts, shouldContinue = false)

        } finally {
            targetNode.recycle()
        }
    }

    override fun getAttemptDescriptions(): List<String> {
        return listOf(
            "ACTION_CLICK - Direct accessibility click",
            "PARENT_CLICK - Click on parent elements (L1-L3)",
            "FOCUS_THEN_CLICK - Focus element before clicking",
            "GESTURE_CENTER - Gesture tap at center",
            "GESTURE_POSITION - Gesture tap at various positions",
            "LONG_TAP - Extended duration tap (200-300ms)",
            "MULTI_POSITION - Try multiple tap positions",
            "DOUBLE_TAP - Quick double tap",
            "TAP_WITH_DELAY - Tap with pre-delay"
        )
    }

    // ============================================================
    // CLICK STRATEGIES
    // ============================================================

    private fun attemptActionClick(node: AccessibilityNodeInfo): Boolean {
        if (!node.isClickable) return false
        return node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
    }

    private fun attemptParentClick(node: AccessibilityNodeInfo, levels: Int): Boolean {
        var current = node.parent
        for (level in 1..levels) {
            if (current == null) break
            if (current.isClickable) {
                val result = current.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                current.recycle()
                return result
            }
            val next = current.parent
            current.recycle()
            current = next
        }
        current?.recycle()
        return false
    }

    private fun attemptFocusThenClick(node: AccessibilityNodeInfo): Boolean {
        if (node.isFocusable) {
            node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
            Thread.sleep(100)
        }
        return node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
    }

    private fun attemptGestureClick(bounds: Rect, durationMs: Long): Boolean {
        if (bounds.width() <= 0 || bounds.height() <= 0) return false

        return GestureHelper.tapAtPosition(
            service,
            bounds.centerX().toFloat(),
            bounds.centerY().toFloat(),
            durationMs
        )
    }

    private fun attemptGestureClickAt(bounds: Rect, xRatio: Float, yRatio: Float, durationMs: Long): Boolean {
        if (bounds.width() <= 0 || bounds.height() <= 0) return false

        val x = bounds.left + bounds.width() * xRatio
        val y = bounds.top + bounds.height() * yRatio

        return GestureHelper.tapAtPosition(service, x, y, durationMs)
    }

    private fun attemptMultiPositionClick(bounds: Rect): Boolean {
        if (bounds.width() <= 0 || bounds.height() <= 0) return false

        val positions = listOf(
            Pair(0.5f, 0.5f),   // Center
            Pair(0.3f, 0.5f),   // Left
            Pair(0.7f, 0.5f),   // Right
            Pair(0.5f, 0.3f),   // Top
            Pair(0.5f, 0.7f),   // Bottom
            Pair(0.25f, 0.25f), // Top-left
            Pair(0.75f, 0.75f)  // Bottom-right
        )

        for ((xRatio, yRatio) in positions) {
            val x = bounds.left + bounds.width() * xRatio
            val y = bounds.top + bounds.height() * yRatio

            if (GestureHelper.tapAtPosition(service, x, y, 150)) {
                return true
            }
            Thread.sleep(30)
        }

        return false
    }

    private fun attemptDoubleTap(bounds: Rect): Boolean {
        if (bounds.width() <= 0 || bounds.height() <= 0) return false

        val x = bounds.centerX().toFloat()
        val y = bounds.centerY().toFloat()

        GestureHelper.tapAtPosition(service, x, y, 50)
        Thread.sleep(100)
        return GestureHelper.tapAtPosition(service, x, y, 50)
    }

    private fun attemptTapWithDelay(bounds: Rect): Boolean {
        if (bounds.width() <= 0 || bounds.height() <= 0) return false

        // Wait a moment before tapping (helps with loading elements)
        Thread.sleep(200)

        return GestureHelper.tapAtPosition(
            service,
            bounds.centerX().toFloat(),
            bounds.centerY().toFloat(),
            150
        )
    }

    // ============================================================
    // HELPERS
    // ============================================================

    private fun findTargetNode(rootNode: AccessibilityNodeInfo, context: FallbackContext): AccessibilityNodeInfo? {
        // Try by text first
        if (context.targetText.isNotEmpty()) {
            val node = NodeFinder.findByText(rootNode, context.targetText)
            if (node != null) return node

            // Try normalized text
            val normalizedNodes = NodeFinder.findAllByNormalizedText(rootNode, context.targetText)
            if (normalizedNodes.isNotEmpty()) {
                return normalizedNodes.first()
            }
        }

        // Try by element identifier (resource ID pattern)
        if (context.targetElement.isNotEmpty()) {
            val node = NodeFinder.findByResourceId(rootNode, listOf(context.targetElement))
            if (node != null) return node
        }

        return null
    }

    /**
     * محاولة نقر واحدة
     * Single click attempt
     */
    private data class ClickAttempt(
        val name: String,
        val action: () -> Boolean
    )
}

/**
 * استراتيجية Fallback للنقر على الاقتراحات
 * Click fallback strategy for suggestions
 *
 * Specialized for clicking suggestion items which often have
 * non-standard clickable areas
 */
class SuggestionClickFallbackStrategy(
    service: AccessibilityService
) : FallbackStrategy(service, "SuggestionClickFallback") {

    override val type: FallbackType = FallbackType.SUGGESTION
    override val priority: Int = 15

    override fun canHandle(context: FallbackContext): Boolean {
        return context.currentState.contains("SUGGESTION", ignoreCase = true) ||
               context.currentState.contains("WAITING", ignoreCase = true)
    }

    override fun execute(rootNode: AccessibilityNodeInfo, context: FallbackContext): FallbackResult {
        AppLogger.d("SuggestionClickFallback: Looking for suggestions", tag)

        // Get all visible text
        val allText = NodeFinder.getAllText(rootNode)

        // Filter for suggestion-like items (with distance indicators)
        val suggestions = allText.filter { text ->
            (text.contains("km", ignoreCase = true) ||
             text.contains("كم") ||
             text.contains("متر") ||
             text.contains("min", ignoreCase = true) ||
             text.contains("دقيقة")) &&
            text.length > 5 &&
            !text.contains("Choose", ignoreCase = true) &&
            !text.contains("اختر")
        }

        if (suggestions.isEmpty()) {
            return failure("NoSuggestions", "No suggestion items found")
        }

        AppLogger.d("Found ${suggestions.size} suggestions: ${suggestions.take(3)}", tag)

        var attempts = 0
        for (suggestion in suggestions.take(5)) {
            attempts++

            val nodes = rootNode.findAccessibilityNodeInfosByText(suggestion)
            for (node in nodes) {
                val bounds = Rect()
                node.getBoundsInScreen(bounds)

                // Try multiple click strategies
                val strategies = listOf(
                    { node.performAction(AccessibilityNodeInfo.ACTION_CLICK) },
                    { GestureHelper.clickNodeOrParent(node) },
                    { GestureHelper.tapAtPosition(service, bounds.centerX().toFloat(), bounds.centerY().toFloat(), 150) },
                    { GestureHelper.tapAtPosition(service, bounds.centerX().toFloat(), bounds.centerY().toFloat(), 250) }
                )

                for (strategy in strategies) {
                    if (strategy()) {
                        node.recycle()
                        nodes.forEach { try { it.recycle() } catch (e: Exception) {} }
                        return success("SuggestionClick", "Clicked: $suggestion", attempts)
                    }
                }
                node.recycle()
            }
        }

        return failure("AllSuggestionsFailed", "Tried $attempts suggestions", attempts)
    }

    override fun getAttemptDescriptions(): List<String> {
        return listOf(
            "Find suggestions by distance indicator (km/كم/متر)",
            "Try ACTION_CLICK on each suggestion",
            "Try parent click on each suggestion",
            "Try gesture tap on each suggestion",
            "Try long tap on each suggestion"
        )
    }
}
