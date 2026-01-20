/**
 * Driver Detail Page with Verification Controls
 */

'use client';

import { useEffect, useState } from 'react';
import { useParams, useRouter } from 'next/navigation';
import { supabase, type Driver, type Vehicle } from '@/lib/supabase';

export default function DriverDetailPage() {
  const params = useParams();
  const router = useRouter();
  const driverId = params.id as string;

  const [driver, setDriver] = useState<Driver | null>(null);
  const [vehicle, setVehicle] = useState<Vehicle | null>(null);
  const [loading, setLoading] = useState(true);
  const [actionLoading, setActionLoading] = useState(false);
  const [rejectionReason, setRejectionReason] = useState('');
  const [showRejectModal, setShowRejectModal] = useState(false);
  const [selectedImage, setSelectedImage] = useState<string | null>(null);

  useEffect(() => {
    fetchDriverData();
  }, [driverId]);

  const fetchDriverData = async () => {
    try {
      // Fetch driver
      const { data: driverData, error: driverError } = await supabase
        .from('drivers')
        .select('*')
        .eq('id', driverId)
        .single();

      if (driverError) throw driverError;
      setDriver(driverData);

      // Fetch vehicle
      const { data: vehicleData } = await supabase
        .from('vehicles')
        .select('*')
        .eq('driver_id', driverId)
        .single();

      setVehicle(vehicleData);
    } catch (error) {
      console.error('Error fetching driver:', error);
    } finally {
      setLoading(false);
    }
  };

  const approveDriver = async () => {
    if (!driver) return;
    setActionLoading(true);

    try {
      const { error } = await supabase
        .from('drivers')
        .update({
          verification_status: 'approved',
          is_verified: true,
          verified_at: new Date().toISOString(),
          rejection_reason: null,
        })
        .eq('id', driverId);

      if (error) throw error;

      // Update profile role
      await supabase
        .from('profiles')
        .update({ role: 'driver' })
        .eq('id', driver.user_id);

      alert('تم قبول السائق بنجاح');
      router.push('/drivers/pending');
    } catch (error) {
      console.error('Error approving driver:', error);
      alert('حدث خطأ في قبول السائق');
    } finally {
      setActionLoading(false);
    }
  };

  const rejectDriver = async () => {
    if (!driver || !rejectionReason.trim()) {
      alert('الرجاء إدخال سبب الرفض');
      return;
    }
    setActionLoading(true);

    try {
      const { error } = await supabase
        .from('drivers')
        .update({
          verification_status: 'rejected',
          is_verified: false,
          rejection_reason: rejectionReason,
        })
        .eq('id', driverId);

      if (error) throw error;

      alert('تم رفض السائق');
      setShowRejectModal(false);
      router.push('/drivers/pending');
    } catch (error) {
      console.error('Error rejecting driver:', error);
      alert('حدث خطأ في رفض السائق');
    } finally {
      setActionLoading(false);
    }
  };

  const toggleOnlineStatus = async () => {
    if (!driver) return;
    setActionLoading(true);

    try {
      const { error } = await supabase
        .from('drivers')
        .update({ is_online: !driver.is_online })
        .eq('id', driverId);

      if (error) throw error;

      setDriver({ ...driver, is_online: !driver.is_online });
    } catch (error) {
      console.error('Error toggling status:', error);
    } finally {
      setActionLoading(false);
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

  if (!driver) {
    return (
      <div className="text-center py-12">
        <p className="text-4xl mb-4">❌</p>
        <p className="text-gray-600">السائق غير موجود</p>
      </div>
    );
  }

  const statusColors = {
    pending: 'bg-yellow-100 text-yellow-800 border-yellow-300',
    approved: 'bg-green-100 text-green-800 border-green-300',
    rejected: 'bg-red-100 text-red-800 border-red-300',
  };

  const statusLabels = {
    pending: 'قيد المراجعة',
    approved: 'موثق',
    rejected: 'مرفوض',
  };

  return (
    <div className="max-w-4xl mx-auto">
      {/* Header */}
      <div className="flex items-center justify-between mb-8">
        <button
          onClick={() => router.back()}
          className="flex items-center gap-2 text-gray-600 hover:text-gray-800"
        >
          <span>→</span>
          <span>رجوع</span>
        </button>

        <div className="flex items-center gap-2">
          {driver.verification_status === 'pending' && (
            <>
              <button
                onClick={() => setShowRejectModal(true)}
                disabled={actionLoading}
                className="px-4 py-2 bg-red-500 text-white rounded-lg hover:bg-red-600 transition-colors disabled:opacity-50"
              >
                رفض
              </button>
              <button
                onClick={approveDriver}
                disabled={actionLoading}
                className="px-4 py-2 bg-green-500 text-white rounded-lg hover:bg-green-600 transition-colors disabled:opacity-50"
              >
                {actionLoading ? 'جاري...' : 'قبول السائق'}
              </button>
            </>
          )}
        </div>
      </div>

      {/* Driver Profile Card */}
      <div className="bg-white rounded-lg shadow-lg overflow-hidden mb-6">
        <div className="bg-gradient-to-l from-yellow-500 to-yellow-600 p-6">
          <div className="flex items-center gap-6">
            {driver.profile_image ? (
              <img
                src={driver.profile_image}
                alt={driver.name}
                className="w-24 h-24 rounded-full object-cover border-4 border-white cursor-pointer"
                onClick={() => setSelectedImage(driver.profile_image)}
              />
            ) : (
              <div className="w-24 h-24 rounded-full bg-white flex items-center justify-center">
                <span className="text-4xl">👤</span>
              </div>
            )}

            <div className="text-white">
              <h1 className="text-2xl font-bold">{driver.name}</h1>
              <p className="opacity-90">{driver.phone}</p>
              <div className="flex items-center gap-2 mt-2">
                <span
                  className={`px-3 py-1 rounded-full text-sm font-medium border ${
                    statusColors[driver.verification_status]
                  }`}
                >
                  {statusLabels[driver.verification_status]}
                </span>
                {driver.is_online && (
                  <span className="px-3 py-1 bg-green-500 text-white rounded-full text-sm">
                    متصل الآن
                  </span>
                )}
              </div>
            </div>
          </div>
        </div>

        {/* Stats */}
        <div className="grid grid-cols-4 border-b">
          <div className="p-4 text-center border-l">
            <p className="text-gray-500 text-sm">التقييم</p>
            <p className="text-xl font-bold">{driver.rating?.toFixed(1) || '5.0'} ⭐</p>
          </div>
          <div className="p-4 text-center border-l">
            <p className="text-gray-500 text-sm">الرحلات</p>
            <p className="text-xl font-bold">{driver.total_rides || 0}</p>
          </div>
          <div className="p-4 text-center border-l">
            <p className="text-gray-500 text-sm">الأرباح الكلية</p>
            <p className="text-xl font-bold">{driver.total_earnings || 0} ج.م</p>
          </div>
          <div className="p-4 text-center">
            <p className="text-gray-500 text-sm">الرصيد المعلق</p>
            <p className="text-xl font-bold">{driver.pending_earnings || 0} ج.م</p>
          </div>
        </div>

        {/* Details */}
        <div className="p-6 grid grid-cols-2 gap-6">
          <div>
            <h3 className="font-bold text-gray-800 mb-3">البيانات الشخصية</h3>
            <div className="space-y-2 text-sm">
              <p>
                <span className="text-gray-500">رقم الهوية:</span>{' '}
                <span className="font-medium">{driver.national_id}</span>
              </p>
              <p>
                <span className="text-gray-500">رقم الرخصة:</span>{' '}
                <span className="font-medium">{driver.license_number}</span>
              </p>
              <p>
                <span className="text-gray-500">انتهاء الرخصة:</span>{' '}
                <span className="font-medium">
                  {new Date(driver.license_expiry).toLocaleDateString('ar-EG')}
                </span>
              </p>
              {driver.whatsapp_number && (
                <p>
                  <span className="text-gray-500">واتساب:</span>{' '}
                  <span className="font-medium">{driver.whatsapp_number}</span>
                </p>
              )}
            </div>
          </div>

          <div>
            <h3 className="font-bold text-gray-800 mb-3">معلومات الحساب</h3>
            <div className="space-y-2 text-sm">
              <p>
                <span className="text-gray-500">تاريخ التسجيل:</span>{' '}
                <span className="font-medium">
                  {new Date(driver.created_at).toLocaleDateString('ar-EG')}
                </span>
              </p>
              {driver.verified_at && (
                <p>
                  <span className="text-gray-500">تاريخ التوثيق:</span>{' '}
                  <span className="font-medium">
                    {new Date(driver.verified_at).toLocaleDateString('ar-EG')}
                  </span>
                </p>
              )}
              {driver.rejection_reason && (
                <p>
                  <span className="text-gray-500">سبب الرفض:</span>{' '}
                  <span className="font-medium text-red-600">
                    {driver.rejection_reason}
                  </span>
                </p>
              )}
            </div>
          </div>
        </div>
      </div>

      {/* Documents Section */}
      <div className="bg-white rounded-lg shadow-lg p-6 mb-6">
        <h2 className="text-xl font-bold mb-4">المستندات</h2>
        <div className="grid grid-cols-3 gap-4">
          {/* National ID */}
          <div className="border rounded-lg overflow-hidden">
            <div className="bg-gray-100 px-3 py-2 text-sm font-medium">
              صورة الهوية الوطنية
            </div>
            {driver.national_id_image ? (
              <img
                src={driver.national_id_image}
                alt="صورة الهوية"
                className="w-full h-40 object-cover cursor-pointer hover:opacity-90"
                onClick={() => setSelectedImage(driver.national_id_image)}
              />
            ) : (
              <div className="h-40 flex items-center justify-center bg-gray-50 text-gray-400">
                لم يتم الرفع
              </div>
            )}
          </div>

          {/* License */}
          <div className="border rounded-lg overflow-hidden">
            <div className="bg-gray-100 px-3 py-2 text-sm font-medium">
              صورة رخصة القيادة
            </div>
            {driver.license_image ? (
              <img
                src={driver.license_image}
                alt="صورة الرخصة"
                className="w-full h-40 object-cover cursor-pointer hover:opacity-90"
                onClick={() => setSelectedImage(driver.license_image)}
              />
            ) : (
              <div className="h-40 flex items-center justify-center bg-gray-50 text-gray-400">
                لم يتم الرفع
              </div>
            )}
          </div>

          {/* Vehicle Registration */}
          {vehicle && (
            <div className="border rounded-lg overflow-hidden">
              <div className="bg-gray-100 px-3 py-2 text-sm font-medium">
                صورة رخصة السيارة
              </div>
              {vehicle.registration_image ? (
                <img
                  src={vehicle.registration_image}
                  alt="رخصة السيارة"
                  className="w-full h-40 object-cover cursor-pointer hover:opacity-90"
                  onClick={() => setSelectedImage(vehicle.registration_image)}
                />
              ) : (
                <div className="h-40 flex items-center justify-center bg-gray-50 text-gray-400">
                  لم يتم الرفع
                </div>
              )}
            </div>
          )}
        </div>
      </div>

      {/* Vehicle Info */}
      {vehicle && (
        <div className="bg-white rounded-lg shadow-lg p-6 mb-6">
          <h2 className="text-xl font-bold mb-4">بيانات المركبة</h2>
          <div className="grid grid-cols-2 md:grid-cols-4 gap-4 text-sm">
            <div>
              <p className="text-gray-500">النوع</p>
              <p className="font-medium">
                {vehicle.type === 'car' && 'سيارة'}
                {vehicle.type === 'motorcycle' && 'موتوسيكل'}
                {vehicle.type === 'van' && 'فان'}
                {vehicle.type === 'truck' && 'شاحنة'}
              </p>
            </div>
            <div>
              <p className="text-gray-500">الماركة</p>
              <p className="font-medium">{vehicle.brand}</p>
            </div>
            <div>
              <p className="text-gray-500">الموديل</p>
              <p className="font-medium">{vehicle.model}</p>
            </div>
            <div>
              <p className="text-gray-500">سنة الصنع</p>
              <p className="font-medium">{vehicle.year}</p>
            </div>
            <div>
              <p className="text-gray-500">اللون</p>
              <p className="font-medium">{vehicle.color}</p>
            </div>
            <div>
              <p className="text-gray-500">رقم اللوحة</p>
              <p className="font-medium">{vehicle.plate_number}</p>
            </div>
          </div>
        </div>
      )}

      {/* Admin Actions */}
      {driver.verification_status === 'approved' && (
        <div className="bg-white rounded-lg shadow-lg p-6">
          <h2 className="text-xl font-bold mb-4">إجراءات إدارية</h2>
          <div className="flex flex-wrap gap-3">
            <button
              onClick={toggleOnlineStatus}
              disabled={actionLoading}
              className={`px-4 py-2 rounded-lg transition-colors ${
                driver.is_online
                  ? 'bg-gray-200 text-gray-800 hover:bg-gray-300'
                  : 'bg-green-500 text-white hover:bg-green-600'
              }`}
            >
              {driver.is_online ? 'إيقاف عن العمل' : 'تفعيل الحساب'}
            </button>
            <button
              onClick={() => setShowRejectModal(true)}
              className="px-4 py-2 bg-red-100 text-red-700 rounded-lg hover:bg-red-200 transition-colors"
            >
              إلغاء التوثيق
            </button>
          </div>
        </div>
      )}

      {/* Reject Modal */}
      {showRejectModal && (
        <div className="fixed inset-0 bg-black bg-opacity-50 flex items-center justify-center z-50">
          <div className="bg-white rounded-lg p-6 max-w-md w-full mx-4">
            <h3 className="text-lg font-bold mb-4">سبب الرفض</h3>
            <textarea
              value={rejectionReason}
              onChange={(e) => setRejectionReason(e.target.value)}
              placeholder="اكتب سبب الرفض هنا..."
              className="w-full border rounded-lg p-3 h-32 resize-none focus:outline-none focus:ring-2 focus:ring-red-500"
            />
            <div className="flex justify-end gap-2 mt-4">
              <button
                onClick={() => setShowRejectModal(false)}
                className="px-4 py-2 text-gray-600 hover:bg-gray-100 rounded-lg"
              >
                إلغاء
              </button>
              <button
                onClick={rejectDriver}
                disabled={actionLoading || !rejectionReason.trim()}
                className="px-4 py-2 bg-red-500 text-white rounded-lg hover:bg-red-600 disabled:opacity-50"
              >
                {actionLoading ? 'جاري...' : 'تأكيد الرفض'}
              </button>
            </div>
          </div>
        </div>
      )}

      {/* Image Viewer Modal */}
      {selectedImage && (
        <div
          className="fixed inset-0 bg-black bg-opacity-90 flex items-center justify-center z-50 cursor-pointer"
          onClick={() => setSelectedImage(null)}
        >
          <img
            src={selectedImage}
            alt="Document"
            className="max-w-full max-h-full object-contain"
          />
          <button
            onClick={() => setSelectedImage(null)}
            className="absolute top-4 left-4 text-white text-3xl hover:text-gray-300"
          >
            ×
          </button>
        </div>
      )}
    </div>
  );
}
