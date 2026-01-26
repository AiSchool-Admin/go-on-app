import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../core/services/pricing_strategy_service.dart';
import '../../../core/services/native_services.dart';

/// Widget for configuring pricing mode (client vs server)
class PricingModeSettings extends ConsumerStatefulWidget {
  const PricingModeSettings({super.key});

  @override
  ConsumerState<PricingModeSettings> createState() => _PricingModeSettingsState();
}

class _PricingModeSettingsState extends ConsumerState<PricingModeSettings> {
  bool _isLoading = false;
  bool _accessibilityEnabled = false;
  bool _serverAvailable = false;

  @override
  void initState() {
    super.initState();
    _checkStatus();
  }

  Future<void> _checkStatus() async {
    setState(() => _isLoading = true);

    final nativeServices = ref.read(nativeServicesProvider);
    final pricingService = ref.read(pricingStrategyProvider);

    final accessibilityEnabled = await nativeServices.isAccessibilityEnabled();
    final serverAvailable = pricingService.isServerAvailable;

    setState(() {
      _accessibilityEnabled = accessibilityEnabled;
      _serverAvailable = serverAvailable;
      _isLoading = false;
    });
  }

  @override
  Widget build(BuildContext context) {
    final pricingService = ref.watch(pricingStrategyProvider);
    final currentMode = pricingService.currentMode;

    return Card(
      margin: const EdgeInsets.all(16),
      child: Padding(
        padding: const EdgeInsets.all(16),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            // Header
            Row(
              children: [
                const Icon(Icons.price_change, size: 28),
                const SizedBox(width: 12),
                const Expanded(
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Text(
                        'طريقة جلب الأسعار',
                        style: TextStyle(
                          fontSize: 18,
                          fontWeight: FontWeight.bold,
                        ),
                      ),
                      Text(
                        'اختر كيفية الحصول على أسعار التطبيقات',
                        style: TextStyle(
                          fontSize: 12,
                          color: Colors.grey,
                        ),
                      ),
                    ],
                  ),
                ),
                if (_isLoading)
                  const SizedBox(
                    width: 20,
                    height: 20,
                    child: CircularProgressIndicator(strokeWidth: 2),
                  )
                else
                  IconButton(
                    icon: const Icon(Icons.refresh),
                    onPressed: _checkStatus,
                  ),
              ],
            ),

            const SizedBox(height: 16),
            const Divider(),
            const SizedBox(height: 8),

            // Status indicators
            _buildStatusRow(
              icon: Icons.cloud,
              label: 'السيرفر',
              isAvailable: _serverAvailable,
            ),
            const SizedBox(height: 8),
            _buildStatusRow(
              icon: Icons.phone_android,
              label: 'خدمة الوصول',
              isAvailable: _accessibilityEnabled,
              onTap: !_accessibilityEnabled
                  ? () async {
                      final nativeServices = ref.read(nativeServicesProvider);
                      await nativeServices.openAccessibilitySettings();
                    }
                  : null,
            ),

            const SizedBox(height: 16),
            const Divider(),
            const SizedBox(height: 8),

            // Mode selection
            _buildModeOption(
              mode: PricingMode.auto,
              title: 'تلقائي',
              subtitle: 'يختار الطريقة الأفضل حسب التوفر',
              icon: Icons.auto_awesome,
              currentMode: currentMode,
            ),
            _buildModeOption(
              mode: PricingMode.hybrid,
              title: 'مختلط',
              subtitle: 'يحاول السيرفر أولاً، ثم الجهاز، ثم التقديري',
              icon: Icons.merge_type,
              currentMode: currentMode,
            ),
            _buildModeOption(
              mode: PricingMode.server,
              title: 'من السيرفر',
              subtitle: 'أسرع - يستخدم الأسعار المخزنة',
              icon: Icons.cloud_download,
              currentMode: currentMode,
              enabled: _serverAvailable,
            ),
            _buildModeOption(
              mode: PricingMode.client,
              title: 'من الجهاز',
              subtitle: 'أدق - يفتح التطبيقات ويقرأ الأسعار',
              icon: Icons.phone_android,
              currentMode: currentMode,
              enabled: _accessibilityEnabled,
            ),
            _buildModeOption(
              mode: PricingMode.estimated,
              title: 'تقديري',
              subtitle: 'فوري - يحسب الأسعار بالمعادلات',
              icon: Icons.calculate,
              currentMode: currentMode,
            ),

            const SizedBox(height: 16),

            // Info box
            Container(
              padding: const EdgeInsets.all(12),
              decoration: BoxDecoration(
                color: Colors.blue.shade50,
                borderRadius: BorderRadius.circular(8),
                border: Border.all(color: Colors.blue.shade200),
              ),
              child: Row(
                children: [
                  Icon(Icons.info_outline, color: Colors.blue.shade700),
                  const SizedBox(width: 12),
                  Expanded(
                    child: Text(
                      _getModeDescription(currentMode),
                      style: TextStyle(
                        fontSize: 12,
                        color: Colors.blue.shade800,
                      ),
                    ),
                  ),
                ],
              ),
            ),
          ],
        ),
      ),
    );
  }

  Widget _buildStatusRow({
    required IconData icon,
    required String label,
    required bool isAvailable,
    VoidCallback? onTap,
  }) {
    return InkWell(
      onTap: onTap,
      borderRadius: BorderRadius.circular(8),
      child: Padding(
        padding: const EdgeInsets.symmetric(vertical: 4, horizontal: 8),
        child: Row(
          children: [
            Icon(
              icon,
              size: 20,
              color: isAvailable ? Colors.green : Colors.grey,
            ),
            const SizedBox(width: 8),
            Text(label),
            const Spacer(),
            Container(
              padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 2),
              decoration: BoxDecoration(
                color: isAvailable ? Colors.green.shade100 : Colors.grey.shade200,
                borderRadius: BorderRadius.circular(12),
              ),
              child: Text(
                isAvailable ? 'متاح' : 'غير متاح',
                style: TextStyle(
                  fontSize: 12,
                  color: isAvailable ? Colors.green.shade800 : Colors.grey.shade700,
                ),
              ),
            ),
            if (onTap != null) ...[
              const SizedBox(width: 4),
              Icon(
                Icons.arrow_forward_ios,
                size: 14,
                color: Colors.grey.shade400,
              ),
            ],
          ],
        ),
      ),
    );
  }

  Widget _buildModeOption({
    required PricingMode mode,
    required String title,
    required String subtitle,
    required IconData icon,
    required PricingMode currentMode,
    bool enabled = true,
  }) {
    final isSelected = mode == currentMode;

    return Opacity(
      opacity: enabled ? 1.0 : 0.5,
      child: InkWell(
        onTap: enabled
            ? () async {
                final pricingService = ref.read(pricingStrategyProvider);
                await pricingService.setMode(mode);
                setState(() {});
              }
            : null,
        borderRadius: BorderRadius.circular(8),
        child: Container(
          padding: const EdgeInsets.all(12),
          margin: const EdgeInsets.only(bottom: 8),
          decoration: BoxDecoration(
            color: isSelected ? Colors.blue.shade50 : null,
            borderRadius: BorderRadius.circular(8),
            border: Border.all(
              color: isSelected ? Colors.blue : Colors.grey.shade300,
              width: isSelected ? 2 : 1,
            ),
          ),
          child: Row(
            children: [
              Icon(
                icon,
                color: isSelected ? Colors.blue : Colors.grey,
              ),
              const SizedBox(width: 12),
              Expanded(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text(
                      title,
                      style: TextStyle(
                        fontWeight: isSelected ? FontWeight.bold : FontWeight.normal,
                        color: isSelected ? Colors.blue : null,
                      ),
                    ),
                    Text(
                      subtitle,
                      style: TextStyle(
                        fontSize: 12,
                        color: Colors.grey.shade600,
                      ),
                    ),
                  ],
                ),
              ),
              if (isSelected)
                const Icon(
                  Icons.check_circle,
                  color: Colors.blue,
                ),
            ],
          ),
        ),
      ),
    );
  }

  String _getModeDescription(PricingMode mode) {
    switch (mode) {
      case PricingMode.auto:
        return 'الوضع التلقائي يختار أفضل طريقة متاحة: السيرفر للسرعة، أو الجهاز للدقة، أو التقديري كبديل.';
      case PricingMode.hybrid:
        return 'يحاول جلب الأسعار من السيرفر أولاً، فإن لم يجد يستخدم الجهاز، وأخيراً التقدير.';
      case PricingMode.server:
        return 'يجلب الأسعار من السيرفر مباشرة. أسرع طريقة لكن قد تكون الأسعار قديمة بدقائق.';
      case PricingMode.client:
        return 'يفتح تطبيقات التوصيل على جهازك ويقرأ الأسعار الحقيقية. أدق لكن يحتاج وقت.';
      case PricingMode.estimated:
        return 'يحسب الأسعار بناءً على المسافة والوقت. فوري لكن تقديري وليس دقيق 100%.';
    }
  }
}
