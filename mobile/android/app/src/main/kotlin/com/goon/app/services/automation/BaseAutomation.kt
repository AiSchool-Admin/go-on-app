package com.goon.app.services.automation

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.os.Bundle
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import com.goon.app.services.config.AppConstants
import com.goon.app.services.config.TimingConfig
import com.goon.app.services.config.PricePatterns
import com.goon.app.utils.AppLogger

/**
 * حالات الأتمتة - تتبع تقدم عملية الحصول على السعر
 * Automation states - track price fetching progress
 */
enum class AutomationState {
    IDLE,
    WAITING_FOR_APP,
    // InDriver-specific states for entering PICKUP first
    FINDING_PICKUP_FIELD,
    ENTERING_PICKUP,
    WAITING_FOR_PICKUP_SUGGESTIONS,
    CONFIRMING_PICKUP_ON_MAP,
    // Common states
    FINDING_DESTINATION_FIELD,
    ENTERING_DESTINATION,
    WAITING_FOR_SUGGESTIONS,
    SELECTING_SUGGESTION,
    WAITING_FOR_PRICE,
    PRICE_CAPTURED,
    FAILED
}

/**
 * نتيجة عملية الأتمتة
 * Automation operation result
 */
data class AutomationResult(
    val success: Boolean,
    val message: String = "",
    val shouldRetry: Boolean = false,
    val nextState: AutomationState? = null
)

/**
 * معلومات السعر المستخرج
 * Extracted price information
 */
data class ExtractedPrice(
    val price: Double,
    val currency: String = "EGP",
    val serviceType: String = "",
    val eta: Int = 0,
    val allPrices: List<Double> = emptyList(),
    val vehiclePrices: Map<String, Double> = emptyMap(),
    val rawTexts: List<String> = emptyList()
)

/**
 * الفئة الأساسية للأتمتة - كل تطبيق يرث من هذه الفئة
 * Base automation class - each app extends this
 *
 * This abstract class provides:
 * - Common automation flow
 * - Reusable click/gesture methods
 * - Node finding utilities
 * - State management
 */
