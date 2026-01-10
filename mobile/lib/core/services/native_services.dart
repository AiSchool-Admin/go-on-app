import 'dart:convert';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

/// Provider for NativeServicesManager
final nativeServicesProvider = Provider<NativeServicesManager>((ref) {
  return NativeServicesManager();
});

/// Model for price data from other apps
class AppPrice {
  final String appName;
  final String packageName;
  final double price;
  final String serviceType;
  final int eta;
  final DateTime timestamp;

  AppPrice({
    required this.appName,
    required this.packageName,
    required this.price,
    this.serviceType = '',
    this.eta = 0,
    required this.timestamp,
  });

  factory AppPrice.fromJson(Map<String, dynamic> json) {
    return AppPrice(
      appName: json['appName'] ?? '',
      packageName: json['packageName'] ?? '',
      price: (json['price'] ?? 0).toDouble(),
      serviceType: json['serviceType'] ?? '',
      eta: json['eta'] ?? 0,
      timestamp: DateTime.fromMillisecondsSinceEpoch(json['timestamp'] ?? 0),
    );
  }
}

/// Model for permission status
class PermissionStatus {
  final bool accessibility;
  final bool overlay;

  PermissionStatus({
    required this.accessibility,
    required this.overlay,
  });

  bool get allGranted => accessibility && overlay;
}

/// Manager for native Android services
/// Handles communication with PriceReaderService and FloatingOverlayService
class NativeServicesManager {
  static const _channel = MethodChannel('com.goon.app/services');

  Function(PermissionStatus)? _onPermissionStatusChanged;

  NativeServicesManager() {
    _setupMethodCallHandler();
  }

  void _setupMethodCallHandler() {
    _channel.setMethodCallHandler((call) async {
      switch (call.method) {
        case 'onPermissionStatusChanged':
          final args = call.arguments as Map<dynamic, dynamic>;
          final status = PermissionStatus(
            accessibility: args['accessibility'] ?? false,
            overlay: args['overlay'] ?? false,
          );
          _onPermissionStatusChanged?.call(status);
          break;
      }
    });
  }

  /// Set listener for permission status changes
  void setPermissionStatusListener(Function(PermissionStatus) listener) {
    _onPermissionStatusChanged = listener;
  }

  // ============ Accessibility Service Methods ============

  /// Check if accessibility service is enabled
  Future<bool> isAccessibilityEnabled() async {
    try {
      final result = await _channel.invokeMethod<bool>('isAccessibilityEnabled');
      return result ?? false;
    } on PlatformException catch (e) {
      print('Error checking accessibility: ${e.message}');
      return false;
    }
  }

  /// Check if the price reader service is active
  Future<bool> isServiceActive() async {
    try {
      final result = await _channel.invokeMethod<bool>('isServiceActive');
      return result ?? false;
    } on PlatformException catch (e) {
      print('Error checking service active: ${e.message}');
      return false;
    }
  }

  /// Open accessibility settings for user to enable the service
  Future<void> openAccessibilitySettings() async {
    try {
      await _channel.invokeMethod('openAccessibilitySettings');
    } on PlatformException catch (e) {
      print('Error opening accessibility settings: ${e.message}');
    }
  }

  /// Get latest prices captured from other apps
  Future<List<AppPrice>> getLatestPrices() async {
    try {
      final result = await _channel.invokeMethod<String>('getLatestPrices');
      if (result == null || result.isEmpty || result == '[]') {
        return [];
      }

      final List<dynamic> jsonList = json.decode(result);
      return jsonList.map((e) => AppPrice.fromJson(e)).toList();
    } on PlatformException catch (e) {
      print('Error getting prices: ${e.message}');
      return [];
    }
  }

  /// Get price for a specific app
  Future<double?> getPriceForApp(String packageName) async {
    try {
      final result = await _channel.invokeMethod<double>('getPriceForApp', {
        'packageName': packageName,
      });
      return result;
    } on PlatformException catch (e) {
      print('Error getting price for app: ${e.message}');
      return null;
    }
  }

