# CLAUDE.md - Instructions for Claude Code

## 🎯 Project Overview

**GO-ON** is a transport aggregator app for Egypt that combines:
- **Ride-hailing comparison** (Uber, Careem, InDrive, etc.)
- **Freight/shipping services** (Aramex, Bosta, independent drivers)
- **Independent drivers network** (WhatsApp-based fleet)

The app shows users the best price across all options in one screen.

---

## 🛠 Tech Stack

```
Mobile App:       Flutter 3.x (Dart) - Android Only
Backend:          Supabase (PostgreSQL + Auth + Storage + Realtime)
Additional APIs:  Railway (Node.js) - for OCR, WhatsApp Bot, etc.
Admin Dashboard:  Next.js on Vercel (future)
Maps:             Google Maps Flutter
Payments:         Paymob API (Egypt)
```

### Why This Stack?
- **Supabase**: PostgreSQL database, built-in Auth, Realtime subscriptions, Storage
- **Railway**: For custom backend services (OCR processing, WhatsApp integration)
- **Vercel**: Admin dashboard (Next.js)
- **Flutter**: Cross-platform but focusing on Android for Accessibility Services

---

## 📁 Project Structure

```
go-on-app/
├── mobile/                        # Flutter Mobile App
│   ├── android/                   # Android native code
│   │   └── app/src/main/kotlin/   # Kotlin for Accessibility
│   ├── lib/                       # Flutter code
│   │   ├── main.dart              # App entry point
│   │   ├── core/                  # Core utilities
│   │   │   ├── constants/         # App constants
│   │   │   ├── theme/             # App theme
│   │   │   ├── utils/             # Helper functions
│   │   │   └── services/          # Core services
│   │   ├── features/              # Feature modules
│   │   │   ├── auth/              # Authentication
│   │   │   ├── home/              # Home screen
│   │   │   ├── rides/             # Passenger rides
│   │   │   ├── freight/           # Shipping/freight
│   │   │   ├── drivers/           # Driver management
│   │   │   ├── wallet/            # Digital wallet
│   │   │   ├── tracking/          # Live tracking
│   │   │   └── profile/           # User profile
│   │   ├── models/                # Data models
│   │   ├── providers/             # State management (Riverpod)
│   │   └── widgets/               # Reusable widgets
│   ├── assets/                    # Images, fonts
│   └── test/                      # Tests
│
├── backend/                       # Railway Backend (Node.js)
│   ├── src/
│   │   ├── services/
│   │   │   ├── ocr/               # OCR for price reading
│   │   │   ├── whatsapp/          # WhatsApp Bot
│   │   │   └── notifications/     # Push notifications
│   │   └── routes/
│   └── package.json
│
├── admin/                         # Vercel Admin Dashboard (Next.js)
│   ├── src/
│   │   ├── app/
│   │   └── components/
│   └── package.json
│
├── supabase/                      # Supabase configuration
│   ├── migrations/                # Database migrations
│   ├── functions/                 # Edge Functions
│   └── seed.sql                   # Initial data
│
└── docs/                          # Documentation
    ├── GO-ON_PRD.md
    ├── DATABASE_SCHEMA.md
    └── GETTING_STARTED.md
```

---

## 🏗 Architecture Guidelines

### State Management: Riverpod
```dart
// Example provider
final userProvider = StateNotifierProvider<UserNotifier, User?>((ref) {
  return UserNotifier();
});

// Supabase client provider
final supabaseProvider = Provider<SupabaseClient>((ref) {
  return Supabase.instance.client;
});
```

### Supabase Service Pattern
```dart
class SupabaseService {
  final SupabaseClient _client;
  
  SupabaseService(this._client);
  
  // Get rides for user
  Future<List<Ride>> getUserRides(String userId) async {
    final response = await _client
        .from('rides')
        .select()
        .eq('user_id', userId)
        .order('created_at', ascending: false);
    
    return (response as List).map((e) => Ride.fromJson(e)).toList();
  }
  
  // Realtime subscription
  Stream<List<Ride>> watchUserRides(String userId) {
    return _client
        .from('rides')
        .stream(primaryKey: ['id'])
        .eq('user_id', userId)
        .map((data) => data.map((e) => Ride.fromJson(e)).toList());
  }
}
```

