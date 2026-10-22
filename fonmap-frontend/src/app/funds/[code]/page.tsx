'use client';

import React, { useState, useEffect } from 'react';
import Link from 'next/link';
import { useParams } from 'next/navigation';
import {
  ArrowLeft,
  TrendingUp,
  TrendingDown,
  ShieldCheck,
  AlertTriangle,
  Clock,
  Sparkles,
  ExternalLink,
} from 'lucide-react';
import PortfolioPieChart from '@/components/PortfolioPieChart';
import BacktestChart from '@/components/BacktestChart';
import {
  fetchFundEstimate,
  fetchBacktestReport,
  getCachedEstimate,
  formatCalculationTime,
} from '@/lib/api';
import { EstimateResult, BacktestReport } from '@/types/fonmap';

export default function FundDetailPage() {
  const routeParams = useParams();
  const rawCode = (routeParams?.code as string) || 'TTE';
  const fundCode = rawCode.toUpperCase();

  // 1. ANINDA RENDER: yalnızca daha önce çekilmiş CANLI önbellek kullanılır; mock/başka fon verisi gösterilmez.
  const initialEstimate = getCachedEstimate(fundCode) ?? null;

  const [estimate, setEstimate] = useState<EstimateResult | null>(initialEstimate);
  const [backtest, setBacktest] = useState<BacktestReport | null>(null);
  const [isSyncing, setIsSyncing] = useState<boolean>(!initialEstimate);
  const [isBacktestLoading, setIsBacktestLoading] = useState<boolean>(true);

  useEffect(() => {
    let isCancelled = false;

    // 1. KANAL (ANINDA RENDER - 20ms): Gün içi canlı tahmin, hisseler ve Pasta Grafik
    async function loadEstimate() {
      try {
        if (!initialEstimate) setIsSyncing(true);
        const estData = await fetchFundEstimate(fundCode);
        if (!isCancelled && estData) {
          setEstimate(estData);
        }
      } catch (err) {
        console.warn('Fon tahmin verisi güncellenemedi:', err);
      } finally {
        if (!isCancelled) setIsSyncing(false);
      }
    }

    // 2. KANAL (ASENKRON): 60 günlük ağır tarihsel simülasyon grafiği
    async function loadBacktest() {
      try {
        setIsBacktestLoading(true);
        const backtestData = await fetchBacktestReport(fundCode);
        if (!isCancelled && backtestData) {
          setBacktest(backtestData);
        }
      } catch (err) {
        console.warn('Fon backtest verisi güncellenemedi:', err);
      } finally {
        if (!isCancelled) setIsBacktestLoading(false);
      }
    }

    loadEstimate();
    loadBacktest();

    return () => {
      isCancelled = true;
    };
  }, [fundCode]);

  if (!estimate) {
    return (
      <div className="mx-auto max-w-3xl px-4 py-16 text-center space-y-4">
        <h1 className="text-xl font-bold text-white">
          {isSyncing ? 'Fon verisi yükleniyor...' : `${fundCode} fonu için tahmin verisi alınamadı`}
        </h1>
        {!isSyncing && (
          <p className="text-xs text-slate-400">
            Fon bulunamadı, henüz portföy raporu yüklenmedi veya sunucuya ulaşılamıyor.
          </p>
        )}
        <Link href="/dashboard" className="inline-block text-xs font-semibold text-emerald-400 hover:underline">
          Canlı Takip Masasına Dön
        </Link>
      </div>
    );
  }

  const currentEstimate = estimate;
  const estReturn = Number(currentEstimate.estimatedReturn ?? 0);
  const isPositive = estReturn >= 0;
  const returnPercentage = (estReturn * 100).toFixed(2);
  const coveragePercentage =
    currentEstimate.coverageRatio != null ? (Number(currentEstimate.coverageRatio) * 100).toFixed(1) : '—';

  // Varlıkları pozitif ve negatif katkılara göre ayır (Top 5 / Top 5)
  const details = currentEstimate.details || [];
  let topGainers = [...details]
    .filter((d) => d.weightedContribution > 0)
    .sort((a, b) => b.weightedContribution - a.weightedContribution)
    .slice(0, 5);

  let topLosers = [...details]
    .filter((d) => d.weightedContribution < 0)
    .sort((a, b) => a.weightedContribution - b.weightedContribution)
    .slice(0, 5);

  if (topGainers.length === 0 && details.length > 0) {
    topGainers = [...details]
      .sort((a, b) => b.weightedContribution - a.weightedContribution)
      .slice(0, 5);
  }

  if (topLosers.length === 0 && details.length > 0) {
    topLosers = [...details]
      .sort((a, b) => a.weightedContribution - b.weightedContribution)
      .slice(0, 5);
  }

  return (
    <div className="mx-auto max-w-7xl px-4 py-8 sm:px-6 lg:px-8 space-y-8">
      {/* GERİ DÖNÜŞ VE BAŞLIK ÇUBUĞU */}
      <div>
        <Link
          href="/dashboard"
          className="inline-flex items-center gap-2 text-xs font-semibold text-slate-400 hover:text-emerald-400 transition-colors mb-4"
        >
          <ArrowLeft className="h-4 w-4" />
          <span>Canlı Takip Masasına Dön</span>
        </Link>

        <div className="flex flex-col md:flex-row md:items-center justify-between gap-4">
          <div className="flex items-center gap-4">
            <div
              className={`flex h-14 w-14 items-center justify-center rounded-2xl font-mono text-2xl font-black shadow-xl ${
                isPositive
                  ? 'bg-emerald-500/20 text-emerald-400 border border-emerald-500/30'
                  : 'bg-rose-500/20 text-rose-400 border border-rose-500/30'
              }`}
            >
              {currentEstimate.fundCode}
            </div>
            <div>
              <h1 className="text-2xl sm:text-3xl font-black text-white tracking-tight">
                {currentEstimate.fundTitle}
              </h1>
              <p className="text-xs text-slate-400 mt-0.5">
                {currentEstimate.manager} • Seans Saati:{' '}
                <span className="font-mono text-slate-200 font-semibold">{formatCalculationTime(currentEstimate.calculatedAt)}</span>
                {currentEstimate.snapshotDate && (
                  <span className="ml-2 font-mono text-indigo-400 font-medium">
                    • Portföy: {currentEstimate.snapshotDate}
                  </span>
                )}
              </p>
            </div>
          </div>

          {/* SAĞ ANA TAHMİN VE FON BÜYÜKLÜĞÜ KARTI */}
          <div className="flex flex-wrap items-center gap-6 rounded-2xl border border-slate-800 bg-slate-900/80 p-4 backdrop-blur-md">
            <div>
              <span className="text-[10px] font-semibold uppercase tracking-wider text-slate-400">
                Gün İçi Tahmin
              </span>
              <p
                className={`font-mono text-3xl font-black tabular-nums ${
                  isPositive ? 'text-emerald-400' : 'text-rose-400'
                }`}
              >
                {isPositive ? `+${returnPercentage}%` : `${returnPercentage}%`}
              </p>
              {currentEstimate.currentPrice != null && currentEstimate.estimatedPrice != null && (
                <div className="flex items-center gap-1.5 mt-1 font-mono text-[11px] text-slate-300">
                  <span className="text-slate-400 tabular-nums">{Number(currentEstimate.currentPrice).toFixed(4)} ₺</span>
                  <span className="text-slate-500 font-bold">➔</span>
                  <span className={`tabular-nums font-bold ${isPositive ? 'text-emerald-400' : 'text-rose-400'}`}>
                    {Number(currentEstimate.estimatedPrice).toFixed(4)} ₺
                  </span>
                </div>
              )}
            </div>
            <div className="border-l border-slate-800 pl-6">
              <span className="text-[10px] font-semibold uppercase tracking-wider text-slate-400">
                Kapsama Oranı
              </span>
              <p className="font-mono text-xl font-bold text-slate-200 mt-1">
                %{coveragePercentage}
              </p>
            </div>
            <div className="border-l border-slate-800 pl-6">
              <span className="text-[10px] font-semibold uppercase tracking-wider text-slate-400">
                Toplam Fon Büyüklüğü (TNV)
              </span>
              <p className="font-mono text-xl font-bold text-emerald-400 mt-1">
                {currentEstimate.totalNetAssetValue
                  ? currentEstimate.totalNetAssetValue >= 1_000_000_000
                    ? `${(currentEstimate.totalNetAssetValue / 1_000_000_000).toFixed(1)} Milyar ₺`
                    : `${(currentEstimate.totalNetAssetValue / 1_000_000).toFixed(0)} Milyon ₺`
                  : '—'}
              </p>
            </div>
          </div>
        </div>
      </div>

      {/* YASAL TAHMİN UYARI ŞERİDİ */}
      <div className="rounded-xl border border-amber-500/20 bg-amber-500/5 px-4 py-3 text-xs text-slate-300 flex items-center justify-between">
        <p className="flex items-center gap-2">
          <span className="text-amber-400 font-bold">⚠️ TAHMİNDİR:</span>
          <span>
            Bu değer Borsa İstanbul gün içi canlı fiyatlarıyla üretilen bir model simülasyonudur;
            akşam resmi TEFAS kapanışı öncesinde bilgilendirme amaçlıdır.
          </span>
        </p>
      </div>

      {/* 1. BÖLÜM: TOP 5 KAZANDIRANLAR VE TOP 5 KAYBETTİRENLER MASASI */}
      <div className="grid grid-cols-1 md:grid-cols-2 gap-6">
        {/* TOP 5 LOKOMOTİF (POZİTİF KATKI) */}
        <div className="rounded-2xl border border-emerald-500/30 bg-emerald-500/5 p-5">
          <div className="flex items-center gap-2 mb-4 text-emerald-400 text-xs font-bold uppercase tracking-wider">
            <TrendingUp className="h-4 w-4" />
            <span>Günün Lokomotifleri (En Çok Pozitif Katkı Sağlayanlar)</span>
          </div>

          <div className="divide-y divide-emerald-500/10 text-xs">
            {topGainers.map((item, idx) => (
              <div key={idx} className="flex items-center justify-between py-2.5 font-mono">
                <div className="flex items-center gap-2 font-sans">
                  <span className="font-bold text-white bg-slate-800 px-2.5 py-1 rounded text-xs">
                    {item.instrumentTicker || (item as any).ticker}
                  </span>
                  <span className="text-slate-300 text-xs truncate max-w-[200px]">
                    {item.instrumentTitle || (item as any).title}
                  </span>
                </div>
                <div className="flex items-center gap-4 text-right">
                  <span className="text-slate-400 text-xs">
                    Ağırlık: %{(Number(item.effectiveWeight || 0) * 100).toFixed(1)}
                  </span>
                  <span className="text-emerald-400 font-bold">
                    +{(Number(item.weightedContribution || 0) * 100).toFixed(3)}%
                  </span>
                </div>
              </div>
            ))}
          </div>
        </div>

        {/* TOP 5 AŞAĞI ÇEKENLER (NEGATİF KATKI) */}
        <div className="rounded-2xl border border-rose-500/30 bg-rose-500/5 p-5">
          <div className="flex items-center gap-2 mb-4 text-rose-400 text-xs font-bold uppercase tracking-wider">
            <TrendingDown className="h-4 w-4" />
            <span>Aşağı Çekenler (En Çok Negatif Katkı Sağlayanlar)</span>
          </div>

          <div className="divide-y divide-rose-500/10 text-xs">
            {topLosers.map((item, idx) => (
              <div key={idx} className="flex items-center justify-between py-2.5 font-mono">
                <div className="flex items-center gap-2 font-sans">
                  <span className="font-bold text-white bg-slate-800 px-2.5 py-1 rounded text-xs">
                    {item.instrumentTicker || (item as any).ticker}
                  </span>
                  <span className="text-slate-300 text-xs truncate max-w-[200px]">
                    {item.instrumentTitle || (item as any).title}
                  </span>
                </div>
                <div className="flex items-center gap-4 text-right">
                  <span className="text-slate-400 text-xs">
                    Ağırlık: %{(Number(item.effectiveWeight || 0) * 100).toFixed(1)}
                  </span>
                  <span className="text-rose-400 font-bold">
                    {(Number(item.weightedContribution || 0) * 100).toFixed(3)}%
                  </span>
                </div>
              </div>
            ))}
          </div>
        </div>
      </div>

      {/* 2. BÖLÜM: 1 HAFTALIK FİYAT DALGALANMASI VE GÜN SONU TEFAS BACKTEST GRAFİĞİ */}
      {backtest && backtest.dailyComparisons && backtest.dailyComparisons.length > 0 ? (
        <BacktestChart
          data={backtest.dailyComparisons}
          mae={backtest.mae}
          rmse={backtest.rmse}
          directionalAccuracy={backtest.directionalAccuracy}
          reportDate={currentEstimate.snapshotDate || ''}
          reportDownloadedAt={currentEstimate.calculatedAt}
          fundCode={currentEstimate.fundCode}
        />
      ) : isBacktestLoading ? (
        <div className="glass-card rounded-2xl p-8 border border-slate-800 text-center space-y-3 animate-pulse">
          <div className="mx-auto w-10 h-10 rounded-full bg-indigo-500/10 border border-indigo-500/20 flex items-center justify-center text-lg text-indigo-400 animate-spin">
            ⏳
          </div>
          <h3 className="text-sm font-bold text-white">Geçmiş Seans Simülasyonu ve TEFAS Mutabakatı Hesaplanıyor...</h3>
          <p className="text-xs text-slate-400 max-w-md mx-auto font-mono">
            Takasbank resmi fiyatlarıyla 60 günlük geriye dönük doğruluk analizi arka planda çözümleniyor.
          </p>
        </div>
      ) : (
        <div className="glass-card rounded-2xl p-8 border border-slate-800 text-center space-y-3">
          <div className="mx-auto w-12 h-12 rounded-full bg-slate-800/80 flex items-center justify-center text-xl">
            📊
          </div>
          <h3 className="text-base font-bold text-white">Bu Fon İçin Henüz Backtest Raporu Bulunmuyor</h3>
          <p className="text-xs text-slate-400 max-w-md mx-auto">
            {fundCode} fonuna ait portföy dağılım raporları üzerinden geriye dönük doğrulama simülasyonunu başlatmak için Yönetim Paneli'ni ziyaret edebilirsiniz.
          </p>
          <a
            href="/admin"
            className="inline-flex items-center gap-2 rounded-xl bg-slate-800 hover:bg-slate-700 px-4 py-2 text-xs font-semibold text-emerald-400 border border-slate-700 transition-all"
          >
            Yönetim Paneline Git →
          </a>
        </div>
      )}

      {/* 3. BÖLÜM: PORTFÖY VARLIK DAĞILIM PASTASI */}
      <PortfolioPieChart
        stockRatio={currentEstimate.stockRatio}
        viopCashRatio={currentEstimate.viopCashRatio}
        totalNetAssetValue={currentEstimate.totalNetAssetValue}
      />
    </div>
  );
}
