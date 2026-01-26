/**
 * Pricing Service
 * Handles server-side price fetching and caching
 */

const { createClient } = require('@supabase/supabase-js');

// Supabase client
const supabase = createClient(
  process.env.SUPABASE_URL,
  process.env.SUPABASE_SERVICE_KEY
);

// App configurations
const APP_CONFIG = {
  uber: {
    name: 'Uber',
    packageName: 'com.ubercab',
    priority: 1,
    timeout: 15000,
  },
  careem: {
    name: 'Careem',
    packageName: 'com.careem.acma',
    priority: 2,
    timeout: 25000,
  },
  indriver: {
    name: 'InDriver',
    packageName: 'sinet.startup.inDriver',
    priority: 3,
    timeout: 20000,
  },
  bolt: {
    name: 'Bolt',
    packageName: 'ee.mtakso.client',
    priority: 4,
    timeout: 15000,
  },
  didi: {
    name: 'DiDi',
    packageName: 'com.didiglobal.passenger',
    priority: 5,
    timeout: 15000,
  },
};

class PricingService {
  constructor() {
    this.isInitialized = false;
    this.config = null;
    this.emulatorManager = null;
  }

  async initialize() {
    try {
      // Load pricing config from Supabase
      const { data, error } = await supabase
        .from('pricing_config')
        .select('*')
        .limit(1)
        .single();

      if (error) {
        console.warn('Could not load pricing config, using defaults:', error.message);
        this.config = this.getDefaultConfig();
      } else {
        this.config = data;
      }

      this.isInitialized = true;
      console.log('✓ Pricing service initialized');
      return true;
    } catch (error) {
      console.error('Failed to initialize pricing service:', error);
      this.config = this.getDefaultConfig();
      this.isInitialized = true;
      return false;
    }
  }

  getDefaultConfig() {
    return {
      default_mode: 'hybrid',
      fallback_mode: 'estimated',
      server_enabled: true,
      server_timeout_ms: 30000,
      cache_ttl_minutes: 5,
      client_enabled: true,
      client_timeout_ms: 45000,
      show_estimated_prices: true,
    };
  }

  /**
   * Get prices for a route
   * @param {Object} params - Route parameters
   * @returns {Object} Price result
   */
  async getPrices({
    originLat,
    originLng,
    destLat,
    destLng,
    originAddress,
    destAddress,
    mode = 'auto', // 'server', 'client', 'estimated', 'auto'
    userId = null,
    forceRefresh = false,
  }) {
    const startTime = Date.now();

    try {
      // Step 1: Check cache first (unless force refresh)
      if (!forceRefresh) {
        const cachedResult = await this.getCachedPrice(originLat, originLng, destLat, destLng);
        if (cachedResult && cachedResult.is_fresh) {
          return {
            success: true,
            source: 'cache',
            prices: cachedResult.prices,
            bestApp: cachedResult.best_app,
            bestPrice: cachedResult.best_price,
            fetchedAt: cachedResult.fetched_at,
            duration: Date.now() - startTime,
          };
        }
      }

      // Step 2: Determine mode
      const effectiveMode = mode === 'auto' ? this.determineMode() : mode;

      // Step 3: Get prices based on mode
      let result;

      switch (effectiveMode) {
        case 'server':
          result = await this.fetchFromServer(originLat, originLng, destLat, destLng, originAddress, destAddress);
          break;

        case 'estimated':
          result = await this.getEstimatedPrices(originLat, originLng, destLat, destLng);
          break;

        case 'client':
        default:
          // Return instruction for client to fetch
          result = {
            success: true,
            source: 'client_instruction',
            instruction: 'fetch_locally',
            apps: Object.keys(APP_CONFIG),
            estimated: await this.getEstimatedPrices(originLat, originLng, destLat, destLng),
          };
          break;
      }

      result.duration = Date.now() - startTime;
      return result;

    } catch (error) {
      console.error('getPrices error:', error);

      // Fallback to estimated prices
      const estimated = await this.getEstimatedPrices(originLat, originLng, destLat, destLng);
      return {
        success: true,
        source: 'estimated_fallback',
        prices: estimated.prices,
        bestApp: estimated.bestApp,
        bestPrice: estimated.bestPrice,
        error: error.message,
        duration: Date.now() - startTime,
      };
    }
  }

  /**
   * Determine which mode to use
   */
  determineMode() {
    if (!this.config) return 'estimated';

    if (this.config.server_enabled && this.emulatorManager?.isReady) {
      return 'server';
    }

    if (this.config.client_enabled) {
      return 'client';
    }

    return 'estimated';
  }

