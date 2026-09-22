package com.fonmap.infrastructure.repository;

import com.fonmap.domain.entity.Fund;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Takip edilen yatırım fonlarının (THF, TLY, TMV vb.) ana kayıtlarını yönetir.
 */
@Repository
public interface FundRepository extends JpaRepository<Fund, UUID> {

    /**
     * 3 harfli fon koduna göre fonu getirir (Örn: "THF").
     * API katmanında (/api/v1/funds/THF) ve servislerde temel erişim noktasıdır.
     */
    @Query("SELECT f FROM Fund f WHERE f.code = :code")
    Optional<Fund> findByCode(@Param("code") String code);

    /**
     * Sadece sistemde aktif olan fonları belirlenen vitrin sırasına (displayOrder) göre listeler.
     * Kullanıcı arayüzündeki fon listesi buradan beslenir.
     */
    @Query("SELECT f FROM Fund f WHERE f.isActive = true ORDER BY f.displayOrder ASC")
    List<Fund> findAllActiveFundsOrdered();

    /**
     * Fon kodunun veritabanında zaten var olup olmadığını doğrular.
     */
    @Query("SELECT CASE WHEN COUNT(f) > 0 THEN true ELSE false END FROM Fund f WHERE f.code = :code")
    boolean existsByCode(@Param("code") String code);
}
