package com.fonmap.infrastructure.client.yahoo;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fonmap.infrastructure.client.dto.MarketPriceDto;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class YahooFinancePriceProviderTest {

    private YahooFinancePriceProvider priceProvider;

    @BeforeEach
    void setUp() {
        priceProvider = new YahooFinancePriceProvider(new ObjectMapper());
    }

    @Test
    @DisplayName("Yahoo Finance canlı API'sinden THYAO fiyatı başarıyla çekilmeli")
    void shouldFetchLiveThyaoPrice() {
        MarketPriceDto dto = priceProvider.getPrice("THYAO");

        assertNotNull(dto, "DTO boş olmamalı");
        assertEquals("THYAO", dto.getSymbol());
        assertEquals("YAHOO_FINANCE", dto.getSource());
        assertNotNull(dto.getCurrentPrice(), "Anlık fiyat dolu olmalı");
        assertNotNull(dto.getPreviousClose(), "Dünkü kapanış dolu olmalı");
        assertNotNull(dto.getDailyChangeRatio(), "Getiri yüzdesi hesaplanmış olmalı");

        assertTrue(dto.getCurrentPrice().compareTo(BigDecimal.ZERO) > 0, "Fiyat sıfırdan büyük olmalı");
        assertTrue(dto.getPreviousClose().compareTo(BigDecimal.ZERO) > 0, "Kapanış sıfırdan büyük olmalı");

        System.out.printf(">>> Test Başarılı: %s | Anlık: %s TL | Dünkü: %s TL | Getiri: %%%s%n",
                dto.getSymbol(), dto.getCurrentPrice(), dto.getPreviousClose(),
                dto.getDailyChangeRatio().multiply(BigDecimal.valueOf(100)));
    }

    @Test
    @DisplayName("Yahoo Finance canlı API'sinden Dolar (USDTRY) kuru başarıyla çekilmeli")
    void shouldFetchLiveUsdTryRate() {
        MarketPriceDto dto = priceProvider.getPrice("USDTRY");

        assertNotNull(dto);
        assertEquals("USDTRY", dto.getSymbol());
        assertTrue(dto.getCurrentPrice().compareTo(BigDecimal.valueOf(20)) > 0, "Dolar kuru 20'den büyük olmalı");

        System.out.printf(">>> Dolar Testi Başarılı: %s | Kur: %s TL%n", dto.getSymbol(), dto.getCurrentPrice());
    }

    @Test
    @DisplayName("Toplu fiyat çekme (getPrices) birden fazla hisseyi eşzamanlı getirmeli")
    void shouldFetchMultiplePricesConcurrently() {
        List<String> symbols = List.of("THYAO", "ASELS", "BIMAS");
        List<MarketPriceDto> prices = priceProvider.getPrices(symbols);

        assertNotNull(prices);
        assertFalse(prices.isEmpty(), "Fiyat listesi boş olmamalı");
        assertEquals(3, prices.size(), "3 hissenin de fiyatı gelmeli");

        for (MarketPriceDto dto : prices) {
            System.out.printf(">>> Toplu Çekim: %s = %s TL%n", dto.getSymbol(), dto.getCurrentPrice());
        }
    }
}
