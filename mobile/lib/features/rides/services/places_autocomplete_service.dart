import 'dart:async';
import 'dart:convert';
import 'package:flutter/foundation.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:google_maps_flutter/google_maps_flutter.dart';
import 'package:http/http.dart' as http;

import '../../../core/constants/app_constants.dart';

/// Place prediction from Google Places Autocomplete
class PlacePrediction {
  final String placeId;
  final String description;
  final String mainText;
  final String? secondaryText;

  PlacePrediction({
    required this.placeId,
    required this.description,
    required this.mainText,
    this.secondaryText,
  });

  factory PlacePrediction.fromJson(Map<String, dynamic> json) {
    final structuredFormatting = json['structured_formatting'] as Map<String, dynamic>?;
    return PlacePrediction(
      placeId: json['place_id'] as String,
      description: json['description'] as String,
      mainText: structuredFormatting?['main_text'] as String? ?? json['description'] as String,
      secondaryText: structuredFormatting?['secondary_text'] as String?,
    );
  }
}

/// Place details with coordinates
class PlaceDetails {
  final String placeId;
  final String name;
  final String address;
  final LatLng location;

  PlaceDetails({
    required this.placeId,
    required this.name,
    required this.address,
    required this.location,
  });
}

/// Service for Google Places Autocomplete API
class PlacesAutocompleteService {
  static const String _baseUrl = 'https://maps.googleapis.com/maps/api/place';
  final String _apiKey = AppConstants.googleMapsApiKey;

  /// Get autocomplete predictions for a search query
  /// Biased towards Egypt for better local results
  Future<List<PlacePrediction>> getAutocompletePredictions(
    String query, {
    LatLng? userLocation,
  }) async {
    if (query.isEmpty || query.length < 2) {
      return [];
    }

    try {
      // Build query parameters
      final params = {
        'input': query,
        'key': _apiKey,
        'language': 'ar', // Arabic for better Egypt results
        'components': 'country:eg', // Restrict to Egypt
      };

      // Add location bias if available
      if (userLocation != null) {
        params['location'] = '${userLocation.latitude},${userLocation.longitude}';
        params['radius'] = '50000'; // 50km radius
      } else {
        // Default to Cairo
        params['location'] = '30.0444,31.2357';
        params['radius'] = '100000'; // 100km radius
      }

      final uri = Uri.parse('$_baseUrl/autocomplete/json').replace(queryParameters: params);

      debugPrint('Places Autocomplete: Searching for "$query"');

      final response = await http.get(uri).timeout(const Duration(seconds: 10));

      if (response.statusCode == 200) {
        final data = json.decode(response.body);
        final status = data['status'] as String?;

        if (status == 'OK') {
          final predictions = (data['predictions'] as List?)
              ?.map((p) => PlacePrediction.fromJson(p as Map<String, dynamic>))
              .toList() ?? [];

          debugPrint('Places Autocomplete: Found ${predictions.length} results');
          return predictions;
        } else if (status == 'ZERO_RESULTS') {
          debugPrint('Places Autocomplete: No results for "$query"');
          return [];
        } else {
          debugPrint('Places Autocomplete: Error status: $status');
          return [];
        }
      }
    } catch (e) {
      debugPrint('Places Autocomplete error: $e');
    }

    return [];
  }

  /// Get place details (coordinates) from place ID
  Future<PlaceDetails?> getPlaceDetails(String placeId) async {
    try {
      final params = {
        'place_id': placeId,
        'key': _apiKey,
        'language': 'ar',
        'fields': 'place_id,name,formatted_address,geometry',
      };

      final uri = Uri.parse('$_baseUrl/details/json').replace(queryParameters: params);

      debugPrint('Places Details: Getting details for $placeId');

      final response = await http.get(uri).timeout(const Duration(seconds: 10));

      if (response.statusCode == 200) {
        final data = json.decode(response.body);
        final status = data['status'] as String?;

        if (status == 'OK') {
          final result = data['result'] as Map<String, dynamic>?;
          if (result != null) {
            final geometry = result['geometry'] as Map<String, dynamic>?;
            final location = geometry?['location'] as Map<String, dynamic>?;

            if (location != null) {
              final lat = (location['lat'] as num).toDouble();
              final lng = (location['lng'] as num).toDouble();

              debugPrint('Places Details: Found location ($lat, $lng)');

              return PlaceDetails(
                placeId: placeId,
                name: result['name'] as String? ?? '',
                address: result['formatted_address'] as String? ?? '',
                location: LatLng(lat, lng),
              );
            }
          }
        } else {
          debugPrint('Places Details: Error status: $status');
        }
      }
    } catch (e) {
      debugPrint('Places Details error: $e');
    }

    return null;
  }
}

/// Provider for PlacesAutocompleteService
final placesAutocompleteServiceProvider = Provider<PlacesAutocompleteService>((ref) {
  return PlacesAutocompleteService();
});

/// State for autocomplete search
class AutocompleteState {
  final bool isLoading;
  final List<PlacePrediction> predictions;
  final String? error;

  const AutocompleteState({
    this.isLoading = false,
    this.predictions = const [],
    this.error,
  });

  AutocompleteState copyWith({
    bool? isLoading,
    List<PlacePrediction>? predictions,
    String? error,
  }) {
    return AutocompleteState(
      isLoading: isLoading ?? this.isLoading,
      predictions: predictions ?? this.predictions,
      error: error,
    );
  }
}

/// Notifier for autocomplete state with debouncing
class AutocompleteNotifier extends StateNotifier<AutocompleteState> {
  final PlacesAutocompleteService _service;
  Timer? _debounceTimer;
  LatLng? _userLocation;

  AutocompleteNotifier(this._service) : super(const AutocompleteState());

  void setUserLocation(LatLng? location) {
    _userLocation = location;
  }

  void search(String query) {
    // Cancel previous timer
    _debounceTimer?.cancel();

    if (query.isEmpty || query.length < 2) {
      state = const AutocompleteState();
      return;
    }

    state = state.copyWith(isLoading: true);

    // Debounce: wait 300ms before searching
    _debounceTimer = Timer(const Duration(milliseconds: 300), () async {
      try {
        final predictions = await _service.getAutocompletePredictions(
          query,
          userLocation: _userLocation,
        );
        state = AutocompleteState(predictions: predictions);
      } catch (e) {
        state = AutocompleteState(error: e.toString());
      }
    });
  }

  void clear() {
    _debounceTimer?.cancel();
    state = const AutocompleteState();
  }

  @override
  void dispose() {
    _debounceTimer?.cancel();
    super.dispose();
  }
}

/// Provider for autocomplete notifier
final autocompleteProvider = StateNotifierProvider.autoDispose<AutocompleteNotifier, AutocompleteState>((ref) {
  final service = ref.watch(placesAutocompleteServiceProvider);
  return AutocompleteNotifier(service);
});
