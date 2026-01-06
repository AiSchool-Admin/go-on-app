package com.goon.app

import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.text.TextUtils
import android.util.Log
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodChannel
import com.goon.app.services.PriceReaderService
import com.goon.app.services.FloatingOverlayService

/**
 * GO-ON Main Activity
 *
 * Handles communication between Flutter and native Android services
 * for price reading and floating overlay functionality.
 */
class MainActivity : FlutterActivity() {

    companion object {
        private const val TAG = "GO-ON-MainActivity"
        private const val CHANNEL = "com.goon.app/services"
    }

    private var methodChannel: MethodChannel? = null

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)

        methodChannel = MethodChannel(flutterEngine.dartExecutor.binaryMessenger, CHANNEL)

        methodChannel?.setMethodCallHandler { call, result ->
            when (call.method) {
                // ============ Accessibility Service Methods ============

                "isAccessibilityEnabled" -> {
                    result.success(isAccessibilityServiceEnabled())
                }

                "openAccessibilitySettings" -> {
                    openAccessibilitySettings()
                    result.success(true)
                }

                "getLatestPrices" -> {
                    val prices = PriceReaderService.instance?.getAllPricesJson() ?: "[]"
                    result.success(prices)
                }

                "clearPrices" -> {
                    PriceReaderService.instance?.clearPrices()
                    result.success(true)
                }

                // ============ Floating Overlay Methods ============

                "canDrawOverlay" -> {
                    result.success(FloatingOverlayService.canDrawOverlay(this))
                }

                "openOverlaySettings" -> {
                    FloatingOverlayService.openOverlaySettings(this)
                    result.success(true)
                }

                "showOverlay" -> {
                    val goonPrice = call.argument<Double>("goonPrice") ?: 0.0
                    val currentPrice = call.argument<Double>("currentPrice") ?: 0.0
                    val currentApp = call.argument<String>("currentApp") ?: ""
                    val savings = call.argument<Int>("savings") ?: 0

                    FloatingOverlayService.showBetterPrice(
                        this, goonPrice, currentPrice, currentApp, savings
                    )
                    result.success(true)
                }

                "hideOverlay" -> {
                    FloatingOverlayService.hide()
                    result.success(true)
                }

                "setGoonBestPrice" -> {
                    val price = call.argument<Double>("price") ?: 0.0
                    FloatingOverlayService.setGoonPrice(price)
                    result.success(true)
                }

                // ============ App Management Methods ============

                "isAppInstalled" -> {
                    val packageName = call.argument<String>("packageName") ?: ""
                    result.success(isAppInstalled(packageName))
                }

                "openApp" -> {
                    val packageName = call.argument<String>("packageName") ?: ""
                    result.success(openApp(packageName))
                }

                "getInstalledRideApps" -> {
                    result.success(getInstalledRideApps())
                }

                // ============ Permissions Check ============

                "checkAllPermissions" -> {
                    val permissions = mapOf(
                        "accessibility" to isAccessibilityServiceEnabled(),
                        "overlay" to FloatingOverlayService.canDrawOverlay(this)
                    )
                    result.success(permissions)
                }

                else -> {
                    result.notImplemented()
                }
            }
        }

        Log.i(TAG, "Flutter Method Channel configured")
    }

    /**
     * Check if our Accessibility Service is enabled
     */
    private fun isAccessibilityServiceEnabled(): Boolean {
        val serviceName = ComponentName(this, PriceReaderService::class.java)
        val enabledServices = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        )

        if (enabledServices.isNullOrEmpty()) return false

        val colonSplitter = TextUtils.SimpleStringSplitter(':')
        colonSplitter.setString(enabledServices)

        while (colonSplitter.hasNext()) {
            val componentNameString = colonSplitter.next()
            val enabledService = ComponentName.unflattenFromString(componentNameString)
            if (enabledService != null && enabledService == serviceName) {
                return true
            }
        }

        return false
    }

    /**
     * Open Accessibility Settings
     */
    private fun openAccessibilitySettings() {
        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
        startActivity(intent)
    }

    /**
     * Check if an app is installed
     */
    private fun isAppInstalled(packageName: String): Boolean {
        return try {
            packageManager.getPackageInfo(packageName, 0)
            true
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Open an app by package name
     */
    private fun openApp(packageName: String): Boolean {
        return try {
            val intent = packageManager.getLaunchIntentForPackage(packageName)
            if (intent != null) {
                startActivity(intent)
                true
            } else {
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error opening app: $packageName - ${e.message}")
            false
        }
    }

    /**
     * Get list of installed ride-hailing apps
     */
    private fun getInstalledRideApps(): List<String> {
        val rideApps = listOf(
            PriceReaderService.UBER_PACKAGE,
            PriceReaderService.CAREEM_PACKAGE,
            PriceReaderService.INDRIVER_PACKAGE,
            PriceReaderService.DIDI_PACKAGE
        )

        return rideApps.filter { isAppInstalled(it) }
    }

    override fun onResume() {
        super.onResume()
        // Send permission status update to Flutter when app resumes
        sendPermissionStatusToFlutter()
    }

    private fun sendPermissionStatusToFlutter() {
        methodChannel?.invokeMethod("onPermissionStatusChanged", mapOf(
            "accessibility" to isAccessibilityServiceEnabled(),
            "overlay" to FloatingOverlayService.canDrawOverlay(this)
        ))
    }
}
