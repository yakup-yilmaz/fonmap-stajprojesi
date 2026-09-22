package com.fonmap.infrastructure.client.tefas;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fonmap.infrastructure.client.tefas.dto.TefasFundDto;
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
import java.util.Optional;

/**
 * TefasClient — TEFAS Resmi Kapanış Fiyatı ve Portföy Büyüklüğü İstemcisi
 * =======================================================================
 *
 * SİSTEMDEKİ ROLÜ:
 * Fonmap platformunun "Gece Resmi Hakemi / Mutabakat İstemcisi"dir.
 *
 * NEDEN VAR?
 * Gün boyu 10:00 - 18:10 arasında hisse senetlerinin borsa hareketlerine göre
 * ürettiğimiz tahmini net getirinin doğruluğunu ölçmek için TEFAS'ın gece 23:00'te
 * yayımladığı resmi kapanış fiyatına ihtiyaç duyarız.
 *
 * TEFAS RESMİ REST API UÇ NOKTASI:
 * - URL: https://www.tefas.gov.tr/api/DB/BindHistoryInfo
 * - Metot: HTTP POST (x-www-form-urlencoded)
 * - Parametreler:
 *     fontip: "YAT" (Yatırım Fonları)
 *     fonkod: "THF" (Fon kodu)
 *     bastarih: "22.09.2026" (dd.MM.yyyy)
 *     bittarih: "22.09.2026" (dd.MM.yyyy)
 *
 * KRİTİK FİNANSAL KURALLAR:
 * 1. 6 Basamak Hassasiyet: Katılma payı fiyatları virgülden sonra en az 6 basamak
 *    (Örn: 3.456789 TL) olarak saklanır (RoundingMode.HALF_UP).
 * 2. Resmi Getiri Formülü: Günlük getiri iki günün TEFAS kapanışından hesaplanır:
 *    r = (P_t - P_{t-1}) / P_{t-1}
 * 3. Hata Toleransı (Resilience): TEFAS gece bakım saatlerinde (23:00 - 00:00) bazen
 *    5-10 dakika yanıt vermeyebilir. İstemci sistemi düşürmez; kontrollü şekilde loglar.
 */
@Component
@Slf4j
public class TefasClient {

    private static final String TEFAS_BASE_URL = "https://www.tefas.gov.tr";
    private static final String TEFAS_HISTORY_ENDPOINT = "/api/DB/BindHistoryInfo";
    private static final DateTimeFormatter TEFAS_DATE_FORMATTER = DateTimeFormatter.ofPattern("dd.MM.yyyy");

    private final WebClient webClient;
    private final ObjectMapper objectMapper;

