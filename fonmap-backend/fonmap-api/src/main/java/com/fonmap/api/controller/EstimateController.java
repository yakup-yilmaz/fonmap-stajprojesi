package com.fonmap.api.controller;

import com.fonmap.application.service.calculation.ReturnCalculationService;
import com.fonmap.application.service.calculation.dto.FundEstimateDto;
import com.fonmap.domain.entity.EstimateRun;
import com.fonmap.domain.entity.Fund;
import com.fonmap.domain.entity.FundSnapshot;
import com.fonmap.domain.enums.ConfidenceLevel;
import com.fonmap.infrastructure.repository.EstimateRunRepository;
import com.fonmap.infrastructure.repository.FundRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * =========================================================================================
 * ⚡ CANLI TAHMİN VE GETİRİ REST CONTROLLER (EstimateController)
 * =========================================================================================
 *
 * 📌 NE İŞE YARAR? (JUNIOR MÜHENDİS REHBERİ):
 * Bu Controller, Fonmap platformunun "Canlı Finansal Nabzı"dır.
 * Borsa seansı boyunca üretilen dakikalık tahmini net getirileri (%+2.45),
 * modelimizin kuruşu kuruşuna hesapladığı anlık TL birim pay fiyatını (Örn: 3.4123 TL),
 * güvenilirlik seviyesini (HIGH/MEDIUM) ve getiriye en çok omuz veren / aşağı çeken
 * "Günün Liderleri ve Baskılayanları" dökümünü dış dünyaya sunar.
 *
 * 🏗️ MİMARİDEKİ ROLÜ:
 * 1. GET /api/v1/estimates/latest:
 *    Dashboard ekranı 60 saniyede bir bu uca istek atarak tüm fon kartlarındaki
 *    tahmini TL fiyatlarını ve yeşil/kırmızı yüzde oranlarını anında günceller.
 * 2. GET /api/v1/estimates/{fundCode}:
 *    Kullanıcı fon detayına girdiğinde, hisselerin bireysel katkılarını sıralar;
 *    ilk 5 "Günün Lideri" ve son 5 "Günün Baskılayanı" listesini üretir.
 * 3. GET /api/v1/estimates/{fundCode}/history:
 *    TradingView tarzı gün içi dakikalık tahmin çizgisi (grafik) için zaman serisi döner.
 */
@RestController
@RequestMapping("/api/v1/estimates")
@Slf4j
@CrossOrigin(origins = "*") // Frontend Next.js için CORS erişim izni
@Tag(name = "2. Canlı Tahminler (Estimate Controller)", description = "Anlık tahmini TL fiyatı, getiri yüzdesi ve günün lider/baskılayan hisseleri")
@RequiredArgsConstructor
public class EstimateController {

    private final FundRepository fundRepository;
    private final EstimateRunRepository estimateRunRepository;
    private final ReturnCalculationService returnCalculationService;
    private final com.fonmap.infrastructure.repository.FundSnapshotRepository fundSnapshotRepository;
    private final com.fonmap.infrastructure.repository.EstimateDetailRepository estimateDetailRepository;
    private final com.fonmap.infrastructure.client.tefas.TefasClient tefasClient;

    // Yüksek performanslı in-memory önbellek (30 saniye TTL - 1 ms hızlı yanıt)
    private volatile List<LiveFundCardResponse> cachedLatestResponses = null;
    private volatile long lastCalculationTimestamp = 0;
    private static final long CACHE_TTL_MS = 30_000;

