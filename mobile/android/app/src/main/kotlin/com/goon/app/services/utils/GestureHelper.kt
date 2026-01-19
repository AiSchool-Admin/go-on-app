package com.goon.app.services.utils

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import com.goon.app.services.config.TimingConfig
import com.goon.app.utils.AppLogger

/**
 * مساعد الإيماءات والنقرات
 * Gesture and click helper utilities
 *
 * Provides various click strategies for different app behaviors:
 * - Some apps respond to ACTION_CLICK
 * - Some require gesture-based taps
 * - Some need longer tap duration
 * - Some crash with rapid clicks (need throttling)
 */
object GestureHelper {
    private const val TAG = "GestureHelper"

    /**
     * نقر ذكي - يجرب عدة استراتيجيات تلقائياً
     * Smart click - automatically tries multiple strategies
     *
     * @param service AccessibilityService instance
     * @param node Node to click
     * @param maxParentLevels How many parent levels to try
     * @return true if any click succeeded
     */
    fun smartClick(
        service: AccessibilityService,
        node: AccessibilityNodeInfo,
        maxParentLevels: Int = 4
    ): Boolean {
        // Strategy 1: Direct ACTION_CLICK
        if (node.isClickable) {
            if (node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                AppLogger.click(TAG, "smartClick: Direct click succeeded")
                return true
            }
        }

        // Strategy 2: Focus then click
        if (node.isFocusable) {
            node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
            Thread.sleep(TimingConfig.textInputDelay)
            if (node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                AppLogger.click(TAG, "smartClick: Focus+click succeeded")
                return true
            }
        }

        // Strategy 3: Try parent clicks
        var current = node.parent
        for (level in 1..maxParentLevels) {
            if (current == null) break
            if (current.isClickable) {
                if (current.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                    AppLogger.click(TAG, "smartClick: Parent L$level click succeeded")
                    current.recycle()
                    return true
                }
            }
            val next = current.parent
            current.recycle()
            current = next
        }

        // Strategy 4: Gesture click at bounds center
        val rect = Rect()
        node.getBoundsInScreen(rect)
        if (rect.width() > 0 && rect.height() > 0) {
            if (tapAtPosition(service, rect.centerX().toFloat(), rect.centerY().toFloat())) {
                AppLogger.click(TAG, "smartClick: Gesture click succeeded")
                return true
            }
        }

        AppLogger.click(TAG, "smartClick: All strategies failed", success = false)
        return false
    }

    /**
     * نقر لطيف - للتطبيقات الحساسة (مثل InDriver)
     * Gentle click - for sensitive apps (like InDriver)
     *
     * Only uses ACTION_CLICK, no gestures
     */
    fun gentleClick(node: AccessibilityNodeInfo, maxParentLevels: Int = 3): Boolean {
        // Only ACTION_CLICK
        if (node.isClickable) {
            if (node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                return true
            }
        }

        // Try clickable parent
        var parent = node.parent
        for (level in 1..maxParentLevels) {
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

    /**
     * نقر باستخدام الإيماءات
     * Tap using gesture API
     *
     * @param service AccessibilityService instance
     * @param x X coordinate
     * @param y Y coordinate
     * @param durationMs Tap duration in milliseconds
     * @return true if gesture was dispatched
     */
    fun tapAtPosition(
        service: AccessibilityService,
        x: Float,
        y: Float,
        durationMs: Long = 100
    ): Boolean {
        try {
            val path = Path()
            path.moveTo(x, y)

            val gesture = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, durationMs))
                .build()

            service.dispatchGesture(gesture, null, null)
            AppLogger.click(TAG, "tapAtPosition: Dispatched at ($x, $y) duration=${durationMs}ms")
            return true
        } catch (e: Exception) {
            AppLogger.e("tapAtPosition failed", e, null, TAG)
            return false
        }
    }

    /**
     * نقر طويل (للتطبيقات التي تحتاج tap أطول)
     * Long tap (for apps that need longer tap duration)
     */
    fun longTap(
        service: AccessibilityService,
        x: Float,
        y: Float,
        durationMs: Long = 200
    ): Boolean {
        return tapAtPosition(service, x, y, durationMs)
    }

    /**
     * نقر على عقدة باستخدام الإيماءات
     * Tap on node using gestures
     */
    fun tapOnNode(
        service: AccessibilityService,
        node: AccessibilityNodeInfo,
        durationMs: Long = 100
    ): Boolean {
        val rect = Rect()
        node.getBoundsInScreen(rect)

        if (rect.width() <= 0 || rect.height() <= 0) {
            AppLogger.click(TAG, "tapOnNode: Invalid bounds", success = false)
            return false
        }

        return tapAtPosition(service, rect.centerX().toFloat(), rect.centerY().toFloat(), durationMs)
    }

