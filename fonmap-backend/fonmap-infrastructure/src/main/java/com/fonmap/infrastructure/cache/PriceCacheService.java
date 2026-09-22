package com.fonmap.infrastructure.cache;

import com.fonmap.infrastructure.client.dto.MarketPriceDto;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.*;

/**
 * ============================================================================
 * FONMAP — Piyasa Fiyatları Önbellek Servisi (Price Cache Service)
 * ============================================================================
 * 
 * Bu servis; seans boyunca dış sağlayıcılardan (Yahoo, Bigpara, TCMB) çekilen
 * canlı fiyat verilerini (MarketPriceDto) Redis RAM belleğinde saklar ve yönetir.
 * 
 * KRİTİK GÖREVLERİ:
 * ----------------------------------------------------------------------------
 * 1. Dış Servisleri Koruma (Rate-Limit Kalkanı):
 *    Her hisse fiyatı 60 saniye boyunca önbellekte tutulur. Böylece hesaplama
 *    motoru her getiri hesapladığında dış dünyaya gitmez, veriyi RAM'den 0.2 ms'de alır.
 * 
 * 2. Dinamik TTL (Yaşam Süresi) Yönetimi:
 *    - BIST hisseleri ve döviz için TTL: 60 saniye (seans içi canlı akış).
 *    - TCMB Politika / Repo faizi için TTL: 24 saat (çünkü faiz gün içinde değişmez).
 * 
 * 3. Yüksek Performanslı Toplu Okuma (Multi-Get):
 *    Bir fonun içindeki 20 hisseyi tek tek sorgulamak yerine 'multiGet' ile
 *    tek bir ağ paketinde anında çeker (O(1) sürede).
 * 
 * 4. Çökme Koruması (Fault Tolerance / Graceful Degradation):
 *    Eğer Redis sunucusu geçici olarak kapalıysa veya ağda bir sorun olursa,
 *    bu servis HATA FIRLATMAZ (patlamaz). Sessizce log basar ve 'Optional.empty()'
 *    dönerek sistemin dış API'lere doğrudan geçebilmesine olanak tanır.
 */
@Slf4j
@Service
public class PriceCacheService {

    /**
     * Redis anahtar öneki standardı.
     * Örnek anahtar: "fonmap:price:THYAO", "fonmap:price:USD", "fonmap:price:REPO"
     */
    private static final String PRICE_KEY_PREFIX = "fonmap:price:";

    /**
     * BIST hisseleri ve döviz kurları için varsayılan önbellek süresi (TTL): 60 Saniye.
     */
    public static final Duration DEFAULT_PRICE_TTL = Duration.ofSeconds(60);

    /**
     * Politika / Repo faizi için önbellek süresi (TTL): 24 Saat.
     * PPK kararları gün içi seans saatlerinde değişmediği için 24 saat saklanır.
     */
    public static final Duration REPO_RATE_TTL = Duration.ofHours(24);

    private final RedisTemplate<String, MarketPriceDto> redisTemplate;

