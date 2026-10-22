'use client';

import React from 'react';
import Link from 'next/link';
import {
  TrendingUp,
  TrendingDown,
  ShieldCheck,
  AlertTriangle,
  ShieldAlert,
  ArrowRight,
  Eye,
  PieChart as PieIcon,
  Clock,
} from 'lucide-react';
import { EstimateResult, ConfidenceLevel } from '@/types/fonmap';
import { formatCalculationTime } from '@/lib/api';

interface FundCardProps {
  estimate: EstimateResult;
  onQuickView?: (estimate: EstimateResult) => void;
}

export default function FundCard({ estimate, onQuickView }: FundCardProps) {
  const estReturn = typeof estimate?.estimatedReturn === 'number' ? estimate.estimatedReturn : 0;
  const isPositive = estReturn >= 0;
  const returnPercentage = (estReturn * 100).toFixed(2);
  const covRatio = typeof estimate?.coverageRatio === 'number' ? estimate.coverageRatio : 0;
  const coveragePercentage = (covRatio * 100).toFixed(1);

  // Güven Rozeti Mantığı (proje_klavuz.md 10.6)
  const getConfidenceBadge = (level: ConfidenceLevel, ratio: number) => {
    if (ratio >= 0.85 || level === 'HIGH') {
      return {
        label: 'YÜKSEK GÜVEN',
        bg: 'bg-emerald-500/10 text-emerald-400 border-emerald-500/30',
        icon: ShieldCheck,
      };
    }
    if (ratio >= 0.7 || level === 'MEDIUM') {
      return {
        label: 'ORTA GÜVEN',
        bg: 'bg-amber-500/10 text-amber-400 border-amber-500/30',
        icon: AlertTriangle,
      };
    }
    return {
      label: 'DÜŞÜK GÜVEN',
      bg: 'bg-rose-500/10 text-rose-400 border-rose-500/30',
      icon: ShieldAlert,
    };
  };

  const confidence = getConfidenceBadge(
    estimate?.confidenceLevel,
    covRatio
  );
  const ConfidenceIcon = confidence.icon;

  const formattedTnv = estimate.totalNetAssetValue
    ? estimate.totalNetAssetValue >= 1_000_000_000
      ? `${(estimate.totalNetAssetValue / 1_000_000_000).toFixed(1)} Mr ₺`
      : `${(estimate.totalNetAssetValue / 1_000_000).toFixed(0)} Mn ₺`
    : null;

  return (
    <div
      className={`glass-card group relative flex flex-col justify-between overflow-hidden rounded-2xl p-5 border transition-all duration-300 hover:scale-[1.01] ${
        isPositive ? 'hover:border-emerald-500/40' : 'hover:border-rose-500/40'
      }`}
    >
      {/* ÜST BİLGİ ALANI: KOD, ŞİRKET VE GÜVEN ROZETİ */}
      <div>
        <div className="flex items-start justify-between gap-3">
          <div className="flex items-center gap-2.5">
            <div
              className={`flex h-11 w-11 items-center justify-center rounded-xl font-mono text-base font-black shadow-lg ${
                isPositive
                  ? 'bg-emerald-500/15 text-emerald-400 border border-emerald-500/30 shadow-emerald-500/10'
                  : 'bg-rose-500/15 text-rose-400 border border-rose-500/30 shadow-rose-500/10'
              }`}
            >
              {estimate.fundCode}
            </div>
            <div>
              <h3 className="text-sm font-semibold text-white tracking-tight line-clamp-1 group-hover:text-emerald-300 transition-colors">
                {estimate.fundTitle}
              </h3>
              <p className="text-xs text-slate-400 line-clamp-1">
                {estimate.manager}
              </p>
            </div>
          </div>

          {/* Güven Rozeti */}
          <div
            className={`inline-flex items-center gap-1.5 rounded-full border px-2.5 py-0.5 text-[10px] font-medium tracking-wide ${confidence.bg}`}
          >
            <ConfidenceIcon className="h-3 w-3" />
            <span>{confidence.label}</span>
          </div>
        </div>

        {/* ANA TAHMİN BİLGİSİ: BÜYÜK YÜZDE */}
        <div className="my-5 flex items-baseline justify-between border-y border-slate-800/80 py-4">
          <div>
            <span className="text-[11px] font-medium uppercase tracking-wider text-slate-400">
              Gün İçi Tahmini Getiri
            </span>
            <div className="flex items-center gap-2 mt-0.5">
              <span
                className={`font-mono text-3xl font-extrabold tracking-tight tabular-nums ${
                  isPositive ? 'text-emerald-400' : 'text-rose-400'
                }`}
              >
                {isPositive ? `+${returnPercentage}%` : `${returnPercentage}%`}
              </span>
              {isPositive ? (
                <div className="rounded-full bg-emerald-500/10 p-1 text-emerald-400">
                  <TrendingUp className="h-5 w-5" />
                </div>
              ) : (
                <div className="rounded-full bg-rose-500/10 p-1 text-rose-400">
                  <TrendingDown className="h-5 w-5" />
                </div>
              )}
            </div>
            {/* NOMİNAL PAY FİYATI (TL BAZINDA CANLI BİRİM DEĞER) */}
            {estimate.currentPrice != null && estimate.estimatedPrice != null && (
              <div className="flex items-center gap-1.5 mt-2 font-mono text-xs text-slate-300">
                <span className="text-[10px] text-slate-400 font-sans uppercase">Birim Pay:</span>
                <span className="text-slate-400 tabular-nums">{Number(estimate.currentPrice).toFixed(4)} ₺</span>
                <span className="text-slate-500 font-bold">➔</span>
                <span className={`tabular-nums font-bold ${isPositive ? 'text-emerald-400' : 'text-rose-400'}`}>
                  {Number(estimate.estimatedPrice).toFixed(4)} ₺
                </span>
                <span className="h-1.5 w-1.5 rounded-full bg-emerald-400 animate-pulse ml-0.5" title="Canlı Seans Fiyatlaması" />
              </div>
            )}
          </div>

          <div className="text-right">
            <span className="text-[11px] font-medium uppercase tracking-wider text-slate-400 flex items-center justify-end gap-1">
              <Clock className="w-3 h-3 text-slate-500" />
              <span>Hesaplama Saati</span>
            </span>
            <p className="font-mono text-sm font-semibold text-slate-200 mt-1 tabular-nums">
              {formatCalculationTime(estimate.calculatedAt)}
            </p>
          </div>
        </div>

        {/* KAPSAMA VE FON TOPLAM DEĞERİ (TNV) BARI */}
        <div className="space-y-2 mb-4">
          <div className="flex items-center justify-between text-xs">
            <span className="text-slate-400 flex items-center gap-1">
              <span>Portföy Kapsama:</span>
              <span className="font-mono font-semibold text-slate-200 tabular-nums">
                %{coveragePercentage}
              </span>
            </span>
            {formattedTnv && (
              <span className="text-slate-400 flex items-center gap-1">
                <span>Toplam Fon Değeri:</span>
                <span className="font-mono font-bold text-emerald-400 tabular-nums">
                  {formattedTnv}
                </span>
              </span>
            )}
          </div>

          {/* İlerleme Çubuğu */}
          <div className="h-1.5 w-full rounded-full bg-slate-800 overflow-hidden">
            <div
              className={`h-full rounded-full transition-all duration-500 ${
                estimate.coverageRatio >= 0.85
                  ? 'bg-emerald-500'
                  : estimate.coverageRatio >= 0.7
                  ? 'bg-amber-500'
                  : 'bg-rose-500'
              }`}
              style={{ width: `${Math.min(estimate.coverageRatio * 100, 100)}%` }}
            />
          </div>

          {/* Dinamik Portföy ve Canlı Tahta Kapsamı Açıklaması */}
          <div className="flex items-center justify-between pt-1 text-[11px]">
            {estimate.coverageRatio >= 0.85 ? (
              <>
                <span className="flex items-center gap-1.5 text-emerald-400 font-medium">
                  <span className="h-1.5 w-1.5 rounded-full bg-emerald-400" />
                  BIST Canlı Tahta: %{coveragePercentage}
                </span>
                <span className="text-slate-400 text-[10px]">
                  Tam Seans Fiyatlaması
                </span>
              </>
            ) : (
              <>
                <span className="flex items-center gap-1.5 text-amber-400 font-medium">
                  <span className="h-1.5 w-1.5 rounded-full bg-amber-400" />
                  Canlı Hisse Payı: %{coveragePercentage}
                </span>
                <span className="text-slate-400 text-[10px]">
                  Kalan: ÖST Bono / Tahvil
                </span>
              </>
            )}
          </div>
        </div>
      </div>

      {/* YASAL TAHMİN UYARI ROZETİ */}
      <div className="mt-3 rounded-lg border border-slate-800 bg-slate-950/60 px-2.5 py-1.5 text-center">
        <p className="text-[10px] text-slate-400 flex items-center justify-center gap-1.5 font-medium">
          <span className="text-amber-400 font-bold">⚠️ TAHMİNDİR:</span>
          <span>BIST seans içi model simülasyonudur; kesin getiri taahhüdü içermez.</span>
        </p>
      </div>

      {/* ALT BUTONLAR: HIZLI İNCELE (TOP 5) VE DETAY SAYFASI */}
      <div className="flex items-center gap-2 pt-3 border-t border-slate-800/60">
        {onQuickView && (
          <button
            onClick={() => onQuickView(estimate)}
            className="flex flex-1 items-center justify-center gap-1.5 rounded-xl border border-slate-700/80 bg-slate-800/60 px-3 py-2 text-xs font-semibold text-slate-300 transition-all hover:bg-slate-700/80 hover:text-white"
          >
            <Eye className="h-3.5 w-3.5 text-slate-400" />
            <span>Hızlı Röntgen (Top 5)</span>
          </button>
        )}

        <Link
          href={`/funds/${estimate.fundCode}`}
          className="flex flex-1 items-center justify-center gap-1.5 rounded-xl bg-gradient-to-r from-emerald-600 to-teal-600 px-3 py-2 text-xs font-semibold text-white shadow-md shadow-emerald-600/20 transition-all hover:from-emerald-500 hover:to-teal-500 group-hover:shadow-emerald-500/30"
        >
          <span>Derin Analiz & Backtest</span>
          <ArrowRight className="h-3.5 w-3.5 transition-transform group-hover:translate-x-0.5" />
        </Link>
      </div>
    </div>
  );
}
