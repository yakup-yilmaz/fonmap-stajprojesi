package com.fonmap.domain.enums;

/**
 * AssetClass — Enstrüman Varlık Sınıfı Enum'u
 * ============================================
 *
 * Kullanıldığı Tablo: instruments.asset_class
 *
 * Bu enum, sistemdeki her enstrümanın (hisse, döviz, bono vb.) hangi finansal
 * varlık sınıfına ait olduğunu belirler.
 *
 * NEDEN ÖNEMLİ?
 * Çünkü her varlık sınıfının fiyat kaynağı ve getiri hesaplama yöntemi farklıdır:
 * - EQUITY (hisse) → Yahoo Finance'ten anlık canlı fiyat çekilir
 * - DEPOSIT (mevduat) → Borsa fiyatı yoktur, günlük faiz tahakkuku uygulanır
 * - BOND (bono) → Nötr varsayım (r=0) kabul edilir
 *
 * Calculation Engine, holdings tablosundaki her satırın instrument'ının asset_class'ına
 * bakarak hangi fiyatlama stratejisini uygulayacağına karar verir (Strategy Pattern).
 *
 * Veritabanında @Enumerated(EnumType.STRING) ile saklanır, yani veritabanına
 * sayısal index (0, 1, 2...) değil, doğrudan "EQUITY", "VIOP" gibi okunabilir
 * metin olarak yazılır. Bu sayede enum sırası değişse bile veri bozulmaz.
 */
public enum AssetClass {

    /**
     * BIST Hisse Senedi.
     * Örnek: THYAO, ASELS, AKBNK, GARAN, EREGL
     * Fiyat Kaynağı: Yahoo Finance API (THYAO.IS formatında anlık canlı fiyat)
     */
    EQUITY,

    /**
     * VİOP (Vadeli İşlem ve Opsiyon Piyasası) Kontratı.
     * Örnek: F_THYAO0926 (THYAO Eylül 2026 vadeli), F_XU0301026 (BIST30 Endeks vadeli)
     * Fiyat Kaynağı: Dayanak varlığın (underlying) borsa fiyatı üzerinden hesaplanır.
     * Kaldıraçlı pozisyondur; ağırlığı %100'ü aşabilir!
     */
    VIOP,

    /**
     * Döviz Kuru.
     * Örnek: USDTRY (Dolar/TL), EURTRY (Euro/TL)
     * Fiyat Kaynağı: TCMB XML servisi veya Yahoo Finance (USDTRY=X formatında)
     */
    FX,

    /**
     * Emtia (Commodity).
     * Örnek: Altın (XAU), Gümüş, Petrol
     * Fiyat Kaynağı: Yahoo Finance
     */
    COMMODITY,

    /**
     * Tahvil, Bono veya Sukuk (Kira Sertifikası).
     * Örnek: TRFTRYBE2614 (Tera Yatırım Bankası Bonosu), TRDTERVK2618 (Tera Varlık Kiralama)
     * Fiyat Kaynağı: YOK — Nötr varsayım (r=0) uygulanır.
     * Bu kalemler borsada hisseler gibi anlık alınıp satılmaz; vadeye kadar tutulur.
     */
    BOND,

    /**
     * Vadeli Mevduat veya Ters Repo.
     * Örnek: QNB Finansbank Vadeli Mevduat, TPKGY Borsa Dışı Ters Repo, Takasbank Teminat Nemalandırma
     * Fiyat Kaynağı: YOK — Günlük faiz tahakkuku formülüyle hesaplanır: r = faiz_oranı / 365
     * PDF raporunda faiz oranı (%43, %38 gibi) açıkça yazmaktadır.
     */
    DEPOSIT,

    /**
     * Yatırım Fonu Katılma Payı (Fon İçinde Fon).
     * Örnek: HMV, MTL, T3B fonları (DOH fonunun içinde THF, TLY, TMV var)
     * Fiyat Kaynağı: YOK (seans içi canlı fiyat yayınlanmaz)
     * TEFAS'ın dün gece açıkladığı resmi T-1 kapanış fiyatı sabit kabul edilir (r=0).
     */
    FUND
}
