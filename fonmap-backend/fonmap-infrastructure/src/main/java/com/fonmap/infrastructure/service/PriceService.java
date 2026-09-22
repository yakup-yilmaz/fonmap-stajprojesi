package com.fonmap.infrastructure.service;

import com.fonmap.domain.entity.Instrument;
import com.fonmap.domain.entity.PriceQuote;
import com.fonmap.domain.enums.AssetClass;
import com.fonmap.infrastructure.cache.PriceCacheService;
import com.fonmap.infrastructure.client.PriceProvider;
import com.fonmap.infrastructure.client.dto.MarketPriceDto;
import com.fonmap.infrastructure.client.exception.PriceProviderException;
import com.fonmap.infrastructure.repository.InstrumentRepository;
import com.fonmap.infrastructure.repository.PriceQuoteRepository;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.*;

/**
 * ============================================================================
 * FONMAP — Fiyat Orkestratörü Servisi (Price Orchestrator Service)
 * ============================================================================
 * 
 * Bu servis, Fonmap platformunun "Fiyat Kaptan Köşkü"dür.
 * Dış sağlayıcılar, önbellek ve veritabanı arasındaki tüm veri akışını yönetir.
 * 
 * KRİTİK GÖREVLERİ:
 * ----------------------------------------------------------------------------
 * 1. Çok Katmanlı Fiyat Temini (Cascade Flow):
 *    - 1. Katman: Önce Redis önbelleğine sorar (0.2 ms - Sıfır ağ trafiği).
 *    - 2. Katman: Önbellekte yoksa 1. Sağlayıcı olan Yahoo Finance'ten çeker.
 *    - 3. Katman (Failover): Yahoo hata verirse Circuit Breaker otomatik olarak
 *      yedek sağlayıcı Bigpara'yı devreye sokar.
 *    - 4. Katman (Son Kale): İnternet tamamen kesilirse veritabanındaki (PostgreSQL)
 *      en son bilinen dünkü kapanış fiyatını kurtarır. Sistem ASLA durmaz.
 * 
 * 2. Resilience4j Koruma Kalkanı:
 *    - @Retry(name = "priceProvider"): Anlık internet dalgalanmasında 500ms arayla 3 kez dener.
 *    - @CircuitBreaker(name = "priceProvider"): Yahoo çökerse (%50 hata oranı) devreyi açar
 *      ve sonraki istekleri boş yere bekletmeden anında Bigpara'ya aktarır.
 * 
 * 3. Çift Yönlü Kayıt (Dual-Write):
 *    Temin edilen taze fiyatı:
 *    - Hızlı erişim için Redis'e (60 saniye TTL) yazar.
 *    - Gece mutabakatı ve geçmiş grafikler için PostgreSQL 'price_quotes' tablosuna kaydeder.
 */
@Slf4j
@Service
public class PriceService {

    private final PriceProvider yahooFinancePriceProvider;
    private final PriceProvider bigparaPriceProvider;
    private final PriceProvider tcmbPriceProvider;
    private final PriceCacheService priceCacheService;
    private final InstrumentRepository instrumentRepository;
    private final PriceQuoteRepository priceQuoteRepository;

    public PriceService(
            @Qualifier("yahooFinancePriceProvider") PriceProvider yahooFinancePriceProvider,
            @Qualifier("bigparaPriceProvider") PriceProvider bigparaPriceProvider,
            @Qualifier("tcmbPriceProvider") PriceProvider tcmbPriceProvider,
            PriceCacheService priceCacheService,
            InstrumentRepository instrumentRepository,
            PriceQuoteRepository priceQuoteRepository) {
        this.yahooFinancePriceProvider = yahooFinancePriceProvider;
        this.bigparaPriceProvider = bigparaPriceProvider;
        this.tcmbPriceProvider = tcmbPriceProvider;
        this.priceCacheService = priceCacheService;
        this.instrumentRepository = instrumentRepository;
        this.priceQuoteRepository = priceQuoteRepository;
    }

