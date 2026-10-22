/**
 * FONMAP — Merkezi API İstemcisi (API Client)
 * ============================================
 * Spring Boot 3 backend uç noktalarına tip-güvenli (Type-safe) istekler atar.
 * Zero-Mock prensibi: Yalnızca canlı backend verileri ve resmi KAP entegrasyonu kullanılır.
 */

import {
  Fund,
  EstimateResult,
  EstimateDetail,
  BacktestReport,
  PendingAlias,
  UploadReportResponse,
  FundReportHistory,
  FundReportItem,
  PdfPreviewResponse,
  BatchBacktestResult,
} from '@/types/fonmap';
const API_BASE_URL =
  process.env.NEXT_PUBLIC_API_URL || 'http://localhost:8080/api/v1';

/**
 * Finansal Zaman Damgası Formatlayıcı
 * Backend'den gelen ham Java LocalDateTime string'lerini ('2026-09-30T19:18:58.4962144')
 * veya zaman verilerini insan gözünün rahat okuyabileceği '19:18:58' formatına dönüştürür.
 */
export function formatCalculationTime(timeStr?: string | null): string {
  if (!timeStr) {
    return new Date().toLocaleTimeString('tr-TR', {
      hour: '2-digit',
      minute: '2-digit',
      second: '2-digit',
    });
  }

  // Eğer ISO formatındaysa (örn: '2026-09-30T19:18:58.4962144')
  if (timeStr.includes('T')) {
    try {
      const timePart = timeStr.split('T')[1];
      if (timePart) {
        return timePart.split('.')[0].slice(0, 8);
      }
    } catch {
      // Hata durumunda aşağıdaki formata devam
    }
  }

  // Eğer saat dakika saniye formatındaysa (örn: '19:18:58.123')
  if (timeStr.includes(':')) {
    return timeStr.split('.')[0].slice(0, 8);
  }

  return timeStr;
}

/**
 * 1. Tüm Aktif Fonların Listesini Getirir
 */
export async function fetchActiveFunds(): Promise<Fund[]> {
  try {
    const res = await fetch(`${API_BASE_URL}/funds`, {
      cache: 'no-store',
    });
    if (!res.ok) throw new Error(`HTTP ${res.status}: Fon listesi alınamadı`);
    return await res.json();
  } catch (error) {
    console.warn('[API] Backend fon listesine ulaşılamadı:', error);
    throw error;
  }
}

export const OFFICIAL_MANAGERS: Record<string, string> = {
  TTE: 'İş Portföy Yönetimi A.Ş.',
  KHA: 'Pardus Portföy Yönetimi A.Ş.',
  ICZ: 'Ak Portföy Yönetimi A.Ş.',
};

/**
 * Borsa İstanbul Pay Piyasası Seans Kontrolü
 * BIST Pay Piyasası Hafta İçi 09:55 - 18:10 saatleri arasında açıktır.
 * 18:10'dan sonra piyasa kapalıdır ve resmi kapanış fiyatları dondurulur.
 */
export function isMarketOpen(now: Date = new Date()): boolean {
  try {
    // BIST seansı kullanıcının yerel tarayıcı saatine değil, resmi Europe/Istanbul (TSİ) saatine bağlıdır
    const formatter = new Intl.DateTimeFormat('en-US', {
      timeZone: 'Europe/Istanbul',
      weekday: 'short',
      hour: 'numeric',
      minute: 'numeric',
      hour12: false,
    });
    const parts = formatter.formatToParts(now);
    const weekday = parts.find((p) => p.type === 'weekday')?.value;
    const hour = parseInt(parts.find((p) => p.type === 'hour')?.value || '0', 10);
    const minute = parseInt(parts.find((p) => p.type === 'minute')?.value || '0', 10);

    if (weekday === 'Sat' || weekday === 'Sun') return false;

    const totalMinutes = hour * 60 + minute;
    const openMinutes = 9 * 60 + 55;   // 09:55
    const closeMinutes = 18 * 60 + 10; // 18:10

    return totalMinutes >= openMinutes && totalMinutes < closeMinutes;
  } catch {
    const day = now.getDay();
    if (day === 0 || day === 6) return false;
    const totalMinutes = now.getHours() * 60 + now.getMinutes();
    return totalMinutes >= 595 && totalMinutes < 1090;
  }
}

// Canlı verilerin bellekte saklanması (Ağ gecikmesinde eski mock'a düşmeyi engeller)
let cachedLiveEstimates: EstimateResult[] = [];