abstract class BaseAutomation(
    protected val service: AccessibilityService,
    protected val tag: String
) {
    // ============================================================
    // ABSTRACT PROPERTIES - Must be implemented by each app
    // ============================================================

    /** اسم الحزمة للتطبيق */
    abstract val packageName: String

    /** اسم التطبيق للعرض */
    abstract val displayName: String

    /** هل يدعم deep link كامل للإحداثيات؟ */
    abstract val supportsFullDeepLink: Boolean

    /** هل يحتاج إدخال نقطة الانطلاق أولاً؟ */
    abstract val requiresPickupFirst: Boolean

    // ============================================================
    // ABSTRACT METHODS - Must be implemented by each app
    // ============================================================

    /**
     * البحث والنقر على حقل نقطة الانطلاق
     * Find and click pickup field
     */
    abstract fun findAndClickPickupField(rootNode: AccessibilityNodeInfo): AutomationResult

    /**
     * إدخال نص نقطة الانطلاق
     * Enter pickup text
     */
    abstract fun enterPickupText(rootNode: AccessibilityNodeInfo, address: String, lat: Double, lng: Double): AutomationResult

    /**
     * البحث والنقر على حقل الوجهة
     * Find and click destination field
     */
    abstract fun findAndClickDestinationField(rootNode: AccessibilityNodeInfo): AutomationResult

    /**
     * إدخال نص الوجهة
     * Enter destination text
     */
    abstract fun enterDestinationText(rootNode: AccessibilityNodeInfo, address: String, lat: Double, lng: Double): AutomationResult

    /**
     * اختيار اقتراح من القائمة
     * Select suggestion from list
     */
    abstract fun selectSuggestion(rootNode: AccessibilityNodeInfo, isForPickup: Boolean): AutomationResult

    /**
     * معالجة الشاشات الوسيطة (dialogs, confirmations)
     * Handle intermediate screens
     */
    abstract fun handleIntermediateScreens(rootNode: AccessibilityNodeInfo): AutomationResult

    /**
     * استخراج الأسعار من الشاشة
     * Extract prices from screen
     */
    abstract fun extractPrices(rootNode: AccessibilityNodeInfo): ExtractedPrice?

    /**
     * التحقق من وجود السعر على الشاشة
     * Check if price screen is visible
     */
    abstract fun isPriceScreenVisible(rootNode: AccessibilityNodeInfo): Boolean

    // ============================================================
    // COMMON METHODS - Shared by all apps
    // ============================================================

    /**
     * نقر ذكي - يجرب عدة استراتيجيات
     * Smart click - tries multiple strategies
     */
    protected fun smartClick(node: AccessibilityNodeInfo): Boolean {
        AppLogger.click(tag, "smartClick: Attempting smart click")

        // Strategy 1: Direct click if clickable
        if (node.isClickable) {
            if (node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                AppLogger.click(tag, "smartClick: Direct ACTION_CLICK succeeded")
                return true
            }
        }

        // Strategy 2: Focus then click
        if (node.isFocusable) {
            node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
            Thread.sleep(TimingConfig.textInputDelay)
            if (node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                AppLogger.click(tag, "smartClick: Focus+Click succeeded")
                return true
            }
        }

        // Strategy 3: Try parent clicks
        var current = node.parent
        for (level in 1..4) {
            if (current == null) break
            if (current.isClickable) {
                if (current.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                    AppLogger.click(tag, "smartClick: Parent L$level click succeeded")
                    current.recycle()
                    return true
                }
            }
            val next = current.parent
            current.recycle()
            current = next
        }

        // Strategy 4: Gesture click at node bounds
        val rect = Rect()
        node.getBoundsInScreen(rect)
        if (rect.width() > 0 && rect.height() > 0) {
            if (clickAtPosition(rect.centerX().toFloat(), rect.centerY().toFloat())) {
                AppLogger.click(tag, "smartClick: Gesture click succeeded")
                return true
            }
        }

        AppLogger.click(tag, "smartClick: All strategies failed", success = false)
        return false
    }

    /**
     * نقر لطيف - للتطبيقات الحساسة للنقرات السريعة
     * Gentle click - for apps sensitive to rapid clicks
     */
    protected fun gentleClick(node: AccessibilityNodeInfo): Boolean {
        AppLogger.click(tag, "gentleClick: Attempting gentle click")

        // Only use ACTION_CLICK, no gestures
        if (node.isClickable) {
            if (node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                return true
            }
        }

        // Try clickable parent
        var parent = node.parent
        for (level in 1..3) {
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
     * النقر باستخدام الإيماءات في موقع محدد
     * Click at specific position using gestures
     */
    protected fun clickAtPosition(x: Float, y: Float, duration: Long = 100): Boolean {
        try {
            val path = Path()
            path.moveTo(x, y)

            val gesture = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, duration))
                .build()

            service.dispatchGesture(gesture, null, null)
            AppLogger.click(tag, "clickAtPosition: Dispatched at ($x, $y)")
            return true
        } catch (e: Exception) {
            AppLogger.e("clickAtPosition failed", e, null, tag)
            return false
        }
    }

    /**
     * النقر في موقع مع مدة مخصصة
     * Click at position with custom duration
     */
    protected fun clickAtPositionWithDuration(x: Float, y: Float, durationMs: Long): Boolean {
        return clickAtPosition(x, y, durationMs)
    }

    /**
     * البحث عن عقدة بالنص
     * Find node with text
     */
    protected fun findNodeWithText(rootNode: AccessibilityNodeInfo, text: String): AccessibilityNodeInfo? {
        val nodes = rootNode.findAccessibilityNodeInfosByText(text)
        return nodes.firstOrNull()
    }

    /**
     * البحث عن كل العقد بالنص
     * Find all nodes with text
     */
    protected fun findAllNodesWithText(rootNode: AccessibilityNodeInfo, text: String): List<AccessibilityNodeInfo> {
        return rootNode.findAccessibilityNodeInfosByText(text)
    }

    /**
     * البحث عن كل حقول الإدخال
     * Find all EditText fields
     */
    protected fun findAllEditTexts(node: AccessibilityNodeInfo, results: MutableList<AccessibilityNodeInfo>) {
        val className = node.className?.toString()?.lowercase() ?: ""
        if (className.contains("edittext") || className.contains("edit")) {
            results.add(AccessibilityNodeInfo.obtain(node))
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            findAllEditTexts(child, results)
            child.recycle()
        }
    }

    /**
     * البحث عن حقل الإدخال المركز
     * Find focused EditText
     */
    protected fun findFocusedEditText(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (node.isFocused) {
            val className = node.className?.toString()?.lowercase() ?: ""
            if (className.contains("edittext") || className.contains("edit")) {
                return AccessibilityNodeInfo.obtain(node)
            }
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = findFocusedEditText(child)
            child.recycle()
            if (found != null) return found
        }

        return null
    }

    /**
     * الحصول على كل النصوص المرئية
     * Get all visible text from UI tree
     */
    protected fun getAllTextFromNode(node: AccessibilityNodeInfo): List<String> {
        val texts = mutableListOf<String>()
        collectAllText(node, texts)
        return texts
    }

    private fun collectAllText(node: AccessibilityNodeInfo, texts: MutableList<String>) {
        node.text?.toString()?.let {
            if (it.isNotBlank()) texts.add(it)
        }
        node.contentDescription?.toString()?.let {
            if (it.isNotBlank()) texts.add(it)
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            collectAllText(child, texts)
            child.recycle()
        }
    }

    /**
     * إدخال نص في حقل الإدخال
     * Enter text into EditText
     */
    protected fun enterTextInField(node: AccessibilityNodeInfo, text: String): Boolean {
        val arguments = Bundle()
        arguments.putCharSequence(
            AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
            text
        )

        if (node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)) {
            AppLogger.d("Entered text: ${text.take(20)}...", tag)
            return true
        }

        return false
    }

    /**
     * مسح نص حقل الإدخال
     * Clear EditText content
     */
    protected fun clearTextField(node: AccessibilityNodeInfo): Boolean {
        val arguments = Bundle()
        arguments.putCharSequence(
            AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
            ""
        )
        return node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
    }

    /**
     * البحث عن عقدة بنص مُعالَج (Unicode normalization)
     * Find nodes with normalized text (handles RTL marks, zero-width chars)
     */
    protected fun findAllNodesWithNormalizedText(rootNode: AccessibilityNodeInfo, searchText: String): List<AccessibilityNodeInfo> {
        val results = mutableListOf<AccessibilityNodeInfo>()
        findNodesWithNormalizedTextRecursive(rootNode, searchText.normalizeArabic(), results)
        return results
    }

    private fun findNodesWithNormalizedTextRecursive(
        node: AccessibilityNodeInfo,
        normalizedSearch: String,
        results: MutableList<AccessibilityNodeInfo>
    ) {
        val nodeText = node.text?.toString()?.normalizeArabic() ?: ""
        val nodeDesc = node.contentDescription?.toString()?.normalizeArabic() ?: ""

        if (nodeText.contains(normalizedSearch, ignoreCase = true) ||
            nodeDesc.contains(normalizedSearch, ignoreCase = true)) {
            results.add(AccessibilityNodeInfo.obtain(node))
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            findNodesWithNormalizedTextRecursive(child, normalizedSearch, results)
            child.recycle()
        }
    }

    /**
     * التحقق من احتواء النص (مع معالجة Unicode)
     * Normalized contains check
     */
    protected fun normalizedContains(text: String, search: String): Boolean {
        return text.normalizeArabic().contains(search.normalizeArabic(), ignoreCase = true)
    }

    /**
     * استخراج السعر من نص
     * Extract price from text using regex patterns
     */
    protected fun extractPriceFromText(text: String): Double? {
        return PricePatterns.extractPriceForApp(text, packageName)
    }

    /**
     * استخراج كل الأسعار من نص
     * Extract all prices from text
     */
    protected fun extractAllPricesFromText(text: String): List<Double> {
        return PricePatterns.extractAllPrices(text)
    }

    /**
     * تسجيل كل النصوص للتشخيص
     * Log all visible text for debugging
     */
    protected fun logAllVisibleText(rootNode: AccessibilityNodeInfo, prefix: String = "") {
        val allText = getAllTextFromNode(rootNode)
        AppLogger.d("=== VISIBLE TEXT ($prefix) ===", tag)
        allText.take(30).forEachIndexed { index, text ->
            AppLogger.d("[$index] '$text'", tag)
        }
        AppLogger.d("=== END (${allText.size} items) ===", tag)
    }

    /**
     * تسجيل مفصل للعناصر
     * Detailed logging of nodes
     */
    protected fun logAllVisibleTextDetailed(node: AccessibilityNodeInfo, depth: Int) {
        if (depth > 15) return

        val text = node.text?.toString() ?: ""
        val desc = node.contentDescription?.toString() ?: ""
        val className = node.className?.toString()?.substringAfterLast(".") ?: ""
        val viewId = node.viewIdResourceName ?: ""

        if (text.isNotBlank() || desc.isNotBlank()) {
            val prefix = "  ".repeat(depth)
            AppLogger.d("$prefix[$className] text='$text' desc='$desc' click=${node.isClickable} id='$viewId'", tag)
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            logAllVisibleTextDetailed(child, depth + 1)
            child.recycle()
        }
    }

    // ============================================================
    // EXTENSION FUNCTIONS
    // ============================================================

    /**
     * تطبيع النص العربي (إزالة علامات Unicode المخفية)
     * Normalize Arabic text (remove invisible Unicode markers)
     */
    protected fun String.normalizeArabic(): String {
        return this
            .replace("\u200E", "") // LRM
            .replace("\u200F", "") // RLM
            .replace("\u200B", "") // Zero-width space
            .replace("\u200C", "") // ZWNJ
            .replace("\u200D", "") // ZWJ
            .replace("\u061C", "") // ALM
            .replace("\uFEFF", "") // BOM
            .trim()
    }

    companion object {
        private const val TAG = "BaseAutomation"
    }
}
