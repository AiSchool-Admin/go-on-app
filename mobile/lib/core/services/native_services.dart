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
  final DateTime timestamp;

  AppPrice({
    required this.appName,
    required this.packageName,
    required this.price,
    required this.timestamp,
  });

  factory AppPrice.fromJson(Map<String, dynamic> json) {
    return AppPrice(
      appName: json['appName'] ?? '',
      packageName: json['packageName'] ?? '',
      price: (json['price'] ?? 0).toDouble(),
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

  // ============ Package Names ============

  static const String uberPackage = 'com.ubercab';
  static const String careemPackage = 'com.careem.acma';
  static const String indriverPackage = 'sinet.startup.inDriver';
  static const String didiPackage = 'com.didiglobal.passenger';
  static const String boltPackage = 'ee.mtakso.client';
}
