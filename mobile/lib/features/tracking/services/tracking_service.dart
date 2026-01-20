import 'dart:async';

import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:google_maps_flutter/google_maps_flutter.dart';
import 'package:supabase_flutter/supabase_flutter.dart';

import '../../../core/services/supabase_service.dart';

/// Tracking data model
class TrackingData {
  final String id;
  final String type; // 'ride' or 'shipment'
  final String status;
  final LatLng? currentLocation;
  final LatLng origin;
  final LatLng destination;
  final String originAddress;
  final String destinationAddress;
  final String? driverName;
  final String? driverPhone;
  final double? driverRating;
  final String? driverAvatarUrl;
  final String? vehiclePlate;
  final String? vehicleModel;
  final String? vehicleColor;
  final int? estimatedMinutes;
  final DateTime? estimatedArrival;
  final DateTime? startedAt;
  final DateTime? completedAt;

  TrackingData({
    required this.id,
    required this.type,
    required this.status,
    this.currentLocation,
    required this.origin,
    required this.destination,
    required this.originAddress,
    required this.destinationAddress,
    this.driverName,
    this.driverPhone,
    this.driverRating,
    this.driverAvatarUrl,
    this.vehiclePlate,
    this.vehicleModel,
    this.vehicleColor,
    this.estimatedMinutes,
    this.estimatedArrival,
    this.startedAt,
    this.completedAt,
  });

  factory TrackingData.fromRide(Map<String, dynamic> ride, Map<String, dynamic>? driver, Map<String, dynamic>? vehicle) {
    final originPoint = _parsePoint(ride['origin_location']);
    final destPoint = _parsePoint(ride['destination_location']);
    final currentPoint = driver != null ? _parsePoint(driver['current_location']) : null;

    return TrackingData(
      id: ride['id'],
      type: 'ride',
      status: ride['status'] ?? 'unknown',
      currentLocation: currentPoint,
      origin: originPoint ?? const LatLng(0, 0),
      destination: destPoint ?? const LatLng(0, 0),
      originAddress: ride['origin_address'] ?? '',
      destinationAddress: ride['destination_address'] ?? '',
      driverName: driver?['name'],
      driverPhone: driver?['phone'],
      driverRating: (driver?['rating'] as num?)?.toDouble(),
      driverAvatarUrl: driver?['avatar_url'],
      vehiclePlate: vehicle?['plate_number'],
      vehicleModel: '${vehicle?['make'] ?? ''} ${vehicle?['model'] ?? ''}'.trim(),
      vehicleColor: vehicle?['color'],
      estimatedMinutes: ride['estimated_minutes'],
      startedAt: ride['started_at'] != null ? DateTime.parse(ride['started_at']) : null,
      completedAt: ride['completed_at'] != null ? DateTime.parse(ride['completed_at']) : null,
    );
  }

  factory TrackingData.fromShipment(Map<String, dynamic> shipment, Map<String, dynamic>? driver, Map<String, dynamic>? vehicle) {
    final pickupPoint = _parsePoint(shipment['pickup_location']);
    final deliveryPoint = _parsePoint(shipment['delivery_location']);
    final currentPoint = shipment['current_location'] != null
        ? _parsePoint(shipment['current_location'])
        : (driver != null ? _parsePoint(driver['current_location']) : null);

    return TrackingData(
      id: shipment['id'],
      type: 'shipment',
      status: shipment['status'] ?? 'unknown',
      currentLocation: currentPoint,
      origin: pickupPoint ?? const LatLng(0, 0),
      destination: deliveryPoint ?? const LatLng(0, 0),
      originAddress: shipment['pickup_address'] ?? '',
      destinationAddress: shipment['delivery_address'] ?? '',
      driverName: driver?['name'],
      driverPhone: driver?['phone'],
      driverRating: (driver?['rating'] as num?)?.toDouble(),
      driverAvatarUrl: driver?['avatar_url'],
      vehiclePlate: vehicle?['plate_number'],
      vehicleModel: '${vehicle?['make'] ?? ''} ${vehicle?['model'] ?? ''}'.trim(),
      vehicleColor: vehicle?['color'],
      estimatedMinutes: shipment['estimated_minutes'],
      startedAt: shipment['picked_up_at'] != null ? DateTime.parse(shipment['picked_up_at']) : null,
      completedAt: shipment['delivered_at'] != null ? DateTime.parse(shipment['delivered_at']) : null,
    );
  }

  static LatLng? _parsePoint(dynamic value) {
    if (value == null) return null;
    if (value is String && value.startsWith('POINT(')) {
      // Parse POINT(lng lat) format
      final coords = value.substring(6, value.length - 1).split(' ');
      if (coords.length == 2) {
        final lng = double.tryParse(coords[0]);
        final lat = double.tryParse(coords[1]);
        if (lat != null && lng != null) {
          return LatLng(lat, lng);
        }
      }
    }
    return null;
  }

  String get statusText {
    switch (status) {
      case 'searching':
        return 'جاري البحث عن سائق';
      case 'accepted':
        return 'السائق في الطريق إليك';
      case 'arrived':
        return 'السائق وصل';
      case 'started':
        return 'الرحلة بدأت';
      case 'completed':
        return 'تم التوصيل';
      case 'cancelled':
        return 'ملغية';
      case 'pending':
        return 'قيد الانتظار';
      case 'picked_up':
        return 'تم الاستلام';
      case 'in_transit':
        return 'في الطريق';
      case 'delivered':
        return 'تم التسليم';
      default:
        return status;
    }
  }

  bool get isActive {
    return ['searching', 'accepted', 'arrived', 'started', 'pending', 'picked_up', 'in_transit'].contains(status);
  }
}

