/**
 * GO-ON Backend Services
 * Railway deployment entry point
 */

require('dotenv').config();
const express = require('express');
const cors = require('cors');
const helmet = require('helmet');
const morgan = require('morgan');
const multer = require('multer');

// Services
const ocrService = require('./services/ocr/ocrService');
const whatsappService = require('./services/whatsapp/whatsappService');
const notificationService = require('./services/notifications/notificationService');
const pricingService = require('./services/pricing/pricingService');

const app = express();
const PORT = process.env.PORT || 3000;

// Multer for file uploads
const upload = multer({
  storage: multer.memoryStorage(),
  limits: { fileSize: 10 * 1024 * 1024 }, // 10MB
});

// Middleware
app.use(helmet());
app.use(cors());
app.use(morgan('combined'));
app.use(express.json());

// Initialize services
(async () => {
  try {
    // Initialize notification service
    notificationService.initialize();
    console.log('✓ Notification service initialized');

    // Initialize OCR service (lazy - on first use)
    console.log('✓ OCR service ready (lazy initialization)');

    // Initialize pricing service
    await pricingService.initialize();

    // Initialize WhatsApp service if credentials are available
    if (process.env.WHATSAPP_ENABLED === 'true') {
      await whatsappService.initialize();
      console.log('✓ WhatsApp service initialized');
    } else {
      console.log('⚠ WhatsApp service disabled');
    }
  } catch (error) {
    console.error('Service initialization error:', error);
  }
})();

// Health check
app.get('/health', (req, res) => {
  res.json({
    status: 'healthy',
    service: 'go-on-backend',
    version: '1.0.0',
    timestamp: new Date().toISOString(),
    services: {
      ocr: 'ready',
      whatsapp: whatsappService.isReady ? 'connected' : 'disconnected',
      notifications: notificationService.isInitialized ? 'ready' : 'disabled',
      pricing: pricingService.isInitialized ? 'ready' : 'initializing',
    },
  });
});

// API Routes
app.get('/api', (req, res) => {
  res.json({
    message: 'GO-ON API',
    endpoints: {
      health: '/health',
      pricing: {
        getPrices: 'POST /api/pricing/prices',
        saveCache: 'POST /api/pricing/cache',
        getConfig: 'GET /api/pricing/config',
        getApps: 'GET /api/pricing/apps',
        getEstimated: 'POST /api/pricing/estimated',
      },
      ocr: {
        extract: 'POST /api/ocr/extract-prices',
        batch: 'POST /api/ocr/batch-extract',
      },
      whatsapp: {
        send: 'POST /api/whatsapp/send',
        broadcast: 'POST /api/whatsapp/broadcast',
        rideOffer: 'POST /api/whatsapp/ride-offer',
      },
      notifications: {
        send: 'POST /api/notifications/send',
        sendToUser: 'POST /api/notifications/send-to-user',
        rideStatus: 'POST /api/notifications/ride-status',
        shipmentStatus: 'POST /api/notifications/shipment-status',
      },
    },
  });
});

// ==================== OCR Routes ====================

/**
 * Extract prices from a screenshot
 */
app.post('/api/ocr/extract-prices', upload.single('image'), async (req, res) => {
  try {
    if (!req.file) {
      return res.status(400).json({ error: 'No image provided' });
    }

    const appName = req.body.app || 'unknown';
    const result = await ocrService.extractPricesFromScreenshot(
      req.file.buffer,
      appName
    );

    res.json(result);
  } catch (error) {
    console.error('OCR error:', error);
    res.status(500).json({ error: error.message });
  }
});

/**
 * Extract prices from multiple screenshots
 */
app.post('/api/ocr/batch-extract', upload.array('images', 5), async (req, res) => {
  try {
    if (!req.files || req.files.length === 0) {
      return res.status(400).json({ error: 'No images provided' });
    }

    const apps = req.body.apps ? req.body.apps.split(',') : [];
    const screenshots = req.files.map((file, index) => ({
      buffer: file.buffer,
      app: apps[index] || 'unknown',
    }));

    const results = await ocrService.batchExtract(screenshots);
    const comparison = ocrService.comparePrices(results);

    res.json({
      success: true,
      results,
      comparison,
      bestPrice: comparison[0] || null,
    });
  } catch (error) {
    console.error('Batch OCR error:', error);
    res.status(500).json({ error: error.message });
  }
});

// ==================== WhatsApp Routes ====================

/**
 * Send WhatsApp message
 */
