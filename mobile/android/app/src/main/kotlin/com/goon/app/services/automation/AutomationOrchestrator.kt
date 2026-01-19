package com.goon.app.services.automation

import android.accessibilityservice.AccessibilityService
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityNodeInfo
import com.goon.app.services.config.AppConstants
import com.goon.app.services.config.TimingConfig
import com.goon.app.services.fallback.FallbackManager
import com.goon.app.services.fallback.FallbackType
import com.goon.app.services.fallback.FallbackContext
import com.goon.app.services.utils.NodeFinder
import com.goon.app.utils.AppLogger

/**
 * منسق الأتمتة - يدير عملية الحصول على الأسعار من تطبيقات النقل
 * Automation Orchestrator - manages price fetching from ride-hailing apps
 *
 * This class coordinates between:
 * - PriceReaderService (the accessibility service)
 * - App-specific automation classes
 * - FloatingOverlayService (for UI updates)
 *
 * Usage:
 * ```kotlin
 * val orchestrator = AutomationOrchestrator(service)
 * orchestrator.startAutomation(packageName, pickup, destination, ...)
 *
 * // In onAccessibilityEvent or periodic scan:
 * orchestrator.performStep(rootNode)
 * ```
 */
class AutomationOrchestrator(
    private val service: AccessibilityService
) {
    companion object {
        private const val TAG = "AutomationOrchestrator"
    }

    // ============================================================
    // STATE
    // ============================================================

    private var currentAutomation: BaseAutomation? = null
    private var currentState = AutomationState.IDLE
    private var currentPackage: String? = null
    private var retries = 0
    private var step = 0
    private var startTime = 0L
    private var isActive = false

    // Trip details
    private var pickupAddress = ""
    private var destinationAddress = ""
    private var pickupLat = 0.0
    private var pickupLng = 0.0
    private var destLat = 0.0
    private var destLng = 0.0

    // Callback for price updates
    var onPriceExtracted: ((String, ExtractedPrice) -> Unit)? = null
    var onAutomationFailed: ((String, String) -> Unit)? = null
    var onAutomationComplete: ((String) -> Unit)? = null
    var onStateChanged: ((AutomationState) -> Unit)? = null

    // Handler for scheduling
    private val handler = Handler(Looper.getMainLooper())
    private var scanRunnable: Runnable? = null

    // Fallback manager for handling failures (Phase 6)
    private val fallbackManager = FallbackManager(service)
    private var consecutiveFailures = 0
    private val maxConsecutiveFailures = 5

    // ============================================================
    // PUBLIC API
    // ============================================================

    /**
     * بدء الأتمتة لتطبيق معين
     * Start automation for a specific app
     */
    fun startAutomation(
        packageName: String,
        pickup: String,
        destination: String,
        pickupLatitude: Double,
        pickupLongitude: Double,
        destLatitude: Double,
        destLongitude: Double
    ) {
        AppLogger.automation(TAG, "Starting automation for $packageName", state = "START")

        // Get or create automation instance
        currentAutomation = AutomationFactory.getAutomation(service, packageName)
        if (currentAutomation == null) {
            AppLogger.e("No automation available for $packageName", null, null, TAG)
            onAutomationFailed?.invoke(packageName, "Unsupported app")
            return
        }

        // Store trip details
        this.pickupAddress = pickup
        this.destinationAddress = destination
        this.pickupLat = pickupLatitude
        this.pickupLng = pickupLongitude
        this.destLat = destLatitude
        this.destLng = destLongitude

        // Set trip details on automation
        AutomationFactory.setTripDetails(
            currentAutomation!!,
            pickup, destination,
            pickupLatitude, pickupLongitude,
            destLatitude, destLongitude
        )

        // Reset state
        AutomationFactory.resetAutomation(currentAutomation!!)
        currentPackage = packageName
        retries = 0
        step = 0
        startTime = System.currentTimeMillis()
        isActive = true

        // Determine initial state
        currentState = AutomationFactory.getInitialState(packageName)
        onStateChanged?.invoke(currentState)

        AppLogger.automation(TAG, "Initial state: $currentState", state = currentState.name)

        // Start automation loop
        startAutomationLoop()
    }

    /**
     * إيقاف الأتمتة
     * Stop automation
     */
    fun stopAutomation() {
        AppLogger.automation(TAG, "Stopping automation", state = "STOP")
        isActive = false
        currentState = AutomationState.IDLE
        scanRunnable?.let { handler.removeCallbacks(it) }
        scanRunnable = null
    }

    /**
     * هل الأتمتة نشطة؟
     * Is automation active?
     */
    fun isAutomationActive(): Boolean = isActive

    /**
     * الحصول على الحالة الحالية
     * Get current state
     */
    fun getCurrentState(): AutomationState = currentState

    /**
     * الحصول على التطبيق الحالي
     * Get current package
     */
    fun getCurrentPackage(): String? = currentPackage

    /**
     * تنفيذ خطوة الأتمتة يدوياً
     * Perform automation step manually
     */
    fun performStep(rootNode: AccessibilityNodeInfo): Boolean {
        if (!isActive || currentAutomation == null) return false

        val packageName = rootNode.packageName?.toString() ?: return false
        if (packageName != currentPackage) {
            AppLogger.d("Waiting for $currentPackage (current: $packageName)", TAG)
            return false
        }

        return executeStep(rootNode)
    }

    // ============================================================
    // PRIVATE - AUTOMATION LOOP
    // ============================================================

    private fun startAutomationLoop() {
        scanRunnable = object : Runnable {
            override fun run() {
                if (isActive && currentState != AutomationState.IDLE) {
                    val rootNode = service.rootInActiveWindow
                    if (rootNode != null) {
                        try {
                            executeStep(rootNode)
                        } finally {
                            rootNode.recycle()
                        }
                    }

                    // Continue if not done
                    if (currentState != AutomationState.PRICE_CAPTURED &&
                        currentState != AutomationState.FAILED &&
                        currentState != AutomationState.IDLE) {
                        handler.postDelayed(this, TimingConfig.scanInterval)
                    }
                }
            }
        }
        handler.postDelayed(scanRunnable!!, TimingConfig.scanInterval)
    }

    private fun executeStep(rootNode: AccessibilityNodeInfo): Boolean {
        val automation = currentAutomation ?: return false
        val packageName = currentPackage ?: return false

        AppLogger.automation(TAG, "State: $currentState, Retries: $retries, Step: $step",
            state = currentState.name, step = step)

        when (currentState) {
            AutomationState.IDLE -> {
                // Do nothing
            }

            AutomationState.WAITING_FOR_APP -> {
                // App is in foreground, transition to appropriate next state
                if (automation.requiresPickupFirst) {
                    transitionTo(AutomationState.FINDING_PICKUP_FIELD)
                } else {
                    transitionTo(AutomationState.FINDING_DESTINATION_FIELD)
                }
            }

            AutomationState.FINDING_PICKUP_FIELD -> {
                val result = automation.findAndClickPickupField(rootNode)
                handleResult(result, AutomationState.ENTERING_PICKUP)
            }

            AutomationState.ENTERING_PICKUP -> {
                val result = automation.enterPickupText(rootNode, pickupAddress, pickupLat, pickupLng)
                if (result.success) {
                    transitionTo(AutomationState.WAITING_FOR_SUGGESTIONS)
                } else {
                    handleRetry(result)
                }
            }

            AutomationState.WAITING_FOR_PICKUP_SUGGESTIONS -> {
                step++
                if (step > 3) {
                    transitionTo(AutomationState.SELECTING_SUGGESTION)
                }
            }

            AutomationState.CONFIRMING_PICKUP_ON_MAP -> {
                val result = automation.handleIntermediateScreens(rootNode)
                if (result.success || step > 3) {
                    transitionTo(AutomationState.FINDING_DESTINATION_FIELD)
                }
                step++
            }

            AutomationState.FINDING_DESTINATION_FIELD -> {
                val result = automation.findAndClickDestinationField(rootNode)
                handleResult(result, AutomationState.ENTERING_DESTINATION)
            }

            AutomationState.ENTERING_DESTINATION -> {
                val result = automation.enterDestinationText(rootNode, destinationAddress, destLat, destLng)
                if (result.success) {
                    transitionTo(AutomationState.WAITING_FOR_SUGGESTIONS)
                } else {
                    handleRetry(result)
                }
            }

            AutomationState.WAITING_FOR_SUGGESTIONS -> {
                // Handle intermediate screens first
                val intermediateResult = automation.handleIntermediateScreens(rootNode)
                if (intermediateResult.nextState != null) {
                    transitionTo(intermediateResult.nextState)
                    return true
                }

                step++
                if (step > 3) {
                    transitionTo(AutomationState.SELECTING_SUGGESTION)
                }
            }

            AutomationState.SELECTING_SUGGESTION -> {
                // Check if on price screen first
                if (automation.isPriceScreenVisible(rootNode)) {
                    transitionTo(AutomationState.WAITING_FOR_PRICE)
                    return true
                }

                // Handle intermediate screens
                val intermediateResult = automation.handleIntermediateScreens(rootNode)
                if (intermediateResult.success) {
                    return true // Screen was handled, wait for next iteration
                }

                // Select suggestion
                val isForPickup = when (automation) {
                    is CareemAutomation -> !automation.pickupAddress.isEmpty() && step < 10
                    is InDriverAutomation -> !automation.pickupAddress.isEmpty() && step < 10
                    is DiDiAutomation -> !automation.pickupAddress.isEmpty() && step < 10
                    else -> false
                }

                val result = automation.selectSuggestion(rootNode, isForPickup)
                if (result.success) {
                    // Check if we need to do pickup first, then destination
                    if (isForPickup && automation.requiresPickupFirst) {
                        transitionTo(AutomationState.FINDING_DESTINATION_FIELD)
                    } else {
                        transitionTo(AutomationState.WAITING_FOR_PRICE)
                    }
                } else {
                    handleRetry(result)
                }
            }

            AutomationState.WAITING_FOR_PRICE -> {
                // Handle intermediate screens
                val intermediateResult = automation.handleIntermediateScreens(rootNode)
                if (intermediateResult.success) {
                    return true
                }

                // Check for price
                if (automation.isPriceScreenVisible(rootNode)) {
                    val prices = automation.extractPrices(rootNode)
                    if (prices != null && prices.price > 0) {
                        AppLogger.automation(TAG, "PRICE CAPTURED: ${prices.price} EGP", state = "PRICE_CAPTURED")
                        onPriceExtracted?.invoke(packageName, prices)
                        transitionTo(AutomationState.PRICE_CAPTURED)
                        return true
                    }
                }

                // Timeout check
                val elapsed = System.currentTimeMillis() - startTime
                if (elapsed > TimingConfig.automationTimeout) {
                    AppLogger.e("Automation timeout after ${elapsed}ms", null, null, TAG)
                    transitionTo(AutomationState.FAILED)
                }

                step++
            }

            AutomationState.PRICE_CAPTURED -> {
                isActive = false
                onAutomationComplete?.invoke(packageName)
            }

            AutomationState.FAILED -> {
                isActive = false
                onAutomationFailed?.invoke(packageName, "Automation failed after $retries retries")
            }
        }

        return true
    }

    // ============================================================
    // PRIVATE - HELPERS
    // ============================================================

    private fun transitionTo(newState: AutomationState) {
        AppLogger.automation(TAG, "$currentState -> $newState", state = newState.name)
        currentState = newState
        retries = 0
        step = 0
        onStateChanged?.invoke(newState)
    }

    private fun handleResult(result: AutomationResult, nextStateOnSuccess: AutomationState) {
        if (result.success) {
            transitionTo(result.nextState ?: nextStateOnSuccess)
        } else if (result.shouldRetry) {
            handleRetry(result)
        } else {
            transitionTo(AutomationState.FAILED)
        }
    }

    private fun handleRetry(result: AutomationResult) {
        retries++
        consecutiveFailures++

        if (retries > TimingConfig.MAX_RETRIES) {
            AppLogger.e("Max retries exceeded in state $currentState - attempting fallback", null, null, TAG)

            // Try fallback before giving up
            val rootNode = service.rootInActiveWindow
            if (rootNode != null) {
                try {
                    val fallbackResult = attemptFallback(rootNode, result.message)
                    if (fallbackResult) {
                        // Fallback succeeded - reset retries and continue
                        retries = 0
                        consecutiveFailures = 0
                        AppLogger.d("Fallback succeeded - continuing automation", TAG)
                        return
                    }
                } finally {
                    rootNode.recycle()
                }
            }

            // Fallback failed - transition to FAILED state
            transitionTo(AutomationState.FAILED)
        } else {
            AppLogger.w("Retry $retries/${TimingConfig.MAX_RETRIES}: ${result.message}", TAG)

            // Check for consecutive failures - might need recovery
            if (consecutiveFailures >= maxConsecutiveFailures) {
                AppLogger.w("$consecutiveFailures consecutive failures - attempting recovery", TAG)
                val rootNode = service.rootInActiveWindow
                if (rootNode != null) {
                    try {
                        attemptRecovery(rootNode)
                    } finally {
                        rootNode.recycle()
                    }
                }
            }
        }
    }

    /**
     * محاولة Fallback عند فشل الأتمتة
     * Attempt fallback when automation fails
     */
    private fun attemptFallback(rootNode: AccessibilityNodeInfo, errorMessage: String): Boolean {
        val packageName = currentPackage ?: return false

        AppLogger.d("Attempting fallback for state: $currentState", TAG)

        // Determine fallback type based on current state
        val fallbackType = when (currentState) {
            AutomationState.FINDING_PICKUP_FIELD,
            AutomationState.FINDING_DESTINATION_FIELD -> FallbackType.CLICK

            AutomationState.ENTERING_PICKUP,
            AutomationState.ENTERING_DESTINATION -> FallbackType.TEXT_ENTRY

            AutomationState.SELECTING_SUGGESTION -> FallbackType.SUGGESTION

            AutomationState.WAITING_FOR_SUGGESTIONS,
            AutomationState.WAITING_FOR_PRICE -> FallbackType.INTERMEDIATE

            else -> FallbackType.RECOVERY
        }

        // Create fallback context
        val context = FallbackContext(
            packageName = packageName,
            currentState = currentState.name,
            targetText = when (currentState) {
                AutomationState.ENTERING_PICKUP -> pickupAddress
                AutomationState.ENTERING_DESTINATION -> destinationAddress
                AutomationState.SELECTING_SUGGESTION -> if (step < 10) pickupAddress else destinationAddress
                else -> ""
            },
            lastError = errorMessage,
            previousAttempts = retries,
            screenTexts = NodeFinder.getAllText(rootNode),
            metadata = mapOf(
                "pickupLat" to pickupLat,
                "pickupLng" to pickupLng,
                "destLat" to destLat,
                "destLng" to destLng
            )
        )

        // Execute fallback
        val result = fallbackManager.handleFailure(rootNode, context, fallbackType)

        AppLogger.d("Fallback result: success=${result.success}, strategy=${result.strategyUsed}", TAG)

        return result.success
    }

    /**
     * محاولة استعادة من حالة فشل متكرر
     * Attempt recovery from repeated failures
     */
    private fun attemptRecovery(rootNode: AccessibilityNodeInfo) {
        val packageName = currentPackage ?: return

        AppLogger.d("Attempting recovery for $packageName", TAG)

        val result = fallbackManager.attemptRecovery(
            rootNode,
            packageName,
            currentState.name,
            "Consecutive failures: $consecutiveFailures"
        )

        if (result.success) {
            consecutiveFailures = 0
            AppLogger.d("Recovery successful", TAG)
        } else {
            AppLogger.w("Recovery failed: ${result.message}", TAG)
        }
    }

    /**
     * إعادة تعيين مدير الـ Fallback
     * Reset fallback manager
     */
    fun resetFallbackAttempts() {
        fallbackManager.resetAttempts()
        consecutiveFailures = 0
    }
}
