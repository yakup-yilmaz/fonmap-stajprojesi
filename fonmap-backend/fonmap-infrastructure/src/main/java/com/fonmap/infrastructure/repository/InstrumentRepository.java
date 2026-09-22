package com.fonmap.infrastructure.repository;

import com.fonmap.domain.entity.Instrument;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * Finansal enstrümanların (Hisseler: THYAO, ASELS, VİOP, Döviz vb.) ana kataloğunu yönetir.
 */
@Repository
public interface InstrumentRepository extends JpaRepository<Instrument, Long> {

    /**
     * Borsa sembolüne göre enstrümanı bulur (Örn: "THYAO").
     * Fiyat servisi ve PDF parser tarafından en sık çağrılan metottur.
     */
    @Query("SELECT i FROM Instrument i WHERE i.ticker = :ticker")
    Optional<Instrument> findByTicker(@Param("ticker") String ticker);

    /**
     * Uluslararası ISIN koduna göre varlığı getirir.
     */
    @Query("SELECT i FROM Instrument i WHERE i.isinCode = :isinCode")
    Optional<Instrument> findByIsinCode(@Param("isinCode") String isinCode);

    /**
     * Varlık sınıfına göre tüm enstrümanları listeler (Örn: "EQUITY" hisse senetleri).
     */
    @Query("SELECT i FROM Instrument i WHERE i.assetClass = :assetClass ORDER BY i.ticker ASC")
    List<Instrument> findByAssetClass(@Param("assetClass") String assetClass);
}
