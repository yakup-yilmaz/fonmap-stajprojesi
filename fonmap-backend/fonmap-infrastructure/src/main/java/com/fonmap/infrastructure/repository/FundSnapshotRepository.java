package com.fonmap.infrastructure.repository;

import com.fonmap.domain.entity.FundSnapshot;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Fonun ayda 1 kez yayımlanan portföy dağılım özetlerini yönetir.
 */
@Repository
public interface FundSnapshotRepository extends JpaRepository<FundSnapshot, UUID> {

    /**
     * Bir fonun EN GÜNCEL portföy snapshot'ını ilişkili Fund nesnesiyle birlikte getirir.
     * Calculation Engine, getiri hesaplamaya başlarken hangi güncel dağılımı baz alacağını buradan öğrenir.
     * PageRequest.of(0, 1) ile çağrılır.
     */
    @Query("SELECT fs FROM FundSnapshot fs JOIN FETCH fs.fund WHERE fs.fund.id = :fundId ORDER BY fs.snapshotDate DESC")
    List<FundSnapshot> findLatestByFundId(@Param("fundId") UUID fundId, Pageable pageable);

    /**
     * Fon koduna göre (Örn: "THF") en güncel snapshot'ı getirir.
     */
    @Query("SELECT fs FROM FundSnapshot fs JOIN FETCH fs.fund WHERE fs.fund.code = :fundCode ORDER BY fs.snapshotDate DESC")
    List<FundSnapshot> findLatestByFundCode(@Param("fundCode") String fundCode, Pageable pageable);

    /**
     * Belirli bir PDF raporundan üretilen snapshot'ı bulur.
     */
    @Query("SELECT fs FROM FundSnapshot fs WHERE fs.report.id = :reportId")
    Optional<FundSnapshot> findByReportId(@Param("reportId") UUID reportId);
}
