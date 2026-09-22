package com.fonmap.infrastructure.repository;

import com.fonmap.domain.entity.Holding;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

/**
 * Fonun portföyünde tutulan hisse senedi ve VİOP pozisyon satırlarını yönetir.
 * Calculation Engine'in en çok veri okuduğu tablodur.
 */
@Repository
public interface HoldingRepository extends JpaRepository<Holding, UUID> {

    /**
     * Belirli bir snapshot'a ait tüm hisseleri, ilişkili Instrument nesnesiyle birlikte TEK SORGUDAN çeker.
     * JOIN FETCH sayesinde 50 hisse için 50 ayrı veritabanı sorgusu atılması (N+1 problemi) tamamen önlenir.
     * Portföy ağırlığı en yüksek olandan küçüğe doğru sıralı gelir.
     */
    @Query("SELECT h FROM Holding h JOIN FETCH h.instrument WHERE h.snapshot.id = :snapshotId ORDER BY h.weightRatio DESC")
    List<Holding> findBySnapshotIdWithInstrument(@Param("snapshotId") UUID snapshotId);
}
