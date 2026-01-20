import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:google_maps_flutter/google_maps_flutter.dart';
import 'package:url_launcher/url_launcher.dart';

import '../../../core/constants/app_colors.dart';
import '../services/tracking_service.dart';

class TrackingScreen extends ConsumerStatefulWidget {
  final String trackingId;
  final String? type; // 'ride' or 'shipment'

  const TrackingScreen({
    super.key,
    required this.trackingId,
    this.type,
  });

  @override
  ConsumerState<TrackingScreen> createState() => _TrackingScreenState();
}

class _TrackingScreenState extends ConsumerState<TrackingScreen> {
  GoogleMapController? _mapController;
  final Set<Marker> _markers = {};
  final Set<Polyline> _polylines = {};
  StreamSubscription? _trackingSubscription;
  TrackingData? _trackingData;

  @override
  void initState() {
    super.initState();
    _startTracking();
  }

  @override
  void dispose() {
    _trackingSubscription?.cancel();
    _mapController?.dispose();
    super.dispose();
  }

  void _startTracking() {
    final trackingService = ref.read(trackingServiceProvider);

    // Determine if it's a ride or shipment based on type or ID format
    final isRide = widget.type == 'ride' || widget.type == null;

    if (isRide) {
      _trackingSubscription = trackingService.watchRide(widget.trackingId).listen((data) {
        _updateTrackingData(data);
      });
    } else {
      _trackingSubscription = trackingService.watchShipment(widget.trackingId).listen((data) {
        _updateTrackingData(data);
      });
    }
  }

  void _updateTrackingData(TrackingData? data) {
    if (!mounted) return;

    setState(() {
      _trackingData = data;
      _updateMapMarkers();
    });
  }

  void _updateMapMarkers() {
    if (_trackingData == null) return;

    _markers.clear();
    _polylines.clear();

    // Origin marker
    _markers.add(
      Marker(
        markerId: const MarkerId('origin'),
        position: _trackingData!.origin,
        icon: BitmapDescriptor.defaultMarkerWithHue(BitmapDescriptor.hueGreen),
        infoWindow: InfoWindow(
          title: _trackingData!.type == 'ride' ? 'نقطة الانطلاق' : 'نقطة الاستلام',
          snippet: _trackingData!.originAddress,
        ),
      ),
    );

    // Destination marker
    _markers.add(
      Marker(
        markerId: const MarkerId('destination'),
        position: _trackingData!.destination,
        icon: BitmapDescriptor.defaultMarkerWithHue(BitmapDescriptor.hueRed),
        infoWindow: InfoWindow(
          title: _trackingData!.type == 'ride' ? 'الوجهة' : 'نقطة التسليم',
          snippet: _trackingData!.destinationAddress,
        ),
      ),
    );

    // Driver/current location marker
    if (_trackingData!.currentLocation != null) {
      _markers.add(
        Marker(
          markerId: const MarkerId('driver'),
          position: _trackingData!.currentLocation!,
          icon: BitmapDescriptor.defaultMarkerWithHue(BitmapDescriptor.hueBlue),
          infoWindow: InfoWindow(
            title: _trackingData!.driverName ?? 'السائق',
            snippet: _trackingData!.vehicleModel ?? '',
          ),
        ),
      );
    }

    // Route polyline
    _polylines.add(
      Polyline(
        polylineId: const PolylineId('route'),
        points: [
          _trackingData!.origin,
          if (_trackingData!.currentLocation != null) _trackingData!.currentLocation!,
          _trackingData!.destination,
        ],
        color: AppColors.primary,
        width: 4,
      ),
    );

    // Fit map to show all markers
    _fitMapToBounds();
  }

  void _fitMapToBounds() {
    if (_mapController == null || _markers.isEmpty) return;

    final bounds = _calculateBounds();
    if (bounds != null) {
      _mapController!.animateCamera(
        CameraUpdate.newLatLngBounds(bounds, 80),
      );
    }
  }

