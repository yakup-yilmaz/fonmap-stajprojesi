package com.fonmap.api.controller;

import com.fonmap.application.service.backtest.BacktestService;
import com.fonmap.application.service.backtest.dto.BacktestReportDto;
import com.fonmap.application.service.pdf.PdfImportService;
import com.fonmap.application.service.reconciliation.ReconciliationService;
import com.fonmap.application.service.reconciliation.dto.ReconciliationDto;
import com.fonmap.domain.entity.FundSnapshot;
import com.fonmap.domain.entity.InstrumentAlias;
import com.fonmap.infrastructure.client.kap.KapClient;
import com.fonmap.infrastructure.client.kap.dto.KapPdfDto;
import com.fonmap.infrastructure.repository.InstrumentAliasRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * =========================================================================================
 * 🛠️ SİSTEM YÖNETİMİ VE ARKA OFİS REST CONTROLLER (AdminController)
 * =========================================================================================
 *
 * 📌 NE İŞE YARAR? (JUNIOR MÜHENDİS REHBERİ):
 * Bu Controller, Fonmap platformunun "Yönetim Odası"dır (Backoffice API).
 * Sıradan kullanıcıların erişemeyeceği, yalnızca sistem yöneticilerinin (Admin)
 * kullanabileceği kritik operasyonel uçları yönetir:
 *
 * 🏗️ YÖNETTİĞİ 4 ANA OPERASYON:
 * 1. Tek Tıkla Manuel PDF Yükleme:
 *    KAP sunucusunda kesinti olduğunda veya acil bir durumda yöneticinin elindeki
 *    KAP dağılım raporunu panele yüklemesini ve sisteme anında işletmesini sağlar.
 * 2. Ekonometrik Backtest / Simülasyon Çalıştırma:
 *    Belirli bir fon için geçmişe dönük (42-60 iş günü) model tahmin doğruluğunu,
 *    MAE, RMSE ve yönsel isabet oranını ekrana döker.
 * 3. Gece Mutabakatını Manuel Tetikleme:
 *    Gece 23:00'te çalışan TEFAS mutabakatını adminin istediği tarih için elle tetiklemesini sağlar.
 * 4. Akıllı Hisse Eşleme Sözlüğü Yönetimi:
 *    PDF'ten gelen ve sisteme ilk kez giren kirli şirket unvanlarını listeler ve onaylatır.
 */
@RestController
@RequestMapping("/api/v1/admin")
@RequiredArgsConstructor
@Slf4j
@CrossOrigin(origins = "*")
@Tag(name = "3. Yönetim & Operasyon (Admin Controller)", description = "PDF yükleme, backtest çalıştırma, mutabakat ve hisse eşleme onayları")
public class AdminController {

    private final PdfImportService pdfImportService;
    private final BacktestService backtestService;
    private final ReconciliationService reconciliationService;
    private final InstrumentAliasRepository instrumentAliasRepository;
    private final KapClient kapClient;

    /**
     * 1. UÇ NOKTA: Manuel KAP Portföy Raporu PDF Yükleme
     * -------------------------------------------------------------------------------------
     * Yönetici tarayıcıdan bir PDF seçip gönderdiğinde dosyanın SHA-256'sını hesaplar,
     * ilgili fonun parser'ı ile ayrıştırır ve veritabanına yeni bir Snapshot kaydeder.
     *
     * @param code Fon kodu (Örn: "THF", "TTE", "DFI")
     * @param file Yüklenen çok parçalı (Multipart) PDF dosyası
     * @return Başarıyla içe aktarılan snapshot özeti
     */
    @PostMapping(value = "/funds/{code}/upload-report", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(
            summary = "Manuel KAP PDF Raporu Yükle",
            description = "Seçilen fon için aylık portföy dağılım PDF dosyasını içeri aktarır ve yeni portföy snapshot'ı oluşturur."
    )
    public ResponseEntity<UploadReportResponse> uploadPdfReport(
            @Parameter(description = "Hedef fon kodu (Örn: THF, TTE)", example = "THF")
            @PathVariable String code,
            @Parameter(description = "KAP Portföy Dağılım Raporu PDF dosyası")
            @RequestParam("file") MultipartFile file) {

        String normalizedCode = code.trim().toUpperCase();
        log.info("[AdminController] Fon '{}' için manuel PDF yükleme isteği alındı: Dosya='{}', Boyut={} KB",
                normalizedCode, file.getOriginalFilename(), file.getSize() / 1024);

        if (file.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Yüklenen PDF dosyası boş olamaz!");
        }

        try {
            byte[] bytes = file.getBytes();
            String sha256 = kapClient.calculateSha256(bytes);

            FundSnapshot snapshot = pdfImportService.importPdfReport(
                    bytes,
                    normalizedCode,
                    "MANUAL_UPLOAD:" + file.getOriginalFilename(),
                    sha256,
                    LocalDateTime.now()
            );

            UploadReportResponse response = new UploadReportResponse(
                    snapshot.getId().toString(),
                    normalizedCode,
                    snapshot.getSnapshotDate(),
                    snapshot.getTotalNetAssetValue(),
                    snapshot.getStockRatio(),
                    snapshot.getViopCashRatio(),
                    snapshot.getIsSuspicious(),
                    "PDF başarıyla ayrıştırıldı ve yeni portföy snapshot'ı devreye alındı."
            );

            return ResponseEntity.ok(response);

        } catch (Exception e) {
            log.error("[AdminController] PDF içeri aktarılırken hata oluştu: {}", e.getMessage(), e);
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "PDF işlenemedi: " + e.getMessage());
        }
    }

