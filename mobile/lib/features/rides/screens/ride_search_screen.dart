import 'dart:async';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import 'package:google_maps_flutter/google_maps_flutter.dart';
import 'package:geolocator/geolocator.dart';

import '../../../core/constants/app_colors.dart';
import '../../../core/routes/app_router.dart';
import '../services/ride_service.dart';
import '../models/price_option.dart';
import 'map_location_picker_screen.dart';

// Providers for location state
final originLocationProvider = StateProvider<LatLng?>((ref) => null);
final destinationLocationProvider = StateProvider<LatLng?>((ref) => null);
final originAddressProvider = StateProvider<String>((ref) => '');
final destinationAddressProvider = StateProvider<String>((ref) => '');

// Provider for price options
final priceOptionsProvider = FutureProvider.family<List<PriceOption>, Map<String, LatLng>>((ref, locations) async {
  final rideService = ref.watch(rideServiceProvider);
  return rideService.getPriceComparison(
    origin: locations['origin']!,
    destination: locations['destination']!,
  );
});

class RideSearchScreen extends ConsumerStatefulWidget {
  const RideSearchScreen({super.key});

  @override
  ConsumerState<RideSearchScreen> createState() => _RideSearchScreenState();
}

class _RideSearchScreenState extends ConsumerState<RideSearchScreen> {
  final _originController = TextEditingController();
  final _destinationController = TextEditingController();
  GoogleMapController? _mapController;
  bool _isLoadingLocation = false;
  Set<Marker> _markers = {};
  Set<Polyline> _polylines = {};

  // Cairo default location
  static const LatLng _cairoCenter = LatLng(30.0444, 31.2357);

  // Predefined locations in Cairo for easy testing
  final Map<String, Map<String, dynamic>> _savedPlaces = {
    'المعادي': {
      'location': const LatLng(29.9602, 31.2569),
      'address': 'المعادي، القاهرة',
    },
    'التجمع الخامس': {
      'location': const LatLng(30.0074, 31.4913),
      'address': 'التجمع الخامس، القاهرة الجديدة',
    },
    'مدينة نصر': {
      'location': const LatLng(30.0511, 31.3656),
      'address': 'مدينة نصر، القاهرة',
    },
    'الدقي': {
      'location': const LatLng(30.0380, 31.2118),
      'address': 'الدقي، الجيزة',
    },
    'الزمالك': {
      'location': const LatLng(30.0609, 31.2243),
      'address': 'الزمالك، القاهرة',
    },
    'وسط البلد': {
      'location': const LatLng(30.0459, 31.2243),
      'address': 'وسط البلد، القاهرة',
    },
    'مصر الجديدة': {
      'location': const LatLng(30.0887, 31.3225),
      'address': 'مصر الجديدة، القاهرة',
    },
    '6 أكتوبر': {
      'location': const LatLng(29.9285, 30.9188),
      'address': '6 أكتوبر، الجيزة',
    },
  };

  @override
  void initState() {
    super.initState();
    _getCurrentLocation();
  }

  @override
  void dispose() {
    _originController.dispose();
    _destinationController.dispose();
    _mapController?.dispose();
    super.dispose();
  }

  Future<void> _getCurrentLocation() async {
    setState(() => _isLoadingLocation = true);

    try {
      // Check location permission
      LocationPermission permission = await Geolocator.checkPermission();
      if (permission == LocationPermission.denied) {
        permission = await Geolocator.requestPermission();
        if (permission == LocationPermission.denied) {
          _showError('تم رفض إذن الموقع');
          setState(() => _isLoadingLocation = false);
          return;
        }
      }

      if (permission == LocationPermission.deniedForever) {
        _showError('إذن الموقع محظور. يرجى تفعيله من الإعدادات');
        setState(() => _isLoadingLocation = false);
        return;
      }

      // Get current position
      final position = await Geolocator.getCurrentPosition(
        desiredAccuracy: LocationAccuracy.high,
      );

      final currentLocation = LatLng(position.latitude, position.longitude);

      ref.read(originLocationProvider.notifier).state = currentLocation;
      ref.read(originAddressProvider.notifier).state = 'موقعي الحالي';
      _originController.text = 'موقعي الحالي';

      _updateMarkers();
      _moveCamera(currentLocation);
    } catch (e) {
      _showError('فشل في تحديد الموقع: $e');
    } finally {
      setState(() => _isLoadingLocation = false);
    }
  }

