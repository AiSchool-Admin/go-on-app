import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import 'package:supabase_flutter/supabase_flutter.dart';

import '../../../core/constants/app_colors.dart';
import '../../../core/routes/app_router.dart';
import '../../../providers/auth_provider.dart';

enum LoginMethod { phone, email }

class LoginScreen extends ConsumerStatefulWidget {
  const LoginScreen({super.key});

  @override
  ConsumerState<LoginScreen> createState() => _LoginScreenState();
}

class _LoginScreenState extends ConsumerState<LoginScreen> {
  final _formKey = GlobalKey<FormState>();
  final _phoneController = TextEditingController();
  final _emailController = TextEditingController();
  final _passwordController = TextEditingController();
  final _nameController = TextEditingController();

  bool _isLoading = false;
  bool _isSignUp = false;
  bool _obscurePassword = true;
  LoginMethod _loginMethod = LoginMethod.phone;

  @override
  void dispose() {
    _phoneController.dispose();
    _emailController.dispose();
    _passwordController.dispose();
    _nameController.dispose();
    super.dispose();
  }

  String _formatPhoneNumber(String phone) {
    // Remove any spaces or dashes
    String cleaned = phone.replaceAll(RegExp(r'[\s\-]'), '');

    // Add Egypt country code if not present
    if (cleaned.startsWith('0')) {
      cleaned = '+2$cleaned';
    } else if (!cleaned.startsWith('+')) {
      cleaned = '+20$cleaned';
    }

    return cleaned;
  }