    /**
     * نقر على عقدة مع تجربة مواضع مختلفة
     * Tap on node with multiple position attempts
     *
     * Useful for apps where the clickable area is not at center
     */
    fun tapOnNodeMultiplePositions(
        service: AccessibilityService,
        node: AccessibilityNodeInfo,
        durationMs: Long = 100
    ): Boolean {
        val rect = Rect()
        node.getBoundsInScreen(rect)

        if (rect.width() <= 0 || rect.height() <= 0) return false

        // Try different positions
        val positions = listOf(
            Pair(rect.centerX().toFloat(), rect.centerY().toFloat()),                    // Center
            Pair(rect.centerX().toFloat(), rect.top + rect.height() * 0.33f),            // Upper third
            Pair(rect.centerX().toFloat(), rect.top + rect.height() * 0.67f),            // Lower third
            Pair(rect.left + rect.width() * 0.25f, rect.centerY().toFloat()),            // Left quarter
            Pair(rect.left + rect.width() * 0.75f, rect.centerY().toFloat())             // Right quarter
        )

        for ((x, y) in positions) {
            if (tapAtPosition(service, x, y, durationMs)) {
                AppLogger.click(TAG, "tapOnNodeMultiplePositions: Success at ($x, $y)")
                return true
            }
            Thread.sleep(50)
        }

        return false
    }

    /**
     * سحب (swipe) من نقطة إلى أخرى
     * Swipe from one point to another
     */
    fun swipe(
        service: AccessibilityService,
        startX: Float,
        startY: Float,
        endX: Float,
        endY: Float,
        durationMs: Long = 300
    ): Boolean {
        try {
            val path = Path()
            path.moveTo(startX, startY)
            path.lineTo(endX, endY)

            val gesture = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, durationMs))
                .build()

            service.dispatchGesture(gesture, null, null)
            AppLogger.d("Swipe: ($startX,$startY) -> ($endX,$endY)", TAG)
            return true
        } catch (e: Exception) {
            AppLogger.e("swipe failed", e, null, TAG)
            return false
        }
    }

    /**
     * التمرير لأسفل
     * Scroll down
     */
    fun scrollDown(
        service: AccessibilityService,
        screenWidth: Int,
        screenHeight: Int
    ): Boolean {
        val centerX = screenWidth / 2f
        val startY = screenHeight * 0.7f
        val endY = screenHeight * 0.3f
        return swipe(service, centerX, startY, centerX, endY)
    }

    /**
     * التمرير لأعلى
     * Scroll up
     */
    fun scrollUp(
        service: AccessibilityService,
        screenWidth: Int,
        screenHeight: Int
    ): Boolean {
        val centerX = screenWidth / 2f
        val startY = screenHeight * 0.3f
        val endY = screenHeight * 0.7f
        return swipe(service, centerX, startY, centerX, endY)
    }

    /**
     * النقر على العقدة أو أحد أبائها
     * Click node or one of its parents
     */
    fun clickNodeOrParent(node: AccessibilityNodeInfo, maxLevels: Int = 5): Boolean {
        // Try node first
        if (node.isClickable) {
            if (node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                return true
            }
        }

        // Try parents
        var current = node.parent
        for (level in 1..maxLevels) {
            if (current == null) break
            if (current.isClickable) {
                if (current.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                    current.recycle()
                    return true
                }
            }
            val next = current.parent
            current.recycle()
            current = next
        }

        return false
    }

    /**
     * إدخال نص في حقل
     * Enter text into field
     */
    fun enterText(node: AccessibilityNodeInfo, text: String): Boolean {
        val arguments = Bundle()
        arguments.putCharSequence(
            AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
            text
        )
        return node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
    }

    /**
     * مسح النص من حقل
     * Clear text from field
     */
    fun clearText(node: AccessibilityNodeInfo): Boolean {
        return enterText(node, "")
    }

    /**
     * الضغط على زر لوحة المفاتيح "Done"
     * Press keyboard Done button
     */
    fun pressKeyboardDone(service: AccessibilityService): Boolean {
        // Send IME action
        val rootNode = service.rootInActiveWindow ?: return false

        try {
            // Find focused EditText and send IME action
            val focusedNode = findFocusedInput(rootNode)
            if (focusedNode != null) {
                val result = focusedNode.performAction(
                    AccessibilityNodeInfo.ACTION_SET_TEXT,
                    Bundle().apply {
                        putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_MOVEMENT_GRANULARITY_INT, 1)
                    }
                )
                focusedNode.recycle()
                return result
            }
        } finally {
            rootNode.recycle()
        }

        return false
    }

    private fun findFocusedInput(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (node.isFocused) {
            val className = node.className?.toString()?.lowercase() ?: ""
            if (className.contains("edit")) {
                return AccessibilityNodeInfo.obtain(node)
            }
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = findFocusedInput(child)
            child.recycle()
            if (found != null) return found
        }

        return null
    }
}
