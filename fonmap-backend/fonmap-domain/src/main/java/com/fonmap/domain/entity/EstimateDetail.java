package com.fonmap.domain.entity;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;

/**
 * EstimateDetail — Hisse Bazlı Katkı Dökümü Entity'si
 * =======================================================
 *
 * Veritabanı Tablosu: estimate_details (Proje Kılavuzu Madde 9)
 *
 * SİSTEMDEKİ ROLÜ:
 * Toplam fon getirisini (%1.23) oluşturan alt kalemlerin (örneğin THYAO
 * hissesinin fona %0.15'lik pozitif katkısı) detaylı kaydıdır.
 *
 * NEDEN BU ENTITY VAR?
 * Frontend'de fon detay sayfasına girildiğinde "Bu Fonu Bugün Ne Yükseltti?
 * / Ne Düşürdü?" şeklinde bir tablo/grafik çizmek için gereklidir.
 * Hangi hissenin fona ne kadar (+/-) katkı yaptığı bu tablodan okunur.
 *
 * İLİŞKİLER:
 * - EstimateRun'a FK verir (N-1): Bu satır hangi "ana tahmin"in parçasıdır?
 *   (1 ana tahmine karşılık ~50 detay satırı olur)
 * - Instrument'a FK verir (N-1): Bu katkıyı yapan hisse senedi hangisidir?
 *
 * PRIMARY KEY: BIGSERIAL (Long)
 * UUID yerine Long kullanılmıştır çünkü estimate_runs'dan 50 kat daha
 * hızlı büyür (1 tahmine 50 detay satırı). Veritabanı şişmesini ve
 * index performans düşüşünü engellemek için BIGSERIAL şarttır.
 */
@Entity
@Table(name = "estimate_details")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class EstimateDetail {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    /**
     * Ait olduğu ana tahmin kaydı.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "estimate_run_id", nullable = false)
    private EstimateRun estimateRun;

    /**
     * Fon getirisine katkı yapan varlık (THYAO, USDTRY vb.)
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "instrument_id", nullable = false)
    private Instrument instrument;

    /**
     * T-1 Güncel Ağırlığı (w_yeni)
     * Örnek: 0.3700 (%37)
     *
     * ÖNEMLİ: Bu değer, PDF'ten okunan statik ağırlık DEĞİLDİR!
     * Proje Kılavuzu 9.4'te açıklanan ağırlık kayması (drift) ile
     * hesaplanmış, güncel seans açılışındaki dinamik ağırlıktır.
     */
    @Column(name = "effective_weight", nullable = false, precision = 6, scale = 4)
    private BigDecimal effectiveWeight;

    /**
     * Varlığın o anki getirisi.
     * Örnek: +0.050000 (%5)
     * PriceQuote tablosundaki dailyChangeRatio değerinin o anki kopyasıdır.
     */
    @Column(name = "asset_return", nullable = false, precision = 8, scale = 6)
    private BigDecimal assetReturn;

    /**
     * Bu varlığın fona yaptığı NET katkı.
     * Formül: weightedContribution = effectiveWeight × assetReturn
     * Örnek: %37 ağırlık × (+%5) getiri = %1.85 katkı (+0.018500)
     *
     * Ana tahmin (EstimateRun.estimatedReturn) aslında buradaki tüm
     * weightedContribution'ların toplamıdır (gider düşülmeden önce).
     */
    @Column(name = "weighted_contribution", nullable = false, precision = 8, scale = 6)
    private BigDecimal weightedContribution;

    // ==================== AUDIT ALANLARI ====================
    // Not: Bu tablo aşırı yüksek hacimli olduğundan (dakikada on binlerce insert)
    // createdAt / updatedAt eklemek DB boyutunu gereksiz şişirir.
    // Zaten bağlı olduğu EstimateRun'ın calculatedAt alanı zaman damgası görevi görür.
    // Performans için audit log alanları bu tabloya konulmamıştır.
}