/**
 * Bellekteki güncel tahmin kaydını döner (Sayfa geçişlerinde anında render için)
 */
export function getCachedEstimate(fundCode: string): EstimateResult | undefined {
  const code = fundCode.trim().toUpperCase();
  return cachedLiveEstimates.find((e) => e.fundCode.toUpperCase() === code);
}

export function getAllCachedEstimates(): EstimateResult[] {
  return cachedLiveEstimates;
}

/**
 * 2. Tüm Fonlar İçin En Son Gün İçi Tahmin Sonuçlarını Getirir (Canlı Kart Izgarası)
 */
export async function fetchLatestEstimates(
  force: boolean = false
): Promise<EstimateResult[]> {
  try {
    const url = force
      ? `${API_BASE_URL}/estimates/latest?force=true`
      : `${API_BASE_URL}/estimates/latest`;
    const res = await fetch(url, {
      cache: 'no-store',
    });
    if (!res.ok) throw new Error(`HTTP ${res.status}: Tahminler alınamadı`);
    const liveCards = await res.json();

    // Her fon için canlı verileri eşle (canlı yanıta asla mock sızdırılmaz)
    const mapped: EstimateResult[] = liveCards.map((card: any) => {
      const code = card.fundCode.toUpperCase();

      return {
        fundCode: card.fundCode,
        fundTitle: card.fundTitle,
        manager:
          card.manager ||
          OFFICIAL_MANAGERS[code] ||
          '—',
        estimatedReturn: card.estimatedReturn,
        currentPrice: card.officialPrice ?? card.currentPrice ?? null,
        estimatedPrice: card.estimatedPrice ?? null,
        coverageRatio: card.coverageRatio,
        confidenceLevel: card.confidenceLevel,
        calculatedAt: formatCalculationTime(card.calculatedAt),
        snapshotDate: card.snapshotDate || null,
        totalNetAssetValue: card.totalNetAssetValue ?? null,
        stockRatio: card.stockRatio ?? null,
        viopCashRatio: card.viopCashRatio ?? null,
        details: [],
      };
    });

    if (mapped.length > 0) {
      cachedLiveEstimates = mapped;
    }
    return mapped;
  } catch (error) {
    console.warn('[API] Backend tahmin servisine ulaşılamadı:', error);
    // Hata sessizce yutulmaz ve bayat veri taze gibi maskelenmez; ekranın gerçek durumu bilmesi için fırlatılır
    throw error;
  }
}

/**
 * 3. Tek Bir Fonun Detaylı Gün İçi Tahmini ve Varlık Katkılarını Getirir
 */
export async function fetchFundEstimate(
  fundCode: string
): Promise<EstimateResult | null> {
  const cleanCode = fundCode.trim().toUpperCase();
  try {
    const res = await fetch(
      `${API_BASE_URL}/estimates/${cleanCode}`,
      { cache: 'no-store' }
    );
    if (!res.ok) throw new Error(`HTTP ${res.status}: Fon tahmini bulunamadı`);
    const data = await res.json();

    // Backend ContributorItem listesini frontend EstimateDetail'e dönüştür
    const mapContributor = (item: any): EstimateDetail => ({
      instrumentTicker: item.ticker,
      instrumentTitle: item.title,
      assetClass: item.assetClass || 'EQUITY',
      effectiveWeight: item.effectiveWeight,
      assetReturn: item.assetReturn,
      weightedContribution: item.weightedContribution,
    });

    const leaders = (data.leaders || []).map(mapContributor);
    const laggards = (data.laggards || []).map(mapContributor);
    const allDetails = [...leaders, ...laggards];

    return {
      fundCode: data.fundCode,
      fundTitle: data.fundTitle,
      manager:
        data.manager ||
        OFFICIAL_MANAGERS[cleanCode] ||
        '—',
      estimatedReturn: data.estimatedReturn,
      currentPrice: data.currentPrice ?? data.officialPrice ?? null,
      estimatedPrice: data.estimatedPrice ?? null,
      coverageRatio: data.coverageRatio,
      confidenceLevel: data.confidenceLevel,
      calculatedAt: formatCalculationTime(data.calculatedAt),
      snapshotDate: data.snapshotDate || null,
      totalNetAssetValue: data.totalNetAssetValue ?? null,
      stockRatio: data.stockRatio ?? null,
      viopCashRatio: data.viopCashRatio ?? null,
      details: allDetails,
    };
  } catch (error) {
    console.warn(`[API] '${cleanCode}' tahmini alınamadı:`, error);
    return null;
  }
}

