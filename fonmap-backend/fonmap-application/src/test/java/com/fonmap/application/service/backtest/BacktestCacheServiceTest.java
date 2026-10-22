package com.fonmap.application.service.backtest;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.fonmap.application.service.backtest.dto.BacktestReportDto;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * BacktestCacheService L1 RAM ve L2 Redis Önbellek Birim Testleri.
 */
@ExtendWith(MockitoExtension.class)
class BacktestCacheServiceTest {

    @Mock
    private RedisTemplate<String, Object> redisTemplate;

    @Mock
    private ValueOperations<String, Object> valueOperations;

    private BacktestCacheService cacheService;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        objectMapper.registerModule(new JavaTimeModule());
    }

    @Test
    @DisplayName("L1 Bellek İçi Önbellek: Redis olmadan da RAM üzerinden 0 ms'de çalışmalıdır")
    void testInMemoryFallbackWithoutRedis() {
        // RedisTemplate = null senaryosu
        cacheService = new BacktestCacheService(null);

        LocalDate start = LocalDate.of(2026, 8, 1);
        LocalDate end = LocalDate.of(2026, 9, 30);
        BacktestReportDto report = BacktestReportDto.builder()
                .fundCode("THF")
                .fundTitle("Tera Portföy Hisse Senedi")
                .startDate(start)
                .endDate(end)
                .totalDaysTested(42)
                .meanAbsoluteError(new BigDecimal("0.0012"))
                .directionalAccuracy(new BigDecimal("100.0"))
                .status("SUCCESS")
                .build();

        // 1. Önbelleğe yaz
        cacheService.put("THF", start, end, report);

        // 2. Önbellekten oku
        Optional<BacktestReportDto> result = cacheService.get("THF", start, end);

        assertTrue(result.isPresent(), "L1 RAM önbelleğinde kayıt bulunmalıdır");
        assertEquals("THF", result.get().getFundCode());
        assertEquals(42, result.get().getTotalDaysTested());
    }

    @Test
    @DisplayName("L2 Redis Entegrasyonu: Rapor JSON olarak Redis'e yazılmalı ve okunabilmelidir")
    void testRedisIntegration_PutAndGet() throws Exception {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        cacheService = new BacktestCacheService(redisTemplate);

        LocalDate start = LocalDate.of(2026, 9, 1);
        LocalDate end = LocalDate.of(2026, 9, 22);
        BacktestReportDto report = BacktestReportDto.builder()
                .fundCode("TTE")
                .fundTitle("İş Portföy BIST Teknoloji")
                .startDate(start)
                .endDate(end)
                .totalDaysTested(16)
                .status("SUCCESS")
                .build();

        String json = objectMapper.writeValueAsString(report);
        when(valueOperations.get(eq("fonmap:backtest:TTE:2026-09-01:2026-09-22"))).thenReturn(json);

        // Redis'ten get çağrısı
        Optional<BacktestReportDto> cached = cacheService.get("TTE", start, end);

        assertTrue(cached.isPresent());
        assertEquals("TTE", cached.get().getFundCode());
        assertEquals(16, cached.get().getTotalDaysTested());
    }

    @Test
    @DisplayName("Eviction: Yeni PDF geldiğinde fona ait tüm önbellek temizlenmelidir")
    void testEvictClearsCache() {
        when(redisTemplate.keys(eq("fonmap:backtest:DFI:*"))).thenReturn(Set.of("fonmap:backtest:DFI:latest", "fonmap:backtest:DFI:2026-09-01:2026-09-30"));
        cacheService = new BacktestCacheService(redisTemplate);

        cacheService.evict("DFI");

        verify(redisTemplate, times(1)).delete(any(Set.class));
    }

    @Test
    @DisplayName("Çökme Koruması: Redis hata verirse sistem patlamamalı, boş dönmelidir")
    void testGracefulDegradationOnRedisError() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get(anyString())).thenThrow(new RuntimeException("Redis connection timeout"));

        cacheService = new BacktestCacheService(redisTemplate);

        assertDoesNotThrow(() -> {
            Optional<BacktestReportDto> result = cacheService.get("KHA", LocalDate.now().minusDays(30), LocalDate.now());
            assertTrue(result.isEmpty(), "Hata durumunda sessizce Optional.empty dönmelidir");
        });
    }
}
