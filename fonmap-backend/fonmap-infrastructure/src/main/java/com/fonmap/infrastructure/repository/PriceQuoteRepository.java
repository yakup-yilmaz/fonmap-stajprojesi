package com.fonmap.infrastructure.repository;

import com.fonmap.domain.entity.PriceQuote;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * Anlık piyasa fiyat kayıtlarını yönetir.
 * Sistemdeki en yüksek veri hacmine sahip tablodur.
 */
@Repository
public interface PriceQuoteRepository extends JpaRepository<PriceQuote, Long> {

    /**
     * Bir enstrümanın (hissenin) çekilen EN SON fiyatını döner.
     * Calculation Engine, getiri hesaplarken hissenin anlık fiyatını bu metotla çeker.
     */
    Optional<PriceQuote> findTopByInstrumentIdOrderByQuoteTimeDesc(Long instrumentId);

    /**
     * Borsa sembolüne (Örn: "THYAO") göre en son fiyat kaydını ilişkili Instrument ile tek seferde çeker.
     * PageRequest.of(0, 1) verilerek LIMIT 1 optimize şekilde çalıştırılır.
     */
    @Query("SELECT pq FROM PriceQuote pq JOIN FETCH pq.instrument WHERE pq.instrument.ticker = :ticker ORDER BY pq.quoteTime DESC")
    List<PriceQuote> findLatestByTickerWithInstrument(@Param("ticker") String ticker, Pageable pageable);

    /**
     * Bir hissenin belirli iki tarih/saat arasındaki fiyat hareketlerini getirir.
     * Web arayüzündeki gün içi çizgi grafiklerini (örneğin 10:00 - 18:00 arası dakika dakika fiyat) besler.
     */
    @Query("SELECT pq FROM PriceQuote pq WHERE pq.instrument.id = :instrumentId " +
           "AND pq.quoteTime BETWEEN :startTime AND :endTime ORDER BY pq.quoteTime ASC")
    List<PriceQuote> findPriceHistory(
            @Param("instrumentId") Long instrumentId,
            @Param("startTime") LocalDateTime startTime,
            @Param("endTime") LocalDateTime endTime
    );
}
