package com.fonmap.infrastructure.parser.strategy;

import com.fonmap.infrastructure.parser.dto.ParsedPortfolioReportDto;

/**
 * PDF Ayrıştırıcı Strateji Arayüzü (Strategy Pattern Interface).
 * 
 * =========================================================================================
 * 🎯 NE İŞE YARAR? (JUNIOR MÜHENDİS REHBERİ):
 * =========================================================================================
 * Türkiye'deki portföy yönetim şirketlerinin KAP'a yüklediği PDF raporları TEK TİP DEĞİLDİR!
 * - Tera Portföy (THF, TLY, TMV, DOH): Yatay (Landscape) SPK formatında basar.
 * - İş Portföy (TTE): İngilizce sayı formatında ve farklı sütun sırasıyla basar.
 * - Atlas Portföy (DFI): Dikey (Portrait) 3 sayfalık tablolar halinde basar.
 * - Pardus Portföy (KHA): Sayfa düzeni ve satır aralıkları farklıdır.
 * 
 * Eğer bu 4 farklı yapıyı tek bir devasa "if-else" yığını içinde yazsaydık:
 * Kod 2000 satırlık okunamaz bir çöplüğe dönerdi ve yarın "Garanti Portföy" eklendiğinde
 * var olan çalışan kod bozulabilirdi (Open/Closed Prensibine aykırı!).
 * 
 * 💡 ÇÖZÜM: STRATEGY PATTERN (STRATEJİ TASARIM DESENİ)
 * Her portföy şirketi için bu sözleşmeyi (interface) uygulayan bağımsız birer uzman
 * sınıf yazarız (TeraPdfParser, IsPortfoyPdfParser vb.).
 * Sistem hangi fon gelirse gelsin sadece bu arayüzün 'parse' metodunu çağırır;
 * arkada hangi uzmanın çalıştığını bilmek zorunda kalmaz (Polymorphism / Çok Biçimlilik).
 */
public interface PdfParserStrategy {

    /**
     * Bu ayrıştırıcı stratejisinin gelen fonu veya portföy yöneticisini
     * tanıyıp ayrıştırıp ayrıştıramayacağını doğrular.
     * 
     * @param fundCode Fon kodu (Örn: "THF", "TTE", "DFI")
     * @param managerName Fon kurucu şirket unvanı (Örn: "Tera Portföy Yönetimi A.Ş.")
     * @return Bu strateji bu fonu destekliyorsa true, değilse false
     */
    boolean supports(String fundCode, String managerName);

    /**
     * Ham PDF dosyasının bayt dizisini (byte[]) alır, Apache PDFBox ile satır satır
     * okur ve sistemin anlayacağı standart 'ParsedPortfolioReportDto' nesnesine dönüştürür.
     * 
     * @param pdfContent KAP'tan indirilen veya yerel diskteki PDF dosyasının ham ikili (binary) verisi
     * @param fundCode İşlenen fon kodu
     * @return Ayrıştırılmış üst özet ve tüm hisse satırlarını içeren eksiksiz kargo paketi
     * @throws Exception PDF okuma veya formatlama sırasında beklenmeyen bir hata olursa
     */
    ParsedPortfolioReportDto parse(byte[] pdfContent, String fundCode) throws Exception;

    /**
     * Bu stratejinin loglarda ve denetim kayıtlarında görünecek tanıtıcı adı.
     * Örnek: "TERA_LANDSCAPE_STRATEGY", "IS_PORTFOY_STRATEGY"
     */
    String getStrategyName();
}
