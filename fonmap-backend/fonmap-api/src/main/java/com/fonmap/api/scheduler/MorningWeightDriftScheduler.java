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
@RequiredArgsConstructor
@Slf4j
public class MorningWeightDriftScheduler {

    private final FundRepository fundRepository;
    private final WeightDriftService weightDriftService;
    private final MarketHolidayRepository marketHolidayRepository;

    /**
     * Sabah Açılış Öncesi Ağırlık Kalibrasyon Döngüsü:
     * -------------------------------------------------------------------------------------
     * Cron İfadesi: "0 50 9 * * MON-FRI"
     * Anlamı: Pazartesi'den Cuma'ya, sabah saat 09:50:00'de tam 1 kez çalışır.
     * Saat Dilimi: Europe/Istanbul
     */
    @Scheduled(cron = "0 50 9 * * MON-FRI", zone = "Europe/Istanbul")
    public void runMorningWeightDrift() {
        LocalDate today = LocalDate.now();

        // 1. ADIM: Tatil Denetimi
        if (marketHolidayRepository.existsByHolidayDate(today)) {
            log.info("[MorningWeightDriftScheduler] Bugün ({}) borsa tatilidir. Ağırlık kayması kalibrasyonu atlanıyor.", today);
            return;
        }

        long startTime = System.currentTimeMillis();
        log.info("[MorningWeightDriftScheduler] 🌅 Sabah 09:50 seans öncesi portföy ağırlık kalibrasyonu başlatıldı...");

        // 2. ADIM: Aktif fonları çek
        List<Fund> activeFunds = fundRepository.findAllActiveFundsOrdered();
        int processedFunds = 0;

        // 3. ADIM: Her fonun portföyündeki hisselerin güncel açılış ağırlıklarını hesapla
        for (Fund fund : activeFunds) {
            try {
                List<DriftedHoldingDto> driftedHoldings = weightDriftService.calculateDriftForLatestFundSnapshot(fund.getCode());
                processedFunds++;

                log.info("[MorningWeightDriftScheduler] ✅ Fon='{}' | {} adet varlığın T-1 seans açılış ağırlığı kalibre edildi.",
                        fund.getCode(), driftedHoldings.size());

            } catch (Exception e) {
                log.warn("[MorningWeightDriftScheduler] ⚠️ Fon '{}' için ağırlık kalibrasyonu yapılamadı: {}",
                        fund.getCode(), e.getMessage());
            }
        }

        long duration = System.currentTimeMillis() - startTime;
        log.info("[MorningWeightDriftScheduler] 🎯 Sabah hazırlığı tamamlandı! {} fon saat 10:00 seans açılışına hazır (Süre: {} ms).",
                processedFunds, duration);
    }
}
