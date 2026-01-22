import 'package:flutter_test/flutter_test.dart';
import 'package:go_on/models/ride.dart';

void main() {
  group('Ride', () {
    final sampleRideJson = {
      'id': 'ride-123',
      'user_id': 'user-456',
      'driver_id': 'driver-789',
      'origin_address': 'Cairo, Tahrir Square',
      'origin_location': {
        'type': 'Point',
        'coordinates': [31.2357, 30.0444],
      },
      'origin_name': 'Tahrir Square',
      'destination_address': 'Giza, Pyramids',
      'destination_location': {
        'type': 'Point',
        'coordinates': [31.1342, 29.9792],
      },
      'destination_name': 'Great Pyramids',
      'distance_km': 15.5,
      'estimated_minutes': 35,
      'actual_minutes': 40,
      'source': 'uber',
      'estimated_price': 85.0,
      'final_price': 90.0,
      'currency': 'EGP',
      'payment_method': 'cash',
      'payment_status': 'completed',
      'status': 'completed',
      'requested_at': '2024-01-15T10:00:00Z',
      'accepted_at': '2024-01-15T10:02:00Z',
      'arrived_at': '2024-01-15T10:10:00Z',
      'started_at': '2024-01-15T10:12:00Z',
      'completed_at': '2024-01-15T10:52:00Z',
      'user_rating': 5,
      'driver_rating': 4,
      'created_at': '2024-01-15T10:00:00Z',
      'updated_at': '2024-01-15T10:52:00Z',
    };

    test('should create Ride from JSON', () {
      final ride = Ride.fromJson(sampleRideJson);

      expect(ride.id, equals('ride-123'));
      expect(ride.userId, equals('user-456'));
      expect(ride.driverId, equals('driver-789'));
      expect(ride.originAddress, equals('Cairo, Tahrir Square'));
      expect(ride.destinationAddress, equals('Giza, Pyramids'));
      expect(ride.distanceKm, equals(15.5));
      expect(ride.estimatedMinutes, equals(35));
      expect(ride.source, equals(RideSource.uber));
      expect(ride.status, equals(RideStatus.completed));
    });

    test('should extract coordinates correctly', () {
      final ride = Ride.fromJson(sampleRideJson);

      // Note: coordinates in GeoJSON are [lng, lat]
      expect(ride.originLat, equals(30.0444));
      expect(ride.originLng, equals(31.2357));
      expect(ride.destinationLat, equals(29.9792));
      expect(ride.destinationLng, equals(31.1342));
    });

    test('should convert Ride to JSON', () {
      final ride = Ride.fromJson(sampleRideJson);
      final json = ride.toJson();

      expect(json['id'], equals('ride-123'));
      expect(json['user_id'], equals('user-456'));
      expect(json['distance_km'], equals(15.5));
      expect(json['source'], equals('uber'));
      expect(json['status'], equals('completed'));
    });

    test('should format price correctly', () {
      final ride = Ride.fromJson(sampleRideJson);

      expect(ride.formattedPrice, equals('85 ج.م'));
    });

    test('should format distance correctly', () {
      final ride = Ride.fromJson(sampleRideJson);

      expect(ride.formattedDistance, equals('15.5 كم'));
    });

    test('should format duration correctly', () {
      final ride = Ride.fromJson(sampleRideJson);

      expect(ride.formattedDuration, equals('35 دقيقة'));
    });

    test('should handle missing optional fields', () {
      final minimalJson = {
        'id': 'ride-123',
        'user_id': 'user-456',
        'origin_address': 'Cairo',
        'origin_location': {'type': 'Point', 'coordinates': [31.0, 30.0]},
        'destination_address': 'Giza',
        'destination_location': {'type': 'Point', 'coordinates': [31.1, 29.9]},
        'distance_km': 10.0,
        'estimated_minutes': 20,
        'estimated_price': 50.0,
        'requested_at': '2024-01-15T10:00:00Z',
        'created_at': '2024-01-15T10:00:00Z',
        'updated_at': '2024-01-15T10:00:00Z',
      };

      final ride = Ride.fromJson(minimalJson);

      expect(ride.driverId, isNull);
      expect(ride.finalPrice, isNull);
      expect(ride.source, equals(RideSource.goOn)); // Default
      expect(ride.status, equals(RideStatus.searching)); // Default
      expect(ride.paymentMethod, equals(PaymentMethod.cash)); // Default
    });
  });

  group('RideSource', () {
    test('should convert string to RideSource', () {
      expect(RideSource.fromString('uber'), equals(RideSource.uber));
      expect(RideSource.fromString('careem'), equals(RideSource.careem));
      expect(RideSource.fromString('indriver'), equals(RideSource.indriver));
      expect(RideSource.fromString('go-on'), equals(RideSource.goOn));
      expect(RideSource.fromString('independent'), equals(RideSource.independent));
    });

    test('should return default for unknown source', () {
      expect(RideSource.fromString('unknown'), equals(RideSource.goOn));
    });

    test('should have correct display names', () {
      expect(RideSource.uber.displayName, equals('أوبر'));
      expect(RideSource.careem.displayName, equals('كريم'));
      expect(RideSource.goOn.displayName, equals('GO-ON'));
      expect(RideSource.independent.displayName, equals('سائق مستقل'));
    });
  });

  group('RideStatus', () {
    test('should convert string to RideStatus', () {
      expect(RideStatus.fromString('searching'), equals(RideStatus.searching));
      expect(RideStatus.fromString('accepted'), equals(RideStatus.accepted));
      expect(RideStatus.fromString('arrived'), equals(RideStatus.arrived));
      expect(RideStatus.fromString('started'), equals(RideStatus.started));
      expect(RideStatus.fromString('completed'), equals(RideStatus.completed));
      expect(RideStatus.fromString('cancelled'), equals(RideStatus.cancelled));
    });

    test('should return default for unknown status', () {
      expect(RideStatus.fromString('unknown'), equals(RideStatus.searching));
    });
  });

  group('PaymentMethod', () {
    test('should convert string to PaymentMethod', () {
      expect(PaymentMethod.fromString('cash'), equals(PaymentMethod.cash));
      expect(PaymentMethod.fromString('wallet'), equals(PaymentMethod.wallet));
      expect(PaymentMethod.fromString('card'), equals(PaymentMethod.card));
    });

    test('should return default for unknown method', () {
      expect(PaymentMethod.fromString('unknown'), equals(PaymentMethod.cash));
    });
  });

  group('PaymentStatus', () {
    test('should convert string to PaymentStatus', () {
      expect(PaymentStatus.fromString('pending'), equals(PaymentStatus.pending));
      expect(PaymentStatus.fromString('completed'), equals(PaymentStatus.completed));
      expect(PaymentStatus.fromString('refunded'), equals(PaymentStatus.refunded));
    });

    test('should return default for unknown status', () {
      expect(PaymentStatus.fromString('unknown'), equals(PaymentStatus.pending));
    });
  });
}
