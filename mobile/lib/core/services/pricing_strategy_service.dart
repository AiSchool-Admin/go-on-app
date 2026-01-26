import 'dart:convert';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:http/http.dart' as http;
import 'package:google_maps_flutter/google_maps_flutter.dart';
import 'package:shared_preferences/shared_preferences.dart';

import 'native_services.dart';
import 'supabase_service.dart';
import '../utils/app_logger.dart';

/// Pricing mode enum
enum PricingMode {
  /// Fetch prices from server (emulator-based)
  server,

  /// Fetch prices on client device (accessibility service)
  client,

  /// Use estimated prices only (formula-based)
  estimated,

  /// Try server first, fallback to client, then estimated
  hybrid,

  /// Automatic selection based on availability
  auto,
}

/// Result from a pricing request
class PricingResult {
  final bool success;
  final String source; // 'server', 'client', 'estimated', 'cache'
  final Map<String, AppPriceInfo> prices;
  final String? bestApp;
  final double? bestPrice;
  final int durationMs;
  final String? error;
  final DateTime fetchedAt;
  final bool isFromCache;

  PricingResult({
    required this.success,
    required this.source,
    required this.prices,
    this.bestApp,
    this.bestPrice,
    required this.durationMs,
    this.error,
    DateTime? fetchedAt,
    this.isFromCache = false,
  }) : fetchedAt = fetchedAt ?? DateTime.now();

  factory PricingResult.fromServerResponse(Map<String, dynamic> json) {
    final pricesJson = json['prices'] as Map<String, dynamic>? ?? {};
    final prices = <String, AppPriceInfo>{};

    pricesJson.forEach((key, value) {
      if (value is Map<String, dynamic>) {
        prices[key] = AppPriceInfo.fromJson(value);
      }
    });

    return PricingResult(
      success: json['success'] ?? true,
      source: json['source'] ?? 'server',
      prices: prices,
      bestApp: json['bestApp'],
      bestPrice: json['bestPrice']?.toDouble(),
      durationMs: json['duration'] ?? 0,
      error: json['error'],
      isFromCache: json['source'] == 'cache',
    );
  }
}

/// Price info for a single app
class AppPriceInfo {
  final String app;
  final double price;
  final double? surge;
  final int? eta;
  final String? vehicleType;
  final bool isEstimated;
  final int? reliability;
  final Map<String, double>? vehiclePrices;

  AppPriceInfo({
    required this.app,
    required this.price,
    this.surge,
    this.eta,
    this.vehicleType,
    this.isEstimated = false,
    this.reliability,
    this.vehiclePrices,
  });

  factory AppPriceInfo.fromJson(Map<String, dynamic> json) {
    Map<String, double>? vehiclePrices;
    if (json['vehiclePrices'] != null) {
      vehiclePrices = {};
      (json['vehiclePrices'] as Map<String, dynamic>).forEach((key, value) {
        vehiclePrices![key] = (value as num).toDouble();
      });
    }

    return AppPriceInfo(
      app: json['app'] ?? '',
      price: (json['price'] ?? 0).toDouble(),
      surge: json['surge']?.toDouble(),
      eta: json['eta'],
      vehicleType: json['vehicle_type'] ?? json['vehicleType'],
      isEstimated: json['is_estimated'] ?? json['isEstimated'] ?? false,
      reliability: json['reliability'],
      vehiclePrices: vehiclePrices,
    );
  }

  Map<String, dynamic> toJson() => {
        'app': app,
        'price': price,
        'surge': surge,
        'eta': eta,
        'vehicleType': vehicleType,
        'isEstimated': isEstimated,
        'reliability': reliability,
        'vehiclePrices': vehiclePrices,
      };
}

/// Pricing Strategy Service
/// Supports dual-mode pricing: server-side and client-side
class PricingStrategyService {
  final NativeServicesManager _nativeServices;
  final String _backendUrl;

  PricingMode _currentMode = PricingMode.auto;
  bool _serverAvailable = true;
  DateTime? _lastServerCheck;

  // Cache settings
  static const _cacheKey = 'pricing_mode';
  static const _serverCheckInterval = Duration(minutes: 5);

