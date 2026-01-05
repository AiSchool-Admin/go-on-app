import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../../../core/constants/app_colors.dart';
import '../../../core/routes/app_router.dart';
import '../widgets/service_card.dart';
import '../widgets/recent_trips_section.dart';

class HomeScreen extends ConsumerStatefulWidget {
  const HomeScreen({super.key});

  @override
  ConsumerState<HomeScreen> createState() => _HomeScreenState();
}

class _HomeScreenState extends ConsumerState<HomeScreen> {
  int _currentIndex = 0;

  final List<Widget> _screens = [
    const _HomeContent(),
    const _RidesTab(),
    const _FreightTab(),
    const _WalletTab(),
    const _ProfileTab(),
  ];

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      body: IndexedStack(
        index: _currentIndex,
        children: _screens,
      ),
      bottomNavigationBar: BottomNavigationBar(
        currentIndex: _currentIndex,
        onTap: (index) => setState(() => _currentIndex = index),
        type: BottomNavigationBarType.fixed,
        items: const [
          BottomNavigationBarItem(
            icon: Icon(Icons.home_outlined),
            activeIcon: Icon(Icons.home),
            label: 'الرئيسية',
          ),
          BottomNavigationBarItem(
            icon: Icon(Icons.directions_car_outlined),
            activeIcon: Icon(Icons.directions_car),
            label: 'ركوب',
          ),
          BottomNavigationBarItem(
            icon: Icon(Icons.local_shipping_outlined),
            activeIcon: Icon(Icons.local_shipping),
            label: 'شحن',
          ),
          BottomNavigationBarItem(
            icon: Icon(Icons.account_balance_wallet_outlined),
            activeIcon: Icon(Icons.account_balance_wallet),
            label: 'المحفظة',
          ),
          BottomNavigationBarItem(
            icon: Icon(Icons.person_outline),
            activeIcon: Icon(Icons.person),
            label: 'حسابي',
          ),
        ],
      ),
    );
  }
}

class _HomeContent extends StatelessWidget {
  const _HomeContent();

  @override
  Widget build(BuildContext context) {
    return SafeArea(
      child: CustomScrollView(
        slivers: [
          // App Bar
          SliverAppBar(
            floating: true,
            backgroundColor: AppColors.background,
            elevation: 0,
            title: const Row(
              children: [
                Text(
                  'GO-ON',
                  style: TextStyle(
                    color: AppColors.primary,
                    fontWeight: FontWeight.bold,
                  ),
                ),
                SizedBox(width: 8),
                Text(
                  'مصر تتحرك',
                  style: TextStyle(
                    color: AppColors.secondary,
                    fontSize: 14,
                  ),
                ),
              ],
            ),
            actions: [
              IconButton(
                icon: const Icon(Icons.notifications_outlined),
                color: AppColors.textPrimary,
                onPressed: () {
                  // TODO: Open notifications
                },
              ),
            ],
          ),

          // Content
          SliverPadding(
            padding: const EdgeInsets.all(16),
            sliver: SliverList(
              delegate: SliverChildListDelegate([
                // Search Bar
                GestureDetector(
                  onTap: () => context.push(AppRoutes.rideSearch),
                  child: Container(
                    padding: const EdgeInsets.all(16),
                    decoration: BoxDecoration(
                      color: AppColors.surface,
                      borderRadius: BorderRadius.circular(12),
                      boxShadow: [
                        BoxShadow(
                          color: AppColors.shadow,
                          blurRadius: 8,
                          offset: const Offset(0, 2),
                        ),
                      ],
                    ),
                    child: const Row(
                      children: [
                        Icon(Icons.search, color: AppColors.textSecondary),
                        SizedBox(width: 12),
                        Text(
                          'إلى أين تريد الذهاب؟',
                          style: TextStyle(
                            color: AppColors.textSecondary,
                            fontSize: 16,
                          ),
                        ),
                      ],
                    ),
                  ),
                ),

                const SizedBox(height: 24),

                // Services Section
                const Text(
                  'خدماتنا',
                  style: TextStyle(
                    fontSize: 18,
                    fontWeight: FontWeight.bold,
                  ),
                ),
                const SizedBox(height: 16),

                Row(
                  children: [
                    Expanded(
                      child: ServiceCard(
                        icon: Icons.directions_car,
                        title: 'ركوب',
                        subtitle: 'قارن الأسعار واحجز',
                        color: AppColors.primary,
                        onTap: () => context.push(AppRoutes.rideSearch),
                      ),
                    ),
                    const SizedBox(width: 16),
                    Expanded(
                      child: ServiceCard(
                        icon: Icons.local_shipping,
                        title: 'شحن',
                        subtitle: 'أرسل طرودك',
                        color: AppColors.secondary,
                        onTap: () => context.push(AppRoutes.freight),
                      ),
                    ),
                  ],
                ),

                const SizedBox(height: 24),

                // Recent Trips
                const RecentTripsSection(),
              ]),
            ),
          ),
        ],
      ),
    );
  }
}

// Placeholder tabs
class _RidesTab extends StatelessWidget {
  const _RidesTab();

  @override
  Widget build(BuildContext context) {
    return const Center(child: Text('قسم الركوب'));
  }
}

class _FreightTab extends StatelessWidget {
  const _FreightTab();

  @override
  Widget build(BuildContext context) {
    return const Center(child: Text('قسم الشحن'));
  }
}

class _WalletTab extends StatelessWidget {
  const _WalletTab();

  @override
  Widget build(BuildContext context) {
    return const Center(child: Text('المحفظة'));
  }
}

class _ProfileTab extends StatelessWidget {
  const _ProfileTab();

  @override
  Widget build(BuildContext context) {
    return const Center(child: Text('حسابي'));
  }
}
