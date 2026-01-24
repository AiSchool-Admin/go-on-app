import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:google_maps_flutter/google_maps_flutter.dart';

import '../../../core/constants/app_colors.dart';
import '../services/places_autocomplete_service.dart';

/// Bottom sheet for searching and selecting a place
class PlaceSearchSheet extends ConsumerStatefulWidget {
  final bool isOrigin;
  final LatLng? currentLocation;
  final Map<String, Map<String, dynamic>> savedPlaces;
  final VoidCallback onCurrentLocationTap;
  final VoidCallback onMapPickerTap;
  final Function(String name, LatLng location, String address) onPlaceSelected;

  const PlaceSearchSheet({
    super.key,
    required this.isOrigin,
    this.currentLocation,
    required this.savedPlaces,
    required this.onCurrentLocationTap,
    required this.onMapPickerTap,
    required this.onPlaceSelected,
  });

  @override
  ConsumerState<PlaceSearchSheet> createState() => _PlaceSearchSheetState();
}

class _PlaceSearchSheetState extends ConsumerState<PlaceSearchSheet> {
  final _searchController = TextEditingController();
  final _focusNode = FocusNode();
  bool _isLoadingDetails = false;

  @override
  void initState() {
    super.initState();
    // Set user location for better autocomplete results
    WidgetsBinding.instance.addPostFrameCallback((_) {
      if (widget.currentLocation != null) {
        ref.read(autocompleteProvider.notifier).setUserLocation(widget.currentLocation);
      }
    });
  }

  @override
  void dispose() {
    _searchController.dispose();
    _focusNode.dispose();
    super.dispose();
  }

  void _onSearchChanged(String query) {
    ref.read(autocompleteProvider.notifier).search(query);
  }

  Future<void> _onPredictionTap(PlacePrediction prediction) async {
    setState(() => _isLoadingDetails = true);

    try {
      final service = ref.read(placesAutocompleteServiceProvider);
      final details = await service.getPlaceDetails(prediction.placeId);

      if (details != null && mounted) {
        widget.onPlaceSelected(
          prediction.mainText,
          details.location,
          details.address,
        );
        Navigator.pop(context);
      } else {
        _showError('فشل في تحديد الموقع');
      }
    } catch (e) {
      _showError('حدث خطأ: $e');
    } finally {
      if (mounted) {
        setState(() => _isLoadingDetails = false);
      }
    }
  }

  void _showError(String message) {
    ScaffoldMessenger.of(context).showSnackBar(
      SnackBar(content: Text(message), backgroundColor: AppColors.error),
    );
  }

  @override
  Widget build(BuildContext context) {
    final autocompleteState = ref.watch(autocompleteProvider);
    final hasSearchQuery = _searchController.text.isNotEmpty;

    return Container(
      decoration: const BoxDecoration(
        color: AppColors.surface,
        borderRadius: BorderRadius.vertical(top: Radius.circular(20)),
      ),
      child: Column(
        mainAxisSize: MainAxisSize.min,
        children: [
          // Drag handle
          Container(
            width: 40,
            height: 4,
            margin: const EdgeInsets.symmetric(vertical: 12),
            decoration: BoxDecoration(
              color: AppColors.divider,
              borderRadius: BorderRadius.circular(2),
            ),
          ),

          // Title
          Padding(
            padding: const EdgeInsets.symmetric(horizontal: 16),
            child: Text(
              widget.isOrigin ? 'اختر نقطة الانطلاق' : 'اختر الوجهة',
              style: const TextStyle(
                fontSize: 18,
                fontWeight: FontWeight.bold,
              ),
            ),
          ),

          const SizedBox(height: 16),

          // Search TextField
          Padding(
            padding: const EdgeInsets.symmetric(horizontal: 16),
            child: TextField(
              controller: _searchController,
              focusNode: _focusNode,
              autofocus: true,
              textDirection: TextDirection.rtl,
              onChanged: _onSearchChanged,
              decoration: InputDecoration(
                hintText: 'ابحث عن مكان...',
                hintTextDirection: TextDirection.rtl,
                prefixIcon: const Icon(Icons.search, color: AppColors.textSecondary),
                suffixIcon: hasSearchQuery
                    ? IconButton(
                        icon: const Icon(Icons.clear, color: AppColors.textSecondary),
                        onPressed: () {
                          _searchController.clear();
                          ref.read(autocompleteProvider.notifier).clear();
                        },
                      )
                    : null,
                border: OutlineInputBorder(
                  borderRadius: BorderRadius.circular(12),
                  borderSide: BorderSide.none,
                ),
                filled: true,
                fillColor: AppColors.background,
                contentPadding: const EdgeInsets.symmetric(horizontal: 16, vertical: 14),
              ),
            ),
          ),

          const SizedBox(height: 12),

          // Loading indicator for place details
          if (_isLoadingDetails)
            const Padding(
              padding: EdgeInsets.all(16),
              child: CircularProgressIndicator(),
            )
          else
            Expanded(
              child: hasSearchQuery
                  ? _buildSearchResults(autocompleteState)
                  : _buildDefaultContent(),
            ),
        ],
      ),
    );
  }

