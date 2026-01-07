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

                "openAppWithTrip" -> {
                    val packageName = call.argument<String>("packageName") ?: ""
                    val pickupLat = call.argument<Double>("pickupLat") ?: 0.0
                    val pickupLng = call.argument<Double>("pickupLng") ?: 0.0
                    val dropoffLat = call.argument<Double>("dropoffLat") ?: 0.0
                    val dropoffLng = call.argument<Double>("dropoffLng") ?: 0.0
                    val pickupAddress = call.argument<String>("pickupAddress") ?: ""
                    val dropoffAddress = call.argument<String>("dropoffAddress") ?: ""

                    result.success(openAppWithTrip(
                        packageName,
                        pickupLat, pickupLng,
                        dropoffLat, dropoffLng,
                        pickupAddress, dropoffAddress
                    ))
                }

                "fetchPriceFromApp" -> {
                    val packageName = call.argument<String>("packageName") ?: ""
                    val pickupLat = call.argument<Double>("pickupLat") ?: 0.0
                    val pickupLng = call.argument<Double>("pickupLng") ?: 0.0
                    val dropoffLat = call.argument<Double>("dropoffLat") ?: 0.0
                    val dropoffLng = call.argument<Double>("dropoffLng") ?: 0.0
                    val pickupAddress = call.argument<String>("pickupAddress") ?: ""
                    val dropoffAddress = call.argument<String>("dropoffAddress") ?: ""

                    // Open app with trip details
                    openAppWithTrip(
                        packageName,
                        pickupLat, pickupLng,
                        dropoffLat, dropoffLng,
                        pickupAddress, dropoffAddress
                    )
                    result.success(true)
                }

                "returnToApp" -> {
                    // Bring GO-ON back to foreground
                    val intent = packageManager.getLaunchIntentForPackage(packageName)
                    intent?.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
                    if (intent != null) {
                        startActivity(intent)
                    }
                    result.success(true)
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
     * Open an app with trip details (pickup/dropoff locations)
     * Uses deep links specific to each ride-hailing app
     */
    private fun openAppWithTrip(
        packageName: String,
        pickupLat: Double,
        pickupLng: Double,
        dropoffLat: Double,
        dropoffLng: Double,
        pickupAddress: String,
        dropoffAddress: String
    ): Boolean {
        return try {
            val deepLink = when (packageName) {
                PriceReaderService.UBER_PACKAGE -> {
                    // Uber deep link format
                    "uber://?action=setPickup" +
                    "&pickup[latitude]=$pickupLat" +
                    "&pickup[longitude]=$pickupLng" +
                    "&pickup[nickname]=${android.net.Uri.encode(pickupAddress)}" +
                    "&dropoff[latitude]=$dropoffLat" +
                    "&dropoff[longitude]=$dropoffLng" +
                    "&dropoff[nickname]=${android.net.Uri.encode(dropoffAddress)}"
                }
                PriceReaderService.CAREEM_PACKAGE -> {
                    // Careem deep link format
                    "careem://booking?" +
                    "pickup_lat=$pickupLat" +
                    "&pickup_lng=$pickupLng" +
                    "&dropoff_lat=$dropoffLat" +
                    "&dropoff_lng=$dropoffLng" +
                    "&pickup_name=${android.net.Uri.encode(pickupAddress)}" +
                    "&dropoff_name=${android.net.Uri.encode(dropoffAddress)}"
                }
                PriceReaderService.INDRIVER_PACKAGE -> {
                    // InDriver - use intent with location data
                    // InDriver doesn't have a public deep link API, so we open the app
                    // and the user needs to enter the destination manually
                    null
                }
                PriceReaderService.DIDI_PACKAGE -> {
                    // DiDi deep link
                    "didiglobal://ridenow?" +
                    "olat=$pickupLat" +
                    "&olng=$pickupLng" +
                    "&dlat=$dropoffLat" +
                    "&dlng=$dropoffLng" +
                    "&oname=${android.net.Uri.encode(pickupAddress)}" +
                    "&dname=${android.net.Uri.encode(dropoffAddress)}"
                }
                PriceReaderService.BOLT_PACKAGE -> {
                    // Bolt deep link
                    "bolt://ride?" +
                    "pickup_lat=$pickupLat" +
                    "&pickup_lng=$pickupLng" +
                    "&dest_lat=$dropoffLat" +
                    "&dest_lng=$dropoffLng" +
                    "&pickup_addr=${android.net.Uri.encode(pickupAddress)}" +
                    "&dest_addr=${android.net.Uri.encode(dropoffAddress)}"
                }
                else -> null
            }

            if (deepLink != null) {
                val intent = Intent(Intent.ACTION_VIEW, android.net.Uri.parse(deepLink))
                intent.setPackage(packageName)
                intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK

                // Check if the app can handle this deep link
                if (intent.resolveActivity(packageManager) != null) {
                    startActivity(intent)
                    Log.d(TAG, "Opened $packageName with deep link: $deepLink")
                    return true
                } else {
                    // Fallback to regular app launch
                    Log.w(TAG, "Deep link not supported by $packageName, using regular launch")
                    return openApp(packageName)
                }
            } else {
                // No deep link available, just open the app
                return openApp(packageName)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error opening app with trip: $packageName - ${e.message}")
            // Fallback to regular app open
            return openApp(packageName)
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
            PriceReaderService.DIDI_PACKAGE,
            PriceReaderService.BOLT_PACKAGE
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
