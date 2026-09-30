package com.fonmap.infrastructure.parser.dto;

import com.fonmap.domain.enums.AssetClass;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * Bir Fonun Ayrıştırılmış Eksiksiz Portföy Dağılım Raporu Ana Kargo Paketi (DTO).
 * 
 * =========================================================================================
 * 🎯 NE İŞE YARAR? (JUNIOR MÜHENDİS REHBERİ):
 * =========================================================================================
 * Az önce konuştuğumuz gibi; bir fonda 50 tane hisse/varlık varsa, 50 adet 'ParsedHoldingDto'
 * üretilir. Peki bu 50 adet DTO, fonun toplam büyüklüğü (TNV) ve rapor tarihi nereye konur?
 * 
 * İşte bu sınıf, o 50 adet varlığı ve raporun tepe özet bilgilerini (Header) tek bir
 * büyük kutuda toplayan "ANA ÇANTA"dır.
 * 
 * 💡 DÖNÜŞÜM ROLÜ:
 * Bu DTO, 'PdfImportService' servisine teslim edildiğinde 2 parçaya ayrılır:
 * 1. Üst özet bilgileri (TNV, Tarih, Hisse Oranı) -> 'FundSnapshot' entity'sine dönüşür (1 Satır).
 * 2. İçindeki 'holdings' listesi -> 'Holding' entity'lerine dönüşür (50 Satır).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ParsedPortfolioReportDto implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * Raporun ait olduğu fonun benzersiz borsa kodu.
     * Örnek: "THF", "TLY", "TTE", "DFI", "KHA"
     */
    private String fundCode;

    /**
     * Portföy dağılımının geçerli olduğu resmi dönem sonu tarihi.
     * Örnek: 2026-08-31
     */
    private LocalDate reportDate;

    /**
     * Fon Toplam Net Varlık Değeri (TNV TL).
     * PDF'in ilk sayfasındaki veya özet tablosundaki fonun toplam net aktif büyüklüğüdür.
     * Örnek: 74.819.592.001,69 TL (THF Fonu)
     */
    private BigDecimal totalNetAssetValue;

    /**
     * Fon portföyündeki toplam hisse senedi varlık oranı.
     * Örnek: 0.791800 (%79.18)
     */
    private BigDecimal stockRatio;

    /**
     * VİOP nakit teminatı oranı (Takasbank teminatı).
     * Örnek: 0.208200 (%20.82)
     */
    private BigDecimal viopCashRatio;

    /**
     * PDF tablosundan satır satır taranıp çıkarılan tüm menkul kıymetlerin listesi.
     * (Hisseler, VİOP kontratları, ters repo, özel sektör bonosu vb.)
     */
    @Builder.Default
    private List<ParsedHoldingDto> holdings = new ArrayList<>();

    // =====================================================================================
    // 🛠️ YARDIMCI ANALİTİK VE KONTROL METOTLARI
    // =====================================================================================

    /**
     * Portföyde ayrıştırılan toplam menkul kıymet / satır sayısını döner.
     */
    public int getItemCount() {
        return holdings != null ? holdings.size() : 0;
    }

    /**
     * Portföydeki tüm varlıkların ağırlık oranlarının toplamını hesaplar (sum of w_i).
     * Normal hisse fonlarında bu toplam yaklaşık %100 (1.000000) olmalıdır.
     * VİOP kaldıraçlı fonlarda ise %100'ü aşabilir (Örn: %140).
     */
    public BigDecimal calculateTotalWeight() {
        if (holdings == null || holdings.isEmpty()) {
            return BigDecimal.ZERO;
        }

        BigDecimal sum = BigDecimal.ZERO;
        for (ParsedHoldingDto h : holdings) {
            if (h.getWeightRatio() != null) {
                sum = sum.add(h.getWeightRatio());
            }
        }
        return sum.setScale(6, RoundingMode.HALF_UP);
    }

    /**
     * Sadece BIST Hisse Senetlerinin toplam portföy ağırlığını hesaplar.
     * Bu değer, raporun başlığındaki 'stockRatio' ile karşılaştırılarak tutarlılık denetlenir.
     */
    public BigDecimal calculateEquityWeightSum() {
        if (holdings == null || holdings.isEmpty()) {
            return BigDecimal.ZERO;
        }

        BigDecimal sum = BigDecimal.ZERO;
        for (ParsedHoldingDto h : holdings) {
            if (h.isEquity() && h.getWeightRatio() != null) {
                sum = sum.add(h.getWeightRatio());
            }
        }
        return sum.setScale(6, RoundingMode.HALF_UP);
    }

    /**
     * Güvenlik ve Bütünlük Kontrolü (Sanity Check):
     * PDF'ten okunan varlıkların toplam ağırlığı ile rapor başlığındaki oranlar
     * birbirini makul bir toleransla (Örn: +/- %3.0) doğruluyor mu?
     * Eğer tutarsızsa rapor 'şüpheli' (isSuspicious = true) bayrağıyla işaretlenir.
     */
    public boolean isConsistent() {
        if (holdings == null || holdings.isEmpty()) {
            return false;
        }
        BigDecimal totalWeight = calculateTotalWeight();
        // Ağırlık toplamı %0 ise veya boşsa tutarsızdır
        return totalWeight.compareTo(BigDecimal.ZERO) > 0;
    }
}
