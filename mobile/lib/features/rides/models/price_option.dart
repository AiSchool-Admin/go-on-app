/// Model for price comparison option
class PriceOption {
  final String id;
  final String name;
  final String provider;
  final double price;
  final String currency;
  final int estimatedMinutes;
  final int etaMinutes;
  final double? rating;
  final int? totalRides;
  final String? driverName;
  final String? driverPhone;
  final String? vehicleInfo;
  final String? vehicleColor;
  final String? category;
  final int? discount;
  final bool isAvailable;
  final bool isBestPrice;
  final bool isEstimate;

  PriceOption({
    required this.id,
    required this.name,
    required this.provider,
    required this.price,
    this.currency = 'EGP',
    this.estimatedMinutes = 0,
    this.etaMinutes = 0,
    this.rating,
    this.totalRides,
    this.driverName,
    this.driverPhone,
    this.vehicleInfo,
    this.vehicleColor,
    this.category,
    this.discount,
    this.isAvailable = true,
    this.isBestPrice = false,
    this.isEstimate = false,
  });

  String get formattedPrice => '${price.round()} ج.م';

  String get formattedEta => '$etaMinutes د';

  String get formattedDuration => '$estimatedMinutes دقيقة';

  String get formattedRating => rating?.toStringAsFixed(1) ?? '-';

  String get providerIcon {
    switch (provider.toLowerCase()) {
      case 'uber':
        return '🚕';
      case 'careem':
        return '🚖';
      case 'indriver':
        return '🚗';
      case 'didi':
        return '🚙';
      case 'bolt':
        return '⚡';
      case 'go-on':
        return '🚐';
      default:
        return '🚗';
    }
  }

  /// Get deep link to open the provider app
  String? getDeepLink({
    required double originLat,
    required double originLng,
    required double destLat,
    required double destLng,
  }) {
    switch (provider.toLowerCase()) {
      case 'uber':
        return 'uber://?action=setPickup&pickup[latitude]=$originLat&pickup[longitude]=$originLng&dropoff[latitude]=$destLat&dropoff[longitude]=$destLng';
      case 'careem':
        return 'careem://booking?pickup_lat=$originLat&pickup_lng=$originLng&dropoff_lat=$destLat&dropoff_lng=$destLng';
      case 'indriver':
        // InDriver deep link
        return 'indriver://';
      case 'didi':
        return 'didiglobal://';
      case 'bolt':
        return 'bolt://';
      default:
        return null;
    }
  }

  /// Get WhatsApp link for independent driver
  String? getWhatsAppLink(String message) {
    if (driverPhone == null) return null;
    final phone = driverPhone!.replaceAll('+', '');
    final encodedMessage = Uri.encodeComponent(message);
    return 'https://wa.me/$phone?text=$encodedMessage';
  }

  PriceOption copyWith({
    String? id,
    String? name,
    String? provider,
    double? price,
    String? currency,
    int? estimatedMinutes,
    int? etaMinutes,
    double? rating,
    int? totalRides,
    String? driverName,
    String? driverPhone,
    String? vehicleInfo,
    String? vehicleColor,
    String? category,
    int? discount,
    bool? isAvailable,
    bool? isBestPrice,
    bool? isEstimate,
  }) {
    return PriceOption(
      id: id ?? this.id,
      name: name ?? this.name,
      provider: provider ?? this.provider,
      price: price ?? this.price,
      currency: currency ?? this.currency,
      estimatedMinutes: estimatedMinutes ?? this.estimatedMinutes,
      etaMinutes: etaMinutes ?? this.etaMinutes,
      rating: rating ?? this.rating,
      totalRides: totalRides ?? this.totalRides,
      driverName: driverName ?? this.driverName,
      driverPhone: driverPhone ?? this.driverPhone,
      vehicleInfo: vehicleInfo ?? this.vehicleInfo,
      vehicleColor: vehicleColor ?? this.vehicleColor,
      category: category ?? this.category,
      discount: discount ?? this.discount,
      isAvailable: isAvailable ?? this.isAvailable,
      isBestPrice: isBestPrice ?? this.isBestPrice,
      isEstimate: isEstimate ?? this.isEstimate,
    );
  }
}
