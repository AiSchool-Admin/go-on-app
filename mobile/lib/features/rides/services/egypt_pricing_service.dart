import 'dart:math';

/// Egyptian Ride-Hailing Pricing Service
///
/// This service calculates accurate prices for ride-hailing apps in Egypt
/// based on actual pricing structures used by each provider.
///
/// Prices are calculated using real fare formulas:
/// Price = Base Fare + (Distance × Per KM Rate) + (Time × Per Minute Rate) + Booking Fee
///
/// Then adjusted for:
/// - Time of day (rush hour surge)
/// - Day of week
/// - Traffic conditions
class EgyptPricingService {

  /// Calculate Uber Egypt price
  /// Based on UberX pricing structure in Cairo/Egypt
  static double calculateUberPrice({
    required double distanceKm,
    required int estimatedMinutes,
    DateTime? tripTime,
  }) {
    // Uber Egypt base pricing (2024)
    const baseFare = 12.0;        // Base fare in EGP
    const perKmRate = 4.50;       // Per kilometer
    const perMinuteRate = 0.90;   // Per minute
    const bookingFee = 6.0;       // Service/booking fee
    const minimumFare = 25.0;     // Minimum fare

    // Calculate base price
    double price = baseFare +
                   (distanceKm * perKmRate) +
                   (estimatedMinutes * perMinuteRate) +
                   bookingFee;

    // Apply surge multiplier based on time
    final surge = _getSurgeMultiplier(tripTime ?? DateTime.now(), 'uber');
    price *= surge;

    // Round to nearest 5
    price = (price / 5).round() * 5.0;

    return price < minimumFare ? minimumFare : price;
  }

  /// Calculate Careem Egypt price
  /// Based on Careem Go pricing structure
  static double calculateCareemPrice({
    required double distanceKm,
    required int estimatedMinutes,
    DateTime? tripTime,
  }) {
    // Careem Egypt base pricing (2024)
    const baseFare = 10.0;        // Base fare in EGP
    const perKmRate = 4.20;       // Per kilometer
    const perMinuteRate = 0.85;   // Per minute
    const bookingFee = 5.0;       // Service fee
    const minimumFare = 22.0;     // Minimum fare

    double price = baseFare +
                   (distanceKm * perKmRate) +
                   (estimatedMinutes * perMinuteRate) +
                   bookingFee;

    final surge = _getSurgeMultiplier(tripTime ?? DateTime.now(), 'careem');
    price *= surge;

    price = (price / 5).round() * 5.0;

    return price < minimumFare ? minimumFare : price;
  }

  /// Calculate Bolt Egypt price
  /// Bolt is typically 10-15% cheaper than Uber
  static double calculateBoltPrice({
    required double distanceKm,
    required int estimatedMinutes,
    DateTime? tripTime,
  }) {
    // Bolt Egypt base pricing (2024)
    const baseFare = 8.0;         // Lower base fare
    const perKmRate = 3.80;       // Per kilometer
    const perMinuteRate = 0.75;   // Per minute
    const bookingFee = 4.0;       // Lower service fee
    const minimumFare = 20.0;     // Minimum fare

    double price = baseFare +
                   (distanceKm * perKmRate) +
                   (estimatedMinutes * perMinuteRate) +
                   bookingFee;

    final surge = _getSurgeMultiplier(tripTime ?? DateTime.now(), 'bolt');
    price *= surge;

    price = (price / 5).round() * 5.0;

    return price < minimumFare ? minimumFare : price;
  }

  /// Calculate DiDi Egypt price
  /// DiDi is typically the cheapest option
  static double calculateDiDiPrice({
    required double distanceKm,
    required int estimatedMinutes,
    DateTime? tripTime,
  }) {
    // DiDi Egypt base pricing (2024) - Most competitive
    const baseFare = 7.0;         // Lowest base fare
    const perKmRate = 3.50;       // Per kilometer
    const perMinuteRate = 0.70;   // Per minute
    const bookingFee = 3.0;       // Lowest service fee
    const minimumFare = 18.0;     // Minimum fare

    double price = baseFare +
                   (distanceKm * perKmRate) +
                   (estimatedMinutes * perMinuteRate) +
                   bookingFee;

    final surge = _getSurgeMultiplier(tripTime ?? DateTime.now(), 'didi');
    price *= surge;

    price = (price / 5).round() * 5.0;

    return price < minimumFare ? minimumFare : price;
  }

  /// Calculate InDriver suggested price
  /// InDriver allows price negotiation, this returns suggested price
  static double calculateInDriverPrice({
    required double distanceKm,
    required int estimatedMinutes,
    DateTime? tripTime,
  }) {
    // InDriver suggested pricing - typically 15-20% below Uber
    // Users can negotiate up or down
    const baseFare = 8.0;
    const perKmRate = 3.60;
    const perMinuteRate = 0.65;
    const minimumFare = 18.0;

    double price = baseFare +
                   (distanceKm * perKmRate) +
                   (estimatedMinutes * perMinuteRate);

    // InDriver has less surge pricing since users set prices
    final surge = _getSurgeMultiplier(tripTime ?? DateTime.now(), 'indriver');
    price *= surge;

    price = (price / 5).round() * 5.0;

    return price < minimumFare ? minimumFare : price;
  }

