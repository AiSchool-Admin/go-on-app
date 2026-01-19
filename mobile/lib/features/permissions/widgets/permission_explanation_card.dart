import 'package:flutter/material.dart';
import '../../../core/constants/app_colors.dart';

/// Enum for permission types
enum PermissionType {
  accessibility,
  overlay,
}

/// Detailed permission explanation card
/// Shows what the permission does, why it's needed, and privacy information
class PermissionExplanationCard extends StatelessWidget {
  final PermissionType type;
  final bool isEnabled;
  final VoidCallback onEnable;
  final VoidCallback? onLearnMore;

  const PermissionExplanationCard({
    super.key,
    required this.type,
    required this.isEnabled,
    required this.onEnable,
    this.onLearnMore,
  });

  @override
  Widget build(BuildContext context) {
    return Container(
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
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          // Header
          Padding(
            padding: const EdgeInsets.all(16),
            child: Row(
              children: [
                _buildIcon(),
                const SizedBox(width: 12),
                Expanded(
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Row(
                        children: [
                          Expanded(
                            child: Text(
                              _getTitle(),
                              style: const TextStyle(
                                fontWeight: FontWeight.bold,
                                fontSize: 15,
                              ),
                            ),
                          ),
                          if (isEnabled)
                            Container(
                              padding: const EdgeInsets.symmetric(
                                horizontal: 8,
                                vertical: 4,
                              ),
                              decoration: BoxDecoration(
                                color: AppColors.success.withOpacity(0.1),
                                borderRadius: BorderRadius.circular(12),
                              ),
                              child: const Row(
                                mainAxisSize: MainAxisSize.min,
                                children: [
                                  Icon(
                                    Icons.check_circle,
                                    color: AppColors.success,
                                    size: 14,
                                  ),
                                  SizedBox(width: 4),
                                  Text(
                                    'مفعّل',
                                    style: TextStyle(
                                      color: AppColors.success,
                                      fontSize: 12,
                                      fontWeight: FontWeight.bold,
                                    ),
                                  ),
                                ],
                              ),
                            ),
                        ],
                      ),
                      const SizedBox(height: 4),
                      Text(
                        _getShortDescription(),
                        style: const TextStyle(
                          color: AppColors.textSecondary,
                          fontSize: 13,
                        ),
                      ),
                    ],
                  ),
                ),
              ],
            ),
          ),

          // Divider
          const Divider(height: 1),

