package com.fonmap.domain.entity;

import jakarta.persistence.*;
import jakarta.validation.constraints.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * FundSnapshot — Fon Portföyü Anlık Görüntüsü Entity'si
 * ========================================================
 *
 * Veritabanı Tablosu: fund_snapshots (Proje Kılavuzu Madde 3)
 *
 * SİSTEMDEKİ ROLÜ:
 * KAP'tan indirilen PDF'in sayısal olarak çıkarılmış/parse edilmiş özetidir.
 * Portföyün belirli bir tarihteki "fotoğrafıdır".
 *
 * NEDEN BU ENTITY VAR?
 * Bir fonun portföy dağılımı her ay değişir. Örneğin; THF fonunun Eylül
 * portföyünde %40 THYAO varken, Ekim ayında %20'ye düşmüş olabilir.
 * Bu tablo, zaman içindeki bu aylık dağılımları versiyonlar (snapshot).
 *
 * İLİŞKİLER:
 * - Fund'a FK verir (N-1): Her snapshot bir fona aittir.
 * - FundReport'a FK verir (1-1): Bu snapshot'ın kaynaklandığı orijinal PDF raporu.
 * - Holding tablosu bu tabloya FK verir (1-N): Snapshot içindeki detay satırları (hisseler).
 */
@Entity
@Table(name = "fund_snapshots")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class FundSnapshot {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "fund_id", nullable = false)
    private Fund fund;

    /**
     * Bu snapshot'ı oluşturan kaynak PDF raporu.
     * Benzersiz (unique) olmalıdır; bir rapordan sadece bir snapshot çıkabilir.
     */
    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "report_id", nullable = false, unique = true)
    private FundReport report;

    /**
     * Snapshot (Portföy fotoğrafı) tarihi.
     * Örnek: 2026-08-31
     * FundReport.reportDate ile aynıdır, SQL JOIN'lerine gerek kalmadan
     * hızlı tarih sorgusu yapabilmek için buraya da kopyalanır (denormalizasyon).
     */
    @Column(name = "snapshot_date", nullable = false)
    @NotNull(message = "Snapshot tarihi boş olamaz")
    private LocalDate snapshotDate;

    /**
     * Fonun rapor tarihindeki "Toplam Net Varlık Değeri" (TNV).
     * PDF'in sonundaki "VIII- PORTFÖY DEĞERİ" tablosundan okunur.
     * Örnek: 850,500,000.00 TL
     *
     * ÖNEMLİ: Holding'lerdeki weightRatio (ağırlıklar) hesaplanırken,
     * PDF'ten okunan pozisyon tutarı (totalValue) bu değere bölünür.
     */
    @Column(name = "total_net_asset_value", nullable = false, precision = 18, scale = 2)
    @NotNull(message = "TNV boş olamaz")
    private BigDecimal totalNetAssetValue;

    /**
     * Portföyün yüzde kaçının BIST hisselerinde olduğu.
     * Örnek: 0.8500 (%85)
     * Hisse senedi yoğun fonların (HSYF) vergi avantajını sürdürebilmesi
     * için bu oran yasal olarak %80'in altına düşemez!
     */
    @Column(name = "stock_ratio", nullable = false, precision = 6, scale = 4)
    @NotNull(message = "Hisse oranı boş olamaz")
    private BigDecimal stockRatio;

    /**
     * Portföyün yüzde kaçının VİOP teminatında (nakit) yattığı.
     * Örnek: 0.1200 (%12)
     */
    @Column(name = "viop_cash_ratio", nullable = false, precision = 6, scale = 4)
    @NotNull(message = "VİOP teminat oranı boş olamaz")
    private BigDecimal viopCashRatio;

    /**
     * PDF parse edilirken matematikte tutarsızlık çıktı mı?
     * Örnek: holdings ağırlıkları toplamı %140'ı aştıysa veya
     * okunan TNV ile hesaplanan TNV eşleşmiyorsa true olur.
     * Admin panelinde inceleme gerektirir.
     */
    @Column(name = "is_suspicious", nullable = false)
    @Builder.Default
    private Boolean isSuspicious = false;

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
