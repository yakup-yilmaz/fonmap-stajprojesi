'use client';

import React, { useState, useEffect } from 'react';
import {
  PieChart,
  Pie,
  Cell,
  ResponsiveContainer,
  Tooltip,
  Legend,
} from 'recharts';

interface AssetAllocationItem {
  name: string;
  value: number; // Yüzde, örn: 79.18
  amountTl?: number;
  color: string;
}

interface PortfolioPieChartProps {
  stockRatio?: number;
  viopCashRatio?: number;
  totalNetAssetValue?: number;
}

const COLORS = [
  '#10b981', // Hisse Senedi (Zümrüt Yeşili)
  '#6366f1', // VİOP Nakit Teminat (İndigo)
  '#f59e0b', // Ters Repo / Para Piyasası (Amber)
  '#06b6d4', // Özel Sektör Bonosu (Camgöbeği)
  '#ec4899', // Diğer Fon Katılma Payları (Pembe)
];

export default function PortfolioPieChart({
  stockRatio,
  viopCashRatio,
  totalNetAssetValue,
}: PortfolioPieChartProps) {
  const [isMounted, setIsMounted] = useState(false);

  useEffect(() => {
    setIsMounted(true);
  }, []);

  // Gerçek dağılım verisi yoksa uydurma değer çizilmez.
  if (stockRatio == null || viopCashRatio == null || totalNetAssetValue == null) {
    return (
      <div className="flex h-72 items-center justify-center rounded-2xl border border-slate-800 bg-slate-900/50 text-xs text-slate-500">
        Portföy dağılım verisi henüz mevcut değil.
      </div>
    );
  }

  const safeStockRatio = Number(stockRatio);
  const safeViopRatio = Number(viopCashRatio);
  const safeTnv = Number(totalNetAssetValue);

  // Portföy varlık dağılım verisini hazırla
  const otherRatio = Math.max(0, 100 - (safeStockRatio + safeViopRatio));

  const data: AssetAllocationItem[] = [
    {
      name: 'BIST Hisse Senetleri',
      value: Number(safeStockRatio.toFixed(2)),
      amountTl: (safeTnv * safeStockRatio) / 100,
      color: COLORS[0],
    },
    {
      name: 'Takasbank VİOP Nakit Teminatı',
      value: Number(safeViopRatio.toFixed(2)),
      amountTl: (safeTnv * safeViopRatio) / 100,
      color: COLORS[1],
    },
  ];

  if (otherRatio > 0.05) {
    data.push({
      name: 'Ters Repo & Diğer Likit',
      value: Number(otherRatio.toFixed(2)),
      amountTl: (safeTnv * otherRatio) / 100,
      color: COLORS[2],
    });
  }

  if (!isMounted) {
    return (
      <div className="flex h-72 items-center justify-center rounded-2xl border border-slate-800 bg-slate-900/50 text-xs text-slate-500">
        Grafik yükleniyor...
      </div>
    );
  }

  const formatCurrency = (val?: number) => {
    if (!val) return '0 ₺';
    if (val >= 1e9) return `${(val / 1e9).toFixed(2)} Milyar ₺`;
    if (val >= 1e6) return `${(val / 1e6).toFixed(2)} Milyon ₺`;
    return `${val.toLocaleString('tr-TR')} ₺`;
  };

  return (
    <div className="rounded-2xl border border-slate-800 bg-slate-900/60 p-5 backdrop-blur-md">
      <div className="flex items-center justify-between mb-4">
        <div>
          <h3 className="text-sm font-bold text-white tracking-tight">
            Portföy Varlık Sınıfı Dağılımı
          </h3>
          <p className="text-xs text-slate-400">
            Resmi KAP Portföy Raporu Üst Dağılımı
          </p>
        </div>
        <div className="text-right">
          <span className="text-[10px] uppercase font-semibold text-slate-400">
            Toplam Portföy (TNV)
          </span>
          <p className="font-mono text-xs font-bold text-emerald-400">
            {formatCurrency(totalNetAssetValue)}
          </p>
        </div>
      </div>

      <div className="h-64 w-full">
        <ResponsiveContainer width="100%" height="100%">
          <PieChart>
            <Pie
              data={data}
              cx="50%"
              cy="50%"
              innerRadius={55}
              outerRadius={80}
              paddingAngle={4}
              dataKey="value"
            >
              {data.map((entry, index) => (
                <Cell
                  key={`cell-${index}`}
                  fill={entry.color}
                  stroke="#0f172a"
                  strokeWidth={2}
                />
              ))}
            </Pie>
            <Tooltip
              content={({ active, payload }) => {
                if (active && payload && payload.length) {
                  const item = payload[0].payload as AssetAllocationItem;
                  return (
                    <div className="rounded-xl border border-slate-700 bg-slate-950 p-3 shadow-xl">
                      <p className="font-semibold text-xs text-white">
                        {item.name}
                      </p>
                      <p className="font-mono text-sm font-bold text-emerald-400 mt-1">
                        %{item.value.toFixed(2)}
                      </p>
                      {item.amountTl && (
                        <p className="font-mono text-[11px] text-slate-400 mt-0.5">
                          {formatCurrency(item.amountTl)}
                        </p>
                      )}
                    </div>
                  );
                }
                return null;
              }}
            />
            <Legend
              formatter={(value, entry: any) => (
                <span className="text-xs text-slate-300 font-medium ml-1">
                  {value} (%{entry.payload.value})
                </span>
              )}
            />
          </PieChart>
        </ResponsiveContainer>
      </div>
    </div>
  );
}
