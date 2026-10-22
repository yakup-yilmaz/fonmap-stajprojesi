package com.fonmap.application.service.drift;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.concurrent.atomic.AtomicReference;

/**
 * =========================================================================================
 * ⏱️ SABAH AĞIRLIK KAYMASI ÇALIŞMA DURUM TAKİPÇİSİ (WeightDriftExecutionTracker)
 * =========================================================================================
 *
 * 📌 NE İŞE YARAR?
 * Sabah 09:50'de çalışan 'MorningWeightDriftScheduler'ın bugün başarıyla çalışıp çalışmadığını
 * takip eder.
 * 
 * Hem Redis üzerinde ('fonmap:drift:last_run_date') hem de bellek içi (In-Memory AtomicReference)
 * olarak çift katmanlı saklar. Böylece sistem açıldığında (Startup Catch-up):
 * - Eğer 09:50'de zaten çalışmışsa: "Bugün zaten yapıldı" diyerek gereksiz hesaplamayı atlar.
 * - Eğer sabah sistem kapalıydıysa: "Bugün henüz yapılmadı" diyerek açılış telafisini tetikler.
 */
@Component
@Slf4j
public class WeightDriftExecutionTracker {

    private static final String REDIS_KEY = "fonmap:drift:last_run_date";

    private final AtomicReference<LocalDate> memoryLastRunDate = new AtomicReference<>(null);
    private final RedisTemplate<String, Object> redisTemplate;

    public WeightDriftExecutionTracker(
            @Autowired(required = false) @Qualifier("genericRedisTemplate") RedisTemplate<String, Object> redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    /**
     * Belirtilen tarih için ağırlık kaydırmasının daha önce icra edilip edilmediğini kontrol eder.
     *
     * @param date Kontrol edilecek tarih (Genellikle LocalDate.now())
     * @return true ise bugün ağırlık kayması zaten yapılmıştır, false ise henüz yapılmamıştır.
     */
    public boolean isCalibratedForDate(LocalDate date) {
        if (date == null) {
            return false;
        }

        // 1. Önce hızlı bellek kontrolü
        LocalDate inMemory = memoryLastRunDate.get();
        if (date.equals(inMemory)) {
            return true;
        }

        // 2. Redis önbellek kontrolü (sunucu yeniden başlamışsa bile durumu hatırlar)
        if (redisTemplate != null) {
            try {
                Object redisVal = redisTemplate.opsForValue().get(REDIS_KEY);
                if (redisVal != null && date.toString().equals(redisVal.toString())) {
                    memoryLastRunDate.set(date);
                    return true;
                }
            } catch (Exception e) {
                log.warn("[WeightDriftExecutionTracker] Redis durum okuma hatası, bellek baz alınıyor: {}", e.getMessage());
            }
        }

        return false;
    }

    /**
     * Ağırlık kaydırmasının başarıyla tamamlandığını mühürler.
     *
     * @param date İşlemin tamamlandığı tarih (Genellikle LocalDate.now())
     */
    public void markCalibrated(LocalDate date) {
        if (date == null) {
            return;
        }

        // 1. Belleğe yaz
        memoryLastRunDate.set(date);

        // 2. Redis'e kalıcı yaz (24 saat geçerlilikle)
        if (redisTemplate != null) {
            try {
                redisTemplate.opsForValue().set(REDIS_KEY, date.toString(), java.time.Duration.ofHours(36));
                log.debug("[WeightDriftExecutionTracker] Tarih Redis'e mühürlendi: {}", date);
            } catch (Exception e) {
                log.warn("[WeightDriftExecutionTracker] Redis mühürleme hatası: {}", e.getMessage());
            }
        }
    }
}
