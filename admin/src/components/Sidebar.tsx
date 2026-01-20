/**
 * Sidebar Navigation Component
 */

'use client';

import Link from 'next/link';
import { usePathname } from 'next/navigation';

interface NavItem {
  href: string;
  label: string;
  icon: string;
}

const navItems: NavItem[] = [
  { href: '/', label: 'الرئيسية', icon: '🏠' },
  { href: '/drivers', label: 'السائقين', icon: '🚗' },
  { href: '/drivers/pending', label: 'طلبات التحقق', icon: '⏳' },
  { href: '/users', label: 'المستخدمين', icon: '👥' },
  { href: '/rides', label: 'الرحلات', icon: '📍' },
  { href: '/shipments', label: 'الشحنات', icon: '📦' },
  { href: '/transactions', label: 'المعاملات', icon: '💰' },
  { href: '/settings', label: 'الإعدادات', icon: '⚙️' },
];

export default function Sidebar() {
  const pathname = usePathname();

  return (
    <aside className="w-64 bg-gray-900 text-white min-h-screen fixed right-0 top-0">
      <div className="p-6">
        <h1 className="text-2xl font-bold text-yellow-500">GO-ON</h1>
        <p className="text-gray-400 text-sm">لوحة التحكم</p>
      </div>

      <nav className="mt-6">
        {navItems.map((item) => {
          const isActive = pathname === item.href ||
            (item.href !== '/' && pathname.startsWith(item.href));

          return (
            <Link
              key={item.href}
              href={item.href}
              className={`flex items-center gap-3 px-6 py-3 transition-colors ${
                isActive
                  ? 'bg-yellow-500 text-gray-900'
                  : 'text-gray-300 hover:bg-gray-800'
              }`}
            >
              <span className="text-xl">{item.icon}</span>
              <span>{item.label}</span>
            </Link>
          );
        })}
      </nav>

      <div className="absolute bottom-0 right-0 left-0 p-6 border-t border-gray-800">
        <div className="flex items-center gap-3">
          <div className="w-10 h-10 rounded-full bg-yellow-500 flex items-center justify-center">
            <span className="text-gray-900 font-bold">A</span>
          </div>
          <div>
            <p className="text-sm font-medium">المسؤول</p>
            <p className="text-xs text-gray-400">admin@goon.app</p>
          </div>
        </div>
      </div>
    </aside>
  );
}
