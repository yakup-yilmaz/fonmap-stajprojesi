'use client';

import React, { useState, useEffect, useCallback } from 'react';
import {
  Activity,
  Search,
  Filter,
  RefreshCw,
  TrendingUp,
  ShieldCheck,
  Clock,
  Layers,
  Lock,
  Sparkles,
} from 'lucide-react';
import FundCard from '@/components/FundCard';
import FundQuickModal from '@/components/FundQuickModal';
import { fetchLatestEstimates, formatCalculationTime, isMarketOpen } from '@/lib/api';
import { EstimateResult } from '@/types/fonmap';

export default function DashboardPage() {
  const [estimates, setEstimates] = useState<EstimateResult[]>([]);
  const [loading, setLoading] = useState<boolean>(true);
  const [loadError, setLoadError] = useState<boolean>(false);
  const [selectedFund, setSelectedFund] = useState<EstimateResult | null>(null);
  const [searchQuery, setSearchQuery] = useState<string>('');
  const [selectedCategory, setSelectedCategory] = useState<string>('ALL');

  // Otonom 60 saniye geri sayım sayacı
  const [countdown, setCountdown] = useState<number>(60);
  const [lastUpdated, setLastUpdated] = useState<string>(() =>
    new Date().toLocaleTimeString('tr-TR', {
      hour: '2-digit',
      minute: '2-digit',

      second: '2-digit',
    })
  );
  const [refreshFeedback, setRefreshFeedback] = useState<string | null>(null);

  const [isMarketActive, setIsMarketActive] = useState<boolean>(() => isMarketOpen());

  const loadData = useCallback(async (force: boolean = false) => {
    try {
      setLoading(true);
      if (force) {
        setRefreshFeedback('Piyasa verileri sorgulanıyor...');
      }
      const data = await fetchLatestEstimates(force);
      setLoadError(false);
      if (data && data.length > 0) {
        setEstimates(data);
      }
      // Yalnızca başarılı ağ yanıtında güncelleme zaman damgası tazelenir
      const nowStr = new Date().toLocaleTimeString('tr-TR', {
        hour: '2-digit',
        minute: '2-digit',
        second: '2-digit',
      });
      setLastUpdated(nowStr);
      if (force) {
        setRefreshFeedback(`✓ Güncellendi (${nowStr})`);
        setTimeout(() => setRefreshFeedback(null), 3000);
      }
    } catch (err) {
      console.warn('Veri yüklenemedi:', err);
      // Hata durumunda lastUpdated ŞU AN olarak güncellenmez; veri bayatlığı kullanıcıdan gizlenmez
      setLoadError(true);
      setRefreshFeedback('⚠️ Veri sunucudan alınamadı, canlı akış kesildi');
      if (force) {
        setTimeout(() => setRefreshFeedback(null), 5000);
      }
    } finally {
      setLoading(false);
      setCountdown(60); // Sayacı sıfırla
    }
  }, []);

  // 1. Sayfa ilk açıldığında veriyi yükle
  useEffect(() => {
    loadData();
  }, [loadData]);

  // 2. DİNAMİK SEANS ZAMANLAYICISI:
  // Sayfa seans öncesinde (09:50) açılsa bile zamanlayıcı seans açıldığında (09:55) otomatik başlar.
  // Seans kapandığında (18:10) arka planda gereksiz istek atılması otomatik durur.
  useEffect(() => {
    const interval = setInterval(() => {
      const openNow = isMarketOpen();
      setIsMarketActive(openNow);

      if (openNow) {
        setCountdown((prev) => {
          if (prev <= 1) {
            loadData();
            return 60;
          }
          return prev - 1;
        });
      }
    }, 1000);

    return () => clearInterval(interval);
  }, [loadData]);

  // Kategori ayrımı yardımcı fonksiyonları
  const isEquity = (f: EstimateResult) =>
    f.fundCode === 'THF' ||
    f.fundCode === 'KHA' ||
    f.fundCode === 'TI2' ||
    f.fundCode === 'KH1' ||
    f.fundTitle.toLowerCase().includes('hisse');

  const isTech = (f: EstimateResult) =>
    f.fundCode === 'TTE' ||
    f.fundCode === 'ICZ' ||
    f.fundTitle.toLowerCase().includes('teknoloji');

  // Filtreleme mantığı
  const filteredEstimates = estimates.filter((fund) => {
    const matchesSearch =
      fund.fundCode.toLowerCase().includes(searchQuery.toLowerCase()) ||
      fund.fundTitle.toLowerCase().includes(searchQuery.toLowerCase()) ||
      fund.manager.toLowerCase().includes(searchQuery.toLowerCase());

    if (!matchesSearch) return false;

    if (selectedCategory === 'EQUITY') {
      return isEquity(fund);
    }
    if (selectedCategory === 'TECH') {
      return isTech(fund);
    }
    if (selectedCategory === 'FREE') {
      return !isEquity(fund) && !isTech(fund);
    }

    return true;
  });

  // Dinamik Kategori Sayıları
  const totalCount = estimates.length;
  const equityCount = estimates.filter(isEquity).length;
  const techCount = estimates.filter(isTech).length;
  const freeCount = Math.max(0, totalCount - equityCount - techCount);

  return (
    <div className="mx-auto max-w-7xl px-4 py-8 sm:px-6 lg:px-8">
      {/* ÜST BİLGİ VE OTONOM GERİ SAYIM BAŞLIĞI */}
      <div className="flex flex-col md:flex-row md:items-center justify-between gap-4 mb-8">
        <div>
          <div className="flex items-center gap-2 mb-1.5">
            <span className="flex h-2.5 w-2.5 rounded-full bg-emerald-500 animate-ping" />
            <span className="text-xs font-bold uppercase tracking-wider text-emerald-400">
              Canlı Seans Masası (Canlı BIST Takibi)
            </span>
          </div>
          <h1 className="text-2xl sm:text-3xl font-black text-white tracking-tight">
            Yatırım Fonları Gün İçi İzleme Izgarası
          </h1>
          <p className="text-xs text-slate-400 mt-1">
            Resmi KAP dağılımları ve seans içi anlık hisse hareketleriyle üretilen
            canlı tahminler
          </p>
        </div>

        {/* SEANS DURUMU & OTONOM GÖSTERGE */}
        <div className="flex items-center gap-3 self-start md:self-auto">
          <div className="flex items-center gap-2.5 rounded-xl border border-slate-700/80 bg-slate-900/90 px-4 py-2.5 shadow-lg backdrop-blur-md">
            <div className="relative flex h-8 w-8 items-center justify-center">
              {isMarketActive ? (
                <Clock className="h-5 w-5 text-emerald-400" />
              ) : (
                <Lock className="h-5 w-5 text-amber-400" />
              )}
            </div>
            <div>
              <div className="flex items-center gap-1.5">
                {isMarketActive ? (
                  <>
                    <span className="h-2 w-2 rounded-full bg-emerald-400 animate-pulse" />
                    <span className="text-[10px] font-semibold uppercase tracking-wider text-slate-300">
                      Otonom Yenileme
                    </span>
                    <span className="font-mono text-xs font-extrabold text-emerald-400">
                      {countdown}s
                    </span>
                  </>
                ) : (
                  <>
                    <span className="h-2 w-2 rounded-full bg-amber-400" />
                    <span className="text-[10px] font-semibold uppercase tracking-wider text-amber-400">
                      Seans Kapalı (TSİ)
                    </span>
                    <span className="font-mono text-[10px] text-slate-400">
                      (Donduruldu)
                    </span>
                  </>
                )}
              </div>
              <div className="flex items-center gap-2">
                <p className="font-mono text-[10px] text-slate-400">
                  {isMarketActive
                    ? `Son Güncelleme: ${lastUpdated}`
                    : `18:10 Seans Kapanışı • Son Veri: ${lastUpdated}`}
                </p>
                {refreshFeedback && (
                  <span className="font-mono text-[10px] font-semibold text-emerald-400 bg-emerald-500/10 px-1.5 py-0.5 rounded border border-emerald-500/20">
                    {refreshFeedback}
                  </span>
                )}
              </div>
            </div>

            {/* Manuel Yenile Butonu */}
            <button
              onClick={() => loadData(true)}
              disabled={loading}
              title={isMarketOpen() ? 'Şimdi Yenile (Piyasayı Canlı Sorgula)' : 'Kapanış Verilerini Yeniden Doğrula'}
              className="ml-2 flex items-center gap-1.5 rounded-lg border border-slate-700/80 bg-slate-800/90 px-2.5 py-1.5 text-xs font-semibold text-slate-200 hover:bg-slate-700 hover:text-white transition-all active:scale-95 disabled:opacity-50"
            >
              <RefreshCw className={`h-3.5 w-3.5 ${loading ? 'animate-spin text-emerald-400' : 'text-slate-400'}`} />
              <span className="hidden sm:inline">Şimdi Yenile</span>
            </button>
          </div>
        </div>
      </div>

      {/* ARAMA VE KATEGORİ FİLTRELEME ÇUBUĞU */}
      <div className="mb-8 flex flex-col sm:flex-row items-center justify-between gap-4 rounded-2xl border border-slate-800 bg-slate-900/40 p-3 backdrop-blur-md">
        {/* Arama Kutusu */}
        <div className="relative w-full sm:w-80">
          <Search className="absolute left-3.5 top-2.5 h-4 w-4 text-slate-400" />
          <input
            type="text"
            placeholder="Fon kodu veya unvan ara (Örn: TTE, KHA, ICZ)..."
            value={searchQuery}
            onChange={(e) => setSearchQuery(e.target.value)}
            className="w-full rounded-xl border border-slate-700 bg-slate-950 py-2 pl-9 pr-3 text-xs text-white placeholder-slate-500 focus:border-emerald-500 focus:outline-none"
          />
        </div>

        {/* Kategori Hapları — 7 Fonun Matematiksel Ayrımı (2 + 1 + 4 = 7) */}
        <div className="flex items-center gap-1.5 w-full sm:w-auto overflow-x-auto pb-1 sm:pb-0">
          <button
            onClick={() => setSelectedCategory('ALL')}
            className={`rounded-xl px-3.5 py-1.5 text-xs font-semibold transition-all shrink-0 ${selectedCategory === 'ALL'
                ? 'bg-emerald-500 text-slate-950 shadow-md shadow-emerald-500/20'
                : 'text-slate-400 hover:bg-slate-800 hover:text-white'
              }`}
          >
            Tüm Fonlar ({totalCount > 0 ? totalCount : 7})
          </button>
          <button
            onClick={() => setSelectedCategory('EQUITY')}
            className={`rounded-xl px-3.5 py-1.5 text-xs font-semibold transition-all shrink-0 ${selectedCategory === 'EQUITY'
                ? 'bg-emerald-500 text-slate-950 shadow-md shadow-emerald-500/20'
                : 'text-slate-400 hover:bg-slate-800 hover:text-white'
              }`}
          >
            Hisse Senedi Yoğun ({equityCount})
          </button>
          <button
            onClick={() => setSelectedCategory('TECH')}
            className={`rounded-xl px-3.5 py-1.5 text-xs font-semibold transition-all shrink-0 ${selectedCategory === 'TECH'
                ? 'bg-emerald-500 text-slate-950 shadow-md shadow-emerald-500/20'
                : 'text-slate-400 hover:bg-slate-800 hover:text-white'
              }`}
          >
            BIST Teknoloji ({techCount})
          </button>
          <button
            onClick={() => setSelectedCategory('FREE')}
            className={`rounded-xl px-3.5 py-1.5 text-xs font-semibold transition-all shrink-0 ${selectedCategory === 'FREE'
                ? 'bg-emerald-500 text-slate-950 shadow-md shadow-emerald-500/20'
                : 'text-slate-400 hover:bg-slate-800 hover:text-white'
              }`}
          >
            Serbest & Algoritmik ({freeCount})
          </button>
        </div>
      </div>

      {/* KARTLAR IZGARASI */}
      {loading && estimates.length === 0 ? (
        <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-6">
          {[1, 2, 3, 4, 5, 6, 7].map((i) => (
            <div
              key={i}
              className="rounded-2xl border border-slate-800 bg-slate-900/60 p-5 shadow-lg backdrop-blur-md animate-pulse space-y-4"
            >
              <div className="flex items-start justify-between">
                <div className="flex items-center gap-3">
                  <div className="h-10 w-10 rounded-xl bg-slate-800" />
                  <div className="space-y-1.5">
                    <div className="h-4 w-32 rounded bg-slate-800" />
                    <div className="h-3 w-20 rounded bg-slate-800/60" />
                  </div>
                </div>
                <div className="h-5 w-24 rounded-full bg-slate-800" />
              </div>

              <div className="rounded-xl border border-slate-800 bg-slate-950/60 p-4 flex justify-between items-center">
                <div className="space-y-1.5">
                  <div className="h-3 w-16 rounded bg-slate-800" />
                  <div className="h-6 w-24 rounded bg-slate-800" />
                </div>
                <div className="space-y-1.5 text-right">
                  <div className="h-3 w-20 rounded bg-slate-800" />
                  <div className="h-4 w-16 rounded bg-slate-800 ml-auto" />
                </div>
              </div>

              <div className="space-y-2">
                <div className="h-3 w-full rounded bg-slate-800" />
                <div className="h-2 w-full rounded-full bg-slate-800" />
              </div>
            </div>
          ))}
        </div>
      ) : filteredEstimates.length > 0 ? (
        <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-6">
          {filteredEstimates.map((estimate) => (
            <FundCard
              key={estimate.fundCode}
              estimate={estimate}
              onQuickView={(f) => setSelectedFund(f)}
            />
          ))}
        </div>
      ) : estimates.length === 0 && !loadError ? (
        <div className="rounded-2xl border border-dashed border-emerald-500/30 bg-gradient-to-b from-slate-900/60 to-slate-950/80 p-12 text-center max-w-2xl mx-auto shadow-2xl backdrop-blur-xl">
          <div className="inline-flex p-4 rounded-2xl bg-emerald-500/10 border border-emerald-500/20 text-emerald-400 mb-4 shadow-lg shadow-emerald-500/10">
            <Sparkles className="h-8 w-8" />
          </div>
          <h3 className="text-xl font-bold text-white mb-2">
            FONMAP Canlı Takip Masasına Hoş Geldiniz
          </h3>
          <p className="text-sm text-slate-300 leading-relaxed mb-6">
            Sistem tamamen dinamik olup harici yapay/mock veri içermez. Sağ üstteki{' '}
            <strong className="text-emerald-400 font-semibold">+ Fon Ekle</strong> butonuna tıklayarak
            TEFAS ve KAP'tan dilediğiniz fonun portföy dağılım raporunu anında canlı olarak sisteme bağlayabilirsiniz.
          </p>
          <div className="flex flex-wrap items-center justify-center gap-2">
            <span className="text-xs text-slate-400 font-medium">Hemen ekleyebileceğiniz popüler fonlar:</span>
            <span className="px-2.5 py-1 rounded-lg bg-slate-800 border border-slate-700 text-xs font-mono text-emerald-300">TTE</span>
            <span className="px-2.5 py-1 rounded-lg bg-slate-800 border border-slate-700 text-xs font-mono text-teal-300">BDY</span>
            <span className="px-2.5 py-1 rounded-lg bg-slate-800 border border-slate-700 text-xs font-mono text-indigo-300">TI2</span>
            <span className="px-2.5 py-1 rounded-lg bg-slate-800 border border-slate-700 text-xs font-mono text-purple-300">ICZ</span>
          </div>
        </div>
      ) : (
        <div className="rounded-2xl border border-slate-800 bg-slate-900/30 p-12 text-center">
          <p className="text-sm font-semibold text-slate-300">
            {loadError && estimates.length === 0
              ? 'Sunucuya ulaşılamadı. Veriler alınamıyor.'
              : 'Aramanıza uygun fon bulunamadı.'}
          </p>
          <p className="text-xs text-slate-500 mt-1">
            Lütfen arama teriminizi değiştirin veya filtreyi sıfırlayın.
          </p>
          <button
            onClick={() => {
              setSearchQuery('');
              setSelectedCategory('ALL');
            }}
            className="mt-4 rounded-xl bg-slate-800 px-4 py-2 text-xs font-semibold text-white hover:bg-slate-700"
          >
            Filtreleri Temizle
          </button>
        </div>
      )}

      {/* HIZLI RÖNTGEN / TOP 5 MODALI */}
      <FundQuickModal
        estimate={selectedFund}
        onClose={() => setSelectedFund(null)}
      />
    </div>
  );
}
