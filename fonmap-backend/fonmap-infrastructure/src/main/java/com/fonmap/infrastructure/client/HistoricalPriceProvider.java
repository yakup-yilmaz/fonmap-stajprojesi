package com.fonmap.infrastructure.client;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * ============================================================================
 * FONMAP — Tarihsel Piyasa Kapanış Fiyatları Sağlayıcı Arayüzü
 * ============================================================================
 * 
 * Backtest, ekonometrik doğrulama ve geriye dönük getiri simülasyonları için
 * belirli bir tarih aralığındaki günlük resmi borsa kapanış fiyat serilerini temin eder.
 * 
 * Clean Code & Provider Pattern:
 * Dış kaynakların (Yahoo Finance, BIST, TCMB vb.) API detayları bu arayüz arkasına gizlenir.
 */
public interface HistoricalPriceProvider {

    /**
     * Belirli bir varlığın verilen tarih aralığındaki günlük kapanış fiyatlarını döner.
     * 
     * @param symbol Varlık sembolü (Örn: "THYAO", "USDTRY", "XU100", "GC=F")
     * @param startDate Başlangıç tarihi (dahil)
     * @param endDate Bitiş tarihi (dahil)
     * @return Tarih -> Kapanış Fiyatı haritası (Sadece borsa işlem günleri ve geçerli fiyatlar)
     */
    Map<LocalDate, BigDecimal> getHistoricalCloses(String symbol, LocalDate startDate, LocalDate endDate);

    /**
     * Birden fazla varlığın verilen tarih aralığındaki günlük kapanış fiyatlarını toplu (batch) olarak çeker.
     * 
     * @param symbols Varlık sembolleri listesi
     * @param startDate Başlangıç tarihi (dahil)
     * @param endDate Bitiş tarihi (dahil)
     * @return Sembol -> (Tarih -> Kapanış Fiyatı) haritası
     */
    Map<String, Map<LocalDate, BigDecimal>> getHistoricalClosesBatch(List<String> symbols, LocalDate startDate, LocalDate endDate);

    /**
     * Sağlayıcının adı (Örn: "YAHOO_HISTORICAL").
     * 
     * @return Sağlayıcı kimliği
     */
    String getProviderName();
}
