# DATABASE_SCHEMA.md - Supabase PostgreSQL Schema

## 📊 Overview

GO-ON uses **Supabase** (PostgreSQL) as its primary database. This document describes the complete database schema.

---

## 🗄 Tables

### 1. profiles
User profiles (extends Supabase auth.users)

```sql
CREATE TABLE profiles (
  -- Primary Key (linked to auth.users)
  id UUID PRIMARY KEY REFERENCES auth.users(id) ON DELETE CASCADE,
  
  -- Basic Info
  email TEXT,
  phone TEXT NOT NULL,
  name TEXT NOT NULL,
  avatar_url TEXT,
  
  -- User Type
  user_type TEXT NOT NULL DEFAULT 'passenger' CHECK (user_type IN ('passenger', 'sender', 'driver', 'admin')),
  is_driver BOOLEAN DEFAULT FALSE,
  
  -- Preferences
  language TEXT DEFAULT 'ar' CHECK (language IN ('ar', 'en')),
  default_payment_method TEXT DEFAULT 'cash' CHECK (default_payment_method IN ('cash', 'wallet', 'card')),
  
  -- Wallet
  wallet_balance DECIMAL(10,2) DEFAULT 0.00,
  
  -- Stats
  total_rides INTEGER DEFAULT 0,
  total_shipments INTEGER DEFAULT 0,
  total_spent DECIMAL(10,2) DEFAULT 0.00,
  
  -- Gamification
  points INTEGER DEFAULT 0,
  level TEXT DEFAULT 'bronze' CHECK (level IN ('bronze', 'silver', 'gold', 'platinum')),
  
  -- Status
  is_active BOOLEAN DEFAULT TRUE,
  is_verified BOOLEAN DEFAULT FALSE,
  
  -- FCM Token for push notifications
  fcm_token TEXT,
  
  -- Timestamps
  created_at TIMESTAMPTZ DEFAULT NOW(),
  updated_at TIMESTAMPTZ DEFAULT NOW(),
  last_active_at TIMESTAMPTZ DEFAULT NOW()
);

-- Indexes
CREATE INDEX idx_profiles_phone ON profiles(phone);
CREATE INDEX idx_profiles_user_type ON profiles(user_type);

-- Trigger for updated_at
CREATE TRIGGER update_profiles_updated_at
  BEFORE UPDATE ON profiles
  FOR EACH ROW
  EXECUTE FUNCTION update_updated_at_column();
```

---

### 2. saved_places
User's saved locations

```sql
CREATE TABLE saved_places (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id UUID NOT NULL REFERENCES profiles(id) ON DELETE CASCADE,
  
  name TEXT NOT NULL,  -- 'البيت', 'الشغل'
  address TEXT NOT NULL,
  location GEOGRAPHY(POINT, 4326) NOT NULL,
  
  created_at TIMESTAMPTZ DEFAULT NOW()
);

CREATE INDEX idx_saved_places_user ON saved_places(user_id);
```

---

### 3. drivers
Driver-specific profiles

```sql
CREATE TABLE drivers (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id UUID NOT NULL UNIQUE REFERENCES profiles(id) ON DELETE CASCADE,
  
  -- Personal Info
  name TEXT NOT NULL,
  phone TEXT NOT NULL,
  whatsapp_number TEXT,
  avatar_url TEXT,
  
  -- Verification Documents
  national_id TEXT NOT NULL,
  national_id_image TEXT NOT NULL,
  license_number TEXT NOT NULL,
  license_image TEXT NOT NULL,
  license_expiry DATE NOT NULL,
  
  -- Service Types
  service_rides BOOLEAN DEFAULT TRUE,
  service_freight BOOLEAN DEFAULT FALSE,
  service_intercity BOOLEAN DEFAULT FALSE,
  
  -- Working Hours
  working_hours_start TIME DEFAULT '08:00',
  working_hours_end TIME DEFAULT '22:00',
  working_days TEXT[] DEFAULT ARRAY['sun', 'mon', 'tue', 'wed', 'thu'],
  
  -- Status
  is_online BOOLEAN DEFAULT FALSE,
  is_available BOOLEAN DEFAULT TRUE,
  current_location GEOGRAPHY(POINT, 4326),
  last_location_update TIMESTAMPTZ,
  
  -- Ratings
  rating DECIMAL(2,1) DEFAULT 5.0,
  total_ratings INTEGER DEFAULT 0,
  
  -- Stats
  total_rides INTEGER DEFAULT 0,
  total_shipments INTEGER DEFAULT 0,
  total_earnings DECIMAL(10,2) DEFAULT 0.00,
  completion_rate DECIMAL(5,2) DEFAULT 100.00,
  acceptance_rate DECIMAL(5,2) DEFAULT 100.00,
  
  -- Earnings
  pending_earnings DECIMAL(10,2) DEFAULT 0.00,
  
  -- Verification
  is_verified BOOLEAN DEFAULT FALSE,
  verified_at TIMESTAMPTZ,
  verified_by UUID REFERENCES profiles(id),
  
  -- Timestamps
  created_at TIMESTAMPTZ DEFAULT NOW(),
  updated_at TIMESTAMPTZ DEFAULT NOW()
);

-- Indexes
CREATE INDEX idx_drivers_user ON drivers(user_id);
CREATE INDEX idx_drivers_online ON drivers(is_online) WHERE is_online = TRUE;
CREATE INDEX idx_drivers_location ON drivers USING GIST(current_location);
CREATE INDEX idx_drivers_verified ON drivers(is_verified) WHERE is_verified = TRUE;
```

