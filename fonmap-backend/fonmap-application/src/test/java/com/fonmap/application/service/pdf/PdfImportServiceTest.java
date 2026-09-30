package com.fonmap.application.service.pdf;

import com.fonmap.domain.entity.*;
import com.fonmap.domain.enums.AssetClass;
import com.fonmap.domain.enums.ReportStatus;
import com.fonmap.infrastructure.client.kap.KapClient;
import com.fonmap.infrastructure.client.kap.dto.KapPdfDto;
import com.fonmap.infrastructure.parser.dto.ParsedHoldingDto;
import com.fonmap.infrastructure.parser.dto.ParsedPortfolioReportDto;
import com.fonmap.infrastructure.parser.factory.PdfParserFactory;
import com.fonmap.infrastructure.parser.strategy.TeraPdfParser;
import com.fonmap.infrastructure.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * PdfImportService Birim Testleri.
 */
@ExtendWith(MockitoExtension.class)
class PdfImportServiceTest {

    @Mock
    private FundRepository fundRepository;
    @Mock
    private FundReportRepository fundReportRepository;
    @Mock
    private FundSnapshotRepository fundSnapshotRepository;
    @Mock
    private HoldingRepository holdingRepository;
    @Mock
    private InstrumentRepository instrumentRepository;
    @Mock
    private InstrumentAliasRepository instrumentAliasRepository;

    private PdfParserFactory pdfParserFactory;
    private PdfImportService pdfImportService;

    private Fund testFund;

    @BeforeEach
    void setUp() {
        pdfParserFactory = new PdfParserFactory(List.of(new TeraPdfParser()));
        pdfImportService = new PdfImportService(
                fundRepository,
                fundReportRepository,
                fundSnapshotRepository,
                holdingRepository,
                instrumentRepository,
                instrumentAliasRepository,
                pdfParserFactory
        );

        testFund = Fund.builder()
                .id(UUID.randomUUID())
                .code("THF")
                .title("TERA PORTFÖY HİSSE SENEDİ FONU")
                .manager("TERA PORTFOY YÖNETİMİ A.Ş.")
                .annualFeeRatio(BigDecimal.valueOf(0.025))
                .isActive(true)
                .build();
    }

    @Test
    @DisplayName("1. Başarılı İçe Aktarma: Mock Rapor ile Snapshot ve Holding Kayıtları")
    void testImportPdfReport_SuccessWithRealSample() throws Exception {
        KapClient kapClient = new KapClient();
        if (!kapClient.hasLocalSamples()) {
            return;
        }

        KapPdfDto kapPdfDto = kapClient.loadFromLocalSample("THF");
        assertNotNull(kapPdfDto);

        when(fundRepository.findByCode("THF")).thenReturn(Optional.of(testFund));

        // Enstrüman arama mock'ları
        Instrument thyao = Instrument.builder().id(1L).ticker("THYAO").assetClass(AssetClass.EQUITY).currency("TRY").build();
        when(instrumentRepository.findByTicker("THYAO")).thenReturn(Optional.of(thyao));
        when(instrumentRepository.findByTicker(argThat(t -> !"THYAO".equals(t)))).thenReturn(Optional.empty());

        when(fundReportRepository.findByFundIdAndReportDateWithFund(any(), any())).thenReturn(Optional.empty());
        when(fundReportRepository.save(any(FundReport.class))).thenAnswer(i -> {
            FundReport r = i.getArgument(0);
            r.setId(UUID.randomUUID());
            return r;
        });

        when(fundSnapshotRepository.findByReportId(any())).thenReturn(Optional.empty());
        when(fundSnapshotRepository.save(any(FundSnapshot.class))).thenAnswer(i -> {
            FundSnapshot s = i.getArgument(0);
            s.setId(UUID.randomUUID());
            return s;
        });

        when(instrumentRepository.save(any(Instrument.class))).thenAnswer(i -> {
            Instrument ins = i.getArgument(0);
            ins.setId(new Random().nextLong(100, 1000));
            return ins;
        });

        // İçe aktarma servisini çağır
        FundSnapshot snapshot = pdfImportService.importPdfReport(
                kapPdfDto.getContent(),
                "THF",
                "https://www.kap.org.tr/tr/BildirimPdf/12345",
                kapPdfDto.getSha256Hash(),
                null
        );

        assertNotNull(snapshot);
        assertEquals(testFund, snapshot.getFund());
        assertNotNull(snapshot.getSnapshotDate());
        assertTrue(snapshot.getTotalNetAssetValue().compareTo(BigDecimal.ZERO) > 0);

        // Holding listesinin kaydedildiğini doğrula
        ArgumentCaptor<List<Holding>> holdingsCaptor = ArgumentCaptor.forClass(List.class);
        verify(holdingRepository, times(1)).saveAll(holdingsCaptor.capture());
        List<Holding> savedHoldings = holdingsCaptor.getValue();

        assertFalse(savedHoldings.isEmpty());
        System.out.printf(">>> [TEST BAŞARILI] Kaydedilen Toplam Holding Sayısı: %d%n", savedHoldings.size());
    }

    @Test
    @DisplayName("2. Idempotency Testi: Aynı SHA256 Daha Önce Kaydedildiyse Tekrar Parse Edilmemeli")
    void testImportPdfReport_Idempotency() throws Exception {
        String existingSha = "abc123sha256hash";
        UUID reportId = UUID.randomUUID();
        FundReport existingReport = FundReport.builder().id(reportId).pdfSha256(existingSha).build();
        FundSnapshot existingSnapshot = FundSnapshot.builder().id(UUID.randomUUID()).report(existingReport).build();

        when(fundReportRepository.findByPdfSha256(existingSha)).thenReturn(Optional.of(existingReport));
        when(fundSnapshotRepository.findByReportId(reportId)).thenReturn(Optional.of(existingSnapshot));

        byte[] dummyPdf = new byte[]{1, 2, 3};
        FundSnapshot result = pdfImportService.importPdfReport(dummyPdf, "THF", "url", existingSha, null);

        assertSame(existingSnapshot, result);
        verify(fundRepository, never()).findByCode(any());
        verify(holdingRepository, never()).saveAll(any());
    }

    @Test
    @DisplayName("3. Hata Senaryosu: Geçersiz / Kayıtsız Fon Kodu")
    void testImportPdfReport_UnknownFund() {
        when(fundRepository.findByCode("XYZ")).thenReturn(Optional.empty());

        byte[] dummyPdf = new byte[]{1, 2, 3};
        assertThrows(IllegalArgumentException.class, () ->
                pdfImportService.importPdfReport(dummyPdf, "XYZ", null, null, null));
    }
}
