package com.fonmap.infrastructure.client.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;

/**
 * Piyasa Fiyat Verisi Taşıyıcı Nesnesi (DTO).
 * 
 * Tüm dış sağlayıcılardan (Yahoo Finance, Bigpara, TCMB) gelen verileri
 * ortak ve standart bir yapıda toplar.
 * 
 * KRİTİK KURAL:
 * Dış servislerin hazır döndüğü yuvarlanmış yüzdelere asla güvenilmez!
 * Gün içi getiri oranı (dailyChangeRatio), her zaman anlık fiyat (P_t) ve
 * dünkü kapanıştan (P_t-1) virgülden sonra 6 basamak hassasiyetle BİZİM tarafımızdan hesaplanır.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MarketPriceDto implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * Varlık sembolü / işlem kodu.
     * Örnek: "THYAO", "USDTRY", "XU100", "GC=F"
     */
    private String symbol;

    /**
     * Anlık fiyat — P(t)
     * Örnek: 288.50 TL veya 34.80 TL (Dolar kuru)
     */
    private BigDecimal currentPrice;

    /**
     * Dünkü resmi kapanış fiyatı — P(t-1)
     * Örnek: 285.50 TL veya 34.65 TL
     * Ağırlık kayması (Weight Drift) ve getiri hesabının temel referansıdır.
     */
    private BigDecimal previousClose;

    /**
     * Düne göre gün içi yüzdesel getiri / değişim oranı.
     * Formül: (currentPrice / previousClose) - 1
     * Virgülden sonra 6 basamak hassasiyetle sistem tarafından otomatik hesaplanır.
     * Örnek: +0.010508 (yani +%1.0508)
     */
    private BigDecimal dailyChangeRatio;

    /**
     * Fiyatın temin edildiği dış kaynak.
     * Örnek: "YAHOO_FINANCE", "BIGPARA", "TCMB_XML", "CACHE_FALLBACK"
     */
    private String source;

    /**
     * Fiyatın çekildiği / oluşturulduğu zaman damgası.
     */
    @Builder.Default
    private LocalDateTime quoteTime = LocalDateTime.now();

    /**
     * Kolay ve güvenli nesne oluşturma fabrikası.
     * Anlık fiyat ve dünkü kapanışı alır, getiri oranını virgülden sonra
     * 6 basamak hassasiyetle (RoundingMode.HALF_UP) anında hesaplar.
     */
    public static MarketPriceDto of(String symbol, BigDecimal currentPrice, BigDecimal previousClose, String source) {
        BigDecimal changeRatio = BigDecimal.ZERO;
        if (currentPrice != null && previousClose != null && previousClose.compareTo(BigDecimal.ZERO) > 0) {
            changeRatio = currentPrice
                    .divide(previousClose, 6, RoundingMode.HALF_UP)
                    .subtract(BigDecimal.ONE);
        }

        return MarketPriceDto.builder()
                .symbol(symbol)
                .currentPrice(currentPrice)
                .previousClose(previousClose)
                .dailyChangeRatio(changeRatio)
                .source(source)
                .quoteTime(LocalDateTime.now())
                .build();
    }

    /**
     * Ters repo, mevduat ve nakit para piyasası varlıkları için nesne oluşturma fabrikası.
     * Şartname Madde 4.5: gunluk_getiri = yillik_faiz / 365
     */
    public static MarketPriceDto ofRepo(String symbol, BigDecimal annualRate, String source) {
        BigDecimal dailyAccrual = BigDecimal.ZERO;
        if (annualRate != null && annualRate.compareTo(BigDecimal.ZERO) > 0) {
            dailyAccrual = annualRate.divide(BigDecimal.valueOf(365), 6, RoundingMode.HALF_UP);
        }

        return MarketPriceDto.builder()
                .symbol(symbol)
                .currentPrice(annualRate)
                .previousClose(annualRate)
                .dailyChangeRatio(dailyAccrual)
                .source(source)
                .quoteTime(LocalDateTime.now())
                .build();
    }
}
