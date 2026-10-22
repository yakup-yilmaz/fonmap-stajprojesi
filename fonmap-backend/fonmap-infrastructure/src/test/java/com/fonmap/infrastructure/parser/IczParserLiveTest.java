package com.fonmap.infrastructure.parser;

import com.fonmap.infrastructure.parser.dto.ParsedHoldingDto;
import com.fonmap.infrastructure.parser.dto.ParsedPortfolioReportDto;
import com.fonmap.infrastructure.parser.strategy.TeraPdfParser;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.math.BigDecimal;
import java.net.URI;
import java.net.URLConnection;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class IczParserLiveTest {

    @Test
    @DisplayName("ICZ Ak Portföy Raporunun TeraPdfParser (SPK Standart Şablonu) ile Ayrıştırılması")
    void testParseIczWithTeraParser() throws Exception {
        String pdfUrl = "https://www.kap.org.tr/tr/api/file/download/4028328c9d4a029c019d53277c5273d6";
        System.out.println("=== KAP'tan ICZ PDF'i İndiriliyor ===");
        System.out.println("URL: " + pdfUrl);

        URLConnection conn = URI.create(pdfUrl).toURL().openConnection();
        conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36");
        
        byte[] pdfBytes;
        try (InputStream in = conn.getInputStream()) {
            pdfBytes = in.readAllBytes();
        }

        System.out.println("İndirme Başarılı! Boyut: " + pdfBytes.length + " bayt");
        assertTrue(pdfBytes.length > 1000, "PDF içeriği boş olamaz");

        com.fonmap.infrastructure.parser.strategy.SpkLandscapePdfParser parser = new com.fonmap.infrastructure.parser.strategy.SpkLandscapePdfParser();
        System.out.println("=== SpkLandscapePdfParser (SPK Standart Stratejisi) Çalıştırılıyor ===");
        ParsedPortfolioReportDto result = parser.parse(pdfBytes, "ICZ");

        assertNotNull(result, "Ayrıştırma sonucu null olamaz");
        System.out.println("Ayrıştırma Tamamlandı!");
        System.out.println("Rapor Tarihi: " + result.getReportDate());
        System.out.println("Toplam Portföy Değeri (TNV): " + result.getTotalNetAssetValue());
        System.out.println("Hisse Senedi Oranı: " + result.getStockRatio());
        System.out.println("Toplam Varlık Sayısı: " + (result.getHoldings() != null ? result.getHoldings().size() : 0));

        assertNotNull(result.getHoldings(), "Holdings listesi boş olamaz");
        assertFalse(result.getHoldings().isEmpty(), "Holdings en az 1 varlık içermeli");

        BigDecimal totalWeight = BigDecimal.ZERO;
        System.out.println("\n--- AYRIŞTIRILAN HİSSELER LİSTESİ ---");
        for (ParsedHoldingDto h : result.getHoldings()) {
            System.out.printf("Ticker: %-10s | Tür: %-8s | Nominal: %-12s | Rayiç: %-14s | Ağırlık: %%%.4f%n",
                    h.getTicker(),
                    h.getAssetClassHint(),
                    h.getNominalAmount(),
                    h.getTotalValue(),
                    h.getWeightRatio() != null ? h.getWeightRatio().doubleValue() * 100 : 0.0);

            if (h.getWeightRatio() != null) {
                totalWeight = totalWeight.add(h.getWeightRatio());
            }
        }

        System.out.printf("%n=== TOPLAM AĞIRLIK: %%%.4f ===%n", totalWeight.doubleValue() * 100);

        // Sanity Check kontrolleri
        assertTrue(result.getHoldings().size() >= 5, "En az 5 hisse senedi bulunmalı");
        assertNotNull(result.getTotalNetAssetValue(), "Toplam portföy değeri bulunmalı");
        assertTrue(result.getTotalNetAssetValue().compareTo(BigDecimal.ZERO) > 0, "Toplam değer pozitif olmalı");

        // Content Sniffing Doğrulaması: Bilinmeyen fon kodu ve boş yönetici ile factory testi
        com.fonmap.infrastructure.parser.strategy.SpkLandscapePdfParser landscapeParser = new com.fonmap.infrastructure.parser.strategy.SpkLandscapePdfParser();
        com.fonmap.infrastructure.parser.factory.PdfParserFactory factory = new com.fonmap.infrastructure.parser.factory.PdfParserFactory(
                java.util.List.of(
                        landscapeParser,
                        new com.fonmap.infrastructure.parser.strategy.IsPortfoyPdfParser(),
                        new com.fonmap.infrastructure.parser.strategy.PardusPdfParser(),
                        new com.fonmap.infrastructure.parser.strategy.AtlasPdfParser()
                )
        );
        // Bilinmeyen yeni bir fon gibi sorgula (Yönetici yok, fon kodu listede yok):
        com.fonmap.infrastructure.parser.strategy.PdfParserStrategy sniffedStrategy = factory.getParser("BILINMEYEN_FON", null, pdfBytes);
        assertNotNull(sniffedStrategy, "Content sniffing bir strateji seçmeli");
        assertEquals("SPK_LANDSCAPE_STRATEGY", sniffedStrategy.getStrategyName(), "Standart SPK yatay formatı koklanarak seçilmeli");
        System.out.println("=== Content Sniffing Testi BAŞARILI: " + sniffedStrategy.getStrategyName() + " seçildi! ===");
    }
}
