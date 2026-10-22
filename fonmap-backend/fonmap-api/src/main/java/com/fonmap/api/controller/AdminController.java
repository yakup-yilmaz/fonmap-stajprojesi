package com.fonmap.api.controller;

import com.fonmap.application.service.backtest.BacktestService;
import com.fonmap.application.service.backtest.dto.BacktestReportDto;
import com.fonmap.application.service.pdf.PdfImportService;
import com.fonmap.application.service.pdf.dto.PdfPreviewResponse;
import com.fonmap.application.service.reconciliation.ReconciliationService;
import com.fonmap.application.service.reconciliation.dto.ReconciliationDto;
import com.fonmap.domain.entity.Fund;
import com.fonmap.domain.entity.FundReport;
import com.fonmap.domain.entity.FundSnapshot;
import com.fonmap.domain.entity.InstrumentAlias;
import com.fonmap.infrastructure.client.kap.KapClient;
import com.fonmap.infrastructure.client.kap.dto.KapPdfDto;
import com.fonmap.infrastructure.client.tefas.TefasClient;
import com.fonmap.infrastructure.client.tefas.dto.TefasFundProfileDto;
import com.fonmap.infrastructure.repository.EstimateRunRepository;
import com.fonmap.infrastructure.repository.FundReportRepository;
import com.fonmap.infrastructure.repository.FundRepository;
import com.fonmap.infrastructure.repository.FundSnapshotRepository;
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

import com.fonmap.application.service.backtest.BacktestCacheService;
import com.fonmap.domain.entity.Holding;
import com.fonmap.infrastructure.repository.HoldingRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;

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
 * KAP sunucusunda kesinti olduğunda veya acil bir durumda yöneticinin elindeki
 * KAP dağılım raporunu panele yüklemesini ve sisteme anında işletmesini sağlar.
 * 2. Ekonometrik Backtest / Simülasyon Çalıştırma:
 * Belirli bir fon için geçmişe dönük (42-60 iş günü) model tahmin doğruluğunu,
 * MAE, RMSE ve yönsel isabet oranını ekrana döker.
 * 3. Gece Mutabakatını Manuel Tetikleme:
 * Gece 23:00'te çalışan TEFAS mutabakatını adminin istediği tarih için elle
 * tetiklemesini sağlar.
 * 4. Akıllı Hisse Eşleme Sözlüğü Yönetimi:
 * PDF'ten gelen ve sisteme ilk kez giren kirli şirket unvanlarını listeler ve
 * onaylatır.
 */
@RestController
@RequestMapping("/api/v1/admin")
@Slf4j
@CrossOrigin(origins = "*")
@Tag(name = "3. Yönetim & Operasyon (Admin Controller)", description = "PDF yükleme, backtest çalıştırma, mutabakat ve hisse eşleme onayları")
@RequiredArgsConstructor
public class AdminController {

        private final PdfImportService pdfImportService;
        private final BacktestService backtestService;
        private final ReconciliationService reconciliationService;
        private final InstrumentAliasRepository instrumentAliasRepository;
        private final KapClient kapClient;
        private final FundRepository fundRepository;
        private final FundSnapshotRepository fundSnapshotRepository;
        private final FundReportRepository fundReportRepository;
        private final HoldingRepository holdingRepository;
        private final EstimateRunRepository estimateRunRepository;
        private final BacktestCacheService backtestCacheService;
        private final TefasClient tefasClient;