  PricingStrategyService(this._nativeServices, this._backendUrl);

  /// Get current pricing mode
  PricingMode get currentMode => _currentMode;

  /// Set pricing mode
  Future<void> setMode(PricingMode mode) async {
    _currentMode = mode;
    final prefs = await SharedPreferences.getInstance();
    await prefs.setString(_cacheKey, mode.name);
    AppLogger.info('Pricing mode set to: ${mode.name}');
  }

  /// Load saved mode from preferences
  Future<void> loadSavedMode() async {
    try {
      final prefs = await SharedPreferences.getInstance();
      final savedMode = prefs.getString(_cacheKey);
      if (savedMode != null) {
        _currentMode = PricingMode.values.firstWhere(
          (m) => m.name == savedMode,
          orElse: () => PricingMode.auto,
        );
      }
    } catch (e) {
      AppLogger.info('Could not load saved pricing mode: $e');
    }
  }

  /// Get prices for a route
  Future<PricingResult> getPrices({
    required LatLng origin,
    required LatLng destination,
    String? originAddress,
    String? destAddress,
    PricingMode? mode,
    bool forceRefresh = false,
    Function(String app, String status)? onProgress,
  }) async {
    final startTime = DateTime.now();
    final effectiveMode = mode ?? _currentMode;

    AppLogger.info('Getting prices with mode: ${effectiveMode.name}');

    try {
      switch (effectiveMode) {
        case PricingMode.server:
          return await _fetchFromServer(
            origin: origin,
            destination: destination,
            originAddress: originAddress,
            destAddress: destAddress,
            forceRefresh: forceRefresh,
          );

        case PricingMode.client:
          return await _fetchFromClient(
            origin: origin,
            destination: destination,
            originAddress: originAddress,
            destAddress: destAddress,
            onProgress: onProgress,
          );

        case PricingMode.estimated:
          return await _getEstimatedPrices(
            origin: origin,
            destination: destination,
          );

        case PricingMode.hybrid:
          return await _fetchHybrid(
            origin: origin,
            destination: destination,
            originAddress: originAddress,
            destAddress: destAddress,
            forceRefresh: forceRefresh,
            onProgress: onProgress,
          );

        case PricingMode.auto:
          return await _fetchAuto(
            origin: origin,
            destination: destination,
            originAddress: originAddress,
            destAddress: destAddress,
            forceRefresh: forceRefresh,
            onProgress: onProgress,
          );
      }
    } catch (e) {
      AppLogger.info('Pricing error: $e');
      // Fallback to estimated prices
      return await _getEstimatedPrices(
        origin: origin,
        destination: destination,
        error: e.toString(),
      );
    }
  }

  /// Fetch prices from server
  Future<PricingResult> _fetchFromServer({
    required LatLng origin,
    required LatLng destination,
    String? originAddress,
    String? destAddress,
    bool forceRefresh = false,
  }) async {
    final startTime = DateTime.now();

    try {
      final response = await http
          .post(
            Uri.parse('$_backendUrl/api/pricing/prices'),
            headers: {'Content-Type': 'application/json'},
            body: jsonEncode({
              'originLat': origin.latitude,
              'originLng': origin.longitude,
              'destLat': destination.latitude,
              'destLng': destination.longitude,
              'originAddress': originAddress,
              'destAddress': destAddress,
              'mode': 'server',
              'forceRefresh': forceRefresh,
            }),
          )
          .timeout(const Duration(seconds: 30));

      if (response.statusCode == 200) {
        final json = jsonDecode(response.body);
        _serverAvailable = true;
        _lastServerCheck = DateTime.now();
        return PricingResult.fromServerResponse(json);
      } else {
        throw Exception('Server error: ${response.statusCode}');
      }
    } catch (e) {
      _serverAvailable = false;
      _lastServerCheck = DateTime.now();
      AppLogger.info('Server fetch failed: $e');
      rethrow;
    }
  }

