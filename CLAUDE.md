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
Frontend:     Flutter 3.x (Dart)
Backend:      Firebase (Firestore, Functions, Auth, FCM)
Native:       Kotlin (for Android Accessibility Services)
Maps:         Google Maps Flutter
Payments:     Paymob API (Egypt)
```

---

## 📁 Project Structure

```
go-on-app/
├── android/                    # Android native code
│   └── app/src/main/kotlin/   # Kotlin for Accessibility
├── lib/                        # Flutter code
│   ├── main.dart              # App entry point
│   ├── core/                  # Core utilities
│   │   ├── constants/         # App constants
│   │   ├── theme/             # App theme
│   │   ├── utils/             # Helper functions
│   │   └── services/          # Core services
│   ├── features/              # Feature modules
│   │   ├── auth/              # Authentication
│   │   ├── home/              # Home screen
│   │   ├── rides/             # Passenger rides
│   │   ├── freight/           # Shipping/freight
│   │   ├── drivers/           # Driver management
│   │   ├── wallet/            # Digital wallet
│   │   ├── tracking/          # Live tracking
│   │   └── profile/           # User profile
│   ├── models/                # Data models
│   ├── providers/             # State management
│   └── widgets/               # Reusable widgets
├── functions/                  # Firebase Cloud Functions
├── assets/                     # Images, fonts
├── test/                       # Tests
└── docs/                       # Documentation
```

---

## 🏗 Architecture Guidelines

### State Management: Riverpod
```dart
// Example provider
final userProvider = StateNotifierProvider<UserNotifier, User?>((ref) {
  return UserNotifier();
});
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
```

---

## 🗄 Firebase Collections

### Main Collections
```
users/                 # User profiles
drivers/               # Driver profiles
rides/                 # Ride requests
shipments/             # Shipping orders
vehicles/              # Vehicle info
transactions/          # Payment transactions
ratings/               # User ratings
price_snapshots/       # Cached prices from other apps
```

### Subcollections
```
users/{userId}/wallet_transactions/
drivers/{driverId}/completed_rides/
drivers/{driverId}/completed_shipments/
```

---

## 🔑 Key Features to Implement

### 1. Price Comparison (Core Feature)
```dart
// Service to get prices from all sources
class PriceComparisonService {
  Future<List<PriceOption>> getPrices({
    required LatLng origin,
    required LatLng destination,
  });
}

// PriceOption model
class PriceOption {
  final String provider;      // 'uber', 'careem', 'indriver', 'independent'
  final double price;
  final int etaMinutes;
  final String vehicleType;
  final double? rating;
}
```

### 2. Floating Overlay (Android)
```kotlin
// Kotlin service for overlay
class FloatingOverlayService : Service() {
    // Show floating bubble with best price
    // Communicate with Flutter via MethodChannel
}
```

### 3. Independent Drivers
```dart
// Driver model
class Driver {
  final String id;
  final String name;
  final String phone;
  final String? whatsappNumber;
  final Vehicle vehicle;
  final double rating;
  final int totalRides;
  final bool isOnline;
  final GeoPoint? currentLocation;
}
```

### 4. Shipment Tracking
```dart
// Real-time tracking
StreamBuilder<Shipment>(
  stream: shipmentService.trackShipment(shipmentId),
  builder: (context, snapshot) {
    // Update map and status
  },
)
```

---

## 🔐 Security Rules (Firestore)

```javascript
// Basic security rules structure
rules_version = '2';
service cloud.firestore {
  match /databases/{database}/documents {
    // Users can only read/write their own data
    match /users/{userId} {
      allow read, write: if request.auth.uid == userId;
    }
    
    // Drivers collection
    match /drivers/{driverId} {
      allow read: if request.auth != null;
      allow write: if request.auth.uid == driverId;
    }
    
    // Rides - creator or assigned driver can access
    match /rides/{rideId} {
      allow read: if request.auth.uid == resource.data.userId 
                  || request.auth.uid == resource.data.driverId;
      allow create: if request.auth != null;
      allow update: if request.auth.uid == resource.data.userId 
                    || request.auth.uid == resource.data.driverId;
    }
  }
}
```

---

## 📱 UI/UX Guidelines

### Colors (Egyptian theme)
```dart
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
```

### Typography
```dart
// Use Cairo font for Arabic
// Use Poppins for English
fontFamily: isArabic ? 'Cairo' : 'Poppins',
```

### Spacing
```dart
// Consistent spacing
static const xs = 4.0;
static const sm = 8.0;
static const md = 16.0;
static const lg = 24.0;
static const xl = 32.0;
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
// Test services and providers
test('PriceComparisonService returns sorted prices', () async {
  final service = PriceComparisonService();
  final prices = await service.getPrices(origin: ..., destination: ...);
  expect(prices.first.price, lessThanOrEqualTo(prices.last.price));
});
```

### Widget Tests
```dart
// Test UI components
testWidgets('PriceCard displays correct information', (tester) async {
  await tester.pumpWidget(PriceCard(option: mockOption));
  expect(find.text('65 ج.م'), findsOneWidget);
});
```

---

## 🚀 Build Commands

### Development
```bash
# Run app
flutter run

# Run with specific device
flutter run -d <device_id>

# Hot reload
r (in terminal)

# Hot restart
R (in terminal)
```

### Build
```bash
# Build APK
flutter build apk --release

# Build App Bundle
flutter build appbundle --release
```

### Firebase
```bash
# Deploy functions
cd functions && npm run deploy

# Deploy rules
firebase deploy --only firestore:rules
```

---

## 📋 Task Checklist for Claude Code

When implementing features, follow this order:

### For each feature:
1. [ ] Create data models in `lib/models/`
2. [ ] Create service class in `lib/features/{feature}/services/`
3. [ ] Create provider in `lib/features/{feature}/providers/`
4. [ ] Create screen in `lib/features/{feature}/screens/`
5. [ ] Create widgets in `lib/features/{feature}/widgets/`
6. [ ] Add routes in `lib/core/routes/`
7. [ ] Write tests in `test/`
8. [ ] Update documentation

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
3. **Offline Support** - Cache important data locally
4. **Battery Optimization** - Be careful with background services
5. **Privacy** - Clear consent for Accessibility permissions

---

## 🔗 Related Files

- `GO-ON_PRD.md` - Product Requirements
- `DATABASE_SCHEMA.md` - Database structure
- `GETTING_STARTED.md` - Setup guide
