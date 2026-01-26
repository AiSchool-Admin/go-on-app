-- Server-Side Pricing Tables
-- Supports dual pricing mode: client-side and server-side

-- =====================================================
-- 1. PRICE CACHE TABLE (Server-fetched prices)
-- =====================================================

CREATE TABLE IF NOT EXISTS price_cache (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),

  -- Location info
  origin_lat DECIMAL(10, 7) NOT NULL,
  origin_lng DECIMAL(10, 7) NOT NULL,
  dest_lat DECIMAL(10, 7) NOT NULL,
  dest_lng DECIMAL(10, 7) NOT NULL,
  origin_address TEXT,
  dest_address TEXT,

  -- Grid cell for fast lookup (rounded to ~500m precision)
  origin_grid TEXT GENERATED ALWAYS AS (
    ROUND(origin_lat * 200)::TEXT || ',' || ROUND(origin_lng * 200)::TEXT
  ) STORED,
  dest_grid TEXT GENERATED ALWAYS AS (
    ROUND(dest_lat * 200)::TEXT || ',' || ROUND(dest_lng * 200)::TEXT
  ) STORED,

  -- Distance info
  distance_km DECIMAL(6, 2),
  estimated_minutes INTEGER,

  -- Prices per app (JSONB for flexibility)
  prices JSONB NOT NULL DEFAULT '{}'::JSONB,
  -- Example: {
  --   "uber": {"price": 65, "eta": 3, "vehicle_type": "UberX", "surge": 1.0},
  --   "careem": {"price": 58, "eta": 5, "vehicle_type": "Go", "surge": 1.2},
  --   "indriver": {"price": 45, "eta": 8, "vehicle_type": "Economy"},
  --   "bolt": {"price": 52, "eta": 4, "vehicle_type": "Bolt"},
  --   "didi": {"price": 48, "eta": 6, "vehicle_type": "Express"}
  -- }

  -- Best price info (computed)
  best_app TEXT,
  best_price DECIMAL(10, 2),

  -- Time context (for surge prediction)
  day_of_week INTEGER CHECK (day_of_week BETWEEN 0 AND 6),
  hour_of_day INTEGER CHECK (hour_of_day BETWEEN 0 AND 23),
  is_rush_hour BOOLEAN DEFAULT FALSE,

  -- Metadata
  fetch_source TEXT DEFAULT 'server' CHECK (fetch_source IN ('server', 'client', 'estimated')),
  fetch_duration_ms INTEGER,
  fetched_at TIMESTAMPTZ DEFAULT NOW(),
  expires_at TIMESTAMPTZ DEFAULT (NOW() + INTERVAL '5 minutes'),

  -- Request tracking
  request_id UUID,
  requested_by UUID REFERENCES profiles(id)
);

-- Indexes for fast lookup
CREATE INDEX IF NOT EXISTS idx_price_cache_grid ON price_cache(origin_grid, dest_grid);
CREATE INDEX IF NOT EXISTS idx_price_cache_expires ON price_cache(expires_at) WHERE expires_at > NOW();
CREATE INDEX IF NOT EXISTS idx_price_cache_fetched ON price_cache(fetched_at DESC);
CREATE INDEX IF NOT EXISTS idx_price_cache_coords ON price_cache(origin_lat, origin_lng, dest_lat, dest_lng);

-- =====================================================
-- 2. PRICE REQUESTS TABLE (Queue for server fetching)
-- =====================================================

CREATE TABLE IF NOT EXISTS price_requests (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),

  -- Location
  origin_lat DECIMAL(10, 7) NOT NULL,
  origin_lng DECIMAL(10, 7) NOT NULL,
  dest_lat DECIMAL(10, 7) NOT NULL,
  dest_lng DECIMAL(10, 7) NOT NULL,
  origin_address TEXT,
  dest_address TEXT,

  -- Request status
  status TEXT DEFAULT 'pending' CHECK (status IN ('pending', 'processing', 'completed', 'failed', 'expired')),
  priority INTEGER DEFAULT 5 CHECK (priority BETWEEN 1 AND 10), -- 1 = highest

  -- Apps to fetch
  apps_to_fetch TEXT[] DEFAULT ARRAY['uber', 'careem', 'indriver', 'bolt', 'didi'],
  apps_completed TEXT[] DEFAULT ARRAY[]::TEXT[],
  apps_failed TEXT[] DEFAULT ARRAY[]::TEXT[],

  -- Result
  result_cache_id UUID REFERENCES price_cache(id),
  error_message TEXT,

  -- Timing
  created_at TIMESTAMPTZ DEFAULT NOW(),
  started_at TIMESTAMPTZ,
  completed_at TIMESTAMPTZ,
  expires_at TIMESTAMPTZ DEFAULT (NOW() + INTERVAL '2 minutes'),

  -- Requester
  requested_by UUID REFERENCES profiles(id),
  client_ip TEXT
);

