package com.fonmap.infrastructure.parser;

import com.fonmap.infrastructure.client.kap.KapClient;
import com.fonmap.infrastructure.client.kap.dto.KapPdfDto;
import com.fonmap.infrastructure.parser.dto.ParsedHoldingDto;
import com.fonmap.infrastructure.parser.dto.ParsedPortfolioReportDto;
import com.fonmap.infrastructure.parser.factory.PdfParserFactory;
import com.fonmap.infrastructure.parser.strategy.PdfParserStrategy;
import com.fonmap.infrastructure.parser.strategy.TeraPdfParser;
import com.fonmap.infrastructure.parser.strategy.IsPortfoyPdfParser;
import com.fonmap.infrastructure.parser.strategy.AtlasPdfParser;
import com.fonmap.infrastructure.parser.strategy.PardusPdfParser;
import com.fonmap.infrastructure.parser.strategy.SpkLandscapePdfParser;
import com.fonmap.infrastructure.parser.strategy.GenericVerticalPdfParser;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ============================================================================
 * FONMAP — 7 Fon Gerçek PDF Ayrıştırma ve Fabrika Entegrasyon Testleri
 * ============================================================================
 * 
 * Bu test sınıfı;
 * Projemizin kapsamındaki 7 gerçek fonun (THF, TLY, TMV, DOH, TTE, DFI, KHA)
 * diskteki orijinal 'samples/*.pdf' dosyalarını sırayla okuyup,
 * PdfParserFactory üzerinden doğru stratejiyle ayrıştırıldığını doğrular.
 */
class PdfParserIntegrationTest {

    private KapClient kapClient;
    private PdfParserFactory parserFactory;

    @BeforeEach
    void setUp() {
        kapClient = new KapClient();

        // Stratejiler Spring context'i olmadan saf Java ile fabrikaya verilir
        List<PdfParserStrategy> strategies = List.of(
                new TeraPdfParser(),
                new IsPortfoyPdfParser(),
                new AtlasPdfParser(),
                new PardusPdfParser()
        );

        parserFactory = new PdfParserFactory(strategies);
    }

    @Test
    @DisplayName("1. Fabrika Sağlamlık Testi: 4 Strateji Başarıyla Yüklenmeli")
    void testFactoryRegistration() {
        assertEquals(4, parserFactory.getStrategyCount());
        List<String> strategyNames = parserFactory.getAvailableStrategies();
        assertTrue(strategyNames.contains("TERA_LANDSCAPE_STRATEGY"));
        assertTrue(strategyNames.contains("IS_PORTFOY_STRATEGY"));
        assertTrue(strategyNames.contains("ATLAS_PORTRAIT_STRATEGY"));
        assertTrue(strategyNames.contains("PARDUS_STRATEGY"));
    }

