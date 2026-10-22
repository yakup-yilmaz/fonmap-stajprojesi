package com.fonmap.api.controller;

import com.fonmap.application.service.backtest.BacktestService;
import com.fonmap.application.service.backtest.dto.BacktestReportDto;
import com.fonmap.domain.entity.Fund;
import com.fonmap.domain.entity.FundReport;
import com.fonmap.domain.entity.FundSnapshot;
import com.fonmap.infrastructure.repository.FundRepository;
import com.fonmap.infrastructure.repository.FundSnapshotRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
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
 * 3. FundSnapshotRepository -> Her fonun KAP'taki en son portföy büyüklüğünü (TNV),
 *    hisse oranını ve PDF indirilme/oluşturulma zamanı gibi meta verilerini ekler.
 * 4. Dış Dünyaya         -> Güvenli, sade ve tip-güvenli DTO (Record) listesi döner.
 */
@RestController
@RequestMapping("/api/v1/funds")
@Slf4j
@CrossOrigin(origins = "*") // Frontend Next.js geliştirme ortamı için CORS izni
@Tag(name = "1. Fon Kataloğu (Fund Controller)", description = "Takip edilen fonların kart listesi ve künye detayları")
@RequiredArgsConstructor
public class FundController {

    private final FundRepository fundRepository;
    private final FundSnapshotRepository fundSnapshotRepository;
    private final BacktestService backtestService;

    /**
     * 1. UÇ NOKTA: Aktif Fon Kartları Listesi
     * -------------------------------------------------------------------------------------
     * Frontend Dashboard ve Admin ana ekranındaki fon kartlarını vitrin sırasına göre döner.
     * Artık her fonun KAP PDF indirilme zamanı, rapor tarihi ve SHA-256 parmak izi de eklenmiştir.
     *
     * @return Sistemde aktif olan tüm fonların kart özet listesi
     */
    @GetMapping
    @Operation(
            summary = "Aktif Fonların Kart Listesi",
            description = "Ana sayfada gösterilecek aktif fonları vitrin sırasına göre son snapshot ve PDF raporu meta verileriyle listeler."
    )
    public ResponseEntity<List<FundCardResponse>> getAllActiveFunds() {
        log.info("[FundController] GET /api/v1/funds isteği alındı.");

        // 1. ADIM: Sadece aktif fonları vitrin sırasına göre çek
        List<Fund> funds = fundRepository.findAllActiveFundsOrdered();

        // 2. ADIM: Her fon için en son portföy snapshot verisini bağla ve DTO'ya dönüştür
        List<FundCardResponse> responseList = funds.stream()
                .map(fund -> {
                    // En güncel 1 adet snapshot'ı ilişkili FundReport ile birlikte çek
                    List<FundSnapshot> snapshots = fundSnapshotRepository.findLatestByFundCode(
                            fund.getCode(), PageRequest.of(0, 1));

                    LocalDate snapshotDate = null;
                    BigDecimal stockRatio = null;
                    BigDecimal totalNetAssetValue = null;
                    LocalDate reportDate = null;
                    LocalDateTime reportDownloadedAt = null;
                    String reportSource = null;
                    String reportSha256 = null;
                    String reportStatus = null;

                    if (!snapshots.isEmpty()) {
                        FundSnapshot latest = snapshots.get(0);
                        snapshotDate = latest.getSnapshotDate();
                        stockRatio = latest.getStockRatio();
                        totalNetAssetValue = latest.getTotalNetAssetValue();

                        // PDF Rapor Meta Verileri (İndirilme Zamanı, Dönem ve SHA-256 Hash)
                        if (latest.getReport() != null) {
                            FundReport report = latest.getReport();
                            reportDate = report.getReportDate();
                            reportDownloadedAt = report.getCreatedAt();
                            reportSource = report.getPdfUrl();
                            reportSha256 = report.getPdfSha256();
                            reportStatus = report.getStatus() != null ? report.getStatus().name() : "PARSED";
                        } else {
                            reportDate = latest.getSnapshotDate();
                            reportDownloadedAt = latest.getCreatedAt();
                            reportStatus = "PARSED";
                        }
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
                            totalNetAssetValue,
                            reportDate,
                            reportDownloadedAt,
                            reportSource,
                            reportSha256,
                            reportStatus
                    );
                })
                .toList();

        return ResponseEntity.ok(responseList);
    }

