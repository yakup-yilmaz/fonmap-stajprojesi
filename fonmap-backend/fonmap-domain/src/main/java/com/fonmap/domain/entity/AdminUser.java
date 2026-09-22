package com.fonmap.domain.entity;

import com.fonmap.domain.enums.UserRole;
import jakarta.persistence.*;
import jakarta.validation.constraints.*;
import lombok.*;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * AdminUser — Admin Panel Kullanıcı Entity'si
 * =============================================
 *
 * Veritabanı Tablosu: admin_users (Proje Kılavuzu Madde 12)
 *
 * SİSTEMDEKİ ROLÜ:
 * Yönetim paneline giriş yapabilecek kullanıcıların kimlik bilgilerini,
 * şifre hash'lerini ve yetki rollerini saklar. Spring Security + JWT
 * kimlik doğrulaması bu tablodan beslenir.
 *
 * NEDEN BU ENTITY VAR?
 * Admin paneli (fon ekleme, alias onaylama, scraper tetikleme gibi kritik işlemler)
 * şifre korumalı olmak zorundadır. Bu tablo olmadan herkes admin paneline erişebilir.
 *
 * GÜVENLİK NOTU:
 * Şifre ASLA düz metin (plain text) olarak saklanmaz!
 * BCrypt algoritmasıyla hash'lenerek saklanır. BCrypt, her hash işleminde
 * farklı bir "salt" ekler; böylece aynı şifre bile her seferinde farklı
 * hash üretir. Bu, rainbow table saldırılarına karşı koruma sağlar.
 *
 * İLİŞKİLER:
 * Bağımsız entity'dir. audit_logs tablosuyla doğrudan FK ilişkisi yoktur
 * (audit_logs.performed_by alanı username string olarak saklar, FK değil).
 */
@Entity
@Table(name = "admin_users")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AdminUser {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    /**
     * Admin kullanıcı adı.
     * Örnek: "admin", "yakup"
     * unique = true: Aynı kullanıcı adı iki kez kayıt edilemez.
     */
    @Column(name = "username", nullable = false, unique = true, length = 50)
    @NotBlank(message = "Kullanıcı adı boş olamaz")
    private String username;

    /**
     * BCrypt ile hash'lenmiş şifre.
     * Örnek: "$2a$10$N9qo8uLOickgx2ZMRZoHK..." (60-72 karakter arası)
     *
     * Spring Security'nin PasswordEncoder arayüzü ile encode/verify edilir.
     * Ham şifreyi görmek veya geri çözmek teknik olarak imkansızdır.
     * VARCHAR(255): BCrypt hash'leri genelde 60 karakter olur ama gelecekte
     * farklı algoritma kullanılırsa (Argon2 gibi) daha uzun olabilir.
     */
    @Column(name = "password_hash", nullable = false, length = 255)
    @NotBlank(message = "Şifre hash'i boş olamaz")
    private String passwordHash;

    /**
     * Kullanıcı yetki rolü — UserRole enum'u ile eşleşir.
     * Şu an yalnızca ROLE_ADMIN var.
     *
     * Spring Security'nin @PreAuthorize("hasRole('ADMIN')") kontrolü
     * bu alandaki değere göre çalışır.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "role", nullable = false, length = 20)
    @NotNull(message = "Kullanıcı rolü boş olamaz")
    @Builder.Default
    private UserRole role = UserRole.ROLE_ADMIN;

    /**
     * Hesap aktif mi?
     * false yapıldığında admin giriş yapamaz ama kaydı silinmez.
     * Bu sayede geçmiş audit log'ları korunur.
     */
    @Column(name = "is_active", nullable = false)
    @Builder.Default
    private Boolean isActive = true;

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
