import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:google_maps_flutter/google_maps_flutter.dart';
import 'package:url_launcher/url_launcher.dart';

import '../../../core/constants/app_colors.dart';
import '../../../core/services/native_services.dart';
import '../services/ride_service.dart';
import '../models/price_option.dart';

class PriceComparisonScreen extends ConsumerStatefulWidget {
  final LatLng origin;
  final LatLng destination;
  final String originAddress;
  final String destinationAddress;

  const PriceComparisonScreen({
    super.key,
    required this.origin,
    required this.destination,
    required this.originAddress,
    required this.destinationAddress,
  });

  @override
  ConsumerState<PriceComparisonScreen> createState() => _PriceComparisonScreenState();
}

class _PriceComparisonScreenState extends ConsumerState<PriceComparisonScreen> {
  List<PriceOption>? _priceOptions;
  bool _isLoading = true;
  String? _error;
  bool _isFetchingRealPrices = false;
  String _fetchingAppName = '';
  int _fetchingCurrent = 0;
  int _fetchingTotal = 0;
  Map<String, double> _realPrices = {};

  @override
  void initState() {
    super.initState();
    _loadPrices();
  }

  Future<void> _loadPrices() async {
    setState(() {
      _isLoading = true;
      _error = null;
    });

    try {
      final rideService = ref.read(rideServiceProvider);
      final options = await rideService.getPriceComparison(
        origin: widget.origin,
        destination: widget.destination,
      );

      if (mounted) {
        setState(() {
          _priceOptions = options;
          _isLoading = false;
        });
      }
    } catch (e) {
      if (mounted) {
        setState(() {
          _error = e.toString();
          _isLoading = false;
        });
      }
    }
  }

  Future<void> _fetchRealPrices() async {
    final nativeServices = ref.read(nativeServicesProvider);

    // Check if accessibility service is enabled
    final isEnabled = await nativeServices.isAccessibilityEnabled();
    if (!isEnabled) {
      _showAccessibilityDialog();
      return;
    }

    setState(() {
      _isFetchingRealPrices = true;
      _fetchingCurrent = 0;
      _fetchingTotal = 0;
    });

    try {
      final prices = await nativeServices.fetchAllRealPrices(
        pickupLat: widget.origin.latitude,
        pickupLng: widget.origin.longitude,
        dropoffLat: widget.destination.latitude,
        dropoffLng: widget.destination.longitude,
        pickupAddress: widget.originAddress,
        dropoffAddress: widget.destinationAddress,
        onProgress: (appName, current, total) {
          if (mounted) {
            setState(() {
              _fetchingAppName = appName;
              _fetchingCurrent = current;
              _fetchingTotal = total;
            });
          }
        },
      );

      if (mounted) {
        setState(() {
          _realPrices = prices;
          _isFetchingRealPrices = false;
        });

        // Update price options with real prices
        _updatePricesWithReal(prices);
      }
    } catch (e) {
      if (mounted) {
        setState(() {
          _isFetchingRealPrices = false;
        });
        _showError('فشل في جلب الأسعار الحقيقية');
      }
    }
  }

  void _updatePricesWithReal(Map<String, double> realPrices) {
    if (_priceOptions == null) return;

    final nativeServices = ref.read(nativeServicesProvider);
    final updatedOptions = _priceOptions!.map((option) {
      String? packageName;
      switch (option.provider.toLowerCase()) {
        case 'uber':
          packageName = NativeServicesManager.uberPackage;
          break;
        case 'careem':
          packageName = NativeServicesManager.careemPackage;
          break;
        case 'indriver':
          packageName = NativeServicesManager.indriverPackage;
          break;
        case 'didi':
          packageName = NativeServicesManager.didiPackage;
          break;
        case 'bolt':
          packageName = NativeServicesManager.boltPackage;
          break;
      }

      if (packageName != null && realPrices.containsKey(packageName)) {
        return option.copyWith(
          price: realPrices[packageName]!,
          isEstimate: false,
        );
      }
      return option;
    }).toList();

    // Sort by price
    updatedOptions.sort((a, b) => a.price.compareTo(b.price));

    setState(() {
      _priceOptions = updatedOptions;
    });
  }

