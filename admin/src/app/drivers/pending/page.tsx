/**
 * Pending Driver Verification Page
 */

'use client';

import { useEffect, useState } from 'react';
import Link from 'next/link';
import { supabase, type Driver } from '@/lib/supabase';

export default function PendingVerificationPage() {
  const [drivers, setDrivers] = useState<Driver[]>([]);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    fetchPendingDrivers();

    // Realtime subscription for new pending drivers
    const subscription = supabase
      .channel('pending-drivers')
      .on(
        'postgres_changes',
        {
          event: '*',
          schema: 'public',
          table: 'drivers',
          filter: 'verification_status=eq.pending',
        },
        () => {
          fetchPendingDrivers();
        }
      )
      .subscribe();

    return () => {
      subscription.unsubscribe();
    };
  }, []);

  const fetchPendingDrivers = async () => {
    try {
      const { data, error } = await supabase
        .from('drivers')
        .select('*')
        .eq('verification_status', 'pending')
        .order('created_at', { ascending: true });

      if (error) throw error;
      setDrivers(data || []);
    } catch (error) {
      console.error('Error fetching pending drivers:', error);
    } finally {
      setLoading(false);
    }
  };

  const quickApprove = async (driverId: string) => {
    try {
      const { error } = await supabase
        .from('drivers')
        .update({
          verification_status: 'approved',
          is_verified: true,
          verified_at: new Date().toISOString(),
        })
        .eq('id', driverId);

      if (error) throw error;

      // Remove from list
      setDrivers((prev) => prev.filter((d) => d.id !== driverId));
    } catch (error) {
      console.error('Error approving driver:', error);
      alert('حدث خطأ في قبول السائق');
    }
  };

  if (loading) {
    return (
      <div className="text-center py-12">
        <div className="animate-spin w-8 h-8 border-4 border-yellow-500 border-t-transparent rounded-full mx-auto"></div>
        <p className="mt-4 text-gray-600">جاري التحميل...</p>
      </div>
    );
  }

  return (
    <div>
      <div className="flex items-center justify-between mb-8">
        <div>
          <h1 className="text-3xl font-bold">طلبات التحقق</h1>
          <p className="text-gray-600">
            {drivers.length} طلب قيد المراجعة
          </p>
        </div>
      </div>

      {drivers.length === 0 ? (
        <div className="text-center py-12 bg-white rounded-lg shadow">
          <p className="text-6xl mb-4">✅</p>
          <h2 className="text-xl font-bold text-gray-800">لا توجد طلبات معلقة</h2>
          <p className="text-gray-600 mt-2">
            جميع طلبات التحقق تمت مراجعتها
          </p>
        </div>
      ) : (
        <div className="space-y-4">
          {drivers.map((driver, index) => (
            <div
              key={driver.id}
              className="bg-white rounded-lg shadow p-6 hover:shadow-lg transition-shadow"
            >
              <div className="flex items-center justify-between">
                <div className="flex items-center gap-4">
                  <div className="w-12 h-12 rounded-full bg-yellow-100 flex items-center justify-center text-xl font-bold text-yellow-600">
                    {index + 1}
                  </div>

                  {driver.profile_image ? (
                    <img
                      src={driver.profile_image}
                      alt={driver.name}
                      className="w-16 h-16 rounded-full object-cover"
                    />
                  ) : (
                    <div className="w-16 h-16 rounded-full bg-gray-200 flex items-center justify-center">
                      <span className="text-2xl">👤</span>
                    </div>
                  )}

                  <div>
                    <h3 className="font-bold text-lg">{driver.name}</h3>
                    <p className="text-gray-600">{driver.phone}</p>
                    <div className="flex items-center gap-2 mt-1">
                      <span className="text-xs bg-gray-100 px-2 py-1 rounded">
                        هوية: {driver.national_id}
                      </span>
                      <span className="text-xs bg-gray-100 px-2 py-1 rounded">
                        رخصة: {driver.license_number}
                      </span>
                    </div>
                  </div>
                </div>

                <div className="flex items-center gap-2">
                  <Link
                    href={`/drivers/${driver.id}`}
                    className="px-4 py-2 bg-blue-500 text-white rounded-lg hover:bg-blue-600 transition-colors"
                  >
                    مراجعة المستندات
                  </Link>
                  <button
                    onClick={() => quickApprove(driver.id)}
                    className="px-4 py-2 bg-green-500 text-white rounded-lg hover:bg-green-600 transition-colors"
                  >
                    قبول سريع
                  </button>
                </div>
              </div>

              <div className="mt-4 flex items-center gap-6 text-sm text-gray-500">
                <span>
                  تاريخ التسجيل:{' '}
                  {new Date(driver.created_at).toLocaleDateString('ar-EG', {
                    year: 'numeric',
                    month: 'long',
                    day: 'numeric',
                    hour: '2-digit',
                    minute: '2-digit',
                  })}
                </span>
                {driver.whatsapp_number && (
                  <span>واتساب: {driver.whatsapp_number}</span>
                )}
                <span>
                  انتهاء الرخصة:{' '}
                  {new Date(driver.license_expiry).toLocaleDateString('ar-EG')}
                </span>
              </div>

              {/* Document Thumbnails */}
              <div className="mt-4 flex items-center gap-4">
                {driver.national_id_image && (
                  <div className="relative group">
                    <img
                      src={driver.national_id_image}
                      alt="صورة الهوية"
                      className="w-20 h-14 object-cover rounded border"
                    />
                    <span className="absolute bottom-0 left-0 right-0 bg-black bg-opacity-50 text-white text-xs text-center">
                      الهوية
                    </span>
                  </div>
                )}
                {driver.license_image && (
                  <div className="relative">
                    <img
                      src={driver.license_image}
                      alt="صورة الرخصة"
                      className="w-20 h-14 object-cover rounded border"
                    />
                    <span className="absolute bottom-0 left-0 right-0 bg-black bg-opacity-50 text-white text-xs text-center">
                      الرخصة
                    </span>
                  </div>
                )}
              </div>
            </div>
          ))}
        </div>
      )}
    </div>
  );
}