app.post('/api/whatsapp/send', async (req, res) => {
  try {
    const { phone, message } = req.body;

    if (!phone || !message) {
      return res.status(400).json({ error: 'Phone and message are required' });
    }

    const success = await whatsappService.sendMessage(phone, message);
    res.json({ success });
  } catch (error) {
    console.error('WhatsApp send error:', error);
    res.status(500).json({ error: error.message });
  }
});

/**
 * Broadcast message to all online drivers
 */
app.post('/api/whatsapp/broadcast', async (req, res) => {
  try {
    const { message } = req.body;

    if (!message) {
      return res.status(400).json({ error: 'Message is required' });
    }

    const results = await whatsappService.broadcastToDrivers(message);
    res.json({
      success: true,
      sent: results.filter(r => r.success).length,
      failed: results.filter(r => !r.success).length,
      results,
    });
  } catch (error) {
    console.error('WhatsApp broadcast error:', error);
    res.status(500).json({ error: error.message });
  }
});

/**
 * Send ride offer to driver via WhatsApp
 */
app.post('/api/whatsapp/ride-offer', async (req, res) => {
  try {
    const { driver, ride } = req.body;

    if (!driver || !ride) {
      return res.status(400).json({ error: 'Driver and ride data are required' });
    }

    const success = await whatsappService.sendRideOffer(driver, ride);
    res.json({ success });
  } catch (error) {
    console.error('WhatsApp ride offer error:', error);
    res.status(500).json({ error: error.message });
  }
});

/**
 * Send shipment offer to driver via WhatsApp
 */
app.post('/api/whatsapp/shipment-offer', async (req, res) => {
  try {
    const { driver, shipment } = req.body;

    if (!driver || !shipment) {
      return res.status(400).json({ error: 'Driver and shipment data are required' });
    }

    const success = await whatsappService.sendShipmentOffer(driver, shipment);
    res.json({ success });
  } catch (error) {
    console.error('WhatsApp shipment offer error:', error);
    res.status(500).json({ error: error.message });
  }
});

// ==================== Notification Routes ====================

/**
 * Send notification to a device
 */
app.post('/api/notifications/send', async (req, res) => {
  try {
    const { fcmToken, title, body, data } = req.body;

    if (!fcmToken || !title || !body) {
      return res.status(400).json({ error: 'fcmToken, title, and body are required' });
    }

    const result = await notificationService.sendToDevice(
      fcmToken,
      { title, body },
      data || {}
    );

    res.json(result);
  } catch (error) {
    console.error('Notification send error:', error);
    res.status(500).json({ error: error.message });
  }
});

/**
 * Send notification to a user
 */
app.post('/api/notifications/send-to-user', async (req, res) => {
  try {
    const { userId, title, body, data } = req.body;

    if (!userId || !title || !body) {
      return res.status(400).json({ error: 'userId, title, and body are required' });
    }

    const result = await notificationService.sendToUser(
      userId,
      { title, body },
      data || {}
    );

    res.json(result);
  } catch (error) {
    console.error('Notification to user error:', error);
    res.status(500).json({ error: error.message });
  }
});

/**
 * Notify about ride status change
 */
app.post('/api/notifications/ride-status', async (req, res) => {
  try {
    const { ride, status } = req.body;

    if (!ride || !status) {
      return res.status(400).json({ error: 'Ride and status are required' });
    }

    const result = await notificationService.notifyRideStatus(ride, status);
    res.json(result);
  } catch (error) {
    console.error('Ride status notification error:', error);
    res.status(500).json({ error: error.message });
  }
});

/**
 * Notify about shipment status change
 */
app.post('/api/notifications/shipment-status', async (req, res) => {
  try {
    const { shipment, status } = req.body;

    if (!shipment || !status) {
      return res.status(400).json({ error: 'Shipment and status are required' });
    }

    const result = await notificationService.notifyShipmentStatus(shipment, status);
    res.json(result);
  } catch (error) {
    console.error('Shipment status notification error:', error);
    res.status(500).json({ error: error.message });
  }
});

/**
 * Send promotional notification
 */
app.post('/api/notifications/promotion', async (req, res) => {
  try {
    const { userIds, title, body, promoData } = req.body;

    if (!userIds || !title || !body) {
      return res.status(400).json({ error: 'userIds, title, and body are required' });
    }

    const results = await notificationService.sendPromotion(
      userIds,
      title,
      body,
      promoData || {}
    );

    res.json({
      success: true,
      sent: results.filter(r => r.success).length,
      failed: results.filter(r => !r.success).length,
      results,
    });
  } catch (error) {
    console.error('Promotion notification error:', error);
    res.status(500).json({ error: error.message });
  }
});

