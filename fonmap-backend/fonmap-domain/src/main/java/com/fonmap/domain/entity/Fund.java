package com.fonmap.domain.entity;

import jakarta.persistence.*;
import jakarta.validation.constraints.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Fund — Yatırım Fonu Ana Kimlik Kartı Entity'si
 * =================================================
 *
 * Veritabanı Tablosu: funds (Proje Kılavuzu Madde 1)
 *
 * SİSTEMDEKİ ROLÜ:
 * Fonmap'in takip ettiği yatırım fonlarının (THF, TLY, DOH, DFI, KHA, TTE, TMV)
 * ana kayıt tablosudur. Arayüzde gösterilen her "fon kartı" bu tablodan beslenir.
 *
 * NEDEN BU ENTITY VAR?
 * - Admin dinamik olarak yeni fon ekleyebilmeli (sabit kodlama yapılamaz).
 * - Admin istemediği fonu pasife çekebilmeli (is_active = false).
 * - Kartların ekrandaki sırası değiştirilebilmeli (display_order).
 * - Her fonun yıllık gider oranı burada saklanır (günlük gider hesabında kullanılır).
 *
 * İLİŞKİLER:
 * Bu entity sistemdeki en üst seviye (root) entity'dir.
 * Hiçbir tabloya FK vermez; aksine birçok tablo bu tabloya FK verir:
 * - fund_reports (1-N): Bir fonun birden fazla aylık PDF raporu olur.
 * - fund_snapshots (1-N): Bir fonun her ay için portföy anlık görüntüsü olur.
 * - estimate_runs (1-N): Bir fon için gün içi dakikalık tahminler üretilir.
 * - reconciliation_results (1-N): Bir fon için her gün mutabakat sonucu oluşur.
 *
 * LOMBOK ANOTASYONLARI:
 * @Getter / @Setter → Tüm alanlar için getter ve setter metodlarını otomatik üretir.
 *                      Böylece getCode(), setCode() gibi boilerplate kod yazılmaz.
 * @NoArgsConstructor → Parametresiz (boş) constructor üretir. JPA spesifikasyonu
 *                      her entity'nin parametresiz constructor'a sahip olmasını zorunlu kılar.
 * @AllArgsConstructor → Tüm alanları parametre olarak alan constructor üretir.
 *                       Test yazarken ve Builder pattern ile birlikte kullanılır.
 * @Builder → Builder pattern'i otomatik üretir. Fund.builder().code("THF").title("...").build()
 *            şeklinde okunabilir nesne oluşturmayı sağlar.
 */
@Entity
@Table(name = "funds")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Fund {

    /**
     * Benzersiz birincil anahtar.
     *
     * @GeneratedValue(strategy = GenerationType.UUID):
     * Hibernate 6+ ile gelen modern UUID üretim stratejisi.
     * Veritabanına INSERT yapılırken JVM tarafında otomatik UUID üretilir.
     * Dağıtık sistemlerde (birden fazla sunucu) ID çakışma riski sıfırdır.
     */
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    /**
     * Fon kodu — TEFAS ve KAP'taki resmi kısa kod.
     * Örnek: "THF", "TLY", "TMV", "DOH", "DFI", "KHA", "TTE"
     *
     * unique = true: Aynı fon kodu ikinci kez eklenemez (veritabanı seviyesinde koruma).
     * length = 10: TEFAS fon kodları en fazla 5-6 karakter olur; 10 yeterli bir üst sınırdır.
     */
    @Column(name = "code", nullable = false, unique = true, length = 10)
    @NotBlank(message = "Fon kodu boş olamaz")
    @Size(max = 10, message = "Fon kodu en fazla 10 karakter olabilir")
    private String code;

    /**
     * Fonun resmi tam adı.
     * Örnek: "Tera Portföy Hisse Senedi Fonu (Hisse Senedi Yoğun Fon)"
     */
    @Column(name = "title", nullable = false)
    @NotBlank(message = "Fon adı boş olamaz")
    private String title;

    /**
     * Kurucu portföy yönetim şirketi.
     * Örnek: "Tera Portföy Yönetimi A.Ş.", "Atlas Portföy Yönetimi A.Ş."
     *
     * ÖNEMLİ: Bu alan, PDF Parser'ın hangi ayrıştırma stratejisini (Strategy Pattern)
     * kullanacağını belirler! Tera ve Pardus fonları SPK Standart Landscape formatında,
     * Atlas Portföy ise Portrait formatında rapor yayımlar.
     */
    @Column(name = "manager", nullable = false)
    @NotBlank(message = "Yönetici şirket adı boş olamaz")
    private String manager;

    /**
     * Yıllık fon yönetim gider kesintisi oranı.
     * Örnek: 0.0200 → %2.00 yıllık gider
     *
     * precision = 6: Toplam 6 basamak (tam + ondalık)
     * scale = 4: Ondalıktan sonra 4 basamak (0.0200 gibi hassas değerler için)
     *
     * Calculation Engine bu değeri 252'ye (yıllık iş günü sayısı) bölerek
     * günlük gider payını hesaplar ve her dakika brüt getiriden düşer:
     *   günlük_gider = annual_fee_ratio / 252
     */
    @Column(name = "annual_fee_ratio", nullable = false, precision = 6, scale = 4)
    @NotNull(message = "Yıllık gider oranı boş olamaz")
    private BigDecimal annualFeeRatio;

    /**
     * Fon aktif olarak taranıp hesaplansın mı?
     * true → Scraper bu fonu KAP'ta arar, Price Worker fiyat çeker, Engine tahmin üretir.
     * false → Fon sistemde kayıtlı kalır ama hiçbir servis bu fonu işlemez.
     *
     * Admin panelinden fon kapatılabilir, açılabilir. Silme yerine pasife çekme tercih edilir
     * çünkü geçmiş tahmin verileri ve mutabakat sonuçları korunmalıdır.
     */
    @Column(name = "is_active", nullable = false)
    @Builder.Default
    private Boolean isActive = true;

    /**
     * Arayüzdeki fon kartlarının gösterim sırası.
     * Örnek: THF → 1, TLY → 2, DOH → 3 (Admin panelinden sürükle-bırakla değiştirilebilir)
     */
    @Column(name = "display_order", nullable = false)
    @Builder.Default
    private Integer displayOrder = 0;

    // ==================== AUDIT ALANLARI ====================
    // Proje Kılavuzu Madde 7: Tüm entity'lere otomatik zaman damgası eklenir.

    /**
     * Kaydın veritabanına ilk eklendiği an.
     * updatable = false: Bir kez set edildikten sonra asla değiştirilmez.
     */
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    /**
     * Kaydın son güncellendiği an.
     */
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    /**
     * @PrePersist — JPA Yaşam Döngüsü Callback'i
     * Entity ilk kez veritabanına kaydedilmeden hemen önce (INSERT) otomatik çalışır.
     * createdAt ve updatedAt alanlarını şu anki zamana set eder.
     * Böylece servis katmanında manuel tarih atamaya gerek kalmaz.
     */
    @PrePersist
    protected void onCreate() {
        this.createdAt = LocalDateTime.now();
        this.updatedAt = LocalDateTime.now();
    }

    /**
     * @PreUpdate — JPA Yaşam Döngüsü Callback'i
     * Entity her güncellendiğinde (UPDATE) otomatik çalışır.
     * Sadece updatedAt alanını yeniler; createdAt'e dokunmaz.
     */
    @PreUpdate
    protected void onUpdate() {
        this.updatedAt = LocalDateTime.now();
    }
}
