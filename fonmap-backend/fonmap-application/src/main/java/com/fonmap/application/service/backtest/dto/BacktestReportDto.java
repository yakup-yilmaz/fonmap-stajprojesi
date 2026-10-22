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
     * Rapor Durumu: "SUCCESS", "INSUFFICIENT_HISTORY", "NO_DATA"
     */
    @Builder.Default
    private String status = "SUCCESS";

    /**
     * Durum Açıklama Mesajı
     */
    private String message;

    /**
     * Güvenilir ekonometrik analiz için gerekli minimum gün sayısı (Varsayılan: 30)
     */
    @Builder.Default
    private Integer minDaysRequired = 30;

    /**
     * Sistemde bu fon için kayıtlı toplam snapshot/PDF raporu sayısı.
     */
    @Builder.Default
    private Integer availableSnapshotCount = 0;

    /**
     * Look-ahead bias olmaksızın simülasyon koşturulabilecek en erken tarih (En eski snapshot + 1 gün).
     */
    private LocalDate earliestTestableDate;

    /**
     * Simülasyonun koşturulduğu en son tarih.
     */
    private LocalDate latestTestableDate;

    /**
     * Snapshot geçişleri ve ağırlık sıfırlama olaylarının dökümü (Kronolojik).
     */
    private List<String> snapshotTransitionLogs;

    /**
     * Zincir Bütünlüğü ve Boşluk Kalkanı Durumları
     */
    public enum ChainIntegrityStatus {
        VALID,                    //  tüm ara raporlar tam ve en güncel rapor devrede
        MISSING_LATEST_SNAPSHOT,  // En son ayın raporu eksik, güvenli sınırda sınırlandırıldı (Smart Clamp)
        INTERMEDIATE_GAP_DETECTED // Ara dönem atlanmış (iki snapshot arasında > 35 gün boşluk var)
    }

    /**
     * Rapor Zincir Bütünlüğü Durumu
     */
    @Builder.Default
    private ChainIntegrityStatus chainStatus = ChainIntegrityStatus.VALID;

    /**
     * Yeni dönem portföy raporunun yüklenmesi gerekiyor mu?
     */
    @Builder.Default
    private boolean requiresNewerSnapshot = false;

    /**
     * Zincir denetim ve rehberlik uyarı mesajı
     */
    private String integrityWarningMessage;

    /**
     * Güvenli simülasyon bitiş tarihi (Smart Clamp sınırı)
     */
    private LocalDate safeSimulationEndDate;

    /**
     * Kayan Pencere İstatistikleri (1 Hafta: Son 5 gün, 1 Ay: Son 22 gün, 2 Ay: Son 44-60 gün)
     */
    private RollingWindowSummary oneWeekSummary;
    private RollingWindowSummary oneMonthSummary;
    private RollingWindowSummary twoMonthsSummary;

    /**
     * Toplam Test Edilen İş Günü Sayısı (Örn: 30, 45, 60)
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
         * Modelimizin o gün için hesapladığı tahmini birim pay fiyatı: P_t-1 * (1 + estimatedReturn)
         */
        private BigDecimal estimatedTefasPrice;

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

        /**
         * Başlangıçtan bugüne modelin birikimli (kümülatif) getiri eğrisi: Π (1 + R_est) - 1
         */
        private BigDecimal cumulativeEstimatedReturn;

        /**
         * Başlangıçtan bugüne TEFAS'ın birikimli (kümülatif) resmi getiri eğrisi: Π (1 + R_act) - 1
         */
        private BigDecimal cumulativeActualReturn;

        /**
         * Bu işlem gününde yeni bir portföy raporuna geçiş yapıldı mı?
         */
        private boolean snapshotTransitionDay;

        /**
         * Geçiş açıklaması (Örn: "30 Eylül 2026 raporu devreye girdi, ağırlıklar sıfırlandı.")
         */
        private String transitionRemark;
    }

    /**
     * Kayan Pencere (Rolling Window) Özet Metrikleri
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class RollingWindowSummary {
        private String windowLabel; // "1 HAFTA (5 GÜN)", "1 AY (22 GÜN)", "2 AY (60 GÜN)"
        private Integer daysCount;
        private BigDecimal meanAbsoluteError;
        private BigDecimal rootMeanSquaredError;
        private BigDecimal directionalAccuracy;
        private BigDecimal successRate;
    }
}