  Future<void> _submitPhone() async {
    if (!_formKey.currentState!.validate()) return;

    setState(() => _isLoading = true);

    final phone = _formatPhoneNumber(_phoneController.text.trim());

    try {
      await ref.read(authNotifierProvider.notifier).sendOtp(phone);

      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          const SnackBar(
            content: Text('تم إرسال رمز التحقق إلى هاتفك'),
            backgroundColor: AppColors.success,
          ),
        );

        // Navigate to OTP screen
        context.push(AppRoutes.otp, extra: phone);
      }
    } catch (e) {
      if (mounted) {
        String errorMessage = 'حدث خطأ';
        if (e.toString().contains('rate_limit')) {
          errorMessage = 'تم تجاوز الحد المسموح. حاول بعد دقيقة';
        } else if (e.toString().contains('invalid_phone')) {
          errorMessage = 'رقم الهاتف غير صحيح';
        }

        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(
            content: Text(errorMessage),
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

  Future<void> _submitEmail() async {
    if (!_formKey.currentState!.validate()) return;

    setState(() => _isLoading = true);

    final email = _emailController.text.trim();
    final password = _passwordController.text;
    final name = _nameController.text.trim();

    try {
      if (_isSignUp) {
        // Sign Up with email
        final response = await Supabase.instance.client.auth.signUp(
          email: email,
          password: password,
          data: {'name': name},
        );

        if (response.user != null && mounted) {
          // Create profile
          await _createProfile(response.user!.id, name, email);

          ScaffoldMessenger.of(context).showSnackBar(
            const SnackBar(
              content: Text('تم إنشاء الحساب بنجاح!'),
              backgroundColor: AppColors.success,
            ),
          );
        }
      }

      // Sign In
      final response = await Supabase.instance.client.auth.signInWithPassword(
        email: email,
        password: password,
      );

      if (response.user != null && mounted) {
        context.go(AppRoutes.home);
      }
    } on AuthException catch (e) {
      if (mounted) {
        String errorMessage = 'خطأ في تسجيل الدخول';
        if (e.message.contains('Invalid login credentials')) {
          errorMessage = 'البريد الإلكتروني أو كلمة المرور غير صحيحة';
        } else if (e.message.contains('User already registered')) {
          errorMessage = 'هذا البريد مسجل بالفعل';
        }

        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(
            content: Text(errorMessage),
            backgroundColor: AppColors.error,
          ),
        );
      }
    } catch (e) {
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(
            content: Text('خطأ: ${e.toString()}'),
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

  Future<void> _createProfile(String userId, String name, String email) async {
    try {
      await Supabase.instance.client.from('profiles').upsert({
        'id': userId,
        'name': name.isNotEmpty ? name : 'مستخدم',
        'email': email,
        'phone': '',
        'user_type': 'passenger',
      });
    } catch (e) {
      debugPrint('Error creating profile: $e');
    }
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      body: SafeArea(
        child: SingleChildScrollView(
          padding: const EdgeInsets.all(24.0),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              const SizedBox(height: 32),

              // Logo & Title
              const Column(
                children: [
                  Text(
                    'GO-ON',
                    style: TextStyle(
                      fontSize: 48,
                      fontWeight: FontWeight.bold,
                      color: AppColors.primary,
                    ),
                  ),
                  SizedBox(height: 8),
                  Text(
                    'مصر تتحرك',
                    style: TextStyle(
                      fontSize: 18,
                      color: AppColors.secondary,
                      fontWeight: FontWeight.w600,
                    ),
                  ),
                ],
              ),

              const SizedBox(height: 40),

              // Login Method Tabs
              Container(
                decoration: BoxDecoration(
                  color: AppColors.surfaceVariant,
                  borderRadius: BorderRadius.circular(12),
                ),
                child: Row(
                  children: [
                    Expanded(
                      child: GestureDetector(
                        onTap: () => setState(() => _loginMethod = LoginMethod.phone),
                        child: Container(
                          padding: const EdgeInsets.symmetric(vertical: 14),
                          decoration: BoxDecoration(
                            color: _loginMethod == LoginMethod.phone
                                ? AppColors.primary
                                : Colors.transparent,
                            borderRadius: BorderRadius.circular(12),
                          ),
                          child: Text(
                            'رقم الهاتف',
                            textAlign: TextAlign.center,
                            style: TextStyle(
                              color: _loginMethod == LoginMethod.phone
                                  ? Colors.white
                                  : AppColors.textSecondary,
                              fontWeight: FontWeight.w600,
                            ),
                          ),
                        ),
                      ),
                    ),
                    Expanded(
                      child: GestureDetector(
                        onTap: () => setState(() => _loginMethod = LoginMethod.email),
                        child: Container(
                          padding: const EdgeInsets.symmetric(vertical: 14),
                          decoration: BoxDecoration(
                            color: _loginMethod == LoginMethod.email
                                ? AppColors.primary
                                : Colors.transparent,
                            borderRadius: BorderRadius.circular(12),
                          ),
                          child: Text(
                            'البريد الإلكتروني',
                            textAlign: TextAlign.center,
                            style: TextStyle(
                              color: _loginMethod == LoginMethod.email
                                  ? Colors.white
                                  : AppColors.textSecondary,
                              fontWeight: FontWeight.w600,
                            ),
                          ),
                        ),
                      ),
                    ),
                  ],
                ),
              ),

              const SizedBox(height: 32),

              // Title
              Text(
                _loginMethod == LoginMethod.phone
                    ? 'سجّل برقم هاتفك'
                    : (_isSignUp ? 'إنشاء حساب جديد' : 'تسجيل الدخول'),
                textAlign: TextAlign.center,
                style: const TextStyle(
                  fontSize: 24,
                  fontWeight: FontWeight.bold,
                  color: AppColors.textPrimary,
                ),
              ),
              const SizedBox(height: 8),
              Text(
                _loginMethod == LoginMethod.phone
                    ? 'سنرسل لك رمز تحقق'
                    : (_isSignUp
                        ? 'أدخل بريدك الإلكتروني وكلمة المرور'
                        : 'أدخل بيانات حسابك'),
                textAlign: TextAlign.center,
                style: const TextStyle(
                  fontSize: 14,
                  color: AppColors.textSecondary,
                ),
              ),

              const SizedBox(height: 32),

              // Form
              Form(
                key: _formKey,
                child: _loginMethod == LoginMethod.phone
                    ? _buildPhoneForm()
                    : _buildEmailForm(),
              ),

              const SizedBox(height: 48),

              // Terms
              const Text(
                'بالمتابعة، أنت توافق على شروط الاستخدام وسياسة الخصوصية',
                textAlign: TextAlign.center,
                style: TextStyle(
                  fontSize: 12,
                  color: AppColors.textSecondary,
                ),
              ),
            ],
          ),
        ),
      ),
    );
  }

  Widget _buildPhoneForm() {
    return Column(
      children: [
        // Phone Number Field
        TextFormField(
          controller: _phoneController,
          keyboardType: TextInputType.phone,
          textDirection: TextDirection.ltr,
          textAlign: TextAlign.left,
          decoration: InputDecoration(
            labelText: 'رقم الهاتف',
            hintText: '01012345678',
            prefixIcon: const Icon(Icons.phone),
            prefixText: '+20 ',
            prefixStyle: const TextStyle(
              color: AppColors.textPrimary,
              fontWeight: FontWeight.w500,
            ),
            border: OutlineInputBorder(
              borderRadius: BorderRadius.circular(12),
            ),
          ),
          inputFormatters: [
            FilteringTextInputFormatter.digitsOnly,
            LengthLimitingTextInputFormatter(11),
          ],
          validator: (value) {
            if (value == null || value.isEmpty) {
              return 'من فضلك أدخل رقم الهاتف';
            }
            if (value.length < 10) {
              return 'رقم الهاتف غير صحيح';
            }
            return null;
          },
        ),

        const SizedBox(height: 24),

        // Submit Button
        SizedBox(
          width: double.infinity,
          height: 52,
          child: ElevatedButton(
            onPressed: _isLoading ? null : _submitPhone,
            child: _isLoading
                ? const SizedBox(
                    width: 24,
                    height: 24,
                    child: CircularProgressIndicator(
                      strokeWidth: 2,
                      color: Colors.white,
                    ),
                  )
                : const Text(
                    'إرسال رمز التحقق',
                    style: TextStyle(
                      fontSize: 16,
                      fontWeight: FontWeight.w600,
                    ),
                  ),
          ),
        ),
      ],
    );
  }

  Widget _buildEmailForm() {
    return Column(
      children: [
        // Name Field (only for signup)
        if (_isSignUp) ...[
          TextFormField(
            controller: _nameController,
            textDirection: TextDirection.rtl,
            decoration: InputDecoration(
              labelText: 'الاسم',
              hintText: 'أدخل اسمك',
              prefixIcon: const Icon(Icons.person),
              border: OutlineInputBorder(
                borderRadius: BorderRadius.circular(12),
              ),
            ),
            validator: (value) {
              if (_isSignUp && (value == null || value.isEmpty)) {
                return 'من فضلك أدخل اسمك';
              }
              return null;
            },
          ),
          const SizedBox(height: 16),
        ],

        // Email Field
        TextFormField(
          controller: _emailController,
          keyboardType: TextInputType.emailAddress,
          textDirection: TextDirection.ltr,
          decoration: InputDecoration(
            labelText: 'البريد الإلكتروني',
            hintText: 'example@email.com',
            prefixIcon: const Icon(Icons.email),
            border: OutlineInputBorder(
              borderRadius: BorderRadius.circular(12),
            ),
          ),
          validator: (value) {
            if (value == null || value.isEmpty) {
              return 'من فضلك أدخل البريد الإلكتروني';
            }
            if (!value.contains('@') || !value.contains('.')) {
              return 'بريد إلكتروني غير صحيح';
            }
            return null;
          },
        ),

        const SizedBox(height: 16),

        // Password Field
        TextFormField(
          controller: _passwordController,
          obscureText: _obscurePassword,
          textDirection: TextDirection.ltr,
          decoration: InputDecoration(
            labelText: 'كلمة المرور',
            hintText: '********',
            prefixIcon: const Icon(Icons.lock),
            suffixIcon: IconButton(
              icon: Icon(
                _obscurePassword ? Icons.visibility : Icons.visibility_off,
              ),
              onPressed: () {
                setState(() => _obscurePassword = !_obscurePassword);
              },
            ),
            border: OutlineInputBorder(
              borderRadius: BorderRadius.circular(12),
            ),
          ),
          validator: (value) {
            if (value == null || value.isEmpty) {
              return 'من فضلك أدخل كلمة المرور';
            }
            if (value.length < 6) {
              return 'كلمة المرور يجب أن تكون 6 أحرف على الأقل';
            }
            return null;
          },
        ),

        const SizedBox(height: 24),

        // Submit Button
        SizedBox(
          width: double.infinity,
          height: 52,
          child: ElevatedButton(
            onPressed: _isLoading ? null : _submitEmail,
            child: _isLoading
                ? const SizedBox(
                    width: 24,
                    height: 24,
                    child: CircularProgressIndicator(
                      strokeWidth: 2,
                      color: Colors.white,
                    ),
                  )
                : Text(
                    _isSignUp ? 'إنشاء حساب' : 'تسجيل الدخول',
                    style: const TextStyle(
                      fontSize: 16,
                      fontWeight: FontWeight.w600,
                    ),
                  ),
          ),
        ),

        const SizedBox(height: 16),

        // Toggle Login/Signup
        TextButton(
          onPressed: () {
            setState(() => _isSignUp = !_isSignUp);
          },
          child: Text(
            _isSignUp
                ? 'لديك حساب؟ تسجيل الدخول'
                : 'ليس لديك حساب؟ إنشاء حساب جديد',
            style: const TextStyle(
              color: AppColors.primary,
              fontWeight: FontWeight.w500,
            ),
          ),
        ),
      ],
    );
  }
}