    @Test
    @DisplayName("2. 7 Fonun Orijinal PDF Raporlarının Ayrıştırılması ve Doğrulanması")
    void testParseAllSevenSamplePdfs() throws Exception {
        Assumptions.assumeTrue(kapClient.hasLocalSamples(),
                "'samples/' klasörü bulunamadı (CI ortamı). Bu yerel geliştirme testi atlanıyor.");

        List<String> fundCodes = List.of("THF", "TLY", "TMV", "DOH", "TTE", "DFI", "KHA");

        for (String fundCode : fundCodes) {
            // 1. KapClient ile örnek PDF baytlarını yükle
            KapPdfDto kapPdfDto = kapClient.loadFromLocalSample(fundCode);
            assertNotNull(kapPdfDto);
            assertFalse(kapPdfDto.isEmpty());

            // 2. Fabrikadan bu fona uygun uzman stratejiyi iste (Dinamik Content Sniffing doğrulaması)
            PdfParserStrategy strategy = parserFactory.getParser(fundCode, null, kapPdfDto.getContent());
            assertNotNull(strategy, fundCode + " için strateji bulunamadı!");

            // 3. PDF'i ayrıştır
            ParsedPortfolioReportDto reportDto = strategy.parse(kapPdfDto.getContent(), fundCode);

            // 4. Doğrulamalar
            assertNotNull(reportDto);
            assertEquals(fundCode, reportDto.getFundCode());
            assertNotNull(reportDto.getReportDate(), "Rapor tarihi null olamaz");
            assertNotNull(reportDto.getHoldings(), "Holdings listesi null olamaz");
            assertFalse(reportDto.getHoldings().isEmpty(), fundCode + " için en az 1 varlık ayrıştırılmalıdır!");

            // İlk 3 hisseyi ve özet istatistikleri ekrana bas
            System.out.printf("%n======================================================%n");
            System.out.printf(">>> [PARSER BAŞARILI] Fon: %-4s | Strateji: %-25s%n", fundCode, strategy.getStrategyName());
            System.out.printf("    Rapor Tarihi: %s | TNV: %,.2f TL | Toplam Varlık Sayısı: %d%n",
                    reportDto.getReportDate(),
                    reportDto.getTotalNetAssetValue() != null ? reportDto.getTotalNetAssetValue().doubleValue() : 0.0,
                    reportDto.getItemCount());

            BigDecimal sumW = BigDecimal.ZERO;
            for (ParsedHoldingDto h : reportDto.getHoldings()) {
                if (h.getWeightRatio() != null) sumW = sumW.add(h.getWeightRatio());
            }
            List<ParsedHoldingDto> firstFew = reportDto.getHoldings().subList(0, Math.min(3, reportDto.getHoldings().size()));
            for (ParsedHoldingDto h : firstFew) {
                System.out.printf("    -> %-6s | ISIN: %-12s | Lot: %,12.2f | Fiyat(P0): %8.2f TL | Ağırlık(w0): %%%5.2f | Short: %s%n",
                        h.getTicker() != null ? h.getTicker() : "N/A",
                        h.getIsinCode() != null ? h.getIsinCode() : "N/A",
                        h.getNominalAmount() != null ? h.getNominalAmount().doubleValue() : 0.0,
                        h.getReportPrice() != null ? h.getReportPrice().doubleValue() : 0.0,
                        h.getWeightRatio() != null ? h.getWeightRatio().multiply(BigDecimal.valueOf(100)).doubleValue() : 0.0,
                        h.getIsShort() != null && h.getIsShort() ? "EVET" : "HAYIR");
            }
            System.out.printf("    ===> %s TOPLAM AĞIRLIK: %%%.2f%n", fundCode, sumW.multiply(BigDecimal.valueOf(100)));
        }
    }

    @Test
    @DisplayName("3. THF Temmuz ve Ağustos Varlık Sınıfları ve Kapsama Oranı Analizi")
    void testInspectNewPdf() throws Exception {
        TeraPdfParser parser = new TeraPdfParser();
        analyzeReport(parser, "c:/Users/Yakup Yılmaz/Desktop/stajprojesi/samples/THF_2026_07.pdf", "TEMMUZ 2026");
        analyzeReport(parser, "c:/Users/Yakup Yılmaz/Desktop/stajprojesi/samples/THF_2026_08.pdf", "AĞUSTOS 2026");
    }

