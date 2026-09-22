package com.fonmap.domain.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * AuditLog — Sistem Denetim İzi (Audit Trail) Entity'si
 * =======================================================
 *
 * Veritabanı Tablosu: audit_logs (Proje Kılavuzu Madde 13)
 *
 * SİSTEMDEKİ ROLÜ:
 * Sistemde yapılan tüm kritik işlemlerin kronolojik kayıt defteridir.
 * "Kim, ne zaman, hangi IP'den, ne yaptı?" sorusuna cevap verir.
 *
 * NEDEN BU ENTITY VAR?
 * Finansal uygulamalarda denetim izi (audit trail) yasal zorunluluktur.
 * 6 ay sonra "Bu fonu kim ekledi?", "Alias eşlemesini kim onayladı?",
 * "Scraper'ı kim manuel tetikledi?" sorularına cevap verebilmek gerekir.
 *
 * KAYDEDİLEN İŞLEM ÖRNEKLERİ:
 * - FUND_CREATED → Yeni fon eklendi
 * - FUND_DEACTIVATED → Fon pasife çekildi
 * - ALIAS_APPROVED → Instrument alias eşlemesi onaylandı
 * - SCRAPER_TRIGGERED → KAP scraper'ı manuel tetiklendi
 * - LOGIN_SUCCESS → Admin paneline başarılı giriş
 * - LOGIN_FAILED → Başarısız giriş denemesi
 *
 * ÖNEMLİ TASARIM KARARI:
 * Bu tablo AdminUser'a FK ile bağlı DEĞİLDİR. performed_by alanı
 * string (username) olarak saklanır. Sebebi: Admin kullanıcı silinse bile
 * geçmiş log kayıtları korunmalıdır (orphan record olmamalı).
 *
 * İLİŞKİLER:
 * Tamamen bağımsız entity. Hiçbir tabloya FK vermez ve almaz.
 * Sadece INSERT yapılır; UPDATE veya DELETE ASLA yapılmaz (immutable log).
 */
@Entity
@Table(name = "audit_logs")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AuditLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    /**
     * Yapılan işlemin kısa kodu.
     * Örnek: "FUND_CREATED", "ALIAS_APPROVED", "SCRAPER_TRIGGERED", "LOGIN_SUCCESS"
     * Bu alan üzerinden filtreleme yapılarak belirli türdeki işlemler listelenebilir.
     */
    @Column(name = "action", nullable = false, length = 100)
    private String action;

    /**
     * İşlemi yapan admin kullanıcı adı.
     * Örnek: "admin", "yakup"
     * NOT: Bu alan admin_users tablosuna FK DEĞİLDİR (bkz. sınıf üstündeki açıklama).
     */
    @Column(name = "performed_by", nullable = false, length = 50)
    private String performedBy;

    /**
     * İşlemin gerçekleştiği zaman damgası.
     * Bu entity'de createdAt/updatedAt yerine bu alan kullanılır
     * çünkü audit log kaydı bir kez oluşturulur, asla güncellenmez.
     */
    @Column(name = "performed_at", nullable = false)
    private LocalDateTime performedAt;

    /**
     * İşlemi yapan kullanıcının IP adresi.
     * Örnek: "192.168.1.100", "2001:0db8:85a3::8a2e:0370:7334" (IPv6)
     * length = 45: IPv6 adreslerinin tam gösterimi en fazla 45 karakter olabilir.
     */
    @Column(name = "ip_address", length = 45)
    private String ipAddress;

    /**
     * İşlemin detay bilgisi — JSON formatında serbest metin.
     * Örnek: {"fundCode": "THF", "action": "activated", "previousState": "inactive"}
     *
     * @Column(columnDefinition = "TEXT"):
     * PostgreSQL'de VARCHAR yerine TEXT tipi kullanılır çünkü detay içeriğinin
     * boyutu önceden tahmin edilemez. TEXT tipi sınırsız karakter uzunluğu sağlar.
     */
    @Column(name = "details", columnDefinition = "TEXT")
    private String details;

    /**
     * @PrePersist — Log oluşturulma zamanını otomatik set eder.
     * Audit log kaydı ASLA güncellenmez, bu yüzden @PreUpdate yoktur.
     */
    @PrePersist
    protected void onCreate() {
        if (this.performedAt == null) {
            this.performedAt = LocalDateTime.now();
        }
    }
}
