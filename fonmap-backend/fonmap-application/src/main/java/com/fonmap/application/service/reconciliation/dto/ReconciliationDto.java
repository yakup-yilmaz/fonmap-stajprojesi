package com.fonmap.application.service.reconciliation.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * ReconciliationDto — Gün Sonu TEFAS Mutabakat Sonuç Taşıyıcısı (DTO)
 * ====================================================================
 *
 * SİSTEMDEKİ ROLÜ:
 * Her iş günü gecesi saat 23:00'te çalışan ReconciliationService tarafından üretilir.
 * Seans saatinde (18:10) modelimizin yaptığı son tahmin ile gece TEFAS'ın açıkladığı
 * kesinleşmiş resmi fiyat ve getiri arasındaki farkı (hata payını) taşır.
 *
 * KULLANIM ALANLARI:
 * 1. Admin Paneli: Modelimizin gün gün ne kadar saptığını (tahmin başarısını) listeler.
 * 2. Kalite Metrikleri: MAE (Ortalama Mutlak Hata) ve RMSE hesaplamalarının temel girdisidir.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ReconciliationDto implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * Veritabanındaki reconciliation_results kayıt ID'si.
     */
    private Long id;

    /**
     * Fonun resmi kodu (Örn: "THF", "TLY", "TTE").
     */
    private String fundCode;

    /**
     * Fonun resmi tam unvanı.
     */
    private String fundTitle;

    /**
     * Mutabakatın yapıldığı borsa işlem günü tarihi.
     * Örnek: 2026-09-22
     */
    private LocalDate date;

    /**
     * Modelimizin gün içi (18:10) ürettiği son tahmini getiri.
     * Örnek: +0.016100 (+%1.61)
     */
    private BigDecimal predictedReturn;

    /**
     * TEFAS'ın gece 23:00'te açıkladığı resmi net getiri.
     * Örnek: +0.015800 (+%1.58)
     */
    private BigDecimal actualReturn;

    /**
     * Tahmin Hatası = predictedReturn - actualReturn
     * Örnek: +0.000300 (yani sadece binde 3 fazla tahmin etmişiz, %99.97 başarı!)
     * Pozitif: Aşırı iyimser tahmin
     * Negatif: Aşırı kötümser tahmin
     */
    private BigDecimal errorDiff;

    /**
     * Mutlak Hata Büyüklüğü = |errorDiff|
     * Yönden bağımsız saf sapma miktarı. MAE hesabında kullanılır.
     * Örnek: 0.000300
     */
    private BigDecimal absoluteError;

    /**
     * TEFAS verisiyle karşılaştırma tamamlandı mı?
     * True: Doğrulandı ve hata hesaplandı.
     * False: TEFAS henüz o günün fiyatını yayımlamamış.
     */
    private boolean isVerified;

    /**
     * Mutabakatın yapıldığı anlık zaman damgası.
     */
    private LocalDateTime verifiedAt;

    /**
     * Model Performance Metrics Summary DTO
     * Fonun geçmiş mutabakatlarına göre genel başarı karnesi.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class PerformanceMetrics implements Serializable {
        private static final long serialVersionUID = 1L;

        private String fundCode;
        private int totalDays;
        
        /** Ortalama Mutlak Hata (Mean Absolute Error) */
        private BigDecimal mae;
        
        /** Kök Ortalama Kare Hata (Root Mean Square Error) */
        private BigDecimal rmse;
        
        /** Yön Doğruluğu Oranı (% kaç gün getiri yönünü -artış/azalış- doğru bildik) */
        private BigDecimal directionalAccuracy;
    }
}