  void _showAccessibilityDialog() {
    final nativeServices = ref.read(nativeServicesProvider);

    showDialog(
      context: context,
      builder: (context) => AlertDialog(
        title: const Text('تفعيل خدمة إمكانية الوصول'),
        content: const Text(
          'لجلب الأسعار الحقيقية من التطبيقات الأخرى، يجب تفعيل خدمة إمكانية الوصول لـ GO-ON.\n\n'
          'اذهب إلى:\n'
          'الإعدادات ← إمكانية الوصول ← الخدمات المثبتة ← GO-ON Price Reader ← تفعيل',
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(context),
            child: const Text('لاحقاً'),
          ),
          ElevatedButton(
            onPressed: () {
              Navigator.pop(context);
              nativeServices.openAccessibilitySettings();
            },
            child: const Text('فتح الإعدادات'),
          ),
        ],
      ),
    );
  }

  @override
  Widget build(BuildContext context) {
    final rideService = ref.read(rideServiceProvider);
    final distance = rideService.calculateDistance(widget.origin, widget.destination);
    final minutes = rideService.calculateEstimatedMinutes(distance);

    return Scaffold(
      appBar: AppBar(
        title: const Text('مقارنة الأسعار'),
        backgroundColor: Colors.transparent,
        elevation: 0,
        foregroundColor: AppColors.textPrimary,
      ),
      body: Column(
        children: [
          // Route Info Header
          Container(
            padding: const EdgeInsets.all(16),
            color: AppColors.surface,
            child: Column(
              children: [
                // Origin
                Row(
                  children: [
                    const Icon(Icons.circle, color: AppColors.success, size: 12),
                    const SizedBox(width: 12),
                    Expanded(
                      child: Text(
                        widget.originAddress,
                        style: const TextStyle(fontSize: 14),
                        maxLines: 1,
                        overflow: TextOverflow.ellipsis,
                      ),
                    ),
                  ],
                ),
                // Vertical line
                Container(
                  margin: const EdgeInsets.only(left: 5),
                  padding: const EdgeInsets.symmetric(vertical: 4),
                  child: Row(
                    children: [
                      Container(
                        width: 2,
                        height: 20,
                        color: AppColors.divider,
                      ),
                    ],
                  ),
                ),
                // Destination
                Row(
                  children: [
                    const Icon(Icons.location_on, color: AppColors.error, size: 14),
                    const SizedBox(width: 12),
                    Expanded(
                      child: Text(
                        widget.destinationAddress,
                        style: const TextStyle(fontSize: 14),
                        maxLines: 1,
                        overflow: TextOverflow.ellipsis,
                      ),
                    ),
                  ],
                ),
                const SizedBox(height: 12),
                // Distance & Time
                Container(
                  padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 8),
                  decoration: BoxDecoration(
                    color: AppColors.background,
                    borderRadius: BorderRadius.circular(8),
                  ),
                  child: Row(
                    mainAxisSize: MainAxisSize.min,
                    children: [
                      const Icon(Icons.straighten, size: 16, color: AppColors.textSecondary),
                      const SizedBox(width: 4),
                      Text(
                        '${distance.toStringAsFixed(1)} كم',
                        style: const TextStyle(
                          fontWeight: FontWeight.bold,
                          fontSize: 14,
                        ),
                      ),
                      const SizedBox(width: 16),
                      const Icon(Icons.access_time, size: 16, color: AppColors.textSecondary),
                      const SizedBox(width: 4),
                      Text(
                        '$minutes دقيقة',
                        style: const TextStyle(
                          fontWeight: FontWeight.bold,
                          fontSize: 14,
                        ),
                      ),
                    ],
                  ),
                ),
              ],
            ),
          ),

          // Fetch Real Prices Button
          if (!_isLoading && _error == null && !_isFetchingRealPrices)
            Container(
              padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 8),
              child: SizedBox(
                width: double.infinity,
                child: ElevatedButton.icon(
                  onPressed: _fetchRealPrices,
                  icon: const Icon(Icons.refresh),
                  label: const Text('جلب الأسعار الحقيقية'),
                  style: ElevatedButton.styleFrom(
                    backgroundColor: AppColors.secondary,
                    foregroundColor: Colors.white,
                    padding: const EdgeInsets.symmetric(vertical: 12),
                    shape: RoundedRectangleBorder(
                      borderRadius: BorderRadius.circular(12),
                    ),
                  ),
                ),
              ),
            ),

          // Fetching Progress
          if (_isFetchingRealPrices)
            Container(
              padding: const EdgeInsets.all(16),
              child: Column(
                children: [
                  const CircularProgressIndicator(),
                  const SizedBox(height: 12),
                  Text(
                    'جاري جلب السعر من $_fetchingAppName...',
                    style: const TextStyle(fontWeight: FontWeight.bold),
                  ),
                  const SizedBox(height: 4),
                  Text(
                    '$_fetchingCurrent من $_fetchingTotal',
                    style: const TextStyle(color: AppColors.textSecondary),
                  ),
                  const SizedBox(height: 8),
                  LinearProgressIndicator(
                    value: _fetchingTotal > 0 ? _fetchingCurrent / _fetchingTotal : 0,
                    backgroundColor: AppColors.divider,
                    valueColor: const AlwaysStoppedAnimation<Color>(AppColors.primary),
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
                : _error != null
                    ? Center(
                        child: Column(
                          mainAxisAlignment: MainAxisAlignment.center,
                          children: [
                            const Icon(Icons.error_outline, size: 48, color: AppColors.error),
                            const SizedBox(height: 16),
                            Text('حدث خطأ: $_error'),
                            const SizedBox(height: 16),
                            ElevatedButton(
                              onPressed: _loadPrices,
                              child: const Text('إعادة المحاولة'),
                            ),
                          ],
                        ),
                      )
                    : RefreshIndicator(
                        onRefresh: _loadPrices,
                        child: ListView.builder(
                          padding: const EdgeInsets.all(16),
                          itemCount: _priceOptions?.length ?? 0,
                          itemBuilder: (context, index) {
                            final option = _priceOptions![index];
                            return _buildPriceCard(option, index == 0);
                          },
                        ),
                      ),
          ),
        ],
      ),
    );
  }

  Widget _buildPriceCard(PriceOption option, bool isBest) {
    return Container(
      margin: const EdgeInsets.only(bottom: 12),
      decoration: BoxDecoration(
        color: AppColors.surface,
        borderRadius: BorderRadius.circular(16),
        border: isBest
            ? Border.all(color: AppColors.success, width: 2)
            : null,
        boxShadow: [
          BoxShadow(
            color: Colors.black.withOpacity(0.05),
            blurRadius: 8,
            offset: const Offset(0, 2),
          ),
        ],
      ),
      child: Column(
        children: [
          // Best price badge
          if (isBest)
            Container(
              width: double.infinity,
              padding: const EdgeInsets.symmetric(vertical: 6),
              decoration: const BoxDecoration(
                color: AppColors.success,
                borderRadius: BorderRadius.vertical(top: Radius.circular(14)),
              ),
              child: const Row(
                mainAxisAlignment: MainAxisAlignment.center,
                children: [
                  Icon(Icons.star, color: Colors.white, size: 16),
                  SizedBox(width: 4),
                  Text(
                    'أفضل سعر',
                    style: TextStyle(
                      color: Colors.white,
                      fontWeight: FontWeight.bold,
                      fontSize: 12,
                    ),
                  ),
                ],
              ),
            ),

          Padding(
            padding: const EdgeInsets.all(16),
            child: Row(
              children: [
                // Provider Icon
                Container(
                  width: 50,
                  height: 50,
                  decoration: BoxDecoration(
                    color: _getProviderColor(option.provider).withOpacity(0.1),
                    borderRadius: BorderRadius.circular(12),
                  ),
                  child: Center(
                    child: Text(
                      option.providerIcon,
                      style: const TextStyle(fontSize: 24),
                    ),
                  ),
                ),

                const SizedBox(width: 12),

                // Info
                Expanded(
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Row(
                        children: [
                          Text(
                            option.name,
                            style: const TextStyle(
                              fontWeight: FontWeight.bold,
                              fontSize: 16,
                            ),
                          ),
                          if (option.isEstimate)
                            Container(
                              margin: const EdgeInsets.only(right: 8),
                              padding: const EdgeInsets.symmetric(horizontal: 6, vertical: 2),
                              decoration: BoxDecoration(
                                color: AppColors.warning.withOpacity(0.1),
                                borderRadius: BorderRadius.circular(4),
                              ),
                              child: const Text(
                                'تقديري',
                                style: TextStyle(
                                  fontSize: 10,
                                  color: AppColors.warning,
                                ),
                              ),
                            )
                          else if (option.provider.toLowerCase() != 'go-on')
                            Container(
                              margin: const EdgeInsets.only(right: 8),
                              padding: const EdgeInsets.symmetric(horizontal: 6, vertical: 2),
                              decoration: BoxDecoration(
                                color: AppColors.success.withOpacity(0.1),
                                borderRadius: BorderRadius.circular(4),
                              ),
                              child: const Text(
                                'حقيقي',
                                style: TextStyle(
                                  fontSize: 10,
                                  color: AppColors.success,
                                ),
                              ),
                            ),
                        ],
                      ),
                      const SizedBox(height: 4),
                      Row(
                        children: [
                          if (option.vehicleInfo != null) ...[
                            Text(
                              option.vehicleInfo!,
                              style: const TextStyle(
                                color: AppColors.textSecondary,
                                fontSize: 12,
                              ),
                            ),
                            const Text(' • ', style: TextStyle(color: AppColors.textSecondary)),
                          ],
                          if (option.category != null)
                            Text(
                              option.category!,
                              style: const TextStyle(
                                color: AppColors.textSecondary,
                                fontSize: 12,
                              ),
                            ),
                          Text(
                            ' • ${option.etaMinutes} د',
                            style: const TextStyle(
                              color: AppColors.textSecondary,
                              fontSize: 12,
                            ),
                          ),
                        ],
                      ),
                      if (option.rating != null) ...[
                        const SizedBox(height: 4),
                        Row(
                          children: [
                            const Icon(Icons.star, color: AppColors.secondary, size: 14),
                            const SizedBox(width: 2),
                            Text(
                              option.formattedRating,
                              style: const TextStyle(fontSize: 12),
                            ),
                            if (option.totalRides != null) ...[
                              const Text(' • ', style: TextStyle(color: AppColors.textSecondary)),
                              Text(
                                '${option.totalRides} رحلة',
                                style: const TextStyle(
                                  color: AppColors.textSecondary,
                                  fontSize: 12,
                                ),
                              ),
                            ],
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
                    if (option.discount != null) ...[
                      Text(
                        '${(option.price * (1 + option.discount! / 100)).round()} ج.م',
                        style: const TextStyle(
                          decoration: TextDecoration.lineThrough,
                          color: AppColors.textSecondary,
                          fontSize: 12,
                        ),
                      ),
                      Container(
                        padding: const EdgeInsets.symmetric(horizontal: 4, vertical: 2),
                        decoration: BoxDecoration(
                          color: AppColors.error.withOpacity(0.1),
                          borderRadius: BorderRadius.circular(4),
                        ),
                        child: Text(
                          '-${option.discount}%',
                          style: const TextStyle(
                            color: AppColors.error,
                            fontSize: 10,
                            fontWeight: FontWeight.bold,
                          ),
                        ),
                      ),
                    ],
                    Text(
                      option.formattedPrice,
                      style: TextStyle(
                        fontWeight: FontWeight.bold,
                        fontSize: 20,
                        color: isBest ? AppColors.success : AppColors.textPrimary,
                      ),
                    ),
                  ],
                ),
              ],
            ),
          ),

          // Action Button
          Container(
            width: double.infinity,
            padding: const EdgeInsets.fromLTRB(16, 0, 16, 16),
            child: ElevatedButton(
              onPressed: () => _onSelectOption(option),
              style: ElevatedButton.styleFrom(
                backgroundColor: _getProviderColor(option.provider),
                foregroundColor: Colors.white,
                padding: const EdgeInsets.symmetric(vertical: 12),
                shape: RoundedRectangleBorder(
                  borderRadius: BorderRadius.circular(12),
                ),
              ),
              child: Text(
                option.provider == 'GO-ON' ? 'تواصل عبر واتساب' : 'افتح ${option.name}',
                style: const TextStyle(fontWeight: FontWeight.bold),
              ),
            ),
          ),
        ],
      ),
    );
  }

  Color _getProviderColor(String provider) {
    switch (provider.toLowerCase()) {
      case 'uber':
        return const Color(0xFF000000);
      case 'careem':
        return const Color(0xFF4CAF50);
      case 'indriver':
        return const Color(0xFF2196F3);
      case 'didi':
        return const Color(0xFFFF6600);
      case 'bolt':
        return const Color(0xFF34D186);
      case 'go-on':
        return AppColors.primary;
      default:
        return AppColors.primary;
    }
  }

  Future<void> _onSelectOption(PriceOption option) async {
    final nativeServices = ref.read(nativeServicesProvider);

    if (option.provider == 'GO-ON') {
      // Independent driver - open WhatsApp
      if (option.driverPhone != null) {
        final message = '''
مرحباً، أريد حجز رحلة عبر GO-ON
من: ${widget.originAddress}
إلى: ${widget.destinationAddress}
السعر المتوقع: ${option.formattedPrice}
''';
        final whatsappUrl = option.getWhatsAppLink(message);
        if (whatsappUrl != null) {
          final uri = Uri.parse(whatsappUrl);
          if (await canLaunchUrl(uri)) {
            await launchUrl(uri, mode: LaunchMode.externalApplication);
          } else {
            _showError('لا يمكن فتح واتساب');
          }
        }
      } else {
        _showError('رقم السائق غير متوفر');
      }
    } else {
      // External app - use native method to open app directly
      String? packageName;
      switch (option.provider.toLowerCase()) {
        case 'uber':
          packageName = NativeServicesManager.uberPackage;
          break;
        case 'careem':
          packageName = NativeServicesManager.careemPackage;
          break;
        case 'indriver':
          packageName = NativeServicesManager.indriverPackage;
          break;
        case 'didi':
          packageName = NativeServicesManager.didiPackage;
          break;
        case 'bolt':
          packageName = NativeServicesManager.boltPackage;
          break;
      }

      if (packageName != null) {
        // Try to open app with trip details using native method
        final opened = await nativeServices.openAppWithTrip(
          packageName: packageName,
          pickupLat: widget.origin.latitude,
          pickupLng: widget.origin.longitude,
          dropoffLat: widget.destination.latitude,
          dropoffLng: widget.destination.longitude,
          pickupAddress: widget.originAddress,
          dropoffAddress: widget.destinationAddress,
        );
        if (!opened) {
          // App not installed - show store dialog
          _showAppNotInstalled(option.provider);
        }
      } else {
        // Fallback to deep link for unknown providers
        final deepLink = option.getDeepLink(
          originLat: widget.origin.latitude,
          originLng: widget.origin.longitude,
          destLat: widget.destination.latitude,
          destLng: widget.destination.longitude,
        );

        if (deepLink != null) {
          final uri = Uri.parse(deepLink);
          if (await canLaunchUrl(uri)) {
            await launchUrl(uri, mode: LaunchMode.externalApplication);
          } else {
            _showError('لا يمكن فتح ${option.name}');
          }
        }
      }
    }
  }

  void _showError(String message) {
    ScaffoldMessenger.of(context).showSnackBar(
      SnackBar(content: Text(message), backgroundColor: AppColors.error),
    );
  }

  void _showAppNotInstalled(String provider) {
    showDialog(
      context: context,
      builder: (context) => AlertDialog(
        title: Text('$provider غير مثبت'),
        content: Text('يرجى تثبيت تطبيق $provider من المتجر'),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(context),
            child: const Text('حسناً'),
          ),
          ElevatedButton(
            onPressed: () async {
              Navigator.pop(context);
              String? storeUrl;
              switch (provider.toLowerCase()) {
                case 'uber':
                  storeUrl = 'https://play.google.com/store/apps/details?id=com.ubercab';
                  break;
                case 'careem':
                  storeUrl = 'https://play.google.com/store/apps/details?id=com.careem.acma';
                  break;
                case 'indriver':
                  storeUrl = 'https://play.google.com/store/apps/details?id=sinet.startup.inDriver';
                  break;
                case 'didi':
                  storeUrl = 'https://play.google.com/store/apps/details?id=com.didiglobal.passenger';
                  break;
                case 'bolt':
                  storeUrl = 'https://play.google.com/store/apps/details?id=ee.mtakso.client';
                  break;
              }
              if (storeUrl != null) {
                final uri = Uri.parse(storeUrl);
                if (await canLaunchUrl(uri)) {
                  await launchUrl(uri, mode: LaunchMode.externalApplication);
                }
              }
            },
            child: const Text('فتح المتجر'),
          ),
        ],
      ),
    );
  }
}
