package com.fonmap.application.service.pdf.dto;

import com.fonmap.domain.enums.AssetClass;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * 📊 Admin PDF Dry-Run Önizleme DTO'su.
 * -----------------------------------------------------------------------------------------
 * PDF veritabanına kaydedilmeden önce içerisindeki tüm enstrümanların (Hisse, VİOP, Repo, Bono)
 * ve özet oranların analiz edilip admin'e güvenli önizleme olarak sunulmasını sağlar.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PdfPreviewResponse implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * İşlenen fon kodu (Örn: "THF", "TTE", "TI2").
     */
    private String fundCode;

    /**
     * Varsa fon unvanı.
     */
    private String fundTitle;

    /**
     * Rapordan okunan portföy dönem tarihi (Örn: 2026-08-31).
     */
    private LocalDate reportDate;

    /**
     * Fon Toplam Net Varlık Değeri (TNV TL).
     */
    private BigDecimal totalNetAssetValue;

    /**
     * Content Sniffing tarafından otomatik seçilen ayrıştırıcı strateji sınıfı.
     * Örnek: "SPK_LANDSCAPE_STRATEGY", "IS_PORTFOY_STRATEGY", "GENERIC_VERTICAL_STRATEGY".
     */
    private String detectedParserStrategy;

    /**
     * Portföyde ayrıştırılan toplam menkul kıymet satır sayısı.
     */
    private int totalItemsCount;

    /**
     * Tablodaki tüm satırların toplam portföy ağırlığı (Yüzde cinsinden, Örn: 99.85).
     */
    private BigDecimal totalPortfolioWeight;

    /**
     * Raporun yasal özet başlığında beyan edilen hisse senedi oranı (Yüzde cinsinden).
     */
    private BigDecimal stockRatioDeclared;

    /**
     * Raporun özet başlığında beyan edilen VİOP Takasbank nakit teminat oranı (Yüzde cinsinden).
     */
    private BigDecimal viopCashRatioDeclared;

    /**
     * Veri bütünlüğü ve tutarlılık kontrolünün (Sanity Check) sonucu.
     */
    private boolean sanityCheckPassed;

    /**
     * Admin için yönlendirici durum veya uyarı mesajı.
     */
    private String sanityCheckMessage;

    /**
     * Varlık sınıflarına göre detaylı portföy kırılımı (Asset Breakdown).
     */
    private AssetBreakdownDto assetBreakdown;

    /**
     * Ayrıştırılan menkul kıymetlerin detaylı listesi.
     */
    private List<HoldingPreviewItemDto> holdings;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class AssetBreakdownDto implements Serializable {
        private static final long serialVersionUID = 1L;

        private CategorySummary equity;
        private CategorySummary viop;
        private CategorySummary depositAndRepo;
        private CategorySummary bond;
        private CategorySummary other;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CategorySummary implements Serializable {
        private static final long serialVersionUID = 1L;

        private int count;
        private BigDecimal weightPercentage;
        private String description;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class HoldingPreviewItemDto implements Serializable {
        private static final long serialVersionUID = 1L;

        private String ticker;
        private String securityName;
        private String isinCode;
        private AssetClass assetClass;
        private BigDecimal weightPercentage;
        private BigDecimal nominalAmount;
        private BigDecimal reportPrice;
        private BigDecimal totalValue;
        private Boolean isShort;
    }
}