CREATE INDEX IF NOT EXISTS idx_price_requests_status ON price_requests(status) WHERE status IN ('pending', 'processing');
CREATE INDEX IF NOT EXISTS idx_price_requests_created ON price_requests(created_at DESC);
CREATE INDEX IF NOT EXISTS idx_price_requests_priority ON price_requests(priority, created_at) WHERE status = 'pending';

-- =====================================================
-- 3. PRICING CONFIG TABLE
-- =====================================================

CREATE TABLE IF NOT EXISTS pricing_config (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),

  -- Mode configuration
  default_mode TEXT DEFAULT 'client' CHECK (default_mode IN ('client', 'server', 'hybrid')),
  fallback_mode TEXT DEFAULT 'client' CHECK (fallback_mode IN ('client', 'estimated')),

  -- Server pricing settings
  server_enabled BOOLEAN DEFAULT TRUE,
  server_timeout_ms INTEGER DEFAULT 30000,
  cache_ttl_minutes INTEGER DEFAULT 5,
  max_concurrent_requests INTEGER DEFAULT 10,

  -- Client pricing settings
  client_enabled BOOLEAN DEFAULT TRUE,
  client_timeout_ms INTEGER DEFAULT 45000,

  -- Surge thresholds
  surge_warning_threshold DECIMAL(3, 2) DEFAULT 1.5,
  surge_block_threshold DECIMAL(3, 2) DEFAULT 3.0,

  -- Rate limiting
  requests_per_minute_per_user INTEGER DEFAULT 10,
  requests_per_minute_global INTEGER DEFAULT 100,

  -- Feature flags
  show_estimated_prices BOOLEAN DEFAULT TRUE,
  show_price_history BOOLEAN DEFAULT TRUE,
  enable_price_alerts BOOLEAN DEFAULT FALSE,

  updated_at TIMESTAMPTZ DEFAULT NOW(),
  updated_by UUID REFERENCES profiles(id)
);

-- Insert default config
INSERT INTO pricing_config (id) VALUES (gen_random_uuid())
ON CONFLICT DO NOTHING;

-- =====================================================
-- 4. APP PRICING STATS (For estimated prices)
-- =====================================================

CREATE TABLE IF NOT EXISTS app_pricing_stats (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  app_name TEXT NOT NULL UNIQUE,

  -- Base pricing formula coefficients
  base_fare DECIMAL(10, 2) NOT NULL,
  per_km_rate DECIMAL(10, 2) NOT NULL,
  per_minute_rate DECIMAL(10, 2) DEFAULT 0,
  minimum_fare DECIMAL(10, 2) NOT NULL,
  booking_fee DECIMAL(10, 2) DEFAULT 0,

  -- Surge patterns (by hour)
  surge_by_hour JSONB DEFAULT '{
    "0": 1.0, "1": 1.0, "2": 1.0, "3": 1.0, "4": 1.0, "5": 1.0,
    "6": 1.1, "7": 1.3, "8": 1.5, "9": 1.3, "10": 1.0, "11": 1.0,
    "12": 1.1, "13": 1.0, "14": 1.0, "15": 1.1, "16": 1.2, "17": 1.4,
    "18": 1.5, "19": 1.4, "20": 1.2, "21": 1.1, "22": 1.0, "23": 1.0
  }'::JSONB,

  -- Reliability score (0-100)
  reliability_score INTEGER DEFAULT 80,
  avg_eta_minutes INTEGER DEFAULT 5,

  -- Package name
  package_name TEXT NOT NULL,

  -- Stats
  total_fetches INTEGER DEFAULT 0,
  successful_fetches INTEGER DEFAULT 0,
  last_successful_fetch TIMESTAMPTZ,
  avg_fetch_time_ms INTEGER,

  updated_at TIMESTAMPTZ DEFAULT NOW()
);

-- Insert default app stats
INSERT INTO app_pricing_stats (app_name, base_fare, per_km_rate, per_minute_rate, minimum_fare, booking_fee, package_name, reliability_score) VALUES
  ('uber', 15, 4.5, 0.75, 25, 5, 'com.ubercab', 95),
  ('careem', 12, 4.0, 0.5, 20, 3, 'com.careem.acma', 90),
  ('indriver', 10, 3.5, 0, 15, 0, 'sinet.startup.inDriver', 85),
  ('bolt', 12, 3.8, 0.4, 18, 2, 'ee.mtakso.client', 88),
  ('didi', 11, 3.6, 0.35, 18, 2, 'com.didiglobal.passenger', 82)
ON CONFLICT (app_name) DO NOTHING;

-- =====================================================
-- 5. HELPER FUNCTIONS
-- =====================================================

