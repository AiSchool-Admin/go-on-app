/**
 * WhatsApp Service for driver communication
 * Uses whatsapp-web.js for WhatsApp Web integration
 */

const { Client, LocalAuth, MessageMedia } = require('whatsapp-web.js');
const qrcode = require('qrcode-terminal');
const { createClient } = require('@supabase/supabase-js');

class WhatsAppService {
  constructor() {
    this.client = null;
    this.isReady = false;
    this.supabase = null;
    this.messageHandlers = new Map();
  }

  /**
   * Initialize WhatsApp client
   */
  async initialize() {
    if (this.client) return;

    // Initialize Supabase
    this.supabase = createClient(
      process.env.SUPABASE_URL,
      process.env.SUPABASE_SERVICE_KEY
    );

    // Initialize WhatsApp client
    this.client = new Client({
      authStrategy: new LocalAuth({ clientId: 'goon-bot' }),
      puppeteer: {
        headless: true,
        args: [
          '--no-sandbox',
          '--disable-setuid-sandbox',
          '--disable-dev-shm-usage',
          '--disable-accelerated-2d-canvas',
          '--no-first-run',
          '--no-zygote',
          '--single-process',
          '--disable-gpu',
        ],
      },
    });

    // Event handlers
    this.client.on('qr', (qr) => {
      console.log('WhatsApp QR Code:');
      qrcode.generate(qr, { small: true });
    });

    this.client.on('ready', () => {
      console.log('WhatsApp client is ready!');
      this.isReady = true;
    });

    this.client.on('message', async (message) => {
      await this.handleIncomingMessage(message);
    });

    this.client.on('disconnected', (reason) => {
      console.log('WhatsApp client disconnected:', reason);
      this.isReady = false;
    });

    await this.client.initialize();
  }

  /**
   * Handle incoming messages
   */
  async handleIncomingMessage(message) {
    try {
      const contact = await message.getContact();
      const phone = contact.number;
      const text = message.body.trim().toLowerCase();

      console.log(`Message from ${phone}: ${text}`);

      // Check if sender is a registered driver
      const { data: driver } = await this.supabase
        .from('drivers')
        .select('*')
        .or(`phone.eq.${phone},whatsapp_number.eq.${phone}`)
        .single();

      if (!driver) {
        // Not a driver - send registration info
        await this.sendMessage(phone, this.getRegistrationMessage());
        return;
      }

      // Handle driver commands
      await this.handleDriverCommand(driver, text, message);
    } catch (error) {
      console.error('Error handling message:', error);
    }
  }

  /**
   * Handle driver commands
   */
  async handleDriverCommand(driver, text, message) {
    const phone = driver.whatsapp_number || driver.phone;

    // Command: Check status
    if (text === 'حالة' || text === 'status') {
      const statusMessage = this.getStatusMessage(driver);
      await this.sendMessage(phone, statusMessage);
      return;
    }

    // Command: Go online
    if (text === 'متاح' || text === 'online' || text === 'اونلاين') {
      await this.setDriverOnline(driver, true);
      await this.sendMessage(phone, '✅ أنت الآن متاح لاستقبال الطلبات');
      return;
    }

    // Command: Go offline
    if (text === 'غير متاح' || text === 'offline' || text === 'اوفلاين') {
      await this.setDriverOnline(driver, false);
      await this.sendMessage(phone, '⏸️ تم إيقاف استقبال الطلبات');
      return;
    }

    // Command: Accept ride
    if (text.startsWith('قبول') || text.startsWith('accept')) {
      const rideId = text.split(' ')[1];
      if (rideId) {
        await this.acceptRide(driver, rideId, phone);
      } else {
        await this.sendMessage(phone, 'الرجاء إرسال رقم الطلب: قبول [رقم الطلب]');
      }
      return;
    }

    // Command: Reject ride
    if (text.startsWith('رفض') || text.startsWith('reject')) {
      const rideId = text.split(' ')[1];
      if (rideId) {
        await this.rejectRide(driver, rideId, phone);
      }
      return;
    }

    // Command: Earnings
    if (text === 'أرباح' || text === 'earnings') {
      const earningsMessage = await this.getEarningsMessage(driver);
      await this.sendMessage(phone, earningsMessage);
      return;
    }

    // Command: Help
    if (text === 'مساعدة' || text === 'help' || text === '؟') {
      await this.sendMessage(phone, this.getHelpMessage());
      return;
    }

    // Default: Show help
    await this.sendMessage(phone, this.getHelpMessage());
  }

