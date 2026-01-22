import 'dart:async';
import 'dart:convert';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:google_maps_flutter/google_maps_flutter.dart';
import 'package:geolocator/geolocator.dart';
import 'package:http/http.dart' as http;

import '../../../core/constants/app_colors.dart';
import '../../../core/constants/app_constants.dart';

/// Screen for precise location selection from map
/// Shows a centered pin that user can position by moving the map
class MapLocationPickerScreen extends ConsumerStatefulWidget {
  final bool isOrigin;
  final LatLng? initialLocation;
  final String? initialAddress;

  const MapLocationPickerScreen({
    super.key,
    required this.isOrigin,
    this.initialLocation,
    this.initialAddress,
  });

  @override
  ConsumerState<MapLocationPickerScreen> createState() => _MapLocationPickerScreenState();
}

class _MapLocationPickerScreenState extends ConsumerState<MapLocationPickerScreen> {
  GoogleMapController? _mapController;
  LatLng? _selectedLocation;
  String _selectedAddress = '';
  bool _isLoadingAddress = false;
  bool _isLoadingLocation = false;
  Timer? _debounceTimer;

  // Cairo default
  static const LatLng _cairoCenter = LatLng(30.0444, 31.2357);

  @override
  void initState() {
    super.initState();
    _selectedLocation = widget.initialLocation;
    _selectedAddress = widget.initialAddress ?? '';

    if (_selectedLocation == null) {
      _getCurrentLocation();
    }
  }

  @override
  void dispose() {
    _debounceTimer?.cancel();
    _mapController?.dispose();
    super.dispose();
  }

  Future<void> _getCurrentLocation() async {
    setState(() => _isLoadingLocation = true);

    try {
      LocationPermission permission = await Geolocator.checkPermission();
      if (permission == LocationPermission.denied) {
        permission = await Geolocator.requestPermission();
      }

      if (permission == LocationPermission.denied ||
          permission == LocationPermission.deniedForever) {
        setState(() {
          _selectedLocation = _cairoCenter;
          _isLoadingLocation = false;
        });
        return;
      }

      final position = await Geolocator.getCurrentPosition(
        desiredAccuracy: LocationAccuracy.high,
      );

      final location = LatLng(position.latitude, position.longitude);

      setState(() {
        _selectedLocation = location;
        _isLoadingLocation = false;
      });

      _mapController?.animateCamera(
        CameraUpdate.newLatLngZoom(location, 16),
      );

      _getAddressFromLocation(location);
    } catch (e) {
      setState(() {
        _selectedLocation = _cairoCenter;
        _isLoadingLocation = false;
      });
    }
  }

  void _onCameraMove(CameraPosition position) {
    setState(() {
      _selectedLocation = position.target;
      _selectedAddress = 'جاري التحميل...';
    });

    // Debounce address lookup
    _debounceTimer?.cancel();
    _debounceTimer = Timer(const Duration(milliseconds: 500), () {
      _getAddressFromLocation(position.target);
    });
  }

  void _onCameraIdle() {
    if (_selectedLocation != null) {
      _getAddressFromLocation(_selectedLocation!);
    }
  }

  /// Check if a string is a Plus Code (format: XXXX+XXX)
  bool _isPlusCode(String? text) {
    if (text == null || text.isEmpty) return false;
    final trimmed = text.trim();
    // Plus Codes contain + and alphanumeric characters
    // Format: 4-8 characters + 2-4 characters (e.g., XVC5+2Q2, 5FX9+PVR)
    // Also check if the string CONTAINS a plus code (not just equals)
    final plusCodeRegex = RegExp(r'[A-Z0-9]{4,8}\+[A-Z0-9]{2,4}', caseSensitive: false);
    return plusCodeRegex.hasMatch(trimmed);
  }

  /// Minimum address length for automation (short addresses don't work well with DiDi/other apps)
  static const int _minAddressLength = 10;

