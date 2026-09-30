package com.fonmap.application.service.backtest;

import com.fonmap.application.service.backtest.dto.BacktestReportDto;
import com.fonmap.application.service.drift.WeightDriftService;
import com.fonmap.application.service.drift.dto.DriftedHoldingDto;
import com.fonmap.domain.enums.AssetClass;
import com.fonmap.domain.entity.Fund;
import com.fonmap.domain.entity.FundSnapshot;
import com.fonmap.domain.entity.Holding;
import com.fonmap.infrastructure.client.dto.MarketPriceDto;
import com.fonmap.infrastructure.client.tefas.TefasClient;
import com.fonmap.infrastructure.client.tefas.dto.TefasFundDto;
import com.fonmap.infrastructure.repository.FundRepository;
import com.fonmap.infrastructure.repository.FundSnapshotRepository;
import com.fonmap.infrastructure.repository.HoldingRepository;
import com.fonmap.infrastructure.service.PriceService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 60 Günlük Geriye Dönük Doğruluk ve Simülasyon Motoru (Backtest Service).
 * 
 * Kılavuz Referansı: Bölüm 8.4 (BacktestReportDto) ve Bölüm 10.4
 * 
 * Temel Görevleri:
 * 1. Belirlenen tarih aralığındaki (örn: son 60 iş günü) resmi TEFAS fiyatlarını çeker.
 * 2. "Zaman Noktası İlkesi" (Point-in-Time) uygulayarak, her test günü için o tarihte
 *    yürürlükte olan en güncel KAP portföy snapshot'ını seçer (Geleceğe bakma hatasını - Look-Ahead Bias önler).
 * 3. Ağırlık kayması (WeightDriftService) uygulayarak günün seans sonu getirisini tahmin eder.
 * 4. TEFAS resmi kapanışıyla mutabakat yaparak MAE, RMSE, yönsel doğruluk ve başarı oranını hesaplar.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BacktestService {

    private static final BigDecimal ANNUAL_TRADING_DAYS = BigDecimal.valueOf(252);
    private static final BigDecimal SUCCESS_ERROR_THRESHOLD = new BigDecimal("0.0020"); // %0.20 (20 baz puan)
    private static final BigDecimal DEFAULT_REPO_ANNUAL_RATE = new BigDecimal("0.5000"); // Yıllık %50 varsayılan repo faizi
    private static final int FINAL_SCALE = 6;

    private final FundRepository fundRepository;
    private final FundSnapshotRepository fundSnapshotRepository;
    private final HoldingRepository holdingRepository;
    private final TefasClient tefasClient;
    private final PriceService priceService;
    private final WeightDriftService weightDriftService;

    /**
     * Fon için son 60 takvim gününü kapsayan geriye dönük doğruluk simülasyonunu çalıştırır.
     * 
     * @param fundCode Fon borsa kodu (Örn: "THF")
     * @return 60 günlük analitik karne
     */
    @Transactional(readOnly = true)
    public BacktestReportDto runLast60DaysBacktest(String fundCode) {
        LocalDate endDate = LocalDate.now();
        LocalDate startDate = endDate.minusDays(60);
        return runBacktest(fundCode, startDate, endDate, null);
    }

    /**
     * Belirli iki tarih arasındaki tüm iş günleri için geriye dönük simülasyon çalıştırır.
     * 
     * @param fundCode  Fon kodu (Örn: "THF")
     * @param startDate Başlangıç tarihi
     * @param endDate   Bitiş tarihi
     * @return Kapsamlı simülasyon karnesi
     */
    @Transactional(readOnly = true)
    public BacktestReportDto runBacktest(String fundCode, LocalDate startDate, LocalDate endDate) {
        return runBacktest(fundCode, startDate, endDate, null);
    }

    /**
     * Geçmiş simülasyonu çalıştırır. Dışarıdan tarih bazlı hisse fiyat haritası verilebilir
     * (Birim testler ve kontrollü geriye dönük veri akışı için).
     * 
     * @param fundCode                 Fon kodu
     * @param startDate                Başlangıç tarihi
     * @param endDate                  Bitiş tarihi
     * @param historicalPriceOverride  Tarih -> (Ticker -> MarketPriceDto) opsiyonel haritası
     * @return Tam karne
     */
    @Transactional(readOnly = true)
    public BacktestReportDto runBacktest(
            String fundCode,
            LocalDate startDate,
            LocalDate endDate,
            Map<LocalDate, Map<String, MarketPriceDto>> historicalPriceOverride) {

        String normalizedCode = fundCode.trim().toUpperCase();
        log.info("[BacktestService] Geriye dönük test başlatılıyor: Fon='{}', Tarih=[{} - {}]",
                normalizedCode, startDate, endDate);

        // 1. Fon Bilgilerini Doğrula
        Fund fund = fundRepository.findByCode(normalizedCode)
                .orElseThrow(() -> new IllegalArgumentException("Sistemde '" + normalizedCode + "' kodlu fon bulunamadı!"));

        // 2. TEFAS'tan Tarihsel Fon Kapanışlarını Çek
        List<TefasFundDto> tefasHistory = tefasClient.fetchHistoricalPrices(normalizedCode, startDate, endDate);
        if (tefasHistory == null || tefasHistory.size() < 2) {
            log.warn("[BacktestService] TEFAS'tan en az 2 günlük veri alınamadı: Fon='{}'", normalizedCode);
            return buildEmptyReport(fund, startDate, endDate);
        }

        // Tarihe göre sırala (Eskiden yeniye)
        tefasHistory.sort(Comparator.comparing(TefasFundDto::getPriceDate));

        // 3. Günlük Getirileri ve Model Tahminlerini Sırayla Simüle Et
        List<BacktestReportDto.DailyBacktestRecord> dailyRecords = new ArrayList<>();
        BigDecimal sumAbsoluteErrors = BigDecimal.ZERO;
        BigDecimal sumSquaredErrors = BigDecimal.ZERO;
        int directionalMatchCount = 0;
        int perfectDaysCount = 0;
        int acceptableDaysCount = 0;
        int anomalyDaysCount = 0;

        BigDecimal maxError = BigDecimal.ZERO;
        LocalDate maxErrorDate = null;
        BigDecimal minError = null;
        LocalDate minErrorDate = null;

        ToleranceConfig toleranceConfig = null;

        for (int i = 1; i < tefasHistory.size(); i++) {
            TefasFundDto previousTefas = tefasHistory.get(i - 1);
            TefasFundDto currentTefas = tefasHistory.get(i);
            LocalDate testDate = currentTefas.getPriceDate();

            BigDecimal pPrev = previousTefas.getUnitPrice();
            BigDecimal pCurr = currentTefas.getUnitPrice();

            if (pPrev == null || pCurr == null || pPrev.compareTo(BigDecimal.ZERO) <= 0) {
                continue;
            }

            // Resmi TEFAS Getirisi: (P_t / P_t-1) - 1
            BigDecimal actualReturn = pCurr.subtract(pPrev)
                    .divide(pPrev, FINAL_SCALE, RoundingMode.HALF_UP);

            // 4. "Point-in-Time" İlkesi: Test tarihinde yürürlükte olan en güncel snapshot'ı bul
            List<FundSnapshot> snapshots = fundSnapshotRepository.findLatestByFundCodeAndDateBeforeOrEqual(
                    normalizedCode, testDate, PageRequest.of(0, 1));

            if (snapshots.isEmpty()) {
                // Eğer o tarihten önce snapshot yoksa en eski mevcut snapshot'ı dene (Fallback)
                snapshots = fundSnapshotRepository.findLatestByFundCode(normalizedCode, PageRequest.of(0, 1));
                if (snapshots.isEmpty()) {
                    log.warn("[BacktestService] Test günü {} için fon snapshot'ı bulunamadı, gün atlandı.", testDate);
                    continue;
                }
            }

            FundSnapshot activeSnapshot = snapshots.get(0);
            List<Holding> holdings = holdingRepository.findBySnapshotIdWithInstrument(activeSnapshot.getId());
            if (holdings.isEmpty()) {
                continue;
            }

            // Fon tolerans kuralını portföyün varlık dağılımına göre ilk geçerli snapshot'ta belirle
            if (toleranceConfig == null) {
                toleranceConfig = determineTolerance(fund, holdings);
            }

            // 5. O Gün İçin Piyasa Fiyatlarını Belirle
            Map<String, MarketPriceDto> dayPriceMap = null;
            if (historicalPriceOverride != null && historicalPriceOverride.containsKey(testDate)) {
                dayPriceMap = historicalPriceOverride.get(testDate);
            } else {
                List<String> tickers = holdings.stream()
                        .map(h -> h.getInstrument() != null ? h.getInstrument().getTicker() : null)
                        .filter(Objects::nonNull)
                        .distinct()
                        .collect(Collectors.toList());
                dayPriceMap = priceService.getPrices(tickers);
            }

            // 6. Tahmini Getiriyi Hesapla
            SimulatedEstimate estimate = calculateDailyEstimate(fund, holdings, dayPriceMap);

            // 7. Sapma ve Metrikleri Hesapla
            BigDecimal estimatedReturn = estimate.netReturn;
            BigDecimal errorDiff = estimatedReturn.subtract(actualReturn).setScale(FINAL_SCALE, RoundingMode.HALF_UP);
            BigDecimal absoluteError = errorDiff.abs();

            boolean directionMatched = (estimatedReturn.compareTo(BigDecimal.ZERO) >= 0 && actualReturn.compareTo(BigDecimal.ZERO) >= 0)
                    || (estimatedReturn.compareTo(BigDecimal.ZERO) <= 0 && actualReturn.compareTo(BigDecimal.ZERO) <= 0);

            if (directionMatched) {
                directionalMatchCount++;
            }

            // Kademeli Tolerans Değerlendirmesi
            String toleranceStatus;
            if (absoluteError.compareTo(toleranceConfig.getStrictThreshold()) <= 0) {
                perfectDaysCount++;
                toleranceStatus = "KUSURSUZ 🟢";
            } else if (absoluteError.compareTo(toleranceConfig.getNormalThreshold()) <= 0) {
                acceptableDaysCount++;
                toleranceStatus = "TOLERANS_İÇİ 🟡";
            } else {
                anomalyDaysCount++;
                toleranceStatus = "ANOMALİ 🔴";
            }

            sumAbsoluteErrors = sumAbsoluteErrors.add(absoluteError);
            sumSquaredErrors = sumSquaredErrors.add(errorDiff.multiply(errorDiff));

            if (absoluteError.compareTo(maxError) > 0) {
                maxError = absoluteError;
                maxErrorDate = testDate;
            }

            if (minError == null || absoluteError.compareTo(minError) < 0) {
                minError = absoluteError;
                minErrorDate = testDate;
            }

            BacktestReportDto.DailyBacktestRecord record = BacktestReportDto.DailyBacktestRecord.builder()
                    .date(testDate)
                    .snapshotDateUsed(activeSnapshot.getSnapshotDate())
                    .previousTefasPrice(pPrev)
                    .actualTefasPrice(pCurr)
                    .actualReturn(actualReturn)
                    .estimatedReturn(estimatedReturn)
                    .errorDiff(errorDiff)
                    .absoluteError(absoluteError)
                    .directionMatched(directionMatched)
                    .coverageRatio(estimate.coverageRatio)
                    .toleranceStatus(toleranceStatus)
                    .build();

            dailyRecords.add(record);
        }

        if (dailyRecords.isEmpty()) {
            return buildEmptyReport(fund, startDate, endDate);
        }

        if (toleranceConfig == null) {
            toleranceConfig = determineTolerance(fund, Collections.emptyList());
        }

        int totalDays = dailyRecords.size();
        BigDecimal totalDaysBd = BigDecimal.valueOf(totalDays);

        // MAE = Σ |e_i| / N
        BigDecimal mae = sumAbsoluteErrors.divide(totalDaysBd, FINAL_SCALE, RoundingMode.HALF_UP);

        // RMSE = sqrt( Σ e_i^2 / N )
        BigDecimal meanSquaredError = sumSquaredErrors.divide(totalDaysBd, 10, RoundingMode.HALF_UP);
        BigDecimal rmse = BigDecimal.valueOf(Math.sqrt(meanSquaredError.doubleValue()))
                .setScale(FINAL_SCALE, RoundingMode.HALF_UP);

        // Yönsel Doğruluk = Eşleşen Gün / Toplam Gün
        BigDecimal directionalAccuracy = BigDecimal.valueOf(directionalMatchCount)
                .divide(totalDaysBd, 4, RoundingMode.HALF_UP);

        // Genel Model Başarı Oranı = (Kusursuz + Tolerans İçi) / Toplam Gün
        BigDecimal overallSuccessRate = BigDecimal.valueOf(perfectDaysCount + acceptableDaysCount)
                .divide(totalDaysBd, 4, RoundingMode.HALF_UP);

        // Kusursuz Başarı Oranı = Kusursuz Gün Sayısı / Toplam Gün
        BigDecimal strictSuccessRate = BigDecimal.valueOf(perfectDaysCount)
                .divide(totalDaysBd, 4, RoundingMode.HALF_UP);

        log.info("[BacktestService] Test tamamlandı: Fon='{}', Test Günü={}, MAE=%{}, RMSE=%{}, Yönsel Başarı=%{}, Genel Başarı=%{} ({})",
                normalizedCode, totalDays,
                mae.multiply(BigDecimal.valueOf(100)).setScale(4, RoundingMode.HALF_UP),
                rmse.multiply(BigDecimal.valueOf(100)).setScale(4, RoundingMode.HALF_UP),
                directionalAccuracy.multiply(BigDecimal.valueOf(100)).setScale(2, RoundingMode.HALF_UP),
                overallSuccessRate.multiply(BigDecimal.valueOf(100)).setScale(2, RoundingMode.HALF_UP),
                toleranceConfig.getCategory());

        return BacktestReportDto.builder()
                .fundCode(normalizedCode)
                .fundTitle(fund.getTitle())
                .startDate(startDate)
                .endDate(endDate)
                .totalDaysTested(totalDays)
                .meanAbsoluteError(mae)
                .rootMeanSquaredError(rmse)
                .maxError(maxError)
                .maxErrorDate(maxErrorDate)
                .minError(minError != null ? minError : BigDecimal.ZERO)
                .minErrorDate(minErrorDate)
                .directionalAccuracy(directionalAccuracy)
                .successRate(overallSuccessRate)
                .strictSuccessRate(strictSuccessRate)
                .fundCategory(toleranceConfig.getCategory())
                .ruleExplanation(toleranceConfig.getExplanation())
                .appliedNormalThreshold(toleranceConfig.getNormalThreshold())
                .appliedStrictThreshold(toleranceConfig.getStrictThreshold())
                .perfectDaysCount(perfectDaysCount)
                .acceptableDaysCount(acceptableDaysCount)
                .anomalyDaysCount(anomalyDaysCount)
                .dailyRecords(dailyRecords)
                .build();
    }

    /**
     * Tek bir simülasyon günü için net getiri tahminini hesaplar.
     */
    private SimulatedEstimate calculateDailyEstimate(
            Fund fund,
            List<Holding> holdings,
            Map<String, MarketPriceDto> priceMap) {

        // 1. Ağırlık kayması hesapla
        List<DriftedHoldingDto> driftList = weightDriftService.calculateDrift(holdings);
        Map<UUID, DriftedHoldingDto> driftMap = driftList.stream()
                .filter(d -> d.getHoldingId() != null)
                .collect(Collectors.toMap(DriftedHoldingDto::getHoldingId, Function.identity()));

        BigDecimal grossReturnSum = BigDecimal.ZERO;
        BigDecimal coveredWeightSum = BigDecimal.ZERO;
        BigDecimal totalEffectiveWeightSum = BigDecimal.ZERO;

        for (Holding holding : holdings) {
            DriftedHoldingDto driftDto = driftMap.get(holding.getId());
            BigDecimal effectiveWeight = (driftDto != null && driftDto.getEffectiveWeight() != null)
                    ? driftDto.getEffectiveWeight()
                    : (holding.getWeightRatio() != null ? holding.getWeightRatio() : BigDecimal.ZERO);

            totalEffectiveWeightSum = totalEffectiveWeightSum.add(effectiveWeight);

            String ticker = holding.getInstrument() != null ? holding.getInstrument().getTicker() : "UNKNOWN";
            AssetClass assetClass = holding.getInstrument() != null ? holding.getInstrument().getAssetClass() : AssetClass.EQUITY;
            boolean isShort = Boolean.TRUE.equals(holding.getIsShort());

            MarketPriceDto priceDto = priceMap.get(ticker);
            BigDecimal assetReturn = BigDecimal.ZERO;
            boolean isCovered = false;

            if (priceDto != null && priceDto.getDailyChangeRatio() != null) {
                assetReturn = priceDto.getDailyChangeRatio();
                isCovered = true;
            } else if (assetClass == AssetClass.DEPOSIT) {
                BigDecimal annualRate = (priceDto != null && priceDto.getCurrentPrice() != null)
                        ? priceDto.getCurrentPrice()
                        : DEFAULT_REPO_ANNUAL_RATE;
                assetReturn = annualRate.divide(BigDecimal.valueOf(365), FINAL_SCALE, RoundingMode.HALF_UP);
                isCovered = true;
            } else {
                assetReturn = BigDecimal.ZERO;
                isCovered = false;
            }

            if (isCovered) {
                coveredWeightSum = coveredWeightSum.add(effectiveWeight);
            }

            BigDecimal effectiveAssetReturn = isShort ? assetReturn.negate() : assetReturn;
            BigDecimal contribution = effectiveWeight.multiply(effectiveAssetReturn).setScale(FINAL_SCALE, RoundingMode.HALF_UP);
            grossReturnSum = grossReturnSum.add(contribution);
        }

        // Günlük gider düş
        BigDecimal annualFee = fund.getAnnualFeeRatio() != null ? fund.getAnnualFeeRatio() : BigDecimal.ZERO;
        BigDecimal dailyFee = annualFee.divide(ANNUAL_TRADING_DAYS, FINAL_SCALE, RoundingMode.HALF_UP);
        BigDecimal netReturn = grossReturnSum.subtract(dailyFee).setScale(FINAL_SCALE, RoundingMode.HALF_UP);

        BigDecimal coverageRatio = BigDecimal.ONE;
        if (totalEffectiveWeightSum.compareTo(BigDecimal.ZERO) > 0) {
            coverageRatio = coveredWeightSum.divide(totalEffectiveWeightSum, 4, RoundingMode.HALF_UP);
        }

        return new SimulatedEstimate(netReturn, coverageRatio);
    }

    /**
     * Fonun portföy dağılımı ve unvanına göre finansal volatilite ve tolerans kurallarını belirler.
     */
    private ToleranceConfig determineTolerance(Fund fund, List<Holding> sampleHoldings) {
        BigDecimal equityWeight = BigDecimal.ZERO;
        BigDecimal depositWeight = BigDecimal.ZERO;
        BigDecimal bondWeight = BigDecimal.ZERO;
        BigDecimal totalWeight = BigDecimal.ZERO;

        for (Holding h : sampleHoldings) {
            BigDecimal w = h.getWeightRatio() != null ? h.getWeightRatio().abs() : BigDecimal.ZERO;
            totalWeight = totalWeight.add(w);
            AssetClass ac = h.getInstrument() != null ? h.getInstrument().getAssetClass() : AssetClass.EQUITY;
            if (ac == AssetClass.EQUITY) {
                equityWeight = equityWeight.add(w);
            } else if (ac == AssetClass.DEPOSIT) {
                depositWeight = depositWeight.add(w);
            } else if (ac == AssetClass.BOND) {
                bondWeight = bondWeight.add(w);
            }
        }

        double equityRatio = totalWeight.compareTo(BigDecimal.ZERO) > 0
                ? equityWeight.divide(totalWeight, 4, RoundingMode.HALF_UP).doubleValue()
                : 0.0;
        double depositRatio = totalWeight.compareTo(BigDecimal.ZERO) > 0
                ? depositWeight.divide(totalWeight, 4, RoundingMode.HALF_UP).doubleValue()
                : 0.0;
        double bondRatio = totalWeight.compareTo(BigDecimal.ZERO) > 0
                ? bondWeight.divide(totalWeight, 4, RoundingMode.HALF_UP).doubleValue()
                : 0.0;

        String fundTitle = fund.getTitle() != null ? fund.getTitle().toUpperCase(Locale.ROOT) : "";

        if (depositRatio >= 0.50 || fundTitle.contains("PARA PİYASASI") || fundTitle.contains("LİKİT")
                || fundTitle.contains("BEŞİNCİ SERBEST") || "TMV".equalsIgnoreCase(fund.getCode())) {
            return new ToleranceConfig(
                    "PARA PİYASASI / LİKİT",
                    new BigDecimal("0.0005"), // %0.05 Normal Eşik (5 baz puan)
                    new BigDecimal("0.0002"), // %0.02 Kusursuz Eşik (2 baz puan)
                    "Portföyün nakit/repo payı ağırlıklı olduğundan durağan getiri gereği azami %0.05 sapma hedeflenir."
            );
        } else if (equityRatio >= 0.50 || fundTitle.contains("HİSSE") || fundTitle.contains("HISSE") || fundTitle.contains("TEKNOLOJİ")) {
            return new ToleranceConfig(
                    "HİSSE SENEDİ AĞIRLIKLI",
                    new BigDecimal("0.0050"), // %0.50 Normal Eşik (50 baz puan)
                    new BigDecimal("0.0020"), // %0.20 Kusursuz Eşik (20 baz puan)
                    "Portföyün hisse payı >= %50 olduğundan BIST volatilitesi gereği %0.50 sapma normal kabul edilir."
            );
        } else if (bondRatio >= 0.35 || fundTitle.contains("BORÇLANMA") || fundTitle.contains("BONO") || fundTitle.contains("TAHVİL")) {
            return new ToleranceConfig(
                    "BORÇLANMA ARAÇLARI",
                    new BigDecimal("0.0020"), // %0.20 Normal Eşik (20 baz puan)
                    new BigDecimal("0.0010"), // %0.10 Kusursuz Eşik (10 baz puan)
                    "Portföyün bono/tahvil payı yüksek olduğundan faiz/kupon duyarlılığı gereği %0.20 sapma normal kabul edilir."
            );
        } else {
            return new ToleranceConfig(
                    "DEĞİŞKEN / KARMA / FON SEPETİ",
                    new BigDecimal("0.0035"), // %0.35 Normal Eşik (35 baz puan)
                    new BigDecimal("0.0015"), // %0.15 Kusursuz Eşik (15 baz puan)
                    "Dengeli ve çoklu varlık portföy dağılımı nedeniyle %0.35 sapma makul kabul edilir."
            );
        }
    }

    private BacktestReportDto buildEmptyReport(Fund fund, LocalDate start, LocalDate end) {
        return BacktestReportDto.builder()
                .fundCode(fund.getCode())
                .fundTitle(fund.getTitle())
                .startDate(start)
                .endDate(end)
                .totalDaysTested(0)
                .meanAbsoluteError(BigDecimal.ZERO)
                .rootMeanSquaredError(BigDecimal.ZERO)
                .maxError(BigDecimal.ZERO)
                .minError(BigDecimal.ZERO)
                .directionalAccuracy(BigDecimal.ZERO)
                .successRate(BigDecimal.ZERO)
                .strictSuccessRate(BigDecimal.ZERO)
                .fundCategory("BİLİNMİYOR")
                .ruleExplanation("Yeterli veri bulunamadı")
                .appliedNormalThreshold(BigDecimal.ZERO)
                .appliedStrictThreshold(BigDecimal.ZERO)
                .perfectDaysCount(0)
                .acceptableDaysCount(0)
                .anomalyDaysCount(0)
                .dailyRecords(Collections.emptyList())
                .build();
    }

    private static class SimulatedEstimate {
        final BigDecimal netReturn;
        final BigDecimal coverageRatio;

        SimulatedEstimate(BigDecimal netReturn, BigDecimal coverageRatio) {
            this.netReturn = netReturn;
            this.coverageRatio = coverageRatio;
        }
    }

    private static class ToleranceConfig {
        private final String category;
        private final BigDecimal normalThreshold;
        private final BigDecimal strictThreshold;
        private final String explanation;

        ToleranceConfig(String category, BigDecimal normalThreshold, BigDecimal strictThreshold, String explanation) {
            this.category = category;
            this.normalThreshold = normalThreshold;
            this.strictThreshold = strictThreshold;
            this.explanation = explanation;
        }

        public String getCategory() { return category; }
        public BigDecimal getNormalThreshold() { return normalThreshold; }
        public BigDecimal getStrictThreshold() { return strictThreshold; }
        public String getExplanation() { return explanation; }
    }
}