  /**
   * Check for cached price
   */
  async getCachedPrice(originLat, originLng, destLat, destLng) {
    try {
      const { data, error } = await supabase
        .rpc('find_cached_price', {
          p_origin_lat: originLat,
          p_origin_lng: originLng,
          p_dest_lat: destLat,
          p_dest_lng: destLng,
        });

      if (error) {
        console.error('Cache lookup error:', error);
        return null;
      }

      return data && data.length > 0 ? data[0] : null;
    } catch (error) {
      console.error('Cache lookup exception:', error);
      return null;
    }
  }

  /**
   * Get estimated prices using formulas
   */
  async getEstimatedPrices(originLat, originLng, destLat, destLng) {
    try {
      // Calculate distance
      const distanceKm = this.calculateDistance(originLat, originLng, destLat, destLng);
      const durationMinutes = Math.round(distanceKm * 2.5); // Rough estimate

      // Get estimated prices from Supabase function
      const { data, error } = await supabase
        .rpc('get_estimated_prices', {
          p_distance_km: distanceKm,
          p_duration_minutes: durationMinutes,
        });

      if (error) {
        console.error('Estimated prices error:', error);
        return this.calculateLocalEstimates(distanceKm, durationMinutes);
      }

      // Find best price
      const prices = data || {};
      let bestApp = null;
      let bestPrice = Infinity;

      for (const [app, info] of Object.entries(prices)) {
        if (info && info.price < bestPrice) {
          bestPrice = info.price;
          bestApp = app;
        }
      }

      return {
        success: true,
        source: 'estimated',
        prices,
        bestApp,
        bestPrice: bestPrice === Infinity ? null : bestPrice,
        distanceKm,
        durationMinutes,
      };
    } catch (error) {
      console.error('getEstimatedPrices error:', error);
      const distanceKm = this.calculateDistance(originLat, originLng, destLat, destLng);
      return this.calculateLocalEstimates(distanceKm, Math.round(distanceKm * 2.5));
    }
  }

  /**
   * Calculate local estimates (fallback)
   */
  calculateLocalEstimates(distanceKm, durationMinutes) {
    const hour = new Date().getHours();
    const isRushHour = (hour >= 7 && hour <= 9) || (hour >= 17 && hour <= 19);
    const surgeMultiplier = isRushHour ? 1.3 : 1.0;

    const prices = {};

    // Uber estimate
    prices.uber = {
      app: 'uber',
      price: Math.round((15 + distanceKm * 4.5 + durationMinutes * 0.75 + 5) * surgeMultiplier),
      surge: surgeMultiplier,
      is_estimated: true,
      eta: 3,
      reliability: 95,
    };

    // Careem estimate
    prices.careem = {
      app: 'careem',
      price: Math.round((12 + distanceKm * 4.0 + durationMinutes * 0.5 + 3) * surgeMultiplier),
      surge: surgeMultiplier,
      is_estimated: true,
      eta: 5,
      reliability: 90,
    };

    // InDriver estimate (usually cheapest)
    prices.indriver = {
      app: 'indriver',
      price: Math.round((10 + distanceKm * 3.5) * surgeMultiplier),
      surge: surgeMultiplier,
      is_estimated: true,
      eta: 8,
      reliability: 85,
    };

    // Bolt estimate
    prices.bolt = {
      app: 'bolt',
      price: Math.round((12 + distanceKm * 3.8 + durationMinutes * 0.4 + 2) * surgeMultiplier),
      surge: surgeMultiplier,
      is_estimated: true,
      eta: 4,
      reliability: 88,
    };

    // DiDi estimate
    prices.didi = {
      app: 'didi',
      price: Math.round((11 + distanceKm * 3.6 + durationMinutes * 0.35 + 2) * surgeMultiplier),
      surge: surgeMultiplier,
      is_estimated: true,
      eta: 6,
      reliability: 82,
    };

    // Find best
    let bestApp = 'indriver';
    let bestPrice = prices.indriver.price;

    for (const [app, info] of Object.entries(prices)) {
      if (info.price < bestPrice) {
        bestPrice = info.price;
        bestApp = app;
      }
    }

    return {
      success: true,
      source: 'estimated_local',
      prices,
      bestApp,
      bestPrice,
      distanceKm,
      durationMinutes,
    };
  }

