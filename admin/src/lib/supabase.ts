/**
 * Supabase Client Configuration for Admin Dashboard
 */

import { createClient } from '@supabase/supabase-js';

const supabaseUrl = process.env.NEXT_PUBLIC_SUPABASE_URL!;
const supabaseAnonKey = process.env.NEXT_PUBLIC_SUPABASE_ANON_KEY!;

export const supabase = createClient(supabaseUrl, supabaseAnonKey);

// Database types
export interface Profile {
  id: string;
  full_name: string;
  phone: string;
  email: string;
  avatar_url: string | null;
  role: 'user' | 'driver' | 'admin';
  created_at: string;
}

export interface Driver {
  id: string;
  user_id: string;
  name: string;
  phone: string;
  whatsapp_number: string | null;
  national_id: string;
  license_number: string;
  license_expiry: string;
  national_id_image: string | null;
  license_image: string | null;
  profile_image: string | null;
  is_verified: boolean;
  is_online: boolean;
  rating: number;
  total_rides: number;
  total_earnings: number;
  pending_earnings: number;
  verification_status: 'pending' | 'approved' | 'rejected';
  rejection_reason: string | null;
  verified_at: string | null;
  verified_by: string | null;
  created_at: string;
  updated_at: string;
  profile?: Profile;
}

export interface Vehicle {
  id: string;
  driver_id: string;
  type: 'car' | 'motorcycle' | 'van' | 'truck';
  brand: string;
  model: string;
  year: number;
  color: string;
  plate_number: string;
  registration_image: string | null;
  is_verified: boolean;
  created_at: string;
}

export interface DashboardStats {
  totalUsers: number;
  totalDrivers: number;
  pendingVerifications: number;
  todayRides: number;
  todayShipments: number;
  todayRevenue: number;
}
