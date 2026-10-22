package com.fonmap.application.service.pdf;

import com.fonmap.domain.entity.*;
import com.fonmap.domain.enums.AssetClass;
import com.fonmap.domain.enums.ReportStatus;
import com.fonmap.infrastructure.parser.dto.ParsedHoldingDto;
import com.fonmap.infrastructure.parser.dto.ParsedPortfolioReportDto;
import com.fonmap.infrastructure.parser.factory.PdfParserFactory;
import com.fonmap.infrastructure.parser.strategy.PdfParserStrategy;
import com.fonmap.infrastructure.repository.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import com.fonmap.application.service.calculation.ReturnCalculationService;
import com.fonmap.application.service.backtest.BacktestCacheService;

import com.fonmap.application.service.pdf.dto.PdfPreviewResponse;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;

/**
 * =========================================================================================
 * 🎯 PDF İÇERİ AKTARMA VE VERİTABANI KAYIT SERVİSİ (PdfImportService)
 * =========================================================================================
 * 
 * 📌 NE İŞE YARAR? (JUNIOR MÜHENDİS REHBERİ):
 * Bu servis, KAP'tan inen veya sisteme yüklenen ham bir portföy dağılım PDF
 * dosyasını
 * alıp, veritabanımızda kalıcı ve sorgulanabilir 'FundSnapshot' ve 'Holding'
 * kayıtlarına
 * dönüştüren ana köprüdür.
 * 
 * 🏗️ MİMARİDEKİ YERİ:
 * 1. KAP veya Kullanıcı -> Ham PDF (byte[])
 * 2. PdfParserFactory -> İlgili portföy şirketine uygun Stratejiyi (Tera, İş
 * Portföy vb.) seçer.
 * 3. PdfParserStrategy -> Ham metinleri 'ParsedPortfolioReportDto' kargosuna
 * çevirir.
 * 4. PdfImportService -> (BU SINIF) DTO'yu alır, 3 kademeli akıllı hisse
 * eşlemesi yapar,
 * finansal tutarlılık (sanity check) testlerini koşar ve
 * PostgreSQL veritabanına tek bir atomik işlemde (@Transactional) yazar.
 * 
 * 🔍 3 KADEMELİ AKILLI EŞLEME MOTORU (CASCADE MATCHING):
 * PDF'lerde bazen borsa kodu ("THYAO"), bazen ISIN ("TRATHYAO91M5"), bazen de
 * sadece uzun
 * şirket unvanı ("TÜRK HAVA YOLLARI A.O.") yazar. Servisimiz şu sırayla eşleme
 * yapar:
 * 1. Aşama: Ticker Doğrudan Eşleme (Örn: "THYAO" -> Instrument ID) — En hızlı
 * yol (%95).
 * 2. Aşama: ISIN Koduyla Eşleme (Örn: "TRATHYAO91M5" -> Instrument ID).
 * 3. Aşama: 'instrument_aliases' Sözlük Tablosu (Geçmişte admin onayından
 * geçmiş unvanlar).
 * - Hiçbirinde bulunamazsa: Yeni enstrüman/alias oluşturulup admin onay
 * havuzuna düşürülür.
 */
@Service
@Slf4j
public class PdfImportService {

    private final FundRepository fundRepository;
    private final FundReportRepository fundReportRepository;
    private final FundSnapshotRepository fundSnapshotRepository;
    private final HoldingRepository holdingRepository;
    private final InstrumentRepository instrumentRepository;
    private final InstrumentAliasRepository instrumentAliasRepository;
    private final PdfParserFactory pdfParserFactory;

    public PdfImportService(
            FundRepository fundRepository,
            FundReportRepository fundReportRepository,
            FundSnapshotRepository fundSnapshotRepository,
            HoldingRepository holdingRepository,
            InstrumentRepository instrumentRepository,
            InstrumentAliasRepository instrumentAliasRepository,
            PdfParserFactory pdfParserFactory) {
        this.fundRepository = fundRepository;
        this.fundReportRepository = fundReportRepository;
        this.fundSnapshotRepository = fundSnapshotRepository;
        this.holdingRepository = holdingRepository;
        this.instrumentRepository = instrumentRepository;
        this.instrumentAliasRepository = instrumentAliasRepository;
        this.pdfParserFactory = pdfParserFactory;
    }

