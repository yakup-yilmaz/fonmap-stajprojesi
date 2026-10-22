package com.fonmap.api.scheduler;

import com.fonmap.application.service.drift.WeightDriftService;
import com.fonmap.application.service.drift.dto.DriftedHoldingDto;
import com.fonmap.domain.entity.Fund;
import com.fonmap.infrastructure.repository.FundRepository;
import com.fonmap.infrastructure.repository.MarketHolidayRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

/**
 * =========================================================================================
 * 🌅 SABAH SEANS ÖNCESİ AĞIRLIK KAYMASI ZAMANLAYICISI (MorningWeightDriftScheduler)
 * =========================================================================================
 *
 * 📌 NE İŞE YARAR? (JUNIOR MÜHENDİS REHBERİ):
 * Bu sınıf, sistemimizin "Açılış Hazırlık Şefi"dir.
 * Borsa İstanbul seansı sabah saat 10:00'da açılır. Bu zamanlayıcı seans açılmadan
 * tam 10 dakika önce (Hafta içi her sabah saat 09:50'de) otomatik devreye girer.
 *
 * NEDEN 09:50'DE ÇALIŞIR?
 * Dün seans kapandıktan sonra hisselerin resmi kapanış fiyatları (P_i(t-1)) kesinleşmiştir.
 * Örneğin THYAO dün %5 yükselmişse, portföy içindeki ağırlığı matematiksel olarak artmıştır.
 * Saat 09:50'de Formül 4.2 çalıştırılarak portföydeki tüm hisselerin yeni gün seans açılış
 * ağırlıkları (w_i(t)) önceden kalibre edilir.
 *
 * Böylece saat 10:00'da borsa zili çaldığında, getiri motorumuz eski ağırlıklarla değil,
 * dünkü kapanışa göre tamamen normalize edilmiş taptaze dinamik ağırlıklarla başlar!
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class MorningWeightDriftScheduler {

    private static final ZoneId ZONE_ISTANBUL = ZoneId.of("Europe/Istanbul");

    private final FundRepository fundRepository;
    private final WeightDriftService weightDriftService;
    private final MarketHolidayRepository marketHolidayRepository;
    private final com.fonmap.application.service.drift.WeightDriftExecutionTracker weightDriftExecutionTracker;

    /**
     * Sabah Açılış Öncesi Ağırlık Kalibrasyon Döngüsü:
     * -------------------------------------------------------------------------------------
     * Cron İfadesi: "0 50 9 * * MON-FRI"
     * Anlamı: Pazartesi'den Cuma'ya, sabah saat 09:50:00'de tam 1 kez çalışır.
     * Saat Dilimi: Europe/Istanbul
     */
    @Scheduled(cron = "0 50 9 * * MON-FRI", zone = "Europe/Istanbul")
    public void runMorningWeightDrift() {
        LocalDate today = LocalDate.now(ZONE_ISTANBUL);

        // 1. ADIM: Tatil Denetimi
        if (marketHolidayRepository.existsByHolidayDate(today)) {
            log.info("[WeightDrift] [STATUS=SKIPPED] [REASON=MARKET_HOLIDAY] [DATE={}] Bugün borsa tatilidir. Ağırlık kayması atlanıyor.", today);
            return;
        }

        long startTime = System.currentTimeMillis();
        log.info("[WeightDrift] [STATUS=STARTING] [EXECUTION=SCHEDULED_0950] [DATE={}] 🌅 Sabah 09:50 seans öncesi portföy ağırlık kalibrasyonu başlatıldı...", today);

        // 2. ADIM: Aktif fonları çek
        List<Fund> activeFunds = fundRepository.findAllActiveFundsOrdered();
        int processedFunds = 0;

        // 3. ADIM: Her fonun portföyündeki hisselerin güncel açılış ağırlıklarını hesapla
        for (Fund fund : activeFunds) {
            try {
                List<DriftedHoldingDto> driftedHoldings = weightDriftService.calibrateAndSaveDriftForLatestFundSnapshot(fund.getCode(), today);
                processedFunds++;

                log.info("[WeightDrift] [STATUS=SUCCESS] [EXECUTION=SCHEDULED_0950] Fon='{}' | {} adet varlığın T-1 seans açılış ağırlığı kalibre edildi ve veritabanına mühürlendi.",
                        fund.getCode(), driftedHoldings.size());

            } catch (Exception e) {
                log.warn("[WeightDrift] [STATUS=FAILED] [EXECUTION=SCHEDULED_0950] Fon='{}' ağırlık kalibrasyon hatası: {}",
                        fund.getCode(), e.getMessage());
            }
        }

        // 4. ADIM: Başarı durumunu mühürle (Böylece sistem gün içinde yeniden başlasa bile tekrar yapmaz)
        weightDriftExecutionTracker.markCalibrated(today);

        long duration = System.currentTimeMillis() - startTime;
        log.info("[WeightDrift] [STATUS=SUCCESS] [EXECUTION=SCHEDULED_0950] [DATE={}] 🎯 Sabah hazırlığı tamamlandı! {} fon saat 10:00 seans açılışına hazır (Süre: {} ms).",
                today, processedFunds, duration);
    }
}