    /**
     * 1.B UÇ NOKTA: KAP Bildirim Linkinden (URL) Doğrudan PDF İndir ve İşle
     * -------------------------------------------------------------------------------------
     * Kullanıcı dosya indirmekle uğraşmak istemediğinde doğrudan KAP bildirimindeki
     * PDF linkini verir. WebClient reaktif olarak PDF'i indirir, SHA-256 idempotency
     * kontrolünden geçirir, ayrıştırır ve sisteme anında yeni snapshot kazandırır.
     *
     * @param code    Fon kodu (Örn: "THF", "TTE")
     * @param request URL gövdesi: {"url": "https://www.kap.org.tr/tr/Bildirim/..."}
     * @return Başarıyla içe aktarılan snapshot özeti
     */
    @PostMapping(value = "/funds/{code}/fetch-report-url")
    @Operation(
            summary = "KAP Bildirim Linkinden PDF İndir ve İşle",
            description = "KAP bildirim ekindeki PDF linkini doğrudan indirir, ayrıştırır ve yeni portföy snapshot'ı oluşturur."
    )
    public ResponseEntity<UploadReportResponse> fetchReportFromUrl(
            @Parameter(description = "Hedef fon kodu (Örn: THF, TTE)", example = "THF")
            @PathVariable String code,
            @RequestBody FetchUrlRequest request) {

        String normalizedCode = code.trim().toUpperCase();
        if (request == null || request.url() == null || request.url().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "KAP PDF URL'si boş olamaz!");
        }

        String pdfUrl = request.url().trim();
        log.info("[AdminController] Fon '{}' için URL'den PDF indirme isteği alındı: URL='{}'",
                normalizedCode, pdfUrl);

