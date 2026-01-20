import 'dart:io';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import 'package:image_picker/image_picker.dart';

import '../../../core/constants/app_colors.dart';
import '../../../core/routes/app_router.dart';
import '../../../providers/auth_provider.dart';
import '../services/driver_service.dart';

class DriverRegistrationScreen extends ConsumerStatefulWidget {
  const DriverRegistrationScreen({super.key});

  @override
  ConsumerState<DriverRegistrationScreen> createState() =>
      _DriverRegistrationScreenState();
}

class _DriverRegistrationScreenState
    extends ConsumerState<DriverRegistrationScreen> {
  final _formKey = GlobalKey<FormState>();
  final PageController _pageController = PageController();

  int _currentStep = 0;
  bool _isLoading = false;

  // Personal info
  final _nameController = TextEditingController();
  final _phoneController = TextEditingController();
  final _whatsappController = TextEditingController();
  final _nationalIdController = TextEditingController();
  final _licenseNumberController = TextEditingController();
  DateTime? _licenseExpiry;

  // Vehicle info
  final _makeController = TextEditingController();
  final _modelController = TextEditingController();
  final _yearController = TextEditingController();
  final _colorController = TextEditingController();
  final _plateNumberController = TextEditingController();
  final _registrationNumberController = TextEditingController();
  DateTime? _registrationExpiry;
  String _vehicleType = 'car';
  String _vehicleCategory = 'economy';

  // Services
  bool _serviceRides = true;
  bool _serviceFreight = false;
  bool _serviceIntercity = false;

  // Documents
  File? _nationalIdImage;
  File? _licenseImage;
  File? _registrationImage;

  final _imagePicker = ImagePicker();

  @override
  void dispose() {
    _pageController.dispose();
    _nameController.dispose();
    _phoneController.dispose();
    _whatsappController.dispose();
    _nationalIdController.dispose();
    _licenseNumberController.dispose();
    _makeController.dispose();
    _modelController.dispose();
    _yearController.dispose();
    _colorController.dispose();
    _plateNumberController.dispose();
    _registrationNumberController.dispose();
    super.dispose();
  }

  Future<void> _pickImage(String type) async {
    final XFile? image = await _imagePicker.pickImage(
      source: ImageSource.camera,
      maxWidth: 1200,
      maxHeight: 1200,
      imageQuality: 85,
    );

    if (image != null) {
      setState(() {
        switch (type) {
          case 'national_id':
            _nationalIdImage = File(image.path);
            break;
          case 'license':
            _licenseImage = File(image.path);
            break;
          case 'registration':
            _registrationImage = File(image.path);
            break;
        }
      });
    }
  }

  Future<void> _pickDate(String type) async {
    final DateTime? picked = await showDatePicker(
      context: context,
      initialDate: DateTime.now().add(const Duration(days: 365)),
      firstDate: DateTime.now(),
      lastDate: DateTime.now().add(const Duration(days: 365 * 10)),
      locale: const Locale('ar'),
    );

    if (picked != null) {
      setState(() {
        switch (type) {
          case 'license':
            _licenseExpiry = picked;
            break;
          case 'registration':
            _registrationExpiry = picked;
            break;
        }
      });
    }
  }

  void _nextStep() {
    if (_currentStep < 2) {
      _pageController.nextPage(
        duration: const Duration(milliseconds: 300),
        curve: Curves.easeInOut,
      );
      setState(() => _currentStep++);
    }
  }

  void _previousStep() {
    if (_currentStep > 0) {
      _pageController.previousPage(
        duration: const Duration(milliseconds: 300),
        curve: Curves.easeInOut,
      );
      setState(() => _currentStep--);
    }
  }

  Future<void> _submit() async {
    if (!_formKey.currentState!.validate()) return;

    if (_nationalIdImage == null ||
        _licenseImage == null ||
        _registrationImage == null) {
      ScaffoldMessenger.of(context).showSnackBar(
        const SnackBar(
          content: Text('من فضلك ارفع جميع المستندات المطلوبة'),
          backgroundColor: AppColors.error,
        ),
      );
      return;
    }

    if (_licenseExpiry == null || _registrationExpiry == null) {
      ScaffoldMessenger.of(context).showSnackBar(
        const SnackBar(
          content: Text('من فضلك أدخل تواريخ انتهاء الصلاحية'),
          backgroundColor: AppColors.error,
        ),
      );
      return;
    }

    setState(() => _isLoading = true);

    try {
      final user = ref.read(currentUserProvider);
      if (user == null) throw Exception('User not logged in');

      final driverService = ref.read(driverServiceProvider);

      // Upload documents
      final nationalIdUrl = await driverService.uploadDocument(
        userId: user.id,
        documentType: 'national_id',
        file: _nationalIdImage!,
      );

      final licenseUrl = await driverService.uploadDocument(
        userId: user.id,
        documentType: 'license',
        file: _licenseImage!,
      );

      final registrationUrl = await driverService.uploadDocument(
        userId: user.id,
        documentType: 'registration',
        file: _registrationImage!,
      );

      // Register driver
      final driver = await driverService.registerDriver(
        userId: user.id,
        name: _nameController.text.trim(),
        phone: _phoneController.text.trim(),
        whatsappNumber: _whatsappController.text.trim().isNotEmpty
            ? _whatsappController.text.trim()
            : null,
        nationalId: _nationalIdController.text.trim(),
        nationalIdImageUrl: nationalIdUrl,
        licenseNumber: _licenseNumberController.text.trim(),
        licenseImageUrl: licenseUrl,
        licenseExpiry: _licenseExpiry!,
        serviceRides: _serviceRides,
        serviceFreight: _serviceFreight,
        serviceIntercity: _serviceIntercity,
      );

      // Register vehicle
      await driverService.registerVehicle(
        driverId: driver['id'],
        type: _vehicleType,
        category: _vehicleCategory,
        make: _makeController.text.trim(),
        model: _modelController.text.trim(),
        year: int.parse(_yearController.text.trim()),
        color: _colorController.text.trim(),
        plateNumber: _plateNumberController.text.trim(),
        registrationNumber: _registrationNumberController.text.trim(),
        registrationImageUrl: registrationUrl,
        registrationExpiry: _registrationExpiry!,
      );

      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          const SnackBar(
            content: Text('تم تقديم طلبك بنجاح! سيتم مراجعته قريباً'),
            backgroundColor: AppColors.success,
          ),
        );

        context.go(AppRoutes.home);
      }
    } catch (e) {
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(
            content: Text('حدث خطأ: ${e.toString()}'),
            backgroundColor: AppColors.error,
          ),
        );
      }
    } finally {
      if (mounted) {
        setState(() => _isLoading = false);
      }
    }
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        title: const Text('التسجيل كسائق'),
        backgroundColor: Colors.transparent,
        elevation: 0,
        foregroundColor: AppColors.textPrimary,
      ),
      body: SafeArea(
        child: Column(
          children: [
            // Progress indicator
            Padding(
              padding: const EdgeInsets.all(16.0),
              child: Row(
                children: List.generate(3, (index) {
                  return Expanded(
                    child: Container(
                      height: 4,
                      margin: const EdgeInsets.symmetric(horizontal: 4),
                      decoration: BoxDecoration(
                        color: index <= _currentStep
                            ? AppColors.primary
                            : AppColors.border,
                        borderRadius: BorderRadius.circular(2),
                      ),
                    ),
                  );
                }),
              ),
            ),

            // Step titles
            Padding(
              padding: const EdgeInsets.symmetric(horizontal: 16),
              child: Row(
                mainAxisAlignment: MainAxisAlignment.spaceAround,
                children: [
                  _buildStepTitle(0, 'البيانات الشخصية'),
                  _buildStepTitle(1, 'بيانات السيارة'),
                  _buildStepTitle(2, 'المستندات'),
                ],
              ),
            ),

            const SizedBox(height: 16),

            // Form pages
            Expanded(
              child: Form(
                key: _formKey,
                child: PageView(
                  controller: _pageController,
                  physics: const NeverScrollableScrollPhysics(),
                  children: [
                    _buildPersonalInfoStep(),
                    _buildVehicleInfoStep(),
                    _buildDocumentsStep(),
                  ],
                ),
              ),
            ),

            // Navigation buttons
            Padding(
              padding: const EdgeInsets.all(16.0),
              child: Row(
                children: [
                  if (_currentStep > 0)
                    Expanded(
                      child: OutlinedButton(
                        onPressed: _previousStep,
                        child: const Text('السابق'),
                      ),
                    ),
                  if (_currentStep > 0) const SizedBox(width: 16),
                  Expanded(
                    flex: 2,
                    child: ElevatedButton(
                      onPressed: _isLoading
                          ? null
                          : (_currentStep < 2 ? _nextStep : _submit),
                      child: _isLoading
                          ? const SizedBox(
                              width: 24,
                              height: 24,
                              child: CircularProgressIndicator(
                                strokeWidth: 2,
                                color: Colors.white,
                              ),
                            )
                          : Text(_currentStep < 2 ? 'التالي' : 'إرسال الطلب'),
                    ),
                  ),
                ],
              ),
            ),
          ],
        ),
      ),
    );
  }

  Widget _buildStepTitle(int step, String title) {
    final isActive = step == _currentStep;
    final isCompleted = step < _currentStep;

    return Column(
      children: [
        Container(
          width: 28,
          height: 28,
          decoration: BoxDecoration(
            color: isActive || isCompleted
                ? AppColors.primary
                : AppColors.surfaceVariant,
            shape: BoxShape.circle,
          ),
          child: Center(
            child: isCompleted
                ? const Icon(Icons.check, color: Colors.white, size: 16)
                : Text(
                    '${step + 1}',
                    style: TextStyle(
                      color: isActive ? Colors.white : AppColors.textSecondary,
                      fontWeight: FontWeight.bold,
                    ),
                  ),
          ),
        ),
        const SizedBox(height: 4),
        Text(
          title,
          style: TextStyle(
            fontSize: 10,
            color: isActive ? AppColors.primary : AppColors.textSecondary,
            fontWeight: isActive ? FontWeight.w600 : FontWeight.normal,
          ),
        ),
      ],
    );
  }

  Widget _buildPersonalInfoStep() {
    return SingleChildScrollView(
      padding: const EdgeInsets.all(16),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          const Text(
            'البيانات الشخصية',
            style: TextStyle(
              fontSize: 20,
              fontWeight: FontWeight.bold,
            ),
          ),
          const SizedBox(height: 24),

          // Name
          TextFormField(
            controller: _nameController,
            decoration: InputDecoration(
              labelText: 'الاسم بالكامل *',
              prefixIcon: const Icon(Icons.person),
              border: OutlineInputBorder(
                borderRadius: BorderRadius.circular(12),
              ),
            ),
            validator: (value) {
              if (value == null || value.isEmpty) {
                return 'من فضلك أدخل اسمك';
              }
              return null;
            },
          ),
          const SizedBox(height: 16),

          // Phone
          TextFormField(
            controller: _phoneController,
            keyboardType: TextInputType.phone,
            textDirection: TextDirection.ltr,
            decoration: InputDecoration(
              labelText: 'رقم الهاتف *',
              prefixIcon: const Icon(Icons.phone),
              prefixText: '+20 ',
              border: OutlineInputBorder(
                borderRadius: BorderRadius.circular(12),
              ),
            ),
            inputFormatters: [
              FilteringTextInputFormatter.digitsOnly,
              LengthLimitingTextInputFormatter(11),
            ],
            validator: (value) {
              if (value == null || value.length < 10) {
                return 'رقم الهاتف غير صحيح';
              }
              return null;
            },
          ),
          const SizedBox(height: 16),

          // WhatsApp
          TextFormField(
            controller: _whatsappController,
            keyboardType: TextInputType.phone,
            textDirection: TextDirection.ltr,
            decoration: InputDecoration(
              labelText: 'رقم واتساب (اختياري)',
              prefixIcon: const Icon(Icons.message),
              prefixText: '+20 ',
              border: OutlineInputBorder(
                borderRadius: BorderRadius.circular(12),
              ),
              helperText: 'اتركه فارغاً لاستخدام رقم الهاتف',
            ),
            inputFormatters: [
              FilteringTextInputFormatter.digitsOnly,
              LengthLimitingTextInputFormatter(11),
            ],
          ),
          const SizedBox(height: 16),

          // National ID
          TextFormField(
            controller: _nationalIdController,
            keyboardType: TextInputType.number,
            textDirection: TextDirection.ltr,
            decoration: InputDecoration(
              labelText: 'الرقم القومي *',
              prefixIcon: const Icon(Icons.badge),
              border: OutlineInputBorder(
                borderRadius: BorderRadius.circular(12),
              ),
            ),
            inputFormatters: [
              FilteringTextInputFormatter.digitsOnly,
              LengthLimitingTextInputFormatter(14),
            ],
            validator: (value) {
              if (value == null || value.length != 14) {
                return 'الرقم القومي يجب أن يكون 14 رقم';
              }
              return null;
            },
          ),
          const SizedBox(height: 16),

          // License number
          TextFormField(
            controller: _licenseNumberController,
            textDirection: TextDirection.ltr,
            decoration: InputDecoration(
              labelText: 'رقم رخصة القيادة *',
              prefixIcon: const Icon(Icons.drive_eta),
              border: OutlineInputBorder(
                borderRadius: BorderRadius.circular(12),
              ),
            ),
            validator: (value) {
              if (value == null || value.isEmpty) {
                return 'من فضلك أدخل رقم الرخصة';
              }
              return null;
            },
          ),
          const SizedBox(height: 16),

          // License expiry
          ListTile(
            contentPadding: EdgeInsets.zero,
            leading: const Icon(Icons.calendar_today),
            title: const Text('تاريخ انتهاء الرخصة *'),
            subtitle: Text(
              _licenseExpiry != null
                  ? '${_licenseExpiry!.day}/${_licenseExpiry!.month}/${_licenseExpiry!.year}'
                  : 'اختر التاريخ',
              style: TextStyle(
                color: _licenseExpiry != null
                    ? AppColors.textPrimary
                    : AppColors.textSecondary,
              ),
            ),
            trailing: const Icon(Icons.arrow_drop_down),
            onTap: () => _pickDate('license'),
          ),

          const SizedBox(height: 24),

          // Services
          const Text(
            'الخدمات التي ترغب في تقديمها',
            style: TextStyle(
              fontSize: 16,
              fontWeight: FontWeight.w600,
            ),
          ),
          const SizedBox(height: 8),
          CheckboxListTile(
            value: _serviceRides,
            onChanged: (v) => setState(() => _serviceRides = v ?? true),
            title: const Text('توصيل ركاب'),
            subtitle: const Text('Uber, Careem style'),
            controlAffinity: ListTileControlAffinity.leading,
          ),
          CheckboxListTile(
            value: _serviceFreight,
            onChanged: (v) => setState(() => _serviceFreight = v ?? false),
            title: const Text('شحن بضائع'),
            subtitle: const Text('توصيل طرود وبضائع'),
            controlAffinity: ListTileControlAffinity.leading,
          ),
          CheckboxListTile(
            value: _serviceIntercity,
            onChanged: (v) => setState(() => _serviceIntercity = v ?? false),
            title: const Text('سفر بين المدن'),
            subtitle: const Text('رحلات طويلة'),
            controlAffinity: ListTileControlAffinity.leading,
          ),
        ],
      ),
    );
  }

  Widget _buildVehicleInfoStep() {
    return SingleChildScrollView(
      padding: const EdgeInsets.all(16),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          const Text(
            'بيانات السيارة',
            style: TextStyle(
              fontSize: 20,
              fontWeight: FontWeight.bold,
            ),
          ),
          const SizedBox(height: 24),

          // Vehicle type
          DropdownButtonFormField<String>(
            value: _vehicleType,
            decoration: InputDecoration(
              labelText: 'نوع المركبة *',
              prefixIcon: const Icon(Icons.directions_car),
              border: OutlineInputBorder(
                borderRadius: BorderRadius.circular(12),
              ),
            ),
            items: const [
              DropdownMenuItem(value: 'car', child: Text('سيارة')),
              DropdownMenuItem(value: 'motorcycle', child: Text('موتوسيكل')),
              DropdownMenuItem(value: 'van', child: Text('فان')),
              DropdownMenuItem(value: 'truck', child: Text('نقل')),
            ],
            onChanged: (v) => setState(() => _vehicleType = v ?? 'car'),
          ),
          const SizedBox(height: 16),

          // Vehicle category
          DropdownButtonFormField<String>(
            value: _vehicleCategory,
            decoration: InputDecoration(
              labelText: 'فئة المركبة *',
              prefixIcon: const Icon(Icons.category),
              border: OutlineInputBorder(
                borderRadius: BorderRadius.circular(12),
              ),
            ),
            items: const [
              DropdownMenuItem(value: 'economy', child: Text('اقتصادي')),
              DropdownMenuItem(value: 'comfort', child: Text('مريح')),
              DropdownMenuItem(value: 'premium', child: Text('فاخر')),
              DropdownMenuItem(value: 'cargo', child: Text('شحن')),
            ],
            onChanged: (v) => setState(() => _vehicleCategory = v ?? 'economy'),
          ),
          const SizedBox(height: 16),

          // Make
          TextFormField(
            controller: _makeController,
            decoration: InputDecoration(
              labelText: 'الماركة *',
              hintText: 'مثال: تويوتا',
              prefixIcon: const Icon(Icons.factory),
              border: OutlineInputBorder(
                borderRadius: BorderRadius.circular(12),
              ),
            ),
            validator: (value) {
              if (value == null || value.isEmpty) {
                return 'من فضلك أدخل ماركة السيارة';
              }
              return null;
            },
          ),
          const SizedBox(height: 16),

          // Model
          TextFormField(
            controller: _modelController,
            decoration: InputDecoration(
              labelText: 'الموديل *',
              hintText: 'مثال: كورولا',
              prefixIcon: const Icon(Icons.model_training),
              border: OutlineInputBorder(
                borderRadius: BorderRadius.circular(12),
              ),
            ),
            validator: (value) {
              if (value == null || value.isEmpty) {
                return 'من فضلك أدخل موديل السيارة';
              }
              return null;
            },
          ),
          const SizedBox(height: 16),

          // Year
          TextFormField(
            controller: _yearController,
            keyboardType: TextInputType.number,
            textDirection: TextDirection.ltr,
            decoration: InputDecoration(
              labelText: 'سنة الصنع *',
              hintText: 'مثال: 2020',
              prefixIcon: const Icon(Icons.calendar_month),
              border: OutlineInputBorder(
                borderRadius: BorderRadius.circular(12),
              ),
            ),
            inputFormatters: [
              FilteringTextInputFormatter.digitsOnly,
              LengthLimitingTextInputFormatter(4),
            ],
            validator: (value) {
              if (value == null || value.length != 4) {
                return 'أدخل سنة صحيحة';
              }
              final year = int.tryParse(value);
              if (year == null || year < 2010 || year > DateTime.now().year + 1) {
                return 'سنة الصنع غير صحيحة';
              }
              return null;
            },
          ),
          const SizedBox(height: 16),

          // Color
          TextFormField(
            controller: _colorController,
            decoration: InputDecoration(
              labelText: 'اللون *',
              hintText: 'مثال: أبيض',
              prefixIcon: const Icon(Icons.palette),
              border: OutlineInputBorder(
                borderRadius: BorderRadius.circular(12),
              ),
            ),
            validator: (value) {
              if (value == null || value.isEmpty) {
                return 'من فضلك أدخل لون السيارة';
              }
              return null;
            },
          ),
          const SizedBox(height: 16),

          // Plate number
          TextFormField(
            controller: _plateNumberController,
            textDirection: TextDirection.ltr,
            decoration: InputDecoration(
              labelText: 'رقم اللوحة *',
              hintText: 'مثال: أ ب ج 123',
              prefixIcon: const Icon(Icons.numbers),
              border: OutlineInputBorder(
                borderRadius: BorderRadius.circular(12),
              ),
            ),
            validator: (value) {
              if (value == null || value.isEmpty) {
                return 'من فضلك أدخل رقم اللوحة';
              }
              return null;
            },
          ),
          const SizedBox(height: 16),

          // Registration number
          TextFormField(
            controller: _registrationNumberController,
            textDirection: TextDirection.ltr,
            decoration: InputDecoration(
              labelText: 'رقم رخصة السيارة *',
              prefixIcon: const Icon(Icons.document_scanner),
              border: OutlineInputBorder(
                borderRadius: BorderRadius.circular(12),
              ),
            ),
            validator: (value) {
              if (value == null || value.isEmpty) {
                return 'من فضلك أدخل رقم الرخصة';
              }
              return null;
            },
          ),
          const SizedBox(height: 16),

          // Registration expiry
          ListTile(
            contentPadding: EdgeInsets.zero,
            leading: const Icon(Icons.calendar_today),
            title: const Text('تاريخ انتهاء رخصة السيارة *'),
            subtitle: Text(
              _registrationExpiry != null
                  ? '${_registrationExpiry!.day}/${_registrationExpiry!.month}/${_registrationExpiry!.year}'
                  : 'اختر التاريخ',
              style: TextStyle(
                color: _registrationExpiry != null
                    ? AppColors.textPrimary
                    : AppColors.textSecondary,
              ),
            ),
            trailing: const Icon(Icons.arrow_drop_down),
            onTap: () => _pickDate('registration'),
          ),
        ],
      ),
    );
  }

  Widget _buildDocumentsStep() {
    return SingleChildScrollView(
      padding: const EdgeInsets.all(16),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          const Text(
            'المستندات المطلوبة',
            style: TextStyle(
              fontSize: 20,
              fontWeight: FontWeight.bold,
            ),
          ),
          const SizedBox(height: 8),
          const Text(
            'ارفع صور واضحة للمستندات التالية',
            style: TextStyle(
              color: AppColors.textSecondary,
            ),
          ),
          const SizedBox(height: 24),

          // National ID
          _buildDocumentUploader(
            title: 'البطاقة الشخصية (الوجه الأمامي)',
            file: _nationalIdImage,
            onTap: () => _pickImage('national_id'),
          ),
          const SizedBox(height: 16),

          // License
          _buildDocumentUploader(
            title: 'رخصة القيادة',
            file: _licenseImage,
            onTap: () => _pickImage('license'),
          ),
          const SizedBox(height: 16),

          // Vehicle registration
          _buildDocumentUploader(
            title: 'رخصة السيارة',
            file: _registrationImage,
            onTap: () => _pickImage('registration'),
          ),

          const SizedBox(height: 32),

          // Terms
          Container(
            padding: const EdgeInsets.all(16),
            decoration: BoxDecoration(
              color: AppColors.info.withOpacity(0.1),
              borderRadius: BorderRadius.circular(12),
              border: Border.all(color: AppColors.info.withOpacity(0.3)),
            ),
            child: const Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Row(
                  children: [
                    Icon(Icons.info_outline, color: AppColors.info),
                    SizedBox(width: 8),
                    Text(
                      'ملاحظات هامة',
                      style: TextStyle(
                        fontWeight: FontWeight.bold,
                        color: AppColors.info,
                      ),
                    ),
                  ],
                ),
                SizedBox(height: 8),
                Text(
                  '• سيتم مراجعة طلبك خلال 24-48 ساعة\n'
                  '• تأكد من وضوح جميع المستندات\n'
                  '• سيتم التواصل معك عبر الواتساب\n'
                  '• يمكنك البدء في العمل بعد الموافقة على طلبك',
                  style: TextStyle(
                    fontSize: 13,
                    color: AppColors.textSecondary,
                    height: 1.5,
                  ),
                ),
              ],
            ),
          ),
        ],
      ),
    );
  }

  Widget _buildDocumentUploader({
    required String title,
    required File? file,
    required VoidCallback onTap,
  }) {
    return InkWell(
      onTap: onTap,
      borderRadius: BorderRadius.circular(12),
      child: Container(
        height: 120,
        decoration: BoxDecoration(
          color: file != null ? AppColors.success.withOpacity(0.1) : AppColors.surfaceVariant,
          borderRadius: BorderRadius.circular(12),
          border: Border.all(
            color: file != null ? AppColors.success : AppColors.border,
            width: 2,
            style: file != null ? BorderStyle.solid : BorderStyle.none,
          ),
        ),
        child: file != null
            ? Stack(
                children: [
                  ClipRRect(
                    borderRadius: BorderRadius.circular(10),
                    child: Image.file(
                      file,
                      width: double.infinity,
                      height: double.infinity,
                      fit: BoxFit.cover,
                    ),
                  ),
                  Positioned(
                    top: 8,
                    right: 8,
                    child: Container(
                      padding: const EdgeInsets.symmetric(
                        horizontal: 8,
                        vertical: 4,
                      ),
                      decoration: BoxDecoration(
                        color: AppColors.success,
                        borderRadius: BorderRadius.circular(8),
                      ),
                      child: const Row(
                        mainAxisSize: MainAxisSize.min,
                        children: [
                          Icon(Icons.check, color: Colors.white, size: 16),
                          SizedBox(width: 4),
                          Text(
                            'تم الرفع',
                            style: TextStyle(
                              color: Colors.white,
                              fontSize: 12,
                            ),
                          ),
                        ],
                      ),
                    ),
                  ),
                  Positioned(
                    bottom: 0,
                    left: 0,
                    right: 0,
                    child: Container(
                      padding: const EdgeInsets.all(8),
                      decoration: BoxDecoration(
                        color: Colors.black54,
                        borderRadius: const BorderRadius.vertical(
                          bottom: Radius.circular(10),
                        ),
                      ),
                      child: Text(
                        title,
                        style: const TextStyle(
                          color: Colors.white,
                          fontSize: 12,
                        ),
                      ),
                    ),
                  ),
                ],
              )
            : Column(
                mainAxisAlignment: MainAxisAlignment.center,
                children: [
                  const Icon(
                    Icons.camera_alt,
                    size: 40,
                    color: AppColors.textSecondary,
                  ),
                  const SizedBox(height: 8),
                  Text(
                    title,
                    style: const TextStyle(
                      color: AppColors.textSecondary,
                      fontWeight: FontWeight.w500,
                    ),
                  ),
                  const SizedBox(height: 4),
                  const Text(
                    'اضغط لالتقاط صورة',
                    style: TextStyle(
                      color: AppColors.textLight,
                      fontSize: 12,
                    ),
                  ),
                ],
              ),
      ),
    );
  }
}
