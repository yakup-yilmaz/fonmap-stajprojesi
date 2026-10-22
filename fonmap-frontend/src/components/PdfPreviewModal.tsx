'use client';

import React, { useState, useEffect } from 'react';
import {
  X,
  FileText,
  AlertTriangle,
  Loader2,
  CheckCircle2,
  Database,
} from 'lucide-react';
import { previewPdfReport } from '@/lib/api';
import { PdfPreviewResponse } from '@/types/fonmap';

interface PdfPreviewModalProps {
  isOpen: boolean;
  fundCode: string | null;
  file: File | null;
  onClose: () => void;
  onConfirmUpload: (code: string, file: File) => Promise<void>;
  isUploading: boolean;
}

export default function PdfPreviewModal({
  isOpen,
  fundCode,
  file,
  onClose,
  onConfirmUpload,
  isUploading,
}: PdfPreviewModalProps) {
  const [loading, setLoading] = useState(false);
  const [previewData, setPreviewData] = useState<PdfPreviewResponse | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    if (isOpen && fundCode && file) {
      loadPreview(fundCode, file);
    } else {
      setPreviewData(null);
      setError(null);
    }
  }, [isOpen, fundCode, file]);

  const loadPreview = async (code: string, pdfFile: File) => {
    try {
      setLoading(true);
      setError(null);
      setPreviewData(null);
      const res = await previewPdfReport(code, pdfFile);
      setPreviewData(res);
    } catch (err: any) {
      setError(err?.message || 'PDF raporu önizlenirken bir hata oluştu.');
    } finally {
      setLoading(false);
    }
  };

  if (!isOpen || !fundCode || !file) return null;

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center p-4 bg-black/80 backdrop-blur-md animate-in fade-in duration-200">
      <div className="relative w-full max-w-2xl max-h-[90vh] overflow-y-auto rounded-2xl border border-slate-700 bg-slate-900 p-6 shadow-2xl">
        {/* Kapat Butonu */}
        <button
          onClick={onClose}
          disabled={loading || isUploading}
          className="absolute right-4 top-4 rounded-xl p-2 text-slate-400 hover:bg-slate-800 hover:text-white transition-colors"
        >
          <X className="h-5 w-5" />
        </button>

        {/* Başlık */}
        <div className="flex items-center gap-3 mb-5">
          <div className="flex h-11 w-11 items-center justify-center rounded-xl bg-cyan-500/20 text-cyan-400 border border-cyan-500/30">
            <FileText className="h-6 w-6" />
          </div>
          <div>
            <div className="flex items-center gap-2">
              <h2 className="text-base font-bold text-white tracking-tight">
                PDF Önizleme & Validasyon Masası (Dry-Run)
              </h2>
              <span className="rounded bg-cyan-500/10 px-2 py-0.5 text-[10px] font-bold text-cyan-400 border border-cyan-500/20 font-mono">
                {fundCode}
              </span>
            </div>
            <p className="text-xs text-slate-400 mt-0.5">
              Dosya veritabanına kaydedilmeden önce ayrıştırıldı. Lütfen verileri kontrol edin.
            </p>
          </div>
        </div>

        {/* Dosya Bilgisi Kartı */}
        <div className="rounded-xl border border-slate-800 bg-slate-950/70 p-3 mb-4 flex items-center justify-between text-xs">
          <div className="flex items-center gap-2">
            <FileText className="h-4 w-4 text-slate-400" />
            <span className="font-semibold text-slate-300 truncate max-w-xs">{file.name}</span>
          </div>
          <span className="font-mono text-slate-500 text-[11px]">
            {(file.size / 1024).toFixed(1)} KB
          </span>
        </div>

        {/* Yükleniyor Durumu */}
        {loading && (
          <div className="py-12 flex flex-col items-center justify-center space-y-3">
            <Loader2 className="h-8 w-8 animate-spin text-cyan-400" />
            <p className="text-xs font-semibold text-slate-300">
              PDF Yapay Zeka & Regex Motoru Tarafından Çözümleniyor...
            </p>
            <p className="text-[11px] text-slate-500">
              (Veritabanına yazılmaz, dry-run modunda çalışıyor)
            </p>
          </div>
        )}

        {/* Hata Durumu */}
        {error && (
          <div className="mb-4 rounded-xl border border-rose-500/30 bg-rose-500/10 p-4 text-xs text-rose-300 space-y-2">
            <div className="flex items-center gap-2 font-bold text-rose-400">
              <AlertTriangle className="h-4 w-4" />
              <span>Ayrıştırma veya Doğrulama Hatası</span>
            </div>
            <p className="font-mono text-[11px]">{error}</p>
            <p className="text-[11px] text-rose-300/80">
              Bu PDF dosyasının yapısı seçilen fonun parser şablonuna uymuyor olabilir.
            </p>
          </div>
        )}

        {/* Başarılı Önizleme Rapor Röntgeni */}
        {previewData && !loading && (
          <div className="space-y-4">
            {/* Sanity Check & Parser Başlığı */}
            <div className="flex flex-wrap items-center justify-between gap-2 p-3 rounded-xl border border-slate-800 bg-slate-950/90">
              <div className="flex items-center gap-2">
                {previewData.sanityCheckPassed ? (
                  <span className="inline-flex items-center gap-1.5 rounded-lg bg-emerald-500/10 px-2.5 py-1 text-xs font-bold text-emerald-400 border border-emerald-500/20">
                    <CheckCircle2 className="h-3.5 w-3.5" />
                    <span>Matematiksel Doğrulama Başarılı</span>
                  </span>
                ) : (
                  <span className="inline-flex items-center gap-1.5 rounded-lg bg-amber-500/10 px-2.5 py-1 text-xs font-bold text-amber-400 border border-amber-500/20">
                    <AlertTriangle className="h-3.5 w-3.5" />
                    <span>Şüpheli Rapor (Tolerans Dışı)</span>
                  </span>
                )}
              </div>
              <div className="text-[11px] text-slate-400">
                Kullanılan Parser: <span className="text-cyan-400 font-mono font-bold">{previewData.parserUsed}</span>
              </div>
            </div>

            {/* Metrik Kartları */}
            <div className="grid grid-cols-2 sm:grid-cols-4 gap-2.5">
              <div className="rounded-xl bg-slate-950 p-3 border border-slate-800">
                <div className="text-[10px] text-slate-400">Nakit Teminat Oranı</div>
                <div className="text-sm font-bold text-cyan-400 font-mono mt-0.5">
                  %{Number(
                    previewData.assetBreakdown?.viop?.weightPercentage ??
                    previewData.viopCashRatioDeclared ??
                    ((previewData.cashCollateralRatio ?? 0) * 100)
                  ).toFixed(2)}
                </div>
              </div>

              <div className="rounded-xl bg-slate-950 p-3 border border-slate-800">
                <div className="text-[10px] text-slate-400">Hisse Oranı</div>
                <div className="text-sm font-bold text-emerald-400 font-mono mt-0.5">
                  %{Number(
                    previewData.assetBreakdown?.equity?.weightPercentage ??
                    previewData.stockRatioDeclared ??
                    ((previewData.equityWeight ?? 0) * 100)
                  ).toFixed(2)}
                </div>
              </div>

              <div className="rounded-xl bg-slate-950 p-3 border border-slate-800">
                <div className="text-[10px] text-slate-400">Repo / Mevduat</div>
                <div className="text-sm font-bold text-amber-400 font-mono mt-0.5">
                  %{Number(
                    previewData.assetBreakdown?.depositAndRepo?.weightPercentage ??
                    ((previewData.depositRepoWeight ?? 0) * 100)
                  ).toFixed(2)}
                </div>
              </div>

              <div className="rounded-xl bg-slate-950 p-3 border border-slate-800">
                <div className="text-[10px] text-slate-400">Varlık Kalem Sayısı</div>
                <div className="text-sm font-bold text-cyan-400 font-mono mt-0.5">
                  {previewData.totalItemsCount || previewData.totalHoldingsCount || previewData.holdings?.length || 0} Kalem
                </div>
              </div>
            </div>

            {/* Varlık Listesi */}
            {previewData.holdings && previewData.holdings.length > 0 && (
              <div>
                <div className="text-xs font-semibold text-slate-300 mb-2 flex items-center justify-between">
                  <span>Ayrıştırılan Varlıklar ({previewData.holdings.length} adet):</span>
                  <span className="text-[11px] text-slate-500">İlk 8 varlık listeleniyor</span>
                </div>
                <div className="max-h-44 overflow-y-auto rounded-xl border border-slate-800 bg-slate-950 p-2 space-y-1">
                  {previewData.holdings.slice(0, 8).map((h, idx) => {
                    const sym = h.ticker || h.symbol || '-';
                    const name = h.securityName || h.name || sym;
                    const wVal = h.weightPercentage !== undefined
                      ? Number(h.weightPercentage)
                      : (h.weight !== undefined ? Number(h.weight) * 100 : 0);
                    return (
                      <div
                        key={idx}
                        className="flex items-center justify-between text-xs px-2.5 py-1.5 rounded-lg hover:bg-slate-900 border border-transparent hover:border-slate-800 transition-colors"
                      >
                        <div className="flex items-center gap-2">
                          <span className="font-mono font-bold text-slate-200">{sym}</span>
                          <span className="text-slate-400 truncate max-w-[220px] text-[11px]">{name}</span>
                        </div>
                        <span className="font-mono text-emerald-400 font-semibold">
                          %{wVal.toFixed(2)}
                        </span>
                      </div>
                    );
                  })}
                </div>
              </div>
            )}
          </div>
        )}

        {/* Alt Aksiyon Butonları */}
        <div className="mt-6 pt-4 flex items-center justify-end gap-3 border-t border-slate-800">
          <button
            type="button"
            onClick={onClose}
            disabled={isUploading}
            className="rounded-xl border border-slate-700 bg-slate-800 px-4 py-2.5 text-xs font-semibold text-slate-300 hover:bg-slate-700 transition-colors"
          >
            İptal (Veritabanına Yazma)
          </button>

          {previewData && (
            <button
              type="button"
              onClick={() => onConfirmUpload(fundCode, file)}
              disabled={isUploading}
              className="flex items-center gap-2 rounded-xl bg-emerald-600 hover:bg-emerald-500 px-5 py-2.5 text-xs font-bold text-white shadow-lg shadow-emerald-600/30 transition-all disabled:opacity-50"
            >
              {isUploading ? (
                <>
                  <Loader2 className="h-4 w-4 animate-spin" />
                  <span>Sisteme Kaydediliyor...</span>
                </>
              ) : (
                <>
                  <Database className="h-4 w-4" />
                  <span>Doğrulandı, Sisteme Kaydet</span>
                </>
              )}
            </button>
          )}
        </div>
      </div>
    </div>
  );
}