    @Autowired
    @Lazy
    private ReturnCalculationService returnCalculationService;

    @Autowired(required = false)
    private BacktestCacheService backtestCacheService;

    /**
     * Ham PDF baytlarını ayrıştırıp veritabanına eksiksiz kaydeder.
     * 
     * @param pdfContent  KAP'tan indirilen PDF'in ham ikili (binary) verisi
     * @param fundCode    İşlem yapılacak fon kodu (Örn: "THF", "TTE", "DFI", "KHA")
     * @param pdfUrl      Varsa KAP indirme bağlantısı (denetim/audit amaçlı)
     * @param sha256      Varsa dosyanın SHA-256 parmak izi (idempotency amaçlı)
     * @param publishDate Raporun KAP'ta yayımlanma zamanı
     * @return Veritabanına kalıcı olarak kaydedilen FundSnapshot nesnesi
     * @throws Exception PDF okuma veya doğrulama hatası durumunda
     */
    public record PdfImportResult(FundSnapshot snapshot, boolean alreadyImported) {
    }

    /**
     * PDF içeriğini ve metaverilerini ayrıştırarak veritabanına aktarır.
     * Geriye dönük uyumluluk için FundSnapshot döner.
     */
    @Transactional
    public FundSnapshot importPdfReport(byte[] pdfContent,
            String fundCode,
            String pdfUrl,
            String sha256,
            LocalDateTime publishDate) throws Exception {
        return importPdfReportWithResult(pdfContent, fundCode, pdfUrl, sha256, publishDate).snapshot();
    }

