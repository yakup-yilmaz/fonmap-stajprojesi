package com.fonmap.domain.entity;

import jakarta.persistence.*;
import jakarta.validation.constraints.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * ReconciliationResult — Gün Sonu Mutabakat Sonucu Entity'si
 * ============================================================
 *
 * Veritabanı Tablosu: reconciliation_results (Proje Kılavuzu Madde 10)
 *
 * SİSTEMDEKİ ROLÜ:
 * Her gün gece 23:00'te TEFAS'ın açıkladığı resmi birim pay değeri ile
 * bizim seans sonu (18:10) tahminimizin karşılaştırma ve başarı ölçüm kaydıdır.
 *
 * NEDEN BU ENTITY VAR?
 * Şartnamede belirtildiği gibi sistemin başarısı doğruluğuyla ölçülür.
 * Bu tablo olmadan "Tahminlerimiz ne kadar isabetli?" sorusuna cevap veremeyiz.
 * Biriken hata kayıtlarından şu metrikler hesaplanır:
 * - MAE (Mean Absolute Error / Ortalama Mutlak Hata)
 * - RMSE (Root Mean Square Error / Kök Ortalama Kare Hata)
 * Bu metrikler admin panelinde grafik olarak gösterilir.
 *
 * ÇALIŞMA MANTIĞI:
 * 1. Seans sonu (18:10): Calculation Engine son tahmini üretir ve kaydeder.
 * 2. Gece (23:00): TEFAS resmi birim pay değerini açıklar.
 * 3. Reconciliation Worker: TEFAS değerinden resmi getiriyi hesaplar,
 *    bizim tahminle karşılaştırır ve farkı (error) bu tabloya yazar.
 *
 * İLİŞKİLER:
 * - Fund'a FK verir (N-1): Her mutabakat sonucu bir fona aittir.
 *   Bir fonun her işlem günü için 1 adet mutabakat kaydı oluşur.
 */
@Entity
@Table(name = "reconciliation_results")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ReconciliationResult {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    /**
     * Bu mutabakat sonucu hangi fona ait?
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "fund_id", nullable = false)
    private Fund fund;

    /**
     * İşlem günü tarihi.
     * Örnek: 2026-09-18
     * Bir fon + bir tarih kombinasyonu benzersiz olmalıdır (uygulama seviyesinde kontrol).
     */
    @Column(name = "date", nullable = false)
    @NotNull(message = "Mutabakat tarihi boş olamaz")
    private LocalDate date;

    /**
     * Seans sonundaki son tahminimiz.
     * Örnek: +0.016100 (yani +%1.61)
     *
     * Bu değer, seans kapanışındaki (18:10) estimate_runs tablosundaki
     * son kaydın estimated_return değerinin kopyasıdır.
     */
    @Column(name = "predicted_return", nullable = false, precision = 8, scale = 6)
    @NotNull(message = "Tahmin edilen getiri boş olamaz")
    private BigDecimal predictedReturn;

    /**
     * TEFAS'ın açıkladığı resmi net getiri.
     * Örnek: +0.015800 (yani +%1.58)
     *
     * Bu değer gece 23:00'te TEFAS API'sinden çekilen resmi birim pay
     * değerinden hesaplanır: actual_return = (bugünkü_fiyat / dünkü_fiyat) - 1
     *
     * Nullable: TEFAS henüz açıklamamışsa bu alan null olabilir.
     * is_verified alanı bu durumu kontrol eder.
     */
    @Column(name = "actual_return", precision = 8, scale = 6)
    private BigDecimal actualReturn;

    /**
     * Tahmin hatası = Tahmin - Gerçekleşen
     * Örnek: +0.000300 (yani +%0.03 fazla tahmin etmişiz)
     *
     * Pozitif: Tahminimiz gerçekleşenden yüksekmiş (aşırı iyimser).
     * Negatif: Tahminimiz gerçekleşenden düşükmüş (aşırı kötümser).
     * Sıfıra ne kadar yakınsa tahminimiz o kadar isabetli.
     */
    @Column(name = "error_diff", precision = 8, scale = 6)
    private BigDecimal errorDiff;

    /**
     * Tahmin hatasının mutlak değeri = |Tahmin - Gerçekleşen|
     * Örnek: 0.000300 (yönden bağımsız hata büyüklüğü)
     *
     * MAE (Mean Absolute Error) hesaplamasında kullanılır:
     *   MAE = (1/N) × Σ|absolute_error|
     * Bu metrik, tahminlerimizin ortalama ne kadar saptığını gösterir.
     */
    @Column(name = "absolute_error", precision = 8, scale = 6)
    private BigDecimal absoluteError;

    /**
     * TEFAS verisiyle doğrulandı mı?
     * false → TEFAS henüz açıklamamış; actual_return null.
     * true → TEFAS verisi geldi, karşılaştırma yapıldı, error_diff hesaplandı.
     */
    @Column(name = "is_verified", nullable = false)
    @Builder.Default
    private Boolean isVerified = false;

    // ==================== AUDIT ALANLARI ====================

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        this.createdAt = LocalDateTime.now();
        this.updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    protected void onUpdate() {
        this.updatedAt = LocalDateTime.now();
    }
}
