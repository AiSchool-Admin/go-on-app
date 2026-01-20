/**
 * GO-ON Admin Dashboard - Home Page
 */

'use client';

import { useEffect, useState } from 'react';
import Link from 'next/link';
import { supabase, type DashboardStats } from '@/lib/supabase';

export default function Home() {
  const [stats, setStats] = useState<DashboardStats>({
    totalUsers: 0,
    totalDrivers: 0,
    pendingVerifications: 0,
    todayRides: 0,
    todayShipments: 0,
    todayRevenue: 0,
  });
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    fetchStats();
  }, []);

  const fetchStats = async () => {
    try {
      const today = new Date();
      today.setHours(0, 0, 0, 0);

      // Fetch all stats in parallel
      const [
        { count: totalUsers },
        { count: totalDrivers },
        { count: pendingVerifications },
        { count: todayRides },
        { count: todayShipments },
      ] = await Promise.all([
        supabase.from('profiles').select('*', { count: 'exact', head: true }),
        supabase.from('drivers').select('*', { count: 'exact', head: true }),
        supabase.from('drivers').select('*', { count: 'exact', head: true }).eq('verification_status', 'pending'),
        supabase.from('rides').select('*', { count: 'exact', head: true }).gte('created_at', today.toISOString()),
        supabase.from('shipments').select('*', { count: 'exact', head: true }).gte('created_at', today.toISOString()),
      ]);

      // Get today's revenue
      const { data: todayRidesData } = await supabase
        .from('rides')
        .select('final_price')
        .gte('created_at', today.toISOString())
        .eq('status', 'completed');

      const todayRevenue = todayRidesData?.reduce((sum, r) => sum + (r.final_price || 0), 0) || 0;

      setStats({
        totalUsers: totalUsers || 0,
        totalDrivers: totalDrivers || 0,
        pendingVerifications: pendingVerifications || 0,
        todayRides: todayRides || 0,
        todayShipments: todayShipments || 0,
        todayRevenue,
      });
    } catch (error) {
      console.error('Error fetching stats:', error);
    } finally {
      setLoading(false);
    }
  };

  return (
    <div>
      <header className="mb-8">
        <h1 className="text-3xl font-bold text-gray-800">
          مرحباً بك في لوحة التحكم
        </h1>
        <p className="text-gray-600">
          نظرة عامة على أداء التطبيق
        </p>
      </header>

      {/* Stats Grid */}
      <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-6 mb-8">
        <StatCard
          title="المستخدمين"
          value={loading ? '...' : stats.totalUsers.toString()}
          icon="👥"
          color="bg-blue-500"
        />
        <StatCard
          title="السائقين"
          value={loading ? '...' : stats.totalDrivers.toString()}
          icon="🚗"
          color="bg-green-500"
        />
        <Link href="/drivers/pending">
          <StatCard
            title="طلبات التحقق المعلقة"
            value={loading ? '...' : stats.pendingVerifications.toString()}
            icon="⏳"
            color="bg-yellow-500"
            highlight={stats.pendingVerifications > 0}
          />
        </Link>
        <StatCard
          title="رحلات اليوم"
          value={loading ? '...' : stats.todayRides.toString()}
          icon="📍"
          color="bg-purple-500"
        />
        <StatCard
          title="شحنات اليوم"
          value={loading ? '...' : stats.todayShipments.toString()}
          icon="📦"
          color="bg-orange-500"
        />
        <StatCard
          title="إيرادات اليوم"
          value={loading ? '...' : `${stats.todayRevenue} ج.م`}
          icon="💰"
          color="bg-emerald-500"
        />
      </div>

      {/* Quick Actions */}
      <div className="grid grid-cols-1 md:grid-cols-2 gap-6 mb-8">
        <div className="bg-white rounded-lg shadow p-6">
          <h2 className="text-xl font-bold mb-4">إجراءات سريعة</h2>
          <div className="space-y-3">
            <Link
              href="/drivers/pending"
              className="flex items-center gap-3 p-3 bg-yellow-50 rounded-lg hover:bg-yellow-100 transition-colors"
            >
              <span className="text-2xl">⏳</span>
              <div>
                <p className="font-medium">مراجعة طلبات التحقق</p>
                <p className="text-sm text-gray-500">
                  {stats.pendingVerifications} طلب في الانتظار
                </p>
              </div>
            </Link>
            <Link
              href="/drivers"
              className="flex items-center gap-3 p-3 bg-green-50 rounded-lg hover:bg-green-100 transition-colors"
            >
              <span className="text-2xl">🚗</span>
              <div>
                <p className="font-medium">إدارة السائقين</p>
                <p className="text-sm text-gray-500">
                  عرض وإدارة جميع السائقين
                </p>
              </div>
            </Link>
            <Link
              href="/rides"
              className="flex items-center gap-3 p-3 bg-purple-50 rounded-lg hover:bg-purple-100 transition-colors"
            >
              <span className="text-2xl">📍</span>
              <div>
                <p className="font-medium">الرحلات</p>
                <p className="text-sm text-gray-500">
                  متابعة الرحلات الجارية
                </p>
              </div>
            </Link>
          </div>
        </div>

        {/* Recent Activity */}
        <div className="bg-white rounded-lg shadow p-6">
          <h2 className="text-xl font-bold mb-4">النشاط الأخير</h2>
          <RecentActivity />
        </div>
      </div>

      {/* System Status */}
      <div className="bg-white rounded-lg shadow p-6">
        <h2 className="text-xl font-bold mb-4">حالة النظام</h2>
        <div className="grid grid-cols-1 md:grid-cols-3 gap-4">
          <StatusItem label="قاعدة البيانات" status="operational" />
          <StatusItem label="خدمة الإشعارات" status="operational" />
          <StatusItem label="بوت واتساب" status="operational" />
        </div>
      </div>
    </div>
  );
}

