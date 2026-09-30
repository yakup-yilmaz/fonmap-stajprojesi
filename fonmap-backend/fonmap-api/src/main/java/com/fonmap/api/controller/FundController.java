package com.fonmap.api.controller;

import com.fonmap.domain.entity.Fund;
import com.fonmap.domain.entity.FundSnapshot;
import com.fonmap.infrastructure.repository.FundRepository;
import com.fonmap.infrastructure.repository.FundSnapshotRepository;
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
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * =========================================================================================
 * 🏛️ FON YÖNETİM VE LİSTELEME REST CONTROLLER (FundController)
 * =========================================================================================
 *
 * 📌 NE İŞE YARAR? (JUNIOR MÜHENDİS REHBERİ):
 * Bu Controller, sistemimizin "Vitrin Müdürü"dür.
 * Frontend (Next.js) gösterge panelinin ana sayfasında gösterilecek fon kartlarını
 * ve bir fonun kartına tıklandığında açılan detay sayfasının ihtiyaç duyduğu temel
 * künye verilerini HTTP JSON formatında dış dünyaya sunar.
 *
 * 🏗️ MİMARİDEKİ ROLÜ:
 * 1. Tarayıcı / Frontend -> GET /api/v1/funds isteği atar.
 * 2. FundController     -> FundRepository'den aktif fonları çeker.
 * 3. FundSnapshotRepository -> Her fonun KAP'taki en son portföy büyüklüğünü (TNV) ve hisse oranını ekler.
 * 4. Dış Dünyaya         -> Güvenli, sade ve tip-güvenli DTO (Record) listesi döner.
 *
 * 🛡️ NEDEN DTO (RECORD) DÖNÜYORUZ? (GÜVENLİK VE ENCAPSULATION):
 * Veritabanındaki 'Fund' JPA Entity'sini doğrudan JSON olarak dönersek:
 * - İleride N+1 lazy loading patlamaları yaşanabilir.
 * - Kullanıcının görmemesi gereken iç alanlar istemciye sızabilir.
 * Java 16+ ile gelen 'Record' yapısı immutable (değiştirilemez) ve hızlı bir veri taşıyıcısıdır.
 */
@RestController
@RequestMapping("/api/v1/funds")
@RequiredArgsConstructor
@Slf4j
@CrossOrigin(origins = "*") // Frontend Next.js geliştirme ortamı için CORS izni
@Tag(name = "1. Fon Kataloğu (Fund Controller)", description = "Takip edilen fonların kart listesi ve künye detayları")
public class FundController {

    private final FundRepository fundRepository;
    private final FundSnapshotRepository fundSnapshotRepository;

    /**
     * 1. UÇ NOKTA: Aktif Fon Kartları Listesi
     * -------------------------------------------------------------------------------------
     * Frontend Dashboard ana ekranındaki fon kartlarını vitrin sırasına (displayOrder) göre döner.
     *
     * Örnek İstek: GET /api/v1/funds
     * Örnek Yanıt: 200 OK [ { "code": "THF", "title": "Tera Portföy...", "stockRatio": 0.9146 } ]
     *
     * @return Sistemde aktif olan tüm fonların kart özet listesi
     */
    @GetMapping
    @Operation(
            summary = "Aktif Fonların Kart Listesi",
            description = "Ana sayfada gösterilecek aktif 7 fonu (THF, TTE, KHA, DFI vb.) vitrin sırasına göre son snapshot özetleriyle listeler."
    )
    public ResponseEntity<List<FundCardResponse>> getAllActiveFunds() {
        log.info("[FundController] GET /api/v1/funds isteği alındı.");

        // 1. ADIM: Sadece aktif fonları vitrin sırasına göre çek
        List<Fund> funds = fundRepository.findAllActiveFundsOrdered();

        // 2. ADIM: Her fon için en son portföy snapshot verisini bağla ve DTO'ya dönüştür
        List<FundCardResponse> responseList = funds.stream()
                .map(fund -> {
                    // En güncel 1 adet snapshot'ı çek
                    List<FundSnapshot> snapshots = fundSnapshotRepository.findLatestByFundCode(
                            fund.getCode(), PageRequest.of(0, 1));

                    LocalDate snapshotDate = null;
                    BigDecimal stockRatio = null;
                    BigDecimal totalNetAssetValue = null;

                    if (!snapshots.isEmpty()) {
                        FundSnapshot latest = snapshots.get(0);
                        snapshotDate = latest.getSnapshotDate();
                        stockRatio = latest.getStockRatio();
                        totalNetAssetValue = latest.getTotalNetAssetValue();
                    }

                    return new FundCardResponse(
                            fund.getId(),
                            fund.getCode(),
                            fund.getTitle(),
                            fund.getManager(),
                            fund.getAnnualFeeRatio(),
                            fund.getDisplayOrder(),
                            snapshotDate,
                            stockRatio,
                            totalNetAssetValue
                    );
                })
                .toList();

        return ResponseEntity.ok(responseList);
    }

