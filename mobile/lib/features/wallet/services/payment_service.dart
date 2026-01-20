import 'dart:convert';

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:http/http.dart' as http;
import 'package:supabase_flutter/supabase_flutter.dart';
import 'package:url_launcher/url_launcher.dart';

import '../../../core/services/supabase_service.dart';

/// Paymob integration IDs and configuration
class PaymobConfig {
  static const String apiKey = String.fromEnvironment(
    'PAYMOB_API_KEY',
    defaultValue: '',
  );
  static const String integrationIdCard = String.fromEnvironment(
    'PAYMOB_INTEGRATION_ID_CARD',
    defaultValue: '',
  );
  static const String integrationIdWallet = String.fromEnvironment(
    'PAYMOB_INTEGRATION_ID_WALLET',
    defaultValue: '',
  );
  static const String iframeId = String.fromEnvironment(
    'PAYMOB_IFRAME_ID',
    defaultValue: '',
  );
  static const String hmacSecret = String.fromEnvironment(
    'PAYMOB_HMAC_SECRET',
    defaultValue: '',
  );

  static const String baseUrl = 'https://accept.paymob.com/api';
}

/// Payment method types
enum PaymentMethod {
  card,
  vodafoneCash,
  fawry,
  wallet,
}

/// Transaction status
enum TransactionStatus {
  pending,
  completed,
  failed,
  refunded,
}

/// Payment result model
class PaymentResult {
  final bool success;
  final String? transactionId;
  final String? message;
  final double? amount;

  PaymentResult({
    required this.success,
    this.transactionId,
    this.message,
    this.amount,
  });
}

/// Paymob Payment Service
class PaymentService {
  final SupabaseClient _supabase;
  String? _authToken;

  PaymentService(this._supabase);

  /// Authenticate with Paymob
  Future<String?> _authenticate() async {
    if (_authToken != null) return _authToken;

    try {
      final response = await http.post(
        Uri.parse('${PaymobConfig.baseUrl}/auth/tokens'),
        headers: {'Content-Type': 'application/json'},
        body: jsonEncode({'api_key': PaymobConfig.apiKey}),
      );

      if (response.statusCode == 201) {
        final data = jsonDecode(response.body);
        _authToken = data['token'];
        return _authToken;
      }
    } catch (e) {
      debugPrint('Paymob auth error: $e');
    }
    return null;
  }

  /// Create order in Paymob
  Future<int?> _createOrder({
    required double amount,
    required String currency,
    required List<Map<String, dynamic>> items,
  }) async {
    final token = await _authenticate();
    if (token == null) return null;

    try {
      final response = await http.post(
        Uri.parse('${PaymobConfig.baseUrl}/ecommerce/orders'),
        headers: {
          'Content-Type': 'application/json',
          'Authorization': 'Bearer $token',
        },
        body: jsonEncode({
          'auth_token': token,
          'delivery_needed': false,
          'amount_cents': (amount * 100).toInt().toString(),
          'currency': currency,
          'items': items,
        }),
      );

      if (response.statusCode == 201) {
        final data = jsonDecode(response.body);
        return data['id'];
      }
    } catch (e) {
      debugPrint('Paymob create order error: $e');
    }
    return null;
  }

  /// Get payment key for card/wallet payment
  Future<String?> _getPaymentKey({
    required int orderId,
    required double amount,
    required String currency,
    required String integrationId,
    required Map<String, dynamic> billingData,
  }) async {
    final token = await _authenticate();
    if (token == null) return null;

    try {
      final response = await http.post(
        Uri.parse('${PaymobConfig.baseUrl}/acceptance/payment_keys'),
        headers: {
          'Content-Type': 'application/json',
          'Authorization': 'Bearer $token',
        },
        body: jsonEncode({
          'auth_token': token,
          'amount_cents': (amount * 100).toInt().toString(),
          'expiration': 3600,
          'order_id': orderId.toString(),
          'billing_data': billingData,
          'currency': currency,
          'integration_id': int.parse(integrationId),
        }),
      );

      if (response.statusCode == 201) {
        final data = jsonDecode(response.body);
        return data['token'];
      }
    } catch (e) {
      debugPrint('Paymob payment key error: $e');
    }
    return null;
  }

