package com.fonmap.api.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fonmap.application.service.backtest.BacktestCacheService;
import com.fonmap.application.service.backtest.BacktestService;
import com.fonmap.application.service.pdf.PdfImportService;
import com.fonmap.application.service.reconciliation.ReconciliationService;
import com.fonmap.domain.entity.Fund;
import com.fonmap.domain.entity.FundReport;
import com.fonmap.domain.entity.FundSnapshot;
import com.fonmap.domain.entity.Holding;
import com.fonmap.infrastructure.client.kap.KapClient;
import com.fonmap.infrastructure.client.kap.dto.KapPdfDto;
import com.fonmap.infrastructure.parser.dto.ParsedPortfolioReportDto;
import com.fonmap.infrastructure.repository.FundReportRepository;
import com.fonmap.infrastructure.repository.FundRepository;
import com.fonmap.infrastructure.repository.FundSnapshotRepository;
import com.fonmap.infrastructure.repository.HoldingRepository;
import com.fonmap.infrastructure.repository.InstrumentAliasRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@ExtendWith(MockitoExtension.class)
class AdminControllerTest {

    private MockMvc mockMvc;
    private ObjectMapper objectMapper;

    @Mock
    private PdfImportService pdfImportService;
    @Mock
    private BacktestService backtestService;
    @Mock
    private ReconciliationService reconciliationService;
    @Mock
    private InstrumentAliasRepository instrumentAliasRepository;
    @Mock
    private KapClient kapClient;
    @Mock
    private FundRepository fundRepository;
    @Mock
    private FundSnapshotRepository fundSnapshotRepository;
    @Mock
    private FundReportRepository fundReportRepository;
    @Mock
    private HoldingRepository holdingRepository;
    @Mock
    private com.fonmap.infrastructure.repository.EstimateRunRepository estimateRunRepository;
    @Mock
    private BacktestCacheService backtestCacheService;
    @Mock
    private com.fonmap.infrastructure.client.tefas.TefasClient tefasClient;

