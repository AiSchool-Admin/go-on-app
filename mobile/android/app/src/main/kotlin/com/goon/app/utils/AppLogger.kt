package com.goon.app.utils

import android.util.Log
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * GO-ON Application Logger (Kotlin)
 *
 * Centralized logging utility for Android/Kotlin code.
 * Provides structured logging with different severity levels.
 *
 * Features:
 * - Consistent log format across the app
 * - Emoji prefixes for easy visual identification
 * - Timestamp included in debug builds
 * - Tag-based filtering
 * - Specialized loggers for different subsystems
 *
 * Usage:
 * ```kotlin
 * AppLogger.d("PriceReader", "Debug message")
 * AppLogger.i("Automation", "Info message")
 * AppLogger.w("Service", "Warning message")
 * AppLogger.e("Error", "Error message", exception)
 * ```
 */
object AppLogger {

    private const val APP_TAG = "GO-ON"

    // Enable/disable verbose logging
    var isVerbose = true

    // Log level thresholds
    enum class Level(val priority: Int) {
        VERBOSE(Log.VERBOSE),
        DEBUG(Log.DEBUG),
        INFO(Log.INFO),
        WARN(Log.WARN),
        ERROR(Log.ERROR)
    }

    // Minimum log level (logs below this level are ignored)
    var minLevel: Level = Level.DEBUG

    // Emojis for visual identification
    private object Emoji {
        const val DEBUG = "🔍"
        const val INFO = "ℹ️"
        const val WARN = "⚠️"
        const val ERROR = "❌"
        const val PRICE = "💰"
        const val AUTOMATION = "🤖"
        const val CLICK = "👆"
        const val SUCCESS = "✅"
        const val FAILED = "❌"
        const val TIMING = "⏱"
        const val DEVICE = "📱"
    }

    // ============================================================
    // CORE LOGGING METHODS
    // ============================================================

    /**
     * Verbose logging (most detailed)
     */
    fun v(tag: String, message: String) {
        if (minLevel.priority <= Level.VERBOSE.priority) {
            Log.v(formatTag(tag), formatMessage(message))
        }
    }

    /**
     * Debug logging
     */
    fun d(tag: String, message: String) {
        if (minLevel.priority <= Level.DEBUG.priority) {
            Log.d(formatTag(tag), formatMessage("${Emoji.DEBUG} $message"))
        }
    }

    /**
     * Info logging
     */
    fun i(tag: String, message: String) {
        if (minLevel.priority <= Level.INFO.priority) {
            Log.i(formatTag(tag), formatMessage("${Emoji.INFO} $message"))
        }
    }

    /**
     * Warning logging
     */
    fun w(tag: String, message: String) {
        if (minLevel.priority <= Level.WARN.priority) {
            Log.w(formatTag(tag), formatMessage("${Emoji.WARN} $message"))
        }
    }

    /**
     * Error logging
     */
    fun e(tag: String, message: String, error: Throwable? = null) {
        if (minLevel.priority <= Level.ERROR.priority) {
            if (error != null) {
                Log.e(formatTag(tag), formatMessage("${Emoji.ERROR} $message"), error)
            } else {
                Log.e(formatTag(tag), formatMessage("${Emoji.ERROR} $message"))
            }
        }
    }

    // ============================================================
    // SPECIALIZED LOGGERS
    // ============================================================

    /**
     * Log price detection events
     */
    fun price(tag: String, message: String, price: Double? = null, app: String? = null) {
        val details = mutableListOf<String>()
        price?.let { details.add("price=$it") }
        app?.let { details.add("app=$it") }
        val suffix = if (details.isNotEmpty()) " [${details.joinToString(", ")}]" else ""
        i(tag, "${Emoji.PRICE} $message$suffix")
    }

    /**
     * Log automation steps
     */
    fun automation(tag: String, message: String, state: String? = null, step: Int? = null) {
        val details = mutableListOf<String>()
        state?.let { details.add("state=$it") }
        step?.let { details.add("step=$it") }
        val suffix = if (details.isNotEmpty()) " [${details.joinToString(", ")}]" else ""
        d(tag, "${Emoji.AUTOMATION} $message$suffix")
    }

    /**
     * Log click/interaction events
     */
    fun click(tag: String, message: String, success: Boolean = true) {
        val emoji = if (success) Emoji.SUCCESS else Emoji.FAILED
        d(tag, "${Emoji.CLICK} $message $emoji")
    }

