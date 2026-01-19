package com.goon.app.services.fallback

import android.accessibilityservice.AccessibilityService
import android.graphics.Rect
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import com.goon.app.services.config.TimingConfig
import com.goon.app.services.utils.GestureHelper
import com.goon.app.services.utils.NodeFinder
import com.goon.app.utils.AppLogger

/**
 * استراتيجية Fallback لإدخال النص
 * Text entry fallback strategy
 *
 * Handles text entry failures with multiple approaches:
 * 1. ACTION_SET_TEXT (standard)
 * 2. Clear then set text
 * 3. Focus, clear, then set text
 * 4. Click field, wait, then set text
 * 5. Character by character input (for resistant fields)
 * 6. Clipboard paste approach
 * 7. Try different EditText fields
 */
class TextEntryFallbackStrategy(
    service: AccessibilityService
) : FallbackStrategy(service, "TextEntryFallback") {

    override val type: FallbackType = FallbackType.TEXT_ENTRY
    override val priority: Int = 20

    override fun canHandle(context: FallbackContext): Boolean {
        return context.targetText.isNotEmpty() &&
               (context.currentState.contains("ENTERING", ignoreCase = true) ||
                context.currentState.contains("TEXT", ignoreCase = true))
    }

    override fun execute(rootNode: AccessibilityNodeInfo, context: FallbackContext): FallbackResult {
        val textToEnter = context.targetText
        AppLogger.d("TextEntryFallback: Entering text '$textToEnter'", tag)

        // Find all EditText fields
        val editTexts = NodeFinder.findAllEditTexts(rootNode)
        if (editTexts.isEmpty()) {
            return failure("NoEditText", "No EditText fields found")
        }

        AppLogger.d("Found ${editTexts.size} EditText fields", tag)

        // Sort by Y position (top to bottom)
        val sortedEditTexts = editTexts.sortedBy { node ->
            val rect = Rect()
            node.getBoundsInScreen(rect)
            rect.top
        }

        var attempts = 0
        val strategies = getTextEntryStrategies(textToEnter)

        // Try each EditText with all strategies
        for ((index, editText) in sortedEditTexts.withIndex()) {
            AppLogger.d("Trying EditText #$index", tag)

            for (strategy in strategies) {
                attempts++
                logAttempt(strategy.name, false, "Trying on EditText #$index")

                val success = strategy.action(editText)
                if (success) {
                    // Verify text was entered
                    Thread.sleep(100)
                    val currentText = editText.text?.toString() ?: ""
                    if (currentText.contains(textToEnter.take(10))) {
                        logAttempt(strategy.name, true, "Text verified")
                        sortedEditTexts.forEach { try { it.recycle() } catch (e: Exception) {} }
                        return success(strategy.name, "Text entered in EditText #$index", attempts)
                    }
                }

                Thread.sleep(50)
            }
        }

        // Fallback: Try focused input if exists
        val focusedInput = NodeFinder.findFocusedEditText(rootNode)
        if (focusedInput != null) {
            AppLogger.d("Trying focused EditText", tag)
            for (strategy in strategies) {
                attempts++
                if (strategy.action(focusedInput)) {
                    Thread.sleep(100)
                    val currentText = focusedInput.text?.toString() ?: ""
                    if (currentText.contains(textToEnter.take(10))) {
                        focusedInput.recycle()
                        sortedEditTexts.forEach { try { it.recycle() } catch (e: Exception) {} }
                        return success(strategy.name, "Text entered in focused field", attempts)
                    }
                }
            }
            focusedInput.recycle()
        }

        sortedEditTexts.forEach { try { it.recycle() } catch (e: Exception) {} }
        return failure("AllStrategiesFailed", "Tried $attempts strategies", attempts)
    }

    override fun getAttemptDescriptions(): List<String> {
        return listOf(
            "ACTION_SET_TEXT - Standard text setting",
            "CLEAR_THEN_SET - Clear field before setting",
            "FOCUS_CLEAR_SET - Focus, clear, then set",
            "CLICK_WAIT_SET - Click field, wait, then set",
            "CHUNKED_INPUT - Enter text in chunks",
            "MULTIPLE_EDITTEXTS - Try different EditText fields"
        )
    }

    // ============================================================
    // TEXT ENTRY STRATEGIES
    // ============================================================

    private fun getTextEntryStrategies(text: String): List<TextEntryAttempt> {
        return listOf(
            TextEntryAttempt("ACTION_SET_TEXT") { node ->
                setTextDirect(node, text)
            },
            TextEntryAttempt("CLEAR_THEN_SET") { node ->
                clearAndSetText(node, text)
            },
            TextEntryAttempt("FOCUS_CLEAR_SET") { node ->
                focusClearAndSetText(node, text)
            },
            TextEntryAttempt("CLICK_WAIT_SET") { node ->
                clickWaitAndSetText(node, text)
            },
            TextEntryAttempt("CHUNKED_INPUT") { node ->
                chunkedTextEntry(node, text)
            }
        )
    }

    private fun setTextDirect(node: AccessibilityNodeInfo, text: String): Boolean {
        val arguments = Bundle()
        arguments.putCharSequence(
            AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
            text
        )
        return node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
    }

    private fun clearAndSetText(node: AccessibilityNodeInfo, text: String): Boolean {
        // Clear first
        val clearArgs = Bundle()
        clearArgs.putCharSequence(
            AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
            ""
        )
        node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, clearArgs)
        Thread.sleep(50)

        // Then set
        return setTextDirect(node, text)
    }

    private fun focusClearAndSetText(node: AccessibilityNodeInfo, text: String): Boolean {
        // Focus
        if (node.isFocusable) {
            node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
            Thread.sleep(100)
        }

        // Click to ensure keyboard appears
        if (node.isClickable) {
            node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            Thread.sleep(150)
        }

        return clearAndSetText(node, text)
    }

    private fun clickWaitAndSetText(node: AccessibilityNodeInfo, text: String): Boolean {
        // Click the field
        val bounds = Rect()
        node.getBoundsInScreen(bounds)

        if (bounds.width() > 0) {
            GestureHelper.tapAtPosition(
                service,
                bounds.centerX().toFloat(),
                bounds.centerY().toFloat(),
                100
            )
            Thread.sleep(300) // Wait for keyboard
        }

        return setTextDirect(node, text)
    }

    private fun chunkedTextEntry(node: AccessibilityNodeInfo, text: String): Boolean {
        // Focus first
        if (node.isFocusable) {
            node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
            Thread.sleep(100)
        }

        // Clear
        val clearArgs = Bundle()
        clearArgs.putCharSequence(
            AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
            ""
        )
        node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, clearArgs)
        Thread.sleep(50)

        // Enter in chunks (helps with some apps that have input validation)
        val chunkSize = 10
        var currentText = ""

        for (i in 0 until text.length step chunkSize) {
            val chunk = text.substring(i, minOf(i + chunkSize, text.length))
            currentText += chunk

            val args = Bundle()
            args.putCharSequence(
                AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                currentText
            )
            node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
            Thread.sleep(30)
        }

        // Verify
        Thread.sleep(100)
        val finalText = node.text?.toString() ?: ""
        return finalText.contains(text.take(10))
    }

    /**
     * محاولة إدخال نص واحدة
     * Single text entry attempt
     */
    private data class TextEntryAttempt(
        val name: String,
        val action: (AccessibilityNodeInfo) -> Boolean
    )
}