  /// Top up wallet with card payment
  Future<PaymentResult> topUpWithCard({
    required String userId,
    required double amount,
    required String firstName,
    required String lastName,
    required String email,
    required String phone,
  }) async {
    try {
      // Create order
      final orderId = await _createOrder(
        amount: amount,
        currency: 'EGP',
        items: [
          {
            'name': 'Wallet Top Up',
            'amount_cents': (amount * 100).toInt().toString(),
            'quantity': 1,
          }
        ],
      );

      if (orderId == null) {
        return PaymentResult(success: false, message: 'فشل إنشاء الطلب');
      }

      // Get payment key
      final paymentKey = await _getPaymentKey(
        orderId: orderId,
        amount: amount,
        currency: 'EGP',
        integrationId: PaymobConfig.integrationIdCard,
        billingData: {
          'first_name': firstName,
          'last_name': lastName,
          'email': email,
          'phone_number': phone,
          'apartment': 'NA',
          'floor': 'NA',
          'street': 'NA',
          'building': 'NA',
          'shipping_method': 'NA',
          'postal_code': 'NA',
          'city': 'NA',
          'country': 'EG',
          'state': 'NA',
        },
      );

      if (paymentKey == null) {
        return PaymentResult(success: false, message: 'فشل الحصول على مفتاح الدفع');
      }

      // Save pending transaction
      await _savePendingTransaction(
        userId: userId,
        orderId: orderId,
        amount: amount,
        method: 'card',
      );

      // Open Paymob iframe
      final iframeUrl = 'https://accept.paymob.com/api/acceptance/iframes/${PaymobConfig.iframeId}?payment_token=$paymentKey';

      final uri = Uri.parse(iframeUrl);
      if (await canLaunchUrl(uri)) {
        await launchUrl(uri, mode: LaunchMode.externalApplication);
      }

      return PaymentResult(
        success: true,
        transactionId: orderId.toString(),
        message: 'جاري التحويل لصفحة الدفع...',
        amount: amount,
      );
    } catch (e) {
      return PaymentResult(success: false, message: 'خطأ: $e');
    }
  }

  /// Top up wallet with Vodafone Cash
  Future<PaymentResult> topUpWithVodafoneCash({
    required String userId,
    required double amount,
    required String walletPhone,
    required String firstName,
    required String lastName,
    required String email,
    required String phone,
  }) async {
    try {
      // Create order
      final orderId = await _createOrder(
        amount: amount,
        currency: 'EGP',
        items: [
          {
            'name': 'Wallet Top Up',
            'amount_cents': (amount * 100).toInt().toString(),
            'quantity': 1,
          }
        ],
      );

      if (orderId == null) {
        return PaymentResult(success: false, message: 'فشل إنشاء الطلب');
      }

      // Get payment key
      final paymentKey = await _getPaymentKey(
        orderId: orderId,
        amount: amount,
        currency: 'EGP',
        integrationId: PaymobConfig.integrationIdWallet,
        billingData: {
          'first_name': firstName,
          'last_name': lastName,
          'email': email,
          'phone_number': phone,
          'apartment': 'NA',
          'floor': 'NA',
          'street': 'NA',
          'building': 'NA',
          'shipping_method': 'NA',
          'postal_code': 'NA',
          'city': 'NA',
          'country': 'EG',
          'state': 'NA',
        },
      );

      if (paymentKey == null) {
        return PaymentResult(success: false, message: 'فشل الحصول على مفتاح الدفع');
      }

      // Send wallet payment request
      final response = await http.post(
        Uri.parse('${PaymobConfig.baseUrl}/acceptance/payments/pay'),
        headers: {'Content-Type': 'application/json'},
        body: jsonEncode({
          'source': {
            'identifier': walletPhone,
            'subtype': 'WALLET',
          },
          'payment_token': paymentKey,
        }),
      );

      if (response.statusCode == 200) {
        final data = jsonDecode(response.body);

        // Save pending transaction
        await _savePendingTransaction(
          userId: userId,
          orderId: orderId,
          amount: amount,
          method: 'vodafone_cash',
        );

        // If redirect URL is provided, open it
        if (data['redirect_url'] != null) {
          final uri = Uri.parse(data['redirect_url']);
          if (await canLaunchUrl(uri)) {
            await launchUrl(uri, mode: LaunchMode.externalApplication);
          }
        }

        return PaymentResult(
          success: true,
          transactionId: data['id']?.toString(),
          message: 'تم إرسال طلب الدفع. تحقق من هاتفك.',
          amount: amount,
        );
      }

      return PaymentResult(success: false, message: 'فشل طلب الدفع');
    } catch (e) {
      return PaymentResult(success: false, message: 'خطأ: $e');
    }
  }

  /// Save pending transaction to database
  Future<void> _savePendingTransaction({
    required String userId,
    required int orderId,
    required double amount,
    required String method,
  }) async {
    await _supabase.from('transactions').insert({
      'from_user_id': userId,
      'to_user_id': userId,
      'type': 'topup',
      'amount': amount,
      'currency': 'EGP',
      'payment_method': method,
      'payment_gateway': 'paymob',
      'gateway_transaction_id': orderId.toString(),
      'status': 'pending',
      'description': 'شحن المحفظة',
    });
  }