/**
 * 4. Fonun 60 Günlük Geriye Dönük Ekonometrik Backtest Raporunu Getirir
 */
/**
 * Backend BacktestReportDto nesnesini Frontend BacktestReport arayüzüne tam uyumlu eşler.
 */
function mapRawBacktestReport(raw: any, code: string): BacktestReport {
  return {
    fundCode: raw.fundCode || code,
    fundTitle: raw.fundTitle || `${code} Yatırım Fonu`,
    category: raw.fundCategory || raw.category || 'HİSSE SENEDİ YOĞUN',
    startDate: raw.startDate || '',
    endDate: raw.endDate || '',
    totalDays: raw.totalDays ?? raw.totalDaysTested ?? (raw.dailyRecords?.length || 0),
    availableSnapshotCount: raw.availableSnapshotCount ?? 0,
    earliestTestableDate: raw.earliestTestableDate || null,
    latestTestableDate: raw.latestTestableDate || null,
    snapshotTransitionLogs: raw.snapshotTransitionLogs || [],
    chainStatus: raw.chainStatus || 'VALID',
    requiresNewerSnapshot: Boolean(raw.requiresNewerSnapshot),
    integrityWarningMessage: raw.integrityWarningMessage || null,
    safeSimulationEndDate: raw.safeSimulationEndDate || null,
    mae: raw.mae ?? (raw.meanAbsoluteError != null ? Number(raw.meanAbsoluteError) : 0),
    rmse: raw.rmse ?? (raw.rootMeanSquaredError != null ? Number(raw.rootMeanSquaredError) : 0),
    directionalAccuracy: raw.directionalAccuracy != null
      ? (Number(raw.directionalAccuracy) <= 1 ? Number(raw.directionalAccuracy) * 100 : Number(raw.directionalAccuracy))
      : 0,
    overallSuccessRate: raw.overallSuccessRate != null
      ? Number(raw.overallSuccessRate)
      : (raw.successRate != null ? Number(raw.successRate) * 100 : 0),
    certifiedDaysCount: raw.certifiedDaysCount ?? (raw.dailyRecords?.length || 0),
    missingDaysCount: raw.missingDaysCount ?? 0,
    perfectDaysCount: raw.perfectDaysCount ?? 0,
    acceptableDaysCount: raw.acceptableDaysCount ?? 0,
    anomalyDaysCount: raw.anomalyDaysCount ?? 0,
    dailyComparisons: raw.dailyComparisons || (raw.dailyRecords || []).map((r: any) => ({
      date: r.date,
      previousTefasPrice: r.previousTefasPrice != null ? Number(r.previousTefasPrice) : null,
      actualTefasPrice: r.actualTefasPrice != null ? Number(r.actualTefasPrice) : null,
      estimatedTefasPrice: r.estimatedTefasPrice != null ? Number(r.estimatedTefasPrice) : null,
      predictedReturn: Number(r.estimatedReturn || r.predictedReturn || 0),
      actualReturn: r.actualReturn != null ? Number(r.actualReturn) : null,
      errorDiff: r.errorDiff != null ? Number(r.errorDiff) : null,
      absoluteError: r.absoluteError != null ? Number(r.absoluteError) : null,
      directionMatched: r.directionMatched != null ? Boolean(r.directionMatched) : null,
      status: r.actualReturn != null ? 'CONFIRMED' : 'MISSING',
      isPerfect: r.toleranceStatus?.includes('KUSURSUZ') ?? false,
      isAcceptable: r.toleranceStatus?.includes('TOLERANS') ?? false,
      snapshotTransitionDay: r.snapshotTransitionDay ?? false,
      transitionRemark: r.transitionRemark ?? null,
    })),
  };
}

/**
 * 4. Fon İçin Ekonometrik Backtest Raporunu Getirir (Redis L2 Cache Destekli)
 */