    private void analyzeReport(TeraPdfParser parser, String path, String period) throws Exception {
        java.io.File file = new java.io.File(path);
        if (!file.exists()) file = new java.io.File("../../" + path);
        byte[] bytes = java.nio.file.Files.readAllBytes(file.toPath());
        ParsedPortfolioReportDto report = parser.parse(bytes, "THF");

        System.out.println("================================================================================");
        System.out.printf(">>> 🔍 THF %s RAPORU ANALİZİ:%n", period);
        System.out.printf("    Rapor Tarihi        : %s%n", report.getReportDate());
        System.out.printf("    Toplam Varlık Değeri: %,.2f TL%n", report.getTotalNetAssetValue());
        System.out.printf("    Toplam Varlık Sayısı: %d adet%n", report.getItemCount());

        int equityCount = 0, bondCount = 0, depositCount = 0, viopCount = 0, otherCount = 0;
        BigDecimal equityWeight = BigDecimal.ZERO;
        BigDecimal bondWeight = BigDecimal.ZERO;
        BigDecimal depositWeight = BigDecimal.ZERO;
        BigDecimal viopWeight = BigDecimal.ZERO;
        BigDecimal otherWeight = BigDecimal.ZERO;

        for (ParsedHoldingDto h : report.getHoldings()) {
            BigDecimal w = h.getWeightRatio() != null ? h.getWeightRatio().abs() : BigDecimal.ZERO;
            com.fonmap.domain.enums.AssetClass ac = h.getAssetClassHint();
            if (ac == com.fonmap.domain.enums.AssetClass.EQUITY) {
                equityCount++;
                equityWeight = equityWeight.add(w);
            } else if (ac == com.fonmap.domain.enums.AssetClass.BOND) {
                bondCount++;
                bondWeight = bondWeight.add(w);
            } else if (ac == com.fonmap.domain.enums.AssetClass.DEPOSIT) {
                depositCount++;
                depositWeight = depositWeight.add(w);
            } else if (ac == com.fonmap.domain.enums.AssetClass.VIOP) {
                viopCount++;
                viopWeight = viopWeight.add(w);
            } else {
                otherCount++;
                otherWeight = otherWeight.add(w);
            }
        }

        BigDecimal totalWeight = equityWeight.add(bondWeight).add(depositWeight).add(viopWeight).add(otherWeight);
        // Kapsanan: Hisse (Canlı) + Mevduat/Repo (TCMB Faizi) + VİOP
        BigDecimal coveredWeight = equityWeight.add(depositWeight).add(viopWeight);
        BigDecimal coverageRatio = totalWeight.compareTo(BigDecimal.ZERO) > 0
                ? coveredWeight.divide(totalWeight, 4, java.math.RoundingMode.HALF_UP)
                : BigDecimal.ONE;

        System.out.println("--------------------------------------------------------------------------------");
        System.out.printf("    • HİSSE SENETLERİ (EQUITY)  : %3d adet | Ağırlık: %%%6.2f (Canlı Fiyat: VAR ✅)%n",
                equityCount, equityWeight.multiply(BigDecimal.valueOf(100)));
        System.out.printf("    • REPO / MEVDUAT  (DEPOSIT) : %3d adet | Ağırlık: %%%6.2f (TCMB Faizi : VAR ✅)%n",
                depositCount, depositWeight.multiply(BigDecimal.valueOf(100)));
        System.out.printf("    • VİOP POZİSYON   (VIOP)    : %3d adet | Ağırlık: %%%6.2f (BIST Dayanak : VAR ✅)%n",
                viopCount, viopWeight.multiply(BigDecimal.valueOf(100)));
        System.out.printf("    • BONO / TAHVİL   (BOND)    : %3d adet | Ağırlık: %%%6.2f (Tahtasız Özel: %s)%n",
                bondCount, bondWeight.multiply(BigDecimal.valueOf(100)),
                bondCount > 0 ? "FİYAT YOK ⚠️ (Nötr)" : "YOK ✅");
        System.out.printf("    • TOPLAM PORTFÖY AĞIRLIĞI   : %%%6.2f%n", totalWeight.multiply(BigDecimal.valueOf(100)));
        System.out.printf("    • KAPSAMA ORANI (Coverage)  : %%%6.2f%n", coverageRatio.multiply(BigDecimal.valueOf(100)));
        System.out.printf("    • GÜVENİLİRLİK SEVİYESİ     : %s%n",
                coverageRatio.compareTo(new BigDecimal("0.8500")) >= 0 ? "YÜKSEK 🟢 (HIGH)" :
                coverageRatio.compareTo(new BigDecimal("0.6500")) >= 0 ? "ORTA 🟡 (MEDIUM)" : "DÜŞÜK 🔴 (LOW)");
        System.out.println("================================================================================");
    }

    @Test
    @DisplayName("4. TTE Temmuz ve Ağustos Raporları Ayrıştırma ve Doğrulama Mekanizması")
    void testInspectTtePdfs() throws Exception {
        IsPortfoyPdfParser parser = new IsPortfoyPdfParser();
        analyzeTteReport(parser, "samples/TTE_2026_07.pdf", "TEMMUZ 2026");
        analyzeTteReport(parser, "samples/TTE_2026_08.pdf", "AĞUSTOS 2026 (YENİ RAPOR)");
    }

