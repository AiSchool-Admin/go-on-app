/**
 * GO-ON Backend Services
 * Railway deployment entry point
 */

require('dotenv').config();
const express = require('express');
const cors = require('cors');
const helmet = require('helmet');
const morgan = require('morgan');

const app = express();
const PORT = process.env.PORT || 3000;

// Middleware
app.use(helmet());
app.use(cors());
app.use(morgan('combined'));
app.use(express.json());

// Health check
app.get('/health', (req, res) => {
  res.json({
    status: 'healthy',
    service: 'go-on-backend',
    version: '1.0.0',
    timestamp: new Date().toISOString(),
  });
});

// API Routes
app.get('/api', (req, res) => {
  res.json({
    message: 'GO-ON API',
    endpoints: {
      health: '/health',
      ocr: '/api/ocr',
      whatsapp: '/api/whatsapp',
      notifications: '/api/notifications',
    },
  });
});

// OCR Routes (placeholder)
app.post('/api/ocr/extract-prices', async (req, res) => {
  try {
    // TODO: Implement OCR price extraction
    res.json({
      success: true,
      message: 'OCR endpoint - coming soon',
      prices: [],
    });
  } catch (error) {
    res.status(500).json({ error: error.message });
  }
});

// WhatsApp Routes (placeholder)
app.post('/api/whatsapp/send', async (req, res) => {
  try {
    // TODO: Implement WhatsApp messaging
    res.json({
      success: true,
      message: 'WhatsApp endpoint - coming soon',
    });
  } catch (error) {
    res.status(500).json({ error: error.message });
  }
});

// Notifications Routes (placeholder)
app.post('/api/notifications/send', async (req, res) => {
  try {
    // TODO: Implement push notifications
    res.json({
      success: true,
      message: 'Notifications endpoint - coming soon',
    });
  } catch (error) {
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

// Start server
app.listen(PORT, () => {
  console.log(`🚀 GO-ON Backend running on port ${PORT}`);
  console.log(`📍 Health check: http://localhost:${PORT}/health`);
});
