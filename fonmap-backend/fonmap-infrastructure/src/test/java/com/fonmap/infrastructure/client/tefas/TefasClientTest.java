package com.fonmap.infrastructure.client.tefas;

import com.fonmap.infrastructure.client.tefas.dto.TefasFundDto;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * TefasClientTest — TEFAS İstemcisi ve JSON Ayrıştırma Birim Testleri
 * ===================================================================
 *
 * Bu test sınıfı; TefasClient'ın TEFAS'tan dönen resmi JSON verilerini
 * (milisaniye veya metin tarih formatı, 6 basamaklı BigDecimal fiyatlar,
 * günlük getiri hesabı) hatasız ve güvenle ayrıştırdığını doğrular.
 */
class TefasClientTest {

    private TefasClient tefasClient;

    @BeforeEach
    void setUp() {
        tefasClient = new TefasClient();
    }

    @Test
    @DisplayName("Epoch Milisaniye Tarih Formatlı TEFAS JSON Yanıtı Ayrıştırma Testi")
    void testParseTefasResponse_EpochMillisDate() {
        // TEFAS bazen tarihi '1726952400000' (Epoch ms) olarak döner
        String mockJson = """
                {
                  "data": [
                    {
                      "TARIH": "1726952400000",
                      "FIYAT": 3.456789123,
                      "TEDPAYSAYISI": 150000000.0,
                      "PORTFOYBUYUKLUK": 518518350.50
                    }
                  ]
                }
                """;

        List<TefasFundDto> results = tefasClient.parseTefasResponse(mockJson, "THF");

        assertNotNull(results);
        assertEquals(1, results.size());

        TefasFundDto dto = results.get(0);
        assertEquals("THF", dto.getFundCode());
        assertNotNull(dto.getPriceDate());
        // 6 basamağa yuvarlanmış olmalı: 3.456789123 -> 3.456789
        assertEquals(new BigDecimal("3.456789"), dto.getUnitPrice());
        assertEquals(0, new BigDecimal("150000000").compareTo(dto.getOutstandingShares()));
        assertEquals(0, new BigDecimal("518518350.50").compareTo(dto.getTotalPortfolioValue()));
        assertNotNull(dto.getFetchedAt());

        System.out.printf(">>> [TEST BAŞARILI] Epoch Tarihli TEFAS Verisi: Tarih=%s, Fiyat=%s TL%n",
                dto.getPriceDate(), dto.getUnitPrice());
    }

    @Test
    @DisplayName("'dd.MM.yyyy' Metin Formatlı TEFAS JSON Yanıtı Ayrıştırma Testi")
    void testParseTefasResponse_FormattedDateString() {
        // TEFAS bazen de doğrudan '22.09.2026' gibi metin formatında döner
        String mockJson = """
                {
                  "data": [
                    {
                      "TARIH": "22.09.2026",
                      "FIYAT": 4.125000,
                      "TEDPAYSAYISI": 85000000,
                      "PORTFOYBUYUKLUK": 350625000
                    }
                  ]
                }
                """;

        List<TefasFundDto> results = tefasClient.parseTefasResponse(mockJson, "TLY");

        assertNotNull(results);
        assertEquals(1, results.size());

        TefasFundDto dto = results.get(0);
        assertEquals("TLY", dto.getFundCode());
        assertEquals(LocalDate.of(2026, 9, 22), dto.getPriceDate());
        assertEquals(new BigDecimal("4.125000"), dto.getUnitPrice());

        System.out.printf(">>> [TEST BAŞARILI] Metin Tarihli TEFAS Verisi: Tarih=%s, Fiyat=%s TL%n",
                dto.getPriceDate(), dto.getUnitPrice());
    }

    @Test
    @DisplayName("Çok Günlü Veride Günlük Getiri Oranı (r = (P_t - P_{t-1}) / P_{t-1}) Hesaplama Testi")
    void testDailyReturnCalculation_MultipleDays() {
        // 1. Gün: 100 TL, 2. Gün: 101.50 TL -> Getiri = +%1.50 (+0.015000)
        String mockJson = """
                {
                  "data": [
                    {
                      "TARIH": "21.09.2026",
                      "FIYAT": 100.000000,
                      "TEDPAYSAYISI": 1000000,
                      "PORTFOYBUYUKLUK": 100000000
                    },
                    {
                      "TARIH": "22.09.2026",
                      "FIYAT": 101.500000,
                      "TEDPAYSAYISI": 1000000,
                      "PORTFOYBUYUKLUK": 101500000
                    }
                  ]
                }
                """;

        List<TefasFundDto> results = tefasClient.parseTefasResponse(mockJson, "TTE");

        assertEquals(2, results.size());
        // 1. günün öncesi olmadığı için günlük getirisi null olmalı
        assertNull(results.get(0).getDailyReturn());
        // 2. günün resmi TEFAS getirisi: (101.5 - 100) / 100 = 0.015000
        assertNotNull(results.get(1).getDailyReturn());
        assertEquals(new BigDecimal("0.015000"), results.get(1).getDailyReturn());

        System.out.printf(">>> [TEST BAŞARILI] TEFAS Resmi Günlük Getiri Hesabı: %%%s (Oran: %s)%n",
                results.get(1).getDailyReturn().multiply(BigDecimal.valueOf(100)),
                results.get(1).getDailyReturn());
    }

