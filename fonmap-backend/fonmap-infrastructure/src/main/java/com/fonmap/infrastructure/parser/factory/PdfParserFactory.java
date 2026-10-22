package com.fonmap.infrastructure.parser.factory;

import com.fonmap.infrastructure.parser.dto.ParsedPortfolioReportDto;
import com.fonmap.infrastructure.parser.strategy.PdfParserStrategy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.stream.Collectors;

/**
 * PDF Ayrıştırıcı Strateji Fabrikası (Factory Pattern).
 * 
 * =========================================================================================
 * 🎯 NE İŞE YARAR? (JUNIOR MÜHENDİS REHBERİ):
 * =========================================================================================
 * Spring Boot başladığında sistemde kayıtlı olan tüm 'PdfParserStrategy' sınıflarını
 * (TeraPdfParser, IsPortfoyPdfParser, AtlasPdfParser, PardusPdfParser) otomatik olarak
 * bir liste halinde bu fabrikanın yapıcı metoduna (Constructor) enjekte eder (Dependency Injection).
 * 
 * Bir PDF dosyası sisteme geldiğinde, çağıran servis (PdfImportService) hangi ayrıştırıcıyı
 * seçeceğini DÜŞÜNMEZ. Fabrikaya fon kodunu ve kurucu yönetici adını söyler:
 * 
 * "Ey fabrika! Elimde 'THF' fonu var, bunu hangi uzman ayrıştırmalı?"
 * 
 * Fabrika kayıtlı tüm stratejileri gezer, 'supports("THF", "Tera Portföy")' metoduna 'true'
 * diyen uzmanı bulur ve servise teslim eder.
 * 
 * 💡 OPEN / CLOSED PRENSİBİNE UYUM:
 * Yarın sisteme yeni bir portföy şirketi (Örn: "Ak Portföy" veya "Garanti Portföy") eklendiğinde
 * bu Fabrika sınıfına TEK BİR SATIR BİLE DOKUNMAYIZ! Sadece yeni bir 'AkPortfoyPdfParser'
 * yazıp '@Component' koymamız yeterlidir; Spring ve Fabrika onu otomatik olarak tanır!
 */
@Component
@Slf4j
public class PdfParserFactory {

    /**
     * Spring context'inde tanımlı tüm ayrıştırıcı stratejilerini tutan liste.
     */
    private final List<PdfParserStrategy> strategies;

    public PdfParserFactory(List<PdfParserStrategy> strategies) {
        this.strategies = strategies;
    }

    /**
     * Verilen fon kodu, kurucu yönetici unvanı ve PDF içeriğine göre en uygun ayrıştırıcı stratejisini seçer.
     * Content Sniffing özelliği sayesinde yeni veya bilinmeyen fonlar dinamik olarak doğru stratejiye yönlendirilir.
     * 
     * @param fundCode Fon kodu (Örn: "THF", "TTE", "DFI", "KHA", "ICZ")
     * @param managerName Kurucu portföy yönetim şirketi adı (Örn: "Tera Portföy Yönetimi A.Ş.")
     * @param pdfBytes Ham PDF içeriği (opsiyonel, dinamik koklama için)
     * @return Bu fonu ayrıştırabilecek uzman strateji nesnesi
     */
    public PdfParserStrategy getParser(String fundCode, String managerName, byte[] pdfBytes) {
        log.debug("[PdfParserFactory] Uygun ayrıştırıcı aranıyor: Fon='{}', Yönetici='{}'", fundCode, managerName);

        // 1. AŞAMA: CONTENT SNIFFING (Eğer ham PDF baytları mevcutsa, İÇERİK TEK GERÇEKTİR!)
        // Şirket unvanı veya fon kodu ne olursa olsun dosyanın gerçek sayfa düzeni ve tablo yapısı incelenir.
        if (pdfBytes != null && pdfBytes.length > 0) {
            try {
                PdfParserStrategy sniffed = sniffStrategy(pdfBytes, fundCode, managerName);
                if (sniffed != null) {
                    log.info("[PdfParserFactory] 🔍 Content Sniffing ile dinamik yapısal strateji seçildi: Fon='{}' -> {}",
                            fundCode, sniffed.getStrategyName());
                    return sniffed;
                }
            } catch (Exception e) {
                log.warn("[PdfParserFactory] Content sniffing sırasında uyarı: {}", e.getMessage());
            }
        }

        // 2. AŞAMA: Kod Bazlı Doğrudan Eşleşme (Eğer PDF henüz indirilmediyse veya bayt yoksa)
        for (PdfParserStrategy strategy : strategies) {
            if (strategy.supports(fundCode, managerName)) {
                log.info("[PdfParserFactory] Strateji fon kodundan eşleşti: Fon='{}' -> {}", fundCode, strategy.getStrategyName());
                return strategy;
            }
        }

        // 3. AŞAMA: Fallback - Standart SPK Yatay (SPK Landscape) Stratejisi
        for (PdfParserStrategy strategy : strategies) {
            if ("SPK_LANDSCAPE_STRATEGY".equals(strategy.getStrategyName())
                    || "TERA_LANDSCAPE_STRATEGY".equals(strategy.getStrategyName())) {
                log.info("[PdfParserFactory] Eşleşme bulunamadı, SPK standart yatay format varsayıldı: Fon='{}'", fundCode);
                return strategy;
            }
        }

        if (!strategies.isEmpty()) {
            return strategies.get(0);
        }

        throw new IllegalArgumentException("Hiçbir PDF ayrıştırıcı stratejisi yüklü değil!");
    }