interface StatCardProps {
  title: string;
  value: string;
  icon: string;
  color: string;
  highlight?: boolean;
}

function StatCard({ title, value, icon, color, highlight }: StatCardProps) {
  return (
    <div className={`bg-white rounded-lg shadow p-6 ${highlight ? 'ring-2 ring-yellow-500' : ''}`}>
      <div className="flex items-center justify-between">
        <div>
          <p className="text-gray-500 text-sm">{title}</p>
          <p className="text-2xl font-bold mt-1">{value}</p>
        </div>
        <div className={`${color} text-white p-3 rounded-full text-2xl`}>
          {icon}
        </div>
      </div>
    </div>
  );
}

function RecentActivity() {
  const [activities, setActivities] = useState<any[]>([]);

  useEffect(() => {
    const fetchActivities = async () => {
      // Fetch recent drivers
      const { data: recentDrivers } = await supabase
        .from('drivers')
        .select('id, name, created_at, verification_status')
        .order('created_at', { ascending: false })
        .limit(5);

      const formatted = recentDrivers?.map((d) => ({
        id: d.id,
        text: `${d.name} - ${d.verification_status === 'pending' ? 'تسجيل جديد' : d.verification_status === 'approved' ? 'تم التوثيق' : 'مرفوض'}`,
        time: new Date(d.created_at).toLocaleDateString('ar-EG'),
        icon: d.verification_status === 'pending' ? '🆕' : d.verification_status === 'approved' ? '✅' : '❌',
      })) || [];

      setActivities(formatted);
    };

    fetchActivities();
  }, []);

  if (activities.length === 0) {
    return <p className="text-gray-500 text-center py-4">لا يوجد نشاط حديث</p>;
  }

  return (
    <div className="space-y-3">
      {activities.map((activity) => (
        <div key={activity.id} className="flex items-center gap-3 p-2 hover:bg-gray-50 rounded">
          <span className="text-xl">{activity.icon}</span>
          <div className="flex-1">
            <p className="text-sm">{activity.text}</p>
            <p className="text-xs text-gray-500">{activity.time}</p>
          </div>
        </div>
      ))}
    </div>
  );
}

interface StatusItemProps {
  label: string;
  status: 'operational' | 'degraded' | 'down';
}

function StatusItem({ label, status }: StatusItemProps) {
  const colors = {
    operational: 'bg-green-500',
    degraded: 'bg-yellow-500',
    down: 'bg-red-500',
  };

  const labels = {
    operational: 'يعمل',
    degraded: 'بطيء',
    down: 'متوقف',
  };

  return (
    <div className="flex items-center justify-between p-3 bg-gray-50 rounded-lg">
      <span className="text-gray-700">{label}</span>
      <span className="flex items-center gap-2">
        <span className={`w-2 h-2 rounded-full ${colors[status]}`}></span>
        <span className="text-sm text-gray-600">{labels[status]}</span>
      </span>
    </div>
  );
}
