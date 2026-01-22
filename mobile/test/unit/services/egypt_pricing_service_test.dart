import 'package:flutter_test/flutter_test.dart';
import 'package:go_on/features/rides/services/egypt_pricing_service.dart';

void main() {
  group('EgyptPricingService', () {
    group('calculateUberPrice', () {
      test('should return minimum fare for very short trips', () {
        final price = EgyptPricingService.calculateUberPrice(
          distanceKm: 0.5,
          estimatedMinutes: 2,
          tripTime: DateTime(2024, 1, 15, 14, 0), // 2 PM - no surge
        );

        expect(price, greaterThanOrEqualTo(25.0)); // Minimum fare
      });

      test('should calculate correct price for 10km trip', () {
        final price = EgyptPricingService.calculateUberPrice(
          distanceKm: 10.0,
          estimatedMinutes: 20,
          tripTime: DateTime(2024, 1, 15, 14, 0), // 2 PM - no surge
        );

        // Base (12) + Distance (10 * 4.5 = 45) + Time (20 * 0.9 = 18) + Booking (6) = 81
        // Rounded to nearest 5 = 80
        expect(price, equals(80.0));
      });

      test('should apply surge during morning rush hour', () {
        final normalPrice = EgyptPricingService.calculateUberPrice(
          distanceKm: 10.0,
          estimatedMinutes: 20,
          tripTime: DateTime(2024, 1, 15, 14, 0), // 2 PM
        );

        final surgePrice = EgyptPricingService.calculateUberPrice(
          distanceKm: 10.0,
          estimatedMinutes: 20,
          tripTime: DateTime(2024, 1, 15, 8, 0), // 8 AM - rush hour
        );

        expect(surgePrice, greaterThan(normalPrice));
      });

      test('should apply surge during evening rush hour', () {
        final normalPrice = EgyptPricingService.calculateUberPrice(
          distanceKm: 10.0,
          estimatedMinutes: 20,
          tripTime: DateTime(2024, 1, 15, 14, 0), // 2 PM
        );

        final surgePrice = EgyptPricingService.calculateUberPrice(
          distanceKm: 10.0,
          estimatedMinutes: 20,
          tripTime: DateTime(2024, 1, 15, 18, 0), // 6 PM - rush hour
        );

        expect(surgePrice, greaterThan(normalPrice));
      });

      test('should round price to nearest 5', () {
        final price = EgyptPricingService.calculateUberPrice(
          distanceKm: 5.0,
          estimatedMinutes: 10,
          tripTime: DateTime(2024, 1, 15, 14, 0),
        );

        expect(price % 5, equals(0));
      });
    });

    group('calculateCareemPrice', () {
      test('should return minimum fare for very short trips', () {
        final price = EgyptPricingService.calculateCareemPrice(
          distanceKm: 0.5,
          estimatedMinutes: 2,
          tripTime: DateTime(2024, 1, 15, 14, 0),
        );

        expect(price, greaterThanOrEqualTo(22.0)); // Careem minimum
      });

      test('should be cheaper than Uber for same trip', () {
        final uberPrice = EgyptPricingService.calculateUberPrice(
          distanceKm: 10.0,
          estimatedMinutes: 20,
          tripTime: DateTime(2024, 1, 15, 14, 0),
        );

        final careemPrice = EgyptPricingService.calculateCareemPrice(
          distanceKm: 10.0,
          estimatedMinutes: 20,
          tripTime: DateTime(2024, 1, 15, 14, 0),
        );

        expect(careemPrice, lessThanOrEqualTo(uberPrice));
      });
    });

    group('calculateBoltPrice', () {
      test('should return minimum fare for very short trips', () {
        final price = EgyptPricingService.calculateBoltPrice(
          distanceKm: 0.5,
          estimatedMinutes: 2,
          tripTime: DateTime(2024, 1, 15, 14, 0),
        );

        expect(price, greaterThanOrEqualTo(20.0)); // Bolt minimum
      });

      test('should be cheaper than Uber', () {
        final uberPrice = EgyptPricingService.calculateUberPrice(
          distanceKm: 15.0,
          estimatedMinutes: 30,
          tripTime: DateTime(2024, 1, 15, 14, 0),
        );

        final boltPrice = EgyptPricingService.calculateBoltPrice(
          distanceKm: 15.0,
          estimatedMinutes: 30,
          tripTime: DateTime(2024, 1, 15, 14, 0),
        );

        expect(boltPrice, lessThan(uberPrice));
      });
    });

    group('calculateDiDiPrice', () {
      test('should return minimum fare for very short trips', () {
        final price = EgyptPricingService.calculateDiDiPrice(
          distanceKm: 0.5,
          estimatedMinutes: 2,
          tripTime: DateTime(2024, 1, 15, 14, 0),
        );

        expect(price, greaterThanOrEqualTo(18.0)); // DiDi minimum
      });

      test('should be the cheapest among major apps', () {
        final time = DateTime(2024, 1, 15, 14, 0);

        final uberPrice = EgyptPricingService.calculateUberPrice(
          distanceKm: 10.0,
          estimatedMinutes: 20,
          tripTime: time,
        );

        final careemPrice = EgyptPricingService.calculateCareemPrice(
          distanceKm: 10.0,
          estimatedMinutes: 20,
          tripTime: time,
        );

        final boltPrice = EgyptPricingService.calculateBoltPrice(
          distanceKm: 10.0,
          estimatedMinutes: 20,
          tripTime: time,
        );

        final didiPrice = EgyptPricingService.calculateDiDiPrice(
          distanceKm: 10.0,
          estimatedMinutes: 20,
          tripTime: time,
        );

        expect(didiPrice, lessThanOrEqualTo(uberPrice));
        expect(didiPrice, lessThanOrEqualTo(careemPrice));
        expect(didiPrice, lessThanOrEqualTo(boltPrice));
      });
    });

    group('calculateInDriverPrice', () {
      test('should return minimum fare for very short trips', () {
        final price = EgyptPricingService.calculateInDriverPrice(
          distanceKm: 0.5,
          estimatedMinutes: 2,
          tripTime: DateTime(2024, 1, 15, 14, 0),
        );

        expect(price, greaterThanOrEqualTo(18.0)); // InDriver minimum
      });
    });

    group('calculateIndependentPrice', () {
      test('should return minimum fare for very short trips', () {
        final price = EgyptPricingService.calculateIndependentPrice(
          distanceKm: 0.5,
          estimatedMinutes: 2,
          tripTime: DateTime(2024, 1, 15, 14, 0),
        );

        expect(price, greaterThanOrEqualTo(15.0)); // Independent minimum
      });

      test('should be the cheapest option overall', () {
        final time = DateTime(2024, 1, 15, 14, 0);

        final uberPrice = EgyptPricingService.calculateUberPrice(
          distanceKm: 10.0,
          estimatedMinutes: 20,
          tripTime: time,
        );

        final independentPrice = EgyptPricingService.calculateIndependentPrice(
          distanceKm: 10.0,
          estimatedMinutes: 20,
          tripTime: time,
        );

        expect(independentPrice, lessThan(uberPrice));
      });
    });

    group('getAllPrices', () {
      test('should return prices for all 6 providers', () {
        final prices = EgyptPricingService.getAllPrices(
          distanceKm: 10.0,
          estimatedMinutes: 20,
          tripTime: DateTime(2024, 1, 15, 14, 0),
        );

        expect(prices.length, equals(6));
        expect(prices.containsKey('uber'), isTrue);
        expect(prices.containsKey('careem'), isTrue);
        expect(prices.containsKey('bolt'), isTrue);
        expect(prices.containsKey('didi'), isTrue);
        expect(prices.containsKey('indriver'), isTrue);
        expect(prices.containsKey('independent'), isTrue);
      });

      test('should return valid PriceEstimate objects', () {
        final prices = EgyptPricingService.getAllPrices(
          distanceKm: 10.0,
          estimatedMinutes: 20,
        );

        for (final entry in prices.entries) {
          expect(entry.value.provider, isNotEmpty);
          expect(entry.value.price, greaterThan(0));
          expect(entry.value.surge, greaterThanOrEqualTo(1.0));
        }
      });

      test('GO-ON should be the cheapest option', () {
        final prices = EgyptPricingService.getAllPrices(
          distanceKm: 15.0,
          estimatedMinutes: 30,
          tripTime: DateTime(2024, 1, 15, 14, 0),
        );

        final goOnPrice = prices['independent']!.price;

        for (final entry in prices.entries) {
          if (entry.key != 'independent') {
            expect(goOnPrice, lessThanOrEqualTo(entry.value.price));
          }
        }
      });
    });

    group('hasSurgePricing', () {
      test('should return true during morning rush hour', () {
        expect(
          EgyptPricingService.hasSurgePricing(DateTime(2024, 1, 15, 8, 0)),
          isTrue,
        );
      });

      test('should return true during evening rush hour', () {
        expect(
          EgyptPricingService.hasSurgePricing(DateTime(2024, 1, 15, 18, 0)),
          isTrue,
        );
      });

      test('should return true late at night', () {
        expect(
          EgyptPricingService.hasSurgePricing(DateTime(2024, 1, 15, 23, 30)),
          isTrue,
        );
        expect(
          EgyptPricingService.hasSurgePricing(DateTime(2024, 1, 15, 2, 0)),
          isTrue,
        );
      });

      test('should return false during normal hours', () {
        expect(
          EgyptPricingService.hasSurgePricing(DateTime(2024, 1, 15, 14, 0)),
          isFalse,
        );
      });
    });

    group('getSurgeDescription', () {
      test('should return morning rush description', () {
        final desc = EgyptPricingService.getSurgeDescription(
          DateTime(2024, 1, 15, 8, 0),
        );

        expect(desc, contains('الصباحية'));
      });

      test('should return evening rush description', () {
        final desc = EgyptPricingService.getSurgeDescription(
          DateTime(2024, 1, 15, 18, 0),
        );

        expect(desc, contains('المسائية'));
      });

      test('should return night description', () {
        final desc = EgyptPricingService.getSurgeDescription(
          DateTime(2024, 1, 15, 23, 30),
        );

        expect(desc, contains('الليل'));
      });

      test('should return empty string during normal hours', () {
        final desc = EgyptPricingService.getSurgeDescription(
          DateTime(2024, 1, 15, 14, 0),
        );

        expect(desc, isEmpty);
      });
    });
  });

  group('PriceEstimate', () {
    test('should correctly identify surge', () {
      final withSurge = PriceEstimate(
        provider: 'Uber',
        price: 100.0,
        surge: 1.25,
      );

      final withoutSurge = PriceEstimate(
        provider: 'Uber',
        price: 80.0,
        surge: 1.0,
      );

      expect(withSurge.hasSurge, isTrue);
      expect(withoutSurge.hasSurge, isFalse);
    });

    test('should calculate surge percent correctly', () {
      final estimate = PriceEstimate(
        provider: 'Uber',
        price: 100.0,
        surge: 1.25,
      );

      expect(estimate.surgePercent, equals(25));
    });
  });
}