---

### 4. vehicles
Vehicle information

```sql
CREATE TABLE vehicles (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  driver_id UUID NOT NULL REFERENCES drivers(id) ON DELETE CASCADE,
  
  -- Vehicle Info
  type TEXT NOT NULL CHECK (type IN ('car', 'motorcycle', 'van', 'truck')),
  category TEXT NOT NULL CHECK (category IN ('economy', 'comfort', 'premium', 'cargo')),
  
  -- Details
  make TEXT NOT NULL,           -- 'Toyota'
  model TEXT NOT NULL,          -- 'Corolla'
  year INTEGER NOT NULL,
  color TEXT NOT NULL,
  plate_number TEXT NOT NULL UNIQUE,
  
  -- Capacity
  passenger_capacity INTEGER DEFAULT 4,
  cargo_capacity_kg DECIMAL(6,2),
  
  -- Images
  images TEXT[] DEFAULT ARRAY[]::TEXT[],
  
  -- Documents
  registration_number TEXT NOT NULL,
  registration_image TEXT NOT NULL,
  registration_expiry DATE NOT NULL,
  insurance_image TEXT,
  insurance_expiry DATE,
  
  -- Status
  is_active BOOLEAN DEFAULT TRUE,
  is_verified BOOLEAN DEFAULT FALSE,
  
  -- Timestamps
  created_at TIMESTAMPTZ DEFAULT NOW(),
  updated_at TIMESTAMPTZ DEFAULT NOW()
);

CREATE INDEX idx_vehicles_driver ON vehicles(driver_id);
CREATE INDEX idx_vehicles_plate ON vehicles(plate_number);
```

---

### 5. rides
Passenger ride requests

