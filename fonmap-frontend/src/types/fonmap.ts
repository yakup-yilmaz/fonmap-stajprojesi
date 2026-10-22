/**
 * FONMAP — TypeScript Tip ve Arayüz Tanımları (Data Contracts)
 * =============================================================
 * Spring Boot 3 backend REST API DTO'ları ile %100 uyumlu,
 * katı tip-güvenliği (Strict Type Safety) sağlayan veri modelleri.
 */

export type ConfidenceLevel = 'HIGH' | 'MEDIUM' | 'LOW';

export type AssetClass =
  | 'EQUITY'
  | 'VIOP'
  | 'FX'
  | 'COMMODITY'
  | 'BOND'
  | 'DEPOSIT'
  | 'FUND';

/**
 * Fon Ana Bilgi Modeli (Entity: Fund & FundCardResponse)
 * Backend /api/v1/funds uç noktasından dönen zenginleştirilmiş fon ve PDF raporu meta verileri.
 */
export interface Fund {
  id: string;
  code: string;
  title: string;
  manager: string;
  annualFeeRatio: number;
  isActive: boolean;
  displayOrder: number;
  latestSnapshotDate?: string;
  latestStockRatio?: number;
  latestTotalNetAssetValue?: number;
  reportDate?: string;             // KAP Portföy Raporu Dönemi (Örn: 2026-08-31)
  reportDownloadedAt?: string;     // PDF'in KAP'tan indirilip sisteme işlendiği an
  reportSource?: string;           // Rapor kaynağı (KAP Otomasyonu URL veya dosya adı)
  reportSha256?: string;           // Dosyanın 64 karakterli SHA-256 güvenlik parmak izi
  reportStatus?: string;           // PARSED, DOWNLOADED, FAILED
  createdAt?: string;
  updatedAt?: string;
}

/**
 * Tahmine Etki Eden Varlık Katkı Dökümü (Entity: EstimateDetail)
 */
export interface EstimateDetail {
  instrumentTicker: string;
  instrumentTitle: string;
  assetClass: AssetClass;
  effectiveWeight: number; // Örn: 0.1735 (%17.35)
  assetReturn: number; // Örn: 0.0200 (%2.00)
  weightedContribution: number; // Örn: 0.00347 (%0.35)
}

/**
 * Anlık Tahmini Getiri Kart Modeli (Entity: EstimateRun)
 */
export interface EstimateResult {
  fundCode: string;
  fundTitle: string;
  manager: string;
  estimatedReturn: number; // Örn: 0.0161 (%1.61)
  coverageRatio: number; // Örn: 0.9850 (%98.50)
  confidenceLevel: ConfidenceLevel;
  calculatedAt: string;
  snapshotDate?: string;
  totalNetAssetValue?: number;
  stockRatio?: number;
  viopCashRatio?: number;
  currentPrice?: number | null;
  estimatedPrice?: number | null;
  details?: EstimateDetail[];
}

/**
 * Backtest Günlük Karşılaştırma Satırı
 */
export interface DailyBacktestRow {
  date: string;
  previousTefasPrice?: number | null;
  actualTefasPrice?: number | null;
  estimatedTefasPrice?: number | null;
  predictedReturn: number;
  actualReturn: number | null;
  errorDiff: number | null;
  absoluteError: number | null;
  directionMatched: boolean | null;
  isPerfect?: boolean;
  isAcceptable?: boolean;
  status?: string;
  snapshotTransitionDay?: boolean;
  transitionRemark?: string;
}

/**
 * 60 Günlük Ekonometrik Backtest Raporu
 */
export interface RollingWindowSummary {
  windowLabel: string;
  daysCount: number;
  meanAbsoluteError: number;
  rootMeanSquaredError: number;
  directionalAccuracy: number;
  successRate: number;
}

export interface BacktestReport {
  status?: 'SUCCESS' | 'INSUFFICIENT_HISTORY' | 'NO_DATA';
  message?: string;
  minDaysRequired?: number;
  availableSnapshotCount?: number;
  earliestTestableDate?: string;
  latestTestableDate?: string;
  snapshotTransitionLogs?: string[];
  chainStatus?: 'VALID' | 'MISSING_LATEST_SNAPSHOT' | 'INTERMEDIATE_GAP_DETECTED';
  requiresNewerSnapshot?: boolean;
  integrityWarningMessage?: string;
  safeSimulationEndDate?: string;
  oneWeekSummary?: RollingWindowSummary;
  oneMonthSummary?: RollingWindowSummary;
  twoMonthsSummary?: RollingWindowSummary;

