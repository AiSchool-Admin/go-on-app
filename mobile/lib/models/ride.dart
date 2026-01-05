/// Ride model - matches Supabase rides table
class Ride {
  final String id;
  final String userId;
  final String? driverId;
  final String originAddress;
  final double originLat;
  final double originLng;
  final String? originName;
  final String destinationAddress;
  final double destinationLat;
  final double destinationLng;
  final String? destinationName;
  final double distanceKm;
  final int estimatedMinutes;
  final int? actualMinutes;
  final RideSource source;
  final double estimatedPrice;
  final double? finalPrice;
  final String currency;
  final Map<String, dynamic>? priceBreakdown;
  final PaymentMethod paymentMethod;
  final PaymentStatus paymentStatus;
  final RideStatus status;
  final DateTime requestedAt;
  final DateTime? acceptedAt;
  final DateTime? arrivedAt;
  final DateTime? startedAt;
  final DateTime? completedAt;
  final DateTime? cancelledAt;
  final String? cancelledBy;
  final String? cancellationReason;
  final int? userRating;
  final int? driverRating;
  final String? userReview;
  final String? driverReview;
  final DateTime createdAt;
  final DateTime updatedAt;

  Ride({
    required this.id,
    required this.userId,
    this.driverId,
    required this.originAddress,
    required this.originLat,
    required this.originLng,
    this.originName,
    required this.destinationAddress,
    required this.destinationLat,
    required this.destinationLng,
    this.destinationName,
    required this.distanceKm,
    required this.estimatedMinutes,
    this.actualMinutes,
    this.source = RideSource.goOn,
    required this.estimatedPrice,
    this.finalPrice,
    this.currency = 'EGP',
    this.priceBreakdown,
    this.paymentMethod = PaymentMethod.cash,
    this.paymentStatus = PaymentStatus.pending,
    this.status = RideStatus.searching,
    required this.requestedAt,
    this.acceptedAt,
    this.arrivedAt,
    this.startedAt,
    this.completedAt,
    this.cancelledAt,
    this.cancelledBy,
    this.cancellationReason,
    this.userRating,
    this.driverRating,
    this.userReview,
    this.driverReview,
    required this.createdAt,
    required this.updatedAt,
  });

  factory Ride.fromJson(Map<String, dynamic> json) {
    return Ride(
      id: json['id'] as String,
      userId: json['user_id'] as String,
      driverId: json['driver_id'] as String?,
      originAddress: json['origin_address'] as String,
      originLat: _extractLat(json['origin_location']),
      originLng: _extractLng(json['origin_location']),
      originName: json['origin_name'] as String?,
      destinationAddress: json['destination_address'] as String,
      destinationLat: _extractLat(json['destination_location']),
      destinationLng: _extractLng(json['destination_location']),
      destinationName: json['destination_name'] as String?,
      distanceKm: (json['distance_km'] as num).toDouble(),
      estimatedMinutes: json['estimated_minutes'] as int,
      actualMinutes: json['actual_minutes'] as int?,
      source: RideSource.fromString(json['source'] as String? ?? 'go-on'),
      estimatedPrice: (json['estimated_price'] as num).toDouble(),
      finalPrice: (json['final_price'] as num?)?.toDouble(),
      currency: json['currency'] as String? ?? 'EGP',
      priceBreakdown: json['price_breakdown'] as Map<String, dynamic>?,
      paymentMethod:
          PaymentMethod.fromString(json['payment_method'] as String? ?? 'cash'),
      paymentStatus: PaymentStatus.fromString(
          json['payment_status'] as String? ?? 'pending'),
      status: RideStatus.fromString(json['status'] as String? ?? 'searching'),
      requestedAt: DateTime.parse(json['requested_at'] as String),
      acceptedAt: json['accepted_at'] != null
          ? DateTime.parse(json['accepted_at'] as String)
          : null,
      arrivedAt: json['arrived_at'] != null
          ? DateTime.parse(json['arrived_at'] as String)
          : null,
      startedAt: json['started_at'] != null
          ? DateTime.parse(json['started_at'] as String)
          : null,
      completedAt: json['completed_at'] != null
          ? DateTime.parse(json['completed_at'] as String)
          : null,
      cancelledAt: json['cancelled_at'] != null
          ? DateTime.parse(json['cancelled_at'] as String)
          : null,
      cancelledBy: json['cancelled_by'] as String?,
      cancellationReason: json['cancellation_reason'] as String?,
      userRating: json['user_rating'] as int?,
      driverRating: json['driver_rating'] as int?,
      userReview: json['user_review'] as String?,
      driverReview: json['driver_review'] as String?,
      createdAt: DateTime.parse(json['created_at'] as String),
      updatedAt: DateTime.parse(json['updated_at'] as String),
    );
  }

