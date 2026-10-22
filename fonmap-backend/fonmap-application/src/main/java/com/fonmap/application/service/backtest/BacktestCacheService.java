package com.fonmap.application.service.backtest;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.fonmap.application.service.backtest.dto.BacktestReportDto;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDate;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * ============================================================================
 * FONMAP — Backtest Sonuçları L1 RAM + L2 Redis Önbellek Servisi
 * ============================================================================
 *
 * Simülasyon hesaplamaları onlarca hissenin tarihsel kapanış fiyatlarını
 * ve ağırlık kayması formüllerini işlediğinden yüksek işlemci gücü gerektirir.
 *
 * Bu servis:
 * 1. L1 Bellek İçi (In-Memory ConcurrentHashMap) ile 0 ms yanıt süresi sunar.
 * 2. L2 Dağıtık Redis ('fonmap:backtest:{fundCode}:*') ile 24 saatlik TTL sağlar.
 * 3. Hata Toleransı: Redis erişilemez olsa bile sistem hata fırlatmaz,
 *    sessizce L1'e veya taze hesaplamaya geri çekilir (Graceful Degradation).
 * 4. Geçersiz Kılma (Eviction): Yeni bir PDF yüklendiğinde eski önbelleği anında temizler.
 */
@Slf4j
@Service
public class BacktestCacheService {

    public static final Duration DEFAULT_TTL = Duration.ofHours(24);
    private static final String KEY_PREFIX = "fonmap:backtest:";

    private final RedisTemplate<String, Object> redisTemplate;
    private final ObjectMapper objectMapper;
    private final Map<String, BacktestReportDto> memoryCache = new ConcurrentHashMap<>();

    public BacktestCacheService(
            @Autowired(required = false) @Qualifier("genericRedisTemplate") RedisTemplate<String, Object> redisTemplate) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = createObjectMapper();
    }

    private ObjectMapper createObjectMapper() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.registerModule(new JavaTimeModule());
        mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        return mapper;
    }

    /**
     * Önbellekten backtest raporunu arar (Önce L1 RAM, sonra L2 Redis).
     */
    public Optional<BacktestReportDto> get(String fundCode, LocalDate startDate, LocalDate endDate) {
        if (fundCode == null || fundCode.isBlank()) {
            return Optional.empty();
        }

        String key = buildKey(fundCode, startDate, endDate);

        // 1. L1 RAM Kontrolü
        BacktestReportDto inMemory = memoryCache.get(key);
        if (inMemory != null) {
            log.trace("[BacktestCacheService] L1 RAM Cache HIT: Key='{}'", key);
            return Optional.of(inMemory);
        }

        // 2. L2 Redis Kontrolü
        if (redisTemplate != null) {
            try {
                Object val = redisTemplate.opsForValue().get(key);
                if (val != null) {
                    BacktestReportDto dto = deserialize(val);
                    if (dto != null) {
                        memoryCache.put(key, dto);
                        log.debug("[BacktestCacheService] L2 Redis Cache HIT: Key='{}'", key);
                        return Optional.of(dto);
                    }
                }
            } catch (Exception e) {
                log.warn("[BacktestCacheService] Redis okuma hatası (Key='{}'): {}", key, e.getMessage());
            }
        }

        return Optional.empty();
    }

    /**
     * Backtest sonucunu hem L1 RAM'e hem L2 Redis'e kaydeder.
     */
    public void put(String fundCode, LocalDate startDate, LocalDate endDate, BacktestReportDto report) {
        if (fundCode == null || report == null) {
            return;
        }

        String key = buildKey(fundCode, startDate, endDate);
        memoryCache.put(key, report);

        if (redisTemplate != null) {
            try {
                String json = objectMapper.writeValueAsString(report);
                redisTemplate.opsForValue().set(key, json, DEFAULT_TTL);

                // Eğer bitiş tarihi güncel tarihse veya null ise ":latest" anahtarına da yaz
                if (endDate == null || endDate.equals(LocalDate.now())) {
                    String latestKey = buildKey(fundCode, null, null);
                    redisTemplate.opsForValue().set(latestKey, json, DEFAULT_TTL);
                    memoryCache.put(latestKey, report);
                }
                log.debug("[BacktestCacheService] Backtest sonucu önbelleğe yazıldı: Key='{}', TTL={}", key, DEFAULT_TTL);
            } catch (Exception e) {
                log.warn("[BacktestCacheService] Redis yazma hatası (Key='{}'): {}", key, e.getMessage());
            }
        }
    }

    /**
     * Belirli bir fona ait tüm backtest önbellek anahtarlarını siler.
     * (Yeni PDF yüklendiğinde çağrılır).
     */
    public void evict(String fundCode) {
        if (fundCode == null || fundCode.isBlank()) {
            return;
        }

        String normalized = fundCode.trim().toUpperCase();
        String pattern = KEY_PREFIX + normalized + ":*";

        // L1 RAM temizliği
        memoryCache.keySet().removeIf(k -> k.startsWith(KEY_PREFIX + normalized));

        // L2 Redis temizliği
        if (redisTemplate != null) {
            try {
                Set<String> keys = redisTemplate.keys(pattern);
                if (keys != null && !keys.isEmpty()) {
                    redisTemplate.delete(keys);
                    log.info("[BacktestCacheService] Fon '{}' için {} adet Redis önbellek anahtarı temizlendi.", normalized, keys.size());
                }
            } catch (Exception e) {
                log.warn("[BacktestCacheService] Redis temizleme hatası (Pattern='{}'): {}", pattern, e.getMessage());
            }
        }
    }

    private String buildKey(String fundCode, LocalDate startDate, LocalDate endDate) {
        String code = fundCode.trim().toUpperCase();
        if (startDate == null && endDate == null) {
            return KEY_PREFIX + code + ":latest";
        }
        String s = (startDate != null) ? startDate.toString() : "all";
        String e = (endDate != null) ? endDate.toString() : "now";
        return KEY_PREFIX + code + ":" + s + ":" + e;
    }

    private BacktestReportDto deserialize(Object val) {
        try {
            if (val instanceof BacktestReportDto dto) {
                return dto;
            }
            if (val instanceof String json) {
                return objectMapper.readValue(json, BacktestReportDto.class);
            }
            if (val instanceof Map<?, ?> map) {
                String json = objectMapper.writeValueAsString(map);
                return objectMapper.readValue(json, BacktestReportDto.class);
            }
        } catch (Exception e) {
            log.trace("[BacktestCacheService] Deserialization hatası: {}", e.getMessage());
        }
        return null;
    }
}