    /**
     * Geriye dönük uyumluluk için fon kodu ve yönetici ile strateji seçimi.
     */
    public PdfParserStrategy getParser(String fundCode, String managerName) {
        return getParser(fundCode, managerName, null);
    }

    /**
     * PDF'i aday stratejiler arasında kademeli olarak (Cascade Fallback) ayrıştırır.
     * Eğer ilk seçilen strateji hata verirse veya yetersiz veri (varlık < 3 veya toplam ağırlık < %40) üretirse,
     * sistem durmadan sıradaki diğer uygun adayları sırayla dener.
     */
    public ParsedPortfolioReportDto parseWithFallback(byte[] pdfBytes, String fundCode, String managerName) throws Exception {
        List<PdfParserStrategy> candidates = getCandidateParsers(fundCode, managerName, pdfBytes);
        Exception lastException = null;

        // 1. TUR: Standart ve Sıkı Finansal Kalite Süzgeci (%80 <= Ağırlık <= %120)
        // Standart hisse ve karma fonların portföy toplamı ~%100 olmalıdır. Bozuk kolon kaymaları anında elenir.
        for (PdfParserStrategy strategy : candidates) {
            try {
                ParsedPortfolioReportDto report = strategy.parse(pdfBytes, fundCode);
                if (report != null && report.getHoldings() != null && report.getHoldings().size() >= 3) {
                    java.math.BigDecimal totalWeight = report.getHoldings().stream()
                            .filter(h -> h.getWeightRatio() != null)
                            .map(com.fonmap.infrastructure.parser.dto.ParsedHoldingDto::getWeightRatio)
                            .reduce(java.math.BigDecimal.ZERO, java.math.BigDecimal::add);

                    boolean isStrictValid = totalWeight.compareTo(new java.math.BigDecimal("0.80")) >= 0
                            && totalWeight.compareTo(new java.math.BigDecimal("1.20")) <= 0;

                    if (isStrictValid) {
                        report.setParserStrategyName(strategy.getStrategyName());
                        log.info("[PdfParserFactory] ✅ Başarılı ve onaylı ayrıştırma (Sıkı Süzgeç): Fon='{}' -> Strateji='{}', Varlık={}, Ağırlık=%{}",
                                fundCode, strategy.getStrategyName(), report.getHoldings().size(),
                                totalWeight.multiply(java.math.BigDecimal.valueOf(100)).setScale(2, java.math.RoundingMode.HALF_UP));
                        return report;
                    } else {
                        log.warn("[PdfParserFactory] ⚠️ Strateji '{}' kabul edilemez ağırlık üretti (Varlık: {}, Ağırlık: %{}). Sıradaki aday deneniyor...",
                                strategy.getStrategyName(), report.getHoldings().size(),
                                totalWeight.multiply(java.math.BigDecimal.valueOf(100)).setScale(2, java.math.RoundingMode.HALF_UP));
                    }
                }
            } catch (Exception e) {
                log.debug("[PdfParserFactory] Strateji '{}' sıkı turda hata verdi: {}", strategy.getStrategyName(), e.getMessage());
                lastException = e;
            }
        }

        // 2. TUR: Kaldıraçlı / Serbest Fonlar İçin Genişletilmiş Süzgeç (%60 <= Ağırlık <= %200)
        // Eğer hiçbir parser %80-%120 aralığına oturmadıysa, aşırı saçma (%500+) olmayan en iyi adayı kabul et
        log.warn("[PdfParserFactory] Sıkı süzgeçten geçen strateji bulunamadı. Serbest/kaldıraçlı fonlar için esnek süzgeç devreye giriyor: Fon='{}'", fundCode);
        for (PdfParserStrategy strategy : candidates) {
            try {
                ParsedPortfolioReportDto report = strategy.parse(pdfBytes, fundCode);
                if (report != null && report.getHoldings() != null && report.getHoldings().size() >= 3) {
                    java.math.BigDecimal totalWeight = report.getHoldings().stream()
                            .filter(h -> h.getWeightRatio() != null)
                            .map(com.fonmap.infrastructure.parser.dto.ParsedHoldingDto::getWeightRatio)
                            .reduce(java.math.BigDecimal.ZERO, java.math.BigDecimal::add);

                    boolean isRelaxedValid = totalWeight.compareTo(new java.math.BigDecimal("0.60")) >= 0
                            && totalWeight.compareTo(new java.math.BigDecimal("2.00")) <= 0;

                    if (isRelaxedValid) {
                        report.setParserStrategyName(strategy.getStrategyName());
                        log.info("[PdfParserFactory] ⚠️ Esnek süzgeç ile ayrıştırma kabul edildi: Fon='{}' -> Strateji='{}', Varlık={}, Ağırlık=%{}",
                                fundCode, strategy.getStrategyName(), report.getHoldings().size(),
                                totalWeight.multiply(java.math.BigDecimal.valueOf(100)).setScale(2, java.math.RoundingMode.HALF_UP));
                        return report;
                    }
                }
            } catch (Exception e) {
                lastException = e;
            }
        }

        if (lastException != null) {
            throw lastException;
        }
        throw new IllegalStateException("Hiçbir PDF ayrıştırıcı stratejisi bu raporu güvenli bir şekilde ayrıştıramadı: " + fundCode);
    }

