package com.fonmap.domain.enums;

/**
 * ConfidenceLevel — Tahmin Güven Seviyesi Enum'u
 * ================================================
 *
 * Kullanıldığı Tablo: estimate_runs.confidence_level
 *
 * Seans saatleri içinde üretilen her dakikalık tahminin ne kadar güvenilir
 * olduğunu belirler. Kapsama Oranına (Coverage Ratio) göre otomatik atanır.
 *
 * Kapsama Oranı Nedir?
 * Portföydeki toplam varlıkların ne kadarının canlı fiyatla takip edilebildiğini
 * gösteren yüzdedir:
 *   Kapsama = Σ(fiyatı_bulunan_varlıkların_ağırlığı) / Σ(tüm_varlıkların_ağırlığı)
 *
 * Örnek: THF fonunun portföyünün %80'i BIST hissesi (canlı fiyat var),
 *        %15'i ters repo (faizle hesaplanıyor), %5'i bono (nötr r=0).
 *        Kapsama = %80 + %15 + %5 = %100 → HIGH
 *
 * Kullanıcı Arayüzünde Nasıl Gösterilir?
 * - HIGH → Yeşil/Kırmızı normal getiri kartı (güvenle gösterilir)
 * - MEDIUM → Kart gösterilir ama sarı uyarı ikonu eklenir
 * - LOW → Kart sönük gösterilir + "Tahmin güvenilirliği düşüktür" uyarısı basılır
 */
public enum ConfidenceLevel {

    /**
     * Yüksek Güven — Kapsama oranı %85 ve üzeri.
     * Portföyün büyük çoğunluğu güvenilir şekilde fiyatlanabiliyor.
     * Kullanıcıya normal yeşil/kırmızı tahmin kartı gösterilir.
     */
    HIGH,

    /**
     * Orta Güven — Kapsama oranı %70 ile %85 arasında.
     * Portföyün önemli bir kısmı fiyatlanabiliyor ama bazı kalemler
     * eksik veya nötr varsayımla hesaplanmış durumda.
     * Kullanıcıya kart gösterilir ancak yanında sarı uyarı ikonu çıkar.
     */
    MEDIUM,

    /**
     * Düşük Güven — Kapsama oranı %70'in altında.
     * Portföyün büyük kısmı fiyatlanamamış (API hatası, tanınmayan varlıklar vb.)
     * Kullanıcıya sönük kart + "Bu fonun portföyünün önemli kısmı
     * fiyatlanamadığından tahmin güvenilirliği düşüktür" uyarısı gösterilir.
     */
    LOW
}