  /**
   * Fetch prices from server (emulator-based)
   * This is a placeholder - actual implementation requires emulator setup
   */
  async fetchFromServer(originLat, originLng, destLat, destLng, originAddress, destAddress) {
    // Create a price request
    const requestId = crypto.randomUUID();

    const { data: request, error } = await supabase
      .from('price_requests')
      .insert({
        id: requestId,
        origin_lat: originLat,
        origin_lng: originLng,
        dest_lat: destLat,
        dest_lng: destLng,
        origin_address: originAddress,
        dest_address: destAddress,
        status: 'pending',
        apps_to_fetch: Object.keys(APP_CONFIG),
      })
      .select()
      .single();

    if (error) {
      console.error('Failed to create price request:', error);
      throw new Error('Failed to create price request');
    }

    // In a real implementation, this would:
    // 1. Queue the request for emulator processing
    // 2. Wait for results (with timeout)
    // 3. Return the fetched prices

    // For now, return estimated prices with server instruction
    const estimated = await this.getEstimatedPrices(originLat, originLng, destLat, destLng);

    return {
      success: true,
      source: 'server_pending',
      requestId,
      message: 'Price request queued for server processing',
      estimated: estimated.prices,
      bestApp: estimated.bestApp,
      bestPrice: estimated.bestPrice,
    };
  }

  /**
   * Save prices to cache (called by emulator or client)
   */
  async savePricesToCache({
    originLat,
    originLng,
    destLat,
    destLng,
    originAddress,
    destAddress,
    distanceKm,
    estimatedMinutes,
    prices,
    fetchSource = 'server',
    fetchDurationMs,
    requestId,
    userId,
  }) {
    try {
      // Calculate best price
      let bestApp = null;
      let bestPrice = Infinity;

      for (const [app, info] of Object.entries(prices)) {
        const price = typeof info === 'object' ? info.price : info;
        if (price && price < bestPrice) {
          bestPrice = price;
          bestApp = app;
        }
      }

      const now = new Date();
      const expiresAt = new Date(now.getTime() + (this.config?.cache_ttl_minutes || 5) * 60 * 1000);

      const { data, error } = await supabase
        .from('price_cache')
        .insert({
          origin_lat: originLat,
          origin_lng: originLng,
          dest_lat: destLat,
          dest_lng: destLng,
          origin_address: originAddress,
          dest_address: destAddress,
          distance_km: distanceKm,
          estimated_minutes: estimatedMinutes,
          prices,
          best_app: bestApp,
          best_price: bestPrice === Infinity ? null : bestPrice,
          day_of_week: now.getDay(),
          hour_of_day: now.getHours(),
          is_rush_hour: this.isRushHour(now),
          fetch_source: fetchSource,
          fetch_duration_ms: fetchDurationMs,
          fetched_at: now.toISOString(),
          expires_at: expiresAt.toISOString(),
          request_id: requestId,
          requested_by: userId,
        })
        .select()
        .single();

      if (error) {
        console.error('Failed to save price cache:', error);
        return { success: false, error: error.message };
      }

      // Update request status if exists
      if (requestId) {
        await supabase
          .from('price_requests')
          .update({
            status: 'completed',
            result_cache_id: data.id,
            completed_at: now.toISOString(),
          })
          .eq('id', requestId);
      }

      return {
        success: true,
        cacheId: data.id,
        bestApp,
        bestPrice,
      };
    } catch (error) {
      console.error('savePricesToCache error:', error);
      return { success: false, error: error.message };
    }
  }

  /**
   * Get pricing configuration
   */
  async getConfig() {
    if (!this.config) {
      await this.initialize();
    }
    return this.config;
  }

  /**
   * Get app list with metadata
   */
  getAppList() {
    return Object.entries(APP_CONFIG).map(([key, config]) => ({
      id: key,
      ...config,
    }));
  }

  /**
   * Calculate distance between two points (Haversine formula)
   */
  calculateDistance(lat1, lng1, lat2, lng2) {
    const R = 6371; // Earth's radius in km
    const dLat = this.toRad(lat2 - lat1);
    const dLng = this.toRad(lng2 - lng1);
    const a =
      Math.sin(dLat / 2) * Math.sin(dLat / 2) +
      Math.cos(this.toRad(lat1)) * Math.cos(this.toRad(lat2)) *
      Math.sin(dLng / 2) * Math.sin(dLng / 2);
    const c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
    return Math.round(R * c * 100) / 100; // Round to 2 decimal places
  }

  toRad(deg) {
    return deg * (Math.PI / 180);
  }

  isRushHour(date = new Date()) {
    const hour = date.getHours();
    const day = date.getDay();
    // Weekend
    if (day === 5 || day === 6) return false;
    // Rush hours: 7-9 AM, 5-7 PM
    return (hour >= 7 && hour <= 9) || (hour >= 17 && hour <= 19);
  }
}

module.exports = new PricingService();
