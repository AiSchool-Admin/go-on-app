package com.goon.app

import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
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
    private val handler = Handler(Looper.getMainLooper())

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

                "scanCurrentApp" -> {
                    // Actively scan the current foreground app for prices
                    val priceInfo = PriceReaderService.instance?.scanCurrentApp()
                    if (priceInfo != null) {
                        result.success(mapOf(
                            "appName" to priceInfo.appName,
                            "packageName" to priceInfo.packageName,
                            "price" to priceInfo.price,
                            "serviceType" to priceInfo.serviceType,
                            "eta" to priceInfo.eta,
                            "allPricesFound" to priceInfo.allPricesFound,
                            "rawTexts" to priceInfo.rawTexts.take(20)
                        ))
                    } else {
                        result.success(null)
                    }
                }

                "startActiveMonitoring" -> {
                    val packageName = call.argument<String>("packageName") ?: ""
                    PriceReaderService.instance?.startActiveMonitoring(packageName)
                    result.success(true)
                }

                "stopActiveMonitoring" -> {
                    PriceReaderService.instance?.stopActiveMonitoring()
                    result.success(true)
                }

                "isActivelyMonitoring" -> {
                    result.success(PriceReaderService.isActiveMonitoring)
                }

                "getMonitoringPackage" -> {
                    result.success(PriceReaderService.monitoringPackage)
                }

                // ========== FULL AUTOMATION - إدخال تلقائي للوجهة ==========
                "automateGetPrice" -> {
                    val packageName = call.argument<String>("packageName") ?: ""
                    val pickup = call.argument<String>("pickup") ?: ""
                    val destination = call.argument<String>("destination") ?: ""
                    val pickupLat = call.argument<Double>("pickupLat") ?: 0.0
                    val pickupLng = call.argument<Double>("pickupLng") ?: 0.0
                    val destLat = call.argument<Double>("destLat") ?: 0.0
                    val destLng = call.argument<Double>("destLng") ?: 0.0

                    Log.i(TAG, "🤖 Starting FULL AUTOMATION for $packageName")
                    Log.i(TAG, "   Destination: $destination")

                    // Start automation (will automatically enter destination & read price)
                    PriceReaderService.instance?.automateGetPrice(
                        packageName, pickup, destination,
                        pickupLat, pickupLng, destLat, destLng
                    )

                    // Open the app
                    val opened = openApp(packageName)
                    result.success(opened)
                }

                "getAutomationState" -> {
                    result.success(PriceReaderService.instance?.getAutomationState() ?: "IDLE")
                }

                "isAutomationComplete" -> {
                    result.success(PriceReaderService.instance?.isAutomationComplete() ?: false)
                }

                "resetAutomation" -> {
                    PriceReaderService.instance?.resetAutomation()
                    result.success(true)
                }

                "getPriceForApp" -> {
                    val packageName = call.argument<String>("packageName") ?: ""
                    val price = PriceReaderService.instance?.getPriceForApp(packageName)
                    result.success(price)
                }

                "isServiceActive" -> {
                    result.success(PriceReaderService.instance?.isActive() ?: false)
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
                    val opened = openAppWithTrip(
                        packageName,
                        pickupLat, pickupLng,
                        dropoffLat, dropoffLng,
                        pickupAddress, dropoffAddress
                    )
                    result.success(opened)
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
            when (packageName) {
                PriceReaderService.UBER_PACKAGE -> {
                    // Uber deep link - this works well
                    val deepLink = "uber://?action=setPickup" +
                        "&pickup[latitude]=$pickupLat" +
                        "&pickup[longitude]=$pickupLng" +
                        "&pickup[nickname]=${android.net.Uri.encode(pickupAddress)}" +
                        "&dropoff[latitude]=$dropoffLat" +
                        "&dropoff[longitude]=$dropoffLng" +
                        "&dropoff[nickname]=${android.net.Uri.encode(dropoffAddress)}"
                    openDeepLink(deepLink, packageName)
                }
                PriceReaderService.CAREEM_PACKAGE -> {
                    // Try multiple Careem deep link formats
                    val success = tryMultipleDeepLinks(packageName, listOf(
                        // Format 1: careem://booking
                        "careem://booking?pickup_lat=$pickupLat&pickup_lng=$pickupLng" +
                            "&dropoff_lat=$dropoffLat&dropoff_lng=$dropoffLng",
                        // Format 2: careem://ride
                        "careem://ride?pickup_latitude=$pickupLat&pickup_longitude=$pickupLng" +
                            "&dropoff_latitude=$dropoffLat&dropoff_longitude=$dropoffLng",
                        // Format 3: https scheme
                        "https://app.careem.com/ride?pickup_lat=$pickupLat&pickup_lng=$pickupLng" +
                            "&dropoff_lat=$dropoffLat&dropoff_lng=$dropoffLng"
                    ))
                    if (!success) openApp(packageName) else true
                }
                PriceReaderService.INDRIVER_PACKAGE -> {
                    // InDriver: Try geo intent then regular open
                    val success = tryMultipleDeepLinks(packageName, listOf(
                        // Try geo intent format
                        "geo:$dropoffLat,$dropoffLng?q=$dropoffLat,$dropoffLng(${android.net.Uri.encode(dropoffAddress)})",
                        // Try indriver scheme
                        "indriver://order?from_lat=$pickupLat&from_lng=$pickupLng" +
                            "&to_lat=$dropoffLat&to_lng=$dropoffLng"
                    ))
                    if (!success) openApp(packageName) else true
                }
                PriceReaderService.DIDI_PACKAGE -> {
                    // DiDi: Try multiple formats
                    val success = tryMultipleDeepLinks(packageName, listOf(
                        // Format 1
                        "didiglobal://passenger?olat=$pickupLat&olng=$pickupLng" +
                            "&dlat=$dropoffLat&dlng=$dropoffLng",
                        // Format 2
                        "didi://passenger/order?flat=$pickupLat&flng=$pickupLng" +
                            "&tlat=$dropoffLat&tlng=$dropoffLng",
                        // Format 3: HTTPS universal link
                        "https://d.didiglobal.com/passenger?action=setPickup" +
                            "&flat=$pickupLat&flng=$pickupLng&tlat=$dropoffLat&tlng=$dropoffLng"
                    ))
                    if (!success) openApp(packageName) else true
                }
                PriceReaderService.BOLT_PACKAGE -> {
                    // Bolt: Try multiple formats
                    val success = tryMultipleDeepLinks(packageName, listOf(
                        // Format 1: bolt scheme with destination
                        "bolt://r?pickup_lat=$pickupLat&pickup_lng=$pickupLng" +
                            "&destination_lat=$dropoffLat&destination_lng=$dropoffLng",
                        // Format 2
                        "bolt://open?pickup_lat=$pickupLat&pickup_lng=$pickupLng" +
                            "&drop_lat=$dropoffLat&drop_lng=$dropoffLng",
                        // Format 3: HTTPS universal link
                        "https://bolt.eu/ride?pickup_lat=$pickupLat&pickup_lng=$pickupLng" +
                            "&destination_lat=$dropoffLat&destination_lng=$dropoffLng"
                    ))
                    if (!success) openApp(packageName) else true
                }
                else -> openApp(packageName)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error opening app with trip: $packageName - ${e.message}")
            openApp(packageName)
        }
    }

    /**
     * Try multiple deep link formats until one works
     */
    private fun tryMultipleDeepLinks(packageName: String, deepLinks: List<String>): Boolean {
        for (deepLink in deepLinks) {
            try {
                val uri = android.net.Uri.parse(deepLink)
                val intent = Intent(Intent.ACTION_VIEW, uri)

                // For geo: intents, set the package to prefer the ride app
                if (deepLink.startsWith("geo:")) {
                    intent.setPackage(packageName)
                } else {
                    intent.setPackage(packageName)
                }

                intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP

                if (intent.resolveActivity(packageManager) != null) {
                    startActivity(intent)
                    Log.i(TAG, "✓ Opened $packageName with: $deepLink")
                    return true
                }
            } catch (e: Exception) {
                Log.d(TAG, "Deep link failed: $deepLink - ${e.message}")
            }
        }
        return false
    }

    /**
     * Open a single deep link
     */
    private fun openDeepLink(deepLink: String, packageName: String): Boolean {
        return try {
            val intent = Intent(Intent.ACTION_VIEW, android.net.Uri.parse(deepLink))
            intent.setPackage(packageName)
            intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK

            if (intent.resolveActivity(packageManager) != null) {
                startActivity(intent)
                Log.i(TAG, "✓ Opened $packageName with deep link")
                true
            } else {
                Log.w(TAG, "Deep link not supported, opening app normally")
                openApp(packageName)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Deep link error: ${e.message}")
            openApp(packageName)
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
