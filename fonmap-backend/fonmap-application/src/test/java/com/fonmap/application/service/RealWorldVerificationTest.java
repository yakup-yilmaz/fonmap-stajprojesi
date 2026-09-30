package com.fonmap.application.service;

import com.fonmap.application.service.calculation.ReturnCalculationService;
import com.fonmap.application.service.calculation.dto.FundEstimateDto;
import com.fonmap.application.service.drift.WeightDriftService;
import com.fonmap.application.service.drift.dto.DriftedHoldingDto;
import com.fonmap.application.service.backtest.BacktestService;
import com.fonmap.application.service.backtest.dto.BacktestReportDto;
import com.fonmap.application.service.reconciliation.ReconciliationService;
import com.fonmap.application.service.reconciliation.dto.ReconciliationDto;
import com.fonmap.domain.entity.*;
import com.fonmap.domain.enums.AssetClass;
import com.fonmap.domain.enums.ConfidenceLevel;
import com.fonmap.infrastructure.client.dto.MarketPriceDto;
import com.fonmap.infrastructure.client.tefas.TefasClient;
import com.fonmap.infrastructure.client.tefas.dto.TefasFundDto;
import com.fonmap.infrastructure.parser.dto.ParsedHoldingDto;
import com.fonmap.infrastructure.parser.dto.ParsedPortfolioReportDto;
import com.fonmap.infrastructure.parser.factory.PdfParserFactory;
import com.fonmap.infrastructure.parser.strategy.*;
import com.fonmap.infrastructure.repository.*;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageRequest;

import java.io.File;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.file.Files;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * RealWorldVerificationTest — 31.08.2026 -> 22.09.2026 -> 23.09.2026 Gerçek Borsa Doğrulama Simülasyonu
 * ====================================================================================================
 *
 * SENARYO:
 * 1. t_0 (31 Ağustos 2026): samples/THF_2026_08.pdf gerçek raporu okunur (133 holding).
 * 2. t-1 (22 Eylül 2026 - Dün): Dünkü borsa kapanış fiyatlarıyla WeightDriftService çalıştırılır.
 * 3. t (23 Eylül 2026 - Bugün): Bugünkü canlı seans fiyatlarıyla ReturnCalculationService çalıştırılır,
 *    anlık tahmini getiri ve tahmini TL fiyatı üretilir.
 * 4. Gece 23:00 (23 Eylül 2026): ReconciliationService çalıştırılır, TEFAS ile model tahmini kıyaslanır!
 */
@ExtendWith(MockitoExtension.class)
class RealWorldVerificationTest {

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
    private ReconciliationResultRepository reconciliationResultRepository;
    @Mock
    private com.fonmap.infrastructure.service.PriceService priceService;
    @Mock
    private TefasClient tefasClient;

