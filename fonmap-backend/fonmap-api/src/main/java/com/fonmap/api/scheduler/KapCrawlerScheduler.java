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
@RequiredArgsConstructor
@Slf4j
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
        LocalDate today = LocalDate.now();

        // Raporlar çoğunlukla ayın 1'i ile 10'u arasında yayımlanır
        LocalDate lastMonthEnd = today.withDayOfMonth(1).minusDays(1);

        log.info("[KapCrawlerScheduler] 🤖 KAP portföy dağılım raporu taraması başlatıldı (Hedef Dönem: {})...", lastMonthEnd);

        List<Fund> activeFunds = fundRepository.findAllActiveFundsOrdered();
        int newReportsImported = 0;

        for (Fund fund : activeFunds) {
            try {
                // 1. ADIM: Bu fonun geçen aya ait raporu zaten veritabanında var mı?
                Optional<FundReport> existingReport = fundReportRepository.findByFundIdAndReportDateWithFund(
                        fund.getId(), lastMonthEnd);

                if (existingReport.isPresent()) {
                    continue; // Zaten indirilmiş ve işlenmiş, tekrar indirme!
                }

                // 2. ADIM: Yeni raporu temin et (Canlı KAP veya yerel fallback)
                log.info("[KapCrawlerScheduler] 🔍 Fon '{}' için {} tarihli yeni rapor aranıyor...",
                        fund.getCode(), lastMonthEnd);

                KapPdfDto pdfDto = kapClient.fetchPdf(fund.getCode(), null);

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
                    log.info("[KapCrawlerScheduler] 🚀 YENİ RAPOR İNDİRİLDİ VE İŞLENDİ: Fon='{}', Rapor Tarihi={}, TNV={}",
                            fund.getCode(), snapshot.getSnapshotDate(), snapshot.getTotalNetAssetValue());
                }

            } catch (Exception e) {
                log.debug("[KapCrawlerScheduler] Fon '{}' için yeni rapor henüz yayımlanmamış veya indirilemedi: {}",
                        fund.getCode(), e.getMessage());
            }
        }

        log.info("[KapCrawlerScheduler] 🏁 KAP taraması tamamlandı: Toplam {} adet yeni rapor sisteme kazandırıldı.",
                newReportsImported);
    }
}