    /**
     * PDF için tüm aday stratejileri uygunluk skorlarına ve destek kriterlerine göre sıralı liste olarak döner.
     */
    public List<PdfParserStrategy> getCandidateParsers(String fundCode, String managerName, byte[] pdfBytes) {
        List<PdfParserStrategy> result = new java.util.ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<>();

        // 1. Content Sniffing Skoruna Göre Sıralı Adaylar
        if (pdfBytes != null && pdfBytes.length > 0) {
            try (org.apache.pdfbox.pdmodel.PDDocument document = org.apache.pdfbox.Loader.loadPDF(pdfBytes)) {
                if (document.getNumberOfPages() > 0) {
                    org.apache.pdfbox.pdmodel.PDPage firstPage = document.getPage(0);
                    org.apache.pdfbox.pdmodel.common.PDRectangle mediaBox = firstPage.getMediaBox();
                    int rotation = firstPage.getRotation();
                    boolean isLandscape = (rotation == 90 || rotation == 270)
                            ? mediaBox.getHeight() > mediaBox.getWidth()
                            : mediaBox.getWidth() > mediaBox.getHeight();

                    org.apache.pdfbox.text.PDFTextStripper stripper = new org.apache.pdfbox.text.PDFTextStripper();
                    stripper.setSortByPosition(true);
                    stripper.setStartPage(1);
                    stripper.setEndPage(Math.min(2, document.getNumberOfPages()));
                    String textSample = stripper.getText(document).toUpperCase(java.util.Locale.ROOT);

                    List<java.util.Map.Entry<PdfParserStrategy, Integer>> scored = new java.util.ArrayList<>();
                    for (PdfParserStrategy strategy : strategies) {
                        int score = strategy.sniffScore(pdfBytes, textSample, isLandscape);
                        scored.add(new java.util.AbstractMap.SimpleEntry<>(strategy, score));
                    }
                    scored.sort((a, b) -> Integer.compare(b.getValue(), a.getValue()));

                    for (var entry : scored) {
                        if (entry.getValue() >= 40 && seen.add(entry.getKey().getStrategyName())) {
                            result.add(entry.getKey());
                        }
                    }
                }
            } catch (Exception e) {
                log.debug("[PdfParserFactory] Aday sıralama sniffing hatası: {}", e.getMessage());
            }
        }

        // 2. supports(fundCode) Desteği Olanlar
        for (PdfParserStrategy s : strategies) {
            if (s.supports(fundCode, managerName) && seen.add(s.getStrategyName())) {
                result.add(s);
            }
        }

        // 3. Fallback Stratejileri
        for (PdfParserStrategy s : strategies) {
            if (seen.add(s.getStrategyName())) {
                result.add(s);
            }
        }

        return result;
    }

