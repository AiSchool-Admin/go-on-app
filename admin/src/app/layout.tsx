import type { Metadata } from 'next';
import './globals.css';
import Sidebar from '@/components/Sidebar';

export const metadata: Metadata = {
  title: 'GO-ON Admin Dashboard',
  description: 'لوحة تحكم إدارة تطبيق GO-ON',
};

export default function RootLayout({
  children,
}: {
  children: React.ReactNode;
}) {
  return (
    <html lang="ar" dir="rtl">
      <body className="bg-gray-100">
        <Sidebar />
        <main className="mr-64 min-h-screen p-8">
          {children}
        </main>
      </body>
    </html>
  );
}
