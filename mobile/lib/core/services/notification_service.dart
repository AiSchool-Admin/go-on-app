import 'dart:convert';
import 'package:firebase_core/firebase_core.dart';
import 'package:firebase_messaging/firebase_messaging.dart';
import 'package:flutter/foundation.dart';
import 'package:flutter_local_notifications/flutter_local_notifications.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import '../utils/app_logger.dart';

/// Provider for NotificationService
final notificationServiceProvider = Provider<NotificationService>((ref) {
  return NotificationService();
});

/// Background message handler - must be top-level function
@pragma('vm:entry-point')
Future<void> _firebaseMessagingBackgroundHandler(RemoteMessage message) async {
  try {
    await Firebase.initializeApp();
    AppLogger.i('Handling background message: ${message.messageId}', 'FCM');
  } catch (e) {
    debugPrint('Background handler error: $e');
  }
}

/// Check if Firebase is properly initialized
bool _isFirebaseInitialized() {
  try {
    return Firebase.apps.isNotEmpty;
  } catch (e) {
    return false;
  }
}

/// GO-ON Notification Service
///
/// Handles all notification functionality:
/// - Firebase Cloud Messaging (FCM) for push notifications
/// - Local notifications for foreground alerts
/// - Price alerts when better prices are found
/// - Ride status updates
class NotificationService {
  static final FlutterLocalNotificationsPlugin _localNotifications =
      FlutterLocalNotificationsPlugin();

  static final FirebaseMessaging _firebaseMessaging =
      FirebaseMessaging.instance;

  static bool _initialized = false;

  // Notification Channels
  static const String _priceAlertChannelId = 'price_alerts';
  static const String _priceAlertChannelName = 'تنبيهات الأسعار';
  static const String _priceAlertChannelDesc = 'إشعارات عند وجود سعر أفضل';

  static const String _rideUpdatesChannelId = 'ride_updates';
  static const String _rideUpdatesChannelName = 'تحديثات الرحلات';
  static const String _rideUpdatesChannelDesc = 'إشعارات حالة الرحلة';

  static const String _generalChannelId = 'general';
  static const String _generalChannelName = 'إشعارات عامة';
  static const String _generalChannelDesc = 'إشعارات عامة من GO-ON';

  /// Initialize notification service
  /// Call this in main() before runApp()
  static Future<void> initialize() async {
    if (_initialized) {
      AppLogger.w('NotificationService already initialized', 'Notification');
      return;
    }

    try {
      // Initialize local notifications first (always works)
      await _initializeLocalNotifications();

      // Only setup Firebase if it's properly initialized
      if (_isFirebaseInitialized()) {
        try {
          // Initialize Firebase Messaging background handler
          FirebaseMessaging.onBackgroundMessage(_firebaseMessagingBackgroundHandler);

          // Request permissions
          await _requestPermissions();

          // Setup message handlers
          _setupMessageHandlers();

          // Get FCM token
          await _getFCMToken();

          AppLogger.i('FCM initialized successfully', 'Notification');
        } catch (e) {
          AppLogger.w('FCM not available, using local notifications only: $e', 'Notification');
        }
      } else {
        AppLogger.w('Firebase not initialized, using local notifications only', 'Notification');
        // Request local notification permissions only
        await _requestLocalNotificationPermission();
      }

      _initialized = true;
      AppLogger.i('NotificationService initialized successfully', 'Notification');
    } catch (e, stackTrace) {
      AppLogger.e('Failed to initialize NotificationService', e, stackTrace, 'Notification');
    }
  }

  /// Request local notification permission only (when Firebase is not available)
  static Future<void> _requestLocalNotificationPermission() async {
    final androidPlugin =
        _localNotifications.resolvePlatformSpecificImplementation<
            AndroidFlutterLocalNotificationsPlugin>();

    if (androidPlugin != null) {
      final granted = await androidPlugin.requestNotificationsPermission();
      AppLogger.permission(
        'Local notification permission',
        permissionType: 'local_notification',
        granted: granted ?? false,
      );
    }
  }

  /// Initialize local notifications plugin
  static Future<void> _initializeLocalNotifications() async {
    // Android settings
    const androidSettings = AndroidInitializationSettings('@mipmap/ic_launcher');

    // iOS settings (for future use)
    const iosSettings = DarwinInitializationSettings(
      requestAlertPermission: true,
      requestBadgePermission: true,
      requestSoundPermission: true,
    );

    const initSettings = InitializationSettings(
      android: androidSettings,
      iOS: iosSettings,
    );

    await _localNotifications.initialize(
      initSettings,
      onDidReceiveNotificationResponse: _onNotificationTapped,
      onDidReceiveBackgroundNotificationResponse: _onBackgroundNotificationTapped,
    );

    // Create notification channels for Android
    await _createNotificationChannels();

    AppLogger.d('Local notifications initialized', 'Notification');
  }

