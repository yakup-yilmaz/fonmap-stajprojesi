package com.fonmap.infrastructure.client.tefas.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * TefasFundDto — TEFAS Resmi Kapanış Fiyatı ve Portföy Veri Taşıma Nesnesi (DTO)
 * ==============================================================================
 *
 * SİSTEMDEKİ ROLÜ:
 * Her iş günü akşamı saat 23:00'te TEFAS (Türkiye Elektronik Fon Alım Satım Platformu)
 * resmi REST API'sinden çekilen kesinleşmiş fon verilerini taşır.
 *
 * MUTABAKATTAKİ (RECONCILIATION) KRİTİK GÖREVİ:
 * Biz gün boyunca saat 10:00 - 18:10 arasında hisse senetlerinin anlık borsa fiyatlarına
 * dayanarak fonun getirisini TAHMİN ederiz (EstimateRun).
 * Gece 23:00'te ise bu DTO üzerinden TEFAS'ın açıkladığı RESMİ fiyatı alırız.
 * Tahminimiz ile TEFAS resmi verisi karşılaştırılarak sistemin mutlak hata payı (MAE)
 * ve karesel hata karekökü (RMSE) başarı metrikleri üretilir.
 *
 * NEDEN BIGDECIMAL VE 6 BASAMAK?
 * SPK mevzuatına göre fon katılma payı birim fiyatları virgülden sonra en az 6 basamak
 * (Örn: 3.456789 TL) hassasiyetle hesaplanmak zorundadır. Double veya float veri tipleri
 * ikili sayı tabanında yuvarlama anomalilerine yol açtığından finansal hesaplamalarda
 * kesinlikle 'BigDecimal' kullanılır.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@ToString
public class TefasFundDto {

    /**
     * Fonun resmi 3-4 karakterli TEFAS kodu.
     * Örnek: "THF", "TLY", "TTE", "DFI"
     */
    private String fundCode;

    /**
     * Resmi fiyatın geçerli olduğu borsa işlem günü tarihi.
     * TEFAS'ta gün içi saatlik fiyat yoktur; her iş günü için tek bir resmi kapanış günü vardır.
     * Örnek: 2026-09-22
     */
    private LocalDate priceDate;

    /**
     * Fonun 1 adet katılma payının resmi TL birim fiyatı (P_t).
     * Virgülden sonra 6 basamak hassasiyetindedir.
     * Örnek: 3.456789 TL
     */
    private BigDecimal unitPrice;

    /**
     * Fonun tedavüldeki (piyasada yatırımcıların elinde dolaşan) toplam pay adedi.
     * Örnek: 150000000.00 adet
     */
    private BigDecimal outstandingShares;

    /**
     * Fonun toplam portföy / net varlık büyüklüğü (TL).
     * Matematiksel kural: Toplam Değer = unitPrice * outstandingShares (yaklaşık).
     * Örnek: 518518350.00 TL
     */
    private BigDecimal totalPortfolioValue;

    /**
     * Önceki iş gününe göre fonun TEFAS tarafından gerçekleşen resmi net getiri oranı.
     * Formül: r = (P_t - P_{t-1}) / P_{t-1}
     * Örnek: +0.014800 (yani %1.48 getiri)
     */
    private BigDecimal dailyReturn;

    /**
     * Bu resmi verinin sistemimiz tarafından TEFAS sunucusundan çekildiği anın tam zamanı.
     * Gece 23:00 mutabakatının tam saat, dakika ve saniyesini belgeler.
     * Örnek: 2026-09-22T23:05:14
     */
    private LocalDateTime fetchedAt;
}
