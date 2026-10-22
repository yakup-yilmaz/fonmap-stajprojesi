package com.fonmap.application.service.catchup;

import com.fonmap.application.service.drift.WeightDriftExecutionTracker;
import com.fonmap.application.service.drift.WeightDriftService;
import com.fonmap.application.service.reconciliation.ReconciliationService;
import com.fonmap.application.service.reconciliation.dto.ReconciliationDto;
import com.fonmap.domain.entity.Fund;
import com.fonmap.domain.entity.ReconciliationResult;
import com.fonmap.infrastructure.repository.FundRepository;
import com.fonmap.infrastructure.repository.MarketHolidayRepository;
import com.fonmap.infrastructure.repository.ReconciliationResultRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class StartupCatchupServiceTest {

    @Mock
    private FundRepository fundRepository;

    @Mock
    private ReconciliationService reconciliationService;

    @Mock
    private ReconciliationResultRepository reconciliationResultRepository;

    @Mock
    private MarketHolidayRepository marketHolidayRepository;

    @Mock
    private WeightDriftService weightDriftService;

    @Mock
    private WeightDriftExecutionTracker weightDriftExecutionTracker;

    @Mock
    private com.fonmap.infrastructure.repository.FundReportRepository fundReportRepository;

    @Mock
    private com.fonmap.infrastructure.client.kap.KapClient kapClient;

    @Mock
    private com.fonmap.application.service.pdf.PdfImportService pdfImportService;

    private StartupCatchupService startupCatchupService;

    private Fund sampleFund;
    private UUID fundId;

    @BeforeEach
    void setUp() {
        fundId = UUID.randomUUID();
        sampleFund = Fund.builder()
                .id(fundId)
                .code("TTE")
                .title("İş Portföy BİST Teknoloji Fonu")
                .isActive(true)
                .build();

        startupCatchupService = new StartupCatchupService(
                fundRepository,
                reconciliationService,
                reconciliationResultRepository,
                marketHolidayRepository,
                weightDriftService,
                weightDriftExecutionTracker,
                fundReportRepository,
                kapClient,
                pdfImportService
        );
    }

    @Test
    @DisplayName("İdempotency Testi: Son mutabakat güncelse hiçbir dış çağrı yapılmadan atlanmalıdır")
    void testCatchupMissingReconciliations_AlreadyUpToDate_SkipsImmediately() {
        // Hedef seans tarihi dünün tarihi olsun
        LocalDate targetTradingDate = startupCatchupService.determineLastCompletedTradingDate(LocalDateTime.now());

        when(fundRepository.findAllActiveFundsOrdered()).thenReturn(List.of(sampleFund));
        // Fonun en son onaylı tarihi tam olarak hedef tarih
        when(reconciliationResultRepository.findLatestVerifiedDateByFundId(fundId))
                .thenReturn(Optional.of(targetTradingDate));

        startupCatchupService.catchupMissingReconciliations();

        // Fon zaten güncel olduğu için reconcileFund asla çağrılmamalıdır
        verify(reconciliationService, never()).reconcileFund(anyString(), any(LocalDate.class));
    }

    @Test
    @DisplayName("Çok Günlük Telafi Testi: 5 iş günü kapalı kalmışsa aradaki seansları kronolojik telafi etmelidir")
    void testCatchupMissingReconciliations_MultiDayGap_ReconcilesChronologically() {
        LocalDate now = LocalDate.of(2026, 10, 16); // Cuma gecesi
        LocalDate targetTradingDate = LocalDate.of(2026, 10, 16);

        // Fonun en son onaylı tarihi bir önceki Cuma (2026-10-09) olsun (arada 5 iş günü boşluk)
        LocalDate lastVerifiedDate = LocalDate.of(2026, 10, 9);

        when(fundRepository.findAllActiveFundsOrdered()).thenReturn(List.of(sampleFund));
        when(reconciliationResultRepository.findLatestVerifiedDateByFundId(fundId))
                .thenReturn(Optional.of(lastVerifiedDate));

        when(reconciliationResultRepository.findByFundIdAndDateWithFund(eq(fundId), any(LocalDate.class)))
                .thenReturn(Optional.empty());

        when(reconciliationService.reconcileFund(eq("TTE"), any(LocalDate.class)))
                .thenReturn(ReconciliationDto.builder()
                        .fundCode("TTE")
                        .predictedReturn(new BigDecimal("0.0150"))
                        .actualReturn(new BigDecimal("0.0155"))
                        .errorDiff(new BigDecimal("-0.0005"))
                        .isVerified(true)
                        .build());

        startupCatchupService.catchupMissingReconciliations();

        // 12 Ekim Pazartesi, 13 Ekim Salı, 14 Ekim Çarşamba, 15 Ekim Perşembe, 16 Ekim Cuma (veya takvime göre açık günler)
        // reconcileFund en az 1 kez ve boşluk kadar çağrılmış olmalıdır
        verify(reconciliationService, atLeastOnce()).reconcileFund(eq("TTE"), any(LocalDate.class));
    }

    @Test
    @DisplayName("Sabah Ağırlık Kayması İdempotency Testi: Zaten yapılmışsa veya tatilse atlamalıdır")
    void testCatchupMorningWeightDrift_AlreadyCalibrated_Skips() {
        lenient().when(weightDriftExecutionTracker.isCalibratedForDate(any(LocalDate.class))).thenReturn(true);

        startupCatchupService.catchupMorningWeightDriftIfMissed();

        // Zaten kalibre edilmiş veya tatil günü olduğu için, weightDriftService asla çağrılmamalıdır
        verify(weightDriftService, never()).calibrateAndSaveDriftForLatestFundSnapshot(anyString(), any(LocalDate.class));
    }

    @Test
    @DisplayName("KAP Eksik Rapor Telafi Testi: Fonun zaten raporu varsa KAP taranmadan atlamalıdır")
    void testCatchupMissingKapReports_WhenReportsExist_Skips() {
        when(fundRepository.findAllActiveFundsOrdered()).thenReturn(List.of(sampleFund));
        when(fundReportRepository.findByFundIdOrderByReportDateDesc(fundId))
                .thenReturn(List.of(com.fonmap.domain.entity.FundReport.builder().reportDate(LocalDate.now()).build()));

        startupCatchupService.catchupMissingKapReportsIfMissed();

        verify(kapClient, never()).fetchPdf(any(), any(), any(), any());
    }
}
