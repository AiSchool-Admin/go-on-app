import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:shared_preferences/shared_preferences.dart';

import '../core/services/supabase_service.dart';
import '../models/user_profile.dart';
import 'auth_provider.dart';

/// Local storage key for ride sort preference
const _kRideSortPreferenceKey = 'ride_sort_preference';

/// MethodChannel for native communication
const _channel = MethodChannel('com.goon.app/services');

/// Ride sort preference provider - manages user's sorting preference
final rideSortPreferenceProvider =
    StateNotifierProvider<RideSortPreferenceNotifier, RideSortPreference>((ref) {
  final supabase = ref.watch(supabaseServiceProvider);
  final userId = ref.watch(currentUserProvider)?.id;
  return RideSortPreferenceNotifier(supabase, userId);
});

/// Notifier for managing ride sort preference state
class RideSortPreferenceNotifier extends StateNotifier<RideSortPreference> {
  final SupabaseService _supabase;
  final String? _userId;

  RideSortPreferenceNotifier(this._supabase, this._userId)
      : super(RideSortPreference.lowestPrice) {
    _loadPreference();
  }

  /// Load preference from local storage first, then sync with server and native
  Future<void> _loadPreference() async {
    // Load from local storage for instant access
    final prefs = await SharedPreferences.getInstance();
    final localValue = prefs.getString(_kRideSortPreferenceKey);
    if (localValue != null) {
      state = RideSortPreference.fromString(localValue);
    }

    // Sync with server if user is logged in
    if (_userId != null) {
      try {
        final response = await _supabase.client
            .from('profiles')
            .select('ride_sort_preference')
            .eq('id', _userId!)
            .single();

        if (response['ride_sort_preference'] != null) {
          final serverPref =
              RideSortPreference.fromString(response['ride_sort_preference']);
          state = serverPref;
          // Update local storage
          await prefs.setString(_kRideSortPreferenceKey, serverPref.value);
        }
      } catch (e) {
        // Use local value if server fails
        print('Failed to load preference from server: $e');
      }
    }

    // Sync loaded preference to native code
    try {
      await _channel.invokeMethod('setRideSortPreference', {
        'preference': state.value,
      });
    } catch (e) {
      print('Failed to sync initial preference to native: $e');
    }
  }

  /// Update preference - saves to local storage, server, and native code
  Future<void> setPreference(RideSortPreference preference) async {
    // Update state immediately
    state = preference;

    // Save to local storage
    final prefs = await SharedPreferences.getInstance();
    await prefs.setString(_kRideSortPreferenceKey, preference.value);

    // Sync to native code (Kotlin)
    try {
      await _channel.invokeMethod('setRideSortPreference', {
        'preference': preference.value,
      });
    } catch (e) {
      print('Failed to sync preference to native: $e');
    }

    // Sync to server if logged in
    if (_userId != null) {
      try {
        await _supabase.client
            .from('profiles')
            .update({'ride_sort_preference': preference.value})
            .eq('id', _userId!);
      } catch (e) {
        print('Failed to save preference to server: $e');
        // Local storage is already updated, so user won't lose their preference
      }
    }
  }

  /// Get current preference value as string (for Kotlin)
  String get preferenceValue => state.value;
}

/// Provider for getting preference as string (used by native code)
final rideSortPreferenceValueProvider = Provider<String>((ref) {
  return ref.watch(rideSortPreferenceProvider).value;
});