  LatLngBounds? _calculateBounds() {
    if (_markers.isEmpty) return null;

    double minLat = 90, maxLat = -90, minLng = 180, maxLng = -180;

    for (final marker in _markers) {
      final lat = marker.position.latitude;
      final lng = marker.position.longitude;

      if (lat < minLat) minLat = lat;
      if (lat > maxLat) maxLat = lat;
      if (lng < minLng) minLng = lng;
      if (lng > maxLng) maxLng = lng;
    }

    return LatLngBounds(
      southwest: LatLng(minLat, minLng),
      northeast: LatLng(maxLat, maxLng),
    );
  }

  Future<void> _callDriver() async {
    if (_trackingData?.driverPhone == null) return;

    final uri = Uri.parse('tel:${_trackingData!.driverPhone}');
    if (await canLaunchUrl(uri)) {
      await launchUrl(uri);
    }
  }

  Future<void> _openWhatsApp() async {
    if (_trackingData?.driverPhone == null) return;

    // Format phone number for WhatsApp
    String phone = _trackingData!.driverPhone!.replaceAll(RegExp(r'[^\d]'), '');
    if (phone.startsWith('0')) {
      phone = '20${phone.substring(1)}';
    } else if (!phone.startsWith('20')) {
      phone = '20$phone';
    }

    final uri = Uri.parse('https://wa.me/$phone');
    if (await canLaunchUrl(uri)) {
      await launchUrl(uri, mode: LaunchMode.externalApplication);
    }
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      body: _trackingData == null
          ? _buildLoadingState()
          : Stack(
              children: [
                // Map
                GoogleMap(
                  initialCameraPosition: CameraPosition(
                    target: _trackingData!.currentLocation ?? _trackingData!.origin,
                    zoom: 14,
                  ),
                  onMapCreated: (controller) {
                    _mapController = controller;
                    _fitMapToBounds();
                  },
                  markers: _markers,
                  polylines: _polylines,
                  myLocationEnabled: true,
                  myLocationButtonEnabled: false,
                  zoomControlsEnabled: false,
                  mapToolbarEnabled: false,
                ),

                // Back button
                Positioned(
                  top: MediaQuery.of(context).padding.top + 8,
                  left: 16,
                  child: CircleAvatar(
                    backgroundColor: AppColors.surface,
                    child: IconButton(
                      icon: const Icon(Icons.arrow_back, color: AppColors.textPrimary),
                      onPressed: () => Navigator.pop(context),
                    ),
                  ),
                ),

                // Center on driver button
                if (_trackingData!.currentLocation != null)
                  Positioned(
                    top: MediaQuery.of(context).padding.top + 8,
                    right: 16,
                    child: CircleAvatar(
                      backgroundColor: AppColors.surface,
                      child: IconButton(
                        icon: const Icon(Icons.my_location, color: AppColors.primary),
                        onPressed: () {
                          _mapController?.animateCamera(
                            CameraUpdate.newLatLng(_trackingData!.currentLocation!),
                          );
                        },
                      ),
                    ),
                  ),

                // Bottom sheet
                _buildBottomSheet(),
              ],
            ),
    );
  }

  Widget _buildLoadingState() {
    return const Center(
      child: Column(
        mainAxisAlignment: MainAxisAlignment.center,
        children: [
          CircularProgressIndicator(),
          SizedBox(height: 16),
          Text('جاري تحميل بيانات التتبع...'),
        ],
      ),
    );
  }

  Widget _buildBottomSheet() {
    return DraggableScrollableSheet(
      initialChildSize: 0.35,
      minChildSize: 0.2,
      maxChildSize: 0.6,
      builder: (context, scrollController) {
        return Container(
          decoration: const BoxDecoration(
            color: AppColors.surface,
            borderRadius: BorderRadius.vertical(top: Radius.circular(24)),
            boxShadow: [
              BoxShadow(
                color: AppColors.shadow,
                blurRadius: 16,
                offset: Offset(0, -4),
              ),
            ],
          ),
          child: SingleChildScrollView(
            controller: scrollController,
            padding: const EdgeInsets.all(20),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                // Handle
                Center(
                  child: Container(
                    width: 40,
                    height: 4,
                    decoration: BoxDecoration(
                      color: AppColors.border,
                      borderRadius: BorderRadius.circular(2),
                    ),
                  ),
                ),
                const SizedBox(height: 16),

                // Status badge
                _buildStatusBadge(),
                const SizedBox(height: 16),

                // Progress indicator
                _buildProgressIndicator(),
                const SizedBox(height: 16),

                // ETA
                if (_trackingData!.estimatedMinutes != null && _trackingData!.isActive)
                  _buildEtaCard(),

                const Divider(height: 32),

                // Driver info
                if (_trackingData!.driverName != null) _buildDriverCard(),

                // Addresses
                const SizedBox(height: 16),
                _buildAddressCard(),
              ],
            ),
          ),
        );
      },
    );
  }

  Widget _buildStatusBadge() {
    Color statusColor;
    switch (_trackingData!.status) {
      case 'completed':
      case 'delivered':
        statusColor = AppColors.success;
        break;
      case 'cancelled':
        statusColor = AppColors.error;
        break;
      case 'searching':
        statusColor = AppColors.warning;
        break;
      default:
        statusColor = AppColors.primary;
    }

    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 8),
      decoration: BoxDecoration(
        color: statusColor.withOpacity(0.1),
        borderRadius: BorderRadius.circular(20),
      ),
      child: Row(
        mainAxisSize: MainAxisSize.min,
        children: [
          Icon(
            _getStatusIcon(),
            size: 18,
            color: statusColor,
          ),
          const SizedBox(width: 8),
          Text(
            _trackingData!.statusText,
            style: TextStyle(
              color: statusColor,
              fontWeight: FontWeight.bold,
            ),
          ),
        ],
      ),
    );
  }

  IconData _getStatusIcon() {
    switch (_trackingData!.status) {
      case 'searching':
        return Icons.search;
      case 'accepted':
        return Icons.directions_car;
      case 'arrived':
        return Icons.location_on;
      case 'started':
      case 'in_transit':
        return Icons.local_shipping;
      case 'completed':
      case 'delivered':
        return Icons.check_circle;
      case 'cancelled':
        return Icons.cancel;
      default:
        return Icons.info;
    }
  }

  Widget _buildProgressIndicator() {
    final steps = _trackingData!.type == 'ride'
        ? ['بحث', 'قبول', 'وصول', 'رحلة', 'تم']
        : ['انتظار', 'قبول', 'استلام', 'توصيل', 'تسليم'];

    int currentStep;
    switch (_trackingData!.status) {
      case 'searching':
      case 'pending':
        currentStep = 0;
        break;
      case 'accepted':
        currentStep = 1;
        break;
      case 'arrived':
      case 'picked_up':
        currentStep = 2;
        break;
      case 'started':
      case 'in_transit':
        currentStep = 3;
        break;
      case 'completed':
      case 'delivered':
        currentStep = 4;
        break;
      default:
        currentStep = 0;
    }

    return Column(
      children: [
        Row(
          children: List.generate(steps.length * 2 - 1, (index) {
            if (index.isEven) {
              final stepIndex = index ~/ 2;
              final isCompleted = stepIndex < currentStep;
              final isCurrent = stepIndex == currentStep;

              return Container(
                width: 24,
                height: 24,
                decoration: BoxDecoration(
                  color: isCompleted || isCurrent
                      ? AppColors.primary
                      : AppColors.border,
                  shape: BoxShape.circle,
                ),
                child: Center(
                  child: isCompleted
                      ? const Icon(Icons.check, size: 14, color: Colors.white)
                      : Text(
                          '${stepIndex + 1}',
                          style: TextStyle(
                            color: isCurrent ? Colors.white : AppColors.textSecondary,
                            fontSize: 12,
                            fontWeight: FontWeight.bold,
                          ),
                        ),
                ),
              );
            } else {
              final lineIndex = index ~/ 2;
              return Expanded(
                child: Container(
                  height: 3,
                  color: lineIndex < currentStep
                      ? AppColors.primary
                      : AppColors.border,
                ),
              );
            }
          }),
        ),
        const SizedBox(height: 8),
        Row(
          mainAxisAlignment: MainAxisAlignment.spaceBetween,
          children: steps.map((step) => Text(
            step,
            style: const TextStyle(fontSize: 10, color: AppColors.textSecondary),
          )).toList(),
        ),
      ],
    );
  }

  Widget _buildEtaCard() {
    return Container(
      padding: const EdgeInsets.all(16),
      decoration: BoxDecoration(
        color: AppColors.primary.withOpacity(0.1),
        borderRadius: BorderRadius.circular(12),
      ),
      child: Row(
        children: [
          const Icon(Icons.access_time, color: AppColors.primary),
          const SizedBox(width: 12),
          Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              const Text(
                'الوقت المتوقع للوصول',
                style: TextStyle(
                  color: AppColors.textSecondary,
                  fontSize: 12,
                ),
              ),
              Text(
                '${_trackingData!.estimatedMinutes} دقيقة',
                style: const TextStyle(
                  fontWeight: FontWeight.bold,
                  fontSize: 18,
                  color: AppColors.primary,
                ),
              ),
            ],
          ),
        ],
      ),
    );
  }

  Widget _buildDriverCard() {
    return Container(
      padding: const EdgeInsets.all(16),
      decoration: BoxDecoration(
        color: AppColors.surfaceVariant,
        borderRadius: BorderRadius.circular(12),
      ),
      child: Row(
        children: [
          CircleAvatar(
            radius: 28,
            backgroundColor: AppColors.primary.withOpacity(0.1),
            backgroundImage: _trackingData!.driverAvatarUrl != null
                ? NetworkImage(_trackingData!.driverAvatarUrl!)
                : null,
            child: _trackingData!.driverAvatarUrl == null
                ? const Icon(Icons.person, color: AppColors.primary, size: 28)
                : null,
          ),
          const SizedBox(width: 16),
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(
                  _trackingData!.driverName ?? 'السائق',
                  style: const TextStyle(
                    fontWeight: FontWeight.bold,
                    fontSize: 16,
                  ),
                ),
                const SizedBox(height: 4),
                if (_trackingData!.driverRating != null)
                  Row(
                    children: [
                      const Icon(Icons.star, color: AppColors.secondary, size: 16),
                      Text(
                        ' ${_trackingData!.driverRating!.toStringAsFixed(1)}',
                        style: const TextStyle(fontWeight: FontWeight.w500),
                      ),
                    ],
                  ),
                if (_trackingData!.vehicleModel != null && _trackingData!.vehicleModel!.isNotEmpty)
                  Text(
                    '${_trackingData!.vehicleModel} - ${_trackingData!.vehicleColor ?? ''} ${_trackingData!.vehiclePlate ?? ''}',
                    style: const TextStyle(
                      color: AppColors.textSecondary,
                      fontSize: 12,
                    ),
                  ),
              ],
            ),
          ),
          IconButton(
            icon: const Icon(Icons.phone, color: AppColors.primary),
            onPressed: _callDriver,
          ),
          IconButton(
            icon: const Icon(Icons.chat, color: AppColors.success),
            onPressed: _openWhatsApp,
          ),
        ],
      ),
    );
  }

  Widget _buildAddressCard() {
    return Column(
      children: [
        _buildAddressRow(
          icon: Icons.trip_origin,
          iconColor: AppColors.success,
          label: _trackingData!.type == 'ride' ? 'من' : 'استلام من',
          address: _trackingData!.originAddress,
        ),
        const SizedBox(height: 8),
        Container(
          margin: const EdgeInsets.only(left: 11),
          height: 24,
          width: 2,
          color: AppColors.border,
        ),
        const SizedBox(height: 8),
        _buildAddressRow(
          icon: Icons.location_on,
          iconColor: AppColors.error,
          label: _trackingData!.type == 'ride' ? 'إلى' : 'توصيل إلى',
          address: _trackingData!.destinationAddress,
        ),
      ],
    );
  }

  Widget _buildAddressRow({
    required IconData icon,
    required Color iconColor,
    required String label,
    required String address,
  }) {
    return Row(
      children: [
        Icon(icon, color: iconColor, size: 24),
        const SizedBox(width: 12),
        Expanded(
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Text(
                label,
                style: const TextStyle(
                  color: AppColors.textSecondary,
                  fontSize: 12,
                ),
              ),
              Text(
                address,
                style: const TextStyle(fontWeight: FontWeight.w500),
                maxLines: 2,
                overflow: TextOverflow.ellipsis,
              ),
            ],
          ),
        ),
      ],
    );
  }
}