    private void analyzeTteReport(IsPortfoyPdfParser parser, String path, String period) throws Exception {
        java.io.File file = new java.io.File(path);
        if (!file.exists()) file = new java.io.File("../" + path);
        if (!file.exists()) file = new java.io.File("../../" + path);
        if (!file.exists()) file = new java.io.File("c:/Users/Yakup Yılmaz/Desktop/stajprojesi/" + path);

        assertTrue(file.exists(), "TTE dosya yolu bulunamadı: " + path);
        byte[] bytes = java.nio.file.Files.readAllBytes(file.toPath());
        ParsedPortfolioReportDto report = parser.parse(bytes, "TTE");

        assertNotNull(report, "TTE rapor DTO null olamaz");
        assertEquals("TTE", report.getFundCode());
        assertNotNull(report.getReportDate(), "TTE rapor tarihi null olamaz");
        assertNotNull(report.getHoldings(), "Holdings listesi null olamaz");
        assertFalse(report.getHoldings().isEmpty(), "TTE için en az 1 hisse ayrıştırılmalıdır");

        System.out.println("================================================================================");
        System.out.printf(">>> 🔍 TTE %s RAPORU ANALİZİ:%n", period);
        System.out.printf("    Rapor Dosyası        : %s (%d bayt)%n", file.getName(), bytes.length);
        System.out.printf("    Rapor Tarihi         : %s%n", report.getReportDate());
        System.out.printf("    Toplam Portföy Değeri: %,.2f TL%n", report.getTotalNetAssetValue());
        System.out.printf("    Toplam Varlık Sayısı : %d adet%n", report.getItemCount());

        BigDecimal sumW = BigDecimal.ZERO;
        for (ParsedHoldingDto h : report.getHoldings()) {
            if (h.getWeightRatio() != null) sumW = sumW.add(h.getWeightRatio());
        }
        System.out.printf("    Toplam Portföy Ağırlığı: %%%.2f%n", sumW.multiply(BigDecimal.valueOf(100)));
        System.out.println("    Örnek Ayrıştırılan İlk 8 Teknoloji Hissesi:");
        for (int i = 0; i < Math.min(8, report.getHoldings().size()); i++) {
            ParsedHoldingDto h = report.getHoldings().get(i);
            System.out.printf("      [%d] %-6s | ISIN: %-12s | Lot: %,12.2f | Fiyat(P0): %8.2f TL | Ağırlık: %%%5.2f%n",
                    i + 1,
                    h.getTicker() != null ? h.getTicker() : "N/A",
                    h.getIsinCode() != null ? h.getIsinCode() : "N/A",
                    h.getNominalAmount() != null ? h.getNominalAmount().doubleValue() : 0.0,
                    h.getReportPrice() != null ? h.getReportPrice().doubleValue() : 0.0,
                    h.getWeightRatio() != null ? h.getWeightRatio().multiply(BigDecimal.valueOf(100)).doubleValue() : 0.0);
        }
        System.out.println("================================================================================");
    }

