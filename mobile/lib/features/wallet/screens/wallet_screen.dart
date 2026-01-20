import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../core/constants/app_colors.dart';
import '../../../providers/auth_provider.dart';
import '../services/payment_service.dart';

class WalletScreen extends ConsumerStatefulWidget {
  const WalletScreen({super.key});

  @override
  ConsumerState<WalletScreen> createState() => _WalletScreenState();
}

class _WalletScreenState extends ConsumerState<WalletScreen> {
  double _walletBalance = 0.0;
  List<Map<String, dynamic>> _transactions = [];
  bool _isLoading = true;

  @override
  void initState() {
    super.initState();
    _loadWalletData();
  }

  Future<void> _loadWalletData() async {
    final user = ref.read(currentUserProvider);
    if (user == null) return;

    final service = ref.read(paymentServiceProvider);

    try {
      final balance = await service.getWalletBalance(user.id);
      final transactions = await service.getTransactionHistory(user.id);

      if (mounted) {
        setState(() {
          _walletBalance = balance;
          _transactions = transactions;
          _isLoading = false;
        });
      }
    } catch (e) {
      if (mounted) {
        setState(() => _isLoading = false);
      }
    }
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        title: const Text('المحفظة'),
        backgroundColor: Colors.transparent,
        elevation: 0,
        foregroundColor: AppColors.textPrimary,
        actions: [
          IconButton(
            icon: const Icon(Icons.refresh),
            onPressed: () {
              setState(() => _isLoading = true);
              _loadWalletData();
            },
          ),
        ],
      ),
      body: _isLoading
          ? const Center(child: CircularProgressIndicator())
          : RefreshIndicator(
              onRefresh: _loadWalletData,
              child: SingleChildScrollView(
                physics: const AlwaysScrollableScrollPhysics(),
                padding: const EdgeInsets.all(16),
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.stretch,
                  children: [
                    // Balance Card
                    Container(
                      padding: const EdgeInsets.all(24),
                      decoration: BoxDecoration(
                        gradient: AppColors.primaryGradient,
                        borderRadius: BorderRadius.circular(20),
                        boxShadow: [
                          BoxShadow(
                            color: AppColors.primary.withOpacity(0.3),
                            blurRadius: 12,
                            offset: const Offset(0, 6),
                          ),
                        ],
                      ),
                      child: Column(
                        children: [
                          const Text(
                            'رصيدك الحالي',
                            style: TextStyle(
                              color: Colors.white70,
                              fontSize: 14,
                            ),
                          ),
                          const SizedBox(height: 8),
                          Text(
                            '${_walletBalance.toStringAsFixed(2)} ج.م',
                            style: const TextStyle(
                              color: Colors.white,
                              fontSize: 36,
                              fontWeight: FontWeight.bold,
                            ),
                          ),
                          const SizedBox(height: 24),
                          SizedBox(
                            width: double.infinity,
                            child: ElevatedButton.icon(
                              onPressed: () => _showTopUpSheet(context),
                              style: ElevatedButton.styleFrom(
                                backgroundColor: Colors.white,
                                foregroundColor: AppColors.primary,
                                padding: const EdgeInsets.symmetric(vertical: 14),
                              ),
                              icon: const Icon(Icons.add),
                              label: const Text('شحن الرصيد'),
                            ),
                          ),
                        ],
                      ),
                    ),

                    const SizedBox(height: 24),

                    // Quick Actions
                    Row(
                      children: [
                        Expanded(
                          child: _buildQuickAction(
                            icon: Icons.send,
                            label: 'تحويل',
                            onTap: () => _showTransferSheet(context),
                          ),
                        ),
                        const SizedBox(width: 12),
                        Expanded(
                          child: _buildQuickAction(
                            icon: Icons.history,
                            label: 'السجل',
                            onTap: () {},
                          ),
                        ),
                        const SizedBox(width: 12),
                        Expanded(
                          child: _buildQuickAction(
                            icon: Icons.help_outline,
                            label: 'مساعدة',
                            onTap: () {},
                          ),
                        ),
                      ],
                    ),

                    const SizedBox(height: 24),

                    // Payment Methods
                    const Text(
                      'طرق الشحن',
                      style: TextStyle(
                        fontSize: 18,
                        fontWeight: FontWeight.bold,
                      ),
                    ),
                    const SizedBox(height: 12),
                    _buildPaymentMethodItem(
                      icon: Icons.credit_card,
                      title: 'بطاقة ائتمان / خصم',
                      subtitle: 'Visa, Mastercard',
                      color: AppColors.primary,
                      onTap: () => _showTopUpSheet(context, method: PaymentMethod.card),
                    ),
                    _buildPaymentMethodItem(
                      icon: Icons.phone_android,
                      title: 'فودافون كاش',
                      subtitle: 'الدفع عبر المحفظة الإلكترونية',
                      color: AppColors.error,
                      onTap: () => _showTopUpSheet(context, method: PaymentMethod.vodafoneCash),
                    ),
                    _buildPaymentMethodItem(
                      icon: Icons.store,
                      title: 'فوري',
                      subtitle: 'اشحن من أي منفذ فوري',
                      color: AppColors.warning,
                      onTap: () => _showTopUpSheet(context, method: PaymentMethod.fawry),
                    ),

                    const SizedBox(height: 24),

                    // Transaction History
                    Row(
                      mainAxisAlignment: MainAxisAlignment.spaceBetween,
                      children: [
                        const Text(
                          'آخر المعاملات',
                          style: TextStyle(
                            fontSize: 18,
                            fontWeight: FontWeight.bold,
                          ),
                        ),
                        if (_transactions.isNotEmpty)
                          TextButton(
                            onPressed: () {},
                            child: const Text('عرض الكل'),
                          ),
                      ],
                    ),
                    const SizedBox(height: 12),

                    if (_transactions.isEmpty)
                      const Center(
                        child: Padding(
                          padding: EdgeInsets.all(32),
                          child: Column(
                            children: [
                              Icon(
                                Icons.receipt_long,
                                size: 48,
                                color: AppColors.textSecondary,
                              ),
                              SizedBox(height: 16),
                              Text(
                                'لا توجد معاملات بعد',
                                style: TextStyle(color: AppColors.textSecondary),
                              ),
                            ],
                          ),
                        ),
                      )
                    else
                      ...(_transactions.take(5).map((tx) => _buildTransactionItem(tx))),
                  ],
                ),
              ),
            ),
    );
  }

  Widget _buildQuickAction({
    required IconData icon,
    required String label,
    required VoidCallback onTap,
  }) {
    return InkWell(
      onTap: onTap,
      borderRadius: BorderRadius.circular(12),
      child: Container(
        padding: const EdgeInsets.symmetric(vertical: 16),
        decoration: BoxDecoration(
          color: AppColors.surfaceVariant,
          borderRadius: BorderRadius.circular(12),
        ),
        child: Column(
          children: [
            Icon(icon, color: AppColors.primary),
            const SizedBox(height: 8),
            Text(
              label,
              style: const TextStyle(
                fontSize: 12,
                fontWeight: FontWeight.w500,
              ),
            ),
          ],
        ),
      ),
    );
  }

  Widget _buildPaymentMethodItem({
    required IconData icon,
    required String title,
    required String subtitle,
    required Color color,
    required VoidCallback onTap,
  }) {
    return Card(
      margin: const EdgeInsets.only(bottom: 12),
      child: ListTile(
        leading: Container(
          padding: const EdgeInsets.all(10),
          decoration: BoxDecoration(
            color: color.withOpacity(0.1),
            borderRadius: BorderRadius.circular(8),
          ),
          child: Icon(icon, color: color),
        ),
        title: Text(title),
        subtitle: Text(
          subtitle,
          style: const TextStyle(
            color: AppColors.textSecondary,
            fontSize: 12,
          ),
        ),
        trailing: const Icon(
          Icons.arrow_forward_ios,
          size: 16,
          color: AppColors.textSecondary,
        ),
        onTap: onTap,
      ),
    );
  }

  Widget _buildTransactionItem(Map<String, dynamic> tx) {
    final isCredit = tx['type'] == 'topup' || tx['to_user_id'] == ref.read(currentUserProvider)?.id;
    final amount = (tx['amount'] as num).toDouble();
    final status = tx['status'] as String;
    final type = tx['type'] as String;

    String typeText;
    IconData typeIcon;
    switch (type) {
      case 'topup':
        typeText = 'شحن الرصيد';
        typeIcon = Icons.add_circle;
        break;
      case 'ride_payment':
        typeText = 'دفع رحلة';
        typeIcon = Icons.directions_car;
        break;
      case 'shipment_payment':
        typeText = 'دفع شحنة';
        typeIcon = Icons.local_shipping;
        break;
      case 'refund':
        typeText = 'استرداد';
        typeIcon = Icons.replay;
        break;
      default:
        typeText = type;
        typeIcon = Icons.swap_horiz;
    }

    Color statusColor;
    switch (status) {
      case 'completed':
        statusColor = AppColors.success;
        break;
      case 'pending':
        statusColor = AppColors.warning;
        break;
      case 'failed':
        statusColor = AppColors.error;
        break;
      default:
        statusColor = AppColors.textSecondary;
    }

    return Card(
      margin: const EdgeInsets.only(bottom: 8),
      child: ListTile(
        leading: CircleAvatar(
          backgroundColor: (isCredit ? AppColors.success : AppColors.error).withOpacity(0.1),
          child: Icon(
            typeIcon,
            color: isCredit ? AppColors.success : AppColors.error,
            size: 20,
          ),
        ),
        title: Text(typeText),
        subtitle: Row(
          children: [
            Container(
              padding: const EdgeInsets.symmetric(horizontal: 6, vertical: 2),
              decoration: BoxDecoration(
                color: statusColor.withOpacity(0.1),
                borderRadius: BorderRadius.circular(4),
              ),
              child: Text(
                status == 'completed' ? 'مكتمل' : (status == 'pending' ? 'قيد الانتظار' : 'فشل'),
                style: TextStyle(
                  fontSize: 10,
                  color: statusColor,
                ),
              ),
            ),
          ],
        ),
        trailing: Text(
          '${isCredit ? '+' : '-'}${amount.toStringAsFixed(2)} ج.م',
          style: TextStyle(
            fontWeight: FontWeight.bold,
            color: isCredit ? AppColors.success : AppColors.error,
          ),
        ),
      ),
    );
  }

  void _showTopUpSheet(BuildContext context, {PaymentMethod? method}) {
    final amountController = TextEditingController();
    double? selectedAmount;

    showModalBottomSheet(
      context: context,
      isScrollControlled: true,
      backgroundColor: Colors.transparent,
      builder: (context) => StatefulBuilder(
        builder: (context, setSheetState) => Container(
          padding: EdgeInsets.fromLTRB(
            24,
            24,
            24,
            MediaQuery.of(context).viewInsets.bottom + 24,
          ),
          decoration: const BoxDecoration(
            color: AppColors.surface,
            borderRadius: BorderRadius.vertical(top: Radius.circular(24)),
          ),
          child: Column(
            mainAxisSize: MainAxisSize.min,
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              Row(
                children: [
                  const Expanded(
                    child: Text(
                      'شحن الرصيد',
                      style: TextStyle(
                        fontSize: 20,
                        fontWeight: FontWeight.bold,
                      ),
                    ),
                  ),
                  IconButton(
                    icon: const Icon(Icons.close),
                    onPressed: () => Navigator.pop(context),
                  ),
                ],
              ),
              const SizedBox(height: 16),
              const Text('اختر المبلغ'),
              const SizedBox(height: 12),
              Wrap(
                spacing: 8,
                runSpacing: 8,
                children: [50, 100, 200, 500].map((amount) {
                  final isSelected = selectedAmount == amount.toDouble();
                  return ChoiceChip(
                    label: Text('$amount ج.م'),
                    selected: isSelected,
                    selectedColor: AppColors.primary.withOpacity(0.2),
                    onSelected: (selected) {
                      setSheetState(() {
                        selectedAmount = selected ? amount.toDouble() : null;
                        if (selected) {
                          amountController.text = amount.toString();
                        }
                      });
                    },
                  );
                }).toList(),
              ),
              const SizedBox(height: 16),
              TextField(
                controller: amountController,
                keyboardType: TextInputType.number,
                inputFormatters: [FilteringTextInputFormatter.digitsOnly],
                decoration: InputDecoration(
                  hintText: 'أو أدخل مبلغ آخر',
                  suffixText: 'ج.م',
                  border: OutlineInputBorder(
                    borderRadius: BorderRadius.circular(12),
                  ),
                ),
                onChanged: (value) {
                  setSheetState(() {
                    selectedAmount = double.tryParse(value);
                  });
                },
              ),
              const SizedBox(height: 24),
              ElevatedButton(
                onPressed: selectedAmount != null && selectedAmount! >= 10
                    ? () async {
                        Navigator.pop(context);
                        await _processTopUp(selectedAmount!, method ?? PaymentMethod.card);
                      }
                    : null,
                child: const Text('متابعة'),
              ),
              const SizedBox(height: 8),
              const Text(
                'الحد الأدنى للشحن 10 ج.م',
                textAlign: TextAlign.center,
                style: TextStyle(
                  color: AppColors.textSecondary,
                  fontSize: 12,
                ),
              ),
            ],
          ),
        ),
      ),
    );
  }

  Future<void> _processTopUp(double amount, PaymentMethod method) async {
    final user = ref.read(currentUserProvider);
    if (user == null) return;

    final service = ref.read(paymentServiceProvider);

    // Show loading dialog
    showDialog(
      context: context,
      barrierDismissible: false,
      builder: (context) => const Center(
        child: CircularProgressIndicator(),
      ),
    );

    try {
      PaymentResult result;

      if (method == PaymentMethod.card) {
        result = await service.topUpWithCard(
          userId: user.id,
          amount: amount,
          firstName: 'User',
          lastName: 'GO-ON',
          email: user.email ?? 'user@goon.app',
          phone: user.phone ?? '',
        );
      } else if (method == PaymentMethod.vodafoneCash) {
        // Show phone input dialog for vodafone cash
        Navigator.pop(context); // Close loading
        _showVodafoneCashDialog(amount);
        return;
      } else {
        result = PaymentResult(
          success: false,
          message: 'طريقة الدفع غير متاحة حالياً',
        );
      }

      Navigator.pop(context); // Close loading

      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(
          content: Text(result.message ?? (result.success ? 'تم بنجاح' : 'فشلت العملية')),
          backgroundColor: result.success ? AppColors.success : AppColors.error,
        ),
      );

      if (result.success) {
        _loadWalletData();
      }
    } catch (e) {
      Navigator.pop(context); // Close loading
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(
          content: Text('خطأ: $e'),
          backgroundColor: AppColors.error,
        ),
      );
    }
  }

  void _showVodafoneCashDialog(double amount) {
    final phoneController = TextEditingController();

    showDialog(
      context: context,
      builder: (context) => AlertDialog(
        title: const Text('فودافون كاش'),
        content: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            const Text('أدخل رقم محفظة فودافون كاش'),
            const SizedBox(height: 16),
            TextField(
              controller: phoneController,
              keyboardType: TextInputType.phone,
              textDirection: TextDirection.ltr,
              decoration: InputDecoration(
                hintText: '01012345678',
                prefixText: '+20 ',
                border: OutlineInputBorder(
                  borderRadius: BorderRadius.circular(12),
                ),
              ),
            ),
          ],
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(context),
            child: const Text('إلغاء'),
          ),
          ElevatedButton(
            onPressed: () async {
              if (phoneController.text.length < 10) {
                ScaffoldMessenger.of(context).showSnackBar(
                  const SnackBar(
                    content: Text('رقم الهاتف غير صحيح'),
                    backgroundColor: AppColors.error,
                  ),
                );
                return;
              }

              Navigator.pop(context);

              final user = ref.read(currentUserProvider);
              if (user == null) return;

              final service = ref.read(paymentServiceProvider);

              showDialog(
                context: context,
                barrierDismissible: false,
                builder: (context) => const Center(child: CircularProgressIndicator()),
              );

              final result = await service.topUpWithVodafoneCash(
                userId: user.id,
                amount: amount,
                walletPhone: '+20${phoneController.text}',
                firstName: 'User',
                lastName: 'GO-ON',
                email: user.email ?? 'user@goon.app',
                phone: user.phone ?? '',
              );

              Navigator.pop(context); // Close loading

              ScaffoldMessenger.of(context).showSnackBar(
                SnackBar(
                  content: Text(result.message ?? 'تم'),
                  backgroundColor: result.success ? AppColors.success : AppColors.error,
                ),
              );

              if (result.success) {
                _loadWalletData();
              }
            },
            child: const Text('متابعة'),
          ),
        ],
      ),
    );
  }

  void _showTransferSheet(BuildContext context) {
    showModalBottomSheet(
      context: context,
      backgroundColor: Colors.transparent,
      builder: (context) => Container(
        padding: const EdgeInsets.all(24),
        decoration: const BoxDecoration(
          color: AppColors.surface,
          borderRadius: BorderRadius.vertical(top: Radius.circular(24)),
        ),
        child: const Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            Icon(
              Icons.construction,
              size: 48,
              color: AppColors.textSecondary,
            ),
            SizedBox(height: 16),
            Text(
              'قريباً!',
              style: TextStyle(
                fontSize: 20,
                fontWeight: FontWeight.bold,
              ),
            ),
            SizedBox(height: 8),
            Text(
              'خاصية التحويل ستكون متاحة قريباً',
              style: TextStyle(color: AppColors.textSecondary),
            ),
          ],
        ),
      ),
    );
  }
}
