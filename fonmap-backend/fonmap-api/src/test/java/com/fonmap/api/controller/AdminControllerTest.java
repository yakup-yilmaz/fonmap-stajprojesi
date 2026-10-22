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
        mockMvc = MockMvcBuilders.standaloneSetup(adminController).build();
        objectMapper = new ObjectMapper();
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
}
