import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:google_maps_flutter/google_maps_flutter.dart';
import 'package:url_launcher/url_launcher.dart';

import '../../../core/constants/app_colors.dart';
import '../../../core/services/native_services.dart';
import '../../../providers/ride_preference_provider.dart';
import '../../../widgets/ride_sort_preference_selector.dart';
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

class _PriceComparisonScreenState extends ConsumerState<PriceComparisonScreen>
    with WidgetsBindingObserver {
  List<PriceOption>? _priceOptions;
  bool _isLoading = true;
  String? _error;
  Map<String, double> _realPrices = {};

  // Manual price capture state
  String? _pendingPackage;
  bool _awaitingReturn = false;
  String? _fetchingApp;

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
    _loadEstimatedPrices();
  }

  @override
  void dispose() {
    WidgetsBinding.instance.removeObserver(this);
    // Stop any ongoing monitoring
    ref.read(nativeServicesProvider).stopActiveMonitoring();
    super.dispose();
  }

  /// Called when app comes back to foreground
  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    super.didChangeAppLifecycleState(state);
    if (state == AppLifecycleState.resumed && _awaitingReturn && _pendingPackage != null) {
      // User returned from ride app - capture the price!
      _onReturnedFromApp(_pendingPackage!);
    }
  }

  /// Load ESTIMATED prices only (no automatic real price fetching)
  Future<void> _loadEstimatedPrices() async {
    setState(() {
      _isLoading = true;
      _error = null;
    });

    try {
      // Clear cached prices to ensure fresh estimates
      final nativeServices = ref.read(nativeServicesProvider);
      await nativeServices.clearPrices();

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

  Future<void> _loadPrices() async {
    await _loadEstimatedPrices();
  }

  /// Get the package name for a provider
  String? _getPackageName(String provider) {
    switch (provider.toLowerCase()) {
      case 'uber':
        return NativeServicesManager.uberPackage;
      case 'careem':
        return NativeServicesManager.careemPackage;
      case 'indriver':
        return NativeServicesManager.indriverPackage;
      case 'didi':
        return NativeServicesManager.didiPackage;
      case 'bolt':
        return NativeServicesManager.boltPackage;
      default:
        return null;
    }
  }

  /// FULL AUTOMATION - The REAL technical solution
  /// 1. User taps "جلب السعر الحقيقي"
  /// 2. Opens the ride app
  /// 3. AUTOMATICALLY enters destination
  /// 4. AUTOMATICALLY selects suggestion
  /// 5. AUTOMATICALLY captures price
  /// 6. User returns to GO-ON to see the price
  Future<void> _fetchRealPriceFor(PriceOption option) async {
    final packageName = _getPackageName(option.provider);
    if (packageName == null) return;

    final nativeServices = ref.read(nativeServicesProvider);

    // Check if accessibility service is enabled
    final isEnabled = await nativeServices.isAccessibilityEnabled();
    if (!isEnabled) {
      _showAccessibilityDialog();
      return;
    }

    // IMPORTANT: Sync user preference to native code BEFORE fetching
    final preferenceNotifier = ref.read(rideSortPreferenceProvider.notifier);
    await preferenceNotifier.syncPreferenceToNative();

    setState(() {
      _fetchingApp = option.name;
      _pendingPackage = packageName;
      _awaitingReturn = true;
    });

    // Show automation dialog and start
    _showAutomationDialog(option, packageName);
  }

  void _showAutomationDialog(PriceOption option, String packageName) {
    final nativeServices = ref.read(nativeServicesProvider);

    showDialog(
      context: context,
      barrierDismissible: false,
      builder: (context) => AlertDialog(
        title: Row(
          children: [
            Text(option.providerIcon, style: const TextStyle(fontSize: 24)),
            const SizedBox(width: 8),
            Expanded(child: Text('جلب سعر ${option.name}')),
          ],
        ),
        content: Column(
          mainAxisSize: MainAxisSize.min,
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Container(
              padding: const EdgeInsets.all(16),
              decoration: BoxDecoration(
                color: AppColors.success.withOpacity(0.1),
                borderRadius: BorderRadius.circular(12),
                border: Border.all(color: AppColors.success.withOpacity(0.3)),
              ),
              child: Column(
                children: [
                  const Icon(Icons.auto_fix_high, color: AppColors.success, size: 40),
                  const SizedBox(height: 12),
                  const Text(
                    'أتمتة كاملة!',
                    style: TextStyle(
                      fontSize: 18,
                      fontWeight: FontWeight.bold,
                      color: AppColors.success,
                    ),
                  ),
                  const SizedBox(height: 8),
                  Text(
                    'سيتم إدخال الوجهة تلقائياً:',
                    style: TextStyle(
                      color: Colors.grey[700],
                    ),
                  ),
                  const SizedBox(height: 8),
                  Text(
                    widget.destinationAddress,
                    style: const TextStyle(
                      fontWeight: FontWeight.bold,
                    ),
                    textAlign: TextAlign.center,
                    maxLines: 2,
                    overflow: TextOverflow.ellipsis,
                  ),
                ],
              ),
            ),
            const SizedBox(height: 16),
            const Text(
              'فقط ارجع إلى GO-ON بعد ظهور السعر',
              style: TextStyle(
                fontSize: 13,
                color: AppColors.textSecondary,
              ),
              textAlign: TextAlign.center,
            ),
          ],
        ),
        actions: [
          TextButton(
            onPressed: () {
              Navigator.pop(context);
              setState(() {
                _fetchingApp = null;
                _pendingPackage = null;
                _awaitingReturn = false;
              });
            },
            child: const Text('إلغاء'),
          ),
          ElevatedButton.icon(
            onPressed: () async {
              Navigator.pop(context);
              // Start FULL AUTOMATION
              await nativeServices.automateGetPrice(
                packageName: packageName,
                pickup: widget.originAddress,
                destination: widget.destinationAddress,
                pickupLat: widget.origin.latitude,
                pickupLng: widget.origin.longitude,
                destLat: widget.destination.latitude,
                destLng: widget.destination.longitude,
              );
            },
            icon: const Icon(Icons.play_arrow),
            label: const Text('ابدأ'),
            style: ElevatedButton.styleFrom(
              backgroundColor: _getProviderColor(option.provider),
            ),
          ),
        ],
      ),
    );
  }

  /// Called when user returns from ride app
  Future<void> _onReturnedFromApp(String packageName) async {
    final nativeServices = ref.read(nativeServicesProvider);

    setState(() {
      _awaitingReturn = false;
    });

    // Get the captured price
    final price = await nativeServices.getCapturedPrice(packageName);

    if (price != null && price > 0) {
      // SUCCESS! Update the price
      _updateSinglePrice(packageName, price);
      _showSuccess('تم التقاط السعر الحقيقي: ${price.round()} ج.م');
    } else {
      // No price captured - show helpful message
      _showNoPriceCaptured();
    }

    setState(() {
      _fetchingApp = null;
      _pendingPackage = null;
    });
  }

  void _updateSinglePrice(String packageName, double price) {
    if (_priceOptions == null) return;

    final updatedOptions = _priceOptions!.map((option) {
      final optionPackage = _getPackageName(option.provider);
      if (optionPackage == packageName) {
        return option.copyWith(
          price: price,
          isEstimate: false,
        );
      }
      return option;
    }).toList();

    // Sort by price
    updatedOptions.sort((a, b) => a.price.compareTo(b.price));

    setState(() {
      _priceOptions = updatedOptions;
      _realPrices[packageName] = price;
    });
  }

  void _showNoPriceCaptured() {
    showDialog(
      context: context,
      builder: (context) => AlertDialog(
        title: const Text('لم يتم التقاط السعر'),
        content: const Column(
          mainAxisSize: MainAxisSize.min,
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text('تأكد من:'),
            SizedBox(height: 8),
            Text('• إدخال نقطتي الانطلاق والوصول'),
            Text('• انتظار ظهور السعر على الشاشة'),
            Text('• تفعيل خدمة إمكانية الوصول'),
          ],
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(context),
            child: const Text('حسناً'),
          ),
          ElevatedButton(
            onPressed: () {
              Navigator.pop(context);
              final nativeServices = ref.read(nativeServicesProvider);
              nativeServices.openAccessibilitySettings();
            },
            child: const Text('إعدادات الوصول'),
          ),
        ],
      ),
    );
  }

  void _showSuccess(String message) {
    ScaffoldMessenger.of(context).showSnackBar(
      SnackBar(
        content: Row(
          children: [
            const Icon(Icons.check_circle, color: Colors.white),
            const SizedBox(width: 8),
            Text(message),
          ],
        ),
        backgroundColor: AppColors.success,
        duration: const Duration(seconds: 3),
      ),
    );
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
                const SizedBox(height: 12),
                // Ride Sort Preference Selector
                Row(
                  mainAxisAlignment: MainAxisAlignment.center,
                  children: [
                    const Icon(Icons.sort, size: 16, color: AppColors.textSecondary),
                    const SizedBox(width: 8),
                    const Text(
                      'ترتيب حسب:',
                      style: TextStyle(
                        fontSize: 13,
                        color: AppColors.textSecondary,
                      ),
                    ),
                    const SizedBox(width: 8),
                    const RideSortPreferenceChip(language: 'ar'),
                  ],
                ),
              ],
            ),
          ),

          // Awaiting return indicator
          if (_awaitingReturn && _fetchingApp != null)
            Container(
              padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 12),
              margin: const EdgeInsets.symmetric(horizontal: 16, vertical: 8),
              decoration: BoxDecoration(
                color: AppColors.primary.withOpacity(0.1),
                borderRadius: BorderRadius.circular(12),
                border: Border.all(color: AppColors.primary.withOpacity(0.3)),
              ),
              child: Row(
                children: [
                  const SizedBox(
                    width: 24,
                    height: 24,
                    child: CircularProgressIndicator(strokeWidth: 2),
                  ),
                  const SizedBox(width: 12),
                  Expanded(
                    child: Text(
                      'في انتظار العودة من $_fetchingApp',
                      style: const TextStyle(
                        fontWeight: FontWeight.bold,
                        fontSize: 13,
                      ),
                    ),
                  ),
                  TextButton(
                    onPressed: () {
                      setState(() {
                        _awaitingReturn = false;
                        _fetchingApp = null;
                        _pendingPackage = null;
                      });
                      ref.read(nativeServicesProvider).stopActiveMonitoring();
                    },
                    child: const Text('إلغاء'),
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

          // Action Buttons
          Container(
            width: double.infinity,
            padding: const EdgeInsets.fromLTRB(16, 0, 16, 16),
            child: Column(
              children: [
                // Fetch Real Price button - for all external apps
                if (option.provider != 'GO-ON')
                  Padding(
                    padding: const EdgeInsets.only(bottom: 8),
                    child: SizedBox(
                      width: double.infinity,
                      child: OutlinedButton.icon(
                        onPressed: _awaitingReturn ? null : () => _fetchRealPriceFor(option),
                        icon: Icon(
                          option.isEstimate ? Icons.price_check : Icons.refresh,
                          size: 18,
                        ),
                        label: Text(
                          option.isEstimate ? 'جلب السعر الحقيقي' : 'تحديث السعر',
                          style: const TextStyle(fontWeight: FontWeight.bold),
                        ),
                        style: OutlinedButton.styleFrom(
                          foregroundColor: option.isEstimate ? AppColors.primary : AppColors.success,
                          side: BorderSide(color: option.isEstimate ? AppColors.primary : AppColors.success),
                          padding: const EdgeInsets.symmetric(vertical: 10),
                          shape: RoundedRectangleBorder(
                            borderRadius: BorderRadius.circular(12),
                          ),
                        ),
                      ),
                    ),
                  ),
                // Main action button
                SizedBox(
                  width: double.infinity,
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
    // Show booking confirmation bottom sheet
    _showBookingConfirmation(option);
  }

  void _showBookingConfirmation(PriceOption option) {
    showModalBottomSheet(
      context: context,
      shape: const RoundedRectangleBorder(
        borderRadius: BorderRadius.vertical(top: Radius.circular(20)),
      ),
      builder: (context) => Container(
        padding: const EdgeInsets.all(24),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            // Handle
            Container(
              width: 40,
              height: 4,
              margin: const EdgeInsets.only(bottom: 20),
              decoration: BoxDecoration(
                color: AppColors.divider,
                borderRadius: BorderRadius.circular(2),
              ),
            ),
            // Title
            Text(
              'حجز مع ${option.name}',
              style: const TextStyle(
                fontSize: 20,
                fontWeight: FontWeight.bold,
              ),
            ),
            const SizedBox(height: 20),
            // Price
            Row(
              mainAxisAlignment: MainAxisAlignment.spaceBetween,
              children: [
                const Text(
                  'السعر',
                  style: TextStyle(
                    color: AppColors.textSecondary,
                    fontSize: 16,
                  ),
                ),
                Text(
                  option.formattedPrice,
                  style: TextStyle(
                    fontSize: 24,
                    fontWeight: FontWeight.bold,
                    color: _getProviderColor(option.provider),
                  ),
                ),
              ],
            ),
            const SizedBox(height: 8),
            // Badge
            Row(
              mainAxisAlignment: MainAxisAlignment.end,
              children: [
                Container(
                  padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 4),
                  decoration: BoxDecoration(
                    color: option.isEstimate
                        ? AppColors.warning.withOpacity(0.1)
                        : AppColors.success.withOpacity(0.1),
                    borderRadius: BorderRadius.circular(4),
                  ),
                  child: Text(
                    option.isEstimate ? 'سعر تقديري' : 'سعر حقيقي',
                    style: TextStyle(
                      fontSize: 12,
                      color: option.isEstimate ? AppColors.warning : AppColors.success,
                    ),
                  ),
                ),
              ],
            ),
            const SizedBox(height: 24),
            // Confirm Button
            SizedBox(
              width: double.infinity,
              child: ElevatedButton(
                onPressed: () {
                  Navigator.pop(context);
                  _confirmBooking(option);
                },
                style: ElevatedButton.styleFrom(
                  backgroundColor: _getProviderColor(option.provider),
                  foregroundColor: Colors.white,
                  padding: const EdgeInsets.symmetric(vertical: 16),
                  shape: RoundedRectangleBorder(
                    borderRadius: BorderRadius.circular(12),
                  ),
                ),
                child: Text(
                  option.provider == 'GO-ON' ? 'تواصل عبر واتساب' : 'تأكيد الحجز',
                  style: const TextStyle(
                    fontSize: 16,
                    fontWeight: FontWeight.bold,
                  ),
                ),
              ),
            ),
            const SizedBox(height: 12),
            // Cancel Button
            SizedBox(
              width: double.infinity,
              child: TextButton(
                onPressed: () => Navigator.pop(context),
                child: const Text('إلغاء'),
              ),
            ),
            SizedBox(height: MediaQuery.of(context).padding.bottom),
          ],
        ),
      ),
    );
  }

  Future<void> _confirmBooking(PriceOption option) async {
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
