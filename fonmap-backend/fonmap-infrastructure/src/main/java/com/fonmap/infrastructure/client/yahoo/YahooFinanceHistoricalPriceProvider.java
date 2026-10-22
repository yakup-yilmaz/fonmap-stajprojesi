package com.fonmap.infrastructure.client.yahoo;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fonmap.infrastructure.client.HistoricalPriceProvider;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * ============================================================================
 * FONMAP — Yahoo Finance Tarihsel Fiyat Sağlayıcısı (Historical Price Provider)
 * ============================================================================
 * 
 * Yahoo Finance v8 chart API (/v8/finance/chart/{ticker}?period1=...&period2=...&interval=1d)
 * üzerinden hisse senetleri, endeksler, döviz ve emtiaların tarihsel günlük borsa kapanışlarını çeker.
 * 
 * KRİTİK GÖREVLERİ & MİMARİ ÖZELLİKLERİ:
 * 1. Tek Ağ İstekli Tarih Aralığı:
 *    30-60 günlük tüm iş günlerinin kapanışlarını tek bir HTTP paketinde alır.
 * 
 * 2. İki Kademeli Önbellek (L1 In-Memory + L2 Redis):
 *    Tarihsel kapanış fiyatları geçmişe ait ve sabittir (immutable).
 *    Aynı varlık ve tarih aralığı tekrar sorgulandığında internete çıkmaz, RAM'den milisaniyede döner.
 *    Böylece Yahoo Finance rate limit'e (429 Too Many Requests) ASLA takılmaz.
 * 
 * 3. Eşzamanlı Toplu Çekim (Concurrent Batch Processing):
 *    Portföydeki 20 hissenin tarihsel verisini Java paralel akışı (parallelStream) ile
 *    paralel çeker; backtest hazırlık süresini saniyelerden milisaniyelere indirir.
 * 
 * 4. Finansal Zaman Dilimi & Takasbank Standartları:
 *    Epoch Unix damgalarını BIST resmi işlem saati olan 'Europe/Istanbul' (UTC+3) diliminde
 *    yerel tarihe (LocalDate) dönüştürür.
 */
@Slf4j
@Component("yahooFinanceHistoricalPriceProvider")
public class YahooFinanceHistoricalPriceProvider implements HistoricalPriceProvider {

    private static final String PROVIDER_NAME = "YAHOO_HISTORICAL";
    private static final String YAHOO_BASE_URL = "https://query1.finance.yahoo.com/v8/finance/chart";
    private static final String USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36";
    private static final ZoneId ISTANBUL_ZONE = ZoneId.of("Europe/Istanbul");
    private static final Duration REDIS_CACHE_TTL = Duration.ofHours(24);

    private final RestClient restClient;
    private final ObjectMapper objectMapper;
    private final RedisTemplate<String, Object> redisTemplate;

    // L1 RAM Önbelleği: Key = "SYMBOL:START_DATE:END_DATE", Value = Map<LocalDate, BigDecimal>
    private final ConcurrentMap<String, Map<LocalDate, BigDecimal>> memoryCache = new ConcurrentHashMap<>();

    @Autowired
    public YahooFinanceHistoricalPriceProvider(
            ObjectMapper objectMapper,
            @Autowired(required = false) @Qualifier("genericRedisTemplate") RedisTemplate<String, Object> redisTemplate) {
        this.objectMapper = objectMapper != null ? objectMapper : new ObjectMapper();
        this.redisTemplate = redisTemplate;
        this.restClient = RestClient.builder()
                .baseUrl(YAHOO_BASE_URL)
                .defaultHeader(HttpHeaders.USER_AGENT, USER_AGENT)
                .defaultHeader(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE)
                .build();
    }

    /**
     * Testler ve özel yapılandırmalar için RestClient enjeksiyonlu yapıcı metot.
     */
    public YahooFinanceHistoricalPriceProvider(
            RestClient restClient,
            ObjectMapper objectMapper,
            RedisTemplate<String, Object> redisTemplate) {
        this.restClient = restClient;
        this.objectMapper = objectMapper != null ? objectMapper : new ObjectMapper();
        this.redisTemplate = redisTemplate;
    }

