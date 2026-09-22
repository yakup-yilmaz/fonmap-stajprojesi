package com.fonmap.domain.entity;

import jakarta.persistence.*;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.*;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * ============================================================================
 * AuditLog — Sistem Denetim İzi (Audit Trail) Entity'si
 * ============================================================================
 *
 * Veritabanı Tablosu: audit_logs (Proje Kılavuzu Madde 13)
 *
 * SİSTEMDEKİ ROLÜ:
 * Sistemde yapılan tüm kritik işlemlerin kronolojik kayıt defteridir.
 * "Kim, ne zaman, hangi IP'den, hangi varlık üzerinde ne yaptı?" sorusuna cevap verir.
 *
 * KAYDEDİLEN İŞLEM ÖRNEKLERİ:
 * - FUND_CREATED → Yeni fon eklendi
 * - FUND_DEACTIVATED → Fon pasife çekildi
 * - ALIAS_APPROVED → Instrument alias eşlemesi onaylandı
 * - SCRAPER_TRIGGERED → KAP scraper'ı manuel tetiklendi
 *
 * İLİŞKİLER:
 * Bağımsız entity. Sadece INSERT yapılır; UPDATE veya DELETE ASLA yapılmaz (immutable log).
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
     * İşlemi yapan kullanıcının UUID kimliği.
     */
    @Column(name = "user_id", nullable = false)
    @NotNull(message = "Kullanıcı ID boş olamaz")
    private UUID userId;

    /**
     * Yapılan işlemin kısa kodu.
     * Örnek: "FUND_CREATED", "ALIAS_APPROVED", "SCRAPER_TRIGGERED"
     */
    @Column(name = "action", nullable = false, length = 100)
    @NotBlank(message = "İşlem tipi boş olamaz")
    private String action;

    /**
     * İşlemin yapıldığı hedef entity adı.
     * Örnek: "Fund", "InstrumentAlias", "FundReport"
     */
    @Column(name = "entity_name", nullable = false, length = 100)
    @NotBlank(message = "Entity adı boş olamaz")
    private String entityName;

    /**
     * İşlemin yapıldığı hedef kaydın ID veya kodu.
     * Örnek: "THF", "123", "a1b2c3d4..."
     */
    @Column(name = "entity_id", nullable = false, length = 100)
    @NotBlank(message = "Entity ID boş olamaz")
    private String entityId;

    /**
     * Değişiklik öncesi eski değerler (JSON formatında).
     */
    @Column(name = "old_values", columnDefinition = "jsonb")
    private String oldValues;

    /**
     * Değişiklik sonrası yeni değerler (JSON formatında).
     */
    @Column(name = "new_values", columnDefinition = "jsonb")
    private String newValues;

    /**
     * İşlemi yapan kullanıcının IP adresi.
     * Örnek: "192.168.1.100"
     */
    @Column(name = "ip_address", nullable = false, length = 45)
    private String ipAddress;

    /**
     * İşlemin gerçekleştiği an.
     */
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        if (this.createdAt == null) {
            this.createdAt = LocalDateTime.now();
        }
    }
}