    @InjectMocks
    private AdminController adminController;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        objectMapper.registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule());
        objectMapper.disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

        org.springframework.http.converter.json.MappingJackson2HttpMessageConverter converter =
                new org.springframework.http.converter.json.MappingJackson2HttpMessageConverter(objectMapper);

        mockMvc = MockMvcBuilders.standaloneSetup(adminController)
                .setMessageConverters(converter)
                .build();
    }

    @Test
    @DisplayName("PUT /funds/{code} - Geçerli güncelleme başarılı olmalı (200 OK)")
    void testUpdateFund_Success() throws Exception {
        Fund existing = Fund.builder()
                .id(UUID.randomUUID())
                .code("THF")
                .title("Eski Başlık")
                .manager("Eski Yönetici")
                .annualFeeRatio(new BigDecimal("0.0200"))
                .isActive(true)
                .displayOrder(1)
                .build();

        when(fundRepository.findByCode("THF")).thenReturn(Optional.of(existing));
        when(fundRepository.save(any(Fund.class))).thenAnswer(invocation -> invocation.getArgument(0));

        AdminController.UpdateFundRequest request = new AdminController.UpdateFundRequest(
                "Yeni Başlık",
                "Yeni Yönetici A.Ş.",
                new BigDecimal("0.0250"),
                true,
                5
        );

        mockMvc.perform(put("/api/v1/admin/funds/THF")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("THF"))
                .andExpect(jsonPath("$.title").value("Yeni Başlık"))
                .andExpect(jsonPath("$.manager").value("Yeni Yönetici A.Ş."))
                .andExpect(jsonPath("$.annualFeeRatio").value(0.0250));

        verify(fundRepository).save(existing);
    }

    @Test
    @DisplayName("PUT /funds/{code} - 1'den büyük veya negatif ücret oranı HTTP 400 Bad Request dönmeli")
    void testUpdateFund_InvalidFeeRatio_Throws400() throws Exception {
        Fund existing = Fund.builder()
                .code("THF")
                .title("THF Fonu")
                .build();

        when(fundRepository.findByCode("THF")).thenReturn(Optional.of(existing));

        AdminController.UpdateFundRequest invalidRequest = new AdminController.UpdateFundRequest(
                null, null, new BigDecimal("1.5000"), null, null
        );

        mockMvc.perform(put("/api/v1/admin/funds/THF")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(invalidRequest)))
                .andExpect(status().isBadRequest());

        verify(fundRepository, never()).save(any());
    }

    @Test
    @DisplayName("DELETE /funds/{code} - Fonu pasife almalı ve backtest cache'ini temizlemeli")
    void testDeleteFund_SoftDeleteAndCacheEvict() throws Exception {
        Fund existing = Fund.builder()
                .id(UUID.randomUUID())
                .code("TTE")
                .title("İş Portföy Teknoloji")
                .isActive(true)
                .build();

        when(fundRepository.findByCode("TTE")).thenReturn(Optional.of(existing));

        mockMvc.perform(delete("/api/v1/admin/funds/TTE"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.fundCode").value("TTE"));

        verify(fundRepository).save(argThat(f -> !f.getIsActive()));
        verify(backtestCacheService).evict("TTE");
    }

    @Test
    @DisplayName("DELETE /funds/{code}/reports/{reportId} - Rapor, ilişkili snapshot ve holdingleri temizleyip cache boşaltmalı")
    void testDeleteReport_CascadeAndCacheEvict() throws Exception {
        UUID reportId = UUID.randomUUID();
        UUID snapshotId = UUID.randomUUID();

        Fund fund = Fund.builder().code("THF").build();
        FundReport report = FundReport.builder()
                .id(reportId)
                .fund(fund)
                .reportDate(LocalDate.of(2026, 8, 31))
                .build();

        FundSnapshot snapshot = FundSnapshot.builder()
                .id(snapshotId)
                .fund(fund)
                .report(report)
                .build();

        Holding holding = Holding.builder()
                .id(UUID.randomUUID())
                .snapshot(snapshot)
                .weightRatio(new BigDecimal("0.10"))
                .totalValue(new BigDecimal("1000000"))
                .build();

        when(fundReportRepository.findById(reportId)).thenReturn(Optional.of(report));
        when(fundSnapshotRepository.findByReportId(reportId)).thenReturn(Optional.of(snapshot));
        when(holdingRepository.findBySnapshotIdWithInstrument(snapshotId)).thenReturn(List.of(holding));

        mockMvc.perform(delete("/api/v1/admin/funds/THF/reports/" + reportId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.reportId").value(reportId.toString()));

        verify(estimateRunRepository).deleteBySnapshotId(snapshotId);
        verify(holdingRepository).deleteAll(List.of(holding));
        verify(fundSnapshotRepository).delete(snapshot);
        verify(fundReportRepository).delete(report);
        verify(backtestCacheService).evict("THF");
    }

    @Test
    @DisplayName("DELETE /funds/{code}/reports/{reportId} - Rapor başka fona aitse HTTP 400 dönmeli")
    void testDeleteReport_MismatchedFund_Throws400() throws Exception {
        UUID reportId = UUID.randomUUID();
        Fund fund = Fund.builder().code("OTHER").build();
        FundReport report = FundReport.builder().id(reportId).fund(fund).build();

        when(fundReportRepository.findById(reportId)).thenReturn(Optional.of(report));

        mockMvc.perform(delete("/api/v1/admin/funds/THF/reports/" + reportId))
                .andExpect(status().isBadRequest());

        verify(fundReportRepository, never()).delete(any());
        verify(backtestCacheService, never()).evict(any());
    }

    @Test
    @DisplayName("POST /funds/{code}/discover-report - KAP'taki rapor tarihi mevcut aktif snapshot ile aynıysa portföy korunmalı")
    void testAutoDiscoverReport_WhenKapHasSameOrOlderDate_ProtectsCurrentPortfolio() throws Exception {
        Fund fund = Fund.builder().code("KHA").title("Kuvvetli Hisse").manager("QNB").build();
        UUID snapshotId = UUID.randomUUID();
        FundSnapshot activeSnapshot = FundSnapshot.builder()
                .id(snapshotId)
                .fund(fund)
                .snapshotDate(LocalDate.of(2026, 8, 31))
                .stockRatio(new BigDecimal("0.85"))
                .totalNetAssetValue(new BigDecimal("50000000"))
                .build();
        Holding holding = Holding.builder().id(UUID.randomUUID()).snapshot(activeSnapshot).build();

        KapPdfDto kapPdf = KapPdfDto.builder()
                .fundCode("KHA")
                .fileName("kha.pdf")
                .content(new byte[]{1, 2, 3})
                .sha256Hash("sha_august")
                .source("https://kap.org.tr/bildirim/123")
                .downloadedAt(java.time.LocalDateTime.now())
                .build();
        ParsedPortfolioReportDto parsedDto = ParsedPortfolioReportDto.builder()
                .fundCode("KHA")
                .reportDate(LocalDate.of(2026, 8, 31))
                .build();

        when(fundRepository.findByCode("KHA")).thenReturn(Optional.of(fund));
        when(fundSnapshotRepository.findLatestByFundCode(eq("KHA"), any())).thenReturn(List.of(activeSnapshot));
        when(kapClient.fetchPdf(eq("KHA"), any(), any(), any())).thenReturn(kapPdf);
        when(fundReportRepository.findByPdfSha256("sha_august")).thenReturn(Optional.empty());
        when(holdingRepository.findBySnapshotIdWithInstrument(snapshotId)).thenReturn(List.of(holding));
        when(pdfImportService.parsePortfolioReport(any(), eq("KHA"))).thenReturn(parsedDto);

        mockMvc.perform(post("/api/v1/admin/funds/KHA/discover-report"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.alreadyImported").value(true))
                .andExpect(jsonPath("$.snapshotDate").value("2026-08-31"))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("korundu")));

        verify(pdfImportService, never()).importPdfReportWithResult(any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("POST /funds/{code}/discover-report - KAP'ta daha yeni tarihli bir rapor varsa içeri aktarılmalı")
    void testAutoDiscoverReport_WhenKapHasNewerDate_ImportsNewReport() throws Exception {
        Fund fund = Fund.builder().code("KHA").title("Kuvvetli Hisse").manager("QNB").build();
        UUID oldSnapshotId = UUID.randomUUID();
        FundSnapshot oldSnapshot = FundSnapshot.builder()
                .id(oldSnapshotId)
                .fund(fund)
                .snapshotDate(LocalDate.of(2026, 8, 31))
                .build();

        UUID newSnapshotId = UUID.randomUUID();
        FundSnapshot newSnapshot = FundSnapshot.builder()
                .id(newSnapshotId)
                .fund(fund)
                .snapshotDate(LocalDate.of(2026, 9, 30))
                .stockRatio(new BigDecimal("0.88"))
                .totalNetAssetValue(new BigDecimal("55000000"))
                .build();

        KapPdfDto kapPdf = KapPdfDto.builder()
                .fundCode("KHA")
                .fileName("kha_sep.pdf")
                .content(new byte[]{4, 5, 6})
                .sha256Hash("sha_september")
                .source("https://kap.org.tr/bildirim/456")
                .downloadedAt(java.time.LocalDateTime.now())
                .build();
        ParsedPortfolioReportDto parsedDto = ParsedPortfolioReportDto.builder()
                .fundCode("KHA")
                .reportDate(LocalDate.of(2026, 9, 30))
                .build();

        when(fundRepository.findByCode("KHA")).thenReturn(Optional.of(fund));
        when(fundSnapshotRepository.findLatestByFundCode(eq("KHA"), any())).thenReturn(List.of(oldSnapshot));
        when(kapClient.fetchPdf(eq("KHA"), any(), any(), any())).thenReturn(kapPdf);
        when(fundReportRepository.findByPdfSha256("sha_september")).thenReturn(Optional.empty());
        when(pdfImportService.parsePortfolioReport(any(), eq("KHA"))).thenReturn(parsedDto);
        when(pdfImportService.importPdfReportWithResult(any(), eq("KHA"), any(), eq("sha_september"), any()))
                .thenReturn(new PdfImportService.PdfImportResult(newSnapshot, false));

        mockMvc.perform(post("/api/v1/admin/funds/KHA/discover-report"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.alreadyImported").value(false))
                .andExpect(jsonPath("$.snapshotDate").value("2026-09-30"))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("yeni dönem")));

        verify(pdfImportService).importPdfReportWithResult(any(), eq("KHA"), any(), eq("sha_september"), any());
    }

    @Test
    @DisplayName("POST /funds/{code}/discover-report - SHA256 zaten kayıtlıysa mevcut portföy korunmalı")
    void testAutoDiscoverReport_WhenSha256Matches_ProtectsCurrentPortfolio() throws Exception {
        Fund fund = Fund.builder().code("KHA").build();
        UUID snapshotId = UUID.randomUUID();
        FundSnapshot activeSnapshot = FundSnapshot.builder()
                .id(snapshotId)
                .fund(fund)
                .snapshotDate(LocalDate.of(2026, 8, 31))
                .build();
        Holding holding = Holding.builder().id(UUID.randomUUID()).snapshot(activeSnapshot).build();

        KapPdfDto kapPdf = KapPdfDto.builder()
                .fundCode("KHA")
                .fileName("kha.pdf")
                .content(new byte[]{1})
                .sha256Hash("sha_exact_match")
                .source("https://kap.org.tr/bildirim/789")
                .downloadedAt(java.time.LocalDateTime.now())
                .build();

        when(fundRepository.findByCode("KHA")).thenReturn(Optional.of(fund));
        when(fundSnapshotRepository.findLatestByFundCode(eq("KHA"), any())).thenReturn(List.of(activeSnapshot));
        when(kapClient.fetchPdf(eq("KHA"), any(), any(), any())).thenReturn(kapPdf);
        when(fundReportRepository.findByPdfSha256("sha_exact_match")).thenReturn(Optional.of(FundReport.builder().build()));
        when(holdingRepository.findBySnapshotIdWithInstrument(snapshotId)).thenReturn(List.of(holding));

        mockMvc.perform(post("/api/v1/admin/funds/KHA/discover-report"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.alreadyImported").value(true))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("SHA-256 doğrulandı")));

        verify(pdfImportService, never()).importPdfReportWithResult(any(), any(), any(), any(), any());
    }
}