  /// Create notification channels for Android
  static Future<void> _createNotificationChannels() async {
    final androidPlugin =
        _localNotifications.resolvePlatformSpecificImplementation<
            AndroidFlutterLocalNotificationsPlugin>();

    if (androidPlugin != null) {
      // Price alerts channel (high priority)
      await androidPlugin.createNotificationChannel(
        const AndroidNotificationChannel(
          _priceAlertChannelId,
          _priceAlertChannelName,
          description: _priceAlertChannelDesc,
          importance: Importance.high,
          playSound: true,
          enableVibration: true,
        ),
      );

      // Ride updates channel
      await androidPlugin.createNotificationChannel(
        const AndroidNotificationChannel(
          _rideUpdatesChannelId,
          _rideUpdatesChannelName,
          description: _rideUpdatesChannelDesc,
          importance: Importance.high,
          playSound: true,
        ),
      );

      // General channel
      await androidPlugin.createNotificationChannel(
        const AndroidNotificationChannel(
          _generalChannelId,
          _generalChannelName,
          description: _generalChannelDesc,
          importance: Importance.defaultImportance,
        ),
      );

      AppLogger.d('Notification channels created', 'Notification');
    }
  }

  /// Request notification permissions
  static Future<void> _requestPermissions() async {
    // Request FCM permissions
    final settings = await _firebaseMessaging.requestPermission(
      alert: true,
      announcement: false,
      badge: true,
      carPlay: false,
      criticalAlert: false,
      provisional: false,
      sound: true,
    );

    AppLogger.permission(
      'FCM permission status: ${settings.authorizationStatus}',
      permissionType: 'notification',
      granted: settings.authorizationStatus == AuthorizationStatus.authorized,
    );

    // Request local notification permissions (Android 13+)
    final androidPlugin =
        _localNotifications.resolvePlatformSpecificImplementation<
            AndroidFlutterLocalNotificationsPlugin>();

    if (androidPlugin != null) {
      final granted = await androidPlugin.requestNotificationsPermission();
      AppLogger.permission(
        'Local notification permission',
        permissionType: 'local_notification',
        granted: granted ?? false,
      );
    }
  }

  /// Setup FCM message handlers
  static void _setupMessageHandlers() {
    // Handle messages when app is in foreground
    FirebaseMessaging.onMessage.listen(_handleForegroundMessage);

    // Handle notification tap when app was in background
    FirebaseMessaging.onMessageOpenedApp.listen(_handleMessageOpenedApp);

    // Check if app was opened from a notification
    _firebaseMessaging.getInitialMessage().then((message) {
      if (message != null) {
        _handleInitialMessage(message);
      }
    });

    AppLogger.d('FCM message handlers setup complete', 'Notification');
  }

  /// Get FCM token for push notifications
  static Future<String?> _getFCMToken() async {
    try {
      final token = await _firebaseMessaging.getToken();
      if (token != null) {
        AppLogger.d('FCM Token: ${token.substring(0, 20)}...', 'Notification');
        // TODO: Send token to backend for push notifications
      }
      return token;
    } catch (e) {
      AppLogger.e('Failed to get FCM token', e, null, 'Notification');
      return null;
    }
  }

  /// Handle foreground messages
  static Future<void> _handleForegroundMessage(RemoteMessage message) async {
    AppLogger.i('Received foreground message: ${message.messageId}', 'FCM');

    final notification = message.notification;
    final data = message.data;

    if (notification != null) {
      // Show local notification
      await showNotification(
        title: notification.title ?? 'GO-ON',
        body: notification.body ?? '',
        payload: jsonEncode(data),
        channelId: _getChannelFromData(data),
      );
    }
  }

  /// Handle message when app is opened from notification
  static void _handleMessageOpenedApp(RemoteMessage message) {
    AppLogger.i('App opened from notification: ${message.messageId}', 'FCM');
    _handleNotificationData(message.data);
  }

  /// Handle initial message (app opened from terminated state)
  static void _handleInitialMessage(RemoteMessage message) {
    AppLogger.i('App opened from terminated state: ${message.messageId}', 'FCM');
    _handleNotificationData(message.data);
  }

  /// Handle notification data for navigation
  static void _handleNotificationData(Map<String, dynamic> data) {
    final type = data['type'] as String?;
    final id = data['id'] as String?;

    AppLogger.d('Handling notification data: type=$type, id=$id', 'Notification');

    // TODO: Navigate based on notification type
    switch (type) {
      case 'price_alert':
        // Navigate to price comparison
        break;
      case 'ride_update':
        // Navigate to ride tracking
        break;
      case 'promo':
        // Navigate to promo details
        break;
    }
  }

  /// Handle notification tap (local notifications)
  static void _onNotificationTapped(NotificationResponse response) {
    AppLogger.i('Notification tapped: ${response.payload}', 'Notification');

    if (response.payload != null) {
      try {
        final data = jsonDecode(response.payload!) as Map<String, dynamic>;
        _handleNotificationData(data);
      } catch (e) {
        AppLogger.e('Failed to parse notification payload', e, null, 'Notification');
      }
    }
  }

  /// Handle background notification tap
  @pragma('vm:entry-point')
  static void _onBackgroundNotificationTapped(NotificationResponse response) {
    // This runs in a separate isolate
    debugPrint('Background notification tapped: ${response.payload}');
  }

