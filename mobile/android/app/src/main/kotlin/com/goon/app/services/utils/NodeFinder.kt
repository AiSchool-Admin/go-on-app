package com.goon.app.services.utils

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import com.goon.app.utils.AppLogger

/**
 * مساعد للبحث عن العناصر في شجرة الـ Accessibility
 * Node finder utility for accessibility tree
 *
 * Provides various search strategies:
 * - By text (exact and partial)
 * - By resource ID
 * - By class name
 * - By position
 * - By normalized text (handles Arabic Unicode issues)
 */
object NodeFinder {
    private const val TAG = "NodeFinder"

    // ============================================================
    // TEXT SEARCH
    // ============================================================

    /**
     * البحث عن عقدة بالنص الكامل
     * Find node by exact text
     */
    fun findByText(rootNode: AccessibilityNodeInfo, text: String): AccessibilityNodeInfo? {
        val nodes = rootNode.findAccessibilityNodeInfosByText(text)
        return nodes.firstOrNull()
    }

    /**
     * البحث عن كل العقد بالنص
     * Find all nodes by text
     */
    fun findAllByText(rootNode: AccessibilityNodeInfo, text: String): List<AccessibilityNodeInfo> {
        return rootNode.findAccessibilityNodeInfosByText(text)
    }

    /**
     * البحث عن عقدة بنص جزئي
     * Find node by partial text match
     */
    fun findByPartialText(rootNode: AccessibilityNodeInfo, partialTexts: List<String>): AccessibilityNodeInfo? {
        for (text in partialTexts) {
            val nodes = rootNode.findAccessibilityNodeInfosByText(text)
            if (nodes.isNotEmpty()) {
                return nodes.first()
            }
        }
        return null
    }

    /**
     * البحث عن عقدة بنص مُعالَج (Unicode normalization)
     * Find nodes with normalized text (handles RTL marks, zero-width chars)
     */
    fun findAllByNormalizedText(
        rootNode: AccessibilityNodeInfo,
        searchText: String
    ): List<AccessibilityNodeInfo> {
        val results = mutableListOf<AccessibilityNodeInfo>()
        findByNormalizedTextRecursive(rootNode, searchText.normalizeArabic(), results)
        return results
    }