/**
 * استراتيجية Fallback لإدخال الإحداثيات
 * Coordinates entry fallback strategy
 *
 * Specialized for entering coordinates (lat, lng) which may need
 * special handling in some apps
 */
class CoordinatesEntryFallbackStrategy(
    service: AccessibilityService
) : FallbackStrategy(service, "CoordinatesEntryFallback") {

    override val type: FallbackType = FallbackType.TEXT_ENTRY
    override val priority: Int = 25

    override fun canHandle(context: FallbackContext): Boolean {
        // Check if metadata contains coordinates
        return context.metadata.containsKey("lat") && context.metadata.containsKey("lng")
    }

    override fun execute(rootNode: AccessibilityNodeInfo, context: FallbackContext): FallbackResult {
        val lat = context.metadata["lat"] as? Double ?: return failure("NoLat", "Latitude not provided")
        val lng = context.metadata["lng"] as? Double ?: return failure("NoLng", "Longitude not provided")

        AppLogger.d("CoordinatesEntryFallback: Entering coordinates ($lat, $lng)", tag)

        // Different coordinate formats to try
        val formats = listOf(
            "$lat, $lng",                          // Standard: "30.123, 31.456"
            "$lat,$lng",                           // No space: "30.123,31.456"
            "${lat.toString().take(8)}, ${lng.toString().take(8)}",  // Truncated
            "%.6f, %.6f".format(lat, lng),         // Fixed precision
            "%.4f, %.4f".format(lat, lng)          // Lower precision (some apps don't like too many decimals)
        )

        val editTexts = NodeFinder.findAllEditTexts(rootNode)
        if (editTexts.isEmpty()) {
            return failure("NoEditText", "No EditText fields found")
        }

        var attempts = 0
        for (format in formats) {
            attempts++
            AppLogger.d("Trying format: $format", tag)

            for (editText in editTexts) {
                val success = enterCoordinates(editText, format)
                if (success) {
                    editTexts.forEach { try { it.recycle() } catch (e: Exception) {} }
                    return success("CoordFormat", "Entered: $format", attempts)
                }
            }
        }

        editTexts.forEach { try { it.recycle() } catch (e: Exception) {} }
        return failure("AllFormatsFailed", "Tried $attempts formats", attempts)
    }

    private fun enterCoordinates(node: AccessibilityNodeInfo, coords: String): Boolean {
        // Focus
        if (node.isFocusable) {
            node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
            Thread.sleep(100)
        }

        // Clear
        val clearArgs = Bundle()
        clearArgs.putCharSequence(
            AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
            ""
        )
        node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, clearArgs)
        Thread.sleep(50)

        // Set coordinates
        val args = Bundle()
        args.putCharSequence(
            AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
            coords
        )
        val result = node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)

        // Verify
        Thread.sleep(100)
        val currentText = node.text?.toString() ?: ""
        return result && currentText.contains(coords.take(5))
    }

    override fun getAttemptDescriptions(): List<String> {
        return listOf(
            "Standard format: lat, lng",
            "No space format: lat,lng",
            "Truncated precision",
            "Fixed 6 decimal precision",
            "Lower 4 decimal precision"
        )
    }
}
