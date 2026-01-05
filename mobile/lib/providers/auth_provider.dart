import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:supabase_flutter/supabase_flutter.dart';

import '../core/services/supabase_service.dart';

/// Auth state provider - streams auth changes
final authStateProvider = StreamProvider<User?>((ref) {
  final supabase = ref.watch(supabaseServiceProvider);
  return supabase.authStateChanges.map((state) => state.session?.user);
});

/// Current user provider
final currentUserProvider = Provider<User?>((ref) {
  return ref.watch(authStateProvider).valueOrNull;
});

/// Is authenticated provider
final isAuthenticatedProvider = Provider<bool>((ref) {
  return ref.watch(currentUserProvider) != null;
});

/// Auth notifier for login/logout actions
class AuthNotifier extends StateNotifier<AsyncValue<User?>> {
  final SupabaseService _supabase;

  AuthNotifier(this._supabase) : super(const AsyncValue.loading()) {
    _init();
  }

  void _init() {
    final user = _supabase.currentUser;
    state = AsyncValue.data(user);
  }

  /// Send OTP to phone number
  Future<void> sendOtp(String phone) async {
    state = const AsyncValue.loading();
    try {
      await _supabase.signInWithPhone(phone);
      state = const AsyncValue.data(null); // OTP sent, waiting for verification
    } catch (e, st) {
      state = AsyncValue.error(e, st);
    }
  }

  /// Verify OTP
  Future<void> verifyOtp({
    required String phone,
    required String token,
  }) async {
    state = const AsyncValue.loading();
    try {
      final response = await _supabase.verifyOtp(phone: phone, token: token);
      state = AsyncValue.data(response.user);
    } catch (e, st) {
      state = AsyncValue.error(e, st);
    }
  }

  /// Sign out
  Future<void> signOut() async {
    state = const AsyncValue.loading();
    try {
      await _supabase.signOut();
      state = const AsyncValue.data(null);
    } catch (e, st) {
      state = AsyncValue.error(e, st);
    }
  }
}

/// Auth notifier provider
final authNotifierProvider =
    StateNotifierProvider<AuthNotifier, AsyncValue<User?>>((ref) {
  final supabase = ref.watch(supabaseServiceProvider);
  return AuthNotifier(supabase);
});
