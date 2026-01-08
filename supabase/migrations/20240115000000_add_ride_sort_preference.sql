-- Add ride sorting preference to profiles table
-- Options: lowest_price, best_service, fastest_arrival

ALTER TABLE profiles
ADD COLUMN IF NOT EXISTS ride_sort_preference TEXT
DEFAULT 'lowest_price'
CHECK (ride_sort_preference IN ('lowest_price', 'best_service', 'fastest_arrival'));

-- Add comment for documentation
COMMENT ON COLUMN profiles.ride_sort_preference IS
'User preference for how to sort/select ride options: lowest_price (default), best_service, or fastest_arrival';
