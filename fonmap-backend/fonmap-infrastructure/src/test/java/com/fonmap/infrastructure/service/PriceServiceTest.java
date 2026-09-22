package com.fonmap.infrastructure.service;

import com.fonmap.domain.entity.Instrument;
import com.fonmap.domain.entity.PriceQuote;
import com.fonmap.infrastructure.cache.PriceCacheService;
import com.fonmap.infrastructure.client.PriceProvider;
import com.fonmap.infrastructure.client.dto.MarketPriceDto;
import com.fonmap.infrastructure.client.exception.PriceProviderException;
import com.fonmap.infrastructure.repository.InstrumentRepository;
import com.fonmap.infrastructure.repository.PriceQuoteRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageRequest;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * ============================================================================
 * FONMAP — PriceService Birim Testleri (PriceServiceTest)
 * ============================================================================
 * 
 * Bu test sınıfı, Fonmap platformunun "Fiyat Kaptan Köşkü" olan PriceService'in
 * tüm koruma kalkanlarını, failover (hata kurtarma) zincirini ve önbellekleme
 * stratejisini izole (Mock) nesnelerle adım adım doğrular.
 * 
 * TEST EDİLEN KRİTİK DAVRANIŞLAR:
 * 1. Cache HIT: Redis'te fiyat varsa dış API'lere ve veritabanına SIFIR istek atılır.
 * 2. Cache MISS: Redis'te yoksa Yahoo'dan çekilir; hem Redis'e hem DB'ye yazılır (Dual-Write).
 * 3. Failover (Yedek Güç): Yahoo çökerse sistem anında yedek Bigpara'ya geçer.
 * 4. Akıllı Yönlendirme (Routing): Döviz/Repo sembolleri hisse sağlayıcılarına değil TCMB'ye gider.
 * 5. Son Kale (DB Fallback): İnternet tamamen kesilirse PostgreSQL'deki dünkü son fiyat kurtarılır.
 */
@ExtendWith(MockitoExtension.class)
class PriceServiceTest {

    // 1. Birincil BIST Hisse Fiyat Sağlayıcısı (Dış API)
    @Mock
    private PriceProvider yahooFinancePriceProvider;

    // 2. İkincil Yedek BIST Hisse Fiyat Sağlayıcısı (Failover Dış API)
    @Mock
    private PriceProvider bigparaPriceProvider;

    // 3. Döviz ve Politika/Repo Faizi Sağlayıcısı (TCMB)
    @Mock
    private PriceProvider tcmbPriceProvider;

    // 4. Redis Önbellek Servisi
    @Mock
    private PriceCacheService priceCacheService;

    // 5. Enstrüman Kataloğu Veritabanı Deposu
    @Mock
    private InstrumentRepository instrumentRepository;

    // 6. Fiyat Geçmişi Veritabanı Deposu (price_quotes tablosu)
    @Mock
    private PriceQuoteRepository priceQuoteRepository;

    // Test edilecek asıl servis nesnesi
    private PriceService priceService;

    @BeforeEach
    void setUp() {
        // Her testten önce PriceService bağımlılıkları mock nesnelerle enjekte edilerek başlatılır
        priceService = new PriceService(
                yahooFinancePriceProvider,
                bigparaPriceProvider,
                tcmbPriceProvider,
                priceCacheService,
                instrumentRepository,
                priceQuoteRepository
        );

        // Normalize sembol davranışı: Sembolü kırpıp büyük harfe çevirir ("thyao" -> "THYAO")
        lenient().when(priceCacheService.normalizeSymbol(anyString()))
                .thenAnswer(invocation -> {
                    String arg = invocation.getArgument(0);
                    return arg != null ? arg.trim().toUpperCase() : "";
                });
    }

