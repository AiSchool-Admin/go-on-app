import 'dart:async';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import '../../../core/services/native_services.dart';
import '../../../core/constants/app_colors.dart';

/// شاشة اختبار الأتمتة
/// Automation Test Screen
///
/// تستخدم لاختبار جلب الأسعار من تطبيقات التوصيل المختلفة
class AutomationTestScreen extends ConsumerStatefulWidget {
  const AutomationTestScreen({super.key});

  @override
  ConsumerState<AutomationTestScreen> createState() => _AutomationTestScreenState();
}

class _AutomationTestScreenState extends ConsumerState<AutomationTestScreen> {
  // Test locations in Cairo
  // ميدان التحرير (Tahrir Square)
  final double _pickupLat = 30.0444;
  final double _pickupLng = 31.2357;
  final String _pickupAddress = 'ميدان التحرير، القاهرة';

  // الأهرامات (Pyramids)
  final double _destLat = 29.9792;
  final double _destLng = 31.1342;
  final String _destAddress = 'أهرامات الجيزة';

  // State
  bool _isLoading = false;
  bool _accessibilityEnabled = false;
  bool _overlayEnabled = false;
  List<String> _installedApps = [];
  final Map<String, AppTestResult> _testResults = {};
  final List<String> _logs = [];
  String _currentStatus = 'جاهز للاختبار';

  // App names
  final Map<String, String> _appNames = {
    'sinet.startup.inDriver': 'InDriver',
    'com.ubercab': 'Uber',
    'com.careem.acma': 'Careem',
    'com.didiglobal.passenger': 'DiDi',
    'ee.mtakso.client': 'Bolt',
  };

  @override
  void initState() {
    super.initState();
    _checkPermissionsAndApps();
  }

  Future<void> _checkPermissionsAndApps() async {
    final nativeServices = ref.read(nativeServicesProvider);

    setState(() {
      _isLoading = true;
      _logs.add('[${_time()}] جاري فحص الأذونات...');
    });

    // Check permissions
    final permissions = await nativeServices.checkAllPermissions();
    _accessibilityEnabled = permissions.accessibility;
    _overlayEnabled = permissions.overlay;

    _logs.add('[${_time()}] Accessibility: ${_accessibilityEnabled ? "✓" : "✗"}');
    _logs.add('[${_time()}] Overlay: ${_overlayEnabled ? "✓" : "✗"}');

    // Get installed apps
    _installedApps = await nativeServices.getInstalledRideApps();
    _logs.add('[${_time()}] التطبيقات المثبتة: ${_installedApps.length}');
    for (final app in _installedApps) {
      _logs.add('  - ${_appNames[app] ?? app}');
    }

    setState(() {
      _isLoading = false;
      _currentStatus = _accessibilityEnabled
          ? 'جاهز للاختبار'
          : 'يرجى تفعيل Accessibility Service';
    });
  }

  String _time() {
    final now = DateTime.now();
    return '${now.hour.toString().padLeft(2, '0')}:${now.minute.toString().padLeft(2, '0')}:${now.second.toString().padLeft(2, '0')}';
  }

  void _log(String message) {
    setState(() {
      _logs.add('[${_time()}] $message');
    });
  }