        try {
            KapPdfDto pdfDto = kapClient.downloadFromUrl(normalizedCode, pdfUrl);

            FundSnapshot snapshot = pdfImportService.importPdfReport(
                    pdfDto.getContent(),
                    normalizedCode,
                    "KAP_URL:" + pdfUrl,
                    pdfDto.getSha256Hash(),
                    LocalDateTime.now()
            );

            UploadReportResponse response = new UploadReportResponse(
                    snapshot.getId().toString(),
                    normalizedCode,
                    snapshot.getSnapshotDate(),
                    snapshot.getTotalNetAssetValue(),
                    snapshot.getStockRatio(),
                    snapshot.getViopCashRatio(),
                    snapshot.getIsSuspicious(),
                    "KAP URL'sindeki PDF başarıyla indirildi, ayrıştırıldı ve yeni portföy snapshot'ı devreye alındı."
            );

            return ResponseEntity.ok(response);

        } catch (Exception e) {
            log.error("[AdminController] KAP URL indirme ve ayrıştırma hatası: {}", e.getMessage(), e);
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "KAP PDF işlenemedi: " + e.getMessage());
        }
    }

    /**
     * 1.C UÇ NOKTA: KAP'tan Otomatik Rapor Keşfet ve İndir (T3.1 & T3.2 Tam Otomasyon)
     * -------------------------------------------------------------------------------------
     * Kullanıcıdan dosya veya link istemez. Sadece fon kodunu alır, KAP bildirim
     * servisini tarar, en son "Portföy Dağılım Raporu"nu ve ekli PDF'i bizzat bulur,
     * indirir ve veritabanına yeni Snapshot olarak kaydeder.
     *
     * @param code Fon kodu (Örn: "DFI", "THF", "TTE")
     * @return Başarıyla içe aktarılan snapshot özeti
     */
    @PostMapping(value = "/funds/{code}/discover-report")
    @Operation(
            summary = "KAP'tan Otomatik Rapor Keşfet ve İndir (T3.1 & T3.2)",
            description = "Fon kodunu kullanarak KAP'ta arama yapar, en son 'Portföy Dağılım Raporu'nu bulur, PDF'i indirir ve sisteme işler."
    )
    public ResponseEntity<UploadReportResponse> autoDiscoverReport(
            @Parameter(description = "Fon kodu (Örn: DFI, THF, TTE)", example = "DFI")
            @PathVariable String code) {

        String normalizedCode = code.trim().toUpperCase();
        log.info("[AdminController] 🤖 Otomatik KAP keşfi tetiklendi: Fon='{}'", normalizedCode);

        try {
            KapPdfDto pdfDto = kapClient.fetchPdf(normalizedCode, null);
            if (pdfDto == null || pdfDto.isEmpty()) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "KAP'ta '" + normalizedCode + "' fonu için dağılım raporu bulunamadı.");
            }

            FundSnapshot snapshot = pdfImportService.importPdfReport(
                    pdfDto.getContent(),
                    normalizedCode,
                    pdfDto.getSource(),
                    pdfDto.getSha256Hash(),
                    LocalDateTime.now()
            );

            UploadReportResponse response = new UploadReportResponse(
                    snapshot.getId().toString(),
                    normalizedCode,
                    snapshot.getSnapshotDate(),
                    snapshot.getTotalNetAssetValue(),
                    snapshot.getStockRatio(),
                    snapshot.getViopCashRatio(),
                    snapshot.getIsSuspicious(),
                    "KAP'tan otomatik keşfedilen rapor başarıyla içe aktarıldı (Kaynak: " + pdfDto.getSource() + ")."
            );

            return ResponseEntity.ok(response);

        } catch (ResponseStatusException rse) {
            throw rse;
        } catch (Exception e) {
            log.error("[AdminController] Otomatik KAP keşfi ve ayrıştırma hatası: {}", e.getMessage(), e);
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "Otomatik keşif hatası: " + e.getMessage());
        }
    }

    /**
     * 2. UÇ NOKTA: Geriye Dönük Doğruluk ve Simülasyon (Backtest) Raporu
     * -------------------------------------------------------------------------------------
     * Belirtilen fon için geçmiş seans günlerinin TEFAS verileriyle model tahminlerini
     * kıyaslar ve ekonometrik başarı karnesini (MAE, RMSE, Yönsel Başarı) döner.
     *
     * @param fundCode Fon kodu (Örn: "THF", "TTE")
     * @param startDate Opsiyonel başlangıç tarihi (Örn: 2026-08-01)
     * @param endDate Opsiyonel bitiş tarihi (Örn: 2026-09-30)
     * @return MAE, RMSE ve günlük karşılaştırma dökümünü içeren Backtest karnesi
     */
    @GetMapping("/backtest/{fundCode}")
    @Operation(
            summary = "Ekonometrik Backtest Simülasyonu Çalıştır",
            description = "Fonun geçmiş seans günlerinde modelin TEFAS ile ne kadar uyumlu çalıştığını (MAE, RMSE, Başarı Oranı) hesaplar."
    )
    public ResponseEntity<BacktestReportDto> runBacktest(
            @Parameter(description = "Fon kodu (Örn: THF, TTE)", example = "THF")
            @PathVariable String fundCode,
            @Parameter(description = "Başlangıç Tarihi (YYYY-MM-DD)", example = "2026-08-01")
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @Parameter(description = "Bitiş Tarihi (YYYY-MM-DD)", example = "2026-09-30")
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate) {

        String normalizedCode = fundCode.trim().toUpperCase();
        log.info("[AdminController] Fon '{}' için backtest isteği alındı (Aralık: {} - {})",
                normalizedCode, startDate, endDate);

        LocalDate effectiveEnd = endDate != null ? endDate : LocalDate.now();
        LocalDate effectiveStart = startDate != null ? startDate : effectiveEnd.minusDays(60);

        BacktestReportDto report = backtestService.runBacktest(normalizedCode, effectiveStart, effectiveEnd);
        return ResponseEntity.ok(report);
    }

    /**
     * 3. UÇ NOKTA: Gece TEFAS Mutabakatını Manuel Tetikleme
     * -------------------------------------------------------------------------------------
     * Belirtilen bir seans günü için tüm aktif fonların gece 23:00 TEFAS resmi mutabakatını
     * anında çalıştırır ve sonuçları döner.
     *
     * @param targetDate Mutabakat tarihi (Örn: 2026-09-03)
     * @return Tüm aktif fonların mutabakat sonuç listesi
     */
    @PostMapping("/reconcile/{targetDate}")
    @Operation(
            summary = "TEFAS Gece Mutabakatını Manuel Çalıştır",
            description = "Belirtilen gün için TEFAS resmi kapanış fiyatları ile model tahminini karşılaştırıp hata skorlarını hesaplar."
    )
    public ResponseEntity<List<ReconciliationDto>> triggerReconciliation(
            @Parameter(description = "Mutabakat yapılacak tarih (YYYY-MM-DD)", example = "2026-09-03")
            @PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate targetDate) {

        log.info("[AdminController] Manuel mutabakat tetiklendi: Tarih={}", targetDate);
        List<ReconciliationDto> results = reconciliationService.reconcileAllActiveFunds(targetDate);
        return ResponseEntity.ok(results);
    }

    /**
     * 4. UÇ NOKTA: Onay Bekleyen Kirli/Yeni Hisse Eşleme Listesi
     * -------------------------------------------------------------------------------------
     * PDF'lerde daha önce hiç karşılaşılmamış ve otomatik eşleştirilememiş şirket
     * adlarını admin incelemesine sunar.
     */
    @GetMapping("/aliases/pending")
    @Operation(
            summary = "Onay Bekleyen Hisse Eşleşmeleri",
            description = "PDF'ten gelen ve sözlükte henüz onaylanmamış enstrüman unvanlarını listeler."
    )
    public ResponseEntity<List<PendingAliasResponse>> getPendingAliases() {
        log.info("[AdminController] Onay bekleyen alias listesi istendi.");

        List<InstrumentAlias> unapproved = instrumentAliasRepository.findAllUnapprovedWithInstrument();
        List<PendingAliasResponse> response = unapproved.stream()
                .map(a -> new PendingAliasResponse(
                        a.getId(),
                        a.getRawName(),
                        a.getInstrument() != null ? a.getInstrument().getTicker() : "TANIMSIZ",
                        a.getInstrument() != null ? a.getInstrument().getTitle() : "TANIMSIZ",
                        a.getMatchConfidence(),
                        a.getCreatedAt()
                ))
                .toList();

        return ResponseEntity.ok(response);
    }

    /**
     * 5. UÇ NOKTA: Hisse Eşleşmesini Onaylama
     * -------------------------------------------------------------------------------------
     * Admin inceledikten sonra eşleşmenin doğru olduğunu onaylar.
     */
    @PostMapping("/aliases/{id}/approve")
    @Operation(
            summary = "Hisse Eşleşmesini Onayla",
            description = "Adminin sözlükteki bir hisse eşlemesini geçerli olarak işaretlemesini sağlar."
    )
    public ResponseEntity<String> approveAlias(@PathVariable Long id) {
        log.info("[AdminController] Alias onayı istendi: ID={}", id);

        InstrumentAlias alias = instrumentAliasRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Alias kaydı bulunamadı: " + id));

        alias.setIsApproved(true);
        instrumentAliasRepository.save(alias);

        return ResponseEntity.ok("Alias (ID: " + id + ") başarıyla onaylandı.");
    }

    // =========================================================================================
    // 📦 DTO (RECORD) MODELLERİ
    // =========================================================================================

    public record UploadReportResponse(
            String snapshotId,
            String fundCode,
            LocalDate snapshotDate,
            BigDecimal totalNetAssetValue,
            BigDecimal stockRatio,
            BigDecimal viopCashRatio,
            Boolean isSuspicious,
            String message
    ) {}

    public record PendingAliasResponse(
            Long id,
            String rawName,
            String mappedTicker,
            String mappedTitle,
            BigDecimal confidence,
            LocalDateTime createdAt
    ) {}

    public record FetchUrlRequest(
            String url
    ) {}
}