    /**
     * PDF içeriğini içe aktarır ve dosyanın yeni mi içe aktarıldığını yoksa
     * sistemde zaten kayıtlı mı
     * olduğunu belirten detaylı PdfImportResult nesnesi döner.
     */
    @Transactional
    public PdfImportResult importPdfReportWithResult(byte[] pdfContent,
            String fundCode,
            String pdfUrl,
            String sha256,
            LocalDateTime publishDate) throws Exception {

        if (pdfContent == null || pdfContent.length == 0) {
            throw new IllegalArgumentException("İçe aktarılacak PDF içeriği boş olamaz: " + fundCode);
        }
        if (fundCode == null || fundCode.isBlank()) {
            throw new IllegalArgumentException("Fon kodu boş olamaz");
        }

        String normalizedCode = fundCode.trim().toUpperCase(Locale.ROOT);
        log.info("[PdfImportService] PDF içe aktarma başlatıldı: Fon='{}', Boyut={} bayt, SHA256={}",
                normalizedCode, pdfContent.length, sha256);

        // 1. ADIM: IDEMPOTENCY DENETİMİ (Mükerrer Kayıt Koruması)
        // Eğer bu dosya (aynı SHA-256) daha önce başarıyla kaydedildiyse, tekrar parse
        // edip
        // veritabanını şişirmemek için doğrudan mevcut snapshot'ı döneriz.
        if (sha256 != null && !sha256.isBlank()) {
            Optional<FundReport> existingReportOpt = fundReportRepository.findByPdfSha256(sha256);
            if (existingReportOpt.isPresent()) {
                FundReport existingReport = existingReportOpt.get();
                Optional<FundSnapshot> existingSnapshotOpt = fundSnapshotRepository
                        .findByReportId(existingReport.getId());
                if (existingSnapshotOpt.isPresent()) {
                    log.warn(
                            "[PdfImportService] Bu rapor (SHA256: {}) daha önce içe aktarılmış. Mevcut snapshot dönülüyor.",
                            sha256);
                    return new PdfImportResult(existingSnapshotOpt.get(), true);
                }
            }
        }

        // 2. ADIM: FONUN VERİTABANINDA MEVCUT OLDUĞUNU DOĞRULA
        Fund fund = fundRepository.findByCode(normalizedCode)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Sistemde '" + normalizedCode + "' kodlu bir fon bulunamadı!"));

        // 3. ADIM: FABRİKADAN UYGUN STRATEJİYİ AL VE KADEMELİ GÜVENLİ FALLBACK İLE
        // AYRIŞTIR
        ParsedPortfolioReportDto parsedReport = pdfParserFactory.parseWithFallback(pdfContent, normalizedCode,
                fund.getManager());

        if (parsedReport == null || parsedReport.getHoldings() == null || parsedReport.getHoldings().isEmpty()) {
            throw new IllegalStateException(
                    "PDF ayrıştırıldı ancak fon portföyünde hiçbir varlık bulunamadı: " + normalizedCode);
        }
        if (parsedReport.getReportDate() == null) {
            throw new IllegalArgumentException(
                    "PDF ayrıştırıldı ancak fon portföy rapor tarihi tespit edilemedi: " + normalizedCode);
        }

        // 4. ADIM: FİNANSAL TUTARLILIK KONTROLÜ (SANITY CHECK)
        // Portföydeki tüm varlıkların ağırlık toplamı hesaplanır (Kaldıraçsız fonlarda
        // ~%100 olmalıdır).
        boolean isSuspicious = performSanityCheck(parsedReport);

        // 5. ADIM: FUND REPORT (RAPOR DENETİM METAVERİSİ) OLUŞTUR VE KAYDET
        FundReport fundReport = fundReportRepository
                .findByFundIdAndReportDateWithFund(fund.getId(), parsedReport.getReportDate())
                .orElseGet(() -> FundReport.builder()
                        .fund(fund)
                        .reportDate(parsedReport.getReportDate())
                        .publishDate(publishDate != null ? publishDate : LocalDateTime.now())
                        .pdfUrl(pdfUrl)
                        .pdfSha256(sha256 != null ? sha256 : "LOCAL_" + UUID.randomUUID())
                        .status(ReportStatus.PARSED)
                        .build());

        fundReport.setStatus(ReportStatus.PARSED);
        if (pdfUrl != null) {
            fundReport.setPdfUrl(pdfUrl);
        }
        if (sha256 != null) {
            fundReport.setPdfSha256(sha256);
        }
        fundReport = fundReportRepository.save(fundReport);

        // 6. ADIM: FUND SNAPSHOT (PORTFÖY ÖZETİ) OLUŞTUR VE KAYDET
        // Önce aynı rapor için daha önceden kalan eski snapshot varsa onu temizle ya da
        // güncelle
        Optional<FundSnapshot> existingSnapshotOpt = fundSnapshotRepository.findByReportId(fundReport.getId());
        FundSnapshot snapshot;
        if (existingSnapshotOpt.isPresent()) {
            snapshot = existingSnapshotOpt.get();
            // Eski snapshot'a bağlı önceki holding kayıtlarını temizle
            List<Holding> oldHoldings = holdingRepository.findBySnapshotIdWithInstrument(snapshot.getId());
            if (!oldHoldings.isEmpty()) {
                holdingRepository.deleteAll(oldHoldings);
            }
        } else {
            snapshot = new FundSnapshot();
        }

        snapshot.setFund(fund);
        snapshot.setReport(fundReport);
        snapshot.setSnapshotDate(parsedReport.getReportDate());

        BigDecimal tnv = parsedReport.getTotalNetAssetValue();
        if (tnv == null || tnv.compareTo(BigDecimal.ZERO) <= 0) {
            BigDecimal sumTotalValue = parsedReport.getHoldings().stream()
                    .filter(h -> h.getTotalValue() != null)
                    .map(ParsedHoldingDto::getTotalValue)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            if (sumTotalValue.compareTo(BigDecimal.ZERO) > 0) {
                tnv = sumTotalValue;
            } else {
                tnv = BigDecimal.ZERO;
                isSuspicious = true;
                log.warn(
                        "[PdfImportService] Fon '{}' için TNV belirlenemedi; yapay değer atanmadı, snapshot şüpheli (isSuspicious=true) olarak işaretlendi.",
                        fund.getCode());
            }
        }
        snapshot.setTotalNetAssetValue(tnv);
        BigDecimal effectiveStockRatio = parsedReport.getStockRatio();
        if (effectiveStockRatio == null || effectiveStockRatio.compareTo(BigDecimal.ZERO) == 0) {
            BigDecimal equitySum = parsedReport.getHoldings().stream()
                    .filter(h -> (h.isEquity() || h.getAssetClassHint() == null
                            || h.getAssetClassHint() == AssetClass.EQUITY) && h.getWeightRatio() != null)
                    .map(ParsedHoldingDto::getWeightRatio)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            if (equitySum.compareTo(BigDecimal.ZERO) > 0) {
                effectiveStockRatio = equitySum;
            }
        }

        snapshot.setStockRatio(effectiveStockRatio != null
                ? effectiveStockRatio.setScale(4, RoundingMode.HALF_UP)
                : BigDecimal.ZERO);
        snapshot.setViopCashRatio(parsedReport.getViopCashRatio() != null
                ? parsedReport.getViopCashRatio().setScale(4, RoundingMode.HALF_UP)
                : BigDecimal.ZERO);
        snapshot.setIsSuspicious(isSuspicious);

        snapshot = fundSnapshotRepository.save(snapshot);

        // 7. ADIM: HER BİR ALT VARLIĞI EŞLE VE 'holdings' TABLOSUNA KAYDET
        List<Holding> holdingsToSave = new ArrayList<>();

        for (ParsedHoldingDto itemDto : parsedReport.getHoldings()) {
            String ticker = itemDto.getTicker() != null ? itemDto.getTicker().trim().toUpperCase(Locale.ROOT) : "";
            if ("FON".equals(ticker) || "TOPLAM".equals(ticker) || "GRUP".equals(ticker)
                    || "GENEL".equals(ticker) || "PORTFOY".equals(ticker) || "PORTFOYU".equals(ticker)
                    || "BORCLAR".equals(ticker) || "ALACAKLAR".equals(ticker) || "HAZIR".equals(ticker)) {
                log.warn("[PdfImportService] ⚠️ Özet / sahte menkul kıymet satırı filtrelendi: '{}' (Ağırlık: %{})",
                        ticker, itemDto.getWeightRatio());
                continue;
            }

            // 3 Kademeli Akıllı Eşleme Motoru çalıştırılır
            Instrument instrument = resolveOrCreateInstrument(itemDto);

            // Ağırlık oranını veritabanı DECIMAL(6,4) sınırına ölçekle
            BigDecimal weightRatio = itemDto.getWeightRatio() != null
                    ? itemDto.getWeightRatio().setScale(4, RoundingMode.HALF_UP)
                    : BigDecimal.ZERO;

            Holding holding = Holding.builder()
                    .snapshot(snapshot)
                    .instrument(instrument)
                    .nominalAmount(itemDto.getNominalAmount())
                    .unitCost(itemDto.getUnitCost())
                    .reportPrice(itemDto.getReportPrice())
                    .totalValue(itemDto.getTotalValue() != null ? itemDto.getTotalValue() : BigDecimal.ZERO)
                    .weightRatio(weightRatio)
                    .isShort(itemDto.getIsShort() != null && itemDto.getIsShort())
                    .build();

            holdingsToSave.add(holding);
        }

        holdingRepository.saveAll(holdingsToSave);

        log.info("[PdfImportService] Başarıyla tamamlandı: Fon='{}', Tarih={}, Toplam TNV={}, Kaydedilen Holding={}",
                normalizedCode, snapshot.getSnapshotDate(), snapshot.getTotalNetAssetValue(), holdingsToSave.size());

        // Yeni PDF eklendiğinde anında günlük tahmini hesapla, alt varlıkların oran ve
        // getirilerini db'ye (estimate_runs, estimate_details) yaz
        if (returnCalculationService != null) {
            try {
                log.info("[PdfImportService] ⚡ Yeni PDF içe aktarıldı, anlık getiri motoru tetikleniyor: Fon='{}'",
                        normalizedCode);
                returnCalculationService.calculateAndSave(snapshot);
            } catch (Exception e) {
                log.warn(
                        "[PdfImportService] Anlık getiri hesaplaması sırasında uyarı (snapshot güvenle kaydedildi): {}",
                        e.getMessage());
            }
        }

        // Yeni PDF geldiğinde eski backtest önbelleğini geçersiz kıl (Eviction)
        if (backtestCacheService != null) {
            try {
                backtestCacheService.evict(normalizedCode);
                log.info(
                        "[PdfImportService] Fon '{}' için yeni snapshot yüklendiğinden eski backtest önbelleği temizlendi.",
                        normalizedCode);
            } catch (Exception e) {
                log.warn("[PdfImportService] Backtest önbelleği temizlenirken hata: {}", e.getMessage());
            }
        }

        return new PdfImportResult(snapshot, false);
    }