  fundCode: string;
  fundTitle: string;
  category: string;
  startDate: string;
  endDate: string;
  totalDays: number;
  certifiedDaysCount?: number;
  missingDaysCount?: number;
  mae: number;
  rmse: number;
  directionalAccuracy: number;
  overallSuccessRate?: number;
  perfectDaysCount?: number;
  acceptableDaysCount?: number;
  anomalyDaysCount?: number;
  dailyComparisons: DailyBacktestRow[];
}

/**
 * Onay Bekleyen Kirli Hisse Eşleme Modeli (Entity: InstrumentAlias)
 */
export interface PendingAlias {
  id: number;
  rawName: string;
  matchedTicker: string;
  matchedTitle: string;
  matchConfidence: number;
  createdAt: string;
}

/**
 * KAP Rapor İçe Aktarma Yanıtı
 */
export interface UploadReportResponse {
  snapshotId: string;
  fundCode: string;
  snapshotDate: string;
  totalNetAssetValue: number;
  stockRatio: number;
  viopCashRatio: number;
  isSuspicious: boolean;
  message: string;
}

/**
 * Fon KAP PDF Rapor Geçmişi Öğesi
 */
export interface FundReportItem {
  snapshotId: string;
  reportId?: string;
  fileName: string;
  reportDate: string;
  downloadedAt?: string;
  source?: string;
  sha256Hash?: string;
  totalNetAssetValue: number;
  stockRatio: number;
  viopCashRatio: number;
  status: string;
  isActive: boolean;
}

export interface DiskReportFile {
  fileName: string;
  sizeInBytes: number;
  sizeInKb: number;
  modifiedDate: string;
  isCurrentlyLoaded: boolean;
}

export interface FundReportHistory {
  fundCode: string;
  fundTitle: string;
  activeReport: FundReportItem | null;
  previousReports: FundReportItem[];
  availableDiskFiles: DiskReportFile[];
}

/**
 * Admin PDF / KAP Dry-Run Önizleme Modeli
 */
export interface ParsedHoldingPreview {
  symbol?: string;
  ticker?: string;
  name?: string;
  securityName?: string;
  weight?: number;
  weightPercentage?: number;
  assetClass?: AssetClass;
  isShort?: boolean;
  nominalShares?: number;
  nominalAmount?: number;
  marketValue?: number;
  totalValue?: number;
}

export interface PdfPreviewResponse {
  fundCode: string;
  fundTitle?: string;
  reportDate: string;
  parserUsed?: string;
  detectedParserStrategy?: string;
  totalHoldingsCount?: number;
  totalItemsCount?: number;
  sanityCheckPassed: boolean;
  sanityCheckMessage?: string;
  totalWeight?: number;
  totalPortfolioWeight?: number;
  stockRatioDeclared?: number;
  viopCashRatioDeclared?: number;
  equityWeight?: number;
  viopWeight?: number;
  depositRepoWeight?: number;
  bondWeight?: number;
  otherWeight?: number;
  cashCollateralRatio?: number;
  assetBreakdown?: {
    equity?: { count: number; weightPercentage: number; description?: string };
    viop?: { count: number; weightPercentage: number; description?: string };
    depositAndRepo?: { count: number; weightPercentage: number; description?: string };
    bond?: { count: number; weightPercentage: number; description?: string };
    other?: { count: number; weightPercentage: number; description?: string };
  };
  holdings: ParsedHoldingPreview[];
}

/**
 * Toplu (Batch) Backtest İcra Sonucu Modelleri
 */
export interface FundBacktestSummary {
  fundCode: string;
  fundTitle: string;
  status: string;
  totalDaysTested: number;
  meanAbsoluteError?: number;
  successRate?: number;
  chainStatus?: string;
  errorMessage?: string;
}

export interface BatchBacktestResult {
  totalFunds: number;
  successfulFunds: number;
  failedFunds: number;
  executionTimeMs: number;
  results: FundBacktestSummary[];
}
