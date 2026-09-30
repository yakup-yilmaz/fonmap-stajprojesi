package com.fonmap.api.scheduler;

import com.fonmap.application.service.reconciliation.ReconciliationService;
import com.fonmap.application.service.reconciliation.dto.ReconciliationDto;
import com.fonmap.domain.entity.Fund;
import com.fonmap.domain.entity.ReconciliationResult;
import com.fonmap.infrastructure.repository.FundRepository;
import com.fonmap.infrastructure.repository.MarketHolidayRepository;
import com.fonmap.infrastructure.repository.ReconciliationResultRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * =========================================================================================
 * 🌙 GECE TEFAS RESMİ MUTABAKAT VE TEKRAR DENEME (RETRY POLLING) ZAMANLAYICISI
 * =========================================================================================
 *
 * 📌 NE İŞE YARAR? (JUNIOR MÜHENDİS REHBERİ):
 * Bu sınıf, Fonmap platformunun "Resmi Hakemi ve Gece Nöbetçisi"dir.
 *
 * 🕒 BORSA & TEFAS ZAMANLAMA ÇELİŞKİSİ:
 * - BIST saat 18:10'da kapanır ve son seans tahmini mühürlenir.
 * - Portföy yönetim şirketleri o günün kesinleşmiş değerini (NAV) TEFAS'a bildirir.
 * - TEFAS resmi fiyatları genellikle 23:00 civarında açıklamaya başlar.
 *
 * ⚠️ KRİTİK PROBLEM (TEFAS GECİKMELERİ VE GECE YARISI GEÇİŞİ):
 * 1. TEFAS'ta tüm fonlar saat 23:00:00'da aynı anda AÇIKLANMAZ! Kimi fon 23:10'da,
 *    kimi 23:45'te, kimi ise gece 00:30 veya 01:15'te açıklar.
 * 2. Saat 23:59'dan 00:00'a geçildiğinde takvim günü değişir (Salı -> Çarşamba).
 *    Eğer kod safça `LocalDate.now()` derse, yeni günün seansını aramaya kalkar ve çöker!
 *    Oysa gece 01:00'de mutabakatı yapılan seans, DÜN saat 18:10'da kapanan seanstr!
 *
 * 🛡️ ÇÖZÜM MİMARİSİ (AKILLI DELTA RETRY POLLING):
 * 1. Akıllı Seans Tarihi Tespiti: Saat 20:00 - 23:59 arasındaysak bugünü,
 *    gece yarısından sonra (00:00 - 09:59) isek bir önceki borsa işlem gününü hedefler.
 * 2. 15 Dakikalık Periyodik Yoklama: 23:00'ten gece 03:00'e kadar her 15 dakikada bir çalışır.
 * 3. Idempotent (Tekrarlanabilir) Delta Filtreleme: Zaten açıklanmış (isVerified = true) fonları
 *    atlayarak TEFAS'a boşuna istek atmaz. Sadece henüz açıklanmamış fonları sorgular.
 * 4. Açıklandığı Anda Mühürleme: TEFAS fiyatı yayımladığı anda getiri farkı hesaplanır ve
 *    veritabanına onaylı olarak kaydedilir.
 * 5. Sabah 08:30 Emniyet Ağı: Gece TEFAS sunucu bakımı vb. nedenlerle geciken son fonlar varsa,
 *    sabah BIST 10:00'da açılmadan önce son bir kez kontrol edilip mutabakat tamamlanır.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class NightReconciliationScheduler {

    private final FundRepository fundRepository;
    private final ReconciliationService reconciliationService;
    private final ReconciliationResultRepository reconciliationResultRepository;
    private final MarketHolidayRepository marketHolidayRepository;

    /**
     * 1. ANA DÖNGÜ: Gece 23:00 ile 02:45 Arası Her 15 Dakikada Bir Tekrar Deneme
     * -------------------------------------------------------------------------------------
     * Cron İfadesi: "0 0/15 23,0,1,2 * * *"
     * Çalışma Saatleri: 23:00, 23:15, 23:30, 23:45, 00:00, 00:15... 02:45 (Toplam 16 deneme)
     * Saat Dilimi: Europe/Istanbul
     */
    @Scheduled(cron = "0 0/15 23,0,1,2 * * *", zone = "Europe/Istanbul")
    public void runNightReconciliationLoop() {
        LocalDateTime now = LocalDateTime.now();
        LocalDate targetTradingDate = determineTargetTradingDate(now);

        // Hedef tarih hafta sonuna denk geliyorsa veya resmi tatilse mutabakat yapılmaz
        if (isNonTradingDay(targetTradingDate)) {
            log.debug("[NightReconciliationScheduler] Hedef tarih ({}) işlem günü değildir. Mutabakat pas geçiliyor.",
                    targetTradingDate);
            return;
        }

        executeReconciliationForDate(targetTradingDate, "GECE PERİYODİK DÖNGÜ");
    }

    /**
     * 2. EMNİYET AĞI (SAFETY NET): Sabah Açılış Öncesi Son Kontrol (08:30)
     * -------------------------------------------------------------------------------------
     * BIST saat 10:00'da yeni seansa başlamadan önce, dünün (veya Cuma'nın) henüz TEFAS'ta
     * eksik kalmış fonu var mı diye sabah 08:30'da son bir tarama yapar.
     * Cron İfadesi: "0 30 8 * * MON-FRI"
     */
    @Scheduled(cron = "0 30 8 * * MON-FRI", zone = "Europe/Istanbul")
    public void runMorningFinalCheck() {
        LocalDateTime now = LocalDateTime.now();
        LocalDate targetTradingDate = determineTargetTradingDate(now);

        if (isNonTradingDay(targetTradingDate)) {
            return;
        }

        log.info("[NightReconciliationScheduler] 🌅 Sabah seans öncesi son mutabakat denetimi başlatılıyor (Hedef Seans: {})...",
                targetTradingDate);
        executeReconciliationForDate(targetTradingDate, "SABAH SEANS ÖNCESİ KONTROL");
    }

    /**
     * Belirli bir seans tarihi için TEFAS mutabakatını delta usulüyle (eksikleri tamamlayarak) yürütür.
     *
     * @param targetTradingDate İncelenen borsa seans günü
     * @param executionContext Loglama amaçlı tetikleyici adı
     */
    public void executeReconciliationForDate(LocalDate targetTradingDate, String executionContext) {
        log.info("[NightReconciliationScheduler] [{}] 🌙 TEFAS mutabakat kontrolü başladı (Hedef Seans: {})...",
                executionContext, targetTradingDate);

        List<Fund> activeFunds = fundRepository.findAllActiveFundsOrdered();
        int alreadyVerifiedCount = 0;
        int newlyVerifiedCount = 0;
        int stillPendingCount = 0;

        for (Fund fund : activeFunds) {
            // 1. ADIM: Bu fon bu hedef tarih için daha önce doğrulanmış mı?
            Optional<ReconciliationResult> existing = reconciliationResultRepository.findByFundIdAndDateWithFund(
                    fund.getId(), targetTradingDate);

            if (existing.isPresent() && Boolean.TRUE.equals(existing.get().getIsVerified())) {
                alreadyVerifiedCount++;
                continue; // Zaten doğrulanmış! TEFAS'a boşuna istek atıp sistemi yorma.
            }

            // 2. ADIM: Henüz doğrulanmamış fon için TEFAS'ı sorgula
            try {
                ReconciliationDto result = reconciliationService.reconcileFund(fund.getCode(), targetTradingDate);

                if (result.isVerified()) {
                    newlyVerifiedCount++;
                    log.info("[NightReconciliationScheduler] ✅ TEFAS AÇIKLANDI: Fon='{}' | Tahmin=%{} | Gerçek=%{} | Hata Farkı=%{}",
                            fund.getCode(),
                            result.getPredictedReturn(),
                            result.getActualReturn(),
                            result.getErrorDiff());
                } else {
                    stillPendingCount++;
                    log.info("[NightReconciliationScheduler] ⏳ TEFAS BEKLENİYOR: Fon='{}' henüz fiyat açıklamadı. Bir sonraki periyotta (15 dk sonra) tekrar denenecek.",
                            fund.getCode());
                }

            } catch (Exception e) {
                stillPendingCount++;
                log.warn("[NightReconciliationScheduler] ⚠️ Fon '{}' mutabakatında geçici hata: {}",
                        fund.getCode(), e.getMessage());
            }
        }

        // 3. ADIM: Sonuç Özeti
        if (stillPendingCount == 0) {
            log.info("[NightReconciliationScheduler] 🎯 MÜKEMMEL! Hedef seans ({}) için tüm fonlar (Toplam: {}) TEFAS ile mutabakat edildi ve mühürlendi.",
                    targetTradingDate, alreadyVerifiedCount + newlyVerifiedCount);
        } else {
            log.info("[NightReconciliationScheduler] 📊 Ara Durum ({}): Doğrulanmış={}, Yeni Açıklanan={}, Hâlâ TEFAS Bekleyen={}",
                    targetTradingDate, alreadyVerifiedCount, newlyVerifiedCount, stillPendingCount);
        }
    }

    /**
     * Borsa çalışma takvimine göre mutabakatı yapılacak hedef seans tarihini belirler.
     *
     * KURALLAR:
     * - Saat 20:00 ile 23:59 arasındaysak: Hedef seans bugünün seansıdır (LocalDate.now()).
     * - Gece yarısını geçtiysek (00:00 ile 09:59 arası): Hedef seans dünün seansıdır.
     *   Eğer dün hafta sonu ise (örneğin Salı gecesi değil de Pazartesi sabahı ise),
     *   takvimi geriye sararak son açık borsa gününe (Cuma'ya) gider.
     */
    public LocalDate determineTargetTradingDate(LocalDateTime now) {
        if (now.getHour() >= 20) {
            return now.toLocalDate();
        } else {
            // 00:00 - 09:59 arası: Gece yarısı geçildi, dünün seansını hedefle
            LocalDate target = now.toLocalDate().minusDays(1);
            while (target.getDayOfWeek() == DayOfWeek.SATURDAY || target.getDayOfWeek() == DayOfWeek.SUNDAY) {
                target = target.minusDays(1);
            }
            return target;
        }
    }

    /**
     * Verilen tarihin resmi tatil veya hafta sonu olup olmadığını kontrol eder.
     */
    private boolean isNonTradingDay(LocalDate date) {
        if (date.getDayOfWeek() == DayOfWeek.SATURDAY || date.getDayOfWeek() == DayOfWeek.SUNDAY) {
            return true;
        }
        return marketHolidayRepository.existsByHolidayDate(date);
    }
}
