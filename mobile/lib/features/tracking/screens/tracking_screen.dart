import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../core/constants/app_colors.dart';

class TrackingScreen extends ConsumerWidget {
  final String trackingId;

  const TrackingScreen({super.key, required this.trackingId});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    return Scaffold(
      appBar: AppBar(
        title: Text('تتبع #$trackingId'),
        backgroundColor: Colors.transparent,
        elevation: 0,
        foregroundColor: AppColors.textPrimary,
      ),
      body: Column(
        children: [
          // Map Placeholder
          Expanded(
            flex: 2,
            child: Container(
              color: AppColors.background,
              child: const Center(
                child: Column(
                  mainAxisAlignment: MainAxisAlignment.center,
                  children: [
                    Icon(
                      Icons.map,
                      size: 64,
                      color: AppColors.textSecondary,
                    ),
                    SizedBox(height: 16),
                    Text(
                      'خريطة التتبع الحي',
                      style: TextStyle(color: AppColors.textSecondary),
                    ),
                  ],
                ),
              ),
            ),
          ),

          // Tracking Details
          Expanded(
            flex: 1,
            child: Container(
              padding: const EdgeInsets.all(16),
              decoration: const BoxDecoration(
                color: AppColors.surface,
                borderRadius: BorderRadius.vertical(top: Radius.circular(24)),
              ),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  // Status
                  Row(
                    children: [
                      Container(
                        padding: const EdgeInsets.symmetric(
                          horizontal: 12,
                          vertical: 6,
                        ),
                        decoration: BoxDecoration(
                          color: AppColors.success.withOpacity(0.1),
                          borderRadius: BorderRadius.circular(20),
                        ),
                        child: const Row(
                          mainAxisSize: MainAxisSize.min,
                          children: [
                            Icon(
                              Icons.local_shipping,
                              size: 16,
                              color: AppColors.success,
                            ),
                            SizedBox(width: 4),
                            Text(
                              'في الطريق',
                              style: TextStyle(
                                color: AppColors.success,
                                fontWeight: FontWeight.bold,
                              ),
                            ),
                          ],
                        ),
                      ),
                    ],
                  ),

                  const SizedBox(height: 16),

                  // Progress Bar
                  Row(
                    children: [
                      _buildProgressDot(true),
                      Expanded(child: _buildProgressLine(true)),
                      _buildProgressDot(true),
                      Expanded(child: _buildProgressLine(false)),
                      _buildProgressDot(false),
                    ],
                  ),
                  const SizedBox(height: 8),
                  const Row(
                    mainAxisAlignment: MainAxisAlignment.spaceBetween,
                    children: [
                      Text('استلام', style: TextStyle(fontSize: 12)),
                      Text('الآن', style: TextStyle(fontSize: 12)),
                      Text('تسليم', style: TextStyle(fontSize: 12)),
                    ],
                  ),

                  const Divider(height: 32),

                  // ETA
                  const Row(
                    children: [
                      Icon(Icons.access_time, color: AppColors.textSecondary),
                      SizedBox(width: 8),
                      Text('الوقت المتوقع: '),
                      Text(
                        '12 دقيقة',
                        style: TextStyle(fontWeight: FontWeight.bold),
                      ),
                    ],
                  ),

                  const SizedBox(height: 12),

                  // Driver Info
                  Row(
                    children: [
                      CircleAvatar(
                        backgroundColor: AppColors.primary.withOpacity(0.1),
                        child: const Icon(Icons.person, color: AppColors.primary),
                      ),
                      const SizedBox(width: 12),
                      const Expanded(
                        child: Column(
                          crossAxisAlignment: CrossAxisAlignment.start,
                          children: [
                            Text(
                              'أحمد محمد',
                              style: TextStyle(fontWeight: FontWeight.bold),
                            ),
                            Row(
                              children: [
                                Icon(Icons.star, color: AppColors.secondary, size: 14),
                                Text(' 4.9'),
                              ],
                            ),
                          ],
                        ),
                      ),
                      IconButton(
                        icon: const Icon(Icons.phone, color: AppColors.primary),
                        onPressed: () {
                          // TODO: Call driver
                        },
                      ),
                      IconButton(
                        icon: const Icon(Icons.chat, color: AppColors.primary),
                        onPressed: () {
                          // TODO: Chat with driver
                        },
                      ),
                    ],
                  ),
                ],
              ),
            ),
          ),
        ],
      ),
    );
  }

  Widget _buildProgressDot(bool isCompleted) {
    return Container(
      width: 16,
      height: 16,
      decoration: BoxDecoration(
        color: isCompleted ? AppColors.success : AppColors.border,
        shape: BoxShape.circle,
      ),
      child: isCompleted
          ? const Icon(Icons.check, size: 12, color: Colors.white)
          : null,
    );
  }

  Widget _buildProgressLine(bool isCompleted) {
    return Container(
      height: 3,
      color: isCompleted ? AppColors.success : AppColors.border,
    );
  }
}