  /// Actively scan the current foreground app for prices
  Future<AppPrice?> scanCurrentApp() async {
    try {
      final result = await _channel.invokeMethod<Map<dynamic, dynamic>>('scanCurrentApp');
      if (result == null) return null;

      return AppPrice(
        appName: result['appName'] ?? '',
        packageName: result['packageName'] ?? '',
        price: (result['price'] ?? 0).toDouble(),
        serviceType: result['serviceType'] ?? '',
        eta: result['eta'] ?? 0,
        timestamp: DateTime.now(),
      );
    } on PlatformException catch (e) {
      print('Error scanning current app: ${e.message}');
      return null;
    }
  }

  // ============ ACTIVE MONITORING - NEW TECHNICAL SOLUTION ============

  /// Start active monitoring for a specific app
  /// This will aggressively scan for prices every 500ms while user is in the app
  Future<void> startActiveMonitoring(String packageName) async {
    try {
      await _channel.invokeMethod('startActiveMonitoring', {
        'packageName': packageName,
      });
      print('✓ Started active monitoring for $packageName');
    } on PlatformException catch (e) {
      print('Error starting active monitoring: ${e.message}');
    }
  }

  /// Stop active monitoring
  Future<void> stopActiveMonitoring() async {
    try {
      await _channel.invokeMethod('stopActiveMonitoring');
      print('✓ Stopped active monitoring');
    } on PlatformException catch (e) {
      print('Error stopping active monitoring: ${e.message}');
    }
  }

  /// Check if we're actively monitoring
  Future<bool> isActivelyMonitoring() async {
    try {
      final result = await _channel.invokeMethod<bool>('isActivelyMonitoring');
      return result ?? false;
    } on PlatformException catch (e) {
      print('Error checking monitoring status: ${e.message}');
      return false;
    }
  }

  /// Get the package currently being monitored
  Future<String?> getMonitoringPackage() async {
    try {
      final result = await _channel.invokeMethod<String>('getMonitoringPackage');
      return result;
    } on PlatformException catch (e) {
      print('Error getting monitoring package: ${e.message}');
      return null;
    }
  }

  /// Open app and start active monitoring - the core method for getting REAL prices
  /// 1. Opens the ride app
  /// 2. Starts aggressive price monitoring
  /// 3. Returns immediately - user sets up trip manually
  /// 4. When user returns, call getCapturedPrice() to get the real price
  Future<bool> openAppAndMonitor(String packageName) async {
    try {
      // Clear old prices for this app
      await clearPrices();

      // Start active monitoring BEFORE opening the app
      await startActiveMonitoring(packageName);

      // Open the app
      final opened = await openApp(packageName);
      if (!opened) {
        await stopActiveMonitoring();
        return false;
      }

      print('✓ Opened $packageName with active monitoring');
      return true;
    } catch (e) {
      print('Error opening app and monitoring: $e');
      await stopActiveMonitoring();
      return false;
    }
  }

  /// Get the captured price after user returns from ride app
  /// Call this after user comes back from the other app
  Future<double?> getCapturedPrice(String packageName) async {
    // Stop monitoring
    await stopActiveMonitoring();

    // Wait a moment for any final scans
    await Future.delayed(const Duration(milliseconds: 300));

    // Get the captured price
    return await getPriceForApp(packageName);
  }

  // ============ FULL AUTOMATION - أتمتة كاملة ============

  /// Fully automated price fetching:
  /// 1. Opens the ride app
  /// 2. AUTOMATICALLY enters the destination
  /// 3. AUTOMATICALLY selects the suggestion
  /// 4. AUTOMATICALLY captures the price
  /// 5. User just returns to GO-ON
  Future<bool> automateGetPrice({
    required String packageName,
    required String pickup,
    required String destination,
    required double pickupLat,
    required double pickupLng,
    required double destLat,
    required double destLng,
  }) async {
    try {
      // Clear old prices
      await clearPrices();

      // Call the automation method
      final result = await _channel.invokeMethod<bool>('automateGetPrice', {
        'packageName': packageName,
        'pickup': pickup,
        'destination': destination,
        'pickupLat': pickupLat,
        'pickupLng': pickupLng,
        'destLat': destLat,
        'destLng': destLng,
      });

      print('🤖 Started FULL AUTOMATION for $packageName');
      return result ?? false;
    } on PlatformException catch (e) {
      print('Error in automation: ${e.message}');
      return false;
    }
  }