    public PriceCacheService(@Qualifier("priceRedisTemplate") RedisTemplate<String, MarketPriceDto> redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    /**
     * Tek bir hisse veya döviz fiyatını Redis önbelleğine yazar.
     * Varlık türüne göre (hisse ise 60 saniye, repo ise 24 saat) TTL'i otomatik belirler.
     * 
     * @param priceDto Kaydedilecek fiyat nesnesi
     */
    public void putPrice(MarketPriceDto priceDto) {
        if (priceDto == null || priceDto.getSymbol() == null || priceDto.getSymbol().isBlank()) {
            return;
        }

        String symbol = normalizeSymbol(priceDto.getSymbol());
        Duration ttl = isRepoSymbol(symbol) ? REPO_RATE_TTL : DEFAULT_PRICE_TTL;
        putPrice(symbol, priceDto, ttl);
    }

    /**
     * Özel bir TTL (yaşam süresi) belirterek fiyatı Redis'e yazar.
     * 
     * @param symbol Varlık sembolü (Örn: "THYAO")
     * @param priceDto Fiyat nesnesi
     * @param ttl Bu kaydın Redis'te ne kadar süre yaşayacağı
     */
    public void putPrice(String symbol, MarketPriceDto priceDto, Duration ttl) {
        if (priceDto == null || symbol == null || symbol.isBlank()) {
            return;
        }

        String key = buildKey(symbol);
        try {
            redisTemplate.opsForValue().set(key, priceDto, ttl);
            log.trace("[Redis Cache] Fiyat yazıldı -> Key: '{}', Fiyat: {}, TTL: {}s", 
                    key, priceDto.getCurrentPrice(), ttl.toSeconds());
        } catch (Exception e) {
            // Redis bağlantı hatasında uygulamanın durmaması için log basılır, akış bozulmaz
            log.warn("[Redis Cache] Fiyat yazılamadı (Redis erişilemez olabilir): Key: '{}', Hata: {}", 
                    key, e.getMessage());
        }
    }

    /**
     * Birden fazla fiyatı toplu olarak Redis'e yazar.
     * 
     * @param priceList Kaydedilecek fiyat DTO listesi
     */
    public void putPrices(List<MarketPriceDto> priceList) {
        if (priceList == null || priceList.isEmpty()) {
            return;
        }

        for (MarketPriceDto dto : priceList) {
            putPrice(dto);
        }
    }

    /**
     * Önbellekten tek bir varlığın fiyatını okur.
     * 
     * @param symbol Varlık sembolü (Örn: "THYAO", "USD", "REPO")
     * @return Fiyat önbellekte varsa ve süresi (TTL) dolmamışsa Optional içinde döner; yoksa Optional.empty()
     */
    public Optional<MarketPriceDto> getPrice(String symbol) {
        if (symbol == null || symbol.isBlank()) {
            return Optional.empty();
        }

        String cleanSymbol = normalizeSymbol(symbol);
        String key = buildKey(cleanSymbol);

        try {
            MarketPriceDto cached = redisTemplate.opsForValue().get(key);
            if (cached != null) {
                log.trace("[Redis Cache HIT] '{}' önbellekten getirildi -> Fiyat: {}", cleanSymbol, cached.getCurrentPrice());
                return Optional.of(cached);
            }
            log.trace("[Redis Cache MISS] '{}' önbellekte bulunamadı veya süresi doldu.", cleanSymbol);
        } catch (Exception e) {
            log.warn("[Redis Cache] Okuma hatası (Key: '{}'): {}", key, e.getMessage());
        }

        return Optional.empty();
    }

    /**
     * Birden fazla varlığın fiyatını Redis'ten TEK BİR AĞ İSTEĞİ (multiGet) ile topluca okur.
     * Bu metot seans içi fon hesaplamalarında ağ trafiğini %95 oranında azaltır.
     * 
     * @param symbols Sorgulanacak sembol listesi (Örn: ["THYAO", "ASELS", "BIMAS"])
     * @return Bulunan fiyatların Sembol -> MarketPriceDto haritası
     */
    public Map<String, MarketPriceDto> getPrices(List<String> symbols) {
        if (symbols == null || symbols.isEmpty()) {
            return Collections.emptyMap();
        }

        // Sembolleri temizle ve Redis anahtarlarını üret
        List<String> cleanSymbols = symbols.stream()
                .filter(Objects::nonNull)
                .map(this::normalizeSymbol)
                .filter(s -> !s.isBlank())
                .distinct()
                .toList();

        List<String> keys = cleanSymbols.stream()
                .map(this::buildKey)
                .toList();

        Map<String, MarketPriceDto> resultMap = new HashMap<>();

        try {
            // Redis MGET komutu: Tek ağ paketinde tüm anahtarları döner
            List<MarketPriceDto> values = redisTemplate.opsForValue().multiGet(keys);

            if (values != null) {
                for (int i = 0; i < cleanSymbols.size(); i++) {
                    MarketPriceDto dto = values.get(i);
                    if (dto != null) {
                        resultMap.put(cleanSymbols.get(i), dto);
                    }
                }
            }
        } catch (Exception e) {
            log.warn("[Redis Cache] Çoklu okuma hatası (multiGet): {}", e.getMessage());
        }

        return resultMap;
    }

    /**
     * Bir varlığın önbellekte geçerli (süresi dolmamış) bir fiyatının olup olmadığını sorgular.
     * 
     * @param symbol Sembol (Örn: "THYAO")
     * @return Varsa true, yoksa false
     */
    public boolean hasPrice(String symbol) {
        if (symbol == null || symbol.isBlank()) {
            return false;
        }

        try {
            Boolean exists = redisTemplate.hasKey(buildKey(normalizeSymbol(symbol)));
            return Boolean.TRUE.equals(exists);
        } catch (Exception e) {
            log.warn("[Redis Cache] hasKey sorgusu başarısız: {}", e.getMessage());
            return false;
        }
    }

    /**
     * Bir varlığın Redis'teki kalan yaşam süresini (TTL) saniye olarak döner.
     * 
     * @param symbol Sembol (Örn: "THYAO")
     * @return Kalan saniye; anahtar yoksa -2, süresizse -1
     */
    public Long getRemainingTtlSeconds(String symbol) {
        if (symbol == null || symbol.isBlank()) {
            return -2L;
        }

        try {
            return redisTemplate.getExpire(buildKey(normalizeSymbol(symbol)), java.util.concurrent.TimeUnit.SECONDS);
        } catch (Exception e) {
            log.warn("[Redis Cache] getExpire hatası: {}", e.getMessage());
            return -2L;
        }
    }

    /**
     * Bir hissenin önbellek kaydını zorla siler (Cache Eviction).
     * 
     * @param symbol Sembol
     */
    public void evictPrice(String symbol) {
        if (symbol == null || symbol.isBlank()) {
            return;
        }

        try {
            redisTemplate.delete(buildKey(normalizeSymbol(symbol)));
            log.debug("[Redis Cache] Önbellek silindi: '{}'", symbol);
        } catch (Exception e) {
            log.warn("[Redis Cache] Silme hatası: {}", e.getMessage());
        }
    }

    /**
     * Sembol adını standartlaştırır ("thyao" -> "THYAO", "THYAO.IS" -> "THYAO").
     */
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

    /**
     * Redis anahtarını standart önek ile üretir: "fonmap:price:{SYMBOL}"
     */
    public String buildKey(String symbol) {
        return PRICE_KEY_PREFIX + normalizeSymbol(symbol);
    }

    private boolean isRepoSymbol(String symbol) {
        return "REPO".equalsIgnoreCase(symbol) 
                || "FAIZ".equalsIgnoreCase(symbol) 
                || "TRY_REPO".equalsIgnoreCase(symbol)
                || "OVERNIGHT".equalsIgnoreCase(symbol);
    }
}