    // ========================================================================
    // TEST 1: Cache HIT (Önbellekten Hızlı Teslimat)
    // ========================================================================
    @Test
    @DisplayName("1. Cache HIT: Fiyat Redis'te varsa dış sağlayıcılara ve DB'ye hiç gitmeden anında dönmeli")
    void shouldReturnPriceFromCacheWhenAvailable() {
        // HAZIRLIK (GIVEN): Redis'te THYAO için taze fiyat kaydı mevcut
        MarketPriceDto cachedDto = MarketPriceDto.of(
                "THYAO", 
                BigDecimal.valueOf(293.50), 
                BigDecimal.valueOf(285.50), 
                "CACHE"
        );
        when(priceCacheService.getPrice("THYAO")).thenReturn(Optional.of(cachedDto));

        // İŞLEM (WHEN): Fiyat servisine THYAO fiyatı sorulur
        MarketPriceDto result = priceService.getPrice("THYAO");

        // DOĞRULAMA (THEN):
        assertNotNull(result, "Dönen fiyat DTO'su null olamaz");
        assertEquals("THYAO", result.getSymbol());
        assertEquals(0, BigDecimal.valueOf(293.50).compareTo(result.getCurrentPrice()));

        // Ağ tasarrufu doğrulaması: Dış API'lere ve veritabanına SIFIR çağrı yapılmış olmalı!
        verifyNoInteractions(yahooFinancePriceProvider);
        verifyNoInteractions(bigparaPriceProvider);
        verifyNoInteractions(priceQuoteRepository);
    }

    // ========================================================================
    // TEST 2: Cache MISS (Canlı Çekim ve Çift Yönlü Kayıt)
    // ========================================================================
    @Test
    @DisplayName("2. Cache MISS: Önbellekte yoksa Yahoo'dan çekmeli, ardından hem Redis'e hem DB'ye kaydetmeli")
    void shouldFetchFromYahooAndPersistWhenCacheMiss() {
        // HAZIRLIK (GIVEN): Redis boş, fakat Yahoo canlı fiyatı veriyor
        when(priceCacheService.getPrice("THYAO")).thenReturn(Optional.empty());

        MarketPriceDto liveDto = MarketPriceDto.of(
                "THYAO", 
                BigDecimal.valueOf(293.50), 
                BigDecimal.valueOf(285.50), 
                "YAHOO"
        );
        when(yahooFinancePriceProvider.getPrice("THYAO")).thenReturn(liveDto);

        // Veritabanında THYAO enstrümanı mevcut
        Instrument instrument = Instrument.builder()
                .id(UUID.randomUUID())
                .ticker("THYAO")
                .build();
        when(instrumentRepository.findByTicker("THYAO")).thenReturn(Optional.of(instrument));

        // İŞLEM (WHEN): Fiyat servisi çağrılır
        MarketPriceDto result = priceService.getPrice("THYAO");

        // DOĞRULAMA (THEN):
        assertNotNull(result);
        assertEquals(0, BigDecimal.valueOf(293.50).compareTo(result.getCurrentPrice()));

        // Çift Yönlü Kayıt (Dual-Write) doğrulaması:
        // 1. Sonraki 60 saniye için Redis'e konmuş olmalı
        verify(priceCacheService).putPrice(liveDto);
        // 2. Gece mutabakatı ve geçmiş için PostgreSQL 'price_quotes' tablosuna yazılmış olmalı
        verify(priceQuoteRepository).save(any(PriceQuote.class));
    }

