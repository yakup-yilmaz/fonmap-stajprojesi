package com.fonmap.application.service.backtest.dto;

import java.time.LocalDate;

/**
 * Özelleştirilmiş Backtest Çalıştırma İsteği DTO'su.
 * Parametreli simülasyon başlangıç ve bitiş tarihlerini taşır.
 */
public record BacktestRunRequest(
        LocalDate startDate,
        LocalDate endDate
) {}
