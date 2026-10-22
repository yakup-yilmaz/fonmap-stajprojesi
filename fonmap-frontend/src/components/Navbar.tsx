'use client';

import React, { useState, useEffect } from 'react';
import Link from 'next/link';
import { usePathname } from 'next/navigation';
import {
  Activity,
  BarChart3,
  ShieldCheck,
  Menu,
  X,
  Clock,
  Sparkles,
  TrendingUp,
} from 'lucide-react';

export default function Navbar() {
  const pathname = usePathname();
  const [currentTime, setCurrentTime] = useState<string>('');
  const [isMarketOpen, setIsMarketOpen] = useState<boolean>(true);
  const [mobileMenuOpen, setMobileMenuOpen] = useState<boolean>(false);

  useEffect(() => {
    const updateMarketStatus = () => {
      const now = new Date();
      // Türkiye saati gösterimi
      setCurrentTime(
        now.toLocaleTimeString('tr-TR', {
          hour: '2-digit',
          minute: '2-digit',
          second: '2-digit',
        })
      );

      const day = now.getDay(); // 0: Pazar, 6: Cumartesi
      const hour = now.getHours();
      const minute = now.getMinutes();
      const timeInMinutes = hour * 60 + minute;

      // Hafta içi 10:00 - 18:15 BIST seans saatleri
      const isOpen =
        day >= 1 && day <= 5 && timeInMinutes >= 600 && timeInMinutes <= 1095;
      setIsMarketOpen(isOpen);
    };

    updateMarketStatus();
    const interval = setInterval(updateMarketStatus, 1000);
    return () => clearInterval(interval);
  }, []);

  const navLinks = [
    { href: '/', label: 'Vitrin', icon: Sparkles },
    { href: '/dashboard', label: 'Canlı Takip Masası', icon: Activity },
    { href: '/admin', label: 'Yönetim Portalı', icon: ShieldCheck },
  ];

  return (
    <header className="sticky top-0 z-50 w-full border-b border-slate-800/80 bg-slate-950/85 backdrop-blur-xl transition-all">
      <div className="mx-auto flex h-16 max-w-7xl items-center justify-between px-4 sm:px-6 lg:px-8">
        {/* LOGO & MARKA */}
        <Link href="/" className="group flex items-center gap-3">
          <div className="flex h-10 w-10 items-center justify-center rounded-xl bg-gradient-to-tr from-emerald-500 via-teal-500 to-indigo-600 p-0.5 shadow-lg shadow-emerald-500/20 transition-transform duration-300 group-hover:scale-105">
            <div className="flex h-full w-full items-center justify-center rounded-[10px] bg-slate-950">
              <TrendingUp className="h-5 w-5 text-emerald-400 transition-colors group-hover:text-emerald-300" />
            </div>
          </div>
          <div className="flex flex-col">
            <div className="flex items-center gap-1.5">
              <span className="text-xl font-black tracking-tight text-white">
                FON<span className="text-emerald-400">MAP</span>
              </span>
              <span className="rounded bg-emerald-500/10 px-1.5 py-0.5 text-[10px] font-semibold text-emerald-400 border border-emerald-500/20">
                PRO
              </span>
            </div>
            <span className="text-[10px] font-medium text-slate-400 tracking-wider">
              GÜN İÇİ FON TAHMİNİ
            </span>
          </div>
        </Link>

        {/* MASAÜSTÜ NAVİGASYON */}
        <nav className="hidden md:flex items-center gap-1">
          {navLinks.map((item) => {
            const isActive = pathname === item.href;
            const Icon = item.icon;
            return (
              <Link
                key={item.href}
                href={item.href}
                className={`flex items-center gap-2 rounded-lg px-3.5 py-2 text-sm font-medium transition-all ${
                  isActive
                    ? 'bg-slate-800/90 text-emerald-400 shadow-inner shadow-emerald-500/10 border border-slate-700/60'
                    : 'text-slate-300 hover:bg-slate-900 hover:text-white'
                }`}
              >
                <Icon className={`h-4 w-4 ${isActive ? 'text-emerald-400' : 'text-slate-400'}`} />
                {item.label}
              </Link>
            );
          })}
        </nav>

        {/* SAĞ BİLGİ ALANI: BIST SEANS VE SAAT */}
        <div className="hidden lg:flex items-center gap-3">
          {/* BIST Seans Durumu */}
          <div
            className={`flex items-center gap-2 rounded-full border px-3 py-1 text-xs font-medium ${
              isMarketOpen
                ? 'border-emerald-500/30 bg-emerald-500/10 text-emerald-300'
                : 'border-amber-500/30 bg-amber-500/10 text-amber-300'
            }`}
          >
            <span className="relative flex h-2 w-2">
              <span
                className={`absolute inline-flex h-full w-full animate-ping rounded-full opacity-75 ${
                  isMarketOpen ? 'bg-emerald-400' : 'bg-amber-400'
                }`}
              />
              <span
                className={`relative inline-flex h-2 w-2 rounded-full ${
                  isMarketOpen ? 'bg-emerald-500' : 'bg-amber-500'
                }`}
              />
            </span>
            <span>{isMarketOpen ? 'BIST Seansı Açık' : 'Seans Kapalı'}</span>
          </div>

          {/* Canlı Saat */}
          <div className="flex items-center gap-1.5 rounded-lg border border-slate-800 bg-slate-900/80 px-3 py-1 text-xs text-slate-300 font-mono tabular-nums">
            <Clock className="h-3.5 w-3.5 text-slate-400" />
            <span>{currentTime || '00:00:00'}</span>
          </div>
        </div>

        {/* MOBİL MENÜ BUTONU */}
        <div className="flex md:hidden items-center gap-2">
          <button
            onClick={() => setMobileMenuOpen(!mobileMenuOpen)}
            className="rounded-lg p-2 text-slate-400 hover:bg-slate-900 hover:text-white"
            aria-label="Menüyü Aç"
          >
            {mobileMenuOpen ? <X className="h-6 w-6" /> : <Menu className="h-6 w-6" />}
          </button>
        </div>
      </div>

      {/* MOBİL AÇILIR MENÜ */}
      {mobileMenuOpen && (
        <div className="md:hidden border-b border-slate-800 bg-slate-950 px-4 py-4 space-y-2">
          {navLinks.map((item) => {
            const isActive = pathname === item.href;
            const Icon = item.icon;
            return (
              <Link
                key={item.href}
                href={item.href}
                onClick={() => setMobileMenuOpen(false)}
                className={`flex items-center gap-3 rounded-lg px-3 py-2.5 text-sm font-medium ${
                  isActive
                    ? 'bg-slate-800 text-emerald-400'
                    : 'text-slate-300 hover:bg-slate-900 hover:text-white'
                }`}
              >
                <Icon className="h-4 w-4" />
                {item.label}
              </Link>
            );
          })}
          <div className="pt-3 border-t border-slate-800/80 flex items-center justify-between text-xs text-slate-400">
            <span>BIST Durumu: {isMarketOpen ? '🟢 Açık' : '🟡 Kapalı'}</span>
            <span className="font-mono">{currentTime}</span>
          </div>
        </div>
      )}
    </header>
  );
}