    // ========================================================================
    // TEST 3: Failover (Yahoo Çökünce Yedek Bigpara Devreye Girer)
    // ========================================================================
    @Test
    @DisplayName("3. Failover: Yahoo Finance çöktüğünde sistem anında yedek Bigpara'dan fiyatı kurtarmalı")
    void shouldFallbackToBigparaWhenYahooFails() {
        // HAZIRLIK (GIVEN): Redis boş
        when(priceCacheService.getPrice("THYAO")).thenReturn(Optional.empty());

        // 1. Sağlayıcı Yahoo bağlantı hatası fırlatıyor (Çöktü simülasyonu)
        when(yahooFinancePriceProvider.getPrice("THYAO"))
                .thenThrow(new PriceProviderException("YAHOO", "THYAO", "Yahoo API bağlantı koptu (Simülasyon)"));

        // 2. Yedek Sağlayıcı Bigpara sağlam ve anlık fiyatı döndürüyor
        MarketPriceDto bigparaDto = MarketPriceDto.of(
                "THYAO", 
                BigDecimal.valueOf(293.50), 
                BigDecimal.valueOf(285.50), 
                "BIGPARA"
        );
        when(bigparaPriceProvider.getPrice("THYAO")).thenReturn(bigparaDto);

        Instrument instrument = Instrument.builder()
                .id(UUID.randomUUID())
                .ticker("THYAO")
                .build();
        when(instrumentRepository.findByTicker("THYAO")).thenReturn(Optional.of(instrument));

        // İŞLEM (WHEN): Fiyat istenir
        MarketPriceDto result = priceService.getPrice("THYAO");

        // DOĞRULAMA (THEN):
        assertNotNull(result);
        assertEquals("BIGPARA", result.getSource(), "Fiyatın kaynağı yedek Bigpara olmalı");
        assertEquals(0, BigDecimal.valueOf(293.50).compareTo(result.getCurrentPrice()));

        // Failover doğrulaması: Bigpara çağrılmış ve taze veri Redis/DB'ye aktarılmış olmalı
        verify(bigparaPriceProvider).getPrice("THYAO");
        verify(priceCacheService).putPrice(bigparaDto);
        verify(priceQuoteRepository).save(any(PriceQuote.class));
    }

    // ========================================================================
    // TEST 4: Akıllı Yönlendirme (Döviz ve Repo için TCMB)
    // ========================================================================
    @Test
    @DisplayName("4. Routing: Döviz (USD) veya Repo faizi istendiğinde hisse sağlayıcılarına değil TCMB'ye gitmeli")
    void shouldRouteToTcmbForFxAndRepo() {
        // HAZIRLIK (GIVEN): Redis'te USD kuru yok
        when(priceCacheService.getPrice("USD")).thenReturn(Optional.empty());

        MarketPriceDto usdDto = MarketPriceDto.of(
                "USD", 
                BigDecimal.valueOf(48.80), 
                BigDecimal.valueOf(48.80), 
                "TCMB"
        );
        when(tcmbPriceProvider.getPrice("USD")).thenReturn(usdDto);

        // İŞLEM (WHEN): USD kuru sorgulanır
        MarketPriceDto result = priceService.getPrice("USD");

        // DOĞRULAMA (THEN):
        assertNotNull(result);
        assertEquals("TCMB", result.getSource());
        assertEquals("USD", result.getSymbol());

        // Doğrudan TCMB çağrılmalı; hisse sağlayıcısı Yahoo'ya ASLA gidilmemeli
        verify(tcmbPriceProvider).getPrice("USD");
        verifyNoInteractions(yahooFinancePriceProvider);
    }

