import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../models/user_profile.dart';
import '../providers/ride_preference_provider.dart';

/// Widget to select ride sorting preference
/// Can be used in settings screen or as a bottom sheet
class RideSortPreferenceSelector extends ConsumerWidget {
  final String language;
  final bool showTitle;

  const RideSortPreferenceSelector({
    super.key,
    this.language = 'ar',
    this.showTitle = true,
  });

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final currentPreference = ref.watch(rideSortPreferenceProvider);
    final notifier = ref.read(rideSortPreferenceProvider.notifier);

    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      mainAxisSize: MainAxisSize.min,
      children: [
        if (showTitle) ...[
          Padding(
            padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 8),
            child: Text(
              language == 'ar' ? 'اختر طريقة الفرز' : 'Select Sorting Method',
              style: Theme.of(context).textTheme.titleMedium?.copyWith(
                    fontWeight: FontWeight.bold,
                  ),
            ),
          ),
          const Divider(),
        ],
        ...RideSortPreference.values.map((preference) {
          final isSelected = preference == currentPreference;
          return _PreferenceOption(
            preference: preference,
            isSelected: isSelected,
            language: language,
            onTap: () => notifier.setPreference(preference),
          );
        }),
      ],
    );
  }

  /// Show as bottom sheet
  static Future<void> showAsBottomSheet(
    BuildContext context, {
    String language = 'ar',
  }) {
    return showModalBottomSheet(
      context: context,
      shape: const RoundedRectangleBorder(
        borderRadius: BorderRadius.vertical(top: Radius.circular(20)),
      ),
      builder: (context) => Padding(
        padding: const EdgeInsets.only(bottom: 24),
        child: RideSortPreferenceSelector(language: language),
      ),
    );
  }
}

class _PreferenceOption extends StatelessWidget {
  final RideSortPreference preference;
  final bool isSelected;
  final String language;
  final VoidCallback onTap;

  const _PreferenceOption({
    required this.preference,
    required this.isSelected,
    required this.language,
    required this.onTap,
  });

  @override
  Widget build(BuildContext context) {
    final icon = _getIcon(preference);
    final color = isSelected
        ? Theme.of(context).colorScheme.primary
        : Theme.of(context).colorScheme.onSurface;

    return ListTile(
      leading: Icon(icon, color: color),
      title: Text(
        preference.getLabel(language),
        style: TextStyle(
          color: color,
          fontWeight: isSelected ? FontWeight.bold : FontWeight.normal,
        ),
      ),
      subtitle: Text(
        _getDescription(preference, language),
        style: TextStyle(
          fontSize: 12,
          color: color.withOpacity(0.7),
        ),
      ),
      trailing: isSelected
          ? Icon(Icons.check_circle, color: Theme.of(context).colorScheme.primary)
          : Icon(Icons.circle_outlined, color: Colors.grey.shade400),
      onTap: onTap,
    );
  }

  IconData _getIcon(RideSortPreference preference) {
    switch (preference) {
      case RideSortPreference.lowestPrice:
        return Icons.savings_outlined;
      case RideSortPreference.bestService:
        return Icons.star_outline;
      case RideSortPreference.fastestArrival:
        return Icons.speed;
    }
  }

  String _getDescription(RideSortPreference preference, String language) {
    switch (preference) {
      case RideSortPreference.lowestPrice:
        return language == 'ar'
            ? 'اختر الخيار الأرخص دائماً'
            : 'Always select the cheapest option';
      case RideSortPreference.bestService:
        return language == 'ar'
            ? 'اختر بناءً على تقييم الخدمة'
            : 'Select based on service rating';
      case RideSortPreference.fastestArrival:
        return language == 'ar'
            ? 'اختر الأسرع وصولاً إليك'
            : 'Select the fastest to arrive';
    }
  }
}

/// Compact chip version for inline display
class RideSortPreferenceChip extends ConsumerWidget {
  final String language;

  const RideSortPreferenceChip({
    super.key,
    this.language = 'ar',
  });

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final currentPreference = ref.watch(rideSortPreferenceProvider);

    return ActionChip(
      avatar: Icon(
        _getIcon(currentPreference),
        size: 18,
      ),
      label: Text(currentPreference.getLabel(language)),
      onPressed: () =>
          RideSortPreferenceSelector.showAsBottomSheet(context, language: language),
    );
  }

  IconData _getIcon(RideSortPreference preference) {
    switch (preference) {
      case RideSortPreference.lowestPrice:
        return Icons.savings_outlined;
      case RideSortPreference.bestService:
        return Icons.star_outline;
      case RideSortPreference.fastestArrival:
        return Icons.speed;
    }
  }
}