```sql
CREATE TABLE rides (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  
  -- Participants
  user_id UUID NOT NULL REFERENCES profiles(id),
  driver_id UUID REFERENCES drivers(id),
  
  -- Origin
  origin_address TEXT NOT NULL,
  origin_location GEOGRAPHY(POINT, 4326) NOT NULL,
  origin_name TEXT,
  
  -- Destination
  destination_address TEXT NOT NULL,
  destination_location GEOGRAPHY(POINT, 4326) NOT NULL,
  destination_name TEXT,
  
  -- Route Info
  distance_km DECIMAL(6,2) NOT NULL,
  estimated_minutes INTEGER NOT NULL,
  actual_minutes INTEGER,
  
  -- Source
  source TEXT NOT NULL DEFAULT 'go-on' CHECK (source IN ('go-on', 'uber', 'careem', 'indriver', 'independent')),
  
  -- Pricing
  estimated_price DECIMAL(10,2) NOT NULL,
  final_price DECIMAL(10,2),
  currency TEXT DEFAULT 'EGP',
  
  -- Price Breakdown (JSONB)
  price_breakdown JSONB,
  /* Example:
  {
    "base_fare": 15.00,
    "distance_fare": 45.00,
    "time_fare": 10.00,
    "discount": 5.00,
    "commission": 7.00
  }
  */
  
  -- Payment
  payment_method TEXT DEFAULT 'cash' CHECK (payment_method IN ('cash', 'wallet', 'card')),
  payment_status TEXT DEFAULT 'pending' CHECK (payment_status IN ('pending', 'completed', 'refunded')),
  
  -- Status
  status TEXT DEFAULT 'searching' CHECK (status IN ('searching', 'accepted', 'arrived', 'started', 'completed', 'cancelled')),
  
  -- Timestamps
  requested_at TIMESTAMPTZ DEFAULT NOW(),
  accepted_at TIMESTAMPTZ,
  arrived_at TIMESTAMPTZ,
  started_at TIMESTAMPTZ,
  completed_at TIMESTAMPTZ,
  cancelled_at TIMESTAMPTZ,
  
  -- Cancellation
  cancelled_by TEXT CHECK (cancelled_by IN ('user', 'driver', 'system')),
  cancellation_reason TEXT,
  
  -- Ratings
  user_rating INTEGER CHECK (user_rating BETWEEN 1 AND 5),
  driver_rating INTEGER CHECK (driver_rating BETWEEN 1 AND 5),
  user_review TEXT,
  driver_review TEXT,
  
  -- Metadata
  created_at TIMESTAMPTZ DEFAULT NOW(),
  updated_at TIMESTAMPTZ DEFAULT NOW()
);

-- Indexes
CREATE INDEX idx_rides_user ON rides(user_id);
CREATE INDEX idx_rides_driver ON rides(driver_id);
CREATE INDEX idx_rides_status ON rides(status);
CREATE INDEX idx_rides_created ON rides(created_at DESC);
CREATE INDEX idx_rides_origin ON rides USING GIST(origin_location);
```

---

### 6. shipments
Freight/shipping orders

```sql
CREATE TABLE shipments (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  
  -- Participants
  sender_id UUID NOT NULL REFERENCES profiles(id),
  receiver_id UUID REFERENCES profiles(id),
  driver_id UUID REFERENCES drivers(id),
  courier_id UUID REFERENCES profiles(id),  -- If passenger-as-courier
  
  -- Receiver Info (if no account)
  receiver_name TEXT NOT NULL,
  receiver_phone TEXT NOT NULL,
  
  -- Package Info (JSONB)
  package JSONB NOT NULL,
  /* Example:
  {
    "type": "small_box",
    "description": "Electronics",
    "weight_kg": 2.5,
    "dimensions": {"length": 30, "width": 20, "height": 15},
    "value": 5000,
    "images": ["url1", "url2"]
  }
  */
  
  -- Pickup
  pickup_address TEXT NOT NULL,
  pickup_location GEOGRAPHY(POINT, 4326) NOT NULL,
  pickup_contact_name TEXT NOT NULL,
  pickup_contact_phone TEXT NOT NULL,
  pickup_notes TEXT,
  
  -- Delivery
  delivery_address TEXT NOT NULL,
  delivery_location GEOGRAPHY(POINT, 4326) NOT NULL,
  delivery_contact_name TEXT NOT NULL,
  delivery_contact_phone TEXT NOT NULL,
  delivery_notes TEXT,
  
  -- Route Info
  distance_km DECIMAL(6,2) NOT NULL,
  estimated_minutes INTEGER NOT NULL,
  
  -- Service Options
  service_type TEXT DEFAULT 'standard' CHECK (service_type IN ('express', 'standard', 'economy')),
  is_fragile BOOLEAN DEFAULT FALSE,
  requires_signature BOOLEAN DEFAULT FALSE,
  is_cod BOOLEAN DEFAULT FALSE,
  cod_amount DECIMAL(10,2),
  
  -- Pricing
  price DECIMAL(10,2) NOT NULL,
  currency TEXT DEFAULT 'EGP',
  price_breakdown JSONB,
  
  -- Payment
  payment_method TEXT DEFAULT 'prepaid' CHECK (payment_method IN ('prepaid', 'cod', 'wallet')),
  payment_status TEXT DEFAULT 'pending' CHECK (payment_status IN ('pending', 'collected', 'transferred', 'refunded')),
  
  -- Status
  status TEXT DEFAULT 'pending' CHECK (status IN ('pending', 'accepted', 'picked_up', 'in_transit', 'delivered', 'cancelled', 'returned')),
  
  -- Current Location (for tracking)
  current_location GEOGRAPHY(POINT, 4326),
  
  -- Tracking History (JSONB Array)
  tracking_history JSONB DEFAULT '[]'::JSONB,
  /* Example:
  [
    {"status": "pending", "timestamp": "2024-01-01T10:00:00Z", "note": "Order created"},
    {"status": "picked_up", "location": {"lat": 30.0, "lng": 31.0}, "timestamp": "2024-01-01T11:00:00Z"}
  ]
  */
  
  -- Proof of Delivery
  pickup_photo TEXT,
  delivery_photo TEXT,
  signature_image TEXT,
  
  -- Timestamps
  requested_at TIMESTAMPTZ DEFAULT NOW(),
  accepted_at TIMESTAMPTZ,
  picked_up_at TIMESTAMPTZ,
  delivered_at TIMESTAMPTZ,
  cancelled_at TIMESTAMPTZ,
  
  -- Ratings
  sender_rating INTEGER CHECK (sender_rating BETWEEN 1 AND 5),
  driver_rating INTEGER CHECK (driver_rating BETWEEN 1 AND 5),
  
  -- Metadata
  created_at TIMESTAMPTZ DEFAULT NOW(),
  updated_at TIMESTAMPTZ DEFAULT NOW()
);

-- Indexes
CREATE INDEX idx_shipments_sender ON shipments(sender_id);
CREATE INDEX idx_shipments_driver ON shipments(driver_id);
CREATE INDEX idx_shipments_status ON shipments(status);
CREATE INDEX idx_shipments_created ON shipments(created_at DESC);
```

