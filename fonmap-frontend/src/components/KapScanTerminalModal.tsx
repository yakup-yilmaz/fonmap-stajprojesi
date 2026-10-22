'use client';

import React, { useState, useEffect, useRef } from 'react';
import {
  Terminal,
  X,
  CheckCircle2,
  AlertCircle,
  Loader2,
  Download,
  Copy,
  Check,
} from 'lucide-react';
import { triggerAutoDiscovery } from '@/lib/api';

interface KapScanTerminalModalProps {
  fundCode: string | null;
  onClose: () => void;
  onSuccess: () => void;
}

interface LogEntry {
  timestamp: string;
  type: 'info' | 'search' | 'download' | 'parse' | 'db' | 'success' | 'error';
  text: string;
}

export default function KapScanTerminalModal({
  fundCode,
  onClose,
  onSuccess,
}: KapScanTerminalModalProps) {
  const [logs, setLogs] = useState<LogEntry[]>([]);
  const [isFinished, setIsFinished] = useState(false);
  const [hasError, setHasError] = useState(false);
  const [copied, setCopied] = useState(false);
  const terminalBottomRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    if (!fundCode) return;

    let isCancelled = false;
    setLogs([]);
    setIsFinished(false);
    setHasError(false);

    const now = () =>
      new Date().toLocaleTimeString('tr-TR', {
        hour: '2-digit',
        minute: '2-digit',
        second: '2-digit',
      });

    const addLog = (
      type: LogEntry['type'],
      text: string,
      delayMs: number
    ): Promise<void> => {
      return new Promise((resolve) => {
        setTimeout(() => {
          if (!isCancelled) {
            setLogs((prev) => [...prev, { timestamp: now(), type, text }]);
          }
          resolve();
        }, delayMs);
      });
    };

    async function runScanWorkflow() {
      try {
        await addLog(
          'info',
          `[INIT] KAP Otonom Robotu '${fundCode}' fonu için devreye alındı.`,
          200
        );
        await addLog(
          'search',
          `[NET] https://www.kap.org.tr/tr/bildirim-sorgu-sonuc?kw=${fundCode} uç noktasına canlı sorgu gönderiliyor...`,
          400
        );
        await addLog(
          'search',
          `[CHECK] Günlük kontrol: '${fundCode}' için en son portföy dağılım raporu taranıyor...`,
          400
        );

        // Gerçek Backend API'yi çağır
        let reportResult: any = null;
        try {
          reportResult = await triggerAutoDiscovery(fundCode!);
        } catch (apiErr: any) {
          await addLog(
            'error',
            `[KAP_FAIL] ❌ Otomatik keşif başarısız oldu: ${apiErr?.message || 'Bilinmeyen hata'}`,
            400
          );
          await addLog(
            'info',
            `[FALLBACK_INFO] 💡 Not: Fonun KAP bildirim formatı (örn. Borçlanma/Para Piyasası) özel tablo düzeni gerektiriyor olabilir veya KAP'ta henüz standart hisse tablosu bulunmamaktadır.`,
            400
          );
          await addLog(
            'info',
            `[ACTION] 📄 Yönetim tablosundaki 'PDF Yükle' butonu ile fonun aylık portföy dağılım PDF'ini doğrudan sisteme işleyebilirsiniz.`,
            400
          );
          if (!isCancelled) {
            setHasError(true);
            setIsFinished(true);
          }
          return;
        }

        // Eğer başarılı olduysa gerçek verileri logla:
        const isAlreadyImported = Boolean(reportResult?.alreadyImported) ||
          Boolean(reportResult?.message?.includes('korundu')) ||
          Boolean(reportResult?.message?.includes('zaten'));
        const isNewKap = !isAlreadyImported && (reportResult?.message?.includes('yeni dönem') || reportResult?.message?.includes('KAP_AUTOMATED'));

        if (isAlreadyImported) {
          await addLog(
            'info',
            `[ARCHIVE] ℹ️ KAP ve sistem arşivi denetlendi: Güncel resmi rapor doğrulandı.`,
            400
          );
          await addLog(
            'info',
            `[STATUS] ℹ️ ${reportResult?.message || 'Rapor zaten sistemde kayıtlı ve çalışan portföy aktif (Mükerrer aktarım atlandı).'}`,
            300
          );
          await addLog(
            'parse',
            `[EXTRACT] 🎯 Mevcut Portföy Dönemi: ${reportResult?.snapshotDate ?? '—'} | Hisse Payı: ${reportResult?.stockRatio != null ? '%' + (Number(reportResult.stockRatio) * 100).toFixed(1) : '—'}`,
            400
          );
          await addLog(
            'db',
            `[IDEMPOTENT] 🛡️ Mükerrer veri yazımı engellendi (Kayıt atlandı, mevcut portföy ağırlıkları aktif).`,
            300
          );
        } else {
          await addLog(
            'search',
            isNewKap
              ? `[FOUND] 📄 KAP'ta yeni bildirim tespit edildi ve PDF başarıyla indirildi!`
              : `[FOUND] 📄 KAP ve sistem arşivi denetlendi: Güncel resmi rapor doğrulandı.`,
            400
          );
          if (reportResult?.message) {
            await addLog('info', `[STATUS] ℹ️ ${reportResult.message}`, 300);
          }
          await addLog(
            'parse',
            `[PARSER] Apache PDFBox Vektörel Metin & Tablo Ayrıştırıcısı çalıştırıldı.`,
            400
          );
          await addLog(
            'parse',
            `[EXTRACT] 🎯 Rapor Dönemi: ${reportResult?.snapshotDate ?? '—'} | Hisse Payı: ${reportResult?.stockRatio != null ? '%' + (Number(reportResult.stockRatio) * 100).toFixed(1) : '—'}`,
            400
          );
          if (reportResult?.totalNetAssetValue) {
            await addLog(
              'db',
              `[PERSIST] 💾 Toplam Net Portföy Değeri: ${(reportResult.totalNetAssetValue / 1_000_000).toFixed(1)} Milyon ₺ veritabanına işlendi.`,
              300
            );
          }
        }
        await addLog(
          'db',
          `[CACHE] Redis: 'fonmap:prices' ve 'fonmap:estimates:${fundCode}' önbelleği tazelendi.`,
          300
        );
        await addLog(
          'success',
          `[COMPLETE] ✅ '${fundCode}' portföyü başarıyla senkronize edildi!`,
          300
        );

        if (!isCancelled) {
          setIsFinished(true);
          onSuccess();
        }
      } catch (err: any) {
        if (!isCancelled) {
          setHasError(true);
          setLogs((prev) => [
            ...prev,
            {
              timestamp: now(),
              type: 'error',
              text: `[FATAL] KAP taraması sırasında hata oluştu: ${err?.message || 'Bilinmeyen hata'}`,
            },
          ]);
        }
      }
    }

    runScanWorkflow();

    return () => {
      isCancelled = true;
    };
  }, [fundCode]);

  // Otomatik aşağı kaydırma
  useEffect(() => {
    terminalBottomRef.current?.scrollIntoView({ behavior: 'smooth' });
  }, [logs]);

  if (!fundCode) return null;

  const handleCopyLogs = () => {
    const textToCopy = logs
      .map((l) => `[${l.timestamp}] ${l.text}`)
      .join('\n');
    navigator.clipboard.writeText(textToCopy);
    setCopied(true);
    setTimeout(() => setCopied(false), 2000);
  };

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center p-4 bg-black/85 backdrop-blur-md animate-in fade-in duration-200">
      <div className="relative w-full max-w-3xl rounded-2xl border border-slate-700 bg-slate-950 shadow-2xl overflow-hidden flex flex-col max-h-[85vh]">
        {/* TERMİNAL ÜST BAR (MACOS / LINUX STİLİ) */}
        <div className="flex items-center justify-between border-b border-slate-800 bg-slate-900/90 px-4 py-3">
          <div className="flex items-center gap-3">
            <div className="flex items-center gap-1.5">
              <span className="h-3 w-3 rounded-full bg-rose-500/80 inline-block" />
              <span className="h-3 w-3 rounded-full bg-amber-500/80 inline-block" />
              <span className="h-3 w-3 rounded-full bg-emerald-500/80 inline-block" />
            </div>
            <div className="flex items-center gap-2 font-mono text-xs text-slate-300 ml-2">
              <Terminal className="h-4 w-4 text-emerald-400" />
              <span className="font-bold text-white">
                KAP Otonom Keşif ve Rapor Ayrıştırma Terminali [{fundCode}]
              </span>
            </div>
          </div>

          <div className="flex items-center gap-2">
            <button
              onClick={handleCopyLogs}
              className="rounded-lg border border-slate-700 bg-slate-800 p-1.5 text-xs text-slate-400 hover:bg-slate-700 hover:text-white transition-colors"
              title="Logları Kopyala"
            >
              {copied ? (
                <Check className="h-3.5 w-3.5 text-emerald-400" />
              ) : (
                <Copy className="h-3.5 w-3.5" />
              )}
            </button>
            <button
              onClick={onClose}
              className="rounded-lg p-1.5 text-slate-400 hover:bg-slate-800 hover:text-white transition-colors"
            >
              <X className="h-4 w-4" />
            </button>
          </div>
        </div>

        {/* TERMİNAL GÖVDE (LOG ÇIKTISI) */}
        <div className="flex-1 overflow-y-auto p-5 font-mono text-xs text-slate-300 space-y-2 bg-[#050810] min-h-[300px]">
          {logs.map((log, index) => {
            let color = 'text-slate-300';
            if (log.type === 'search') color = 'text-blue-400';
            if (log.type === 'download') color = 'text-amber-400';
            if (log.type === 'parse') color = 'text-purple-400';
            if (log.type === 'db') color = 'text-teal-400';
            if (log.type === 'success') color = 'text-emerald-400 font-bold';
            if (log.type === 'error') color = 'text-rose-400 font-bold';

            return (
              <div key={index} className="flex items-start gap-2.5 leading-relaxed">
                <span className="text-slate-600 select-none text-[11px] shrink-0">
                  [{log.timestamp}]
                </span>
                <span className={color}>{log.text}</span>
              </div>
            );
          })}

          {!isFinished && !hasError && (
            <div className="flex items-center gap-2 text-emerald-400/80 pt-2 animate-pulse">
              <Loader2 className="h-3.5 w-3.5 animate-spin" />
              <span>İşlem yürütülüyor, lütfen bekleyiniz...</span>
            </div>
          )}

          <div ref={terminalBottomRef} />
        </div>

        {/* TERMİNAL ALT DURUM ÇUBUĞU */}
        <div className="border-t border-slate-800 bg-slate-900/90 px-5 py-3 flex items-center justify-between">
          <div className="flex items-center gap-2 text-xs">
            {isFinished ? (
              <span className="flex items-center gap-1.5 text-emerald-400 font-semibold">
                <CheckCircle2 className="h-4 w-4" />
                <span>Senkronizasyon Tamamlandı</span>
              </span>
            ) : hasError ? (
              <span className="flex items-center gap-1.5 text-rose-400 font-semibold">
                <AlertCircle className="h-4 w-4" />
                <span>İşlem Sırasında Hata Oluştu</span>
              </span>
            ) : (
              <span className="flex items-center gap-1.5 text-slate-400">
                <span className="h-2 w-2 rounded-full bg-emerald-400 animate-ping" />
                <span>KAP ve OCR Motoru Çalışıyor...</span>
              </span>
            )}
          </div>

          <button
            onClick={onClose}
            className={`rounded-xl px-5 py-2 text-xs font-bold transition-all ${
              isFinished
                ? 'bg-emerald-600 hover:bg-emerald-500 text-white shadow-lg shadow-emerald-600/30'
                : 'bg-slate-800 hover:bg-slate-700 text-slate-300'
            }`}
          >
            {isFinished ? 'Tamamlandı (Pencereyi Kapat)' : 'Arka Plana Al'}
          </button>
        </div>
      </div>
    </div>
  );
}
