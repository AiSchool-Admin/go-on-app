import 'package:flutter_test/flutter_test.dart';
import 'package:go_on/models/price_option.dart';
import 'package:go_on/models/ride.dart';

void main() {
  group('PriceOption', () {
    final sampleJson = {
      'source': 'uber',
      'price': 85.0,
      'eta_minutes': 5,
      'vehicle_type': 'UberX',
      'driver_name': 'Ahmed',
      'driver_rating': 4.8,
      'driver_total_rides': 1500,
      'driver_id': 'driver-123',
      'is_available': true,
      'promo_code': 'SAVE10',
      'original_price': 95.0,
    };

    test('should create PriceOption from JSON', () {
      final option = PriceOption.fromJson(sampleJson);

      expect(option.source, equals(RideSource.uber));
      expect(option.price, equals(85.0));
      expect(option.etaMinutes, equals(5));
      expect(option.vehicleType, equals('UberX'));
      expect(option.driverName, equals('Ahmed'));
      expect(option.driverRating, equals(4.8));
      expect(option.driverTotalRides, equals(1500));
      expect(option.isAvailable, isTrue);
      expect(option.promoCode, equals('SAVE10'));
      expect(option.originalPrice, equals(95.0));
    });

    test('should convert PriceOption to JSON', () {
      final option = PriceOption.fromJson(sampleJson);
      final json = option.toJson();

      expect(json['source'], equals('uber'));
      expect(json['price'], equals(85.0));
      expect(json['eta_minutes'], equals(5));
      expect(json['driver_rating'], equals(4.8));
    });

    test('should format price correctly', () {
      final option = PriceOption(
        source: RideSource.uber,
        price: 85.0,
        etaMinutes: 5,
      );

      expect(option.formattedPrice, equals('85 ج.م'));
    });

    test('should format ETA correctly', () {
      final option = PriceOption(
        source: RideSource.uber,
        price: 85.0,
        etaMinutes: 5,
      );

      expect(option.formattedEta, equals('5 د'));
    });

    test('should detect discount correctly', () {
      final withDiscount = PriceOption(
        source: RideSource.uber,
        price: 85.0,
        etaMinutes: 5,
        originalPrice: 100.0,
      );

      final withoutDiscount = PriceOption(
        source: RideSource.uber,
        price: 85.0,
        etaMinutes: 5,
      );

      expect(withDiscount.hasDiscount, isTrue);
      expect(withoutDiscount.hasDiscount, isFalse);
    });

    test('should calculate discount percent correctly', () {
      final option = PriceOption(
        source: RideSource.uber,
        price: 80.0,
        etaMinutes: 5,
        originalPrice: 100.0,
      );

      expect(option.discountPercent, equals(20));
    });

    test('should return 0 discount when no original price', () {
      final option = PriceOption(
        source: RideSource.uber,
        price: 85.0,
        etaMinutes: 5,
      );

      expect(option.discountPercent, equals(0));
    });

    test('should handle default isAvailable value', () {
      final jsonWithoutAvailable = {
        'source': 'uber',
        'price': 85.0,
        'eta_minutes': 5,
      };

      final option = PriceOption.fromJson(jsonWithoutAvailable);
      expect(option.isAvailable, isTrue);
    });
  });

  group('PriceOptionListExt', () {
    test('should sort by price ascending', () {
      final options = [
        PriceOption(source: RideSource.uber, price: 100.0, etaMinutes: 5),
        PriceOption(source: RideSource.careem, price: 80.0, etaMinutes: 4),
        PriceOption(source: RideSource.goOn, price: 60.0, etaMinutes: 3),
      ];

      final sorted = options.sortByPrice();

      expect(sorted[0].price, equals(60.0));
      expect(sorted[1].price, equals(80.0));
      expect(sorted[2].price, equals(100.0));
    });

    test('should mark best price after sorting', () {
      final options = [
        PriceOption(source: RideSource.uber, price: 100.0, etaMinutes: 5),
        PriceOption(source: RideSource.careem, price: 80.0, etaMinutes: 4),
        PriceOption(source: RideSource.goOn, price: 60.0, etaMinutes: 3),
      ];

      final sorted = options.sortByPrice();

      expect(sorted[0].isBestPrice, isTrue);
      expect(sorted[1].isBestPrice, isFalse);
      expect(sorted[2].isBestPrice, isFalse);
    });

    test('should filter available options', () {
      final options = [
        PriceOption(source: RideSource.uber, price: 100.0, etaMinutes: 5, isAvailable: true),
        PriceOption(source: RideSource.careem, price: 80.0, etaMinutes: 4, isAvailable: false),
        PriceOption(source: RideSource.goOn, price: 60.0, etaMinutes: 3, isAvailable: true),
      ];

      final available = options.available;

      expect(available.length, equals(2));
      expect(available.any((o) => o.source == RideSource.careem), isFalse);
    });

    test('should get best price option', () {
      final options = [
        PriceOption(source: RideSource.uber, price: 100.0, etaMinutes: 5),
        PriceOption(source: RideSource.careem, price: 80.0, etaMinutes: 4),
        PriceOption(source: RideSource.goOn, price: 60.0, etaMinutes: 3),
      ];

      final best = options.bestPrice;

      expect(best, isNotNull);
      expect(best!.price, equals(60.0));
      expect(best.source, equals(RideSource.goOn));
    });

    test('should return null for empty list best price', () {
      final options = <PriceOption>[];
      expect(options.bestPrice, isNull);
    });
  });
}