  /// Get the current automation state
  Future<String> getAutomationState() async {
    try {
      final result = await _channel.invokeMethod<String>('getAutomationState');
      return result ?? 'IDLE';
    } on PlatformException catch (e) {
      print('Error getting automation state: ${e.message}');
      return 'ERROR';
    }
  }

  /// Check if automation is complete
  Future<bool> isAutomationComplete() async {
    try {
      final result = await _channel.invokeMethod<bool>('isAutomationComplete');
      return result ?? false;
    } on PlatformException catch (e) {
      print('Error checking automation: ${e.message}');
      return false;
    }
  }

  /// Reset automation state
  Future<void> resetAutomation() async {
    try {
      await _channel.invokeMethod('resetAutomation');
    } on PlatformException catch (e) {
      print('Error resetting automation: ${e.message}');
    }
  }

  /// Wait for automation to complete and get the price
  Future<double?> waitForAutomationAndGetPrice(String packageName) async {
    // Poll for completion (max 15 seconds)
    for (int i = 0; i < 30; i++) {
      await Future.delayed(const Duration(milliseconds: 500));

      final complete = await isAutomationComplete();
      if (complete) {
        final state = await getAutomationState();
        print('Automation completed with state: $state');

        if (state == 'PRICE_CAPTURED') {
          return await getPriceForApp(packageName);
        } else {
          return null; // Failed
        }
      }
    }

    print('Automation timeout');
    return null;
  }

  /// Clear captured prices
  Future<void> clearPrices() async {
    try {
      await _channel.invokeMethod('clearPrices');
    } on PlatformException catch (e) {
      print('Error clearing prices: ${e.message}');
    }
  }

  // ============ Floating Overlay Methods ============

  /// Check if app can draw overlays
  Future<bool> canDrawOverlay() async {
    try {
      final result = await _channel.invokeMethod<bool>('canDrawOverlay');
      return result ?? false;
    } on PlatformException catch (e) {
      print('Error checking overlay permission: ${e.message}');
      return false;
    }
  }

  /// Open overlay settings for user to enable
  Future<void> openOverlaySettings() async {
    try {
      await _channel.invokeMethod('openOverlaySettings');
    } on PlatformException catch (e) {
      print('Error opening overlay settings: ${e.message}');
    }
  }

  /// Show floating overlay with price comparison
  Future<void> showOverlay({
    required double goonPrice,
    required double currentPrice,
    required String currentApp,
    required int savingsPercent,
  }) async {
    try {
      await _channel.invokeMethod('showOverlay', {
        'goonPrice': goonPrice,
        'currentPrice': currentPrice,
        'currentApp': currentApp,
        'savings': savingsPercent,
      });
    } on PlatformException catch (e) {
      print('Error showing overlay: ${e.message}');
    }
  }

  /// Hide floating overlay
  Future<void> hideOverlay() async {
    try {
      await _channel.invokeMethod('hideOverlay');
    } on PlatformException catch (e) {
      print('Error hiding overlay: ${e.message}');
    }
  }

  /// Set GO-ON best price for comparison
  Future<void> setGoonBestPrice(double price) async {
    try {
      await _channel.invokeMethod('setGoonBestPrice', {'price': price});
    } on PlatformException catch (e) {
      print('Error setting GOON price: ${e.message}');
    }
  }

  // ============ App Management Methods ============

  /// Check if an app is installed
  Future<bool> isAppInstalled(String packageName) async {
    try {
      final result = await _channel.invokeMethod<bool>('isAppInstalled', {
        'packageName': packageName,
      });
      return result ?? false;
    } on PlatformException catch (e) {
      print('Error checking app installed: ${e.message}');
      return false;
    }
  }

  /// Open an app by package name
  Future<bool> openApp(String packageName) async {
    try {
      final result = await _channel.invokeMethod<bool>('openApp', {
        'packageName': packageName,
      });
      return result ?? false;
    } on PlatformException catch (e) {
      print('Error opening app: ${e.message}');
      return false;
    }
  }

