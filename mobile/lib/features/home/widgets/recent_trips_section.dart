import 'package:flutter/material.dart';

import '../../../core/constants/app_colors.dart';

class RecentTripsSection extends StatelessWidget {
  const RecentTripsSection({super.key});

  @override
  Widget build(BuildContext context) {
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Row(
          mainAxisAlignment: MainAxisAlignment.spaceBetween,
          children: [
            const Text(
              'رحلاتك الأخيرة',
              style: TextStyle(
                fontSize: 18,
                fontWeight: FontWeight.bold,
              ),
            ),
            TextButton(
              onPressed: () {
                // TODO: View all trips
              },
              child: const Text('عرض الكل'),
            ),
          ],
        ),
        const SizedBox(height: 12),

        // Recent trips list
        _buildRecentTripItem(
          icon: Icons.work_outline,
          title: 'العمل',
          subtitle: 'التجمع الخامس',
        ),
        const Divider(height: 1),
        _buildRecentTripItem(
          icon: Icons.home_outlined,
          title: 'البيت',
          subtitle: 'المعادي',
        ),
        const Divider(height: 1),
        _buildRecentTripItem(
          icon: Icons.fitness_center_outlined,
          title: 'الجيم',
          subtitle: 'مدينة نصر',
        ),
      ],
    );
  }

  Widget _buildRecentTripItem({
    required IconData icon,
    required String title,
    required String subtitle,
  }) {
    return ListTile(
      contentPadding: EdgeInsets.zero,
      leading: Container(
        padding: const EdgeInsets.all(10),
        decoration: BoxDecoration(
          color: AppColors.background,
          borderRadius: BorderRadius.circular(8),
        ),
        child: Icon(
          icon,
          color: AppColors.textSecondary,
          size: 20,
        ),
      ),
      title: Text(
        title,
        style: const TextStyle(
          fontWeight: FontWeight.w600,
        ),
      ),
      subtitle: Text(
        subtitle,
        style: const TextStyle(
          color: AppColors.textSecondary,
          fontSize: 12,
        ),
      ),
      trailing: const Icon(
        Icons.arrow_forward_ios,
        size: 16,
        color: AppColors.textSecondary,
      ),
      onTap: () {
        // TODO: Navigate to location
      },
    );
  }
}
