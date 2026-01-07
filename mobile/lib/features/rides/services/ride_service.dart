import 'dart:math';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:supabase_flutter/supabase_flutter.dart';
import 'package:google_maps_flutter/google_maps_flutter.dart';

import '../../../core/services/supabase_service.dart';
import '../../../core/services/native_services.dart';
import '../models/driver_model.dart';
import '../models/price_option.dart';
import 'egypt_pricing_service.dart';

/// Service for ride-related operations
class RideService {
  final SupabaseClient _client;
  final NativeServicesManager _nativeServices;

  RideService(this._client, this._nativeServices);

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

    // Add 20% for road distance vs straight line
    return earthRadius * c * 1.20;
  }

  double _toRadians(double degree) => degree * pi / 180;

  /// Calculate estimated time in minutes based on distance
  int calculateEstimatedMinutes(double distanceKm) {
    // Average speed in Cairo traffic varies by time
    final hour = DateTime.now().hour;
    double avgSpeed;

    // Rush hours - slower
    if ((hour >= 7 && hour < 10) || (hour >= 16 && hour < 20)) {
      avgSpeed = 18.0; // km/h in traffic
    }
    // Night - faster
    else if (hour >= 22 || hour < 6) {
      avgSpeed = 35.0;
    }
    // Normal hours
    else {
      avgSpeed = 25.0;
    }

    return ((distanceKm / avgSpeed) * 60).round();
  }

  /// Calculate approximate price for independent drivers
  double calculateIndependentDriverPrice(double distanceKm) {
    final estimatedMinutes = calculateEstimatedMinutes(distanceKm);
    return EgyptPricingService.calculateIndependentPrice(
      distanceKm: distanceKm,
      estimatedMinutes: estimatedMinutes,
      tripTime: DateTime.now(),
    );
  }

  /// Find nearby drivers from Supabase
  Future<List<DriverModel>> findNearbyDrivers({
    required LatLng userLocation,
    double radiusKm = 10,
  }) async {
    try {
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
  /// Uses accurate Egyptian pricing formulas
  Future<List<PriceOption>> getPriceComparison({
    required LatLng origin,
    required LatLng destination,
  }) async {
    final distanceKm = calculateDistance(origin, destination);
    final estimatedMinutes = calculateEstimatedMinutes(distanceKm);
    final now = DateTime.now();

    // Get nearby drivers for independent option
    final nearbyDrivers = await findNearbyDrivers(userLocation: origin);

    // Get calculated prices using Egyptian pricing formulas
    final calculatedPrices = EgyptPricingService.getAllPrices(
      distanceKm: distanceKm,
      estimatedMinutes: estimatedMinutes,
      tripTime: now,
    );

    // Try to get real prices from accessibility service (if available)
    final realPrices = await _nativeServices.getLatestPrices();
    final realPricesMap = <String, double>{};
    for (final price in realPrices) {
      if (price.price > 0) {
        realPricesMap[price.packageName] = price.price;
      }
    }

    final options = <PriceOption>[];

    // Check surge pricing
    final hasSurge = EgyptPricingService.hasSurgePricing(now);
    final surgeDescription = EgyptPricingService.getSurgeDescription(now);

    // 1. GO-ON Independent Drivers (cheapest)
    if (nearbyDrivers.isNotEmpty) {
      final independentPrice = calculatedPrices['independent']!.price;
      final bestDriver = nearbyDrivers.first;

      options.add(PriceOption(
        id: 'independent',
        name: 'سائق مستقل',
        provider: 'GO-ON',
        price: independentPrice,
        currency: 'EGP',
        estimatedMinutes: estimatedMinutes,
        etaMinutes: 5,
        rating: bestDriver.rating,
        totalRides: bestDriver.totalRides,
        driverName: bestDriver.name,
        driverPhone: bestDriver.phone,
        vehicleInfo: '${bestDriver.vehicleMake} ${bestDriver.vehicleModel}',
        vehicleColor: bestDriver.vehicleColor,
        isAvailable: true,
        isBestPrice: true,
        isEstimate: false, // Our direct price
      ));

      _nativeServices.setGoonBestPrice(independentPrice);
    }

    // 2. DiDi - Usually cheapest app
    final didiRealPrice = realPricesMap[NativeServicesManager.didiPackage];
    final didiCalcPrice = calculatedPrices['didi']!.price;
    options.add(PriceOption(
      id: 'didi',
      name: 'ديدي',
      provider: 'DiDi',
      price: didiRealPrice ?? didiCalcPrice,
      currency: 'EGP',
      estimatedMinutes: estimatedMinutes,
      etaMinutes: 5,
      isAvailable: true,
      isEstimate: didiRealPrice == null,
      category: 'DiDi Express',
      surgeMultiplier: calculatedPrices['didi']!.surge,
    ));

    // 3. InDriver
    final indriverRealPrice = realPricesMap[NativeServicesManager.indriverPackage];
    final indriverCalcPrice = calculatedPrices['indriver']!.price;
    options.add(PriceOption(
      id: 'indriver',
      name: 'إندرايف',
      provider: 'InDriver',
      price: indriverRealPrice ?? indriverCalcPrice,
      currency: 'EGP',
      estimatedMinutes: estimatedMinutes,
      etaMinutes: 4,
      isAvailable: true,
      isEstimate: indriverRealPrice == null,
      category: 'السعر المقترح',
    ));

    // 4. Bolt
    final boltRealPrice = realPricesMap[NativeServicesManager.boltPackage];
    final boltCalcPrice = calculatedPrices['bolt']!.price;
    options.add(PriceOption(
      id: 'bolt',
      name: 'بولت',
      provider: 'Bolt',
      price: boltRealPrice ?? boltCalcPrice,
      currency: 'EGP',
      estimatedMinutes: estimatedMinutes,
      etaMinutes: 4,
      isAvailable: true,
      isEstimate: boltRealPrice == null,
      category: 'Bolt',
      surgeMultiplier: calculatedPrices['bolt']!.surge,
    ));

    // 5. Careem
    final careemRealPrice = realPricesMap[NativeServicesManager.careemPackage];
    final careemCalcPrice = calculatedPrices['careem']!.price;
    options.add(PriceOption(
      id: 'careem',
      name: 'كريم',
      provider: 'Careem',
      price: careemRealPrice ?? careemCalcPrice,
      currency: 'EGP',
      estimatedMinutes: estimatedMinutes,
      etaMinutes: 4,
      isAvailable: true,
      isEstimate: careemRealPrice == null,
      category: 'Go',
      surgeMultiplier: calculatedPrices['careem']!.surge,
    ));

    // 6. Uber - Usually most expensive
    final uberRealPrice = realPricesMap[NativeServicesManager.uberPackage];
    final uberCalcPrice = calculatedPrices['uber']!.price;
    options.add(PriceOption(
      id: 'uber',
      name: 'أوبر',
      provider: 'Uber',
      price: uberRealPrice ?? uberCalcPrice,
      currency: 'EGP',
      estimatedMinutes: estimatedMinutes,
      etaMinutes: 3,
      isAvailable: true,
      isEstimate: uberRealPrice == null,
      category: 'UberX',
      surgeMultiplier: calculatedPrices['uber']!.surge,
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
        phone: '+201094458873',
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
        phone: '+201094458873',
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
  final nativeServices = ref.watch(nativeServicesProvider);
  return RideService(client, nativeServices);
});