    @Test
    @DisplayName("5. Dinamik Sniffing: Şirket İsminden Bağımsız Saf Sayfa Düzeni ve TI2 Yatay Doğrulaması")
    void testDynamicSniffingWithoutCompanyNames() throws Exception {
        java.io.File ti2File = new java.io.File("c:/Users/Yakup Yılmaz/Desktop/stajprojesi/samples/TI2_2026_09.pdf");
        if (!ti2File.exists()) ti2File = new java.io.File("../samples/TI2_2026_09.pdf");
        if (!ti2File.exists()) ti2File = new java.io.File("../../samples/TI2_2026_09.pdf");

        Assumptions.assumeTrue(ti2File.exists(), "TI2 PDF bulunamadı.");
        byte[] ti2Bytes = java.nio.file.Files.readAllBytes(ti2File.toPath());

        try (org.apache.pdfbox.pdmodel.PDDocument doc = org.apache.pdfbox.Loader.loadPDF(ti2Bytes)) {
            org.apache.pdfbox.pdmodel.PDPage page = doc.getPage(0);
            org.apache.pdfbox.pdmodel.common.PDRectangle mb = page.getMediaBox();
            int rot = page.getRotation();
            boolean debugLandscape = (rot == 90 || rot == 270) ? mb.getHeight() > mb.getWidth() : mb.getWidth() > mb.getHeight();
            System.out.printf("DEBUG TI2: width=%f, height=%f, rot=%d, isLandscape=%b%n", mb.getWidth(), mb.getHeight(), rot, debugLandscape);

            org.apache.pdfbox.text.PDFTextStripper s = new org.apache.pdfbox.text.PDFTextStripper();
            s.setSortByPosition(true);
            String fullText = s.getText(doc);
            String[] lines = fullText.split("\\r?\\n");
            System.out.println("=== LINES 25 TO 75 OF TI2 ===");
            for (int i = 25; i < Math.min(75, lines.length); i++) {
                System.out.println("[" + i + "] " + lines[i]);
            }
        }

        // Fabrikaya açıkça İş Portföy yönetici adı verilmesine rağmen PDF Landscape olduğu için
        // IsPortfoyPdfParser (dikey) ASLA seçilmemeli, SPK/Tera Landscape stratejisi seçilmelidir!
        PdfParserStrategy strategy = parserFactory.getParser("TI2", "İŞ PORTFÖY YÖNETİMİ A.Ş.", ti2Bytes);
        assertNotNull(strategy);
        assertTrue(strategy.getStrategyName().contains("LANDSCAPE"),
                "TI2 bir İş Portföy fonu olmasına rağmen yatay SPK formatı içerdiğinden LANDSCAPE stratejisine yönlendirilmelidir! Seçilen: " + strategy.getStrategyName());

        // Bireysel Sniff Skorları Testi
        IsPortfoyPdfParser isParser = new IsPortfoyPdfParser();
        // Yatay olduğu için IsPortfoy skoru 0 olmalıdır
        int isScore = isParser.sniffScore(ti2Bytes, "İŞ PORTFÖY YÖNETİMİ A.Ş. FON PORTFÖY DEĞERİ", true);
        assertEquals(0, isScore, "Yatay PDF için IsPortfoyPdfParser sniff skoru 0 olmalıdır!");

        // Dikey metin testi (18 haneli referans numarası ile puan almalı, şirket isminden değil!)
        String portraitSampleWith18Digits = "PORTFÖY DEĞERİ TABLOSU BİRİM ALIŞ GÜNLÜK DEĞER 202608310000123456 115.500000 3,867,888.50";
        int ttePortraitScore = isParser.sniffScore(new byte[0], portraitSampleWith18Digits, false);
        assertTrue(ttePortraitScore >= 100, "18 haneli referans kodu ve İngilizce ondalık içeren dikey metin yüksek puan almalı! Alınan: " + ttePortraitScore);

        String portraitSampleWithoutDigits = "PORTFÖY DAĞILIM RAPORU İHRAÇ NOMİNAL 31,981,435.00";
        AtlasPdfParser atlasParser = new AtlasPdfParser();
        int atlasScore = atlasParser.sniffScore(new byte[0], portraitSampleWithoutDigits, false);
        assertTrue(atlasScore >= 100, "Atlas dikey formatı yüksek puan almalı! Alınan: " + atlasScore);
    }

