package com.fonmap.infrastructure.client.bigpara;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fonmap.infrastructure.client.dto.MarketPriceDto;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class BigparaPriceProviderTest {

    private BigparaPriceProvider priceProvider;

    @BeforeEach
    void setUp() {
        priceProvider = new BigparaPriceProvider(new ObjectMapper());
    }

    @Test
    @DisplayName("Bigpara canlı API'sinden THYAO fiyatı başarıyla çekilmeli")
    void shouldFetchLiveThyaoPriceFromBigpara() {
        MarketPriceDto dto = priceProvider.getPrice("THYAO");

        assertNotNull(dto, "DTO boş olmamalı");
        assertEquals("THYAO", dto.getSymbol());
        assertEquals("BIGPARA", dto.getSource());
        assertNotNull(dto.getCurrentPrice(), "Anlık fiyat dolu olmalı");
        assertNotNull(dto.getPreviousClose(), "Dünkü kapanış dolu olmalı");
        assertNotNull(dto.getDailyChangeRatio(), "Getiri yüzdesi hesaplanmış olmalı");

        assertTrue(dto.getCurrentPrice().compareTo(BigDecimal.ZERO) > 0, "Fiyat sıfırdan büyük olmalı");
        assertTrue(dto.getPreviousClose().compareTo(BigDecimal.ZERO) > 0, "Kapanış sıfırdan büyük olmalı");

        System.out.printf(">>> Bigpara Test Başarılı: %s | Anlık: %s TL | Dünkü: %s TL | Getiri: %%%s%n",
                dto.getSymbol(), dto.getCurrentPrice(), dto.getPreviousClose(),
                dto.getDailyChangeRatio().multiply(BigDecimal.valueOf(100)));
    }

    @Test
    @DisplayName("Bigpara '.IS' uzantılı sembolü (THYAO.IS) otomatik temizleyip çekebilmeli")
    void shouldCleanSymbolAndFetch() {
        MarketPriceDto dto = priceProvider.getPrice("THYAO.IS");

        assertNotNull(dto);
        assertEquals("THYAO", dto.getSymbol());
        assertTrue(dto.getCurrentPrice().compareTo(BigDecimal.ZERO) > 0);
    }

    @Test
    @DisplayName("Bigpara üzerinden toplu fiyat çekme (getPrices) çalışmalı")
    void shouldFetchMultiplePricesFromBigpara() {
        List<String> symbols = List.of("THYAO", "ASELS", "BIMAS");
        List<MarketPriceDto> prices = priceProvider.getPrices(symbols);

        assertNotNull(prices);
        assertEquals(3, prices.size(), "3 hissenin de fiyatı gelmeli");

        for (MarketPriceDto dto : prices) {
            System.out.printf(">>> Bigpara Toplu: %s = %s TL%n", dto.getSymbol(), dto.getCurrentPrice());
        }
    }
}
