package com.fonmap.application.service.calculation;

import com.fonmap.application.service.calculation.dto.FundEstimateDto;
import com.fonmap.application.service.drift.WeightDriftService;
import com.fonmap.domain.entity.*;
import com.fonmap.domain.enums.AssetClass;
import com.fonmap.domain.enums.ConfidenceLevel;
import com.fonmap.infrastructure.client.dto.MarketPriceDto;
import com.fonmap.infrastructure.client.tefas.TefasClient;
import com.fonmap.infrastructure.client.tefas.dto.TefasFundDto;
import com.fonmap.infrastructure.repository.*;
import com.fonmap.infrastructure.service.PriceService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageRequest;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.*;

/**
 * ReturnCalculationServiceTest — Seans İçi Getiri Motoru Matematiksel ve Birim Testleri
 * ======================================================================================
 */
@ExtendWith(MockitoExtension.class)
class ReturnCalculationServiceTest {

    @Mock
    private FundRepository fundRepository;
    @Mock
    private FundSnapshotRepository fundSnapshotRepository;
    @Mock
    private HoldingRepository holdingRepository;
    @Mock
    private EstimateRunRepository estimateRunRepository;
    @Mock
    private EstimateDetailRepository estimateDetailRepository;
    @Mock
    private PriceService priceService;
    @Mock
    private WeightDriftService weightDriftService;
    @Mock
    private TefasClient tefasClient;

    @InjectMocks
    private ReturnCalculationService returnCalculationService;

    private Fund fund;
    private FundSnapshot snapshot;
    private Holding thyaoHolding;
    private Holding kcholHolding;
    private Holding repoHolding;

    @BeforeEach
    void setUp() {
        returnCalculationService.setConfiguredPolicyRate(new BigDecimal("0.45"));

        fund = Fund.builder()
                .id(UUID.randomUUID())
                .code("THF")
                .title("Tera Portföy Hisse Senedi Fonu")
                .manager("Tera Portföy")
                .annualFeeRatio(new BigDecimal("0.0252")) // Günlük ücret: 0.0252 / 252 = 0.0001 (%0.01)
                .isActive(true)
                .build();

        snapshot = FundSnapshot.builder()
                .id(UUID.randomUUID())
                .fund(fund)
                .snapshotDate(LocalDate.now().minusDays(5))
                .totalNetAssetValue(new BigDecimal("100000000"))
                .stockRatio(new BigDecimal("0.90"))
                .build();

        Instrument thyao = Instrument.builder()
                .id(1L)
                .ticker("THYAO")
                .title("Türk Hava Yolları")
                .assetClass(AssetClass.EQUITY)
                .build();

        thyaoHolding = Holding.builder()
                .id(UUID.randomUUID())
                .snapshot(snapshot)
                .instrument(thyao)
                .weightRatio(new BigDecimal("0.5000"))
                .totalValue(new BigDecimal("50000000"))
                .isShort(false)
                .build();

        Instrument kchol = Instrument.builder()
                .id(2L)
                .ticker("KCHOL")
                .title("Koç Holding")
                .assetClass(AssetClass.EQUITY)
                .build();

        kcholHolding = Holding.builder()
                .id(UUID.randomUUID())
                .snapshot(snapshot)
                .instrument(kchol)
                .weightRatio(new BigDecimal("0.4000"))
                .totalValue(new BigDecimal("40000000"))
                .isShort(false)
                .build();

        Instrument repo = Instrument.builder()
                .id(3L)
                .ticker("PP_REPO")
                .title("Takasbank BPP")
                .assetClass(AssetClass.DEPOSIT)
                .build();

        repoHolding = Holding.builder()
                .id(UUID.randomUUID())
                .snapshot(snapshot)
                .instrument(repo)
                .weightRatio(new BigDecimal("0.1000"))
                .totalValue(new BigDecimal("10000000"))
                .isShort(false)
                .build();
    }