  /**
   * Set driver online/offline status
   */
  async setDriverOnline(driver, isOnline) {
    await this.supabase
      .from('drivers')
      .update({
        is_online: isOnline,
        last_location_update: new Date().toISOString(),
      })
      .eq('id', driver.id);
  }

  /**
   * Accept a ride
   */
  async acceptRide(driver, rideId, phone) {
    try {
      // Check if ride exists and is still searching
      const { data: ride } = await this.supabase
        .from('rides')
        .select('*')
        .eq('id', rideId)
        .eq('status', 'searching')
        .single();

      if (!ride) {
        await this.sendMessage(phone, '❌ هذا الطلب غير متاح أو تم قبوله بالفعل');
        return;
      }

      // Accept the ride
      await this.supabase
        .from('rides')
        .update({
          driver_id: driver.id,
          status: 'accepted',
          accepted_at: new Date().toISOString(),
        })
        .eq('id', rideId);

      // Notify driver
      await this.sendMessage(phone, `✅ تم قبول الطلب!\n\n` +
        `📍 من: ${ride.origin_address}\n` +
        `📍 إلى: ${ride.destination_address}\n` +
        `💰 السعر: ${ride.estimated_price} ج.م\n\n` +
        `تواصل مع العميل للتنسيق.`);

      // Notify customer (via push notification - handled separately)
    } catch (error) {
      console.error('Error accepting ride:', error);
      await this.sendMessage(phone, '❌ حدث خطأ. حاول مرة أخرى.');
    }
  }

  /**
   * Reject a ride
   */
  async rejectRide(driver, rideId, phone) {
    // Just acknowledge - ride stays in searching state
    await this.sendMessage(phone, '⏭️ تم تخطي الطلب');
  }

  /**
   * Get driver status message
   */
  getStatusMessage(driver) {
    const status = driver.is_online ? '🟢 متاح' : '🔴 غير متاح';
    const verified = driver.is_verified ? '✅ موثق' : '⏳ قيد المراجعة';

    return `*حالة حسابك*\n\n` +
      `👤 ${driver.name}\n` +
      `📊 الحالة: ${status}\n` +
      `🏆 التقييم: ${driver.rating || 5.0} ⭐\n` +
      `🚗 الرحلات: ${driver.total_rides || 0}\n` +
      `💰 الأرباح المعلقة: ${driver.pending_earnings || 0} ج.م\n` +
      `✓ التحقق: ${verified}`;
  }

  /**
   * Get earnings message
   */
  async getEarningsMessage(driver) {
    const today = new Date();
    today.setHours(0, 0, 0, 0);

    const { data: todayRides } = await this.supabase
      .from('rides')
      .select('final_price')
      .eq('driver_id', driver.id)
      .eq('status', 'completed')
      .gte('completed_at', today.toISOString());

    const todayEarnings = todayRides?.reduce((sum, r) => sum + (r.final_price || 0), 0) || 0;

    return `*أرباحك*\n\n` +
      `📅 اليوم: ${todayEarnings} ج.م\n` +
      `💰 المعلق: ${driver.pending_earnings || 0} ج.م\n` +
      `📊 إجمالي: ${driver.total_earnings || 0} ج.م\n\n` +
      `🚗 إجمالي الرحلات: ${driver.total_rides || 0}`;
  }