  Future<void> _getAddressFromLocation(LatLng location) async {
    setState(() => _isLoadingAddress = true);

    try {
      // STEP 1: Get detailed street address from Geocoding API FIRST
      // This gives us the most detailed address (street name + area)
      final streetAddress = await _getDetailedStreetAddress(location);
      if (streetAddress != null && streetAddress.length >= _minAddressLength) {
        debugPrint('✓ Got detailed street address: $streetAddress');
        setState(() {
          _selectedAddress = streetAddress;
          _isLoadingAddress = false;
        });
        return;
      }

      // STEP 2: Try Google Places API to get actual place name
      final placeName = await _getNearbyPlaceName(location);
      if (placeName != null && placeName.length >= _minAddressLength) {
        debugPrint('✓ Got place name from Places API: $placeName');
        setState(() {
          _selectedAddress = placeName;
          _isLoadingAddress = false;
        });
        return;
      }

      // STEP 3: Fallback to general Geocoding API
      final address = await _getAddressFromGoogleAPI(location);
      if (address != null && address.length >= _minAddressLength) {
        debugPrint('✓ Got address from Geocoding API: $address');
        setState(() {
          _selectedAddress = address;
          _isLoadingAddress = false;
        });
        return;
      }

      // STEP 4: If all addresses are too short, combine them with area name
      final combinedAddress = await _getCombinedAddress(location, streetAddress ?? placeName ?? address);
      if (combinedAddress != null && combinedAddress.length >= _minAddressLength) {
        debugPrint('✓ Got combined address: $combinedAddress');
        setState(() {
          _selectedAddress = combinedAddress;
          _isLoadingAddress = false;
        });
        return;
      }

      // STEP 5: Last resort - use whatever we have or coordinates
      final bestAddress = streetAddress ?? placeName ?? address;
      setState(() {
        _selectedAddress = bestAddress ?? '${location.latitude.toStringAsFixed(6)}, ${location.longitude.toStringAsFixed(6)}';
        _isLoadingAddress = false;
      });
    } catch (e) {
      debugPrint('Address lookup error: $e');
      setState(() {
        _selectedAddress = '${location.latitude.toStringAsFixed(6)}, ${location.longitude.toStringAsFixed(6)}';
        _isLoadingAddress = false;
      });
    }
  }

  /// Get detailed street address with components (street name + neighborhood)
  Future<String?> _getDetailedStreetAddress(LatLng location) async {
    try {
      final apiKey = AppConstants.googleMapsApiKey;
      final url = Uri.parse(
        'https://maps.googleapis.com/maps/api/geocode/json'
        '?latlng=${location.latitude},${location.longitude}'
        '&language=ar'
        '&key=$apiKey'
      );

      final response = await http.get(url).timeout(const Duration(seconds: 10));

      if (response.statusCode == 200) {
        final data = json.decode(response.body);
        if (data['status'] == 'OK') {
          final results = data['results'] as List?;
          if (results != null && results.isNotEmpty) {
            // Extract components from first result
            final components = results.first['address_components'] as List?;
            if (components != null) {
              String? streetNumber;
              String? route;  // Street name
              String? neighborhood;
              String? sublocality;
              String? locality;

              for (final comp in components) {
                final types = (comp['types'] as List?)?.cast<String>() ?? [];
                final name = comp['long_name'] as String?;

                if (types.contains('street_number')) {
                  streetNumber = name;
                } else if (types.contains('route')) {
                  route = name;
                } else if (types.contains('neighborhood')) {
                  neighborhood = name;
                } else if (types.contains('sublocality') || types.contains('sublocality_level_1')) {
                  sublocality = name;
                } else if (types.contains('locality')) {
                  locality = name;
                }
              }

              // Build detailed address
              final parts = <String>[];

              // Add street with number
              if (route != null && route.isNotEmpty && !_isPlusCode(route)) {
                if (streetNumber != null) {
                  parts.add('$streetNumber $route');
                } else {
                  parts.add(route);
                }
              }

              // Add neighborhood/area
              final area = neighborhood ?? sublocality ?? locality;
              if (area != null && area.isNotEmpty && !_isPlusCode(area)) {
                // Don't duplicate if area is same as route
                if (parts.isEmpty || !parts.first.contains(area)) {
                  parts.add(area);
                }
              }

              if (parts.isNotEmpty) {
                final result = parts.join('، ');
                debugPrint('Detailed address components: street=$route, area=$area -> $result');
                return result;
              }
            }
          }
        }
      }
    } catch (e) {
      debugPrint('Detailed address error: $e');
    }
    return null;
  }