export async function fetchBacktestReport(
  fundCode: string,
  startDate?: string,
  endDate?: string
): Promise<BacktestReport | null> {
  const code = fundCode.trim().toUpperCase();
  try {
    let url = `${API_BASE_URL}/funds/${code}/backtest`;
    const params = new URLSearchParams();
    if (startDate) params.append('startDate', startDate);
    if (endDate) params.append('endDate', endDate);
    if (params.toString()) url += `?${params.toString()}`;

    const res = await fetch(url, { cache: 'no-store' });
    if (!res.ok) throw new Error(`HTTP ${res.status}: Backtest raporu alınamadı`);
    const raw: any = await res.json();

    // Eğer backend'den hiç kayıt gelmemişse veya dizi boşsa null dön
    if (!raw || (!raw.dailyRecords && !raw.dailyComparisons) || (raw.dailyRecords && raw.dailyRecords.length === 0)) {
      return null;
    }

    return mapRawBacktestReport(raw, code);
  } catch (error) {
    console.warn(`[API] '${code}' backtest raporu alınamadı:`, error);
    return null;
  }
}

/**
 * 4.1. Admin İçin Parametreli Backtest Tetikleme (POST /api/v1/admin/backtest/{code}/run)
 */
export async function triggerBacktestRun(
  fundCode: string,
  startDate?: string,
  endDate?: string
): Promise<BacktestReport | null> {
  const code = fundCode.trim().toUpperCase();
  const body: any = {};
  if (startDate) body.startDate = startDate;
  if (endDate) body.endDate = endDate;

  const res = await fetch(`${API_BASE_URL}/admin/backtest/${code}/run`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
  });
  if (!res.ok) throw new Error(`HTTP ${res.status}: Backtest çalıştırılamadı`);
  const raw: any = await res.json();
  return mapRawBacktestReport(raw, code);
}

/**
 * 4.2. Tüm Aktif Fonların Backtest'ini Toplu Çalıştır (POST /api/v1/admin/backtest/run-all)
 */
export async function triggerRunAllBacktests(): Promise<BatchBacktestResult> {
  const res = await fetch(`${API_BASE_URL}/admin/backtest/run-all`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
  });
  if (!res.ok) throw new Error(`HTTP ${res.status}: Toplu backtest simülasyonu çalıştırılamadı`);
  return await res.json();
}

/**
 * 5. KAP'tan Otomatik Rapor Keşfet ve İndir (T3.1 & T3.2)
 */
export async function triggerAutoDiscovery(
  fundCode: string
): Promise<UploadReportResponse> {
  const res = await fetch(
    `${API_BASE_URL}/admin/funds/${fundCode.toUpperCase()}/discover-report`,
    {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
    }
  );
  if (!res.ok) {
    let errMessage = 'KAP otomatik keşfi başarısız oldu.';
    try {
      const errJson = await res.json();
      errMessage = errJson.message || errJson.error || errMessage;
    } catch {
      const text = await res.text();
      if (text) errMessage = text;
    }
    throw new Error(errMessage);
  }
  return await res.json();
}

/**
 * 6. Onay Bekleyen Kirli Hisse Eşleme Listesini Getirir
 */
export async function fetchPendingAliases(): Promise<PendingAlias[]> {
  try {
    const res = await fetch(`${API_BASE_URL}/admin/aliases/pending`, {
      cache: 'no-store',
    });
    if (!res.ok) return [];
    return await res.json();
  } catch (error) {
    // Sahte alias üretilmez: admin'in var olmayan bir eşlemeyi onaylamasına yol açardı.
    console.warn('[API] Pending alias listesi alınamadı:', error);
    return [];
  }
}

/**
 * 7. Kirli Hisse Eşleşmesini Onayla
 */
export async function approveAlias(id: number): Promise<string> {
  const res = await fetch(`${API_BASE_URL}/admin/aliases/${id}/approve`, {
    method: 'POST',
  });
  if (!res.ok) {
    throw new Error('Alias onaylanamadı.');
  }
  return await res.text();
}

/**
 * 8. Manuel KAP Dağılım Raporu PDF Yükleme
 */
export async function uploadPdfReport(
  fundCode: string,
  file: File
): Promise<UploadReportResponse> {
  const formData = new FormData();
  formData.append('file', file);

  const res = await fetch(
    `${API_BASE_URL}/admin/funds/${fundCode.toUpperCase()}/upload-report`,
    {
      method: 'POST',
      body: formData,
    }
  );
  if (!res.ok) {
    const errText = await res.text();
    throw new Error(errText || 'PDF yükleme başarısız oldu.');
  }
  return await res.json();
}

/**
 * 9. Yeni Takip Fonu Ekle & Anında KAP Dağılım Raporunu İndir
 * Şartname T3.1 / T3.2: Fon sisteme tanımlandığı anda KAP taranarak en son portföy dağılım raporu çekilir.
 */
