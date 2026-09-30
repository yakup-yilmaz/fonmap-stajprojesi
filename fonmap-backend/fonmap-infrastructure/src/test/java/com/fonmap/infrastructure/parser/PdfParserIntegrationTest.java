package com.fonmap.infrastructure.parser;

import com.fonmap.infrastructure.client.kap.KapClient;
import com.fonmap.infrastructure.client.kap.dto.KapPdfDto;
import com.fonmap.infrastructure.parser.dto.ParsedHoldingDto;
import com.fonmap.infrastructure.parser.dto.ParsedPortfolioReportDto;
import com.fonmap.infrastructure.parser.factory.PdfParserFactory;
import com.fonmap.infrastructure.parser.strategy.*;
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

            // 2. Fabrikadan bu fona uygun uzman stratejiyi iste
            PdfParserStrategy strategy = parserFactory.getParser(fundCode, null);
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
}