    @Test
    @DisplayName("Standart hisse fonunda gün içi getiri, yönetim ücreti düşümü ve HIGH güven seviyesi doğrulanmalı")
    void testCalculateAndSave_StandardEquityFund_HighConfidence() {
        when(fundRepository.findByCode("THF")).thenReturn(Optional.of(fund));
        when(fundSnapshotRepository.findLatestByFundCode(eq("THF"), any(PageRequest.class)))
                .thenReturn(List.of(snapshot));
        when(holdingRepository.findBySnapshotIdWithInstrument(snapshot.getId()))
                .thenReturn(List.of(thyaoHolding, kcholHolding, repoHolding));

        Map<UUID, BigDecimal> driftMap = new HashMap<>();
        driftMap.put(thyaoHolding.getId(), new BigDecimal("0.5000"));
        driftMap.put(kcholHolding.getId(), new BigDecimal("0.4000"));
        driftMap.put(repoHolding.getId(), new BigDecimal("0.1000"));
        when(weightDriftService.getOrCalculateEffectiveWeights(eq(snapshot), anyList(), any(LocalDate.class)))
                .thenReturn(driftMap);

        // THYAO: +%2.00, KCHOL: +%1.00
        MarketPriceDto thyaoPrice = MarketPriceDto.builder()
                .symbol("THYAO")
                .currentPrice(new BigDecimal("300.0"))
                .dailyChangeRatio(new BigDecimal("0.0200"))
                .build();
        MarketPriceDto kcholPrice = MarketPriceDto.builder()
                .symbol("KCHOL")
                .currentPrice(new BigDecimal("200.0"))
                .dailyChangeRatio(new BigDecimal("0.0100"))
                .build();

        when(priceService.getPrices(anyList())).thenReturn(Map.of(
                "THYAO", thyaoPrice,
                "KCHOL", kcholPrice
        ));

        // Dünkü TEFAS fiyatı: 10.0000 TL
        TefasFundDto tefasDto = TefasFundDto.builder()
                .fundCode("THF")
                .unitPrice(new BigDecimal("10.000000"))
                .priceDate(LocalDate.now().minusDays(1))
                .build();
        when(tefasClient.fetchLatestFundPrice("THF")).thenReturn(Optional.of(tefasDto));

        when(estimateRunRepository.save(any(EstimateRun.class))).thenAnswer(i -> {
            EstimateRun run = i.getArgument(0);
            return EstimateRun.builder()
                    .id(UUID.randomUUID())
                    .fund(run.getFund())
                    .snapshot(run.getSnapshot())
                    .estimatedReturn(run.getEstimatedReturn())
                    .coverageRatio(run.getCoverageRatio())
                    .confidenceLevel(run.getConfidenceLevel())
                    .calculatedAt(run.getCalculatedAt())
                    .build();
        });

        FundEstimateDto result = returnCalculationService.calculateAndSave("THF");

        assertThat(result).isNotNull();
        assertThat(result.getFundCode()).isEqualTo("THF");
        // Beklenen getiri: (0.50 * 0.02) + (0.40 * 0.01) + (0.10 * (0.45 / 365)) - (0.0252 / 252)
        assertThat(result.getEstimatedReturn()).isPositive();
        assertThat(result.getConfidenceLevel()).isEqualTo(ConfidenceLevel.HIGH);
        assertThat(result.getCoverageRatio()).isGreaterThanOrEqualTo(new BigDecimal("0.8500"));
        assertThat(result.getCurrentPrice()).isEqualByComparingTo("10.000000");

        verify(estimateRunRepository).save(any(EstimateRun.class));
        verify(estimateDetailRepository).saveAll(anyList());
    }

    @Test
    @DisplayName("Fiyat bulunamadığında kapsama oranı düşmeli ve LOW güven seviyesi atanmalı")
    void testCalculateAndSave_MissingPrices_LowConfidence() {
        when(fundRepository.findByCode("THF")).thenReturn(Optional.of(fund));
        when(fundSnapshotRepository.findLatestByFundCode(eq("THF"), any(PageRequest.class)))
                .thenReturn(List.of(snapshot));
        when(holdingRepository.findBySnapshotIdWithInstrument(snapshot.getId()))
                .thenReturn(List.of(thyaoHolding, kcholHolding));

        Map<UUID, BigDecimal> driftMap = new HashMap<>();
        driftMap.put(thyaoHolding.getId(), new BigDecimal("0.5000"));
        driftMap.put(kcholHolding.getId(), new BigDecimal("0.5000"));
        when(weightDriftService.getOrCalculateEffectiveWeights(eq(snapshot), anyList(), any(LocalDate.class)))
                .thenReturn(driftMap);

        // Fiyat servisi hiçbir fiyat dönmüyor (örn. borsa veri kesintisi)
        when(priceService.getPrices(anyList())).thenReturn(Collections.emptyMap());

        when(estimateRunRepository.save(any(EstimateRun.class))).thenAnswer(i -> {
            EstimateRun run = i.getArgument(0);
            return EstimateRun.builder()
                    .id(UUID.randomUUID())
                    .fund(run.getFund())
                    .snapshot(run.getSnapshot())
                    .estimatedReturn(run.getEstimatedReturn())
                    .coverageRatio(run.getCoverageRatio())
                    .confidenceLevel(run.getConfidenceLevel())
                    .calculatedAt(run.getCalculatedAt())
                    .build();
        });

        FundEstimateDto result = returnCalculationService.calculateAndSave("THF");

        assertThat(result).isNotNull();
        // Hiçbir fiyat kapsanmadığı için coverageRatio = 0.0000
        assertThat(result.getCoverageRatio()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(result.getConfidenceLevel()).isEqualTo(ConfidenceLevel.LOW);
    }

    @Test
    @DisplayName("Geçersiz fon kodu verildiğinde IllegalArgumentException fırlatılmalı")
    void testCalculateAndSave_InvalidFund_ThrowsException() {
        when(fundRepository.findByCode("INVALID")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> returnCalculationService.calculateAndSave("INVALID"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("bulunamadı");
    }
}
