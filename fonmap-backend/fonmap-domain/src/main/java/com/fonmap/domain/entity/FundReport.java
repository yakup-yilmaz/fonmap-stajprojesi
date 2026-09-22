package com.fonmap.domain.entity;

import com.fonmap.domain.enums.ReportStatus;
import jakarta.persistence.*;
import jakarta.validation.constraints.*;
import lombok.*;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * FundReport — KAP PDF Rapor Kaydı Entity'si
 * =============================================
 *
 * Veritabanı Tablosu: fund_reports (Proje Kılavuzu Madde 2)
 *
 * SİSTEMDEKİ ROLÜ:
 * KAP'tan (Kamuyu Aydınlatma Platformu) indirilen aylık resmi "Portföy Dağılım
 * Raporu" PDF belgelerinin meta verilerini ve arşiv kaydını tutar.
 *
 * NEDEN BU ENTITY VAR?
 * 1. IDEMPOTENCY (Tekrarlanabilirlik Koruması):
 *    Aynı raporu günde 10 kez çekip sistemi gereksiz yere yormamak için
 *    indirilen dosyanın SHA-256 parmak izini saklarız. Dosya daha önce
 *    indirilmişse (aynı hash) işlem atlanır.
 *
 * 2. DENETLENEBİLİRLİK (Audit):
 *    6 ay sonra "Bu tahmin hangi rapora dayanarak yapılmıştı?" dendiğinde
 *    orijinal PDF URL'si ve tarihi burada kayıtlıdır.
 *
 * İLİŞKİLER:
 * - Fund'a FK verir (N-1): Her rapor bir fona aittir.
 * - FundSnapshot'tan FK alır (1-1): Her rapor başarıyla parse edildiğinde
 *   tam olarak 1 adet snapshot oluşur.
 */
@Entity
@Table(name = "fund_reports")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class FundReport {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    /**
     * Bu rapor hangi fona ait?
     *
     * @ManyToOne: Çok-a-Bir ilişki. Birden fazla rapor aynı fona ait olabilir
     *             (her ay yeni rapor yayımlanır).
     *
     * fetch = FetchType.LAZY:
     * İlişkili Fund entity'si rapor sorgulandığında otomatik yüklenmez;
     * sadece fund.getCode() gibi bir erişim yapıldığında veritabanından çekilir.
     * Bu, gereksiz JOIN sorgusu yapılmasını önler ve performansı artırır.
     *
     * @JoinColumn(name = "fund_id"):
     * Veritabanındaki FK sütununun adını belirler. "fund_reports.fund_id → funds.id"
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "fund_id", nullable = false)
    private Fund fund;

    /**
     * Raporun ait olduğu dönem sonu tarihi.
     * Örnek: 2026-08-31 (Ağustos 2026 sonu portföy dağılımı)
     * Bu tarih, raporun KAP'ta yayımlandığı tarih DEĞİLDİR;
     * portföyün fotoğrafının çekildiği tarihtir.
     */
    @Column(name = "report_date", nullable = false)
    @NotNull(message = "Rapor tarihi boş olamaz")
    private LocalDate reportDate;

    /**
     * Raporun KAP'ta yayımlandığı an.
     * Örnek: 2026-09-05T10:30:00 (KAP'a bildirim zamanı)
     * Nullable: Bazı durumlarda yayımlanma zamanı bilinmeyebilir.
     */
    @Column(name = "publish_date")
    private LocalDateTime publishDate;

    /**
     * KAP üzerindeki PDF indirme linki.
     * Örnek: "https://www.kap.org.tr/tr/BildirimPdf/1234567"
     * Bu URL geriye dönük denetim için saklanır.
     */
    @Column(name = "pdf_url", length = 500)
    private String pdfUrl;

    /**
     * İndirilen PDF dosyasının SHA-256 hash değeri (parmak izi).
     * Örnek: "a1b2c3d4e5f6..." (64 karakter hex string)
     *
     * Mükerrer kaydı engeller: Scraper yeni bir PDF indirdiğinde önce
     * bu hash'i veritabanında arar. Zaten varsa "Bu rapor daha önce
     * indirilmiş" deyip işlemi atlar (idempotency).
     */
    @Column(name = "pdf_sha256", length = 64)
    private String pdfSha256;

    /**
     * PDF rapor işleme durumu — ReportStatus enum'u ile eşleşir.
     * DOWNLOADED → PARSED (başarılı) veya FAILED (hatalı)
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    @NotNull(message = "Rapor durumu boş olamaz")
    @Builder.Default
    private ReportStatus status = ReportStatus.DOWNLOADED;

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
