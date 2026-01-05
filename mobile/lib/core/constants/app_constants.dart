/// Application constants
class AppConstants {
  AppConstants._();

  // App Info
  static const String appName = 'GO-ON';
  static const String appNameArabic = 'جو-أون';
  static const String appTagline = 'مصر تتحرك';
  static const String appVersion = '1.0.0';

  // Supabase Configuration
  // TODO: Replace with actual values from environment
  static const String supabaseUrl = 'https://your-project.supabase.co';
  static const String supabaseAnonKey = 'your-anon-key';

  // Google Maps
  // TODO: Replace with actual API key
  static const String googleMapsApiKey = 'your-google-maps-api-key';

  // API Endpoints
  static const String railwayBaseUrl = 'https://your-railway-app.railway.app';

  // Timeouts
  static const Duration apiTimeout = Duration(seconds: 30);
  static const Duration locationUpdateInterval = Duration(seconds: 10);

  // Pagination
  static const int defaultPageSize = 20;

  // Location
  static const double defaultLatitude = 30.0444; // Cairo
  static const double defaultLongitude = 31.2357;
  static const double searchRadiusKm = 5.0;

  // Pricing
  static const double minFare = 20.0;
  static const double baseFare = 15.0;
  static const double perKmRate = 3.5;
  static const double perMinuteRate = 0.5;
  static const double commissionRate = 0.15;

  // Currency
  static const String currency = 'EGP';
  static const String currencySymbol = 'ج.م';

  // Storage Keys
  static const String tokenKey = 'auth_token';
  static const String userKey = 'user_data';
  static const String languageKey = 'app_language';
  static const String themeKey = 'app_theme';
  static const String onboardingKey = 'onboarding_completed';
}
