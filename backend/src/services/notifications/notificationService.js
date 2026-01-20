/**
 * Notification Service for push notifications
 * Uses Firebase Cloud Messaging (FCM)
 */

const admin = require('firebase-admin');
const { createClient } = require('@supabase/supabase-js');

class NotificationService {
  constructor() {
    this.isInitialized = false;
    this.supabase = null;
  }

  /**
   * Initialize Firebase Admin SDK
   */
  initialize() {
    if (this.isInitialized) return;

    // Initialize Firebase if credentials are provided
    if (process.env.FIREBASE_SERVICE_ACCOUNT) {
      const serviceAccount = JSON.parse(process.env.FIREBASE_SERVICE_ACCOUNT);
      admin.initializeApp({
        credential: admin.credential.cert(serviceAccount),
      });
    } else if (process.env.GOOGLE_APPLICATION_CREDENTIALS) {
      admin.initializeApp({
        credential: admin.credential.applicationDefault(),
      });
    } else {
      console.warn('Firebase credentials not found - notifications disabled');
      return;
    }

    // Initialize Supabase
    this.supabase = createClient(
      process.env.SUPABASE_URL,
      process.env.SUPABASE_SERVICE_KEY
    );

    this.isInitialized = true;
    console.log('Notification Service initialized');
  }

  /**
   * Send notification to a single device
   */
  async sendToDevice(fcmToken, notification, data = {}) {
    if (!this.isInitialized) {
      console.warn('Notification service not initialized');
      return { success: false, error: 'Service not initialized' };
    }

    try {
      const message = {
        token: fcmToken,
        notification: {
          title: notification.title,
          body: notification.body,
        },
        data: {
          ...data,
          click_action: 'FLUTTER_NOTIFICATION_CLICK',
        },
        android: {
          priority: 'high',
          notification: {
            channelId: 'go_on_channel',
            priority: 'high',
            defaultSound: true,
            defaultVibrateTimings: true,
          },
        },
        apns: {
          payload: {
            aps: {
              sound: 'default',
              badge: 1,
            },
          },
        },
      };

      const response = await admin.messaging().send(message);
      return { success: true, messageId: response };
    } catch (error) {
      console.error('Error sending notification:', error);
      return { success: false, error: error.message };
    }
  }

  /**
   * Send notification to a user
   */
  async sendToUser(userId, notification, data = {}) {
    // Get user's FCM token
    const { data: profile } = await this.supabase
      .from('profiles')
      .select('fcm_token')
      .eq('id', userId)
      .single();

    if (!profile?.fcm_token) {
      return { success: false, error: 'User has no FCM token' };
    }

    // Save notification to database
    await this.saveNotification(userId, notification, data);

    return await this.sendToDevice(profile.fcm_token, notification, data);
  }

  /**
   * Send notification to multiple users
   */
  async sendToUsers(userIds, notification, data = {}) {
    const results = [];

    for (const userId of userIds) {
      const result = await this.sendToUser(userId, notification, data);
      results.push({ userId, ...result });
    }

    return results;
  }

  /**
   * Send notification to a topic
   */
  async sendToTopic(topic, notification, data = {}) {
    if (!this.isInitialized) {
      return { success: false, error: 'Service not initialized' };
    }

    try {
      const message = {
        topic,
        notification: {
          title: notification.title,
          body: notification.body,
        },
        data: {
          ...data,
          click_action: 'FLUTTER_NOTIFICATION_CLICK',
        },
      };

      const response = await admin.messaging().send(message);
      return { success: true, messageId: response };
    } catch (error) {
      console.error('Error sending topic notification:', error);
      return { success: false, error: error.message };
    }
  }

  /**
   * Save notification to database
   */
  async saveNotification(userId, notification, data = {}) {
    try {
      await this.supabase.from('notifications').insert({
        user_id: userId,
        title: notification.title,
        body: notification.body,
        data,
        is_read: false,
      });
    } catch (error) {
      console.error('Error saving notification:', error);
    }
  }

  /**
   * Notify user about ride status change
   */
  async notifyRideStatus(ride, status) {
    const notifications = {
      accepted: {
        title: 'تم قبول طلبك! 🚗',
        body: 'السائق في الطريق إليك',
      },
      arrived: {
        title: 'السائق وصل! 📍',
        body: 'السائق في انتظارك في نقطة الانطلاق',
      },
      started: {
        title: 'الرحلة بدأت! 🛣️',
        body: 'استمتع برحلتك',
      },
      completed: {
        title: 'وصلت بالسلامة! ✅',
        body: `إجمالي الرحلة: ${ride.final_price || ride.estimated_price} ج.م`,
      },
      cancelled: {
        title: 'تم إلغاء الرحلة ❌',
        body: ride.cancellation_reason || 'تم إلغاء الرحلة',
      },
    };

    const notification = notifications[status];
    if (!notification) return;

    return await this.sendToUser(ride.user_id, notification, {
      type: 'ride_status',
      ride_id: ride.id,
      status,
    });
  }

  /**
   * Notify driver about new ride
   */
  async notifyDriverNewRide(driverId, ride) {
    // Get driver's user_id
    const { data: driver } = await this.supabase
      .from('drivers')
      .select('user_id')
      .eq('id', driverId)
      .single();

    if (!driver?.user_id) return;

    return await this.sendToUser(driver.user_id, {
      title: 'طلب جديد! 🚗',
      body: `من ${ride.origin_name || ride.origin_address} - ${ride.estimated_price} ج.م`,
    }, {
      type: 'new_ride',
      ride_id: ride.id,
    });
  }

  /**
   * Notify user about shipment status change
   */
  async notifyShipmentStatus(shipment, status) {
    const notifications = {
      accepted: {
        title: 'تم قبول شحنتك! 📦',
        body: 'السائق في الطريق للاستلام',
      },
      picked_up: {
        title: 'تم استلام الشحنة! ✅',
        body: 'الشحنة في الطريق للتسليم',
      },
      in_transit: {
        title: 'الشحنة في الطريق! 🚚',
        body: 'تابع شحنتك في التطبيق',
      },
      delivered: {
        title: 'تم التسليم! 🎉',
        body: 'شكراً لاستخدامك GO-ON',
      },
      cancelled: {
        title: 'تم إلغاء الشحنة ❌',
        body: 'تم إلغاء طلب الشحن',
      },
    };

    const notification = notifications[status];
    if (!notification) return;

    return await this.sendToUser(shipment.sender_id, notification, {
      type: 'shipment_status',
      shipment_id: shipment.id,
      status,
    });
  }

  /**
   * Send promotional notification
   */
  async sendPromotion(userIds, title, body, promoData = {}) {
    const results = [];

    for (const userId of userIds) {
      const result = await this.sendToUser(userId, { title, body }, {
        type: 'promotion',
        ...promoData,
      });
      results.push({ userId, ...result });
    }

    return results;
  }

  /**
   * Subscribe user to topic
   */
  async subscribeToTopic(fcmToken, topic) {
    if (!this.isInitialized) return;

    try {
      await admin.messaging().subscribeToTopic(fcmToken, topic);
      return { success: true };
    } catch (error) {
      return { success: false, error: error.message };
    }
  }

  /**
   * Unsubscribe user from topic
   */
  async unsubscribeFromTopic(fcmToken, topic) {
    if (!this.isInitialized) return;

    try {
      await admin.messaging().unsubscribeFromTopic(fcmToken, topic);
      return { success: true };
    } catch (error) {
      return { success: false, error: error.message };
    }
  }
}

module.exports = new NotificationService();
