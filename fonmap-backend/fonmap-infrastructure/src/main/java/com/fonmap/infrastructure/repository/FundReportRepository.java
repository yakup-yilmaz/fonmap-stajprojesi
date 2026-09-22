package com.fonmap.infrastructure.repository;

import com.fonmap.domain.entity.FundReport;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * KAP'tan indirilen ve işlenen fon aylık portföy dağılım PDF raporlarının
 * veritabanı işlemlerini yönetir.
 */
@Repository
public interface FundReportRepository extends JpaRepository<FundReport, UUID> {

    /**
     * İndirilen PDF dosyasının SHA-256 parmak iziyle var olup olmadığını denetler.
     * Mükerrer indirmeyi engellemek için kullanılır.
     */
    @Query("SELECT r FROM FundReport r WHERE r.pdfSha256 = :pdfSha256")
    Optional<FundReport> findByPdfSha256(@Param("pdfSha256") String pdfSha256);

    /**
     * Bir fonun belirli bir rapor tarihindeki (Örn: 31.08.2026) raporunu getirir.
     */
    @Query("SELECT r FROM FundReport r JOIN FETCH r.fund WHERE r.fund.id = :fundId AND r.reportDate = :reportDate")
    Optional<FundReport> findByFundIdAndReportDateWithFund(
            @Param("fundId") UUID fundId,
            @Param("reportDate") LocalDate reportDate
    );

    /**
     * Bir fonun geçmişten bugüne tüm PDF raporlarını tarihe göre tersten listeler.
     */
    @Query("SELECT r FROM FundReport r WHERE r.fund.id = :fundId ORDER BY r.reportDate DESC")
    List<FundReport> findByFundIdOrderByReportDateDesc(@Param("fundId") UUID fundId);
}
