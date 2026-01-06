-- GO-ON Test Data: Drivers in Cairo
-- Run this AFTER the initial migration

-- First, create test user profiles for drivers
-- Note: You need to create these users in Supabase Auth first, then use their IDs

-- For testing, we'll create profiles with manual UUIDs
-- In production, these would be linked to auth.users

-- Test Driver Profiles (use these UUIDs for testing)
INSERT INTO profiles (id, email, phone, name, user_type, is_driver, is_verified) VALUES
  ('11111111-1111-1111-1111-111111111111', 'driver1@test.com', '+201001234567', 'أحمد محمد', 'driver', true, true),
  ('22222222-2222-2222-2222-222222222222', 'driver2@test.com', '+201002345678', 'محمود علي', 'driver', true, true),
  ('33333333-3333-3333-3333-333333333333', 'driver3@test.com', '+201003456789', 'خالد حسن', 'driver', true, true),
  ('44444444-4444-4444-4444-444444444444', 'driver4@test.com', '+201004567890', 'عمر سعيد', 'driver', true, true),
  ('55555555-5555-5555-5555-555555555555', 'driver5@test.com', '+201005678901', 'يوسف أحمد', 'driver', true, true)
ON CONFLICT (id) DO NOTHING;

-- Test Drivers with locations in Cairo
INSERT INTO drivers (
  id, user_id, name, phone, whatsapp_number,
  national_id, national_id_image, license_number, license_image, license_expiry,
  service_rides, service_freight, is_online, is_available, is_verified,
  current_location, rating, total_ratings, total_rides
) VALUES
  -- Driver 1: المعادي (Maadi)
  (
    'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa',
    '11111111-1111-1111-1111-111111111111',
    'أحمد محمد',
    '+201001234567',
    '+201001234567',
    '29901011234567', 'https://placeholder.com/id1.jpg',
    'DRV123456', 'https://placeholder.com/lic1.jpg', '2026-12-31',
    true, false, true, true, true,
    ST_SetSRID(ST_MakePoint(31.2357, 29.9602), 4326)::geography,
    4.8, 230, 450
  ),
  -- Driver 2: مدينة نصر (Nasr City)
  (
    'bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb',
    '22222222-2222-2222-2222-222222222222',
    'محمود علي',
    '+201002345678',
    '+201002345678',
    '29902022345678', 'https://placeholder.com/id2.jpg',
    'DRV234567', 'https://placeholder.com/lic2.jpg', '2026-12-31',
    true, true, true, true, true,
    ST_SetSRID(ST_MakePoint(31.3525, 30.0511), 4326)::geography,
    4.9, 180, 320
  ),
  -- Driver 3: الدقي (Dokki)
  (
    'cccccccc-cccc-cccc-cccc-cccccccccccc',
    '33333333-3333-3333-3333-333333333333',
    'خالد حسن',
    '+201003456789',
    '+201003456789',
    '29903033456789', 'https://placeholder.com/id3.jpg',
    'DRV345678', 'https://placeholder.com/lic3.jpg', '2026-12-31',
    true, false, true, true, true,
    ST_SetSRID(ST_MakePoint(31.2118, 30.0380), 4326)::geography,
    4.7, 150, 280
  ),
  -- Driver 4: الزمالك (Zamalek)
  (
    'dddddddd-dddd-dddd-dddd-dddddddddddd',
    '44444444-4444-4444-4444-444444444444',
    'عمر سعيد',
    '+201004567890',
    '+201004567890',
    '29904044567890', 'https://placeholder.com/id4.jpg',
    'DRV456789', 'https://placeholder.com/lic4.jpg', '2026-12-31',
    true, true, true, true, true,
    ST_SetSRID(ST_MakePoint(31.2243, 30.0609), 4326)::geography,
    4.6, 120, 200
  ),
  -- Driver 5: التجمع الخامس (5th Settlement)
  (
    'eeeeeeee-eeee-eeee-eeee-eeeeeeeeeeee',
    '55555555-5555-5555-5555-555555555555',
    'يوسف أحمد',
    '+201005678901',
    '+201005678901',
    '29905055678901', 'https://placeholder.com/id5.jpg',
    'DRV567890', 'https://placeholder.com/lic5.jpg', '2026-12-31',
    true, false, true, true, true,
    ST_SetSRID(ST_MakePoint(31.4913, 30.0074), 4326)::geography,
    4.9, 200, 380
  )
ON CONFLICT (id) DO NOTHING;

-- Test Vehicles
INSERT INTO vehicles (
  id, driver_id, type, category, make, model, year, color, plate_number,
  passenger_capacity, registration_number, registration_image, registration_expiry,
  is_active, is_verified
) VALUES
  ('vvvvvvv1-vvvv-vvvv-vvvv-vvvvvvvvvvv1', 'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa',
   'car', 'economy', 'Toyota', 'Corolla', 2020, 'أبيض', 'ق س ط 1234',
   4, 'REG123456', 'https://placeholder.com/reg1.jpg', '2026-12-31', true, true),

  ('vvvvvvv2-vvvv-vvvv-vvvv-vvvvvvvvvvv2', 'bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb',
   'car', 'comfort', 'Hyundai', 'Elantra', 2021, 'فضي', 'ن ص ر 5678',
   4, 'REG234567', 'https://placeholder.com/reg2.jpg', '2026-12-31', true, true),

  ('vvvvvvv3-vvvv-vvvv-vvvv-vvvvvvvvvvv3', 'cccccccc-cccc-cccc-cccc-cccccccccccc',
   'car', 'economy', 'Nissan', 'Sunny', 2019, 'أسود', 'د ق ي 9012',
   4, 'REG345678', 'https://placeholder.com/reg3.jpg', '2026-12-31', true, true),

  ('vvvvvvv4-vvvv-vvvv-vvvv-vvvvvvvvvvv4', 'dddddddd-dddd-dddd-dddd-dddddddddddd',
   'car', 'premium', 'Mercedes', 'C-Class', 2022, 'أسود', 'ز م ك 3456',
   4, 'REG456789', 'https://placeholder.com/reg4.jpg', '2026-12-31', true, true),

  ('vvvvvvv5-vvvv-vvvv-vvvv-vvvvvvvvvvv5', 'eeeeeeee-eeee-eeee-eeee-eeeeeeeeeeee',
   'car', 'economy', 'Chevrolet', 'Optra', 2020, 'رمادي', 'ت ج م 7890',
   4, 'REG567890', 'https://placeholder.com/reg5.jpg', '2026-12-31', true, true)
ON CONFLICT (id) DO NOTHING;

-- Verify data
SELECT 'Drivers count:' as info, COUNT(*) as count FROM drivers WHERE is_verified = true;
SELECT 'Vehicles count:' as info, COUNT(*) as count FROM vehicles WHERE is_verified = true;