  /// Fetch prices from client (accessibility service)
  Future<PricingResult> _fetchFromClient({
    required LatLng origin,
    required LatLng destination,
    String? originAddress,
    String? destAddress,
    Function(String app, String status)? onProgress,
  }) async {
    final startTime = DateTime.now();

    // Check if accessibility is enabled
    final isEnabled = await _nativeServices.isAccessibilityEnabled();
    if (!isEnabled) {
      throw Exception('Accessibility service not enabled');
    }

    // Use the native automation to fetch prices
    final pricesMap = await _nativeServices.fetchAllPricesWithAutomation(
      pickupLat: origin.latitude,
      pickupLng: origin.longitude,
      dropoffLat: destination.latitude,
      dropoffLng: destination.longitude,
      pickupAddress: originAddress ?? '',
      dropoffAddress: destAddress ?? '',
      onProgress: onProgress,
    );

    // Convert to AppPriceInfo map
    final prices = <String, AppPriceInfo>{};
    String? bestApp;
    double? bestPrice;

    pricesMap.forEach((packageName, price) {
      final appName = _packageToAppName(packageName);
      prices[appName] = AppPriceInfo(
        app: appName,
        price: price,
        isEstimated: false,
      );

      if (bestPrice == null || price < bestPrice!) {
        bestPrice = price;
        bestApp = appName;
      }
    });

    final duration = DateTime.now().difference(startTime).inMilliseconds;

    // Save to server cache for other users
    _savePricesToServerCache(
      origin: origin,
      destination: destination,
      originAddress: originAddress,
      destAddress: destAddress,
      prices: prices,
      fetchDurationMs: duration,
    );

    return PricingResult(
      success: prices.isNotEmpty,
      source: 'client',
      prices: prices,
      bestApp: bestApp,
      bestPrice: bestPrice,
      durationMs: duration,
    );
  }

  /// Get estimated prices (formula-based)
  Future<PricingResult> _getEstimatedPrices({
    required LatLng origin,
    required LatLng destination,
    String? error,
  }) async {
    final startTime = DateTime.now();

    try {
      final response = await http
          .post(
            Uri.parse('$_backendUrl/api/pricing/estimated'),
            headers: {'Content-Type': 'application/json'},
            body: jsonEncode({
              'originLat': origin.latitude,
              'originLng': origin.longitude,
              'destLat': destination.latitude,
              'destLng': destination.longitude,
            }),
          )
          .timeout(const Duration(seconds: 10));

      if (response.statusCode == 200) {
        final json = jsonDecode(response.body);
        final result = PricingResult.fromServerResponse(json);
        return PricingResult(
          success: result.success,
          source: 'estimated',
          prices: result.prices,
          bestApp: result.bestApp,
          bestPrice: result.bestPrice,
          durationMs: DateTime.now().difference(startTime).inMilliseconds,
          error: error,
        );
      }
    } catch (e) {
      AppLogger.info('Server estimated prices failed, using local: $e');
    }

    // Fallback to local calculation
    return _calculateLocalEstimates(origin, destination, error);
  }

  /// Calculate local estimates (offline fallback)
  PricingResult _calculateLocalEstimates(
    LatLng origin,
    LatLng destination,
    String? error,
  ) {
    final distanceKm = _calculateDistance(origin, destination);
    final hour = DateTime.now().hour;
    final isRushHour = (hour >= 7 && hour <= 9) || (hour >= 17 && hour <= 19);
    final surge = isRushHour ? 1.3 : 1.0;

    final prices = <String, AppPriceInfo>{
      'uber': AppPriceInfo(
        app: 'uber',
        price: ((15 + distanceKm * 4.5 + 5) * surge).roundToDouble(),
        surge: surge,
        eta: 3,
        isEstimated: true,
        reliability: 95,
      ),
      'careem': AppPriceInfo(
        app: 'careem',
        price: ((12 + distanceKm * 4.0 + 3) * surge).roundToDouble(),
        surge: surge,
        eta: 5,
        isEstimated: true,
        reliability: 90,
      ),
      'indriver': AppPriceInfo(
        app: 'indriver',
        price: ((10 + distanceKm * 3.5) * surge).roundToDouble(),
        surge: surge,
        eta: 8,
        isEstimated: true,
        reliability: 85,
      ),
      'bolt': AppPriceInfo(
        app: 'bolt',
        price: ((12 + distanceKm * 3.8 + 2) * surge).roundToDouble(),
        surge: surge,
        eta: 4,
        isEstimated: true,
        reliability: 88,
      ),
      'didi': AppPriceInfo(
        app: 'didi',
        price: ((11 + distanceKm * 3.6 + 2) * surge).roundToDouble(),
        surge: surge,
        eta: 6,
        isEstimated: true,
        reliability: 82,
      ),
    };

    // Find best price
    String? bestApp;
    double? bestPrice;
    prices.forEach((app, info) {
      if (bestPrice == null || info.price < bestPrice!) {
        bestPrice = info.price;
        bestApp = app;
      }
    });

    return PricingResult(
      success: true,
      source: 'estimated_local',
      prices: prices,
      bestApp: bestApp,
      bestPrice: bestPrice,
      durationMs: 0,
      error: error,
    );
  }

