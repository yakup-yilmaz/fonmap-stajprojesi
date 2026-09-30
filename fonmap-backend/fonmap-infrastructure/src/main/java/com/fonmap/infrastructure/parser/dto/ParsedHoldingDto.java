package com.fonmap.infrastructure.parser.dto;

import com.fonmap.domain.enums.AssetClass;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * PDF Portföy Dağılım Raporundan Okunan Ham Menkul Kıymet / Varlık DTO'su.
 * 
 * =========================================================================================
 * 🎯 NE İŞE YARAR? (JUNIOR MÜHENDİS REHBERİ):
 * =========================================================================================
 * Bu sınıf, Apache PDFBox ile KAP'tan indirilen aylık PDF raporunun tabloları
 * (Hisse Senedi, VİOP, Ters Repo, Devlet Tahvili, Özel Sektör Bonosu, Fon Katılma Payı vb.)
 * satır satır taranırken elde edilen HER BİR SATIRIN ham verisini taşır.
 * 
 * 💡 ENTITY DEĞİLDİR, DTO'DUR:
 * Veritabanında doğrudan tablosu yoktur. PDF ayrıştırıcı (Parser) tarafından bellekte üretilir,
 * ardından 3 Kademeli Eşleme Servisi (PdfImportService) tarafından doğrulanıp kanonik
 * enstrümanla (Instrument) eşleştirildikten sonra kalıcı "Holding" entity'sine dönüştürülür.
 * 
 * 🔍 KRİTİK FİNANSAL ALANLAR VE FAZ 3'TEKİ ROLLERİ:
 * 1. reportPrice: Rapor tarihindeki gün sonu kapanış fiyatıdır — P_i(t_0).
 *    Ağırlık Kayması (Weight Drift) formülünde pay ve payda hesabında hayati önem taşır.
 * 2. weightRatio: Rapor tarihindeki başlangıç portföy ağırlığıdır — w_i(t_0).
 * 3. isShort: Negatif lotlar veya VİOP kısa pozisyonları için true olur.
 *    Getiri motoru formülünde işaretin tersine çevrilmesini (-1 * r_i) tetikler.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ParsedHoldingDto implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * PDF tablosundaki "Menkul Kıymet" sütununda tespit edilen borsa işlem kodu.
     * Örnek: "THYAO", "ASELS", "F_THYAO0926", "TPKGY"
     * İncelediğimiz 7 fon raporunda (Tera, İş Portföy, Pardus, Atlas) bu sütun doğrudan BIST kodunu içerir.
     */
    private String ticker;

    /**
     * PDF'ten okunan ham şirket veya menkul kıymet unvanı.
     * Örnek: "TÜRK HAVA YOLLARI A.O.", "EREĞLİ DEMİR VE ÇELİK FABRİKALARI T.A.Ş."
     * Ticker bulunamazsa veya özel bir alias gerekiyorsa InstrumentAlias eşlemesinde bu alan kullanılır.
     */
    private String securityName;

    /**
     * 12 haneli Uluslararası Menkul Kıymet Tanımlama Numarası (ISIN).
     * Örnek: "TRATHYAO91M5", "TRFTRYBE2614"
     * 3 Kademeli Eşleme Motorunun 2. basamağında %100 kesin kimlik doğrulaması sağlar.
     */
    private String isinCode;

    /**
     * Menkul kıymetin portföydeki nominal lot veya kontrat adedi.
     * Örnek: 50.000,00 lot veya -1.344.966,00 lot (ödünç/short pozisyon).
     * Negatif adetler sistem tarafından otomatik olarak 'isShort = true' olarak işaretlenir.
     */
    private BigDecimal nominalAmount;

    /**
     * Fonun menkul kıymeti portföye katış birim maliyeti (Alış maliyeti TL).
     * Örnek: 275.40 TL
     */
    private BigDecimal unitCost;

    /**
     * Rapor tarihindeki gün sonu resmi borsa kapanış fiyatı — P_i(t_0).
     * Örnek: 285.50 TL
     * 
     * ⚠️ ÇOK ÖNEMLİ:
     * Ağırlık kayması (Weight Drift) servisinin güncel ağırlığı (w_i(t)) hesaplayabilmesi için
     * hissenin ilk rapor günündeki fiyatı (P_0) ile dünkü kapanış fiyatı (P_t-1) oranlanmak zorundadır:
     * P_i(t-1) / P_i(t_0). Bu yüzden bu alan null bırakılamaz!
     */
    private BigDecimal reportPrice;

    /**
     * Menkul kıymetin fon portföyündeki toplam piyasa değeri (TL).
     * Formül: nominalAmount * reportPrice (veya PDF'teki "Toplam Tutar / Rayiç Değer" sütunu).
     * Örnek: 14.275.000,00 TL
     */
    private BigDecimal totalValue;

    /**
     * Menkul kıymetin fon toplam portföy büyüklüğüne başlangıç ağırlık oranı — w_i(t_0).
     * PDF'teki yüzde değeri (Örn: %8,20) matematiksel orana dönüştürülerek saklanır: 0.082000.
     * Virgülden sonra 6 basamak hassasiyetle tutulur.
     */
    private BigDecimal weightRatio;

    /**
     * Pozisyonun yönü: Açığa satış (Short), ödünç satım veya VİOP Kısa pozisyon mu?
     * - false: Normal (Long) pozisyon. Hisse yükselirse fon kazanır.
     * - true: Kısa (Short) pozisyon. Hisse düşerse fon kazanır, yükselirse fon kaybeder.
     * Formüldeki etkisi: Katkı = w_i * (-1) * r_i.
     */
    @Builder.Default
    private Boolean isShort = false;

    /**
     * Satırın ait olduğu varlık sınıfı ipucu (Tablo başlığından otomatik çıkarılır).
     * Örnek: EQUITY (Hisse Senedi), VIOP (Vadeli İşlem Kontratı), DEPOSIT (Ters Repo/Mevduat), BOND (Bono/Tahvil).
     */
    private AssetClass assetClassHint;

    /**
     * Vade tarihi (VİOP kontratları, vadeli mevduat, ters repo ve tahvil/bono için).
     * Örnek: 2026-09-30
     */
    private LocalDate maturityDate;

    /**
     * Yıllık faiz / kupon oranı (Ters repo ve vadeli mevduat satırları için).
     * PDF'teki "%43,00" ifadesi "0.430000" olarak ayrıştırılır.
     * Günlük nema hesabı için kullanılır: gunluk_nema = annualRate / 365.
     */
    private BigDecimal interestRate;

    // =====================================================================================
    // 🛠️ YARDIMCI VE TÜRETİLMİŞ METOTLAR (CONVENIENCE METHODS)
    // =====================================================================================

    /**
     * Bu varlığın bir BIST hisse senedi olup olmadığını kontrol eder.
     */
    public boolean isEquity() {
        return AssetClass.EQUITY.equals(this.assetClassHint);
    }

    /**
     * Bu varlığın bir VİOP vadeli işlem kontratı olup olmadığını kontrol eder.
     */
    public boolean isViop() {
        return AssetClass.VIOP.equals(this.assetClassHint);
    }

    /**
     * Bu varlığın gecelik faiz getiren bir para piyasası aracı (Ters Repo / Mevduat) olup olmadığını kontrol eder.
     */
    public boolean isDepositOrRepo() {
        return AssetClass.DEPOSIT.equals(this.assetClassHint);
    }

    /**
     * Bu varlığın sabit getirili bir borçlanma aracı (Tahvil / Bono / Sukuk) olup olmadığını kontrol eder.
     */
    public boolean isBondOrSukuk() {
        return AssetClass.BOND.equals(this.assetClassHint);
    }

    /**
     * Mutlak ağırlık oranını döner (Kaldıraçlı veya negatif ağırlıklarda toplam büyüklük hesabı için).
     */
    public BigDecimal getAbsoluteWeight() {
        return weightRatio != null ? weightRatio.abs() : BigDecimal.ZERO;
    }
}