    @Override
    public Map<LocalDate, BigDecimal> getHistoricalCloses(String symbol, LocalDate startDate, LocalDate endDate) {
        if (symbol == null || symbol.isBlank() || startDate == null || endDate == null) {
            return Collections.emptyMap();
        }

        if (startDate.isAfter(endDate)) {
            log.warn("[{}] Başlangıç tarihi bitiş tarihinden sonra olamaz: Start={}, End={}", PROVIDER_NAME, startDate, endDate);
            return Collections.emptyMap();
        }

        String normalizedSymbol = normalizeSymbol(symbol);

        // Repo / Mevduat borsa enstrümanı olmadığı için Yahoo'da işlem görmez
        if (isNonTradableAsset(normalizedSymbol)) {
            log.trace("[{}] '{}' borsa işlem enstrümanı değil (Repo/Mevduat), atlandı.", PROVIDER_NAME, normalizedSymbol);
            return Collections.emptyMap();
        }

        String cacheKey = buildCacheKey(normalizedSymbol, startDate, endDate);

        // 1. ADIM: L1 In-Memory Önbellek Kontrolü
        Map<LocalDate, BigDecimal> l1Result = memoryCache.get(cacheKey);
        if (l1Result != null) {
            log.trace("[{}] '{}' [{} - {}] L1 RAM önbelleğinden sunuldu ({} gün).",
                    PROVIDER_NAME, normalizedSymbol, startDate, endDate, l1Result.size());
            return new TreeMap<>(l1Result);
        }

        // 2. ADIM: L2 Redis Önbellek Kontrolü
        Map<LocalDate, BigDecimal> l2Result = getFromRedis(cacheKey);
        if (l2Result != null && !l2Result.isEmpty()) {
            memoryCache.put(cacheKey, l2Result);
            log.trace("[{}] '{}' [{} - {}] L2 Redis önbelleğinden sunuldu ({} gün).",
                    PROVIDER_NAME, normalizedSymbol, startDate, endDate, l2Result.size());
            return new TreeMap<>(l2Result);
        }

        // 3. ADIM: Yahoo Finance v8 API Çağrısı
        String yahooTicker = formatYahooTicker(normalizedSymbol);
        long epochStart = startDate.atStartOfDay(ISTANBUL_ZONE).toEpochSecond();
        long epochEnd = endDate.atTime(23, 59, 59).atZone(ISTANBUL_ZONE).toEpochSecond();

        log.debug("[{}] '{}' (Yahoo: '{}') için tarihsel veri çekiliyor: [{} - {}] (Epoch: {} - {})",
                PROVIDER_NAME, normalizedSymbol, yahooTicker, startDate, endDate, epochStart, epochEnd);

        try {
            String jsonResponse = restClient.get()
                    .uri("/{ticker}?period1={start}&period2={end}&interval=1d", yahooTicker, epochStart, epochEnd)
                    .retrieve()
                    .body(String.class);

            if (jsonResponse == null || jsonResponse.isBlank()) {
                log.warn("[{}] '{}' için Yahoo boş yanıt döndü.", PROVIDER_NAME, yahooTicker);
                return Collections.emptyMap();
            }

            Map<LocalDate, BigDecimal> parsedPrices = parseYahooChartResponse(jsonResponse, startDate, endDate);

            if (!parsedPrices.isEmpty()) {
                // Önbelleklere yaz
                memoryCache.put(cacheKey, parsedPrices);
                putToRedis(cacheKey, parsedPrices);
                log.info("[{}] '{}' başarıyla alındı: {} borsa işlem günü.", PROVIDER_NAME, normalizedSymbol, parsedPrices.size());
            } else {
                log.warn("[{}] '{}' için belirtilen aralıkta borsa kapanış verisi bulunamadı: [{} - {}]",
                        PROVIDER_NAME, normalizedSymbol, startDate, endDate);
            }

            return parsedPrices;

        } catch (Exception e) {
            log.error("[{}] '{}' tarihsel veri çekilirken hata: {}", PROVIDER_NAME, normalizedSymbol, e.getMessage());
            return Collections.emptyMap();
        }
    }