  void _updateMarkers() {
    final origin = ref.read(originLocationProvider);
    final destination = ref.read(destinationLocationProvider);

    final markers = <Marker>{};

    if (origin != null) {
      markers.add(Marker(
        markerId: const MarkerId('origin'),
        position: origin,
        icon: BitmapDescriptor.defaultMarkerWithHue(BitmapDescriptor.hueGreen),
        infoWindow: InfoWindow(title: 'نقطة الانطلاق', snippet: ref.read(originAddressProvider)),
      ));
    }

    if (destination != null) {
      markers.add(Marker(
        markerId: const MarkerId('destination'),
        position: destination,
        icon: BitmapDescriptor.defaultMarkerWithHue(BitmapDescriptor.hueRed),
        infoWindow: InfoWindow(title: 'الوجهة', snippet: ref.read(destinationAddressProvider)),
      ));
    }

    setState(() => _markers = markers);

    // Draw line between points
    if (origin != null && destination != null) {
      setState(() {
        _polylines = {
          Polyline(
            polylineId: const PolylineId('route'),
            points: [origin, destination],
            color: AppColors.primary,
            width: 4,
          ),
        };
      });

      // Fit bounds to show both markers
      _fitBounds(origin, destination);
    }
  }

  void _fitBounds(LatLng origin, LatLng destination) {
    final bounds = LatLngBounds(
      southwest: LatLng(
        origin.latitude < destination.latitude ? origin.latitude : destination.latitude,
        origin.longitude < destination.longitude ? origin.longitude : destination.longitude,
      ),
      northeast: LatLng(
        origin.latitude > destination.latitude ? origin.latitude : destination.latitude,
        origin.longitude > destination.longitude ? origin.longitude : destination.longitude,
      ),
    );

    _mapController?.animateCamera(
      CameraUpdate.newLatLngBounds(bounds, 80),
    );
  }

  void _moveCamera(LatLng position) {
    _mapController?.animateCamera(
      CameraUpdate.newLatLngZoom(position, 14),
    );
  }

  void _selectPlace(String name, LatLng location, String address, bool isOrigin) {
    if (isOrigin) {
      ref.read(originLocationProvider.notifier).state = location;
      ref.read(originAddressProvider.notifier).state = address;
      _originController.text = name;
    } else {
      ref.read(destinationLocationProvider.notifier).state = location;
      ref.read(destinationAddressProvider.notifier).state = address;
      _destinationController.text = name;
    }

    _updateMarkers();
  }

