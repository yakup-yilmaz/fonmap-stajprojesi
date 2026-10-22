package com.fonmap.api.scheduler;

import com.fonmap.application.service.calculation.ReturnCalculationService;
import com.fonmap.application.service.calculation.dto.FundEstimateDto;
import com.fonmap.domain.entity.Fund;
import com.fonmap.domain.entity.MarketHoliday;
import com.fonmap.infrastructure.repository.FundRepository;
import com.fonmap.infrastructure.repository.MarketHolidayRepository;
import com.fonmap.infrastructure.service.PriceService;
import com.fonmap.api.controller.EstimateController;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
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
@Slf4j
public class LiveEstimateScheduler {

    private final FundRepository fundRepository;
    private final ReturnCalculationService returnCalculationService;
    private final MarketHolidayRepository marketHolidayRepository;
    private final PriceService priceService;
    private final EstimateController estimateController;

    public LiveEstimateScheduler(
            FundRepository fundRepository,
            ReturnCalculationService returnCalculationService,
            MarketHolidayRepository marketHolidayRepository,
            PriceService priceService,
            @Lazy EstimateController estimateController) {
        this.fundRepository = fundRepository;
        this.returnCalculationService = returnCalculationService;
        this.marketHolidayRepository = marketHolidayRepository;
        this.priceService = priceService;
        this.estimateController = estimateController;
    }

    private static final LocalTime MARKET_OPEN = LocalTime.of(10, 0);
    private static final LocalTime MARKET_CLOSE = LocalTime.of(18, 10);
    private static final LocalTime CANARY_CHECK_TIME = LocalTime.of(10, 5);
    private static final ZoneId ZONE_ISTANBUL = ZoneId.of("Europe/Istanbul");

    /**
     * Seans İçi Dakikalık Çalışma Döngüsü:
     * -------------------------------------------------------------------------------------
     * Cron İfadesi: "0 * 10-18 * * MON-FRI"
     * Çalışma Penceresi: 10:00 - 18:10 (Saat 18:10'dan sonra borsa kapandığı için pas geçer).
     * Saat Dilimi: Europe/Istanbul (BIST ile birebir senkronize).
     */
    @Scheduled(cron = "0 * 10-18 * * MON-FRI", zone = "Europe/Istanbul")
    public void runLiveEstimates() {
        LocalDate today = LocalDate.now(ZONE_ISTANBUL);
        LocalTime now = LocalTime.now(ZONE_ISTANBUL);

        // 1. ADIM: Seans Saatleri Kontrolü (Borsa tam 18:10'da kesin olarak kapanır)
        if (now.isBefore(MARKET_OPEN) || now.isAfter(MARKET_CLOSE)) {
            log.debug("[LiveEstimateScheduler] Borsa seansı kapalı (Şu an: {} | Seans: 10:00 - 18:10). Pas geçiliyor.", now);
            return;
        }

        // 2. ADIM: Veritabanı Resmi Tatil Takvimi Kontrolü
        if (marketHolidayRepository.existsByHolidayDate(today)) {
            log.info("[LiveEstimateScheduler] Bugün ({}) resmi borsa tatilidir. Seans tahmini çalıştırılmıyor.", today);
            return;
        }

        // 4. ADIM: Dinamik Piyasa Canlılık Nabız Yoklaması (Canary Liveness Check)
        // Saat 10:05 ve sonrasında borsa seansının gerçekten açılıp açılmadığını kontrol eder.
        // Seans henüz oturmamışsa veya geçici bir gecikme varsa tüm günü iptal etmek yerine
        // sadece bu dakikayı pas geçer; sonraki dakikalarda seans başladığında döngü kendiliğinden devam eder.
        if (now.isAfter(CANARY_CHECK_TIME)) {
            boolean isTrading = priceService.isMarketTradingToday();
            if (!isTrading) {
                log.warn("[LiveEstimateScheduler] 🩺 Lokomotif hisselerde henüz fiyat hareketi tespit edilemedi. Bu dakikalık döngü pas geçiliyor, bir sonraki dakikada tekrar denenecek.");
                return;
            }
        }

        long startTime = System.currentTimeMillis();
        log.info("[PRICE_POLL] [EVENT=MINUTE_CYCLE] [STATUS=STARTING] [TIME={}] 🔔 Dakikalık canlı fiyat & portföy getiri döngüsü başlatıldı...", now);

        // 5. ADIM: Sistemde aktif olan tüm fonları getir
        List<Fund> activeFunds = fundRepository.findAllActiveFundsOrdered();
        int successCount = 0;
        int errorCount = 0;

        // 6. ADIM: Her fon için anlık getiri motorunu sırayla çalıştır
        for (Fund fund : activeFunds) {
            try {
                FundEstimateDto estimate = returnCalculationService.calculateAndSave(fund.getCode());
                successCount++;

                log.info("[PRICE_POLL] [STATUS=SUCCESS] Fon='{}' | Tahmin=%{} | Fiyat={} TL | Kapsama=%{} | Güven={}",
                        fund.getCode(),
                        estimate.getEstimatedReturn(),
                        estimate.getEstimatedPrice(),
                        estimate.getCoverageRatio(),
                        estimate.getConfidenceLevel());

            } catch (IllegalStateException ise) {
                // Henüz snapshot yüklenmemiş fon (örn: yeni eklenmiş fon veya henüz raporu bulunmayan fon)
                log.debug("[PRICE_POLL] [STATUS=SKIPPED] Fon='{}' henüz aktif bir portföy snapshot'ına sahip değil, döngü atlandı: {}",
                        fund.getCode(), ise.getMessage());
            } catch (Exception e) {
                errorCount++;
                log.warn("[PRICE_POLL] [STATUS=FAILED] Fon='{}' için dakikalık tahmin üretilemedi: {}",
                        fund.getCode(), e.getMessage());
            }
        }

        long duration = System.currentTimeMillis() - startTime;
        log.info("[PRICE_POLL] [EVENT=MINUTE_CYCLE] [STATUS=COMPLETED] [TIME={}] 🏁 1 dakikalık seans turu tamamlandı: Başarılı={}/{}, Hatalı={}, Süre={} ms",
                now, successCount, activeFunds.size(), errorCount, duration);

        // API Controller'daki anasayfa önbelleğini anında tazele, kullanıcılar en güncel dakikayı anında görsün
        try {
            if (estimateController != null) {
                estimateController.invalidateCache();
            }
        } catch (Exception e) {
            log.warn("[LiveEstimateScheduler] Controller önbellek temizleme uyarısı: {}", e.getMessage());
        }
    }
}
