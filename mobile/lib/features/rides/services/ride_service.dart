import 'dart:math';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:supabase_flutter/supabase_flutter.dart';
import 'package:google_maps_flutter/google_maps_flutter.dart';

import '../../../core/services/supabase_service.dart';
import '../models/driver_model.dart';
import '../models/price_option.dart';

/// Service for ride-related operations
class RideService {
  final SupabaseClient _client;

  RideService(this._client);

  /// Calculate distance between two points in km
  double calculateDistance(LatLng origin, LatLng destination) {
    const double earthRadius = 6371; // km

    final dLat = _toRadians(destination.latitude - origin.latitude);
    final dLon = _toRadians(destination.longitude - origin.longitude);

    final a = sin(dLat / 2) * sin(dLat / 2) +
        cos(_toRadians(origin.latitude)) *
            cos(_toRadians(destination.latitude)) *
            sin(dLon / 2) *
            sin(dLon / 2);

    final c = 2 * atan2(sqrt(a), sqrt(1 - a));
    return earthRadius * c;
  }

  double _toRadians(double degree) => degree * pi / 180;

  /// Calculate estimated time in minutes based on distance
  int calculateEstimatedMinutes(double distanceKm) {
    // Average speed in Cairo traffic: ~25 km/h
    const averageSpeedKmH = 25.0;
    return ((distanceKm / averageSpeedKmH) * 60).round();
  }

  /// Calculate price for independent driver
  double calculateIndependentDriverPrice(double distanceKm) {
    // Pricing from app_config
    const baseFare = 15.0;
    const perKmRate = 3.5;
    const minimumFare = 20.0;

    final price = baseFare + (distanceKm * perKmRate);
    return price < minimumFare ? minimumFare : price;
  }

  /// Get pricing config from Supabase
  Future<Map<String, dynamic>> getPricingConfig() async {
    try {
      final response = await _client
          .from('app_config')
          .select('value')
          .eq('key', 'pricing')
          .single();
      return response['value'] as Map<String, dynamic>;
    } catch (e) {
      // Return default pricing if config not found
      return {
        'base_fare': 15,
        'per_km_rate': 3.5,
        'per_minute_rate': 0.5,
        'minimum_fare': 20,
        'commission_rate': 0.15,
      };
    }
  }

  /// Find nearby drivers from Supabase
  Future<List<DriverModel>> findNearbyDrivers({
    required LatLng userLocation,
    double radiusKm = 10,
  }) async {
    try {
      // Call the PostgreSQL function
      final response = await _client.rpc('find_nearby_drivers', params: {
        'user_location':
            'POINT(${userLocation.longitude} ${userLocation.latitude})',
        'radius_km': radiusKm,
        'service_type': 'rides',
      });

      if (response == null) return [];

      return (response as List)
          .map((json) => DriverModel.fromJson(json))
          .toList();
    } catch (e) {
      print('Error finding nearby drivers: $e');
      // Return mock data if database not set up
      return _getMockDrivers(userLocation);
    }
  }

  /// Get all verified online drivers
  Future<List<DriverModel>> getOnlineDrivers() async {
    try {
      final response = await _client
          .from('drivers')
          .select('*, vehicles(*)')
          .eq('is_online', true)
          .eq('is_verified', true);

      return (response as List)
          .map((json) => DriverModel.fromSupabase(json))
          .toList();
    } catch (e) {
      print('Error getting online drivers: $e');
      return [];
    }
  }