  /// Combine short address with area name to make it longer
  Future<String?> _getCombinedAddress(LatLng location, String? shortAddress) async {
    if (shortAddress == null || shortAddress.isEmpty) return null;

    try {
      final apiKey = AppConstants.googleMapsApiKey;
      final url = Uri.parse(
        'https://maps.googleapis.com/maps/api/geocode/json'
        '?latlng=${location.latitude},${location.longitude}'
        '&language=ar'
        '&result_type=sublocality|locality|administrative_area_level_2'
        '&key=$apiKey'
      );

      final response = await http.get(url).timeout(const Duration(seconds: 5));

      if (response.statusCode == 200) {
        final data = json.decode(response.body);
        if (data['status'] == 'OK') {
          final results = data['results'] as List?;
          if (results != null && results.isNotEmpty) {
            final formattedAddress = results.first['formatted_address'] as String?;
            if (formattedAddress != null) {
              // Extract area name (first part before comma)
              final parts = formattedAddress.split(RegExp(r'[,،]'));
              final areaName = parts.isNotEmpty ? parts.first.trim() : null;

              if (areaName != null &&
                  areaName.isNotEmpty &&
                  !_isPlusCode(areaName) &&
                  !shortAddress.contains(areaName)) {
                return '$shortAddress، $areaName';
              }
            }
          }
        }
      }
    } catch (e) {
      debugPrint('Combined address error: $e');
    }
    return null;
  }

  /// Get nearby place name using Google Places API (this is what Google Maps uses!)
  Future<String?> _getNearbyPlaceName(LatLng location) async {
    try {
      final apiKey = AppConstants.googleMapsApiKey;

      // Use Places Nearby Search API with larger radius for better results
      final url = Uri.parse(
        'https://maps.googleapis.com/maps/api/place/nearbysearch/json'
        '?location=${location.latitude},${location.longitude}'
        '&radius=100'  // 100 meters - wider search for more results
        '&language=ar'
        '&key=$apiKey'
      );

      debugPrint('Calling Places API...');
      final response = await http.get(url).timeout(const Duration(seconds: 10));

      if (response.statusCode == 200) {
        final data = json.decode(response.body);
        final status = data['status'] as String?;

        debugPrint('Places API status: $status');

        if (status == 'OK') {
          final results = data['results'] as List?;

          if (results != null && results.isNotEmpty) {
            debugPrint('Places API found ${results.length} nearby places');

            // Get the closest/most relevant place - prefer longer, more descriptive names
            String? bestName;
            String? bestVicinity;
            int bestScore = 0;

            for (final place in results) {
              final name = place['name'] as String?;
              final vicinity = place['vicinity'] as String?;
              final types = (place['types'] as List?)?.cast<String>() ?? [];

              // Skip generic types like "locality", "political", "route"
              final isGenericType = types.any((t) =>
                t == 'locality' ||
                t == 'political' ||
                t == 'route' ||
                t == 'street_address' ||
                t == 'administrative_area_level_1' ||
                t == 'administrative_area_level_2'
              );

              if (name == null || name.isEmpty || isGenericType) continue;

              // Skip very short names (less than 5 characters)
              if (name.length < 5) continue;

              // Skip Plus Codes
              if (_isPlusCode(name)) continue;

              debugPrint('Found place: $name, vicinity: $vicinity (types: $types)');

              // Score the name - prefer longer, more descriptive names
              int score = name.length;

              // Bonus for specific place types
              if (types.contains('establishment')) score += 10;
              if (types.contains('point_of_interest')) score += 8;
              if (types.contains('store') || types.contains('restaurant')) score += 5;

              // Bonus for Arabic names
              if (RegExp(r'[\u0600-\u06FF]').hasMatch(name)) score += 5;

              if (score > bestScore) {
                bestScore = score;
                bestName = name;
                bestVicinity = vicinity;
              }
            }

            if (bestName != null) {
              // Clean vicinity - extract only Arabic location name
              final cleanVicinity = _cleanVicinity(bestVicinity);
              debugPrint('Best place: $bestName, clean vicinity: $cleanVicinity');

              // Return name with clean vicinity for longer address
              if (cleanVicinity != null && cleanVicinity.isNotEmpty) {
                return '$bestName، $cleanVicinity';
              }
              return bestName;
            }
          }
        } else if (status == 'REQUEST_DENIED') {
          debugPrint('⚠️ Places API denied - make sure Places API is enabled!');
        } else if (status == 'ZERO_RESULTS') {
          debugPrint('Places API: No nearby places found');
        }
      }
    } catch (e) {
      debugPrint('Places API error: $e');
    }
    return null;
  }