    @Test
    @DisplayName("4. TTE Temmuz, Ağustos ve Eylül Raporlarının Kapsamlı Ayrıştırma ve Doğruluk Analizi")
    void testTteAllThreeMonthsDetailedAnalysis() throws Exception {
        String[] tteFiles = {
            "c:/Users/Yakup Yılmaz/Desktop/stajprojesi/samples/TTE_2026_07.pdf",
            "c:/Users/Yakup Yılmaz/Desktop/stajprojesi/samples/TTE_2026_08.pdf",
            "c:/Users/Yakup Yılmaz/Desktop/stajprojesi/samples/TTE_2026_09.pdf"
        };
        String[] periods = {"TEMMUZ 2026", "AĞUSTOS 2026", "EYLÜL 2026"};

        for (int i = 0; i < tteFiles.length; i++) {
            java.io.File file = new java.io.File(tteFiles[i]);
            if (!file.exists()) file = new java.io.File("../../" + tteFiles[i]);
            if (!file.exists()) {
                System.out.println("Dosya bulunamadı: " + tteFiles[i]);
                continue;
            }
            byte[] bytes = java.nio.file.Files.readAllBytes(file.toPath());

            boolean isLand = false;
            String t2 = "";
            try (org.apache.pdfbox.pdmodel.PDDocument doc = org.apache.pdfbox.Loader.loadPDF(bytes)) {
                org.apache.pdfbox.pdmodel.PDPage page0 = doc.getPage(0);
                int rot = page0.getRotation();
                float w = page0.getMediaBox().getWidth();
                float h = page0.getMediaBox().getHeight();
                isLand = (rot == 90 || rot == 270) ? h > w : w > h;
                System.out.printf("[%s] Pages: %d, Rot: %d, W: %.1f, H: %.1f, isLandscape: %b%n",
                        periods[i], doc.getNumberOfPages(), rot, w, h, isLand);

                org.apache.pdfbox.text.PDFTextStripper s = new org.apache.pdfbox.text.PDFTextStripper();
                s.setSortByPosition(true);
                s.setStartPage(2);
                s.setEndPage(2);
                t2 = s.getText(doc);
                System.out.println("--- FIRST 35 LINES OF PAGE 2 ---");
                String[] sampleLines2 = t2.split("\\r?\\n");
                for (int l = 0; l < Math.min(35, sampleLines2.length); l++) {
                    System.out.println("  P2_L" + l + ": " + sampleLines2[l]);
                }
            }

            PdfParserStrategy strategy = parserFactory.getParser("TTE", "İŞ PORTFÖY YÖNETİMİ A.Ş.", bytes);
            assertNotNull(strategy, "TTE strateji bulunamadı");
            assertEquals("IS_PORTFOY_STRATEGY", strategy.getStrategyName(), "TTE için IS_PORTFOY_STRATEGY seçilmeli!");

            ParsedPortfolioReportDto report = strategy.parse(bytes, "TTE");

            System.out.println("\n================================================================================");
            System.out.printf(">>> 🚀 TTE %s RAPORU AYRIŞTIRMA SONUÇLARI:%n", periods[i]);
            System.out.printf("    Seçilen Strateji     : %s%n", strategy.getStrategyName());
            System.out.printf("    Rapor Tarihi         : %s%n", report.getReportDate());
            System.out.printf("    Toplam Fon Büyüklüğü : %,.2f TL%n", report.getTotalNetAssetValue());
            System.out.printf("    Birim Pay Fiyatı     : %s TL%n", report.getUnitSharePrice());
            System.out.printf("    Katılma Payı Sayısı  : %,.0f adet%n", report.getTotalShareCount());
            System.out.printf("    Toplam Varlık Sayısı : %d adet%n", report.getItemCount());

            BigDecimal sumWeight = BigDecimal.ZERO;
            int equityCount = 0;
            BigDecimal equityWeight = BigDecimal.ZERO;

            for (ParsedHoldingDto h : report.getHoldings()) {
                if (h.getWeightRatio() != null) {
                    sumWeight = sumWeight.add(h.getWeightRatio());
                    if (h.getAssetClassHint() == com.fonmap.domain.enums.AssetClass.EQUITY) {
                        equityCount++;
                        equityWeight = equityWeight.add(h.getWeightRatio());
                    }
                }
            }

            System.out.printf("    Hisse Sayısı / Oranı : %d hisse / %%%.2f%n", equityCount, equityWeight.multiply(BigDecimal.valueOf(100)));
            System.out.printf("    Toplam Portföy Oranı : %%%.2f%n", sumWeight.multiply(BigDecimal.valueOf(100)));
            System.out.println("    İlk 5 Varlık:");
            for (int k = 0; k < Math.min(5, report.getHoldings().size()); k++) {
                ParsedHoldingDto h = report.getHoldings().get(k);
                System.out.printf("      [%d] %-6s | %-25s | Ağırlık: %%%.2f | Değer: %,.2f TL%n",
                        k + 1,
                        h.getTicker() != null ? h.getTicker() : "N/A",
                        h.getSecurityName() != null ? (h.getSecurityName().length() > 25 ? h.getSecurityName().substring(0, 25) : h.getSecurityName()) : "N/A",
                        h.getWeightRatio() != null ? h.getWeightRatio().multiply(BigDecimal.valueOf(100)).doubleValue() : 0.0,
                        h.getTotalValue() != null ? h.getTotalValue().doubleValue() : 0.0);
            }
        }
    }
}
