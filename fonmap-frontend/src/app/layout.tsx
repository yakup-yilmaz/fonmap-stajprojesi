import type { Metadata } from 'next';
import { Geist, Geist_Mono } from 'next/font/google';
import './globals.css';
import Navbar from '@/components/Navbar';
import Footer from '@/components/Footer';

const geistSans = Geist({
  variable: '--font-geist-sans',
  subsets: ['latin'],
});

const geistMono = Geist_Mono({
  variable: '--font-geist-mono',
  subsets: ['latin'],
});

export const metadata: Metadata = {
  title: 'FONMAP — Yatırım Fonları Gün İçi Tahmini Getiri Platformu',
  description:
    'KAP portföy dağılım raporlarını yapay zeka ve ekonometrik modellerle ayrıştıran, BIST canlı verileriyle seans içi tahmini getiri hesaplayan yeni nesil FinTech platformu.',
};

export default function RootLayout({
  children,
}: {
  children: React.ReactNode;
}) {
  return (
    <html
      lang="tr"
      className={`${geistSans.variable} ${geistMono.variable} h-full antialiased`}
    >
      <body className="min-h-full flex flex-col bg-[#080c14] text-slate-100 selection:bg-emerald-500/30 selection:text-emerald-300">
        <Navbar />
        <main className="flex-1 w-full">{children}</main>
        <Footer />
      </body>
    </html>
  );
}
