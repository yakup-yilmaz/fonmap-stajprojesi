package com.fonmap.domain.enums;

/**
 * ReportStatus — PDF Rapor İşleme Durumu Enum'u
 * ===============================================
 *
 * Kullanıldığı Tablo: fund_reports.status
 *
 * KAP'tan indirilen aylık portföy dağılım raporu PDF'inin hangi aşamada
 * olduğunu takip eden durum makinesidir (State Machine).
 *
 * Yaşam Döngüsü:
 *   DOWNLOADED → PARSED (başarılı ayrıştırma)
 *   DOWNLOADED → FAILED (hatalı ayrıştırma)
 *
 * NEDEN ÖNEMLİ?
 * Scraper servisi KAP'tan bir PDF indirdiğinde hemen parse etmez;
 * önce DOWNLOADED olarak kaydeder. Ardından PdfParser servisi bu
 * raporu işlemeye alır. Eğer tablo yapısı tanınırsa PARSED olur ve
 * fund_snapshots + holdings kayıtları oluşturulur. Tanınmazsa FAILED
 * olarak işaretlenir ve admin panelinde "Hatalı Raporlar" listesine düşer.
 *
 * Bu sayede:
 * 1. İndirme ve ayrıştırma işlemleri birbirinden bağımsız çalışabilir (async).
 * 2. Hatalı raporlar kaybolmaz, admin tarafından manuel incelenebilir.
 * 3. Aynı raporun tekrar tekrar indirilmesi engellenir (idempotency).
 */
public enum ReportStatus {

    /**
     * PDF dosyası KAP'tan başarıyla indirildi.
     * Henüz PDFBox ile içeriği ayrıştırılmadı (parse edilmedi).
     * Bu durumdaki raporlar, PdfParser servisinin işlem kuyruğunda bekler.
     */
    DOWNLOADED,

    /**
     * PDF başarıyla ayrıştırıldı.
     * fund_snapshots tablosuna 1 adet snapshot ve holdings tablosuna
     * portföydeki tüm varlık satırları kaydedildi.
     * Artık bu rapor Calculation Engine tarafından kullanılmaya hazırdır.
     */
    PARSED,

    /**
     * Ayrıştırma sırasında hata oluştu.
     * Olası sebepler:
     * - PDF'in tablo yapısı tanınan şablonlardan hiçbirine uymadı
     *   (yeni bir portföy yönetim şirketi farklı format kullanmış olabilir)
     * - Sayfa sayısı beklenenden farklı
     * - Türkçe sayı formatı düzgün çözümlenemedi
     * - PDF dosyası bozuk veya şifreli
     *
     * Admin panelinde "Hatalı Raporlar" listesine düşer ve manuel müdahale bekler.
     */
    FAILED
}
