'use client';

import React from 'react';
import {
  CheckCircle2,
  AlertTriangle,
  AlertOctagon,
  Target,
  Award,
  Layers,
  HelpCircle,
} from 'lucide-react';
import { BacktestReport } from '@/types/fonmap';

interface BacktestToleranceMatrixProps {
  report: BacktestReport | null;
}

export default function BacktestToleranceMatrix({
  report,
}: BacktestToleranceMatrixProps) {
  if (!report) {
    return (
      <div className="rounded-2xl border border-slate-800 bg-slate-900/50 p-6 text-center text-sm text-slate-400">
        Bu fon için henüz geriye dönük mutabakat kaydı bulunamadı.
      </div>
    );
  }

  const code = (report.fundCode || '').toUpperCase();
  const cat = (report.category || '').toUpperCase();

  const isTechCategory = cat.includes('TEKNOLOJİ') || cat.includes('YÜKSEK BETA') || code === 'TTE' || code === 'ICZ';
  const isMoneyMarket = !isTechCategory && (cat.includes('LİKİT') || cat.includes('PARA') || code === 'TI1' || code === 'TI2');
  const isBondCategory = !isTechCategory && !isMoneyMarket && (cat.includes('BORÇ') || cat.includes('TAHVİL') || cat.includes('BONO'));
  const isBroadEquity = !isTechCategory && !isMoneyMarket && !isBondCategory && (cat.includes('HİSSE') || code === 'THF' || code === 'KHA' || code === 'GAE');
  const isVariable = !isTechCategory && !isMoneyMarket && !isBondCategory && !isBroadEquity;

  const categoryTolerances = [
    {
      category: 'Teknoloji / Yüksek Beta Tematik (TTE, ICZ)',
      rule: 'Teknoloji hisse payı ≥ %80 (Beta ≥ 1.2)',
      normal: '≤ %1.25 (125 bps)',
      strict: '≤ %0.50 (50 bps)',
      reason: 'BIST teknoloji hisseleri seans içi %4-%7 dalgalandığı için 125 bps sapma makuldür.',
      isCurrent: isTechCategory,
    },
    {
      category: 'Geniş Tabanlı Hisse Senedi (THF, KHA, GAE)',
      rule: 'Hisse payı ≥ %50',
      normal: '≤ %0.50 (50 bps)',
      strict: '≤ %0.20 (20 bps)',
      reason: 'BIST hisseleri gün içi %3-%5 dalgalandığı için 50 bps sapma normal karşılanır.',
      isCurrent: isBroadEquity,
    },
    {
      category: 'Para Piyasası / Likit Fonlar (TI1, TI2)',
      rule: 'Repo/Nakit ≥ %75',
      normal: '≤ %0.05 (5 bps)',
      strict: '≤ %0.02 (2 bps)',
      reason: 'Sabit faizli durağan yapı; hata payı binde 0.5\'i kesinlikle geçemez.',
      isCurrent: isMoneyMarket,
    },
    {
      category: 'Borçlanma Araçları Fonları',
      rule: 'Bono/Tahvil ≥ %40',
      normal: '≤ %0.20 (20 bps)',
      strict: '≤ %0.10 (10 bps)',
      reason: 'Tahvil ve bono kupon faizlerine duyarlılık nedeniyle %0.20 makuldür.',
      isCurrent: isBondCategory,
    },
    {
      category: 'Değişken / Serbest (TLY, TMV, DFI)',
      rule: 'Diğer tüm karma yapılar',
      normal: '≤ %0.35 (35 bps)',
      strict: '≤ %0.15 (15 bps)',
      reason: 'Dengeli çoklu varlık portföyü; 35 bps sapma standart kabul edilir.',
      isCurrent: isVariable,
    },
  ];

  return (
    <div className="space-y-6">
      {/* 🛡️ ZİNCİR BÜTÜNLÜĞÜ VE BOŞLUK KALKANI BİLDİRİMİ */}
      {report.requiresNewerSnapshot && (
        <div className="rounded-2xl border border-amber-500/40 bg-gradient-to-r from-amber-500/10 via-amber-950/20 to-slate-900/60 p-4.5 backdrop-blur-md shadow-xl flex items-start gap-3.5">
          <div className="p-2 rounded-xl bg-amber-500/20 text-amber-400 border border-amber-500/30 shrink-0 mt-0.5">
            <AlertTriangle className="h-5 w-5" />
          </div>
          <div className="space-y-1">
            <div className="flex items-center gap-2">
              <h4 className="text-sm font-bold text-amber-300 tracking-tight">
                Kısmi Simülasyon Tamamlandı (Son Rapor Bekleniyor)
              </h4>
              <span className="rounded bg-amber-500/20 px-2 py-0.5 text-[10px] font-bold text-amber-300 border border-amber-500/30 font-mono">
                GÜVENLİ SINIR: {report.safeSimulationEndDate || report.endDate}
              </span>
            </div>
            <p className="text-xs text-slate-300 leading-relaxed">
              {report.integrityWarningMessage ||
                "Sistemdeki portföy raporunun üzerinden 35 günden fazla süre geçtiği için simülasyon son geçerli sınırda durduruldu. Canlı seansa bağlanmak için lütfen en güncel portföy dağılım raporunu sisteme yükleyiniz."}
            </p>
          </div>
        </div>
      )}

      {report.chainStatus === 'INTERMEDIATE_GAP_DETECTED' && !report.requiresNewerSnapshot && (
        <div className="rounded-2xl border border-rose-500/40 bg-gradient-to-r from-rose-500/10 via-rose-950/20 to-slate-900/60 p-4.5 backdrop-blur-md shadow-xl flex items-start gap-3.5">
          <div className="p-2 rounded-xl bg-rose-500/20 text-rose-400 border border-rose-500/30 shrink-0 mt-0.5">
            <AlertOctagon className="h-5 w-5" />
          </div>
          <div className="space-y-1">
            <h4 className="text-sm font-bold text-rose-300 tracking-tight">
              Ara Dönem Portföy Boşluğu Tespit Edildi
            </h4>
            <p className="text-xs text-slate-300 leading-relaxed">
              {report.integrityWarningMessage ||
                "Yüklenen snapshot'lar arasında atlanmış bir ay bulunmaktadır. Doğru ve kesintisiz simülasyon için lütfen ara döneme ait portföy dağılım raporunu da sisteme ekleyiniz."}
            </p>
          </div>
        </div>
      )}

      {report.chainStatus === 'VALID' && !report.requiresNewerSnapshot && (
        <div className="rounded-xl border border-emerald-500/30 bg-emerald-500/5 px-4 py-2.5 flex items-center justify-between text-xs">
          <div className="flex items-center gap-2 text-emerald-300 font-medium">
            <CheckCircle2 className="h-4 w-4 text-emerald-400" />
            <span>Portföy Rapor Zinciri Eksiksiz: Tüm aylar bağlı ve en güncel resmi rapor yürürlükte.</span>
          </div>
          <span className="font-mono text-[11px] text-emerald-400/80 bg-emerald-500/10 px-2 py-0.5 rounded border border-emerald-500/20">
            {report.totalDays} İŞ GÜNÜ DOĞRULANDI
          </span>
        </div>
      )}

      {/* 4 KADEMELİ EKONOMETRİK MODEL BAŞARI KARNESİ */}
      <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-4 gap-4">
        {/* 1. ORTALAMA MUTLAK HATA (MAE) */}
        <div className="glass-card rounded-2xl border border-indigo-500/30 bg-indigo-500/5 p-4 relative overflow-hidden">
          <div className="flex items-center justify-between">
            <span className="text-xs font-semibold uppercase tracking-wider text-indigo-400">
              Ortalama Hata (MAE)
            </span>
            <Target className="h-5 w-5 text-indigo-400" />
          </div>
          <div className="mt-3 flex items-baseline gap-2">
            <span className="font-mono text-3xl font-extrabold text-white">
              %{(Number(report.mae ?? 0) * 100).toFixed(3)}
            </span>
          </div>
          <p className="mt-1 text-[11px] text-indigo-300/80">
            Modelin ortalama tahmin sapma büyüklüğü
          </p>
        </div>

        {/* 2. ŞOK VE VOLATİLİTE RİSKİ (RMSE) */}
        <div className="glass-card rounded-2xl border border-teal-500/30 bg-teal-500/5 p-4 relative overflow-hidden">
          <div className="flex items-center justify-between">
            <span className="text-xs font-semibold uppercase tracking-wider text-teal-400">
              Volatilite Riski (RMSE)
            </span>
            <Award className="h-5 w-5 text-teal-400" />
          </div>
          <div className="mt-3 flex items-baseline gap-2">
            <span className="font-mono text-3xl font-extrabold text-white">
              %{(Number(report.rmse ?? 0) * 100).toFixed(3)}
            </span>
          </div>
          <p className="mt-1 text-[11px] text-teal-300/80">
            Kök ortalama kare hata (Büyük sapmaları cezalandırır)
          </p>
        </div>

        {/* 3. YÖNSEL DOĞRULUK (% DIRECTIONAL ACCURACY) */}
        <div className="glass-card rounded-2xl border border-emerald-500/30 bg-emerald-500/5 p-4 relative overflow-hidden">
          <div className="flex items-center justify-between">
            <span className="text-xs font-semibold uppercase tracking-wider text-emerald-400">
              Yönsel İsabet Oranı
            </span>
            <CheckCircle2 className="h-5 w-5 text-emerald-400" />
          </div>
          <div className="mt-3 flex items-baseline gap-2">
            <span className="font-mono text-3xl font-extrabold text-emerald-400">
              %{Number(report.directionalAccuracy ?? 0).toFixed(1)}
            </span>
          </div>
          <p className="mt-1 text-[11px] text-emerald-300/80">
            Piyasa yönünü (artı/eksi trendi) doğru tahmin etme oranı
          </p>
        </div>

        {/* 4. TESCİLLİ SEANS SAYISI / KAPSAMA */}
        <div className="glass-card rounded-2xl border border-slate-700/60 bg-slate-800/40 p-4 relative overflow-hidden">
          <div className="flex items-center justify-between">
            <span className="text-xs font-semibold uppercase tracking-wider text-slate-300">
              Takasbank Tescilli Seans
            </span>
            <Layers className="h-5 w-5 text-slate-400" />
          </div>
          <div className="mt-3 flex items-baseline gap-2">
            <span className="font-mono text-3xl font-extrabold text-white">
              {report.certifiedDaysCount ?? (report.totalDays - (report.missingDaysCount ?? 0))}
            </span>
            <span className="text-xs text-slate-400 font-mono">
              / {report.totalDays ?? 15} seans
            </span>
          </div>
          <p className="mt-1 text-[11px] text-slate-400">
            {report.missingDaysCount ?? 0} seans resmi kurucu ilanı bekleniyor
          </p>
        </div>
      </div>

      {/* 2 GERÇEK FİYAT KARŞILAŞTIRMA KRİTERİ VE KATEGORİ TOLERANS TABLOSU */}
      <div className="rounded-2xl border border-slate-800 bg-slate-900/60 p-5 backdrop-blur-md">
        <div className="flex flex-col sm:flex-row sm:items-center justify-between gap-2 mb-4">
          <div>
            <h3 className="text-sm font-bold text-white tracking-tight flex items-center gap-2">
              <Layers className="h-4 w-4 text-emerald-400" />
              <span>Admin Ekonometrik Kapsama ve Başarı Parametreleri (Proje Kılavuzu 10.6)</span>
            </h3>
            <p className="text-xs text-slate-400">
              Fonun iç varlık sınıfı yapısına göre uygulanan niceliksel portföy takip parametreleri
            </p>
          </div>
          <div className="flex items-center gap-3 text-xs font-mono">
            <span className="text-slate-400">
              MAE: <strong className="text-white">%{(Number(report.mae ?? 0) * 100).toFixed(3)}</strong>
            </span>
            <span className="text-slate-400">
              RMSE: <strong className="text-white">%{(Number(report.rmse ?? 0) * 100).toFixed(3)}</strong>
            </span>
          </div>
        </div>

        <div className="overflow-x-auto">
          <table className="w-full text-left text-xs">
            <thead>
              <tr className="border-b border-slate-800 text-[11px] font-semibold uppercase tracking-wider text-slate-400">
                <th className="py-2.5 px-3">Fon Kategorisi</th>
                <th className="py-2.5 px-3">Portföy Kuralı</th>
                <th className="py-2.5 px-3">Beklenen MAE Bandı</th>
                <th className="py-2.5 px-3">Beklenen RMSE Bandı</th>
                <th className="py-2.5 px-3">Ekonometrik Dinamik ve Gerekçe</th>
                <th className="py-2.5 px-3 text-right">Durum</th>
              </tr>
            </thead>
            <tbody className="divide-y divide-slate-800/60 font-mono">
              {categoryTolerances.map((item, idx) => (
                <tr
                  key={idx}
                  className={`transition-colors ${
                    item.isCurrent
                      ? 'bg-emerald-500/10 text-white font-semibold'
                      : 'text-slate-300 hover:bg-slate-800/30'
                  }`}
                >
                  <td className="py-3 px-3 font-sans font-medium flex items-center gap-2">
                    {item.isCurrent && (
                      <span className="h-2 w-2 rounded-full bg-emerald-400 animate-pulse" />
                    )}
                    {item.category}
                  </td>
                  <td className="py-3 px-3 text-slate-400">{item.rule}</td>
                  <td className="py-3 px-3 text-amber-400 font-bold">{item.normal}</td>
                  <td className="py-3 px-3 text-emerald-400 font-bold">{item.strict}</td>
                  <td className="py-3 px-3 font-sans text-xs text-slate-300">
                    {item.reason}
                  </td>
                  <td className="py-3 px-3 text-right font-sans">
                    {item.isCurrent ? (
                      <span className="rounded bg-emerald-500/20 px-2 py-0.5 text-[10px] font-bold text-emerald-300 border border-emerald-500/30">
                        UYGULANAN
                      </span>
                    ) : (
                      <span className="text-slate-500 text-[11px]">-</span>
                    )}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </div>

      {/* GÜNLÜK SATIR SATIR TEFAS MUTABAKAT KARŞILAŞTIRMA GÜNLÜĞÜ */}
      <div className="rounded-2xl border border-slate-800 bg-slate-900/60 p-5 backdrop-blur-md">
        <div className="flex flex-col sm:flex-row sm:items-center justify-between gap-2 mb-3">
          <h4 className="text-xs font-bold uppercase tracking-wider text-slate-300">
            Günlük TEFAS Kapanış Mutabakat Günlüğü (10 - 30 Eylül Seansları)
          </h4>
          <span className="text-[11px] text-slate-400">
            Takasbank Resmi Kapanışları &middot; ⚪ Değer Yok: Resmi fiyat ilanı bekleniyor
          </span>
        </div>

        <div className="max-h-64 overflow-y-auto">
          <table className="w-full text-left text-xs font-mono">
            <thead className="sticky top-0 bg-slate-900 border-b border-slate-800 text-[10px] uppercase text-slate-400">
              <tr>
                <th className="py-2 px-3">Tarih</th>
                <th className="py-2 px-3">Model Tahmini</th>
                <th className="py-2 px-3">TEFAS Kapanış</th>
                <th className="py-2 px-3">Sapma Hata</th>
                <th className="py-2 px-3 text-center">Yön</th>
                <th className="py-2 px-3 text-right">Durum</th>
              </tr>
            </thead>
            <tbody className="divide-y divide-slate-800/50">
              {[...(report.dailyComparisons || [])]
                .sort((a, b) => new Date(b.date).getTime() - new Date(a.date).getTime())
                .map((row, idx) => {
                  const isMissing = row.actualReturn === null;
                  return (
                    <tr key={idx} className="hover:bg-slate-800/40">
                      <td className="py-2 px-3 text-slate-300 font-semibold">{row.date}</td>
                      <td className="py-2 px-3 text-indigo-400 font-semibold">
                        <div>
                          {row.estimatedTefasPrice != null && (
                            <div className="text-white text-[11px] font-mono">{row.estimatedTefasPrice.toFixed(4)} TL</div>
                          )}
                          <div className="text-[10px] text-indigo-400">
                            {row.predictedReturn > 0
                              ? `+${(row.predictedReturn * 100).toFixed(2)}%`
                              : `${(row.predictedReturn * 100).toFixed(2)}%`}
                          </div>
                        </div>
                      </td>
                      <td className="py-2 px-3">
                        {isMissing ? (
                          <span className="text-slate-400 italic">⚪ Açıklanmadı</span>
                        ) : (
                          <div>
                            {row.actualTefasPrice != null && (
                              <div className="text-white text-[11px] font-mono">{row.actualTefasPrice.toFixed(4)} TL</div>
                            )}
                            <div className="text-[10px] text-emerald-400 font-semibold">
                              {row.actualReturn! > 0
                                ? `+${(row.actualReturn! * 100).toFixed(2)}%`
                                : `${(row.actualReturn! * 100).toFixed(2)}%`}
                            </div>
                          </div>
                        )}
                      </td>
                      <td className="py-2 px-3">
                        {isMissing || row.errorDiff === null ? (
                          <span className="text-slate-500">—</span>
                        ) : (
                          <span className="text-slate-300 font-mono">
                            {(row.errorDiff * 10000).toFixed(1)} bps
                          </span>
                        )}
                      </td>
                      <td className="py-2 px-3 text-center">
                        {isMissing ? (
                          <span className="text-slate-500">—</span>
                        ) : (
                          row.directionMatched ? '✅' : '❌'
                        )}
                      </td>
                      <td className="py-2 px-3 text-right font-sans">
                        {isMissing ? (
                          <span className="rounded bg-slate-800 px-2 py-0.5 text-[10px] text-slate-400 border border-slate-700 font-mono">
                            ⚪ DEĞER YOK
                          </span>
                        ) : (
                          <span className="rounded bg-emerald-500/20 px-2 py-0.5 text-[10px] font-semibold text-emerald-300 border border-emerald-500/30">
                            🟢 Tescillendi
                          </span>
                        )}
                      </td>
                    </tr>
                  );
                })}
            </tbody>
          </table>
        </div>
      </div>
    </div>
  );
}
