package com.fonmap.api.scheduler;

import com.fonmap.application.service.pdf.PdfImportService;
import com.fonmap.domain.entity.Fund;
import com.fonmap.domain.entity.FundReport;
import com.fonmap.domain.entity.FundSnapshot;
import com.fonmap.infrastructure.client.kap.KapClient;
import com.fonmap.infrastructure.client.kap.dto.KapPdfDto;
import com.fonmap.infrastructure.repository.FundReportRepository;
import com.fonmap.infrastructure.repository.FundRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * =========================================================================================
 * 🤖 AYLIK KAP PORTFÖY RAPORU TARAYICISI (KapCrawlerScheduler)
 * =========================================================================================
 *
 * 📌 NE İŞE YARAR? (JUNIOR MÜHENDİS REHBERİ):
 * Bu sınıf, sistemimizin "KAP Nöbetçisi ve İthalat Robotu"dur.
 * Sermaye Piyasası Kurulu (SPK) mevzuatına göre fon yönetim şirketleri her ayın ilk
 * 6 iş günü içinde bir önceki ayın son gününe ait "Aylık Portföy Dağılım Raporu"nu
 * Kamuyu Aydınlatma Platformu'nda (KAP) yayımlamak zorundadır.
 *
 * Bu zamanlayıcı:
 * 1. Her iş günü belirli aralıklarla (mesai saatlerinde) sessizce devreye girer.
 * 2. Takip ettiğimiz fonların en son ay raporunun veritabanımızda olup olmadığını kontrol eder.
 * 3. Eğer yeni bir ayın raporu çıkmışsa (ve bizde henüz yoksa); KapClient ile PDF'i indirir,
 *    PdfImportService ile ayrıştırır ve sisteme anında yeni bir Snapshot kazandırır.
 * 4. Böylece ay başında insanın tek tek KAP'a girip PDF aramasına gerek kalmaz!
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class KapCrawlerScheduler {

    private final FundRepository fundRepository;
    private final FundReportRepository fundReportRepository;
    private final KapClient kapClient;
    private final PdfImportService pdfImportService;

    /**
     * KAP Bildirim Tarama Döngüsü:
     * -------------------------------------------------------------------------------------
     * Cron İfadesi: "0 0 9,11,14,17,19 * * MON-FRI"
     * Anlamı: Pazartesi'den Cuma'ya; mesai saatleri içinde günde 5 kez (09:00, 11:00, 14:00, 17:00, 19:00).
     * Saat Dilimi: Europe/Istanbul
     */
    @Scheduled(cron = "0 0 9,11,14,17,19 * * MON-FRI", zone = "Europe/Istanbul")
    public void runMonthlyKapCrawler() {
        crawlAndImportMissingReports("SCHEDULED");
    }

    /**
     * Eksik KAP raporlarını tarar, keşfeder ve sisteme yeni snapshot olarak kazandırır.
     * Hem zamanlanmış cron (09:00, 11:00, 14:00, 17:00, 19:00) hem de Startup Catch-up tarafından çağrılır.
     *
     * @param executionType Çalışma tetikleyici tipi ("SCHEDULED" veya "CATCHUP")
     * @return Sisteme yeni kazandırılan rapor sayısı
     */
    public int crawlAndImportMissingReports(String executionType) {
        LocalDate today = LocalDate.now();

        // Raporlar çoğunlukla ayın 1'i ile 10'u arasında yayımlanır (Bir önceki ayın son günü hedef alınır)
        LocalDate lastMonthEnd = today.withDayOfMonth(1).minusDays(1);

        log.info("[KAP_DISCOVERY] [STATUS=CHECKING] [EXECUTION={}] [TARGET_PERIOD={}] 🤖 KAP portföy dağılım raporu taraması başlatıldı...",
                executionType, lastMonthEnd);

        List<Fund> activeFunds = fundRepository.findAllActiveFundsOrdered();
        int newReportsImported = 0;
        int alreadyExistingCount = 0;

        for (Fund fund : activeFunds) {
            try {
                // 1. ADIM: Bu fonun geçen aya ait raporu zaten veritabanında var mı?
                List<FundReport> existingReports = fundReportRepository.findByFundIdOrderByReportDateDesc(fund.getId());
                boolean alreadyHasTargetMonth = !existingReports.isEmpty() && existingReports.stream().anyMatch(r ->
                        r.getReportDate() != null &&
                        r.getReportDate().getYear() == lastMonthEnd.getYear() &&
                        r.getReportDate().getMonth() == lastMonthEnd.getMonth());

                if (alreadyHasTargetMonth) {
                    alreadyExistingCount++;
                    log.debug("[KAP_DISCOVERY] [STATUS=SKIPPED] [REASON=ALREADY_EXISTS] Fon='{}' {} dönemi raporu zaten mevcut.",
                            fund.getCode(), lastMonthEnd);
                    continue; // Zaten indirilmiş ve işlenmiş
                }

                // 2. ADIM: Yeni raporu temin et (Canlı KAP veya yerel fallback)
                log.info("[KAP_DISCOVERY] [STATUS=SEARCHING] [EXECUTION={}] Fon='{}' için {} tarihli yeni rapor taranıyor...",
                        executionType, fund.getCode(), lastMonthEnd);

                KapPdfDto pdfDto = kapClient.fetchPdf(fund.getCode(), fund.getManager(), null, fund.getTitle());

                if (pdfDto != null && pdfDto.getContent() != null && pdfDto.getContent().length > 0) {
                    // 3. ADIM: PDF'i ayrıştır ve veritabanına yeni snapshot olarak kaydet
                    FundSnapshot snapshot = pdfImportService.importPdfReport(
                            pdfDto.getContent(),
                            fund.getCode(),
                            pdfDto.getSource(),
                            pdfDto.getSha256Hash(),
                            LocalDateTime.now()
                    );

                    newReportsImported++;
                    log.info("[KAP_DISCOVERY] [STATUS=SUCCESS] [EXECUTION={}] 🚀 YENİ RAPOR İNDİRİLDİ VE İŞLENDİ: Fon='{}', Rapor Tarihi={}, TNV={}",
                            executionType, fund.getCode(), snapshot.getSnapshotDate(), snapshot.getTotalNetAssetValue());
                }

            } catch (Exception e) {
                log.debug("[KAP_DISCOVERY] [STATUS=PENDING] Fon '{}' için yeni rapor henüz yayımlanmamış veya indirilemedi: {}",
                        fund.getCode(), e.getMessage());
            }
        }

        log.info("[KAP_DISCOVERY] [STATUS=SUMMARY] [EXECUTION={}] 🏁 KAP taraması tamamlandı: Mevcut={}, Yeni İndirilen={}",
                executionType, alreadyExistingCount, newReportsImported);

        return newReportsImported;
    }
}
