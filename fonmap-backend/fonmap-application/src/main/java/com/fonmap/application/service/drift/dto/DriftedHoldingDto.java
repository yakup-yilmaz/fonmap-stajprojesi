package com.fonmap.application.service.drift.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.math.BigDecimal;
import java.util.UUID;

/**
 * DriftedHoldingDto — Ağırlık Kayması (Weight Drift) Sonuç Veri Taşıyıcısı
 * =========================================================================
 *
 * SİSTEMDEKİ ROLÜ VE ÖNEMİ:
 * KAP'tan indirilen PDF'lerdeki portföy ağırlıkları (w_i(t_0)), rapor tarihindeki
 * (t_0, örneğin 31 Ağustos) duruma aittir. Ancak aradan günler geçtikten sonra (t_1,
 * örneğin 23 Eylül) hisse fiyatları birbirinden bağımsız olarak yükselmiş veya düşmüştür.
 * 
 * Fiyatı artan hissenin portföy içindeki fiili payı (ağırlığı) kendiliğinden büyür,
 * fiyatı düşen hissenin payı ise küçülür. İşte bu olguya finans literatüründe
 * "Ağırlık Kayması (Weight Drift)" denir.
 *
 * Bu DTO, tek bir hisse/pozisyon satırının:
 * 1. Başlangıçtaki PDF ağırlığını (initialWeight),
 * 2. Rapor tarihindeki referans fiyatını (reportPrice),
 * 3. Dün akşamki resmi borsa kapanış fiyatını (previousClose),
 * 4. Fiyat büyüme katsayısını (growthFactor = P_t-1 / P_t0),
 * 5. Seans açılışındaki normalize edilmiş GÜNCEL FİİLİ AĞIRLIĞINI (effectiveWeight)
 * taşır.
 *
 * Calculation Engine (ReturnCalculationService), seans içi dakikalık getiri
 * hesaplarken PDF'teki eski ağırlıkları DEĞİL, bu DTO'daki "effectiveWeight"
 * değerlerini kullanır.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DriftedHoldingDto implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * Holding tablosundaki birincil anahtar (UUID).
     */
    private UUID holdingId;

    /**
     * Menkul kıymetin resmi borsa işlem kodu (Örn: "THYAO", "ASELS", "USDTRY").
     */
    private String ticker;

    /**
     * Varsa menkul kıymetin 12 haneli ISIN kodu (Örn: "TRATHYAO91M5").
     */
    private String isinCode;

    /**
     * PDF raporundan okunan başlangıç statik ağırlığı — w_i(t_0)
     * Örnek: 0.1500 (yani %15.00)
     */
    private BigDecimal initialWeight;

    /**
     * Raporlama günündeki referans kapanış birim fiyatı — P_i(t_0)
     * Örnek: 280.00 TL
     */
    private BigDecimal reportPrice;

    /**
     * Dün akşamki resmi borsa kapanış fiyatı — P_i(t-1)
     * Örnek: 350.00 TL (Hisse %25 değer kazanmış)
     */
    private BigDecimal previousClose;

    /**
     * Varlığın t_0'dan t-1'e kadar olan fiyat büyüme katsayısı — G_i = P_i(t-1) / P_i(t_0)
     * Örnek: 350 / 280 = 1.250000 (Varlık 1.25 katına çıkmış)
     * Eğer fiyat bulunamazsa nötr kabul edilir: 1.000000
     */
    private BigDecimal growthFactor;

    /**
     * Düzeltilmiş ve normalize edilmiş GÜNCEL FİİLİ AĞIRLIK — w_i(t)
     * Formül 4.2'nin nihai sonucudur:
     * w_i(t) = [w_i(t_0) * G_i] / Σ [w_j(t_0) * G_j]
     * Örnek: 0.1735 (yani %17.35)
     */
    private BigDecimal effectiveWeight;

    /**
     * Bu pozisyon açığa satış / short pozisyon mu?
     * True ise hisse fiyatı arttıkça pozisyonun ağırlığı ve değeri küçülür.
     */
    private boolean isShort;
}
