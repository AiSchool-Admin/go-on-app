import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:shared_preferences/shared_preferences.dart';

import '../models/user_profile.dart';

/// Local storage key for ride sort preference
const _kRideSortPreferenceKey = 'ride_sort_preference';

/// MethodChannel for native communication
const _channel = MethodChannel('com.goon.app/services');

/// Ride sort preference provider - simple implementation without auth dependency
final rideSortPreferenceProvider =
    StateNotifierProvider<RideSortPreferenceNotifier, RideSortPreference>((ref) {
  return RideSortPreferenceNotifier();
});

/// Notifier for managing ride sort preference state
class RideSortPreferenceNotifier extends StateNotifier<RideSortPreference> {
  RideSortPreferenceNotifier() : super(RideSortPreference.lowestPrice) {
    _loadPreference();
  }

  /// Load preference from local storage
  Future<void> _loadPreference() async {
    try {
      final prefs = await SharedPreferences.getInstance();
      final localValue = prefs.getString(_kRideSortPreferenceKey);
      if (localValue != null) {
        state = RideSortPreference.fromString(localValue);
        // Sync to native
        await _syncToNative(state);
      }
    } catch (e) {
      print('Failed to load preference: $e');
    }
  }

  /// Sync preference to native Kotlin code
  Future<void> _syncToNative(RideSortPreference preference) async {
    try {
      await _channel.invokeMethod('setRideSortPreference', {
        'preference': preference.value,
      });
    } catch (e) {
      print('Failed to sync preference to native: $e');
    }
  }

  /// Update preference
  Future<void> setPreference(RideSortPreference preference) async {
    state = preference;

    // Save to local storage
    try {
      final prefs = await SharedPreferences.getInstance();
      await prefs.setString(_kRideSortPreferenceKey, preference.value);
    } catch (e) {
      print('Failed to save preference: $e');
    }

    // Sync to native
    await _syncToNative(preference);
  }
}
