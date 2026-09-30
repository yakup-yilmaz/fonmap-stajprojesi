package com.fonmap.application.service.pdf;

import com.fonmap.domain.entity.*;
import com.fonmap.domain.enums.AssetClass;
import com.fonmap.domain.enums.ReportStatus;
import com.fonmap.infrastructure.parser.dto.ParsedHoldingDto;
import com.fonmap.infrastructure.parser.dto.ParsedPortfolioReportDto;
import com.fonmap.infrastructure.parser.factory.PdfParserFactory;
import com.fonmap.infrastructure.parser.strategy.PdfParserStrategy;
import com.fonmap.infrastructure.repository.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.*;

/**
 * =========================================================================================
 * 🎯 PDF İÇERİ AKTARMA VE VERİTABANI KAYIT SERVİSİ (PdfImportService)
 * =========================================================================================
 * 
 * 📌 NE İŞE YARAR? (JUNIOR MÜHENDİS REHBERİ):
 * Bu servis, KAP'tan inen veya sisteme yüklenen ham bir portföy dağılım PDF dosyasını
 * alıp, veritabanımızda kalıcı ve sorgulanabilir 'FundSnapshot' ve 'Holding' kayıtlarına
 * dönüştüren ana köprüdür.
 * 
 * 🏗️ MİMARİDEKİ YERİ:
 * 1. KAP veya Kullanıcı -> Ham PDF (byte[])
 * 2. PdfParserFactory  -> İlgili portföy şirketine uygun Stratejiyi (Tera, İş Portföy vb.) seçer.
 * 3. PdfParserStrategy -> Ham metinleri 'ParsedPortfolioReportDto' kargosuna çevirir.
 * 4. PdfImportService  -> (BU SINIF) DTO'yu alır, 3 kademeli akıllı hisse eşlemesi yapar,
 *                         finansal tutarlılık (sanity check) testlerini koşar ve
 *                         PostgreSQL veritabanına tek bir atomik işlemde (@Transactional) yazar.
 * 
 * 🔍 3 KADEMELİ AKILLI EŞLEME MOTORU (CASCADE MATCHING):
 * PDF'lerde bazen borsa kodu ("THYAO"), bazen ISIN ("TRATHYAO91M5"), bazen de sadece uzun
 * şirket unvanı ("TÜRK HAVA YOLLARI A.O.") yazar. Servisimiz şu sırayla eşleme yapar:
 *   1. Aşama: Ticker Doğrudan Eşleme (Örn: "THYAO" -> Instrument ID) — En hızlı yol (%95).
 *   2. Aşama: ISIN Koduyla Eşleme (Örn: "TRATHYAO91M5" -> Instrument ID).
 *   3. Aşama: 'instrument_aliases' Sözlük Tablosu (Geçmişte admin onayından geçmiş unvanlar).
 *   - Hiçbirinde bulunamazsa: Yeni enstrüman/alias oluşturulup admin onay havuzuna düşürülür.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class PdfImportService {

    private final FundRepository fundRepository;
    private final FundReportRepository fundReportRepository;
    private final FundSnapshotRepository fundSnapshotRepository;
    private final HoldingRepository holdingRepository;
    private final InstrumentRepository instrumentRepository;
    private final InstrumentAliasRepository instrumentAliasRepository;
    private final PdfParserFactory pdfParserFactory;

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
    @Transactional
    public FundSnapshot importPdfReport(byte[] pdfContent,
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
        // Eğer bu dosya (aynı SHA-256) daha önce başarıyla kaydedildiyse, tekrar parse edip
        // veritabanını şişirmemek için doğrudan mevcut snapshot'ı döneriz.
        if (sha256 != null && !sha256.isBlank()) {
            Optional<FundReport> existingReportOpt = fundReportRepository.findByPdfSha256(sha256);
            if (existingReportOpt.isPresent()) {
                FundReport existingReport = existingReportOpt.get();
                Optional<FundSnapshot> existingSnapshotOpt = fundSnapshotRepository.findByReportId(existingReport.getId());
                if (existingSnapshotOpt.isPresent()) {
                    log.warn("[PdfImportService] Bu rapor (SHA256: {}) daha önce içe aktarılmış. Mevcut snapshot dönülüyor.", sha256);
                    return existingSnapshotOpt.get();
                }
            }
        }

        // 2. ADIM: FONUN VERİTABANINDA MEVCUT OLDUĞUNU DOĞRULA
        Fund fund = fundRepository.findByCode(normalizedCode)
                .orElseThrow(() -> new IllegalArgumentException("Sistemde '" + normalizedCode + "' kodlu bir fon bulunamadı!"));

        // 3. ADIM: FABRİKADAN UYGUN STRATEJİYİ AL VE PDF'İ AYRIŞTIR
        PdfParserStrategy parserStrategy = pdfParserFactory.getParser(normalizedCode, fund.getManager());
        ParsedPortfolioReportDto parsedReport = parserStrategy.parse(pdfContent, normalizedCode);

        if (parsedReport == null || parsedReport.getHoldings() == null || parsedReport.getHoldings().isEmpty()) {
            throw new IllegalStateException("PDF ayrıştırıldı ancak fon portföyünde hiçbir varlık bulunamadı: " + normalizedCode);
        }

        // 4. ADIM: FİNANSAL TUTARLILIK KONTROLÜ (SANITY CHECK)
        // Portföydeki tüm varlıkların ağırlık toplamı hesaplanır (Kaldıraçsız fonlarda ~%100 olmalıdır).
        boolean isSuspicious = performSanityCheck(parsedReport);

        // 5. ADIM: FUND REPORT (RAPOR DENETİM METAVERİSİ) OLUŞTUR VE KAYDET
        FundReport fundReport = fundReportRepository.findByFundIdAndReportDateWithFund(fund.getId(), parsedReport.getReportDate())
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
        // Önce aynı rapor için daha önceden kalan eski snapshot varsa onu temizle ya da güncelle
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
        snapshot.setTotalNetAssetValue(parsedReport.getTotalNetAssetValue());
        snapshot.setStockRatio(parsedReport.getStockRatio() != null
                ? parsedReport.getStockRatio().setScale(4, RoundingMode.HALF_UP)
                : BigDecimal.ZERO);
        snapshot.setViopCashRatio(parsedReport.getViopCashRatio() != null
                ? parsedReport.getViopCashRatio().setScale(4, RoundingMode.HALF_UP)
                : BigDecimal.ZERO);
        snapshot.setIsSuspicious(isSuspicious);

        snapshot = fundSnapshotRepository.save(snapshot);

        // 7. ADIM: HER BİR ALT VARLIĞI EŞLE VE 'holdings' TABLOSUNA KAYDET
        List<Holding> holdingsToSave = new ArrayList<>();

        for (ParsedHoldingDto itemDto : parsedReport.getHoldings()) {
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

        return snapshot;
    }

    /**
     * Kolaylık metodu: URL ve SHA-256 olmadan yerel dosyaları içe aktarırken kullanılır.
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
     * 3. Kademe: 'instrument_aliases' sözlük tablosu (Örn: "TURK HAVA YOLLARI A.O.")
     * 
     * Hiçbiri eşleşmezse: Yeni bir Instrument ve onay bekleyen InstrumentAlias oluşturur.
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
        for (ParsedHoldingDto h : report.getHoldings()) {
            if (h.getWeightRatio() != null) {
                totalWeight = totalWeight.add(h.getWeightRatio());
            }
        }

        // Normalde toplam portföy ağırlığı %100 civarında (0.90 - 1.15) olmalıdır
        if (totalWeight.compareTo(BigDecimal.valueOf(0.80)) < 0 || totalWeight.compareTo(BigDecimal.valueOf(1.25)) > 0) {
            log.warn("[SanityCheck] Şüpheli Portföy Dağılımı: Fon='{}', Toplam Ağırlık={}% (Normal aralık: %80 - %125)",
                    report.getFundCode(), totalWeight.multiply(BigDecimal.valueOf(100)));
            return true;
        }

        return false;
    }
}