// ==================== Pricing Routes ====================

/**
 * Get prices for a route
 * Supports: server, client, estimated, auto modes
 */
app.post('/api/pricing/prices', async (req, res) => {
  try {
    const {
      originLat,
      originLng,
      destLat,
      destLng,
      originAddress,
      destAddress,
      mode,
      forceRefresh,
    } = req.body;

    // Validate required fields
    if (!originLat || !originLng || !destLat || !destLng) {
      return res.status(400).json({
        error: 'Missing required coordinates',
        required: ['originLat', 'originLng', 'destLat', 'destLng'],
      });
    }

    const result = await pricingService.getPrices({
      originLat: parseFloat(originLat),
      originLng: parseFloat(originLng),
      destLat: parseFloat(destLat),
      destLng: parseFloat(destLng),
      originAddress,
      destAddress,
      mode: mode || 'auto',
      forceRefresh: forceRefresh === true,
    });

    res.json(result);
  } catch (error) {
    console.error('Pricing error:', error);
    res.status(500).json({ error: error.message });
  }
});

/**
 * Save prices to cache (from client or emulator)
 */
app.post('/api/pricing/cache', async (req, res) => {
  try {
    const {
      originLat,
      originLng,
      destLat,
      destLng,
      originAddress,
      destAddress,
      distanceKm,
      estimatedMinutes,
      prices,
      fetchSource,
      fetchDurationMs,
      requestId,
      userId,
    } = req.body;

    // Validate required fields
    if (!originLat || !originLng || !destLat || !destLng || !prices) {
      return res.status(400).json({
        error: 'Missing required fields',
        required: ['originLat', 'originLng', 'destLat', 'destLng', 'prices'],
      });
    }

    const result = await pricingService.savePricesToCache({
      originLat: parseFloat(originLat),
      originLng: parseFloat(originLng),
      destLat: parseFloat(destLat),
      destLng: parseFloat(destLng),
      originAddress,
      destAddress,
      distanceKm: distanceKm ? parseFloat(distanceKm) : null,
      estimatedMinutes: estimatedMinutes ? parseInt(estimatedMinutes) : null,
      prices,
      fetchSource: fetchSource || 'client',
      fetchDurationMs: fetchDurationMs ? parseInt(fetchDurationMs) : null,
      requestId,
      userId,
    });

    res.json(result);
  } catch (error) {
    console.error('Cache save error:', error);
    res.status(500).json({ error: error.message });
  }
});

/**
 * Get estimated prices only (fast, no real fetching)
 */
app.post('/api/pricing/estimated', async (req, res) => {
  try {
    const { originLat, originLng, destLat, destLng } = req.body;

    if (!originLat || !originLng || !destLat || !destLng) {
      return res.status(400).json({
        error: 'Missing required coordinates',
      });
    }

    const result = await pricingService.getEstimatedPrices(
      parseFloat(originLat),
      parseFloat(originLng),
      parseFloat(destLat),
      parseFloat(destLng)
    );

    res.json(result);
  } catch (error) {
    console.error('Estimated prices error:', error);
    res.status(500).json({ error: error.message });
  }
});

/**
 * Get pricing configuration
 */
app.get('/api/pricing/config', async (req, res) => {
  try {
    const config = await pricingService.getConfig();
    res.json({ success: true, config });
  } catch (error) {
    console.error('Config error:', error);
    res.status(500).json({ error: error.message });
  }
});

/**
 * Get list of supported apps
 */
app.get('/api/pricing/apps', (req, res) => {
  try {
    const apps = pricingService.getAppList();
    res.json({ success: true, apps });
  } catch (error) {
    console.error('Apps list error:', error);
    res.status(500).json({ error: error.message });
  }
});

// Error handling
app.use((err, req, res, next) => {
  console.error(err.stack);
  res.status(500).json({
    error: 'Internal Server Error',
    message: process.env.NODE_ENV === 'development' ? err.message : undefined,
  });
});

// 404 handler
app.use((req, res) => {
  res.status(404).json({ error: 'Not Found' });
});

// Graceful shutdown
process.on('SIGTERM', async () => {
  console.log('SIGTERM received, shutting down...');
  await ocrService.terminate();
  await whatsappService.destroy();
  process.exit(0);
});

// Start server
app.listen(PORT, () => {
  console.log(`🚀 GO-ON Backend running on port ${PORT}`);
  console.log(`📍 Health check: http://localhost:${PORT}/health`);
  console.log(`📖 API docs: http://localhost:${PORT}/api`);
});
