package com.fonmap.application.service.reconciliation;

import com.fonmap.application.service.reconciliation.dto.ReconciliationDto;
import com.fonmap.domain.entity.*;
import com.fonmap.domain.enums.AssetClass;
import com.fonmap.infrastructure.client.HistoricalPriceProvider;
import com.fonmap.infrastructure.client.tefas.TefasClient;
import com.fonmap.infrastructure.client.tefas.dto.TefasFundDto;
import com.fonmap.infrastructure.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * ReconciliationServiceTest — TEFAS Mutabakatı ve MAE/RMSE Doğrulama Birim Testleri
 * ====================================================================================
 */
@ExtendWith(MockitoExtension.class)
class ReconciliationServiceTest {

    @Mock
    private FundRepository fundRepository;
    @Mock
    private EstimateRunRepository estimateRunRepository;
    @Mock
    private ReconciliationResultRepository reconciliationResultRepository;
    @Mock
    private TefasClient tefasClient;
    @Mock
    private FundSnapshotRepository fundSnapshotRepository;
    @Mock
    private HoldingRepository holdingRepository;
    @Mock
    private HistoricalPriceProvider historicalPriceProvider;
    @Mock
    private MarketHolidayRepository marketHolidayRepository;

    @InjectMocks
    private ReconciliationService reconciliationService;

    private Fund fund;
    private FundSnapshot snapshot;
    private Holding thyaoHolding;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(reconciliationService, "fundSnapshotRepository", fundSnapshotRepository);
        ReflectionTestUtils.setField(reconciliationService, "holdingRepository", holdingRepository);
        ReflectionTestUtils.setField(reconciliationService, "historicalPriceProvider", historicalPriceProvider);
        ReflectionTestUtils.setField(reconciliationService, "marketHolidayRepository", marketHolidayRepository);
        ReflectionTestUtils.setField(reconciliationService, "defaultPolicyRate", new BigDecimal("0.45"));

        fund = Fund.builder()
                .id(UUID.randomUUID())
                .code("THF")
                .title("Tera Portföy Hisse Senedi Fonu")
                .manager("Tera Portföy")
                .annualFeeRatio(new BigDecimal("0.0250"))
                .isActive(true)
                .build();

        snapshot = FundSnapshot.builder()
                .id(UUID.randomUUID())
                .fund(fund)
                .snapshotDate(LocalDate.of(2026, 9, 1))
                .totalNetAssetValue(new BigDecimal("100000000"))
                .build();

        Instrument thyao = Instrument.builder()
                .id(1L)
                .ticker("THYAO")
                .assetClass(AssetClass.EQUITY)
                .build();

