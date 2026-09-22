package com.fonmap.infrastructure.repository;

import com.fonmap.domain.entity.MarketHoliday;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.Optional;

/**
 * Borsa İstanbul resmi tatil takvimini yönetir.
 * Fiyat çekme ve getiri hesaplama motorlarının kapalı günlerde gereksiz çalışmasını önler.
 */
@Repository
public interface MarketHolidayRepository extends JpaRepository<MarketHoliday, Long> {

    /**
     * Verilen tarihin resmi borsa tatili olup olmadığını kontrol eder.
     */
    @Query("SELECT CASE WHEN COUNT(h) > 0 THEN true ELSE false END FROM MarketHoliday h WHERE h.holidayDate = :holidayDate")
    boolean existsByHolidayDate(@Param("holidayDate") LocalDate holidayDate);

    /**
     * Belirli bir tarihteki tatil detayını getirir (Örn: Yarım gün mü, tam gün mü?).
     */
    @Query("SELECT h FROM MarketHoliday h WHERE h.holidayDate = :holidayDate")
    Optional<MarketHoliday> findByHolidayDate(@Param("holidayDate") LocalDate holidayDate);
}
