package com.goon.app.services.config

import android.util.Log

/**
 * Device-Adaptive Timing Configuration
 *
 * All timing values are centralized here for easy tuning across different devices.
 * The calibrate() function adjusts these based on device performance.
 *
 * Usage:
 *   TimingConfig.calibrate()  // Call once when service starts
 *   delay(TimingConfig.clickDelay)  // Use computed values
 */
object TimingConfig {

    private const val TAG = "GO-ON-TimingConfig"

    // ============================================================
    // CALIBRATION STATE
    // ============================================================

    /**
     * Performance multiplier (1.0 = normal, <1.0 = fast device, >1.0 = slow device)
     */
    private var performanceMultiplier = 1.0

    /**
     * Whether calibration has been performed
     */
    private var isCalibrated = false

    // ============================================================
    // BASE TIMING VALUES (in milliseconds)
    // These are the reference values for a "normal" device
    // ============================================================

    private const val BASE_CLICK_DELAY = 300L
    private const val BASE_ANIMATION_WAIT = 500L
    private const val BASE_SCAN_INTERVAL = 500L
    private const val BASE_TEXT_INPUT_DELAY = 100L
    private const val BASE_KEYBOARD_WAIT = 300L
    private const val BASE_SUGGESTION_WAIT = 800L
    private const val BASE_SCROLL_DELAY = 200L
    private const val BASE_FOCUS_DELAY = 150L

    // ============================================================
    // COMPUTED TIMING VALUES
    // These are adjusted by the performance multiplier
    // ============================================================

    /**
     * Delay after clicking a UI element
     */
    val clickDelay: Long
        get() = (BASE_CLICK_DELAY * performanceMultiplier).toLong()

    /**
     * Wait time for animations to complete
     */
    val animationWait: Long
        get() = (BASE_ANIMATION_WAIT * performanceMultiplier).toLong()

    /**
     * Interval between scans when actively monitoring
     */
    val scanInterval: Long
        get() = (BASE_SCAN_INTERVAL * performanceMultiplier).toLong()

    /**
     * Delay between characters when entering text
     */
    val textInputDelay: Long
        get() = (BASE_TEXT_INPUT_DELAY * performanceMultiplier).toLong()

    /**
     * Wait time for keyboard to appear
     */
    val keyboardWait: Long
        get() = (BASE_KEYBOARD_WAIT * performanceMultiplier).toLong()

    /**
     * Wait time for suggestions to load after typing
     */
    val suggestionWait: Long
        get() = (BASE_SUGGESTION_WAIT * performanceMultiplier).toLong()

    /**
     * Delay after scrolling
     */
    val scrollDelay: Long
        get() = (BASE_SCROLL_DELAY * performanceMultiplier).toLong()

    /**
     * Delay after focusing an element
     */
    val focusDelay: Long
        get() = (BASE_FOCUS_DELAY * performanceMultiplier).toLong()

    // ============================================================
    // FIXED TIMEOUT VALUES
    // These are less affected by device speed
    // ============================================================

    /**
     * App-specific timeout values
     */
    object AppTimeouts {
        const val UBER_MIN_WAIT_MS = 3000L
        const val DIDI_TIMEOUT_MS = 20000L
        const val CAREEM_TIMEOUT_MS = 25000L
        const val CAREEM_LOADER_EXTENSION_MS = 10000L
        const val INDRIVER_MIN_WAIT_AFTER_DONE_MS = 1000L
        const val DEFAULT_TIMEOUT_MS = 12000L
        const val BOLT_TIMEOUT_MS = 15000L
    }

    // ============================================================
    // RETRY CONFIGURATION
    // ============================================================

    object RetryConfig {
        const val MAX_RETRIES = 10
        const val DIDI_MAX_DESTINATION_ATTEMPTS = 10
        const val DIDI_MAX_SUGGESTION_RETRIES = 3
        const val INDRIVER_MAX_DONE_CLICKS = 3
    }

    // ============================================================
    // PRICE VALIDATION
    // ============================================================

    object PriceValidation {
        const val UBER_MIN_PRICE = 50.0
        const val GENERAL_MIN_PRICE = 10.0
        const val GENERAL_MAX_PRICE = 5000.0
    }

    // ============================================================
    // CALIBRATION METHODS
    // ============================================================

