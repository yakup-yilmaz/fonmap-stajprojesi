package com.fonmap.domain.entity;

import jakarta.persistence.*;
import jakarta.validation.constraints.NotNull;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * DailyHoldingDrift — Günlük Kalibre Edilmiş Ağırlık Kayması Entity'si
 * ======================================================================
 *
 * Veritabanı Tablosu: daily_holding_drifts
 *
 * SİSTEMDEKİ ROLÜ:
 * Her sabah 09:50'de çalışan MorningWeightDriftScheduler veya açılış telafi motoru (StartupCatchupService),
 * dünkü resmi borsa kapanış fiyatlarına (P_t-1) göre Formül 4.2'yi işleterek portföydeki her bir varlığın
 * seans açılışındaki dinamik ağırlığını hesaplar.
 *
 * NEDEN BU ENTITY VAR?
 * Kılavuz gereği: "Ağırlıklar gün boyunca seans bitene kadar SABİT kalır. Sabah sabitlenen ağırlıklar
 * seans boyunca canlı getiri tahmininde kullanılır."
 *
 * Bu tablo; sabah hesaplanan ağırlıkları (effectiveWeight) mühürleyerek seans içi getiri motorunun
 * her dakika sıfırdan drift hesaplamak yerine hazır dondurulmuş ağırlıkları O(1) hızında okumasını sağlar.
 */
@Entity
@Table(name = "daily_holding_drifts", uniqueConstraints = {
        @UniqueConstraint(name = "uk_holding_drift_date", columnNames = {"holding_id", "drift_date"})
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DailyHoldingDrift {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", updatable = false, nullable = false)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "fund_id", nullable = false)
    @NotNull(message = "Fon referansı boş olamaz")
    private Fund fund;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "snapshot_id", nullable = false)
    @NotNull(message = "Snapshot referansı boş olamaz")
    private FundSnapshot snapshot;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "holding_id", nullable = false)
    @NotNull(message = "Holding referansı boş olamaz")
    private Holding holding;

    /**
     * Kalibrasyonun ait olduğu seans tarihi (Genellikle günün tarihi).
     */
    @Column(name = "drift_date", nullable = false)
    @NotNull(message = "Drift tarihi boş olamaz")
    private LocalDate driftDate;

    /**
     * PDF'ten okunan başlangıç ağırlığı (w_i(t_0)).
     */
    @Column(name = "initial_weight", nullable = false, precision = 8, scale = 6)
    @NotNull(message = "Başlangıç ağırlığı boş olamaz")
    private BigDecimal initialWeight;

    /**
     * Fiyat büyüme katsayısı: G_i = P_i(t-1) / P_i(t_0)
     */
    @Column(name = "growth_factor", nullable = false, precision = 10, scale = 6)
    @NotNull(message = "Büyüme katsayısı boş olamaz")
    private BigDecimal growthFactor;

    /**
     * Seans boyunca sabit kalacak nihai kalibre edilmiş açılış ağırlığı (w_i(t)).
     */
    @Column(name = "effective_weight", nullable = false, precision = 8, scale = 6)
    @NotNull(message = "Efektif ağırlık boş olamaz")
    private BigDecimal effectiveWeight;

    /**
     * Dünkü resmi borsa kapanış birim fiyatı (P_i(t-1)).
     */
    @Column(name = "previous_close", precision = 18, scale = 6)
    private BigDecimal previousClose;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        this.createdAt = LocalDateTime.now();
    }
}
