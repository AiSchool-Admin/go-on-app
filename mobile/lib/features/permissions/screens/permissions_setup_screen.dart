import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../../../core/constants/app_colors.dart';
import '../../../core/services/native_services.dart';
import '../../../core/routes/app_router.dart';

class PermissionsSetupScreen extends ConsumerStatefulWidget {
  const PermissionsSetupScreen({super.key});

  @override
  ConsumerState<PermissionsSetupScreen> createState() => _PermissionsSetupScreenState();
}

class _PermissionsSetupScreenState extends ConsumerState<PermissionsSetupScreen>
    with WidgetsBindingObserver {
  bool _accessibilityEnabled = false;
  bool _overlayEnabled = false;
  bool _isChecking = false; // Start with false to show cards immediately

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
    // Check permissions after a short delay to ensure UI is built
    Future.delayed(const Duration(milliseconds: 500), _checkPermissions);
  }

  @override
  void dispose() {
    WidgetsBinding.instance.removeObserver(this);
    super.dispose();
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    // Re-check permissions when app resumes (user might have enabled them)
    if (state == AppLifecycleState.resumed) {
      _checkPermissions();
    }
  }

  Future<void> _checkPermissions() async {
    try {
      final nativeServices = ref.read(nativeServicesProvider);
      final status = await nativeServices.checkAllPermissions();

      if (mounted) {
        setState(() {
          _accessibilityEnabled = status.accessibility;
          _overlayEnabled = status.overlay;
        });

        // If all permissions are granted, proceed to home
        if (status.allGranted) {
          _proceedToHome();
        }
      }
    } catch (e) {
      // If error occurs, just keep permissions as false (buttons will show)
      debugPrint('Error checking permissions: $e');
    }
  }

  void _proceedToHome() {
    context.go(AppRoutes.home);
  }

  @override
  Widget build(BuildContext context) {
    final nativeServices = ref.read(nativeServicesProvider);

    return Scaffold(
      backgroundColor: AppColors.background,
      body: SafeArea(
        child: Padding(
          padding: const EdgeInsets.all(24.0),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              const SizedBox(height: 40),

              // Header
              const Text(
                'GO-ON',
                style: TextStyle(
                  fontSize: 32,
                  fontWeight: FontWeight.bold,
                  color: AppColors.primary,
                ),
                textAlign: TextAlign.center,
              ),
              const SizedBox(height: 8),
              const Text(
                'إعداد الأذونات',
                style: TextStyle(
                  fontSize: 20,
                  color: AppColors.textSecondary,
                ),
                textAlign: TextAlign.center,
              ),

              const SizedBox(height: 40),

              // Explanation
              Container(
                padding: const EdgeInsets.all(16),
                decoration: BoxDecoration(
                  color: AppColors.primary.withOpacity(0.1),
                  borderRadius: BorderRadius.circular(12),
                ),
                child: const Column(
                  children: [
                    Icon(Icons.info_outline, color: AppColors.primary, size: 32),
                    SizedBox(height: 12),
                    Text(
                      'لكي يعمل GO-ON بشكل صحيح، نحتاج لبعض الأذونات:',
                      style: TextStyle(
                        fontSize: 14,
                        color: AppColors.textPrimary,
                      ),
                      textAlign: TextAlign.center,
                    ),
                    SizedBox(height: 8),
                    Text(
                      '• قراءة الأسعار من التطبيقات الأخرى\n• عرض فقاعة المقارنة فوق التطبيقات',
                      style: TextStyle(
                        fontSize: 13,
                        color: AppColors.textSecondary,
                      ),
                      textAlign: TextAlign.center,
                    ),
                  ],
                ),
              ),

              const SizedBox(height: 32),

              // Permission Cards - Always show
              Expanded(
                child: ListView(
                  children: [
                    // Accessibility Permission
                    _PermissionCard(
                      title: 'خدمة إمكانية الوصول',
                      description:
                          'تسمح لـ GO-ON بقراءة أسعار الرحلات من أوبر وكريم وغيرها',
                      icon: Icons.accessibility_new,
                      isEnabled: _accessibilityEnabled,
                      onEnable: () async {
                        await nativeServices.openAccessibilitySettings();
                      },
                    ),

                    const SizedBox(height: 16),

                    // Overlay Permission
                    _PermissionCard(
                      title: 'العرض فوق التطبيقات',
                      description:
                          'تسمح بعرض فقاعة تظهر لك سعر أفضل أثناء استخدام التطبيقات الأخرى',
                      icon: Icons.picture_in_picture,
                      isEnabled: _overlayEnabled,
                      onEnable: () async {
                        await nativeServices.openOverlaySettings();
                      },
                    ),
                  ],
                ),
              ),

              // Continue Button
              const SizedBox(height: 24),

              if (_accessibilityEnabled && _overlayEnabled)
                ElevatedButton(
                  onPressed: _proceedToHome,
                  style: ElevatedButton.styleFrom(
                    backgroundColor: AppColors.success,
                    foregroundColor: Colors.white,
                    padding: const EdgeInsets.symmetric(vertical: 16),
                    shape: RoundedRectangleBorder(
                      borderRadius: BorderRadius.circular(12),
                    ),
                  ),
                  child: const Row(
                    mainAxisAlignment: MainAxisAlignment.center,
                    children: [
                      Icon(Icons.check_circle),
                      SizedBox(width: 8),
                      Text(
                        'ابدأ استخدام GO-ON',
                        style: TextStyle(
                          fontSize: 16,
                          fontWeight: FontWeight.bold,
                        ),
                      ),
                    ],
                  ),
                )
              else
                Column(
                  children: [
                    ElevatedButton(
                      onPressed: _checkPermissions,
                      style: ElevatedButton.styleFrom(
                        backgroundColor: AppColors.primary,
                        foregroundColor: Colors.white,
                        padding: const EdgeInsets.symmetric(vertical: 16),
                        shape: RoundedRectangleBorder(
                          borderRadius: BorderRadius.circular(12),
                        ),
                      ),
                      child: const Row(
                        mainAxisAlignment: MainAxisAlignment.center,
                        children: [
                          Icon(Icons.refresh),
                          SizedBox(width: 8),
                          Text(
                            'تحقق من الأذونات',
                            style: TextStyle(
                              fontSize: 16,
                              fontWeight: FontWeight.bold,
                            ),
                          ),
                        ],
                      ),
                    ),
                    const SizedBox(height: 12),
                    TextButton(
                      onPressed: _proceedToHome,
                      child: const Text(
                        'تخطي (الميزات ستكون محدودة)',
                        style: TextStyle(color: AppColors.textSecondary),
                      ),
                    ),
                  ],
                ),
            ],
          ),
        ),
      ),
    );
  }
}