### Feature Structure
Each feature should have:
```
feature_name/
├── screens/           # UI screens
├── widgets/           # Feature-specific widgets
├── providers/         # Feature providers
├── services/          # Feature services
└── models/            # Feature-specific models
```

### Naming Conventions
```
Files:          snake_case.dart
Classes:        PascalCase
Variables:      camelCase
Constants:      SCREAMING_SNAKE_CASE
Widgets:        PascalCaseWidget
Screens:        PascalCaseScreen
DB Tables:      snake_case (PostgreSQL convention)
DB Columns:     snake_case (PostgreSQL convention)
```

---

## 🗄 Supabase Tables

### Main Tables (PostgreSQL)
```sql
-- Users table (extends Supabase auth.users)
profiles
drivers
vehicles
rides
shipments
transactions
ratings
price_snapshots
notifications
app_config
```

### Key Relationships
```
profiles.id → auth.users.id (1:1)
drivers.user_id → profiles.id (1:1)
vehicles.driver_id → drivers.id (1:1)
rides.user_id → profiles.id (N:1)
rides.driver_id → drivers.id (N:1)
shipments.sender_id → profiles.id (N:1)
shipments.driver_id → drivers.id (N:1)
```

---

## 🔑 Key Features to Implement

### 1. Supabase Auth
```dart
// Sign up with phone
final response = await supabase.auth.signUp(
  phone: '+201234567890',
  password: 'password123',
);

// Sign in with OTP
await supabase.auth.signInWithOtp(phone: '+201234567890');

// Verify OTP
await supabase.auth.verifyOTP(
  phone: '+201234567890',
  token: '123456',
  type: OtpType.sms,
);
```

### 2. Price Comparison (Core Feature)
```dart
class PriceComparisonService {
  Future<List<PriceOption>> getPrices({
    required LatLng origin,
    required LatLng destination,
  }) async {
    // Call Railway backend for OCR prices
    final ocrPrices = await _getOcrPrices(origin, destination);
    
    // Get independent drivers from Supabase
    final independentDrivers = await _getIndependentDrivers(origin);
    
    // Combine and sort
    return [...ocrPrices, ...independentDrivers]
      ..sort((a, b) => a.price.compareTo(b.price));
  }
}
```

### 3. Realtime Tracking
```dart
// Subscribe to shipment updates
final subscription = supabase
    .from('shipments')
    .stream(primaryKey: ['id'])
    .eq('id', shipmentId)
    .listen((data) {
      // Update UI with new location
      final shipment = Shipment.fromJson(data.first);
      updateMap(shipment.currentLocation);
    });
```

### 4. Floating Overlay (Android Kotlin)
```kotlin
// Kotlin service for overlay
class FloatingOverlayService : Service() {
    // Show floating bubble with best price
    // Communicate with Flutter via MethodChannel
}
```

### 5. Driver Location Updates
```dart
// Update driver location every 10 seconds
Timer.periodic(Duration(seconds: 10), (_) async {
  final position = await Geolocator.getCurrentPosition();
  
  await supabase
      .from('drivers')
      .update({
        'current_location': 'POINT(${position.longitude} ${position.latitude})',
        'last_location_update': DateTime.now().toIso8601String(),
      })
      .eq('id', driverId);
});
```

---

## 🔐 Row Level Security (RLS)

### Enable RLS on all tables
```sql
-- Users can only read/update their own profile
CREATE POLICY "Users can view own profile"
  ON profiles FOR SELECT
  USING (auth.uid() = id);

CREATE POLICY "Users can update own profile"
  ON profiles FOR UPDATE
  USING (auth.uid() = id);

-- Users can view their own rides
CREATE POLICY "Users can view own rides"
  ON rides FOR SELECT
  USING (auth.uid() = user_id OR auth.uid() = driver_id);

-- Drivers can view available rides
CREATE POLICY "Drivers can view searching rides"
  ON rides FOR SELECT
  USING (status = 'searching' AND EXISTS (
    SELECT 1 FROM drivers WHERE user_id = auth.uid() AND is_online = true
  ));
```

---

## 📱 UI/UX Guidelines

