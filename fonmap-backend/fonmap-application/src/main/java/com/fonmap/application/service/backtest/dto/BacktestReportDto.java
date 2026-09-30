package com.fonmap.application.service.backtest.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * 60 Günlük Geriye Dönük Doğruluk ve Başarı Karnesi DTO'su (Backtest Report).
 * 
 * Kılavuz Referansı: Bölüm 8.4 ve Bölüm 10.4
 * 
 * Bir yatırım fonunun geçmiş iş günlerinde seans sonu kapanış tahminlerinin,
 * aynı gece TEFAS tarafından ilan edilen resmi fon fiyatlarıyla karşılaştırıldığı
 * kantitatif başarı karnesidir.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BacktestReportDto {

    /**
     * Fon Borsa Kodu (Örn: "THF", "TLY")
     */
    private String fundCode;

    /**
     * Fonun Resmi Unvanı (Örn: "Tera Portföy Hisse Senedi Fonu")
     */
    private String fundTitle;

    /**
     * Simülasyon Başlangıç Tarihi
     */
    private LocalDate startDate;

    /**
     * Simülasyon Bitiş Tarihi
     */
    private LocalDate endDate;

    /**
     * Toplam Test Edilen İş Günü Sayısı (Örn: 60)
     */
    private Integer totalDaysTested;

    /**
     * Ortalama Mutlak Hata (MAE - Mean Absolute Error).
     * Tüm test günlerinin mutlak hatalarının aritmetik ortalamasıdır.
     * Hedef: <= 0.0015 (%0.15 / 15 baz puan).
     */
    private BigDecimal meanAbsoluteError;

    /**
     * Kök Ortalama Kare Hata (RMSE - Root Mean Squared Error).
     * Büyük sapmaları cezalandıran istatistiksel standart sapma metriği.
     */
    private BigDecimal rootMeanSquaredError;

    /**
     * Test dönemi boyunca karşılaşılan en büyük tekil mutlak sapma.
     */
    private BigDecimal maxError;

    /**
     * En büyük hatanın gerçekleştiği işlem günü.
     */
    private LocalDate maxErrorDate;

    /**
     * Test dönemi boyunca karşılaşılan en küçük tekil mutlak sapma.
     */
    private BigDecimal minError;

    /**
     * En başarılı tahminin gerçekleştiği işlem günü.
     */
    private LocalDate minErrorDate;

    /**
     * Yönsel Doğruluk Oranı (Directional Accuracy).
     * Fonun prim yaptığı günlerde pozitif (+), değer kaybettiği günlerde negatif (-)
     * tahmin etme başarısının yüzdesi (Örn: 0.9500 = %95.00).
     */
    private BigDecimal directionalAccuracy;

    /**
     * Başarı Oranı (Success Rate / Genel Model Başarısı).
     * Mutlak hatanın fon kategorisi için belirlenen normal tolerans eşiği (örn: <= %0.50) altında kaldığı günlerin oranı.
     */
    private BigDecimal successRate;

    /**
     * Kusursuz Başarı Oranı (Strict Success Rate).
     * Mutlak hatanın strict eşiğin altında (örn: <= %0.20) kaldığı günlerin oranı.
     */
    private BigDecimal strictSuccessRate;

    /**
     * Fon Varlık / Risk Kategorisi (Örn: "HİSSE SENEDİ AĞIRLIKLI", "PARA PİYASASI / LİKİT", "BORÇLANMA ARAÇLARI")
     */
    private String fundCategory;

    /**
     * Eşik Belirleme Kuralı ve Ekonometrik Açıklaması
     */
    private String ruleExplanation;

    /**
     * Bu fon için işletilen normal tolerans eşiği (Örn: 0.0050 = %0.50)
     */
    private BigDecimal appliedNormalThreshold;

    /**
     * Bu fon için işletilen kusursuz eşiği (Örn: 0.0020 = %0.20)
     */
    private BigDecimal appliedStrictThreshold;

    /**
     * Kusursuz Tahmin Yapılan Gün Sayısı (Hata <= strictThreshold)
     */
    private Integer perfectDaysCount;

    /**
     * Normal Tolerans İçi Tahmin Yapılan Gün Sayısı (strictThreshold < Hata <= normalThreshold)
     */
    private Integer acceptableDaysCount;

    /**
     * Tolerans Dışı Aykırı Sapma (Anomali) Gerçekleşen Gün Sayısı (Hata > normalThreshold)
     */
    private Integer anomalyDaysCount;

    /**
     * Günlük simülasyon ve mutabakat kayıtlarının kronolojik dökümü.
     */
    private List<DailyBacktestRecord> dailyRecords;

    /**
     * Tek bir işlem gününe ait simülasyon detay kaydı.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class DailyBacktestRecord {

        /**
         * Simüle edilen işlem günü tarihi
         */
        private LocalDate date;

        /**
         * "Point-in-Time" ilkesiyle o gün geçerli olan KAP snapshot'ının tarihi.
         * Look-ahead bias (geleceğe bakma hatası) oluşmadığını kanıtlar.
         */
        private LocalDate snapshotDateUsed;

        /**
         * Dünkü TEFAS resmi kapanış fiyatı (P_t-1)
         */
        private BigDecimal previousTefasPrice;

        /**
         * O günün resmi TEFAS kapanış fiyatı (P_t)
         */
        private BigDecimal actualTefasPrice;

        /**
         * TEFAS Resmi Günlük Getirisi: (P_t / P_t-1) - 1
         */
        private BigDecimal actualReturn;

        /**
         * Modelimizin Seans Sonu Tahmini Net Getirisi
         */
        private BigDecimal estimatedReturn;

        /**
         * Tahmin Hatası (Hata Farkı): Tahmin - Gerçek
         */
        private BigDecimal errorDiff;

        /**
         * Mutlak Hata: |errorDiff|
         */
        private BigDecimal absoluteError;

        /**
         * Yön Tutarlılığı: Tahmin ve gerçek aynı yönde mi kapattı?
         */
        private boolean directionMatched;

        /**
         * O gün portföyde fiyatı bulunabilen varlıkların kapsama oranı (Örn: %99.98)
         */
        private BigDecimal coverageRatio;

        /**
         * Tolerans Durumu ("KUSURSUZ 🟢", "TOLERANS_İÇİ 🟡", "ANOMALİ 🔴")
         */
        private String toleranceStatus;
    }
}
