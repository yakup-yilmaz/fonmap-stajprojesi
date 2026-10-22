package com.fonmap.infrastructure.repository;

import com.fonmap.domain.entity.DailyHoldingDrift;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * DailyHoldingDriftRepository — Günlük Kalibre Edilmiş Ağırlık Kayması Repository'si
 * ===================================================================================
 */
@Repository
public interface DailyHoldingDriftRepository extends JpaRepository<DailyHoldingDrift, Long> {

    /**
     * Snapshot ve tarihe göre kalibre edilmiş holding ağırlıklarını Holding ve Instrument ile birlikte tek sorguda çeker.
     */
    @Query("SELECT d FROM DailyHoldingDrift d JOIN FETCH d.holding h JOIN FETCH h.instrument WHERE d.snapshot.id = :snapshotId AND d.driftDate = :driftDate")
    List<DailyHoldingDrift> findBySnapshotIdAndDriftDateWithHolding(
            @Param("snapshotId") UUID snapshotId,
            @Param("driftDate") LocalDate driftDate
    );

    /**
     * Fon ID ve tarihe göre kayıtlı kalibre edilmiş ağırlıkları getirir.
     */
    @Query("SELECT d FROM DailyHoldingDrift d WHERE d.fund.id = :fundId AND d.driftDate = :driftDate")
    List<DailyHoldingDrift> findByFundIdAndDriftDate(
            @Param("fundId") UUID fundId,
            @Param("driftDate") LocalDate driftDate
    );

    /**
     * Belirli bir snapshot ve tarih için kalibrasyonun daha önce kaydedilip kaydedilmediğini sorgular.
     */
    boolean existsBySnapshotIdAndDriftDate(UUID snapshotId, LocalDate driftDate);

    /**
     * Belirli bir fon ve tarih için kalibrasyonun bulunup bulunmadığını sorgular.
     */
    boolean existsByFundIdAndDriftDate(UUID fundId, LocalDate driftDate);

    /**
     * Yeniden kalibrasyon yapıldığında eski kaydı temizler.
     */
    @Modifying
    @Transactional
    void deleteBySnapshotIdAndDriftDate(UUID snapshotId, LocalDate driftDate);
}