  /// Get price comparison for a route
  Future<List<PriceOption>> getPriceComparison({
    required LatLng origin,
    required LatLng destination,
  }) async {
    final distanceKm = calculateDistance(origin, destination);
    final estimatedMinutes = calculateEstimatedMinutes(distanceKm);

    // Get nearby drivers for independent option
    final nearbyDrivers = await findNearbyDrivers(userLocation: origin);

    final options = <PriceOption>[];

    // 1. Independent Drivers (from our database)
    if (nearbyDrivers.isNotEmpty) {
      final independentPrice = calculateIndependentDriverPrice(distanceKm);
      final bestDriver = nearbyDrivers.first;

      options.add(PriceOption(
        id: 'independent',
        name: 'سائق مستقل',
        provider: 'GO-ON',
        price: independentPrice,
        currency: 'EGP',
        estimatedMinutes: estimatedMinutes,
        etaMinutes: 5, // Based on nearest driver
        rating: bestDriver.rating,
        totalRides: bestDriver.totalRides,
        driverName: bestDriver.name,
        driverPhone: bestDriver.phone,
        vehicleInfo: '${bestDriver.vehicleMake} ${bestDriver.vehicleModel}',
        vehicleColor: bestDriver.vehicleColor,
        isAvailable: true,
        isBestPrice: true,
      ));
    }

    // 2. Estimated prices for other apps (based on our pricing model + markup)
    // In real implementation, these would come from OCR/Accessibility Services

    // InDrive estimate (usually 10-15% more than independent)
    options.add(PriceOption(
      id: 'indriver',
      name: 'إندرايف',
      provider: 'InDriver',
      price: calculateIndependentDriverPrice(distanceKm) * 1.15,
      currency: 'EGP',
      estimatedMinutes: estimatedMinutes,
      etaMinutes: 4,
      isAvailable: true,
      isEstimate: true,
      category: 'Economy',
    ));

    // Careem estimate (usually 30-40% more than independent)
    options.add(PriceOption(
      id: 'careem',
      name: 'كريم',
      provider: 'Careem',
      price: calculateIndependentDriverPrice(distanceKm) * 1.38,
      currency: 'EGP',
      estimatedMinutes: estimatedMinutes,
      etaMinutes: 4,
      isAvailable: true,
      isEstimate: true,
      category: 'Go',
    ));

    // Uber estimate (usually 40-50% more than independent)
    options.add(PriceOption(
      id: 'uber',
      name: 'أوبر',
      provider: 'Uber',
      price: calculateIndependentDriverPrice(distanceKm) * 1.46,
      currency: 'EGP',
      estimatedMinutes: estimatedMinutes,
      etaMinutes: 3,
      isAvailable: true,
      isEstimate: true,
      category: 'UberX',
      discount: 14,
    ));

    // Sort by price
    options.sort((a, b) => a.price.compareTo(b.price));

    // Mark best price
    if (options.isNotEmpty) {
      options[0] = options[0].copyWith(isBestPrice: true);
    }

    return options;
  }

  /// Create a new ride request
  Future<String?> createRide({
    required String userId,
    required LatLng origin,
    required LatLng destination,
    required String originAddress,
    required String destinationAddress,
    required double estimatedPrice,
    String? driverId,
    String source = 'independent',
  }) async {
    try {
      final distanceKm = calculateDistance(origin, destination);
      final estimatedMinutes = calculateEstimatedMinutes(distanceKm);

      final response = await _client.from('rides').insert({
        'user_id': userId,
        'driver_id': driverId,
        'origin_address': originAddress,
        'origin_location': 'POINT(${origin.longitude} ${origin.latitude})',
        'destination_address': destinationAddress,
        'destination_location':
            'POINT(${destination.longitude} ${destination.latitude})',
        'distance_km': distanceKm,
        'estimated_minutes': estimatedMinutes,
        'estimated_price': estimatedPrice,
        'source': source,
        'status': 'searching',
      }).select('id').single();

      return response['id'] as String;
    } catch (e) {
      print('Error creating ride: $e');
      return null;
    }
  }

  /// Mock drivers for testing when database is not available
  List<DriverModel> _getMockDrivers(LatLng userLocation) {
    return [
      DriverModel(
        id: 'mock-1',
        name: 'أحمد محمد',
        phone: '+201001234567',
        rating: 4.8,
        totalRides: 230,
        distanceKm: 0.5,
        vehicleType: 'car',
        vehicleMake: 'Toyota',
        vehicleModel: 'Corolla',
        vehicleColor: 'أبيض',
        plateNumber: 'ق س ط 1234',
      ),
      DriverModel(
        id: 'mock-2',
        name: 'محمود علي',
        phone: '+201002345678',
        rating: 4.9,
        totalRides: 180,
        distanceKm: 1.2,
        vehicleType: 'car',
        vehicleMake: 'Hyundai',
        vehicleModel: 'Elantra',
        vehicleColor: 'فضي',
        plateNumber: 'ن ص ر 5678',
      ),
    ];
  }
}

/// Provider for RideService
final rideServiceProvider = Provider<RideService>((ref) {
  final client = ref.watch(supabaseClientProvider);
  return RideService(client);
});