  Future<void> _testInDriver() async {
    if (!_accessibilityEnabled) {
      _showPermissionDialog();
      return;
    }

    final nativeServices = ref.read(nativeServicesProvider);
    const packageName = 'sinet.startup.inDriver';

    if (!_installedApps.contains(packageName)) {
      _log('⚠️ تطبيق InDriver غير مثبت');
      ScaffoldMessenger.of(context).showSnackBar(
        const SnackBar(content: Text('تطبيق InDriver غير مثبت')),
      );
      return;
    }

    setState(() {
      _isLoading = true;
      _currentStatus = 'جاري اختبار InDriver...';
      _testResults[packageName] = AppTestResult(
        status: TestStatus.running,
        startTime: DateTime.now(),
      );
    });

    _log('🚀 بدء اختبار InDriver');
    _log('📍 من: $_pickupAddress');
    _log('📍 إلى: $_destAddress');

    try {
      // Clear old prices
      await nativeServices.clearPrices();
      _log('🗑️ تم مسح الأسعار القديمة');

      // Start automation
      _log('⚙️ بدء الأتمتة...');
      final started = await nativeServices.automateGetPrice(
        packageName: packageName,
        pickup: _pickupAddress,
        destination: _destAddress,
        pickupLat: _pickupLat,
        pickupLng: _pickupLng,
        destLat: _destLat,
        destLng: _destLng,
      );

      if (!started) {
        _log('❌ فشل بدء الأتمتة');
        setState(() {
          _testResults[packageName] = AppTestResult(
            status: TestStatus.failed,
            startTime: _testResults[packageName]!.startTime,
            endTime: DateTime.now(),
            error: 'فشل بدء الأتمتة',
          );
          _isLoading = false;
          _currentStatus = 'فشل الاختبار';
        });
        return;
      }

      _log('✓ تم فتح التطبيق');
      setState(() {
        _currentStatus = 'جاري البحث عن السعر...';
      });

      // Poll for state updates
      for (int i = 0; i < 60; i++) {
        await Future.delayed(const Duration(milliseconds: 500));

        final state = await nativeServices.getAutomationState();
        if (i % 4 == 0) {
          _log('⏳ الحالة: $state');
        }

        final complete = await nativeServices.isAutomationComplete();
        if (complete) {
          _log('✓ اكتملت الأتمتة - الحالة: $state');

          if (state == 'PRICE_CAPTURED') {
            // Get price
            final priceInfo = await nativeServices.getPriceInfoForApp(packageName);

            if (priceInfo != null && priceInfo.price > 0) {
              _log('💰 السعر: ${priceInfo.price} ج.م');

              if (priceInfo.allPricesFound.isNotEmpty) {
                _log('📊 جميع الأسعار: ${priceInfo.allPricesFound.join(", ")} ج.م');
              }

              if (priceInfo.vehiclePrices.isNotEmpty) {
                _log('🚗 أنواع السيارات:');
                priceInfo.vehiclePrices.forEach((type, price) {
                  _log('   - $type: $price ج.م');
                });
              }

              setState(() {
                _testResults[packageName] = AppTestResult(
                  status: TestStatus.success,
                  startTime: _testResults[packageName]!.startTime,
                  endTime: DateTime.now(),
                  price: priceInfo.price,
                  allPrices: priceInfo.allPricesFound,
                  vehiclePrices: priceInfo.vehiclePrices,
                );
                _isLoading = false;
                _currentStatus = 'نجح الاختبار ✓';
              });
              return;
            }
          }

          _log('❌ لم يتم العثور على سعر');
          setState(() {
            _testResults[packageName] = AppTestResult(
              status: TestStatus.failed,
              startTime: _testResults[packageName]!.startTime,
              endTime: DateTime.now(),
              error: 'لم يتم العثور على سعر',
            );
            _isLoading = false;
            _currentStatus = 'فشل الاختبار';
          });
          return;
        }
      }

      // Timeout
      _log('⏱️ انتهت المهلة الزمنية');
      setState(() {
        _testResults[packageName] = AppTestResult(
          status: TestStatus.timeout,
          startTime: _testResults[packageName]!.startTime,
          endTime: DateTime.now(),
          error: 'انتهت المهلة الزمنية (30 ثانية)',
        );
        _isLoading = false;
        _currentStatus = 'انتهت المهلة';
      });
    } catch (e) {
      _log('❌ خطأ: $e');
      setState(() {
        _testResults[packageName] = AppTestResult(
          status: TestStatus.failed,
          startTime: _testResults[packageName]!.startTime,
          endTime: DateTime.now(),
          error: e.toString(),
        );
        _isLoading = false;
        _currentStatus = 'حدث خطأ';
      });
    } finally {
      await nativeServices.resetAutomation();
    }
  }

  Future<void> _testAllApps() async {
    if (!_accessibilityEnabled) {
      _showPermissionDialog();
      return;
    }

    _log('🚀 بدء اختبار جميع التطبيقات');

    for (final packageName in _installedApps) {
      final appName = _appNames[packageName] ?? packageName;
      _log('\n========== $appName ==========');

      if (packageName == 'sinet.startup.inDriver') {
        await _testInDriver();
      } else {
        // TODO: Add tests for other apps
        _log('⏭️ تخطي $appName (لم يتم تنفيذه بعد)');
      }

      // Wait between apps
      await Future.delayed(const Duration(seconds: 2));
    }

    _log('\n✓ اكتمل اختبار جميع التطبيقات');
  }

