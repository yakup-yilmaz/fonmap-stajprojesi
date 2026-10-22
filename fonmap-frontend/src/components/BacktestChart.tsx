'use client';

import React, { useState, useEffect } from 'react';
import {
  ResponsiveContainer,
  ComposedChart,
  Line,
  Area,
  XAxis,
  YAxis,
  CartesianGrid,
  Tooltip,
  Legend,
  ReferenceLine,
} from 'recharts';
import { DailyBacktestRow } from '@/types/fonmap';
import { Calendar, TrendingUp, CheckCircle2, AlertCircle, Clock } from 'lucide-react';

interface BacktestChartProps {
  data: DailyBacktestRow[];
  mae?: number;
  rmse?: number;
  directionalAccuracy?: number;
  reportDate?: string;
  reportDownloadedAt?: string;
  fundCode?: string;
}

type TimeRange = '1W' | '1M' | '2M';

export default function BacktestChart({
  data = [],
  mae = 0.001677,
  rmse = 0.001837,
  directionalAccuracy = 100.0,
  reportDate,
  reportDownloadedAt,
  fundCode,
}: BacktestChartProps) {
  const [isMounted, setIsMounted] = useState(false);
  const [timeRange, setTimeRange] = useState<TimeRange>('1W');

  useEffect(() => {
    setIsMounted(true);
  }, []);

  // Zaman aralığına göre veriyi filtrele (Kayan 30 İş Günü Penceresi)
  const filteredData = React.useMemo(() => {
    if (!data || data.length === 0) return [];
    const sorted = [...data].sort(
      (a, b) => new Date(a.date).getTime() - new Date(b.date).getTime()
    );

    if (timeRange === '1W') {
      // 1 Hafta: Son 5 işlem günü kayan penceresi
      return sorted.slice(-5);
    }
    if (timeRange === '1M') {
      // 1 Ay: Son 22 işlem günü kayan penceresi
      return sorted.slice(-22);
    }
    // 2 Ay: Son 44-60 işlem günü kayan penceresi
    return sorted.slice(-44);
  }, [data, timeRange]);

  // Kayan Pencere (Rolling Window) Dinamik Metrikleri (1H, 1A, 2A)
  const windowMetrics = React.useMemo(() => {
    if (!filteredData || filteredData.length === 0) {
      return { mae, rmse, directionalAccuracy };
    }
    let sumAbs = 0;
    let sumSq = 0;
    let matched = 0;
    let validCount = 0;

    filteredData.forEach((row) => {
      if (row.actualReturn != null && row.predictedReturn != null) {
        const err = Math.abs(row.predictedReturn - row.actualReturn);
        sumAbs += err;
        sumSq += err * err;
        if (
          (row.predictedReturn >= 0 && row.actualReturn >= 0) ||
          (row.predictedReturn <= 0 && row.actualReturn <= 0)
        ) {
          matched++;
        }
        validCount++;
      }
    });

    if (validCount === 0) return { mae, rmse, directionalAccuracy };
    return {
      mae: sumAbs / validCount,
      rmse: Math.sqrt(sumSq / validCount),
      directionalAccuracy: (matched / validCount) * 100,
    };
  }, [filteredData, mae, rmse, directionalAccuracy]);

  // Recharts için yüzdelik dönüştürülmüş format
  const chartData = filteredData.map((row) => {
    const isMissing = row.actualReturn === null;
    return {
      date: (row.date || '').length > 5 ? row.date.slice(5) : row.date || '',
      fullDate: row.date || '',
      predicted: Number(((row.predictedReturn ?? 0) * 100).toFixed(2)),
      actual: isMissing ? null : Number(((row.actualReturn ?? 0) * 100).toFixed(2)),
      previousTefasPrice: row.previousTefasPrice != null ? Number(row.previousTefasPrice) : null,
      actualTefasPrice: row.actualTefasPrice != null ? Number(row.actualTefasPrice) : null,
      estimatedTefasPrice: row.estimatedTefasPrice != null ? Number(row.estimatedTefasPrice) : null,
      errorDiffBps: isMissing || row.errorDiff == null ? null : Number((row.errorDiff * 10000).toFixed(1)),
      directionMatched: row.directionMatched,
      isMissing,
      snapshotTransitionDay: row.snapshotTransitionDay ?? false,
      transitionRemark: row.transitionRemark ?? null,
      status: isMissing
        ? '⚪ Değer Yok'
        : (row.directionMatched ? '🟢 Tescillendi (Yön Doğru)' : '🟡 Tescillendi (Ters Yön)'),
    };
  });

  // Dinamik periyot etiketi
  const periodLabel = React.useMemo(() => {
    if (filteredData.length === 0) return '';
    const first = filteredData[0].date;
    const last = filteredData[filteredData.length - 1].date;
    return `${first} — ${last} (${filteredData.length} İŞ GÜNÜ)`;
  }, [filteredData]);

  if (!isMounted) {
    return (
      <div className="flex h-80 items-center justify-center rounded-2xl border border-slate-800 bg-slate-900/50 text-xs text-slate-500">
        Backtest grafiği yükleniyor...
      </div>
    );
  }

    // =========================================================================
  // 🌟 YENİ FON: HENÜZ HİÇBİR SEANS KAPANISI GERÇEKLEŞMEMİŞSE (0 GÜN)
  // =========================================================================
  if (!data || data.length === 0) {
    return (
      <div className="rounded-2xl border border-indigo-500/30 bg-gradient-to-br from-slate-900/90 via-slate-900/60 to-indigo-950/20 p-6 backdrop-blur-md shadow-2xl space-y-6">
        <div className="flex flex-col sm:flex-row sm:items-center justify-between gap-4 border-b border-slate-800 pb-5">
          <div className="flex items-start gap-3">
            <div className="flex h-10 w-10 shrink-0 items-center justify-center rounded-xl bg-indigo-500/20 text-indigo-400 border border-indigo-500/30">
              <Clock className="h-5 w-5" />
            </div>
            <div>
              <div className="flex items-center gap-2">
                <h3 className="text-base font-bold text-white tracking-tight">
                  Model Performans & Hata Grafiği: İlk Seans Bekleniyor
                </h3>
                <span className="rounded bg-indigo-500/10 px-2 py-0.5 text-[10px] font-bold text-indigo-400 border border-indigo-500/20">
                  YENİ FON KAYDI
                </span>
              </div>
              <p className="text-xs text-slate-400 mt-1">
                Bu fon sisteme en güncel portföy dağılım PDF'i ile yeni dahil edilmiştir. İlk borsa seansı tamamlandığında ve 23:00 TEFAS resmi kapanış fiyatı tescillendiğinde 1. günden itibaren grafik ve MAE metrikleri burada çizilmeye başlanacaktır.
              </p>
            </div>
          </div>
          <div className="flex flex-wrap items-center gap-2 text-xs">
            <span className="inline-flex items-center gap-1.5 rounded-xl bg-slate-800/80 px-3 py-1.5 text-slate-300 border border-slate-700 font-mono">
              <Calendar className="h-3.5 w-3.5 text-indigo-400" />
              <span>Rapor Dönemi: {reportDate || '31 Ağustos 2026'}</span>
            </span>
          </div>
        </div>
      </div>
    );
  }

  return (
    <div className="rounded-2xl border border-slate-800 bg-slate-900/60 p-5 backdrop-blur-md">
      {/* ÜST BAŞLIK VE ZAMAN ARALIĞI BUTONLARI */}
      <div className="flex flex-col sm:flex-row sm:items-center justify-between gap-4 mb-6">
        <div>
          <div className="flex items-center gap-2">
            <h3 className="text-base font-bold text-white tracking-tight">
              Fiyat & Getiri Dalgalanması: Model Tahmini vs. TEFAS Kapanışı
            </h3>
            {periodLabel && (
              <span className="rounded bg-indigo-500/10 px-2 py-0.5 text-[10px] font-semibold text-indigo-400 border border-indigo-500/20 font-mono">
                {periodLabel}
              </span>
            )}
          </div>
          <p className="text-xs text-slate-400 mt-1">
            KAP portföyünün kesinleştiği dönem boyunca seans sonu model tahmini ve TEFAS resmi kapanış mutabakatı.
          </p>
        </div>

        {/* ZAMAN ARALIĞI BUTONLARI */}
        <div className="flex items-center gap-1 rounded-xl bg-slate-800/80 p-1 border border-slate-700/60 self-start sm:self-auto">
          <button
            onClick={() => setTimeRange('1W')}
            className={`rounded-lg px-3 py-1.5 text-xs font-semibold transition-all ${
              timeRange === '1W'
                ? 'bg-emerald-500 text-slate-950 shadow-md shadow-emerald-500/20'
                : 'text-slate-300 hover:text-white'
            }`}
          >
            1 Hafta (Son 7 Gün)
          </button>
          <button
            onClick={() => setTimeRange('1M')}
            className={`rounded-lg px-3 py-1.5 text-xs font-semibold transition-all ${
              timeRange === '1M'
                ? 'bg-emerald-500 text-slate-950 shadow-md shadow-emerald-500/20'
                : 'text-slate-300 hover:text-white'
            }`}
          >
            1 Ay (Kayan 30 Gün)
          </button>
          <button
            onClick={() => setTimeRange('2M')}
            className={`rounded-lg px-3 py-1.5 text-xs font-semibold transition-all ${
              timeRange === '2M'
                ? 'bg-emerald-500 text-slate-950 shadow-md shadow-emerald-500/20'
                : 'text-slate-300 hover:text-white'
            }`}
          >
            2 Ay (Kayan 60 Gün)
          </button>
        </div>
      </div>

      {/* RECHARTS GRAFİK ALANI */}
      <div className="h-72 w-full">
        <ResponsiveContainer width="100%" height="100%">
          <ComposedChart
            data={chartData}
            margin={{ top: 10, right: 10, left: -20, bottom: 0 }}
          >
            <defs>
              <linearGradient id="actualGradient" x1="0" y1="0" x2="0" y2="1">
                <stop offset="5%" stopColor="#10b981" stopOpacity={0.25} />
                <stop offset="95%" stopColor="#10b981" stopOpacity={0.0} />
              </linearGradient>
            </defs>
            <CartesianGrid strokeDasharray="3 3" stroke="#1e293b" vertical={false} />
            <XAxis
              dataKey="date"
              stroke="#64748b"
              fontSize={11}
              tickLine={false}
              axisLine={{ stroke: '#334155' }}
            />
            <YAxis
              stroke="#64748b"
              fontSize={11}
              tickLine={false}
              axisLine={{ stroke: '#334155' }}
              tickFormatter={(v) => `%${v}`}
            />
            <Tooltip
              content={({ active, payload }) => {
                if (active && payload && payload.length) {
                  const d = payload[0].payload;
                  return (
                    <div className="rounded-xl border border-slate-700 bg-slate-950/95 p-3.5 shadow-2xl backdrop-blur-md min-w-[220px]">
                      <div className="flex items-center justify-between gap-4 border-b border-slate-800 pb-2 mb-2">
                        <span className="font-mono text-xs font-bold text-white flex items-center gap-1.5">
                          <Calendar className="h-3.5 w-3.5 text-slate-400" />
                          {d.fullDate}
                        </span>
                        <span className={`text-[10px] font-semibold px-2 py-0.5 rounded ${
                          d.isMissing
                            ? 'bg-slate-800 text-slate-400 border border-slate-700'
                            : 'bg-emerald-500/20 text-emerald-300 border border-emerald-500/30'
                        }`}>
                          {d.status}
                        </span>
                      </div>

                      <div className="space-y-1.5 text-xs font-mono">
                        <div className="flex items-center justify-between gap-4">
                          <span className="text-slate-400">Model Tahmini:</span>
                          <span className="font-bold text-indigo-400">
                            {d.estimatedTefasPrice != null ? `${d.estimatedTefasPrice.toFixed(4)} TL ` : ''}
                            ({d.predicted > 0 ? `+${d.predicted}%` : `${d.predicted}%`})
                          </span>
                        </div>
                        <div className="flex items-center justify-between gap-4">
                          <span className="text-slate-400">TEFAS Kapanış:</span>
                          {d.isMissing ? (
                            <span className="font-medium text-slate-400 italic">
                              ⚪ Değer Yok / Açıklanmadı
                            </span>
                          ) : (
                            <span className="font-bold text-emerald-400">
                              {d.actualTefasPrice != null ? `${d.actualTefasPrice.toFixed(4)} TL ` : ''}
                              ({d.actual > 0 ? `+${d.actual}%` : `${d.actual}%`})
                            </span>
                          )}
                        </div>
                        {d.previousTefasPrice != null && (
                          <div className="flex items-center justify-between gap-4 text-[11px] text-slate-400">
                            <span>Dünkü Çapa (P_t-1):</span>
                            <span className="font-mono text-slate-300">{d.previousTefasPrice.toFixed(4)} TL</span>
                          </div>
                        )}
                        <div className="flex items-center justify-between gap-4 pt-1 border-t border-slate-800/80 text-[11px]">
                          <span className="text-slate-500">Sapma Farkı:</span>
                          {d.isMissing || d.errorDiffBps == null ? (
                            <span className="text-slate-500 italic">
                              — (Tescil Bekleniyor)
                            </span>
                          ) : (
                            <span className="text-slate-300 font-semibold">
                              {d.errorDiffBps > 0 ? `+${d.errorDiffBps}` : d.errorDiffBps} bps
                            </span>
                          )}
                        </div>

                        {d.snapshotTransitionDay && (
                          <div className="mt-2.5 rounded-lg bg-amber-500/15 border border-amber-500/30 p-2 text-[10px] text-amber-300 font-sans flex items-start gap-1.5 leading-relaxed">
                            <span className="font-bold shrink-0">🔄 Rapor Geçişi:</span>
                            <span>{d.transitionRemark || 'Yeni portföy dağılımı yürürlüğe girdi. Ağırlık kayması sıfırlandı.'}</span>
                          </div>
                        )}
                      </div>
                    </div>
                  );
                }
                return null;
              }}
            />
            <Legend
              verticalAlign="top"
              align="right"
              iconType="circle"
              wrapperStyle={{ paddingBottom: '12px', fontSize: '12px' }}
            />
            <ReferenceLine y={0} stroke="#475569" strokeDasharray="3 3" />
            <Area
              type="monotone"
              dataKey="actual"
              name="TEFAS Resmi Kapanış Getirisi"
              fill="url(#actualGradient)"
              stroke="#10b981"
              strokeWidth={2}
              connectNulls={false}
            />
            <Line
              type="monotone"
              dataKey="predicted"
              name="Model Seans Sonu Tahmini"
              stroke="#818cf8"
              strokeWidth={2}
              strokeDasharray="4 4"
              dot={{ r: 3, fill: '#818cf8', strokeWidth: 0 }}
              activeDot={{ r: 5 }}
              connectNulls={true}
            />
          </ComposedChart>
        </ResponsiveContainer>
      </div>

      {/* ALT EKONOMETRİK ÖZET ROZETLERİ */}
      <div className="mt-4 grid grid-cols-3 gap-3 border-t border-slate-800/80 pt-4 text-center">
        <div className="rounded-xl border border-slate-800 bg-slate-950/40 p-2.5">
          <span className="text-[10px] font-medium uppercase tracking-wider text-slate-400">
            Ortalama Hata (MAE)
          </span>
          <p className="font-mono text-sm font-bold text-white mt-0.5">
            %{(windowMetrics.mae * 100).toFixed(3)}
          </p>
        </div>
        <div className="rounded-xl border border-slate-800 bg-slate-950/40 p-2.5">
          <span className="text-[10px] font-medium uppercase tracking-wider text-slate-400">
            Volatilite Cezası (RMSE)
          </span>
          <p className="font-mono text-sm font-bold text-white mt-0.5">
            %{(windowMetrics.rmse * 100).toFixed(3)}
          </p>
        </div>
        <div className="rounded-xl border border-emerald-500/20 bg-emerald-500/5 p-2.5">
          <span className="text-[10px] font-medium uppercase tracking-wider text-emerald-400">
            Yönsel Doğruluk
          </span>
          <p className="font-mono text-sm font-bold text-emerald-400 mt-0.5">
            %{windowMetrics.directionalAccuracy.toFixed(1)}
          </p>
        </div>
      </div>

      {/* 30 EYLÜL (BUGÜN) DURUMU VE 23:00 RESMİ MUTABAKAT BİLGİLENDİRMESİ */}
      <div className="mt-4 rounded-xl border border-indigo-500/20 bg-indigo-500/5 p-3 flex items-start gap-2.5 text-xs text-slate-300">
        <Clock className="h-4 w-4 text-indigo-400 mt-0.5 shrink-0" />
        <div>
          <span className="font-semibold text-indigo-400">30 Eylül (Bugün) Seans Kapanışı Hakkında: </span>
          <span>
            30 Eylül seans sonu model tahminimiz üst kartta canlı olarak sunulmaktadır. BIST seansı 18:10&apos;da tamamlanmış olup, resmi TEFAS kapanış birim fiyatı bu gece saat <strong>23:00</strong> civarında Takasbank tarafından ilan edilecektir. Gece mutabakat motoru çalıştığında 30 Eylül kesinleşmiş verisi 15. gün olarak bu tabloya eklenecektir.
          </span>
        </div>
      </div>
    </div>
  );
}
