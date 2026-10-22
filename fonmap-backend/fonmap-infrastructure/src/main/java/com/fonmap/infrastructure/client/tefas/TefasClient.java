package com.fonmap.infrastructure.client.tefas;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fonmap.infrastructure.client.tefas.dto.TefasFundDto;
import com.fonmap.infrastructure.client.tefas.dto.TefasFundProfileDto;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/**
 * TefasClient — TEFAS Resmi Kapanış Fiyatı ve Portföy Büyüklüğü İstemcisi
 * =======================================================================
 *
 * SİSTEMDEKİ ROLÜ:
 * Fonmap platformunun "Gece Resmi Hakemi / Mutabakat İstemcisi"dir.
 *
 * NEDEN VAR?
 * Gün boyu 10:00 - 18:10 arasında hisse senetlerinin borsa hareketlerine göre
 * ürettiğimiz tahmini net getirinin doğruluğunu ölçmek için TEFAS'ın gece
 * 23:00'te
 * yayımladığı resmi kapanış fiyatına ihtiyaç duyarız.
 *
 * TEFAS RESMİ REST API UÇ NOKTASI:
 * - URL: https://www.tefas.gov.tr/api/DB/BindHistoryInfo
 * - Metot: HTTP POST (x-www-form-urlencoded)
 * - Parametreler:
 * fontip: "YAT" (Yatırım Fonları)
 * fonkod: "THF" (Fon kodu)
 * bastarih: "22.09.2026" (dd.MM.yyyy)
 * bittarih: "22.09.2026" (dd.MM.yyyy)
 *
 * KRİTİK FİNANSAL KURALLAR:
 * 1. 6 Basamak Hassasiyet: Katılma payı fiyatları virgülden sonra en az 6
 * basamak
 * (Örn: 3.456789 TL) olarak saklanır (RoundingMode.HALF_UP).
 * 2. Resmi Getiri Formülü: Günlük getiri iki günün TEFAS kapanışından
 * hesaplanır:
 * r = (P_t - P_{t-1}) / P_{t-1}
 * 3. Hata Toleransı (Resilience): TEFAS gece bakım saatlerinde (23:00 - 00:00)
 * bazen
 * 5-10 dakika yanıt vermeyebilir. İstemci sistemi düşürmez; kontrollü şekilde
 * loglar.
 */
@Component
@Slf4j
public class TefasClient {

    /** TEFAS/BIST takvimi Türkiye saatine göre işler; JVM varsayılan saat diliminden bağımsız olmalı. */
    private static final ZoneId ISTANBUL_ZONE = ZoneId.of("Europe/Istanbul");

    private static final String TEFAS_BASE_URL = "https://www.tefas.gov.tr";
    private static final String TEFAS_HISTORY_ENDPOINT = "/api/funds/fonFiyatBilgiGetir";
    private static final String TEFAS_LEGACY_ENDPOINT = "/api/DB/BindHistoryInfo";
    private static final String TEFAS_MODERN_ENDPOINT = "/api/funds/fonBilgiGetir";
    private static final DateTimeFormatter TEFAS_DATE_FORMATTER = DateTimeFormatter.ofPattern("dd.MM.yyyy");

    private final WebClient webClient;
    private final ObjectMapper objectMapper;

    private record CacheEntry(TefasFundDto dto, Instant expiresAt) {
    }

    private final java.util.concurrent.ConcurrentMap<String, CacheEntry> cache = new java.util.concurrent.ConcurrentHashMap<>();
    private static final Duration CACHE_TTL = Duration.ofMinutes(5);