---

### 7. transactions
Financial transactions

```sql
CREATE TABLE transactions (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  
  -- Parties
  from_user_id UUID REFERENCES profiles(id),
  to_user_id UUID REFERENCES profiles(id),
  
  -- Type
  type TEXT NOT NULL CHECK (type IN ('topup', 'ride_payment', 'shipment_payment', 'driver_payout', 'refund', 'bonus', 'commission')),
  
  -- Amount
  amount DECIMAL(10,2) NOT NULL,
  currency TEXT DEFAULT 'EGP',
  
  -- Related
  related_type TEXT CHECK (related_type IN ('ride', 'shipment', 'topup')),
  related_id UUID,
  
  -- Payment Details
  payment_method TEXT CHECK (payment_method IN ('cash', 'wallet', 'card', 'fawry', 'vodafone_cash')),
  payment_gateway TEXT,
  gateway_transaction_id TEXT,
  
  -- Status
  status TEXT DEFAULT 'pending' CHECK (status IN ('pending', 'completed', 'failed', 'refunded')),
  
  -- Metadata
  description TEXT,
  created_at TIMESTAMPTZ DEFAULT NOW(),
  completed_at TIMESTAMPTZ
);

-- Indexes
CREATE INDEX idx_transactions_from ON transactions(from_user_id);
CREATE INDEX idx_transactions_to ON transactions(to_user_id);
CREATE INDEX idx_transactions_type ON transactions(type);
CREATE INDEX idx_transactions_created ON transactions(created_at DESC);
```

---

### 8. ratings
Ratings and reviews

```sql
CREATE TABLE ratings (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  
  -- Participants
  from_user_id UUID NOT NULL REFERENCES profiles(id),
  to_user_id UUID NOT NULL REFERENCES profiles(id),
  
  -- Related
  related_type TEXT NOT NULL CHECK (related_type IN ('ride', 'shipment')),
  related_id UUID NOT NULL,
  
  -- Rating
  rating INTEGER NOT NULL CHECK (rating BETWEEN 1 AND 5),
  review TEXT,
  
  -- Tags
  tags TEXT[] DEFAULT ARRAY[]::TEXT[],
  
  -- Metadata
  created_at TIMESTAMPTZ DEFAULT NOW()
);

-- Indexes
CREATE INDEX idx_ratings_to ON ratings(to_user_id);
CREATE INDEX idx_ratings_related ON ratings(related_type, related_id);
```

---

### 9. price_snapshots
Cached prices from other apps

