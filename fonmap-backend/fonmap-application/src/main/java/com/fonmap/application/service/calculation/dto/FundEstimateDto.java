package com.fonmap.application.service.calculation.dto;

import com.fonmap.domain.enums.ConfidenceLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * FundEstimateDto — Fon Tahmini Canlı Getiri Taşıyıcısı (DTO)
 * =============================================================
 *
 * SİSTEMDEKİ ROLÜ:
 * Seans saatleri (10:00 - 18:10) boyunca Calculation Engine (ReturnCalculationService)
 * tarafından dakika dakika üretilen tahmini getiri sonucunu taşır.
 *
 * KULLANIM ALANLARI:
 * 1. REST API: Frontend (Next.js) her 60 saniyede bir GET isteği atarak anlık fon kartını günceller.
 * 2. Detay Sayfası: Hangi hissenin fona ne kadar pozitif/negatif katkı yaptığını
 *    listelemek için "details" listesini sağlar.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FundEstimateDto implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * Veritabanındaki estimate_runs tablosu kayıt kimliği (UUID).
     */
    private UUID estimateRunId;

    /**
     * Fonun resmi kodu (Örn: "THF", "TLY", "TTE").
     */
    private String fundCode;

    /**
     * Fonun resmi tam unvanı.
     */
    private String fundTitle;

    /**
     * Fonun en son açıklanan resmi TEFAS birim fiyatı (TL).
     * Dün akşamki resmi kapanış fiyatıdır.
     * Örnek: 11.194749 TL
     */
    private BigDecimal currentPrice;

    /**
     * Fonun o dakikadaki seans içi TAHMİNİ BİRİM FİYATI (TL).
     * Formül: currentPrice * (1 + estimatedReturn)
     * Örnek: 10.979091 TL
     */
    private BigDecimal estimatedPrice;

    /**
     * Fonun bugünkü NİHAİ TAHMİNİ NET GETİRİSİ (Kullanıcının ekranda gördüğü yüzde).
     * Formül: Brüt Getiri - Günlük Yönetim Gideri
     * Örnek: -0.019264 (-%1.9264)
     */
    private BigDecimal estimatedReturn;

    /**
     * Fon portföyündeki varlıkların toplam brüt getirisi (gider düşülmeden önce).
     * Formül: Σ [ effectiveWeight * assetReturn ]
     */
    private BigDecimal grossReturn;

    /**
     * Fondan bugün için düşülen günlük yönetim gider payı.
     * Formül: annualFeeRatio / 252
     * Örnek: 0.000079 (%0.0079)
     */
    private BigDecimal dailyFee;

    /**
     * Portföyün canlı fiyatı bulunabilen kısmının oranı.
     * Örnek: 0.9650 (%96.50)
     */
    private BigDecimal coverageRatio;

    /**
     * Kapsama oranına göre belirlenen güven seviyesi (HIGH, MEDIUM, LOW).
     */
    private ConfidenceLevel confidenceLevel;

    /**
     * Tahminin hesaplandığı anlık zaman damgası.
     */
    private LocalDateTime calculatedAt;

    /**
     * Bu tahmini oluşturan alt varlıkların (hisseler, repo) detay katkı listesi.
     * Frontend detay sayfasında "En Çok Kazandıran / Kaybettiren Hisseler" tablosunu çizer.
     */
    private List<EstimateDetailItem> details;

    /**
     * EstimateDetailItem — Tekil Hisse/Varlık Katkı Detay DTO'su
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class EstimateDetailItem implements Serializable {
        private static final long serialVersionUID = 1L;

        /**
         * Varlık sembolü (Örn: "THYAO", "ASELS", "REPO").
         */
        private String ticker;

        /**
         * Varlık unvanı / şirket adı.
         */
        private String title;

        /**
         * Seans açılışındaki güncel dinamik ağırlık — w_i(t).
         * Örnek: 0.1735 (%17.35)
         */
        private BigDecimal effectiveWeight;

        /**
         * Varlığın o anki gün içi getirisi — r_i(t).
         * Örnek: +0.025000 (+%2.50)
         */
        private BigDecimal assetReturn;

        /**
         * Bu varlığın fonun toplam getirisine net katkısı.
         * Formül: effectiveWeight * assetReturn (Short ise eksiyle çarpılır)
         * Örnek: +0.004338 (+%0.43 katkı)
         */
        private BigDecimal weightedContribution;

        /**
         * Pozisyon açığa satış / short pozisyon mu?
         */
        private boolean isShort;
    }
}
