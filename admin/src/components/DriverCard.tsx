/**
 * Driver Card Component for listing drivers
 */

'use client';

import Link from 'next/link';
import type { Driver } from '@/lib/supabase';

interface DriverCardProps {
  driver: Driver;
}

export default function DriverCard({ driver }: DriverCardProps) {
  const statusColors = {
    pending: 'bg-yellow-100 text-yellow-800',
    approved: 'bg-green-100 text-green-800',
    rejected: 'bg-red-100 text-red-800',
  };

  const statusLabels = {
    pending: 'قيد المراجعة',
    approved: 'موثق',
    rejected: 'مرفوض',
  };

  return (
    <Link href={`/drivers/${driver.id}`}>
      <div className="bg-white rounded-lg shadow hover:shadow-lg transition-shadow p-6 cursor-pointer">
        <div className="flex items-start justify-between">
          <div className="flex items-center gap-4">
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
              <p className="text-gray-500 text-sm">
                رقم الهوية: {driver.national_id}
              </p>
            </div>
          </div>

          <span
            className={`px-3 py-1 rounded-full text-sm font-medium ${
              statusColors[driver.verification_status]
            }`}
          >
            {statusLabels[driver.verification_status]}
          </span>
        </div>

        <div className="mt-4 grid grid-cols-3 gap-4 text-center border-t pt-4">
          <div>
            <p className="text-gray-500 text-sm">التقييم</p>
            <p className="font-bold">{driver.rating?.toFixed(1) || '5.0'} ⭐</p>
          </div>
          <div>
            <p className="text-gray-500 text-sm">الرحلات</p>
            <p className="font-bold">{driver.total_rides || 0}</p>
          </div>
          <div>
            <p className="text-gray-500 text-sm">الأرباح</p>
            <p className="font-bold">{driver.total_earnings || 0} ج.م</p>
          </div>
        </div>

        <div className="mt-4 flex items-center justify-between text-sm text-gray-500">
          <span>
            تاريخ التسجيل:{' '}
            {new Date(driver.created_at).toLocaleDateString('ar-EG')}
          </span>
          {driver.is_online && (
            <span className="flex items-center gap-1 text-green-600">
              <span className="w-2 h-2 rounded-full bg-green-500"></span>
              متصل الآن
            </span>
          )}
        </div>
      </div>
    </Link>
  );
}
