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
    // COOLDOWN CONFIGURATION
    // ============================================================

    /**
     * Cooldown periods to prevent rapid clicking that can crash apps
     * or cause unexpected behavior
     */
    object Cooldowns {
        /**
         * InDriver cooldowns - this app crashes with rapid interactions
         */
        const val INDRIVER_CLICK_COOLDOWN_MS = 1500L     // General click cooldown
        const val INDRIVER_DONE_BUTTON_COOLDOWN_MS = 800L // Between تم clicks

        /**
         * DiDi cooldowns - needs stability between actions
         */
        const val DIDI_ADDRESS_CLICK_COOLDOWN_MS = 2000L // Address selection cooldown

        /**
         * General cooldowns
         */
        const val DEFAULT_CLICK_COOLDOWN_MS = 500L       // Default between clicks
    }

    // ============================================================
    // PRICE VALIDATION
    // ============================================================

    object PriceValidation {
        const val UBER_MIN_PRICE = 50.0
        const val GENERAL_MIN_PRICE = 10.0
        const val GENERAL_MAX_PRICE = 5000.0

        /**
         * Minimum number of prices required to consider price capture complete
         * Some apps show multiple vehicle options (Uber, Careem), others may show just one
         */
        const val MIN_PRICES_FOR_COMPLETION = 1  // Changed from 2 to 1 for flexibility

        /**
         * Preferred minimum prices (for apps that typically show multiple options)
         */
        const val PREFERRED_MIN_PRICES = 2

        /**
         * App-specific minimum prices
         */
        fun getMinPricesForApp(packageName: String): Int = when {
            packageName.contains("uber") -> 2      // Uber shows multiple vehicle types
            packageName.contains("careem") -> 2    // Careem shows multiple options
            packageName.contains("didi") -> 1      // DiDi may show just one
            packageName.contains("inDriver") -> 1  // InDriver shows suggested fare
            packageName.contains("bolt") -> 2      // Bolt shows multiple
            else -> MIN_PRICES_FOR_COMPLETION
        }
    }

    // ============================================================
    // MAP SWIPE CONFIGURATION
    // ============================================================

    object MapConfig {
        /**
         * InDriver map swipe settings
         * When InDriver shows "Choose on map", the map opens at user's GPS location.
         * We swipe to move the pin toward the target coordinates.
         */
        const val INDRIVER_MAP_SWIPE_COOLDOWN_MS = 800L
        const val INDRIVER_MAX_MAP_SWIPES = 6
        const val INDRIVER_SWIPE_DURATION_MS = 500L
        const val INDRIVER_SWIPE_SETTLE_MS = 600L

        /**
         * Swipe distance as fraction of screen width (0.4 = 40%)
         */
        const val MAP_SWIPE_DISTANCE_FRACTION = 0.4f

        /**
         * Map center position as fraction of screen height (0.4 = 40% from top)
         */
        const val MAP_CENTER_Y_FRACTION = 0.4f
    }

    // ============================================================
    // SCREEN POSITION CONFIGURATION
    // ============================================================

    /**
     * Screen positions as fractions of screen dimensions
     * These replace hardcoded pixel values for better device compatibility
     */
    object ScreenPositions {
        /**
         * Button positions (as fraction of screen height from top)
         */
        const val BOTTOM_BUTTON_Y_FRACTION = 0.85f      // Common position for bottom buttons
        const val BOTTOM_BUTTON_Y_ALT_1 = 0.88f         // Alternative position
        const val BOTTOM_BUTTON_Y_ALT_2 = 0.90f         // Alternative position
        const val BOTTOM_BUTTON_Y_ALT_3 = 0.92f         // Alternative position
        const val BOTTOM_BUTTON_Y_ALT_4 = 0.95f         // Very bottom position

        /**
         * Scroll gesture positions
         */
        const val SCROLL_START_Y_FRACTION = 0.7f        // Scroll start position (70% from top)
        const val SCROLL_END_Y_FRACTION = 0.3f          // Scroll end position (30% from top)

        /**
         * DiDi specific positions
         */
        const val DIDI_PICKUP_FIELD_Y = 350f            // Pickup field approximate Y position (pixels)
        const val DIDI_PICKUP_FIELD_Y_FRACTION = 0.18f  // As fraction of screen height
        const val DIDI_SEARCH_Y_FRACTION_1 = 0.30f      // DiDi search bar position 1 (30% from top)
        const val DIDI_SEARCH_Y_FRACTION_2 = 0.25f      // DiDi search bar position 2 (25% from top)
        const val DIDI_SEARCH_Y_FRACTION_3 = 0.40f      // DiDi search bar position 3 (40% from top)
        const val DIDI_SEARCH_Y_FRACTION_4 = 0.35f      // DiDi search bar position 4 (35% from top)
        const val DIDI_SEARCH_Y_FRACTION_5 = 0.45f      // DiDi search bar position 5 (45% from top)

        /**
         * Careem specific positions (service grid)
         */
        const val CAREEM_BOTTOM_NAV_X = 0.125f          // Bottom nav first item X
        const val CAREEM_BOTTOM_NAV_Y = 0.92f           // Bottom nav Y
        const val CAREEM_GRID_TOP_LEFT_X = 0.20f        // Service grid top-left X
        const val CAREEM_GRID_TOP_LEFT_Y = 0.35f        // Service grid top-left Y
        const val CAREEM_GRID_TOP_CENTER_X = 0.50f      // Service grid top-center X
        const val CAREEM_GRID_TOP_CENTER_Y = 0.35f      // Service grid top-center Y
        const val CAREEM_ROW_FIRST_X = 0.15f            // Service row first item X
        const val CAREEM_ROW_FIRST_Y = 0.25f            // Service row first item Y
        const val CAREEM_GRID_MIDDLE_LEFT_X = 0.20f     // Service grid middle-left X
        const val CAREEM_GRID_MIDDLE_LEFT_Y = 0.45f     // Service grid middle-left Y
        const val CAREEM_GRID_TOP_AREA_X = 0.25f        // Service grid top area X
        const val CAREEM_GRID_TOP_AREA_Y = 0.30f        // Service grid top area Y

        /**
         * Pin/marker positions
         */
        const val PIN_Y_FRACTION = 0.85f                // Pin location for map screens

        /**
         * Search/input field positions
         */
        const val SEARCH_FIELD_Y_FRACTION = 0.15f       // Top search field
        const val DESTINATION_FIELD_Y_FRACTION = 0.25f  // Destination input field
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
