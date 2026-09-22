package com.fonmap.infrastructure.repository;

import com.fonmap.domain.entity.InstrumentAlias;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * PDF'lerdeki değişken/kirli hisse isimlerinin standart hisse kaydına (Instrument)
 * eşlenmesini sağlayan dinamik sözlük repository'si.
 */
@Repository
public interface InstrumentAliasRepository extends JpaRepository<InstrumentAlias, Long> {

    /**
     * PDF'ten okunan ham isme göre eşleşen alias kaydını getirir.
     * JOIN FETCH kullanarak bağlı Instrument nesnesini de tek sorguda yükler (N+1 engellenir).
     */
    @Query("SELECT ia FROM InstrumentAlias ia LEFT JOIN FETCH ia.instrument WHERE ia.rawName = :rawName")
    Optional<InstrumentAlias> findByRawNameWithInstrument(@Param("rawName") String rawName);

    /**
     * Sistem tarafından otomatik tanınamayıp henüz admin onayı almamış alias'ları listeler.
     * Admin Panelindeki "Eşleşmeyen Yeni Varlıklar" listesini besler.
     */
    @Query("SELECT ia FROM InstrumentAlias ia LEFT JOIN FETCH ia.instrument WHERE ia.isApproved = false ORDER BY ia.createdAt DESC")
    List<InstrumentAlias> findAllUnapprovedWithInstrument();
}