        /**
         * Yıllık yönetim ücreti API sözleşmesi: ORAN (0.0 – 1.0). Örn. %2,5 → 0.025.
         * Eski "1'den büyükse 100'e böl" tahmini kaldırıldı; belirsiz birim yanlış
         * ücret kaydediyordu.
         */
        private static BigDecimal validateFeeRatio(BigDecimal fee) {
                if (fee.signum() < 0 || fee.compareTo(BigDecimal.ONE) > 0) {
                        throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                                        "annualFeeRatio 0 ile 1 arasında bir ORAN olmalıdır (örn. %2,5 için 0.025). Gelen: "
                                                        + fee);
                }
                return fee;
        }

        /**
         * 1. UÇ NOKTA: Manuel KAP Portföy Raporu PDF Yükleme
         * -------------------------------------------------------------------------------------
         * Yönetici tarayıcıdan bir PDF seçip gönderdiğinde dosyanın SHA-256'sını
         * hesaplar,
         * ilgili fonun parser'ı ile ayrıştırır ve veritabanına yeni bir Snapshot
         * kaydeder.
         *
         * @param code Fon kodu (Örn: "THF", "TTE", "DFI")
         * @param file Yüklenen çok parçalı (Multipart) PDF dosyası
         * @return Başarıyla içe aktarılan snapshot özeti
         */
        @PostMapping(value = "/funds/{code}/upload-report", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
        @Operation(summary = "Manuel KAP PDF Raporu Yükle", description = "Seçilen fon için aylık portföy dağılım PDF dosyasını içeri aktarır ve yeni portföy snapshot'ı oluşturur.")
        public ResponseEntity<UploadReportResponse> uploadPdfReport(
                        @Parameter(description = "Hedef fon kodu (Örn: THF, TTE)", example = "THF") @PathVariable String code,
                        @Parameter(description = "KAP Portföy Dağılım Raporu PDF dosyası") @RequestParam("file") MultipartFile file) {

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
                                        LocalDateTime.now());

                        UploadReportResponse response = new UploadReportResponse(
                                        snapshot.getId().toString(),
                                        normalizedCode,
                                        snapshot.getSnapshotDate(),
                                        snapshot.getTotalNetAssetValue(),
                                        snapshot.getStockRatio(),
                                        snapshot.getViopCashRatio(),
                                        snapshot.getIsSuspicious(),
                                        "PDF başarıyla ayrıştırıldı ve yeni portföy snapshot'ı devreye alındı.");

                        return ResponseEntity.ok(response);

                } catch (Exception e) {
                        log.error("[AdminController] PDF içeri aktarılırken hata oluştu: {}", e.getMessage(), e);
                        throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                                        "PDF işlenemedi: " + e.getMessage());
                }
        }

        /**
         * 1.C UÇ NOKTA: PDF Önizleme ve Doğrulama (Dry-Run Preview)
         * -------------------------------------------------------------------------------------
         * Yönetici sisteme bir PDF yüklemeden önce veritabanına hiçbir şey kaydetmeden
         * dosyanın ayrıştırılıp ayrıştırılamadığını, seçilen stratejiyi ve varlık
         * sınıflarını
         * (Hisse, VİOP, Repo, Bono kırılımı) önizleme olarak görür.
         *
         * @param code Hedef fon kodu
         * @param file Yüklenen çok parçalı PDF dosyası
         * @return Zengin varlık kırılımı ve doğrulama özeti (DB'ye yazmaz)
         */
        @PostMapping(value = "/funds/{code}/preview-pdf", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
        @Operation(summary = "PDF Raporu Önizle (Dry-Run)", description = "Seçilen fon için yüklenen PDF dosyasını veritabanına kaydetmeden ayrıştırır, varlık kırılımını ve doğrulama durumunu önizleme olarak sunar.")
        public ResponseEntity<PdfPreviewResponse> previewPdfReport(
                        @Parameter(description = "Hedef fon kodu (Örn: THF, TTE, TI2)", example = "THF") @PathVariable String code,
                        @Parameter(description = "KAP Portföy Dağılım Raporu PDF dosyası") @RequestParam("file") MultipartFile file) {

                String normalizedCode = code.trim().toUpperCase();
                log.info("[AdminController] Fon '{}' için PDF önizleme (Dry-Run) isteği alındı: Dosya='{}', Boyut={} KB",
                                normalizedCode, file.getOriginalFilename(), file.getSize() / 1024);

                if (file.isEmpty()) {
                        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Yüklenen PDF dosyası boş olamaz!");
                }

                try {
                        byte[] bytes = file.getBytes();
                        PdfPreviewResponse previewResponse = pdfImportService.previewPdfReport(bytes, normalizedCode);
                        return ResponseEntity.ok(previewResponse);
                } catch (Exception e) {
                        log.error("[AdminController] PDF önizleme sırasında hata oluştu: Fon='{}' -> {}",
                                        normalizedCode, e.getMessage(), e);
                        throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                                        "PDF önizleme başarısız: " + e.getMessage());
                }
        }

        /**
         * 1.B UÇ NOKTA: KAP Bildirim Linkinden (URL) Doğrudan PDF İndir ve İşle
         * -------------------------------------------------------------------------------------
         * Kullanıcı dosya indirmekle uğraşmak istemediğinde doğrudan KAP bildirimindeki
         * PDF linkini verir. WebClient reaktif olarak PDF'i indirir, SHA-256
         * idempotency
         * kontrolünden geçirir, ayrıştırır ve sisteme anında yeni snapshot kazandırır.
         *
         * @param code    Fon kodu (Örn: "THF", "TTE")
         * @param request URL gövdesi: {"url": "https://www.kap.org.tr/tr/Bildirim/..."}
         * @return Başarıyla içe aktarılan snapshot özeti
         */
        @PostMapping(value = "/funds/{code}/fetch-report-url")
        @Operation(summary = "KAP Bildirim Linkinden PDF İndir ve İşle", description = "KAP bildirim ekindeki PDF linkini doğrudan indirir, ayrıştırır ve yeni portföy snapshot'ı oluşturur.")
        public ResponseEntity<UploadReportResponse> fetchReportFromUrl(
                        @Parameter(description = "Hedef fon kodu (Örn: THF, TTE)", example = "THF") @PathVariable String code,
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
                                        LocalDateTime.now());

                        UploadReportResponse response = new UploadReportResponse(
                                        snapshot.getId().toString(),
                                        normalizedCode,
                                        snapshot.getSnapshotDate(),
                                        snapshot.getTotalNetAssetValue(),
                                        snapshot.getStockRatio(),
                                        snapshot.getViopCashRatio(),
                                        snapshot.getIsSuspicious(),
                                        "KAP URL'sindeki PDF başarıyla indirildi, ayrıştırıldı ve yeni portföy snapshot'ı devreye alındı.");

                        return ResponseEntity.ok(response);

                } catch (Exception e) {
                        log.error("[AdminController] KAP URL indirme ve ayrıştırma hatası: {}", e.getMessage(), e);
                        throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                                        "KAP PDF işlenemedi: " + e.getMessage());
                }
        }

        /**
         * 1.C UÇ NOKTA: KAP'tan Otomatik Rapor Keşfet ve İndir (T3.1 & T3.2 Tam
         * Otomasyon)
         * -------------------------------------------------------------------------------------
         * Kullanıcıdan dosya veya link istemez. Sadece fon kodunu alır, KAP bildirim
         * servisini tarar, en son "Portföy Dağılım Raporu"nu ve ekli PDF'i bizzat
         * bulur,
         * indirir ve veritabanına yeni Snapshot olarak kaydeder.
         *
         * @param code Fon kodu (Örn: "DFI", "THF", "TTE")
         * @return Başarıyla içe aktarılan snapshot özeti
         */
        @PostMapping(value = "/funds/{code}/discover-report")
        @Operation(summary = "KAP'tan Otomatik Rapor Keşfet ve İndir (T3.1 & T3.2)", description = "Fon kodunu kullanarak KAP'ta arama yapar, en son 'Portföy Dağılım Raporu'nu bulur, PDF'i indirir ve sisteme işler.")
        public ResponseEntity<UploadReportResponse> autoDiscoverReport(
                        @Parameter(description = "Fon kodu (Örn: DFI, THF, TTE)", example = "DFI") @PathVariable String code) {

                String normalizedCode = code.trim().toUpperCase();
                log.info("[AdminController] 🤖 Otomatik KAP keşfi tetiklendi: Fon='{}'", normalizedCode);

                try {
                        Fund fund = fundRepository.findByCode(normalizedCode)
                                        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                                                        "Fon bulunamadı: " + normalizedCode));

                        KapPdfDto pdfDto = kapClient.fetchPdf(normalizedCode, fund.getManager(), null, fund.getTitle());
                        if (pdfDto == null || pdfDto.isEmpty()) {
                                throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                                                "KAP'ta ve arşivde '" + normalizedCode
                                                                + "' fonu için dağılım raporu bulunamadı.");
                        }

                        PdfImportService.PdfImportResult importResult = pdfImportService.importPdfReportWithResult(
                                        pdfDto.getContent(),
                                        normalizedCode,
                                        pdfDto.getSource(),
                                        pdfDto.getSha256Hash(),
                                        LocalDateTime.now());
                        FundSnapshot snapshot = importResult.snapshot();
                        boolean isAlreadyImported = importResult.alreadyImported();

                        String message;
                        if (isAlreadyImported) {
                                message = "KAP ve sistem arşivi denetlendi: '" + normalizedCode
                                                + "' için en güncel resmi rapor ("
                                                + snapshot.getSnapshotDate()
                                                + ") zaten sistemde kayıtlı ve portföy ağırlıkları aktif (SHA-256 doğrulandı, mükerrer aktarım atlandı).";
                        } else {
                                message = "KAP'tan yeni dönem dağılım raporu (" + snapshot.getSnapshotDate()
                                                + ") başarıyla indirilip içe aktarıldı (Kaynak: " + pdfDto.getSource()
                                                + ").";
                        }

                        UploadReportResponse response = new UploadReportResponse(
                                        snapshot.getId().toString(),
                                        normalizedCode,
                                        snapshot.getSnapshotDate(),
                                        snapshot.getTotalNetAssetValue(),
                                        snapshot.getStockRatio(),
                                        snapshot.getViopCashRatio(),
                                        snapshot.getIsSuspicious(),
                                        message,
                                        isAlreadyImported);

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
         * 1.D UÇ NOKTA: KAP'tan Otomatik PDF Çek ve Kaydetmeden Önizle (Dry-Run KAP
         * Preview)
         * -------------------------------------------------------------------------------------
         * Kullanıcı dosya yüklemek zorunda kalmaz; fon kodunu verir. Sistem KAP'taki en
         * son
         * dağılım raporunu ve ekli PDF'i canlı olarak çeker, ayrıştırır, varlık
         * kırılımını
         * ve doğrulama durumunu önizleme olarak döner. Veritabanına hiçbir şey
         * KAYDETMEZ.
         *
         * @param code Fon kodu (Örn: "TI2", "THF", "TTE", "DFI")
         * @return Zengin varlık kırılımı, seçilen strateji ve doğrulama özeti
         */
        @PostMapping(value = "/funds/{code}/preview-kap")
        @Operation(summary = "KAP'tan En Son PDF'i İndir ve Önizle (Dry-Run)", description = "Fon kodunu kullanarak KAP'ta arama yapar, en son dağılım raporu PDF'ini indirir ve veritabanına kaydetmeden zengin varlık sınıfı kırılımıyla önizleme olarak sunar.")
        public ResponseEntity<PdfPreviewResponse> previewKapReport(
                        @Parameter(description = "Fon kodu (Örn: TI2, THF, TTE, DFI)", example = "TI2") @PathVariable String code) {

                String normalizedCode = code.trim().toUpperCase();
                log.info("[AdminController] 🤖 KAP PDF önizleme (Dry-Run) isteği alındı: Fon='{}'", normalizedCode);

                try {
                        Optional<Fund> fundOpt = fundRepository.findByCode(normalizedCode);
                        String manager = fundOpt.map(Fund::getManager).orElse(null);
                        String title = fundOpt.map(Fund::getTitle).orElse(null);

                        // Fon henüz veritabanına kaydedilmemişse TEFAS üzerinden resmi künyesini çek
                        if (title == null || manager == null) {
                                try {
                                        Optional<TefasFundProfileDto> profileOpt = tefasClient.fetchFundProfile(normalizedCode);
                                        if (profileOpt.isPresent()) {
                                                TefasFundProfileDto profile = profileOpt.get();
                                                if (title == null) title = profile.getTitle();
                                                if (manager == null) manager = profile.getManager();
                                                log.info("[AdminController] TEFAS üzerinden künye otomatik tamamlandı: Fon='{}', Unvan='{}', Yönetici='{}'",
                                                                normalizedCode, title, manager);
                                        }
                                } catch (Exception e) {
                                        log.warn("[AdminController] TEFAS künye tamamlama uyarısı: Fon='{}' -> {}", normalizedCode, e.getMessage());
                                }
                        }

                        KapPdfDto pdfDto = kapClient.fetchPdf(normalizedCode, manager, null, title);
                        if (pdfDto == null || pdfDto.isEmpty()) {
                                throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                                                "KAP'ta '" + normalizedCode
                                                                + "' fonu için dağılım raporu PDF'i bulunamadı.");
                        }

                        PdfPreviewResponse previewResponse = pdfImportService.previewPdfReport(pdfDto.getContent(),
                                        normalizedCode);
                        return ResponseEntity.ok(previewResponse);

                } catch (ResponseStatusException rse) {
                        throw rse;
                } catch (Exception e) {
                        log.error("[AdminController] KAP PDF önizleme hatası: Fon='{}' -> {}", normalizedCode,
                                        e.getMessage(), e);
                        throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                                        "KAP PDF önizleme başarısız: " + e.getMessage());
                }
        }

        /**
         * 1.D UÇ NOKTA: Fonun Aktif ve Geçmiş KAP Rapor Dosyaları Dökümü
         * -------------------------------------------------------------------------------------
         * Yönetici panelinde fonun şu an hesaplama motorunda aktif olan raporunu,
         * daha önce kullanılmış geçmiş dönem raporlarını ve diskteki örnek/arşiv
         * dosyalarını döner.
         */
        @GetMapping("/funds/{code}/reports")
        @Operation(summary = "Fonun Aktif ve Geçmiş Rapor Dosyaları Arşivi", description = "Şu anda kullanılan aktif raporu, geçmiş raporları ve diskteki arşivlenmiş PDF dosyalarını listeler.")
        public ResponseEntity<FundReportHistoryResponse> getFundReportHistory(
                        @Parameter(description = "Fon kodu (Örn: TTE, THF)", example = "TTE") @PathVariable String code) {

                String normalizedCode = code.trim().toUpperCase();
                Fund fund = fundRepository.findByCode(normalizedCode)
                                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                                                "Fon bulunamadı: " + normalizedCode));

                // 1. Veritabanındaki tüm snapshot'ları çek (en yeni en başta)
                List<FundSnapshot> snapshots = fundSnapshotRepository.findAllByFundCodeOrderByDateDesc(normalizedCode);

                FundReportItemDto activeReport = null;
                List<FundReportItemDto> previousReports = new ArrayList<>();

                for (int i = 0; i < snapshots.size(); i++) {
                        FundSnapshot snap = snapshots.get(i);
                        FundReport report = snap.getReport();
                        boolean isActive = (i == 0);

                        String fileName = report != null && report.getPdfUrl() != null ? report.getPdfUrl()
                                        : (normalizedCode + "_" + snap.getSnapshotDate() + ".pdf");
                        LocalDateTime dlAt = report != null && report.getCreatedAt() != null ? report.getCreatedAt()
                                        : snap.getCreatedAt();
                        String sha = report != null ? report.getPdfSha256() : null;
                        String source = report != null && report.getPdfUrl() != null ? report.getPdfUrl()
                                        : "DB_SNAPSHOT";
                        String status = report != null && report.getStatus() != null ? report.getStatus().name()
                                        : "PARSED";

                        FundReportItemDto item = new FundReportItemDto(
                                        snap.getId(),
                                        report != null ? report.getId() : null,
                                        fileName,
                                        snap.getSnapshotDate(),
                                        dlAt,
                                        source,
                                        sha,
                                        snap.getTotalNetAssetValue(),
                                        snap.getStockRatio(),
                                        snap.getViopCashRatio(),
                                        status,
                                        isActive);

                        if (isActive) {
                                activeReport = item;
                        } else {
                                previousReports.add(item);
                        }
                }

                // 2. Diskteki (samples/ klasörü) tüm dosyaları bul
                List<DiskReportFileDto> diskFiles = new ArrayList<>();
                List<Path> matchingPaths = kapClient.findAllSamplePdfFiles(normalizedCode);
                for (Path p : matchingPaths) {
                        try {
                                String fname = p.getFileName().toString();
                                long size = Files.size(p);
                                BasicFileAttributes attrs = Files.readAttributes(p, BasicFileAttributes.class);
                                String modTime = attrs.lastModifiedTime().toString();
                                boolean isCurrent = activeReport != null && activeReport.fileName() != null &&
                                                (activeReport.fileName().contains(fname)
                                                                || (activeReport.source() != null && activeReport
                                                                                .source().contains(fname)));

                                diskFiles.add(new DiskReportFileDto(
                                                fname,
                                                size,
                                                size / 1024.0,
                                                modTime,
                                                isCurrent));
                        } catch (Exception ignored) {
                        }
                }

                FundReportHistoryResponse response = new FundReportHistoryResponse(
                                fund.getCode(),
                                fund.getTitle(),
                                activeReport,
                                previousReports,
                                diskFiles);

                return ResponseEntity.ok(response);
        }

        /**
         * 1.E UÇ NOKTA: Diskteki Arşivlenmiş Bir PDF Dosyasını İçe Aktar ve Devreye Al
         */
        @PostMapping("/funds/{code}/load-disk-file")
        @Operation(summary = "Diskteki Örnek/Arşiv Dosyasını Yükle ve Devreye Al", description = "samples/ klasöründe bulunan bir PDF'i sisteme aktararak aktif snapshot yapar.")
        public ResponseEntity<UploadReportResponse> loadDiskFile(
                        @PathVariable String code,
                        @RequestParam String fileName) {

                String normalizedCode = code.trim().toUpperCase();
                if (fileName == null || fileName.isBlank() || fileName.contains("..") || fileName.contains("/")
                                || fileName.contains("\\")) {
                        throw new IllegalArgumentException("Geçersiz veya güvensiz dosya adı formatı: " + fileName);
                }
                log.info("[AdminController] Diskteki dosya devreye alınıyor: Fon='{}', Dosya='{}'", normalizedCode,
                                fileName);

                try {
                        KapPdfDto pdfDto = kapClient.loadSpecificSampleFile(normalizedCode, fileName);
                        FundSnapshot snapshot = pdfImportService.importPdfReport(
                                        pdfDto.getContent(),
                                        normalizedCode,
                                        "DISK_FILE:" + fileName,
                                        pdfDto.getSha256Hash(),
                                        LocalDateTime.now());

                        UploadReportResponse response = new UploadReportResponse(
                                        snapshot.getId().toString(),
                                        normalizedCode,
                                        snapshot.getSnapshotDate(),
                                        snapshot.getTotalNetAssetValue(),
                                        snapshot.getStockRatio(),
                                        snapshot.getViopCashRatio(),
                                        snapshot.getIsSuspicious(),
                                        "Diskteki dosya ('" + fileName
                                                        + "') başarıyla devreye alındı ve yeni portföy snapshot'ı oluşturuldu.");

                        return ResponseEntity.ok(response);
                } catch (Exception e) {
                        log.error("[AdminController] Disk dosyası yükleme hatası: {}", e.getMessage(), e);
                        throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                                        "Dosya işlenemedi: " + e.getMessage());
                }
        }

        /**
         * 2. UÇ NOKTA: Geriye Dönük Doğruluk ve Simülasyon (Backtest) Raporu
         * -------------------------------------------------------------------------------------
         * Belirtilen fon için geçmiş seans günlerinin TEFAS verileriyle model
         * tahminlerini
         * kıyaslar ve ekonometrik başarı karnesini (MAE, RMSE, Yönsel Başarı) döner.
         *
         * @param fundCode  Fon kodu (Örn: "THF", "TTE")
         * @param startDate Opsiyonel başlangıç tarihi (Örn: 2026-08-01)
         * @param endDate   Opsiyonel bitiş tarihi (Örn: 2026-09-30)
         * @return MAE, RMSE ve günlük karşılaştırma dökümünü içeren Backtest karnesi
         */
        @GetMapping("/backtest/{fundCode}")
        @Operation(summary = "Ekonometrik Backtest Simülasyonu Çalıştır", description = "Fonun geçmiş seans günlerinde modelin TEFAS ile ne kadar uyumlu çalıştığını (MAE, RMSE, Başarı Oranı) hesaplar.")
        public ResponseEntity<BacktestReportDto> runBacktest(
                        @Parameter(description = "Fon kodu (Örn: THF, TTE)", example = "THF") @PathVariable String fundCode,
                        @Parameter(description = "Başlangıç Tarihi (YYYY-MM-DD)", example = "2026-08-01") @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
                        @Parameter(description = "Bitiş Tarihi (YYYY-MM-DD)", example = "2026-09-30") @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate) {

                String normalizedCode = fundCode.trim().toUpperCase();
                log.info("[AdminController] Fon '{}' için backtest isteği alındı (Aralık: {} - {})",
                                normalizedCode, startDate, endDate);

                BacktestReportDto report = backtestService.getOrRunBacktest(normalizedCode, startDate, endDate, false);
                return ResponseEntity.ok(report);
        }

        /**
         * 2.1. UÇ NOKTA: Parametreli Backtest Simülasyonu Çalıştır (POST)
         * -------------------------------------------------------------------------------------
         * İsteğe bağlı tarih aralığı (startDate - endDate) ile fon için geçmiş doğruluk
         * ve ağırlık kayması
         * (Dynamic Weight Drift) simülasyonunu başlatır.
         *
         * @param fundCode Fon kodu (Örn: "THF", "TTE", "KHA")
         * @param request  Başlangıç ve bitiş tarihlerini içeren gövde nesnesi
         *                 (opsiyonel)
         * @return MAE, RMSE, kümülatif getiri ve geçiş loglarını içeren
         *         zenginleştirilmiş karne
         */
        @PostMapping("/backtest/{fundCode}/run")
        @Operation(summary = "Parametreli Ekonometrik Backtest Simülasyonu Çalıştır (POST)", description = "İsteğe bağlı tarih aralığı ile fon için geçmiş doğruluk ve ağırlık kayması (drift) simülasyonunu tetikler.")
        public ResponseEntity<BacktestReportDto> runBacktestPost(
                        @Parameter(description = "Hedef fon kodu (Örn: THF, TTE)", example = "THF") @PathVariable String fundCode,
                        @RequestBody(required = false) com.fonmap.application.service.backtest.dto.BacktestRunRequest request) {

                String normalizedCode = fundCode.trim().toUpperCase();
                LocalDate start = request != null ? request.startDate() : null;
                LocalDate end = request != null ? request.endDate() : null;

                if (start != null && end != null && start.isAfter(end)) {
                        throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                                        "Başlangıç tarihi bitiş tarihinden sonra olamaz!");
                }

                log.info("[AdminController] POST /backtest/{}/run isteği alındı (Aralık: {} - {})",
                                normalizedCode, start, end);

                BacktestReportDto report = backtestService.getOrRunBacktest(normalizedCode, start, end, true);
                return ResponseEntity.ok(report);
        }

        /**
         * 2.B UÇ NOKTA: Tüm Aktif Fonların Backtest'ini Toplu Çalıştır (Batch Run All)
         * -------------------------------------------------------------------------------------
         * Sistemdeki tüm aktif yatırım fonlarını sırayla simüle eder. İlk fon ortak
         * hisseleri
         * Yahoo'dan çekip Redis'e attığı için, sonraki tüm fonlar paylaşımlı
         * önbellekten
         * 0 ms'de faydalanır ve saniyeler içinde tüm fon karneleri güncellenir.
         *
         * @return Tüm fonların konsolide simülasyon ve başarı özeti
         */
        @PostMapping("/backtest/run-all")
        @Operation(summary = "Tüm Fonların Backtest'ini Toplu Çalıştır (Batch Run All)", description = "Sistemdeki tüm aktif fonlar için sırayla ekonometrik simülasyonu tetikler ve önbelleği tazeler.")
        public ResponseEntity<com.fonmap.application.service.backtest.dto.BatchBacktestResultDto> runAllBacktests() {
                log.info("[AdminController] 🚀 POST /api/v1/admin/backtest/run-all toplu simülasyon isteği alındı.");
                com.fonmap.application.service.backtest.dto.BatchBacktestResultDto result = backtestService
                                .runAllBacktests(true);
                return ResponseEntity.ok(result);
        }

        /**
         * 3. UÇ NOKTA: Gece TEFAS Mutabakatını Manuel Tetikleme
         * -------------------------------------------------------------------------------------
         * Belirtilen bir seans günü için tüm aktif fonların gece 23:00 TEFAS resmi
         * mutabakatını
         * anında çalıştırır ve sonuçları döner.
         *
         * @param targetDate Mutabakat tarihi (Örn: 2026-09-03)
         * @return Tüm aktif fonların mutabakat sonuç listesi
         */
        @PostMapping("/reconcile/{targetDate}")
        @Operation(summary = "TEFAS Gece Mutabakatını Manuel Çalıştır", description = "Belirtilen gün için TEFAS resmi kapanış fiyatları ile model tahminini karşılaştırıp hata skorlarını hesaplar.")
        public ResponseEntity<List<ReconciliationDto>> triggerReconciliation(
                        @Parameter(description = "Mutabakat yapılacak tarih (YYYY-MM-DD)", example = "2026-09-03") @PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate targetDate) {

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
        @Operation(summary = "Onay Bekleyen Hisse Eşleşmeleri", description = "PDF'ten gelen ve sözlükte henüz onaylanmamış enstrüman unvanlarını listeler.")
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
                                                a.getCreatedAt()))
                                .toList();

                return ResponseEntity.ok(response);
        }

        /**
         * 5. UÇ NOKTA: Hisse Eşleşmesini Onaylama
         * -------------------------------------------------------------------------------------
         * Admin inceledikten sonra eşleşmenin doğru olduğunu onaylar.
         */
        @PostMapping("/aliases/{id}/approve")
        @Operation(summary = "Hisse Eşleşmesini Onayla", description = "Adminin sözlükteki bir hisse eşlemesini geçerli olarak işaretlemesini sağlar.")
        public ResponseEntity<String> approveAlias(@PathVariable Long id) {
                log.info("[AdminController] Alias onayı istendi: ID={}", id);

                InstrumentAlias alias = instrumentAliasRepository.findById(id)
                                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                                                "Alias kaydı bulunamadı: " + id));

                alias.setIsApproved(true);
                instrumentAliasRepository.save(alias);

                return ResponseEntity.ok("Alias (ID: " + id + ") başarıyla onaylandı.");
        }

        /**
         * 5.B UÇ NOKTA: TEFAS'tan Otomatik Fon Künyesi ve Kurucu Sorgula
         * -------------------------------------------------------------------------------------
         * Kullanıcı fon eklerken sadece 3 harfli kodu girer (Örn: "BDY", "TI2").
         * Sistem TEFAS'a gidip resmi fon unvanını, kurucu portföy yönetim şirketini
         * ve kategorisini anında döner.
         */
        @GetMapping({"/funds/lookup/{code}", "/funds/tefas-sniff/{code}"})
        @Operation(summary = "TEFAS'tan Fon Künyesi Sorgula", description = "Verilen 3-4 karakterli fon kodu için TEFAS'tan resmi unvanı, kurucu şirketi ve son fiyatı otomatik çeker.")
        public ResponseEntity<FundLookupResponse> lookupFundProfile(
                        @Parameter(description = "Fon kodu (Örn: BDY, TI2, KHA)", example = "BDY") @PathVariable String code) {

                String normalizedCode = code.trim().toUpperCase();
                log.info("[AdminController] TEFAS fon künyesi sorgulanıyor: '{}'", normalizedCode);

                Optional<TefasFundProfileDto> profileOpt = tefasClient.fetchFundProfile(normalizedCode);
                if (profileOpt.isPresent()) {
                        TefasFundProfileDto p = profileOpt.get();
                        return ResponseEntity.ok(new FundLookupResponse(
                                p.getFundCode(),
                                p.getTitle(),
                                p.getManager(),
                                p.getCategory(),
                                p.getLatestPrice(),
                                p.getAnnualFeeRatio(),
                                true
                        ));
                }

                return ResponseEntity.ok(new FundLookupResponse(
                        normalizedCode,
                        normalizedCode + " Yatırım Fonu",
                        "Bilinmeyen Portföy Yönetimi A.Ş.",
                        "Hisse Senedi Fonu",
                        null,
                        new BigDecimal("0.0200"),
                        false
                ));
        }

        /**
         * 6. UÇ NOKTA: Yeni Fon Tanımlama (7 Fon Dışında Dinamik Ekleme)
         * -------------------------------------------------------------------------------------
         * Sisteme yeni bir fon kodu ve kurucu şirket kaydı açar.
         */
        @PostMapping("/funds")
        @Operation(summary = "Yeni Takip Fonu Ekle & Otomatik KAP Dağılımını İndir", description = "Sisteme yeni bir fon tanımlar; hemen ardından KAP'tan en son portföy dağılım raporunu bizzat arayıp indirir, ayrıştırır ve sisteme yeni snapshot olarak kazandırır.")
        public ResponseEntity<CreateFundResponse> createFund(
                        @RequestBody CreateFundRequest request) {

                if (request == null || request.code() == null || request.code().isBlank()) {
                        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Fon kodu boş olamaz!");
                }

                String normalizedCode = request.code().trim().toUpperCase();
                if (fundRepository.findByCode(normalizedCode).isPresent()) {
                        throw new ResponseStatusException(HttpStatus.CONFLICT,
                                        "Bu fon kodu zaten kayıtlı: " + normalizedCode);
                }

                log.info("[AdminController] Yeni fon kaydediliyor: '{}' - '{}'", normalizedCode, request.title());

                // 1. ADIM: Fon Başlığı ve Kurucu Şirketi Belirle (Akıllı TEFAS Zenginleştirmesi)
                String finalTitle = request.title() != null && !request.title().isBlank()
                                ? request.title().trim() : null;
                String finalManager = request.manager() != null && !request.manager().isBlank()
                                && !request.manager().equalsIgnoreCase("Otomatik")
                                && !request.manager().toLowerCase().contains("bilinmeyen")
                                && !request.manager().toLowerCase().contains("diğer")
                                ? request.manager().trim() : null;

                if (finalTitle == null || finalManager == null) {
                        try {
                                Optional<TefasFundProfileDto> profileOpt = tefasClient.fetchFundProfile(normalizedCode);
                                if (profileOpt.isPresent()) {
                                        TefasFundProfileDto p = profileOpt.get();
                                        if (finalTitle == null) finalTitle = p.getTitle();
                                        if (finalManager == null) finalManager = p.getManager();
                                }
                        } catch (Exception e) {
                                log.warn("[AdminController] TEFAS otomatik künye zenginleştirme uyarısı: Fon='{}' -> {}", normalizedCode, e.getMessage());
                        }
                }

                if (finalTitle == null) finalTitle = normalizedCode + " Yatırım Fonu";
                if (finalManager == null) finalManager = "Bilinmeyen Yönetici";

                BigDecimal rawFee = request.annualFeeRatio() != null
                                ? validateFeeRatio(request.annualFeeRatio())
                                : new BigDecimal("0.0200");

                com.fonmap.domain.entity.Fund fund = com.fonmap.domain.entity.Fund.builder()
                                .code(normalizedCode)
                                .title(finalTitle)
                                .manager(finalManager)
                                .annualFeeRatio(rawFee)
                                .isActive(true)
                                .displayOrder(request.displayOrder() != null ? request.displayOrder() : 99)
                                .build();

                com.fonmap.domain.entity.Fund saved = fundRepository.save(fund);

                boolean discovered = false;
                String message = "Fon başarıyla sisteme kaydedildi.";
                LocalDate reportDate = null;
                BigDecimal stockRatio = null;
                BigDecimal totalNetAssetValue = null;

                // 2. ADIM: İstenmişse KAP'tan En Güncel Dağılım Raporunu Anında Otomatik Keşfet
                // ve İndir (Varsayılan: true)
                boolean shouldAutoDiscover = request.autoDiscover() == null
                                || Boolean.TRUE.equals(request.autoDiscover());
                if (shouldAutoDiscover) {
                        try {
                                log.info("[KAP_DISCOVERY] [STATUS=TRIGGERED] [REASON=ADMIN_CREATE_FUND] 🤖 Yeni açılan fon='{}' için KAP dağılım raporu taranıyor...",
                                                normalizedCode);
                                KapPdfDto pdfDto = kapClient.fetchPdf(normalizedCode, saved.getManager(), null, saved.getTitle());
                                if (pdfDto != null && !pdfDto.isEmpty()) {
                                        FundSnapshot snapshot = pdfImportService.importPdfReport(
                                                        pdfDto.getContent(),
                                                        normalizedCode,
                                                        pdfDto.getSource(),
                                                        pdfDto.getSha256Hash(),
                                                        LocalDateTime.now());
                                        discovered = true;
                                        reportDate = snapshot.getSnapshotDate();
                                        stockRatio = snapshot.getStockRatio();
                                        totalNetAssetValue = snapshot.getTotalNetAssetValue();
                                        message = "Fon sisteme eklendi ve KAP'tan son dağılım raporu (" + reportDate
                                                        + ") indirilip başarıyla ayrıştırıldı.";
                                        log.info("[KAP_DISCOVERY] [STATUS=SUCCESS] [REASON=ADMIN_CREATE_FUND] 🚀 Fon='{}' KAP raporu sisteme işlendi (Tarih={}, TNV={}).",
                                                        normalizedCode, reportDate, totalNetAssetValue);
                                } else {
                                        message = "Fon kaydedildi ancak KAP'ta bu fon için henüz yayınlanmış portföy dağılım raporu bulunamadı.";
                                        log.warn("[KAP_DISCOVERY] [STATUS=SKIPPED] [REASON=NO_REPORT_FOUND] Fon='{}' KAP'ta PDF bulunamadı.",
                                                        normalizedCode);
                                }
                        } catch (Exception e) {
                                log.error("[KAP_DISCOVERY] [STATUS=FAILED] [REASON=ADMIN_CREATE_FUND] Fon='{}' otomatik KAP keşif hatası: {}",
                                                normalizedCode, e.getMessage(), e);
                                message = "Fon sisteme kaydedildi ancak KAP raporu indirilirken hata oluştu: "
                                                + e.getMessage();
                        }
                }

                CreateFundResponse response = new CreateFundResponse(
                                saved.getId(),
                                saved.getCode(),
                                saved.getTitle(),
                                saved.getManager(),
                                saved.getAnnualFeeRatio(),
                                saved.getIsActive(),
                                saved.getDisplayOrder(),
                                discovered,
                                message,
                                reportDate,
                                stockRatio,
                                totalNetAssetValue);

                return ResponseEntity.status(HttpStatus.CREATED).body(response);
        }

        /**
         * 7. UÇ NOKTA: Fon Bilgilerini Güncelle (PUT /funds/{code})
         * -------------------------------------------------------------------------------------
         * Sistemde kayıtlı bir fonun unvanını, yöneticisini, yıllık gider oranını veya
         * aktiflik durumunu günceller.
         */
        @PutMapping("/funds/{code}")
        @Operation(summary = "Fon Bilgilerini Güncelle", description = "Sistemde kayıtlı bir fonun unvanını, yöneticisini, yıllık gider oranını veya aktiflik durumunu günceller.")
        @Transactional
        public ResponseEntity<CreateFundResponse> updateFund(
                        @PathVariable String code,
                        @RequestBody UpdateFundRequest request) {

                String normalizedCode = code.trim().toUpperCase();
                Fund fund = fundRepository.findByCode(normalizedCode)
                                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                                                "Fon bulunamadı: " + normalizedCode));

                if (request != null) {
                        if (request.title() != null && !request.title().isBlank()) {
                                fund.setTitle(request.title().trim());
                        }
                        if (request.manager() != null && !request.manager().isBlank()) {
                                fund.setManager(request.manager().trim());
                        }
                        if (request.annualFeeRatio() != null) {
                                fund.setAnnualFeeRatio(validateFeeRatio(request.annualFeeRatio()));
                        }
                        if (request.isActive() != null) {
                                fund.setIsActive(request.isActive());
                        }
                        if (request.displayOrder() != null) {
                                fund.setDisplayOrder(request.displayOrder());
                        }
                }

                Fund updated = fundRepository.save(fund);
                log.info("[AdminController] Fon güncellendi: '{}'", normalizedCode);

                return ResponseEntity.ok(new CreateFundResponse(
                                updated.getId(),
                                updated.getCode(),
                                updated.getTitle(),
                                updated.getManager(),
                                updated.getAnnualFeeRatio(),
                                updated.getIsActive(),
                                updated.getDisplayOrder(),
                                false,
                                "Fon bilgileri başarıyla güncellendi.",
                                null, null, null));
        }

        /**
         * 8. UÇ NOKTA: Fonu Arşivle / Takipten Çıkar (DELETE /funds/{code})
         * -------------------------------------------------------------------------------------
         * Fonu tamamen silmek yerine güvenle pasife alır (isActive = false), böylece
         * tarihsel veriler korunur.
         */
        @DeleteMapping("/funds/{code}")
        @Operation(summary = "Fonu Arşivle / Takipten Çıkar", description = "Fonu silmek yerine güvenle pasife alır (isActive = false), böylece tarihsel veriler korunurken fon vitrinden kaldırılır.")
        @Transactional
        public ResponseEntity<Map<String, Object>> deleteFund(@PathVariable String code) {
                String normalizedCode = code.trim().toUpperCase();
                Fund fund = fundRepository.findByCode(normalizedCode)
                                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                                                "Fon bulunamadı: " + normalizedCode));

                fund.setIsActive(false);
                fundRepository.save(fund);
                // Pasife alınan fonun eski backtest önbelleği vitrinde kalmasın.
                backtestCacheService.evict(normalizedCode);
                log.info("[AdminController] Fon pasife alındı (arşivlendi): '{}'", normalizedCode);

                return ResponseEntity.ok(Map.of(
                                "success", true,
                                "fundCode", normalizedCode,
                                "message", "Fon başarıyla pasife alındı (arşivlendi)."));
        }

        /**
         * 9. UÇ NOKTA: Hatalı Raporu ve Snapshot'ı Sil (DELETE
         * /funds/{code}/reports/{reportId})
         * -------------------------------------------------------------------------------------
         * Yanlış yüklenen veya hasarlı bir PDF raporunu, ilişkili portföy snapshot'ını
         * ve varlık kayıtlarını sistemden temizler.
         */
        @DeleteMapping("/funds/{code}/reports/{reportId}")
        @Operation(summary = "Hatalı Portföy Raporunu ve Snapshot'ı Sil", description = "Yanlış yüklenen veya hasarlı bir PDF raporunu, ilişkili portföy snapshot'ını ve varlık kayıtlarını sistemden temizler.")
        @Transactional
        public ResponseEntity<Map<String, Object>> deleteReport(
                        @PathVariable String code,
                        @PathVariable UUID reportId) {

                String normalizedCode = code.trim().toUpperCase();
                FundReport report = fundReportRepository.findById(reportId)
                                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                                                "Rapor kaydı bulunamadı: " + reportId));

                if (!report.getFund().getCode().equalsIgnoreCase(normalizedCode)) {
                        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Rapor fon kodu ile uyuşmuyor!");
                }

                // İlişkili Snapshot, Canlı Tahminler (EstimateRun) ve Holding kayıtlarını
                // temizle
                Optional<FundSnapshot> snapshotOpt = fundSnapshotRepository.findByReportId(reportId);
                if (snapshotOpt.isPresent()) {
                        FundSnapshot snapshot = snapshotOpt.get();
                        try {
                                estimateRunRepository.deleteBySnapshotId(snapshot.getId());
                        } catch (Exception e) {
                                log.warn("[AdminController] Snapshot={} canlı tahmin kayıtları silinirken uyarı: {}",
                                                snapshot.getId(), e.getMessage());
                        }
                        List<Holding> holdings = holdingRepository.findBySnapshotIdWithInstrument(snapshot.getId());
                        if (!holdings.isEmpty()) {
                                holdingRepository.deleteAll(holdings);
                        }
                        fundSnapshotRepository.delete(snapshot);
                }

                fundReportRepository.delete(report);

                // Önbelleği temizle
                backtestCacheService.evict(normalizedCode);

                log.info("[AdminController] Rapor başarıyla silindi: Fon='{}', ReportId={}", normalizedCode, reportId);

                return ResponseEntity.ok(Map.of(
                                "success", true,
                                "fundCode", normalizedCode,
                                "reportId", reportId.toString(),
                                "message", "Rapor ve ilişkili portföy snapshot'ı başarıyla silindi."));
        }

        public record UpdateFundRequest(
                        String title,
                        String manager,
                        /** ORAN (0.0–1.0); örn. %2,5 = 0.025. Aralık dışı değer HTTP 400 döner. */
                        BigDecimal annualFeeRatio,
                        Boolean isActive,
                        Integer displayOrder) {
        }

        public record CreateFundRequest(
                        String code,
                        String title,
                        String manager,
                        BigDecimal annualFeeRatio,
                        Integer displayOrder,
                        Boolean autoDiscover) {
        }

        public record CreateFundResponse(
                        java.util.UUID id,
                        String code,
                        String title,
                        String manager,
                        BigDecimal annualFeeRatio,
                        Boolean isActive,
                        Integer displayOrder,
                        Boolean reportDiscovered,
                        String message,
                        LocalDate reportDate,
                        BigDecimal stockRatio,
                        BigDecimal totalNetAssetValue) {
        }

        public record FundLookupResponse(
                        String fundCode,
                        String title,
                        String manager,
                        String category,
                        BigDecimal latestPrice,
                        BigDecimal defaultFeeRatio,
                        Boolean found) {
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
                        String message,
                        Boolean alreadyImported) {
                public UploadReportResponse(
                                String snapshotId,
                                String fundCode,
                                LocalDate snapshotDate,
                                BigDecimal totalNetAssetValue,
                                BigDecimal stockRatio,
                                BigDecimal viopCashRatio,
                                Boolean isSuspicious,
                                String message) {
                        this(snapshotId, fundCode, snapshotDate, totalNetAssetValue, stockRatio, viopCashRatio,
                                        isSuspicious, message, false);
                }
        }

        public record PendingAliasResponse(
                        Long id,
                        String rawName,
                        String mappedTicker,
                        String mappedTitle,
                        BigDecimal confidence,
                        LocalDateTime createdAt) {
        }

        public record FetchUrlRequest(
                        String url) {
        }

        public record FundReportItemDto(
                        UUID snapshotId,
                        UUID reportId,
                        String fileName,
                        LocalDate reportDate,
                        LocalDateTime downloadedAt,
                        String source,
                        String sha256Hash,
                        BigDecimal totalNetAssetValue,
                        BigDecimal stockRatio,
                        BigDecimal viopCashRatio,
                        String status,
                        boolean isActive) {
        }

        public record DiskReportFileDto(
                        String fileName,
                        long sizeInBytes,
                        double sizeInKb,
                        String modifiedDate,
                        boolean isCurrentlyLoaded) {
        }

        public record FundReportHistoryResponse(
                        String fundCode,
                        String fundTitle,
                        FundReportItemDto activeReport,
                        List<FundReportItemDto> previousReports,
                        List<DiskReportFileDto> availableDiskFiles) {
        }
}
