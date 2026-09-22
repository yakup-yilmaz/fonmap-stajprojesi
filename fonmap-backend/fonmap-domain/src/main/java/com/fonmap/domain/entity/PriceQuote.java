package com.fonmap.domain.entity;

import jakarta.persistence.*;
import jakarta.validation.constraints.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;

//Her alt varlığın dakika da bir fiyatını ve yüzde değişimini kaydeder.Grafik çizimleri için kullanılır.
/**
 * PriceQuote — Anlık Fiyat Kaydı Entity'si
 * ==========================================
 *
 * Veritabanı Tablosu: price_quotes (Proje Kılavuzu Madde 7)
 *
 * SİSTEMDEKİ ROLÜ:
 * Price Worker servisinin seans saatlerinde (10:00-18:15) Yahoo Finance ve
 * TCMB'den her dakika çektiği anlık fiyat ve dünkü kapanış fiyatı kayıtlarıdır.
 *
 * NEDEN BU ENTITY VAR?
 * Anlık fiyatlar öncelikle hızlı erişim için Redis'te tutulur (TTL: 120
 * saniye).
 * Ancak bu tablo şu nedenlerle gereklidir:
 * 1. GERİYE DÖNÜK DOĞRULAMA (Backtest): "2 hafta önceki tahminimiz neden
 * hatalıydı?"
 * sorusuna cevap vermek için o anki fiyatlar gereklidir.
 * 2. GRAFİK ÇİZİMİ: Fon detay sayfasındaki "Gün İçi Fiyat Çizgisi" grafiği
 * bu tablodan beslenir.
 * 3. GEÇMİŞ SEANS ANALİZİ: İstatistiksel analiz ve MAE/RMSE hesaplamaları için.
 *
 * PERFORMANS NOTU:
 * Bu tablo sistemdeki EN YOĞUN tablodur. 7 fon × ~50 enstrüman × dakikada 1
 * kayıt
 * = saatte ~21.000 kayıt. Composite index (instrument_id, quote_time DESC)
 * sayesinde
 * "bir hissenin en son fiyatı" sorgusu milisaniyeler içinde döner.
 *
 * PRIMARY KEY: BIGSERIAL (Long)
 * UUID yerine Long tercih edilmesinin sebebi: Dakikada yüzlerce kayıt atan
 * yüksek-hacim tablolarda UUID üretimi ve indekslenmesi Long'a göre daha
 * yavaştır.
 *
 * İLİŞKİLER:
 * - Instrument'a FK verir (N-1): Her fiyat kaydı bir enstrümana aittir.
 */
@Entity
@Table(name = "price_quotes")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PriceQuote {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    /**
     * Bu fiyat kaydı hangi enstrümana ait?
     * Örnek: THYAO enstrümanının saat 14:35'teki fiyat kaydı.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "instrument_id", nullable = false)
    private Instrument instrument;

    /**
     * Anlık çekilen fiyat — P(t)
     * Örnek: 285.500000 TL (THYAO'nun saat 14:35'teki fiyatı)
     *
     * precision = 18, scale = 6: Toplam 18 basamak, ondalıktan sonra 6 basamak.
     * Kuruş hassasiyetinin ötesinde mikro-fiyat farklarını yakalayabilmek için
     * 6 ondalık basamak kullanılır (özellikle döviz kurlarında önemli).
     */
    @Column(name = "current_price", nullable = false, precision = 18, scale = 6)
    @NotNull(message = "Anlık fiyat boş olamaz")
    private BigDecimal currentPrice;

    /**
     * Dünkü resmi kapanış fiyatı — P(t-1)
     * Örnek: 289.250000 TL (THYAO'nun dün akşamki kapanış fiyatı)
     *
     * Bu değer, gün içi getiri oranının (r_i) hesaplanmasında referans noktasıdır:
     * r_i = (current_price / previous_close) - 1
     * r_i = (285.50 / 289.25) - 1 = -0.0130 (yani -%1.30)
     *
     * Yahoo Finance API'si bu değeri "regularMarketPreviousClose" alanında döner.
     */
    @Column(name = "previous_close", nullable = false, precision = 18, scale = 6)
    @NotNull(message = "Dünkü kapanış fiyatı boş olamaz")
    private BigDecimal previousClose;

    /**
     * Gün içi getiri oranı — r_i(t)
     * Örnek: -0.013000 (yani -%1.30)
     *
     * Formül: daily_change_ratio = (current_price / previous_close) - 1
     *
     * Bu değer Calculation Engine'in doğrudan kullandığı değerdir:
     * Fon getirisi = Σ(w_i × daily_change_ratio_i) - günlük_gider
     *
     * Negatif değer: Hisse düşmüş demektir.
     * Pozitif değer: Hisse yükselmiş demektir.
     */
    @Column(name = "daily_change_ratio", nullable = false, precision = 8, scale = 6)
    @NotNull(message = "Günlük değişim oranı boş olamaz")
    private BigDecimal dailyChangeRatio;

    /**
     * Fiyatın çekildiği tam zaman damgası.
     * Örnek: 2026-09-20T14:35:00
     * Price Worker her dakika başı çalıştığında bu zaman kaydedilir.
     */
    @Column(name = "quote_time", nullable = false)
    @NotNull(message = "Fiyat zamanı boş olamaz")
    private LocalDateTime quoteTime;

    /**
     * Fiyat verisinin alındığı kaynak.
     * Örnek: "YAHOO_FINANCE", "TCMB_XML", "MANUAL"
     *
     * MANUAL: Admin panelinden elle girilen fiyat (API'nin çöktüğü istisnai
     * durumlarda).
     * Bu alan, geriye dönük doğrulamada "bu fiyat nereden geldi?" sorusuna cevap
     * verir.
     */
    @Column(name = "source", nullable = false, length = 50)
    @NotBlank(message = "Fiyat kaynağı boş olamaz")
    private String source;
}