        thyaoHolding = Holding.builder()
                .id(UUID.randomUUID())
                .snapshot(snapshot)
                .instrument(thyao)
                .weightRatio(new BigDecimal("1.0000"))
                .totalValue(new BigDecimal("100000000"))
                .isShort(false)
                .build();
    }

    @Test
    @DisplayName("Kayıtlı canlı tahmin ile TEFAS resmi fiyatı karşılaştırılıp mutabakat mühürlenmeli")
    void testReconcileFund_WithExistingEstimateRun_Success() {
        LocalDate targetDate = LocalDate.of(2026, 9, 23); // Çarşamba

        when(fundRepository.findByCode("THF")).thenReturn(Optional.of(fund));

        // Gün sonu kayıtlı model tahmini: +%1.50
        EstimateRun estimateRun = EstimateRun.builder()
                .id(UUID.randomUUID())
                .fund(fund)
                .snapshot(snapshot)
                .estimatedReturn(new BigDecimal("0.015000"))
                .coverageRatio(new BigDecimal("0.9500"))
                .calculatedAt(LocalDateTime.of(2026, 9, 23, 18, 5))
                .build();

        when(estimateRunRepository.findEstimateHistory(eq(fund.getId()), any(LocalDateTime.class), any(LocalDateTime.class)))
                .thenReturn(List.of(estimateRun));

        // TEFAS resmi getiri verisi: +%1.60
        TefasFundDto currTefas = TefasFundDto.builder()
                .fundCode("THF")
                .unitPrice(new BigDecimal("10.160000"))
                .dailyReturn(new BigDecimal("0.016000"))
                .priceDate(targetDate)
                .build();

        when(tefasClient.fetchFundPrice("THF", targetDate)).thenReturn(Optional.of(currTefas));
        when(reconciliationResultRepository.findByFundIdAndDateWithFund(fund.getId(), targetDate))
                .thenReturn(Optional.empty());

        when(reconciliationResultRepository.save(any(ReconciliationResult.class))).thenAnswer(i -> {
            ReconciliationResult r = i.getArgument(0);
            r.setId(1L);
            return r;
        });

        ReconciliationDto result = reconciliationService.reconcileFund("THF", targetDate);

        assertThat(result).isNotNull();
        assertThat(result.getFundCode()).isEqualTo("THF");
        assertThat(result.isVerified()).isTrue();
        // Model: 0.0150, TEFAS: 0.0160 -> absoluteError = 0.0010 (%0.10, yani 10 bps)
        assertThat(result.getAbsoluteError()).isEqualByComparingTo("0.001000");

        verify(reconciliationResultRepository).save(any(ReconciliationResult.class));
    }

    @Test
    @DisplayName("Pazartesi günü rekonstrüksiyonda 3 günlük borsa takvim gün farkı (Cuma'dan Pazartesi'ye) kullanılmalı")
    void testReconstructPredictedReturn_Monday_CalculatesThreeDaysCalendarDifference() {
        // Pazartesi: 2026-09-28. Cuma: 2026-09-25 (Fark: 3 takvim günü)
        LocalDate monday = LocalDate.of(2026, 9, 28);
        LocalDate friday = LocalDate.of(2026, 9, 25);

        when(fundSnapshotRepository.findLatestByFundCodeAndDateBefore(eq("THF"), eq(monday), any(PageRequest.class)))
                .thenReturn(List.of(snapshot));
        when(holdingRepository.findBySnapshotIdWithInstrument(snapshot.getId()))
                .thenReturn(List.of(thyaoHolding));

        // Tarihsel fiyat serisi: Cuma 100 TL, Pazartesi 102 TL (%+2.00 hisse getirisi)
        Map<LocalDate, BigDecimal> thyaoCloses = new HashMap<>();
        thyaoCloses.put(friday, new BigDecimal("100.00"));
        thyaoCloses.put(monday, new BigDecimal("102.00"));

        when(historicalPriceProvider.getHistoricalClosesBatch(anyList(), any(LocalDate.class), any(LocalDate.class)))
                .thenReturn(Map.of("THYAO", thyaoCloses));

        BigDecimal predictedReturn = reconciliationService.reconstructPredictedReturn(fund, monday);

        assertThat(predictedReturn).isNotNull();
        // Hisse Brüt Getirisi = +0.020000 (%2.00)
        // 3 Günlük Gider = 0.0250 * 3 / 365 = 0.075 / 365 = 0.000205
        // Net Tahmini Getiri = 0.020000 - 0.000205 = 0.019795 (%1.98)
        assertThat(predictedReturn).isEqualByComparingTo("0.019795");
    }

    @Test
    @DisplayName("TEFAS henüz açıklamadığında PENDING durumunda DTO dönmeli")
    void testReconcileFund_TefasPending_ReturnsUnverified() {
        LocalDate targetDate = LocalDate.of(2026, 9, 23);

        when(fundRepository.findByCode("THF")).thenReturn(Optional.of(fund));
        when(estimateRunRepository.findEstimateHistory(eq(fund.getId()), any(LocalDateTime.class), any(LocalDateTime.class)))
                .thenReturn(Collections.emptyList());
        when(fundSnapshotRepository.findLatestByFundCodeAndDateBefore(eq("THF"), eq(targetDate), any(PageRequest.class)))
                .thenReturn(Collections.emptyList());
        when(fundSnapshotRepository.findLatestByFundCodeAndDateBeforeOrEqual(eq("THF"), eq(targetDate), any(PageRequest.class)))
                .thenReturn(Collections.emptyList());

        ReconciliationDto result = reconciliationService.reconcileFund("THF", targetDate);

        assertThat(result).isNotNull();
        assertThat(result.isVerified()).isFalse();
    }
}
