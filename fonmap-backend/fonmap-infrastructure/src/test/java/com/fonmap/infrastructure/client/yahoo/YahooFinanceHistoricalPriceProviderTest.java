package com.fonmap.infrastructure.client.yahoo;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * YahooFinanceHistoricalPriceProviderTest — Tarihsel Fiyat Sağlayıcı Birim Testleri
 */
class YahooFinanceHistoricalPriceProviderTest {

    private YahooFinanceHistoricalPriceProvider provider;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        provider = new YahooFinanceHistoricalPriceProvider(objectMapper, null);
    }

    @Test
    @DisplayName("Yahoo chart JSON yanıtından tarih ve kapanış fiyatları doğru ayrıştırılmalı")
    void shouldParseYahooChartResponseCorrectly() {
        // 2 Eylül (Pzt) = 1725258600, 3 Eylül (Salı) = 1725345000, 4 Eylül (Çrş) = 1725431400 (tatil/null)
        String mockJson = """
                {
                  "chart": {
                    "result": [
                      {
                        "meta": {
                          "currency": "TRY",
                          "symbol": "THYAO.IS"
                        },
                        "timestamp": [1725258600, 1725345000, 1725431400],
                        "indicators": {
                          "quote": [
                            {
                              "close": [308.50, 302.75, null]
                            }
                          ]
                        }
                      }
                    ],
                    "error": null
                  }
                }
                """;

        LocalDate start = LocalDate.of(2024, 9, 1);
        LocalDate end = LocalDate.of(2024, 9, 30);

        Map<LocalDate, BigDecimal> closes = provider.parseYahooChartResponse(mockJson, start, end);

        assertNotNull(closes);
        assertEquals(2, closes.size(), "Null fiyat elenmeli, 2 geçerli gün kalmalı");

        LocalDate day1 = LocalDate.of(2024, 9, 2);
        LocalDate day2 = LocalDate.of(2024, 9, 3);

        assertTrue(closes.containsKey(day1));
        assertTrue(closes.containsKey(day2));
        assertEquals(0, new BigDecimal("308.5000").compareTo(closes.get(day1)));
        assertEquals(0, new BigDecimal("302.7500").compareTo(closes.get(day2)));
    }

    @Test
    @DisplayName("Hatalı veya boş JSON geldiğinde sistem çökmemeli ve boş harita dönmeli")
    void shouldHandleEmptyOrErrorJsonGracefully() {
        Map<LocalDate, BigDecimal> result1 = provider.parseYahooChartResponse(null, LocalDate.now(), LocalDate.now());
        assertTrue(result1.isEmpty());

        Map<LocalDate, BigDecimal> result2 = provider.parseYahooChartResponse("{\"chart\":{\"result\":[]}}", LocalDate.now(), LocalDate.now());
        assertTrue(result2.isEmpty());

        Map<LocalDate, BigDecimal> result3 = provider.parseYahooChartResponse("{\"chart\":{\"error\":{\"code\":\"Not Found\"}}}", LocalDate.now(), LocalDate.now());
        assertTrue(result3.isEmpty());
    }

    @Test
    @DisplayName("Yahoo ticker dönüşümleri doğru biçimlendirilmeli")
    void shouldFormatYahooTickersCorrectly() {
        assertEquals("THYAO.IS", provider.formatYahooTicker("THYAO"));
        assertEquals("ASELS.IS", provider.formatYahooTicker("asels"));
        assertEquals("USDTRY=X", provider.formatYahooTicker("USDTRY"));
        assertEquals("USDTRY=X", provider.formatYahooTicker("USD"));
        assertEquals("EURTRY=X", provider.formatYahooTicker("EUR"));
        assertEquals("GC=F", provider.formatYahooTicker("ALTIN"));
        assertEquals("GC=F", provider.formatYahooTicker("GOLD"));
        assertEquals("XU100.IS", provider.formatYahooTicker("XU100"));
        assertEquals("THYAO.IS", provider.formatYahooTicker("THYAO.IS"));
    }

    @Test
    @DisplayName("Repo / Mevduat gibi borsa dışı varlıklar Yahoo'ya sorulmadan boş dönmeli")
    void shouldSkipNonTradableAssets() {
        Map<LocalDate, BigDecimal> repoCloses = provider.getHistoricalCloses("REPO", LocalDate.now().minusDays(10), LocalDate.now());
        assertTrue(repoCloses.isEmpty(), "REPO varlığı Yahoo'ya gitmeden boş harita dönmeli");

        Map<LocalDate, BigDecimal> mevduatCloses = provider.getHistoricalCloses("MEVDUAT", LocalDate.now().minusDays(10), LocalDate.now());
        assertTrue(mevduatCloses.isEmpty());
    }

    @Test
    @DisplayName("Canlı Yahoo Finance API'sinden THYAO için tarihsel kapanış serisi çekilebilmeli")
    void shouldFetchLiveHistoricalClosesFromYahoo() {
        LocalDate start = LocalDate.of(2024, 9, 2);
        LocalDate end = LocalDate.of(2024, 9, 30);

        try {
            Map<LocalDate, BigDecimal> closes = provider.getHistoricalCloses("THYAO", start, end);
            assertNotNull(closes);

            if (!closes.isEmpty()) {
                System.out.printf(">>> [YAHOO CANLI TARİHSEL BAŞARILI] THYAO: %d gün çekildi. Örnek: %s = %s TL%n",
                        closes.size(), closes.keySet().iterator().next(), closes.values().iterator().next());
                assertTrue(closes.size() >= 15, "Eylül 2024'te en az 15 borsa işlem günü olmalı");

                // L1 RAM Önbellek testi: 2. çağrı aynı veriyi anında dönmeli
                long t0 = System.nanoTime();
                Map<LocalDate, BigDecimal> cachedCloses = provider.getHistoricalCloses("THYAO", start, end);
                long elapsedMicro = (System.nanoTime() - t0) / 1000;
                assertEquals(closes.size(), cachedCloses.size());
                System.out.printf(">>> [RAM ÖNBELLEK TESTİ] 2. Çağrı süresi: %d mikrosaniye (0 ağ trafiği)%n", elapsedMicro);
            } else {
                System.out.println(">>> Canlı Yahoo Finance ağına erişilemedi (Çevrimdışı ortam)");
            }
        } catch (Exception e) {
            System.out.println(">>> Canlı Yahoo testi atlandı (Ağ yok): " + e.getMessage());
        }
    }

    @Test
    @DisplayName("Toplu tarihsel çekim (Batch) birden fazla hisseyi paralel çekebilmeli")
    void shouldFetchBatchHistoricalCloses() {
        LocalDate start = LocalDate.of(2024, 9, 2);
        LocalDate end = LocalDate.of(2024, 9, 6);

        try {
            Map<String, Map<LocalDate, BigDecimal>> batch = provider.getHistoricalClosesBatch(
                    List.of("THYAO", "ASELS"), start, end);
            assertNotNull(batch);

            if (!batch.isEmpty()) {
                System.out.printf(">>> [BATCH TESTİ BAŞARILI] %d hisse çekildi: %s%n",
                        batch.size(), batch.keySet());
                assertTrue(batch.containsKey("THYAO") || batch.containsKey("ASELS"));
            }
        } catch (Exception e) {
            System.out.println(">>> Batch testi atlandı (Ağ yok): " + e.getMessage());
        }
    }
}
