import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../../../core/constants/app_colors.dart';
import '../../../core/routes/app_router.dart';

class RideSearchScreen extends ConsumerStatefulWidget {
  const RideSearchScreen({super.key});

  @override
  ConsumerState<RideSearchScreen> createState() => _RideSearchScreenState();
}

class _RideSearchScreenState extends ConsumerState<RideSearchScreen> {
  final _originController = TextEditingController();
  final _destinationController = TextEditingController();

  @override
  void dispose() {
    _originController.dispose();
    _destinationController.dispose();
    super.dispose();
  }

  void _searchPrices() {
    if (_originController.text.isEmpty || _destinationController.text.isEmpty) {
      ScaffoldMessenger.of(context).showSnackBar(
        const SnackBar(
          content: Text('من فضلك أدخل نقطة الانطلاق والوجهة'),
          backgroundColor: AppColors.error,
        ),
      );
      return;
    }

    // TODO: Pass location data
    context.push(AppRoutes.priceComparison);
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        title: const Text('إلى أين؟'),
        backgroundColor: Colors.transparent,
        elevation: 0,
        foregroundColor: AppColors.textPrimary,
      ),
      body: Column(
        children: [
          // Search Fields
          Container(
            padding: const EdgeInsets.all(16),
            color: AppColors.surface,
            child: Column(
              children: [
                // Origin Field
                TextField(
                  controller: _originController,
                  decoration: InputDecoration(
                    hintText: 'نقطة الانطلاق',
                    prefixIcon: const Icon(
                      Icons.circle,
                      color: AppColors.success,
                      size: 12,
                    ),
                    suffixIcon: IconButton(
                      icon: const Icon(Icons.my_location),
                      onPressed: () {
                        // TODO: Get current location
                        _originController.text = 'موقعي الحالي';
                      },
                    ),
                    border: OutlineInputBorder(
                      borderRadius: BorderRadius.circular(12),
                    ),
                  ),
                ),

                const SizedBox(height: 12),

                // Destination Field
                TextField(
                  controller: _destinationController,
                  decoration: InputDecoration(
                    hintText: 'الوجهة',
                    prefixIcon: const Icon(
                      Icons.location_on,
                      color: AppColors.error,
                      size: 18,
                    ),
                    border: OutlineInputBorder(
                      borderRadius: BorderRadius.circular(12),
                    ),
                  ),
                  onSubmitted: (_) => _searchPrices(),
                ),

                const SizedBox(height: 16),

                // Search Button
                SizedBox(
                  width: double.infinity,
                  height: 52,
                  child: ElevatedButton.icon(
                    onPressed: _searchPrices,
                    icon: const Icon(Icons.search),
                    label: const Text('بحث عن أفضل سعر'),
                  ),
                ),
              ],
            ),
          ),

          // Saved Places
          Expanded(
            child: ListView(
              padding: const EdgeInsets.all(16),
              children: [
                const Text(
                  'الأماكن المحفوظة',
                  style: TextStyle(
                    fontSize: 16,
                    fontWeight: FontWeight.bold,
                  ),
                ),
                const SizedBox(height: 12),
                _buildPlaceItem(
                  icon: Icons.home,
                  title: 'البيت',
                  subtitle: 'المعادي، شارع 9',
                  onTap: () {
                    _destinationController.text = 'المعادي، شارع 9';
                  },
                ),
                _buildPlaceItem(
                  icon: Icons.work,
                  title: 'العمل',
                  subtitle: 'التجمع الخامس، مبنى 5',
                  onTap: () {
                    _destinationController.text = 'التجمع الخامس، مبنى 5';
                  },
                ),
                const Divider(height: 32),
                const Text(
                  'الأماكن الأخيرة',
                  style: TextStyle(
                    fontSize: 16,
                    fontWeight: FontWeight.bold,
                  ),
                ),
                const SizedBox(height: 12),
                _buildPlaceItem(
                  icon: Icons.history,
                  title: 'سيتي ستارز',
                  subtitle: 'مدينة نصر',
                  onTap: () {
                    _destinationController.text = 'سيتي ستارز، مدينة نصر';
                  },
                ),
                _buildPlaceItem(
                  icon: Icons.history,
                  title: 'مول مصر',
                  subtitle: '6 أكتوبر',
                  onTap: () {
                    _destinationController.text = 'مول مصر، 6 أكتوبر';
                  },
                ),
              ],
            ),
          ),
        ],
      ),
    );
  }

  Widget _buildPlaceItem({
    required IconData icon,
    required String title,
    required String subtitle,
    required VoidCallback onTap,
  }) {
    return ListTile(
      contentPadding: EdgeInsets.zero,
      leading: Container(
        padding: const EdgeInsets.all(10),
        decoration: BoxDecoration(
          color: AppColors.background,
          borderRadius: BorderRadius.circular(8),
        ),
        child: Icon(icon, color: AppColors.primary, size: 20),
      ),
      title: Text(title),
      subtitle: Text(
        subtitle,
        style: const TextStyle(
          color: AppColors.textSecondary,
          fontSize: 12,
        ),
      ),
      onTap: onTap,
    );
  }
}
