import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

/// Test helper utilities for GO-ON tests

/// Creates a testable widget wrapped with necessary providers
Widget createTestableWidget(Widget child, {List<Override>? overrides}) {
  return ProviderScope(
    overrides: overrides ?? [],
    child: MaterialApp(
      locale: const Locale('ar'),
      home: Directionality(
        textDirection: TextDirection.rtl,
        child: Scaffold(body: child),
      ),
    ),
  );
}

/// Creates a MaterialApp wrapper for widget tests
Widget wrapWithMaterialApp(Widget child) {
  return MaterialApp(
    home: Scaffold(body: child),
  );
}

/// Mock data generators for tests
class MockData {
  static Map<String, dynamic> createRideJson({
    String? id,
    String? userId,
    String? status,
    double? distanceKm,
    double? estimatedPrice,
  }) {
    return {
      'id': id ?? 'test-ride-${DateTime.now().millisecondsSinceEpoch}',
      'user_id': userId ?? 'test-user-123',
      'origin_address': 'Cairo, Test Location',
      'origin_location': {'type': 'Point', 'coordinates': [31.2357, 30.0444]},
      'destination_address': 'Giza, Test Destination',
      'destination_location': {'type': 'Point', 'coordinates': [31.1342, 29.9792]},
      'distance_km': distanceKm ?? 10.0,
      'estimated_minutes': 25,
      'estimated_price': estimatedPrice ?? 85.0,
      'source': 'go-on',
      'status': status ?? 'searching',
      'payment_method': 'cash',
      'payment_status': 'pending',
      'requested_at': DateTime.now().toIso8601String(),
      'created_at': DateTime.now().toIso8601String(),
      'updated_at': DateTime.now().toIso8601String(),
    };
  }

  static Map<String, dynamic> createPriceOptionJson({
    String? source,
    double? price,
    int? etaMinutes,
  }) {
    return {
      'source': source ?? 'uber',
      'price': price ?? 85.0,
      'eta_minutes': etaMinutes ?? 5,
      'is_available': true,
    };
  }

  static Map<String, dynamic> createUserProfileJson({
    String? id,
    String? phone,
    String? name,
  }) {
    return {
      'id': id ?? 'test-user-123',
      'phone': phone ?? '+201234567890',
      'full_name': name ?? 'Test User',
      'email': null,
      'avatar_url': null,
      'default_payment_method': 'cash',
      'language': 'ar',
      'is_active': true,
      'created_at': DateTime.now().toIso8601String(),
      'updated_at': DateTime.now().toIso8601String(),
    };
  }
}

/// Custom test matchers
class PriceMatcher extends Matcher {
  final double minPrice;
  final double maxPrice;

  PriceMatcher({required this.minPrice, required this.maxPrice});

  @override
  Description describe(Description description) {
    return description.add('price between $minPrice and $maxPrice');
  }

  @override
  bool matches(item, Map matchState) {
    if (item is double) {
      return item >= minPrice && item <= maxPrice;
    }
    return false;
  }
}

/// Helper to check if price is in reasonable range
Matcher isReasonablePrice(double basePrice, {double tolerance = 0.3}) {
  return PriceMatcher(
    minPrice: basePrice * (1 - tolerance),
    maxPrice: basePrice * (1 + tolerance),
  );
}