    private record CachedFundDetail(FundEstimateDetailResponse response, long timestamp) {}
    private final java.util.Map<String, CachedFundDetail> cachedDetails = new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * 1. UÇ NOKTA: Tüm Aktif Fonların Canlı Tahmin Kartları
     * -------------------------------------------------------------------------------------
     * Dashboard ana sayfasında 60 saniyelik periyotlarla çağrılır.
     * Her fon için TEFAS dünkü resmi fiyatı, bizim hesapladığımız anlık TL fiyatı
     * ve net getiri yüzdesini topluca döner.
     *
     * @return Tüm aktif fonların canlı tahmin kart listesi
     */
    @GetMapping({"", "/latest"})
    @Operation(
            summary = "Tüm Fonların Güncel Tahminleri",
            description = "Aktif fonların en son hesaplanan tahmini TL birim fiyatlarını, yüzde getirilerini ve güven skorlarını döner."
    )
    public ResponseEntity<List<LiveFundCardResponse>> getLatestEstimates(
            @Parameter(description = "Önbelleği atlayıp taze hesaplama yapmaya zorla")
            @RequestParam(required = false, defaultValue = "false") boolean force) {

        long now = System.currentTimeMillis();
        if (!force && cachedLatestResponses != null && (now - lastCalculationTimestamp < CACHE_TTL_MS)) {
            return ResponseEntity.ok(cachedLatestResponses);
        }

        log.info("[EstimateController] GET /api/v1/estimates/latest isteği işleniyor (force={}).", force);

        List<Fund> activeFunds = fundRepository.findAllActiveFundsOrdered();
        List<LiveFundCardResponse> responses = new ArrayList<>(activeFunds.size());

        for (Fund fund : activeFunds) {
            List<FundSnapshot> snapshots = fundSnapshotRepository.findLatestByFundCode(
                    fund.getCode(), PageRequest.of(0, 1));
            LocalDate snapDate = snapshots.isEmpty() ? null : snapshots.get(0).getSnapshotDate();
            BigDecimal stockRatio = snapshots.isEmpty() ? null : snapshots.get(0).getStockRatio();
            BigDecimal tnv = snapshots.isEmpty() ? null : snapshots.get(0).getTotalNetAssetValue();

            if (force) {
                try {
                    // Kullanıcı zorla yenileme istediğinde motoru çalıştır
                    FundEstimateDto estimate = returnCalculationService.calculateAndSave(fund.getCode());
                    responses.add(new LiveFundCardResponse(
                            fund.getId(),
                            fund.getCode(),
                            fund.getTitle(),
                            fund.getManager(),
                            estimate.getCurrentPrice(),
                            estimate.getEstimatedPrice(),
                            estimate.getEstimatedReturn(),
                            estimate.getCoverageRatio(),
                            estimate.getConfidenceLevel(),
                            estimate.getCalculatedAt(),
                            snapDate,
                            stockRatio,
                            tnv
                    ));
                    continue;
                } catch (Exception e) {
                    log.warn("[EstimateController] Fon '{}' force hesaplama hatası: {}", fund.getCode(), e.getMessage());
                }
            }

            // Normal istek: Veritabanındaki en güncel tamamlanmış kaydı oku (2-3 ms hızında)
            List<EstimateRun> latestRuns = estimateRunRepository.findLatestByFundCode(
                    fund.getCode(), PageRequest.of(0, 1));

            if (!latestRuns.isEmpty()) {
                EstimateRun last = latestRuns.get(0);
                BigDecimal curPrice = null;
                BigDecimal estPrice = null;
                try {
                    var tefasOpt = tefasClient.fetchLatestFundPrice(fund.getCode());
                    if (tefasOpt.isPresent() && tefasOpt.get().getUnitPrice() != null) {
                        curPrice = tefasOpt.get().getUnitPrice();
                        if (last.getEstimatedReturn() != null) {
                            estPrice = curPrice.multiply(BigDecimal.ONE.add(last.getEstimatedReturn()))
                                    .setScale(6, RoundingMode.HALF_UP);
                        }
                    }
                } catch (Exception ignored) {}

                responses.add(new LiveFundCardResponse(
                        fund.getId(),
                        fund.getCode(),
                        fund.getTitle(),
                        fund.getManager(),
                        curPrice,
                        estPrice,
                        last.getEstimatedReturn(),
                        last.getCoverageRatio(),
                        last.getConfidenceLevel(),
                        last.getCalculatedAt(),
                        snapDate,
                        stockRatio,
                        tnv
                ));
            } else {
                // Veritabanında kayıt yoksa hesapla
                try {
                    FundEstimateDto estimate = returnCalculationService.calculateAndSave(fund.getCode());
                    responses.add(new LiveFundCardResponse(
                            fund.getId(),
                            fund.getCode(),
                            fund.getTitle(),
                            fund.getManager(),
                            estimate.getCurrentPrice(),
                            estimate.getEstimatedPrice(),
                            estimate.getEstimatedReturn(),
                            estimate.getCoverageRatio(),
                            estimate.getConfidenceLevel(),
                            estimate.getCalculatedAt(),
                            snapDate,
                            stockRatio,
                            tnv
                    ));
                } catch (Exception ex) {
                    log.warn("[EstimateController] İlk hesaplama yapılamadı: {}", ex.getMessage());
                }
            }
        }

        if (!responses.isEmpty()) {
            cachedLatestResponses = responses;
            lastCalculationTimestamp = System.currentTimeMillis();
        }

        return ResponseEntity.ok(responses);
    }