### Colors (Egyptian theme)
```dart
class AppColors {
  // Primary colors
  static const primary = Color(0xFF1A365D);      // Deep blue
  static const secondary = Color(0xFFD69E2E);    // Gold
  static const accent = Color(0xFF2B6CB0);       // Light blue

  // Status colors
  static const success = Color(0xFF38A169);
  static const warning = Color(0xFFDD6B20);
  static const error = Color(0xFFE53E3E);

  // Background
  static const background = Color(0xFFF7FAFC);
  static const surface = Color(0xFFFFFFFF);
}
```

### Typography
```dart
// Use Cairo font for Arabic
// Use Poppins for English
fontFamily: isArabic ? 'Cairo' : 'Poppins',
```

### Spacing
```dart
class AppSpacing {
  static const xs = 4.0;
  static const sm = 8.0;
  static const md = 16.0;
  static const lg = 24.0;
  static const xl = 32.0;
}
```

---

## 🌐 Localization

### Supported Languages
- Arabic (ar) - Primary
- English (en) - Secondary

### Implementation
```dart
// Use flutter_localizations
// ARB files in lib/l10n/
// app_ar.arb, app_en.arb
```

### RTL Support
```dart
// App supports RTL for Arabic
// Use Directionality widget when needed
// Avoid hardcoded margins (use start/end instead of left/right)
```

---

## 🧪 Testing Guidelines

### Unit Tests
```dart
test('PriceComparisonService returns sorted prices', () async {
  final service = PriceComparisonService(mockSupabase);
  final prices = await service.getPrices(origin: ..., destination: ...);
  expect(prices.first.price, lessThanOrEqualTo(prices.last.price));
});
```

### Widget Tests
```dart
testWidgets('PriceCard displays correct information', (tester) async {
  await tester.pumpWidget(PriceCard(option: mockOption));
  expect(find.text('65 ج.م'), findsOneWidget);
});
```

---

## 🚀 Commands

### Flutter (Mobile)
```bash
# Navigate to mobile folder
cd mobile

# Get dependencies
flutter pub get

# Run app
flutter run

# Build APK
flutter build apk --release
```

### Supabase
```bash
# Link project
supabase link --project-ref YOUR_PROJECT_REF

# Run migrations
supabase db push

# Generate types (for TypeScript admin)
supabase gen types typescript --local > types/supabase.ts
```

### Railway Backend
```bash
cd backend
npm install
npm run dev
```

### Vercel Admin
```bash
cd admin
npm install
npm run dev
```

---

## 📋 Task Checklist for Claude Code

When implementing features, follow this order:

### For each feature:
1. [ ] Create/update Supabase migration in `supabase/migrations/`
2. [ ] Create data models in `mobile/lib/models/`
3. [ ] Create service class in `mobile/lib/features/{feature}/services/`
4. [ ] Create provider in `mobile/lib/features/{feature}/providers/`
5. [ ] Create screen in `mobile/lib/features/{feature}/screens/`
6. [ ] Create widgets in `mobile/lib/features/{feature}/widgets/`
7. [ ] Add routes in `mobile/lib/core/routes/`
8. [ ] Write tests in `mobile/test/`
9. [ ] Update RLS policies if needed

### Code Quality:
- [ ] Run `flutter analyze` - no errors
- [ ] Run `flutter format .` - consistent formatting
- [ ] Add comments for complex logic
- [ ] Handle errors gracefully
- [ ] Show loading states
- [ ] Support Arabic RTL

---

## ⚠️ Important Notes

1. **Android Only** - iOS doesn't support Accessibility Services
2. **Arabic First** - Primary language is Arabic, RTL layout
3. **Offline Support** - Cache important data locally with Hive/SharedPreferences
4. **Battery Optimization** - Be careful with background services and location updates
5. **Privacy** - Clear consent for Accessibility permissions
6. **Supabase Free Tier** - 500MB database, 1GB storage, 2GB bandwidth - enough for MVP

---

## 🔗 Related Files

- `GO-ON_PRD.md` - Product Requirements
- `DATABASE_SCHEMA.md` - PostgreSQL Schema for Supabase
- `GETTING_STARTED.md` - Setup guide