    /**
     * 2. UÇ NOKTA: Tek Bir Fonun Detay Bilgisi
     * -------------------------------------------------------------------------------------
     * Kullanıcı bir fon kartına tıkladığında fonun kurucu şirketi, yıllık gider kesintisi,
     * yürürlükteki portföy büyüklüğü ve PDF raporunun ne zaman indirilip işlendiği gibi
     * tüm künye alanlarını döner.
     *
     * @param code 3 harfli fon kodu (Büyük/küçük harf duyarsız: "thf" -> "THF")
     * @return Fonun tüm detaylarını ve PDF meta verilerini içeren DTO
     */
    @GetMapping("/{code}")
    @Operation(
            summary = "Tek Bir Fonun Künye Detayı",
            description = "Fon koduna göre (Örn: THF, TTE) fonun kurucu şirketi, gider oranı ve son portföy/PDF meta verilerini döner."
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

        // 2. ADIM: Fonun en güncel snapshot kaydını ve ilişkili PDF raporunu bul
        List<FundSnapshot> snapshots = fundSnapshotRepository.findLatestByFundCode(
                normalizedCode, PageRequest.of(0, 1));

        LocalDate snapshotDate = null;
        BigDecimal stockRatio = null;
        BigDecimal viopCashRatio = null;
        BigDecimal totalNetAssetValue = null;
        Boolean isSuspicious = false;
        LocalDate reportDate = null;
        LocalDateTime reportDownloadedAt = null;
        String reportSource = null;
        String reportSha256 = null;
        String reportStatus = null;

        if (!snapshots.isEmpty()) {
            FundSnapshot latest = snapshots.get(0);
            snapshotDate = latest.getSnapshotDate();
            stockRatio = latest.getStockRatio();
            viopCashRatio = latest.getViopCashRatio();
            totalNetAssetValue = latest.getTotalNetAssetValue();
            isSuspicious = latest.getIsSuspicious();

            if (latest.getReport() != null) {
                FundReport report = latest.getReport();
                reportDate = report.getReportDate();
                reportDownloadedAt = report.getCreatedAt();
                reportSource = report.getPdfUrl();
                reportSha256 = report.getPdfSha256();
                reportStatus = report.getStatus() != null ? report.getStatus().name() : "PARSED";
            } else {
                reportDate = latest.getSnapshotDate();
                reportDownloadedAt = latest.getCreatedAt();
                reportStatus = "PARSED";
            }
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
                isSuspicious,
                reportDate,
                reportDownloadedAt,
                reportSource,
                reportSha256,
                reportStatus
        );

        return ResponseEntity.ok(response);
    }

    /**
     * 3. UÇ NOKTA: Fonun Ekonometrik Backtest ve Doğruluk Raporu (Genel / Müşteri Erişimi)
     * -------------------------------------------------------------------------------------
     * Belirtilen fon için geçmiş seans günlerinin TEFAS verileriyle model tahminlerini
     * kıyaslar ve ekonometrik başarı karnesini (MAE, RMSE, Yönsel Başarı, Kümülatif Getiri) döner.
     *
     * @param code Fon borsa kodu (Örn: "THF", "TTE", "KHA")
     * @param startDate Opsiyonel başlangıç tarihi (YYYY-MM-DD)
     * @param endDate Opsiyonel bitiş tarihi (YYYY-MM-DD)
     * @return Ekonometrik başarı karnesi DTO'su
     */
    @GetMapping("/{code}/backtest")
    @Operation(
            summary = "Fonun Ekonometrik Backtest Raporunu Getir (Müşteri/Genel)",
            description = "Fonun geçmiş seans günlerinde modelin TEFAS ile ne kadar uyumlu çalıştığını (MAE, RMSE, Başarı Oranı) döner."
    )
    public ResponseEntity<BacktestReportDto> getFundBacktest(
            @Parameter(description = "Hedef fon kodu (Örn: THF, TTE)", example = "THF")
            @PathVariable String code,
            @Parameter(description = "Başlangıç Tarihi (YYYY-MM-DD)", example = "2026-08-01")
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @Parameter(description = "Bitiş Tarihi (YYYY-MM-DD)", example = "2026-09-30")
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate) {

        String normalizedCode = code.trim().toUpperCase();
        log.info("[FundController] GET /api/v1/funds/{}/backtest isteği alındı (Aralık: {} - {})",
                normalizedCode, startDate, endDate);

        BacktestReportDto report = backtestService.getOrRunBacktest(normalizedCode, startDate, endDate, false);
        return ResponseEntity.ok(report);
    }

    // =========================================================================================
    // 📦 DTO (DATA TRANSFER OBJECT) RECORD TANIMLARI
    // =========================================================================================

    /**
     * Dashboard ve Admin Ana Ekran Fon Kartı DTO'su.
     * Artık PDF indirilme zamanı ve hash parmak izini de taşır.
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
            BigDecimal latestTotalNetAssetValue,
            LocalDate reportDate,            // KAP Raporunun ait olduğu dönem sonu (Örn: 2026-08-31)
            LocalDateTime reportDownloadedAt,// Sisteme indirilip veritabanına işlendiği an
            String reportSource,             // Rapor kaynağı (KAP Otomasyon URL veya dosya adı)
            String reportSha256,             // Dosyanın SHA-256 güvenlik parmak izi
            String reportStatus              // PARSED, DOWNLOADED, FAILED
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
            Boolean isSnapshotSuspicious,
            LocalDate reportDate,
            LocalDateTime reportDownloadedAt,
            String reportSource,
            String reportSha256,
            String reportStatus
    ) {}
}
