package com.fonmap.application.service.drift;

import com.fonmap.application.service.drift.dto.DriftedHoldingDto;
import com.fonmap.domain.entity.Fund;
import com.fonmap.domain.entity.FundSnapshot;
import com.fonmap.domain.entity.Holding;
import com.fonmap.domain.entity.Instrument;
import com.fonmap.domain.enums.AssetClass;
import com.fonmap.infrastructure.client.dto.MarketPriceDto;
import com.fonmap.infrastructure.repository.FundSnapshotRepository;
import com.fonmap.infrastructure.repository.HoldingRepository;
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
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.*;

/**
 * WeightDriftServiceTest — Ağırlık Kayması Servisi Matematiksel Birim Testleri
 * ==============================================================================
 *
 * AMAÇ:
 * Bu test sınıfı, Formül 4.2'nin matematiksel doğruluğunu, normalizasyon kuralını,
 * fiyat eksikliği ve short pozisyon istisnalarını doğrular.
 */
@ExtendWith(MockitoExtension.class)
class WeightDriftServiceTest {

    @Mock
    private HoldingRepository holdingRepository;

    @Mock
    private FundSnapshotRepository fundSnapshotRepository;

    @Mock
    private PriceService priceService;

    @InjectMocks
    private WeightDriftService weightDriftService;

    private Instrument thyaoInstrument;
    private Instrument aselsInstrument;
    private Instrument repoInstrument;

    @BeforeEach
    void setUp() {
        thyaoInstrument = Instrument.builder()
                .id(1L)
                .ticker("THYAO")
                .isinCode("TRATHYAO91M5")
                .assetClass(AssetClass.EQUITY)
                .build();

        aselsInstrument = Instrument.builder()
                .id(2L)
                .ticker("ASELS")
                .isinCode("TRAASELS91H2")
                .assetClass(AssetClass.EQUITY)
                .build();

        repoInstrument = Instrument.builder()
                .id(3L)
                .ticker("REPO")
                .assetClass(AssetClass.DEPOSIT)
                .build();
    }

    @Test
    @DisplayName("Formül 4.2 Matematiksel Doğrulama: %50-%50 başlayan iki hisseden biri %50 artınca ağırlıklar %60-%40 olmalıdır")
    void shouldCalculateCorrectDriftForTwoEquities() {
        // Senaryo:
        // Portföy başlangıç: %50 THYAO (P_0 = 100 TL), %50 ASELS (P_0 = 100 TL)
        // Dün akşam kapanış: THYAO = 150 TL (+%50), ASELS = 100 TL (%0)
        // Beklenen ara değerler:
        // THYAO ham pay: 0.50 * (150/100) = 0.75
        // ASELS ham pay: 0.50 * (100/100) = 0.50
        // Toplam pay: 0.75 + 0.50 = 1.25
        // Normalize THYAO yeni ağırlık: 0.75 / 1.25 = 0.600000 (%60.00)
        // Normalize ASELS yeni ağırlık: 0.50 / 1.25 = 0.400000 (%40.00)

        Holding thyaoHolding = Holding.builder()
                .id(UUID.randomUUID())
                .instrument(thyaoInstrument)
                .weightRatio(new BigDecimal("0.5000"))
                .reportPrice(new BigDecimal("100.00"))
                .isShort(false)
                .build();

        Holding aselsHolding = Holding.builder()
                .id(UUID.randomUUID())
                .instrument(aselsInstrument)
                .weightRatio(new BigDecimal("0.5000"))
                .reportPrice(new BigDecimal("100.00"))
                .isShort(false)
                .build();

        List<Holding> holdings = List.of(thyaoHolding, aselsHolding);

        // PriceService dünkü kapanışları döner
        Map<String, MarketPriceDto> priceMap = new HashMap<>();
        priceMap.put("THYAO", MarketPriceDto.builder()
                .symbol("THYAO")
                .currentPrice(new BigDecimal("155.00"))
                .previousClose(new BigDecimal("150.00"))
                .build());
        priceMap.put("ASELS", MarketPriceDto.builder()
                .symbol("ASELS")
                .currentPrice(new BigDecimal("101.00"))
                .previousClose(new BigDecimal("100.00"))
                .build());

        when(priceService.getPrices(anyList())).thenReturn(priceMap);

        // İcra
        List<DriftedHoldingDto> results = weightDriftService.calculateDrift(holdings);

        // Doğrulama
        assertThat(results).hasSize(2);

        DriftedHoldingDto thyaoDto = results.stream().filter(d -> d.getTicker().equals("THYAO")).findFirst().orElseThrow();
        DriftedHoldingDto aselsDto = results.stream().filter(d -> d.getTicker().equals("ASELS")).findFirst().orElseThrow();

        // Büyüme katsayıları
        assertThat(thyaoDto.getGrowthFactor()).isEqualByComparingTo("1.500000");
        assertThat(aselsDto.getGrowthFactor()).isEqualByComparingTo("1.000000");

        // Nihai ağırlıklar
        assertThat(thyaoDto.getEffectiveWeight()).isEqualByComparingTo("0.600000");
        assertThat(aselsDto.getEffectiveWeight()).isEqualByComparingTo("0.400000");

        // Toplam normalize ağırlık tam 1.0 (%100) olmalı
        BigDecimal totalSum = thyaoDto.getEffectiveWeight().add(aselsDto.getEffectiveWeight());
        assertThat(totalSum).isEqualByComparingTo("1.000000");
    }

