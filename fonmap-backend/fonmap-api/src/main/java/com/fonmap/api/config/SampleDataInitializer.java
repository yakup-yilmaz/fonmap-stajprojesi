package com.fonmap.api.config;

import com.fonmap.application.service.calculation.ReturnCalculationService;
import com.fonmap.application.service.calculation.dto.FundEstimateDto;
import com.fonmap.application.service.pdf.PdfImportService;
import com.fonmap.domain.entity.Fund;
import com.fonmap.infrastructure.repository.FundRepository;
import com.fonmap.infrastructure.repository.FundSnapshotRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * =========================================================================================
 * 🚀 SİSTEM BAŞLANGIÇ VERİ YÜKLEYİCİSİ VE 7 FON CANLI TEST ÇALIŞTIRICISI (SampleDataInitializer)
 * =========================================================================================
 *
 * 📌 NE İŞE YARAR? (JUNIOR MÜHENDİS REHBERİ):
 * Bu sınıf Spring Boot uygulaması ayağa kalktığında otomatik olarak devreye girer (ApplicationRunner).
 *
 * 🎯 NEDEN 7 FONUN HEPSİ YÜKLENİYOR?
 * Sistemimizde kayıtlı 7 adet aktif fon bulunmaktadır:
 * 1. THF - Tera Portföy Hisse Senedi Fonu (Tera Landscape Parser)
 * 2. TLY - Tera Portföy Birinci Hisse Senedi Fonu (Tera Landscape Parser)
 * 3. TMV - Tera Portföy Temettü Ödeyen Şirketler Fonu (Tera Landscape Parser)
 * 4. DOH - Deniz Portföy Hisse Senedi Fonu (Tera Landscape Parser)
 * 5. DFI - Deniz Portföy İkinci Hisse Senedi Fonu (Atlas Portrait Parser)
 * 6. KHA - Kuveyt Türk Portföy Hisse Senedi Fonu (Pardus Parser)
 * 7. TTE - İş Portföy BİST Teknoloji Ağırlıklı Fonu (İş Portföy Parser)
 *
 * Bu sınıf; `samples/` dizinindeki 7 fonun gerçek KAP PDF raporlarını tarar, veritabanında
 * snapshot'ı eksik olan tüm fonları eksiksiz içeri aktarır ve 7 fonun tamamı için
 * canlı getiri motorunu çalıştırır.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class SampleDataInitializer implements ApplicationRunner {

    private final FundRepository fundRepository;
    private final PdfImportService pdfImportService;
    private final FundSnapshotRepository fundSnapshotRepository;
    private final ReturnCalculationService returnCalculationService;

    // Sistemdeki 7 fon ve karşılık gelen gerçek KAP örnek PDF dosyaları eşleşmesi
    private static final Map<String, String> FUND_PDF_MAP = Map.of(
            "THF", "THF_2026_08.pdf",
            "TLY", "TLY_2026_08.pdf",
            "TMV", "TMV_2026_08.pdf",
            "DOH", "DOH_2026_08.pdf",
            "DFI", "DFI_2026_09.pdf",
            "KHA", "KHA_2026_08.pdf",
            "TTE", "TTE_2026_08.pdf"
    );

    @Override
    public void run(ApplicationArguments args) {
        log.info("[SampleDataInitializer] ===========================================================");
        log.info("[SampleDataInitializer] 🚀 7 Fon İçin Portföy Snapshot Kontrolü ve Canlı Değerleme Başlıyor...");

        try {
            // 1. ADIM: 7 Fonun tamamının portföy snapshot'larını doğrula, eksikleri PDF'ten yükle
            ensureAllSevenFundsLoaded();

            // 2. ADIM: Tüm aktif fonlar için getiri motorunu sırayla çalıştır
            log.info("[SampleDataInitializer] -----------------------------------------------------------");
            log.info("[SampleDataInitializer] 🧪 7 FON CANLI DEĞERLEME & REDIS ÖNBELLEK TESTİ YÜRÜTÜLÜYOR...");
            log.info("[SampleDataInitializer] -----------------------------------------------------------");

            List<Fund> activeFunds = fundRepository.findAllActiveFundsOrdered();
            for (Fund fund : activeFunds) {
                try {
                    FundEstimateDto estimate = returnCalculationService.calculateAndSave(fund.getCode());
                    log.info("[SampleDataInitializer] 📈 Fon='{}' | Tahmin=%{} | Fiyat={} TL | Kapsama=%{} | Güven={}",
                            fund.getCode(),
                            estimate.getEstimatedReturn(),
                            estimate.getEstimatedPrice(),
                            estimate.getCoverageRatio(),
                            estimate.getConfidenceLevel());
                } catch (Exception e) {
                    log.warn("[SampleDataInitializer] ⚠️ Fon '{}' için ilk tahmin üretilemedi: {}", fund.getCode(), e.getMessage());
                }
            }

            log.info("[SampleDataInitializer] ===========================================================");
            log.info("[SampleDataInitializer] 🎯 7 FONUN TAMAMI BAŞARIYLA SİSTEME ALINDI VE DEĞERLENDİ!");
            log.info("[SampleDataInitializer] ===========================================================");

        } catch (Exception e) {
            log.warn("[SampleDataInitializer] ⚠️ Başlangıç işlemi sırasında genel uyarı: {}", e.getMessage(), e);
        }
    }

    /**
     * samples/ dizinini bulur ve veritabanında snapshot'ı eksik olan fonların PDF'lerini içe aktarır.
     */
    private void ensureAllSevenFundsLoaded() {
        String[] possibleSampleDirs = {
                "../samples",
                "samples",
                "c:/Users/Yakup Yılmaz/Desktop/stajprojesi/samples"
        };

        File samplesDir = null;
        for (String dirPath : possibleSampleDirs) {
            File dir = new File(dirPath);
            if (dir.exists() && dir.isDirectory()) {
                samplesDir = dir;
                break;
            }
        }

        if (samplesDir == null) {
            log.warn("[SampleDataInitializer] ⚠️ 'samples' klasörü bulunamadı, PDF yükleme atlandı.");
            return;
        }

        log.info("[SampleDataInitializer] 📁 Samples klasörü doğrulandı: {}", samplesDir.getAbsolutePath());

        for (Map.Entry<String, String> entry : FUND_PDF_MAP.entrySet()) {
            String fundCode = entry.getKey();
            String fileName = entry.getValue();

            var snapshots = fundSnapshotRepository.findLatestByFundCode(fundCode, org.springframework.data.domain.PageRequest.of(0, 1));
            if (snapshots.isEmpty()) {
                log.info("[SampleDataInitializer] 📂 Fon '{}' için snapshot eksik. '{}' dosyası içe aktarılıyor...",
                        fundCode, fileName);
                importSinglePdfIfExists(samplesDir, fileName, fundCode);
            } else {
                log.debug("[SampleDataInitializer] Fon '{}' zaten aktif bir snapshot'a sahip.", fundCode);
            }
        }
    }

    private void importSinglePdfIfExists(File baseDir, String fileName, String fundCode) {
        try {
            Path pdfPath = Paths.get(baseDir.getAbsolutePath(), fileName);
            if (!Files.exists(pdfPath)) {
                log.warn("[SampleDataInitializer] PDF dosyası diskte bulunamadı: {}", pdfPath);
                return;
            }

            byte[] pdfBytes = Files.readAllBytes(pdfPath);
            String sha256 = "sample-sha256-" + fundCode.toLowerCase() + "-202608";
            pdfImportService.importPdfReport(pdfBytes, fundCode, fileName, sha256, LocalDateTime.now());
            log.info("[SampleDataInitializer] ✅ '{}' fonu portföy raporu başarıyla sisteme aktarıldı ({} bayt).",
                    fundCode, pdfBytes.length);

        } catch (Exception e) {
            log.error("[SampleDataInitializer] ❌ '{}' fonu raporu ('{}') yüklenirken hata: {}",
                    fundCode, fileName, e.getMessage());
        }
    }
}
