package com.fonmap.application.service.backtest.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * Tüm aktif fonlar için toplu backtest çalıştırıldığında dönen konsolide özet DTO.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BatchBacktestResultDto {

    private int totalFunds;
    private int successfulFunds;
    private int failedFunds;
    private long executionTimeMs;

    @Builder.Default
    private List<FundBacktestSummary> results = new ArrayList<>();

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class FundBacktestSummary {
        private String fundCode;
        private String fundTitle;
        private String status;
        private Integer totalDaysTested;
        private BigDecimal meanAbsoluteError;
        private BigDecimal successRate;
        private String chainStatus;
        private String errorMessage;
    }
}