    /**
     * Kolaylık metodu: URL ve SHA-256 olmadan yerel dosyaları içe aktarırken
     * kullanılır.
     */
    @Transactional
    public FundSnapshot importPdfReport(byte[] pdfContent, String fundCode) throws Exception {
        return importPdfReport(pdfContent, fundCode, null, null, null);
    }

    /**
     * 3 Kademeli Akıllı Eşleme Algoritması (Cascade Matching):
     * 
     * 1. Kademe: Borsa Kodu (Ticker) ile doğrudan arama (Örn: "THYAO", "AKBNK")
     * 2. Kademe: ISIN Kodu ile arama (Örn: "TRATHYAO91M5")
     * 3. Kademe: 'instrument_aliases' sözlük tablosu (Örn: "TURK HAVA YOLLARI
     * A.O.")
     * 
     * Hiçbiri eşleşmezse: Yeni bir Instrument ve onay bekleyen InstrumentAlias
     * oluşturur.
     */
    private Instrument resolveOrCreateInstrument(ParsedHoldingDto item) {
        String ticker = item.getTicker();
        String isin = item.getIsinCode();
        String rawName = item.getSecurityName();

        // 1. KADEME: Ticker Doğrudan Eşleşme
        if (ticker != null && !ticker.isBlank()) {
            Optional<Instrument> byTicker = instrumentRepository.findByTicker(ticker.trim().toUpperCase());
            if (byTicker.isPresent()) {
                return byTicker.get();
            }
        }

        // 2. KADEME: ISIN Kodu Eşleşmesi
        if (isin != null && !isin.isBlank()) {
            Optional<Instrument> byIsin = instrumentRepository.findByIsinCode(isin.trim().toUpperCase());
            if (byIsin.isPresent()) {
                return byIsin.get();
            }
        }

        // 3. KADEME: InstrumentAlias Sözlük Arama
        if (rawName != null && !rawName.isBlank()) {
            Optional<InstrumentAlias> byAlias = instrumentAliasRepository.findByRawNameWithInstrument(rawName.trim());
            if (byAlias.isPresent() && byAlias.get().getInstrument() != null) {
                return byAlias.get().getInstrument();
            }
        }

        // EŞLEŞME BULUNAMADI: Otomatik Yeni Enstrüman Üretimi ve Admin Onay Kaydı
        String effectiveTicker = (ticker != null && !ticker.isBlank())
                ? ticker.trim().toUpperCase()
                : (isin != null ? isin.trim().toUpperCase() : "UNK_" + UUID.randomUUID().toString().substring(0, 6));

        AssetClass assetClass = item.getAssetClassHint() != null ? item.getAssetClassHint() : AssetClass.EQUITY;

        Instrument newInstrument = Instrument.builder()
                .ticker(effectiveTicker)
                .isinCode(isin)
                .title(rawName != null ? rawName : effectiveTicker)
                .assetClass(assetClass)
                .currency("TRY")
                .build();

        newInstrument = instrumentRepository.save(newInstrument);
        log.info("[PdfImportService] Yeni enstrüman kataloğa eklendi: Ticker='{}', ISIN='{}', Varlık Sınıfı='{}'",
                effectiveTicker, isin, assetClass);

        // Gelecekte aynı kirli unvan geldiğinde tanımak için InstrumentAlias kaydı aç
        if (rawName != null && !rawName.isBlank()) {
            InstrumentAlias alias = InstrumentAlias.builder()
                    .rawName(rawName.trim())
                    .instrument(newInstrument)
                    .isApproved(true) // Sistem otomatik türetti
                    .matchConfidence(BigDecimal.valueOf(1.00))
                    .build();
            try {
                instrumentAliasRepository.save(alias);
            } catch (Exception e) {
                log.debug("[PdfImportService] Alias zaten kayıtlı olabilir: {}", rawName);
            }
        }

        return newInstrument;
    }