class _PermissionCard extends StatelessWidget {
  final String title;
  final String description;
  final IconData icon;
  final bool isEnabled;
  final VoidCallback onEnable;

  const _PermissionCard({
    required this.title,
    required this.description,
    required this.icon,
    required this.isEnabled,
    required this.onEnable,
  });

  @override
  Widget build(BuildContext context) {
    return Container(
      padding: const EdgeInsets.all(16),
      decoration: BoxDecoration(
        color: AppColors.surface,
        borderRadius: BorderRadius.circular(16),
        border: Border.all(
          color: isEnabled ? AppColors.success : AppColors.divider,
          width: isEnabled ? 2 : 1,
        ),
        boxShadow: [
          BoxShadow(
            color: Colors.black.withOpacity(0.05),
            blurRadius: 8,
            offset: const Offset(0, 2),
          ),
        ],
      ),
      child: Row(
        children: [
          // Icon
          Container(
            width: 50,
            height: 50,
            decoration: BoxDecoration(
              color: isEnabled
                  ? AppColors.success.withOpacity(0.1)
                  : AppColors.primary.withOpacity(0.1),
              borderRadius: BorderRadius.circular(12),
            ),
            child: Icon(
              icon,
              color: isEnabled ? AppColors.success : AppColors.primary,
              size: 28,
            ),
          ),

          const SizedBox(width: 16),

          // Text
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Row(
                  children: [
                    Text(
                      title,
                      style: const TextStyle(
                        fontWeight: FontWeight.bold,
                        fontSize: 14,
                      ),
                    ),
                    if (isEnabled) ...[
                      const SizedBox(width: 8),
                      const Icon(
                        Icons.check_circle,
                        color: AppColors.success,
                        size: 18,
                      ),
                    ],
                  ],
                ),
                const SizedBox(height: 4),
                Text(
                  description,
                  style: const TextStyle(
                    color: AppColors.textSecondary,
                    fontSize: 12,
                  ),
                ),
              ],
            ),
          ),

          // Enable Button
          if (!isEnabled)
            ElevatedButton(
              onPressed: onEnable,
              style: ElevatedButton.styleFrom(
                backgroundColor: AppColors.primary,
                foregroundColor: Colors.white,
                padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 8),
                shape: RoundedRectangleBorder(
                  borderRadius: BorderRadius.circular(8),
                ),
              ),
              child: const Text('تفعيل'),
            ),
        ],
      ),
    );
  }
}
