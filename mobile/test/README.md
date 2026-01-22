# GO-ON Test Suite

## Overview

This directory contains the test suite for the GO-ON mobile application.

## Test Structure

```
test/
├── unit/                           # Unit tests
│   ├── services/                   # Service tests
│   │   └── egypt_pricing_service_test.dart
│   └── models/                     # Model tests
│       ├── ride_model_test.dart
│       ├── price_option_test.dart
│       └── user_profile_test.dart
│
├── widget/                         # Widget tests
│   ├── rides/
│   │   └── price_card_test.dart
│   └── home/
│       └── service_card_test.dart
│
├── integration/                    # Integration tests
│   └── price_comparison_flow_test.dart
│
├── test_helpers.dart              # Test utilities
└── flutter_test_config.dart       # Test configuration
```

## Running Tests

### All Flutter Tests

```bash
cd mobile
flutter test
```

### Specific Test File

```bash
flutter test test/unit/services/egypt_pricing_service_test.dart
```

### With Coverage

```bash
flutter test --coverage
```

### Android Kotlin Tests

```bash
cd mobile/android
./gradlew test
```

## Test Categories

### Unit Tests

Test individual functions and classes in isolation.

- **egypt_pricing_service_test.dart**: Tests for price calculation algorithms
- **ride_model_test.dart**: Tests for Ride model serialization/deserialization
- **price_option_test.dart**: Tests for PriceOption model and sorting
- **user_profile_test.dart**: Tests for UserProfile model

### Widget Tests

Test UI components in isolation.

- **price_card_test.dart**: Tests for PriceCard widget display and interaction
- **service_card_test.dart**: Tests for ServiceCard widget

### Integration Tests

Test complete user flows and feature integrations.

- **price_comparison_flow_test.dart**: Tests the full price comparison journey

### Android Tests (Kotlin)

Test Android-specific functionality.

- **PriceReaderServiceTest.kt**: Tests for price extraction patterns
- **AutomationStateTest.kt**: Tests for automation state machine

## Writing Tests

### Best Practices

1. **Descriptive names**: Use descriptive test names in Arabic or English
2. **Single responsibility**: Each test should verify one thing
3. **Arrange-Act-Assert**: Structure tests clearly
4. **Use test helpers**: Utilize `test_helpers.dart` for common setups

### Example Unit Test

```dart
test('should calculate correct price for 10km trip', () {
  // Arrange
  final distanceKm = 10.0;
  final minutes = 20;

  // Act
  final price = EgyptPricingService.calculateUberPrice(
    distanceKm: distanceKm,
    estimatedMinutes: minutes,
  );

  // Assert
  expect(price, equals(80.0));
});
```

### Example Widget Test

```dart
testWidgets('should display price', (tester) async {
  // Arrange
  final option = PriceOption(source: RideSource.uber, price: 85.0, etaMinutes: 5);

  // Act
  await tester.pumpWidget(createTestWidget(option));

  // Assert
  expect(find.text('85 ج.م'), findsOneWidget);
});
```

## Coverage Goals

| Component | Target |
|-----------|--------|
| Services | 90% |
| Models | 95% |
| Widgets | 80% |
| Overall | 85% |

## Continuous Integration

Tests are automatically run on:
- Every push to `main` branch
- Every pull request
- Nightly builds

## Troubleshooting

### Common Issues

1. **Missing dependencies**: Run `flutter pub get`
2. **Widget test failures**: Ensure `pumpWidget` is called before assertions
3. **Async test issues**: Use `await tester.pump()` for animations

### Getting Help

Contact the development team or check the documentation at `docs/TESTING.md`.
