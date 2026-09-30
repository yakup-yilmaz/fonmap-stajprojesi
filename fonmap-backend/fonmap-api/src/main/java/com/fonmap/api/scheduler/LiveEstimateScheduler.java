package com.fonmap.api.scheduler;

import com.fonmap.application.service.calculation.ReturnCalculationService;
import com.fonmap.application.service.calculation.dto.FundEstimateDto;
import com.fonmap.domain.entity.Fund;
import com.fonmap.domain.entity.MarketHoliday;
import com.fonmap.infrastructure.repository.FundRepository;
import com.fonmap.infrastructure.repository.MarketHolidayRepository;
import com.fonmap.infrastructure.service.PriceService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

/**
 * =========================================================================================
 * ⏱️ SEANS İÇİ DAKİKALIK CANLI TAHMİN & DİNAMİK PİYASA DEDEKTÖRÜ (LiveEstimateScheduler)
 * =========================================================================================
 *
 * 📌 NE İŞE YARAR? (JUNIOR MÜHENDİS REHBERİ):
 * Bu sınıf, Fonmap platformunun "Otomatik Kalp Atışı"dır (Engine Heartbeat).
 * Borsa İstanbul seans saatleri boyunca (Hafta içi Pazartesi - Cuma, saat 10:00 - 18:10 arası)
 * her 60 saniyede bir otomatik olarak devreye girer.
 *
 * 🛡️ DİNAMİK DEVRE KESİCİ & CANARY PİYASA NABIZ YOKLAMASI (CIRCUIT BREAKER):
 * Bazen hükümet bayram tatillerini son anda idari izinle 9 güne çıkarır ve bu durum
 * veritabanımızdaki sabit takvimde henüz yazmıyor olabilir.
 * Bu zamanlayıcı:
 * 1. Saat 10:05'te (seans başladıktan 5 dk sonra) lokomotif hisselerin (THYAO, ISCTR vb.)
 *    işlem görüp görmediğini dinamik olarak test eder.
 * 2. Eğer saat 10:05 olduğu halde borsada hiçbir yaprak kımıldamıyorsa (%0.00 değişim / işlem yoksa),
 *    sistem durumu anında anlar: "Bugün beklenmedik bir borsa tatili var!"
 * 3. Dinamik devre kesiciyi kilitler (dynamicClosedDate = today).
 * 4. Bu durumu otomatik olarak veritabanındaki market_holidays tablosuna da kaydeder.
 * 5. Saat 18:10'a kadar kalan 484 dakikanın hiçbirinde dış API'lere TEK BİR İSTEK BİLE ATMAZ!
 *    Böylece API kotasını ve sunucuyu sıfır yorgunlukla korur.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class LiveEstimateScheduler {

    private final FundRepository fundRepository;
    private final ReturnCalculationService returnCalculationService;
    private final MarketHolidayRepository marketHolidayRepository;
    private final PriceService priceService;

    private static final LocalTime MARKET_OPEN = LocalTime.of(10, 0);
    private static final LocalTime MARKET_CLOSE = LocalTime.of(18, 10);
    private static final LocalTime CANARY_CHECK_TIME = LocalTime.of(10, 5);

    /**
     * O gün borsanın beklenmedik şekilde kapalı olduğu tespit edildiyse,
     * günün geri kalanındaki 484 dakikada gereksiz sorgu atılmasını engelleyen hafıza kilidi.
     */
    private LocalDate dynamicClosedDate = null;

    /**
     * Seans İçi Dakikalık Çalışma Döngüsü:
     * -------------------------------------------------------------------------------------
     * Cron İfadesi: "0 * 10-18 * * MON-FRI"
     * Çalışma Penceresi: 10:00 - 18:10 (Saat 18:10'dan sonra borsa kapandığı için pas geçer).
     * Saat Dilimi: Europe/Istanbul (BIST ile birebir senkronize).
     */
    @Scheduled(cron = "0 * 10-18 * * MON-FRI", zone = "Europe/Istanbul")
    public void runLiveEstimates() {
        LocalDate today = LocalDate.now();
        LocalTime now = LocalTime.now();

        // 1. ADIM: Seans Saatleri Kontrolü (Borsa tam 18:10'da kesin olarak kapanır)
        if (now.isBefore(MARKET_OPEN) || now.isAfter(MARKET_CLOSE)) {
            log.debug("[LiveEstimateScheduler] Borsa seansı kapalı (Şu an: {} | Seans: 10:00 - 18:10). Pas geçiliyor.", now);
            return;
        }

        // 2. ADIM: Dinamik Devre Kesici Kontrolü (Bugün daha önce kapalı tespit edildiyse tüm gün sessiz kal)
        if (today.equals(dynamicClosedDate)) {
            log.debug("[LiveEstimateScheduler] Borsa İstanbul bugün kapalı olarak tespit edildiğinden pas geçiliyor.");
            return;
        }

        // 3. ADIM: Veritabanı Resmi Tatil Takvimi Kontrolü
        if (marketHolidayRepository.existsByHolidayDate(today)) {
            log.info("[LiveEstimateScheduler] Bugün ({}) resmi borsa tatilidir. Seans tahmini çalıştırılmıyor.", today);
            return;
        }

        // 4. ADIM: Dinamik Piyasa Canlılık Nabız Yoklaması (Canary Liveness Check)
        // Saat 10:05 ve sonrasında, tatil tablosunda yazmasa bile Borsa'nın gerçekten açılıp açılmadığını test eder.
        if (now.isAfter(CANARY_CHECK_TIME) && dynamicClosedDate == null) {
            boolean isTrading = priceService.isMarketTradingToday();
            if (!isTrading) {
                dynamicClosedDate = today;
                log.warn("[LiveEstimateScheduler] 🛑 DİNAMİK DEVRE KESİCİ DEVREYE GİRDİ: Borsa İstanbul bugün seans açmadı (Uzatılmış / beklenmedik tatil tespit edildi)! Gün boyu (18:10'a kadar) kalan tüm dakikalık sorgular iptal edildi.");

                // Diğer gece servislerinin de bilmesi için MarketHoliday tablosuna dinamik tatil kaydı aç
                try {
                    if (!marketHolidayRepository.existsByHolidayDate(today)) {
                        marketHolidayRepository.save(MarketHoliday.builder()
                                .holidayDate(today)
                                .isHalfDay(false)
                                .build());
                        log.info("[LiveEstimateScheduler] 📝 Bugün ({}) veritabanına otomatik borsa tatili olarak kaydedildi.", today);
                    }
                } catch (Exception ignored) {
                }

                return;
            }
        }

        long startTime = System.currentTimeMillis();
        log.info("[LiveEstimateScheduler] 🔔 Dakikalık canlı tahmin döngüsü başlatıldı (Tarih: {})...", today);

        // 5. ADIM: Sistemde aktif olan tüm fonları getir
        List<Fund> activeFunds = fundRepository.findAllActiveFundsOrdered();
        int successCount = 0;
        int errorCount = 0;

        // 6. ADIM: Her fon için anlık getiri motorunu sırayla çalıştır
        for (Fund fund : activeFunds) {
            try {
                FundEstimateDto estimate = returnCalculationService.calculateAndSave(fund.getCode());
                successCount++;

                log.info("[LiveEstimateScheduler] 📈 Fon='{}' | Tahmin=%{} | Tahmini Fiyat={} TL | Kapsama=%{} | Güven={}",
                        fund.getCode(),
                        estimate.getEstimatedReturn(),
                        estimate.getEstimatedPrice(),
                        estimate.getCoverageRatio(),
                        estimate.getConfidenceLevel());

            } catch (Exception e) {
                errorCount++;
                log.warn("[LiveEstimateScheduler] ⚠️ Fon '{}' için dakikalık tahmin üretilemedi: {}",
                        fund.getCode(), e.getMessage());
            }
        }

        long duration = System.currentTimeMillis() - startTime;
        log.info("[LiveEstimateScheduler] 🏁 Döngü tamamlandı: Başarılı={}, Hatalı={}, Süre={} ms",
                successCount, errorCount, duration);
    }
}