  void _showPermissionDialog() {
    showDialog(
      context: context,
      builder: (context) => AlertDialog(
        title: const Text('الأذونات مطلوبة'),
        content: const Text(
          'يجب تفعيل Accessibility Service للقيام بالاختبار.\n\n'
          'سيتم فتح الإعدادات لتفعيلها.',
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(context),
            child: const Text('إلغاء'),
          ),
          ElevatedButton(
            onPressed: () {
              Navigator.pop(context);
              ref.read(nativeServicesProvider).openAccessibilitySettings();
            },
            child: const Text('فتح الإعدادات'),
          ),
        ],
      ),
    );
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        title: const Text('اختبار الأتمتة'),
        backgroundColor: AppColors.primary,
        foregroundColor: Colors.white,
        actions: [
          IconButton(
            icon: const Icon(Icons.refresh),
            onPressed: _checkPermissionsAndApps,
          ),
        ],
      ),
      body: Column(
        children: [
          // Status Bar
          Container(
            width: double.infinity,
            padding: const EdgeInsets.all(16),
            color: _isLoading
                ? Colors.orange.shade100
                : _currentStatus.contains('نجح')
                    ? Colors.green.shade100
                    : _currentStatus.contains('فشل')
                        ? Colors.red.shade100
                        : Colors.blue.shade100,
            child: Row(
              children: [
                if (_isLoading)
                  const SizedBox(
                    width: 20,
                    height: 20,
                    child: CircularProgressIndicator(strokeWidth: 2),
                  )
                else
                  Icon(
                    _currentStatus.contains('نجح')
                        ? Icons.check_circle
                        : _currentStatus.contains('فشل')
                            ? Icons.error
                            : Icons.info,
                    color: _currentStatus.contains('نجح')
                        ? Colors.green
                        : _currentStatus.contains('فشل')
                            ? Colors.red
                            : Colors.blue,
                  ),
                const SizedBox(width: 12),
                Text(
                  _currentStatus,
                  style: const TextStyle(fontWeight: FontWeight.bold),
                ),
              ],
            ),
          ),

          // Permissions Status
          Padding(
            padding: const EdgeInsets.all(16),
            child: Row(
              children: [
                _buildPermissionChip('Accessibility', _accessibilityEnabled),
                const SizedBox(width: 8),
                _buildPermissionChip('Overlay', _overlayEnabled),
                const Spacer(),
                Text('${_installedApps.length} تطبيقات'),
              ],
            ),
          ),

          // Test Locations
          Container(
            margin: const EdgeInsets.symmetric(horizontal: 16),
            padding: const EdgeInsets.all(12),
            decoration: BoxDecoration(
              color: Colors.grey.shade100,
              borderRadius: BorderRadius.circular(8),
            ),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                const Text('مسار الاختبار:', style: TextStyle(fontWeight: FontWeight.bold)),
                const SizedBox(height: 8),
                Row(
                  children: [
                    const Icon(Icons.location_on, color: Colors.green, size: 20),
                    const SizedBox(width: 8),
                    Expanded(child: Text(_pickupAddress)),
                  ],
                ),
                const SizedBox(height: 4),
                Row(
                  children: [
                    const Icon(Icons.flag, color: Colors.red, size: 20),
                    const SizedBox(width: 8),
                    Expanded(child: Text(_destAddress)),
                  ],
                ),
              ],
            ),
          ),

          // Test Buttons
          Padding(
            padding: const EdgeInsets.all(16),
            child: Row(
              children: [
                Expanded(
                  child: ElevatedButton.icon(
                    onPressed: _isLoading ? null : _testInDriver,
                    icon: const Icon(Icons.play_arrow),
                    label: const Text('اختبار InDriver'),
                    style: ElevatedButton.styleFrom(
                      backgroundColor: AppColors.indriver,
                      foregroundColor: Colors.white,
                      padding: const EdgeInsets.symmetric(vertical: 12),
                    ),
                  ),
                ),
                const SizedBox(width: 8),
                Expanded(
                  child: OutlinedButton.icon(
                    onPressed: _isLoading ? null : _testAllApps,
                    icon: const Icon(Icons.playlist_play),
                    label: const Text('اختبار الكل'),
                    style: OutlinedButton.styleFrom(
                      padding: const EdgeInsets.symmetric(vertical: 12),
                    ),
                  ),
                ),
              ],
            ),
          ),

          // Test Results
          if (_testResults.isNotEmpty) ...[
            const Padding(
              padding: EdgeInsets.symmetric(horizontal: 16),
              child: Align(
                alignment: Alignment.centerRight,
                child: Text('النتائج:', style: TextStyle(fontWeight: FontWeight.bold)),
              ),
            ),
            SizedBox(
              height: 100,
              child: ListView.builder(
                scrollDirection: Axis.horizontal,
                padding: const EdgeInsets.symmetric(horizontal: 16),
                itemCount: _testResults.length,
                itemBuilder: (context, index) {
                  final entry = _testResults.entries.elementAt(index);
                  return _buildResultCard(entry.key, entry.value);
                },
              ),
            ),
          ],

          // Logs
          Expanded(
            child: Container(
              margin: const EdgeInsets.all(16),
              decoration: BoxDecoration(
                color: Colors.grey.shade900,
                borderRadius: BorderRadius.circular(8),
              ),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Container(
                    padding: const EdgeInsets.all(8),
                    decoration: BoxDecoration(
                      color: Colors.grey.shade800,
                      borderRadius: const BorderRadius.vertical(top: Radius.circular(8)),
                    ),
                    child: Row(
                      children: [
                        const Text(
                          'السجلات',
                          style: TextStyle(color: Colors.white, fontWeight: FontWeight.bold),
                        ),
                        const Spacer(),
                        IconButton(
                          icon: const Icon(Icons.delete, color: Colors.white, size: 18),
                          onPressed: () => setState(() => _logs.clear()),
                          padding: EdgeInsets.zero,
                          constraints: const BoxConstraints(),
                        ),
                      ],
                    ),
                  ),
                  Expanded(
                    child: ListView.builder(
                      padding: const EdgeInsets.all(8),
                      itemCount: _logs.length,
                      itemBuilder: (context, index) {
                        final log = _logs[index];
                        return Text(
                          log,
                          style: TextStyle(
                            fontFamily: 'monospace',
                            fontSize: 12,
                            color: log.contains('❌')
                                ? Colors.red.shade300
                                : log.contains('✓') || log.contains('💰')
                                    ? Colors.green.shade300
                                    : log.contains('⚠️')
                                        ? Colors.orange.shade300
                                        : Colors.grey.shade300,
                          ),
                        );
                      },
                    ),
                  ),
                ],
              ),
            ),
          ),
        ],
      ),
    );
  }

  Widget _buildPermissionChip(String label, bool enabled) {
    return Chip(
      avatar: Icon(
        enabled ? Icons.check_circle : Icons.cancel,
        size: 18,
        color: enabled ? Colors.green : Colors.red,
      ),
      label: Text(label),
      backgroundColor: enabled ? Colors.green.shade50 : Colors.red.shade50,
    );
  }

  Widget _buildResultCard(String packageName, AppTestResult result) {
    final appName = _appNames[packageName] ?? packageName;
    final duration = result.endTime != null
        ? result.endTime!.difference(result.startTime).inSeconds
        : null;

    return Container(
      width: 140,
      margin: const EdgeInsets.only(left: 8, top: 8, bottom: 8),
      padding: const EdgeInsets.all(12),
      decoration: BoxDecoration(
        color: result.status == TestStatus.success
            ? Colors.green.shade50
            : result.status == TestStatus.failed
                ? Colors.red.shade50
                : result.status == TestStatus.timeout
                    ? Colors.orange.shade50
                    : Colors.blue.shade50,
        borderRadius: BorderRadius.circular(8),
        border: Border.all(
          color: result.status == TestStatus.success
              ? Colors.green
              : result.status == TestStatus.failed
                  ? Colors.red
                  : result.status == TestStatus.timeout
                      ? Colors.orange
                      : Colors.blue,
        ),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text(
            appName,
            style: const TextStyle(fontWeight: FontWeight.bold),
          ),
          const SizedBox(height: 4),
          if (result.status == TestStatus.running)
            const SizedBox(
              width: 16,
              height: 16,
              child: CircularProgressIndicator(strokeWidth: 2),
            )
          else if (result.price != null)
            Text(
              '${result.price!.round()} ج.م',
              style: TextStyle(
                fontSize: 18,
                fontWeight: FontWeight.bold,
                color: Colors.green.shade700,
              ),
            )
          else
            Text(
              result.error ?? 'فشل',
              style: TextStyle(color: Colors.red.shade700, fontSize: 12),
              maxLines: 2,
              overflow: TextOverflow.ellipsis,
            ),
          if (duration != null)
            Text(
              '$duration ثانية',
              style: TextStyle(color: Colors.grey.shade600, fontSize: 11),
            ),
        ],
      ),
    );
  }
}

enum TestStatus { running, success, failed, timeout }

class AppTestResult {
  final TestStatus status;
  final DateTime startTime;
  final DateTime? endTime;
  final double? price;
  final List<double>? allPrices;
  final Map<String, double>? vehiclePrices;
  final String? error;

  AppTestResult({
    required this.status,
    required this.startTime,
    this.endTime,
    this.price,
    this.allPrices,
    this.vehiclePrices,
    this.error,
  });
}
