package com.fonmap.domain.enums;

/**
 * UserRole — Admin Kullanıcı Rolü Enum'u
 * ========================================
 *
 * Kullanıldığı Tablo: admin_users.role
 *
 * Admin panelindeki kullanıcıların yetki seviyesini belirler.
 * Spring Security framework'ünün @PreAuthorize("hasRole('ADMIN')")
 * anotasyonuyla entegre çalışır.
 *
 * Spring Security Konvansiyonu:
 * Spring Security rol isimlerinin "ROLE_" prefix'iyle başlamasını bekler.
 * Bu yüzden enum değeri ROLE_ADMIN olarak tanımlanmıştır; Spring Security
 * bunu otomatik olarak "ADMIN" rolüne çevirir.
 *
 * Şu An Tek Rol Var (ROLE_ADMIN) Ama İleride Genişletilebilir:
 * - ROLE_VIEWER → Sadece dashboard'u görebilen, değişiklik yapamayan kullanıcı
 * - ROLE_OPERATOR → Scraper tetikleyebilen ama fon ekleme/silme yetkisi olmayan kullanıcı
 */
public enum UserRole {

    /**
     * Tam yetkili sistem yöneticisi.
     * Bu role sahip kullanıcı şu işlemleri yapabilir:
     * - Yeni fon ekleme / mevcut fonu pasife çekme
     * - Instrument alias eşleme onaylama
     * - KAP scraper'ını manuel tetikleme
     * - Sistem ayarlarını değiştirme
     * - Tüm admin paneli sayfalarına erişim
     */
    ROLE_ADMIN
}
