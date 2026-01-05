import 'ride.dart';

/// Price option from different providers
class PriceOption {
  final RideSource source;
  final double price;
  final int etaMinutes;
  final String? vehicleType;
  final String? driverName;
  final double? driverRating;
  final int? driverTotalRides;
  final String? driverId;
  final bool isAvailable;
  final String? promoCode;
  final double? originalPrice;

  PriceOption({
    required this.source,
    required this.price,
    required this.etaMinutes,
    this.vehicleType,
    this.driverName,
    this.driverRating,
    this.driverTotalRides,
    this.driverId,
    this.isAvailable = true,
    this.promoCode,
    this.originalPrice,
  });

  factory PriceOption.fromJson(Map<String, dynamic> json) {
    return PriceOption(
      source: RideSource.fromString(json['source'] as String),
      price: (json['price'] as num).toDouble(),
      etaMinutes: json['eta_minutes'] as int,
      vehicleType: json['vehicle_type'] as String?,
      driverName: json['driver_name'] as String?,
      driverRating: (json['driver_rating'] as num?)?.toDouble(),
      driverTotalRides: json['driver_total_rides'] as int?,
      driverId: json['driver_id'] as String?,
      isAvailable: json['is_available'] as bool? ?? true,
      promoCode: json['promo_code'] as String?,
      originalPrice: (json['original_price'] as num?)?.toDouble(),
    );
  }

  Map<String, dynamic> toJson() {
    return {
      'source': source.value,
      'price': price,
      'eta_minutes': etaMinutes,
      'vehicle_type': vehicleType,
      'driver_name': driverName,
      'driver_rating': driverRating,
      'driver_total_rides': driverTotalRides,
      'driver_id': driverId,
      'is_available': isAvailable,
      'promo_code': promoCode,
      'original_price': originalPrice,
    };
  }

  /// Formatted price with currency
  String get formattedPrice => '${price.toStringAsFixed(0)} ج.م';

  /// Formatted ETA
  String get formattedEta => '$etaMinutes د';

  /// Has discount
  bool get hasDiscount => originalPrice != null && originalPrice! > price;

  /// Discount percentage
  int get discountPercent {
    if (!hasDiscount) return 0;
    return (((originalPrice! - price) / originalPrice!) * 100).round();
  }

  /// Is best price (should be marked after comparison)
  bool isBestPrice = false;
}

/// Extension to sort price options
extension PriceOptionListExt on List<PriceOption> {
  /// Sort by price ascending and mark best price
  List<PriceOption> sortByPrice() {
    final sorted = [...this]..sort((a, b) => a.price.compareTo(b.price));
    if (sorted.isNotEmpty) {
      sorted.first.isBestPrice = true;
    }
    return sorted;
  }

  /// Get only available options
  List<PriceOption> get available => where((o) => o.isAvailable).toList();

  /// Get best price option
  PriceOption? get bestPrice => isEmpty ? null : sortByPrice().first;
}
