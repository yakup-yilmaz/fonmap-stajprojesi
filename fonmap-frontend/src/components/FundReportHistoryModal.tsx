'use client';

import React, { useState, useEffect } from 'react';
import {
  X,
  FileText,
  Calendar,
  Clock,
  CheckCircle2,
  AlertCircle,
  Copy,
  Check,
  HardDrive,
  History,
  ArrowRight,
  Loader2,
  RefreshCw,
  Sparkles,
} from 'lucide-react';
import { FundReportHistory, FundReportItem, DiskReportFile } from '@/types/fonmap';
import { fetchFundReportHistory, loadDiskReportFile } from '@/lib/api';

interface FundReportHistoryModalProps {
  fundCode: string;
  fundTitle?: string;
  isOpen: boolean;
  onClose: () => void;
  onReportActivated?: () => void;
}

export default function FundReportHistoryModal({
  fundCode,
  fundTitle,
  isOpen,
  onClose,
  onReportActivated,
}: FundReportHistoryModalProps) {
  const [data, setData] = useState<FundReportHistory | null>(null);
  const [loading, setLoading] = useState<boolean>(true);
  const [actionLoading, setActionLoading] = useState<string | null>(null);
  const [copiedHash, setCopiedHash] = useState<string | null>(null);
  const [errorMsg, setErrorMsg] = useState<string | null>(null);
  const [successMsg, setSuccessMsg] = useState<string | null>(null);

  const loadHistory = async () => {
    try {
      setLoading(true);
      setErrorMsg(null);
      const res = await fetchFundReportHistory(fundCode);
      setData(res);
    } catch (err: any) {
      setErrorMsg('Rapor geçmişi yüklenirken hata oluştu: ' + (err?.message || 'Bilinmeyen hata'));
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    if (isOpen && fundCode) {
      loadHistory();
    }
  }, [isOpen, fundCode]);

  if (!isOpen) return null;

  const handleCopy = (hash: string) => {
    navigator.clipboard.writeText(hash);
    setCopiedHash(hash);
    setTimeout(() => setCopiedHash(null), 2000);
  };

  const handleActivateDiskFile = async (fileName: string) => {
    try {
      setActionLoading(fileName);
      setErrorMsg(null);
      setSuccessMsg(null);
      const res = await loadDiskReportFile(fundCode, fileName);
      setSuccessMsg(`'${fileName}' dosyası başarıyla devreye alındı! Portföy TNV: ${res.totalNetAssetValue.toLocaleString('tr-TR')} TL`);
      await loadHistory();
      if (onReportActivated) {
        onReportActivated();
      }
    } catch (err: any) {
      setErrorMsg('Dosya yüklenirken hata oluştu: ' + (err?.message || 'Hata'));
    } finally {
      setActionLoading(null);
    }
  };

  const formatCurrency = (val?: number) => {
    if (val === undefined || val === null) return '—';
    return new Intl.NumberFormat('tr-TR', {
      style: 'currency',
      currency: 'TRY',
      maximumFractionDigits: 2,
    }).format(val);
  };

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center p-4 bg-black/80 backdrop-blur-md animate-in fade-in duration-200">
      <div className="relative w-full max-w-4xl max-h-[90vh] flex flex-col rounded-3xl border border-slate-800 bg-slate-950/95 shadow-2xl overflow-hidden font-sans">
        
        {/* HEADER */}
        <div className="flex items-center justify-between border-b border-slate-800/80 p-6 bg-slate-900/50">
          <div className="flex items-center gap-3">
            <div className="flex h-12 w-12 items-center justify-center rounded-2xl bg-indigo-500/10 border border-indigo-500/20 text-indigo-400">
              <History className="h-6 w-6" />
            </div>
            <div>
              <div className="flex items-center gap-2">
                <span className="font-mono text-xs font-black uppercase tracking-wider text-indigo-400 bg-indigo-500/10 px-2.5 py-0.5 rounded-md border border-indigo-500/20">
                  {fundCode}
                </span>
                <h2 className="text-base font-bold text-white tracking-tight">
                  KAP PDF Rapor ve Dosya Arşivi
                </h2>
              </div>
              <p className="text-xs text-slate-400 mt-0.5">
                {fundTitle || data?.fundTitle || `${fundCode} Fonu`} — Aktif ve geçmiş tüm portföy raporları
              </p>
            </div>
          </div>

          <div className="flex items-center gap-2">
            <button
              onClick={loadHistory}
              disabled={loading}
              className="p-2 rounded-xl text-slate-400 hover:text-white hover:bg-slate-800/60 transition-colors"
              title="Yenile"
            >
              <RefreshCw className={`h-4 w-4 ${loading ? 'animate-spin' : ''}`} />
            </button>
            <button
              onClick={onClose}
              className="p-2 rounded-xl text-slate-400 hover:text-white hover:bg-slate-800/60 transition-colors"
              title="Kapat"
            >
              <X className="h-5 w-5" />
            </button>
          </div>
        </div>

        {/* BODY */}
        <div className="p-6 overflow-y-auto space-y-6 flex-1">
          {/* DURUM MESAJLARI */}
          {errorMsg && (
            <div className="rounded-xl border border-rose-500/30 bg-rose-500/10 p-3 text-xs text-rose-300 flex items-center gap-2">
              <AlertCircle className="h-4 w-4 flex-shrink-0" />
              <span>{errorMsg}</span>
            </div>
          )}

          {successMsg && (
            <div className="rounded-xl border border-emerald-500/30 bg-emerald-500/10 p-3 text-xs text-emerald-300 flex items-center gap-2">
              <CheckCircle2 className="h-4 w-4 flex-shrink-0" />
              <span>{successMsg}</span>
            </div>
          )}

          {loading && !data ? (
            <div className="flex flex-col items-center justify-center py-16 gap-3">
              <Loader2 className="h-8 w-8 text-indigo-400 animate-spin" />
              <p className="text-xs text-slate-400 font-mono">Rapor arşivi yükleniyor...</p>
            </div>
          ) : (
            <>
              {/* 1. BÖLÜM: ŞU ANKİ AKTİF DOSYA */}
              <div>
                <div className="flex items-center justify-between mb-3">
                  <div className="flex items-center gap-2">
                    <span className="flex h-2 w-2 rounded-full bg-emerald-400 shadow-[0_0_8px_rgba(52,211,153,0.8)]" />
                    <h3 className="text-xs font-bold uppercase tracking-wider text-emerald-400">
                      Şu Anda Aktif Olarak Kullanılan Dosya
                    </h3>
                  </div>
                  <span className="text-[11px] font-mono text-slate-400">
                    Canlı tahmin motoru bu dosyayı baz alır
                  </span>
                </div>

                {data?.activeReport ? (
                  <div className="rounded-2xl border border-emerald-500/30 bg-gradient-to-br from-emerald-500/10 via-slate-900/60 to-slate-950 p-5 shadow-lg relative overflow-hidden">
                    <div className="absolute top-0 right-0 p-4">
                      <span className="inline-flex items-center gap-1.5 rounded-full bg-emerald-500/20 px-3 py-1 text-xs font-extrabold text-emerald-400 border border-emerald-500/30 shadow-[0_0_12px_rgba(16,185,129,0.2)]">
                        <CheckCircle2 className="h-3.5 w-3.5" />
                        AKTİF KULLANIMDA
                      </span>
                    </div>

                    <div className="flex items-start gap-4">
                      <div className="p-3 rounded-2xl bg-emerald-500/10 border border-emerald-500/20 text-emerald-400">
                        <FileText className="h-8 w-8" />
                      </div>
                      <div className="space-y-1 max-w-[70%]">
                        <h4 className="font-mono text-base font-black text-white break-all">
                          {data.activeReport.fileName}
                        </h4>
                        <p className="text-xs text-slate-400 flex items-center gap-2">
                          <span>Kaynak: <span className="font-mono text-slate-300">{data.activeReport.source || 'Bilinmiyor'}</span></span>
                        </p>
                      </div>
                    </div>

                    {/* METRİKLER */}
                    <div className="grid grid-cols-2 md:grid-cols-4 gap-3 mt-5 pt-4 border-t border-emerald-500/20 text-xs">
                      <div className="rounded-xl bg-slate-900/80 p-2.5 border border-slate-800">
                        <span className="text-[10px] text-slate-400 uppercase font-semibold block">Rapor Dönemi</span>
                        <span className="font-mono font-bold text-white text-sm mt-0.5 block">
                          {data.activeReport.reportDate}
                        </span>
                      </div>

                      <div className="rounded-xl bg-slate-900/80 p-2.5 border border-slate-800">
                        <span className="text-[10px] text-slate-400 uppercase font-semibold block">Portföy Değeri (TNV)</span>
                        <span className="font-mono font-bold text-emerald-300 text-sm mt-0.5 block truncate">
                          {formatCurrency(data.activeReport.totalNetAssetValue)}
                        </span>
                      </div>

                      <div className="rounded-xl bg-slate-900/80 p-2.5 border border-slate-800">
                        <span className="text-[10px] text-slate-400 uppercase font-semibold block">Hisse Dağılım Oranı</span>
                        <span className="font-mono font-bold text-teal-300 text-sm mt-0.5 block">
                          %{(data.activeReport.stockRatio * 100).toFixed(2)}
                        </span>
                      </div>

                      <div className="rounded-xl bg-slate-900/80 p-2.5 border border-slate-800">
                        <span className="text-[10px] text-slate-400 uppercase font-semibold block">İşlenme Zamanı</span>
                        <span className="font-mono text-slate-300 text-xs mt-1 block">
                          {data.activeReport.downloadedAt
                            ? new Date(data.activeReport.downloadedAt).toLocaleString('tr-TR')
                            : '—'}
                        </span>
                      </div>
                    </div>

                    {/* SHA256 */}
                    {data.activeReport.sha256Hash && (
                      <div className="mt-4 flex items-center justify-between gap-2 rounded-xl bg-slate-950/80 border border-slate-800/80 px-3 py-2 text-xs">
                        <div className="flex items-center gap-2 overflow-hidden">
                          <span className="text-[10px] uppercase font-bold text-slate-400 font-mono">SHA-256:</span>
                          <span className="font-mono text-slate-300 truncate text-[11px]">
                            {data.activeReport.sha256Hash}
                          </span>
                        </div>
                        <button
                          onClick={() => handleCopy(data.activeReport!.sha256Hash!)}
                          className="flex items-center gap-1 text-[11px] font-mono text-slate-400 hover:text-white px-2 py-1 rounded bg-slate-800 transition-colors"
                        >
                          {copiedHash === data.activeReport.sha256Hash ? (
                            <>
                              <Check className="h-3 w-3 text-emerald-400" />
                              <span className="text-emerald-400">Kopyalandı</span>
                            </>
                          ) : (
                            <>
                              <Copy className="h-3 w-3" />
                              <span>Kopyala</span>
                            </>
                          )}
                        </button>
                      </div>
                    )}
                  </div>
                ) : (
                  <div className="rounded-2xl border border-dashed border-slate-800 p-8 text-center text-xs text-slate-500 font-mono">
                    Bu fon için henüz aktif bir rapor kaydı bulunamadı.
                  </div>
                )}
              </div>

              {/* 2. BÖLÜM: BUNDAN ÖNCE KULLANILAN DOSYALAR */}
              <div>
                <div className="flex items-center justify-between mb-3">
                  <div className="flex items-center gap-2">
                    <History className="h-4 w-4 text-slate-400" />
                    <h3 className="text-xs font-bold uppercase tracking-wider text-slate-300">
                      Bundan Önce Kullanılan Dosyalar & Veritabanı Arşivi ({data?.previousReports.length || 0})
                    </h3>
                  </div>
                  <span className="text-[11px] text-slate-500">
                    Önceki dönemlerde kullanılmış resmi portföy kayıtları
                  </span>
                </div>

                {data?.previousReports && data.previousReports.length > 0 ? (
                  <div className="overflow-x-auto rounded-2xl border border-slate-800 bg-slate-900/40">
                    <table className="w-full text-left text-xs font-mono">
                      <thead>
                        <tr className="border-b border-slate-800 text-[11px] uppercase tracking-wider text-slate-400 bg-slate-900/60">
                          <th className="py-2.5 px-4 font-sans">Dosya / Kaynak</th>
                          <th className="py-2.5 px-4 font-sans">Rapor Dönemi</th>
                          <th className="py-2.5 px-4 font-sans">Portföy TNV</th>
                          <th className="py-2.5 px-4 font-sans">Hisse Oranı</th>
                          <th className="py-2.5 px-4 font-sans">İşlenme Tarihi</th>
                          <th className="py-2.5 px-4 font-sans text-right">Durum</th>
                        </tr>
                      </thead>
                      <tbody className="divide-y divide-slate-800/60">
                        {data.previousReports.map((item, idx) => (
                          <tr key={item.snapshotId || idx} className="hover:bg-slate-800/30 transition-colors">
                            <td className="py-3 px-4 text-slate-200 font-bold max-w-[220px] truncate" title={item.fileName}>
                              <div className="flex items-center gap-2">
                                <FileText className="h-4 w-4 text-slate-500 flex-shrink-0" />
                                <span className="truncate">{item.fileName}</span>
                              </div>
                            </td>
                            <td className="py-3 px-4 text-indigo-300 font-semibold">
                              {item.reportDate}
                            </td>
                            <td className="py-3 px-4 text-slate-300">
                              {formatCurrency(item.totalNetAssetValue)}
                            </td>
                            <td className="py-3 px-4 text-teal-400">
                              %{(item.stockRatio * 100).toFixed(2)}
                            </td>
                            <td className="py-3 px-4 text-slate-400 text-[11px]">
                              {item.downloadedAt ? new Date(item.downloadedAt).toLocaleDateString('tr-TR') : '—'}
                            </td>
                            <td className="py-3 px-4 text-right">
                              <span className="inline-flex items-center gap-1 rounded bg-slate-800/80 px-2 py-0.5 text-[10px] font-semibold text-slate-400 border border-slate-700">
                                ARŞİVDE (PASİF)
                              </span>
                            </td>
                          </tr>
                        ))}
                      </tbody>
                    </table>
                  </div>
                ) : (
                  <div className="rounded-2xl border border-dashed border-slate-800 p-6 text-center text-xs text-slate-500 font-mono">
                    Bu fon için henüz geçmişe ait ikinci bir veritabanı snapshot kaydı yok.
                  </div>
                )}
              </div>

              {/* 3. BÖLÜM: DİSKTE BULUNAN ÖRNEK VE ARŞİV DOSYALARI (samples/ KLASÖRÜ) */}
              <div>
                <div className="flex items-center justify-between mb-3">
                  <div className="flex items-center gap-2">
                    <HardDrive className="h-4 w-4 text-indigo-400" />
                    <h3 className="text-xs font-bold uppercase tracking-wider text-indigo-400">
                      Diskteki Hazır Rapor Dosyaları (samples/ Klasörü) ({data?.availableDiskFiles.length || 0})
                    </h3>
                  </div>
                  <span className="text-[11px] text-slate-500">
                    Yerel depolamada tespit edilen PDF dosyaları
                  </span>
                </div>

                {data?.availableDiskFiles && data.availableDiskFiles.length > 0 ? (
                  <div className="grid grid-cols-1 md:grid-cols-2 gap-3">
                    {data.availableDiskFiles.map((file) => (
                      <div
                        key={file.fileName}
                        className={`rounded-2xl border p-4 transition-all flex items-center justify-between gap-3 ${
                          file.isCurrentlyLoaded
                            ? 'border-emerald-500/40 bg-emerald-500/5'
                            : 'border-slate-800 bg-slate-900/50 hover:border-slate-700'
                        }`}
                      >
                        <div className="flex items-center gap-3 overflow-hidden">
                          <div
                            className={`p-2.5 rounded-xl ${
                              file.isCurrentlyLoaded
                                ? 'bg-emerald-500/20 text-emerald-400'
                                : 'bg-slate-800 text-slate-400'
                            }`}
                          >
                            <FileText className="h-5 w-5" />
                          </div>
                          <div className="overflow-hidden">
                            <h5 className="font-mono text-xs font-black text-white truncate" title={file.fileName}>
                              {file.fileName}
                            </h5>
                            <p className="text-[11px] font-mono text-slate-400 mt-0.5">
                              {file.sizeInKb.toFixed(1)} KB • {file.isCurrentlyLoaded ? 'Şu An Yüklü Rapor' : 'Hazır Arşiv'}
                            </p>
                          </div>
                        </div>

                        <div>
                          {file.isCurrentlyLoaded ? (
                            <span className="inline-flex items-center gap-1 text-[11px] font-bold text-emerald-400 font-mono px-2 py-1 rounded bg-emerald-500/10 border border-emerald-500/30">
                              <Check className="h-3 w-3" />
                              YÜKLÜ
                            </span>
                          ) : (
                            <button
                              onClick={() => handleActivateDiskFile(file.fileName)}
                              disabled={actionLoading === file.fileName}
                              className="flex items-center gap-1.5 px-3 py-1.5 rounded-xl bg-indigo-600 hover:bg-indigo-500 text-white text-xs font-bold transition-all shadow-md active:scale-95 disabled:opacity-50"
                            >
                              {actionLoading === file.fileName ? (
                                <Loader2 className="h-3.5 w-3.5 animate-spin" />
                              ) : (
                                <Sparkles className="h-3.5 w-3.5" />
                              )}
                              <span>Devreye Al</span>
                            </button>
                          )}
                        </div>
                      </div>
                    ))}
                  </div>
                ) : (
                  <div className="rounded-2xl border border-dashed border-slate-800 p-6 text-center text-xs text-slate-500 font-mono">
                    Diskte bu fon için ayrılmış örnek PDF bulunamadı.
                  </div>
                )}
              </div>
            </>
          )}
        </div>

        {/* FOOTER */}
        <div className="border-t border-slate-800/80 p-4 bg-slate-900/50 flex items-center justify-between text-xs text-slate-400 font-mono">
          <div className="flex items-center gap-2">
            <span className="h-2 w-2 rounded-full bg-emerald-400" />
            <span>KAP İdempotency SHA-256 Koruması Aktif</span>
          </div>
          <button
            onClick={onClose}
            className="px-4 py-2 rounded-xl bg-slate-800 hover:bg-slate-700 text-white font-sans font-bold transition-colors text-xs"
          >
            Kapat
          </button>
        </div>

      </div>
    </div>
  );
}