  /// Hybrid mode: try server first, then client, then estimated
  Future<PricingResult> _fetchHybrid({
    required LatLng origin,
    required LatLng destination,
    String? originAddress,
    String? destAddress,
    bool forceRefresh = false,
    Function(String app, String status)? onProgress,
  }) async {
    // Step 1: Try server (fast if cached)
    try {
      final serverResult = await _fetchFromServer(
        origin: origin,
        destination: destination,
        originAddress: originAddress,
        destAddress: destAddress,
        forceRefresh: forceRefresh,
      );

      // If we got real prices (not just estimated), return them
      if (serverResult.success &&
          serverResult.source != 'estimated' &&
          serverResult.prices.isNotEmpty) {
        return serverResult;
      }
    } catch (e) {
      AppLogger.info('Hybrid: Server failed, trying client');
    }

    // Step 2: Try client (if accessibility is enabled)
    try {
      final isEnabled = await _nativeServices.isAccessibilityEnabled();
      if (isEnabled) {
        return await _fetchFromClient(
          origin: origin,
          destination: destination,
          originAddress: originAddress,
          destAddress: destAddress,
          onProgress: onProgress,
        );
      }
    } catch (e) {
      AppLogger.info('Hybrid: Client failed, using estimates');
    }

    // Step 3: Fallback to estimated
    return await _getEstimatedPrices(
      origin: origin,
      destination: destination,
    );
  }

  /// Auto mode: select best available method
  Future<PricingResult> _fetchAuto({
    required LatLng origin,
    required LatLng destination,
    String? originAddress,
    String? destAddress,
    bool forceRefresh = false,
    Function(String app, String status)? onProgress,
  }) async {
    // Check server availability
    await _checkServerAvailability();

    if (_serverAvailable) {
      // Try server first for speed
      try {
        final result = await _fetchFromServer(
          origin: origin,
          destination: destination,
          originAddress: originAddress,
          destAddress: destAddress,
          forceRefresh: forceRefresh,
        );

        if (result.success && result.prices.isNotEmpty) {
          return result;
        }
      } catch (e) {
        AppLogger.info('Auto: Server not available');
      }
    }

    // Check if client fetching is possible
    final isEnabled = await _nativeServices.isAccessibilityEnabled();
    if (isEnabled) {
      try {
        return await _fetchFromClient(
          origin: origin,
          destination: destination,
          originAddress: originAddress,
          destAddress: destAddress,
          onProgress: onProgress,
        );
      } catch (e) {
        AppLogger.info('Auto: Client fetch failed');
      }
    }

    // Fallback to estimates
    return await _getEstimatedPrices(
      origin: origin,
      destination: destination,
    );
  }

  /// Check server availability
  Future<void> _checkServerAvailability() async {
    // Don't check too frequently
    if (_lastServerCheck != null &&
        DateTime.now().difference(_lastServerCheck!) < _serverCheckInterval) {
      return;
    }

    try {
      final response = await http
          .get(Uri.parse('$_backendUrl/health'))
          .timeout(const Duration(seconds: 5));

      _serverAvailable = response.statusCode == 200;
      _lastServerCheck = DateTime.now();
    } catch (e) {
      _serverAvailable = false;
      _lastServerCheck = DateTime.now();
    }
  }