  /**
   * Get help message
   */
  getHelpMessage() {
    return `*أوامر GO-ON*\n\n` +
      `📊 *حالة* - عرض حالة حسابك\n` +
      `🟢 *متاح* - بدء استقبال الطلبات\n` +
      `🔴 *غير متاح* - إيقاف استقبال الطلبات\n` +
      `✅ *قبول [رقم]* - قبول طلب\n` +
      `❌ *رفض [رقم]* - رفض طلب\n` +
      `💰 *أرباح* - عرض أرباحك\n` +
      `❓ *مساعدة* - عرض هذه القائمة\n\n` +
      `للتحدث مع الدعم: support@goon.app`;
  }

  /**
   * Get registration message
   */
  getRegistrationMessage() {
    return `مرحباً بك في GO-ON! 🚗\n\n` +
      `أنت غير مسجل كسائق بعد.\n\n` +
      `للتسجيل كسائق، قم بتحميل تطبيق GO-ON وسجل من خلال:\n` +
      `حسابي > سجّل كسائق\n\n` +
      `بعد التسجيل والموافقة، ستتمكن من استقبال الطلبات هنا.`;
  }

  /**
   * Send message to a number
   */
  async sendMessage(phone, message) {
    if (!this.isReady) {
      console.warn('WhatsApp client not ready');
      return false;
    }

    try {
      // Format phone number
      let formattedPhone = phone.replace(/[^\d]/g, '');
      if (formattedPhone.startsWith('0')) {
        formattedPhone = '2' + formattedPhone;
      } else if (!formattedPhone.startsWith('2')) {
        formattedPhone = '20' + formattedPhone;
      }
      formattedPhone += '@c.us';

      await this.client.sendMessage(formattedPhone, message);
      return true;
    } catch (error) {
      console.error('Error sending message:', error);
      return false;
    }
  }

  /**
   * Send ride offer to driver
   */
  async sendRideOffer(driver, ride) {
    const phone = driver.whatsapp_number || driver.phone;

    const message = `🚗 *طلب جديد!*\n\n` +
      `📍 من: ${ride.origin_address}\n` +
      `📍 إلى: ${ride.destination_address}\n` +
      `📏 المسافة: ${ride.distance_km} كم\n` +
      `⏱️ الوقت: ${ride.estimated_minutes} دقيقة\n` +
      `💰 السعر: ${ride.estimated_price} ج.م\n\n` +
      `للقبول أرسل: قبول ${ride.id.substring(0, 8)}\n` +
      `للرفض أرسل: رفض ${ride.id.substring(0, 8)}`;

    return await this.sendMessage(phone, message);
  }

  /**
   * Send shipment offer to driver
   */
  async sendShipmentOffer(driver, shipment) {
    const phone = driver.whatsapp_number || driver.phone;

    const message = `📦 *طلب شحن جديد!*\n\n` +
      `📍 الاستلام: ${shipment.pickup_address}\n` +
      `📍 التسليم: ${shipment.delivery_address}\n` +
      `📏 المسافة: ${shipment.distance_km} كم\n` +
      `💰 السعر: ${shipment.price} ج.م\n` +
      (shipment.is_cod ? `💵 COD: ${shipment.cod_amount} ج.م\n` : '') +
      `\nللقبول أرسل: قبول ${shipment.id.substring(0, 8)}`;

    return await this.sendMessage(phone, message);
  }

  /**
   * Broadcast message to all online drivers
   */
  async broadcastToDrivers(message) {
    const { data: drivers } = await this.supabase
      .from('drivers')
      .select('phone, whatsapp_number')
      .eq('is_online', true)
      .eq('is_verified', true);

    const results = [];
    for (const driver of drivers || []) {
      const phone = driver.whatsapp_number || driver.phone;
      const result = await this.sendMessage(phone, message);
      results.push({ phone, success: result });
    }

    return results;
  }

  /**
   * Cleanup
   */
  async destroy() {
    if (this.client) {
      await this.client.destroy();
      this.client = null;
      this.isReady = false;
    }
  }
}

module.exports = new WhatsAppService();