    /**
     * 2. UÇ NOKTA: Tek Bir Fonun Detaylı Canlı Tahmini ve Lider/Baskılayanlar Listesi
     * -------------------------------------------------------------------------------------
     * Kullanıcı fon detay sayfasına girdiğinde çalışır.
     * Getiriye en çok omuz veren 5 hisse (Liderler) ve en çok aşağı çeken 5 hisseyi (Baskılayanlar)
     * katkı paylarıyla birlikte döner.
     *
     * @param fundCode 3 harfli fon kodu (Örn: "THF", "TTE")
     * @return Fonun detaylı tahmini, liderler ve baskılayanlar listesi
     */
    @GetMapping("/{fundCode}")
    @Operation(
            summary = "Fonun Detaylı Tahmini ve Lider/Baskılayan Hisseleri",
            description = "Fonun canlı TL fiyatı ve getirisinin yanı sıra fona en çok kazandıran ilk 5 (Liderler) ve en çok düşüren 5 (Baskılayanlar) hisseyi döner."
    )
    public ResponseEntity<FundEstimateDetailResponse> getEstimateForFund(
            @Parameter(description = "Resmi fon kodu (Örn: THF, TTE)", example = "THF")
            @PathVariable String fundCode,
            @Parameter(description = "Önbelleği atlayıp taze hesaplama yapmaya zorla")
            @RequestParam(required = false, defaultValue = "false") boolean force) {

        String normalizedCode = fundCode.trim().toUpperCase();

        long now = System.currentTimeMillis();
        CachedFundDetail cached = cachedDetails.get(normalizedCode);
        if (!force && cached != null && (now - cached.timestamp() < CACHE_TTL_MS)) {
            return ResponseEntity.ok(cached.response());
        }

        log.info("[EstimateController] GET /api/v1/estimates/{} detayı hesaplanıyor (force={}).", normalizedCode, force);

        // 1. ADIM: Fonun varlığını doğrula
        Fund fund = fundRepository.findByCode(normalizedCode)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "Sistemde '" + normalizedCode + "' kodlu bir fon bulunamadı!"));

        // En son snapshot verilerini al
        List<FundSnapshot> snapshots = fundSnapshotRepository.findLatestByFundCode(
                normalizedCode, PageRequest.of(0, 1));
        LocalDate snapshotDate = snapshots.isEmpty() ? null : snapshots.get(0).getSnapshotDate();
        BigDecimal stockRatio = snapshots.isEmpty() ? null : snapshots.get(0).getStockRatio();
        BigDecimal viopCashRatio = snapshots.isEmpty() ? null : snapshots.get(0).getViopCashRatio();
        BigDecimal totalNetAssetValue = snapshots.isEmpty() ? null : snapshots.get(0).getTotalNetAssetValue();

        // 2. ADIM: HIZLI YOL (Instant Fast-Path): force=false ise veritabanındaki en son hazır tahmin ve detaylarını oku (2-5 ms)
        if (!force) {
            List<EstimateRun> latestRuns = estimateRunRepository.findLatestByFundCode(
                    normalizedCode, PageRequest.of(0, 1));
            if (!latestRuns.isEmpty()) {
                EstimateRun run = latestRuns.get(0);
                List<com.fonmap.domain.entity.EstimateDetail> details =
                        estimateDetailRepository.findByEstimateRunIdWithInstrument(run.getId());

                if (!details.isEmpty()) {
                    BigDecimal curPrice = null;
                    BigDecimal estPrice = null;
                    try {
                        var tefasOpt = tefasClient.fetchLatestFundPrice(normalizedCode);
                        if (tefasOpt.isPresent() && tefasOpt.get().getUnitPrice() != null) {
                            curPrice = tefasOpt.get().getUnitPrice();
                            if (run.getEstimatedReturn() != null) {
                                estPrice = curPrice.multiply(BigDecimal.ONE.add(run.getEstimatedReturn()))
                                        .setScale(6, RoundingMode.HALF_UP);
                            }
                        }
                    } catch (Exception ignored) {}

                    BigDecimal annualFee = fund.getAnnualFeeRatio() != null ? fund.getAnnualFeeRatio() : BigDecimal.ZERO;
                    BigDecimal dailyFee = annualFee.divide(BigDecimal.valueOf(252), 6, RoundingMode.HALF_UP);
                    BigDecimal grossReturn = run.getEstimatedReturn().add(dailyFee);

                    List<ContributorItem> allHoldings = details.stream()
                            .map(d -> new ContributorItem(
                                    d.getInstrument() != null ? d.getInstrument().getTicker() : "UNKNOWN",
                                    d.getInstrument() != null ? d.getInstrument().getTitle() : "Bilinmeyen Varlık",
                                    d.getEffectiveWeight(),
                                    d.getAssetReturn(),
                                    d.getWeightedContribution(),
                                    false
                            ))
                            .toList();

                    List<ContributorItem> leaders = allHoldings.stream()
                            .sorted(Comparator.comparing(ContributorItem::weightedContribution, Comparator.reverseOrder()))
                            .limit(5)
                            .toList();

                    List<ContributorItem> laggards = allHoldings.stream()
                            .sorted(Comparator.comparing(ContributorItem::weightedContribution))
                            .limit(5)
                            .toList();

                    FundEstimateDetailResponse fastResponse = new FundEstimateDetailResponse(
                            run.getId(),
                            normalizedCode,
                            fund.getTitle(),
                            fund.getManager(),
                            curPrice,
                            estPrice,
                            run.getEstimatedReturn(),
                            grossReturn,
                            dailyFee,
                            run.getCoverageRatio(),
                            run.getConfidenceLevel(),
                            run.getCalculatedAt(),
                            snapshotDate,
                            stockRatio,
                            viopCashRatio,
                            totalNetAssetValue,
                            leaders,
                            laggards,
                            allHoldings
                    );

                    cachedDetails.put(normalizedCode, new CachedFundDetail(fastResponse, now));
                    return ResponseEntity.ok(fastResponse);
                }
            }
        }

        // 3. ADIM: Canlı getiri ve hisse katkı motorunu çalıştır (Cold start veya force=true)
        FundEstimateDto estimate = returnCalculationService.calculateAndSave(normalizedCode);

        // 3. ADIM: Hisseleri getiri katkısına göre (weightedContribution) sırala
        List<FundEstimateDto.EstimateDetailItem> allItems = estimate.getDetails() != null
                ? new ArrayList<>(estimate.getDetails())
                : new ArrayList<>();

        allItems.sort(Comparator.comparing(
                item -> item.getWeightedContribution() != null ? item.getWeightedContribution() : BigDecimal.ZERO,
                Comparator.reverseOrder()));

        // 4. ADIM: İlk 5 Lider (Top Gainers) ve Son 5 Baskılayan (Top Losers)
        List<ContributorItem> leaders = allItems.stream()
                .limit(5)
                .map(this::mapToContributor)
                .toList();

        List<ContributorItem> laggards = allItems.stream()
                .sorted(Comparator.comparing(item -> item.getWeightedContribution() != null ? item.getWeightedContribution() : BigDecimal.ZERO))
                .limit(5)
                .map(this::mapToContributor)
                .toList();

        // 5. ADIM: Tüm alt varlıkların tam dökümü
        List<ContributorItem> allContributors = allItems.stream()
                .map(this::mapToContributor)
                .toList();

        FundEstimateDetailResponse response = new FundEstimateDetailResponse(
                estimate.getEstimateRunId(),
                estimate.getFundCode(),
                estimate.getFundTitle(),
                fund.getManager(),
                estimate.getCurrentPrice(),
                estimate.getEstimatedPrice(),
                estimate.getEstimatedReturn(),
                estimate.getGrossReturn(),
                estimate.getDailyFee(),
                estimate.getCoverageRatio(),
                estimate.getConfidenceLevel(),
                estimate.getCalculatedAt(),
                snapshotDate,
                stockRatio,
                viopCashRatio,
                totalNetAssetValue,
                leaders,
                laggards,
                allContributors
        );

        cachedDetails.put(normalizedCode, new CachedFundDetail(response, now));

        return ResponseEntity.ok(response);
    }

    /**
     * 3. UÇ NOKTA: Gün İçi Dakikalık Tahmin Çizgisi (Grafik Zaman Serisi)
     * -------------------------------------------------------------------------------------
     * Detay ekranında gün içi seans grafiğini çizmek için bugünkü tüm tahmin noktalarını döner.
     *
     * @param fundCode Fon kodu
     * @return Dakikalık getiri zaman serisi listesi
     */
    @GetMapping("/{fundCode}/history")
    @Operation(
            summary = "Gün İçi Tahmin Geçmişi (Grafik Serisi)",
            description = "Seans boyunca (10:00 - 18:00) üretilen dakikalık tahmin geçmişini çizgi grafik için döner."
    )
    public ResponseEntity<List<EstimateHistoryPoint>> getIntradayHistory(
            @PathVariable String fundCode) {

        String normalizedCode = fundCode.trim().toUpperCase();
        Fund fund = fundRepository.findByCode(normalizedCode)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "Fon bulunamadı: " + normalizedCode));

        LocalDateTime startOfDay = LocalDate.now().atStartOfDay();
        LocalDateTime endOfDay = LocalDate.now().atTime(LocalTime.MAX);

        List<EstimateRun> history = estimateRunRepository.findEstimateHistory(fund.getId(), startOfDay, endOfDay);

        List<EstimateHistoryPoint> points = history.stream()
                .map(run -> new EstimateHistoryPoint(
                        run.getCalculatedAt(),
                        run.getEstimatedReturn(),
                        run.getCoverageRatio()
                ))
                .toList();

        return ResponseEntity.ok(points);
    }

    private ContributorItem mapToContributor(FundEstimateDto.EstimateDetailItem item) {
        return new ContributorItem(
                item.getTicker(),
                item.getTitle(),
                item.getEffectiveWeight(),
                item.getAssetReturn(),
                item.getWeightedContribution(),
                item.isShort()
        );
    }

    // =========================================================================================
    // 📦 DTO (RECORD) MODELLERİ
    // =========================================================================================

    /**
     * Dashboard Ana Ekran Kartı Tahmin DTO'su.
     */
    public record LiveFundCardResponse(
            UUID fundId,
            String fundCode,
            String fundTitle,
            String manager,
            BigDecimal officialPrice,       // Dünkü Resmi Kapanış (TEFAS TL)
            BigDecimal estimatedPrice,      // Bugün Bizim Modelin Tahmini (TL)
            BigDecimal estimatedReturn,     // Net Tahmini Getiri (%)
            BigDecimal coverageRatio,       // Kapsama Oranı
            ConfidenceLevel confidenceLevel,// HIGH, MEDIUM, LOW
            LocalDateTime calculatedAt,     // Son Güncelleme Zamanı
            LocalDate snapshotDate,
            BigDecimal stockRatio,
            BigDecimal totalNetAssetValue
    ) {}

    /**
     * Detay Ekranı Kapsamlı Tahmin ve Katkı DTO'su.
     */
    public record FundEstimateDetailResponse(
            UUID estimateRunId,
            String fundCode,
            String fundTitle,
            String manager,
            BigDecimal currentPrice,
            BigDecimal estimatedPrice,
            BigDecimal estimatedReturn,
            BigDecimal grossReturn,
            BigDecimal dailyFee,
            BigDecimal coverageRatio,
            ConfidenceLevel confidenceLevel,
            LocalDateTime calculatedAt,
            LocalDate snapshotDate,
            BigDecimal stockRatio,
            BigDecimal viopCashRatio,
            BigDecimal totalNetAssetValue,
            List<ContributorItem> leaders,    // Günün Liderleri (İlk 5)
            List<ContributorItem> laggards,   // Günün Baskılayanları (İlk 5)
            List<ContributorItem> allHoldings // Tüm Portföy
    ) {}

    /**
     * Tekil Varlık/Hisse Katkı DTO'su.
     */
    public record ContributorItem(
            String ticker,
            String title,
            BigDecimal effectiveWeight,
            BigDecimal assetReturn,
            BigDecimal weightedContribution,
            boolean isShort
    ) {}

    /**
     * Zamanlayıcı (LiveEstimateScheduler) her dakika hesaplama döngüsünü bitirdiğinde
     * veya yeni bir KAP/fiyat verisi geldiğinde önbelleği anında temizler.
     */
    public void invalidateCache() {
        this.cachedLatestResponses = null;
        this.lastCalculationTimestamp = 0L;
        this.cachedDetails.clear();
    }

    /**
     * Grafik İçin Zaman Noktası DTO'su.
     */
    public record EstimateHistoryPoint(
            LocalDateTime timestamp,
            BigDecimal estimatedReturn,
            BigDecimal coverageRatio
    ) {}
}