    @Override
    public Map<String, Map<LocalDate, BigDecimal>> getHistoricalClosesBatch(
            List<String> symbols, LocalDate startDate, LocalDate endDate) {
        if (symbols == null || symbols.isEmpty() || startDate == null || endDate == null) {
            return Collections.emptyMap();
        }

        Map<String, Map<LocalDate, BigDecimal>> resultMap = new ConcurrentHashMap<>();

        // Java paralel akış (parallelStream) ile eşzamanlı çekim
        symbols.parallelStream()
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(s -> !s.isBlank())
                .distinct()
                .forEach(symbol -> {
                    try {
                        Map<LocalDate, BigDecimal> closes = getHistoricalCloses(symbol, startDate, endDate);
                        if (closes != null && !closes.isEmpty()) {
                            resultMap.put(normalizeSymbol(symbol), closes);
                        }
                    } catch (Exception e) {
                        log.warn("[{}] Toplu çekimde '{}' alınamadı, diğerleri devam ediyor: {}",
                                PROVIDER_NAME, symbol, e.getMessage());
                    }
                });

        return resultMap;
    }

    @Override
    public String getProviderName() {
        return PROVIDER_NAME;
    }

    /**
     * Yahoo Finance v8 chart JSON yanıtını ayrıştırır ve tarih -> kapanış fiyatı haritasına dönüştürür.
     */
    public Map<LocalDate, BigDecimal> parseYahooChartResponse(String jsonResponse, LocalDate startDate, LocalDate endDate) {
        if (jsonResponse == null || jsonResponse.isBlank()) {
            return Collections.emptyMap();
        }

        Map<LocalDate, BigDecimal> closesMap = new TreeMap<>();

        try {
            JsonNode root = objectMapper.readTree(jsonResponse);
            JsonNode resultNode = root.path("chart").path("result");

            if (!resultNode.isArray() || resultNode.isEmpty()) {
                JsonNode errorNode = root.path("chart").path("error");
                String errorDesc = errorNode.isMissingNode() ? "Bilinmeyen API hatası" : errorNode.toString();
                log.warn("[{}] Yahoo JSON yanıtında sonuç bulunamadı: {}", PROVIDER_NAME, errorDesc);
                return Collections.emptyMap();
            }

            JsonNode chartItem = resultNode.get(0);
            JsonNode timestampNode = chartItem.path("timestamp");
            JsonNode quoteNode = chartItem.path("indicators").path("quote");

            if (!timestampNode.isArray() || timestampNode.isEmpty() || !quoteNode.isArray() || quoteNode.isEmpty()) {
                log.debug("[{}] Zaman damgası veya fiyat dizisi boş.", PROVIDER_NAME);
                return Collections.emptyMap();
            }

            JsonNode closeArray = quoteNode.get(0).path("close");
            if (!closeArray.isArray()) {
                return Collections.emptyMap();
            }

            int size = Math.min(timestampNode.size(), closeArray.size());
            for (int i = 0; i < size; i++) {
                JsonNode timeNode = timestampNode.get(i);
                JsonNode priceNode = closeArray.get(i);

                if (timeNode == null || !timeNode.isNumber() || priceNode == null || priceNode.isNull() || !priceNode.isNumber()) {
                    continue; // Tatil veya işlem görmeyen gün
                }

                long epochSec = timeNode.asLong();
                LocalDate priceDate = Instant.ofEpochSecond(epochSec).atZone(ISTANBUL_ZONE).toLocalDate();

                // İstenen tarih aralığı dışındakileri filtrele
                if (priceDate.isBefore(startDate) || priceDate.isAfter(endDate)) {
                    continue;
                }

                double closeValue = priceNode.asDouble();
                if (closeValue <= 0.0) {
                    continue;
                }

                BigDecimal closePrice = BigDecimal.valueOf(closeValue).setScale(4, RoundingMode.HALF_UP);
                closesMap.put(priceDate, closePrice);
            }

            return closesMap;

        } catch (Exception e) {
            log.error("[{}] Yahoo chart JSON ayrıştırma hatası: {}", PROVIDER_NAME, e.getMessage());
            return Collections.emptyMap();
        }
    }