/// Tracking service for realtime updates
class TrackingService {
  final SupabaseClient _client;
  final Map<String, RealtimeChannel> _activeChannels = {};

  TrackingService(this._client);

  /// Get initial tracking data for a ride
  Future<TrackingData?> getRideTracking(String rideId) async {
    final ride = await _client
        .from('rides')
        .select()
        .eq('id', rideId)
        .maybeSingle();

    if (ride == null) return null;

    Map<String, dynamic>? driver;
    Map<String, dynamic>? vehicle;

    if (ride['driver_id'] != null) {
      driver = await _client
          .from('drivers')
          .select()
          .eq('id', ride['driver_id'])
          .maybeSingle();

      if (driver != null) {
        vehicle = await _client
            .from('vehicles')
            .select()
            .eq('driver_id', driver['id'])
            .eq('is_active', true)
            .maybeSingle();
      }
    }

    return TrackingData.fromRide(ride, driver, vehicle);
  }

  /// Get initial tracking data for a shipment
  Future<TrackingData?> getShipmentTracking(String shipmentId) async {
    final shipment = await _client
        .from('shipments')
        .select()
        .eq('id', shipmentId)
        .maybeSingle();

    if (shipment == null) return null;

    Map<String, dynamic>? driver;
    Map<String, dynamic>? vehicle;

    if (shipment['driver_id'] != null) {
      driver = await _client
          .from('drivers')
          .select()
          .eq('id', shipment['driver_id'])
          .maybeSingle();

      if (driver != null) {
        vehicle = await _client
            .from('vehicles')
            .select()
            .eq('driver_id', driver['id'])
            .eq('is_active', true)
            .maybeSingle();
      }
    }

    return TrackingData.fromShipment(shipment, driver, vehicle);
  }

  /// Subscribe to ride updates
  Stream<TrackingData?> watchRide(String rideId) {
    final controller = StreamController<TrackingData?>();

    // Get initial data
    getRideTracking(rideId).then((data) {
      if (!controller.isClosed) {
        controller.add(data);
      }
    });

    // Subscribe to realtime updates
    final channel = _client.channel('ride_$rideId');

    channel
        .onPostgresChanges(
          event: PostgresChangeEvent.update,
          schema: 'public',
          table: 'rides',
          filter: PostgresChangeFilter(
            type: PostgresChangeFilterType.eq,
            column: 'id',
            value: rideId,
          ),
          callback: (payload) async {
            final data = await getRideTracking(rideId);
            if (!controller.isClosed) {
              controller.add(data);
            }
          },
        )
        .subscribe();

    _activeChannels['ride_$rideId'] = channel;

    controller.onCancel = () {
      _unsubscribe('ride_$rideId');
    };

    return controller.stream;
  }

  /// Subscribe to shipment updates
  Stream<TrackingData?> watchShipment(String shipmentId) {
    final controller = StreamController<TrackingData?>();

    // Get initial data
    getShipmentTracking(shipmentId).then((data) {
      if (!controller.isClosed) {
        controller.add(data);
      }
    });

    // Subscribe to realtime updates
    final channel = _client.channel('shipment_$shipmentId');

    channel
        .onPostgresChanges(
          event: PostgresChangeEvent.update,
          schema: 'public',
          table: 'shipments',
          filter: PostgresChangeFilter(
            type: PostgresChangeFilterType.eq,
            column: 'id',
            value: shipmentId,
          ),
          callback: (payload) async {
            final data = await getShipmentTracking(shipmentId);
            if (!controller.isClosed) {
              controller.add(data);
            }
          },
        )
        .subscribe();

    _activeChannels['shipment_$shipmentId'] = channel;

    controller.onCancel = () {
      _unsubscribe('shipment_$shipmentId');
    };

    return controller.stream;
  }

  /// Subscribe to driver location updates
  Stream<LatLng?> watchDriverLocation(String driverId) {
    final controller = StreamController<LatLng?>();

    // Subscribe to realtime updates
    final channel = _client.channel('driver_location_$driverId');

    channel
        .onPostgresChanges(
          event: PostgresChangeEvent.update,
          schema: 'public',
          table: 'drivers',
          filter: PostgresChangeFilter(
            type: PostgresChangeFilterType.eq,
            column: 'id',
            value: driverId,
          ),
          callback: (payload) {
            final newData = payload.newRecord;
            final location = TrackingData._parsePoint(newData['current_location']);
            if (!controller.isClosed) {
              controller.add(location);
            }
          },
        )
        .subscribe();

    _activeChannels['driver_location_$driverId'] = channel;

    controller.onCancel = () {
      _unsubscribe('driver_location_$driverId');
    };

    return controller.stream;
  }

  void _unsubscribe(String channelKey) {
    final channel = _activeChannels.remove(channelKey);
    if (channel != null) {
      _client.removeChannel(channel);
    }
  }

  /// Unsubscribe from all channels
  void dispose() {
    for (final key in _activeChannels.keys.toList()) {
      _unsubscribe(key);
    }
  }
}

/// Tracking service provider
final trackingServiceProvider = Provider<TrackingService>((ref) {
  final client = ref.watch(supabaseClientProvider);
  final service = TrackingService(client);

  ref.onDispose(() {
    service.dispose();
  });

  return service;
});

/// Provider for tracking a specific ride
final rideTrackingProvider = StreamProvider.family<TrackingData?, String>((ref, rideId) {
  final service = ref.watch(trackingServiceProvider);
  return service.watchRide(rideId);
});

/// Provider for tracking a specific shipment
final shipmentTrackingProvider = StreamProvider.family<TrackingData?, String>((ref, shipmentId) {
  final service = ref.watch(trackingServiceProvider);
  return service.watchShipment(shipmentId);
});