  Widget _buildSearchResults(AutocompleteState state) {
    if (state.isLoading) {
      return const Center(
        child: Padding(
          padding: EdgeInsets.all(32),
          child: CircularProgressIndicator(),
        ),
      );
    }

    if (state.predictions.isEmpty) {
      return Center(
        child: Padding(
          padding: const EdgeInsets.all(32),
          child: Column(
            mainAxisSize: MainAxisSize.min,
            children: [
              Icon(
                Icons.search_off,
                size: 48,
                color: AppColors.textSecondary.withOpacity(0.5),
              ),
              const SizedBox(height: 16),
              const Text(
                'لا توجد نتائج',
                style: TextStyle(
                  color: AppColors.textSecondary,
                  fontSize: 16,
                ),
              ),
              const SizedBox(height: 8),
              TextButton.icon(
                onPressed: () {
                  Navigator.pop(context);
                  widget.onMapPickerTap();
                },
                icon: const Icon(Icons.map),
                label: const Text('اختر من الخريطة'),
              ),
            ],
          ),
        ),
      );
    }

    return ListView.separated(
      padding: const EdgeInsets.symmetric(horizontal: 16),
      itemCount: state.predictions.length,
      separatorBuilder: (context, index) => const Divider(height: 1),
      itemBuilder: (context, index) {
        final prediction = state.predictions[index];
        return _buildPredictionTile(prediction);
      },
    );
  }

  Widget _buildPredictionTile(PlacePrediction prediction) {
    return ListTile(
      contentPadding: const EdgeInsets.symmetric(vertical: 4),
      leading: Container(
        padding: const EdgeInsets.all(10),
        decoration: BoxDecoration(
          color: (widget.isOrigin ? AppColors.success : AppColors.error).withOpacity(0.1),
          borderRadius: BorderRadius.circular(8),
        ),
        child: Icon(
          Icons.location_on,
          color: widget.isOrigin ? AppColors.success : AppColors.error,
          size: 20,
        ),
      ),
      title: Text(
        prediction.mainText,
        style: const TextStyle(fontWeight: FontWeight.w500),
        maxLines: 1,
        overflow: TextOverflow.ellipsis,
      ),
      subtitle: prediction.secondaryText != null
          ? Text(
              prediction.secondaryText!,
              style: const TextStyle(
                color: AppColors.textSecondary,
                fontSize: 12,
              ),
              maxLines: 1,
              overflow: TextOverflow.ellipsis,
            )
          : null,
      onTap: () => _onPredictionTap(prediction),
    );
  }

  Widget _buildDefaultContent() {
    return ListView(
      padding: const EdgeInsets.symmetric(horizontal: 16),
      children: [
        // Map Picker Button
        SizedBox(
          width: double.infinity,
          child: ElevatedButton.icon(
            onPressed: () {
              Navigator.pop(context);
              widget.onMapPickerTap();
            },
            icon: const Icon(Icons.map),
            label: const Text('اختر من الخريطة'),
            style: ElevatedButton.styleFrom(
              backgroundColor: widget.isOrigin ? AppColors.success : AppColors.primary,
              foregroundColor: Colors.white,
              padding: const EdgeInsets.symmetric(vertical: 14),
              shape: RoundedRectangleBorder(
                borderRadius: BorderRadius.circular(12),
              ),
            ),
          ),
        ),

        const SizedBox(height: 16),
        const Divider(height: 1),
        const SizedBox(height: 12),

        // Current Location option (for origin only)
        if (widget.isOrigin) ...[
          _buildPlaceOption(
            icon: Icons.my_location,
            title: 'موقعي الحالي',
            subtitle: 'استخدم GPS',
            iconColor: AppColors.primary,
            onTap: () {
              Navigator.pop(context);
              widget.onCurrentLocationTap();
            },
          ),
          const Divider(),
        ],

        // Saved Places header
        const Text(
          'الأماكن المحفوظة',
          style: TextStyle(
            fontSize: 14,
            fontWeight: FontWeight.bold,
            color: AppColors.textSecondary,
          ),
        ),
        const SizedBox(height: 8),

        // Saved places list
        ...widget.savedPlaces.entries.map((entry) => _buildPlaceOption(
          icon: Icons.location_on,
          title: entry.key,
          subtitle: entry.value['address'] as String,
          iconColor: AppColors.secondary,
          onTap: () {
            widget.onPlaceSelected(
              entry.key,
              entry.value['location'] as LatLng,
              entry.value['address'] as String,
            );
            Navigator.pop(context);
          },
        )),

        // Bottom padding for safe area
        SizedBox(height: MediaQuery.of(context).padding.bottom + 16),
      ],
    );
  }

  Widget _buildPlaceOption({
    required IconData icon,
    required String title,
    required String subtitle,
    required Color iconColor,
    required VoidCallback onTap,
  }) {
    return ListTile(
      contentPadding: EdgeInsets.zero,
      leading: Container(
        padding: const EdgeInsets.all(10),
        decoration: BoxDecoration(
          color: iconColor.withOpacity(0.1),
          borderRadius: BorderRadius.circular(8),
        ),
        child: Icon(icon, color: iconColor, size: 20),
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