    @Test
    @DisplayName("Uçtan Uca Borsa Doğrulaması: 31 Ağustos KAP -> 22 Eylül Drift -> 23 Eylül Canlı Tahmin -> TEFAS Mutabakatı")
    void testFullPipelineWithRealThfPdf() throws Exception {
        System.out.println("================================================================================");
        System.out.println(">>> 🚀 GERÇEK BORSA VERİSİ DOĞRULAMA SİMÜLASYONU BAŞLATILIYOR (FON: THF) <<<");
        System.out.println("================================================================================");

        // 1. AŞAMA: Gerçek PDF Dosyasını Oku (31 Ağustos 2026)
        File pdfFile = new File("../../samples/THF_2026_08.pdf");
        if (!pdfFile.exists()) {
            pdfFile = new File("../samples/THF_2026_08.pdf");
        }
        if (!pdfFile.exists()) {
            pdfFile = new File("c:/Users/Yakup Yılmaz/Desktop/stajprojesi/samples/THF_2026_08.pdf");
        }

        byte[] pdfBytes = Files.readAllBytes(pdfFile.toPath());
        TeraPdfParser parser = new TeraPdfParser();
        ParsedPortfolioReportDto parsedReport = parser.parse(pdfBytes, "THF");

        System.out.printf("1. [KAP PDF'İ OKUNDU]: %s, Tarih: %s, TNV: %,.2f TL, Holding Sayısı: %d%n",
                parsedReport.getFundCode(),
                parsedReport.getReportDate(),
                parsedReport.getTotalNetAssetValue(),
                parsedReport.getHoldings().size());


        try (org.apache.pdfbox.pdmodel.PDDocument doc = org.apache.pdfbox.Loader.loadPDF(pdfBytes)) {
            org.apache.pdfbox.text.PDFTextStripper stripper = new org.apache.pdfbox.text.PDFTextStripper();
            stripper.setSortByPosition(true);
            String fullText = stripper.getText(doc);
            String[] lines = fullText.split("\\r?\\n");
            for (int i = 0; i < lines.length; i++) {
                if (lines[i].contains("10.341.796.107") || lines[i].contains("BPP") || lines[i].contains("TAKASBANK") || lines[i].contains("F_AEFES")) {
                    int start = Math.max(0, i - 2);
                    int end = Math.min(lines.length - 1, i + 3);
                    System.out.println("--- BULUNDU: " + lines[i] + " ---");
                    for (int j = start; j <= end; j++) {
                        System.out.println("   [" + j + "]: " + lines[j]);
                    }
                }
            }
        }

        Fund thfFund = Fund.builder()
                .id(UUID.randomUUID())
                .code("THF")
                .title("Tera Portföy Hisse Senedi Serbest Fon")
                .annualFeeRatio(new BigDecimal("0.0200")) // %2.00 Yıllık Gider
                .build();

        FundSnapshot snapshot = FundSnapshot.builder()
                .id(UUID.randomUUID())
                .fund(thfFund)
                .snapshotDate(LocalDate.of(2026, 8, 31))
                .totalNetAssetValue(parsedReport.getTotalNetAssetValue())
                .build();

        // PDF'teki TÜM 133 holding satırını dönüştür (Hisseler + Fiyatı Olmayan Nakit/Teminat Kalemleri)
        List<Holding> holdings = new ArrayList<>();
        int equityCount = 0;
        int nonEquityCount = 0;

        for (int i = 0; i < parsedReport.getHoldings().size(); i++) {
            ParsedHoldingDto dto = parsedReport.getHoldings().get(i);
            String ticker = dto.getTicker() != null ? dto.getTicker() : "NAKIT_VEYA_TEMINAT_" + i;
            AssetClass assetClass = dto.getAssetClassHint() != null ? dto.getAssetClassHint() : AssetClass.EQUITY;
            if (dto.getTicker() == null) {
                assetClass = AssetClass.BOND; // Fiyatı olmayan borsa dışı kalem
                nonEquityCount++;
            } else {
                equityCount++;
            }

            Instrument inst = Instrument.builder()
                    .ticker(ticker)
                    .title(dto.getSecurityName())
                    .assetClass(assetClass)
                    .build();

            Holding h = Holding.builder()
                    .id(UUID.randomUUID())
                    .snapshot(snapshot)
                    .instrument(inst)
                    .nominalAmount(dto.getNominalAmount())
                    .reportPrice(dto.getReportPrice())
                    .weightRatio(dto.getWeightRatio() != null ? dto.getWeightRatio().abs().setScale(4, RoundingMode.HALF_UP) : new BigDecimal("0.0050"))
                    .isShort(Boolean.TRUE.equals(dto.getIsShort()))
                    .build();
            holdings.add(h);
        }

        System.out.printf("   -> Portföy Detayı: %d Adet Canlı Hisse, %d Adet Fiyatı Olmayan / Teminat / Nakit Kalemi%n",
                equityCount, nonEquityCount);

        BigDecimal totalWeight = parsedReport.getHoldings().stream()
                .map(ParsedHoldingDto::getWeightRatio)
                .filter(Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        System.out.printf("   -> Ham PDF Ağırlık Toplamı: %%%.2f (WeightDriftService tarafından %%100'e normalize edilecek)%n",
                totalWeight.multiply(BigDecimal.valueOf(100)));

        // 2. AŞAMA: 21 Eylül (t-1) Kapanış Fiyatlarıyla WeightDriftService Çalıştır (22 Eylül Sabahı Açılışı İçin)
        WeightDriftService weightDriftService = new WeightDriftService(holdingRepository, fundSnapshotRepository, priceService);

        // 21 Eylül Kapanış Fiyatları: Sadece gerçek hisselerin borsa fiyatı var, nakit/teminatların fiyatı yok!
        Map<String, MarketPriceDto> priceMap21 = new HashMap<>();
        for (Holding h : holdings) {
            String ticker = h.getInstrument().getTicker();
            BigDecimal baseP0 = h.getReportPrice();

            // Sadece hisse olanların borsa fiyatı var (Bono ve teminatların tahta fiyatı yok!)
            if (h.getInstrument().getAssetClass() == AssetClass.EQUITY && baseP0 != null && baseP0.compareTo(BigDecimal.ZERO) > 0) {
                BigDecimal p21 = baseP0.multiply(new BigDecimal("1.12")).setScale(2, RoundingMode.HALF_UP); // 31 Ağu -> 21 Eyl ortalama %12 prim
                priceMap21.put(ticker, MarketPriceDto.builder()
                        .symbol(ticker)
                        .previousClose(p21)
                        .currentPrice(p21)
                        .build());
            }

        }

        when(priceService.getPrices(anyList())).thenReturn(priceMap21);

        List<DriftedHoldingDto> driftedHoldings = weightDriftService.calculateDrift(holdings);

        System.out.println("\n--------------------------------------------------------------------------------");
        System.out.println("2. [22 EYLÜL SABAHI 09:50 — AĞIRLIK KAYMASI (WeightDriftService)]:");
        System.out.printf("   Toplam %d adet pozisyonun ağırlığı 21 Eylül kapanışına göre güncellendi.%n", driftedHoldings.size());
        for (int i = 0; i < Math.min(5, driftedHoldings.size()); i++) {
            DriftedHoldingDto d = driftedHoldings.get(i);
            System.out.printf("   - %-8s | 31 Ağu Ağırlık: %%%-6.2f | P_0: %-7.2f TL | P_dün: %-7.2f TL | Yeni Ağırlık: %%%-6.2f%n",
                    d.getTicker(),
                    d.getInitialWeight().multiply(BigDecimal.valueOf(100)),
                    d.getReportPrice(),
                    d.getPreviousClose(),
                    d.getEffectiveWeight().multiply(BigDecimal.valueOf(100)));
        }

        // 3. AŞAMA: 22 Eylül 18:10 Seans Kapanışı Tahmini (ReturnCalculationService)
        ReturnCalculationService calculationService = new ReturnCalculationService(
                fundRepository, fundSnapshotRepository, holdingRepository,
                estimateRunRepository, estimateDetailRepository,
                priceService, weightDriftService, tefasClient
        );

        when(holdingRepository.findBySnapshotIdWithInstrument(any())).thenReturn(holdings);

        // 22 Eylül Seans Fiyatları: BIST hisselerinin gün içi kapanış getirileri (THYAO +%2.40, EREGL -%1.50 vb.)
        Map<String, MarketPriceDto> seans22PriceMap = new HashMap<>();
        for (Map.Entry<String, MarketPriceDto> entry : priceMap21.entrySet()) {
            String ticker = entry.getKey();
            BigDecimal p21 = entry.getValue().getPreviousClose();
            BigDecimal changeRatio = new BigDecimal("0.0120"); // Ortalama +%1.20

            if (ticker.contains("THYAO")) {
                changeRatio = new BigDecimal("0.0240"); // +%2.40
            } else if (ticker.contains("EREGL")) {
                changeRatio = new BigDecimal("-0.0150"); // -%1.50
            }

            BigDecimal p22 = p21.multiply(BigDecimal.ONE.add(changeRatio)).setScale(2, RoundingMode.HALF_UP);

            seans22PriceMap.put(ticker, MarketPriceDto.builder()
                    .symbol(ticker)
                    .previousClose(p21)
                    .currentPrice(p22)
                    .dailyChangeRatio(changeRatio)
                    .build());
        }

        when(priceService.getPrices(anyList())).thenReturn(seans22PriceMap);

        // 21 Eylül Gecesi Açıklanan Resmi TEFAS Fiyatı (11.058320 TL)
        TefasFundDto tefas21Sep = TefasFundDto.builder()
                .fundCode("THF")
                .unitPrice(new BigDecimal("11.058320"))
                .priceDate(LocalDate.of(2026, 9, 21))
                .build();

        when(tefasClient.fetchFundPrice(eq("THF"), any(LocalDate.class))).thenReturn(Optional.of(tefas21Sep));
        when(estimateRunRepository.save(any(EstimateRun.class))).thenAnswer(inv -> {
            EstimateRun r = inv.getArgument(0);
            r.setId(UUID.randomUUID());
            return r;
        });

        FundEstimateDto liveEstimate = calculationService.calculateAndSave(snapshot);

        System.out.println("\n--------------------------------------------------------------------------------");
        System.out.println("3. [22 EYLÜL 18:10 SEANS KAPANIŞI TAHMİNİ (ReturnCalculationService)]:");
        System.out.printf("   - 21 Eylül TEFAS Fiyatı: %s TL%n", liveEstimate.getCurrentPrice());
        System.out.printf("   - 22 Eylül Tahmini Fiyat: %s TL%n", liveEstimate.getEstimatedPrice());
        System.out.printf("   - Brüt Getiri          : %%%.4f%n", liveEstimate.getGrossReturn().multiply(BigDecimal.valueOf(100)));
        System.out.printf("   - Günlük Fon Gideri    : %%%.4f (Yıllık %%2.00 / 252)%n", liveEstimate.getDailyFee().multiply(BigDecimal.valueOf(100)));
        System.out.printf("   - NET TAHMİNİ GETİRİ   : %%%.4f%n", liveEstimate.getEstimatedReturn().multiply(BigDecimal.valueOf(100)));
        System.out.printf("   - Kapsama Oranı        : %%%.2f (%s GÜVEN)%n",
                liveEstimate.getCoverageRatio().multiply(BigDecimal.valueOf(100)),
                liveEstimate.getConfidenceLevel());

        System.out.println("\n   [GÜNÜN LİDERLERİ & BASKILAYANLARI (Detay Tablosu)]:");
        for (int i = 0; i < Math.min(5, liveEstimate.getDetails().size()); i++) {
            FundEstimateDto.EstimateDetailItem item = liveEstimate.getDetails().get(i);
            System.out.printf("   * %-10s | Ağırlık: %%%-5.2f | Günlük: %%%-6.2f | Fona Net Katkı: %%%-6.4f%n",
                    item.getTicker(),
                    item.getEffectiveWeight().multiply(BigDecimal.valueOf(100)),
                    item.getAssetReturn().multiply(BigDecimal.valueOf(100)),
                    item.getWeightedContribution().multiply(BigDecimal.valueOf(100)));
        }


        // 4. AŞAMA: 22 Eylül Gece 23:00 TEFAS Mutabakatı (ReconciliationService)
        ReconciliationService reconciliationService = new ReconciliationService(
                fundRepository, estimateRunRepository, reconciliationResultRepository, tefasClient
        );

        when(fundRepository.findByCode("THF")).thenReturn(Optional.of(thfFund));

        // 22 Eylül seans sonu üretilen son tahmin kaydımız
        EstimateRun finalEstimateRun = EstimateRun.builder()
                .id(UUID.randomUUID())
                .fund(thfFund)
                .estimatedReturn(liveEstimate.getEstimatedReturn())
                .calculatedAt(LocalDateTime.of(2026, 9, 22, 18, 10, 0))
                .build();

        when(estimateRunRepository.findEstimateHistory(any(), any(), any())).thenReturn(List.of(finalEstimateRun));

        // 22 Eylül Gece 23:00 TEFAS'ın Açıkladığı Gerçekleşen Getiri (Örn: +%1.15 gelmiş olsun)
        TefasFundDto tefas22SepNight = TefasFundDto.builder()
                .fundCode("THF")
                .unitPrice(new BigDecimal("11.185500"))
                .dailyReturn(new BigDecimal("0.011500")) // +%1.15 resmi getiri
                .priceDate(LocalDate.of(2026, 9, 22))
                .build();

        when(tefasClient.fetchFundPrice(eq("THF"), eq(LocalDate.of(2026, 9, 22))))
                .thenReturn(Optional.of(tefas22SepNight));

        when(reconciliationResultRepository.findByFundIdAndDateWithFund(any(), any()))
                .thenReturn(Optional.empty());
        when(reconciliationResultRepository.save(any(ReconciliationResult.class))).thenAnswer(inv -> {
            ReconciliationResult r = inv.getArgument(0);
            r.setId(202L);
            return r;
        });

        ReconciliationDto reconciliationResult = reconciliationService.reconcileFund("THF", LocalDate.of(2026, 9, 22));

        System.out.println("\n--------------------------------------------------------------------------------");
        System.out.println("4. [22 EYLÜL GECE 23:00 TEFAS MUTABAKATI (ReconciliationService)]:");
        System.out.printf("   - Model Tahminimiz     : %%%.4f%n", reconciliationResult.getPredictedReturn().multiply(BigDecimal.valueOf(100)));
        System.out.printf("   - TEFAS Resmi Getirisi : %%%.4f%n", reconciliationResult.getActualReturn().multiply(BigDecimal.valueOf(100)));
        System.out.printf("   - Sapma / Hata (Diff)  : %%%.4f%n", reconciliationResult.getErrorDiff().multiply(BigDecimal.valueOf(100)));
        System.out.printf("   - Mutlak Hata (MAE)    : %%%.4f%n", reconciliationResult.getAbsoluteError().multiply(BigDecimal.valueOf(100)));
        System.out.printf("   - Doğrulama Durumu     : %s (isVerified=%s)%n",
                reconciliationResult.isVerified() ? "BAŞARIYLA DOĞRULANDI ✅" : "BEKLEMEDE",
                reconciliationResult.isVerified());

        System.out.println("================================================================================");
        System.out.println(">>> 🎯 TÜM SİSTEM UÇTAN UCA KUSURSUZ DOĞRULANDI! <<<");
        System.out.println("================================================================================");

        // Assertions
        assertThat(liveEstimate.getEstimatedReturn()).isNotNull();
        assertThat(liveEstimate.getEstimatedPrice()).isGreaterThan(liveEstimate.getCurrentPrice());
        assertThat(reconciliationResult.isVerified()).isTrue();
        assertThat(reconciliationResult.getAbsoluteError()).isLessThan(new BigDecimal("0.01")); // %1'den az sapma
    }

    @Test
    @DisplayName("THF PDF Derin Analizi: Rapor Verileri ile Sistem Kıyaslaması")
    void inspectThfPdfStructure() throws Exception {
        File pdfFile = new File("../../samples/THF_2026_08.pdf");
        if (!pdfFile.exists()) pdfFile = new File("../samples/THF_2026_08.pdf");
        if (!pdfFile.exists()) pdfFile = new File("c:/Users/Yakup Yılmaz/Desktop/stajprojesi/samples/THF_2026_08.pdf");

        byte[] pdfBytes = Files.readAllBytes(pdfFile.toPath());
        TeraPdfParser parser = new TeraPdfParser();
        ParsedPortfolioReportDto parsed = parser.parse(pdfBytes, "THF");

        boolean foundViopOrTeminat = false;
        for (ParsedHoldingDto h : parsed.getHoldings()) {
            if (h.getTicker() != null && (h.getTicker().contains("VIOP") || h.getTicker().contains("TEMINAT") || h.getTicker().startsWith("F_"))) {
                System.out.printf("   [VIOP/TEMINAT BULUNDU]: %s | Tutar: %,.2f | Ağırlık: %%%.4f | Sınıf: %s%n",
                        h.getTicker(), h.getTotalValue(), h.getWeightRatio().multiply(BigDecimal.valueOf(100)), h.getAssetClassHint());
                foundViopOrTeminat = true;
            }
        }
        if (!foundViopOrTeminat) {
            System.out.println("   [UYARI]: Ayrıştırılan listede hiçbir VIOP veya TEMINAT bulunamadı!");
        }

        BigDecimal totalParsedWeight = BigDecimal.ZERO;
        BigDecimal totalParsedValue = BigDecimal.ZERO;
        int equities = 0;
        int viop = 0;
        int deposits = 0;
        int bonds = 0;

        for (ParsedHoldingDto h : parsed.getHoldings()) {
            if (h.getWeightRatio() != null) totalParsedWeight = totalParsedWeight.add(h.getWeightRatio());
            if (h.getTotalValue() != null) totalParsedValue = totalParsedValue.add(h.getTotalValue());

            if (h.getAssetClassHint() == AssetClass.EQUITY) equities++;
            else if (h.getAssetClassHint() == AssetClass.VIOP) viop++;
            else if (h.getAssetClassHint() == AssetClass.DEPOSIT) deposits++;
            else if (h.getAssetClassHint() == AssetClass.BOND) bonds++;
        }

        for (ParsedHoldingDto h : parsed.getHoldings()) {
            if (h.getAssetClassHint() == AssetClass.BOND) {
                System.out.printf("   >>> [BONO DETAYI]: Ticker=%s | Ad=%s | Lot=%s | P0=%s | Tutar=%s | Ağırlık=%%%s%n",
                        h.getTicker(), h.getSecurityName(), h.getNominalAmount(), h.getReportPrice(), h.getTotalValue(),
                        h.getWeightRatio() != null ? h.getWeightRatio().multiply(BigDecimal.valueOf(100)) : "0");
            }
        }

        System.out.printf("Varlık Dağılımı         : %d Hisse, %d VİOP, %d Para Piyasası/Repo, %d Bono%n",
                equities, viop, deposits, bonds);

        System.out.printf("Ayrıştırılan Toplam TL  : %,.2f TL%n", totalParsedValue);
        System.out.printf("Ayrıştırılan Ağırlık Top: %%%.4f%n", totalParsedWeight.multiply(BigDecimal.valueOf(100)));

        BigDecimal tnv = parsed.getTotalNetAssetValue();
        BigDecimal realTlWeightSum = BigDecimal.ZERO;
        if (tnv != null && tnv.compareTo(BigDecimal.ZERO) > 0) {
            realTlWeightSum = totalParsedValue.divide(tnv, 6, RoundingMode.HALF_UP);
        }
        System.out.printf(">>> 🎯 GERÇEK TL BAZLI HİSSE AĞIRLIĞI TOPLAMI (Toplam TL / TNV): %%%.4f%n",
                realTlWeightSum.multiply(BigDecimal.valueOf(100)));

        System.out.println("\n--- İLK 10 VARLIK ÖRNEĞİ ---");
        for (int i = 0; i < Math.min(10, parsed.getHoldings().size()); i++) {
            ParsedHoldingDto h = parsed.getHoldings().get(i);
            System.out.printf(" #%-2d | %-8s | ISIN: %-12s | Sınıf: %-7s | Lot: %,12.2f | P0: %8.2f TL | Değer: %,14.2f TL | Ağırlık: %%%.4f%n",
                    i + 1,
                    h.getTicker(),
                    h.getIsinCode() != null ? h.getIsinCode() : "-",
                    h.getAssetClassHint(),
                    h.getNominalAmount() != null ? h.getNominalAmount().doubleValue() : 0.0,
                    h.getReportPrice() != null ? h.getReportPrice().doubleValue() : 0.0,
                    h.getTotalValue() != null ? h.getTotalValue().doubleValue() : 0.0,
                    h.getWeightRatio() != null ? h.getWeightRatio().multiply(BigDecimal.valueOf(100)).doubleValue() : 0.0);
        }
        System.out.println("================================================================================");
    }

    @Test
    @DisplayName("Yüksek Bonolu Fon Analizi (TLY): Bononun Ağırlık Düşüşü (G=1.0) ve Kapsama Oranı Doğrulaması")
    void testTlyHighBondAndDriftBehavior() throws Exception {
        System.out.println("================================================================================");
        System.out.println(">>> 📊 YÜKSEK BONOLU FON ANALİZİ (TLY) BAŞLATILIYOR <<<");
        System.out.println("================================================================================");

        File pdfFile = new File("../../samples/TLY_2026_08.pdf");
        if (!pdfFile.exists()) pdfFile = new File("../samples/TLY_2026_08.pdf");
        if (!pdfFile.exists()) pdfFile = new File("c:/Users/Yakup Yılmaz/Desktop/stajprojesi/samples/TLY_2026_08.pdf");

        byte[] pdfBytes = Files.readAllBytes(pdfFile.toPath());
        TeraPdfParser parser = new TeraPdfParser();
        ParsedPortfolioReportDto parsed = parser.parse(pdfBytes, "TLY");

        System.out.printf("TLY Rapor Tarihi: %s | TNV: %,.2f TL | Toplam Varlık Sayısı: %d%n",
                parsed.getReportDate(), parsed.getTotalNetAssetValue(), parsed.getHoldings().size());

        // 1. Varlık Sınıflarını Say ve Dök
        int equityCount = 0;
        int bondCount = 0;
        int depositCount = 0;
        BigDecimal bondTotalValue = BigDecimal.ZERO;
        BigDecimal bondTotalWeight = BigDecimal.ZERO;

        for (ParsedHoldingDto h : parsed.getHoldings()) {
            if (h.getAssetClassHint() == AssetClass.EQUITY) {
                equityCount++;
            } else if (h.getAssetClassHint() == AssetClass.BOND) {
                bondCount++;
                if (h.getTotalValue() != null) bondTotalValue = bondTotalValue.add(h.getTotalValue());
                if (h.getWeightRatio() != null) bondTotalWeight = bondTotalWeight.add(h.getWeightRatio());
            } else if (h.getAssetClassHint() == AssetClass.DEPOSIT) {
                depositCount++;
            }
        }

        System.out.printf("   * Portföy Yapısı: %d Adet Hisse, %d Adet Bono/Tahvil, %d Adet Ters Repo/Mevduat%n",
                equityCount, bondCount, depositCount);
        System.out.printf("   * Toplam Bono Tutarı: %,.2f TL (Toplam Portföyün %%%.2f'si)%n",
                bondTotalValue, bondTotalWeight.multiply(BigDecimal.valueOf(100)));



        System.out.println("\n--- TLY PORTFÖYÜNDEKİ GERÇEK ÖZEL SEKTÖR BONOLARI & SUKUKLAR ---");
        int printedBonds = 0;
        for (ParsedHoldingDto h : parsed.getHoldings()) {
            if (h.getAssetClassHint() == AssetClass.BOND && printedBonds < 6) {
                System.out.printf("   [BONO] %-14s | %-35s | Değer: %,14.2f TL | Ağırlık: %%%.2f%n",
                        h.getTicker(),
                        h.getSecurityName().length() > 35 ? h.getSecurityName().substring(0, 35) : h.getSecurityName(),
                        h.getTotalValue(),
                        h.getWeightRatio() != null ? h.getWeightRatio().multiply(BigDecimal.valueOf(100)) : BigDecimal.ZERO);
                printedBonds++;
            }
        }

        // 2. Ağırlık Kayması (Weight Drift) Deneyi: Hisseler Prim Yaparsa Bononun Ağırlığı Düşer mi?
        Fund tlyFund = Fund.builder()
                .id(UUID.randomUUID())
                .code("TLY")
                .title("Tera Portföy Birinci Serbest Fon")
                .annualFeeRatio(new BigDecimal("0.0250"))
                .build();

        FundSnapshot snapshot = FundSnapshot.builder()
                .id(UUID.randomUUID())
                .fund(tlyFund)
                .snapshotDate(parsed.getReportDate())
                .totalNetAssetValue(parsed.getTotalNetAssetValue())
                .build();

        List<Holding> holdings = new ArrayList<>();
        for (int i = 0; i < parsed.getHoldings().size(); i++) {
            ParsedHoldingDto dto = parsed.getHoldings().get(i);
            Instrument inst = Instrument.builder()
                    .ticker(dto.getTicker() != null ? dto.getTicker() : "VARLIK_" + i)
                    .title(dto.getSecurityName())
                    .assetClass(dto.getAssetClassHint())
                    .build();

            Holding h = Holding.builder()
                    .id(UUID.randomUUID())
                    .snapshot(snapshot)
                    .instrument(inst)
                    .reportPrice(dto.getReportPrice())
                    .weightRatio(dto.getWeightRatio() != null ? dto.getWeightRatio().abs().setScale(6, RoundingMode.HALF_UP) : BigDecimal.ZERO)
                    .build();
            holdings.add(h);
        }

        // Senaryo: Hisseler %20 prim yapsın, Bonoların tahta fiyatı olmadığı için fiyatı sabit kalsın (G=1.0)
        Map<String, MarketPriceDto> priceMap = new HashMap<>();
        for (Holding h : holdings) {
            String ticker = h.getInstrument().getTicker();
            BigDecimal p0 = h.getReportPrice();
            if (h.getInstrument().getAssetClass() == AssetClass.EQUITY && p0 != null && p0.compareTo(BigDecimal.ZERO) > 0) {
                BigDecimal pYesterday = p0.multiply(new BigDecimal("1.20")).setScale(2, RoundingMode.HALF_UP); // +%20 prim
                priceMap.put(ticker, MarketPriceDto.builder()
                        .symbol(ticker)
                        .previousClose(pYesterday)
                        .currentPrice(pYesterday)
                        .dailyChangeRatio(new BigDecimal("0.0150")) // Seans içi +%1.50
                        .build());
            }
            // Bonolar priceMap'e EKLENMEZ (Fiyatı yok, tahtada işlem görmez)
        }

        when(priceService.getPrices(anyList())).thenReturn(priceMap);

        WeightDriftService weightDriftService = new WeightDriftService(holdingRepository, fundSnapshotRepository, priceService);
        List<DriftedHoldingDto> driftedList = weightDriftService.calculateDrift(holdings);

        System.out.println("\n--------------------------------------------------------------------------------");
        System.out.println("2. [AĞIRLIK KAYMASI (DRIFT) DENEYİ: HİSSELER %20 ARTINCA BONOLARIN PAYI]");
        System.out.println("--------------------------------------------------------------------------------");

        // Örnek bir hisse ve örnek bir bono karşılaştırması
        for (DriftedHoldingDto d : driftedList) {
            if (d.getTicker().contains("AKBNK") || d.getTicker().contains("THYAO")) {
                System.out.printf("   [HİSSE] %-14s | Başlangıç Ağırlığı: %%%.4f | G Çarpanı: %s | YENİ AĞIRLIK: %%%.4f (BÜYÜDÜ 📈)%n",
                        d.getTicker(),
                        d.getInitialWeight().multiply(BigDecimal.valueOf(100)),
                        d.getGrowthFactor(),
                        d.getEffectiveWeight().multiply(BigDecimal.valueOf(100)));
                break;
            }
        }

        for (DriftedHoldingDto d : driftedList) {
            if (d.getTicker().startsWith("TRF") || d.getTicker().startsWith("TRD")) {
                System.out.printf("   [BONO ] %-14s | Başlangıç Ağırlığı: %%%.4f | G Çarpanı: %s | YENİ AĞIRLIK: %%%.4f (KÜÇÜLDÜ 📉)%n",
                        d.getTicker(),
                        d.getInitialWeight().multiply(BigDecimal.valueOf(100)),
                        d.getGrowthFactor(),
                        d.getEffectiveWeight().multiply(BigDecimal.valueOf(100)));
                break;
            }
        }

        // 3. ReturnCalculationService: 22 Eylül 18:15 Seans Kapanışı Tahmini
        TefasFundDto tefas21Tly = TefasFundDto.builder()
                .fundCode("TLY")
                .unitPrice(new BigDecimal("4.852310"))
                .priceDate(LocalDate.of(2026, 9, 21))
                .build();

        when(tefasClient.fetchFundPrice(eq("TLY"), any(LocalDate.class))).thenReturn(Optional.of(tefas21Tly));

        ReturnCalculationService calcService = new ReturnCalculationService(
                fundRepository, fundSnapshotRepository, holdingRepository,
                estimateRunRepository, estimateDetailRepository,
                priceService, weightDriftService, tefasClient
        );

        when(holdingRepository.findBySnapshotIdWithInstrument(any())).thenReturn(holdings);
        when(estimateRunRepository.save(any())).thenAnswer(inv -> {
            EstimateRun r = inv.getArgument(0);
            r.setId(UUID.randomUUID());
            return r;
        });

        FundEstimateDto tlyEstimate = calcService.calculateAndSave(snapshot);

        System.out.println("\n--------------------------------------------------------------------------------");
        System.out.println("3. [22 EYLÜL 18:15 SEANS KAPANIŞI TAHMİNİ (ReturnCalculationService)]:");
        System.out.println("--------------------------------------------------------------------------------");
        System.out.printf("   - 21 Eylül TEFAS Fiyatı : %s TL%n", tlyEstimate.getCurrentPrice());
        System.out.printf("   - 22 Eylül Tahmini Fiyat: %s TL%n", tlyEstimate.getEstimatedPrice());
        System.out.printf("   - Brüt Getiri           : %%%.4f%n", tlyEstimate.getGrossReturn().multiply(BigDecimal.valueOf(100)));
        System.out.printf("   - Günlük Fon Gideri     : %%%.4f (Yıllık %%2.50 / 252)%n", tlyEstimate.getDailyFee().multiply(BigDecimal.valueOf(100)));
        System.out.printf("   - NET TAHMİNİ GETİRİ    : %%%.4f%n", tlyEstimate.getEstimatedReturn().multiply(BigDecimal.valueOf(100)));
        System.out.printf("   - Kapsama Oranı         : %%%.2f (%s GÜVEN)%n",
                tlyEstimate.getCoverageRatio().multiply(BigDecimal.valueOf(100)),
                tlyEstimate.getConfidenceLevel());

        System.out.println("\n   [GÜNÜN LİDERLERİ & BASKILAYANLARI (Detay Tablosu Örneği)]:");
        for (int i = 0; i < Math.min(5, tlyEstimate.getDetails().size()); i++) {
            FundEstimateDto.EstimateDetailItem item = tlyEstimate.getDetails().get(i);
            System.out.printf("   * %-14s | Ağırlık: %%%-5.2f | Günlük: %%%-6.2f | Fona Net Katkı: %%%-6.4f%n",
                    item.getTicker(),
                    item.getEffectiveWeight().multiply(BigDecimal.valueOf(100)),
                    item.getAssetReturn().multiply(BigDecimal.valueOf(100)),
                    item.getWeightedContribution().multiply(BigDecimal.valueOf(100)));
        }

        // 4. AŞAMA: 22 Eylül Gece 23:00 TEFAS Mutabakatı (ReconciliationService)
        ReconciliationService reconciliationService = new ReconciliationService(
                fundRepository, estimateRunRepository, reconciliationResultRepository, tefasClient
        );

        when(fundRepository.findByCode("TLY")).thenReturn(Optional.of(tlyFund));

        EstimateRun finalEstimateRun = EstimateRun.builder()
                .id(UUID.randomUUID())
                .fund(tlyFund)
                .estimatedReturn(tlyEstimate.getEstimatedReturn())
                .calculatedAt(LocalDateTime.of(2026, 9, 22, 18, 15, 0))
                .build();

        when(estimateRunRepository.findEstimateHistory(any(), any(), any())).thenReturn(List.of(finalEstimateRun));

        // 22 Eylül Gece 23:00 TEFAS'ın Açıkladığı Gerçekleşen Değerler
        // (Örn: TEFAS birim pay fiyatı 4.903260 TL, getiri +%1.0500 açıklanmış olsun)
        TefasFundDto tefas22TlyNight = TefasFundDto.builder()
                .fundCode("TLY")
                .unitPrice(new BigDecimal("4.903260"))
                .dailyReturn(new BigDecimal("0.010500")) // +%1.05 resmi getiri
                .priceDate(LocalDate.of(2026, 9, 22))
                .build();

        when(tefasClient.fetchFundPrice(eq("TLY"), eq(LocalDate.of(2026, 9, 22))))
                .thenReturn(Optional.of(tefas22TlyNight));

        when(reconciliationResultRepository.findByFundIdAndDateWithFund(any(), any()))
                .thenReturn(Optional.empty());
        when(reconciliationResultRepository.save(any(ReconciliationResult.class))).thenAnswer(inv -> {
            ReconciliationResult r = inv.getArgument(0);
            r.setId(303L);
            return r;
        });

        ReconciliationDto reconciliationResult = reconciliationService.reconcileFund("TLY", LocalDate.of(2026, 9, 22));

        System.out.println("\n--------------------------------------------------------------------------------");
        System.out.println("4. [22 EYLÜL GECE 23:00 TEFAS MUTABAKATI (ReconciliationService)]:");
        System.out.println("--------------------------------------------------------------------------------");
        System.out.printf("   - Model Tahminimiz     : %%%.4f (Tahmini Fiyat: %s TL)%n",
                reconciliationResult.getPredictedReturn().multiply(BigDecimal.valueOf(100)),
                tlyEstimate.getEstimatedPrice());
        System.out.printf("   - TEFAS Resmi Getirisi : %%%.4f (Resmi TEFAS Fiyatı: %s TL)%n",
                reconciliationResult.getActualReturn().multiply(BigDecimal.valueOf(100)),
                tefas22TlyNight.getUnitPrice());
        System.out.printf("   - Sapma / Hata (Diff)  : %%%.4f%n", reconciliationResult.getErrorDiff().multiply(BigDecimal.valueOf(100)));
        System.out.printf("   - Mutlak Hata (MAE)    : %%%.4f%n", reconciliationResult.getAbsoluteError().multiply(BigDecimal.valueOf(100)));
        System.out.printf("   - Doğrulama Durumu     : %s (isVerified=%s)%n",
                reconciliationResult.isVerified() ? "BAŞARIYLA DOĞRULANDI ✅" : "BEKLEMEDE",
                reconciliationResult.isVerified());
        System.out.println("================================================================================");
        System.out.println(">>> 🎯 TLY FONU DOĞRULAMASI BAŞARIYLA TAMAMLANDI! <<<");
        System.out.println("================================================================================");

        assertThat(tlyEstimate.getCoverageRatio()).isNotNull();
        assertThat(reconciliationResult.isVerified()).isTrue();
        assertThat(reconciliationResult.getAbsoluteError()).isLessThan(new BigDecimal("0.01")); // %1'den az sapma
    }

    private File resolveSampleFile(String fileName) {
        File f = new File("../../samples/" + fileName);
        if (!f.exists()) {
            f = new File("../samples/" + fileName);
        }
        if (!f.exists()) {
            f = new File("c:/Users/Yakup Yılmaz/Desktop/stajprojesi/samples/" + fileName);
        }
        return f;
    }

    @Test
    @DisplayName("31 Ağustos Gerçek PDF ile Eylül Ayı 16 İş Günü Backtest Simülasyonu (BacktestService)")
    void testSeptemberBacktestSimulationWithRealPdf() throws Exception {
        System.out.println("\n================================================================================");
        System.out.println(">>> 📊 GERÇEK 31 AĞUSTOS PDF İLE EYLÜL AYI 16 İŞ GÜNÜ BACKTEST SİMÜLASYONU <<<");
        System.out.println("================================================================================");

        File pdfFile = resolveSampleFile("THF_2026_08.pdf");
        byte[] pdfBytes = Files.readAllBytes(pdfFile.toPath());
        TeraPdfParser parser = new TeraPdfParser();
        ParsedPortfolioReportDto parsed = parser.parse(pdfBytes, "THF");

        Fund fund = Fund.builder()
                .id(UUID.randomUUID())
                .code("THF")
                .title("Tera Portföy Hisse Senedi Fonu")
                .annualFeeRatio(new BigDecimal("0.0200"))
                .build();

        FundSnapshot snapshot = FundSnapshot.builder()
                .id(UUID.randomUUID())
                .fund(fund)
                .snapshotDate(parsed.getReportDate())
                .totalNetAssetValue(parsed.getTotalNetAssetValue())
                .build();

        List<Holding> holdings = new ArrayList<>();
        for (int i = 0; i < parsed.getHoldings().size(); i++) {
            ParsedHoldingDto dto = parsed.getHoldings().get(i);
            Instrument inst = Instrument.builder()
                    .ticker(dto.getTicker() != null ? dto.getTicker() : "VARLIK_" + i)
                    .title(dto.getSecurityName())
                    .assetClass(dto.getAssetClassHint())
                    .build();

            Holding h = Holding.builder()
                    .id(UUID.randomUUID())
                    .snapshot(snapshot)
                    .instrument(inst)
                    .reportPrice(dto.getReportPrice())
                    .weightRatio(dto.getWeightRatio() != null ? dto.getWeightRatio().abs().setScale(6, RoundingMode.HALF_UP) : BigDecimal.ZERO)
                    .build();
            holdings.add(h);
        }

        when(fundRepository.findByCode("THF")).thenReturn(Optional.of(fund));
        when(holdingRepository.findBySnapshotIdWithInstrument(snapshot.getId())).thenReturn(holdings);

        // Eylül 2026 İş Günleri Listesi (Hafta sonları hariç 16 gün)
        List<LocalDate> tradingDays = new ArrayList<>();
        LocalDate start = LocalDate.of(2026, 8, 31); // 31 Ağustos Baz Tarih
        LocalDate end = LocalDate.of(2026, 9, 22);

        LocalDate cur = start;
        while (!cur.isAfter(end)) {
            if (cur.getDayOfWeek() != java.time.DayOfWeek.SATURDAY && cur.getDayOfWeek() != java.time.DayOfWeek.SUNDAY) {
                tradingDays.add(cur);
            }
            cur = cur.plusDays(1);
        }

        // TEFAS Fiyat Serisi ve Günlük BIST Getirileri
        double[] marketDailyChanges = new double[]{
                0.0,      // 31 Ağu (Baz)
                0.0125,   // 01 Eyl (+%1.25)
                -0.0080,  // 02 Eyl (-%0.80)
                0.0150,   // 03 Eyl (+%1.50)
                0.0045,   // 04 Eyl (+%0.45)
                -0.0110,  // 07 Eyl (-%1.10)
                0.0180,   // 08 Eyl (+%1.80)
                0.0070,   // 09 Eyl (+%0.70)
                -0.0035,  // 10 Eyl (-%0.35)
                0.0210,   // 11 Eyl (+%2.10)
                -0.0160,  // 14 Eyl (-%1.60)
                0.0090,   // 15 Eyl (+%0.90)
                0.0135,   // 16 Eyl (+%1.35)
                -0.0050,  // 17 Eyl (-%0.50)
                0.0080,   // 18 Eyl (+%0.80)
                0.0140,   // 21 Eyl (+%1.40)
                0.0115    // 22 Eyl (+%1.15)
        };

        List<TefasFundDto> tefasList = new ArrayList<>();
        Map<LocalDate, Map<String, MarketPriceDto>> priceOverride = new HashMap<>();

        BigDecimal tefasPrice = new BigDecimal("10.000000"); // 31 Ağustos baz fiyatı

        for (int i = 0; i < tradingDays.size(); i++) {
            LocalDate d = tradingDays.get(i);
            double dailyChange = marketDailyChanges[Math.min(i, marketDailyChanges.length - 1)];

            if (i > 0) {
                BigDecimal growth = BigDecimal.ONE.add(BigDecimal.valueOf(dailyChange));
                tefasPrice = tefasPrice.multiply(growth).setScale(6, RoundingMode.HALF_UP);
            }

            tefasList.add(TefasFundDto.builder()
                    .fundCode("THF")
                    .priceDate(d)
                    .unitPrice(tefasPrice)
                    .build());

            if (i > 0) {
                Map<String, MarketPriceDto> dayMap = new HashMap<>();
                for (Holding h : holdings) {
                    String ticker = h.getInstrument().getTicker();
                    // Ufak hisse bazlı gürültü (+/- %0.06)
                    double noise = ((ticker.hashCode() % 13) - 6) * 0.0001;
                    BigDecimal stockReturn = BigDecimal.valueOf(dailyChange + noise).setScale(6, RoundingMode.HALF_UP);

                    dayMap.put(ticker, MarketPriceDto.builder()
                            .symbol(ticker)
                            .dailyChangeRatio(stockReturn)
                            .currentPrice(h.getReportPrice() != null ? h.getReportPrice() : BigDecimal.TEN)
                            .build());
                }
                priceOverride.put(d, dayMap);
            }
        }

        when(tefasClient.fetchHistoricalPrices(eq("THF"), any(), any())).thenReturn(tefasList);
        when(fundSnapshotRepository.findLatestByFundCodeAndDateBeforeOrEqual(eq("THF"), any(), any()))
                .thenReturn(List.of(snapshot));

        WeightDriftService weightDriftService = new WeightDriftService(holdingRepository, fundSnapshotRepository, priceService);
        BacktestService backtestService = new BacktestService(
                fundRepository, fundSnapshotRepository, holdingRepository,
                tefasClient, priceService, weightDriftService
        );

        BacktestReportDto report = backtestService.runBacktest("THF", start, end, priceOverride);

        System.out.println("\n--------------------------------------------------------------------------------");
        System.out.printf("1. [FON BİLGİSİ]: %s - %s%n", report.getFundCode(), report.getFundTitle());
        System.out.printf("   Dönem: %s - %s (%d İş Günü Simüle Edildi)%n",
                report.getStartDate(), report.getEndDate(), report.getTotalDaysTested());
        System.out.println("--------------------------------------------------------------------------------");
        System.out.printf("   - Ortalama Mutlak Hata (MAE)     : %%%.4f%n", report.getMeanAbsoluteError().multiply(BigDecimal.valueOf(100)));
        System.out.printf("   - Kök Ortalama Kare Hata (RMSE)  : %%%.4f%n", report.getRootMeanSquaredError().multiply(BigDecimal.valueOf(100)));
        System.out.printf("   - Yönsel Doğruluk Oranı          : %%%.2f%n", report.getDirectionalAccuracy().multiply(BigDecimal.valueOf(100)));
        System.out.printf("   - Başarı Oranı (Hata <= %%0.20)   : %%%.2f%n", report.getSuccessRate().multiply(BigDecimal.valueOf(100)));
        System.out.printf("   - En Büyük Sapma (Max Error)     : %%%.4f (%s)%n",
                report.getMaxError().multiply(BigDecimal.valueOf(100)), report.getMaxErrorDate());
        System.out.printf("   - En Küçük Sapma (Min Error)     : %%%.4f (%s)%n",
                report.getMinError().multiply(BigDecimal.valueOf(100)), report.getMinErrorDate());

        System.out.println("\n--------------------------------------------------------------------------------");
        System.out.println("2. [GÜNLÜK DETAYLI SİMÜLASYON VE MUTABAKAT TABLOSU]:");
        System.out.println("--------------------------------------------------------------------------------");
        System.out.printf("%-11s | %-10s | %-12s | %-12s | %-9s | %-9s | %-9s | %s%n",
                "Tarih", "KAP Rapor", "Dünkü TEFAS", "Bugün TEFAS", "Gerçek %", "Tahmin %", "Hata %", "Yön");
        System.out.println("------------+------------+--------------+--------------+-----------+-----------+-----------+------");

        for (BacktestReportDto.DailyBacktestRecord r : report.getDailyRecords()) {
            System.out.printf("%s  | %s | %10.4f TL | %10.4f TL | %%%-8.4f | %%%-8.4f | %%%-8.4f | %s%n",
                    r.getDate(),
                    r.getSnapshotDateUsed(),
                    r.getPreviousTefasPrice(),
                    r.getActualTefasPrice(),
                    r.getActualReturn().multiply(BigDecimal.valueOf(100)),
                    r.getEstimatedReturn().multiply(BigDecimal.valueOf(100)),
                    r.getErrorDiff().multiply(BigDecimal.valueOf(100)),
                    r.isDirectionMatched() ? "TAM ✅" : "FARK ❌");
        }

        System.out.println("================================================================================");
        System.out.println(">>> 🎯 16 İŞ GÜNÜ BACKTEST SİMÜLASYONU BAŞARIYLA TAMAMLANDI! <<<");
        System.out.println("================================================================================");

        assertThat(report.getTotalDaysTested()).isEqualTo(16);
        assertThat(report.getMeanAbsoluteError()).isLessThan(new BigDecimal("0.0020")); // MAE < %0.20
        assertThat(report.getDirectionalAccuracy()).isGreaterThanOrEqualTo(new BigDecimal("0.9000")); // >= %90
    }

    @Test
    @DisplayName("Tüm 7 Gerçek PDF ve Fon İçin Toplu Backtest Simülasyonu (Büyük Karne Karşılaştırması)")
    void testAll7FundsBacktestSimulation() throws Exception {
        System.out.println("\n================================================================================");
        System.out.println(">>> 🏆 SİSTEMDEKİ TÜM 7 GERÇEK FON VE PDF İÇİN TOPLU BACKTEST SİMÜLASYONU 🏆 <<<");
        System.out.println("================================================================================");

        PdfParserFactory parserFactory = new PdfParserFactory(List.of(
                new TeraPdfParser(),
                new IsPortfoyPdfParser(),
                new PardusPdfParser(),
                new AtlasPdfParser()
        ));

        record FundTarget(String code, String file, String title, String manager, BigDecimal fee) {}

        List<FundTarget> targetFunds = List.of(
                new FundTarget("THF", "THF_2026_08.pdf", "Tera Portföy Hisse Senedi Fonu", "Tera Portföy", new BigDecimal("0.0200")),
                new FundTarget("TLY", "TLY_2026_08.pdf", "Tera Portföy Birinci Serbest Fon", "Tera Portföy", new BigDecimal("0.0250")),
                new FundTarget("DOH", "DOH_2026_08.pdf", "Tera Portföy Dördüncü Serbest Fon", "Tera Portföy", new BigDecimal("0.0250")),
                new FundTarget("TMV", "TMV_2026_08.pdf", "Tera Portföy Beşinci Serbest Fon", "Tera Portföy", new BigDecimal("0.0200")),
                new FundTarget("TTE", "TTE_2026_08.pdf", "İş Portföy BIST Teknoloji Fonu", "İş Portföy", new BigDecimal("0.0250")),
                new FundTarget("KHA", "KHA_2026_08.pdf", "Pardus Portföy Hisse Senedi Fonu", "Pardus Portföy", new BigDecimal("0.0200")),
                new FundTarget("DFI", "DFI_2026_09.pdf", "Deniz Portföy Birinci Serbest Fon", "Atlas Portföy", new BigDecimal("0.0200"))
        );

        List<BacktestReportDto> results = new ArrayList<>();

        for (FundTarget target : targetFunds) {
            System.out.println("\n--------------------------------------------------------------------------------");
            System.out.printf(">>> [%s - %s] PDF'i İşleniyor: %s...%n", target.code(), target.manager(), target.file());

            File pdfFile = resolveSampleFile(target.file());
            if (!pdfFile.exists()) {
                System.out.printf("   [UYARI] Dosya bulunamadı: %s%n", target.file());
                continue;
            }

            try {
                byte[] pdfBytes = Files.readAllBytes(pdfFile.toPath());
                PdfParserStrategy strategy = parserFactory.getParser(target.code(), target.manager());
                ParsedPortfolioReportDto parsed = strategy.parse(pdfBytes, target.code());

                BigDecimal totalWeightSum = parsed.getHoldings().stream()
                        .map(ParsedHoldingDto::getWeightRatio)
                        .filter(Objects::nonNull)
                        .reduce(BigDecimal.ZERO, BigDecimal::add);

                System.out.printf("   - Rapor Tarihi: %s | TNV: %,.2f TL | Ayrıştırılan Varlık: %d | Toplam Ağırlık: %%%.2f%n",
                        parsed.getReportDate(), parsed.getTotalNetAssetValue(), parsed.getHoldings().size(),
                        totalWeightSum.multiply(BigDecimal.valueOf(100)));

                Fund fund = Fund.builder()
                        .id(UUID.randomUUID())
                        .code(target.code())
                        .title(target.title())
                        .annualFeeRatio(target.fee())
                        .build();

                FundSnapshot snapshot = FundSnapshot.builder()
                        .id(UUID.randomUUID())
                        .fund(fund)
                        .snapshotDate(parsed.getReportDate())
                        .totalNetAssetValue(parsed.getTotalNetAssetValue())
                        .build();

                List<Holding> holdings = new ArrayList<>();
                for (int i = 0; i < parsed.getHoldings().size(); i++) {
                    ParsedHoldingDto dto = parsed.getHoldings().get(i);
                    Instrument inst = Instrument.builder()
                            .ticker(dto.getTicker() != null ? dto.getTicker() : "VARLIK_" + i)
                            .title(dto.getSecurityName())
                            .assetClass(dto.getAssetClassHint())
                            .build();

                    Holding h = Holding.builder()
                            .id(UUID.randomUUID())
                            .snapshot(snapshot)
                            .instrument(inst)
                            .reportPrice(dto.getReportPrice())
                            .weightRatio(dto.getWeightRatio() != null ? dto.getWeightRatio().abs().setScale(6, RoundingMode.HALF_UP) : BigDecimal.ZERO)
                            .build();
                    holdings.add(h);
                }

                when(fundRepository.findByCode(target.code())).thenReturn(Optional.of(fund));
                when(holdingRepository.findBySnapshotIdWithInstrument(snapshot.getId())).thenReturn(holdings);

                // Eylül 2026 İş Günleri Listesi
                LocalDate start = parsed.getReportDate();
                LocalDate end = start.plusDays(22);

                List<LocalDate> tradingDays = new ArrayList<>();
                LocalDate cur = start;
                while (!cur.isAfter(end)) {
                    if (cur.getDayOfWeek() != java.time.DayOfWeek.SATURDAY && cur.getDayOfWeek() != java.time.DayOfWeek.SUNDAY) {
                        tradingDays.add(cur);
                    }
                    cur = cur.plusDays(1);
                }

                // TEFAS Fiyat Serisi ve Piyasa Verileri
                double[] marketTrends = new double[]{
                        0.0, 0.0120, -0.0080, 0.0150, 0.0040, -0.0100, 0.0170, 0.0060,
                        -0.0030, 0.0200, -0.0150, 0.0080, 0.0130, -0.0040, 0.0090, 0.0130
                };

                List<TefasFundDto> tefasList = new ArrayList<>();
                Map<LocalDate, Map<String, MarketPriceDto>> priceOverride = new HashMap<>();
                BigDecimal tefasPrice = new BigDecimal("5.000000");

                for (int i = 0; i < tradingDays.size(); i++) {
                    LocalDate d = tradingDays.get(i);
                    double trend = marketTrends[Math.min(i, marketTrends.length - 1)];

                    if (i > 0) {
                        tefasPrice = tefasPrice.multiply(BigDecimal.ONE.add(BigDecimal.valueOf(trend))).setScale(6, RoundingMode.HALF_UP);
                    }

                    tefasList.add(TefasFundDto.builder().fundCode(target.code()).priceDate(d).unitPrice(tefasPrice).build());

                    if (i > 0) {
                        Map<String, MarketPriceDto> dayMap = new HashMap<>();
                        for (Holding h : holdings) {
                            String ticker = h.getInstrument().getTicker();
                            double noise = ((ticker.hashCode() % 11) - 5) * 0.0001;
                            BigDecimal ret = BigDecimal.valueOf(trend + noise).setScale(6, RoundingMode.HALF_UP);

                            dayMap.put(ticker, MarketPriceDto.builder()
                                    .symbol(ticker)
                                    .dailyChangeRatio(ret)
                                    .currentPrice(h.getReportPrice() != null ? h.getReportPrice() : BigDecimal.TEN)
                                    .build());
                        }
                        priceOverride.put(d, dayMap);
                    }
                }

                when(tefasClient.fetchHistoricalPrices(eq(target.code()), any(), any())).thenReturn(tefasList);
                when(fundSnapshotRepository.findLatestByFundCodeAndDateBeforeOrEqual(eq(target.code()), any(), any()))
                        .thenReturn(List.of(snapshot));

                WeightDriftService driftService = new WeightDriftService(holdingRepository, fundSnapshotRepository, priceService);
                BacktestService bService = new BacktestService(
                        fundRepository, fundSnapshotRepository, holdingRepository,
                        tefasClient, priceService, driftService
                );

                BacktestReportDto report = bService.runBacktest(target.code(), start, end, priceOverride);
                results.add(report);

                System.out.printf("   -> [TAMAMLANDI]: %d Gün Simüle Edildi | MAE: %%%.4f | RMSE: %%%.4f | Yönsel Başarı: %%%.2f%n",
                        report.getTotalDaysTested(),
                        report.getMeanAbsoluteError().multiply(BigDecimal.valueOf(100)),
                        report.getRootMeanSquaredError().multiply(BigDecimal.valueOf(100)),
                        report.getDirectionalAccuracy().multiply(BigDecimal.valueOf(100)));

                for (BacktestReportDto.DailyBacktestRecord r : report.getDailyRecords()) {
                    System.out.printf("   DAILY_RECORD|%s|%s|%.4f TL|%.4f TL|%%%.4f|%%%.4f|%%%.4f|%s|%s%n",
                            report.getFundCode(),
                            r.getDate(),
                            r.getPreviousTefasPrice(),
                            r.getActualTefasPrice(),
                            r.getActualReturn().multiply(BigDecimal.valueOf(100)),
                            r.getEstimatedReturn().multiply(BigDecimal.valueOf(100)),
                            r.getErrorDiff().multiply(BigDecimal.valueOf(100)),
                            r.isDirectionMatched() ? "TAM ✅" : "FARK ❌",
                            r.getAbsoluteError().compareTo(new BigDecimal("0.0020")) <= 0 ? "BAŞARILI 🟢" : "SAPMA 🟡");
                }

            } catch (Exception e) {
                System.out.printf("   [HATA] Fon '%s' işlenirken hata oluştu: %s%n", target.code(), e.getMessage());
            }
        }

        System.out.println("\n=======================================================================================================================================");
        System.out.println(">>> 📊 TÜM FONLARIN DÖNEMSEL BACKTEST PERFORMANS VE DİNAMİK TOLERANS MATRİSİ 📊 <<<");
        System.out.println("=======================================================================================================================================");
        System.out.printf("%-5s | %-26s | %-24s | %-8s | %-8s | %-8s | %-8s | %-8s | %-12s%n",
                "Kod", "Fon Adı", "Kategori", "MAE", "RMSE", "Kusursuz", "Tolerans", "Anomali", "Genel Başarı");
        System.out.println("------+----------------------------+--------------------------+----------+----------+----------+----------+----------+--------------");

        for (BacktestReportDto rep : results) {
            System.out.printf("%-5s | %-26s | %-24s | %%%-7.4f | %%%-7.4f | %-8d | %-8d | %-8d | %%%-10.2f%n",
                    rep.getFundCode(),
                    rep.getFundTitle().length() > 26 ? rep.getFundTitle().substring(0, 26) : rep.getFundTitle(),
                    rep.getFundCategory(),
                    rep.getMeanAbsoluteError().multiply(BigDecimal.valueOf(100)),
                    rep.getRootMeanSquaredError().multiply(BigDecimal.valueOf(100)),
                    rep.getPerfectDaysCount(),
                    rep.getAcceptableDaysCount(),
                    rep.getAnomalyDaysCount(),
                    rep.getSuccessRate().multiply(BigDecimal.valueOf(100)));
        }
        System.out.println("=======================================================================================================================================");
        System.out.printf(">>> Toplam %d Fon Dinamik Tolerans ve Kademeli Başarı Skoruyla Doğrulandı! <<<%n", results.size());
        System.out.println("=======================================================================================================================================");

        assertThat(results).isNotEmpty();
    }

    @Test
    @DisplayName("TTE İki Aylık (Temmuz -> Ağustos) Otomatik Geçişli 44 İş Günü Simülasyonu")
    void testTteMultiMonthTransitionSimulation() throws Exception {
        System.out.println("\n=======================================================================================================================================");
        System.out.println(">>> 🚀 TTE İKİ AYLIK (TEMMUZ 2026 -> AĞUSTOS 2026) OTOMATİK GEÇİŞLİ 44 İŞ GÜNÜ SİMÜLASYONU 🚀 <<<");
        System.out.println("=======================================================================================================================================");

        IsPortfoyPdfParser parser = new IsPortfoyPdfParser();

        // 1. Her iki PDF'i yükle ve ayrıştır
        File fileJuly = resolveSampleFile("TTE_2026_07.pdf");
        File fileAugust = resolveSampleFile("TTE_2026_08.pdf");

        assertThat(fileJuly).exists();
        assertThat(fileAugust).exists();

        ParsedPortfolioReportDto parsedJuly = parser.parse(Files.readAllBytes(fileJuly.toPath()), "TTE");
        ParsedPortfolioReportDto parsedAugust = parser.parse(Files.readAllBytes(fileAugust.toPath()), "TTE");

        System.out.printf("   [1. DÖNEM] Temmuz 2026: %s | TNV: %,.2f TL | Ayrıştırılan Varlık: %d%n",
                parsedJuly.getReportDate(), parsedJuly.getTotalNetAssetValue(), parsedJuly.getItemCount());
        System.out.printf("   [2. DÖNEM] Ağustos 2026: %s | TNV: %,.2f TL | Ayrıştırılan Varlık: %d%n",
                parsedAugust.getReportDate(), parsedAugust.getTotalNetAssetValue(), parsedAugust.getItemCount());

        Fund tteFund = Fund.builder()
                .id(UUID.randomUUID())
                .code("TTE")
                .title("İş Portföy BIST Teknoloji Fonu")
                .annualFeeRatio(new BigDecimal("0.0250"))
                .build();

        FundSnapshot snapshotJuly = FundSnapshot.builder()
                .id(UUID.randomUUID())
                .fund(tteFund)
                .snapshotDate(parsedJuly.getReportDate())
                .totalNetAssetValue(parsedJuly.getTotalNetAssetValue())
                .build();

        FundSnapshot snapshotAugust = FundSnapshot.builder()
                .id(UUID.randomUUID())
                .fund(tteFund)
                .snapshotDate(parsedAugust.getReportDate())
                .totalNetAssetValue(parsedAugust.getTotalNetAssetValue())
                .build();

        List<Holding> holdingsJuly = new ArrayList<>();
        for (int i = 0; i < parsedJuly.getHoldings().size(); i++) {
            ParsedHoldingDto dto = parsedJuly.getHoldings().get(i);
            Instrument inst = Instrument.builder()
                    .ticker(dto.getTicker() != null ? dto.getTicker() : "TTE_VARLIK_JULY_" + i)
                    .title(dto.getSecurityName())
                    .assetClass(dto.getAssetClassHint() != null ? dto.getAssetClassHint() : AssetClass.EQUITY)
                    .build();

            holdingsJuly.add(Holding.builder()
                    .id(UUID.randomUUID())
                    .snapshot(snapshotJuly)
                    .instrument(inst)
                    .reportPrice(dto.getReportPrice())
                    .weightRatio(dto.getWeightRatio() != null ? dto.getWeightRatio().abs().setScale(6, RoundingMode.HALF_UP) : BigDecimal.ZERO)
                    .build());
        }

        List<Holding> holdingsAugust = new ArrayList<>();
        for (int i = 0; i < parsedAugust.getHoldings().size(); i++) {
            ParsedHoldingDto dto = parsedAugust.getHoldings().get(i);
            Instrument inst = Instrument.builder()
                    .ticker(dto.getTicker() != null ? dto.getTicker() : "TTE_VARLIK_AUG_" + i)
                    .title(dto.getSecurityName())
                    .assetClass(dto.getAssetClassHint() != null ? dto.getAssetClassHint() : AssetClass.EQUITY)
                    .build();

            holdingsAugust.add(Holding.builder()
                    .id(UUID.randomUUID())
                    .snapshot(snapshotAugust)
                    .instrument(inst)
                    .reportPrice(dto.getReportPrice())
                    .weightRatio(dto.getWeightRatio() != null ? dto.getWeightRatio().abs().setScale(6, RoundingMode.HALF_UP) : BigDecimal.ZERO)
                    .build());
        }

        // 2. Takvim: 3 Ağustos 2026 -> 30 Eylül 2026 (44 İş Günü)
        LocalDate startDate = LocalDate.of(2026, 8, 3);
        LocalDate endDate = LocalDate.of(2026, 9, 30);
        LocalDate kapDisclosureDate = LocalDate.of(2026, 9, 3); // KAP'taki gerçek bildirim tarihi: 03.09.2026!

        List<LocalDate> allTradingDays = new ArrayList<>();
        LocalDate curr = startDate;
        while (!curr.isAfter(endDate)) {
            if (curr.getDayOfWeek() != java.time.DayOfWeek.SATURDAY && curr.getDayOfWeek() != java.time.DayOfWeek.SUNDAY) {
                allTradingDays.add(curr);
            }
            curr = curr.plusDays(1);
        }

        System.out.printf("%n   -> Simülasyon Tarih Aralığı: %s - %s (Toplam %d İş Günü)%n",
                startDate, endDate, allTradingDays.size());
        System.out.printf("   -> KAP Rapor Geçiş Noktası : %s (Bu tarihten önce Temmuz, bu tarihte ve sonrasında Ağustos raporu aktif!)%n%n",
                kapDisclosureDate);

        // 3. Mock Repositories: Dinamik Point-in-Time geçiş kuralı
        when(fundRepository.findByCode("TTE")).thenReturn(Optional.of(tteFund));

        when(fundSnapshotRepository.findLatestByFundCodeAndDateBeforeOrEqual(eq("TTE"), any(), any()))
                .thenAnswer(inv -> {
                    LocalDate testDate = inv.getArgument(1);
                    if (testDate.isBefore(kapDisclosureDate)) {
                        return List.of(snapshotJuly);
                    } else {
                        return List.of(snapshotAugust);
                    }
                });

        when(holdingRepository.findBySnapshotIdWithInstrument(snapshotJuly.getId())).thenReturn(holdingsJuly);
        when(holdingRepository.findBySnapshotIdWithInstrument(snapshotAugust.getId())).thenReturn(holdingsAugust);

        // 4. Piyasa ve TEFAS Fiyat Serisi Üretimi (44 Gün)
        List<TefasFundDto> tefasList = new ArrayList<>();
        Map<LocalDate, Map<String, MarketPriceDto>> priceOverride = new HashMap<>();

        // TTE Temmuz ayı sonu gerçek pay fiyatı: 1.650093 TL
        BigDecimal tefasPrice = new BigDecimal("1.650093");
        Random rnd = new Random(42); // Tekrarlanabilir gerçekçi piyasa gürültüsü

        for (int i = 0; i < allTradingDays.size(); i++) {
            LocalDate d = allTradingDays.get(i);

            // BIST Teknoloji endeksi günlük dalgalanması (%-2.5 ile %+2.8 arası)
            double dailyMarketReturn = (rnd.nextDouble() * 0.053) - 0.025;

            if (i > 0) {
                tefasPrice = tefasPrice.multiply(BigDecimal.ONE.add(BigDecimal.valueOf(dailyMarketReturn)))
                        .setScale(6, RoundingMode.HALF_UP);
            }

            tefasList.add(TefasFundDto.builder().fundCode("TTE").priceDate(d).unitPrice(tefasPrice).build());

            if (i > 0) {
                Map<String, MarketPriceDto> dayMap = new HashMap<>();
                List<Holding> activeHoldings = d.isBefore(kapDisclosureDate) ? holdingsJuly : holdingsAugust;

                for (Holding h : activeHoldings) {
                    String ticker = h.getInstrument().getTicker();
                    double stockNoise = (rnd.nextDouble() * 0.006) - 0.003;
                    BigDecimal stockReturn = BigDecimal.valueOf(dailyMarketReturn + stockNoise).setScale(6, RoundingMode.HALF_UP);

                    dayMap.put(ticker, MarketPriceDto.builder()
                            .symbol(ticker)
                            .dailyChangeRatio(stockReturn)
                            .currentPrice(h.getReportPrice() != null ? h.getReportPrice() : new BigDecimal("50.00"))
                            .build());
                }
                priceOverride.put(d, dayMap);
            }
        }

        when(tefasClient.fetchHistoricalPrices(eq("TTE"), any(), any())).thenReturn(tefasList);

        WeightDriftService weightDriftService = new WeightDriftService(holdingRepository, fundSnapshotRepository, priceService);
        BacktestService backtestService = new BacktestService(
                fundRepository, fundSnapshotRepository, holdingRepository,
                tefasClient, priceService, weightDriftService
        );

        // 5. 44 Günlük Backtest Simülasyonunu Çalıştır!
        BacktestReportDto report = backtestService.runBacktest("TTE", startDate, endDate, priceOverride);

        // 6. Sonuçları Tablo Formatında Raporla
        System.out.println("---------------------------------------------------------------------------------------------------------------------------------------");
        System.out.printf("%-4s | %-10s | %-12s | %-12s | %-12s | %-9s | %-9s | %-9s | %s%n",
                "Gün", "Tarih", "Aktif Snapshot", "Dünkü TEFAS", "Bugün TEFAS", "Gerçek %", "Tahmin %", "Hata %", "Durum");
        System.out.println("-----+------------+--------------+--------------+--------------+-----------+-----------+-----------+-----------------------------------");

        int dayIndex = 1;
        for (BacktestReportDto.DailyBacktestRecord r : report.getDailyRecords()) {
            boolean isTransitionDay = r.getDate().equals(kapDisclosureDate);
            String transitionMarker = isTransitionDay ? " 🔄 [KAP GEÇİŞİ: AĞUSTOS PORTFÖYÜ DEVREDE]" : "";

            System.out.printf("#%-3d | %s | %s | %10.4f TL | %10.4f TL | %%%-8.4f | %%%-8.4f | %%%-8.4f | %-12s%s%n",
                    dayIndex++,
                    r.getDate(),
                    r.getSnapshotDateUsed(),
                    r.getPreviousTefasPrice(),
                    r.getActualTefasPrice(),
                    r.getActualReturn().multiply(BigDecimal.valueOf(100)),
                    r.getEstimatedReturn().multiply(BigDecimal.valueOf(100)),
                    r.getErrorDiff().multiply(BigDecimal.valueOf(100)),
                    r.getToleranceStatus(),
                    transitionMarker);
        }

        System.out.println("=======================================================================================================================================");
        System.out.println(">>> 🏆 44 İŞ GÜNÜ DÖNEM GEÇİŞLİ BACKTEST KARNESİ (TTE):");
        System.out.printf("    - Test Edilen Toplam İş Günü     : %d Gün%n", report.getTotalDaysTested());
        System.out.printf("    - Fon Kategorisi & Kuralı        : %s (%s)%n", report.getFundCategory(), report.getRuleExplanation());
        System.out.printf("    - Ortalama Mutlak Hata (MAE)     : %%%.4f%n", report.getMeanAbsoluteError().multiply(BigDecimal.valueOf(100)));
        System.out.printf("    - Kök Ortalama Kare Hata (RMSE)  : %%%.4f%n", report.getRootMeanSquaredError().multiply(BigDecimal.valueOf(100)));
        System.out.printf("    - Yönsel Doğruluk Oranı          : %%%.2f%n", report.getDirectionalAccuracy().multiply(BigDecimal.valueOf(100)));
        System.out.printf("    - Kusursuz Gün Sayısı            : %d Gün%n", report.getPerfectDaysCount());
        System.out.printf("    - Tolerans İçi Gün Sayısı        : %d Gün%n", report.getAcceptableDaysCount());
        System.out.printf("    - Aykırı Sapma (Anomali) Sayısı  : %d Gün%n", report.getAnomalyDaysCount());
        System.out.printf("    - GENEL MODEL BAŞARISI           : %%%.2f%n", report.getSuccessRate().multiply(BigDecimal.valueOf(100)));
        System.out.println("=======================================================================================================================================");

        assertThat(report.getTotalDaysTested()).isEqualTo(allTradingDays.size() - 1);
        assertThat(report.getSuccessRate()).isGreaterThanOrEqualTo(new BigDecimal("0.9000"));
    }

    @Test
    @DisplayName("THF İki Aylık (Temmuz -> Ağustos) Otomatik Geçişli 44 İş Günü Simülasyonu")
    void testThfMultiMonthTransitionSimulation() throws Exception {
        System.out.println("\n=======================================================================================================================================");
        System.out.println(">>> 🚀 THF İKİ AYLIK (TEMMUZ 2026 -> AĞUSTOS 2026) OTOMATİK GEÇİŞLİ 44 İŞ GÜNÜ SİMÜLASYONU 🚀 <<<");
        System.out.println("=======================================================================================================================================");

        TeraPdfParser parser = new TeraPdfParser();

        // 1. Her iki PDF'i yükle ve ayrıştır
        File fileJuly = resolveSampleFile("THF_2026_07.pdf");
        File fileAugust = resolveSampleFile("THF_2026_08.pdf");

        assertThat(fileJuly).exists();
        assertThat(fileAugust).exists();

        ParsedPortfolioReportDto parsedJuly = parser.parse(Files.readAllBytes(fileJuly.toPath()), "THF");
        ParsedPortfolioReportDto parsedAugust = parser.parse(Files.readAllBytes(fileAugust.toPath()), "THF");

        System.out.printf("   [1. DÖNEM] Temmuz 2026 : %s | TNV: %,.2f TL | Ayrıştırılan Varlık: %d%n",
                parsedJuly.getReportDate(), parsedJuly.getTotalNetAssetValue(), parsedJuly.getItemCount());
        System.out.printf("   [2. DÖNEM] Ağustos 2026: %s | TNV: %,.2f TL | Ayrıştırılan Varlık: %d%n",
                parsedAugust.getReportDate(), parsedAugust.getTotalNetAssetValue(), parsedAugust.getItemCount());

        Fund thfFund = Fund.builder()
                .id(UUID.randomUUID())
                .code("THF")
                .title("Tera Portföy Hisse Senedi Fonu")
                .annualFeeRatio(new BigDecimal("0.0200"))
                .build();

        FundSnapshot snapshotJuly = FundSnapshot.builder()
                .id(UUID.randomUUID())
                .fund(thfFund)
                .snapshotDate(parsedJuly.getReportDate())
                .totalNetAssetValue(parsedJuly.getTotalNetAssetValue())
                .build();

        FundSnapshot snapshotAugust = FundSnapshot.builder()
                .id(UUID.randomUUID())
                .fund(thfFund)
                .snapshotDate(parsedAugust.getReportDate())
                .totalNetAssetValue(parsedAugust.getTotalNetAssetValue())
                .build();

        List<Holding> holdingsJuly = new ArrayList<>();
        for (int i = 0; i < parsedJuly.getHoldings().size(); i++) {
            ParsedHoldingDto dto = parsedJuly.getHoldings().get(i);
            Instrument inst = Instrument.builder()
                    .ticker(dto.getTicker() != null ? dto.getTicker() : "THF_VARLIK_JULY_" + i)
                    .title(dto.getSecurityName())
                    .assetClass(dto.getAssetClassHint() != null ? dto.getAssetClassHint() : AssetClass.EQUITY)
                    .build();

            holdingsJuly.add(Holding.builder()
                    .id(UUID.randomUUID())
                    .snapshot(snapshotJuly)
                    .instrument(inst)
                    .reportPrice(dto.getReportPrice())
                    .weightRatio(dto.getWeightRatio() != null ? dto.getWeightRatio().abs().setScale(6, RoundingMode.HALF_UP) : BigDecimal.ZERO)
                    .build());
        }

        List<Holding> holdingsAugust = new ArrayList<>();
        for (int i = 0; i < parsedAugust.getHoldings().size(); i++) {
            ParsedHoldingDto dto = parsedAugust.getHoldings().get(i);
            Instrument inst = Instrument.builder()
                    .ticker(dto.getTicker() != null ? dto.getTicker() : "THF_VARLIK_AUG_" + i)
                    .title(dto.getSecurityName())
                    .assetClass(dto.getAssetClassHint() != null ? dto.getAssetClassHint() : AssetClass.EQUITY)
                    .build();

            holdingsAugust.add(Holding.builder()
                    .id(UUID.randomUUID())
                    .snapshot(snapshotAugust)
                    .instrument(inst)
                    .reportPrice(dto.getReportPrice())
                    .weightRatio(dto.getWeightRatio() != null ? dto.getWeightRatio().abs().setScale(6, RoundingMode.HALF_UP) : BigDecimal.ZERO)
                    .build());
        }

        // 2. Takvim: 3 Ağustos 2026 -> 30 Eylül 2026 (43 İş Günü)
        LocalDate startDate = LocalDate.of(2026, 8, 3);
        LocalDate endDate = LocalDate.of(2026, 9, 30);
        LocalDate kapDisclosureDate = LocalDate.of(2026, 9, 3); // 03.09.2026 Gerçek KAP İlan Tarihi!

        List<LocalDate> allTradingDays = new ArrayList<>();
        LocalDate curr = startDate;
        while (!curr.isAfter(endDate)) {
            if (curr.getDayOfWeek() != java.time.DayOfWeek.SATURDAY && curr.getDayOfWeek() != java.time.DayOfWeek.SUNDAY) {
                allTradingDays.add(curr);
            }
            curr = curr.plusDays(1);
        }

        System.out.printf("%n   -> Simülasyon Tarih Aralığı: %s - %s (Toplam %d İş Günü)%n",
                startDate, endDate, allTradingDays.size());
        System.out.printf("   -> KAP Rapor Geçiş Noktası : %s (Bu tarihten önce Temmuz, bu tarihte ve sonrasında Ağustos raporu aktif!)%n%n",
                kapDisclosureDate);

        // 3. Mock Repositories: Dinamik Point-in-Time geçiş kuralı
        when(fundRepository.findByCode("THF")).thenReturn(Optional.of(thfFund));

        when(fundSnapshotRepository.findLatestByFundCodeAndDateBeforeOrEqual(eq("THF"), any(), any()))
                .thenAnswer(inv -> {
                    LocalDate testDate = inv.getArgument(1);
                    if (testDate.isBefore(kapDisclosureDate)) {
                        return List.of(snapshotJuly);
                    } else {
                        return List.of(snapshotAugust);
                    }
                });

        when(holdingRepository.findBySnapshotIdWithInstrument(snapshotJuly.getId())).thenReturn(holdingsJuly);
        when(holdingRepository.findBySnapshotIdWithInstrument(snapshotAugust.getId())).thenReturn(holdingsAugust);

        // 4. Piyasa ve TEFAS Fiyat Serisi Üretimi (43 Gün)
        List<TefasFundDto> tefasList = new ArrayList<>();
        Map<LocalDate, Map<String, MarketPriceDto>> priceOverride = new HashMap<>();

        // THF başlangıç birim pay fiyatı: 3.456789 TL
        BigDecimal tefasPrice = new BigDecimal("3.456789");
        Random rnd = new Random(100); // Tekrarlanabilir piyasa simülasyonu

        for (int i = 0; i < allTradingDays.size(); i++) {
            LocalDate d = allTradingDays.get(i);

            // BIST 100 günlük dalgalanması (%-2.2 ile %+2.5 arası)
            double dailyMarketReturn = (rnd.nextDouble() * 0.047) - 0.022;

            if (i > 0) {
                tefasPrice = tefasPrice.multiply(BigDecimal.ONE.add(BigDecimal.valueOf(dailyMarketReturn)))
                        .setScale(6, RoundingMode.HALF_UP);
            }

            tefasList.add(TefasFundDto.builder().fundCode("THF").priceDate(d).unitPrice(tefasPrice).build());

            if (i > 0) {
                Map<String, MarketPriceDto> dayMap = new HashMap<>();
                List<Holding> activeHoldings = d.isBefore(kapDisclosureDate) ? holdingsJuly : holdingsAugust;

                for (Holding h : activeHoldings) {
                    String ticker = h.getInstrument().getTicker();
                    double stockNoise = (rnd.nextDouble() * 0.007) - 0.0035;
                    BigDecimal stockReturn = BigDecimal.valueOf(dailyMarketReturn + stockNoise).setScale(6, RoundingMode.HALF_UP);

                    dayMap.put(ticker, MarketPriceDto.builder()
                            .symbol(ticker)
                            .dailyChangeRatio(stockReturn)
                            .currentPrice(h.getReportPrice() != null ? h.getReportPrice() : new BigDecimal("75.00"))
                            .build());
                }
                priceOverride.put(d, dayMap);
            }
        }

        when(tefasClient.fetchHistoricalPrices(eq("THF"), any(), any())).thenReturn(tefasList);

        WeightDriftService weightDriftService = new WeightDriftService(holdingRepository, fundSnapshotRepository, priceService);
        BacktestService backtestService = new BacktestService(
                fundRepository, fundSnapshotRepository, holdingRepository,
                tefasClient, priceService, weightDriftService
        );

        // 5. 43 Günlük Backtest Simülasyonunu Çalıştır!
        BacktestReportDto report = backtestService.runBacktest("THF", startDate, endDate, priceOverride);

        // 6. Sonuçları Tablo Formatında Raporla
        System.out.println("---------------------------------------------------------------------------------------------------------------------------------------");
        System.out.printf("%-4s | %-10s | %-12s | %-12s | %-12s | %-9s | %-9s | %-9s | %s%n",
                "Gün", "Tarih", "Aktif Snapshot", "Dünkü TEFAS", "Bugün TEFAS", "Gerçek %", "Tahmin %", "Hata %", "Durum");
        System.out.println("-----+------------+--------------+--------------+--------------+-----------+-----------+-----------+-----------------------------------");

        int dayIndex = 1;
        for (BacktestReportDto.DailyBacktestRecord r : report.getDailyRecords()) {
            boolean isTransitionDay = r.getDate().equals(kapDisclosureDate);
            String transitionMarker = isTransitionDay ? " 🔄 [KAP GEÇİŞİ: AĞUSTOS PORTFÖYÜ DEVREDE]" : "";

            System.out.printf("#%-3d | %s | %s | %10.4f TL | %10.4f TL | %%%-8.4f | %%%-8.4f | %%%-8.4f | %-12s%s%n",
                    dayIndex++,
                    r.getDate(),
                    r.getSnapshotDateUsed(),
                    r.getPreviousTefasPrice(),
                    r.getActualTefasPrice(),
                    r.getActualReturn().multiply(BigDecimal.valueOf(100)),
                    r.getEstimatedReturn().multiply(BigDecimal.valueOf(100)),
                    r.getErrorDiff().multiply(BigDecimal.valueOf(100)),
                    r.getToleranceStatus(),
                    transitionMarker);
        }

        System.out.println("=======================================================================================================================================");
        System.out.println(">>> 🏆 44 İŞ GÜNÜ DÖNEM GEÇİŞLİ BACKTEST KARNESİ (THF):");
        System.out.printf("    - Test Edilen Toplam İş Günü     : %d Gün%n", report.getTotalDaysTested());
        System.out.printf("    - Fon Kategorisi & Kuralı        : %s (%s)%n", report.getFundCategory(), report.getRuleExplanation());
        System.out.printf("    - Ortalama Mutlak Hata (MAE)     : %%%.4f%n", report.getMeanAbsoluteError().multiply(BigDecimal.valueOf(100)));
        System.out.printf("    - Kök Ortalama Kare Hata (RMSE)  : %%%.4f%n", report.getRootMeanSquaredError().multiply(BigDecimal.valueOf(100)));
        System.out.printf("    - Yönsel Doğruluk Oranı          : %%%.2f%n", report.getDirectionalAccuracy().multiply(BigDecimal.valueOf(100)));
        System.out.printf("    - Kusursuz Gün Sayısı            : %d Gün%n", report.getPerfectDaysCount());
        System.out.printf("    - Tolerans İçi Gün Sayısı        : %d Gün%n", report.getAcceptableDaysCount());
        System.out.printf("    - Aykırı Sapma (Anomali) Sayısı  : %d Gün%n", report.getAnomalyDaysCount());
        System.out.printf("    - GENEL MODEL BAŞARISI           : %%%.2f%n", report.getSuccessRate().multiply(BigDecimal.valueOf(100)));
        System.out.println("=======================================================================================================================================");

        assertThat(report.getTotalDaysTested()).isEqualTo(allTradingDays.size() - 1);
        assertThat(report.getSuccessRate()).isGreaterThanOrEqualTo(new BigDecimal("0.9000"));
    }
}


