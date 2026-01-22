import 'package:flutter_test/flutter_test.dart';
import 'package:go_on/models/user_profile.dart';

void main() {
  group('UserProfile', () {
    final sampleJson = {
      'id': 'user-123',
      'phone': '+201234567890',
      'full_name': 'أحمد محمد',
      'email': 'ahmed@example.com',
      'avatar_url': 'https://example.com/avatar.jpg',
      'default_payment_method': 'cash',
      'wallet_balance': 150.0,
      'total_rides': 25,
      'total_shipments': 5,
      'rating': 4.8,
      'language': 'ar',
      'notification_enabled': true,
      'is_active': true,
      'created_at': '2024-01-01T10:00:00Z',
      'updated_at': '2024-01-15T10:00:00Z',
    };

    test('should create UserProfile from JSON', () {
      final profile = UserProfile.fromJson(sampleJson);

      expect(profile.id, equals('user-123'));
      expect(profile.phone, equals('+201234567890'));
      expect(profile.fullName, equals('أحمد محمد'));
      expect(profile.email, equals('ahmed@example.com'));
      expect(profile.defaultPaymentMethod, equals(PaymentMethodType.cash));
      expect(profile.walletBalance, equals(150.0));
      expect(profile.totalRides, equals(25));
      expect(profile.rating, equals(4.8));
      expect(profile.language, equals('ar'));
      expect(profile.isActive, isTrue);
    });

    test('should convert UserProfile to JSON', () {
      final profile = UserProfile.fromJson(sampleJson);
      final json = profile.toJson();

      expect(json['id'], equals('user-123'));
      expect(json['phone'], equals('+201234567890'));
      expect(json['full_name'], equals('أحمد محمد'));
      expect(json['default_payment_method'], equals('cash'));
    });

    test('should handle missing optional fields', () {
      final minimalJson = {
        'id': 'user-456',
        'phone': '+201111111111',
        'created_at': '2024-01-01T10:00:00Z',
        'updated_at': '2024-01-01T10:00:00Z',
      };

      final profile = UserProfile.fromJson(minimalJson);

      expect(profile.id, equals('user-456'));
      expect(profile.fullName, isNull);
      expect(profile.email, isNull);
      expect(profile.walletBalance, equals(0.0));
      expect(profile.totalRides, equals(0));
      expect(profile.isActive, isTrue); // Default
    });

    test('should format phone number correctly', () {
      final profile = UserProfile.fromJson(sampleJson);

      expect(profile.formattedPhone, equals('0123 456 7890'));
    });

    test('should format wallet balance correctly', () {
      final profile = UserProfile.fromJson(sampleJson);

      expect(profile.formattedWalletBalance, equals('150 ج.م'));
    });

    test('should identify if profile is complete', () {
      final completeProfile = UserProfile.fromJson(sampleJson);
      expect(completeProfile.isProfileComplete, isTrue);

      final incompleteJson = {
        'id': 'user-789',
        'phone': '+201222222222',
        'created_at': '2024-01-01T10:00:00Z',
        'updated_at': '2024-01-01T10:00:00Z',
      };
      final incompleteProfile = UserProfile.fromJson(incompleteJson);
      expect(incompleteProfile.isProfileComplete, isFalse);
    });
  });

  group('PaymentMethodType', () {
    test('should convert string to PaymentMethodType', () {
      expect(PaymentMethodType.fromString('cash'), equals(PaymentMethodType.cash));
      expect(PaymentMethodType.fromString('wallet'), equals(PaymentMethodType.wallet));
      expect(PaymentMethodType.fromString('card'), equals(PaymentMethodType.card));
    });

    test('should return default for unknown type', () {
      expect(PaymentMethodType.fromString('unknown'), equals(PaymentMethodType.cash));
    });

    test('should have correct display names', () {
      expect(PaymentMethodType.cash.displayName, equals('كاش'));
      expect(PaymentMethodType.wallet.displayName, equals('المحفظة'));
      expect(PaymentMethodType.card.displayName, equals('بطاقة'));
    });
  });
}
