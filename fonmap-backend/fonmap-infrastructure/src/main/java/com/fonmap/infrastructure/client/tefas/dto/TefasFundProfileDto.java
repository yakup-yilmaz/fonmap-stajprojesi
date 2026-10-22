package com.fonmap.infrastructure.client.tefas.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

import java.math.BigDecimal;

/**
 * TefasFundProfileDto — TEFAS Resmi Fon Künyesi ve Kurucu Bilgisi Taşıma Nesnesi (DTO)
 * ==================================================================================
 * Kullanıcı yeni bir fon kodu (Örn: "BDY", "TI2", "KLU") girdiğinde, TEFAS API'sinden
 * fonun resmi unvanını, kurucu portföy yönetim şirketini ve kategorisini otomatik
 * keşfederek sisteme aktarır.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@ToString
public class TefasFundProfileDto {

    /** Fonun 3-4 karakterli resmi TEFAS kodu (Örn: "BDY") */
    private String fundCode;

    /** Fonun tam resmi unvanı (Örn: "AK PORTFÖY BIST 100 DIŞI ŞİRKETLER HİSSE SENEDİ (TL) FONU") */
    private String title;

    /** Otomatik ayıklanan kurucu portföy şirketi (Örn: "Ak Portföy Yönetimi A.Ş.") */
    private String manager;

    /** Fon kategorisi (Örn: "Hisse Senedi Fonu") */
    private String category;

    /** En son resmi birim pay fiyatı (TL) */
    private BigDecimal latestPrice;

    /** Önerilen yıllık yönetim ücreti oranı (Varsayılan: 0.0200 / %2.00) */
    @Builder.Default
    private BigDecimal annualFeeRatio = new BigDecimal("0.0200");

    /** Bilginin TEFAS'tan başarıyla doğrulanıp doğrulanmadığı */
    private boolean isFound;
}
