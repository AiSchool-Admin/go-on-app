import type { Metadata } from 'next';
import './globals.css';

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
      <body>{children}</body>
    </html>
  );
}
