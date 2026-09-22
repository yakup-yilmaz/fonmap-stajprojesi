package com.fonmap.domain.entity;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * InstrumentAlias — PDF Metin Eşleme Köprüsü Entity'si
 * =======================================================
 *
 * Veritabanı Tablosu: instrument_aliases (Proje Kılavuzu Madde 5)
 *
 * SİSTEMDEKİ ROLÜ:
 * PDF'lerdeki standart dışı serbest metinleri kanonik instruments tablosuna
 * bağlayan eşleme köprüsüdür. SİSTEMİN EN CAN ALICI TABLOSUDUR.
 *
 * NEDEN BU ENTITY VAR?
 * KAP PDF'inde hisse kodu yerine bazen şunlar yazılır:
 * - "EREĞLİ DEMİR VE ÇELİK FABRİKALARI T.A.Ş." (uzun şirket unvanı)
 * - "ERDEMİR" (kısaltma)
 * - "EREGL" (doğru kod)
 *
 * Yahoo Finance bu uzun adları bilmez; ona "EREGL.IS" kodu lazımdır.
 * Bu tablo, PDF'ten okunan karmaşık metinleri temizleyip tek bir
 * kanonik enstrümana bağlar.
 *
 * ÇALIŞMA MANTIĞI:
 * 1. PDFParser bir metin okur: "EREĞLİ DEMİR VE ÇELİK FAB. T.A.Ş."
 * 2. 3 Kademeli Eşleme Algoritmasının 3. aşamasında bu tabloya sorulur.
 * 3. Eğer daha önce eşleştirilmişse (is_approved = true) → anında kanonik koda dönüşür.
 * 4. Eğer hiç görülmemişse → yeni alias kaydı oluşturulur (is_approved = false)
 *    ve admin panelinde "Eşleme Bekleyenler" listesine düşer.
 * 5. Admin bir kez onaylayınca artık sistem o ismi sonsuza kadar tanır!
 *
 * İLİŞKİLER:
 * - Instrument'a FK verir (N-1): Birden fazla farklı metin aynı enstrümana bağlanabilir.
 */
@Entity
@Table(name = "instrument_aliases")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class InstrumentAlias {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    /**
     * PDF'ten okunan ham serbest metin.
     * Örnek: "EREĞLİ DEMİR VE ÇELİK FABRİKALARI T.A.Ş."
     * Bu metin olduğu gibi saklanır, hiçbir normalizasyon uygulanmaz.
     * unique = true: Aynı ham metin iki kez eklenemez.
     */
    @Column(name = "raw_name", nullable = false, unique = true, length = 255)
    private String rawName;

    /**
     * Bu serbest metnin bağlandığı kanonik enstrüman.
     * Örnek: "EREĞLİ DEMİR VE ÇELİK FAB." → instruments tablosundaki "EREGL" kaydı
     *
     * Nullable: Sistem otomatik eşleme yapamadığında instrument_id null olabilir.
     * Bu durumda admin panelinde "Eşleştirilmemiş Alias" olarak görünür ve
     * admin manuel olarak doğru enstrümanı seçer.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "instrument_id")
    private Instrument instrument;

    /**
     * Eşleme admin tarafından onaylandı mı?
     * false → Sistem otomatik eşlemiş ama emin değil; admin panelinde
     *          "Eşleme Bekleyenler" listesinde görünür.
     * true → Admin onaylamış; artık bu isim sonsuza kadar tanınır.
     *
     * Otomatik eşleme güven skoru %90'ın üzerindeyse bile admin onayı beklenir
     * çünkü finansal verilerde yanlış eşleme ciddi tahmin hatalarına yol açar.
     */
    @Column(name = "is_approved", nullable = false)
    @Builder.Default
    private Boolean isApproved = false;

    /**
     * Otomatik eşleme algoritmasının benzerlik skoru.
     * Örnek: 0.95 → %95 benzerlik
     *
     * precision = 3, scale = 2: 0.00 ile 1.00 arasında değer alır.
     * Levenshtein distance veya Jaro-Winkler gibi string benzerlik
     * algoritmalarıyla hesaplanır.
     * Nullable: Manuel eşlemelerde skor olmayabilir.
     */
    @Column(name = "match_confidence", precision = 3, scale = 2)
    private BigDecimal matchConfidence;

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
