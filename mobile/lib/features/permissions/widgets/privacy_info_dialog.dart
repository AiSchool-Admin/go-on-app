import 'package:flutter/material.dart';
import '../../../core/constants/app_colors.dart';

/// Privacy information dialog
/// Shows detailed information about what data the app accesses and how it's used
class PrivacyInfoDialog extends StatelessWidget {
  final PermissionDetailType? focusedPermission;

  const PrivacyInfoDialog({
    super.key,
    this.focusedPermission,
  });

  static Future<void> show(BuildContext context, {PermissionDetailType? focusedPermission}) {
    return showDialog(
      context: context,
      builder: (context) => PrivacyInfoDialog(focusedPermission: focusedPermission),
    );
  }

  @override
  Widget build(BuildContext context) {
    return Dialog(
      shape: RoundedRectangleBorder(
        borderRadius: BorderRadius.circular(20),
      ),
      child: SingleChildScrollView(
        child: Padding(
          padding: const EdgeInsets.all(20),
          child: Column(
            mainAxisSize: MainAxisSize.min,
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              // Header
              Row(
                children: [
                  Container(
                    padding: const EdgeInsets.all(10),
                    decoration: BoxDecoration(
                      color: AppColors.primary.withOpacity(0.1),
                      borderRadius: BorderRadius.circular(12),
                    ),
                    child: const Icon(
                      Icons.privacy_tip,
                      color: AppColors.primary,
                      size: 24,
                    ),
                  ),
                  const SizedBox(width: 12),
                  const Expanded(
                    child: Text(
                      'خصوصيتك مهمة لنا',
                      style: TextStyle(
                        fontSize: 18,
                        fontWeight: FontWeight.bold,
                        color: AppColors.textPrimary,
                      ),
                    ),
                  ),
                  IconButton(
                    onPressed: () => Navigator.pop(context),
                    icon: const Icon(Icons.close),
                    padding: EdgeInsets.zero,
                    constraints: const BoxConstraints(),
                  ),
                ],
              ),

              const SizedBox(height: 20),

              // Introduction
              const Text(
                'نحن نهتم بحماية بياناتك. إليك ما نفعله وما لا نفعله:',
                style: TextStyle(
                  fontSize: 14,
                  color: AppColors.textSecondary,
                ),
              ),

              const SizedBox(height: 20),

              // What we DO
              _buildSection(
                title: 'ما نفعله',
                icon: Icons.check_circle,
                iconColor: AppColors.success,
                items: const [
                  PrivacyItem(
                    icon: Icons.price_check,
                    title: 'نقرأ الأسعار فقط',
                    description: 'نقرأ أسعار الرحلات المعروضة على الشاشة لمقارنتها',
                  ),
                  PrivacyItem(
                    icon: Icons.phone_android,
                    title: 'نحفظ محلياً',
                    description: 'جميع البيانات تُحفظ على جهازك فقط، ولا تُرسل لأي مكان',
                  ),
                  PrivacyItem(
                    icon: Icons.location_on,
                    title: 'نستخدم الموقع للرحلات',
                    description: 'نستخدم موقعك فقط عند طلب رحلة لتحديد نقطة الانطلاق',
                  ),
                  PrivacyItem(
                    icon: Icons.toggle_on,
                    title: 'نتيح التحكم الكامل',
                    description: 'يمكنك إلغاء أي صلاحية في أي وقت من إعدادات الجهاز',
                  ),
                ],
              ),

              const SizedBox(height: 16),

              // What we DON'T do
              _buildSection(
                title: 'ما لا نفعله',
                icon: Icons.cancel,
                iconColor: AppColors.error,
                items: const [
                  PrivacyItem(
                    icon: Icons.message,
                    title: 'لا نقرأ رسائلك',
                    description: 'لا نصل إلى رسائلك النصية أو المحادثات',
                  ),
                  PrivacyItem(
                    icon: Icons.password,
                    title: 'لا نصل لكلمات المرور',
                    description: 'لا نقرأ أو نحفظ أي كلمات مرور أو بيانات حساسة',
                  ),
                  PrivacyItem(
                    icon: Icons.cloud_upload,
                    title: 'لا نرسل بياناتك',
                    description: 'لا نشارك أي معلومات مع أطراف ثالثة',
                  ),
                  PrivacyItem(
                    icon: Icons.track_changes,
                    title: 'لا نتتبع نشاطك',
                    description: 'لا نسجل ما تفعله في التطبيقات الأخرى',
                  ),
                ],
              ),

              const SizedBox(height: 20),

              // Specific permission details if focused
              if (focusedPermission != null) ...[
                const Divider(),
                const SizedBox(height: 16),
                _buildPermissionDetail(focusedPermission!),
                const SizedBox(height: 16),
              ],

              // Close button
              SizedBox(
                width: double.infinity,
                child: ElevatedButton(
                  onPressed: () => Navigator.pop(context),
                  style: ElevatedButton.styleFrom(
                    backgroundColor: AppColors.primary,
                    foregroundColor: Colors.white,
                    padding: const EdgeInsets.symmetric(vertical: 14),
                    shape: RoundedRectangleBorder(
                      borderRadius: BorderRadius.circular(12),
                    ),
                  ),
                  child: const Text(
                    'فهمت',
                    style: TextStyle(
                      fontSize: 16,
                      fontWeight: FontWeight.bold,
                    ),
                  ),
                ),
              ),
            ],
          ),
        ),
      ),
    );
  }

  Widget _buildSection({
    required String title,
    required IconData icon,
    required Color iconColor,
    required List<PrivacyItem> items,
  }) {
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Row(
          children: [
            Icon(icon, color: iconColor, size: 20),
            const SizedBox(width: 8),
            Text(
              title,
              style: TextStyle(
                fontSize: 15,
                fontWeight: FontWeight.bold,
                color: iconColor,
              ),
            ),
          ],
        ),
        const SizedBox(height: 12),
        ...items.map((item) => _buildPrivacyItem(item)),
      ],
    );
  }

  Widget _buildPrivacyItem(PrivacyItem item) {
    return Padding(
      padding: const EdgeInsets.only(bottom: 10),
      child: Row(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Container(
            padding: const EdgeInsets.all(6),
            decoration: BoxDecoration(
              color: AppColors.background,
              borderRadius: BorderRadius.circular(8),
            ),
            child: Icon(
              item.icon,
              size: 16,
              color: AppColors.textSecondary,
            ),
          ),
          const SizedBox(width: 10),
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(
                  item.title,
                  style: const TextStyle(
                    fontSize: 13,
                    fontWeight: FontWeight.w600,
                    color: AppColors.textPrimary,
                  ),
                ),
                const SizedBox(height: 2),
                Text(
                  item.description,
                  style: const TextStyle(
                    fontSize: 11,
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

  Widget _buildPermissionDetail(PermissionDetailType type) {
    String title;
    String description;
    List<String> technicalDetails;

    switch (type) {
      case PermissionDetailType.accessibility:
        title = 'تفاصيل صلاحية إمكانية الوصول';
        description = 'هذه الصلاحية تمكن GO-ON من قراءة محتوى الشاشة في تطبيقات الرحلات فقط.';
        technicalDetails = [
          'نراقب فقط تطبيقات: Uber, Careem, InDriver, DiDi, Bolt',
          'نقرأ النصوص المعروضة للبحث عن الأسعار بنمط معين (مثل "65 ج.م")',
          'لا نقرأ أي محتوى في التطبيقات الأخرى (واتساب، فيسبوك، إلخ)',
          'الخدمة تعمل محلياً 100% - لا اتصال بالإنترنت مطلوب للقراءة',
        ];
        break;
      case PermissionDetailType.overlay:
        title = 'تفاصيل صلاحية العرض فوق التطبيقات';
        description = 'هذه الصلاحية تمكن GO-ON من عرض فقاعة صغيرة فوق التطبيقات الأخرى.';
        technicalDetails = [
          'الفقاعة تظهر فقط عند اكتشاف سعر في تطبيقات الرحلات',
          'يمكنك سحب الفقاعة لأي مكان على الشاشة',
          'يمكنك إغلاق الفقاعة بالضغط على X',
          'الفقاعة تختفي تلقائياً بعد 10 ثوانٍ',
        ];
        break;
    }

    return Container(
      padding: const EdgeInsets.all(14),
      decoration: BoxDecoration(
        color: AppColors.primary.withOpacity(0.05),
        borderRadius: BorderRadius.circular(12),
        border: Border.all(
          color: AppColors.primary.withOpacity(0.2),
        ),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(
            children: [
              const Icon(
                Icons.info_outline,
                color: AppColors.primary,
                size: 18,
              ),
              const SizedBox(width: 8),
              Text(
                title,
                style: const TextStyle(
                  fontSize: 14,
                  fontWeight: FontWeight.bold,
                  color: AppColors.primary,
                ),
              ),
            ],
          ),
          const SizedBox(height: 10),
          Text(
            description,
            style: const TextStyle(
              fontSize: 12,
              color: AppColors.textSecondary,
            ),
          ),
          const SizedBox(height: 10),
          ...technicalDetails.map((detail) => Padding(
            padding: const EdgeInsets.only(bottom: 6),
            child: Row(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                const Text(
                  '•',
                  style: TextStyle(
                    color: AppColors.primary,
                    fontSize: 12,
                  ),
                ),
                const SizedBox(width: 8),
                Expanded(
                  child: Text(
                    detail,
                    style: const TextStyle(
                      fontSize: 11,
                      color: AppColors.textPrimary,
                      height: 1.3,
                    ),
                  ),
                ),
              ],
            ),
          )),
        ],
      ),
    );
  }
}

/// Permission detail type for focused explanation
enum PermissionDetailType {
  accessibility,
  overlay,
}

/// Privacy item model
class PrivacyItem {
  final IconData icon;
  final String title;
  final String description;

  const PrivacyItem({
    required this.icon,
    required this.title,
    required this.description,
  });
}
