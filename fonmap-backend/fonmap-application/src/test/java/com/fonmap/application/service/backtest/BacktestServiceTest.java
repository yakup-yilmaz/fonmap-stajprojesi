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
                                weightDriftService);

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
                                TefasFundDto.builder().fundCode("THF").priceDate(date1)
                                                .unitPrice(new BigDecimal("10.000000")).build(),
                                TefasFundDto.builder().fundCode("THF").priceDate(date2)
                                                .unitPrice(new BigDecimal("10.100000")).build(), // +%1.0
                                TefasFundDto.builder().fundCode("THF").priceDate(date3)
                                                .unitPrice(new BigDecimal("10.302000")).build() // +%2.0
                );

                when(tefasClient.fetchHistoricalPrices(eq("THF"), any(), any())).thenReturn(new ArrayList<>(tefasList));

                // 11 Ağustos simülasyonunda 31 Temmuz snapshot'ı gelmeli
                when(fundSnapshotRepository.findLatestByFundCodeAndDateBeforeOrEqual(eq("THF"), eq(date2),
                                any(Pageable.class)))
                                .thenReturn(List.of(julySnapshot));
                when(holdingRepository.findBySnapshotIdWithInstrument(julySnapshot.getId()))
                                .thenReturn(List.of(holdingJuly));

                // 5 Eylül simülasyonunda 31 Ağustos snapshot'ı gelmeli
                when(fundSnapshotRepository.findLatestByFundCodeAndDateBeforeOrEqual(eq("THF"), eq(date3),
                                any(Pageable.class)))
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

                // Fiyatlar: date2 için +%1.0079, date3 için +%2.0079 (günlük gider kesintisi
                // %0.0079 düşünce net tam otursun)
                Map<LocalDate, Map<String, MarketPriceDto>> priceOverride = new HashMap<>();

                Map<String, MarketPriceDto> day2Map = new HashMap<>();
                day2Map.put("THYAO", MarketPriceDto.builder().symbol("THYAO")
                                .dailyChangeRatio(new BigDecimal("0.010079")).build());
                priceOverride.put(date2, day2Map);

                Map<String, MarketPriceDto> day3Map = new HashMap<>();
                day3Map.put("THYAO", MarketPriceDto.builder().symbol("THYAO")
                                .dailyChangeRatio(new BigDecimal("0.020079")).build());
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

                FundSnapshot snapshot = FundSnapshot.builder().id(UUID.randomUUID()).fund(fund).snapshotDate(date1)
                                .build();
                Holding holding = Holding.builder().id(UUID.randomUUID()).snapshot(snapshot).instrument(thyaoInstrument)
                                .weightRatio(BigDecimal.ONE).build();

                when(fundRepository.findByCode("THF")).thenReturn(Optional.of(fund));

                List<TefasFundDto> tefasList = List.of(
                                TefasFundDto.builder().priceDate(date1).unitPrice(new BigDecimal("10.00")).build(),
                                TefasFundDto.builder().priceDate(date2).unitPrice(new BigDecimal("10.10")).build(), // Gerçek:
                                                                                                                    // +%1.0000
                                TefasFundDto.builder().priceDate(date3).unitPrice(new BigDecimal("10.2010")).build() // Gerçek:
                                                                                                                     // +%1.0000
                );
                when(tefasClient.fetchHistoricalPrices(any(), any(), any())).thenReturn(new ArrayList<>(tefasList));

                when(fundSnapshotRepository.findLatestByFundCodeAndDateBeforeOrEqual(any(), any(), any(Pageable.class)))
                                .thenReturn(List.of(snapshot));
                when(holdingRepository.findBySnapshotIdWithInstrument(any())).thenReturn(List.of(holding));

                DriftedHoldingDto driftedDto = DriftedHoldingDto.builder().holdingId(holding.getId()).ticker("THYAO")
                                .effectiveWeight(BigDecimal.ONE).build();
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

        @Test
        @DisplayName("Günlük Ağırlık Kayması (Weight Drift) Doğrulama Testi: 2. Gün %50-%50 yerine %55-%45 kullanılmalıdır")
        void shouldTrackDailyWeightDriftAccurately() {
                LocalDate snapDate = LocalDate.of(2026, 8, 31);
                FundSnapshot snapshot = FundSnapshot.builder().id(UUID.randomUUID()).fund(fund).snapshotDate(snapDate)
                                .build();

                Instrument ereglInstrument = Instrument.builder().id(2L).ticker("EREGL").assetClass(AssetClass.EQUITY)
                                .build();

                Holding hThyao = Holding.builder().id(UUID.randomUUID()).snapshot(snapshot).instrument(thyaoInstrument)
                                .weightRatio(new BigDecimal("0.5000")).build();
                Holding hEregl = Holding.builder().id(UUID.randomUUID()).snapshot(snapshot).instrument(ereglInstrument)
                                .weightRatio(new BigDecimal("0.5000")).build();

                when(fundRepository.findByCode("THF")).thenReturn(Optional.of(fund));
                when(fundSnapshotRepository.findLatestByFundCodeAndDateBefore(eq("THF"), any(), any()))
                                .thenReturn(List.of(snapshot));
                when(holdingRepository.findBySnapshotIdWithInstrument(snapshot.getId()))
                                .thenReturn(List.of(hThyao, hEregl));

                LocalDate d0 = LocalDate.of(2026, 9, 1);
                LocalDate d1 = LocalDate.of(2026, 9, 2);
                LocalDate d2 = LocalDate.of(2026, 9, 3);

                List<TefasFundDto> tefasList = List.of(
                                TefasFundDto.builder().priceDate(d0).unitPrice(new BigDecimal("100.00")).build(),
                                TefasFundDto.builder().priceDate(d1).unitPrice(new BigDecimal("100.00")).build(),
                                TefasFundDto.builder().priceDate(d2).unitPrice(new BigDecimal("102.75")).build());
                when(tefasClient.fetchHistoricalPrices(any(), any(), any())).thenReturn(new ArrayList<>(tefasList));

                // Gün 1: THYAO +%10, EREGL -%10 -> Gün sonu portföy: 55 TL + 45 TL = 100 TL.
                // Gün 2 açılış ağırlıkları: THYAO %55, EREGL %45 olmalıdır!
                Map<LocalDate, Map<String, MarketPriceDto>> priceOverride = new HashMap<>();

                Map<String, MarketPriceDto> pD1 = new HashMap<>();
                pD1.put("THYAO", MarketPriceDto.builder().dailyChangeRatio(new BigDecimal("0.1000")).build());
                pD1.put("EREGL", MarketPriceDto.builder().dailyChangeRatio(new BigDecimal("-0.1000")).build());
                priceOverride.put(d1, pD1);

                // Gün 2: THYAO +%5, EREGL %0
                // Eğer ağırlık sabit kalsaydı (%50-%50) brüt getiri: 0.5*0.05 + 0.5*0 = %2.50
                // olurdu.
                // Ağırlık kayması ile (%55-%45) brüt getiri: 0.55*0.05 + 0.45*0 = %2.75 olur!
                Map<String, MarketPriceDto> pD2 = new HashMap<>();
                pD2.put("THYAO", MarketPriceDto.builder().dailyChangeRatio(new BigDecimal("0.0500")).build());
                pD2.put("EREGL", MarketPriceDto.builder().dailyChangeRatio(BigDecimal.ZERO).build());
                priceOverride.put(d2, pD2);

                BacktestReportDto report = backtestService.runBacktest("THF", d0, d2, priceOverride);

                assertThat(report.getTotalDaysTested()).isEqualTo(2);
                BacktestReportDto.DailyBacktestRecord day2Record = report.getDailyRecords().get(1);

                // Gün 2 brüt getirisinin %2.50 DEĞİL, %2.75 civarında (günlük gider hariç)
                // olduğunu doğrula
                // %2.75 - (%2.0/365 * 1 gün = %0.005479) ~= %2.744521
                assertThat(day2Record.getEstimatedReturn()).isGreaterThan(new BigDecimal("0.0260"));
                assertThat(day2Record.getToleranceStatus()).isEqualTo("KUSURSUZ 🟢");

                // Kümülatif Getiri Eğrisi Testi (Equity Curve)
                assertThat(day2Record.getCumulativeEstimatedReturn()).isNotNull();
                assertThat(day2Record.getCumulativeActualReturn()).isNotNull();
                assertThat(day2Record.getCumulativeActualReturn()).isEqualByComparingTo(new BigDecimal("0.027500"));
        }

        @Test
        @DisplayName("Zincir Bütünlüğü Kalkanı: En güncel rapor eski kalmışsa simülasyon güvenli sınırda durdurulmalı ve uyarı vermelidir")
        void shouldClampSimulationEndDateWhenLatestSnapshotIsStale() {
                // En güncel snapshot 31 Ağustos 2026 (Bugün üzerinden > 35 gün geçmiş)
                LocalDate snapDate = LocalDate.of(2026, 8, 31);
                FundSnapshot staleSnapshot = FundSnapshot.builder()
                                .id(UUID.randomUUID())
                                .fund(fund)
                                .snapshotDate(snapDate)
                                .build();

                when(fundRepository.findByCode("THF")).thenReturn(Optional.of(fund));
                when(fundSnapshotRepository.findAllByFundCodeOrderByDateDesc("THF")).thenReturn(List.of(staleSnapshot));

                LocalDate requestedEnd = LocalDate.of(2026, 10, 15);
                LocalDate clampedEnd = snapDate.plusMonths(1); // 2026-09-30

                TefasFundDto tefasD0 = TefasFundDto.builder().fundCode("THF").priceDate(LocalDate.of(2026, 9, 1))
                                .unitPrice(new BigDecimal("10.0")).build();
                TefasFundDto tefasD1 = TefasFundDto.builder().fundCode("THF").priceDate(clampedEnd)
                                .unitPrice(new BigDecimal("10.5")).build();
                when(tefasClient.fetchHistoricalPrices(eq("THF"), eq(LocalDate.of(2026, 9, 1)), eq(clampedEnd)))
                                .thenReturn(new ArrayList<>(List.of(tefasD0, tefasD1)));

                when(fundSnapshotRepository.findLatestByFundCodeAndDateBefore(eq("THF"), any(LocalDate.class),
                                any(Pageable.class)))
                                .thenReturn(List.of(staleSnapshot));
                when(holdingRepository.findBySnapshotIdWithInstrument(any())).thenReturn(Collections.emptyList());

                BacktestReportDto report = backtestService.runBacktest("THF", LocalDate.of(2026, 9, 1), requestedEnd,
                                Collections.emptyMap());

                assertThat(report.getChainStatus())
                                .isEqualTo(BacktestReportDto.ChainIntegrityStatus.MISSING_LATEST_SNAPSHOT);
                assertThat(report.isRequiresNewerSnapshot()).isTrue();
                assertThat(report.getEndDate()).isEqualTo(clampedEnd);
                assertThat(report.getIntegrityWarningMessage())
                                .contains("Sistemdeki en güncel rapor '2026-08-31' tarihlidir");
        }

        @Test
        @DisplayName("Zincir Bütünlüğü Kalkanı: İki snapshot arasında atlanmış bir ay varsa INTERMEDIATE_GAP_DETECTED tespit edilmelidir")
        void shouldDetectIntermediateGapBetweenSnapshots() {
                // 31 Temmuz ve 30 Eylül snapshot'ları (Ağustos eksik, aralarında 61 gün var)
                LocalDate snapJuly = LocalDate.of(2026, 7, 31);
                LocalDate snapSep = LocalDate.of(2026, 9, 30);

                FundSnapshot s1 = FundSnapshot.builder().id(UUID.randomUUID()).fund(fund).snapshotDate(snapJuly)
                                .build();
                FundSnapshot s2 = FundSnapshot.builder().id(UUID.randomUUID()).fund(fund).snapshotDate(snapSep).build();

                when(fundRepository.findByCode("THF")).thenReturn(Optional.of(fund));
                when(fundSnapshotRepository.findAllByFundCodeOrderByDateDesc("THF")).thenReturn(List.of(s2, s1));

                LocalDate d0 = LocalDate.of(2026, 8, 1);
                LocalDate d1 = LocalDate.of(2026, 8, 2);

                TefasFundDto tefasD0 = TefasFundDto.builder().fundCode("THF").priceDate(d0)
                                .unitPrice(new BigDecimal("10.0")).build();
                TefasFundDto tefasD1 = TefasFundDto.builder().fundCode("THF").priceDate(d1)
                                .unitPrice(new BigDecimal("10.2")).build();
                when(tefasClient.fetchHistoricalPrices(eq("THF"), any(LocalDate.class), any(LocalDate.class)))
                                .thenReturn(new ArrayList<>(List.of(tefasD0, tefasD1)));

                when(fundSnapshotRepository.findLatestByFundCodeAndDateBefore(eq("THF"), any(LocalDate.class),
                                any(Pageable.class)))
                                .thenReturn(List.of(s1));
                when(holdingRepository.findBySnapshotIdWithInstrument(any())).thenReturn(Collections.emptyList());

                BacktestReportDto report = backtestService.runBacktest("THF", d0, d1, Collections.emptyMap());

                assertThat(report.getChainStatus())
                                .isEqualTo(BacktestReportDto.ChainIntegrityStatus.INTERMEDIATE_GAP_DETECTED);
                assertThat(report.getIntegrityWarningMessage()).contains("Ara dönem raporu eksik");
        }

        @Test
        @DisplayName("Toplu Backtest: Sistemdeki tüm aktif fonlar sırayla simüle edilmeli ve konsolide özet dönmelidir")
        void shouldRunAllBacktestsSuccessfully() {
                when(fundRepository.findAllActiveFundsOrdered()).thenReturn(List.of(fund));
                when(fundRepository.findByCode("THF")).thenReturn(Optional.of(fund));
                when(fundSnapshotRepository.findAllByFundCodeOrderByDateDesc("THF"))
                                .thenReturn(Collections.emptyList());
                when(fundSnapshotRepository.findLatestByFundCode(eq("THF"), any(Pageable.class)))
                                .thenReturn(Collections.emptyList());

                com.fonmap.application.service.backtest.dto.BatchBacktestResultDto batchResult = backtestService
                                .runAllBacktests(true);

                assertThat(batchResult).isNotNull();
                assertThat(batchResult.getTotalFunds()).isEqualTo(1);
                assertThat(batchResult.getResults()).hasSize(1);
                assertThat(batchResult.getResults().get(0).getFundCode()).isEqualTo("THF");
        }
}
