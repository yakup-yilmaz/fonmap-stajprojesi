package com.fonmap.domain.entity;

import jakarta.persistence.*;
import jakarta.validation.constraints.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Holding — Portföy Detay Satırı (Hisse/Pozisyon) Entity'si (Ayda 1)
 * ============================================================
 *
 * Veritabanı Tablosu: holdings (Proje Kılavuzu Madde 6)
 *
 * SİSTEMDEKİ ROLÜ:
 * KAP'tan indirilen PDF'in içindeki tablolarda (Hisse Senetleri, Türev Araçlar,
 * Ters Repo vb.) okunan HER BİR SATIR bu tabloya kaydedilir.
 * Bir snapshot içinde 50 ile 150 arasında holding satırı bulunur.
 *
 * NEDEN BU ENTITY VAR?
 * Calculation Engine'in asıl kullandığı tablodur. Getiri hesaplarken:
 * Getiri = Σ(holding.weightRatio × instrument.getiri)
 * formülünü uygular.
 *
 * VİOP VE KALDIRAÇ (ÇOK ÖNEMLİ):
 * - Bir VİOP pozisyonu yatırılan teminattan çok daha büyük olabilir.
 * - Bizim sistemimiz kaldıraç oranıyla (4x, 10x) İLGİLENMEZ!
 * - PDF'teki "Fon Portföy Değerine Oranı (%)" sütunundan okunan hazır yüzdelik
 * değeri doğrudan weightRatio alanına yazarız.
 * - Bu sayede VİOP matematiği kodun içine bulaşmaz.
 *
 * İLİŞKİLER:
 * - FundSnapshot'a FK verir (N-1): Bu satır hangi portföy fotoğrafına ait?
 * - Instrument'a FK verir (N-1): Bu satırda tutulan varlık ne? (THYAO, XAU vb.)
 */
@Entity
@Table(name = "holdings")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Holding {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    /**
     * Bu pozisyon satırı hangi snapshot'a ait?
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "snapshot_id", nullable = false)
    private FundSnapshot snapshot;

    /**
     * Bu pozisyonda tutulan finansal varlık (Instrument).
     * Örnek: "THYAO", "F_THYAO0926", "USDTRY"
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "instrument_id", nullable = false)
    private Instrument instrument;

    /**
     * Elimizde bu varlıktan kaç adet var?
     * Örnek: 15,000,000 adet THYAO hissesi.
     */
    @Column(name = "nominal_amount", precision = 18, scale = 4)
    private BigDecimal nominalAmount;

    /**
     * Bu varlığı alırken ödenen birim maliyet.
     */
    @Column(name = "unit_cost", precision = 18, scale = 6)
    private BigDecimal unitCost;

    /**
     * Raporlama günü borsa kapanışındaki birim fiyat.
     */
    @Column(name = "report_price", precision = 18, scale = 6)
    private BigDecimal reportPrice;

    /**
     * Bu pozisyonun toplam büyüklüğü (TL).
     * VİOP ise yatırılan teminat değil, KONTROL EDİLEN toplam büyüklüktür.
     * Örnek: 40.000.000 TL
     */
    @Column(name = "total_value", nullable = false, precision = 18, scale = 2)
    @NotNull(message = "Toplam değer boş olamaz")
    private BigDecimal totalValue;

    /**
     * Bu pozisyonun fon toplamına oranı (Ağırlık - w_i).
     * Örnek: 0.4000 (yani %40.00)
     *
     * PDF'ten doğrudan okunur (VİOP tablosundan).
     * Eğer okunamıyorsa PDFParser tarafından şu formülle hesaplanır:
     * weightRatio = totalValue / FundSnapshot.totalNetAssetValue
     */
    @Column(name = "weight_ratio", nullable = false, precision = 6, scale = 4)
    @NotNull(message = "Ağırlık oranı boş olamaz")
    private BigDecimal weightRatio;

    /**
     * Bu pozisyon açığa satış / short pozisyon mu?
     * Normal hisse (Long): Hisse yükselince kazanılır (false).
     * Short VİOP: Hisse düşünce kazanılır (true).
     *
     * Eğer true ise, Calculation Engine bu satırın getiri katkısını TERSİNE
     * ÇEVİRİR:
     * Katkı = weightRatio × (-1) × instrument.getiri
     */
    @Column(name = "is_short", nullable = false)
    @Builder.Default
    private Boolean isShort = false;

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
