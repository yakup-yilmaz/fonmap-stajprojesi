package com.fonmap.application.service.drift;

import com.fonmap.application.service.drift.dto.DriftedHoldingDto;
import com.fonmap.domain.entity.FundSnapshot;
import com.fonmap.domain.entity.Holding;
import com.fonmap.infrastructure.client.dto.MarketPriceDto;
import com.fonmap.infrastructure.repository.FundSnapshotRepository;
import com.fonmap.infrastructure.repository.HoldingRepository;
import com.fonmap.infrastructure.service.PriceService;
import com.fonmap.domain.entity.DailyHoldingDrift;
import com.fonmap.infrastructure.repository.DailyHoldingDriftRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
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
 * WeightDriftService — Ağırlık Kayması (Weight Drift) Hesaplama Servisi
 * ======================================================================
 *
 * FİNANSAL ARKA PLAN VE MATEMATİKSEL TEMEL:
 * ----------------------------------------
 * Yatırım fonları portföy dağılım raporlarını (PDF) ayda sadece 1 kez yayımlar
 * (t_0).
 * Örneğin rapor 31 Ağustos tarihlidir. Ancak biz bugün (t seansı, örn. 23
 * Eylül)
 * canlı getiri tahmini hesaplamak istediğimizde aradan 23 gün geçmiştir.
 *
 * Bu 23 gün boyunca:
 * - THYAO hissesi %30 değer kazanmış olabilir.
 * - ASELS hissesi %10 değer kaybetmiş olabilir.
 *
 * Bir hisse değer kazandıkça, fonun kasasındaki payı kendiliğinden BÜYÜR.
 * Değer kaybeden hissenin payı ise KÜÇÜLÜR.
 * Eğer biz 23 gün önceki raporda yazan eski ağırlıkları (%10, %5) bugünkü
 * seansa
 * doğrudan uygularsak ciddi tahmin sapmaları (tracking error) oluşur.
 *
 * İşte bu servis, dünkü resmi borsa kapanış fiyatlarını (P_i(t-1)) baz alarak
 * portföydeki tüm hisselerin seans açılışındaki GÜNCEL FİİLİ AĞIRLIKLARINI
 * (w_i(t))
 * hesaplar.
 *
 * SPESİFİKASYON FORMÜLÜ (Formül 4.2):
 * -----------------------------------
 * w_i(t_0) * [ P_i(t-1) / P_i(t_0) ]
 * w_i(t) = ------------------------------------
 * Σ_j [ w_j(t_0) * [ P_j(t-1) / P_j(t_0) ] ]
 *
 * Burada:
 * - w_i(t_0) : PDF'ten okunan başlangıç ağırlığı (holding.weightRatio)
 * - P_i(t_0) : Rapor günündeki borsa kapanış fiyatı (holding.reportPrice)
 * - P_i(t-1) : Dün akşamki resmi borsa kapanış fiyatı (PriceService ->
 * previousClose)
 * - G_i : Varlığın fiyat büyüme çarpanı [ P_i(t-1) / P_i(t_0) ]
 *
 * ÇALIŞMA ZAMANI:
 * Her sabah borsa açılmadan önce (09:50) çalıştırılır ve gün içi seans
 * süresince
 * bu taze ağırlıklar getiri hesaplama motoruna (ReturnCalculationService) girdi
 * olur.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WeightDriftService {

    private final HoldingRepository holdingRepository;
    private final FundSnapshotRepository fundSnapshotRepository;
    private final PriceService priceService;

    @Autowired(required = false)
    private DailyHoldingDriftRepository dailyHoldingDriftRepository;

    @Autowired(required = false)
    private com.fonmap.infrastructure.client.HistoricalPriceProvider historicalPriceProvider;

    @Autowired(required = false)
    private com.fonmap.infrastructure.repository.MarketHolidayRepository marketHolidayRepository;

    /**
     * Ara hesaplamalarda yuvarlama hatalarını önlemek için yüksek hassasiyet (10
     * basamak).
     */
    private static final int CALC_SCALE = 10;

    /**
     * Veritabanı ve DTO katmanında saklanacak nihai ağırlık hassasiyeti (6
     * basamak).
     * Örnek: 0.123456 (%12.3456)
     */
    private static final int FINAL_SCALE = 6;

    /**
     * Canlı fiyat servisi üzerinden dünkü kapanış fiyatlarını (P_t-1) çekerek
     * portföy pozisyonlarının güncellenmiş dinamik ağırlıklarını hesaplar.
     *
     * @param holdings Fon portföyündeki varlık satırları listesi
     * @return Her bir hissenin güncel ağırlık ve fiyat çarpanını içeren DTO listesi
     */
    public List<DriftedHoldingDto> calculateDrift(List<Holding> holdings) {
        if (holdings == null || holdings.isEmpty()) {
            log.warn("[WeightDriftService] İşlenecek holding listesi boş.");
            return Collections.emptyList();
        }

        // 1. AŞAMA: Portföydeki tüm enstrümanların borsa sembollerini (ticker) topla
        List<String> tickers = holdings.stream()
                .filter(h -> h.getInstrument() != null && h.getInstrument().getTicker() != null)
                .map(h -> h.getInstrument().getTicker())
                .distinct()
                .collect(Collectors.toList());

        // 2. AŞAMA: PriceService üzerinden tek hamlede (toplu) dünkü kapanış fiyatlarını çek
        Map<String, MarketPriceDto> priceMap = priceService.getPrices(tickers);
        Map<String, BigDecimal> previousCloses = new HashMap<>();
        if (priceMap != null) {
            for (Map.Entry<String, MarketPriceDto> entry : priceMap.entrySet()) {
                if (entry.getValue() != null) {
                    BigDecimal close = entry.getValue().getPreviousClose() != null
                            ? entry.getValue().getPreviousClose()
                            : entry.getValue().getCurrentPrice();
                    if (close != null) {
                        previousCloses.put(entry.getKey().toUpperCase(Locale.ROOT), close);
                    }
                }
            }
        }

        return calculateDriftWithPreviousCloses(holdings, previousCloses);
    }

    /**
     * Belirli bir seans gününe ait dünkü borsa kapanış fiyatları (P_t-1) haritasını alarak
     * SPK Formül 4.2 ile normalize edilmiş dinamik ağırlıkları hesaplar.
     * (Tarihsel telafi ve Backtest simülasyonu için tam deterministik hesaplama)
     *
     * @param holdings Fon portföyündeki varlık satırları listesi
     * @param previousCloses Sembol bazında dünkü kapanış fiyatları (P_t-1)
     * @return Normalize edilmiş ağırlık DTO listesi
     */
    public List<DriftedHoldingDto> calculateDriftWithPreviousCloses(List<Holding> holdings, Map<String, BigDecimal> previousCloses) {
        if (holdings == null || holdings.isEmpty()) {
            return Collections.emptyList();
        }

        List<DriftedHoldingDto> intermediateList = new ArrayList<>(holdings.size());
        BigDecimal totalAdjustedSum = BigDecimal.ZERO;
        BigDecimal totalInitialSum = BigDecimal.ZERO;

        for (Holding holding : holdings) {
            String ticker = holding.getInstrument() != null ? holding.getInstrument().getTicker() : "UNKNOWN";
            String isin = holding.getInstrument() != null ? holding.getInstrument().getIsinCode() : null;
            BigDecimal initialWeight = holding.getWeightRatio() != null ? holding.getWeightRatio() : BigDecimal.ZERO;
            BigDecimal reportPrice = holding.getReportPrice();
            boolean isShort = Boolean.TRUE.equals(holding.getIsShort());

            totalInitialSum = totalInitialSum.add(initialWeight);

            BigDecimal previousClose = (previousCloses != null && ticker != null)
                    ? previousCloses.get(ticker.toUpperCase(Locale.ROOT))
                    : null;

            BigDecimal growthFactor = calculateGrowthFactor(reportPrice, previousClose, isShort);
            BigDecimal unnormalizedWeight = initialWeight.multiply(growthFactor);
            totalAdjustedSum = totalAdjustedSum.add(unnormalizedWeight);

            DriftedHoldingDto dto = DriftedHoldingDto.builder()
                    .holdingId(holding.getId())
                    .ticker(ticker)
                    .isinCode(isin)
                    .initialWeight(initialWeight)
                    .reportPrice(reportPrice)
                    .previousClose(previousClose)
                    .growthFactor(growthFactor.setScale(FINAL_SCALE, RoundingMode.HALF_UP))
                    .effectiveWeight(unnormalizedWeight)
                    .isShort(isShort)
                    .build();

            intermediateList.add(dto);
        }

        // Portföy Büyüme Oranına Göre Güncel Ağırlıkların Hesaplanması (Formül 4.2)
        if (totalAdjustedSum.compareTo(BigDecimal.ZERO) > 0 && totalInitialSum.compareTo(BigDecimal.ZERO) > 0) {
            boolean isStandardFund = totalInitialSum.compareTo(new BigDecimal("0.90")) >= 0
                    && totalInitialSum.compareTo(new BigDecimal("1.10")) <= 0;

            for (DriftedHoldingDto dto : intermediateList) {
                BigDecimal scaledWeight;
                if (isStandardFund) {
                    scaledWeight = dto.getEffectiveWeight()
                            .divide(totalAdjustedSum, FINAL_SCALE, RoundingMode.HALF_UP);
                } else {
                    scaledWeight = dto.getEffectiveWeight()
                            .multiply(totalInitialSum)
                            .divide(totalAdjustedSum, FINAL_SCALE, RoundingMode.HALF_UP);
                }
                dto.setEffectiveWeight(scaledWeight);
            }
        } else {
            log.warn("[WeightDriftService] Toplam düzeltilmiş ağırlık sıfır veya negatif! Başlangıç ağırlıkları korundu.");
            for (DriftedHoldingDto dto : intermediateList) {
                dto.setEffectiveWeight(dto.getInitialWeight().setScale(FINAL_SCALE, RoundingMode.HALF_UP));
            }
        }

        log.debug("[WeightDriftService] Toplam {} pozisyon için ağırlık kayması hesaplandı. Toplam ham portföy çarpanı: {}",
                intermediateList.size(), totalAdjustedSum.setScale(4, RoundingMode.HALF_UP));

        return intermediateList;
    }

    /**
     * CalculationEngine için pratik yardımcı metod:
     * Holding ID'sini doğrudan güncel normalize edilmiş ağırlığa (effectiveWeight)
     * eşleyen bir harita döner.
     *
     * @param holdings Varlık listesi
     * @return Map<HoldingID, EffectiveWeight>
     */
    public Map<UUID, BigDecimal> calculateEffectiveWeightMap(List<Holding> holdings) {
        List<DriftedHoldingDto> driftList = calculateDrift(holdings);
        Map<UUID, BigDecimal> map = new HashMap<>(driftList.size());
        for (DriftedHoldingDto dto : driftList) {
            if (dto.getHoldingId() != null) {
                map.put(dto.getHoldingId(), dto.getEffectiveWeight());
            }
        }
        return map;
    }

    /**
     * Belirli bir snapshot ID'sine ait holdingleri veritabanından tek sorguda çeker
     * ve ağırlık kaymasını hesaplar.
     *
     * @param snapshotId Snapshot benzersiz kimliği
     * @return Ağırlık kayması hesaplanmış DTO listesi
     */
    public List<DriftedHoldingDto> calculateDriftForSnapshot(UUID snapshotId) {
        if (snapshotId == null) {
            throw new IllegalArgumentException("Snapshot ID boş olamaz.");
        }
        List<Holding> holdings = holdingRepository.findBySnapshotIdWithInstrument(snapshotId);
        return calculateDrift(holdings);
    }

    /**
     * Fon koduna göre (Örn: "THF", "TTE") en güncel snapshot'ı bulur ve ağırlık
     * kaymasını hesaplar.
     *
     * @param fundCode Fon borsa kodu
     * @return Ağırlık kayması hesaplanmış DTO listesi
     */
    public List<DriftedHoldingDto> calculateDriftForLatestFundSnapshot(String fundCode) {
        if (fundCode == null || fundCode.isBlank()) {
            throw new IllegalArgumentException("Fon kodu boş olamaz.");
        }
        List<FundSnapshot> snapshots = fundSnapshotRepository.findLatestByFundCode(fundCode.trim().toUpperCase(),
                PageRequest.of(0, 1));
        if (snapshots.isEmpty()) {
            log.warn("[WeightDriftService] Fon '{}' için henüz kayıtlı bir snapshot bulunamadı.", fundCode);
            return Collections.emptyList();
        }
        return calculateDriftForSnapshot(snapshots.get(0).getId());
    }

    /**
     * Belirli bir snapshot için T-1 borsa kapanışlarına göre ağırlık kaymasını
     * hesaplar
     * ve hesaplanan sonuçları daily_holding_drifts tablosuna kalıcı olarak
     * mühürler.
     *
     * @param snapshotId Snapshot benzersiz kimliği
     * @param driftDate  Seans tarihi (Genellikle günün tarihi)
     * @return Kaydedilen güncel ağırlık DTO listesi
     */
    @Transactional
    public List<DriftedHoldingDto> calibrateAndSaveDriftForSnapshot(UUID snapshotId, LocalDate driftDate) {
        if (snapshotId == null) {
            log.error("[WEIGHT_DRIFT] [STATUS=ERROR] [REASON=NULL_SNAPSHOT_ID] Snapshot ID boş olamaz.");
            throw new IllegalArgumentException("Snapshot ID boş olamaz.");
        }
        LocalDate targetDate = (driftDate != null) ? driftDate : LocalDate.now();

        Optional<FundSnapshot> snapshotOpt = fundSnapshotRepository.findById(snapshotId);
        if (snapshotOpt.isEmpty()) {
            log.warn(
                    "[WEIGHT_DRIFT] [STATUS=SKIPPED] [SNAPSHOT_ID={}] Snapshot veritabanında bulunamadı, kalibrasyon atlandı.",
                    snapshotId);
            return Collections.emptyList();
        }
        FundSnapshot snapshot = snapshotOpt.get();

        List<Holding> holdings = holdingRepository.findBySnapshotIdWithInstrument(snapshotId);
        if (holdings.isEmpty()) {
            log.warn(
                    "[WEIGHT_DRIFT] [STATUS=SKIPPED] [SNAPSHOT_ID={}] Snapshot için holding bulunamadı, kalibrasyon atlandı.",
                    snapshotId);
            return Collections.emptyList();
        }

        long startMs = System.currentTimeMillis();
        List<DriftedHoldingDto> driftList = calculateDrift(holdings);

        if (dailyHoldingDriftRepository != null) {
            try {
                // Varsa o güne ait önceki geçici kaydı temizle
                dailyHoldingDriftRepository.deleteBySnapshotIdAndDriftDate(snapshotId, targetDate);

                Map<UUID, Holding> holdingMap = holdings.stream()
                        .filter(h -> h.getId() != null)
                        .collect(Collectors.toMap(Holding::getId, Function.identity()));

                List<DailyHoldingDrift> entities = new ArrayList<>(driftList.size());
                for (DriftedHoldingDto dto : driftList) {
                    Holding holding = holdingMap.get(dto.getHoldingId());
                    if (holding != null) {
                        entities.add(DailyHoldingDrift.builder()
                                .fund(snapshot.getFund())
                                .snapshot(snapshot)
                                .holding(holding)
                                .driftDate(targetDate)
                                .initialWeight(dto.getInitialWeight())
                                .growthFactor(dto.getGrowthFactor())
                                .effectiveWeight(dto.getEffectiveWeight())
                                .previousClose(dto.getPreviousClose())
                                .build());
                    }
                }

                dailyHoldingDriftRepository.saveAll(entities);
                long elapsedMs = System.currentTimeMillis() - startMs;
                log.info(
                        "[WEIGHT_DRIFT] [STATUS=SUCCESS] [EXECUTION=PERSISTED] Fon='{}' | Snapshot={} | Tarih={} | {} adet açılış ağırlığı veritabanına mühürlendi (Süre: {} ms).",
                        snapshot.getFund() != null ? snapshot.getFund().getCode() : "-", snapshotId, targetDate,
                        entities.size(), elapsedMs);
            } catch (Exception e) {
                log.error(
                        "[WEIGHT_DRIFT] [STATUS=ERROR] [EXECUTION=PERSIST_FAILED] Snapshot={} ağırlık kayması veritabanına mühürlenirken hata: {}",
                        snapshotId, e.getMessage(), e);
            }
        }

        return driftList;
    }

    /**
     * En güncel snapshot üzerinden fonun açılış ağırlıklarını hesaplar ve
     * veritabanına mühürler.
     *
     * @param fundCode  Fon borsa kodu (Örn: "THF", "TTE")
     * @param driftDate Seans tarihi
     * @return Kaydedilen DTO listesi
     */
    @Transactional
    public List<DriftedHoldingDto> calibrateAndSaveDriftForLatestFundSnapshot(String fundCode, LocalDate driftDate) {
        if (fundCode == null || fundCode.isBlank()) {
            throw new IllegalArgumentException("Fon kodu boş olamaz.");
        }
        List<FundSnapshot> snapshots = fundSnapshotRepository.findLatestByFundCode(fundCode.trim().toUpperCase(),
                PageRequest.of(0, 1));
        if (snapshots.isEmpty()) {
            log.warn("[WEIGHT_DRIFT] [STATUS=SKIPPED] Fon '{}' için henüz kayıtlı bir snapshot bulunamadı.", fundCode);
            return Collections.emptyList();
        }
        return calibrateAndSaveDriftForSnapshot(snapshots.get(0).getId(), driftDate);
    }

    /**
     * Seans İçi Motor (ReturnCalculationService) için Yüksek Performanslı Ağırlık
     * Getirici:
     * -----------------------------------------------------------------------------------
     * Kılavuz gereği: "Ağırlıklar gün boyunca seans bitene kadar SABİT kalır."
     *
     * 1. Önce veritabanında bugünün tarihine ait dondurulmuş (calibrated)
     * ağırlıklar var mı bakar.
     * 2. Varsa tek sorguda O(1) hızında döner (CPU ve PriceService tasarrufu).
     * 3. Eğer sabah kalibrasyonu kaçmışsa (Graceful Auto-Calibration), anında
     * hesaplar, kaydeder ve döner.
     *
     * @param snapshot  Snapshot nesnesi
     * @param holdings  Portföy holding listesi (varsa doğrudan kullanılır)
     * @param driftDate Tarih (Genellikle bugünün tarihi)
     * @return Map<HoldingId, EffectiveWeight>
     */
    @Transactional
    public Map<UUID, BigDecimal> getOrCalculateEffectiveWeights(FundSnapshot snapshot, List<Holding> holdings,
            LocalDate driftDate) {
        return getOrCalculateEffectiveWeights(snapshot, holdings, driftDate, null);
    }

    @Transactional
    public Map<UUID, BigDecimal> getOrCalculateEffectiveWeights(FundSnapshot snapshot, List<Holding> holdings,
            LocalDate driftDate, Map<String, BigDecimal> precalculatedPreviousCloses) {
        if (snapshot == null) {
            return Collections.emptyMap();
        }
        LocalDate targetDate = (driftDate != null) ? driftDate : LocalDate.now();

        // 1. ADIM: Kalibre edilmiş hazır dondurulmuş ağırlıklara bak
        if (dailyHoldingDriftRepository != null && snapshot.getId() != null) {
            try {
                List<DailyHoldingDrift> cachedDrifts = dailyHoldingDriftRepository
                        .findBySnapshotIdAndDriftDateWithHolding(snapshot.getId(), targetDate);

                if (!cachedDrifts.isEmpty()) {
                    log.debug(
                            "[WEIGHT_DRIFT] [STATUS=CACHE_HIT] Fon='{}' | Snapshot={} | Tarih={} | {} adet kalibre edilmiş ağırlık veritabanından hazır okundu.",
                            snapshot.getFund() != null ? snapshot.getFund().getCode() : "-", snapshot.getId(),
                            targetDate, cachedDrifts.size());

                    Map<UUID, BigDecimal> weightMap = new HashMap<>(cachedDrifts.size());
                    for (DailyHoldingDrift d : cachedDrifts) {
                        weightMap.put(d.getHolding().getId(), d.getEffectiveWeight());
                    }
                    return weightMap;
                }
            } catch (Exception e) {
                log.warn("[WEIGHT_DRIFT] [STATUS=CACHE_WARN] Snapshot={} kalibre edilmiş ağırlık okunurken uyarı: {}",
                        snapshot.getId(), e.getMessage());
            }
        }

        // 2. ADIM: Eğer kalibre edilmiş hazır kayıt yoksa dinamik ağırlık kaymasını hesapla
        List<Holding> activeHoldings = holdings;
        if ((activeHoldings == null || activeHoldings.isEmpty()) && snapshot.getId() != null) {
            activeHoldings = holdingRepository.findBySnapshotIdWithInstrument(snapshot.getId());
        }
        if (activeHoldings == null || activeHoldings.isEmpty()) {
            return Collections.emptyMap();
        }

        List<DriftedHoldingDto> calculatedList;
        if (precalculatedPreviousCloses != null && !precalculatedPreviousCloses.isEmpty()) {
            log.info("[WEIGHT_DRIFT] [STATUS=HISTORICAL_DRIFT] Fon='{}' | Tarih={} | Çözümlenmiş tarihsel kapanışlarla geriye dönük ağırlık kaydırılıyor.",
                    snapshot.getFund() != null ? snapshot.getFund().getCode() : "-", targetDate);
            calculatedList = calculateDriftWithPreviousCloses(activeHoldings, precalculatedPreviousCloses);
        } else if (targetDate.isBefore(LocalDate.now()) && historicalPriceProvider != null) {
            LocalDate previousTradingDate = findPreviousTradingDate(targetDate);
            log.info("[WEIGHT_DRIFT] [STATUS=HISTORICAL_LOOKUP] Fon='{}' | Tarih={} | Önceki seans ({}) kapanışlarıyla geriye dönük ağırlık kaydırılıyor.",
                    snapshot.getFund() != null ? snapshot.getFund().getCode() : "-", targetDate, previousTradingDate);

            List<String> tickers = activeHoldings.stream()
                    .filter(h -> h.getInstrument() != null && h.getInstrument().getTicker() != null)
                    .map(h -> h.getInstrument().getTicker())
                    .distinct()
                    .collect(Collectors.toList());

            Map<String, Map<LocalDate, BigDecimal>> historyMap = historicalPriceProvider.getHistoricalClosesBatch(
                    tickers, previousTradingDate.minusDays(7), previousTradingDate);

            Map<String, BigDecimal> resolvedCloses = new HashMap<>();
            if (historyMap != null) {
                for (String ticker : tickers) {
                    Map<LocalDate, BigDecimal> tickerHist = historyMap.get(ticker.toUpperCase(Locale.ROOT));
                    BigDecimal pPrev = findLatestCloseOnOrBefore(tickerHist, previousTradingDate);
                    if (pPrev != null) {
                        resolvedCloses.put(ticker.toUpperCase(Locale.ROOT), pPrev);
                    }
                }
            }
            calculatedList = calculateDriftWithPreviousCloses(activeHoldings, resolvedCloses);
        } else {
            log.info("[WEIGHT_DRIFT] [STATUS=AUTO_CALIBRATED] Fon='{}' | Snapshot={} | Tarih={} | Canlı seans motoru için anlık hesaplanıyor...",
                    snapshot.getFund() != null ? snapshot.getFund().getCode() : "-", snapshot.getId(), targetDate);
            calculatedList = calculateDrift(activeHoldings);
        }

        // 3. ADIM: Hesaplanan ağırlıkları veritabanına mühürle
        if (dailyHoldingDriftRepository != null && snapshot.getId() != null && snapshot.getFund() != null) {
            try {
                dailyHoldingDriftRepository.deleteBySnapshotIdAndDriftDate(snapshot.getId(), targetDate);

                Map<UUID, Holding> holdingMap = activeHoldings.stream()
                        .filter(h -> h.getId() != null)
                        .collect(Collectors.toMap(Holding::getId, Function.identity()));

                List<DailyHoldingDrift> entities = new ArrayList<>(calculatedList.size());
                for (DriftedHoldingDto dto : calculatedList) {
                    Holding holding = holdingMap.get(dto.getHoldingId());
                    if (holding != null) {
                        entities.add(DailyHoldingDrift.builder()
                                .fund(snapshot.getFund())
                                .snapshot(snapshot)
                                .holding(holding)
                                .driftDate(targetDate)
                                .initialWeight(dto.getInitialWeight())
                                .growthFactor(dto.getGrowthFactor())
                                .effectiveWeight(dto.getEffectiveWeight())
                                .previousClose(dto.getPreviousClose())
                                .build());
                    }
                }
                dailyHoldingDriftRepository.saveAll(entities);
                log.info("[WEIGHT_DRIFT] [STATUS=SUCCESS] [EXECUTION=PERSISTED] Fon='{}' için {} adet ağırlık (Tarih={}) kalıcı mühürlendi.",
                        snapshot.getFund().getCode(), entities.size(), targetDate);
            } catch (Exception e) {
                log.warn("[WEIGHT_DRIFT] [STATUS=WARN] Anlık kalibrasyon veritabanına mühürlenirken uyarı: {}",
                        e.getMessage());
            }
        }

        Map<UUID, BigDecimal> weightMap = new HashMap<>(calculatedList.size());
        for (DriftedHoldingDto dto : calculatedList) {
            if (dto.getHoldingId() != null) {
                weightMap.put(dto.getHoldingId(), dto.getEffectiveWeight());
            }
        }
        return weightMap;
    }

    public Map<UUID, BigDecimal> getOrCalculateEffectiveWeights(FundSnapshot snapshot, LocalDate driftDate) {
        return getOrCalculateEffectiveWeights(snapshot, null, driftDate, null);
    }

    private LocalDate findPreviousTradingDate(LocalDate date) {
        LocalDate prev = date.minusDays(1);
        while (prev.getDayOfWeek() == java.time.DayOfWeek.SATURDAY || prev.getDayOfWeek() == java.time.DayOfWeek.SUNDAY
                || (marketHolidayRepository != null && marketHolidayRepository.existsByHolidayDate(prev))) {
            prev = prev.minusDays(1);
        }
        return prev;
    }

    private BigDecimal findLatestCloseOnOrBefore(Map<LocalDate, BigDecimal> history, LocalDate date) {
        if (history == null || date == null) return null;
        return history.entrySet().stream()
                .filter(e -> !e.getKey().isAfter(date) && e.getValue() != null && e.getValue().compareTo(BigDecimal.ZERO) > 0)
                .max(Map.Entry.comparingByKey())
                .map(Map.Entry::getValue)
                .orElse(null);
    }

    /**
     * Tek bir menkul kıymetin fiyat büyüme katsayısını (G_i) hesaplar.
     *
     * KURALLAR:
     * 1. Eğer rapor fiyatı (P_t0) veya dünkü borsa kapanış fiyatı (P_t-1) yoksa,
     * sıfırsa veya negatifse:
     * Varlıkta fiyat hareketi olmamış varsayılır -> G_i = 1.0 (Repo, mevduat, bono
     * veya yeni hisseler).
     * 2. Standart Pozisyon (Long):
     * G_i = P_t-1 / P_t0
     * Örnek: 100 TL'den 130 TL'ye çıkmışsa G_i = 1.30 (hissenin fon içindeki
     * ağırlığı artar).
     * 3. Açığa Satış / VİOP Kısa Pozisyon (Short):
     * Hisse yükselirse pozisyon zarar eder, dolayısıyla portföydeki göreceli
     * ağırlığı küçülür.
     * G_i = max(0, 1.0 - [(P_t-1 - P_t0) / P_t0]) = max(0, 2.0 - [P_t-1 / P_t0])
     * Örnek: Hisse %30 artmışsa short pozisyon katsayısı 1.0 - 0.30 = 0.70 olur.
     */
    private BigDecimal calculateGrowthFactor(BigDecimal reportPrice, BigDecimal previousClose, boolean isShort) {
        if (reportPrice == null || reportPrice.compareTo(BigDecimal.ZERO) <= 0 ||
                previousClose == null || previousClose.compareTo(BigDecimal.ZERO) <= 0) {
            // Fiyat verisi yoksa veya varlık nakit/repo ise nötr katsayı (1.0) dön
            return BigDecimal.ONE;
        }

        try {
            // Ham fiyat oranı: P_i(t-1) / P_i(t_0)
            BigDecimal priceRatio = previousClose.divide(reportPrice, CALC_SCALE, RoundingMode.HALF_UP);

            if (!isShort) {
                // Standart Long pozisyon: G_i = priceRatio
                return priceRatio;
            } else {
                // Short pozisyon: G_i = 2.0 - priceRatio (0'ın altına düşemez)
                BigDecimal shortRatio = BigDecimal.valueOf(2.0).subtract(priceRatio);
                return shortRatio.compareTo(BigDecimal.ZERO) < 0 ? BigDecimal.ZERO : shortRatio;
            }
        } catch (Exception e) {
            log.warn(
                    "[WeightDriftService] Fiyat büyüme katsayısı hesaplanırken hata oluştu: reportPrice={}, previousClose={}. Nötr (1.0) kabul edildi.",
                    reportPrice, previousClose);
            return BigDecimal.ONE;
        }
    }
}
