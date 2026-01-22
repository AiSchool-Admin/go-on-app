import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:go_on/features/home/widgets/service_card.dart';

void main() {
  Widget createTestWidget({
    required String title,
    required IconData icon,
    required Color color,
    VoidCallback? onTap,
  }) {
    return MaterialApp(
      home: Scaffold(
        body: ServiceCard(
          title: title,
          icon: icon,
          color: color,
          onTap: onTap ?? () {},
        ),
      ),
    );
  }

  group('ServiceCard', () {
    testWidgets('should display title', (tester) async {
      await tester.pumpWidget(createTestWidget(
        title: 'رحلات',
        icon: Icons.directions_car,
        color: Colors.blue,
      ));

      expect(find.text('رحلات'), findsOneWidget);
    });

    testWidgets('should display icon', (tester) async {
      await tester.pumpWidget(createTestWidget(
        title: 'رحلات',
        icon: Icons.directions_car,
        color: Colors.blue,
      ));

      expect(find.byIcon(Icons.directions_car), findsOneWidget);
    });

    testWidgets('should call onTap when tapped', (tester) async {
      bool tapped = false;

      await tester.pumpWidget(createTestWidget(
        title: 'رحلات',
        icon: Icons.directions_car,
        color: Colors.blue,
        onTap: () => tapped = true,
      ));

      await tester.tap(find.byType(ServiceCard));
      await tester.pump();

      expect(tapped, isTrue);
    });

    testWidgets('should render as a clickable card', (tester) async {
      await tester.pumpWidget(createTestWidget(
        title: 'شحن',
        icon: Icons.local_shipping,
        color: Colors.green,
      ));

      // Should find a GestureDetector or InkWell wrapping the card
      expect(
        find.descendant(
          of: find.byType(ServiceCard),
          matching: find.byType(GestureDetector),
        ),
        findsWidgets,
      );
    });
  });
}
