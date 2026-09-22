package com.fonmap.infrastructure.client.tcmb;

import com.fonmap.infrastructure.client.dto.MarketPriceDto;
import com.fonmap.infrastructure.client.exception.PriceProviderException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class TcmbPriceProviderTest {

    private TcmbPriceProvider priceProvider;

    @BeforeEach
    void setUp() {
        priceProvider = new TcmbPriceProvider();
    }

    @Test
    @DisplayName("TCMB canlı XML bülteninden USD/TRY kuru başarıyla çekilmeli")
    void shouldFetchLiveUsdPriceFromTcmb() {
        MarketPriceDto dto = priceProvider.getPrice("USD");

        assertNotNull(dto, "DTO boş olmamalı");
        assertEquals("USD", dto.getSymbol());
        assertEquals("TCMB", dto.getSource());
        assertNotNull(dto.getCurrentPrice(), "TCMB USD kuru dolu olmalı");
        assertTrue(dto.getCurrentPrice().compareTo(BigDecimal.valueOf(10)) > 0, "Dolar kuru 10 TL'den büyük olmalı");

        System.out.printf(">>> TCMB Test Başarılı: %s | Satış Kuru: %s TL | Kaynak: %s%n",
                dto.getSymbol(), dto.getCurrentPrice(), dto.getSource());
    }

    @Test
    @DisplayName("TCMB farklı sembol formatlarını ('USDTRY', 'USDTRY=X', 'USD/TRY') otomatik normalize etmeli")
    void shouldNormalizeVariousCurrencySymbols() {
        MarketPriceDto dto1 = priceProvider.getPrice("USDTRY");
        MarketPriceDto dto2 = priceProvider.getPrice("USDTRY=X");
        MarketPriceDto dto3 = priceProvider.getPrice("USD/TRY");

        assertNotNull(dto1);
        assertNotNull(dto2);
        assertNotNull(dto3);
        assertEquals("USD", dto1.getSymbol());
        assertEquals("USD", dto2.getSymbol());
        assertEquals("USD", dto3.getSymbol());
        assertEquals(dto1.getCurrentPrice(), dto2.getCurrentPrice());
    }

    @Test
    @DisplayName("TCMB üzerinden toplu döviz kurları (USD, EUR, GBP) tek XML indirmesiyle çekilmeli")
    void shouldFetchMultipleCurrenciesFromTcmb() {
        List<String> symbols = List.of("USD", "EUR", "GBP");
        List<MarketPriceDto> prices = priceProvider.getPrices(symbols);

        assertNotNull(prices);
        assertEquals(3, prices.size(), "3 para birimi de gelmeli");

        for (MarketPriceDto dto : prices) {
            System.out.printf(">>> TCMB Toplu: %s = %s TL%n", dto.getSymbol(), dto.getCurrentPrice());
        }
    }

    @Test
    @DisplayName("TCMB bülteninde olmayan geçersiz sembol sorgulandığında PriceProviderException fırlatılmalı")
    void shouldThrowExceptionWhenSymbolNotFound() {
        assertThrows(PriceProviderException.class, () -> priceProvider.getPrice("INVALID_CURRENCY"));
    }

    @Test
    @DisplayName("TCMB politika ve repo faiz oranı otomatik olarak çekilmeli ve günlük getiri hesaplanmalı")
    void shouldFetchLivePolicyRateFromTcmb() {
        MarketPriceDto dto = priceProvider.getPrice("REPO");

        assertNotNull(dto, "DTO boş olmamalı");
        assertEquals("REPO", dto.getSymbol());
        assertEquals("TCMB", dto.getSource());
        assertNotNull(dto.getCurrentPrice(), "Faiz oranı dolu olmalı");
        assertNotNull(dto.getDailyChangeRatio(), "Günlük tahakkuk oranı dolu olmalı");

        // Faiz oranı %10 ile %100 arasında makul bir değer olmalı (0.10 - 1.00)
        assertTrue(dto.getCurrentPrice().compareTo(BigDecimal.valueOf(0.10)) > 0);
        assertTrue(dto.getCurrentPrice().compareTo(BigDecimal.valueOf(1.00)) < 0);

        // Günlük tahakkuk oranı (yıllık faiz / 365) sıfırdan büyük olmalı
        assertTrue(dto.getDailyChangeRatio().compareTo(BigDecimal.ZERO) > 0);

        System.out.printf(">>> TCMB Politika Faizi Testi Başarılı: Yıllık Faiz: %%%s | Günlük Repo Getirisi: %%%s%n",
                dto.getCurrentPrice().multiply(BigDecimal.valueOf(100)),
                dto.getDailyChangeRatio().multiply(BigDecimal.valueOf(100)));
    }

    @Test
    @DisplayName("TCMB toplu çekimde hem döviz (USD) hem de repo faizini aynı anda dönebilmeli")
    void shouldFetchMixedCurrenciesAndRepo() {
        List<String> symbols = List.of("USD", "EUR", "REPO");
        List<MarketPriceDto> prices = priceProvider.getPrices(symbols);

        assertNotNull(prices);
        assertEquals(3, prices.size());

        for (MarketPriceDto dto : prices) {
            System.out.printf(">>> TCMB Karma Toplu: %s | Fiyat/Oran: %s | Günlük Değişim/Nema: %%%s%n",
                    dto.getSymbol(), dto.getCurrentPrice(),
                    dto.getDailyChangeRatio().multiply(BigDecimal.valueOf(100)));
        }
    }
}
