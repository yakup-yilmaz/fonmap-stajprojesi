package com.fonmap.infrastructure.repository;

import com.fonmap.domain.entity.EstimateRun;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Motorun her dakika fon bazında ürettiği tahmini getiri (Estimated Return)
 * çalıştırma sonuçlarını yönetir.
 */
@Repository
public interface EstimateRunRepository extends JpaRepository<EstimateRun, UUID> {

    /**
     * Bir fonun EN SON hesaplanan tahmini getiri kaydını getirir.
     * Anasayfada kullanıcının gördüğü o anki canlı getiri yüzdesi (%+1.25) bu sorgudan döner.
     * PageRequest.of(0, 1) ile çağrılır.
     */
    @Query("SELECT er FROM EstimateRun er JOIN FETCH er.fund WHERE er.fund.id = :fundId ORDER BY er.calculatedAt DESC")
    List<EstimateRun> findLatestByFundId(@Param("fundId") UUID fundId, Pageable pageable);

    /**
     * Fon koduna göre (Örn: "THF") en güncel tahmini getiri kaydını getirir.
     */
    @Query("SELECT er FROM EstimateRun er JOIN FETCH er.fund WHERE er.fund.code = :fundCode ORDER BY er.calculatedAt DESC")
    List<EstimateRun> findLatestByFundCode(@Param("fundCode") String fundCode, Pageable pageable);

    /**
     * Bir fonun seans içindeki tahmin geçmişini döner (Örn: 10:00 - 18:00 arası tahmini getiri çizgisi).
     * Detay sayfasındaki "Gün İçi Fon Getiri Grafiği"ni besler.
     */
    @Query("SELECT er FROM EstimateRun er WHERE er.fund.id = :fundId " +
           "AND er.calculatedAt BETWEEN :startTime AND :endTime ORDER BY er.calculatedAt ASC")
    List<EstimateRun> findEstimateHistory(
            @Param("fundId") UUID fundId,
            @Param("startTime") LocalDateTime startTime,
            @Param("endTime") LocalDateTime endTime
    );
}
