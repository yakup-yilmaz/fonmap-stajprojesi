package com.fonmap.infrastructure.client.bigpara;

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
 * 2. İkincil Yedek Fiyat Sağlayıcısı (Failover): Bigpara API İstemcisi.
 * 
 * Yahoo Finance çöktüğünde veya rate limit koyduğunda devreye girer.
 * Hürriyet / Bigpara'nın halka açık, şifresiz Borsa İstanbul hisse
 * ve endeks servisinden verileri çeker.
 */
@Slf4j
@Component("bigparaPriceProvider")
public class BigparaPriceProvider implements PriceProvider {

    private static final String PROVIDER_NAME = "BIGPARA";
    private static final String BIGPARA_BASE_URL = "https://api-bigpara.hurriyet.com.tr/api/trade/offset/filter";
    private static final String USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36";

    private final RestClient restClient;
    private final ObjectMapper objectMapper;

    public BigparaPriceProvider(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        this.restClient = RestClient.builder()
                .baseUrl(BIGPARA_BASE_URL)
                .defaultHeader(HttpHeaders.USER_AGENT, USER_AGENT)
                .defaultHeader(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE)
                .build();
    }

    @Override
    public MarketPriceDto getPrice(String symbol) {
        String cleanTicker = cleanSymbol(symbol);
        log.debug("[{}] '{}' (Bigpara: '{}') için fiyat çekiliyor...", PROVIDER_NAME, symbol, cleanTicker);

        try {
            String jsonResponse = restClient.get()
                    .uri("?query={ticker}", cleanTicker)
                    .retrieve()
                    .body(String.class);

            if (jsonResponse == null || jsonResponse.isBlank()) {
                throw new PriceProviderException(PROVIDER_NAME, symbol, "Boş yanıt alındı.");
            }

            JsonNode root = objectMapper.readTree(jsonResponse);
            JsonNode dataArray = root.path("data");

            if (!dataArray.isArray() || dataArray.isEmpty()) {
                throw new PriceProviderException(PROVIDER_NAME, symbol, "Bigpara veri dönmedi veya sembol bulunamadı.");
            }

            // Arama sonucundaki eşleşen ilk hisse senedi düğümü
            JsonNode itemNode = dataArray.get(0);

            // c: Anlık seans fiyatı / son işlem fiyatı
            double currentPriceDouble = itemNode.path("c").asDouble(0.0);
            if (currentPriceDouble <= 0.0) {
                currentPriceDouble = itemNode.path("a").asDouble(0.0);
            }
            if (currentPriceDouble <= 0.0) {
                currentPriceDouble = itemNode.path("b").asDouble(0.0);
            }

            // ch: Günlük net TL değişimi
            double changeDouble = itemNode.path("ch").asDouble(0.0);

            // Dünkü kapanış fiyatı = Anlık fiyat - Değişim farkı
            double previousCloseDouble = currentPriceDouble - changeDouble;
            if (previousCloseDouble <= 0.0) {
                // Açılış fiyatına fallback yap
                previousCloseDouble = itemNode.path("o").asDouble(currentPriceDouble);
            }

            if (currentPriceDouble <= 0.0 || previousCloseDouble <= 0.0) {
                throw new PriceProviderException(PROVIDER_NAME, symbol, 
                        String.format("Geçersiz fiyat: anlık=%.4f, dünkü=%.4f", currentPriceDouble, previousCloseDouble));
            }

            BigDecimal currentPrice = BigDecimal.valueOf(currentPriceDouble);
            BigDecimal previousClose = BigDecimal.valueOf(previousCloseDouble);

            // Standart DTO fabrikamız ile getiri oranını virgülden sonra 6 basamak hassasiyetle hesaplar
            MarketPriceDto dto = MarketPriceDto.of(cleanTicker, currentPrice, previousClose, PROVIDER_NAME);
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

        // Java 21 parallelStream ile eşzamanlı ve hızlı yedek çekim
        return symbols.parallelStream()
                .map(symbol -> {
                    try {
                        return getPrice(symbol);
                    } catch (Exception e) {
                        log.warn("[{}] '{}' çekilemedi: {}", PROVIDER_NAME, symbol, e.getMessage());
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
     * Sembolü Bigpara formatına temizler.
     * Örneğin: "THYAO.IS" -> "THYAO", "XU100.IS" -> "XU100"
     */
    private String cleanSymbol(String symbol) {
        if (symbol == null) return "";
        String upper = symbol.trim().toUpperCase();
        if (upper.endsWith(".IS")) {
            return upper.substring(0, upper.length() - 3);
        }
        return upper;
    }
}
