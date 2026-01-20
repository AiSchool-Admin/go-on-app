/**
 * All Drivers Page
 */

'use client';

import { useEffect, useState } from 'react';
import { supabase, type Driver } from '@/lib/supabase';
import DriverCard from '@/components/DriverCard';

export default function DriversPage() {
  const [drivers, setDrivers] = useState<Driver[]>([]);
  const [loading, setLoading] = useState(true);
  const [filter, setFilter] = useState<'all' | 'verified' | 'pending' | 'rejected'>('all');
  const [searchQuery, setSearchQuery] = useState('');

  useEffect(() => {
    fetchDrivers();
  }, [filter]);

  const fetchDrivers = async () => {
    setLoading(true);
    try {
      let query = supabase
        .from('drivers')
        .select('*')
        .order('created_at', { ascending: false });

      if (filter === 'verified') {
        query = query.eq('verification_status', 'approved');
      } else if (filter === 'pending') {
        query = query.eq('verification_status', 'pending');
      } else if (filter === 'rejected') {
        query = query.eq('verification_status', 'rejected');
      }

      const { data, error } = await query;

      if (error) throw error;
      setDrivers(data || []);
    } catch (error) {
      console.error('Error fetching drivers:', error);
    } finally {
      setLoading(false);
    }
  };

  const filteredDrivers = drivers.filter((driver) => {
    if (!searchQuery) return true;
    const query = searchQuery.toLowerCase();
    return (
      driver.name?.toLowerCase().includes(query) ||
      driver.phone?.includes(query) ||
      driver.national_id?.includes(query)
    );
  });

  const stats = {
    total: drivers.length,
    verified: drivers.filter((d) => d.verification_status === 'approved').length,
    pending: drivers.filter((d) => d.verification_status === 'pending').length,
    online: drivers.filter((d) => d.is_online).length,
  };

  return (
    <div>
      <div className="flex items-center justify-between mb-8">
        <div>
          <h1 className="text-3xl font-bold">السائقين</h1>
          <p className="text-gray-600">إدارة ومراجعة السائقين المسجلين</p>
        </div>
      </div>

      {/* Stats */}
      <div className="grid grid-cols-1 md:grid-cols-4 gap-4 mb-8">
        <div className="bg-white rounded-lg shadow p-4">
          <p className="text-gray-500 text-sm">إجمالي السائقين</p>
          <p className="text-2xl font-bold">{stats.total}</p>
        </div>
        <div className="bg-white rounded-lg shadow p-4">
          <p className="text-gray-500 text-sm">موثقين</p>
          <p className="text-2xl font-bold text-green-600">{stats.verified}</p>
        </div>
        <div className="bg-white rounded-lg shadow p-4">
          <p className="text-gray-500 text-sm">قيد المراجعة</p>
          <p className="text-2xl font-bold text-yellow-600">{stats.pending}</p>
        </div>
        <div className="bg-white rounded-lg shadow p-4">
          <p className="text-gray-500 text-sm">متصلين الآن</p>
          <p className="text-2xl font-bold text-blue-600">{stats.online}</p>
        </div>
      </div>

      {/* Filters */}
      <div className="flex flex-wrap items-center gap-4 mb-6">
        <div className="flex bg-white rounded-lg shadow overflow-hidden">
          {[
            { value: 'all', label: 'الكل' },
            { value: 'verified', label: 'موثقين' },
            { value: 'pending', label: 'قيد المراجعة' },
            { value: 'rejected', label: 'مرفوضين' },
          ].map((option) => (
            <button
              key={option.value}
              onClick={() => setFilter(option.value as typeof filter)}
              className={`px-4 py-2 text-sm font-medium transition-colors ${
                filter === option.value
                  ? 'bg-yellow-500 text-gray-900'
                  : 'text-gray-600 hover:bg-gray-100'
              }`}
            >
              {option.label}
            </button>
          ))}
        </div>

        <input
          type="text"
          placeholder="بحث بالاسم أو الرقم..."
          value={searchQuery}
          onChange={(e) => setSearchQuery(e.target.value)}
          className="flex-1 max-w-md px-4 py-2 border rounded-lg focus:outline-none focus:ring-2 focus:ring-yellow-500"
        />
      </div>

      {/* Drivers Grid */}
      {loading ? (
        <div className="text-center py-12">
          <div className="animate-spin w-8 h-8 border-4 border-yellow-500 border-t-transparent rounded-full mx-auto"></div>
          <p className="mt-4 text-gray-600">جاري التحميل...</p>
        </div>
      ) : filteredDrivers.length === 0 ? (
        <div className="text-center py-12 bg-white rounded-lg shadow">
          <p className="text-4xl mb-4">🚗</p>
          <p className="text-gray-600">لا يوجد سائقين</p>
        </div>
      ) : (
        <div className="grid grid-cols-1 lg:grid-cols-2 gap-6">
          {filteredDrivers.map((driver) => (
            <DriverCard key={driver.id} driver={driver} />
          ))}
        </div>
      )}
    </div>
  );
}