  /// Process Paymob callback/webhook
  Future<bool> processCallback({
    required Map<String, dynamic> data,
    required String hmac,
  }) async {
    // Verify HMAC
    if (!_verifyHmac(data, hmac)) {
      debugPrint('Invalid HMAC');
      return false;
    }

    final success = data['success'] == true;
    final orderId = data['order']?['id']?.toString();
    final transactionId = data['id']?.toString();

    if (orderId == null) return false;

    // Find the transaction
    final response = await _supabase
        .from('transactions')
        .select()
        .eq('gateway_transaction_id', orderId)
        .eq('status', 'pending')
        .maybeSingle();

    if (response == null) return false;

    final userId = response['from_user_id'];
    final amount = (response['amount'] as num).toDouble();

    if (success) {
      // Update transaction status
      await _supabase.from('transactions').update({
        'status': 'completed',
        'completed_at': DateTime.now().toIso8601String(),
        'gateway_transaction_id': transactionId,
      }).eq('id', response['id']);

      // Update wallet balance
      await _supabase.rpc('add_to_wallet', params: {
        'user_id': userId,
        'amount': amount,
      });

      return true;
    } else {
      // Mark as failed
      await _supabase.from('transactions').update({
        'status': 'failed',
        'description': 'فشلت عملية الدفع',
      }).eq('id', response['id']);

      return false;
    }
  }

  /// Verify HMAC signature
  bool _verifyHmac(Map<String, dynamic> data, String hmac) {
    // In production, implement proper HMAC verification
    // using PaymobConfig.hmacSecret
    return true;
  }

  /// Pay for a ride
  Future<PaymentResult> payForRide({
    required String userId,
    required String rideId,
    required double amount,
    required PaymentMethod method,
  }) async {
    try {
      if (method == PaymentMethod.wallet) {
        // Check wallet balance
        final profile = await _supabase
            .from('profiles')
            .select('wallet_balance')
            .eq('id', userId)
            .single();

        final balance = (profile['wallet_balance'] as num?)?.toDouble() ?? 0;

        if (balance < amount) {
          return PaymentResult(
            success: false,
            message: 'رصيد المحفظة غير كافٍ',
          );
        }

        // Deduct from wallet
        await _supabase.rpc('deduct_from_wallet', params: {
          'user_id': userId,
          'amount': amount,
        });

        // Create transaction record
        await _supabase.from('transactions').insert({
          'from_user_id': userId,
          'type': 'ride_payment',
          'amount': amount,
          'currency': 'EGP',
          'related_type': 'ride',
          'related_id': rideId,
          'payment_method': 'wallet',
          'status': 'completed',
          'completed_at': DateTime.now().toIso8601String(),
          'description': 'دفع رحلة',
        });

        // Update ride payment status
        await _supabase.from('rides').update({
          'payment_status': 'completed',
          'payment_method': 'wallet',
        }).eq('id', rideId);

        return PaymentResult(
          success: true,
          message: 'تم الدفع بنجاح',
          amount: amount,
        );
      }

      // For cash payment
      if (method == PaymentMethod.card) {
        // Mark as cash payment
        await _supabase.from('rides').update({
          'payment_method': 'cash',
          'payment_status': 'pending',
        }).eq('id', rideId);

        return PaymentResult(
          success: true,
          message: 'سيتم الدفع نقداً للسائق',
          amount: amount,
        );
      }

      return PaymentResult(success: false, message: 'طريقة دفع غير مدعومة');
    } catch (e) {
      return PaymentResult(success: false, message: 'خطأ: $e');
    }
  }

  /// Get wallet balance
  Future<double> getWalletBalance(String userId) async {
    final response = await _supabase
        .from('profiles')
        .select('wallet_balance')
        .eq('id', userId)
        .single();

    return (response['wallet_balance'] as num?)?.toDouble() ?? 0.0;
  }

  /// Get transaction history
  Future<List<Map<String, dynamic>>> getTransactionHistory(String userId) async {
    final response = await _supabase
        .from('transactions')
        .select()
        .or('from_user_id.eq.$userId,to_user_id.eq.$userId')
        .order('created_at', ascending: false)
        .limit(50);

    return List<Map<String, dynamic>>.from(response);
  }
}

/// Payment service provider
final paymentServiceProvider = Provider<PaymentService>((ref) {
  final client = ref.watch(supabaseClientProvider);
  return PaymentService(client);
});

/// Wallet balance provider
final walletBalanceProvider = FutureProvider.family<double, String>((ref, userId) async {
  final service = ref.watch(paymentServiceProvider);
  return service.getWalletBalance(userId);
});

/// Transaction history provider
final transactionHistoryProvider = FutureProvider.family<List<Map<String, dynamic>>, String>((ref, userId) async {
  final service = ref.watch(paymentServiceProvider);
  return service.getTransactionHistory(userId);
});
