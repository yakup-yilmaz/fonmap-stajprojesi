package com.fonmap.application.service.catchup;

import com.fonmap.application.service.drift.WeightDriftExecutionTracker;
import com.fonmap.application.service.drift.WeightDriftService;
import com.fonmap.application.service.drift.dto.DriftedHoldingDto;
import com.fonmap.application.service.reconciliation.ReconciliationService;
import com.fonmap.application.service.reconciliation.dto.ReconciliationDto;
import com.fonmap.domain.entity.Fund;
import com.fonmap.domain.entity.FundReport;
import com.fonmap.domain.entity.FundSnapshot;
import com.fonmap.domain.entity.ReconciliationResult;
import com.fonmap.infrastructure.client.kap.dto.KapPdfDto;
import com.fonmap.infrastructure.repository.FundRepository;
import com.fonmap.infrastructure.repository.MarketHolidayRepository;
import com.fonmap.infrastructure.repository.ReconciliationResultRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * =========================================================================================
 * 🔄 SİSTEM AÇILIŞ TELAFİ VE SENKRONİZASYON MOTORU (StartupCatchupService)
 * =========================================================================================
 *
 * 📌 NE İŞE YARAR? (JUNIOR MÜHENDİS REHBERİ):
 * Sunucu 7/24 çalışmadığında (örneğin yerel geliştirme ortamında bilgisayar gece veya sabah
 * kapalıyken), iki kritik zamanlanmış görev kaçabilir:
 * 1. Gece 23:00 TEFAS Resmi Kapanış Mutabakatı
 * 2. Sabah 09:50 Borsa Açılışı Öncesi Ağırlık Kayması Kalibrasyonu
 *
 * 🎯 AKILLI KONTROL PRENSİBİ (IDEMPOTENT STATE CHECK):
 * Sistem açıldığında KÖRÜ KÖRÜNE İŞ YAPMAZ! Önce duruma bakar:
 * - Eğer 23:00 mutabakatı veya 09:50 ağırlık kaydırması ZATEN normal saatinde yapılmışsa:
 *   [STATUS=SKIPPED] basarak atlar ve sistemi yormaz.
 * - Yalnızca ve yalnızca zamanında ÇALIŞMAMIŞSA (kaçırılmışsa):
 *   [STATUS=TRIGGERED] diyerek telafi motorunu devreye sokar, eksik mutabakatı mühürler,
 *   ağırlıkları kalibre eder ve Loki / Prometheus'a yapılandırılmış log üretir.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StartupCatchupService {

    private final FundRepository fundRepository;
    private final ReconciliationService reconciliationService;
    private final ReconciliationResultRepository reconciliationResultRepository;
    private final MarketHolidayRepository marketHolidayRepository;
    private final WeightDriftService weightDriftService;
    private final WeightDriftExecutionTracker weightDriftExecutionTracker;
    private final com.fonmap.infrastructure.repository.FundReportRepository fundReportRepository;
    private final com.fonmap.infrastructure.client.kap.KapClient kapClient;
    private final com.fonmap.application.service.pdf.PdfImportService pdfImportService;

    /**
     * Tüm açılış telafi adımlarını doğru finansal sırayla yürütür:
     * 1. Eksik KAP portföy dağılım raporlarını tara ve gerekirse indir.
     * 2. Dünün eksik TEFAS resmi mutabakatını telafi et.
     * 3. Bugünün sabah 09:50 açılış ağırlık kaymasını telafi et.
     */
    public void runFullCatchupSequence() {
        log.info("[StartupCatchup] ===========================================================");
        log.info("[StartupCatchup] 🔍 Sistem Açılış Durum Denetimi ve Telafi Motoru Başlatılıyor...");
        log.info("[StartupCatchup] ===========================================================");

        try {
            // 1. ADIM: Eksik KAP raporu taraması ve telafisi (09:00, 11:00, 14:00, 17:00, 19:00 kaçtıysa)
            catchupMissingKapReportsIfMissed();

            // 2. ADIM: Gece mutabakatı durumu kontrolü ve gerekirse telafisi (23:00 kaçtıysa)
            catchupMissingReconciliations();

            // 3. ADIM: Sabah 09:50 ağırlık kayması durumu kontrolü ve gerekirse telafisi
            catchupMorningWeightDriftIfMissed();

            log.info("[StartupCatchup] ===========================================================");
            log.info("[StartupCatchup] 🎯 Sistem Açılış Durum Denetimi ve Telafisi Tamamlandı!");
            log.info("[StartupCatchup] ===========================================================");

        } catch (Exception e) {
            log.error("[StartupCatchup] [STATUS=ERROR] Açılış telafi motorunda beklenmeyen hata: {}", e.getMessage(), e);
        }
    }

    private static final int MAX_CATCHUP_DAYS = 15;

    /**
     * 1. ÇOK GÜNLÜK TELAFİ: Sistem Kapalıyken Kaçan Tüm Borsa Günlerinin TEFAS Mutabakat Denetimi
     * -------------------------------------------------------------------------------------
     * Sistem 7 gün veya 14 gün kapalı kalsa bile açıldığı anda:
     * 1. Her fonun veritabanındaki en son doğrulanmış mutabakat tarihini (lastVerifiedDate) tespit eder.
     * 2. O tarihten son tamamlanmış seans tarihine (targetTradingDate) kadar olan aralığı kronolojik tarar.
     * 3. Hafta sonu ve resmi tatil günlerini otomatik atlar (isNonTradingDay).
     * 4. Aradaki her açık iş günü için ağırlıkları dinamik kaydırarak (Formül 4.2 / Backtest)
     *    TEFAS mutabakatını telafi eder ve mühürler.
     * 5. O gün zaten yapılmışsa hiçbir dış çağrı yapmadan milisaniyede SKIPPED basar.
     */
    public void catchupMissingReconciliations() {
        LocalDateTime now = LocalDateTime.now();
        LocalDate targetTradingDate = determineLastCompletedTradingDate(now);

        log.info("[Reconciliation] [STATUS=CHECKING] [TARGET_DATE={}] Çok günlük mutabakat açığı ve telafi denetimi başlatılıyor...",
                targetTradingDate);

        List<Fund> activeFunds = fundRepository.findAllActiveFundsOrdered();
        int totalReconciledCount = 0;
        int totalAlreadyVerifiedCount = 0;

        for (Fund fund : activeFunds) {
            // Fonun doğrulanmış en son mutabakat tarihini bul
            Optional<LocalDate> lastVerifiedOpt = reconciliationResultRepository.findLatestVerifiedDateByFundId(fund.getId());

            LocalDate startDate;
            if (lastVerifiedOpt.isPresent()) {
                startDate = lastVerifiedOpt.get().plusDays(1);
            } else {
                startDate = targetTradingDate.minusDays(MAX_CATCHUP_DAYS);
            }

            LocalDate minAllowedDate = targetTradingDate.minusDays(MAX_CATCHUP_DAYS);
            if (startDate.isBefore(minAllowedDate)) {
                startDate = minAllowedDate;
            }

            if (startDate.isAfter(targetTradingDate)) {
                log.info("[Reconciliation] [STATUS=SKIPPED] [REASON=ALREADY_UP_TO_DATE] [TARGET_DATE={}] Fon='{}' mutabakatları güncel.",
                        targetTradingDate, fund.getCode());
                totalAlreadyVerifiedCount++;
                continue;
            }

            log.info("[Reconciliation] [STATUS=GAP_DETECTED] Fon='{}' için [{} -> {}] tarih aralığında telafi döngüsü yürütülüyor...",
                    fund.getCode(), startDate, targetTradingDate);

            // Kronolojik döngü: Eskiden yeniye doğru boşlukları kapat
            for (LocalDate curDate = startDate; !curDate.isAfter(targetTradingDate); curDate = curDate.plusDays(1)) {
                if (isNonTradingDay(curDate)) {
                    continue; // Hafta sonu veya resmi tatil, seans yok
                }

                Optional<ReconciliationResult> existing = reconciliationResultRepository.findByFundIdAndDateWithFund(
                        fund.getId(), curDate);

                if (existing.isPresent() && Boolean.TRUE.equals(existing.get().getIsVerified())) {
                    continue; // O gün zaten doğrulanmış, pas geç
                }

                try {
                    log.info("[Reconciliation] [STATUS=TRIGGERED] [TYPE=MULTI_DAY_CATCHUP] Fon='{}' Tarih={} mutabakatı telafi ediliyor...",
                            fund.getCode(), curDate);

                    ReconciliationDto result = reconciliationService.reconcileFund(fund.getCode(), curDate);
                    if (result.isVerified()) {
                        totalReconciledCount++;
                        log.info("[Reconciliation] [STATUS=SUCCESS] [TYPE=CATCHUP] [DATE={}] Fon='{}' mutabakatı telafi edilip mühürlendi (Tahmin=%{}, Gerçek=%{}, Fark=%{}).",
                                curDate, fund.getCode(), result.getPredictedReturn(), result.getActualReturn(), result.getErrorDiff());
                    } else {
                        log.info("[Reconciliation] [STATUS=PENDING] [DATE={}] Fon='{}' için TEFAS henüz resmi fiyat açıklamamış.",
                                curDate, fund.getCode());
                    }
                } catch (Exception e) {
                    log.warn("[Reconciliation] [STATUS=FAILED] [DATE={}] Fon='{}' telafi hatası: {}",
                            curDate, fund.getCode(), e.getMessage());
                }
            }
        }

        log.info("[Reconciliation] [STATUS=SUMMARY] [TARGET_DATE={}] Çok günlük telafi tamamlandı: Güncel Olan Fonlar={}, Telafi Edilen Yeni Seanslar={}",
                targetTradingDate, totalAlreadyVerifiedCount, totalReconciledCount);
    }

    /**
     * 2. TELAFİ: Sabah 09:50 Ağırlık Kayması Kalibrasyon Denetimi
     * -------------------------------------------------------------------------------------
     * Bugünün borsa açılış ağırlıklarının Formül 4.2 ile T-1 kapanışlarına göre kalibre edilip
     * edilmediğini denetler. 09:50'de yapılmışsa SKIPPED basar, yapılmamışsa hemen telafi eder.
     */
    public void catchupMorningWeightDriftIfMissed() {
        LocalDate today = LocalDate.now();

        // Hafta sonu veya tatil kontrolü
        if (isNonTradingDay(today)) {
            log.info("[WeightDrift] [STATUS=SKIPPED] [REASON=NON_TRADING_DAY] [DATE={}] Bugün işlem günü değildir (Tatil/Hafta sonu).", today);
            return;
        }

        // 09:50'de çalıştı mı kontrolü
        if (weightDriftExecutionTracker.isCalibratedForDate(today)) {
            log.info("[WeightDrift] [STATUS=SKIPPED] [REASON=ALREADY_CALIBRATED] [DATE={}] Bugünün açılış ağırlık kayması 09:50'de zaten icra edilmiş. Yeniden hesaplamaya gerek yok.",
                    today);
            return;
        }

        // 09:50 kaçırılmış (bilgisayar kapalıydı)! Telafi motoru devrede:
        long startTime = System.currentTimeMillis();
        log.info("[WeightDrift] [STATUS=TRIGGERED] [REASON=MISSED_SCHEDULE] [DATE={}] 🌅 09:50 kalibrasyonu tespit edilemedi (Sistem kapalıydı). Startup Catch-up ağırlıkları şimdi kalibre ediyor...",
                today);

        List<Fund> activeFunds = fundRepository.findAllActiveFundsOrdered();
        int processedFunds = 0;

        for (Fund fund : activeFunds) {
            try {
                List<DriftedHoldingDto> driftedHoldings = weightDriftService.calibrateAndSaveDriftForLatestFundSnapshot(fund.getCode(), today);
                processedFunds++;

                log.info("[WeightDrift] [STATUS=SUCCESS] [TYPE=CATCHUP] Fon='{}' | {} adet varlığın T-1 seans açılış ağırlığı kalibre edildi ve veritabanına mühürlendi.",
                        fund.getCode(), driftedHoldings.size());

            } catch (Exception e) {
                log.warn("[WeightDrift] [STATUS=FAILED] [TYPE=CATCHUP] Fon='{}' ağırlık kalibrasyon hatası: {}",
                        fund.getCode(), e.getMessage());
            }
        }

        // Başarıyla mühürle
        weightDriftExecutionTracker.markCalibrated(today);

        long duration = System.currentTimeMillis() - startTime;
        log.info("[WeightDrift] [STATUS=SUCCESS] [TYPE=CATCHUP] [DATE={}] 🎯 Ağırlık kayması telafisi tamamlandı! {} fonun açılış ağırlıkları hazırlandı (Süre: {} ms).",
                today, processedFunds, duration);
    }

    /**
     * Son tamamlanmış ve resmi olarak kapanmış borsa işlem seansı tarihini belirler.
     * Saat 23:00 ve sonrası ise bugünün seansıdır.
     * Saat 23:00 öncesi ise dünün seansıdır (Hafta sonu/tatil geriye sarılır).
     */
    public LocalDate determineLastCompletedTradingDate(LocalDateTime now) {
        LocalDate date;
        if (now.getHour() >= 23) {
            date = now.toLocalDate();
        } else {
            date = now.toLocalDate().minusDays(1);
        }

        while (isNonTradingDay(date)) {
            date = date.minusDays(1);
        }
        return date;
    }

    private boolean isNonTradingDay(LocalDate date) {
        if (date.getDayOfWeek() == DayOfWeek.SATURDAY || date.getDayOfWeek() == DayOfWeek.SUNDAY) {
            return true;
        }
        return marketHolidayRepository.existsByHolidayDate(date);
    }

    /**
     * 3. TELAFİ: Eksik KAP Portföy Raporu Denetimi ve Keşfi
     * -------------------------------------------------------------------------------------
     * Sistem mesai saatlerindeki (09:00, 11:00, 14:00, 17:00, 19:00) taramaları kaçırdıysa,
     * açılışta son ay sonuna ait portföy raporu eksik olan fonları tarar ve varsa indirir.
     */
    public void catchupMissingKapReportsIfMissed() {
        LocalDate today = LocalDate.now();
        LocalDate lastMonthEnd = today.withDayOfMonth(1).minusDays(1);

        log.info("[KAP_DISCOVERY] [STATUS=CHECKING] [EXECUTION=CATCHUP] [TARGET_PERIOD={}] 🤖 Açılışta eksik KAP portföy raporları taranıyor...",
                lastMonthEnd);

        List<Fund> activeFunds = fundRepository.findAllActiveFundsOrdered();
        int missingCount = 0;
        int newlyImported = 0;

        for (Fund fund : activeFunds) {
            Optional<FundReport> existingReport = fundReportRepository.findByFundIdAndReportDateWithFund(
                    fund.getId(), lastMonthEnd);

            if (existingReport.isPresent()) {
                log.debug("[KAP_DISCOVERY] [STATUS=SKIPPED] [REASON=ALREADY_EXISTS] Fon='{}' {} dönemi raporu zaten mevcut.",
                        fund.getCode(), lastMonthEnd);
                continue;
            }

            missingCount++;
            log.info("[KAP_DISCOVERY] [STATUS=TRIGGERED] [REASON=MISSED_SCHEDULE] [EXECUTION=CATCHUP] Fon='{}' {} dönemi raporu eksik. KAP taranıyor...",
                    fund.getCode(), lastMonthEnd);

            try {
                KapPdfDto pdfDto = kapClient.fetchPdf(fund.getCode(), fund.getManager(), null, fund.getTitle());
                if (pdfDto != null && pdfDto.getContent() != null && pdfDto.getContent().length > 0) {
                    FundSnapshot snapshot = pdfImportService.importPdfReport(
                            pdfDto.getContent(),
                            fund.getCode(),
                            pdfDto.getSource(),
                            pdfDto.getSha256Hash(),
                            LocalDateTime.now()
                    );
                    newlyImported++;
                    log.info("[KAP_DISCOVERY] [STATUS=SUCCESS] [EXECUTION=CATCHUP] 🚀 Fon='{}' yeni raporu başarıyla indirilip sisteme eklendi (TNV={}).",
                            fund.getCode(), snapshot.getTotalNetAssetValue());
                }
            } catch (Exception e) {
                log.debug("[KAP_DISCOVERY] [STATUS=PENDING] Fon='{}' yeni rapor henüz yayımlanmamış veya indirilemedi: {}",
                        fund.getCode(), e.getMessage());
            }
        }

        log.info("[KAP_DISCOVERY] [STATUS=SUMMARY] [EXECUTION=CATCHUP] 🏁 KAP telafisi tamamlandı: Eksik={}, Yeni İndirilen={}",
                missingCount, newlyImported);
    }
}
