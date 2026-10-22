package com.fonmap.api.config;

import com.fonmap.application.service.catchup.StartupCatchupService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * =========================================================================================
 * 🚀 SİSTEM AÇILIŞ TELAFİ VE SENKRONİZASYON KOŞUCUSU (StartupCatchupRunner)
 * =========================================================================================
 *
 * Spring Boot uygulaması ayağa kalktığında otomatik devreye girer.
 * Sunucu kapalıyken kaçırılmış olabilecek zamanlanmış görevleri kontrol eder:
 * 1. Aylık KAP portföy dağılım raporu eksiklerini tarar.
 * 2. Dünün / son işlem gününün TEFAS gece kapanış mutabakatını kontrol edip gerekirse telafi eder.
 * 3. Bugünün sabah 09:50 açılış ağırlık kayması kalibrasyonunu denetler.
 */
@Component
@Order(10)
@Slf4j
@RequiredArgsConstructor
public class StartupCatchupRunner implements ApplicationRunner {

    private final StartupCatchupService startupCatchupService;

    @Override
    public void run(ApplicationArguments args) {
        log.info("[StartupCatchupRunner] Spring Boot ayağa kalktı. Açılış telafi sekansı başlatılıyor...");
        try {
            startupCatchupService.runFullCatchupSequence();
        } catch (Exception e) {
            log.error("[StartupCatchupRunner] Açılış telafi sekansı yürütülürken hata oluştu: {}", e.getMessage(), e);
        }
    }
}