    /**
     * PDF'in ilk 2 sayfasını ve sayfa oryantasyonunu PDFBox ile inceleyerek en uygun stratejiyi tespit eder.
     */
    private PdfParserStrategy sniffStrategy(byte[] pdfBytes, String fundCode, String managerName) {
        try (org.apache.pdfbox.pdmodel.PDDocument document = org.apache.pdfbox.Loader.loadPDF(pdfBytes)) {
            if (document.getNumberOfPages() == 0) {
                return null;
            }

            // 1. Sayfa Oryantasyonu (Landscape vs Portrait - Rotasyon Dikkate Alınarak)
            org.apache.pdfbox.pdmodel.PDPage firstPage = document.getPage(0);
            org.apache.pdfbox.pdmodel.common.PDRectangle mediaBox = firstPage.getMediaBox();
            int rotation = firstPage.getRotation();
            boolean isLandscape = (rotation == 90 || rotation == 270)
                    ? mediaBox.getHeight() > mediaBox.getWidth()
                    : mediaBox.getWidth() > mediaBox.getHeight();

            // 2. İlk 1-2 sayfanın metin örneği
            org.apache.pdfbox.text.PDFTextStripper stripper = new org.apache.pdfbox.text.PDFTextStripper();
            stripper.setSortByPosition(true);
            stripper.setStartPage(1);
            stripper.setEndPage(Math.min(2, document.getNumberOfPages()));
            String textSample = stripper.getText(document).toUpperCase(java.util.Locale.ROOT);

            PdfParserStrategy bestStrategy = null;
            int maxScore = 0;

            for (PdfParserStrategy strategy : strategies) {
                int score = strategy.sniffScore(pdfBytes, textSample, isLandscape);
                log.debug("[PdfParserFactory] Strateji: {} -> Sniff Skoru: {}", strategy.getStrategyName(), score);
                if (score > maxScore) {
                    maxScore = score;
                    bestStrategy = strategy;
                }
            }

            // En az 40 puanlık bir güven eşiği (Confidence Threshold) sağlanmalıdır
            if (bestStrategy != null && maxScore >= 40) {
                log.info("[PdfParserFactory] 🔍 En yüksek skoru alan strateji seçildi: {} (Skor: {})",
                        bestStrategy.getStrategyName(), maxScore);
                return bestStrategy;
            }
            return null;
        } catch (Exception e) {
            log.debug("[PdfParserFactory] PDFBox sniff ayrıştırma hatası: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Sistemde halihazırda aktif ve yüklü olan tüm strateji isimlerini döner (Admin Paneli ve Loglar için).
     */
    public List<String> getAvailableStrategies() {
        return strategies.stream()
                .map(PdfParserStrategy::getStrategyName)
                .collect(Collectors.toList());
    }

    /**
     * Yüklü strateji sayısını döner.
     */
    public int getStrategyCount() {
        return strategies != null ? strategies.size() : 0;
    }
}
