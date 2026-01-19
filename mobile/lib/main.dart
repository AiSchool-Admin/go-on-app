import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:supabase_flutter/supabase_flutter.dart';
import 'package:flutter_localizations/flutter_localizations.dart';
import 'package:firebase_core/firebase_core.dart';

import 'core/theme/app_theme.dart';
import 'core/routes/app_router.dart';
import 'core/constants/app_constants.dart';
import 'core/services/notification_service.dart';
import 'core/utils/app_logger.dart';

void main() async {
  WidgetsFlutterBinding.ensureInitialized();

  // Initialize Firebase
  try {
    await Firebase.initializeApp();
    AppLogger.i('Firebase initialized', 'Main');
  } catch (e) {
    AppLogger.e('Failed to initialize Firebase', e, null, 'Main');
  }

  // Initialize Notifications
  try {
    await NotificationService.initialize();
    AppLogger.i('NotificationService initialized', 'Main');
  } catch (e) {
    AppLogger.e('Failed to initialize NotificationService', e, null, 'Main');
  }

  // Initialize Supabase
  try {
    await Supabase.initialize(
      url: AppConstants.supabaseUrl,
      anonKey: AppConstants.supabaseAnonKey,
    );
    AppLogger.i('Supabase initialized', 'Main');
  } catch (e) {
    AppLogger.e('Failed to initialize Supabase', e, null, 'Main');
  }

  runApp(
    const ProviderScope(
      child: GoOnApp(),
    ),
  );
}

class GoOnApp extends ConsumerWidget {
  const GoOnApp({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final router = ref.watch(appRouterProvider);

    return MaterialApp.router(
      title: 'GO-ON',
      debugShowCheckedModeBanner: false,

      // Theme
      theme: AppTheme.lightTheme,
      darkTheme: AppTheme.darkTheme,
      themeMode: ThemeMode.light,

      // Localization
      locale: const Locale('ar'),
      supportedLocales: const [
        Locale('ar'), // Arabic
        Locale('en'), // English
      ],
      localizationsDelegates: const [
        GlobalMaterialLocalizations.delegate,
        GlobalWidgetsLocalizations.delegate,
        GlobalCupertinoLocalizations.delegate,
      ],

      // Router
      routerConfig: router,
    );
  }
}