    /**
     * Finansal Tutarlılık Kontrolü (Sanity Check).
     * 
     * PDF'ten okunan verilerde mantıksal bozukluk var mı kontrol eder:
     * 1. TNV sıfır veya negatif mi?
     * 2. Ağırlıkların toplamı %80'in çok altında veya %125'in çok üzerinde mi?
     */
    private boolean performSanityCheck(ParsedPortfolioReportDto report) {
        if (report.getTotalNetAssetValue() == null || report.getTotalNetAssetValue().compareTo(BigDecimal.ZERO) <= 0) {
            log.warn("[SanityCheck] Şüpheli Rapor: Fon='{}' TNV sıfır veya negatif!", report.getFundCode());
            return true;
        }

        BigDecimal totalWeight = BigDecimal.ZERO;
        boolean hasViop = false;

        if (report.getViopCashRatio() != null && report.getViopCashRatio().compareTo(BigDecimal.ZERO) > 0) {
            hasViop = true;
        }

        for (ParsedHoldingDto h : report.getHoldings()) {
            if (h.getWeightRatio() != null) {
                totalWeight = totalWeight.add(h.getWeightRatio());
            }
            if (h.getAssetClassHint() == AssetClass.VIOP
                    || (h.getTicker() != null && h.getTicker().toUpperCase(Locale.ROOT).startsWith("F_"))) {
                hasViop = true;
            }
        }

        // VİOP ve türev pozisyonu içeren fonlarda kaldıraç ve nominal büyüklük
        // nedeniyle üst sınır %160'a esnetilir.
        // Standart spot hisse fonlarında ise kabul edilebilir normal dağılım aralığı:
        // %80 - %125'tir.
        BigDecimal maxAllowedWeight = hasViop ? BigDecimal.valueOf(1.60) : BigDecimal.valueOf(1.25);
        BigDecimal minAllowedWeight = BigDecimal.valueOf(0.80);

        if (totalWeight.compareTo(minAllowedWeight) < 0 || totalWeight.compareTo(maxAllowedWeight) > 0) {
            log.warn(
                    "[SanityCheck] Şüpheli Portföy Dağılımı: Fon='{}', Toplam Ağırlık={}% (Beklenen aralık: %{} - %{}, VİOP Mevcut Mu={})",
                    report.getFundCode(),
                    totalWeight.multiply(BigDecimal.valueOf(100)).setScale(2, RoundingMode.HALF_UP),
                    minAllowedWeight.multiply(BigDecimal.valueOf(100)),
                    maxAllowedWeight.multiply(BigDecimal.valueOf(100)),
                    hasViop);
            return true;
        }

        return false;
    }