    private fun findByNormalizedTextRecursive(
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
            findByNormalizedTextRecursive(child, normalizedSearch, results)
            child.recycle()
        }
    }

    // ============================================================
    // RESOURCE ID SEARCH
    // ============================================================

    /**
     * البحث عن عقدة بمعرف المورد
     * Find node by resource ID
     */
    fun findByResourceId(rootNode: AccessibilityNodeInfo, resourceIds: List<String>): AccessibilityNodeInfo? {
        for (resourceId in resourceIds) {
            val found = findByResourceIdRecursive(rootNode, resourceId)
            if (found != null) return found
        }
        return null
    }

    private fun findByResourceIdRecursive(
        node: AccessibilityNodeInfo,
        resourceId: String
    ): AccessibilityNodeInfo? {
        val viewId = node.viewIdResourceName?.lowercase() ?: ""
        if (viewId.contains(resourceId.lowercase())) {
            return AccessibilityNodeInfo.obtain(node)
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = findByResourceIdRecursive(child, resourceId)
            child.recycle()
            if (found != null) return found
        }

        return null
    }

    /**
     * البحث والنقر بمعرف المورد
     * Find and click by resource ID
     */
    fun findAndClickByResourceId(
        rootNode: AccessibilityNodeInfo,
        resourceIds: List<String>
    ): Boolean {
        val node = findByResourceId(rootNode, resourceIds) ?: return false

        val clicked = if (node.isClickable) {
            node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        } else {
            GestureHelper.clickNodeOrParent(node)
        }

        node.recycle()
        return clicked
    }

    // ============================================================
    // CLASS NAME SEARCH
    // ============================================================

    /**
     * البحث عن كل عناصر EditText
     * Find all EditText fields
     */
    fun findAllEditTexts(rootNode: AccessibilityNodeInfo): List<AccessibilityNodeInfo> {
        val results = mutableListOf<AccessibilityNodeInfo>()
        findByClassNameRecursive(rootNode, listOf("edittext", "edit"), results)
        return results
    }

    /**
     * البحث عن عنصر EditText المركز
     * Find focused EditText
     */
    fun findFocusedEditText(rootNode: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        return findFocusedEditTextRecursive(rootNode)
    }

    private fun findFocusedEditTextRecursive(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (node.isFocused) {
            val className = node.className?.toString()?.lowercase() ?: ""
            if (className.contains("edittext") || className.contains("edit")) {
                return AccessibilityNodeInfo.obtain(node)
            }
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = findFocusedEditTextRecursive(child)
            child.recycle()
            if (found != null) return found
        }

        return null
    }

    private fun findByClassNameRecursive(
        node: AccessibilityNodeInfo,
        classPatterns: List<String>,
        results: MutableList<AccessibilityNodeInfo>
    ) {
        val className = node.className?.toString()?.lowercase() ?: ""
        if (classPatterns.any { className.contains(it) }) {
            results.add(AccessibilityNodeInfo.obtain(node))
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            findByClassNameRecursive(child, classPatterns, results)
            child.recycle()
        }
    }

    // ============================================================
    // POSITION-BASED SEARCH
    // ============================================================

    /**
     * فرز العناصر حسب الموضع العمودي
     * Sort nodes by vertical position
     */
    fun sortByVerticalPosition(nodes: List<AccessibilityNodeInfo>): List<AccessibilityNodeInfo> {
        return nodes.sortedBy { node ->
            val rect = Rect()
            node.getBoundsInScreen(rect)
            rect.top
        }
    }

    /**
     * البحث عن عنصر في النصف العلوي من الشاشة
     * Find element in upper half of screen
     */
    fun findInUpperScreen(
        rootNode: AccessibilityNodeInfo,
        maxY: Int = 600,
        filter: (AccessibilityNodeInfo) -> Boolean
    ): AccessibilityNodeInfo? {
        return findInScreenRegionRecursive(rootNode, 0, 0, Int.MAX_VALUE, maxY, filter)
    }

    /**
     * البحث عن عنصر في منطقة محددة من الشاشة
     * Find element in specific screen region
     */
    private fun findInScreenRegionRecursive(
        node: AccessibilityNodeInfo,
        minX: Int,
        minY: Int,
        maxX: Int,
        maxY: Int,
        filter: (AccessibilityNodeInfo) -> Boolean
    ): AccessibilityNodeInfo? {
        val rect = Rect()
        node.getBoundsInScreen(rect)

        if (rect.left >= minX && rect.top >= minY &&
            rect.right <= maxX && rect.bottom <= maxY) {
            if (filter(node)) {
                return AccessibilityNodeInfo.obtain(node)
            }
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = findInScreenRegionRecursive(child, minX, minY, maxX, maxY, filter)
            child.recycle()
            if (found != null) return found
        }

        return null
    }

    /**
     * البحث عن أيقونات صغيرة (مثل زر الإغلاق/الحذف)
     * Find small icons (like close/delete buttons)
     */
    fun findSmallClickableIcons(
        rootNode: AccessibilityNodeInfo,
        minSize: Int = 20,
        maxSize: Int = 100
    ): List<AccessibilityNodeInfo> {
        val results = mutableListOf<AccessibilityNodeInfo>()
        findSmallIconsRecursive(rootNode, minSize, maxSize, results)
        return results
    }

    private fun findSmallIconsRecursive(
        node: AccessibilityNodeInfo,
        minSize: Int,
        maxSize: Int,
        results: MutableList<AccessibilityNodeInfo>
    ) {
        val className = node.className?.toString()?.lowercase() ?: ""
        val bounds = Rect()
        node.getBoundsInScreen(bounds)

        val isSmallClickable = node.isClickable &&
            bounds.width() in minSize..maxSize &&
            bounds.height() in minSize..maxSize

        val isImageLike = className.contains("image") ||
            className.contains("icon") ||
            className.contains("button") ||
            className.contains("view")

        if (isSmallClickable && isImageLike) {
            results.add(AccessibilityNodeInfo.obtain(node))
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            findSmallIconsRecursive(child, minSize, maxSize, results)
            child.recycle()
        }
    }

    // ============================================================
    // TEXT EXTRACTION
    // ============================================================

    /**
     * الحصول على كل النصوص المرئية
     * Get all visible text from UI tree
     */
    fun getAllText(rootNode: AccessibilityNodeInfo): List<String> {
        val texts = mutableListOf<String>()
        collectAllText(rootNode, texts)
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
     * التحقق من وجود أي نص من القائمة على الشاشة
     * Check if any text from list is visible on screen
     */
    fun hasAnyText(rootNode: AccessibilityNodeInfo, textsToFind: List<String>): Boolean {
        val allText = getAllText(rootNode)
        return textsToFind.any { search ->
            allText.any { text ->
                text.contains(search, ignoreCase = true)
            }
        }
    }

    /**
     * التحقق من وجود كل النصوص من القائمة على الشاشة
     * Check if all texts from list are visible on screen
     */
    fun hasAllTexts(rootNode: AccessibilityNodeInfo, textsToFind: List<String>): Boolean {
        val allText = getAllText(rootNode)
        return textsToFind.all { search ->
            allText.any { text ->
                text.contains(search, ignoreCase = true)
            }
        }
    }

    // ============================================================
    // DEBUG/LOGGING
    // ============================================================

    /**
     * تسجيل كل العناصر للتشخيص
     * Log all nodes for debugging
     */
    fun logAllNodes(rootNode: AccessibilityNodeInfo, tag: String = TAG) {
        logNodesRecursive(rootNode, 0, tag)
    }

    private fun logNodesRecursive(node: AccessibilityNodeInfo, depth: Int, tag: String) {
        if (depth > 15) return

        val text = node.text?.toString() ?: ""
        val desc = node.contentDescription?.toString() ?: ""
        val className = node.className?.toString()?.substringAfterLast(".") ?: ""
        val viewId = node.viewIdResourceName ?: ""
        val bounds = Rect()
        node.getBoundsInScreen(bounds)

        if (text.isNotBlank() || desc.isNotBlank() || node.isClickable) {
            val prefix = "  ".repeat(depth)
            AppLogger.d(
                "$prefix[$className] text='${text.take(30)}' desc='${desc.take(30)}' " +
                "click=${node.isClickable} id='${viewId.takeLast(30)}' bounds=${bounds.toShortString()}",
                tag
            )
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            logNodesRecursive(child, depth + 1, tag)
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
    private fun String.normalizeArabic(): String {
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
}