          // Usage bullets
          Padding(
            padding: const EdgeInsets.all(16),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                const Text(
                  'ماذا يفعل هذا الإذن؟',
                  style: TextStyle(
                    fontWeight: FontWeight.bold,
                    fontSize: 13,
                    color: AppColors.textPrimary,
                  ),
                ),
                const SizedBox(height: 8),
                ..._getUsageBullets().map((bullet) => _buildBulletPoint(bullet)),
              ],
            ),
          ),

          // Privacy note
          Container(
            margin: const EdgeInsets.symmetric(horizontal: 16),
            padding: const EdgeInsets.all(12),
            decoration: BoxDecoration(
              color: AppColors.success.withOpacity(0.08),
              borderRadius: BorderRadius.circular(12),
              border: Border.all(
                color: AppColors.success.withOpacity(0.3),
              ),
            ),
            child: Row(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Icon(
                  Icons.shield_outlined,
                  color: AppColors.success.shade700,
                  size: 20,
                ),
                const SizedBox(width: 10),
                Expanded(
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Text(
                        'خصوصيتك محمية',
                        style: TextStyle(
                          fontWeight: FontWeight.bold,
                          fontSize: 12,
                          color: AppColors.success.shade700,
                        ),
                      ),
                      const SizedBox(height: 4),
                      Text(
                        _getPrivacyNote(),
                        style: TextStyle(
                          fontSize: 11,
                          color: AppColors.success.shade700,
                          height: 1.4,
                        ),
                      ),
                    ],
                  ),
                ),
              ],
            ),
          ),

          const SizedBox(height: 16),

          // Action buttons
          if (!isEnabled)
            Padding(
              padding: const EdgeInsets.fromLTRB(16, 0, 16, 16),
              child: Row(
                children: [
                  if (onLearnMore != null)
                    Expanded(
                      child: OutlinedButton(
                        onPressed: onLearnMore,
                        style: OutlinedButton.styleFrom(
                          foregroundColor: AppColors.primary,
                          side: const BorderSide(color: AppColors.primary),
                          padding: const EdgeInsets.symmetric(vertical: 12),
                          shape: RoundedRectangleBorder(
                            borderRadius: BorderRadius.circular(10),
                          ),
                        ),
                        child: const Text(
                          'تعرف أكثر',
                          style: TextStyle(fontWeight: FontWeight.bold),
                        ),
                      ),
                    ),
                  if (onLearnMore != null) const SizedBox(width: 12),
                  Expanded(
                    flex: onLearnMore != null ? 2 : 1,
                    child: ElevatedButton(
                      onPressed: onEnable,
                      style: ElevatedButton.styleFrom(
                        backgroundColor: AppColors.primary,
                        foregroundColor: Colors.white,
                        padding: const EdgeInsets.symmetric(vertical: 12),
                        shape: RoundedRectangleBorder(
                          borderRadius: BorderRadius.circular(10),
                        ),
                      ),
                      child: const Row(
                        mainAxisAlignment: MainAxisAlignment.center,
                        children: [
                          Icon(Icons.settings, size: 18),
                          SizedBox(width: 8),
                          Text(
                            'تفعيل الآن',
                            style: TextStyle(fontWeight: FontWeight.bold),
                          ),
                        ],
                      ),
                    ),
                  ),
                ],
              ),
            ),
        ],
      ),
    );
  }

  Widget _buildIcon() {
    return Container(
      width: 52,
      height: 52,
      decoration: BoxDecoration(
        color: isEnabled
            ? AppColors.success.withOpacity(0.1)
            : AppColors.primary.withOpacity(0.1),
        borderRadius: BorderRadius.circular(14),
      ),
      child: Icon(
        _getIcon(),
        color: isEnabled ? AppColors.success : AppColors.primary,
        size: 28,
      ),
    );
  }

  Widget _buildBulletPoint(String text) {
    return Padding(
      padding: const EdgeInsets.only(bottom: 6),
      child: Row(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Container(
            margin: const EdgeInsets.only(top: 6),
            width: 6,
            height: 6,
            decoration: BoxDecoration(
              color: AppColors.primary,
              borderRadius: BorderRadius.circular(3),
            ),
          ),
          const SizedBox(width: 10),
          Expanded(
            child: Text(
              text,
              style: const TextStyle(
                fontSize: 12,
                color: AppColors.textSecondary,
                height: 1.4,
              ),
            ),
          ),
        ],
      ),
    );
  }

  IconData _getIcon() {
    switch (type) {
      case PermissionType.accessibility:
        return Icons.accessibility_new;
      case PermissionType.overlay:
        return Icons.picture_in_picture_alt;
    }
  }

  String _getTitle() {
    switch (type) {
      case PermissionType.accessibility:
        return 'خدمة إمكانية الوصول';
      case PermissionType.overlay:
        return 'العرض فوق التطبيقات';
    }
  }

  String _getShortDescription() {
    switch (type) {
      case PermissionType.accessibility:
        return 'لقراءة أسعار الرحلات من Uber وCareem وغيرها';
      case PermissionType.overlay:
        return 'لعرض فقاعة السعر الأفضل أثناء استخدام التطبيقات';
    }
  }

  List<String> _getUsageBullets() {
    switch (type) {
      case PermissionType.accessibility:
        return [
          'قراءة الأسعار المعروضة على شاشة تطبيقات الرحلات',
          'إدخال عنوان الوجهة تلقائياً في التطبيقات الأخرى',
          'اختيار أفضل سعر من بين جميع التطبيقات المثبتة',
        ];
      case PermissionType.overlay:
        return [
          'عرض فقاعة صغيرة تظهر السعر الأفضل',
          'إظهار مقارنة الأسعار أثناء تصفح التطبيقات الأخرى',
          'يمكنك إيقاف الفقاعة في أي وقت من الإعدادات',
        ];
    }
  }

  String _getPrivacyNote() {
    switch (type) {
      case PermissionType.accessibility:
        return 'نقرأ فقط أسعار الرحلات - لا نصل إلى رسائلك أو بياناتك الشخصية أو كلمات المرور. البيانات تُحفظ محلياً على جهازك فقط.';
      case PermissionType.overlay:
        return 'الفقاعة تظهر فقط عند استخدام تطبيقات الرحلات. يمكنك تحريكها أو إغلاقها في أي وقت. لا نسجل أي شيء تفعله.';
    }
  }
}

/// Extension to add shade method to Color
extension ColorShade on Color {
  Color get shade700 {
    final hsl = HSLColor.fromColor(this);
    return hsl.withLightness((hsl.lightness - 0.1).clamp(0.0, 1.0)).toColor();
  }
}