    /**
     * Varsayılan Yapıcı Metot (Default Constructor).
     * TEFAS'ın modern REST API ve F5 WAF filtrelerine uygun HTTP başlıklarıyla WebClient'ı yapılandırır:
     * - Origin / Referer: https://www.tefas.gov.tr
     * - Content-Type: application/json
     * - 15 saniyelik yanıt zaman aşımı (Timeout)
     */
    public TefasClient() {
        this.objectMapper = new ObjectMapper();

        HttpClient httpClient = HttpClient.create()
                .responseTimeout(Duration.ofSeconds(15));

        this.webClient = WebClient.builder()
                .baseUrl(TEFAS_BASE_URL)
                .clientConnector(new ReactorClientHttpConnector(httpClient))
                .defaultHeader("User-Agent",
                        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36")
                .defaultHeader("Origin", "https://www.tefas.gov.tr")
                .defaultHeader("Referer", "https://www.tefas.gov.tr/tr/fon-verileri")
                .defaultHeader("Accept", "application/json, text/plain, */*")
                .defaultHeader("Accept-Language", "tr-TR,tr;q=0.9,en;q=0.8")
                .defaultHeader("X-Requested-With", "XMLHttpRequest")
                .build();
    }

    /**
     * Testler ve Harici Konfigürasyon İçin Özelleştirilmiş Yapıcı Metot.
     */
    public TefasClient(WebClient webClient, ObjectMapper objectMapper) {
        this.webClient = webClient;
        this.objectMapper = objectMapper != null ? objectMapper : new ObjectMapper();
    }

    /**
     * 1. ANA GÖREV: Belirli Bir Tarihteki Resmi Fon Kapanış Verisini ve Getirisini Çeker.
     * Gece mutabakat ve retry zamanlayıcısı tarafından periyodik olarak çağrılır.
     *
     * @param fundCode Fon kodu (Örn: "THF", "TLY", "TTE", "KHA", "TI2")
     * @param date     Resmi fiyatın ait olduğu işlem günü tarihi
     * @return Bulunursa ve o güne ait fiyat açıklanmışsa TefasFundDto, henüz açıklanmadıysa Optional.empty()
     */
    public Optional<TefasFundDto> fetchFundPrice(String fundCode, LocalDate date) {
        if (fundCode == null || fundCode.trim().isEmpty()) {
            throw new IllegalArgumentException("[TefasClient] Fon kodu boş olamaz!");
        }
        if (date == null) {
            throw new IllegalArgumentException("[TefasClient] Tarih boş olamaz!");
        }

        String normalizedCode = fundCode.trim().toUpperCase();
        log.info("[TefasClient] TEFAS resmi fiyatı sorgulanıyor: Fon='{}', Hedef Tarih={}", normalizedCode, date);

        LocalDate currentTefasDate = determineCurrentTefasDate();

        // Eğer hedef tarih bugün ve saat 23:00'ten önceyse, TEFAS henüz bugünün resmi fiyatını açıklamamıştır
        if (date.equals(LocalDate.now(ISTANBUL_ZONE)) && LocalDateTime.now(ISTANBUL_ZONE).getHour() < 23) {
            log.info("[TefasClient] ⏳ Fon '{}' için {} tarihli resmi fiyat henüz TEFAS'ta yayımlanmamış (Beklemede).",
                    normalizedCode, date);
            return Optional.empty();
        }

        // Eğer hedef tarih güncel TEFAS resmi tarihimizle uyuşuyorsa doğrudan güncel fiyattan dön
        if (date.equals(currentTefasDate)) {
            Optional<TefasFundDto> latest = fetchLatestFundPrice(normalizedCode);
            if (latest.isPresent()) {
                return latest;
            }
        }

        // Değilse geriye dönük aralık sorgusu yap
        LocalDate queryStart = date.minusDays(15);
        List<TefasFundDto> results = fetchHistoricalPrices(normalizedCode, queryStart, date);
        if (results.isEmpty()) {
            log.warn(
                    "[TefasClient] TEFAS'ta fon için tarih aralığında veri dönmedi, son bilinen fiyat deneniyor: Fon='{}', Tarih={}",
                    normalizedCode, date);
            return fetchLatestFundPrice(normalizedCode);
        }

        Optional<TefasFundDto> targetDayRecord = results.stream()
                .filter(r -> r.getPriceDate().equals(date))
                .findFirst();

        return targetDayRecord.or(() -> fetchLatestFundPrice(normalizedCode));
    }

    /**
     * Fon için TEFAS'ta yayımlanmış en son resmi birim fiyat kaydını çeker.
     * Modern TEFAS API'sini (/api/funds/fonBilgiGetir) kullanır ve 5 dakika önbellekler.
     *
     * @param fundCode Fon kodu (Örn: "THF", "TTE", "KHA", "TI2")
     * @return En son resmi işlem günü kapanış fiyatı DTO'su
     */
    public Optional<TefasFundDto> fetchLatestFundPrice(String fundCode) {
        if (fundCode == null || fundCode.trim().isEmpty()) {
            return Optional.empty();
        }
        String normalizedCode = fundCode.trim().toUpperCase();

        // 1. Önbelleğe bak (5 dk TTL)
        CacheEntry cached = cache.get(normalizedCode);
        if (cached != null && Instant.now().isBefore(cached.expiresAt())) {
            log.trace("[TefasClient] Fon '{}' resmi fiyatı önbellekten sunuldu.", normalizedCode);
            return Optional.of(cached.dto());
        }

        // 2. Modern TEFAS API'sine sorgu at (/api/funds/fonBilgiGetir)
        try {
            Map<String, String> payload = Map.of("fonKodu", normalizedCode, "dil", "TR");
            String responseBody = webClient.post()
                    .uri(TEFAS_MODERN_ENDPOINT)
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(payload)
                    .retrieve()
                    .bodyToMono(String.class)
                    .block(Duration.ofSeconds(15));

            if (responseBody != null && !responseBody.isBlank()) {
                Optional<TefasFundDto> dtoOpt = parseModernTefasResponse(responseBody, normalizedCode);
                if (dtoOpt.isPresent()) {
                    TefasFundDto dto = dtoOpt.get();
                    cache.put(normalizedCode, new CacheEntry(dto, Instant.now().plus(CACHE_TTL)));
                    log.info(
                            "[TefasClient] TEFAS resmi verisi başarıyla alındı: Fon='{}', Son Fiyat={} TL, Günlük Getiri=%{}, Büyüklük={} TL",
                            normalizedCode, dto.getUnitPrice(),
                            dto.getDailyReturn() != null ? dto.getDailyReturn().multiply(BigDecimal.valueOf(100))
                                    .setScale(4, RoundingMode.HALF_UP) : "-",
                            dto.getTotalPortfolioValue());
                    return Optional.of(dto);
                }
            }
        } catch (Exception e) {
            log.warn("[TefasClient] Modern TEFAS API hatası: Fon='{}' -> {}", normalizedCode, e.getMessage());
        }

        // 3. Fallback: Geçmiş fiyat aralığı üzerinden son günü bul
        LocalDate today = LocalDate.now(ISTANBUL_ZONE);
        LocalDate queryStart = today.minusDays(15);
        List<TefasFundDto> results = fetchHistoricalPrices(normalizedCode, queryStart, today);
        if (!results.isEmpty()) {
            TefasFundDto dto = results.get(results.size() - 1);
            cache.put(normalizedCode, new CacheEntry(dto, Instant.now().plus(CACHE_TTL)));
            return Optional.of(dto);
        }

        return Optional.empty();
    }

    /**
     * Fon kodu ile TEFAS modern REST API'sinden (/api/funds/fonBilgiGetir) fonun tam profilini çeker.
     * Resmi fon unvanı, kurucu portföy yönetim şirketi, kategori ve son birim fiyatı döner.
     *
     * @param fundCode Fon kodu (Örn: "BDY", "TI2", "KHA")
     * @return TefasFundProfileDto nesnesi
     */
    public Optional<TefasFundProfileDto> fetchFundProfile(String fundCode) {
        if (fundCode == null || fundCode.trim().isEmpty()) {
            return Optional.empty();
        }
        String normalizedCode = fundCode.trim().toUpperCase();

        try {
            Map<String, String> payload = Map.of("fonKodu", normalizedCode, "dil", "TR");
            String responseBody = webClient.post()
                    .uri(TEFAS_MODERN_ENDPOINT)
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(payload)
                    .retrieve()
                    .bodyToMono(String.class)
                    .block(Duration.ofSeconds(10));

            if (responseBody != null && !responseBody.isBlank()) {
                JsonNode root = objectMapper.readTree(responseBody);
                JsonNode list = root.path("resultList");
                if (list.isArray() && !list.isEmpty()) {
                    JsonNode item = list.get(0);
                    String unvan = item.path("fonUnvan").asText(normalizedCode + " Yatırım Fonu").trim();
                    String kategori = item.path("fonKategori").asText("Hisse Senedi Fonu").trim();
                    BigDecimal sonFiyat = parseBigDecimal(item.path("sonFiyat"));
                    String manager = deduceManagerFromTitle(unvan);

                    log.info("[TefasClient] 🎯 TEFAS Fon Künyesi Keşfedildi: Fon='{}', Unvan='{}', Kurucu='{}', Kategori='{}'",
                            normalizedCode, unvan, manager, kategori);

                    return Optional.of(TefasFundProfileDto.builder()
                            .fundCode(normalizedCode)
                            .title(unvan)
                            .manager(manager)
                            .category(kategori)
                            .latestPrice(sonFiyat)
                            .annualFeeRatio(new BigDecimal("0.0200"))
                            .isFound(true)
                            .build());
                }
            }
        } catch (Exception e) {
            log.warn("[TefasClient] Fon künyesi TEFAS'tan çekilemedi: Fon='{}' -> {}", normalizedCode, e.getMessage());
        }

        return Optional.empty();
    }

    private static final java.util.regex.Pattern PORTFOY_PATTERN =
            java.util.regex.Pattern.compile("^(.*?)\\s+PORTF[ÖO]Y\\b", java.util.regex.Pattern.CASE_INSENSITIVE | java.util.regex.Pattern.UNICODE_CASE);

    /**
     * SPK fon isimlendirme standardına göre fon unvanından kurucu portföy yönetim şirketini generic ayıklar.
     *
     * Yasal SPK Şablonu:
     * Türkiye'deki tüm yatırım fonları "<KURUCU_MARKA> PORTFÖY ..." formatında başlar.
     * Örn: "AK PORTFÖY ..."          -> "Ak Portföy Yönetimi A.Ş."
     * Örn: "GARANTİ BBVA PORTFÖY..." -> "Garanti BBVA Portföy Yönetimi A.Ş."
     * Örn: "İŞ PORTFÖY ..."          -> "İş Portföy Yönetimi A.Ş."
     * Örn: "HEDEF PORTFÖY ..."       -> "Hedef Portföy Yönetimi A.Ş."
     *
     * Sıfır Hardcode Prensibi:
     * Kodda şirket isim listesi TUTULMAZ. SPK düzenlemesi uyarınca "PORTFÖY" kelimesinden
     * önceki kurucu marka kökü dinamik yakalanır ve Title-Case kurumsal unvana dönüştürülür.
     */
    public String deduceManagerFromTitle(String title) {
        if (title == null || title.isBlank()) {
            return "Bilinmeyen Portföy Yönetimi A.Ş.";
        }

        java.util.regex.Matcher matcher = PORTFOY_PATTERN.matcher(title.trim());
        if (matcher.find()) {
            String brandRaw = matcher.group(1).trim();
            if (!brandRaw.isEmpty()) {
                return formatTurkishTitleCase(brandRaw) + " Portföy Yönetimi A.Ş.";
            }
        }

        // Eğer doğrudan "PORTFÖY YÖNETİMİ" veya başka varyasyon varsa fallback
        int idx = title.toUpperCase(java.util.Locale.ROOT).indexOf("PORTFÖY");
        if (idx == -1) {
            idx = title.toUpperCase(java.util.Locale.ROOT).indexOf("PORTFOY");
        }
        if (idx > 0) {
            String brandRaw = title.substring(0, idx).trim();
            return formatTurkishTitleCase(brandRaw) + " Portföy Yönetimi A.Ş.";
        }

        return "Bilinmeyen Portföy Yönetimi A.Ş.";
    }

    private String formatTurkishTitleCase(String text) {
        if (text == null || text.isBlank()) return "";
        java.util.Locale trLocale = java.util.Locale.forLanguageTag("tr-TR");
        String[] words = text.split("\\s+");
        StringBuilder sb = new StringBuilder();
        for (String w : words) {
            if (w.isBlank()) continue;
            // Finansal bilinen kısaltmaları büyük tut (BBVA, QNB, TEB, HSBC vb.)
            if (w.equalsIgnoreCase("BBVA") || w.equalsIgnoreCase("QNB") || w.equalsIgnoreCase("TEB") 
                    || w.equalsIgnoreCase("HSBC") || w.equalsIgnoreCase("ING")) {
                sb.append(w.toUpperCase(trLocale)).append(" ");
            } else {
                String lower = w.toLowerCase(trLocale);
                String firstCharUpper = lower.substring(0, 1).toUpperCase(trLocale);
                String capitalized = firstCharUpper + (lower.length() > 1 ? lower.substring(1) : "");
                sb.append(capitalized).append(" ");
            }
        }
        return sb.toString().trim();
    }

    /**
     * TEFAS API uç noktalarını doğrudan test etmek/keşfetmek için yardımcı metot.
     */
    public String queryDirectEndpoint(String endpoint, Map<String, ?> body) {
        return webClient.post()
                .uri(endpoint)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .retrieve()
                .bodyToMono(String.class)
                .block(Duration.ofSeconds(10));
    }

    public String queryFormEndpoint(String endpoint, org.springframework.util.MultiValueMap<String, String> formData) {
        return webClient.post()
                .uri(endpoint)
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(org.springframework.web.reactive.function.BodyInserters.fromFormData(formData))
                .retrieve()
                .bodyToMono(String.class)
                .block(Duration.ofSeconds(10));
    }

    public String queryGetEndpoint(String endpoint) {
        return webClient.get()
                .uri(endpoint)
                .retrieve()
                .bodyToMono(String.class)
                .block(Duration.ofSeconds(10));
    }

    /**
     * Modern TEFAS /api/funds/fonBilgiGetir yanıtını ayrıştırır.
     */
    private Optional<TefasFundDto> parseModernTefasResponse(String jsonResponse, String fundCode) {
        try {
            JsonNode root = objectMapper.readTree(jsonResponse);
            JsonNode list = root.path("resultList");
            if (!list.isArray() || list.isEmpty()) {
                return Optional.empty();
            }
            JsonNode item = list.get(0);
            BigDecimal sonFiyat = parseBigDecimal(item.path("sonFiyat"));
            if (sonFiyat == null || sonFiyat.compareTo(BigDecimal.ZERO) <= 0) {
                return Optional.empty();
            }
            sonFiyat = sonFiyat.setScale(6, RoundingMode.HALF_UP);

            BigDecimal gunlukGetiri = parseBigDecimal(item.path("gunlukGetiri"));
            BigDecimal dailyReturn = null;
            if (gunlukGetiri != null) {
                dailyReturn = gunlukGetiri.divide(BigDecimal.valueOf(100), 6, RoundingMode.HALF_UP);
            }

            BigDecimal payAdet = parseBigDecimal(item.path("payAdet"));
            BigDecimal portBuyukluk = parseBigDecimal(item.path("portBuyukluk"));
            LocalDate priceDate = determineCurrentTefasDate();

            return Optional.of(TefasFundDto.builder()
                    .fundCode(fundCode)
                    .priceDate(priceDate)
                    .unitPrice(sonFiyat)
                    .dailyReturn(dailyReturn)
                    .outstandingShares(payAdet)
                    .totalPortfolioValue(portBuyukluk)
                    .fetchedAt(LocalDateTime.now(ISTANBUL_ZONE))
                    .build());
        } catch (Exception e) {
            log.error("[TefasClient] Modern TEFAS JSON ayrıştırma hatası: {}", e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Güncel TEFAS fiyatının geçerli olduğu resmi işlem günü tarihini belirler.
     * Saat 23:00 ve sonrasında bugünün kapanışıdır.
     * Gün içinde ve 23:00 öncesinde dünün (veya hafta sonu sonrası Cuma'nın) kapanışıdır.
     */
    public LocalDate determineCurrentTefasDate() {
        LocalDateTime now = LocalDateTime.now(ISTANBUL_ZONE);
        if (now.getHour() >= 23) {
            return now.toLocalDate();
        } else {
            LocalDate date = now.toLocalDate().minusDays(1);
            while (date.getDayOfWeek() == java.time.DayOfWeek.SATURDAY
                    || date.getDayOfWeek() == java.time.DayOfWeek.SUNDAY) {
                date = date.minusDays(1);
            }
            return date;
        }
    }

    private final java.util.concurrent.ConcurrentMap<String, List<TefasFundDto>> historicalCache = new java.util.concurrent.ConcurrentHashMap<>();
    private static final int MAX_TEFAS_QUERY_DAYS = 90;

    /**
     * 2. TARİH ARALIĞI GÖREVİ: Belirli İki Tarih Arasındaki Tüm Resmi Kapanışları Çeker.
     * Bu metot; geriye dönük doğruluk testi (Backtest) ve toplu mutabakat için kullanılır.
     * 
     * MİMARİ VE YENİLİKLER:
     * 1. Modern TEFAS Resmi API: '/api/funds/fonFiyatBilgiGetir' REST JSON uç noktasını kullanır.
     * 2. Otomatik Periyot Seçimi: İstenen başlangıç tarihine göre 1, 3, 6, 12, 36 veya 60 aylık periyot
     *    TEFAS'tan tek seferde çekilir ve tarih filtresi uygulanır.
     * 3. Kesintisiz Getiri Hesabı: Tüm periyot için (P_t - P_{t-1}) / P_{t-1} hesaplanarak
     *    başlangıç gününün de getirisi doğru belirlenir.
     * 4. Geriye Uyumluluk (Fallback): Modern API yanıt vermezse legacy /api/DB/BindHistoryInfo denenir.
     * 5. Bellek İçi Önbellek: Sabit geçmiş borsa kapanışları bellekten hızlıca sunulur.
     *
     * @param fundCode  Fon kodu (Örn: "THF", "TTE", "TI2")
     * @param startDate Başlangıç tarihi
     * @param endDate   Bitiş tarihi
     * @return Tarihe göre kronolojik sıralanmış TefasFundDto listesi
     */
    public List<TefasFundDto> fetchHistoricalPrices(String fundCode, LocalDate startDate, LocalDate endDate) {
        if (fundCode == null || fundCode.trim().isEmpty() || startDate == null || endDate == null) {
            return Collections.emptyList();
        }

        if (startDate.isAfter(endDate)) {
            log.warn("[TefasClient] Başlangıç tarihi bitiş tarihinden sonra olamaz: Start={}, End={}", startDate, endDate);
            return Collections.emptyList();
        }

        String normalizedCode = fundCode.trim().toUpperCase();
        String cacheKey = normalizedCode + ":" + startDate + ":" + endDate;

        // 1. Önbellek kontrolü
        List<TefasFundDto> cached = historicalCache.get(cacheKey);
        if (cached != null && !cached.isEmpty()) {
            log.trace("[TefasClient] Fon '{}' [{} - {}] tarihsel verisi önbellekten sunuldu ({} gün).",
                    normalizedCode, startDate, endDate, cached.size());
            return new ArrayList<>(cached);
        }

        // 2. Birincil Yol: Modern TEFAS JSON API (/api/funds/fonFiyatBilgiGetir)
        List<TefasFundDto> results = fetchFromModernTefasHistory(normalizedCode, startDate, endDate);

        // 3. İkincil Yol: Eski TEFAS Form API (Fallback)
        if (results.isEmpty()) {
            results = fetchFromLegacyTefasHistory(normalizedCode, startDate, endDate);
        }

        if (results.isEmpty()) {
            log.warn("[TefasClient] Fon '{}' için [{} - {}] aralığında TEFAS verisi bulunamadı.",
                    normalizedCode, startDate, endDate);
            return Collections.emptyList();
        }

        // 4. Tarihsel önbelleğe kaydet
        historicalCache.put(cacheKey, results);
        return new ArrayList<>(results);
    }

    /**
     * Modern TEFAS Resmi JSON API (/api/funds/fonFiyatBilgiGetir) üzerinden tarihsel fiyatları çeker.
     */
    private List<TefasFundDto> fetchFromModernTefasHistory(String normalizedCode, LocalDate startDate, LocalDate endDate) {
        LocalDate today = LocalDate.now(ISTANBUL_ZONE);
        long monthsAgo = java.time.temporal.ChronoUnit.MONTHS.between(startDate, today);

        int periyod;
        if (monthsAgo <= 1) {
            periyod = 3; // 3 ay güvenle 1-2 ayı kapsar
        } else if (monthsAgo <= 3) {
            periyod = 3;
        } else if (monthsAgo <= 6) {
            periyod = 6;
        } else if (monthsAgo <= 12) {
            periyod = 12;
        } else if (monthsAgo <= 36) {
            periyod = 36;
        } else {
            periyod = 60;
        }

        try {
            Map<String, Object> payload = Map.of(
                    "fonKodu", normalizedCode,
                    "dil", "TR",
                    "periyod", periyod
            );

            log.info("[TefasClient] Modern TEFAS tarihsel sorgusu yapılıyor: Fon='{}', Periyod={} ay, Hedef=[{} - {}]",
                    normalizedCode, periyod, startDate, endDate);

            String responseBody = webClient.post()
                    .uri(TEFAS_HISTORY_ENDPOINT)
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(payload)
                    .retrieve()
                    .bodyToMono(String.class)
                    .block(Duration.ofSeconds(15));

            if (responseBody != null && !responseBody.isBlank()) {
                List<TefasFundDto> allRecords = parseTefasResponse(responseBody, normalizedCode);
                if (!allRecords.isEmpty()) {
                    List<TefasFundDto> inRange = new ArrayList<>(allRecords.stream()
                            .filter(dto -> dto.getPriceDate() != null &&
                                    !dto.getPriceDate().isBefore(startDate) &&
                                    !dto.getPriceDate().isAfter(endDate))
                            .toList());

                    log.info("[TefasClient] Modern TEFAS tarihsel verisi başarıyla alındı: Fon='{}', Gelen Gün={}, Aralıktaki Gün={}",
                            normalizedCode, allRecords.size(), inRange.size());
                    return inRange;
                }
            }
        } catch (Exception e) {
            log.warn("[TefasClient] Modern TEFAS tarihsel sorgu hatası: Fon='{}' -> {}", normalizedCode, e.getMessage());
        }

        return Collections.emptyList();
    }

    /**
     * Eski TEFAS Form API üzerinden geriye dönük aralık sorgusu (Fallback).
     */
    private List<TefasFundDto> fetchFromLegacyTefasHistory(String normalizedCode, LocalDate startDate, LocalDate endDate) {
        long daysBetween = java.time.temporal.ChronoUnit.DAYS.between(startDate, endDate);
        List<TefasFundDto> allResults = new ArrayList<>();

        if (daysBetween > MAX_TEFAS_QUERY_DAYS) {
            LocalDate chunkStart = startDate;
            while (!chunkStart.isAfter(endDate)) {
                LocalDate chunkEnd = chunkStart.plusDays(MAX_TEFAS_QUERY_DAYS - 1);
                if (chunkEnd.isAfter(endDate)) {
                    chunkEnd = endDate;
                }
                List<TefasFundDto> chunkList = fetchHistoricalPricesChunk(normalizedCode, chunkStart, chunkEnd);
                allResults.addAll(chunkList);
                chunkStart = chunkEnd.plusDays(1);
            }
        } else {
            allResults = fetchHistoricalPricesChunk(normalizedCode, startDate, endDate);
        }

        if (allResults.isEmpty()) {
            return Collections.emptyList();
        }

        Map<LocalDate, TefasFundDto> uniqueByDate = new TreeMap<>();
        for (TefasFundDto dto : allResults) {
            if (dto.getPriceDate() != null) {
                uniqueByDate.put(dto.getPriceDate(), dto);
            }
        }
        List<TefasFundDto> sortedList = new ArrayList<>(uniqueByDate.values());

        for (int i = 1; i < sortedList.size(); i++) {
            BigDecimal previousPrice = sortedList.get(i - 1).getUnitPrice();
            BigDecimal currentPrice = sortedList.get(i).getUnitPrice();
            if (previousPrice != null && previousPrice.compareTo(BigDecimal.ZERO) > 0) {
                BigDecimal dailyReturn = currentPrice.subtract(previousPrice)
                        .divide(previousPrice, 6, RoundingMode.HALF_UP);
                sortedList.get(i).setDailyReturn(dailyReturn);
            }
        }

        return sortedList;
    }

    private List<TefasFundDto> fetchHistoricalPricesChunk(String normalizedCode, LocalDate startDate, LocalDate endDate) {
        String formattedStart = startDate.format(TEFAS_DATE_FORMATTER);
        String formattedEnd = endDate.format(TEFAS_DATE_FORMATTER);

        List<TefasFundDto> results = executeLegacyTefasQuery(normalizedCode, formattedStart, formattedEnd, "YAT");
        if (!results.isEmpty()) {
            return results;
        }

        results = executeLegacyTefasQuery(normalizedCode, formattedStart, formattedEnd, "EMK");
        return results;
    }

    private List<TefasFundDto> executeLegacyTefasQuery(
            String normalizedCode, String formattedStart, String formattedEnd, String fontip) {
        try {
            String responseBody = webClient.post()
                    .uri(TEFAS_LEGACY_ENDPOINT)
                    .header("Referer", "https://www.tefas.gov.tr/TarihselVeriler.aspx")
                    .header("Origin", "https://www.tefas.gov.tr")
                    .header("X-Requested-With", "XMLHttpRequest")
                    .header("Accept", "application/json, text/javascript, */*; q=0.01")
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(BodyInserters.fromFormData("fontip", fontip)
                            .with("sfontur", "")
                            .with("fonkod", normalizedCode)
                            .with("bastarih", formattedStart)
                            .with("bittarih", formattedEnd))
                    .retrieve()
                    .bodyToMono(String.class)
                    .block(Duration.ofSeconds(15));

            if (responseBody == null || responseBody.trim().isEmpty()) {
                return Collections.emptyList();
            }

            return parseTefasResponse(responseBody, normalizedCode);

        } catch (Exception e) {
            return Collections.emptyList();
        }
    }

    /**
     * 3. AYRIŞTIRMA GÖREVİ: TEFAS JSON Yanıtını Ayrıştırıp DTO Listesine
     * Dönüştürür.
     *
     * Örnek TEFAS JSON Yanıtı:
     * {
     * "data": [
     * {
     * "TARIH": "1726952400000",
     * "FIYAT": 3.456789,
     * "TEDPAYSAYISI": 150000000.0,
     * "PORTFOYBUYUKLUK": 518518350.0
     * }
     * ]
     * }
     *
     * @param jsonResponse TEFAS'tan dönen ham JSON dizgesi
     * @param fundCode     Fon kodu
     * @return Doldurulmuş ve kronolojik sıralanmış DTO listesi
     */
    public List<TefasFundDto> parseTefasResponse(String jsonResponse, String fundCode) {
        if (jsonResponse == null || jsonResponse.trim().isEmpty()) {
            return Collections.emptyList();
        }

        List<TefasFundDto> dtoList = new ArrayList<>();
        LocalDateTime now = LocalDateTime.now(ISTANBUL_ZONE);

        try {
            JsonNode rootNode = objectMapper.readTree(jsonResponse);
            JsonNode dataArray = rootNode.path("data");
            boolean isModernList = false;

            if (!dataArray.isArray() || dataArray.isEmpty()) {
                dataArray = rootNode.path("resultList");
                isModernList = true;
            }

            if (!dataArray.isArray() || dataArray.isEmpty()) {
                log.debug("[TefasClient] TEFAS yanıtında veri dizisi boş: Fon='{}'", fundCode);
                return Collections.emptyList();
            }

            for (JsonNode item : dataArray) {
                // 1. Fiyat (sonFiyat, fiyat veya FIYAT) -> 6 basamak BigDecimal
                BigDecimal unitPrice = parseBigDecimal(
                        item.has("sonFiyat") ? item.path("sonFiyat")
                                : (item.has("fiyat") ? item.path("fiyat") : item.path("FIYAT")));
                if (unitPrice == null || unitPrice.compareTo(BigDecimal.ZERO) <= 0) {
                    continue; // Fiyatı olmayan veya 0 olan hatalı satırları atla
                }
                unitPrice = unitPrice.setScale(6, RoundingMode.HALF_UP);

                // 2. Tedavüldeki Pay Sayısı (TEDPAYSAYISI veya payAdet)
                BigDecimal outstandingShares = parseBigDecimal(
                        item.has("payAdet") ? item.path("payAdet") : item.path("TEDPAYSAYISI"));

                // 3. Portföy Toplam Büyüklüğü (PORTFOYBUYUKLUK veya portBuyukluk)
                BigDecimal totalPortfolioValue = parseBigDecimal(
                        item.has("portBuyukluk") ? item.path("portBuyukluk") : item.path("PORTFOYBUYUKLUK"));

                // 4. Tarih (tarih, TARIH veya güncel TEFAS tarihi)
                LocalDate priceDate = item.has("tarih") ? parseTefasDate(item.path("tarih"))
                        : (item.has("TARIH") ? parseTefasDate(item.path("TARIH")) : determineCurrentTefasDate());
                if (priceDate == null) {
                    continue;
                }

                TefasFundDto dto = TefasFundDto.builder()
                        .fundCode(fundCode)
                        .priceDate(priceDate)
                        .unitPrice(unitPrice)
                        .outstandingShares(outstandingShares)
                        .totalPortfolioValue(totalPortfolioValue)
                        .fetchedAt(now)
                        .build();

                dtoList.add(dto);
            }

            // Tarihe göre artan (kronolojik) sıralama: En eski günden en yeni güne
            dtoList.sort(Comparator.comparing(TefasFundDto::getPriceDate));

            // Eğer listede birden fazla gün varsa günlük getiri oranını hesapla: (P_t -
            // P_{t-1}) / P_{t-1}
            for (int i = 1; i < dtoList.size(); i++) {
                BigDecimal previousPrice = dtoList.get(i - 1).getUnitPrice();
                BigDecimal currentPrice = dtoList.get(i).getUnitPrice();
                if (previousPrice != null && previousPrice.compareTo(BigDecimal.ZERO) > 0) {
                    BigDecimal dailyReturn = currentPrice.subtract(previousPrice)
                            .divide(previousPrice, 6, RoundingMode.HALF_UP);
                    dtoList.get(i).setDailyReturn(dailyReturn);
                }
            }

            log.info("[TefasClient] TEFAS verisi başarıyla ayrıştırıldı: Fon='{}', Kayıt Sayısı={}",
                    fundCode, dtoList.size());

            return dtoList;

        } catch (Exception e) {
            log.error("[TefasClient] TEFAS JSON ayrıştırma hatası: Fon='{}' -> {}", fundCode, e.getMessage());
            return Collections.emptyList();
        }
    }

    /**
     * TEFAS'ın tarih düğümünü ('TARIH') çözer.
     * Hem milisaniye damgasını ("1726952400000") hem de gün formatını
     * ("22.09.2026") destekler.
     */
    private LocalDate parseTefasDate(JsonNode dateNode) {
        if (dateNode == null || dateNode.isMissingNode() || dateNode.isNull()) {
            return null;
        }

        String rawDate = dateNode.asText().trim();
        if (rawDate.isEmpty()) {
            return null;
        }

        // 1. Sırf sayılardan oluşuyorsa Epoch milisaniye damgasıdır
        if (rawDate.matches("^\\d+$")) {
            try {
                long millis = Long.parseLong(rawDate);
                return Instant.ofEpochMilli(millis)
                        .atZone(ZoneId.of("Europe/Istanbul"))
                        .toLocalDate();
            } catch (Exception ignored) {
            }
        }

        // 2. 'dd.MM.yyyy' metin formatı
        try {
            return LocalDate.parse(rawDate, TEFAS_DATE_FORMATTER);
        } catch (Exception ignored) {
        }

        // 3. 'yyyy-MM-dd' ISO formatı
        try {
            return LocalDate.parse(rawDate, DateTimeFormatter.ISO_LOCAL_DATE);
        } catch (Exception ignored) {
        }

        log.warn("[TefasClient] Tanınmayan TEFAS tarih formatı: '{}'", rawDate);
        return null;
    }

    /**
     * Sayısal JSON alanını BigDecimal'e dönüştürür.
     */
    private BigDecimal parseBigDecimal(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return null;
        }
        try {
            if (node.isNumber()) {
                return BigDecimal.valueOf(node.asDouble());
            }
            String text = node.asText().replace(",", ".").trim();
            return new BigDecimal(text);
        } catch (Exception e) {
            return null;
        }
    }
}