-- Function to find cached price
CREATE OR REPLACE FUNCTION find_cached_price(
  p_origin_lat DECIMAL,
  p_origin_lng DECIMAL,
  p_dest_lat DECIMAL,
  p_dest_lng DECIMAL,
  p_max_distance_meters INTEGER DEFAULT 500
) RETURNS TABLE (
  cache_id UUID,
  prices JSONB,
  best_app TEXT,
  best_price DECIMAL,
  fetched_at TIMESTAMPTZ,
  is_fresh BOOLEAN
) AS $$
BEGIN
  RETURN QUERY
  SELECT
    pc.id,
    pc.prices,
    pc.best_app,
    pc.best_price,
    pc.fetched_at,
    pc.expires_at > NOW() as is_fresh
  FROM price_cache pc
  WHERE
    -- Within ~500m of origin
    ABS(pc.origin_lat - p_origin_lat) < 0.005
    AND ABS(pc.origin_lng - p_origin_lng) < 0.005
    -- Within ~500m of destination
    AND ABS(pc.dest_lat - p_dest_lat) < 0.005
    AND ABS(pc.dest_lng - p_dest_lng) < 0.005
    -- Not expired
    AND pc.expires_at > NOW()
  ORDER BY pc.fetched_at DESC
  LIMIT 1;
END;
$$ LANGUAGE plpgsql;

-- Function to estimate price
CREATE OR REPLACE FUNCTION estimate_price(
  p_app_name TEXT,
  p_distance_km DECIMAL,
  p_duration_minutes INTEGER DEFAULT NULL
) RETURNS JSONB AS $$
DECLARE
  v_stats app_pricing_stats%ROWTYPE;
  v_hour INTEGER;
  v_surge DECIMAL;
  v_price DECIMAL;
BEGIN
  SELECT * INTO v_stats FROM app_pricing_stats WHERE app_name = p_app_name;

  IF NOT FOUND THEN
    RETURN NULL;
  END IF;

  v_hour := EXTRACT(HOUR FROM NOW());
  v_surge := COALESCE((v_stats.surge_by_hour->>v_hour::TEXT)::DECIMAL, 1.0);

  v_price := v_stats.base_fare
    + (p_distance_km * v_stats.per_km_rate)
    + (COALESCE(p_duration_minutes, p_distance_km * 2) * v_stats.per_minute_rate)
    + v_stats.booking_fee;

  v_price := v_price * v_surge;
  v_price := GREATEST(v_price, v_stats.minimum_fare);

  RETURN jsonb_build_object(
    'app', p_app_name,
    'price', ROUND(v_price),
    'surge', v_surge,
    'is_estimated', true,
    'eta', v_stats.avg_eta_minutes,
    'reliability', v_stats.reliability_score
  );
END;
$$ LANGUAGE plpgsql;

-- Function to get all estimated prices
CREATE OR REPLACE FUNCTION get_estimated_prices(
  p_distance_km DECIMAL,
  p_duration_minutes INTEGER DEFAULT NULL
) RETURNS JSONB AS $$
DECLARE
  v_result JSONB := '{}'::JSONB;
  v_app_stats app_pricing_stats;
  v_price_info JSONB;
BEGIN
  FOR v_app_stats IN SELECT * FROM app_pricing_stats ORDER BY reliability_score DESC
  LOOP
    v_price_info := estimate_price(v_app_stats.app_name, p_distance_km, p_duration_minutes);
    v_result := v_result || jsonb_build_object(v_app_stats.app_name, v_price_info);
  END LOOP;

  RETURN v_result;
END;
$$ LANGUAGE plpgsql;

-- =====================================================
-- 6. RLS POLICIES
-- =====================================================

ALTER TABLE price_cache ENABLE ROW LEVEL SECURITY;
ALTER TABLE price_requests ENABLE ROW LEVEL SECURITY;
ALTER TABLE pricing_config ENABLE ROW LEVEL SECURITY;
ALTER TABLE app_pricing_stats ENABLE ROW LEVEL SECURITY;

-- Price cache: public read, server write
CREATE POLICY "Anyone can read price cache" ON price_cache
  FOR SELECT USING (TRUE);

CREATE POLICY "Service role can insert price cache" ON price_cache
  FOR INSERT WITH CHECK (TRUE);

-- Price requests: users can create, read own
CREATE POLICY "Users can create price requests" ON price_requests
  FOR INSERT WITH CHECK (TRUE);

CREATE POLICY "Users can view own requests" ON price_requests
  FOR SELECT USING (requested_by = auth.uid() OR requested_by IS NULL);

-- Pricing config: public read
CREATE POLICY "Anyone can read pricing config" ON pricing_config
  FOR SELECT USING (TRUE);

-- App pricing stats: public read
CREATE POLICY "Anyone can read app stats" ON app_pricing_stats
  FOR SELECT USING (TRUE);

-- =====================================================
-- 7. CLEANUP FUNCTION (Run periodically)
-- =====================================================

CREATE OR REPLACE FUNCTION cleanup_expired_prices() RETURNS void AS $$
BEGIN
  -- Delete expired cache entries (older than 1 hour)
  DELETE FROM price_cache WHERE expires_at < NOW() - INTERVAL '1 hour';

  -- Delete old requests (older than 1 day)
  DELETE FROM price_requests WHERE created_at < NOW() - INTERVAL '1 day';
END;
$$ LANGUAGE plpgsql;
