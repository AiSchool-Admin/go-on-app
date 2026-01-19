package com.goon.app.services.automation

import android.accessibilityservice.AccessibilityService
import com.goon.app.services.config.AppConstants
import com.goon.app.utils.AppLogger

/**
 * مصنع الأتمتة - ينشئ فئة الأتمتة المناسبة لكل تطبيق
 * Automation Factory - creates appropriate automation class for each app
 *
 * Usage:
 * ```kotlin
 * val automation = AutomationFactory.create(service, packageName)
 * automation?.let {
 *     it.pickupAddress = pickup
 *     it.destinationAddress = destination
 *     it.resetState()
 *
 *     when (state) {
 *         FINDING_PICKUP_FIELD -> it.findAndClickPickupField(rootNode)
 *         // ...
 *     }
 * }
 * ```
 */
object AutomationFactory {
    private const val TAG = "AutomationFactory"

    // Cache automation instances
    private val automationCache = mutableMapOf<String, BaseAutomation>()

    /**
     * الحصول على/إنشاء فئة الأتمتة المناسبة للتطبيق
     * Get or create appropriate automation class for app
     */
    fun getAutomation(
        service: AccessibilityService,
        packageName: String
    ): BaseAutomation? {
        // Check cache first
        automationCache[packageName]?.let { return it }

        // Create new instance
        val automation = when (packageName) {
            AppConstants.Packages.UBER -> UberAutomation(service)
            AppConstants.Packages.CAREEM -> CareemAutomation(service)
            AppConstants.Packages.INDRIVER -> InDriverAutomation(service)
            AppConstants.Packages.DIDI -> DiDiAutomation(service)
            AppConstants.Packages.BOLT -> BoltAutomation(service)
            else -> {
                AppLogger.w("Unknown package: $packageName", TAG)
                null
            }
        }

        automation?.let {
            automationCache[packageName] = it
            AppLogger.d("Created automation for: ${it.displayName}", TAG)
        }

        return automation
    }

    /**
     * إنشاء فئة جديدة (بدون تخزين مؤقت)
     * Create new instance (no caching)
     */
    fun create(
        service: AccessibilityService,
        packageName: String
    ): BaseAutomation? {
        return when (packageName) {
            AppConstants.Packages.UBER -> UberAutomation(service)
            AppConstants.Packages.CAREEM -> CareemAutomation(service)
            AppConstants.Packages.INDRIVER -> InDriverAutomation(service)
            AppConstants.Packages.DIDI -> DiDiAutomation(service)
            AppConstants.Packages.BOLT -> BoltAutomation(service)
            else -> null
        }
    }

    /**
     * تعيين تفاصيل الرحلة لفئة الأتمتة
     * Set trip details for automation class
     */
    fun setTripDetails(
        automation: BaseAutomation,
        pickup: String,
        destination: String,
        pickupLat: Double,
        pickupLng: Double,
        destLat: Double,
        destLng: Double
    ) {
        when (automation) {
            is UberAutomation -> {
                // Uber uses deep link, minimal setup needed
            }
            is CareemAutomation -> {
                automation.pickupAddress = pickup
                automation.destinationAddress = destination
                automation.pickupLat = pickupLat
                automation.pickupLng = pickupLng
                automation.destLat = destLat
                automation.destLng = destLng
            }
            is InDriverAutomation -> {
                automation.pickupAddress = pickup
                automation.destinationAddress = destination
                automation.pickupLat = pickupLat
                automation.pickupLng = pickupLng
                automation.destLat = destLat
                automation.destLng = destLng
            }
            is DiDiAutomation -> {
                automation.pickupAddress = pickup
                automation.destinationAddress = destination
                automation.pickupLat = pickupLat
                automation.pickupLng = pickupLng
                automation.destLat = destLat
                automation.destLng = destLng
            }
            is BoltAutomation -> {
                automation.pickupAddress = pickup
                automation.destinationAddress = destination
                automation.pickupLat = pickupLat
                automation.pickupLng = pickupLng
                automation.destLat = destLat
                automation.destLng = destLng
            }
        }
    }

    /**
     * إعادة تعيين حالة الأتمتة
     * Reset automation state
     */
    fun resetAutomation(automation: BaseAutomation) {
        when (automation) {
            is UberAutomation -> automation.resetState()
            is CareemAutomation -> automation.resetState()
            is InDriverAutomation -> automation.resetState()
            is DiDiAutomation -> automation.resetState()
            is BoltAutomation -> automation.resetState()
        }
    }

    /**
     * مسح ذاكرة التخزين المؤقت
     * Clear automation cache
     */
    fun clearCache() {
        automationCache.clear()
        AppLogger.d("Automation cache cleared", TAG)
    }

    /**
     * الحصول على قائمة التطبيقات المدعومة
     * Get list of supported apps
     */
    fun getSupportedPackages(): List<String> {
        return listOf(
            AppConstants.Packages.UBER,
            AppConstants.Packages.CAREEM,
            AppConstants.Packages.INDRIVER,
            AppConstants.Packages.DIDI,
            AppConstants.Packages.BOLT
        )
    }

    /**
     * هل التطبيق مدعوم؟
     * Is the app supported?
     */
    fun isSupported(packageName: String): Boolean {
        return packageName in getSupportedPackages()
    }

    /**
     * هل التطبيق يدعم deep link كامل؟
     * Does the app support full deep link?
     */
    fun supportsFullDeepLink(packageName: String): Boolean {
        return when (packageName) {
            AppConstants.Packages.UBER -> true
            else -> false
        }
    }

    /**
     * هل التطبيق يحتاج إدخال نقطة الانطلاق أولاً؟
     * Does the app require pickup first?
     */
    fun requiresPickupFirst(packageName: String): Boolean {
        return when (packageName) {
            AppConstants.Packages.CAREEM -> true
            AppConstants.Packages.INDRIVER -> true
            AppConstants.Packages.DIDI -> true
            else -> false
        }
    }

    /**
     * الحصول على الحالة الأولية للأتمتة
     * Get initial automation state for package
     */
    fun getInitialState(packageName: String): AutomationState {
        return when {
            supportsFullDeepLink(packageName) -> AutomationState.WAITING_FOR_PRICE
            requiresPickupFirst(packageName) -> AutomationState.WAITING_FOR_APP
            else -> AutomationState.WAITING_FOR_APP
        }
    }

    /**
     * الحصول على اسم العرض للتطبيق
     * Get display name for package
     */
    fun getDisplayName(packageName: String): String {
        return when (packageName) {
            AppConstants.Packages.UBER -> "أوبر"
            AppConstants.Packages.CAREEM -> "كريم"
            AppConstants.Packages.INDRIVER -> "إندرايفر"
            AppConstants.Packages.DIDI -> "ديدي"
            AppConstants.Packages.BOLT -> "بولت"
            else -> packageName
        }
    }
}
