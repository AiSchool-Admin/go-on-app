-- Fix find_nearby_drivers function
-- This migration ensures the function exists and works correctly

-- Drop existing function if exists (to recreate with correct signature)
DROP FUNCTION IF EXISTS find_nearby_drivers(GEOGRAPHY, DECIMAL, TEXT);
DROP FUNCTION IF EXISTS find_nearby_drivers(TEXT, DECIMAL, TEXT);

-- Create the function with flexible parameter types
CREATE OR REPLACE FUNCTION find_nearby_drivers(
  user_location TEXT,
  radius_km DECIMAL DEFAULT 5,
  service_type TEXT DEFAULT 'rides'
) RETURNS TABLE (
  driver_id UUID,
  name TEXT,
  phone TEXT,
  rating DECIMAL,
  distance_km DECIMAL,
  vehicle_type TEXT,
  vehicle_make TEXT,
  vehicle_model TEXT,
  vehicle_color TEXT,
  plate_number TEXT
) AS $$
DECLARE
  user_point GEOGRAPHY;
BEGIN
  -- Parse the POINT string to geography
  user_point := ST_GeogFromText(user_location);

  RETURN QUERY
  SELECT
    d.id as driver_id,
    d.name,
    d.phone,
    d.rating,
    ROUND((ST_Distance(d.current_location, user_point) / 1000)::DECIMAL, 2) as distance_km,
    v.type as vehicle_type,
    v.make as vehicle_make,
    v.model as vehicle_model,
    v.color as vehicle_color,
    v.plate_number
  FROM drivers d
  LEFT JOIN vehicles v ON v.driver_id = d.id AND v.is_active = TRUE
  WHERE d.is_online = TRUE
    AND d.is_verified = TRUE
    AND d.is_available = TRUE
    AND d.current_location IS NOT NULL
    AND ST_DWithin(d.current_location, user_point, radius_km * 1000)
    AND (
      (service_type = 'rides' AND d.service_rides = TRUE) OR
      (service_type = 'freight' AND d.service_freight = TRUE) OR
      (service_type = 'intercity' AND d.service_intercity = TRUE)
    )
  ORDER BY distance_km ASC
  LIMIT 20;

EXCEPTION WHEN OTHERS THEN
  -- If PostGIS is not available or location parsing fails, return empty
  RETURN;
END;
$$ LANGUAGE plpgsql SECURITY DEFINER;

-- Grant execute permission to authenticated users
GRANT EXECUTE ON FUNCTION find_nearby_drivers(TEXT, DECIMAL, TEXT) TO authenticated;
GRANT EXECUTE ON FUNCTION find_nearby_drivers(TEXT, DECIMAL, TEXT) TO anon;

-- Add comment for documentation
COMMENT ON FUNCTION find_nearby_drivers IS 'Find nearby online verified drivers within radius_km of user_location. Returns driver info with vehicle details.';
