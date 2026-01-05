import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../core/constants/app_colors.dart';
import '../../../models/price_option.dart';
import '../../../models/ride.dart';
import '../widgets/price_card.dart';

class PriceComparisonScreen extends ConsumerStatefulWidget {
  const PriceComparisonScreen({super.key});

  @override
  ConsumerState<PriceComparisonScreen> createState() =>
      _PriceComparisonScreenState();
}

class _PriceComparisonScreenState extends ConsumerState<PriceComparisonScreen> {
  bool _isLoading = true;
  List<PriceOption> _prices = [];

  @override
  void initState() {
    super.initState();
    _loadPrices();
  }

  Future<void> _loadPrices() async {
    // Simulate loading prices
    await Future.delayed(const Duration(seconds: 2));

    // Mock data - will be replaced with actual API calls
    setState(() {
      _prices = [
        PriceOption(
          source: RideSource.independent,
          price: 65,
          etaMinutes: 5,
          driverName: 'أحمد محمد',
          driverRating: 4.8,
          driverTotalRides: 230,
          vehicleType: 'تويوتا كورولا',
        ),
        PriceOption(
          source: RideSource.indriver,
          price: 75,
          etaMinutes: 4,
          vehicleType: 'Economy',
        ),
        PriceOption(
          source: RideSource.careem,
          price: 90,
          etaMinutes: 4,
          vehicleType: 'Go',
        ),
        PriceOption(
          source: RideSource.uber,
          price: 95,
          etaMinutes: 3,
          vehicleType: 'UberX',
          originalPrice: 110,
        ),
      ].sortByPrice();
      _isLoading = false;
    });
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        title: const Text('مقارنة الأسعار'),
        backgroundColor: Colors.transparent,
        elevation: 0,
        foregroundColor: AppColors.textPrimary,
      ),
      body: Column(
        children: [
          // Trip Summary
          Container(
            padding: const EdgeInsets.all(16),
            color: AppColors.surface,
            child: Column(
              children: [
                Row(
                  children: [
                    Column(
                      children: [
                        Container(
                          width: 12,
                          height: 12,
                          decoration: const BoxDecoration(
                            color: AppColors.success,
                            shape: BoxShape.circle,
                          ),
                        ),
                        Container(
                          width: 2,
                          height: 30,
                          color: AppColors.border,
                        ),
                        const Icon(
                          Icons.location_on,
                          color: AppColors.error,
                          size: 16,
                        ),
                      ],
                    ),
                    const SizedBox(width: 12),
                    const Expanded(
                      child: Column(
                        crossAxisAlignment: CrossAxisAlignment.start,
                        children: [
                          Text(
                            'المعادي',
                            style: TextStyle(fontWeight: FontWeight.w600),
                          ),
                          SizedBox(height: 20),
                          Text(
                            'التجمع الخامس',
                            style: TextStyle(fontWeight: FontWeight.w600),
                          ),
                        ],
                      ),
                    ),
                  ],
                ),
                const SizedBox(height: 12),
                const Row(
                  mainAxisAlignment: MainAxisAlignment.center,
                  children: [
                    Icon(Icons.straighten, size: 16, color: AppColors.textSecondary),
                    SizedBox(width: 4),
                    Text(
                      '25 كم',
                      style: TextStyle(color: AppColors.textSecondary),
                    ),
                    SizedBox(width: 16),
                    Icon(Icons.access_time, size: 16, color: AppColors.textSecondary),
                    SizedBox(width: 4),
                    Text(
                      '35 دقيقة',
                      style: TextStyle(color: AppColors.textSecondary),
                    ),
                  ],
                ),
              ],
            ),
          ),

          // Price List
          Expanded(
            child: _isLoading
                ? const Center(
                    child: Column(
                      mainAxisAlignment: MainAxisAlignment.center,
                      children: [
                        CircularProgressIndicator(),
                        SizedBox(height: 16),
                        Text('جاري البحث عن أفضل الأسعار...'),
                      ],
                    ),
                  )
                : ListView.builder(
                    padding: const EdgeInsets.all(16),
                    itemCount: _prices.length,
                    itemBuilder: (context, index) {
                      final price = _prices[index];
                      return Padding(
                        padding: const EdgeInsets.only(bottom: 12),
                        child: PriceCard(
                          option: price,
                          onTap: () => _onPriceSelected(price),
                        ),
                      );
                    },
                  ),
          ),
        ],
      ),
    );
  }

  void _onPriceSelected(PriceOption option) {
    showModalBottomSheet(
      context: context,
      builder: (context) => Container(
        padding: const EdgeInsets.all(24),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            Text(
              'حجز مع ${option.source.displayName}',
              style: const TextStyle(
                fontSize: 20,
                fontWeight: FontWeight.bold,
              ),
            ),
            const SizedBox(height: 16),
            Row(
              mainAxisAlignment: MainAxisAlignment.spaceBetween,
              children: [
                const Text('السعر المتوقع'),
                Text(
                  option.formattedPrice,
                  style: const TextStyle(
                    fontSize: 24,
                    fontWeight: FontWeight.bold,
                    color: AppColors.primary,
                  ),
                ),
              ],
            ),
            const SizedBox(height: 24),
            ElevatedButton(
              onPressed: () {
                Navigator.pop(context);
                // TODO: Book the ride
                ScaffoldMessenger.of(context).showSnackBar(
                  const SnackBar(
                    content: Text('جاري الحجز...'),
                    backgroundColor: AppColors.success,
                  ),
                );
              },
              child: const Text('تأكيد الحجز'),
            ),
            const SizedBox(height: 12),
            OutlinedButton(
              onPressed: () => Navigator.pop(context),
              child: const Text('إلغاء'),
            ),
          ],
        ),
      ),
    );
  }
}
