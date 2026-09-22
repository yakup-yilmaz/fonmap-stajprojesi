package com.fonmap.infrastructure.repository;

import com.fonmap.domain.entity.EstimateDetail;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

/**
 * Bir getiri tahmininin alt detaylarını (hangi hisse fona ne kadar katkı sağladı) yönetir.
 * Drift hesaplaması sonrası dinamik ağırlıkları tutar.
 */
@Repository
public interface EstimateDetailRepository extends JpaRepository<EstimateDetail, Long> {

    /**
     * Belirli bir tahmine ait tüm hisse katkılarını, ilişkili Instrument nesnesiyle birlikte tek sorguda çeker.
     * JOIN FETCH ile N+1 engellenir.
     * Fona en yüksek getiri katkısı sağlayandan en çok düşürene doğru sıralı listeler.
     */
    @Query("SELECT ed FROM EstimateDetail ed JOIN FETCH ed.instrument WHERE ed.estimateRun.id = :estimateRunId ORDER BY ed.weightedContribution DESC")
    List<EstimateDetail> findByEstimateRunIdWithInstrument(@Param("estimateRunId") UUID estimateRunId);
}
