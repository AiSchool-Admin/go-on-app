/**
 * OCR Service for extracting prices from screenshots
 * Uses Tesseract.js for optical character recognition
 */

const Tesseract = require('tesseract.js');
const sharp = require('sharp');

// Price patterns for different apps
const PRICE_PATTERNS = {
  // Egyptian Pound patterns
  EGP: [
    /(\d{1,4}(?:[.,]\d{2})?)\s*(?:ج\.م|ج\.م\.|جنيه|EGP|LE)/gi,
    /(?:ج\.م|جنيه|EGP|LE)\s*(\d{1,4}(?:[.,]\d{2})?)/gi,
    /(\d{2,4})\s*(?:-|–)\s*(\d{2,4})/g, // Range: 50-60
  ],
  // Generic number patterns
  NUMBERS: [
    /\b(\d{2,4})\b/g,
  ],
};

// App-specific extraction strategies
const APP_STRATEGIES = {
  uber: {
    priceRegions: [
      { top: 0.4, left: 0.5, width: 0.4, height: 0.15 }, // Main price area
      { top: 0.55, left: 0.5, width: 0.4, height: 0.15 }, // Secondary price
    ],
    priceFormat: 'right-aligned',
  },
  careem: {
    priceRegions: [
      { top: 0.45, left: 0.5, width: 0.45, height: 0.2 },
    ],
    priceFormat: 'center-aligned',
  },
  indriver: {
    priceRegions: [
      { top: 0.35, left: 0.3, width: 0.4, height: 0.2 },
    ],
    priceFormat: 'center-aligned',
  },
  didi: {
    priceRegions: [
      { top: 0.4, left: 0.4, width: 0.5, height: 0.2 },
    ],
    priceFormat: 'right-aligned',
  },
};

class OCRService {
  constructor() {
    this.worker = null;
    this.isInitialized = false;
  }

  /**
   * Initialize Tesseract worker
   */
  async initialize() {
    if (this.isInitialized) return;

    this.worker = await Tesseract.createWorker('ara+eng');
    this.isInitialized = true;
    console.log('OCR Service initialized');
  }

  /**
   * Preprocess image for better OCR results
   */
  async preprocessImage(imageBuffer) {
    try {
      return await sharp(imageBuffer)
        .greyscale()
        .normalize()
        .sharpen()
        .threshold(128)
        .toBuffer();
    } catch (error) {
      console.error('Image preprocessing error:', error);
      return imageBuffer;
    }
  }

  /**
   * Extract region from image
   */
  async extractRegion(imageBuffer, region) {
    try {
      const metadata = await sharp(imageBuffer).metadata();
      const { width, height } = metadata;

      const left = Math.floor(region.left * width);
      const top = Math.floor(region.top * height);
      const extractWidth = Math.floor(region.width * width);
      const extractHeight = Math.floor(region.height * height);

      return await sharp(imageBuffer)
        .extract({
          left,
          top,
          width: extractWidth,
          height: extractHeight,
        })
        .toBuffer();
    } catch (error) {
      console.error('Region extraction error:', error);
      return imageBuffer;
    }
  }

  /**
   * Extract text from image buffer
   */
  async extractText(imageBuffer) {
    if (!this.isInitialized) {
      await this.initialize();
    }

    const preprocessed = await this.preprocessImage(imageBuffer);
    const { data: { text } } = await this.worker.recognize(preprocessed);
    return text;
  }

  /**
   * Parse prices from text
   */
  parsePrices(text) {
    const prices = [];
    const seen = new Set();

    // Try EGP patterns first
    for (const pattern of PRICE_PATTERNS.EGP) {
      const matches = text.matchAll(pattern);
      for (const match of matches) {
        const price = parseFloat(match[1].replace(',', '.'));
        if (!isNaN(price) && price > 10 && price < 5000 && !seen.has(price)) {
          seen.add(price);
          prices.push({
            value: price,
            currency: 'EGP',
            raw: match[0],
          });
        }
      }
    }

    // If no EGP prices found, try generic numbers
    if (prices.length === 0) {
      for (const pattern of PRICE_PATTERNS.NUMBERS) {
        const matches = text.matchAll(pattern);
        for (const match of matches) {
          const price = parseFloat(match[1]);
          if (!isNaN(price) && price >= 15 && price <= 2000 && !seen.has(price)) {
            seen.add(price);
            prices.push({
              value: price,
              currency: 'EGP',
              raw: match[0],
              uncertain: true,
            });
          }
        }
      }
    }

    return prices.sort((a, b) => a.value - b.value);
  }

  /**
   * Extract prices from screenshot
   */
  async extractPricesFromScreenshot(imageBuffer, appName = 'unknown') {
    try {
      if (!this.isInitialized) {
        await this.initialize();
      }

      const strategy = APP_STRATEGIES[appName.toLowerCase()];
      let allPrices = [];

      if (strategy && strategy.priceRegions) {
        // Extract from specific regions
        for (const region of strategy.priceRegions) {
          const regionBuffer = await this.extractRegion(imageBuffer, region);
          const text = await this.extractText(regionBuffer);
          const prices = this.parsePrices(text);
          allPrices.push(...prices);
        }
      } else {
        // Full image extraction
        const text = await this.extractText(imageBuffer);
        allPrices = this.parsePrices(text);
      }

      // Remove duplicates and sort
      const uniquePrices = [];
      const seen = new Set();
      for (const price of allPrices) {
        if (!seen.has(price.value)) {
          seen.add(price.value);
          uniquePrices.push(price);
        }
      }

      return {
        success: true,
        app: appName,
        prices: uniquePrices.sort((a, b) => a.value - b.value),
        timestamp: new Date().toISOString(),
      };
    } catch (error) {
      console.error('OCR extraction error:', error);
      return {
        success: false,
        error: error.message,
        prices: [],
      };
    }
  }

  /**
   * Process multiple screenshots in batch
   */
  async batchExtract(screenshots) {
    const results = [];

    for (const screenshot of screenshots) {
      const result = await this.extractPricesFromScreenshot(
        screenshot.buffer,
        screenshot.app
      );
      results.push({
        ...result,
        app: screenshot.app,
      });
    }

    return results;
  }

  /**
   * Compare prices across apps
   */
  comparePrices(extractionResults) {
    const comparison = [];

    for (const result of extractionResults) {
      if (result.success && result.prices.length > 0) {
        const lowestPrice = result.prices[0];
        comparison.push({
          app: result.app,
          price: lowestPrice.value,
          currency: lowestPrice.currency,
          allPrices: result.prices.map(p => p.value),
        });
      }
    }

    return comparison.sort((a, b) => a.price - b.price);
  }

  /**
   * Cleanup resources
   */
  async terminate() {
    if (this.worker) {
      await this.worker.terminate();
      this.worker = null;
      this.isInitialized = false;
    }
  }
}

module.exports = new OCRService();