  void _showError(String message) {
    if (mounted) {
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(content: Text(message), backgroundColor: AppColors.error),
      );
    }
  }

  void _searchPrices() {
    final origin = ref.read(originLocationProvider);
    final destination = ref.read(destinationLocationProvider);

    if (origin == null) {
      _showError('من فضلك حدد نقطة الانطلاق');
      return;
    }

    if (destination == null) {
      _showError('من فضلك حدد الوجهة');
      return;
    }

    // Navigate to price comparison with location data
    context.push(
      AppRoutes.priceComparison,
      extra: {
        'origin': origin,
        'destination': destination,
        'originAddress': ref.read(originAddressProvider),
        'destinationAddress': ref.read(destinationAddressProvider),
      },
    );
  }

  @override
  Widget build(BuildContext context) {
    final origin = ref.watch(originLocationProvider);
    final destination = ref.watch(destinationLocationProvider);

    return Scaffold(
      body: Stack(
        children: [
          // Google Map
          GoogleMap(
            initialCameraPosition: CameraPosition(
              target: origin ?? _cairoCenter,
              zoom: 12,
            ),
            onMapCreated: (controller) {
              _mapController = controller;
              if (origin != null) {
                _moveCamera(origin);
              }
            },
            markers: _markers,
            polylines: _polylines,
            myLocationEnabled: true,
            myLocationButtonEnabled: false,
            zoomControlsEnabled: false,
            mapToolbarEnabled: false,
            onTap: (position) {
              // Allow selecting destination by tapping map
              if (origin != null && destination == null) {
                _selectPlace('نقطة على الخريطة', position, 'موقع مختار', false);
              }
            },
          ),

          // Search Panel
          SafeArea(
            child: Column(
              children: [
                // Top Panel with search fields
                Container(
                  margin: const EdgeInsets.all(16),
                  padding: const EdgeInsets.all(16),
                  decoration: BoxDecoration(
                    color: AppColors.surface,
                    borderRadius: BorderRadius.circular(16),
                    boxShadow: [
                      BoxShadow(
                        color: Colors.black.withOpacity(0.1),
                        blurRadius: 10,
                        offset: const Offset(0, 4),
                      ),
                    ],
                  ),
                  child: Column(
                    mainAxisSize: MainAxisSize.min,
                    children: [
                      // Header
                      Row(
                        children: [
                          IconButton(
                            icon: const Icon(Icons.arrow_back),
                            onPressed: () => context.pop(),
                          ),
                          const Expanded(
                            child: Text(
                              'إلى أين؟',
                              style: TextStyle(
                                fontSize: 18,
                                fontWeight: FontWeight.bold,
                              ),
                            ),
                          ),
                          if (_isLoadingLocation)
                            const SizedBox(
                              width: 20,
                              height: 20,
                              child: CircularProgressIndicator(strokeWidth: 2),
                            ),
                        ],
                      ),

                      const SizedBox(height: 16),

                      // Origin Field
                      TextField(
                        controller: _originController,
                        readOnly: true,
                        onTap: () => _showPlacePicker(isOrigin: true),
                        decoration: InputDecoration(
                          hintText: 'نقطة الانطلاق',
                          prefixIcon: Container(
                            padding: const EdgeInsets.all(12),
                            child: const Icon(Icons.circle, color: AppColors.success, size: 12),
                          ),
                          suffixIcon: IconButton(
                            icon: const Icon(Icons.my_location, color: AppColors.primary),
                            onPressed: _getCurrentLocation,
                          ),
                          border: OutlineInputBorder(
                            borderRadius: BorderRadius.circular(12),
                          ),
                          filled: true,
                          fillColor: AppColors.background,
                        ),
                      ),

                      const SizedBox(height: 12),

                      // Destination Field
                      TextField(
                        controller: _destinationController,
                        readOnly: true,
                        onTap: () => _showPlacePicker(isOrigin: false),
                        decoration: InputDecoration(
                          hintText: 'الوجهة',
                          prefixIcon: Container(
                            padding: const EdgeInsets.all(12),
                            child: const Icon(Icons.location_on, color: AppColors.error, size: 16),
                          ),
                          border: OutlineInputBorder(
                            borderRadius: BorderRadius.circular(12),
                          ),
                          filled: true,
                          fillColor: AppColors.background,
                        ),
                      ),

                      const SizedBox(height: 16),

                      // Search Button
                      SizedBox(
                        width: double.infinity,
                        height: 52,
                        child: ElevatedButton.icon(
                          onPressed: (origin != null && destination != null) ? _searchPrices : null,
                          icon: const Icon(Icons.search),
                          label: const Text('بحث عن أفضل سعر'),
                          style: ElevatedButton.styleFrom(
                            backgroundColor: AppColors.primary,
                            foregroundColor: Colors.white,
                            shape: RoundedRectangleBorder(
                              borderRadius: BorderRadius.circular(12),
                            ),
                          ),
                        ),
                      ),
                    ],
                  ),
                ),

                const Spacer(),

                // Distance info card (shown when both points selected)
                if (origin != null && destination != null)
                  _buildRouteInfoCard(origin, destination),
              ],
            ),
          ),
        ],
      ),
    );
  }

  Widget _buildRouteInfoCard(LatLng origin, LatLng destination) {
    final rideService = ref.read(rideServiceProvider);
    final distance = rideService.calculateDistance(origin, destination);
    final minutes = rideService.calculateEstimatedMinutes(distance);
    final price = rideService.calculateIndependentDriverPrice(distance);

    return Container(
      margin: const EdgeInsets.all(16),
      padding: const EdgeInsets.all(16),
      decoration: BoxDecoration(
        color: AppColors.surface,
        borderRadius: BorderRadius.circular(16),
        boxShadow: [
          BoxShadow(
            color: Colors.black.withOpacity(0.1),
            blurRadius: 10,
          ),
        ],
      ),
      child: Row(
        mainAxisAlignment: MainAxisAlignment.spaceAround,
        children: [
          _buildInfoItem(Icons.straighten, '${distance.toStringAsFixed(1)} كم', 'المسافة'),
          Container(width: 1, height: 40, color: AppColors.divider),
          _buildInfoItem(Icons.access_time, '$minutes دقيقة', 'الوقت المتوقع'),
          Container(width: 1, height: 40, color: AppColors.divider),
          _buildInfoItem(Icons.attach_money, '~${price.round()} ج.م', 'السعر التقريبي'),
        ],
      ),
    );
  }

  Widget _buildInfoItem(IconData icon, String value, String label) {
    return Column(
      mainAxisSize: MainAxisSize.min,
      children: [
        Icon(icon, color: AppColors.primary, size: 20),
        const SizedBox(height: 4),
        Text(
          value,
          style: const TextStyle(
            fontWeight: FontWeight.bold,
            fontSize: 16,
          ),
        ),
        Text(
          label,
          style: const TextStyle(
            color: AppColors.textSecondary,
            fontSize: 12,
          ),
        ),
      ],
    );
  }

  void _showPlacePicker({required bool isOrigin}) {
    showModalBottomSheet(
      context: context,
      isScrollControlled: true,
      shape: const RoundedRectangleBorder(
        borderRadius: BorderRadius.vertical(top: Radius.circular(20)),
      ),
      builder: (context) => DraggableScrollableSheet(
        initialChildSize: 0.7,
        minChildSize: 0.5,
        maxChildSize: 0.9,
        expand: false,
        builder: (context, scrollController) => Column(
          children: [
            Container(
              width: 40,
              height: 4,
              margin: const EdgeInsets.symmetric(vertical: 12),
              decoration: BoxDecoration(
                color: AppColors.divider,
                borderRadius: BorderRadius.circular(2),
              ),
            ),
            Padding(
              padding: const EdgeInsets.symmetric(horizontal: 16),
              child: Text(
                isOrigin ? 'اختر نقطة الانطلاق' : 'اختر الوجهة',
                style: const TextStyle(
                  fontSize: 18,
                  fontWeight: FontWeight.bold,
                ),
              ),
            ),
            const SizedBox(height: 16),
            // Map Picker Button
            Padding(
              padding: const EdgeInsets.symmetric(horizontal: 16),
              child: SizedBox(
                width: double.infinity,
                child: ElevatedButton.icon(
                  onPressed: () {
                    Navigator.pop(context);
                    _openMapPicker(isOrigin: isOrigin);
                  },
                  icon: const Icon(Icons.map),
                  label: const Text('اختر من الخريطة'),
                  style: ElevatedButton.styleFrom(
                    backgroundColor: isOrigin ? AppColors.success : AppColors.primary,
                    foregroundColor: Colors.white,
                    padding: const EdgeInsets.symmetric(vertical: 14),
                    shape: RoundedRectangleBorder(
                      borderRadius: BorderRadius.circular(12),
                    ),
                  ),
                ),
              ),
            ),
            const SizedBox(height: 16),
            const Divider(height: 1),
            Expanded(
              child: ListView(
                controller: scrollController,
                padding: const EdgeInsets.symmetric(horizontal: 16),
                children: [
                  const SizedBox(height: 12),
                  if (isOrigin) ...[
                    _buildPlaceOption(
                      icon: Icons.my_location,
                      title: 'موقعي الحالي',
                      subtitle: 'استخدم GPS',
                      iconColor: AppColors.primary,
                      onTap: () {
                        Navigator.pop(context);
                        _getCurrentLocation();
                      },
                    ),
                    const Divider(),
                  ],
                  const Text(
                    'الأماكن المحفوظة',
                    style: TextStyle(
                      fontSize: 14,
                      fontWeight: FontWeight.bold,
                      color: AppColors.textSecondary,
                    ),
                  ),
                  const SizedBox(height: 8),
                  ..._savedPlaces.entries.map((entry) => _buildPlaceOption(
                    icon: Icons.location_on,
                    title: entry.key,
                    subtitle: entry.value['address'] as String,
                    iconColor: AppColors.secondary,
                    onTap: () {
                      Navigator.pop(context);
                      _selectPlace(
                        entry.key,
                        entry.value['location'] as LatLng,
                        entry.value['address'] as String,
                        isOrigin,
                      );
                    },
                  )),
                ],
              ),
            ),
          ],
        ),
      ),
    );
  }

  Future<void> _openMapPicker({required bool isOrigin}) async {
    final currentLocation = isOrigin
        ? ref.read(originLocationProvider)
        : ref.read(destinationLocationProvider);
    final currentAddress = isOrigin
        ? ref.read(originAddressProvider)
        : ref.read(destinationAddressProvider);

    final result = await Navigator.push<Map<String, dynamic>>(
      context,
      MaterialPageRoute(
        builder: (context) => MapLocationPickerScreen(
          isOrigin: isOrigin,
          initialLocation: currentLocation,
          initialAddress: currentAddress.isNotEmpty ? currentAddress : null,
        ),
      ),
    );

    if (result != null && mounted) {
      final location = result['location'] as LatLng;
      final address = result['address'] as String;

      _selectPlace(
        address.length > 30 ? '${address.substring(0, 30)}...' : address,
        location,
        address,
        isOrigin,
      );
    }
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