    /**
     * Calibrate timing based on device performance.
     * Call this once when the service starts.
     *
     * This runs a simple benchmark to determine if the device is
     * fast, normal, or slow, and adjusts timing values accordingly.
     */
    fun calibrate() {
        if (isCalibrated) {
            Log.d(TAG, "Already calibrated, skipping...")
            return
        }

        try {
            val startTime = System.nanoTime()

            // Simple benchmark: string operations
            var dummy = ""
            repeat(10000) {
                dummy += "x"
                dummy = dummy.takeLast(100)
            }

            val elapsedNs = System.nanoTime() - startTime
            val elapsedMs = elapsedNs / 1_000_000

            // Determine performance tier
            performanceMultiplier = when {
                elapsedMs < 20 -> 0.8    // Fast device - reduce delays
                elapsedMs < 50 -> 1.0    // Normal device
                elapsedMs < 100 -> 1.3   // Slower device
                else -> 1.5              // Very slow device
            }

            isCalibrated = true

            Log.i(TAG, "========================================")
            Log.i(TAG, "Device Calibration Complete")
            Log.i(TAG, "========================================")
            Log.i(TAG, "Benchmark: ${elapsedMs}ms")
            Log.i(TAG, "Performance Tier: ${getPerformanceTier()}")
            Log.i(TAG, "Multiplier: $performanceMultiplier")
            Log.i(TAG, "----------------------------------------")
            Log.i(TAG, "Computed Timings:")
            Log.i(TAG, "  Click delay: ${clickDelay}ms")
            Log.i(TAG, "  Animation wait: ${animationWait}ms")
            Log.i(TAG, "  Scan interval: ${scanInterval}ms")
            Log.i(TAG, "  Keyboard wait: ${keyboardWait}ms")
            Log.i(TAG, "========================================")

        } catch (e: Exception) {
            Log.e(TAG, "Calibration failed, using defaults: ${e.message}")
            performanceMultiplier = 1.0
            isCalibrated = true
        }
    }

    /**
     * Force recalibration (useful for testing or after device state changes)
     */
    fun recalibrate() {
        isCalibrated = false
        calibrate()
    }

    /**
     * Get the current performance tier as a human-readable string
     */
    fun getPerformanceTier(): String = when {
        performanceMultiplier < 0.9 -> "FAST"
        performanceMultiplier < 1.1 -> "NORMAL"
        performanceMultiplier < 1.4 -> "SLOW"
        else -> "VERY_SLOW"
    }

    /**
     * Get the current performance multiplier
     */
    fun getMultiplier(): Double = performanceMultiplier

    /**
     * Check if device has been calibrated
     */
    fun isDeviceCalibrated(): Boolean = isCalibrated

    // ============================================================
    // COMPUTED DELAY HELPERS
    // ============================================================

    /**
     * Get a delay value scaled by a factor
     * Useful for operations that need longer/shorter delays
     */
    fun scaledDelay(baseMs: Long, factor: Double = 1.0): Long {
        return (baseMs * performanceMultiplier * factor).toLong()
    }

    /**
     * Get a short delay (half of normal click delay)
     */
    val shortDelay: Long
        get() = clickDelay / 2

    /**
     * Get a long delay (double the normal animation wait)
     */
    val longDelay: Long
        get() = animationWait * 2

    /**
     * Get timeout for a specific app
     */
    fun getAppTimeout(packageName: String): Long = when (packageName) {
        AppConstants.Packages.UBER -> AppTimeouts.UBER_MIN_WAIT_MS
        AppConstants.Packages.CAREEM -> AppTimeouts.CAREEM_TIMEOUT_MS
        AppConstants.Packages.DIDI -> AppTimeouts.DIDI_TIMEOUT_MS
        AppConstants.Packages.BOLT -> AppTimeouts.BOLT_TIMEOUT_MS
        AppConstants.Packages.INDRIVER -> AppTimeouts.DEFAULT_TIMEOUT_MS
        else -> AppTimeouts.DEFAULT_TIMEOUT_MS
    }

    // ============================================================
    // CONVENIENCE CONSTANTS (for backward compatibility)
    // These expose nested values at the top level
    // ============================================================

    // Retry config
    val MAX_RETRIES: Int get() = RetryConfig.MAX_RETRIES
    val DIDI_MAX_DESTINATION_ATTEMPTS: Int get() = RetryConfig.DIDI_MAX_DESTINATION_ATTEMPTS
    val DIDI_MAX_SUGGESTION_RETRIES: Int get() = RetryConfig.DIDI_MAX_SUGGESTION_RETRIES
    val INDRIVER_MAX_DONE_CLICKS: Int get() = RetryConfig.INDRIVER_MAX_DONE_CLICKS

    // App timeouts
    val UBER_MIN_WAIT_MS: Long get() = AppTimeouts.UBER_MIN_WAIT_MS
    val DIDI_TIMEOUT_MS: Long get() = AppTimeouts.DIDI_TIMEOUT_MS
    val CAREEM_TIMEOUT_MS: Long get() = AppTimeouts.CAREEM_TIMEOUT_MS
    val CAREEM_LOADER_EXTENSION_MS: Long get() = AppTimeouts.CAREEM_LOADER_EXTENSION_MS
    val INDRIVER_MIN_WAIT_AFTER_DONE_MS: Long get() = AppTimeouts.INDRIVER_MIN_WAIT_AFTER_DONE_MS
    val DEFAULT_TIMEOUT_MS: Long get() = AppTimeouts.DEFAULT_TIMEOUT_MS
    val BOLT_TIMEOUT_MS: Long get() = AppTimeouts.BOLT_TIMEOUT_MS

    // Automation timeout
    val automationTimeout: Long get() = DEFAULT_TIMEOUT_MS
}