  /// Open an app with trip details (pickup/dropoff locations)
  /// This will try to create the trip automatically in the other app
  Future<bool> openAppWithTrip({
    required String packageName,
    required double pickupLat,
    required double pickupLng,
    required double dropoffLat,
    required double dropoffLng,
    String pickupAddress = '',
    String dropoffAddress = '',
  }) async {
    try {
      final result = await _channel.invokeMethod<bool>('openAppWithTrip', {
        'packageName': packageName,
        'pickupLat': pickupLat,
        'pickupLng': pickupLng,
        'dropoffLat': dropoffLat,
        'dropoffLng': dropoffLng,
        'pickupAddress': pickupAddress,
        'dropoffAddress': dropoffAddress,
      });
      return result ?? false;
    } on PlatformException catch (e) {
      print('Error opening app with trip: ${e.message}');
      return false;
    }
  }

  /// Fetch price from a specific app by opening it with trip details
  /// Returns after opening the app - price will be captured by accessibility service
  Future<bool> fetchPriceFromApp({
    required String packageName,
    required double pickupLat,
    required double pickupLng,
    required double dropoffLat,
    required double dropoffLng,
    String pickupAddress = '',
    String dropoffAddress = '',
  }) async {
    try {
      final result = await _channel.invokeMethod<bool>('fetchPriceFromApp', {
        'packageName': packageName,
        'pickupLat': pickupLat,
        'pickupLng': pickupLng,
        'dropoffLat': dropoffLat,
        'dropoffLng': dropoffLng,
        'pickupAddress': pickupAddress,
        'dropoffAddress': dropoffAddress,
      });
      return result ?? false;
    } on PlatformException catch (e) {
      print('Error fetching price from app: ${e.message}');
      return false;
    }
  }

  /// Return to GO-ON app (bring to foreground)
  Future<void> returnToApp() async {
    try {
      await _channel.invokeMethod('returnToApp');
    } on PlatformException catch (e) {
      print('Error returning to app: ${e.message}');
    }
  }

  /// Fetch real prices from all installed apps
  /// Opens each app sequentially, waits for price, then returns
  Future<Map<String, double>> fetchAllRealPrices({
    required double pickupLat,
    required double pickupLng,
    required double dropoffLat,
    required double dropoffLng,
    String pickupAddress = '',
    String dropoffAddress = '',
    Function(String appName, int current, int total)? onProgress,
  }) async {
    final installedApps = await getInstalledRideApps();
    final prices = <String, double>{};

    if (installedApps.isEmpty) {
      print('No ride apps installed');
      return prices;
    }

    // Clear old prices
    await clearPrices();

    for (int i = 0; i < installedApps.length; i++) {
      final packageName = installedApps[i];
      final appName = _getAppName(packageName);

      onProgress?.call(appName, i + 1, installedApps.length);
      print('Fetching price from $appName ($packageName)...');

      // Open the app with trip details
      final opened = await fetchPriceFromApp(
        packageName: packageName,
        pickupLat: pickupLat,
        pickupLng: pickupLng,
        dropoffLat: dropoffLat,
        dropoffLng: dropoffLng,
        pickupAddress: pickupAddress,
        dropoffAddress: dropoffAddress,
      );

      if (!opened) {
        print('Failed to open $appName');
        continue;
      }

      // Wait for app to load and show prices (increased to 6 seconds)
      await Future.delayed(const Duration(seconds: 6));

      // Try to get the captured price with retries
      double? capturedPrice;
      for (int retry = 0; retry < 3; retry++) {
        // First try to get from stored prices
        final latestPrices = await getLatestPrices();
        final appPrice = latestPrices.where((p) => p.packageName == packageName).firstOrNull;

        if (appPrice != null && appPrice.price > 0) {
          capturedPrice = appPrice.price;
          print('✓ Got price from $appName: ${appPrice.price} EGP (from stored)');
          break;
        }

        // Also try direct query
        final directPrice = await getPriceForApp(packageName);
        if (directPrice != null && directPrice > 0) {
          capturedPrice = directPrice;
          print('✓ Got price from $appName: $directPrice EGP (direct)');
          break;
        }

        // Wait a bit more if no price yet
        if (retry < 2) {
          print('No price yet from $appName, waiting... (retry ${retry + 1})');
          await Future.delayed(const Duration(seconds: 2));
        }
      }

      if (capturedPrice != null) {
        prices[packageName] = capturedPrice;
      } else {
        print('✗ Could not get price from $appName');
      }

      // Return to GO-ON
      await returnToApp();
      await Future.delayed(const Duration(milliseconds: 800));
    }

    print('Fetched ${prices.length} prices from ${installedApps.length} apps');
    return prices;
  }