    /**
     * Varsayılan Yapıcı Metot (Default Constructor).
     * TEFAS'ın güvenlik ve AJAX filtrelerine uygun HTTP başlıklarıyla WebClient'ı yapılandırır:
     * - X-Requested-With: XMLHttpRequest (TEFAS doğrudan tarayıcı dışı botları engellemek için AJAX başlığı arar)
     * - Content-Type: application/x-www-form-urlencoded
     * - 15 saniyelik yanıt zaman aşımı (Timeout)
     */
    public TefasClient() {
        this.objectMapper = new ObjectMapper();

        HttpClient httpClient = HttpClient.create()
                .responseTimeout(Duration.ofSeconds(15));

        this.webClient = WebClient.builder()
                .baseUrl(TEFAS_BASE_URL)
                .clientConnector(new ReactorClientHttpConnector(httpClient))
                .defaultHeader("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36")
                .defaultHeader("X-Requested-With", "XMLHttpRequest")
                .defaultHeader("Accept", "application/json, text/javascript, */*; q=0.01")
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
     * 1. ANA GÖREV: Belirli Bir Tarihteki Resmi Fon Kapanış Verisini Çeker.
     * Gece 23:00 mutabakat servisi tarafından çağrılır.
     *
     * @param fundCode Fon kodu (Örn: "THF", "TLY", "TTE")
     * @param date     Resmi fiyatın ait olduğu işlem günü tarihi
     * @return Bulunursa TefasFundDto, henüz açıklanmadıysa veya hata oluştuysa Optional.empty()
     */
    public Optional<TefasFundDto> fetchFundPrice(String fundCode, LocalDate date) {
        if (fundCode == null || fundCode.trim().isEmpty()) {
            throw new IllegalArgumentException("[TefasClient] Fon kodu boş olamaz!");
        }
        if (date == null) {
            throw new IllegalArgumentException("[TefasClient] Tarih boş olamaz!");
        }

        String normalizedCode = fundCode.trim().toUpperCase();
        log.info("[TefasClient] TEFAS resmi fiyatı sorgulanıyor: Fon='{}', Tarih={}", normalizedCode, date);

        List<TefasFundDto> results = fetchHistoricalPrices(normalizedCode, date, date);
        if (results.isEmpty()) {
            log.warn("[TefasClient] TEFAS'ta fon için fiyat kaydı bulunamadı (Henüz açıklanmamış olabilir): Fon='{}', Tarih={}",
                    normalizedCode, date);
            return Optional.empty();
        }

        // İlgili tarihe en uygun kaydı döner
        return Optional.of(results.get(0));
    }

    /**
     * 2. TARİH ARALIĞI GÖREVİ: Belirli İki Tarih Arasındaki Tüm Resmi Kapanışları Çeker.
     * Bu metot; geriye dönük 60 günlük doğruluk testi (Backtest) ve toplu mutabakat için kullanılır.
     *
     * @param fundCode  Fon kodu (Örn: "THF")
     * @param startDate Başlangıç tarihi
     * @param endDate   Bitiş tarihi
     * @return Tarihe göre kronolojik sıralanmış TefasFundDto listesi
     */
    public List<TefasFundDto> fetchHistoricalPrices(String fundCode, LocalDate startDate, LocalDate endDate) {
        String normalizedCode = fundCode.trim().toUpperCase();
        String formattedStart = startDate.format(TEFAS_DATE_FORMATTER);
        String formattedEnd = endDate.format(TEFAS_DATE_FORMATTER);

        try {
            // TEFAS POST form-urlencoded gövdesi
            String responseBody = webClient.post()
                    .uri(TEFAS_HISTORY_ENDPOINT)
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(BodyInserters.fromFormData("fontip", "YAT")
                            .with("fonkod", normalizedCode)
                            .with("bastarih", formattedStart)
                            .with("bittarih", formattedEnd))
                    .retrieve()
                    .bodyToMono(String.class)
                    .block(Duration.ofSeconds(15));

            if (responseBody == null || responseBody.trim().isEmpty()) {
                log.warn("[TefasClient] TEFAS sunucusundan boş gövde döndü: Fon='{}'", normalizedCode);
                return Collections.emptyList();
            }

            return parseTefasResponse(responseBody, normalizedCode);

        } catch (Exception e) {
            log.error("[TefasClient] TEFAS veri çekme hatası: Fon='{}', Tarih=[{} - {}] -> {}",
                    normalizedCode, formattedStart, formattedEnd, e.getMessage());
            return Collections.emptyList();
        }
    }

    /**
     * 3. AYRIŞTIRMA GÖREVİ: TEFAS JSON Yanıtını Ayrıştırıp DTO Listesine Dönüştürür.
     *
     * Örnek TEFAS JSON Yanıtı:
     * {
     *   "data": [
     *     {
     *       "TARIH": "1726952400000",
     *       "FIYAT": 3.456789,
     *       "TEDPAYSAYISI": 150000000.0,
     *       "PORTFOYBUYUKLUK": 518518350.0
     *     }
     *   ]
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
        LocalDateTime now = LocalDateTime.now();

        try {
            JsonNode rootNode = objectMapper.readTree(jsonResponse);
            JsonNode dataArray = rootNode.path("data");

            if (!dataArray.isArray() || dataArray.isEmpty()) {
                log.debug("[TefasClient] TEFAS yanıtında veri dizisi ('data') boş: Fon='{}'", fundCode);
                return Collections.emptyList();
            }

            for (JsonNode item : dataArray) {
                // 1. Fiyat (FIYAT) -> 6 basamak BigDecimal
                BigDecimal unitPrice = parseBigDecimal(item.path("FIYAT"));
                if (unitPrice == null || unitPrice.compareTo(BigDecimal.ZERO) <= 0) {
                    continue; // Fiyatı olmayan veya 0 olan hatalı satırları atla
                }
                unitPrice = unitPrice.setScale(6, RoundingMode.HALF_UP);

                // 2. Tedavüldeki Pay Sayısı (TEDPAYSAYISI)
                BigDecimal outstandingShares = parseBigDecimal(item.path("TEDPAYSAYISI"));

                // 3. Portföy Toplam Büyüklüğü (PORTFOYBUYUKLUK)
                BigDecimal totalPortfolioValue = parseBigDecimal(item.path("PORTFOYBUYUKLUK"));

                // 4. Tarih (TARIH) -> Epoch millis veya 'dd.MM.yyyy' formatını destekler
                LocalDate priceDate = parseTefasDate(item.path("TARIH"));
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

            // Eğer listede birden fazla gün varsa günlük getiri oranını hesapla: (P_t - P_{t-1}) / P_{t-1}
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
     * Hem milisaniye damgasını ("1726952400000") hem de gün formatını ("22.09.2026") destekler.
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