    /**
     * PDF dosyasını veritabanına hiçbir şey kaydetmeden analiz eder (Dry-Run
     * Preview).
     * Ayrıştırılan varlık sınıflarını (Hisse, VİOP, Repo, Bono), oranları ve
     * seçilen stratejiyi döner.
     *
     * @param pdfContent Ham PDF baytları
     * @param fundCode   Fon kodu (Örn: "THF", "TTE", "TI2")
     * @return Zengin önizleme DTO'su (Asset Breakdown, Varlık Listesi, Strateji)
     */
    public PdfPreviewResponse previewPdfReport(byte[] pdfContent, String fundCode) throws Exception {
        if (pdfContent == null || pdfContent.length == 0) {
            throw new IllegalArgumentException("Önizleme yapılacak PDF içeriği boş olamaz: " + fundCode);
        }
        if (fundCode == null || fundCode.isBlank()) {
            throw new IllegalArgumentException("Fon kodu boş olamaz");
        }

        String normalizedCode = fundCode.trim().toUpperCase(Locale.ROOT);
        log.info("[PdfImportService] [DRY_RUN] PDF önizleme başlatıldı: Fon='{}', Boyut={} bayt",
                normalizedCode, pdfContent.length);

        Optional<Fund> fundOpt = fundRepository.findByCode(normalizedCode);
        String manager = fundOpt.map(Fund::getManager).orElse(null);
        String fundTitle = fundOpt.map(Fund::getTitle).orElse(normalizedCode);

        // Fabrikadan uygun stratejiyi Content Sniffing ve Kademeli Fallback ile
        // çalıştır
        ParsedPortfolioReportDto parsedReport = pdfParserFactory.parseWithFallback(pdfContent, normalizedCode, manager);

        if (parsedReport == null || parsedReport.getHoldings() == null || parsedReport.getHoldings().isEmpty()) {
            throw new IllegalStateException(
                    "PDF ayrıştırıldı ancak portföyde hiçbir varlık bulunamadı: " + normalizedCode);
        }

        // Varlık Sınıfı Kırılımı (Asset Breakdown)
        int eqCount = 0, viopCount = 0, depCount = 0, bondCount = 0, otherCount = 0;
        BigDecimal eqWeight = BigDecimal.ZERO;
        BigDecimal viopWeight = BigDecimal.ZERO;
        BigDecimal depWeight = BigDecimal.ZERO;
        BigDecimal bondWeight = BigDecimal.ZERO;
        BigDecimal otherWeight = BigDecimal.ZERO;
        BigDecimal totalWeight = BigDecimal.ZERO;

        List<PdfPreviewResponse.HoldingPreviewItemDto> holdingItems = new ArrayList<>();

        for (ParsedHoldingDto h : parsedReport.getHoldings()) {
            BigDecimal w = h.getWeightRatio() != null ? h.getWeightRatio() : BigDecimal.ZERO;
            totalWeight = totalWeight.add(w);
            BigDecimal wPct = w.multiply(BigDecimal.valueOf(100)).setScale(2, RoundingMode.HALF_UP);

            AssetClass ac = h.getAssetClassHint() != null ? h.getAssetClassHint() : AssetClass.EQUITY;
            if (ac == AssetClass.EQUITY) {
                eqCount++;
                eqWeight = eqWeight.add(w);
            } else if (ac == AssetClass.VIOP) {
                viopCount++;
                viopWeight = viopWeight.add(w);
            } else if (ac == AssetClass.DEPOSIT) {
                depCount++;
                depWeight = depWeight.add(w);
            } else if (ac == AssetClass.BOND) {
                bondCount++;
                bondWeight = bondWeight.add(w);
            } else {
                otherCount++;
                otherWeight = otherWeight.add(w);
            }

            holdingItems.add(PdfPreviewResponse.HoldingPreviewItemDto.builder()
                    .ticker(h.getTicker())
                    .securityName(h.getSecurityName() != null ? h.getSecurityName() : h.getTicker())
                    .isinCode(h.getIsinCode())
                    .assetClass(ac)
                    .weightPercentage(wPct)
                    .nominalAmount(h.getNominalAmount())
                    .reportPrice(h.getReportPrice())
                    .totalValue(h.getTotalValue())
                    .isShort(h.getIsShort())
                    .build());
        }

        BigDecimal totalWeightPct = totalWeight.multiply(BigDecimal.valueOf(100)).setScale(2, RoundingMode.HALF_UP);
        BigDecimal declaredStockPct = (parsedReport.getStockRatio() != null
                && parsedReport.getStockRatio().compareTo(BigDecimal.ZERO) > 0)
                        ? parsedReport.getStockRatio().multiply(BigDecimal.valueOf(100)).setScale(2,
                                RoundingMode.HALF_UP)
                        : null;
        BigDecimal declaredViopPct = (parsedReport.getViopCashRatio() != null
                && parsedReport.getViopCashRatio().compareTo(BigDecimal.ZERO) > 0)
                        ? parsedReport.getViopCashRatio().multiply(BigDecimal.valueOf(100)).setScale(2,
                                RoundingMode.HALF_UP)
                        : null;

        // Sanity Check Değerlendirmesi
        boolean sanityPassed = true;
        String sanityMessage = "Veri bütünlüğü doğrulandı. Portföy güvenle içe aktarılabilir.";

        if (totalWeight.compareTo(new BigDecimal("0.60")) < 0) {
            sanityPassed = false;
            sanityMessage = String.format(
                    "DİKKAT: Toplam portföy ağırlığı (%%%s) çok düşük! Tablodan eksik satır okunmuş olabilir.",
                    totalWeightPct);
        } else if (declaredStockPct != null) {
            BigDecimal readStockPct = eqWeight.multiply(BigDecimal.valueOf(100)).setScale(2, RoundingMode.HALF_UP);
            BigDecimal diff = readStockPct.subtract(declaredStockPct).abs();
            if (diff.compareTo(new BigDecimal("7.0")) > 0) {
                if (declaredStockPct.compareTo(new BigDecimal("5.0")) < 0 && readStockPct.compareTo(new BigDecimal("50.0")) > 0) {
                    declaredStockPct = null; // Başlıktan yanlışlıkla okunan pay birim fiyatını filtrele
                } else {
                    sanityPassed = false;
                    sanityMessage = String.format(
                            "UYARI: Tablodan okunan hisse ağırlığı (%%%s) ile yasal beyan edilen oran (%%%s) arasında %%7'den fazla fark var!",
                            readStockPct, declaredStockPct);
                }
            }
        }

        PdfPreviewResponse.AssetBreakdownDto breakdown = PdfPreviewResponse.AssetBreakdownDto.builder()
                .equity(PdfPreviewResponse.CategorySummary.builder()
                        .count(eqCount)
                        .weightPercentage(eqWeight.multiply(BigDecimal.valueOf(100)).setScale(2, RoundingMode.HALF_UP))
                        .description("BIST Hisse Senetleri")
                        .build())
                .viop(PdfPreviewResponse.CategorySummary.builder()
                        .count(viopCount)
                        .weightPercentage(
                                viopWeight.multiply(BigDecimal.valueOf(100)).setScale(2, RoundingMode.HALF_UP))
                        .description("VİOP Vadeli İşlem Kontratları")
                        .build())
                .depositAndRepo(PdfPreviewResponse.CategorySummary.builder()
                        .count(depCount)
                        .weightPercentage(depWeight.multiply(BigDecimal.valueOf(100)).setScale(2, RoundingMode.HALF_UP))
                        .description("Ters Repo / Takasbank Para Piyasası (BPP)")
                        .build())
                .bond(PdfPreviewResponse.CategorySummary.builder()
                        .count(bondCount)
                        .weightPercentage(
                                bondWeight.multiply(BigDecimal.valueOf(100)).setScale(2, RoundingMode.HALF_UP))
                        .description("Devlet / Özel Sektör Tahvili ve Bonoları")
                        .build())
                .other(PdfPreviewResponse.CategorySummary.builder()
                        .count(otherCount)
                        .weightPercentage(
                                otherWeight.multiply(BigDecimal.valueOf(100)).setScale(2, RoundingMode.HALF_UP))
                        .description("Diğer Menkul Kıymetler")
                        .build())
                .build();

        BigDecimal previewTnv = parsedReport.getTotalNetAssetValue();
        if (previewTnv == null || previewTnv.compareTo(BigDecimal.ZERO) <= 0) {
            BigDecimal sumTotalValue = parsedReport.getHoldings().stream()
                    .filter(h -> h.getTotalValue() != null)
                    .map(ParsedHoldingDto::getTotalValue)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            if (sumTotalValue.compareTo(BigDecimal.ZERO) > 0) {
                previewTnv = sumTotalValue;
            }
        }

        return PdfPreviewResponse.builder()
                .fundCode(normalizedCode)
                .fundTitle(fundTitle)
                .reportDate(parsedReport.getReportDate() != null ? parsedReport.getReportDate() : LocalDate.now())
                .totalNetAssetValue(previewTnv)
                .detectedParserStrategy(
                        parsedReport.getParserStrategyName() != null ? parsedReport.getParserStrategyName()
                                : "Otomatik Çoklu Ayrıştırıcı (Cascade)")
                .totalItemsCount(holdingItems.size())
                .totalPortfolioWeight(totalWeightPct)
                .stockRatioDeclared(declaredStockPct)
                .viopCashRatioDeclared(declaredViopPct)
                .sanityCheckPassed(sanityPassed)
                .sanityCheckMessage(sanityMessage)
                .assetBreakdown(breakdown)
                .holdings(holdingItems)
                .build();
    }
}
