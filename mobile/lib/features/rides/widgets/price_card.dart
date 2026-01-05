import 'package:flutter/material.dart';

import '../../../core/constants/app_colors.dart';
import '../../../models/price_option.dart';
import '../../../models/ride.dart';

class PriceCard extends StatelessWidget {
  final PriceOption option;
  final VoidCallback onTap;

  const PriceCard({
    super.key,
    required this.option,
    required this.onTap,
  });

  @override
  Widget build(BuildContext context) {
    return GestureDetector(
      onTap: onTap,
      child: Container(
        padding: const EdgeInsets.all(16),
        decoration: BoxDecoration(
          color: AppColors.surface,
          borderRadius: BorderRadius.circular(16),
          border: option.isBestPrice
              ? Border.all(color: AppColors.success, width: 2)
              : null,
          boxShadow: [
            BoxShadow(
              color: AppColors.shadow,
              blurRadius: 8,
              offset: const Offset(0, 2),
            ),
          ],
        ),
        child: Column(
          children: [
            // Best Price Badge
            if (option.isBestPrice)
              Container(
                width: double.infinity,
                padding: const EdgeInsets.symmetric(vertical: 6),
                margin: const EdgeInsets.only(bottom: 12),
                decoration: BoxDecoration(
                  color: AppColors.success.withOpacity(0.1),
                  borderRadius: BorderRadius.circular(8),
                ),
                child: const Row(
                  mainAxisAlignment: MainAxisAlignment.center,
                  children: [
                    Icon(Icons.star, color: AppColors.success, size: 16),
                    SizedBox(width: 4),
                    Text(
                      'أفضل سعر',
                      style: TextStyle(
                        color: AppColors.success,
                        fontWeight: FontWeight.bold,
                        fontSize: 12,
                      ),
                    ),
                  ],
                ),
              ),

            // Main Content
            Row(
              children: [
                // Source Icon
                Container(
                  width: 48,
                  height: 48,
                  decoration: BoxDecoration(
                    color: _getSourceColor(option.source).withOpacity(0.1),
                    borderRadius: BorderRadius.circular(12),
                  ),
                  child: Icon(
                    _getSourceIcon(option.source),
                    color: _getSourceColor(option.source),
                  ),
                ),

                const SizedBox(width: 12),

                // Info
                Expanded(
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Text(
                        option.source.displayName,
                        style: const TextStyle(
                          fontWeight: FontWeight.bold,
                          fontSize: 16,
                        ),
                      ),
                      const SizedBox(height: 4),
                      Row(
                        children: [
                          if (option.vehicleType != null) ...[
                            Text(
                              option.vehicleType!,
                              style: const TextStyle(
                                color: AppColors.textSecondary,
                                fontSize: 12,
                              ),
                            ),
                            const Text(' • ', style: TextStyle(color: AppColors.textSecondary)),
                          ],
                          Icon(Icons.access_time, size: 12, color: AppColors.textSecondary),
                          const SizedBox(width: 2),
                          Text(
                            option.formattedEta,
                            style: const TextStyle(
                              color: AppColors.textSecondary,
                              fontSize: 12,
                            ),
                          ),
                        ],
                      ),
                      // Driver info for independent
                      if (option.source == RideSource.independent &&
                          option.driverName != null) ...[
                        const SizedBox(height: 4),
                        Row(
                          children: [
                            const Icon(Icons.star, color: AppColors.secondary, size: 14),
                            const SizedBox(width: 2),
                            Text(
                              '${option.driverRating}',
                              style: const TextStyle(fontSize: 12),
                            ),
                            const SizedBox(width: 8),
                            Text(
                              '${option.driverTotalRides} رحلة',
                              style: const TextStyle(
                                color: AppColors.textSecondary,
                                fontSize: 12,
                              ),
                            ),
                          ],
                        ),
                      ],
                    ],
                  ),
                ),

                // Price
                Column(
                  crossAxisAlignment: CrossAxisAlignment.end,
                  children: [
                    if (option.hasDiscount)
                      Text(
                        '${option.originalPrice!.toStringAsFixed(0)} ج.م',
                        style: const TextStyle(
                          color: AppColors.textSecondary,
                          fontSize: 12,
                          decoration: TextDecoration.lineThrough,
                        ),
                      ),
                    Text(
                      option.formattedPrice,
                      style: TextStyle(
                        fontSize: 20,
                        fontWeight: FontWeight.bold,
                        color: option.isBestPrice
                            ? AppColors.success
                            : AppColors.primary,
                      ),
                    ),
                    if (option.hasDiscount)
                      Container(
                        padding: const EdgeInsets.symmetric(
                          horizontal: 6,
                          vertical: 2,
                        ),
                        decoration: BoxDecoration(
                          color: AppColors.error.withOpacity(0.1),
                          borderRadius: BorderRadius.circular(4),
                        ),
                        child: Text(
                          '-${option.discountPercent}%',
                          style: const TextStyle(
                            color: AppColors.error,
                            fontSize: 10,
                            fontWeight: FontWeight.bold,
                          ),
                        ),
                      ),
                  ],
                ),
              ],
            ),
          ],
        ),
      ),
    );
  }

  IconData _getSourceIcon(RideSource source) {
    switch (source) {
      case RideSource.uber:
        return Icons.directions_car;
      case RideSource.careem:
        return Icons.local_taxi;
      case RideSource.indriver:
        return Icons.car_rental;
      case RideSource.independent:
        return Icons.person;
      case RideSource.goOn:
        return Icons.star;
    }
  }

  Color _getSourceColor(RideSource source) {
    switch (source) {
      case RideSource.uber:
        return AppColors.uber;
      case RideSource.careem:
        return AppColors.careem;
      case RideSource.indriver:
        return AppColors.indriver;
      case RideSource.independent:
        return AppColors.independent;
      case RideSource.goOn:
        return AppColors.primary;
    }
  }
}