    /**
     * Tek bir varlığın anlık piyasa fiyatını getirir.
     * Cache-Aside ve Failover mekanizmalarını uçtan uca işletir.
     * 
     * @param symbol Varlık sembolü (Örn: "THYAO", "USDTRY", "REPO")
     * @return Standart piyasa fiyat DTO'su
     */
    public MarketPriceDto getPrice(String symbol) {
        if (symbol == null || symbol.isBlank()) {
            throw new IllegalArgumentException("Fiyat sorgulamak için sembol boş olamaz.");
        }

        String cleanSymbol = priceCacheService.normalizeSymbol(symbol);

        // 1. AŞAMA: Önce Redis önbelleğine bak (Cache HIT)
        Optional<MarketPriceDto> cached = priceCacheService.getPrice(cleanSymbol);
        if (cached.isPresent()) {
            log.trace("[PriceService] '{}' Redis önbelleğinden teslim edildi.", cleanSymbol);
            return cached.get();
        }

        // 2. AŞAMA: Önbellekte yoksa canlı kaynaktan dayanıklılık (Resilience) zinciriyle çek
        log.debug("[PriceService] '{}' önbellekte yok, canlı sağlayıcıdan çekiliyor...", cleanSymbol);
        MarketPriceDto livePrice;
        try {
            livePrice = fetchLivePriceWithResilience(cleanSymbol);
        } catch (Exception e) {
            // Eğer Resilience4j AOP proxy bypass edilmişse (örneğin self-invocation veya unit test),
            // failover zincirini manuel tetikleyerek Bigpara ve Veritabanı koruma kalkanını garantiye al
            livePrice = fallbackToBigpara(cleanSymbol, e);
        }

        // 3. AŞAMA: Çekilen canlı fiyatı hem Redis'e koy hem PostgreSQL'e kalıcı yaz
        priceCacheService.putPrice(livePrice);
        persistPriceQuote(livePrice);

        return livePrice;
    }

    /**
     * Birden fazla varlığın fiyatını topluca getirir.
     * Önce Redis'te mevcut olanları tek ağ isteğinde (multiGet) alır,
     * sadece eksik kalanları canlı sağlayıcılardan çeker.
     * 
     * @param symbols Varlık sembolleri listesi (Örn: ["THYAO", "ASELS", "USD", "REPO"])
     * @return Sembol -> MarketPriceDto haritası
     */
    public Map<String, MarketPriceDto> getPrices(List<String> symbols) {
        if (symbols == null || symbols.isEmpty()) {
            return Collections.emptyMap();
        }

        // 1. Önce Redis'te var olanları tek hamlede (multiGet) al
        Map<String, MarketPriceDto> resultMap = new HashMap<>(priceCacheService.getPrices(symbols));

        // 2. Önbellekte eksik kalan (süresi dolmuş veya hiç çekilmemiş) sembolleri bul
        List<String> missingSymbols = symbols.stream()
                .filter(Objects::nonNull)
                .map(priceCacheService::normalizeSymbol)
                .filter(s -> !s.isBlank() && !resultMap.containsKey(s))
                .distinct()
                .toList();

        if (!missingSymbols.isEmpty()) {
            log.debug("[PriceService] {} adet sembol önbellekte eksik, dış sağlayıcılardan temin edilecek: {}", 
                    missingSymbols.size(), missingSymbols);

            for (String missingSymbol : missingSymbols) {
                try {
                    MarketPriceDto livePrice = getPrice(missingSymbol);
                    resultMap.put(missingSymbol, livePrice);
                } catch (Exception e) {
                    log.error("[PriceService] '{}' fiyatı hiçbir kaynaktan temin edilemedi: {}", 
                            missingSymbol, e.getMessage());
                }
            }
        }

        return resultMap;
    }

    /**
     * Resilience4j ile korunan canlı fiyat çekim metodu.
     * 
     * @Retry: Dış sağlayıcıda anlık hata olursa 500ms aralıkla 3 defa dener.
     * @CircuitBreaker: Hata oranı %50'yi aşarsa devreyi açar ve fallbackToBigpara'ya yönlendirir.
     */
    @Retry(name = "priceProvider")
    @CircuitBreaker(name = "priceProvider", fallbackMethod = "fallbackToBigpara")
    public MarketPriceDto fetchLivePriceWithResilience(String symbol) {
        // Döviz veya Repo faizi ise doğrudan TCMB sağlayıcısına yönlendir
        if (isFxOrRepo(symbol)) {
            return tcmbPriceProvider.getPrice(symbol);
        }

        // Hisse senedi veya borsa endeksi ise 1. Birincil sağlayıcı Yahoo Finance'e sor
        return yahooFinancePriceProvider.getPrice(symbol);
    }

    /**
     * FALLBACK 1: Yahoo Finance arızalandığında veya Circuit Breaker devreyi açtığında çalışır.
     * İstekleri otomatik olarak yerli yedek sağlayıcı Bigpara'ya paslar.
     */
    public MarketPriceDto fallbackToBigpara(String symbol, Throwable throwable) {
        log.warn("[FAILOVER] Yahoo Finance başarısız ({}), yedek Bigpara devreye giriyor! Sembol: '{}'", 
                throwable.getMessage(), symbol);

        try {
            // Bigpara üzerinden dene
            return bigparaPriceProvider.getPrice(symbol);
        } catch (Exception bigparaEx) {
            log.error("[CRITICAL] Hem Yahoo hem Bigpara çöktü! Son kale veritabanına bakılıyor: '{}'", symbol);
            return fallbackToDatabase(symbol, bigparaEx);
        }
    }