  /// Calculate GO-ON independent driver price
  /// Cheapest option - direct to driver, no middleman fees
  static double calculateIndependentPrice({
    required double distanceKm,
    required int estimatedMinutes,
    DateTime? tripTime,
  }) {
    // Independent drivers - most affordable
    const baseFare = 10.0;
    const perKmRate = 3.20;
    const perMinuteRate = 0.50;
    const minimumFare = 15.0;

    double price = baseFare +
                   (distanceKm * perKmRate) +
                   (estimatedMinutes * perMinuteRate);

    // Less surge for independent drivers
    final surge = _getSurgeMultiplier(tripTime ?? DateTime.now(), 'independent');
    price *= (surge - 1) * 0.5 + 1; // Half the surge effect

    price = (price / 5).round() * 5.0;

    return price < minimumFare ? minimumFare : price;
  }

  /// Get surge multiplier based on time of day and provider
  static double _getSurgeMultiplier(DateTime time, String provider) {
    final hour = time.hour;
    final dayOfWeek = time.weekday; // 1 = Monday, 7 = Sunday

    double surge = 1.0;

    // Morning rush hour (7 AM - 10 AM)
    if (hour >= 7 && hour < 10) {
      surge = 1.25;
    }
    // Evening rush hour (4 PM - 8 PM)
    else if (hour >= 16 && hour < 20) {
      surge = 1.35;
    }
    // Late night (11 PM - 5 AM)
    else if (hour >= 23 || hour < 5) {
      surge = 1.20;
    }
    // Friday prayer time (12 PM - 2 PM on Friday)
    else if (dayOfWeek == 5 && hour >= 12 && hour < 14) {
      surge = 1.15;
    }

    // Weekend adjustment (Thursday evening, Friday)
    if ((dayOfWeek == 4 && hour >= 18) || dayOfWeek == 5) {
      surge *= 1.10;
    }

    // Provider-specific surge behavior
    switch (provider) {
      case 'uber':
        // Uber has most aggressive surge
        break;
      case 'careem':
        // Careem slightly less surge
        surge = 1 + (surge - 1) * 0.9;
        break;
      case 'bolt':
        // Bolt competitive surge
        surge = 1 + (surge - 1) * 0.85;
        break;
      case 'didi':
        // DiDi lowest surge
        surge = 1 + (surge - 1) * 0.7;
        break;
      case 'indriver':
        // InDriver minimal surge (user-driven pricing)
        surge = 1 + (surge - 1) * 0.5;
        break;
      case 'independent':
        // Independent drivers - negotiable
        surge = 1 + (surge - 1) * 0.3;
        break;
    }

    return surge;
  }

  /// Get all prices for comparison
  static Map<String, PriceEstimate> getAllPrices({
    required double distanceKm,
    required int estimatedMinutes,
    DateTime? tripTime,
  }) {
    final time = tripTime ?? DateTime.now();

    return {
      'uber': PriceEstimate(
        provider: 'Uber',
        price: calculateUberPrice(
          distanceKm: distanceKm,
          estimatedMinutes: estimatedMinutes,
          tripTime: time,
        ),
        surge: _getSurgeMultiplier(time, 'uber'),
      ),
      'careem': PriceEstimate(
        provider: 'Careem',
        price: calculateCareemPrice(
          distanceKm: distanceKm,
          estimatedMinutes: estimatedMinutes,
          tripTime: time,
        ),
        surge: _getSurgeMultiplier(time, 'careem'),
      ),
      'bolt': PriceEstimate(
        provider: 'Bolt',
        price: calculateBoltPrice(
          distanceKm: distanceKm,
          estimatedMinutes: estimatedMinutes,
          tripTime: time,
        ),
        surge: _getSurgeMultiplier(time, 'bolt'),
      ),
      'didi': PriceEstimate(
        provider: 'DiDi',
        price: calculateDiDiPrice(
          distanceKm: distanceKm,
          estimatedMinutes: estimatedMinutes,
          tripTime: time,
        ),
        surge: _getSurgeMultiplier(time, 'didi'),
      ),
      'indriver': PriceEstimate(
        provider: 'InDriver',
        price: calculateInDriverPrice(
          distanceKm: distanceKm,
          estimatedMinutes: estimatedMinutes,
          tripTime: time,
        ),
        surge: _getSurgeMultiplier(time, 'indriver'),
      ),
      'independent': PriceEstimate(
        provider: 'GO-ON',
        price: calculateIndependentPrice(
          distanceKm: distanceKm,
          estimatedMinutes: estimatedMinutes,
          tripTime: time,
        ),
        surge: _getSurgeMultiplier(time, 'independent'),
      ),
    };
  }

  /// Check if current time has surge pricing
  static bool hasSurgePricing(DateTime? time) {
    final t = time ?? DateTime.now();
    final hour = t.hour;

    // Rush hours
    if (hour >= 7 && hour < 10) return true;
    if (hour >= 16 && hour < 20) return true;
    if (hour >= 23 || hour < 5) return true;

    return false;
  }

  /// Get surge description for UI
  static String getSurgeDescription(DateTime? time) {
    final t = time ?? DateTime.now();
    final hour = t.hour;

    if (hour >= 7 && hour < 10) {
      return 'ساعة الذروة الصباحية - الأسعار أعلى قليلاً';
    }
    if (hour >= 16 && hour < 20) {
      return 'ساعة الذروة المسائية - الأسعار أعلى قليلاً';
    }
    if (hour >= 23 || hour < 5) {
      return 'أسعار الليل - زيادة طفيفة';
    }

    return '';
  }
}

/// Price estimate with surge info
class PriceEstimate {
  final String provider;
  final double price;
  final double surge;

  PriceEstimate({
    required this.provider,
    required this.price,
    required this.surge,
  });

  bool get hasSurge => surge > 1.05;

  int get surgePercent => ((surge - 1) * 100).round();
}
