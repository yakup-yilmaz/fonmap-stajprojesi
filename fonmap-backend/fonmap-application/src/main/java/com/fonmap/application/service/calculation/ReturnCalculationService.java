package com.fonmap.application.service.calculation;

import com.fonmap.application.service.calculation.dto.FundEstimateDto;
import com.fonmap.application.service.drift.WeightDriftService;
import com.fonmap.application.service.drift.dto.DriftedHoldingDto;
import com.fonmap.domain.entity.EstimateDetail;
import com.fonmap.domain.entity.EstimateRun;
import com.fonmap.domain.entity.Fund;
import com.fonmap.domain.entity.FundSnapshot;
import com.fonmap.domain.entity.Holding;
import com.fonmap.domain.enums.AssetClass;
import com.fonmap.domain.enums.ConfidenceLevel;
import com.fonmap.infrastructure.client.dto.MarketPriceDto;
import com.fonmap.infrastructure.repository.EstimateDetailRepository;
import com.fonmap.infrastructure.repository.EstimateRunRepository;
import com.fonmap.infrastructure.repository.FundRepository;
import com.fonmap.infrastructure.repository.FundSnapshotRepository;
import com.fonmap.infrastructure.repository.HoldingRepository;
import com.fonmap.infrastructure.service.PriceService;
import com.fonmap.infrastructure.client.tefas.TefasClient;
import com.fonmap.infrastructure.client.tefas.dto.TefasFundDto;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * ReturnCalculationService — Seans İçi Canlı Getiri Hesaplama Motoru
 * ====================================================================
 *
 * SİSTEMDEKİ ROLÜ (CALCULATION ENGINE):
 * Fonmap platformunun can damarıdır! Seans saatleri boyunca (10:00 - 18:10)
 * periyodik olarak çalışır ve takip edilen tüm fonlar için dakikalık tahmini net getiriyi üretir.
 *
 * ÇALIŞMA ZİNCİRİ:
 * 1. Fonun en güncel portföy dağılımını (FundSnapshot & Holdings) alır.
 * 2. WeightDriftService'ten hisselerin seans açılışındaki güncel dinamik ağırlıklarını (effectiveWeight) alır.
 * 3. PriceService üzerinden tüm hisselerin o anki canlı piyasa getirilerini (r_i(t)) topluca çeker.
 * 4. VİOP Kısa Pozisyon (Short) ve Ters Repo mantıklarını işleterek her varlığın net katkısını hesaplar.
 * 5. Bireysel katkıları toplayarak fonun Brüt Getirisini bulur.
 * 6. Fon seviyesinde günlük yönetim gider payını (annualFeeRatio / 252) düşerek NET TAHMİNİ GETİRİYE ulaşır.
 * 7. Portföy kapsama oranına (coverageRatio) göre güven seviyesini (HIGH, MEDIUM, LOW) belirler.
 * 8. Sonucu atomik olarak estimate_runs ve estimate_details tablolarına kalıcı olarak kaydeder.
 * 9. Dünkü TEFAS resmi birim fiyatı üzerinden anlık TAHMİNİ BİRİM FİYATI (TL) hesaplar.
 *
 * SPESİFİKASYON FORMÜLÜ (Formül 4.1):
 * -----------------------------------
 * Net Tahmini Getiri = Σ [ w_i(t) * r_i(t) ] + VİOP_Katkı + Repo_Faiz - (annual_fee_ratio / 252)
 * Tahmini Fiyat (TL) = TEFAS_Resmi_Fiyat * (1 + Net_Tahmini_Getiri)
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReturnCalculationService {

    private final FundRepository fundRepository;
    private final FundSnapshotRepository fundSnapshotRepository;
    private final HoldingRepository holdingRepository;
    private final EstimateRunRepository estimateRunRepository;
    private final EstimateDetailRepository estimateDetailRepository;
    private final PriceService priceService;
    private final WeightDriftService weightDriftService;
    private final TefasClient tefasClient;

    /**
     * Yıldaki ortalama borsa iş günü sayısı (SPK standart katsayısı).
     * Yıllık yönetim giderini günlük gidere çevirmek için kullanılır.
     */
    private static final BigDecimal ANNUAL_TRADING_DAYS = BigDecimal.valueOf(252);

    /**
     * Varsayılan gecelik ters repo / Takasbank para piyasası yıllık referans faiz oranı (%50.0).
     * Günlük getiri: %50 / 365 = ~+%0.1370
     */
    private static final BigDecimal DEFAULT_REPO_ANNUAL_RATE = new BigDecimal("0.50");

    /**
     * Güven seviyesi eşikleri (Confidence Level Thresholds)
     */
    private static final BigDecimal HIGH_CONFIDENCE_THRESHOLD = new BigDecimal("0.8500");
    private static final BigDecimal MEDIUM_CONFIDENCE_THRESHOLD = new BigDecimal("0.7000");

    /**
     * Fon koduna göre (Örn: "THF", "TLY", "TTE") anlık tahmini getiriyi hesaplar,
     * veritabanına kaydeder ve detaylı DTO sonucunu döner.
     *
     * @param fundCode Fonun resmi borsa kodu
     * @return Hesaplanmış ve kaydedilmiş tahmin DTO'su
     */
    @Transactional
    public FundEstimateDto calculateAndSave(String fundCode) {
        if (fundCode == null || fundCode.isBlank()) {
            throw new IllegalArgumentException("Fon kodu boş olamaz.");
        }

        String cleanCode = fundCode.trim().toUpperCase();

        // 1. Fonun sistemde var olduğunu doğrula
        Fund fund = fundRepository.findByCode(cleanCode)
                .orElseThrow(() -> new IllegalArgumentException("Fon veritabanında bulunamadı: " + cleanCode));

        // 2. Fonun en güncel portföy snapshot'ını çek
        List<FundSnapshot> snapshots = fundSnapshotRepository.findLatestByFundCode(cleanCode, PageRequest.of(0, 1));
        if (snapshots.isEmpty()) {
            throw new IllegalStateException("Fon '" + cleanCode + "' için henüz bir portföy snapshot kaydı bulunmuyor.");
        }

        FundSnapshot latestSnapshot = snapshots.get(0);
        return calculateAndSave(latestSnapshot);
    }

    /**
     * Belirli bir portföy snapshot'ı üzerinden hesaplamayı çalıştırır ve veritabanına kaydeder.
     *
     * @param snapshot Hesaplanacak portföy snapshot'ı
     * @return Nihai tahmin DTO'su
     */
    @Transactional
    public FundEstimateDto calculateAndSave(FundSnapshot snapshot) {
        if (snapshot == null) {
            throw new IllegalArgumentException("Snapshot boş olamaz.");
        }

        Fund fund = snapshot.getFund();
        UUID snapshotId = snapshot.getId();

        // 1. AŞAMA: Portföydeki tüm pozisyonları (Holdings) enstrümanlarıyla birlikte tek sorguda çek
        List<Holding> holdings = holdingRepository.findBySnapshotIdWithInstrument(snapshotId);
        if (holdings.isEmpty()) {
            log.warn("[ReturnCalculationService] Snapshot {} için hiçbir holding kaydı bulunamadı.", snapshotId);
            return buildEmptyEstimateDto(fund, snapshot);
        }

        // 2. AŞAMA: WeightDriftService ile seans öncesi güncel fiili ağırlıkları (effectiveWeight) hesapla
        List<DriftedHoldingDto> driftList = weightDriftService.calculateDrift(holdings);
        Map<UUID, DriftedHoldingDto> driftMap = driftList.stream()
                .filter(d -> d.getHoldingId() != null)
                .collect(Collectors.toMap(DriftedHoldingDto::getHoldingId, Function.identity()));

        // 3. AŞAMA: Tüm hisselerin canlı seans fiyatlarını (r_i(t)) PriceService üzerinden topluca çek
        List<String> tickers = driftList.stream()
                .map(DriftedHoldingDto::getTicker)
                .filter(Objects::nonNull)
                .distinct()
                .collect(Collectors.toList());

        Map<String, MarketPriceDto> livePriceMap = priceService.getPrices(tickers);

        // 4. AŞAMA: Bireysel katkıları (weightedContribution) hesapla ve topla
        BigDecimal grossReturnSum = BigDecimal.ZERO;
        BigDecimal coveredWeightSum = BigDecimal.ZERO;
        BigDecimal totalEffectiveWeightSum = BigDecimal.ZERO;

        List<EstimateDetail> detailEntityList = new ArrayList<>(holdings.size());
        List<FundEstimateDto.EstimateDetailItem> detailDtoList = new ArrayList<>(holdings.size());

        for (Holding holding : holdings) {
            DriftedHoldingDto driftDto = driftMap.get(holding.getId());
            BigDecimal effectiveWeight = (driftDto != null && driftDto.getEffectiveWeight() != null)
                    ? driftDto.getEffectiveWeight()
                    : (holding.getWeightRatio() != null ? holding.getWeightRatio() : BigDecimal.ZERO);

            totalEffectiveWeightSum = totalEffectiveWeightSum.add(effectiveWeight);

            String ticker = holding.getInstrument() != null ? holding.getInstrument().getTicker() : "UNKNOWN";
            String title = holding.getInstrument() != null ? holding.getInstrument().getTitle() : ticker;
            AssetClass assetClass = holding.getInstrument() != null ? holding.getInstrument().getAssetClass() : AssetClass.EQUITY;
            boolean isShort = Boolean.TRUE.equals(holding.getIsShort());

            // Varlığın o anki canlı getirisini belirle
            MarketPriceDto priceDto = livePriceMap.get(ticker);
            BigDecimal assetReturn = BigDecimal.ZERO;
            boolean isCovered = false;

            if (priceDto != null && priceDto.getDailyChangeRatio() != null) {
                // Hisse senedi veya canlı verisi olan varlık
                assetReturn = priceDto.getDailyChangeRatio();
                isCovered = true;
            } else if (assetClass == AssetClass.DEPOSIT) {
                // Ters repo / para piyasası: Günlük faiz getirisi = yıllık / 365
                BigDecimal annualRate = (priceDto != null && priceDto.getCurrentPrice() != null)
                        ? priceDto.getCurrentPrice()
                        : DEFAULT_REPO_ANNUAL_RATE;
                assetReturn = annualRate.divide(BigDecimal.valueOf(365), 6, RoundingMode.HALF_UP);
                isCovered = true;
            } else {
                // Özel sektör bonosu veya fiyatı bulunamayan kalem: Nötr varsayım (r=0)
                assetReturn = BigDecimal.ZERO;
                isCovered = false;
            }

            // Kapsanan varlık ağırlığına ekle
            if (isCovered) {
                coveredWeightSum = coveredWeightSum.add(effectiveWeight);
            }

            // Short (Kısa Pozisyon) Düzeltmesi: Hisse düşerse short pozisyon kazanır (-1 ile çarp)
            BigDecimal effectiveAssetReturn = isShort ? assetReturn.negate() : assetReturn;

            // Fona Bireysel Katkı: w_i * r_i
            BigDecimal weightedContribution = effectiveWeight.multiply(effectiveAssetReturn)
                    .setScale(6, RoundingMode.HALF_UP);

            grossReturnSum = grossReturnSum.add(weightedContribution);

            // DTO Detayı oluştur (Arayüze gidecek veri)
            detailDtoList.add(FundEstimateDto.EstimateDetailItem.builder()
                    .ticker(ticker)
                    .title(title)
                    .effectiveWeight(effectiveWeight.setScale(4, RoundingMode.HALF_UP))
                    .assetReturn(assetReturn.setScale(6, RoundingMode.HALF_UP))
                    .weightedContribution(weightedContribution)
                    .isShort(isShort)
                    .build());

            // DB Detayı oluştur (estimate_details tablosuna gidecek veri)
            EstimateDetail detailEntity = EstimateDetail.builder()
                    .instrument(holding.getInstrument())
                    .effectiveWeight(effectiveWeight.setScale(4, RoundingMode.HALF_UP))
                    .assetReturn(assetReturn.setScale(6, RoundingMode.HALF_UP))
                    .weightedContribution(weightedContribution)
                    .build();

            detailEntityList.add(detailEntity);
        }

        // 5. AŞAMA: Günlük Fon Yönetim Giderini Düş (dailyFee = annualFeeRatio / 252)
        BigDecimal annualFee = fund.getAnnualFeeRatio() != null ? fund.getAnnualFeeRatio() : BigDecimal.ZERO;
        BigDecimal dailyFee = annualFee.divide(ANNUAL_TRADING_DAYS, 6, RoundingMode.HALF_UP);

        // Net Tahmini Getiri = Brüt Getiri - Günlük Gider
        BigDecimal netEstimatedReturn = grossReturnSum.subtract(dailyFee).setScale(6, RoundingMode.HALF_UP);

        // 6. AŞAMA: Kapsama Oranı ve Güven Seviyesini Belirle
        BigDecimal coverageRatio = BigDecimal.ONE;
        if (totalEffectiveWeightSum.compareTo(BigDecimal.ZERO) > 0) {
            coverageRatio = coveredWeightSum.divide(totalEffectiveWeightSum, 4, RoundingMode.HALF_UP);
        }

        ConfidenceLevel confidenceLevel;
        if (coverageRatio.compareTo(HIGH_CONFIDENCE_THRESHOLD) >= 0) {
            confidenceLevel = ConfidenceLevel.HIGH;
        } else if (coverageRatio.compareTo(MEDIUM_CONFIDENCE_THRESHOLD) >= 0) {
            confidenceLevel = ConfidenceLevel.MEDIUM;
        } else {
            confidenceLevel = ConfidenceLevel.LOW;
        }

        LocalDateTime calculationTime = LocalDateTime.now();

        // 7. AŞAMA: Veritabanına Kalıcı Kayıt (estimate_runs ve estimate_details)
        EstimateRun estimateRun = EstimateRun.builder()
                .fund(fund)
                .snapshot(snapshot)
                .estimatedReturn(netEstimatedReturn)
                .coverageRatio(coverageRatio)
                .confidenceLevel(confidenceLevel)
                .calculatedAt(calculationTime)
                .build();

        EstimateRun savedRun = estimateRunRepository.save(estimateRun);

        // Her bir detay satırını kaydedilen ana EstimateRun'a bağla
        for (EstimateDetail detail : detailEntityList) {
            detail.setEstimateRun(savedRun);
        }
        estimateDetailRepository.saveAll(detailEntityList);

        log.info("[ReturnCalculationService] Fon '{}' için getiri hesaplandı: Net=%{}, Brüt=%{}, Kapsama=%{}, Güven={}",
                fund.getCode(),
                netEstimatedReturn.multiply(BigDecimal.valueOf(100)).setScale(2, RoundingMode.HALF_UP),
                grossReturnSum.multiply(BigDecimal.valueOf(100)).setScale(2, RoundingMode.HALF_UP),
                coverageRatio.multiply(BigDecimal.valueOf(100)).setScale(1, RoundingMode.HALF_UP),
                confidenceLevel);

        // 8. AŞAMA: TEFAS Resmi Kapanış Fiyatı ve Anlık Tahmini Birim Fiyatı (TL) Hesapla
        BigDecimal currentPrice = null;
        BigDecimal estimatedPrice = null;
        try {
            Optional<TefasFundDto> tefasOpt = tefasClient.fetchFundPrice(fund.getCode(), LocalDate.now().minusDays(1));
            if (tefasOpt.isPresent() && tefasOpt.get().getUnitPrice() != null) {
                currentPrice = tefasOpt.get().getUnitPrice();
                // Formül: Tahmini Fiyat = Mevcut Fiyat * (1 + Net Getiri)
                estimatedPrice = currentPrice.multiply(BigDecimal.ONE.add(netEstimatedReturn))
                        .setScale(6, RoundingMode.HALF_UP);
            }
        } catch (Exception e) {
            log.debug("[ReturnCalculationService] TEFAS resmi fiyatı o an sorgulanamadı: {}", e.getMessage());
        }

        // 9. AŞAMA: Frontend ve API'ye sunulacak zengin DTO'yu döndür
        return FundEstimateDto.builder()
                .estimateRunId(savedRun.getId())
                .fundCode(fund.getCode())
                .fundTitle(fund.getTitle())
                .currentPrice(currentPrice)
                .estimatedPrice(estimatedPrice)
                .estimatedReturn(netEstimatedReturn)
                .grossReturn(grossReturnSum.setScale(6, RoundingMode.HALF_UP))
                .dailyFee(dailyFee)
                .coverageRatio(coverageRatio)
                .confidenceLevel(confidenceLevel)
                .calculatedAt(calculationTime)
                .details(detailDtoList)
                .build();
    }

    /**
     * Boş veya hatalı snapshot durumunda güvenli sıfır DTO'su oluşturur.
     */
    private FundEstimateDto buildEmptyEstimateDto(Fund fund, FundSnapshot snapshot) {
        return FundEstimateDto.builder()
                .fundCode(fund != null ? fund.getCode() : "UNKNOWN")
                .fundTitle(fund != null ? fund.getTitle() : "Unknown Fund")
                .estimatedReturn(BigDecimal.ZERO)
                .grossReturn(BigDecimal.ZERO)
                .dailyFee(BigDecimal.ZERO)
                .coverageRatio(BigDecimal.ZERO)
                .confidenceLevel(ConfidenceLevel.LOW)
                .calculatedAt(LocalDateTime.now())
                .details(Collections.emptyList())
                .build();
    }
}
