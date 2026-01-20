import 'dart:io';

import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:supabase_flutter/supabase_flutter.dart';

import '../../../core/services/supabase_service.dart';

class DriverService {
  final SupabaseClient _client;

  DriverService(this._client);

  /// Get current user's driver profile
  Future<Map<String, dynamic>?> getDriverProfile(String userId) async {
    final response = await _client
        .from('drivers')
        .select('*, vehicles(*)')
        .eq('user_id', userId)
        .maybeSingle();
    return response;
  }

  /// Check if user is already registered as driver
  Future<bool> isDriverRegistered(String userId) async {
    final response = await _client
        .from('drivers')
        .select('id')
        .eq('user_id', userId)
        .maybeSingle();
    return response != null;
  }

  /// Register new driver
  Future<Map<String, dynamic>> registerDriver({
    required String userId,
    required String name,
    required String phone,
    String? whatsappNumber,
    required String nationalId,
    required String nationalIdImageUrl,
    required String licenseNumber,
    required String licenseImageUrl,
    required DateTime licenseExpiry,
    bool serviceRides = true,
    bool serviceFreight = false,
    bool serviceIntercity = false,
  }) async {
    // First update user profile to mark as driver
    await _client.from('profiles').update({
      'is_driver': true,
      'user_type': 'driver',
    }).eq('id', userId);

    // Create driver record
    final driverData = {
      'user_id': userId,
      'name': name,
      'phone': phone,
      'whatsapp_number': whatsappNumber ?? phone,
      'national_id': nationalId,
      'national_id_image': nationalIdImageUrl,
      'license_number': licenseNumber,
      'license_image': licenseImageUrl,
      'license_expiry': licenseExpiry.toIso8601String().split('T')[0],
      'service_rides': serviceRides,
      'service_freight': serviceFreight,
      'service_intercity': serviceIntercity,
      'is_verified': false,
      'is_online': false,
    };

    final response = await _client
        .from('drivers')
        .insert(driverData)
        .select()
        .single();

    return response;
  }

  /// Register vehicle for driver
  Future<Map<String, dynamic>> registerVehicle({
    required String driverId,
    required String type,
    required String category,
    required String make,
    required String model,
    required int year,
    required String color,
    required String plateNumber,
    required String registrationNumber,
    required String registrationImageUrl,
    required DateTime registrationExpiry,
    int passengerCapacity = 4,
    double? cargoCapacityKg,
    String? insuranceImageUrl,
    DateTime? insuranceExpiry,
  }) async {
    final vehicleData = {
      'driver_id': driverId,
      'type': type,
      'category': category,
      'make': make,
      'model': model,
      'year': year,
      'color': color,
      'plate_number': plateNumber,
      'registration_number': registrationNumber,
      'registration_image': registrationImageUrl,
      'registration_expiry': registrationExpiry.toIso8601String().split('T')[0],
      'passenger_capacity': passengerCapacity,
      'cargo_capacity_kg': cargoCapacityKg,
      'insurance_image': insuranceImageUrl,
      'insurance_expiry': insuranceExpiry?.toIso8601String().split('T')[0],
      'is_active': true,
      'is_verified': false,
    };

    final response = await _client
        .from('vehicles')
        .insert(vehicleData)
        .select()
        .single();

    return response;
  }

  /// Upload document image
  Future<String> uploadDocument({
    required String userId,
    required String documentType,
    required File file,
  }) async {
    final fileExt = file.path.split('.').last;
    final fileName = '${userId}_${documentType}_${DateTime.now().millisecondsSinceEpoch}.$fileExt';
    final filePath = 'driver_documents/$userId/$fileName';

    await _client.storage.from('documents').upload(
          filePath,
          file,
          fileOptions: const FileOptions(cacheControl: '3600', upsert: true),
        );

    final publicUrl = _client.storage.from('documents').getPublicUrl(filePath);

    return publicUrl;
  }

  /// Update driver online status
  Future<void> setOnlineStatus(String driverId, bool isOnline) async {
    await _client.from('drivers').update({
      'is_online': isOnline,
      if (!isOnline) 'last_location_update': DateTime.now().toIso8601String(),
    }).eq('id', driverId);
  }

  /// Update driver location
  Future<void> updateLocation({
    required String driverId,
    required double latitude,
    required double longitude,
  }) async {
    await _client.from('drivers').update({
      'current_location': 'POINT($longitude $latitude)',
      'last_location_update': DateTime.now().toIso8601String(),
    }).eq('id', driverId);
  }

  /// Get available rides for driver
  Future<List<Map<String, dynamic>>> getAvailableRides({
    required double latitude,
    required double longitude,
    double radiusKm = 5.0,
  }) async {
    final response = await _client.rpc('find_rides_for_driver', params: {
      'driver_lat': latitude,
      'driver_lng': longitude,
      'radius_km': radiusKm,
    });

    return List<Map<String, dynamic>>.from(response ?? []);
  }

  /// Accept a ride
  Future<void> acceptRide(String rideId, String driverId) async {
    await _client.from('rides').update({
      'driver_id': driverId,
      'status': 'accepted',
      'accepted_at': DateTime.now().toIso8601String(),
    }).eq('id', rideId).eq('status', 'searching');
  }

  /// Get driver's pending earnings
  Future<double> getPendingEarnings(String driverId) async {
    final response = await _client
        .from('drivers')
        .select('pending_earnings')
        .eq('id', driverId)
        .single();

    return (response['pending_earnings'] as num?)?.toDouble() ?? 0.0;
  }

  /// Get driver's completed rides
  Future<List<Map<String, dynamic>>> getCompletedRides(String driverId) async {
    final response = await _client
        .from('rides')
        .select('*, profiles!rides_user_id_fkey(*)')
        .eq('driver_id', driverId)
        .eq('status', 'completed')
        .order('completed_at', ascending: false)
        .limit(20);

    return List<Map<String, dynamic>>.from(response);
  }
}

/// Driver service provider
final driverServiceProvider = Provider<DriverService>((ref) {
  final client = ref.watch(supabaseClientProvider);
  return DriverService(client);
});
