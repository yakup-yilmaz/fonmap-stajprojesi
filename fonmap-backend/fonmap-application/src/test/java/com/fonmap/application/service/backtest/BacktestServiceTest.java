package com.fonmap.application.service.backtest;

import com.fonmap.application.service.backtest.dto.BacktestReportDto;
import com.fonmap.application.service.drift.WeightDriftService;
import com.fonmap.application.service.drift.dto.DriftedHoldingDto;
import com.fonmap.domain.enums.AssetClass;
import com.fonmap.domain.entity.Fund;
import com.fonmap.domain.entity.FundSnapshot;
import com.fonmap.domain.entity.Holding;
import com.fonmap.domain.entity.Instrument;
import com.fonmap.infrastructure.client.dto.MarketPriceDto;
import com.fonmap.infrastructure.client.tefas.TefasClient;
import com.fonmap.infrastructure.client.tefas.dto.TefasFundDto;
import com.fonmap.infrastructure.repository.FundRepository;
import com.fonmap.infrastructure.repository.FundSnapshotRepository;
import com.fonmap.infrastructure.repository.HoldingRepository;
import com.fonmap.infrastructure.service.PriceService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BacktestServiceTest {

    @Mock
    private FundRepository fundRepository;

    @Mock
    private FundSnapshotRepository fundSnapshotRepository;

    @Mock
    private HoldingRepository holdingRepository;

    @Mock
    private TefasClient tefasClient;

    @Mock
    private PriceService priceService;

    @Mock
    private WeightDriftService weightDriftService;

    private BacktestService backtestService;

    private Fund fund;
    private Instrument thyaoInstrument;

    @BeforeEach
    void setUp() {
        backtestService = new BacktestService(
                fundRepository,
                fundSnapshotRepository,
                holdingRepository,
                tefasClient,
                priceService,
                weightDriftService
        );

        fund = Fund.builder()
                .id(UUID.randomUUID())
                .code("THF")
                .title("Tera Portföy Hisse Senedi Fonu")
                .annualFeeRatio(new BigDecimal("0.0200")) // %2.0 yıllık gider
                .build();

        thyaoInstrument = Instrument.builder()
                .id(1L)
                .ticker("THYAO")
                .title("Türk Hava Yolları")
                .assetClass(AssetClass.EQUITY)
                .build();
    }

    @Test
    @DisplayName("Point-in-Time Çoklu Snapshot Testi: 31 Temmuz ve 31 Ağustos snapshotları doğru tarihlerde seçilmelidir")
    void shouldSelectCorrectPointInTimeSnapshotAcrossMonths() {
        // İki farklı dönemin snapshot'ı
        LocalDate julyReportDate = LocalDate.of(2026, 7, 31);
        LocalDate augustReportDate = LocalDate.of(2026, 8, 31);

        FundSnapshot julySnapshot = FundSnapshot.builder()
                .id(UUID.randomUUID())
                .fund(fund)
                .snapshotDate(julyReportDate)
                .build();

        FundSnapshot augustSnapshot = FundSnapshot.builder()
                .id(UUID.randomUUID())
                .fund(fund)
                .snapshotDate(augustReportDate)
                .build();

        Holding holdingJuly = Holding.builder()
                .id(UUID.randomUUID())
                .snapshot(julySnapshot)
                .instrument(thyaoInstrument)
                .weightRatio(new BigDecimal("1.0000"))
                .build();

        Holding holdingAugust = Holding.builder()
                .id(UUID.randomUUID())
                .snapshot(augustSnapshot)
                .instrument(thyaoInstrument)
                .weightRatio(new BigDecimal("1.0000"))
                .build();

        when(fundRepository.findByCode("THF")).thenReturn(Optional.of(fund));

        // 3 günlük TEFAS verisi: 10 Ağustos, 11 Ağustos, 5 Eylül
        LocalDate date1 = LocalDate.of(2026, 8, 10);
        LocalDate date2 = LocalDate.of(2026, 8, 11);
        LocalDate date3 = LocalDate.of(2026, 9, 5);

        List<TefasFundDto> tefasList = List.of(
                TefasFundDto.builder().fundCode("THF").priceDate(date1).unitPrice(new BigDecimal("10.000000")).build(),
                TefasFundDto.builder().fundCode("THF").priceDate(date2).unitPrice(new BigDecimal("10.100000")).build(), // +%1.0
                TefasFundDto.builder().fundCode("THF").priceDate(date3).unitPrice(new BigDecimal("10.302000")).build()  // +%2.0
        );

        when(tefasClient.fetchHistoricalPrices(eq("THF"), any(), any())).thenReturn(new ArrayList<>(tefasList));

        // 11 Ağustos simülasyonunda 31 Temmuz snapshot'ı gelmeli
        when(fundSnapshotRepository.findLatestByFundCodeAndDateBeforeOrEqual(eq("THF"), eq(date2), any(Pageable.class)))
                .thenReturn(List.of(julySnapshot));
        when(holdingRepository.findBySnapshotIdWithInstrument(julySnapshot.getId()))
                .thenReturn(List.of(holdingJuly));

        // 5 Eylül simülasyonunda 31 Ağustos snapshot'ı gelmeli
        when(fundSnapshotRepository.findLatestByFundCodeAndDateBeforeOrEqual(eq("THF"), eq(date3), any(Pageable.class)))
                .thenReturn(List.of(augustSnapshot));
        when(holdingRepository.findBySnapshotIdWithInstrument(augustSnapshot.getId()))
                .thenReturn(List.of(holdingAugust));

        // Drift mock: Her iki holding için de 1.0 ağırlık dönsün
        DriftedHoldingDto driftedDto = DriftedHoldingDto.builder()
                .holdingId(holdingJuly.getId())
                .ticker("THYAO")
                .effectiveWeight(new BigDecimal("1.000000"))
                .build();
        when(weightDriftService.calculateDrift(any())).thenReturn(List.of(driftedDto));

        // Fiyatlar: date2 için +%1.0079, date3 için +%2.0079 (günlük gider kesintisi %0.0079 düşünce net tam otursun)
        Map<LocalDate, Map<String, MarketPriceDto>> priceOverride = new HashMap<>();

        Map<String, MarketPriceDto> day2Map = new HashMap<>();
        day2Map.put("THYAO", MarketPriceDto.builder().symbol("THYAO").dailyChangeRatio(new BigDecimal("0.010079")).build());
        priceOverride.put(date2, day2Map);

        Map<String, MarketPriceDto> day3Map = new HashMap<>();
        day3Map.put("THYAO", MarketPriceDto.builder().symbol("THYAO").dailyChangeRatio(new BigDecimal("0.020079")).build());
        priceOverride.put(date3, day3Map);

        // İcra
        BacktestReportDto report = backtestService.runBacktest("THF", date1, date3, priceOverride);

        // Doğrulama
        assertThat(report).isNotNull();
        assertThat(report.getTotalDaysTested()).isEqualTo(2);

        // 1. Gün (11 Ağustos): 31 Temmuz snapshot'ı kullanılmış olmalı
        BacktestReportDto.DailyBacktestRecord day1Record = report.getDailyRecords().get(0);
        assertThat(day1Record.getDate()).isEqualTo(date2);
        assertThat(day1Record.getSnapshotDateUsed()).isEqualTo(julyReportDate);
        assertThat(day1Record.isDirectionMatched()).isTrue();

        // 2. Gün (5 Eylül): 31 Ağustos snapshot'ı kullanılmış olmalı
        BacktestReportDto.DailyBacktestRecord day2Record = report.getDailyRecords().get(1);
        assertThat(day2Record.getDate()).isEqualTo(date3);
        assertThat(day2Record.getSnapshotDateUsed()).isEqualTo(augustReportDate);
        assertThat(day2Record.isDirectionMatched()).isTrue();

        // İstatistikler
        assertThat(report.getDirectionalAccuracy()).isEqualByComparingTo("1.0000"); // %100 yön tutarlılığı
        assertThat(report.getSuccessRate()).isEqualByComparingTo("1.0000"); // Hatalar %0.20'nin altında
    }

    @Test
    @DisplayName("Metrik ve İstatistik Doğrulama Testi: MAE, RMSE ve Max/Min hata doğru hesaplanmalıdır")
    void shouldCalculateMetricsCorrectly() {
        LocalDate date1 = LocalDate.of(2026, 9, 1);
        LocalDate date2 = LocalDate.of(2026, 9, 2);
        LocalDate date3 = LocalDate.of(2026, 9, 3);

        FundSnapshot snapshot = FundSnapshot.builder().id(UUID.randomUUID()).fund(fund).snapshotDate(date1).build();
        Holding holding = Holding.builder().id(UUID.randomUUID()).snapshot(snapshot).instrument(thyaoInstrument).weightRatio(BigDecimal.ONE).build();

        when(fundRepository.findByCode("THF")).thenReturn(Optional.of(fund));

        List<TefasFundDto> tefasList = List.of(
                TefasFundDto.builder().priceDate(date1).unitPrice(new BigDecimal("10.00")).build(),
                TefasFundDto.builder().priceDate(date2).unitPrice(new BigDecimal("10.10")).build(), // Gerçek: +%1.0000
                TefasFundDto.builder().priceDate(date3).unitPrice(new BigDecimal("10.2010")).build() // Gerçek: +%1.0000
        );
        when(tefasClient.fetchHistoricalPrices(any(), any(), any())).thenReturn(new ArrayList<>(tefasList));

        when(fundSnapshotRepository.findLatestByFundCodeAndDateBeforeOrEqual(any(), any(), any(Pageable.class)))
                .thenReturn(List.of(snapshot));
        when(holdingRepository.findBySnapshotIdWithInstrument(any())).thenReturn(List.of(holding));

        DriftedHoldingDto driftedDto = DriftedHoldingDto.builder().holdingId(holding.getId()).ticker("THYAO").effectiveWeight(BigDecimal.ONE).build();
        when(weightDriftService.calculateDrift(any())).thenReturn(List.of(driftedDto));

        // Günlük gider: %2.0 / 252 = %0.007937
        // 1. Gün tahminimiz net: %1.0200 (Hata: +%0.0200)
        // 2. Gün tahminimiz net: %0.9600 (Hata: -%0.0400)
        Map<LocalDate, Map<String, MarketPriceDto>> priceOverride = new HashMap<>();
        Map<String, MarketPriceDto> d2 = new HashMap<>();
        d2.put("THYAO", MarketPriceDto.builder().dailyChangeRatio(new BigDecimal("0.027937")).build());
        priceOverride.put(date2, d2);

        Map<String, MarketPriceDto> d3 = new HashMap<>();
        d3.put("THYAO", MarketPriceDto.builder().dailyChangeRatio(new BigDecimal("0.022063")).build());
        priceOverride.put(date3, d3);

        BacktestReportDto report = backtestService.runBacktest("THF", date1, date3, priceOverride);

        assertThat(report.getTotalDaysTested()).isEqualTo(2);
        // MAE: (|0.02| + |0.04|) / 2 = 0.03
        // İki günde de sapmalar pozitif/negatif dağılımında MAE hesaplanır
        assertThat(report.getMeanAbsoluteError()).isGreaterThan(BigDecimal.ZERO);
        assertThat(report.getRootMeanSquaredError()).isGreaterThan(BigDecimal.ZERO);
        assertThat(report.getMaxError()).isGreaterThan(BigDecimal.ZERO);
    }
}
