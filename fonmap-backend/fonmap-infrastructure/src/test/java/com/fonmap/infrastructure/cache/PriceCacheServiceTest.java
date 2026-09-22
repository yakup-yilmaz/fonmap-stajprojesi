package com.fonmap.infrastructure.cache;

import com.fonmap.infrastructure.client.dto.MarketPriceDto;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class PriceCacheServiceTest {

    private PriceCacheService priceCacheService;

    @BeforeEach
    void setUp() {
        // Docker üzerinde 6379 portunda çalışan yerel Redis'e bağlanır
        LettuceConnectionFactory connectionFactory = new LettuceConnectionFactory("localhost", 6379);
        connectionFactory.afterPropertiesSet();

        RedisConfig redisConfig = new RedisConfig();
        RedisTemplate<String, MarketPriceDto> priceRedisTemplate = redisConfig.priceRedisTemplate(connectionFactory);

        priceCacheService = new PriceCacheService(priceRedisTemplate);
    }

    @Test
    @DisplayName("Redis'e yazılan hisse fiyatı başarıyla okunabilmeli ve TTL 60 saniye olmalı")
    void shouldPutAndGetPriceFromRedisWithTtl() {
        MarketPriceDto thyao = MarketPriceDto.of("THYAO", BigDecimal.valueOf(293.50), BigDecimal.valueOf(285.50), "YAHOO");

        // 1. Önbelleğe yaz
        priceCacheService.putPrice(thyao);

        // 2. Önbellekten oku
        Optional<MarketPriceDto> cachedOpt = priceCacheService.getPrice("THYAO");
        assertTrue(cachedOpt.isPresent(), "Fiyat Redis'te bulunmalı");

        MarketPriceDto cached = cachedOpt.get();
        assertEquals("THYAO", cached.getSymbol());
        assertEquals(0, BigDecimal.valueOf(293.50).compareTo(cached.getCurrentPrice()));
        assertEquals(0, BigDecimal.valueOf(285.50).compareTo(cached.getPreviousClose()));
        assertEquals("YAHOO", cached.getSource());

        // 3. TTL (yaşam süresi) kontrolü: 60 saniyeden küçük ve 0'dan büyük olmalı
        Long ttl = priceCacheService.getRemainingTtlSeconds("THYAO");
        assertNotNull(ttl);
        assertTrue(ttl > 0 && ttl <= 60, "TTL 60 saniye aralığında olmalı, gelen: " + ttl);

        System.out.printf(">>> Redis Test Başarılı: %s | Fiyat: %s TL | Kalan TTL: %d sn%n",
                cached.getSymbol(), cached.getCurrentPrice(), ttl);
    }

    @Test
    @DisplayName("Politika/Repo faizi Redis'e 24 saatlik (86400 sn) uzun TTL ile yazılmalı")
    void shouldSet24HourTtlForRepoRate() {
        MarketPriceDto repoDto = MarketPriceDto.ofRepo("REPO", BigDecimal.valueOf(0.37), "TCMB");

        priceCacheService.putPrice(repoDto);

        Long ttl = priceCacheService.getRemainingTtlSeconds("REPO");
        assertNotNull(ttl);
        // 24 saat (86400 sn) - 1 saaten (3600 sn) büyük olmalı
        assertTrue(ttl > 3600, "Repo faizinin TTL'i uzun (24 saat) olmalı, gelen: " + ttl);

        System.out.printf(">>> Redis Repo Faizi Testi Başarılı: Oran: %s | Kalan TTL: %d sn%n",
                repoDto.getCurrentPrice(), ttl);
    }

    @Test
    @DisplayName("Çoklu hisse sorgusu (multiGet) tek seferde tüm portföyü getirmeli")
    void shouldFetchMultiplePricesUsingMultiGet() {
        MarketPriceDto thyao = MarketPriceDto.of("THYAO", BigDecimal.valueOf(293.50), BigDecimal.valueOf(285.50), "YAHOO");
        MarketPriceDto asels = MarketPriceDto.of("ASELS", BigDecimal.valueOf(378.00), BigDecimal.valueOf(372.75), "YAHOO");
        MarketPriceDto bimas = MarketPriceDto.of("BIMAS", BigDecimal.valueOf(433.00), BigDecimal.valueOf(430.00), "YAHOO");

        // Toplu yazma
        priceCacheService.putPrices(List.of(thyao, asels, bimas));

        // Toplu okuma (multiGet)
        Map<String, MarketPriceDto> prices = priceCacheService.getPrices(List.of("THYAO", "ASELS", "BIMAS"));

        assertNotNull(prices);
        assertEquals(3, prices.size(), "3 hisse de gelmeli");
        assertEquals(0, BigDecimal.valueOf(293.50).compareTo(prices.get("THYAO").getCurrentPrice()));
        assertEquals(0, BigDecimal.valueOf(378.00).compareTo(prices.get("ASELS").getCurrentPrice()));
        assertEquals(0, BigDecimal.valueOf(433.00).compareTo(prices.get("BIMAS").getCurrentPrice()));

        System.out.printf(">>> Redis MultiGet Testi Başarılı: %d adet hisse tek ağ çağrısıyla çekildi.%n", prices.size());
    }

    @Test
    @DisplayName("Önbellekten hisse silindiğinde (evict) getPrice boş Optional dönmeli")
    void shouldEvictPriceFromCache() {
        MarketPriceDto dto = MarketPriceDto.of("TEMP_STOCK", BigDecimal.valueOf(100), BigDecimal.valueOf(95), "TEST");
        priceCacheService.putPrice(dto);

        assertTrue(priceCacheService.hasPrice("TEMP_STOCK"));

        // Sil
        priceCacheService.evictPrice("TEMP_STOCK");

        assertFalse(priceCacheService.hasPrice("TEMP_STOCK"));
        assertTrue(priceCacheService.getPrice("TEMP_STOCK").isEmpty());
    }
}
