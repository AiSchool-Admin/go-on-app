/// Model for driver data
class DriverModel {
  final String id;
  final String name;
  final String phone;
  final String? whatsappNumber;
  final double rating;
  final int totalRides;
  final double distanceKm;
  final String? vehicleType;
  final String? vehicleMake;
  final String? vehicleModel;
  final String? vehicleColor;
  final String? plateNumber;
  final String? avatarUrl;
  final bool isOnline;
  final bool isVerified;

  DriverModel({
    required this.id,
    required this.name,
    required this.phone,
    this.whatsappNumber,
    this.rating = 5.0,
    this.totalRides = 0,
    this.distanceKm = 0,
    this.vehicleType,
    this.vehicleMake,
    this.vehicleModel,
    this.vehicleColor,
    this.plateNumber,
    this.avatarUrl,
    this.isOnline = true,
    this.isVerified = true,
  });

  /// Create from find_nearby_drivers function result
  factory DriverModel.fromJson(Map<String, dynamic> json) {
    return DriverModel(
      id: json['driver_id'] as String,
      name: json['name'] as String,
      phone: '', // Not returned by the function
      rating: (json['rating'] as num?)?.toDouble() ?? 5.0,
      totalRides: 0,
      distanceKm: (json['distance_km'] as num?)?.toDouble() ?? 0,
      vehicleType: json['vehicle_type'] as String?,
    );
  }

  /// Create from full Supabase query with vehicles join
  factory DriverModel.fromSupabase(Map<String, dynamic> json) {
    final vehicle = json['vehicles'] is List && (json['vehicles'] as List).isNotEmpty
        ? (json['vehicles'] as List).first
        : json['vehicles'];

    return DriverModel(
      id: json['id'] as String,
      name: json['name'] as String,
      phone: json['phone'] as String,
      whatsappNumber: json['whatsapp_number'] as String?,
      rating: (json['rating'] as num?)?.toDouble() ?? 5.0,
      totalRides: json['total_rides'] as int? ?? 0,
      avatarUrl: json['avatar_url'] as String?,
      isOnline: json['is_online'] as bool? ?? false,
      isVerified: json['is_verified'] as bool? ?? false,
      vehicleType: vehicle?['type'] as String?,
      vehicleMake: vehicle?['make'] as String?,
      vehicleModel: vehicle?['model'] as String?,
      vehicleColor: vehicle?['color'] as String?,
      plateNumber: vehicle?['plate_number'] as String?,
    );
  }

  String get vehicleInfo {
    if (vehicleMake != null && vehicleModel != null) {
      return '$vehicleMake $vehicleModel';
    }
    return vehicleType ?? 'سيارة';
  }

  String get formattedRating => rating.toStringAsFixed(1);

  String get formattedDistance {
    if (distanceKm < 1) {
      return '${(distanceKm * 1000).round()} م';
    }
    return '${distanceKm.toStringAsFixed(1)} كم';
  }
}