    /**
     * FALLBACK 2 (Son Kale): Tüm dış internet servisleri çöktüğünde çalışır.
     * PostgreSQL 'price_quotes' tablosundaki en son geçerli kapanış fiyatını kurtarır.
     */
    public MarketPriceDto fallbackToDatabase(String symbol, Throwable throwable) {
        String cleanSymbol = priceCacheService.normalizeSymbol(symbol);

        // Veritabanındaki en son fiyat kaydını ara
        List<PriceQuote> latestQuotes = priceQuoteRepository.findLatestByTickerWithInstrument(
                cleanSymbol, PageRequest.of(0, 1));

        if (!latestQuotes.isEmpty()) {
            PriceQuote quote = latestQuotes.get(0);
            log.warn("[DB FALLBACK] '{}' için veritabanındaki son kayıt kurtarıldı -> Fiyat: {}, Tarih: {}", 
                    cleanSymbol, quote.getCurrentPrice(), quote.getQuoteTime());

            return MarketPriceDto.builder()
                    .symbol(cleanSymbol)
                    .currentPrice(quote.getCurrentPrice())
                    .previousClose(quote.getPreviousClose())
                    .dailyChangeRatio(quote.getDailyChangeRatio())
                    .source("DB_FALLBACK")
                    .quoteTime(quote.getQuoteTime())
                    .build();
        }

        // Veritabanında da hiç kayıt yoksa mecbur hata fırlatılır
        throw new PriceProviderException("PRICE_SERVICE", cleanSymbol, 
                "Tüm dış sağlayıcılar (Yahoo, Bigpara) ve veritabanı tükendi, fiyat bulunamadı.");
    }

    /**
     * Çekilen fiyatı PostgreSQL 'price_quotes' tablosuna kaydeder.
     * Akşam TEFAS mutabakatı ve gün içi grafik çizimleri bu tablodan beslenir.
     */
    private void persistPriceQuote(MarketPriceDto dto) {
        try {
            String ticker = priceCacheService.normalizeSymbol(dto.getSymbol());
            Optional<Instrument> instrumentOpt = instrumentRepository.findByTicker(ticker);

            Instrument instrument;
            if (instrumentOpt.isPresent()) {
                instrument = instrumentOpt.get();
            } else {
                // Eğer enstrüman tabloda henüz yoksa dinamik olarak oluştur
                AssetClass assetClass = AssetClass.EQUITY;
                if (isFx(ticker)) {
                    assetClass = AssetClass.FX;
                } else if (isDeposit(ticker)) {
                    assetClass = AssetClass.DEPOSIT;
                }

                instrument = Instrument.builder()
                        .ticker(ticker)
                        .title(ticker + " Otomatik Tanımlı Varlık")
                        .assetClass(assetClass)
                        .currency("TRY")
                        .createdAt(LocalDateTime.now())
                        .build();
                instrument = instrumentRepository.save(instrument);
                log.info("[PriceService] Yeni enstrüman veritabanına otomatik eklendi: {}", ticker);
            }

            PriceQuote quote = PriceQuote.builder()
                    .instrument(instrument)
                    .currentPrice(dto.getCurrentPrice())
                    .previousClose(dto.getPreviousClose())
                    .dailyChangeRatio(dto.getDailyChangeRatio())
                    .quoteTime(dto.getQuoteTime() != null ? dto.getQuoteTime() : LocalDateTime.now())
                    .source(dto.getSource())
                    .build();

            priceQuoteRepository.save(quote);
            log.trace("[PriceService] Fiyat veritabanına kaydedildi -> {}: {}", ticker, dto.getCurrentPrice());

        } catch (Exception e) {
            // Veritabanı yazma hatası getiri hesaplamasını durdurmasın diye log basılır
            log.warn("[PriceService] Fiyat veritabanına yazılamadı: {}", e.getMessage());
        }
    }

    private boolean isFx(String symbol) {
        if (symbol == null) return false;
        String s = symbol.trim().toUpperCase(Locale.ROOT);
        return s.startsWith("USD") || s.startsWith("EUR") || s.startsWith("GBP");
    }

    private boolean isDeposit(String symbol) {
        if (symbol == null) return false;
        String s = symbol.trim().toUpperCase(Locale.ROOT);
        return s.equals("REPO") || s.equals("FAIZ") || s.equals("TRY_REPO") || s.contains("MEVDUAT");
    }

    private boolean isFxOrRepo(String symbol) {
        return isFx(symbol) || isDeposit(symbol);
    }
}