  static double _extractLat(dynamic location) {
    if (location is Map) {
      return (location['coordinates'] as List)[1].toDouble();
    }
    return 0.0;
  }

  static double _extractLng(dynamic location) {
    if (location is Map) {
      return (location['coordinates'] as List)[0].toDouble();
    }
    return 0.0;
  }

  Map<String, dynamic> toJson() {
    return {
      'id': id,
      'user_id': userId,
      'driver_id': driverId,
      'origin_address': originAddress,
      'origin_location': 'POINT($originLng $originLat)',
      'origin_name': originName,
      'destination_address': destinationAddress,
      'destination_location': 'POINT($destinationLng $destinationLat)',
      'destination_name': destinationName,
      'distance_km': distanceKm,
      'estimated_minutes': estimatedMinutes,
      'actual_minutes': actualMinutes,
      'source': source.value,
      'estimated_price': estimatedPrice,
      'final_price': finalPrice,
      'currency': currency,
      'price_breakdown': priceBreakdown,
      'payment_method': paymentMethod.value,
      'payment_status': paymentStatus.value,
      'status': status.value,
    };
  }

  /// Get formatted price with currency
  String get formattedPrice => '${estimatedPrice.toStringAsFixed(0)} ج.م';

  /// Get formatted distance
  String get formattedDistance => '${distanceKm.toStringAsFixed(1)} كم';

  /// Get formatted duration
  String get formattedDuration => '$estimatedMinutes دقيقة';
}

enum RideSource {
  goOn('go-on'),
  uber('uber'),
  careem('careem'),
  indriver('indriver'),
  independent('independent');

  final String value;
  const RideSource(this.value);

  static RideSource fromString(String value) {
    return RideSource.values.firstWhere(
      (e) => e.value == value,
      orElse: () => RideSource.goOn,
    );
  }

  String get displayName {
    switch (this) {
      case RideSource.goOn:
        return 'GO-ON';
      case RideSource.uber:
        return 'أوبر';
      case RideSource.careem:
        return 'كريم';
      case RideSource.indriver:
        return 'إندرايف';
      case RideSource.independent:
        return 'سائق مستقل';
    }
  }
}

enum RideStatus {
  searching('searching'),
  accepted('accepted'),
  arrived('arrived'),
  started('started'),
  completed('completed'),
  cancelled('cancelled');

  final String value;
  const RideStatus(this.value);

  static RideStatus fromString(String value) {
    return RideStatus.values.firstWhere(
      (e) => e.value == value,
      orElse: () => RideStatus.searching,
    );
  }
}

enum PaymentMethod {
  cash('cash'),
  wallet('wallet'),
  card('card');

  final String value;
  const PaymentMethod(this.value);

  static PaymentMethod fromString(String value) {
    return PaymentMethod.values.firstWhere(
      (e) => e.value == value,
      orElse: () => PaymentMethod.cash,
    );
  }
}

enum PaymentStatus {
  pending('pending'),
  completed('completed'),
  refunded('refunded');

  final String value;
  const PaymentStatus(this.value);

  static PaymentStatus fromString(String value) {
    return PaymentStatus.values.firstWhere(
      (e) => e.value == value,
      orElse: () => PaymentStatus.pending,
    );
  }
}
