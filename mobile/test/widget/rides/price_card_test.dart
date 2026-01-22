import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:go_on/features/rides/widgets/price_card.dart';
import 'package:go_on/models/price_option.dart';
import 'package:go_on/models/ride.dart';

void main() {
  Widget createTestWidget(PriceOption option, {VoidCallback? onTap}) {
    return MaterialApp(
      home: Scaffold(
        body: PriceCard(
          option: option,
          onTap: onTap ?? () {},
        ),
      ),
    );
  }

  group('PriceCard', () {
    testWidgets('should display provider name', (tester) async {
      final option = PriceOption(
        source: RideSource.uber,
        price: 85.0,
        etaMinutes: 5,
      );

      await tester.pumpWidget(createTestWidget(option));

      expect(find.text('أوبر'), findsOneWidget);
    });

    testWidgets('should display formatted price', (tester) async {
      final option = PriceOption(
        source: RideSource.uber,
        price: 85.0,
        etaMinutes: 5,
      );

      await tester.pumpWidget(createTestWidget(option));

      expect(find.text('85 ج.م'), findsOneWidget);
    });

    testWidgets('should display formatted ETA', (tester) async {
      final option = PriceOption(
        source: RideSource.uber,
        price: 85.0,
        etaMinutes: 5,
      );

      await tester.pumpWidget(createTestWidget(option));

      expect(find.text('5 د'), findsOneWidget);
    });

    testWidgets('should display vehicle type when available', (tester) async {
      final option = PriceOption(
        source: RideSource.uber,
        price: 85.0,
        etaMinutes: 5,
        vehicleType: 'UberX',
      );

      await tester.pumpWidget(createTestWidget(option));

      expect(find.text('UberX'), findsOneWidget);
    });

    testWidgets('should display best price badge when isBestPrice is true', (tester) async {
      final option = PriceOption(
        source: RideSource.goOn,
        price: 60.0,
        etaMinutes: 3,
      );
      option.isBestPrice = true;

      await tester.pumpWidget(createTestWidget(option));

      expect(find.text('أفضل سعر'), findsOneWidget);
      expect(find.byIcon(Icons.star), findsWidgets);
    });

    testWidgets('should not display best price badge when isBestPrice is false', (tester) async {
      final option = PriceOption(
        source: RideSource.uber,
        price: 85.0,
        etaMinutes: 5,
      );

      await tester.pumpWidget(createTestWidget(option));

      expect(find.text('أفضل سعر'), findsNothing);
    });

    testWidgets('should display discount when available', (tester) async {
      final option = PriceOption(
        source: RideSource.uber,
        price: 80.0,
        etaMinutes: 5,
        originalPrice: 100.0,
      );

      await tester.pumpWidget(createTestWidget(option));

      expect(find.text('100 ج.م'), findsOneWidget); // Original price
      expect(find.text('-20%'), findsOneWidget); // Discount badge
    });

    testWidgets('should display driver info for independent drivers', (tester) async {
      final option = PriceOption(
        source: RideSource.independent,
        price: 60.0,
        etaMinutes: 3,
        driverName: 'أحمد',
        driverRating: 4.8,
        driverTotalRides: 500,
      );

      await tester.pumpWidget(createTestWidget(option));

      expect(find.text('4.8'), findsOneWidget);
      expect(find.text('500 رحلة'), findsOneWidget);
    });

    testWidgets('should call onTap when card is tapped', (tester) async {
      bool tapped = false;
      final option = PriceOption(
        source: RideSource.uber,
        price: 85.0,
        etaMinutes: 5,
      );

      await tester.pumpWidget(createTestWidget(
        option,
        onTap: () => tapped = true,
      ));

      await tester.tap(find.byType(PriceCard));
      await tester.pump();

      expect(tapped, isTrue);
    });

    testWidgets('should display correct icon for Uber', (tester) async {
      final option = PriceOption(
        source: RideSource.uber,
        price: 85.0,
        etaMinutes: 5,
      );

      await tester.pumpWidget(createTestWidget(option));

      expect(find.byIcon(Icons.directions_car), findsOneWidget);
    });

    testWidgets('should display correct icon for Careem', (tester) async {
      final option = PriceOption(
        source: RideSource.careem,
        price: 80.0,
        etaMinutes: 4,
      );

      await tester.pumpWidget(createTestWidget(option));

      expect(find.byIcon(Icons.local_taxi), findsOneWidget);
    });

    testWidgets('should display correct icon for InDriver', (tester) async {
      final option = PriceOption(
        source: RideSource.indriver,
        price: 70.0,
        etaMinutes: 6,
      );

      await tester.pumpWidget(createTestWidget(option));

      expect(find.byIcon(Icons.car_rental), findsOneWidget);
    });

    testWidgets('should display correct icon for independent driver', (tester) async {
      final option = PriceOption(
        source: RideSource.independent,
        price: 60.0,
        etaMinutes: 3,
      );

      await tester.pumpWidget(createTestWidget(option));

      expect(find.byIcon(Icons.person), findsOneWidget);
    });

    testWidgets('should display all provider names correctly', (tester) async {
      final providers = [
        (RideSource.uber, 'أوبر'),
        (RideSource.careem, 'كريم'),
        (RideSource.indriver, 'إندرايف'),
        (RideSource.goOn, 'GO-ON'),
        (RideSource.independent, 'سائق مستقل'),
      ];

      for (final (source, expectedName) in providers) {
        final option = PriceOption(
          source: source,
          price: 50.0,
          etaMinutes: 5,
        );

        await tester.pumpWidget(createTestWidget(option));
        expect(find.text(expectedName), findsOneWidget);
      }
    });
  });
}