    @Test
    @DisplayName("Fiyat Verisi Olmayan Varlık Testi: Repo ve nakit gibi fiyatı bulunamayan kalemlerde growthFactor=1.0 olmalıdır")
    void shouldHandleMissingOrFixedPricesGracefully() {
        Holding thyaoHolding = Holding.builder()
                .id(UUID.randomUUID())
                .instrument(thyaoInstrument)
                .weightRatio(new BigDecimal("0.7000"))
                .reportPrice(new BigDecimal("200.00"))
                .isShort(false)
                .build();

        Holding repoHolding = Holding.builder()
                .id(UUID.randomUUID())
                .instrument(repoInstrument)
                .weightRatio(new BigDecimal("0.3000"))
                .reportPrice(null) // Repo için borsa hisse fiyatı olmaz
                .isShort(false)
                .build();

        Map<String, MarketPriceDto> priceMap = new HashMap<>();
        priceMap.put("THYAO", MarketPriceDto.builder()
                .symbol("THYAO")
                .previousClose(new BigDecimal("200.00")) // Fiyat aynı kalmış
                .build());
        // REPO için fiyat servisi hiçbir şey dönmüyor

        when(priceService.getPrices(anyList())).thenReturn(priceMap);

        List<DriftedHoldingDto> results = weightDriftService.calculateDrift(List.of(thyaoHolding, repoHolding));

        DriftedHoldingDto repoDto = results.stream().filter(d -> d.getTicker().equals("REPO")).findFirst().orElseThrow();
        assertThat(repoDto.getGrowthFactor()).isEqualByComparingTo("1.000000");
        assertThat(repoDto.getEffectiveWeight()).isEqualByComparingTo("0.300000");
    }

    @Test
    @DisplayName("Short Pozisyon Testi: Hisse %20 artarsa short pozisyonun büyüme katsayısı 0.80'e gerilemelidir")
    void shouldHandleShortPositionsCorrectly() {
        Holding shortHolding = Holding.builder()
                .id(UUID.randomUUID())
                .instrument(thyaoInstrument)
                .weightRatio(new BigDecimal("0.2000"))
                .reportPrice(new BigDecimal("100.00"))
                .isShort(true) // Kısa pozisyon
                .build();

        Holding cashHolding = Holding.builder()
                .id(UUID.randomUUID())
                .instrument(repoInstrument)
                .weightRatio(new BigDecimal("0.8000"))
                .reportPrice(null)
                .isShort(false)
                .build();

        Map<String, MarketPriceDto> priceMap = new HashMap<>();
        priceMap.put("THYAO", MarketPriceDto.builder()
                .symbol("THYAO")
                .previousClose(new BigDecimal("120.00")) // %20 arttı
                .build());

        when(priceService.getPrices(anyList())).thenReturn(priceMap);

        List<DriftedHoldingDto> results = weightDriftService.calculateDrift(List.of(shortHolding, cashHolding));

        DriftedHoldingDto shortDto = results.stream().filter(d -> d.getTicker().equals("THYAO")).findFirst().orElseThrow();

        // Short katsayısı: 2.0 - (120/100) = 0.800000
        assertThat(shortDto.getGrowthFactor()).isEqualByComparingTo("0.800000");
    }