  /// Get channel ID from notification data
  static String _getChannelFromData(Map<String, dynamic> data) {
    final type = data['type'] as String?;
    switch (type) {
      case 'price_alert':
        return _priceAlertChannelId;
      case 'ride_update':
        return _rideUpdatesChannelId;
      default:
        return _generalChannelId;
    }
  }

  // ============================================================
  // PUBLIC METHODS
  // ============================================================

  /// Show a local notification
  static Future<void> showNotification({
    required String title,
    required String body,
    String? payload,
    String channelId = _generalChannelId,
    bool playSound = true,
  }) async {
    final androidDetails = AndroidNotificationDetails(
      channelId,
      _getChannelName(channelId),
      channelDescription: _getChannelDescription(channelId),
      importance: Importance.high,
      priority: Priority.high,
      playSound: playSound,
      icon: '@mipmap/ic_launcher',
    );

    const iosDetails = DarwinNotificationDetails(
      presentAlert: true,
      presentBadge: true,
      presentSound: true,
    );

    final details = NotificationDetails(
      android: androidDetails,
      iOS: iosDetails,
    );

    final id = DateTime.now().millisecondsSinceEpoch ~/ 1000;

    await _localNotifications.show(id, title, body, details, payload: payload);

    AppLogger.d('Showed notification: $title', 'Notification');
  }

  /// Show price alert notification
  static Future<void> showPriceAlert({
    required String appName,
    required double currentPrice,
    required double betterPrice,
    required int savingsPercent,
  }) async {
    final savings = currentPrice - betterPrice;

    await showNotification(
      title: 'سعر أفضل متاح! 💰',
      body: 'وفر ${savings.toInt()} ج.م (${savingsPercent}%) - ${betterPrice.toInt()} ج.م بدلاً من ${currentPrice.toInt()} ج.م في $appName',
      payload: jsonEncode({
        'type': 'price_alert',
        'app': appName,
        'price': betterPrice,
      }),
      channelId: _priceAlertChannelId,
    );

    AppLogger.price(
      'Price alert shown',
      price: betterPrice,
      app: appName,
    );
  }

  /// Show ride status notification
  static Future<void> showRideUpdate({
    required String title,
    required String message,
    String? rideId,
  }) async {
    await showNotification(
      title: title,
      body: message,
      payload: jsonEncode({
        'type': 'ride_update',
        'id': rideId,
      }),
      channelId: _rideUpdatesChannelId,
    );
  }

  /// Show driver arrived notification
  static Future<void> showDriverArrived({
    required String driverName,
    required String carInfo,
  }) async {
    await showRideUpdate(
      title: 'السائق وصل! 🚗',
      message: '$driverName وصل - $carInfo',
    );
  }

  /// Show ride completed notification
  static Future<void> showRideCompleted({
    required double price,
    required String? driverName,
  }) async {
    await showRideUpdate(
      title: 'وصلت بالسلامة! ✅',
      message: 'المبلغ: ${price.toInt()} ج.م${driverName != null ? ' - شكراً $driverName' : ''}',
    );
  }

  /// Cancel all notifications
  static Future<void> cancelAll() async {
    await _localNotifications.cancelAll();
    AppLogger.d('All notifications cancelled', 'Notification');
  }

  /// Cancel specific notification
  static Future<void> cancel(int id) async {
    await _localNotifications.cancel(id);
  }

  /// Get FCM token (for backend registration)
  static Future<String?> getToken() async {
    if (!_isFirebaseInitialized()) return null;
    try {
      return await _firebaseMessaging.getToken();
    } catch (e) {
      AppLogger.w('Failed to get FCM token: $e', 'Notification');
      return null;
    }
  }

  /// Subscribe to topic for group notifications
  static Future<void> subscribeToTopic(String topic) async {
    if (!_isFirebaseInitialized()) return;
    try {
      await _firebaseMessaging.subscribeToTopic(topic);
      AppLogger.d('Subscribed to topic: $topic', 'Notification');
    } catch (e) {
      AppLogger.w('Failed to subscribe to topic: $e', 'Notification');
    }
  }

  /// Unsubscribe from topic
  static Future<void> unsubscribeFromTopic(String topic) async {
    if (!_isFirebaseInitialized()) return;
    try {
      await _firebaseMessaging.unsubscribeFromTopic(topic);
      AppLogger.d('Unsubscribed from topic: $topic', 'Notification');
    } catch (e) {
      AppLogger.w('Failed to unsubscribe from topic: $e', 'Notification');
    }
  }

  // Helper methods
  static String _getChannelName(String channelId) {
    switch (channelId) {
      case _priceAlertChannelId:
        return _priceAlertChannelName;
      case _rideUpdatesChannelId:
        return _rideUpdatesChannelName;
      default:
        return _generalChannelName;
    }
  }

  static String _getChannelDescription(String channelId) {
    switch (channelId) {
      case _priceAlertChannelId:
        return _priceAlertChannelDesc;
      case _rideUpdatesChannelId:
        return _rideUpdatesChannelDesc;
      default:
        return _generalChannelDesc;
    }
  }
}
