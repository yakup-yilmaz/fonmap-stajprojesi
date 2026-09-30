package com.fonmap.infrastructure.parser.factory;

import com.fonmap.infrastructure.parser.strategy.PdfParserStrategy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.stream.Collectors;

/**
 * PDF Ayrıştırıcı Strateji Fabrikası (Factory Pattern).
 * 
 * =========================================================================================
 * 🎯 NE İŞE YARAR? (JUNIOR MÜHENDİS REHBERİ):
 * =========================================================================================
 * Spring Boot başladığında sistemde kayıtlı olan tüm 'PdfParserStrategy' sınıflarını
 * (TeraPdfParser, IsPortfoyPdfParser, AtlasPdfParser, PardusPdfParser) otomatik olarak
 * bir liste halinde bu fabrikanın yapıcı metoduna (Constructor) enjekte eder (Dependency Injection).
 * 
 * Bir PDF dosyası sisteme geldiğinde, çağıran servis (PdfImportService) hangi ayrıştırıcıyı
 * seçeceğini DÜŞÜNMEZ. Fabrikaya fon kodunu ve kurucu yönetici adını söyler:
 * 
 * "Ey fabrika! Elimde 'THF' fonu var, bunu hangi uzman ayrıştırmalı?"
 * 
 * Fabrika kayıtlı tüm stratejileri gezer, 'supports("THF", "Tera Portföy")' metoduna 'true'
 * diyen uzmanı bulur ve servise teslim eder.
 * 
 * 💡 OPEN / CLOSED PRENSİBİNE UYUM:
 * Yarın sisteme yeni bir portföy şirketi (Örn: "Ak Portföy" veya "Garanti Portföy") eklendiğinde
 * bu Fabrika sınıfına TEK BİR SATIR BİLE DOKUNMAYIZ! Sadece yeni bir 'AkPortfoyPdfParser'
 * yazıp '@Component' koymamız yeterlidir; Spring ve Fabrika onu otomatik olarak tanır!
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class PdfParserFactory {

    /**
     * Spring context'inde tanımlı tüm ayrıştırıcı stratejilerini tutan liste.
     */
    private final List<PdfParserStrategy> strategies;

    /**
     * Verilen fon kodu ve kurucu yönetici unvanına göre en uygun ayrıştırıcı stratejisini seçer.
     * 
     * @param fundCode Fon kodu (Örn: "THF", "TTE", "DFI", "KHA")
     * @param managerName Kurucu portföy yönetim şirketi adı (Örn: "Tera Portföy Yönetimi A.Ş.")
     * @return Bu fonu ayrıştırabilecek uzman strateji nesnesi
     * @throws IllegalArgumentException Desteklenen hiçbir ayrıştırıcı bulunamazsa
     */
    public PdfParserStrategy getParser(String fundCode, String managerName) {
        log.debug("[PdfParserFactory] Uygun ayrıştırıcı aranıyor: Fon='{}', Yönetici='{}'", fundCode, managerName);

        return strategies.stream()
                .filter(strategy -> strategy.supports(fundCode, managerName))
                .findFirst()
                .orElseThrow(() -> {
                    String errorMsg = String.format(
                            "Bu fon veya portföy şirketi için kayıtlı bir PDF ayrıştırıcı bulunamadı! Fon='%s', Yönetici='%s'",
                            fundCode, managerName
                    );
                    log.error("[PdfParserFactory] {}", errorMsg);
                    return new IllegalArgumentException(errorMsg);
                });
    }

    /**
     * Sistemde halihazırda aktif ve yüklü olan tüm strateji isimlerini döner (Admin Paneli ve Loglar için).
     */
    public List<String> getAvailableStrategies() {
        return strategies.stream()
                .map(PdfParserStrategy::getStrategyName)
                .collect(Collectors.toList());
    }

    /**
     * Yüklü strateji sayısını döner.
     */
    public int getStrategyCount() {
        return strategies != null ? strategies.size() : 0;
    }
}
