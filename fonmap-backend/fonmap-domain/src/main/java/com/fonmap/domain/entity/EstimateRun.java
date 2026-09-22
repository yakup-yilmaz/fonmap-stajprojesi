package com.fonmap.domain.entity;

import com.fonmap.domain.enums.ConfidenceLevel;
import jakarta.persistence.*;
import jakarta.validation.constraints.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * EstimateRun — Dakikalık Tahmin Sonucu Entity'si
 * =================================================
 *
 * Veritabanı Tablosu: estimate_runs (Proje Kılavuzu Madde 8)
 *
 * SİSTEMDEKİ ROLÜ:
 * Calculation Engine'in her fon için dakika dakika ürettiği tahmini
 * getiri sonuçlarının kaydedildiği ana tablodur.
 *
 * NEDEN BU ENTITY VAR?
 * 1. FONMAP UYGULAMASININ KALBİDİR: Arayüzdeki (frontend) %1.23 gibi
 *    büyük yeşil/kırmızı getiri oranları doğrudan bu tablodan beslenir.
 * 2. GEÇMİŞE DÖNÜK ANALİZ: "Saat 15:30'da THF fonu ne kadardı, kapanışta ne oldu?"
 *    gibi fon içi grafik çizimleri bu tablodaki gün içi verilerinden beslenir.
 *
 * İLİŞKİLER:
 * - Fund'a FK verir (N-1): Bu tahmin hangi fona ait?
 * - FundSnapshot'a FK verir (N-1): Bu tahmin portföyün hangi "fotoğrafına" (dağılımına)
 *   göre yapıldı?
 * - EstimateDetail'den FK alır (1-N): Bu 1 adet tahmini oluşturan 50-60 adet alt
 *   hisse senedi getiri detayı (hangi hisse ne kadar katkı yaptı).
 */
@Entity
@Table(name = "estimate_runs")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class EstimateRun {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    /**
     * Bu tahmin hangi fona ait?
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "fund_id", nullable = false)
    private Fund fund;

    /**
     * Bu tahmin hangi portföy dağılımına (snapshot) dayanılarak hesaplandı?
     * Çünkü aylar değiştikçe portföy dağılımları değişir; geriye dönük
     * analiz yaparken hesaplamanın hangi ayın ağırlıklarıyla yapıldığını bilmek şarttır.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "snapshot_id", nullable = false)
    private FundSnapshot snapshot;

    /**
     * Fonun bugünkü tahmini günlük getiri oranı. (Net Getiri)
     * Örnek: +0.012300 (yani +%1.23)
     *
     * HESAPLAMA MANTIĞI:
     *   Brüt Getiri = Σ(T-1_Güncel_Ağırlık × Güncel_Hisse_Getirisi)
     *   Net Getiri = Brüt Getiri - Günlük_Yönetim_Gider_Payı
     */
    @Column(name = "estimated_return", nullable = false, precision = 8, scale = 6)
    @NotNull(message = "Tahmini getiri boş olamaz")
    private BigDecimal estimatedReturn;

    /**
     * Bu hesaplama anında fon portföyünün yüzde kaçının canlı
     * fiyat verisine ulaşılabildi?
     * Örnek: 0.9500 (Portföyün %95'i canlı hisse, %5'i bilinmeyen bono)
     */
    @Column(name = "coverage_ratio", nullable = false, precision = 6, scale = 4)
    @NotNull(message = "Kapsama oranı boş olamaz")
    private BigDecimal coverageRatio;

    /**
     * coverageRatio (kapsama oranı) değerine göre atanan güvenilirlik seviyesi.
     * HIGH (>= %85), MEDIUM (< %85), LOW (< %70)
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "confidence_level", nullable = false, length = 20)
    @NotNull(message = "Güven seviyesi boş olamaz")
    private ConfidenceLevel confidenceLevel;

    /**
     * Bu tahminin tam olarak hesaplandığı zaman damgası.
     * Frontend, her fon için estimated_return'u en son calculatedAt
     * kaydına göre çeker. (Composite Index kullanılır)
     */
    @Column(name = "calculated_at", nullable = false)
    @NotNull(message = "Hesaplama zamanı boş olamaz")
    private LocalDateTime calculatedAt;

    // ==================== AUDIT ALANLARI ====================
    // Not: estimate_runs çok hızlı büyüyen log tablosu benzeri bir tablo
    // olduğu için updatedAt konulmayabilir ama kılavuza uyumluluk için eklenmiştir.

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        this.createdAt = LocalDateTime.now();
        this.updatedAt = LocalDateTime.now();
        if (this.calculatedAt == null) {
            this.calculatedAt = LocalDateTime.now();
        }
    }

    @PreUpdate
    protected void onUpdate() {
        this.updatedAt = LocalDateTime.now();
    }
}
