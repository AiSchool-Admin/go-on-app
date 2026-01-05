import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../core/constants/app_colors.dart';

class FreightScreen extends ConsumerWidget {
  const FreightScreen({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    return Scaffold(
      appBar: AppBar(
        title: const Text('شحن طرد'),
        backgroundColor: Colors.transparent,
        elevation: 0,
        foregroundColor: AppColors.textPrimary,
      ),
      body: SingleChildScrollView(
        padding: const EdgeInsets.all(16),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            // Package Type Selection
            const Text(
              'نوع الطرد',
              style: TextStyle(
                fontSize: 16,
                fontWeight: FontWeight.bold,
              ),
            ),
            const SizedBox(height: 12),
            Row(
              children: [
                _PackageTypeCard(
                  icon: Icons.description,
                  title: 'مستندات',
                  isSelected: true,
                  onTap: () {},
                ),
                const SizedBox(width: 12),
                _PackageTypeCard(
                  icon: Icons.inventory_2,
                  title: 'صندوق صغير',
                  isSelected: false,
                  onTap: () {},
                ),
                const SizedBox(width: 12),
                _PackageTypeCard(
                  icon: Icons.widgets,
                  title: 'صندوق كبير',
                  isSelected: false,
                  onTap: () {},
                ),
              ],
            ),

            const SizedBox(height: 24),

            // Pickup Location
            const Text(
              'مكان الاستلام',
              style: TextStyle(
                fontSize: 16,
                fontWeight: FontWeight.bold,
              ),
            ),
            const SizedBox(height: 12),
            TextField(
              decoration: InputDecoration(
                hintText: 'أدخل عنوان الاستلام',
                prefixIcon: const Icon(Icons.location_on, color: AppColors.success),
                border: OutlineInputBorder(
                  borderRadius: BorderRadius.circular(12),
                ),
              ),
            ),

            const SizedBox(height: 16),

            // Delivery Location
            const Text(
              'مكان التسليم',
              style: TextStyle(
                fontSize: 16,
                fontWeight: FontWeight.bold,
              ),
            ),
            const SizedBox(height: 12),
            TextField(
              decoration: InputDecoration(
                hintText: 'أدخل عنوان التسليم',
                prefixIcon: const Icon(Icons.location_on, color: AppColors.error),
                border: OutlineInputBorder(
                  borderRadius: BorderRadius.circular(12),
                ),
              ),
            ),

            const SizedBox(height: 16),

            // Receiver Info
            const Text(
              'بيانات المستلم',
              style: TextStyle(
                fontSize: 16,
                fontWeight: FontWeight.bold,
              ),
            ),
            const SizedBox(height: 12),
            TextField(
              decoration: InputDecoration(
                hintText: 'اسم المستلم',
                prefixIcon: const Icon(Icons.person),
                border: OutlineInputBorder(
                  borderRadius: BorderRadius.circular(12),
                ),
              ),
            ),
            const SizedBox(height: 12),
            TextField(
              keyboardType: TextInputType.phone,
              decoration: InputDecoration(
                hintText: 'رقم هاتف المستلم',
                prefixIcon: const Icon(Icons.phone),
                border: OutlineInputBorder(
                  borderRadius: BorderRadius.circular(12),
                ),
              ),
            ),

            const SizedBox(height: 24),

            // Options
            const Text(
              'خيارات إضافية',
              style: TextStyle(
                fontSize: 16,
                fontWeight: FontWeight.bold,
              ),
            ),
            const SizedBox(height: 12),
            CheckboxListTile(
              value: false,
              onChanged: (_) {},
              title: const Text('الدفع عند الاستلام (COD)'),
              subtitle: const Text('المستلم يدفع عند التسليم'),
              controlAffinity: ListTileControlAffinity.leading,
              contentPadding: EdgeInsets.zero,
            ),
            CheckboxListTile(
              value: false,
              onChanged: (_) {},
              title: const Text('طرد هش'),
              subtitle: const Text('يحتاج عناية خاصة'),
              controlAffinity: ListTileControlAffinity.leading,
              contentPadding: EdgeInsets.zero,
            ),

            const SizedBox(height: 24),

            // Submit Button
            ElevatedButton(
              onPressed: () {
                // TODO: Compare shipping prices
              },
              child: const Text('مقارنة الأسعار'),
            ),
          ],
        ),
      ),
    );
  }
}

class _PackageTypeCard extends StatelessWidget {
  final IconData icon;
  final String title;
  final bool isSelected;
  final VoidCallback onTap;

  const _PackageTypeCard({
    required this.icon,
    required this.title,
    required this.isSelected,
    required this.onTap,
  });

  @override
  Widget build(BuildContext context) {
    return Expanded(
      child: GestureDetector(
        onTap: onTap,
        child: Container(
          padding: const EdgeInsets.all(16),
          decoration: BoxDecoration(
            color: isSelected ? AppColors.primary.withOpacity(0.1) : AppColors.surface,
            borderRadius: BorderRadius.circular(12),
            border: Border.all(
              color: isSelected ? AppColors.primary : AppColors.border,
              width: isSelected ? 2 : 1,
            ),
          ),
          child: Column(
            children: [
              Icon(
                icon,
                color: isSelected ? AppColors.primary : AppColors.textSecondary,
                size: 32,
              ),
              const SizedBox(height: 8),
              Text(
                title,
                style: TextStyle(
                  fontSize: 12,
                  fontWeight: isSelected ? FontWeight.bold : FontWeight.normal,
                  color: isSelected ? AppColors.primary : AppColors.textSecondary,
                ),
                textAlign: TextAlign.center,
              ),
            ],
          ),
        ),
      ),
    );
  }
}
