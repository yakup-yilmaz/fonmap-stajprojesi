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
     * Bir fonun EN GÜNCEL portföy snapshot'ını ilişkili Fund ve FundReport (PDF meta verileri) ile birlikte getirir.
     * Calculation Engine ve Vitrin Controller'ları, PDF indirilme tarihi ve SHA-256 bilgilerini
     * N+1 problemi olmadan tek seferde okumak için bu sorguyu kullanır.
     * PageRequest.of(0, 1) ile çağrılır.
     */
    @Query("SELECT fs FROM FundSnapshot fs JOIN FETCH fs.fund LEFT JOIN FETCH fs.report WHERE fs.fund.id = :fundId ORDER BY fs.snapshotDate DESC")
    List<FundSnapshot> findLatestByFundId(@Param("fundId") UUID fundId, Pageable pageable);

    /**
     * Fon koduna göre (Örn: "THF") en güncel snapshot'ı ve ilişkili KAP PDF raporu meta verilerini getirir.
     */
    @Query("SELECT fs FROM FundSnapshot fs JOIN FETCH fs.fund LEFT JOIN FETCH fs.report WHERE fs.fund.code = :fundCode ORDER BY fs.snapshotDate DESC")
    List<FundSnapshot> findLatestByFundCode(@Param("fundCode") String fundCode, Pageable pageable);

    /**
     * Belirli bir PDF raporundan üretilen snapshot'ı bulur.
     */
    @Query("SELECT fs FROM FundSnapshot fs WHERE fs.report.id = :reportId")
    Optional<FundSnapshot> findByReportId(@Param("reportId") UUID reportId);

    /**
     * Fon koduna göre geçmişten bugüne tüm portföy snapshot'larını ilişkili raporlarıyla birlikte getirir.
     */
    @Query("SELECT fs FROM FundSnapshot fs JOIN FETCH fs.fund LEFT JOIN FETCH fs.report WHERE fs.fund.code = :fundCode ORDER BY fs.snapshotDate DESC")
    List<FundSnapshot> findAllByFundCodeOrderByDateDesc(@Param("fundCode") String fundCode);

    /**
     * Backtest ve Geçmiş Simülasyonu için Zaman Noktası (Point-in-Time) Sorgusu:
     * Belirli bir simülasyon gününde (targetDate) yürürlükte olan en güncel snapshot'ı getirir.
     * Geleceğe bakma hatasını (Look-Ahead Bias) önlemek için snapshotDate <= targetDate şartı aranır.
     * PageRequest.of(0, 1) ile çağrılır.
     */
    @Query("SELECT fs FROM FundSnapshot fs JOIN FETCH fs.fund WHERE fs.fund.code = :fundCode AND fs.snapshotDate <= :targetDate ORDER BY fs.snapshotDate DESC")
    List<FundSnapshot> findLatestByFundCodeAndDateBeforeOrEqual(
            @Param("fundCode") String fundCode,
            @Param("targetDate") java.time.LocalDate targetDate,
            Pageable pageable);

    /**
     * Katı Point-in-Time Kuralı (Mod A):
     * Simülasyon gününde (targetDate) sabah seans açıldığında o günün akşam PDF'i henüz yayımlanmamıştır.
     * Bu nedenle testDate'den ÖNCEKİ (< targetDate) son resmi snapshot aranır.
     * Örneğin 1 Eylül - 30 Eylül arasında 31 Ağustos snapshot'ı seçilir; 1 Ekim'de 30 Eylül snapshot'ına geçilir.
     */
    @Query("SELECT fs FROM FundSnapshot fs JOIN FETCH fs.fund WHERE fs.fund.code = :fundCode AND fs.snapshotDate < :targetDate ORDER BY fs.snapshotDate DESC")
    List<FundSnapshot> findLatestByFundCodeAndDateBefore(
            @Param("fundCode") String fundCode,
            @Param("targetDate") java.time.LocalDate targetDate,
            Pageable pageable);
}