    /**
     * Log timing/performance events
     */
    fun timing(tag: String, message: String, durationMs: Long? = null) {
        val suffix = durationMs?.let { " (${it}ms)" } ?: ""
        d(tag, "${Emoji.TIMING} $message$suffix")
    }

    /**
     * Log device calibration events
     */
    fun device(tag: String, message: String) {
        i(tag, "${Emoji.DEVICE} $message")
    }

    /**
     * Log state transitions
     */
    fun stateChange(tag: String, from: String, to: String) {
        d(tag, "State: $from → $to")
    }

    /**
     * Log section separators (for grouping related logs)
     */
    fun section(tag: String, title: String) {
        d(tag, "════════════════════════════════════════")
        d(tag, title)
        d(tag, "════════════════════════════════════════")
    }

    /**
     * Log success message
     */
    fun success(tag: String, message: String) {
        i(tag, "${Emoji.SUCCESS} $message")
    }

    /**
     * Log failure message
     */
    fun failed(tag: String, message: String, error: Throwable? = null) {
        e(tag, "${Emoji.FAILED} $message", error)
    }

    // ============================================================
    // HELPER METHODS
    // ============================================================

    private fun formatTag(tag: String): String {
        return "$APP_TAG-$tag"
    }

    private fun formatMessage(message: String): String {
        return if (isVerbose) {
            val timestamp = SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date())
            "[$timestamp] $message"
        } else {
            message
        }
    }

    /**
     * Log a block of related operations
     */
    inline fun <T> logBlock(tag: String, name: String, block: () -> T): T {
        val startTime = System.currentTimeMillis()
        d(tag, "▶ Starting: $name")
        return try {
            val result = block()
            val duration = System.currentTimeMillis() - startTime
            success(tag, "◀ Completed: $name (${duration}ms)")
            result
        } catch (e: Exception) {
            val duration = System.currentTimeMillis() - startTime
            failed(tag, "◀ Failed: $name (${duration}ms)", e)
            throw e
        }
    }

    /**
     * Log a timed operation (returns duration in ms)
     */
    inline fun <T> timed(tag: String, name: String, block: () -> T): Pair<T, Long> {
        val startTime = System.currentTimeMillis()
        val result = block()
        val duration = System.currentTimeMillis() - startTime
        timing(tag, "$name completed", duration)
        return Pair(result, duration)
    }

    // ============================================================
    // SUMMARY LOGGING - للتصفية السهلة
    // Use: adb logcat | findstr "SUMMARY"
    // ============================================================

    /**
     * ملخص - نجاح
     * Summary log for SUCCESS - easy to filter
     */
    fun summaryOk(action: String, details: String = "") {
        val msg = if (details.isNotEmpty()) "$action | $details" else action
        Log.i("GO-ON-SUMMARY", "✅ $msg")
    }

    /**
     * ملخص - فشل
     * Summary log for FAILURE - easy to filter
     */
    fun summaryFail(action: String, reason: String = "") {
        val msg = if (reason.isNotEmpty()) "$action | $reason" else action
        Log.e("GO-ON-SUMMARY", "❌ $msg")
    }

    /**
     * ملخص - تحذير
     * Summary log for WARNING - easy to filter
     */
    fun summaryWarn(action: String, details: String = "") {
        val msg = if (details.isNotEmpty()) "$action | $details" else action
        Log.w("GO-ON-SUMMARY", "⚠️ $msg")
    }

    /**
     * ملخص - خطوة
     * Summary log for STEP - easy to filter
     */
    fun summaryStep(step: String) {
        Log.i("GO-ON-SUMMARY", "▶ $step")
    }

    /**
     * ملخص - سعر
     * Summary log for PRICE - easy to filter
     */
    fun summaryPrice(app: String, price: Double) {
        Log.i("GO-ON-SUMMARY", "💰 $app = $price EGP")
    }
}

// ============================================================
// EXTENSION FUNCTIONS
// ============================================================

/**
 * Extension to log from any class using its simple name as tag
 */
fun Any.logD(message: String) = AppLogger.d(this::class.java.simpleName, message)
fun Any.logI(message: String) = AppLogger.i(this::class.java.simpleName, message)
fun Any.logW(message: String) = AppLogger.w(this::class.java.simpleName, message)
fun Any.logE(message: String, error: Throwable? = null) = AppLogger.e(this::class.java.simpleName, message, error)