  /// Clean vicinity string - remove English words, Plus Codes, etc.
  String? _cleanVicinity(String? vicinity) {
    if (vicinity == null || vicinity.isEmpty) return null;

    // Skip if it's a Plus Code
    if (_isPlusCode(vicinity)) return null;

    // Split by comma and clean each part
    final parts = vicinity.split(RegExp(r'[,،]')).map((p) => p.trim()).toList();

    final cleanedParts = <String>[];
    for (final part in parts) {
      // Skip Plus Codes
      if (_isPlusCode(part)) continue;

      // Skip English-only parts (like "Mall", "KALIOBEYA", "Street 400")
      if (RegExp(r'^[A-Za-z0-9\s]+$').hasMatch(part)) continue;

      // Skip parts that are mostly English
      final arabicChars = RegExp(r'[\u0600-\u06FF]').allMatches(part).length;
      final totalChars = part.replaceAll(' ', '').length;
      if (totalChars > 0 && arabicChars < totalChars * 0.5) continue;

      // Skip very short parts
      if (part.length < 3) continue;

      cleanedParts.add(part);
    }

    // Return the first meaningful Arabic part
    return cleanedParts.isNotEmpty ? cleanedParts.first : null;
  }

  /// Get address using Google Geocoding API (same as Google Maps)
  Future<String?> _getAddressFromGoogleAPI(LatLng location) async {
    try {
      final apiKey = AppConstants.googleMapsApiKey;

      // Use Google Geocoding API with Arabic language
      final url = Uri.parse(
        'https://maps.googleapis.com/maps/api/geocode/json'
        '?latlng=${location.latitude},${location.longitude}'
        '&language=ar'
        '&result_type=street_address|route|sublocality|locality|point_of_interest|establishment'
        '&key=$apiKey'
      );

      final response = await http.get(url).timeout(const Duration(seconds: 10));

      if (response.statusCode == 200) {
        final data = json.decode(response.body);
        final status = data['status'] as String?;

        debugPrint('Geocoding API status: $status');

        if (status == 'OK') {
          final results = data['results'] as List?;

          if (results != null && results.isNotEmpty) {
            debugPrint('Geocoding returned ${results.length} results');

            // Try each result and clean it
            for (final result in results) {
              final formattedAddress = result['formatted_address'] as String?;

              if (formattedAddress != null && formattedAddress.isNotEmpty) {
                debugPrint('Raw address: $formattedAddress');

                // Clean up the address (removes Plus Codes, country, postal codes)
                final cleanedAddress = _cleanGoogleAddress(formattedAddress);

                // Check if we got a meaningful address (not empty or just governorate)
                if (cleanedAddress.isNotEmpty &&
                    !cleanedAddress.toLowerCase().contains('governorate') &&
                    cleanedAddress.length > 5) {
                  debugPrint('Cleaned address: $cleanedAddress');
                  return cleanedAddress;
                }
              }
            }

            // Fallback: use first result even if not perfect
            final firstResult = results.first;
            final formattedAddress = firstResult['formatted_address'] as String?;
            if (formattedAddress != null) {
              final cleaned = _cleanGoogleAddress(formattedAddress);
              debugPrint('Fallback address: $cleaned');
              return cleaned.isNotEmpty ? cleaned : null;
            }
          }
        } else if (status == 'REQUEST_DENIED') {
          debugPrint('Geocoding API denied - check API key and enable Geocoding API');
        } else if (status == 'ZERO_RESULTS') {
          debugPrint('Geocoding API: No results for this location');
        }
      }
    } catch (e) {
      debugPrint('Google Geocoding API error: $e');
    }
    return null;
  }

  /// Clean up Google's formatted address
  String _cleanGoogleAddress(String address) {
    // Split by both English and Arabic commas
    final parts = address.split(RegExp(r'[,،]')).map((p) => p.trim()).toList();

    // Remove unwanted parts
    final cleanedParts = parts.where((part) {
      if (part.isEmpty) return false;
      final lower = part.toLowerCase();
      // Remove Plus Codes (e.g., 5FX9+PVR, 6F49+2PX)
      if (_isPlusCode(part)) return false;
      // Remove country name
      if (lower == 'egypt' || lower == 'مصر') return false;
      // Remove postal codes (numbers only or with governorate code like 6361122)
      if (RegExp(r'^\d{5,}$').hasMatch(part)) return false;
      // Remove parts that start with postal code
      if (RegExp(r'^\d{5,}\s').hasMatch(part)) return false;
      // Remove "محافظة" prefix standalone
      if (part == 'محافظة') return false;
      return true;
    }).toList();

    // Take first 2-3 meaningful parts
    final result = cleanedParts.take(3).join('، ');
    return result;
  }

