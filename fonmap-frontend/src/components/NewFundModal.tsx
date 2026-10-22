'use client';

import React, { useState, useEffect } from 'react';
import {
  X,
  Plus,
  Sparkles,
  Building2,
  Percent,
  Check,
  Loader2,
  Eye,
  ShieldCheck,
  AlertTriangle,
  PieChart,
  Layers,
  RefreshCw,
} from 'lucide-react';
import { createFund, triggerAutoDiscovery, previewKapReport, sniffTefasProfile } from '@/lib/api';
import { PdfPreviewResponse } from '@/types/fonmap';

interface NewFundModalProps {
  isOpen: boolean;
  onClose: () => void;
  onSuccess: (newFundCode: string) => void;
}

function safeFormatNum(val: any, mult100: boolean = false): string {
  if (val === null || val === undefined) return '0.00';
  const num = typeof val === 'number' ? val : parseFloat(val);
  if (!Number.isFinite(num)) return '0.00';
  const finalNum = mult100 ? num * 100 : num;
  return finalNum.toFixed(2);
}

export default function NewFundModal({
  isOpen,
  onClose,
  onSuccess,
}: NewFundModalProps) {
  const [code, setCode] = useState('');
  const [title, setTitle] = useState('');
  const [manager, setManager] = useState('Tera Portföy Yönetimi A.Ş.');
  const [feePercent, setFeePercent] = useState('2.00');
  const [isManualFee, setIsManualFee] = useState(false);
  const [autoDiscover, setAutoDiscover] = useState(true);
  const [loading, setLoading] = useState(false);
  const [previewLoading, setPreviewLoading] = useState(false);
  const [isSniffing, setIsSniffing] = useState(false);
  const [sniffedSuccess, setSniffedSuccess] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);

  // Dry-Run Önizleme Durumu
  const [previewData, setPreviewData] = useState<PdfPreviewResponse | null>(null);

  // Modal her açıldığında form durumunu sıfırla (Önceki fonun bilgileri kalmasın)
  useEffect(() => {
    if (isOpen) {
      setCode('');
      setTitle('');
      setManager('');
      setFeePercent('2.00');
      setIsManualFee(false);
      setAutoDiscover(true);
      setPreviewData(null);
      setError(null);
      setSniffedSuccess(null);
      setIsSniffing(false);
    }
  }, [isOpen]);

  if (!isOpen) return null;

  const supportedManagers = [
    { name: 'Kuveyt Türk Portföy Yönetimi A.Ş.', description: 'Katılım & Hisse Fonları' },
    { name: 'Tera Portföy Yönetimi A.Ş.', description: 'Hisse, Serbest & Algoritmik' },
    { name: 'İş Portföy Yönetimi A.Ş.', description: 'Endeks & Hisse Fonları' },
    { name: 'Ak Portföy Yönetimi A.Ş.', description: 'Teknoloji & Tematik Fonlar' },
    { name: 'Deniz Portföy Yönetimi A.Ş.', description: 'Hisse & Değişken Fonlar' },
    { name: 'Garanti BBVA Portföy Yönetimi A.Ş.', description: 'Hisse & Sektör Fonları' },
    { name: 'Yapı Kredi Portföy Yönetimi A.Ş.', description: 'Koç Holding & Hisse Fonları' },
    { name: 'Atlas Portföy Yönetimi A.Ş.', description: 'Hisse & Serbest Fonlar' },
    { name: 'Pardus Portföy Yönetimi A.Ş.', description: 'Hisse & Katılım Fonları' },
    { name: 'Diğer Portföy Şirketleri', description: 'Otomatik Akıllı Sniffing' },
  ];

  // Fon Kodu Girildiğinde TEFAS'tan Otomatik Künye ve PYŞ Çekme
  const handleCodeChange = async (val: string) => {
    const clean = val.toUpperCase().trim();
    setCode(clean);
    setSniffedSuccess(null);

    // 3, 4 veya 5 karakterli fon kodlarında TEFAS'tan sorgula (Örn: TI1, TTE, GAE, ZP61)
    if (clean.length >= 3 && clean.length <= 5) {
      try {
        setIsSniffing(true);
        const profile = await sniffTefasProfile(clean);
        if (profile && (profile.foundInTefas || (profile as any).found)) {
          if (profile.title) setTitle(profile.title);
          if (profile.manager) setManager(profile.manager);
          if (profile.annualFeeRatio && !isManualFee) {
            setFeePercent((profile.annualFeeRatio * 100).toFixed(2));
          }
          setSniffedSuccess(profile.manager || profile.title);
        }
      } catch (err) {
        // Sessiz devam et
      } finally {
        setIsSniffing(false);
      }
    }
  };

  // 1. Dry-Run KAP Önizleme (Veritabanına Yazmaz)
  const handlePreviewKap = async () => {
    const cleanCode = code.trim().toUpperCase();
    if (!cleanCode) {
      setError('Önizleme için lütfen önce bir fon kodu giriniz.');
      return;
    }
    try {
      setError(null);
      setPreviewLoading(true);
      const preview = await previewKapReport(cleanCode);
      setPreviewData(preview);
    } catch (err: any) {
      setError(err?.message || 'KAP raporu önizlenirken bir hata oluştu.');
      setPreviewData(null);
    } finally {
      setPreviewLoading(false);
    }
  };

  // 2. Asıl Fon Kaydı (KAP Taraması ile)
  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    setError(null);

    const cleanCode = code.trim().toUpperCase();
    if (!cleanCode) {
      setError('Lütfen geçerli bir fon kodu giriniz.');
      return;
    }

    try {
      setLoading(true);

      const feePercentValue = parseFloat(feePercent);
      if (!Number.isFinite(feePercentValue) || feePercentValue < 0 || feePercentValue > 100) {
        setError('Yıllık yönetim ücreti 0 ile 100 arasında geçerli bir yüzde olmalıdır.');
        setLoading(false);
        return;
      }
      const feeRatio = feePercentValue / 100; // API sözleşmesi: oran (0–1)

      // 1. Backend'e yeni fonu kaydet ve anında KAP'tan son dağılım raporunu indirip ayrıştır
      const result = await createFund({
        code: cleanCode,
        title: title.trim() || `${cleanCode} Yatırım Fonu`,
        manager: manager.trim() || 'Diğer Portföy Şirketleri',
        annualFeeRatio: feeRatio,
        displayOrder: 10,
        autoDiscover: autoDiscover,
      });

      // 2. Fallback KAP taraması
      if (autoDiscover && !result.reportDiscovered) {
        try {
          await triggerAutoDiscovery(cleanCode);
        } catch (kapErr) {
          console.warn('KAP ek tarama uyarısı:', kapErr);
        }
      }

      onSuccess(cleanCode);
      onClose();
    } catch (err: any) {
      setError(err?.message || 'Fon kaydedilirken bir hata oluştu.');
    } finally {
      setLoading(false);
    }
  };

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center p-4 bg-black/80 backdrop-blur-md animate-in fade-in duration-200">
      <div className="relative w-full max-w-xl max-h-[90vh] overflow-y-auto rounded-2xl border border-slate-700 bg-slate-900 p-6 shadow-2xl">
        <button
          onClick={onClose}
          disabled={loading || previewLoading}
          className="absolute right-4 top-4 rounded-xl p-2 text-slate-400 hover:bg-slate-800 hover:text-white transition-colors"
        >
          <X className="h-5 w-5" />
        </button>

        <div className="flex items-center gap-3 mb-5">
          <div className="flex h-10 w-10 items-center justify-center rounded-xl bg-emerald-500/20 text-emerald-400 border border-emerald-500/30">
            <Plus className="h-5 w-5" />
          </div>
          <div>
            <h2 className="text-base font-bold text-white tracking-tight">
              Yeni Fon Ekle & KAP Dağılımını Bağla
            </h2>
            <p className="text-xs text-slate-400">
              Sisteme yeni fon tanımlayın, dry-run ile önizleyin ve canlı seans tahminini başlatın
            </p>
          </div>
        </div>

        {error && (
          <div className="mb-4 rounded-xl border border-rose-500/30 bg-rose-500/10 p-3 text-xs text-rose-300">
            {error}
          </div>
        )}

        <form onSubmit={handleSubmit} className="space-y-4">
          <div>
            <div className="flex items-center justify-between mb-1">
              <label className="text-xs font-semibold text-slate-300">
                Fon Kodu (Ticker) *
              </label>
              <button
                type="button"
                onClick={handlePreviewKap}
                disabled={previewLoading || !code.trim()}
                className="flex items-center gap-1.5 text-[11px] font-bold text-cyan-400 hover:text-cyan-300 bg-cyan-500/10 hover:bg-cyan-500/20 border border-cyan-500/30 px-2.5 py-1 rounded-lg transition-all disabled:opacity-40"
              >
                {previewLoading ? (
                  <>
                    <Loader2 className="h-3 w-3 animate-spin" />
                    <span>KAP Taranıyor...</span>
                  </>
                ) : (
                  <>
                    <Eye className="h-3 w-3" />
                    <span>⚡ KAP'tan Önizle (Dry-Run)</span>
                  </>
                )}
              </button>
            </div>
            <input
              type="text"
              required
              maxLength={10}
              placeholder="Örn: GAE, AFT, MAC, TI2, TTE..."
              value={code}
              onChange={(e) => handleCodeChange(e.target.value)}
              className="w-full rounded-xl border border-slate-700 bg-slate-950 px-3.5 py-2.5 font-mono text-sm text-white placeholder-slate-500 focus:border-emerald-500 focus:outline-none"
            />
            {isSniffing && (
              <p className="mt-1.5 flex items-center gap-1.5 text-xs text-indigo-400 animate-pulse">
                <RefreshCw className="h-3 w-3 animate-spin" />
                <span>TEFAS'tan fon künyesi ve kurucu şirket taranıyor...</span>
              </p>
            )}
            {previewLoading && (
              <p className="mt-1.5 flex items-center gap-1.5 text-xs text-cyan-400 animate-pulse">
                <Loader2 className="h-3 w-3 animate-spin" />
                <span>KAP'tan son portföy dağılım raporu indiriliyor ve analiz ediliyor (5-10 sn sürebilir)...</span>
              </p>
            )}
            {sniffedSuccess && !isSniffing && !previewLoading && (
              <p className="mt-1.5 flex items-center gap-1.5 text-xs text-emerald-400 font-medium">
                <Check className="h-3.5 w-3.5" />
                <span>TEFAS'tan Otomatik Doğrulandı: <strong>{sniffedSuccess}</strong></span>
              </p>
            )}
          </div>

          {/* ⚡ DRY-RUN ÖNİZLEME KARTI */}
          {previewData && (
            <div className="rounded-xl border border-cyan-500/30 bg-slate-950/90 p-4 shadow-lg animate-in fade-in slide-in-from-top-2 duration-300">
              <div className="flex items-center justify-between border-b border-slate-800 pb-2.5 mb-3">
                <div className="flex items-center gap-2">
                  <ShieldCheck className="h-4 w-4 text-emerald-400" />
                  <span className="text-xs font-bold text-white">
                    Dry-Run Rapor Röntgeni ({previewData.fundCode})
                  </span>
                </div>
                <span className={`text-[10px] font-bold px-2 py-0.5 rounded-full border ${
                  previewData.sanityCheckPassed
                    ? 'bg-emerald-500/10 text-emerald-400 border-emerald-500/30'
                    : 'bg-rose-500/10 text-rose-400 border-rose-500/30'
                }`}>
                  {previewData.sanityCheckPassed ? '🟢 Tutarlı (Geçti)' : '🔴 Şüpheli'}
                </span>
              </div>

              <div className="grid grid-cols-2 sm:grid-cols-4 gap-2 mb-3 text-center">
                <div className="rounded-lg bg-slate-900/80 p-2 border border-slate-800">
                  <div className="text-[10px] text-slate-400">Varlık Sayısı</div>
                  <div className="text-sm font-bold text-white font-mono">
                    {previewData.totalItemsCount || previewData.totalHoldingsCount || previewData.holdings?.length || 0} Kalem
                  </div>
                </div>
                <div className="rounded-lg bg-slate-900/80 p-2 border border-slate-800">
                  <div className="text-[10px] text-slate-400">Hisse Oranı</div>
                  <div className="text-sm font-bold text-emerald-400 font-mono">
                    %{safeFormatNum(
                      previewData.assetBreakdown?.equity?.weightPercentage ??
                      previewData.stockRatioDeclared ??
                      (previewData.equityWeight !== undefined ? previewData.equityWeight * 100 : 0)
                    )}
                  </div>
                </div>
                <div className="rounded-lg bg-slate-900/80 p-2 border border-slate-800">
                  <div className="text-[10px] text-slate-400">Repo / Faiz</div>
                  <div className="text-sm font-bold text-amber-400 font-mono">
                    %{safeFormatNum(
                      previewData.assetBreakdown?.depositAndRepo?.weightPercentage ??
                      (previewData.depositRepoWeight !== undefined ? previewData.depositRepoWeight * 100 : 0)
                    )}
                  </div>
                </div>
                <div className="rounded-lg bg-slate-900/80 p-2 border border-slate-800">
                  <div className="text-[10px] text-slate-400">Teminat Oranı</div>
                  <div className="text-sm font-bold text-cyan-400 font-mono">
                    %{safeFormatNum(
                      previewData.assetBreakdown?.viop?.weightPercentage ??
                      previewData.viopCashRatioDeclared ??
                      (previewData.cashCollateralRatio !== undefined ? previewData.cashCollateralRatio * 100 : 0)
                    )}
                  </div>
                </div>
              </div>

              {previewData.holdings && previewData.holdings.length > 0 && (
                <div className="mt-2">
                  <div className="text-[10px] font-semibold text-slate-400 mb-1.5 flex items-center justify-between">
                    <span>İlk Varlıklar ({previewData.holdings.length} kalemden):</span>
                    <span className="text-[10px] text-slate-500">
                      Strateji: {previewData.detectedParserStrategy ?? previewData.parserUsed ?? 'Otomatik SPK'}
                    </span>
                  </div>
                  <div className="max-h-28 overflow-y-auto rounded-lg border border-slate-800/80 bg-slate-900/40 p-1.5 space-y-1">
                    {previewData.holdings.slice(0, 5).map((h, idx) => {
                      const ticker = h.ticker || h.symbol || (h.securityName ? h.securityName.split(' ')[0] : `Varlık #${idx + 1}`);
                      const name = h.securityName || h.name || ticker;
                      const weightStr = safeFormatNum(
                        h.weightPercentage !== undefined ? h.weightPercentage : (h.weight !== undefined ? h.weight * 100 : 0)
                      );
                      return (
                        <div key={idx} className="flex items-center justify-between text-[11px] px-2 py-0.5 rounded hover:bg-slate-800/50">
                          <span className="font-mono font-bold text-slate-200">{ticker}</span>
                          <span className="text-slate-400 truncate max-w-[150px]">{name}</span>
                          <span className="font-mono text-emerald-400 font-semibold">%{weightStr}</span>
                        </div>
                      );
                    })}
                  </div>
                </div>
              )}
            </div>
          )}

          <div>
            <label className="block text-xs font-semibold text-slate-300 mb-1">
              Fon Tam Adı
            </label>
            <input
              type="text"
              placeholder="Örn: İş Portföy BIST Banka Endeksi Fonu"
              value={title}
              onChange={(e) => setTitle(e.target.value)}
              className="w-full rounded-xl border border-slate-700 bg-slate-950 px-3.5 py-2.5 text-sm text-white placeholder-slate-500 focus:border-emerald-500 focus:outline-none"
            />
          </div>

          <div>
            <div className="flex items-center justify-between mb-1">
              <label className="text-xs font-semibold text-slate-300">
                Portföy Yönetim Şirketi (Kurucu Kurum)
              </label>
              <span className="text-[10px] text-emerald-400 font-semibold bg-emerald-500/10 px-2 py-0.5 rounded border border-emerald-500/20">
                ⚡ Otonom AI Sniffing
              </span>
            </div>
            <select
              value={manager}
              onChange={(e) => setManager(e.target.value)}
              className="w-full rounded-xl border border-slate-700 bg-slate-950 px-3.5 py-2.5 text-xs text-white focus:border-emerald-500 focus:outline-none"
            >
              <option value="" disabled>
                -- Portföy Yönetim Şirketi (Kod girildiğinde TEFAS'tan otomatik dolar) --
              </option>
              {manager && !supportedManagers.some((m) => m.name === manager) && (
                <option value={manager}>
                  {manager} (TEFAS Resmi Kurucu)
                </option>
              )}
              {supportedManagers.map((m, idx) => (
                <option key={idx} value={m.name}>
                  {m.name} ({m.description})
                </option>
              ))}
            </select>
            <p className="text-[10px] text-slate-500 mt-1">
              💡 Not: Raporun sayfa düzeni (yatay/dikey, SPK formatı) Content Sniffing yarış motoruyla otomatik tespit edilir; manuel parser seçmeniz gerekmez.
            </p>
          </div>

          {/* Yıllık Yönetim Ücreti */}
          <div className="rounded-xl border border-slate-800 bg-slate-950/70 p-3.5">
            <div className="flex items-center justify-between mb-1.5">
              <label className="text-xs font-semibold text-slate-300 flex items-center gap-1.5">
                <Sparkles className="h-3.5 w-3.5 text-emerald-400" />
                <span>Yıllık Fon Yönetim Ücreti</span>
              </label>
              <span className="rounded-full bg-emerald-500/10 px-2 py-0.5 text-[10px] font-semibold text-emerald-400 border border-emerald-500/20">
                ⚡ Otomatik PDF/TEFAS Okuması
              </span>
            </div>

            {!isManualFee ? (
              <div className="flex items-center justify-between pt-1">
                <p className="text-[11px] text-slate-400">
                  PDF raporu ve TEFAS künyesinden otomatik ayrıştırılır (Varsayılan: %2.00).
                </p>
                <button
                  type="button"
                  onClick={() => setIsManualFee(true)}
                  className="text-[11px] text-emerald-400 hover:text-emerald-300 font-semibold underline shrink-0 ml-2"
                >
                  Manuel Gir
                </button>
              </div>
            ) : (
              <div className="pt-2">
                <div className="relative">
                  <input
                    type="number"
                    step="0.01"
                    min="0"
                    max="10"
                    value={feePercent}
                    onChange={(e) => setFeePercent(e.target.value)}
                    className="w-full rounded-xl border border-slate-700 bg-slate-900 px-3.5 py-2 font-mono text-xs text-white focus:border-emerald-500 focus:outline-none pr-8"
                  />
                  <span className="absolute right-3.5 top-2 text-slate-400 font-mono text-xs">
                    %
                  </span>
                </div>
                <button
                  type="button"
                  onClick={() => {
                    setIsManualFee(false);
                    setFeePercent('2.00');
                  }}
                  className="text-[10px] text-slate-500 hover:text-slate-400 mt-1 block"
                >
                  ↺ Otomatik algılamaya geri dön
                </button>
              </div>
            )}
          </div>

          <div className="flex items-center gap-2 pt-2">
            <input
              type="checkbox"
              id="autoDiscover"
              checked={autoDiscover}
              onChange={(e) => setAutoDiscover(e.target.checked)}
              className="h-4 w-4 rounded border-slate-700 bg-slate-950 text-emerald-500 focus:ring-emerald-500"
            />
            <label htmlFor="autoDiscover" className="text-xs text-slate-300 select-none">
              Kaydeder kaydetmez KAP'tan en son dağılım raporunu otomatik tara ve indir
            </label>
          </div>

          <div className="pt-4 flex items-center justify-end gap-3 border-t border-slate-800">
            <button
              type="button"
              onClick={onClose}
              disabled={loading || previewLoading}
              className="rounded-xl border border-slate-700 bg-slate-800 px-4 py-2.5 text-xs font-semibold text-slate-300 hover:bg-slate-700 transition-colors"
            >
              İptal
            </button>
            <button
              type="submit"
              disabled={loading || previewLoading}
              className="flex items-center gap-2 rounded-xl bg-emerald-600 hover:bg-emerald-500 px-5 py-2.5 text-xs font-bold text-white shadow-lg shadow-emerald-600/30 transition-all disabled:opacity-50"
            >
              {loading ? (
                <>
                  <Loader2 className="h-4 w-4 animate-spin" />
                  <span>KAP Taranıyor & Kaydediliyor...</span>
                </>
              ) : (
                <>
                  <Sparkles className="h-4 w-4" />
                  <span>Fonu Kaydet ve Canlıya Al</span>
                </>
              )}
            </button>
          </div>
        </form>
      </div>
    </div>
  );
}
