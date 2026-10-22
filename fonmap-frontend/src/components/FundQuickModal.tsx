'use client';

import React, { useState, useEffect } from 'react';
import Link from 'next/link';
import {
  X,
  TrendingUp,
  TrendingDown,
  ArrowRight,
  ShieldCheck,
  Percent,
  Loader2,
} from 'lucide-react';
import { EstimateResult, EstimateDetail } from '@/types/fonmap';
import { fetchFundEstimate, formatCalculationTime } from '@/lib/api';

interface FundQuickModalProps {
  estimate: EstimateResult | null;
  onClose: () => void;
}

export default function FundQuickModal({
  estimate,
  onClose,
}: FundQuickModalProps) {
  const [currentEstimate, setCurrentEstimate] = useState<EstimateResult | null>(estimate);
  const [loadingDetails, setLoadingDetails] = useState<boolean>(false);

  useEffect(() => {
    if (!estimate) {
      setCurrentEstimate(null);
      return;
    }

    setCurrentEstimate(estimate);

    // Detaylar boşsa veya 5'ten azsa arka planda canlı backend detayını çek
    if (!estimate.details || estimate.details.length < 8) {
      setLoadingDetails(true);
      fetchFundEstimate(estimate.fundCode)
        .then((detailRes) => {
          if (detailRes && detailRes.details && detailRes.details.length > 0) {
            setCurrentEstimate(detailRes);
          }
        })
        .catch((err) => {
          console.warn('Fon detayları alınamadı:', err);
        })
        .finally(() => {
          setLoadingDetails(false);
        });
    }
  }, [estimate]);

  if (!estimate || !currentEstimate) return null;

  const isPositive = currentEstimate.estimatedReturn >= 0;
  const returnPercentage = (currentEstimate.estimatedReturn * 100).toFixed(2);

  // Varlık detaylarını pozitif ve negatif katkılara göre ayır ve sırala
  const allDetails = currentEstimate.details || [];
  
  // Pozitif katkılar (Liderler / Lokomotifler)
  let positiveContributors = [...allDetails]
    .filter((d) => d.weightedContribution > 0)
    .sort((a, b) => b.weightedContribution - a.weightedContribution)
    .slice(0, 5);

  // Negatif katkılar (Baskılayanlar / Aşağı Çekenler)
  let negativeContributors = [...allDetails]
    .filter((d) => d.weightedContribution < 0)
    .sort((a, b) => a.weightedContribution - b.weightedContribution)
    .slice(0, 5);

  // Eğer piyasa tek yönlü hareket ettiyse (tüm hisseler düştü veya tümü yükseldi),
  // modalda 5'er adet kartın tam dolması için en az düşenleri/en çok düşenleri sırala
  if (positiveContributors.length === 0 && allDetails.length > 0) {
    positiveContributors = [...allDetails]
      .sort((a, b) => b.weightedContribution - a.weightedContribution)
      .slice(0, 5);
  }

  if (negativeContributors.length === 0 && allDetails.length > 0) {
    negativeContributors = [...allDetails]
      .sort((a, b) => a.weightedContribution - b.weightedContribution)
      .slice(0, 5);
  }

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center p-4 bg-black/80 backdrop-blur-md animate-in fade-in duration-200">
      <div className="relative w-full max-w-2xl rounded-2xl border border-slate-700/80 bg-slate-900 p-6 shadow-2xl max-h-[90vh] overflow-y-auto">
        {/* KAPAT BUTONU */}
        <button
          onClick={onClose}
          className="absolute right-4 top-4 rounded-xl p-2 text-slate-400 hover:bg-slate-800 hover:text-white transition-colors"
          aria-label="Kapat"
        >
          <X className="h-5 w-5" />
        </button>

        {/* BAŞLIK & TAHMİN ÖZETİ */}
        <div className="flex items-center gap-3.5 mb-6">
          <div
            className={`flex h-12 w-12 items-center justify-center rounded-xl font-mono text-lg font-black shadow-lg ${
              isPositive
                ? 'bg-emerald-500/20 text-emerald-400 border border-emerald-500/30'
                : 'bg-rose-500/20 text-rose-400 border border-rose-500/30'
            }`}
          >
            {currentEstimate.fundCode}
          </div>
          <div>
            <div className="flex items-center gap-2">
              <h2 className="text-lg font-bold text-white tracking-tight">
                {currentEstimate.fundTitle}
              </h2>
              {loadingDetails && (
                <Loader2 className="h-4 w-4 animate-spin text-emerald-400" />
              )}
            </div>
            <p className="text-xs text-slate-400">
              {currentEstimate.manager} • Model Saati: <span className="font-mono text-slate-300 font-semibold">{formatCalculationTime(currentEstimate.calculatedAt)}</span> • Canlı Röntgen
            </p>
          </div>
          <div className="ml-auto text-right pr-8">
            <span className="text-[10px] uppercase font-semibold text-slate-400">
              Tahmini Getiri
            </span>
            <p
              className={`font-mono text-xl font-extrabold ${
                isPositive ? 'text-emerald-400' : 'text-rose-400'
              }`}
            >
              {isPositive ? `+${returnPercentage}%` : `${returnPercentage}%`}
            </p>
            {currentEstimate.currentPrice != null && currentEstimate.estimatedPrice != null && (
              <div className="flex items-center justify-end gap-1 font-mono text-xs mt-1 text-slate-300">
                <span className="text-slate-400">{Number(currentEstimate.currentPrice).toFixed(4)} ₺</span>
                <span className="text-slate-500">➔</span>
                <span className={`font-bold ${isPositive ? 'text-emerald-400' : 'text-rose-400'}`}>
                  {Number(currentEstimate.estimatedPrice).toFixed(4)} ₺
                </span>
              </div>
            )}
          </div>
        </div>

        {/* TOP 5 KAZANDIRAN VE KAYBETTİREN MASASI */}
        <div className="space-y-5">
          {/* 1. TOP 5 LOKOMOTİF (POZİTİF KATKI SAĞLAYANLAR) */}
          <div className="rounded-xl border border-emerald-500/20 bg-emerald-500/5 p-4">
            <div className="flex items-center justify-between mb-3">
              <div className="flex items-center gap-2 text-emerald-400 text-xs font-semibold uppercase tracking-wider">
                <TrendingUp className="h-4 w-4" />
                <span>Günün Lokomotifleri (En Çok Kazandıran 5 Hisse)</span>
              </div>
              <span className="text-[10px] text-emerald-400/80 font-mono">
                5 Varlık
              </span>
            </div>

            {positiveContributors.length > 0 ? (
              <div className="divide-y divide-emerald-500/10 text-xs">
                {positiveContributors.map((item, idx) => (
                  <div
                    key={idx}
                    className="flex items-center justify-between py-2 font-mono"
                  >
                    <div className="flex items-center gap-2 font-sans">
                      <span className="font-bold text-white bg-slate-800 px-2 py-0.5 rounded text-[11px] border border-slate-700">
                        {item.instrumentTicker}
                      </span>
                      <span className="text-slate-300 text-[11px] truncate max-w-[200px]">
                        {item.instrumentTitle}
                      </span>
                    </div>
                    <div className="flex items-center gap-4 text-right">
                      <span className="text-slate-400 text-[11px]">
                        Ağırlık: %{(item.effectiveWeight * 100).toFixed(1)}
                      </span>
                      <span className="text-emerald-400 font-bold tabular-nums">
                        {item.weightedContribution >= 0 ? '+' : ''}
                        {(item.weightedContribution * 100).toFixed(3)}%
                      </span>
                    </div>
                  </div>
                ))}
              </div>
            ) : (
              <p className="text-xs text-slate-500 italic py-2">
                Pozitif katkı sağlayan hisse tespit edilemedi.
              </p>
            )}
          </div>

          {/* 2. TOP 5 AŞAĞI ÇEKEN (NEGATİF BASKILAYAN 5 HİSSE) */}
          <div className="rounded-xl border border-rose-500/20 bg-rose-500/5 p-4">
            <div className="flex items-center justify-between mb-3">
              <div className="flex items-center gap-2 text-rose-400 text-xs font-semibold uppercase tracking-wider">
                <TrendingDown className="h-4 w-4" />
                <span>Aşağı Çekenler (En Çok Düşüş Katkısı Veren 5 Hisse)</span>
              </div>
              <span className="text-[10px] text-rose-400/80 font-mono">
                5 Varlık
              </span>
            </div>

            {negativeContributors.length > 0 ? (
              <div className="divide-y divide-rose-500/10 text-xs">
                {negativeContributors.map((item, idx) => (
                  <div
                    key={idx}
                    className="flex items-center justify-between py-2 font-mono"
                  >
                    <div className="flex items-center gap-2 font-sans">
                      <span className="font-bold text-white bg-slate-800 px-2 py-0.5 rounded text-[11px] border border-slate-700">
                        {item.instrumentTicker}
                      </span>
                      <span className="text-slate-300 text-[11px] truncate max-w-[200px]">
                        {item.instrumentTitle}
                      </span>
                    </div>
                    <div className="flex items-center gap-4 text-right">
                      <span className="text-slate-400 text-[11px]">
                        Ağırlık: %{(item.effectiveWeight * 100).toFixed(1)}
                      </span>
                      <span className="text-rose-400 font-bold tabular-nums">
                        {(item.weightedContribution * 100).toFixed(3)}%
                      </span>
                    </div>
                  </div>
                ))}
              </div>
            ) : (
              <p className="text-xs text-slate-500 italic py-2">
                Negatif katkı sağlayan hisse tespit edilemedi.
              </p>
            )}
          </div>
        </div>

        {/* ALT BİLGİ VE TAM DETAY SAYFASINA YÖNLENDİRME */}
        <div className="mt-6 pt-4 border-t border-slate-800 flex flex-col sm:flex-row sm:items-center justify-between gap-3">
          <p className="text-[11px] text-slate-400">
            * Katkı = Ağırlık ($w_i$) × Gün İçi Getiri ($r_i$) formülüyle anlık hesaplanır.
          </p>
          <Link
            href={`/funds/${currentEstimate.fundCode}`}
            onClick={onClose}
            className="flex items-center justify-center gap-2 rounded-xl bg-emerald-600 hover:bg-emerald-500 px-4 py-2 text-xs font-bold text-white shadow-lg transition-all"
          >
            <span>Detaylı Analiz & Backtest Grafiği</span>
            <ArrowRight className="h-4 w-4" />
          </Link>
        </div>
      </div>
    </div>
  );
}
