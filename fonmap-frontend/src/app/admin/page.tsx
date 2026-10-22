'use client';

import React, { useState, useEffect } from 'react';
import {
  ShieldCheck,
  Plus,
  RefreshCw,
  Upload,
  CheckCircle2,
  AlertTriangle,
  FileText,
  Layers,
  Database,
  Search,
  ExternalLink,
  Loader2,
  Sparkles,
  Clock,
  Calendar,
  Copy,
  Check,
  History,
  Zap,
} from 'lucide-react';
import NewFundModal from '@/components/NewFundModal';
import BacktestToleranceMatrix from '@/components/BacktestToleranceMatrix';
import KapScanTerminalModal from '@/components/KapScanTerminalModal';
import FundReportHistoryModal from '@/components/FundReportHistoryModal';
import PdfPreviewModal from '@/components/PdfPreviewModal';
import {
  fetchActiveFunds,
  triggerAutoDiscovery,
  uploadPdfReport,
  fetchPendingAliases,
  approveAlias,
  fetchBacktestReport,
  triggerBacktestRun,
  triggerRunAllBacktests,
} from '@/lib/api';
import { Fund, PendingAlias, BacktestReport, BatchBacktestResult } from '@/types/fonmap';

export default function AdminPage() {
  const [funds, setFunds] = useState<Fund[]>([]);
  const [pendingAliases, setPendingAliases] = useState<PendingAlias[]>([]);
  const [selectedBacktestFund, setSelectedBacktestFund] = useState<string>('');
  const [backtestReport, setBacktestReport] = useState<BacktestReport | null>(null);
  const [isRunningAllBacktests, setIsRunningAllBacktests] = useState(false);
  const [batchBacktestSummary, setBatchBacktestSummary] = useState<BatchBacktestResult | null>(null);

  // Modal ve Form Durumları
  const [isNewFundModalOpen, setIsNewFundModalOpen] = useState(false);
  const [terminalFundCode, setTerminalFundCode] = useState<string | null>(null);
  const [historyModalFund, setHistoryModalFund] = useState<{ code: string; title: string } | null>(null);
  const [pdfPreviewModal, setPdfPreviewModal] = useState<{ fundCode: string; file: File } | null>(null);
  const [isUploadingPdf, setIsUploadingPdf] = useState(false);
  const [activeTab, setActiveTab] = useState<'FUNDS' | 'TOLERANCE' | 'ALIASES'>('FUNDS');
  const [actionLoading, setActionLoading] = useState<Record<string, boolean>>({});
  const [statusMessage, setStatusMessage] = useState<{ type: 'success' | 'error'; text: string } | null>(null);
  const [copiedHash, setCopiedHash] = useState<string | null>(null);
  const [isRunningBacktest, setIsRunningBacktest] = useState<boolean>(false);
  const [isBacktestLoading, setIsBacktestLoading] = useState<boolean>(false);

  const handleCopyHash = (hash: string) => {
    navigator.clipboard.writeText(hash);
    setCopiedHash(hash);
    setTimeout(() => setCopiedHash(null), 2000);
  };

  const handleRunBacktest = async (code: string) => {
    try {
      setIsRunningBacktest(true);
      setStatusMessage(null);
      const res = await triggerBacktestRun(code);
      if (res) {
        setBacktestReport(res);
        setStatusMessage({
          type: 'success',
          text: `'${code}' için geriye dönük test (Backtest) başarıyla çalıştırıldı! (${res.totalDays} iş günü simüle edildi, MAE: %${(res.mae * 100).toFixed(4)})`,
        });
      }
    } catch (err: any) {
      console.error('Backtest çalıştırma hatası:', err);
      setStatusMessage({
        type: 'error',
        text: `Backtest çalıştırılırken hata: ${err.message}`,
      });
    } finally {
      setIsRunningBacktest(false);
    }
  };

  const handleRunAllBacktests = async () => {
    try {
      setIsRunningAllBacktests(true);
      setStatusMessage(null);
      const res = await triggerRunAllBacktests();
      setBatchBacktestSummary(res);
      setStatusMessage({
        type: 'success',
        text: `🚀 Toplu Backtest Başarıyla Tamamlandı! Toplam: ${res.totalFunds} fon (${res.successfulFunds} Başarılı, ${res.failedFunds} Hatalı) — Süre: ${(res.executionTimeMs / 1000).toFixed(1)} sn. Tüm ortak varlıklar ve karneler önbelleğe işlendi.`,
      });
      // Seçili fonun güncel karnesini yeniden çek
      const updated = await fetchBacktestReport(selectedBacktestFund);
      if (updated) setBacktestReport(updated);
    } catch (err: any) {
      console.error('Toplu backtest hatası:', err);
      setStatusMessage({
        type: 'error',
        text: `Toplu backtest çalıştırılırken hata: ${err.message}`,
      });
    } finally {
      setIsRunningAllBacktests(false);
    }
  };

  useEffect(() => {
    loadAdminData();
  }, []);

  useEffect(() => {
    if (!selectedBacktestFund) return;
    setIsBacktestLoading(true);
    fetchBacktestReport(selectedBacktestFund)
      .then((res) => {
        setBacktestReport(res);
      })
      .catch((err) => {
        console.warn('Backtest raporu alınırken hata:', err);
      })
      .finally(() => {
        setIsBacktestLoading(false);
      });
  }, [selectedBacktestFund]);

  const loadAdminData = async () => {
    try {
      const [fundsData, aliasData] = await Promise.all([
        fetchActiveFunds(),
        fetchPendingAliases(),
      ]);
      if (fundsData && fundsData.length > 0) {
        setFunds(fundsData);
        setSelectedBacktestFund((prev) => (prev && fundsData.some(f => f.code === prev) ? prev : fundsData[0].code));
      }
      if (aliasData) setPendingAliases(aliasData);
    } catch (err) {
      console.warn('Admin verileri yüklenirken hata:', err);
      setStatusMessage({
        type: 'error',
        text: 'Fon listesi alınamadı: sunucuya ulaşılamıyor.',
      });
    }
  };

  // 1. KAP Otomatik Keşif Tetikleme
  const handleAutoDiscover = async (code: string) => {
    try {
      setActionLoading((prev) => ({ ...prev, [code]: true }));
      setStatusMessage(null);
      const res = await triggerAutoDiscovery(code);
      setStatusMessage({
        type: 'success',
        text: `'${code}' için en son KAP raporu başarıyla çekildi ve ayrıştırıldı! (Snapshot: ${res.snapshotDate})`,
      });
      loadAdminData();
    } catch (err: any) {
      setStatusMessage({
        type: 'error',
        text: `'${code}' KAP keşfi başarısız: ${err?.message || 'Bilinmeyen hata'}`,
      });
    } finally {
      setActionLoading((prev) => ({ ...prev, [code]: false }));
    }
  };

  // 2. Manuel PDF Seçimi -> Dry-Run Önizleme Modalı Açılır
  const handleFileSelect = (code: string, e: React.ChangeEvent<HTMLInputElement>) => {
    const file = e.target.files?.[0];
    if (!file) return;
    setPdfPreviewModal({ fundCode: code, file });
    e.target.value = '';
  };

  // 2.B Dry-Run Onaylandıktan Sonra Asıl Yükleme
  const handleConfirmUpload = async (code: string, file: File) => {
    try {
      setIsUploadingPdf(true);
      setStatusMessage(null);
      const res = await uploadPdfReport(code, file);
      setStatusMessage({
        type: 'success',
        text: `'${code}' için yüklenen PDF doğrulandı ve başarıyla sisteme kaydedildi! (Hisse Oranı: %${res.stockRatio})`,
      });
      setPdfPreviewModal(null);
      loadAdminData();
    } catch (err: any) {
      setStatusMessage({
        type: 'error',
        text: `PDF yükleme hatası: ${err?.message || 'Ayrıştırılamadı'}`,
      });
    } finally {
      setIsUploadingPdf(false);
    }
  };

  // 3. Hisse Eşleme Onaylama
  const handleApproveAlias = async (id: number) => {
    try {
      await approveAlias(id);
      setPendingAliases((prev) => prev.filter((a) => a.id !== id));
      setStatusMessage({
        type: 'success',
        text: `Hisse eşleşmesi (ID: ${id}) başarıyla onaylandı ve sözlüğe kaydedildi.`,
      });
    } catch (err: any) {
      setStatusMessage({
        type: 'error',
        text: `Eşleşme onaylanırken hata oluştu: ${err?.message}`,
      });
    }
  };

  return (
    <div className="mx-auto max-w-7xl px-4 py-8 sm:px-6 lg:px-8 space-y-8">
      {/* ÜST BAŞLIK VE YENİ FON EKLE BUTONU */}
      <div className="flex flex-col sm:flex-row sm:items-center justify-between gap-4">
        <div>
          <div className="flex items-center gap-2 mb-1.5">
            <ShieldCheck className="h-5 w-5 text-emerald-400" />
            <span className="text-xs font-bold uppercase tracking-wider text-emerald-400">
              Sistem Yönetim & Denetim Odası (Backoffice)
            </span>
          </div>
          <h1 className="text-2xl sm:text-3xl font-black text-white tracking-tight">
            Yönetim ve Operasyon Portalı
          </h1>
          <p className="text-xs text-slate-400 mt-1">
            KAP robotu tetikleme, kategori bazlı tolerans denetimi ve hisse eşleme sözlüğü
          </p>
        </div>

        {/* 7 FON DIŞINDA YENİ FON EKLEME BUTONU */}
        <button
          onClick={() => setIsNewFundModalOpen(true)}
          className="flex items-center gap-2 rounded-xl bg-gradient-to-r from-emerald-600 to-teal-600 px-5 py-3 text-xs font-bold text-white shadow-lg shadow-emerald-600/30 transition-all hover:scale-105"
        >
          <Plus className="h-4 w-4" />
          <span>Yeni Takip Fonu Ekle</span>
        </button>
      </div>

      {/* GERİ BİLDİRİM BİLDİRİMİ */}
      {statusMessage && (
        <div
          className={`rounded-2xl p-4 text-xs font-semibold flex items-center justify-between border ${
            statusMessage.type === 'success'
              ? 'border-emerald-500/30 bg-emerald-500/10 text-emerald-300'
              : 'border-rose-500/30 bg-rose-500/10 text-rose-300'
          }`}
        >
          <span>{statusMessage.text}</span>
          <button
            onClick={() => setStatusMessage(null)}
            className="text-slate-400 hover:text-white"
          >
            ✕
          </button>
        </div>
      )}

      {/* ADMIN KPI ÖZET KARTLARI */}
      <div className="grid grid-cols-2 md:grid-cols-4 gap-4">
        <div className="glass-card rounded-2xl p-4 border border-slate-800">
          <span className="text-[11px] font-semibold uppercase text-slate-400">
            Aktif Takip Fonları
          </span>
          <p className="font-mono text-2xl font-black text-white mt-1">
            {funds.length} Fon
          </p>
        </div>

        <div className="glass-card rounded-2xl p-4 border border-slate-800">
          <span className="text-[11px] font-semibold uppercase text-slate-400">
            KAP Robot Durumu
          </span>
          <p className="font-mono text-2xl font-black text-emerald-400 mt-1">
            OTOMATİK
          </p>
        </div>

        <div className="glass-card rounded-2xl p-4 border border-slate-800">
          <span className="text-[11px] font-semibold uppercase text-slate-400">
            Onay Bekleyen Eşleşme
          </span>
          <p className="font-mono text-2xl font-black text-amber-400 mt-1">
            {pendingAliases.length} Adet
          </p>
        </div>

        <div className="glass-card rounded-2xl p-4 border border-slate-800">
          <span className="text-[11px] font-semibold uppercase text-slate-400">
            Yönsel Tahmin İsabeti
          </span>
          <p className="font-mono text-2xl font-black text-emerald-400 mt-1">
            {backtestReport?.directionalAccuracy != null
              ? `%${Number(backtestReport.directionalAccuracy).toFixed(1)}`
              : '—'}
          </p>
          <span className="text-[10px] text-slate-500 font-medium">
            {backtestReport ? `(${backtestReport.fundCode} Trend Doğruluğu)` : 'Takasbank Tescilli Seans'}
          </span>
        </div>
      </div>

      {/* SEKMELER */}
      <div className="flex items-center gap-2 border-b border-slate-800 pb-3">
        <button
          onClick={() => setActiveTab('FUNDS')}
          className={`flex items-center gap-2 rounded-xl px-4 py-2 text-xs font-bold transition-all ${
            activeTab === 'FUNDS'
              ? 'bg-slate-800 text-emerald-400 border border-slate-700'
              : 'text-slate-400 hover:bg-slate-900 hover:text-white'
          }`}
        >
          <Database className="h-4 w-4" />
          <span>Fonlar & KAP Otomasyonu</span>
        </button>

        <button
          onClick={() => setActiveTab('TOLERANCE')}
          className={`flex items-center gap-2 rounded-xl px-4 py-2 text-xs font-bold transition-all ${
            activeTab === 'TOLERANCE'
              ? 'bg-slate-800 text-emerald-400 border border-slate-700'
              : 'text-slate-400 hover:bg-slate-900 hover:text-white'
          }`}
        >
          <Layers className="h-4 w-4" />
          <span>Kategori Tolerans Matrisi & Karne</span>
        </button>

        <button
          onClick={() => setActiveTab('ALIASES')}
          className={`flex items-center gap-2 rounded-xl px-4 py-2 text-xs font-bold transition-all ${
            activeTab === 'ALIASES'
              ? 'bg-slate-800 text-emerald-400 border border-slate-700'
              : 'text-slate-400 hover:bg-slate-900 hover:text-white'
          }`}
        >
          <FileText className="h-4 w-4" />
          <span>Hisse Eşleme Sözlüğü ({pendingAliases.length})</span>
        </button>
      </div>

      {/* 1. SEKME: FON LİSTESİ VE TEK TIKLA KAP KEŞFİ */}
      {/* 1. SEKME: FON LİSTESİ VE TEK TIKLA KAP KEŞFİ */}
      {activeTab === 'FUNDS' && (
        <div className="rounded-2xl border border-slate-800 bg-slate-900/60 p-6 backdrop-blur-md">
          <div className="flex items-center justify-between mb-4">
            <div>
              <h3 className="text-sm font-bold text-white tracking-tight">
                Takip Edilen Fonlar & KAP PDF Meta Veri Takip Masası
              </h3>
              <p className="text-xs text-slate-400">
                Şartname T3.1 ve T3.2 tam otomasyonu: Her fonun en son indirilen PDF rapor dönemi,
                sisteme işlenme zamanı, SHA-256 güvenlik parmak izi ve güncel portföy büyüklüğü.
              </p>
            </div>
          </div>

          <div className="overflow-x-auto">
            <table className="w-full text-left text-xs">
              <thead>
                <tr className="border-b border-slate-800 text-[11px] font-semibold uppercase tracking-wider text-slate-400">
                  <th className="py-3 px-3">Kod & Fon Adı</th>
                  <th className="py-3 px-3">Yönetici Şirket</th>
                  <th className="py-3 px-3">KAP Rapor Dönemi</th>
                  <th className="py-3 px-3">İndirilme Zamanı</th>
                  <th className="py-3 px-3">SHA-256 & Kaynak</th>
                  <th className="py-3 px-3">Portföy Dağılımı</th>
                  <th className="py-3 px-3 text-right">KAP Otomasyonu</th>
                </tr>
              </thead>
              <tbody className="divide-y divide-slate-800/60 font-mono">
                {funds.map((fund) => {
                  const hasReport = !!(fund.reportDate || fund.latestSnapshotDate);
                  const reportDateFormatted = fund.reportDate || fund.latestSnapshotDate || '—';
                  const downloadedAtFormatted = fund.reportDownloadedAt
                    ? new Date(fund.reportDownloadedAt).toLocaleString('tr-TR', {
                        day: '2-digit',
                        month: '2-digit',
                        year: 'numeric',
                        hour: '2-digit',
                        minute: '2-digit',
                        second: '2-digit',
                      })
                    : '—';

                  const shortSha = fund.reportSha256
                    ? `${fund.reportSha256.substring(0, 8)}...${fund.reportSha256.substring(fund.reportSha256.length - 6)}`
                    : '—';

                  const isCopied = copiedHash === fund.reportSha256;

                  return (
                    <tr key={fund.code} className="hover:bg-slate-800/30 transition-colors">
                      {/* 1. KOD VE ADI */}
                      <td className="py-3.5 px-3 font-sans">
                        <div className="flex items-center gap-2">
                          <span className="rounded bg-slate-800 px-2 py-0.5 text-xs font-bold text-emerald-400 font-mono border border-slate-700">
                            {fund.code}
                          </span>
                          <div>
                            <span className="font-semibold text-slate-200 block truncate max-w-[190px]">
                              {fund.title}
                            </span>
                            <span className="text-[10px] text-slate-500 font-mono">
                              Yıllık Gider: {fund.annualFeeRatio != null ? `%${(fund.annualFeeRatio * 100).toFixed(2)}` : '—'}
                            </span>
                          </div>
                        </div>
                      </td>

                      {/* 2. YÖNETİCİ */}
                      <td className="py-3.5 px-3 font-sans text-slate-400 truncate max-w-[150px]">
                        {fund.manager}
                      </td>

                      {/* 3. RAPOR DÖNEMİ */}
                      <td className="py-3.5 px-3 font-sans">
                        {hasReport ? (
                          <span className="inline-flex items-center gap-1.5 rounded-lg bg-indigo-500/10 px-2.5 py-1 text-xs font-semibold text-indigo-400 border border-indigo-500/20">
                            <Calendar className="h-3 w-3" />
                            <span>{reportDateFormatted}</span>
                          </span>
                        ) : (
                          <span className="inline-flex items-center gap-1 rounded bg-amber-500/10 px-2 py-0.5 text-[11px] font-medium text-amber-400 border border-amber-500/20">
                            <AlertTriangle className="h-3 w-3" />
                            <span>Rapor Bekleniyor</span>
                          </span>
                        )}
                      </td>

                      {/* 4. İNDİRİLME ZAMANI */}
                      <td className="py-3.5 px-3 font-mono text-[11px] text-slate-300">
                        {downloadedAtFormatted !== '—' ? (
                          <span className="inline-flex items-center gap-1.5 text-slate-300">
                            <Clock className="h-3 w-3 text-slate-400" />
                            <span>{downloadedAtFormatted}</span>
                          </span>
                        ) : (
                          <span className="text-slate-600">—</span>
                        )}
                      </td>

                      {/* 5. SHA-256 VE KAYNAK */}
                      <td className="py-3.5 px-3 font-sans">
                        {fund.reportSha256 ? (
                          <div className="space-y-1">
                            <div className="flex items-center gap-1.5">
                              <span className="font-mono text-[11px] text-slate-400 bg-slate-950 px-2 py-0.5 rounded border border-slate-800">
                                {shortSha}
                              </span>
                              <button
                                onClick={() => handleCopyHash(fund.reportSha256!)}
                                title="SHA-256 Hash Kopyala"
                                className="text-slate-400 hover:text-emerald-400 transition-colors p-1"
                              >
                                {isCopied ? (
                                  <Check className="h-3 w-3 text-emerald-400" />
                                ) : (
                                  <Copy className="h-3 w-3" />
                                )}
                              </button>
                            </div>
                            <span className="inline-block text-[10px] text-emerald-400/90 font-medium">
                              {fund.reportSource && fund.reportSource.includes('MANUAL')
                                ? '• Manuel Yükleme'
                                : '• KAP Otomasyonu (Canlı)'}
                            </span>
                          </div>
                        ) : (
                          <span className="text-slate-600">—</span>
                        )}
                      </td>

                      {/* 6. PORTFÖY ÖZETİ (TNV & HİSSE) */}
                      <td className="py-3.5 px-3 font-mono">
                        {hasReport ? (
                          <div>
                            <span className="text-white font-bold block text-xs">
                              {fund.latestTotalNetAssetValue
                                ? `${(fund.latestTotalNetAssetValue / 1_000_000).toFixed(1)} M ₺`
                                : '—'}
                            </span>
                            <span className="text-[11px] text-emerald-400 font-sans">
                              Hisse: {fund.latestStockRatio != null ? `%${(fund.latestStockRatio * 100).toFixed(1)}` : '—'}
                            </span>
                          </div>
                        ) : (
                          <span className="text-slate-600">—</span>
                        )}
                      </td>

                      {/* 7. AKSİYONLAR */}
                      <td className="py-3.5 px-3 text-right font-sans">
                        <div className="flex items-center justify-end gap-1.5">
                          {/* DOSYA VE RAPOR GEÇMİŞİ ARŞİVİ BUTONU */}
                          <button
                            onClick={() => setHistoryModalFund({ code: fund.code, title: fund.title })}
                            className="flex items-center gap-1 rounded-lg bg-indigo-600/20 hover:bg-indigo-600/35 text-indigo-300 border border-indigo-500/30 px-2.5 py-1.5 text-[11px] font-bold transition-all hover:scale-105"
                            title="Aktif ve Geçmiş Dosyaları Görüntüle"
                          >
                            <History className="h-3.5 w-3.5" />
                            <span>Dosya Geçmişi</span>
                          </button>

                          {/* TEK TIKLA KAP'TAN OTOMATİK İNDİR VE CANLI TERMİNAL LOGU BUTONU */}
                          <button
                            onClick={() => setTerminalFundCode(fund.code)}
                            className="flex items-center gap-1.5 rounded-lg bg-emerald-600/90 hover:bg-emerald-500 px-3 py-1.5 text-[11px] font-bold text-white shadow-md transition-all hover:scale-105"
                          >
                            <Sparkles className="h-3.5 w-3.5" />
                            <span>KAP'ı Canlı Tara</span>
                          </button>

                          {/* MANUEL PDF YÜKLEME FALLBACK */}
                          <label className="cursor-pointer rounded-lg border border-slate-700 bg-slate-800 px-2.5 py-1.5 text-[11px] font-semibold text-slate-300 hover:bg-slate-700 hover:text-white transition-colors flex items-center gap-1">
                            <Upload className="h-3 w-3 text-slate-400" />
                            <span>PDF Yükle</span>
                            <input
                              type="file"
                              accept=".pdf"
                              className="hidden"
                              onChange={(e) => handleFileSelect(fund.code, e)}
                            />
                          </label>
                        </div>
                      </td>
                    </tr>
                  );
                })}
              </tbody>
            </table>
          </div>
        </div>
      )}

      {/* 2. SEKME: KATEGORİ BAZLI DİNAMİK TOLERANS MATRİSİ & KARNE */}
      {activeTab === 'TOLERANCE' && (
        <div className="space-y-6">
          <div className="flex flex-col sm:flex-row sm:items-center justify-between gap-4 rounded-2xl border border-slate-800 bg-slate-900/60 p-4">
            <div className="flex items-center gap-3">
              <span className="text-xs font-bold text-white">Denetlenecek Fon:</span>
              <select
                value={selectedBacktestFund}
                onChange={(e) => setSelectedBacktestFund(e.target.value)}
                className="rounded-xl border border-slate-700 bg-slate-950 px-4 py-2 text-xs font-bold text-white focus:border-emerald-500 focus:outline-none"
              >
                {funds.map((f) => (
                  <option key={f.code} value={f.code}>
                    {f.code} — {f.title}
                  </option>
                ))}
              </select>
            </div>

            <div className="flex flex-wrap items-center gap-3">
              {/* ⚡ Tekil Fon Backtest'i */}
              <button
                onClick={() => handleRunBacktest(selectedBacktestFund)}
                disabled={isRunningBacktest || isRunningAllBacktests}
                className="flex items-center gap-2 rounded-xl bg-slate-800 hover:bg-slate-700 border border-slate-700 px-3.5 py-2 text-xs font-bold text-white transition-all hover:scale-105 disabled:opacity-50 disabled:scale-100"
              >
                {isRunningBacktest ? (
                  <>
                    <RefreshCw className="h-3.5 w-3.5 animate-spin text-emerald-400" />
                    <span>Seçili Fon Hesaplanıyor...</span>
                  </>
                ) : (
                  <>
                    <Zap className="h-3.5 w-3.5 text-amber-300" />
                    <span>⚡ Seçili Fonu Çalıştır</span>
                  </>
                )}
              </button>

              {/* 🚀 Tüm Fonların Backtest'ini Toplu Çalıştır Butonu */}
              <button
                onClick={handleRunAllBacktests}
                disabled={isRunningBacktest || isRunningAllBacktests}
                className="flex items-center gap-2 rounded-xl bg-gradient-to-r from-emerald-600 via-teal-600 to-indigo-600 hover:from-emerald-500 hover:to-indigo-500 px-4 py-2 text-xs font-bold text-white shadow-lg shadow-emerald-900/30 transition-all hover:scale-105 disabled:opacity-50 disabled:scale-100"
              >
                {isRunningAllBacktests ? (
                  <>
                    <RefreshCw className="h-3.5 w-3.5 animate-spin text-white" />
                    <span>Tüm Fonlar Simüle Ediliyor...</span>
                  </>
                ) : (
                  <>
                    <Sparkles className="h-3.5 w-3.5 text-amber-300" />
                    <span>🚀 Tüm Fonları Simüle Et (Batch)</span>
                  </>
                )}
              </button>
            </div>
          </div>

          {/* Toplu Simülasyon Özeti Kartı */}
          {batchBacktestSummary && (
            <div className="rounded-xl border border-emerald-500/30 bg-emerald-950/20 p-3.5 text-xs">
              <div className="flex flex-wrap items-center justify-between gap-3 mb-2">
                <span className="font-bold text-emerald-400 flex items-center gap-1.5">
                  <CheckCircle2 className="h-4 w-4" />
                  Toplu Simülasyon Raporu ({batchBacktestSummary.totalFunds} Fon)
                </span>
                <span className="font-mono text-slate-400 text-[11px]">
                  İcra Süresi: {(batchBacktestSummary.executionTimeMs / 1000).toFixed(2)} sn | Başarılı: {batchBacktestSummary.successfulFunds} | Hatalı: {batchBacktestSummary.failedFunds}
                </span>
              </div>
              <div className="flex flex-wrap gap-2">
                {batchBacktestSummary.results.map((r) => (
                  <span
                    key={r.fundCode}
                    className={`inline-flex items-center gap-1.5 px-2.5 py-1 rounded-lg border font-mono text-[11px] ${
                      r.status === 'SUCCESS' || r.status === 'INSUFFICIENT_HISTORY'
                        ? 'bg-slate-900/80 border-emerald-500/30 text-emerald-300'
                        : 'bg-rose-950/40 border-rose-500/30 text-rose-300'
                    }`}
                  >
                    <span className="font-bold">{r.fundCode}:</span>
                    <span>{r.totalDaysTested} Gün</span>
                    {r.meanAbsoluteError != null && (
                      <span className="text-slate-400">(MAE: %{(r.meanAbsoluteError * 100).toFixed(2)})</span>
                    )}
                  </span>
                ))}
              </div>
            </div>
          )}

          {isBacktestLoading ? (
            <div className="rounded-2xl border border-slate-800 bg-slate-900/40 p-12 text-center flex flex-col items-center justify-center gap-3">
              <RefreshCw className="h-6 w-6 text-emerald-400 animate-spin" />
              <p className="text-xs text-slate-400 font-mono">
                {selectedBacktestFund} için ekonometrik tolerans matrisi ve karne yükleniyor...
              </p>
            </div>
          ) : (
            <BacktestToleranceMatrix report={backtestReport} />
          )}
        </div>
      )}

      {/* 3. SEKME: ONAY BEKLEYEN KİRLİ HİSSE EŞLEME MASASI */}
      {activeTab === 'ALIASES' && (
        <div className="rounded-2xl border border-slate-800 bg-slate-900/60 p-6 backdrop-blur-md">
          <div className="mb-4">
            <h3 className="text-sm font-bold text-white tracking-tight">
              KAP PDF Kirli Şirket Unvanı Eşleme Masası
            </h3>
            <p className="text-xs text-slate-400">
              PDF'lerde standart dışı uzun şirket unvanları ile karşılaşıldığında sistemin
              ürettiği öneriler. Bir kez onaylandığında sistem bu unvanı sonsuza dek tanır.
            </p>
          </div>

          {pendingAliases.length > 0 ? (
            <div className="overflow-x-auto">
              <table className="w-full text-left text-xs">
                <thead>
                  <tr className="border-b border-slate-800 text-[11px] font-semibold uppercase tracking-wider text-slate-400">
                    <th className="py-2.5 px-3">PDF'teki Ham Metin</th>
                    <th className="py-2.5 px-3">Eşleşen BIST Sembolü</th>
                    <th className="py-2.5 px-3">Kanonik Şirket Adı</th>
                    <th className="py-2.5 px-3">Benzerlik Skoru</th>
                    <th className="py-2.5 px-3 text-right">İşlem</th>
                  </tr>
                </thead>
                <tbody className="divide-y divide-slate-800/60 font-mono">
                  {pendingAliases.map((item) => (
                    <tr key={item.id} className="hover:bg-slate-800/30">
                      <td className="py-3 px-3 text-slate-300 font-sans font-medium">
                        {item.rawName}
                      </td>
                      <td className="py-3 px-3 font-bold text-emerald-400">
                        {item.matchedTicker}
                      </td>
                      <td className="py-3 px-3 text-slate-400 font-sans">
                        {item.matchedTitle}
                      </td>
                      <td className="py-3 px-3 text-indigo-400">
                        %{((item.matchConfidence || 0.95) * 100).toFixed(0)}
                      </td>
                      <td className="py-3 px-3 text-right font-sans">
                        <button
                          onClick={() => handleApproveAlias(item.id)}
                          className="rounded-lg bg-emerald-600 hover:bg-emerald-500 px-3 py-1.5 text-xs font-bold text-white shadow-md transition-all"
                        >
                          Eşleşmeyi Onayla
                        </button>
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          ) : (
            <div className="rounded-xl border border-slate-800 bg-slate-950/40 p-8 text-center text-xs text-slate-400">
              <CheckCircle2 className="h-6 w-6 text-emerald-400 mx-auto mb-2" />
              <span>Harika! Onay bekleyen hiçbir kirli hisse eşleşmesi kalmadı. Tüm şirket adları kanonik sözlükle %100 uyumludur.</span>
            </div>
          )}
        </div>
      )}

      {/* YENİ FON EKLEME MODALI */}
      <NewFundModal
        isOpen={isNewFundModalOpen}
        onClose={() => setIsNewFundModalOpen(false)}
        onSuccess={(newCode) => {
          setStatusMessage({
            type: 'success',
            text: `'${newCode}' fonu sisteme başarıyla tanımlandı ve takibe alındı!`,
          });
          loadAdminData();
        }}
      />

      {/* KAP CANLI TARAMA VE OCR TERMİNAL LOG MODALI */}
      <KapScanTerminalModal
        fundCode={terminalFundCode}
        onClose={() => setTerminalFundCode(null)}
        onSuccess={() => {
          setStatusMessage({
            type: 'success',
            text: `'${terminalFundCode}' KAP raporu başarıyla tarandı ve portföy güncellendi!`,
          });
          loadAdminData();
        }}
      />

      {/* FON RAPOR VE DOSYA GEÇMİŞİ ARŞİVİ MODALI */}
      {historyModalFund && (
        <FundReportHistoryModal
          fundCode={historyModalFund.code}
          fundTitle={historyModalFund.title}
          isOpen={!!historyModalFund}
          onClose={() => setHistoryModalFund(null)}
          onReportActivated={() => loadAdminData()}
        />
      )}
    </div>
  );
}
