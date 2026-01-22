import 'package:flutter_test/flutter_test.dart';
import 'package:integration_test/integration_test.dart';
import 'package:go_on/features/rides/services/egypt_pricing_service.dart';
import 'package:go_on/models/price_option.dart';
import 'package:go_on/models/ride.dart';

/// Integration tests for the price comparison flow
/// These tests verify the complete user journey from
/// entering locations to viewing and selecting prices.
void main() {
  IntegrationTestWidgetsFlutterBinding.ensureInitialized();

  group('Price Comparison Flow', () {
    test('should get prices from all providers for a typical Cairo trip', () {
      // Simulate a 10km, 25 minute trip from Tahrir to Maadi
      final prices = EgyptPricingService.getAllPrices(
        distanceKm: 10.0,
        estimatedMinutes: 25,
        tripTime: DateTime(2024, 1, 15, 14, 0), // Normal time
      );

      // Verify all providers are present
      expect(prices.length, equals(6));

      // Verify price ordering (GO-ON should be cheapest)
      final sortedPrices = prices.values.toList()
        ..sort((a, b) => a.price.compareTo(b.price));

      expect(sortedPrices.first.provider, equals('GO-ON'));
    });

    test('should correctly calculate savings compared to Uber', () {
      final prices = EgyptPricingService.getAllPrices(
        distanceKm: 15.0,
        estimatedMinutes: 35,
        tripTime: DateTime(2024, 1, 15, 14, 0),
      );

      final uberPrice = prices['uber']!.price;
      final goOnPrice = prices['independent']!.price;

      // Should save at least 15%
      final savingsPercent = ((uberPrice - goOnPrice) / uberPrice) * 100;
      expect(savingsPercent, greaterThan(15));
    });

    test('should handle surge pricing during rush hours', () {
      // Normal price at 2 PM
      final normalPrices = EgyptPricingService.getAllPrices(
        distanceKm: 10.0,
        estimatedMinutes: 25,
        tripTime: DateTime(2024, 1, 15, 14, 0),
      );

      // Rush hour price at 6 PM
      final rushPrices = EgyptPricingService.getAllPrices(
        distanceKm: 10.0,
        estimatedMinutes: 25,
        tripTime: DateTime(2024, 1, 15, 18, 0),
      );

      // All prices should be higher during rush hour
      expect(rushPrices['uber']!.price, greaterThan(normalPrices['uber']!.price));
      expect(rushPrices['careem']!.price, greaterThan(normalPrices['careem']!.price));
    });

    test('should sort price options correctly', () {
      final options = [
        PriceOption(source: RideSource.uber, price: 100.0, etaMinutes: 5),
        PriceOption(source: RideSource.careem, price: 90.0, etaMinutes: 4),
        PriceOption(source: RideSource.goOn, price: 70.0, etaMinutes: 6),
        PriceOption(source: RideSource.indriver, price: 75.0, etaMinutes: 3),
      ];

      final sorted = options.sortByPrice();

      expect(sorted[0].source, equals(RideSource.goOn));
      expect(sorted[0].isBestPrice, isTrue);
      expect(sorted.last.source, equals(RideSource.uber));
    });

    test('should filter unavailable options', () {
      final options = [
        PriceOption(source: RideSource.uber, price: 100.0, etaMinutes: 5, isAvailable: true),
        PriceOption(source: RideSource.careem, price: 90.0, etaMinutes: 4, isAvailable: false),
        PriceOption(source: RideSource.goOn, price: 70.0, etaMinutes: 6, isAvailable: true),
      ];

      final available = options.available;

      expect(available.length, equals(2));
      expect(available.every((o) => o.isAvailable), isTrue);
    });
  });

  group('Trip Scenarios', () {
    test('short trip (2km) should respect minimum fares', () {
      final prices = EgyptPricingService.getAllPrices(
        distanceKm: 2.0,
        estimatedMinutes: 8,
      );

      // Uber minimum is 25 EGP
      expect(prices['uber']!.price, greaterThanOrEqualTo(25.0));

      // DiDi minimum is 18 EGP
      expect(prices['didi']!.price, greaterThanOrEqualTo(18.0));
    });

    test('long trip (30km) should calculate correctly', () {
      final prices = EgyptPricingService.getAllPrices(
        distanceKm: 30.0,
        estimatedMinutes: 60,
        tripTime: DateTime(2024, 1, 15, 14, 0),
      );

      // Long trips should show significant price differences
      final uberPrice = prices['uber']!.price;
      final goOnPrice = prices['independent']!.price;

      // Should save more on longer trips
      final savings = uberPrice - goOnPrice;
      expect(savings, greaterThan(30)); // At least 30 EGP savings
    });

    test('weekend pricing should apply correctly', () {
      // Friday evening (weekend in Egypt)
      final weekendPrices = EgyptPricingService.getAllPrices(
        distanceKm: 10.0,
        estimatedMinutes: 25,
        tripTime: DateTime(2024, 1, 12, 20, 0), // Friday 8 PM
      );

      // Wednesday same time (weekday)
      final weekdayPrices = EgyptPricingService.getAllPrices(
        distanceKm: 10.0,
        estimatedMinutes: 25,
        tripTime: DateTime(2024, 1, 10, 20, 0), // Wednesday 8 PM
      );

      // Weekend prices should be higher
      expect(weekendPrices['uber']!.price, greaterThanOrEqualTo(weekdayPrices['uber']!.price));
    });
  });
}
