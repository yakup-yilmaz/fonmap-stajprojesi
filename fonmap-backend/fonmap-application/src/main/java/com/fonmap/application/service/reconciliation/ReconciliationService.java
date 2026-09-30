package com.fonmap.application.service.reconciliation;

import com.fonmap.application.service.reconciliation.dto.ReconciliationDto;
import com.fonmap.domain.entity.EstimateRun;
import com.fonmap.domain.entity.Fund;
import com.fonmap.domain.entity.ReconciliationResult;
import com.fonmap.infrastructure.client.tefas.TefasClient;
import com.fonmap.infrastructure.client.tefas.dto.TefasFundDto;
import com.fonmap.infrastructure.repository.EstimateRunRepository;
import com.fonmap.infrastructure.repository.FundRepository;
import com.fonmap.infrastructure.repository.ReconciliationResultRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * ReconciliationService — Gece TEFAS Mutabakatı ve Model Doğrulama Servisi
 * ==========================================================================
 *
 * SİSTEMDEKİ ROLÜ:
 * Fonmap platformunun "Hakem ve Kalite Kontrol" servisidir.
 * Seans saatleri (10:00 - 18:10) boyunca hisse bazlı dinamik modelimizin ürettiği
 * son tahmini, gece saat 23:00'te TEFAS'ın yayımladığı kesinleşmiş resmi fiyat ve getiriyle
 * karşılaştırır.
 *
 * NEDEN BU SERVİS VAR?
 * 1. Şeffaflık ve Hesap Verebilirlik: "Modelimiz ne kadar doğru tahmin etti?" sorusunun cevabıdır.
 * 2. Model Kalite Ölçümü: MAE (Ortalama Mutlak Hata) ve RMSE (Kök Ortalama Kare Hata)
 *    istatistiklerini üreterek sistemin matematiksel başarısını belgeler.
 * 3. Backtest ve Sürekli İyileştirme: Hangi fonlarda veya hangi piyasa koşullarında
 *    tahminlerin saptığını ortaya çıkarır.
 *
 * ÇALIŞMA ZAMANI:
 * Her iş günü gecesi saat 23:00'te otomatik cron worker tarafından tetiklenir
 * veya admin panelinden geçmiş günler için manuel çalıştırılabilir.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReconciliationService {

    private final FundRepository fundRepository;
    private final EstimateRunRepository estimateRunRepository;
    private final ReconciliationResultRepository reconciliationResultRepository;
    private final TefasClient tefasClient;

    /**
     * Finansal hesaplamalar için standart yuvarlama hassasiyeti (6 basamak).
     */
    private static final int CALC_SCALE = 6;

    /**
     * Tek bir fon için belirli bir işlem gününün mutabakatını yapar ve veritabanına kaydeder.
     *
     * @param fundCode Fon borsa kodu (Örn: "THF", "TLY", "TTE")
     * @param targetDate Mutabakat yapılacak işlem günü tarihi (Örn: 2026-09-22)
     * @return Mutabakat sonuç DTO'su
     */
    @Transactional
    public ReconciliationDto reconcileFund(String fundCode, LocalDate targetDate) {
        if (fundCode == null || fundCode.isBlank()) {
            throw new IllegalArgumentException("Fon kodu boş olamaz.");
        }
        if (targetDate == null) {
            throw new IllegalArgumentException("Mutabakat tarihi boş olamaz.");
        }

        String cleanCode = fundCode.trim().toUpperCase();

        // 1. Fonun sistemde var olduğunu doğrula
        Fund fund = fundRepository.findByCode(cleanCode)
                .orElseThrow(() -> new IllegalArgumentException("Fon veritabanında bulunamadı: " + cleanCode));

        // 2. AŞAMA: O günün seans sonu (18:10 civarı) üretilen son tahminimizi çek
        LocalDateTime startOfDay = targetDate.atStartOfDay();
        LocalDateTime endOfDay = targetDate.atTime(23, 59, 59);

        List<EstimateRun> dailyEstimates = estimateRunRepository.findEstimateHistory(fund.getId(), startOfDay, endOfDay);

        if (dailyEstimates.isEmpty()) {
            log.warn("[ReconciliationService] Fon '{}' için {} tarihinde hiçbir tahmin kaydı bulunamadı (Tatil veya seans dışı).",
                    cleanCode, targetDate);
            return buildUnverifiedDto(fund, targetDate, "O güne ait seans tahmin kaydı bulunmuyor.");
        }

        // Günün en son üretilen tahmini kapanış tahminimizdir
        EstimateRun finalEstimate = dailyEstimates.get(dailyEstimates.size() - 1);
        BigDecimal predictedReturn = finalEstimate.getEstimatedReturn();

        // 3. AŞAMA: TEFAS'tan o günün resmi gerçekleşen getirisini çek
        Optional<TefasFundDto> tefasOpt = tefasClient.fetchFundPrice(cleanCode, targetDate);

        if (tefasOpt.isEmpty() || tefasOpt.get().getDailyReturn() == null) {
            log.warn("[ReconciliationService] TEFAS'ta fon '{}' için {} tarihli resmi getiri henüz yayımlanmamış.",
                    cleanCode, targetDate);
            return buildPendingDto(fund, targetDate, predictedReturn);
        }

        TefasFundDto tefasData = tefasOpt.get();
        BigDecimal actualReturn = tefasData.getDailyReturn();

        // 4. AŞAMA: Hata Metriklerini Hesapla
        // errorDiff = predictedReturn - actualReturn
        BigDecimal errorDiff = predictedReturn.subtract(actualReturn).setScale(CALC_SCALE, RoundingMode.HALF_UP);
        // absoluteError = |errorDiff|
        BigDecimal absoluteError = errorDiff.abs().setScale(CALC_SCALE, RoundingMode.HALF_UP);

        LocalDateTime now = LocalDateTime.now();

        // 5. AŞAMA: Veritabanına Kalıcı Kayıt (Varsa güncelle, yoksa yeni ekle)
        Optional<ReconciliationResult> existingRecord =
                reconciliationResultRepository.findByFundIdAndDateWithFund(fund.getId(), targetDate);

        ReconciliationResult resultEntity;
        if (existingRecord.isPresent()) {
            resultEntity = existingRecord.get();
            resultEntity.setPredictedReturn(predictedReturn);
            resultEntity.setActualReturn(actualReturn);
            resultEntity.setErrorDiff(errorDiff);
            resultEntity.setAbsoluteError(absoluteError);
            resultEntity.setIsVerified(true);
        } else {
            resultEntity = ReconciliationResult.builder()
                    .fund(fund)
                    .date(targetDate)
                    .predictedReturn(predictedReturn)
                    .actualReturn(actualReturn)
                    .errorDiff(errorDiff)
                    .absoluteError(absoluteError)
                    .isVerified(true)
                    .build();
        }

        ReconciliationResult savedResult = reconciliationResultRepository.save(resultEntity);

        log.info("[ReconciliationService] Mutabakat tamamlandı: Fon='{}', Tarih={}, Tahmin=%{}, Gerçek=%{}, Sapma=%{} (Mutlak Hata: %{})",
                cleanCode,
                targetDate,
                predictedReturn.multiply(BigDecimal.valueOf(100)).setScale(4, RoundingMode.HALF_UP),
                actualReturn.multiply(BigDecimal.valueOf(100)).setScale(4, RoundingMode.HALF_UP),
                errorDiff.multiply(BigDecimal.valueOf(100)).setScale(4, RoundingMode.HALF_UP),
                absoluteError.multiply(BigDecimal.valueOf(100)).setScale(4, RoundingMode.HALF_UP));

        return ReconciliationDto.builder()
                .id(savedResult.getId())
                .fundCode(fund.getCode())
                .fundTitle(fund.getTitle())
                .date(targetDate)
                .predictedReturn(predictedReturn)
                .actualReturn(actualReturn)
                .errorDiff(errorDiff)
                .absoluteError(absoluteError)
                .isVerified(true)
                .verifiedAt(now)
                .build();
    }

    /**
     * Sistemdeki tüm aktif fonlar için belirli bir günün gece mutabakatını topluca çalıştırır.
     * Gece 23:00'te tetiklenen otomatik Cron Job bu metodu çağırır.
     *
     * @param targetDate Mutabakat tarihi
     * @return Tüm aktif fonların mutabakat sonuç listesi
     */
    @Transactional
    public List<ReconciliationDto> reconcileAllActiveFunds(LocalDate targetDate) {
        log.info("[ReconciliationService] Tüm aktif fonlar için {} tarihli toplu mutabakat başlatılıyor...", targetDate);

        List<Fund> activeFunds = fundRepository.findAllActiveFundsOrdered();
        List<ReconciliationDto> results = new ArrayList<>(activeFunds.size());

        for (Fund fund : activeFunds) {
            try {
                ReconciliationDto dto = reconcileFund(fund.getCode(), targetDate);
                results.add(dto);
            } catch (Exception e) {
                log.error("[ReconciliationService] Fon '{}' için mutabakat sırasında hata oluştu: {}",
                        fund.getCode(), e.getMessage());
            }
        }

        log.info("[ReconciliationService] Toplu mutabakat tamamlandı. Toplam işlenen fon: {}", results.size());
        return results;
    }

    /**
     * Bir fonun geçmiş mutabakat kayıtlarını analiz ederek modelin matematiksel
     * başarı karnesini (MAE, RMSE, Yön Doğruluğu) hesaplar.
     *
     * @param fundCode Fon borsa kodu
     * @param lastNDays Son kaç iş günü baz alınsın? (0 veya negatif ise tüm geçmiş)
     * @return Başarı metrikleri DTO'su
     */
    public ReconciliationDto.PerformanceMetrics calculateMetrics(String fundCode, int lastNDays) {
        if (fundCode == null || fundCode.isBlank()) {
            throw new IllegalArgumentException("Fon kodu boş olamaz.");
        }

        Fund fund = fundRepository.findByCode(fundCode.trim().toUpperCase())
                .orElseThrow(() -> new IllegalArgumentException("Fon bulunamadı: " + fundCode));

        List<ReconciliationResult> records = reconciliationResultRepository.findByFundIdOrderByDateDesc(fund.getId())
                .stream()
                .filter(r -> Boolean.TRUE.equals(r.getIsVerified()) && r.getAbsoluteError() != null)
                .collect(Collectors.toList());

        if (lastNDays > 0 && records.size() > lastNDays) {
            records = records.subList(0, lastNDays);
        }

        if (records.isEmpty()) {
            return ReconciliationDto.PerformanceMetrics.builder()
                    .fundCode(fund.getCode())
                    .totalDays(0)
                    .mae(BigDecimal.ZERO)
                    .rmse(BigDecimal.ZERO)
                    .directionalAccuracy(BigDecimal.ZERO)
                    .build();
        }

        int count = records.size();
        BigDecimal totalAbsError = BigDecimal.ZERO;
        BigDecimal totalSquaredError = BigDecimal.ZERO;
        int correctDirectionDays = 0;

        for (ReconciliationResult r : records) {
            BigDecimal absErr = r.getAbsoluteError();
            totalAbsError = totalAbsError.add(absErr);

            // Karesel Hata: (errorDiff)^2
            BigDecimal errDiff = r.getErrorDiff() != null ? r.getErrorDiff() : absErr;
            totalSquaredError = totalSquaredError.add(errDiff.multiply(errDiff));

            // Yön Doğruluğu: Tahmin ve gerçek aynı yönde mi? (İkisi de pozitif veya ikisi de negatif)
            BigDecimal pred = r.getPredictedReturn();
            BigDecimal act = r.getActualReturn();
            if (pred != null && act != null) {
                // pred * act >= 0 ise yön doğru tahmin edilmiş demektir
                if (pred.multiply(act).compareTo(BigDecimal.ZERO) >= 0) {
                    correctDirectionDays++;
                }
            }
        }

        // MAE = Σ |error| / N
        BigDecimal mae = totalAbsError.divide(BigDecimal.valueOf(count), CALC_SCALE, RoundingMode.HALF_UP);

        // RMSE = sqrt( Σ (error)^2 / N )
        BigDecimal meanSquaredError = totalSquaredError.divide(BigDecimal.valueOf(count), CALC_SCALE, RoundingMode.HALF_UP);
        BigDecimal rmse = meanSquaredError.sqrt(new MathContext(CALC_SCALE + 2, RoundingMode.HALF_UP))
                .setScale(CALC_SCALE, RoundingMode.HALF_UP);

        // Yön Doğruluğu Oranı: doğruGün / toplamGün
        BigDecimal directionalAccuracy = BigDecimal.valueOf(correctDirectionDays)
                .divide(BigDecimal.valueOf(count), 4, RoundingMode.HALF_UP);

        return ReconciliationDto.PerformanceMetrics.builder()
                .fundCode(fund.getCode())
                .totalDays(count)
                .mae(mae)
                .rmse(rmse)
                .directionalAccuracy(directionalAccuracy)
                .build();
    }

    private ReconciliationDto buildUnverifiedDto(Fund fund, LocalDate date, String reason) {
        return ReconciliationDto.builder()
                .fundCode(fund.getCode())
                .fundTitle(fund.getTitle())
                .date(date)
                .predictedReturn(BigDecimal.ZERO)
                .actualReturn(BigDecimal.ZERO)
                .errorDiff(BigDecimal.ZERO)
                .absoluteError(BigDecimal.ZERO)
                .isVerified(false)
                .verifiedAt(LocalDateTime.now())
                .build();
    }

    private ReconciliationDto buildPendingDto(Fund fund, LocalDate date, BigDecimal predicted) {
        return ReconciliationDto.builder()
                .fundCode(fund.getCode())
                .fundTitle(fund.getTitle())
                .date(date)
                .predictedReturn(predicted)
                .actualReturn(null)
                .errorDiff(null)
                .absoluteError(null)
                .isVerified(false)
                .verifiedAt(LocalDateTime.now())
                .build();
    }
}
