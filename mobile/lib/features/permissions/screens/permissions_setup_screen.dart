import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../../../core/constants/app_colors.dart';
import '../../../core/services/native_services.dart';
import '../../../core/routes/app_router.dart';
import '../../../core/utils/app_logger.dart';
import '../widgets/permission_explanation_card.dart';
import '../widgets/privacy_info_dialog.dart';

/// Permissions Setup Screen
///
/// This screen guides users through enabling required permissions
/// with clear explanations of why each permission is needed and
/// how their privacy is protected.
class PermissionsSetupScreen extends ConsumerStatefulWidget {
  const PermissionsSetupScreen({super.key});

  @override
  ConsumerState<PermissionsSetupScreen> createState() => _PermissionsSetupScreenState();
}

class _PermissionsSetupScreenState extends ConsumerState<PermissionsSetupScreen>
    with WidgetsBindingObserver {
  bool _accessibilityEnabled = false;
  bool _overlayEnabled = false;
  bool _isLoading = true;

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
    Future.delayed(const Duration(milliseconds: 300), _checkPermissions);
  }

  @override
  void dispose() {
    WidgetsBinding.instance.removeObserver(this);
    super.dispose();
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    if (state == AppLifecycleState.resumed) {
      _checkPermissions();
    }
  }

  Future<void> _checkPermissions() async {
    try {
      final nativeServices = ref.read(nativeServicesProvider);
      final status = await nativeServices.checkAllPermissions();

      AppLogger.permission(
        'Permissions checked',
        permissionType: 'all',
        granted: status.allGranted,
      );

      if (mounted) {
        setState(() {
          _accessibilityEnabled = status.accessibility;
          _overlayEnabled = status.overlay;
          _isLoading = false;
        });

        if (status.allGranted) {
          _proceedToHome();
        }
      }
    } catch (e) {
      AppLogger.e('Error checking permissions', e);
      if (mounted) {
        setState(() => _isLoading = false);
      }
    }
  }

  void _proceedToHome() {
    AppLogger.navigation('Proceeding to home', route: AppRoutes.home);
    context.go(AppRoutes.home);
  }

  void _showPrivacyDialog({PermissionDetailType? focusedPermission}) {
    PrivacyInfoDialog.show(context, focusedPermission: focusedPermission);
  }

  @override
  Widget build(BuildContext context) {
    final nativeServices = ref.read(nativeServicesProvider);

    return Scaffold(
      backgroundColor: AppColors.background,
      body: SafeArea(
        child: _isLoading
            ? const Center(child: CircularProgressIndicator())
            : SingleChildScrollView(
                padding: const EdgeInsets.all(20.0),
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.stretch,
                  children: [
                    const SizedBox(height: 16),

                    // Header
                    _buildHeader(),

                    const SizedBox(height: 24),

                    // Trust banner
                    _buildTrustBanner(),

                    const SizedBox(height: 24),

                    // Permission Card 1 - Accessibility
                    PermissionExplanationCard(
                      type: PermissionType.accessibility,
                      isEnabled: _accessibilityEnabled,
                      onEnable: () {
                        AppLogger.permission(
                          'Opening accessibility settings',
                          permissionType: 'accessibility',
                        );
                        nativeServices.openAccessibilitySettings();
                      },
                      onLearnMore: () => _showPrivacyDialog(
                        focusedPermission: PermissionDetailType.accessibility,
                      ),
                    ),

                    const SizedBox(height: 16),

                    // Permission Card 2 - Overlay
                    PermissionExplanationCard(
                      type: PermissionType.overlay,
                      isEnabled: _overlayEnabled,
                      onEnable: () {
                        AppLogger.permission(
                          'Opening overlay settings',
                          permissionType: 'overlay',
                        );
                        nativeServices.openOverlaySettings();
                      },
                      onLearnMore: () => _showPrivacyDialog(
                        focusedPermission: PermissionDetailType.overlay,
                      ),
                    ),

                    const SizedBox(height: 24),

                    // Privacy link
                    _buildPrivacyLink(),

                    const SizedBox(height: 24),

                    // Action buttons
                    _buildActionButtons(),

                    const SizedBox(height: 16),
                  ],
                ),
              ),
      ),
    );
  }

  Widget _buildHeader() {
    return Column(
      children: [
        // Logo/Icon
        Container(
          width: 72,
          height: 72,
          decoration: BoxDecoration(
            color: AppColors.primary,
            borderRadius: BorderRadius.circular(20),
            boxShadow: [
              BoxShadow(
                color: AppColors.primary.withOpacity(0.3),
                blurRadius: 12,
                offset: const Offset(0, 4),
              ),
            ],
          ),
          child: const Icon(
            Icons.directions_car,
            color: Colors.white,
            size: 36,
          ),
        ),
        const SizedBox(height: 16),
        const Text(
          'GO-ON',
          style: TextStyle(
            fontSize: 28,
            fontWeight: FontWeight.bold,
            color: AppColors.primary,
          ),
        ),
        const SizedBox(height: 8),
        const Text(
          'إعداد الأذونات المطلوبة',
          style: TextStyle(
            fontSize: 16,
            color: AppColors.textSecondary,
          ),
        ),
      ],
    );
  }

  Widget _buildTrustBanner() {
    return Container(
      padding: const EdgeInsets.all(14),
      decoration: BoxDecoration(
        gradient: LinearGradient(
          colors: [
            AppColors.primary.withOpacity(0.08),
            AppColors.secondary.withOpacity(0.08),
          ],
          begin: Alignment.topLeft,
          end: Alignment.bottomRight,
        ),
        borderRadius: BorderRadius.circular(14),
        border: Border.all(
          color: AppColors.primary.withOpacity(0.2),
        ),
      ),
      child: Row(
        children: [
          Container(
            padding: const EdgeInsets.all(8),
            decoration: BoxDecoration(
              color: AppColors.primary.withOpacity(0.15),
              borderRadius: BorderRadius.circular(10),
            ),
            child: const Icon(
              Icons.verified_user,
              color: AppColors.primary,
              size: 22,
            ),
          ),
          const SizedBox(width: 12),
          const Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(
                  'بياناتك في أمان',
                  style: TextStyle(
                    fontWeight: FontWeight.bold,
                    fontSize: 14,
                    color: AppColors.textPrimary,
                  ),
                ),
                SizedBox(height: 4),
                Text(
                  'نقرأ الأسعار فقط - لا نصل لرسائلك أو بياناتك الشخصية',
                  style: TextStyle(
                    fontSize: 12,
                    color: AppColors.textSecondary,
                    height: 1.3,
                  ),
                ),
              ],
            ),
          ),
        ],
      ),
    );
  }

  Widget _buildPrivacyLink() {
    return Center(
      child: TextButton.icon(
        onPressed: () => _showPrivacyDialog(),
        icon: const Icon(
          Icons.privacy_tip_outlined,
          size: 18,
          color: AppColors.primary,
        ),
        label: const Text(
          'اعرف المزيد عن حماية خصوصيتك',
          style: TextStyle(
            color: AppColors.primary,
            fontSize: 13,
            decoration: TextDecoration.underline,
          ),
        ),
      ),
    );
  }

  Widget _buildActionButtons() {
    final allPermissionsGranted = _accessibilityEnabled && _overlayEnabled;

    if (allPermissionsGranted) {
      return ElevatedButton(
        onPressed: _proceedToHome,
        style: ElevatedButton.styleFrom(
          backgroundColor: AppColors.success,
          foregroundColor: Colors.white,
          padding: const EdgeInsets.symmetric(vertical: 16),
          shape: RoundedRectangleBorder(
            borderRadius: BorderRadius.circular(14),
          ),
          elevation: 2,
        ),
        child: const Row(
          mainAxisAlignment: MainAxisAlignment.center,
          children: [
            Icon(Icons.check_circle, size: 22),
            SizedBox(width: 10),
            Text(
              'ابدأ استخدام GO-ON',
              style: TextStyle(
                fontSize: 16,
                fontWeight: FontWeight.bold,
              ),
            ),
          ],
        ),
      );
    }

    return Column(
      children: [
        // Check permissions button
        ElevatedButton(
          onPressed: () {
            setState(() => _isLoading = true);
            _checkPermissions();
          },
          style: ElevatedButton.styleFrom(
            backgroundColor: AppColors.primary,
            foregroundColor: Colors.white,
            padding: const EdgeInsets.symmetric(vertical: 16),
            shape: RoundedRectangleBorder(
              borderRadius: BorderRadius.circular(14),
            ),
            elevation: 2,
          ),
          child: const Row(
            mainAxisAlignment: MainAxisAlignment.center,
            children: [
              Icon(Icons.refresh, size: 20),
              SizedBox(width: 10),
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

        // Skip button with warning
        TextButton(
          onPressed: () {
            _showSkipWarningDialog();
          },
          child: const Text(
            'تخطي الآن',
            style: TextStyle(
              color: AppColors.textSecondary,
              fontSize: 14,
            ),
          ),
        ),

        // Status indicators
        const SizedBox(height: 8),
        _buildStatusIndicator(
          'خدمة إمكانية الوصول',
          _accessibilityEnabled,
        ),
        const SizedBox(height: 4),
        _buildStatusIndicator(
          'العرض فوق التطبيقات',
          _overlayEnabled,
        ),
      ],
    );
  }

  Widget _buildStatusIndicator(String label, bool isEnabled) {
    return Row(
      mainAxisAlignment: MainAxisAlignment.center,
      children: [
        Icon(
          isEnabled ? Icons.check_circle : Icons.radio_button_unchecked,
          size: 16,
          color: isEnabled ? AppColors.success : AppColors.textSecondary,
        ),
        const SizedBox(width: 6),
        Text(
          label,
          style: TextStyle(
            fontSize: 12,
            color: isEnabled ? AppColors.success : AppColors.textSecondary,
          ),
        ),
      ],
    );
  }

  void _showSkipWarningDialog() {
    showDialog(
      context: context,
      builder: (context) => AlertDialog(
        shape: RoundedRectangleBorder(
          borderRadius: BorderRadius.circular(16),
        ),
        title: const Row(
          children: [
            Icon(Icons.warning_amber, color: AppColors.warning),
            SizedBox(width: 10),
            Text(
              'تنبيه',
              style: TextStyle(fontSize: 18),
            ),
          ],
        ),
        content: const Text(
          'بدون تفعيل الأذونات، لن تتمكن من:\n\n'
          '• مقارنة الأسعار من التطبيقات الأخرى\n'
          '• رؤية أفضل سعر تلقائياً\n'
          '• توفير المال على الرحلات\n\n'
          'يمكنك تفعيل الأذونات لاحقاً من الإعدادات.',
          style: TextStyle(height: 1.5),
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(context),
            child: const Text('تفعيل الآن'),
          ),
          ElevatedButton(
            onPressed: () {
              Navigator.pop(context);
              _proceedToHome();
            },
            style: ElevatedButton.styleFrom(
              backgroundColor: AppColors.textSecondary,
            ),
            child: const Text('تخطي'),
          ),
        ],
      ),
    );
  }
}