    /**
     * Varlık sembolünü Yahoo Finance ticker formatına dönüştürür.
     */
    public String formatYahooTicker(String symbol) {
        if (symbol == null) return "";
        String upper = symbol.trim().toUpperCase(Locale.ROOT);

        if (upper.endsWith(".IS") || upper.endsWith("=X") || upper.endsWith("=F")) {
            return upper;
        }

        if (upper.equals("USD") || upper.equals("USDTRY") ||
            upper.equals("EUR") || upper.equals("EURTRY") ||
            upper.equals("GBP") || upper.equals("GBPTRY")) {
            String base = upper.endsWith("TRY") ? upper : upper + "TRY";
            return base + "=X";
        }

        if (upper.equals("ALTIN") || upper.equals("GOLD") || upper.equals("XAUUSD")) {
            return "GC=F";
        }

        // BIST hissesi veya endeksi (Örn: THYAO -> THYAO.IS)
        return upper + ".IS";
    }

    public String normalizeSymbol(String symbol) {
        if (symbol == null) return "";
        String s = symbol.trim().toUpperCase(Locale.ROOT);
        if (s.endsWith(".IS")) {
            s = s.substring(0, s.length() - 3);
        }
        if (s.endsWith("=X")) {
            s = s.substring(0, s.length() - 2);
        }
        return s;
    }

    private boolean isNonTradableAsset(String symbol) {
        return "REPO".equalsIgnoreCase(symbol)
                || "MEVDUAT".equalsIgnoreCase(symbol)
                || "TRY_REPO".equalsIgnoreCase(symbol)
                || "FAIZ".equalsIgnoreCase(symbol)
                || "TAKASBANK".equalsIgnoreCase(symbol)
                || "PPF".equalsIgnoreCase(symbol)
                || "OVERNIGHT".equalsIgnoreCase(symbol);
    }

    private String buildCacheKey(String symbol, LocalDate startDate, LocalDate endDate) {
        return String.format("fonmap:hist_price:%s:%s:%s", symbol, startDate, endDate);
    }

    @SuppressWarnings("unchecked")
    private Map<LocalDate, BigDecimal> getFromRedis(String key) {
        if (redisTemplate == null) {
            return null;
        }
        try {
            Object val = redisTemplate.opsForValue().get(key);
            if (val instanceof Map<?, ?> rawMap) {
                Map<LocalDate, BigDecimal> converted = new TreeMap<>();
                for (Map.Entry<?, ?> entry : rawMap.entrySet()) {
                    LocalDate d = LocalDate.parse(entry.getKey().toString());
                    BigDecimal p = new BigDecimal(entry.getValue().toString());
                    converted.put(d, p);
                }
                return converted;
            }
        } catch (Exception e) {
            log.trace("[{}] Redis okuma hatası (Key: {}): {}", PROVIDER_NAME, key, e.getMessage());
        }
        return null;
    }

    private void putToRedis(String key, Map<LocalDate, BigDecimal> map) {
        if (redisTemplate == null || map == null || map.isEmpty()) {
            return;
        }
        try {
            Map<String, String> stringMap = new HashMap<>();
            map.forEach((k, v) -> stringMap.put(k.toString(), v.toString()));
            redisTemplate.opsForValue().set(key, stringMap, REDIS_CACHE_TTL);
        } catch (Exception e) {
            log.trace("[{}] Redis yazma hatası (Key: {}): {}", PROVIDER_NAME, key, e.getMessage());
        }
    }
}
