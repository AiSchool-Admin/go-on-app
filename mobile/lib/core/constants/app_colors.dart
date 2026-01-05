import 'package:flutter/material.dart';

/// Application color palette - Egyptian theme
class AppColors {
  AppColors._();

  // Primary Colors
  static const Color primary = Color(0xFF1A365D);      // Deep blue
  static const Color primaryLight = Color(0xFF2B6CB0); // Light blue
  static const Color primaryDark = Color(0xFF0F2942);  // Dark blue

  // Secondary Colors
  static const Color secondary = Color(0xFFD69E2E);    // Gold
  static const Color secondaryLight = Color(0xFFECC94B);
  static const Color secondaryDark = Color(0xFFB7791F);

  // Accent
  static const Color accent = Color(0xFF2B6CB0);       // Light blue

  // Status Colors
  static const Color success = Color(0xFF38A169);
  static const Color successLight = Color(0xFF68D391);
  static const Color warning = Color(0xFFDD6B20);
  static const Color warningLight = Color(0xFFF6AD55);
  static const Color error = Color(0xFFE53E3E);
  static const Color errorLight = Color(0xFFFC8181);
  static const Color info = Color(0xFF3182CE);
  static const Color infoLight = Color(0xFF63B3ED);

  // Background Colors
  static const Color background = Color(0xFFF7FAFC);
  static const Color backgroundDark = Color(0xFF1A202C);
  static const Color surface = Color(0xFFFFFFFF);
  static const Color surfaceDark = Color(0xFF2D3748);

  // Text Colors
  static const Color textPrimary = Color(0xFF1A202C);
  static const Color textSecondary = Color(0xFF718096);
  static const Color textLight = Color(0xFFA0AEC0);
  static const Color textOnPrimary = Color(0xFFFFFFFF);
  static const Color textOnSecondary = Color(0xFF1A202C);

  // Border Colors
  static const Color border = Color(0xFFE2E8F0);
  static const Color borderDark = Color(0xFF4A5568);

  // Other
  static const Color divider = Color(0xFFE2E8F0);
  static const Color shadow = Color(0x1A000000);
  static const Color overlay = Color(0x80000000);

  // Transport App Colors
  static const Color uber = Color(0xFF000000);
  static const Color careem = Color(0xFF00B140);
  static const Color indriver = Color(0xFF00C853);
  static const Color independent = Color(0xFFD69E2E);

  // Gradient
  static const LinearGradient primaryGradient = LinearGradient(
    begin: Alignment.topLeft,
    end: Alignment.bottomRight,
    colors: [primary, primaryLight],
  );

  static const LinearGradient goldGradient = LinearGradient(
    begin: Alignment.topLeft,
    end: Alignment.bottomRight,
    colors: [secondary, secondaryLight],
  );
}