export interface CreateFundResult extends Fund {
  reportDiscovered?: boolean;
  message?: string;
}

export async function createFund(data: {
  code: string;
  title: string;
  manager: string;
  annualFeeRatio: number;
  displayOrder?: number;
  autoDiscover?: boolean;
}): Promise<CreateFundResult> {
  const res = await fetch(`${API_BASE_URL}/admin/funds`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({
      ...data,
      autoDiscover: data.autoDiscover !== undefined ? data.autoDiscover : true,
    }),
  });
  if (!res.ok) {
    const errText = await res.text();
    throw new Error(errText || 'Fon oluşturulamadı.');
  }
  return await res.json();
}

/**
 * 10. Fonun Aktif ve Geçmiş KAP Rapor Dosyaları Dökümünü Getirir
 */
export async function fetchFundReportHistory(fundCode: string): Promise<FundReportHistory | null> {
  const cleanCode = fundCode.trim().toUpperCase();
  try {
    const res = await fetch(`${API_BASE_URL}/admin/funds/${cleanCode}/reports`, {
      cache: 'no-store'
    });
    if (!res.ok) {
      throw new Error(`Rapor geçmişi alınamadı: HTTP ${res.status}`);
    }
    return await res.json();
  } catch (error) {
    console.warn(`[API] '${cleanCode}' için rapor geçmişi çekilemedi:`, error);
    return null;
  }
}

/**
 * 11. Diskteki Örnek/Arşiv Dosyasını Yükle ve Devreye Al
 */
export async function loadDiskReportFile(
  fundCode: string,
  fileName: string
): Promise<UploadReportResponse> {
  const cleanCode = fundCode.trim().toUpperCase();
  const res = await fetch(
    `${API_BASE_URL}/admin/funds/${cleanCode}/load-disk-file?fileName=${encodeURIComponent(fileName)}`,
    {
      method: 'POST'
    }
  );
  if (!res.ok) {
    const err = await res.text();
    throw new Error(err || 'Disk dosyası yüklenemedi.');
  }
  return await res.json();
}


/**
 * 9. KAP Raporu Dry-Run Önizleme (Veritabanına Yazmaz)
 */
export async function previewKapReport(fundCode: string): Promise<PdfPreviewResponse> {
  const cleanCode = fundCode.trim().toUpperCase();
  const res = await fetch(`${API_BASE_URL}/admin/funds/${cleanCode}/preview-kap`, {
    method: 'POST',
  });
  if (!res.ok) {
    const errText = await res.text();
    throw new Error(errText || 'KAP raporu önizlenemedi.');
  }
  return await res.json();
}

/**
 * 10. Manuel PDF Raporu Dry-Run Önizleme (Veritabanına Yazmaz)
 */
export async function previewPdfReport(fundCode: string, file: File): Promise<PdfPreviewResponse> {
  const cleanCode = fundCode.trim().toUpperCase();
  const formData = new FormData();
  formData.append('file', file);

  const res = await fetch(`${API_BASE_URL}/admin/funds/${cleanCode}/preview-pdf`, {
    method: 'POST',
    body: formData,
  });
  if (!res.ok) {
    const errText = await res.text();
    throw new Error(errText || 'PDF raporu önizlenemedi.');
  }
  return await res.json();
}

/**
 * 11. TEFAS Künyesi ve Kurucu Şirket (PYŞ) Otomatik Keşfi
 */
export async function sniffTefasProfile(fundCode: string): Promise<{
  fundCode: string;
  title: string;
  manager: string;
  category: string;
  latestPrice: number | null;
  annualFeeRatio: number | null;
  foundInTefas: boolean;
}> {
  const clean = fundCode.trim().toUpperCase();
  const res = await fetch(`${API_BASE_URL}/admin/funds/lookup/${clean}`, {
    cache: 'no-store',
  });
  if (!res.ok) {
    throw new Error('TEFAS künyesi alınamadı.');
  }
  const raw = await res.json();
  return {
    fundCode: raw.fundCode ?? clean,
    title: raw.title ?? '',
    manager: raw.manager ?? '',
    category: raw.category ?? '',
    latestPrice: raw.latestPrice != null ? Number(raw.latestPrice) : null,
    annualFeeRatio: raw.defaultFeeRatio != null ? Number(raw.defaultFeeRatio) : (raw.annualFeeRatio != null ? Number(raw.annualFeeRatio) : null),
    foundInTefas: Boolean(raw.found ?? raw.foundInTefas ?? false),
  };
}

