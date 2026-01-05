/**
 * GO-ON Admin Dashboard - Home Page
 */

export default function Home() {
  return (
    <main className="min-h-screen bg-gray-100">
      <div className="container mx-auto px-4 py-8">
        <header className="mb-8">
          <h1 className="text-3xl font-bold text-gray-800">
            GO-ON Admin Dashboard
          </h1>
          <p className="text-gray-600">
            لوحة تحكم إدارة تطبيق GO-ON
          </p>
        </header>

        <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-4 gap-6">
          {/* Stats Cards */}
          <StatCard
            title="المستخدمين"
            value="0"
            icon="👥"
            color="bg-blue-500"
          />
          <StatCard
            title="السائقين"
            value="0"
            icon="🚗"
            color="bg-green-500"
          />
          <StatCard
            title="الرحلات اليوم"
            value="0"
            icon="📍"
            color="bg-purple-500"
          />
          <StatCard
            title="الشحنات اليوم"
            value="0"
            icon="📦"
            color="bg-orange-500"
          />
        </div>

        <div className="mt-8 bg-white rounded-lg shadow p-6">
          <h2 className="text-xl font-semibold mb-4">قريباً</h2>
          <ul className="list-disc list-inside text-gray-600 space-y-2">
            <li>إدارة المستخدمين والسائقين</li>
            <li>مراجعة طلبات التحقق</li>
            <li>تتبع الرحلات والشحنات</li>
            <li>إدارة المعاملات المالية</li>
            <li>التقارير والإحصائيات</li>
            <li>إعدادات التطبيق</li>
          </ul>
        </div>
      </div>
    </main>
  );
}

interface StatCardProps {
  title: string;
  value: string;
  icon: string;
  color: string;
}

function StatCard({ title, value, icon, color }: StatCardProps) {
  return (
    <div className="bg-white rounded-lg shadow p-6">
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
