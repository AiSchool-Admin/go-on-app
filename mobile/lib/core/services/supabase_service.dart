import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:supabase_flutter/supabase_flutter.dart';

/// Supabase client provider
final supabaseClientProvider = Provider<SupabaseClient>((ref) {
  return Supabase.instance.client;
});

/// Supabase service for common database operations
class SupabaseService {
  final SupabaseClient _client;

  SupabaseService(this._client);

  SupabaseClient get client => _client;

  // Auth shortcuts
  User? get currentUser => _client.auth.currentUser;
  String? get userId => currentUser?.id;
  bool get isAuthenticated => currentUser != null;

  // Stream of auth state changes
  Stream<AuthState> get authStateChanges => _client.auth.onAuthStateChange;

  // Sign in with phone OTP
  Future<void> signInWithPhone(String phone) async {
    await _client.auth.signInWithOtp(phone: phone);
  }

  // Verify OTP
  Future<AuthResponse> verifyOtp({
    required String phone,
    required String token,
  }) async {
    return await _client.auth.verifyOTP(
      phone: phone,
      token: token,
      type: OtpType.sms,
    );
  }

  // Sign out
  Future<void> signOut() async {
    await _client.auth.signOut();
  }

  // Generic table operations
  Future<List<Map<String, dynamic>>> getAll(String table) async {
    final response = await _client.from(table).select();
    return List<Map<String, dynamic>>.from(response);
  }

  Future<Map<String, dynamic>?> getById(String table, String id) async {
    final response = await _client.from(table).select().eq('id', id).single();
    return response;
  }

  Future<Map<String, dynamic>> insert(
    String table,
    Map<String, dynamic> data,
  ) async {
    final response = await _client.from(table).insert(data).select().single();
    return response;
  }

  Future<Map<String, dynamic>> update(
    String table,
    String id,
    Map<String, dynamic> data,
  ) async {
    final response = await _client
        .from(table)
        .update(data)
        .eq('id', id)
        .select()
        .single();
    return response;
  }

  Future<void> delete(String table, String id) async {
    await _client.from(table).delete().eq('id', id);
  }

  // Realtime subscription
  Stream<List<Map<String, dynamic>>> watchTable(
    String table, {
    String? column,
    dynamic value,
  }) {
    var stream = _client.from(table).stream(primaryKey: ['id']);
    if (column != null && value != null) {
      stream = stream.eq(column, value);
    }
    return stream;
  }
}

/// Supabase service provider
final supabaseServiceProvider = Provider<SupabaseService>((ref) {
  final client = ref.watch(supabaseClientProvider);
  return SupabaseService(client);
});