    @Test
    @DisplayName("Fund Snapshot Delegasyon Testi: Fon koduna göre en güncel snapshot bulunup holdingleri drift ettirilmelidir")
    void shouldCalculateDriftForLatestFundSnapshot() {
        UUID snapshotId = UUID.randomUUID();
        Fund fund = Fund.builder().code("THF").build();
        FundSnapshot snapshot = FundSnapshot.builder().id(snapshotId).fund(fund).snapshotDate(LocalDate.now()).build();

        Holding holding = Holding.builder()
                .id(UUID.randomUUID())
                .instrument(thyaoInstrument)
                .weightRatio(new BigDecimal("1.0000"))
                .reportPrice(new BigDecimal("100.00"))
                .isShort(false)
                .build();

        when(fundSnapshotRepository.findLatestByFundCode(eq("THF"), any(PageRequest.class)))
                .thenReturn(List.of(snapshot));
        when(holdingRepository.findBySnapshotIdWithInstrument(snapshotId))
                .thenReturn(List.of(holding));

        Map<String, MarketPriceDto> priceMap = new HashMap<>();
        priceMap.put("THYAO", MarketPriceDto.builder().symbol("THYAO").previousClose(new BigDecimal("100.00")).build());
        when(priceService.getPrices(anyList())).thenReturn(priceMap);

        List<DriftedHoldingDto> results = weightDriftService.calculateDriftForLatestFundSnapshot("THF");

        assertThat(results).hasSize(1);
        assertThat(results.get(0).getEffectiveWeight()).isEqualByComparingTo("1.000000");
    }

    @Test
    @DisplayName("Kaldıraçlı ve Serbest Fon Testi (Kılavuz Bölüm 9.2): Toplam ağırlık %100'ü aştığında (örn: %140), kaldıraç ezilmemeli ve brüt toplam korunmalıdır")
    void shouldPreserveLeverageForSerbestAndLeveragedFunds() {
        // Kılavuz Bölüm 9.2 Örneği:
        // Repo: %90, Teminat: %10, VİOP THYAO: %40 -> Toplam = %140 (1.4000)
        Holding repoHolding = Holding.builder()
                .id(UUID.randomUUID())
                .instrument(repoInstrument)
                .weightRatio(new BigDecimal("0.9000"))
                .reportPrice(null)
                .isShort(false)
                .build();

        Holding cashHolding = Holding.builder()
                .id(UUID.randomUUID())
                .instrument(Instrument.builder().ticker("TEMINAT").assetClass(AssetClass.DEPOSIT).build())
                .weightRatio(new BigDecimal("0.1000"))
                .reportPrice(null)
                .isShort(false)
                .build();

        Holding viopHolding = Holding.builder()
                .id(UUID.randomUUID())
                .instrument(thyaoInstrument)
                .weightRatio(new BigDecimal("0.4000"))
                .reportPrice(new BigDecimal("100.00"))
                .isShort(false)
                .build();

        // THYAO 100 TL'den 150 TL'ye (1.5 katına) yükselsin
        Map<String, MarketPriceDto> priceMap = new HashMap<>();
        priceMap.put("THYAO", MarketPriceDto.builder()
                .symbol("THYAO")
                .previousClose(new BigDecimal("150.00"))
                .build());

        when(priceService.getPrices(anyList())).thenReturn(priceMap);

        List<DriftedHoldingDto> results = weightDriftService.calculateDrift(List.of(repoHolding, cashHolding, viopHolding));

        assertThat(results).hasSize(3);

        DriftedHoldingDto viopDto = results.stream().filter(d -> d.getTicker().equals("THYAO")).findFirst().orElseThrow();
        DriftedHoldingDto repoDto = results.stream().filter(d -> d.getTicker().equals("REPO")).findFirst().orElseThrow();
        DriftedHoldingDto cashDto = results.stream().filter(d -> d.getTicker().equals("TEMINAT")).findFirst().orElseThrow();

        // VİOP büyüme katsayısı: 1.500000
        assertThat(viopDto.getGrowthFactor()).isEqualByComparingTo("1.500000");

        // Toplam ağırlık suni olarak 1.0'e (%100) EZİLMEMELİ, brüt kaldıraç olan 1.40 (%140) olarak kalmalıdır!
        BigDecimal totalEffectiveSum = viopDto.getEffectiveWeight()
                .add(repoDto.getEffectiveWeight())
                .add(cashDto.getEffectiveWeight());
        assertThat(totalEffectiveSum).isEqualByComparingTo("1.400000");

        // Göreceli olarak değer kazanan VİOP'un ağırlığı %40'tan %52.50'ye yükselmiş olmalı
        assertThat(viopDto.getEffectiveWeight()).isEqualByComparingTo("0.525000");
    }
}