    @Test
    @DisplayName("Boş veya Hatalı JSON Geldiğinde Sistemin Çökmemesi (Fail-Safe) Testi")
    void testEmptyAndMalformedJson_SafeHandling() {
        // 1. Boş veri dizisi
        List<TefasFundDto> emptyData = tefasClient.parseTefasResponse("{\"data\": []}", "THF");
        assertTrue(emptyData.isEmpty());

        // 2. Null veya boş string
        List<TefasFundDto> nullData = tefasClient.parseTefasResponse(null, "THF");
        assertTrue(nullData.isEmpty());

        // 3. Geçersiz JSON formatı
        List<TefasFundDto> brokenJson = tefasClient.parseTefasResponse("{bozuk_json...}", "THF");
        assertTrue(brokenJson.isEmpty());
    }

    @Test
    @DisplayName("Canlı TEFAS Birim Pay Fiyatı Çekme Testi (TTE)")
    void testLiveTefasConnection_TTE() {
        try {
            Optional<TefasFundDto> result = tefasClient.fetchLatestFundPrice("TTE");
            if (result.isPresent()) {
                TefasFundDto dto = result.get();
                System.out.printf(">>> [CANLI TEFAS TTE BAŞARILI] Fon: %s | Birim Pay Fiyatı: %s TL | Tarih: %s | Portföy Büyüklüğü: %s TL | Pay Adedi: %s%n",
                        dto.getFundCode(), dto.getUnitPrice(), dto.getPriceDate(), dto.getTotalPortfolioValue(), dto.getOutstandingShares());
                assertNotNull(dto.getUnitPrice(), "TTE birim fiyatı boş olmamalı");
                assertTrue(dto.getUnitPrice().compareTo(BigDecimal.ZERO) > 0, "TTE birim fiyatı 0'dan büyük olmalı");
            } else {
                System.out.println(">>> Canlı TEFAS TTE yanıtı dönmedi (Ağ / Güvenlik filtresi veya bakım modu).");
            }
        } catch (Exception e) {
            System.out.println(">>> Canlı TEFAS TTE bağlantı hatası: " + e.getMessage());
        }
    }

    @Test
    @DisplayName("Canlı TEFAS Tarihsel Fiyat Çekme Testi (fetchHistoricalPrices - TTE)")
    void testDiscoverHistoricalApi() {
        LocalDate start = LocalDate.of(2026, 8, 1);
        LocalDate end = LocalDate.of(2026, 8, 31);

        List<TefasFundDto> results = tefasClient.fetchHistoricalPrices("TTE", start, end);

        System.out.printf("🎉🎉🎉 [fetchHistoricalPrices TTE] Alınan Gün Sayısı: %d%n", results.size());
        for (int i = 0; i < Math.min(5, results.size()); i++) {
            TefasFundDto d = results.get(i);
            System.out.printf("  -> Tarih: %s | Fiyat: %s TL | Günlük Getiri: %%%s%n",
                    d.getPriceDate(), d.getUnitPrice(),
                    d.getDailyReturn() != null ? d.getDailyReturn().multiply(BigDecimal.valueOf(100)) : "0");
        }

        assertFalse(results.isEmpty(), "TTE Ağustos ayı verisi boş olmamalı!");
        assertTrue(results.size() >= 15, "İşlem günleri en az 15 gün olmalı (Ağustos)");
        assertNotNull(results.get(0).getUnitPrice());
    }

    @Test
    @DisplayName("Dinamik Kurucu Şirket Çıkarımı Testi (Sıfır Hardcode / SPK Şablonu)")
    void testDeduceManagerFromTitle_Generic() {
        // 1. Ak Portföy (BDY)
        String ak = tefasClient.deduceManagerFromTitle("AK PORTFÖY BIST 100 DIŞI ŞİRKETLER HİSSE SENEDİ (TL) FONU (HİSSE SENEDİ YOĞUN FON)");
        assertEquals("Ak Portföy Yönetimi A.Ş.", ak);

        // 2. Garanti BBVA Portföy
        String garanti = tefasClient.deduceManagerFromTitle("GARANTİ BBVA PORTFÖY HİSSE SENEDİ FONU");
        assertEquals("Garanti BBVA Portföy Yönetimi A.Ş.", garanti);

        // 3. İş Portföy
        String is = tefasClient.deduceManagerFromTitle("İŞ PORTFÖY BIST TEKNOLOJİ ENDEKSİ FONU");
        assertEquals("İş Portföy Yönetimi A.Ş.", is);

        // 4. Kuveyt Türk Portföy
        String kuveyt = tefasClient.deduceManagerFromTitle("KUVEYT TÜRK PORTFÖY KATILIM FONU");
        assertEquals("Kuveyt Türk Portföy Yönetimi A.Ş.", kuveyt);

        // 5. Bilinmeyen / Piyasaya Yeni Giren Şirket (XYZ Portföy)
        String yeni = tefasClient.deduceManagerFromTitle("XYZ PORTFÖY YENİ NESİL TEKNOLOJİ FONU");
        assertEquals("Xyz Portföy Yönetimi A.Ş.", yeni);
    }
}