```sql
CREATE TABLE price_snapshots (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  
  -- Location
  origin GEOGRAPHY(POINT, 4326) NOT NULL,
  destination GEOGRAPHY(POINT, 4326) NOT NULL,
  origin_address TEXT,
  destination_address TEXT,
  distance_km DECIMAL(6,2),
  
  -- Prices (JSONB)
  prices JSONB NOT NULL,
  /* Example:
  {
    "uber": {"available": true, "uberX": 95, "eta": 3},
    "careem": {"available": true, "go": 90, "eta": 4},
    "indriver": {"available": true, "suggested": 75},
    "independent": {"available": true, "average": 65, "count": 3}
  }
  */
  
  -- Context
  day_of_week INTEGER CHECK (day_of_week BETWEEN 0 AND 6),
  hour_of_day INTEGER CHECK (hour_of_day BETWEEN 0 AND 23),
  is_rush_hour BOOLEAN DEFAULT FALSE,
  
  -- Metadata
  captured_at TIMESTAMPTZ DEFAULT NOW(),
  captured_by UUID REFERENCES profiles(id)
);

-- Indexes
CREATE INDEX idx_snapshots_captured ON price_snapshots(captured_at DESC);
CREATE INDEX idx_snapshots_hour ON price_snapshots(hour_of_day);
```

---

### 10. notifications
Push notification history

```sql
CREATE TABLE notifications (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id UUID NOT NULL REFERENCES profiles(id) ON DELETE CASCADE,
  
  -- Content
  title TEXT NOT NULL,
  body TEXT NOT NULL,
  data JSONB,
  
  -- Status
  is_read BOOLEAN DEFAULT FALSE,
  read_at TIMESTAMPTZ,
  
  -- Metadata
  created_at TIMESTAMPTZ DEFAULT NOW()
);

CREATE INDEX idx_notifications_user ON notifications(user_id);
CREATE INDEX idx_notifications_unread ON notifications(user_id, is_read) WHERE is_read = FALSE;
```

---

### 11. app_config
Application configuration

```sql
CREATE TABLE app_config (
  key TEXT PRIMARY KEY,
  value JSONB NOT NULL,
  updated_at TIMESTAMPTZ DEFAULT NOW()
);

-- Insert default config
INSERT INTO app_config (key, value) VALUES
('pricing', '{
  "base_fare": 15,
  "per_km_rate": 3.5,
  "per_minute_rate": 0.5,
  "minimum_fare": 20,
  "commission_rate": 0.15,
  "rush_hour_multiplier": 1.5
}'::JSONB),
('freight_pricing', '{
  "base_fare": 20,
  "per_km_rate": 2.5,
  "per_kg_rate": 1.0,
  "express_multiplier": 2.0,
  "cod_fee_percent": 0.02
}'::JSONB),
('features', '{
  "rides_enabled": true,
  "freight_enabled": true,
  "wallet_enabled": true,
  "cod_enabled": true
}'::JSONB),
('versions', '{
  "min_app_version": "1.0.0",
  "latest_app_version": "1.0.0"
}'::JSONB);
```

---

## 🔧 Helper Functions

### Update timestamp trigger
```sql
CREATE OR REPLACE FUNCTION update_updated_at_column()
RETURNS TRIGGER AS $$
BEGIN
  NEW.updated_at = NOW();
  RETURN NEW;
END;
$$ LANGUAGE plpgsql;
```

### Calculate distance between points
```sql
CREATE OR REPLACE FUNCTION calculate_distance_km(
  point1 GEOGRAPHY,
  point2 GEOGRAPHY
) RETURNS DECIMAL AS $$
BEGIN
  RETURN ST_Distance(point1, point2) / 1000;
END;
$$ LANGUAGE plpgsql;
```

### Find nearby drivers
```sql
CREATE OR REPLACE FUNCTION find_nearby_drivers(
  user_location GEOGRAPHY,
  radius_km DECIMAL DEFAULT 5,
  service_type TEXT DEFAULT 'rides'
) RETURNS TABLE (
  driver_id UUID,
  name TEXT,
  rating DECIMAL,
  distance_km DECIMAL,
  vehicle_type TEXT
) AS $$
BEGIN
  RETURN QUERY
  SELECT 
    d.id,
    d.name,
    d.rating,
    ST_Distance(d.current_location, user_location) / 1000 as distance_km,
    v.type as vehicle_type
  FROM drivers d
  JOIN vehicles v ON v.driver_id = d.id
  WHERE d.is_online = TRUE
    AND d.is_verified = TRUE
    AND d.current_location IS NOT NULL
    AND ST_DWithin(d.current_location, user_location, radius_km * 1000)
    AND (
      (service_type = 'rides' AND d.service_rides = TRUE) OR
      (service_type = 'freight' AND d.service_freight = TRUE)
    )
  ORDER BY distance_km ASC;
END;
$$ LANGUAGE plpgsql;
```