  void _confirmLocation() {
    if (_selectedLocation != null) {
      Navigator.pop(context, {
        'location': _selectedLocation,
        'address': _selectedAddress,
      });
    }
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      body: Stack(
        children: [
          // Google Map
          GoogleMap(
            initialCameraPosition: CameraPosition(
              target: _selectedLocation ?? _cairoCenter,
              zoom: 16,
            ),
            onMapCreated: (controller) {
              _mapController = controller;
            },
            onCameraMove: _onCameraMove,
            onCameraIdle: _onCameraIdle,
            myLocationEnabled: true,
            myLocationButtonEnabled: false,
            zoomControlsEnabled: false,
            mapToolbarEnabled: false,
          ),

          // Center Pin
          Center(
            child: Padding(
              padding: const EdgeInsets.only(bottom: 36),
              child: Icon(
                Icons.location_on,
                size: 48,
                color: widget.isOrigin ? AppColors.success : AppColors.error,
              ),
            ),
          ),

          // Pin shadow
          Center(
            child: Container(
              width: 8,
              height: 8,
              decoration: BoxDecoration(
                color: Colors.black.withOpacity(0.3),
                shape: BoxShape.circle,
              ),
            ),
          ),

          // Top Bar
          SafeArea(
            child: Padding(
              padding: const EdgeInsets.all(16),
              child: Row(
                children: [
                  // Back button
                  Container(
                    decoration: BoxDecoration(
                      color: AppColors.surface,
                      borderRadius: BorderRadius.circular(12),
                      boxShadow: [
                        BoxShadow(
                          color: Colors.black.withOpacity(0.1),
                          blurRadius: 8,
                        ),
                      ],
                    ),
                    child: IconButton(
                      icon: const Icon(Icons.arrow_back),
                      onPressed: () => Navigator.pop(context),
                    ),
                  ),
                  const SizedBox(width: 12),
                  // Title
                  Expanded(
                    child: Container(
                      padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 12),
                      decoration: BoxDecoration(
                        color: AppColors.surface,
                        borderRadius: BorderRadius.circular(12),
                        boxShadow: [
                          BoxShadow(
                            color: Colors.black.withOpacity(0.1),
                            blurRadius: 8,
                          ),
                        ],
                      ),
                      child: Text(
                        widget.isOrigin ? 'حدد نقطة الانطلاق' : 'حدد الوجهة',
                        style: const TextStyle(
                          fontWeight: FontWeight.bold,
                          fontSize: 16,
                        ),
                      ),
                    ),
                  ),
                ],
              ),
            ),
          ),

          // Bottom Panel
          Positioned(
            left: 0,
            right: 0,
            bottom: 0,
            child: Container(
              padding: const EdgeInsets.all(20),
              decoration: BoxDecoration(
                color: AppColors.surface,
                borderRadius: const BorderRadius.vertical(top: Radius.circular(24)),
                boxShadow: [
                  BoxShadow(
                    color: Colors.black.withOpacity(0.1),
                    blurRadius: 10,
                    offset: const Offset(0, -4),
                  ),
                ],
              ),
              child: Column(
                mainAxisSize: MainAxisSize.min,
                children: [
                  // Drag handle
                  Container(
                    width: 40,
                    height: 4,
                    margin: const EdgeInsets.only(bottom: 16),
                    decoration: BoxDecoration(
                      color: AppColors.divider,
                      borderRadius: BorderRadius.circular(2),
                    ),
                  ),

                  // Location info
                  Row(
                    children: [
                      Container(
                        padding: const EdgeInsets.all(12),
                        decoration: BoxDecoration(
                          color: (widget.isOrigin ? AppColors.success : AppColors.error)
                              .withOpacity(0.1),
                          borderRadius: BorderRadius.circular(12),
                        ),
                        child: Icon(
                          widget.isOrigin ? Icons.circle : Icons.location_on,
                          color: widget.isOrigin ? AppColors.success : AppColors.error,
                          size: widget.isOrigin ? 16 : 24,
                        ),
                      ),
                      const SizedBox(width: 12),
                      Expanded(
                        child: Column(
                          crossAxisAlignment: CrossAxisAlignment.start,
                          children: [
                            Text(
                              widget.isOrigin ? 'نقطة الانطلاق' : 'الوجهة',
                              style: const TextStyle(
                                color: AppColors.textSecondary,
                                fontSize: 12,
                              ),
                            ),
                            const SizedBox(height: 4),
                            if (_isLoadingAddress)
                              const SizedBox(
                                height: 16,
                                width: 16,
                                child: CircularProgressIndicator(strokeWidth: 2),
                              )
                            else
                              Text(
                                _selectedAddress.isNotEmpty
                                    ? _selectedAddress
                                    : 'حرّك الخريطة لتحديد الموقع',
                                style: const TextStyle(
                                  fontWeight: FontWeight.w500,
                                  fontSize: 14,
                                ),
                                maxLines: 2,
                                overflow: TextOverflow.ellipsis,
                              ),
                          ],
                        ),
                      ),
                    ],
                  ),

                  const SizedBox(height: 16),

                  // Coordinates display
                  if (_selectedLocation != null)
                    Container(
                      padding: const EdgeInsets.all(12),
                      decoration: BoxDecoration(
                        color: AppColors.background,
                        borderRadius: BorderRadius.circular(8),
                      ),
                      child: Row(
                        mainAxisAlignment: MainAxisAlignment.center,
                        children: [
                          const Icon(Icons.gps_fixed, size: 16, color: AppColors.textSecondary),
                          const SizedBox(width: 8),
                          Text(
                            '${_selectedLocation!.latitude.toStringAsFixed(6)}, ${_selectedLocation!.longitude.toStringAsFixed(6)}',
                            style: const TextStyle(
                              fontFamily: 'monospace',
                              fontSize: 12,
                              color: AppColors.textSecondary,
                            ),
                          ),
                        ],
                      ),
                    ),

                  const SizedBox(height: 16),

                  // Actions
                  Row(
                    children: [
                      // My location button
                      Expanded(
                        child: OutlinedButton.icon(
                          onPressed: _isLoadingLocation ? null : _getCurrentLocation,
                          icon: _isLoadingLocation
                              ? const SizedBox(
                                  width: 16,
                                  height: 16,
                                  child: CircularProgressIndicator(strokeWidth: 2),
                                )
                              : const Icon(Icons.my_location),
                          label: const Text('موقعي'),
                          style: OutlinedButton.styleFrom(
                            padding: const EdgeInsets.symmetric(vertical: 14),
                            shape: RoundedRectangleBorder(
                              borderRadius: BorderRadius.circular(12),
                            ),
                          ),
                        ),
                      ),
                      const SizedBox(width: 12),
                      // Confirm button
                      Expanded(
                        flex: 2,
                        child: ElevatedButton.icon(
                          onPressed: _selectedLocation != null ? _confirmLocation : null,
                          icon: const Icon(Icons.check),
                          label: const Text('تأكيد الموقع'),
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
                    ],
                  ),

                  // Safe area padding
                  SizedBox(height: MediaQuery.of(context).padding.bottom),
                ],
              ),
            ),
          ),

          // Zoom controls
          Positioned(
            right: 16,
            bottom: 280,
            child: Column(
              children: [
                Container(
                  decoration: BoxDecoration(
                    color: AppColors.surface,
                    borderRadius: BorderRadius.circular(8),
                    boxShadow: [
                      BoxShadow(
                        color: Colors.black.withOpacity(0.1),
                        blurRadius: 8,
                      ),
                    ],
                  ),
                  child: Column(
                    children: [
                      IconButton(
                        icon: const Icon(Icons.add),
                        onPressed: () {
                          _mapController?.animateCamera(CameraUpdate.zoomIn());
                        },
                      ),
                      const Divider(height: 1),
                      IconButton(
                        icon: const Icon(Icons.remove),
                        onPressed: () {
                          _mapController?.animateCamera(CameraUpdate.zoomOut());
                        },
                      ),
                    ],
                  ),
                ),
              ],
            ),
          ),
        ],
      ),
    );
  }
}