    /**
     * 2. UÇ NOKTA: Tek Bir Fonun Detay Bilgisi
     * -------------------------------------------------------------------------------------
     * Kullanıcı bir fon kartına tıkladığında fonun kurucu şirketi, yıllık gider kesintisi
     * ve yürürlükteki portföy büyüklüğü gibi tüm künye alanlarını döner.
     *
     * Örnek İstek: GET /api/v1/funds/THF
     *
     * @param code 3 harfli fon kodu (Büyük/küçük harf duyarsız: "thf" -> "THF")
     * @return Fonun tüm detaylarını içeren DTO
     */
    @GetMapping("/{code}")
    @Operation(
            summary = "Tek Bir Fonun Künye Detayı",
            description = "Fon koduna göre (Örn: THF, TTE) fonun kurucu şirketi, gider oranı ve son portföy verilerini döner."
    )
    public ResponseEntity<FundDetailResponse> getFundByCode(
            @Parameter(description = "3 harfli resmi fon kodu (Örn: THF, TTE)", example = "THF")
            @PathVariable String code) {

        String normalizedCode = code.trim().toUpperCase();
        log.info("[FundController] GET /api/v1/funds/{} detayı istendi.", normalizedCode);

        // 1. ADIM: Fonu veritabanında ara; yoksa HTTP 404 Not Found dön
        Fund fund = fundRepository.findByCode(normalizedCode)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "Sistemde '" + normalizedCode + "' kodlu bir fon bulunamadı!"));

        // 2. ADIM: Fonun en güncel snapshot kaydını bul
        List<FundSnapshot> snapshots = fundSnapshotRepository.findLatestByFundCode(
                normalizedCode, PageRequest.of(0, 1));

        LocalDate snapshotDate = null;
        BigDecimal stockRatio = null;
        BigDecimal viopCashRatio = null;
        BigDecimal totalNetAssetValue = null;
        Boolean isSuspicious = false;

        if (!snapshots.isEmpty()) {
            FundSnapshot latest = snapshots.get(0);
            snapshotDate = latest.getSnapshotDate();
            stockRatio = latest.getStockRatio();
            viopCashRatio = latest.getViopCashRatio();
            totalNetAssetValue = latest.getTotalNetAssetValue();
            isSuspicious = latest.getIsSuspicious();
        }

        // 3. ADIM: İstemciye DTO olarak yanıt ver
        FundDetailResponse response = new FundDetailResponse(
                fund.getId(),
                fund.getCode(),
                fund.getTitle(),
                fund.getManager(),
                fund.getAnnualFeeRatio(),
                fund.getIsActive(),
                fund.getDisplayOrder(),
                snapshotDate,
                stockRatio,
                viopCashRatio,
                totalNetAssetValue,
                isSuspicious
        );

        return ResponseEntity.ok(response);
    }

    // =========================================================================================
    // 📦 DTO (DATA TRANSFER OBJECT) RECORD TANIMLARI
    // =========================================================================================

    /**
     * Dashboard Ana Ekran Fon Kartı DTO'su.
     */
    public record FundCardResponse(
            UUID id,
            String code,
            String title,
            String manager,
            BigDecimal annualFeeRatio,
            Integer displayOrder,
            LocalDate latestSnapshotDate,
            BigDecimal latestStockRatio,
            BigDecimal latestTotalNetAssetValue
    ) {}

    /**
     * Fon Detay Ekranı Künye DTO'su.
     */
    public record FundDetailResponse(
            UUID id,
            String code,
            String title,
            String manager,
            BigDecimal annualFeeRatio,
            Boolean isActive,
            Integer displayOrder,
            LocalDate latestSnapshotDate,
            BigDecimal stockRatio,
            BigDecimal viopCashRatio,
            BigDecimal totalNetAssetValue,
            Boolean isSnapshotSuspicious
    ) {}
}