---

## 🔐 Row Level Security (RLS)

```sql
-- Enable RLS on all tables
ALTER TABLE profiles ENABLE ROW LEVEL SECURITY;
ALTER TABLE saved_places ENABLE ROW LEVEL SECURITY;
ALTER TABLE drivers ENABLE ROW LEVEL SECURITY;
ALTER TABLE vehicles ENABLE ROW LEVEL SECURITY;
ALTER TABLE rides ENABLE ROW LEVEL SECURITY;
ALTER TABLE shipments ENABLE ROW LEVEL SECURITY;
ALTER TABLE transactions ENABLE ROW LEVEL SECURITY;
ALTER TABLE ratings ENABLE ROW LEVEL SECURITY;
ALTER TABLE notifications ENABLE ROW LEVEL SECURITY;

-- Profiles
CREATE POLICY "Users can view own profile" ON profiles
  FOR SELECT USING (auth.uid() = id);
  
CREATE POLICY "Users can update own profile" ON profiles
  FOR UPDATE USING (auth.uid() = id);

-- Saved Places
CREATE POLICY "Users can manage own places" ON saved_places
  FOR ALL USING (auth.uid() = user_id);

-- Drivers (public read, own write)
CREATE POLICY "Anyone can view verified drivers" ON drivers
  FOR SELECT USING (is_verified = TRUE);
  
CREATE POLICY "Drivers can update own profile" ON drivers
  FOR UPDATE USING (auth.uid() = user_id);

-- Rides
CREATE POLICY "Users can view own rides" ON rides
  FOR SELECT USING (auth.uid() = user_id OR auth.uid() IN (
    SELECT user_id FROM drivers WHERE id = rides.driver_id
  ));

CREATE POLICY "Users can create rides" ON rides
  FOR INSERT WITH CHECK (auth.uid() = user_id);

-- Shipments
CREATE POLICY "Users can view own shipments" ON shipments
  FOR SELECT USING (
    auth.uid() = sender_id OR 
    auth.uid() = receiver_id OR
    auth.uid() IN (SELECT user_id FROM drivers WHERE id = shipments.driver_id)
  );

CREATE POLICY "Users can create shipments" ON shipments
  FOR INSERT WITH CHECK (auth.uid() = sender_id);

-- Notifications
CREATE POLICY "Users can view own notifications" ON notifications
  FOR SELECT USING (auth.uid() = user_id);

CREATE POLICY "Users can update own notifications" ON notifications
  FOR UPDATE USING (auth.uid() = user_id);

-- App Config (public read)
CREATE POLICY "Anyone can read config" ON app_config
  FOR SELECT USING (TRUE);
```

---

## 📊 Realtime Subscriptions

Enable realtime for these tables:
```sql
-- In Supabase Dashboard > Database > Replication
-- Enable for:
-- - rides (for tracking ride status)
-- - shipments (for tracking shipment status)
-- - drivers (for driver location updates)
-- - notifications (for push notifications)
```

---

## 🔗 ER Diagram

```
┌──────────────┐       ┌──────────────┐       ┌──────────────┐
│   profiles   │───────│    drivers   │───────│   vehicles   │
│              │  1:1  │              │  1:1  │              │
└──────────────┘       └──────────────┘       └──────────────┘
       │                      │
       │ 1:N                  │ 1:N
       ▼                      ▼
┌──────────────┐       ┌──────────────┐
│    rides     │       │  shipments   │
│              │       │              │
└──────────────┘       └──────────────┘
       │                      │
       │                      │
       ▼                      ▼
┌──────────────┐       ┌──────────────┐
│   ratings    │       │ transactions │
│              │       │              │
└──────────────┘       └──────────────┘
```

---

## 📝 Migration Order

1. Create helper functions
2. Create `profiles` table
3. Create `saved_places` table
4. Create `drivers` table
5. Create `vehicles` table
6. Create `rides` table
7. Create `shipments` table
8. Create `transactions` table
9. Create `ratings` table
10. Create `notifications` table
11. Create `price_snapshots` table
12. Create `app_config` table
13. Enable RLS policies
14. Enable Realtime