  /// Save client-fetched prices to server cache
  Future<void> _savePricesToServerCache({
    required LatLng origin,
    required LatLng destination,
    String? originAddress,
    String? destAddress,
    required Map<String, AppPriceInfo> prices,
    required int fetchDurationMs,
  }) async {
    try {
      // Convert prices to JSON format
      final pricesJson = <String, dynamic>{};
      prices.forEach((app, info) {
        pricesJson[app] = info.toJson();
      });

      await http.post(
        Uri.parse('$_backendUrl/api/pricing/cache'),
        headers: {'Content-Type': 'application/json'},
        body: jsonEncode({
          'originLat': origin.latitude,
          'originLng': origin.longitude,
          'destLat': destination.latitude,
          'destLng': destination.longitude,
          'originAddress': originAddress,
          'destAddress': destAddress,
          'prices': pricesJson,
          'fetchSource': 'client',
          'fetchDurationMs': fetchDurationMs,
        }),
      );

      AppLogger.info('Saved prices to server cache');
    } catch (e) {
      // Non-critical, don't throw
      AppLogger.info('Could not save to server cache: $e');
    }
  }

  /// Calculate distance between two points
  double _calculateDistance(LatLng origin, LatLng destination) {
    const earthRadius = 6371.0; // km
    final dLat = _toRadians(destination.latitude - origin.latitude);
    final dLng = _toRadians(destination.longitude - origin.longitude);

    final a = _sin(dLat / 2) * _sin(dLat / 2) +
        _cos(_toRadians(origin.latitude)) *
            _cos(_toRadians(destination.latitude)) *
            _sin(dLng / 2) *
            _sin(dLng / 2);

    final c = 2 * _atan2(_sqrt(a), _sqrt(1 - a));
    return earthRadius * c * 1.2; // Add 20% for road distance
  }

  double _toRadians(double deg) => deg * 3.141592653589793 / 180;
  double _sin(double x) => x - (x * x * x) / 6 + (x * x * x * x * x) / 120;
  double _cos(double x) => 1 - (x * x) / 2 + (x * x * x * x) / 24;
  double _sqrt(double x) {
    if (x <= 0) return 0;
    double guess = x / 2;
    for (int i = 0; i < 10; i++) {
      guess = (guess + x / guess) / 2;
    }
    return guess;
  }

  double _atan2(double y, double x) {
    if (x > 0) return _atan(y / x);
    if (x < 0 && y >= 0) return _atan(y / x) + 3.141592653589793;
    if (x < 0 && y < 0) return _atan(y / x) - 3.141592653589793;
    if (x == 0 && y > 0) return 3.141592653589793 / 2;
    if (x == 0 && y < 0) return -3.141592653589793 / 2;
    return 0;
  }

  double _atan(double x) => x - (x * x * x) / 3 + (x * x * x * x * x) / 5;

  /// Convert package name to app name
  String _packageToAppName(String packageName) {
    switch (packageName) {
      case 'com.ubercab':
        return 'uber';
      case 'com.careem.acma':
        return 'careem';
      case 'sinet.startup.inDriver':
        return 'indriver';
      case 'com.didiglobal.passenger':
        return 'didi';
      case 'ee.mtakso.client':
        return 'bolt';
      default:
        return packageName;
    }
  }

  /// Get server status
  bool get isServerAvailable => _serverAvailable;

  /// Check if client pricing is available
  Future<bool> isClientPricingAvailable() async {
    return await _nativeServices.isAccessibilityEnabled();
  }
}

/// Provider for PricingStrategyService
final pricingStrategyProvider = Provider<PricingStrategyService>((ref) {
  final nativeServices = ref.watch(nativeServicesProvider);
  // Use environment variable or default
  const backendUrl = String.fromEnvironment(
    'BACKEND_URL',
    defaultValue: 'https://go-on-backend.railway.app',
  );
  return PricingStrategyService(nativeServices, backendUrl);
});

/// Provider for current pricing mode
final pricingModeProvider = StateProvider<PricingMode>((ref) {
  return PricingMode.auto;
});