  String _getAppName(String packageName) {
    switch (packageName) {
      case uberPackage:
        return 'Uber';
      case careemPackage:
        return 'Careem';
      case indriverPackage:
        return 'InDriver';
      case didiPackage:
        return 'DiDi';
      case boltPackage:
        return 'Bolt';
      default:
        return packageName;
    }
  }

  /// Get list of installed ride-hailing apps
  Future<List<String>> getInstalledRideApps() async {
    try {
      final result = await _channel.invokeMethod<List<dynamic>>('getInstalledRideApps');
      return result?.cast<String>() ?? [];
    } on PlatformException catch (e) {
      print('Error getting installed apps: ${e.message}');
      return [];
    }
  }

  // ============ Permissions Check ============

  /// Check all required permissions
  Future<PermissionStatus> checkAllPermissions() async {
    try {
      final result = await _channel.invokeMethod<Map<dynamic, dynamic>>('checkAllPermissions');
      return PermissionStatus(
        accessibility: result?['accessibility'] ?? false,
        overlay: result?['overlay'] ?? false,
      );
    } on PlatformException catch (e) {
      print('Error checking permissions: ${e.message}');
      return PermissionStatus(accessibility: false, overlay: false);
    }
  }

  // ============ BATCH FETCH WITH AUTO-RETURN ============

  /// Fetch real prices from all installed apps using FULL AUTOMATION
  /// Each app: open with deep link → auto-enter destination → capture price → auto-return
  /// InDriver is skipped (use formula) due to anti-automation protection
  Future<Map<String, double>> fetchAllPricesWithAutomation({
    required double pickupLat,
    required double pickupLng,
    required double dropoffLat,
    required double dropoffLng,
    required String pickupAddress,
    required String dropoffAddress,
    Function(String appName, String status)? onProgress,
  }) async {
    final prices = <String, double>{};

    // Get installed apps (skip InDriver - has anti-automation protection)
    final allApps = await getInstalledRideApps();
    final appsToFetch = allApps.where((p) => p != indriverPackage).toList();

    if (appsToFetch.isEmpty) {
      print('No ride apps installed (excluding InDriver)');
      return prices;
    }

    // Clear old prices
    await clearPrices();

    for (final packageName in appsToFetch) {
      final appName = _getAppName(packageName);
      onProgress?.call(appName, 'جاري فتح التطبيق...');

      print('🚀 Starting automation for $appName...');

      // Start full automation (opens app, enters destination, captures price, auto-returns)
      final started = await automateGetPrice(
        packageName: packageName,
        pickup: pickupAddress,
        destination: dropoffAddress,
        pickupLat: pickupLat,
        pickupLng: pickupLng,
        destLat: dropoffLat,
        destLng: dropoffLng,
      );

      if (!started) {
        print('✗ Failed to start automation for $appName');
        onProgress?.call(appName, 'فشل');
        continue;
      }

      onProgress?.call(appName, 'جاري البحث عن السعر...');

      // Wait for automation to complete (with auto-return)
      final price = await waitForAutomationAndGetPrice(packageName);

      if (price != null && price > 0) {
        prices[packageName] = price;
        print('✓ Got price from $appName: $price EGP');
        onProgress?.call(appName, '${price.round()} ج.م ✓');
      } else {
        print('✗ No price from $appName');
        onProgress?.call(appName, 'لم يتم العثور على سعر');
      }

      // Reset automation state for next app
      await resetAutomation();

      // Small delay before next app
      await Future.delayed(const Duration(milliseconds: 500));
    }

    print('✓ Fetched ${prices.length} prices from ${appsToFetch.length} apps');
    return prices;
  }

  // ============ Package Names ============

  static const String uberPackage = 'com.ubercab';
  static const String careemPackage = 'com.careem.acma';
  static const String indriverPackage = 'sinet.startup.inDriver';
  static const String didiPackage = 'com.didiglobal.passenger';
  static const String boltPackage = 'ee.mtakso.client';
}
