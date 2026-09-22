package com.fonmap.infrastructure.repository;

import com.fonmap.domain.entity.ReconciliationResult;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Gündüz hesaplanan tahmini getiri ile gece TEFAS'ta açıklanan resmi getiri
 * arasındaki farkı (Doğrulama / Backtest) saklayan ve sorgulayan repository.
 */
@Repository
public interface ReconciliationResultRepository extends JpaRepository<ReconciliationResult, Long> {

    /**
     * Belirli bir fonun belirli bir gündeki tahmin vs. gerçek sonuç kaydını getirir.
     */
    @Query("SELECT r FROM ReconciliationResult r JOIN FETCH r.fund WHERE r.fund.id = :fundId AND r.date = :date")
    Optional<ReconciliationResult> findByFundIdAndDateWithFund(
            @Param("fundId") UUID fundId,
            @Param("date") LocalDate date
    );

    /**
     * Bir fonun geçmiş doğrulama ve mutabakat sonuçlarını tarihe göre tersten listeler.
     * Fonun tahmin tutarlılık (MAE/RMSE hata payı) istatistiklerini hesaplamak için kullanılır.
     */
    @Query("SELECT r FROM ReconciliationResult r WHERE r.fund.id = :fundId ORDER BY r.date DESC")
    List<ReconciliationResult> findByFundIdOrderByDateDesc(@Param("fundId") UUID fundId);
}
