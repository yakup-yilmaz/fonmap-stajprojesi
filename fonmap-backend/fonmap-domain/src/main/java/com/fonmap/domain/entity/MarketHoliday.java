package com.fonmap.domain.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDate;

/**
 * MarketHoliday — Borsa İstanbul Resmi Tatil Takvimi Entity'si
 * ==============================================================
 *
 * Veritabanı Tablosu: market_holidays (Proje Kılavuzu Madde 11)
 *
 * SİSTEMDEKİ ROLÜ:
 * Borsa İstanbul'un resmi tatil günlerini saklayan takvim tablosudur.
 *
 * NEDEN BU ENTITY VAR?
 * 29 Ekim Cumhuriyet Bayramı, 23 Nisan, Ramazan/Kurban Bayramı gibi
 * tatil günlerinde borsa kapalıdır; hisse fiyatları değişmez. Sistem:
 * 1. Bu tabloya bakarak tatil günlerinde Yahoo Finance API'sine boşuna
 *    istek atmaz (API kotasını korur).
 * 2. Calculation Engine tatil günlerinde tahmin üretmez.
 * 3. Reconciliation servisi tatil günlerinde mutabakat aramaz.
 *
 * ÖZEL DURUM: YARISI GÜN SEANSLAR
 * Arefe günlerinde (Ramazan ve Kurban Bayramı arifesi) Borsa İstanbul
 * saat 13:00'e kadar açıktır. is_half_day = true olan günlerde
 * Price Worker 13:00'ten sonra fiyat çekmeyi durdurur.
 *
 * İLİŞKİLER:
 * Bu entity tamamen bağımsızdır (standalone). Hiçbir tabloya FK vermez
 * ve hiçbir tablodan FK almaz. Sadece referans/lookup tablosu olarak kullanılır.
 *
 * PRİMARY KEY: BIGSERIAL (Long)
 * UUID yerine Long tercih edilmesinin sebebi: Bu tablo yılda sadece ~15 kayıt içerir,
 * dağıtık sisteme ihtiyaç duymaz ve basit sıralı bir ID yeterlidir.
 */
@Entity
@Table(name = "market_holidays")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class MarketHoliday {

    /**
     * @GeneratedValue(strategy = GenerationType.IDENTITY):
     * PostgreSQL'in BIGSERIAL (auto-increment) mekanizmasını kullanır.
     * Her INSERT'te veritabanı otomatik olarak sıradaki sayıyı atar (1, 2, 3...).
     * UUID'den farklı olarak ID üretimi veritabanı tarafında yapılır.
     */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    /**
     * Tatil günü tarihi.
     * Örnek: 2026-10-29 (Cumhuriyet Bayramı)
     *
     * unique = true: Aynı tarih iki kez eklenemez.
     * LocalDate: Saat bilgisi içermez; sadece yıl-ay-gün tutar.
     */
    @Column(name = "holiday_date", nullable = false, unique = true)
    private LocalDate holidayDate;

    /**
     * Tatilin açıklayıcı adı.
     * Örnek: "Cumhuriyet Bayramı", "Ramazan Bayramı 1. Gün", "Yılbaşı"
     */
    @Column(name = "description", nullable = false, length = 100)
    private String description;

    /**
     * Yarım gün seans mı?
     * true → Borsa saat 13:00'e kadar açık (Arefe günleri).
     *         Price Worker 13:00'ten sonra fiyat çekmeyi durdurur.
     * false → Borsa tüm gün kapalı. Hiçbir servis çalışmaz.
     */
    @Column(name = "is_half_day", nullable = false)
    @Builder.Default
    private Boolean isHalfDay = false;
}
