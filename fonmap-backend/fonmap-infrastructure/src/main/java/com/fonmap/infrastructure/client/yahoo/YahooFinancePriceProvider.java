package com.fonmap.infrastructure.client.yahoo;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fonmap.infrastructure.client.PriceProvider;
import com.fonmap.infrastructure.client.dto.MarketPriceDto;
import com.fonmap.infrastructure.client.exception.PriceProviderException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;

/**
 * 1. Birincil Fiyat Sağlayıcısı: Yahoo Finance API İstemcisi.
 * 
 * BIST hisse senetleri (THYAO.IS), BIST 100 endeksi (XU100.IS),
 * Döviz kurları (USDTRY=X) ve Altın (GC=F) fiyatlarını ücretsiz
 * Yahoo Finance v8 JSON endpoint'inden çeker.
 */
@Slf4j
@Component("yahooFinancePriceProvider")
public class YahooFinancePriceProvider implements PriceProvider {

    private static final String PROVIDER_NAME = "YAHOO_FINANCE";
    private static final String YAHOO_BASE_URL = "https://query1.finance.yahoo.com/v8/finance/chart";
    private static final String USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36";

    private final RestClient restClient;
    private final ObjectMapper objectMapper;

    public YahooFinancePriceProvider(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        this.restClient = RestClient.builder()
                .baseUrl(YAHOO_BASE_URL)
                .defaultHeader(HttpHeaders.USER_AGENT, USER_AGENT)
                .defaultHeader(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE)
                .build();
    }

    @Override
    public MarketPriceDto getPrice(String symbol) {
        String yahooTicker = formatYahooTicker(symbol);
        log.debug("[{}] '{}' (Yahoo: '{}') için fiyat çekiliyor...", PROVIDER_NAME, symbol, yahooTicker);

        try {
            String jsonResponse = restClient.get()
                    .uri("/{ticker}", yahooTicker)
                    .retrieve()
                    .body(String.class);

            if (jsonResponse == null || jsonResponse.isBlank()) {
                throw new PriceProviderException(PROVIDER_NAME, symbol, "Boş yanıt alındı.");
            }

            JsonNode root = objectMapper.readTree(jsonResponse);
            JsonNode resultNode = root.path("chart").path("result");

            if (!resultNode.isArray() || resultNode.isEmpty()) {
                JsonNode errorNode = root.path("chart").path("error");
                String errorDesc = errorNode.isMissingNode() ? "Bilinmeyen API hatası" : errorNode.toString();
                throw new PriceProviderException(PROVIDER_NAME, symbol, "Yahoo hata döndü: " + errorDesc);
            }

            JsonNode metaNode = resultNode.get(0).path("meta");
            if (metaNode.isMissingNode()) {
                throw new PriceProviderException(PROVIDER_NAME, symbol, "JSON içinde 'meta' alanı bulunamadı.");
            }

            // Anlık fiyat ve dünkü kapanış değerlerini oku
            double currentPriceDouble = metaNode.path("regularMarketPrice").asDouble(0.0);
            double previousCloseDouble = metaNode.path("previousClose").asDouble(0.0);

            // Eğer previousClose sıfırsa chartPreviousClose alanını dene
            if (previousCloseDouble <= 0.0) {
                previousCloseDouble = metaNode.path("chartPreviousClose").asDouble(0.0);
            }

            if (currentPriceDouble <= 0.0 || previousCloseDouble <= 0.0) {
                throw new PriceProviderException(PROVIDER_NAME, symbol, 
                        String.format("Geçersiz fiyat değerleri: anlık=%.4f, dünkü=%.4f", currentPriceDouble, previousCloseDouble));
            }

            BigDecimal currentPrice = BigDecimal.valueOf(currentPriceDouble);
            BigDecimal previousClose = BigDecimal.valueOf(previousCloseDouble);

            MarketPriceDto dto = MarketPriceDto.of(symbol.toUpperCase(), currentPrice, previousClose, PROVIDER_NAME);
            log.debug("[{}] '{}' başarıyla çekildi: Anlık={}, Dünkü={}, Değişim={}", 
                    PROVIDER_NAME, symbol, currentPrice, previousClose, dto.getDailyChangeRatio());

            return dto;

        } catch (PriceProviderException ppe) {
            throw ppe;
        } catch (Exception e) {
            log.error("[{}] '{}' fiyatı çekilirken beklenmedik hata: {}", PROVIDER_NAME, symbol, e.getMessage());
            throw new PriceProviderException(PROVIDER_NAME, symbol, e.getMessage(), e);
        }
    }

    @Override
    public List<MarketPriceDto> getPrices(List<String> symbols) {
        if (symbols == null || symbols.isEmpty()) {
            return List.of();
        }

        // Java 21 paralel akış (parallelStream) ile eşzamanlı ve hızlı çekim
        return symbols.parallelStream()
                .map(symbol -> {
                    try {
                        return getPrice(symbol);
                    } catch (Exception e) {
                        log.warn("[{}] '{}' çekilemedi, diğerleri devam ediyor: {}", PROVIDER_NAME, symbol, e.getMessage());
                        return null;
                    }
                })
                .filter(Objects::nonNull)
                .toList();
    }

    @Override
    public String getProviderName() {
        return PROVIDER_NAME;
    }

    /**
     * Fonmap varlık sembolünü Yahoo Finance formatına dönüştürür.
     * Örnekler:
     * - "THYAO" -> "THYAO.IS"
     * - "USDTRY" -> "USDTRY=X"
     * - "EURTRY" -> "EURTRY=X"
     * - "XU100" -> "XU100.IS"
     * - "ALTIN" veya "GOLD" -> "GC=F"
     */
    private String formatYahooTicker(String symbol) {
        if (symbol == null) return "";
        String upper = symbol.trim().toUpperCase();

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

        // Standart BIST hissesi veya BIST endeksi (Örn: THYAO, ASELS, XU100)
        return upper + ".IS";
    }
}