    // ========================================================================
    // TEST 5: Son Kale (Tüm Dış İnternet Çöktüğünde DB Kapanışını Kurtarma)
    // ========================================================================
    @Test
    @DisplayName("5. Son Kale: Hem Yahoo hem Bigpara çökerse veritabanındaki son kayıt kurtarılmalı")
    void shouldFallbackToDatabaseWhenAllExternalProvidersFail() {
        // HAZIRLIK (GIVEN): Redis boş
        when(priceCacheService.getPrice("THYAO")).thenReturn(Optional.empty());

        // Hem Yahoo hem Bigpara aynı anda çöküyor (Global internet / dış API kesintisi)
        when(yahooFinancePriceProvider.getPrice("THYAO"))
                .thenThrow(new PriceProviderException("YAHOO", "THYAO", "Yahoo kapalı"));
        when(bigparaPriceProvider.getPrice("THYAO"))
                .thenThrow(new PriceProviderException("BIGPARA", "THYAO", "Bigpara kapalı"));

        // PostgreSQL veritabanımızda dünkü işlem gününden kalma son geçerli fiyat kaydı var
        Instrument instrument = Instrument.builder()
                .id(UUID.randomUUID())
                .ticker("THYAO")
                .build();

        PriceQuote dbQuote = PriceQuote.builder()
                .instrument(instrument)
                .currentPrice(BigDecimal.valueOf(290.00))
                .previousClose(BigDecimal.valueOf(285.00))
                .dailyChangeRatio(BigDecimal.valueOf(0.017543))
                .quoteTime(LocalDateTime.now().minusHours(2))
                .source("DB_FALLBACK")
                .build();

        when(priceQuoteRepository.findLatestByTickerWithInstrument(eq("THYAO"), any(PageRequest.class)))
                .thenReturn(List.of(dbQuote));

        // İŞLEM (WHEN): Fiyat istenir
        MarketPriceDto result = priceService.getPrice("THYAO");

        // DOĞRULAMA (THEN): Sistem ÇÖKMEZ, veritabanındaki son bilinen fiyatla tahmine devam eder
        assertNotNull(result);
        assertEquals("DB_FALLBACK", result.getSource(), "Kaynağın veritabanı yedeği olduğu belirtilmeli");
        assertEquals(0, BigDecimal.valueOf(290.00).compareTo(result.getCurrentPrice()));

        // Veritabanı sorgusunun yapıldığı teyit edilir
        verify(priceQuoteRepository).findLatestByTickerWithInstrument(eq("THYAO"), any(PageRequest.class));
    }

    // ========================================================================
    // TEST 6: TCMB Failover (TCMB Çökünce Dolar Kuru Yahoo Finance'ten Kurtarılır)
    // ========================================================================
    @Test
    @DisplayName("6. TCMB Failover: TCMB servisi çöktüğünde Dolar kuru (USD) otomatik olarak Yahoo Finance'ten kurtarılmalı")
    void shouldFallbackToYahooWhenTcmbFailsForUsd() {
        // HAZIRLIK (GIVEN): Redis boş
        when(priceCacheService.getPrice("USD")).thenReturn(Optional.empty());

        // TCMB bağlantı hatası veriyor (Çöktü simülasyonu)
        when(tcmbPriceProvider.getPrice("USD"))
                .thenThrow(new PriceProviderException("TCMB", "USD", "TCMB XML sunucusu yanıt vermedi"));

        // Yedek sağlayıcı Yahoo Finance canlı Dolar kurunu (USDTRY=X) veriyor
        MarketPriceDto yahooUsdDto = MarketPriceDto.of(
                "USD",
                BigDecimal.valueOf(48.85),
                BigDecimal.valueOf(48.80),
                "YAHOO"
        );
        when(yahooFinancePriceProvider.getPrice("USD")).thenReturn(yahooUsdDto);

        Instrument instrument = Instrument.builder()
                .id(UUID.randomUUID())
                .ticker("USD")
                .build();
        when(instrumentRepository.findByTicker("USD")).thenReturn(Optional.of(instrument));

        // İŞLEM (WHEN): USD kuru istenir
        MarketPriceDto result = priceService.getPrice("USD");

        // DOĞRULAMA (THEN):
        assertNotNull(result);
        assertEquals("YAHOO_FX_FALLBACK", result.getSource(), "Kaynağın Yahoo FX yedeği olduğu doğrulanmalı");
        assertEquals(0, BigDecimal.valueOf(48.85).compareTo(result.getCurrentPrice()));

        // TCMB'nin denendiği ve Yahoo'nun kurtardığı doğrulanır
        verify(tcmbPriceProvider).getPrice("USD");
        verify(yahooFinancePriceProvider).getPrice("USD");
        verify(priceCacheService).putPrice(any(MarketPriceDto.class));
        verify(priceQuoteRepository).save(any(PriceQuote.class));
    }
}
