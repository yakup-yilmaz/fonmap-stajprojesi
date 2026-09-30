package com.fonmap.application.service.drift;

import com.fonmap.application.service.drift.dto.DriftedHoldingDto;
import com.fonmap.domain.entity.FundSnapshot;
import com.fonmap.domain.entity.Holding;
import com.fonmap.infrastructure.client.dto.MarketPriceDto;
import com.fonmap.infrastructure.repository.FundSnapshotRepository;
import com.fonmap.infrastructure.repository.HoldingRepository;
import com.fonmap.infrastructure.service.PriceService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;
import java.util.stream.Collectors;

/**
 * WeightDriftService — Ağırlık Kayması (Weight Drift) Hesaplama Servisi
 * ======================================================================
 *
 * FİNANSAL ARKA PLAN VE MATEMATİKSEL TEMEL:
 * ----------------------------------------
 * Yatırım fonları portföy dağılım raporlarını (PDF) ayda sadece 1 kez yayımlar (t_0).
 * Örneğin rapor 31 Ağustos tarihlidir. Ancak biz bugün (t seansı, örn. 23 Eylül)
 * canlı getiri tahmini hesaplamak istediğimizde aradan 23 gün geçmiştir.
 *
 * Bu 23 gün boyunca:
 * - THYAO hissesi %30 değer kazanmış olabilir.
 * - ASELS hissesi %10 değer kaybetmiş olabilir.
 *
 * Bir hisse değer kazandıkça, fonun kasasındaki payı kendiliğinden BÜYÜR.
 * Değer kaybeden hissenin payı ise KÜÇÜLÜR.
 * Eğer biz 23 gün önceki raporda yazan eski ağırlıkları (%10, %5) bugünkü seansa
 * doğrudan uygularsak ciddi tahmin sapmaları (tracking error) oluşur.
 *
 * İşte bu servis, dünkü resmi borsa kapanış fiyatlarını (P_i(t-1)) baz alarak
 * portföydeki tüm hisselerin seans açılışındaki GÜNCEL FİİLİ AĞIRLIKLARINI (w_i(t))
 * hesaplar.
 *
 * SPESİFİKASYON FORMÜLÜ (Formül 4.2):
 * -----------------------------------
 *           w_i(t_0) * [ P_i(t-1) / P_i(t_0) ]
 * w_i(t) = ------------------------------------
 *          Σ_j [ w_j(t_0) * [ P_j(t-1) / P_j(t_0) ] ]
 *
 * Burada:
 * - w_i(t_0) : PDF'ten okunan başlangıç ağırlığı (holding.weightRatio)
 * - P_i(t_0) : Rapor günündeki borsa kapanış fiyatı (holding.reportPrice)
 * - P_i(t-1) : Dün akşamki resmi borsa kapanış fiyatı (PriceService -> previousClose)
 * - G_i      : Varlığın fiyat büyüme çarpanı [ P_i(t-1) / P_i(t_0) ]
 *
 * ÇALIŞMA ZAMANI:
 * Her sabah borsa açılmadan önce (09:50) çalıştırılır ve gün içi seans süresince
 * bu taze ağırlıklar getiri hesaplama motoruna (ReturnCalculationService) girdi olur.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WeightDriftService {

    private final HoldingRepository holdingRepository;
    private final FundSnapshotRepository fundSnapshotRepository;
    private final PriceService priceService;

    /**
     * Ara hesaplamalarda yuvarlama hatalarını önlemek için yüksek hassasiyet (10 basamak).
     */
    private static final int CALC_SCALE = 10;

    /**
     * Veritabanı ve DTO katmanında saklanacak nihai ağırlık hassasiyeti (6 basamak).
     * Örnek: 0.123456 (%12.3456)
     */
    private static final int FINAL_SCALE = 6;

    /**
     * Bir hisse/varlık listesini alır ve Formül 4.2'yi uygulayarak
     * her bir pozisyonun güncellenmiş dinamik ağırlığını detaylı DTO listesi olarak döner.
     *
     * @param holdings Fon portföyündeki varlık satırları listesi
     * @return Her bir hissenin güncel ağırlık ve fiyat çarpanını içeren DTO listesi
     */
    public List<DriftedHoldingDto> calculateDrift(List<Holding> holdings) {
        if (holdings == null || holdings.isEmpty()) {
            log.warn("[WeightDriftService] İşlenecek holding listesi boş.");
            return Collections.emptyList();
        }

        // 1. AŞAMA: Portföydeki tüm enstrümanların borsa sembollerini (ticker) topla
        List<String> tickers = holdings.stream()
                .filter(h -> h.getInstrument() != null && h.getInstrument().getTicker() != null)
                .map(h -> h.getInstrument().getTicker())
                .distinct()
                .collect(Collectors.toList());

        // 2. AŞAMA: PriceService üzerinden tek hamlede (toplu) dünkü kapanış fiyatlarını çek
        // Redis önbelleği ve gerekirse dış sağlayıcılar burada şeffaf olarak devreye girer.
        Map<String, MarketPriceDto> priceMap = priceService.getPrices(tickers);

        // 3. AŞAMA: Her bir pozisyon için fiyat büyüme katsayısını (G_i) ve ham güncel payını hesapla
        List<DriftedHoldingDto> intermediateList = new ArrayList<>(holdings.size());
        BigDecimal totalAdjustedSum = BigDecimal.ZERO;
        BigDecimal totalInitialSum = BigDecimal.ZERO;

        for (Holding holding : holdings) {
            String ticker = holding.getInstrument() != null ? holding.getInstrument().getTicker() : "UNKNOWN";
            String isin = holding.getInstrument() != null ? holding.getInstrument().getIsinCode() : null;
            BigDecimal initialWeight = holding.getWeightRatio() != null ? holding.getWeightRatio() : BigDecimal.ZERO;
            BigDecimal reportPrice = holding.getReportPrice();
            boolean isShort = Boolean.TRUE.equals(holding.getIsShort());

            totalInitialSum = totalInitialSum.add(initialWeight);

            // Dünkü kapanış fiyatını (P_t-1) bul
            MarketPriceDto priceDto = priceMap.get(ticker);
            BigDecimal previousClose = null;
            if (priceDto != null) {
                previousClose = priceDto.getPreviousClose() != null ? priceDto.getPreviousClose() : priceDto.getCurrentPrice();
            }

            // Büyüme katsayısını hesapla (G_i)
            BigDecimal growthFactor = calculateGrowthFactor(reportPrice, previousClose, isShort);

            // Ham güncellenmiş pay: w'_i = w_i(t_0) * G_i
            BigDecimal unnormalizedWeight = initialWeight.multiply(growthFactor);

            // Toplam portföy payı toplamına ekle (Payda: Σ w'_j)
            totalAdjustedSum = totalAdjustedSum.add(unnormalizedWeight);

            DriftedHoldingDto dto = DriftedHoldingDto.builder()
                    .holdingId(holding.getId())
                    .ticker(ticker)
                    .isinCode(isin)
                    .initialWeight(initialWeight)
                    .reportPrice(reportPrice)
                    .previousClose(previousClose)
                    .growthFactor(growthFactor.setScale(FINAL_SCALE, RoundingMode.HALF_UP))
                    .effectiveWeight(unnormalizedWeight) // Geçici olarak ham pay yazıldı, 4. adımda güncellenecek
                    .isShort(isShort)
                    .build();

            intermediateList.add(dto);
        }

        // 4. AŞAMA: Portföy Büyüme Oranına Göre Güncel Ağırlıkların Hesaplanması
        // Kılavuz İki Ayrı Finansal Durumu Tanımlar:
        //
        // 1) Standart Hisse / Karma Fonlar (totalInitialSum ≈ %100, aralık: %90 - %110):
        //    Kılavuz Formül 4.2 ve Satır 1110 gereği:
        //    "Normalizasyon: Payların toplamına bölünerek portföy toplam ağırlığının tam %100 (1.000000) olması matematiksel olarak garanti edilir."
        //    w_i(t) = [ w_i(t_0) * G_i ] / Σ [ w_j(t_0) * G_j ]
        //
        // 2) Kaldıraçlı ve Serbest Fonlar (totalInitialSum > %110 veya < %90):
        //    Kılavuz Bölüm 9.2 gereği kaldıraçlı/serbest fonlarda (VİOP teminatı, borçlanma)
        //    brüt pozisyonlar suni olarak %100'e bölünerek seyreltilmez (kaldıraç yok edilmez)!
        //    Toplam brüt büyüklük (W_0 = totalInitialSum) korunarak göreceli kayma uygulanır:
        //    w_i(t) = ( [ w_i(t_0) * G_i ] / Σ [ w_j(t_0) * G_j ] ) * totalInitialSum
        if (totalAdjustedSum.compareTo(BigDecimal.ZERO) > 0 && totalInitialSum.compareTo(BigDecimal.ZERO) > 0) {
            boolean isStandardFund = totalInitialSum.compareTo(new BigDecimal("0.90")) >= 0
                    && totalInitialSum.compareTo(new BigDecimal("1.10")) <= 0;

            for (DriftedHoldingDto dto : intermediateList) {
                BigDecimal scaledWeight;
                if (isStandardFund) {
                    // Standart Fonlar: Kılavuz Formül 4.2 gereği tam %100'e (1.000000) normalize edilir
                    scaledWeight = dto.getEffectiveWeight()
                            .divide(totalAdjustedSum, FINAL_SCALE, RoundingMode.HALF_UP);
                } else {
                    // Kaldıraçlı / Serbest Fonlar: Kılavuz Bölüm 9.2 gereği brüt kaldıracı koru
                    scaledWeight = dto.getEffectiveWeight()
                            .multiply(totalInitialSum)
                            .divide(totalAdjustedSum, FINAL_SCALE, RoundingMode.HALF_UP);
                }
                dto.setEffectiveWeight(scaledWeight);
            }
        } else {
            // Olağanüstü durum: Toplam ağırlık sıfırsa başlangıç ağırlıklarına geri dön
            log.warn("[WeightDriftService] Toplam düzeltilmiş ağırlık sıfır veya negatif! Başlangıç ağırlıkları korundu.");
            for (DriftedHoldingDto dto : intermediateList) {
                dto.setEffectiveWeight(dto.getInitialWeight().setScale(FINAL_SCALE, RoundingMode.HALF_UP));
            }
        }

        log.debug("[WeightDriftService] Toplam {} pozisyon için ağırlık kayması hesaplandı. Toplam ham portföy çarpanı: {}",
                intermediateList.size(), totalAdjustedSum.setScale(4, RoundingMode.HALF_UP));

        return intermediateList;

    }

    /**
     * CalculationEngine için pratik yardımcı metod:
     * Holding ID'sini doğrudan güncel normalize edilmiş ağırlığa (effectiveWeight) eşleyen bir harita döner.
     *
     * @param holdings Varlık listesi
     * @return Map<HoldingID, EffectiveWeight>
     */
    public Map<UUID, BigDecimal> calculateEffectiveWeightMap(List<Holding> holdings) {
        List<DriftedHoldingDto> driftList = calculateDrift(holdings);
        Map<UUID, BigDecimal> map = new HashMap<>(driftList.size());
        for (DriftedHoldingDto dto : driftList) {
            if (dto.getHoldingId() != null) {
                map.put(dto.getHoldingId(), dto.getEffectiveWeight());
            }
        }
        return map;
    }

    /**
     * Belirli bir snapshot ID'sine ait holdingleri veritabanından tek sorguda çeker
     * ve ağırlık kaymasını hesaplar.
     *
     * @param snapshotId Snapshot benzersiz kimliği
     * @return Ağırlık kayması hesaplanmış DTO listesi
     */
    public List<DriftedHoldingDto> calculateDriftForSnapshot(UUID snapshotId) {
        if (snapshotId == null) {
            throw new IllegalArgumentException("Snapshot ID boş olamaz.");
        }
        List<Holding> holdings = holdingRepository.findBySnapshotIdWithInstrument(snapshotId);
        return calculateDrift(holdings);
    }

    /**
     * Fon koduna göre (Örn: "THF", "TTE") en güncel snapshot'ı bulur ve ağırlık kaymasını hesaplar.
     *
     * @param fundCode Fon borsa kodu
     * @return Ağırlık kayması hesaplanmış DTO listesi
     */
    public List<DriftedHoldingDto> calculateDriftForLatestFundSnapshot(String fundCode) {
        if (fundCode == null || fundCode.isBlank()) {
            throw new IllegalArgumentException("Fon kodu boş olamaz.");
        }
        List<FundSnapshot> snapshots = fundSnapshotRepository.findLatestByFundCode(fundCode.trim().toUpperCase(), PageRequest.of(0, 1));
        if (snapshots.isEmpty()) {
            log.warn("[WeightDriftService] Fon '{}' için henüz kayıtlı bir snapshot bulunamadı.", fundCode);
            return Collections.emptyList();
        }
        return calculateDriftForSnapshot(snapshots.get(0).getId());
    }

    /**
     * Tek bir menkul kıymetin fiyat büyüme katsayısını (G_i) hesaplar.
     *
     * KURALLAR:
     * 1. Eğer rapor fiyatı (P_t0) veya dünkü borsa kapanış fiyatı (P_t-1) yoksa, sıfırsa veya negatifse:
     *    Varlıkta fiyat hareketi olmamış varsayılır -> G_i = 1.0 (Repo, mevduat, bono veya yeni hisseler).
     * 2. Standart Pozisyon (Long):
     *    G_i = P_t-1 / P_t0
     *    Örnek: 100 TL'den 130 TL'ye çıkmışsa G_i = 1.30 (hissenin fon içindeki ağırlığı artar).
     * 3. Açığa Satış / VİOP Kısa Pozisyon (Short):
     *    Hisse yükselirse pozisyon zarar eder, dolayısıyla portföydeki göreceli ağırlığı küçülür.
     *    G_i = max(0, 1.0 - [(P_t-1 - P_t0) / P_t0]) = max(0, 2.0 - [P_t-1 / P_t0])
     *    Örnek: Hisse %30 artmışsa short pozisyon katsayısı 1.0 - 0.30 = 0.70 olur.
     */
    private BigDecimal calculateGrowthFactor(BigDecimal reportPrice, BigDecimal previousClose, boolean isShort) {
        if (reportPrice == null || reportPrice.compareTo(BigDecimal.ZERO) <= 0 ||
            previousClose == null || previousClose.compareTo(BigDecimal.ZERO) <= 0) {
            // Fiyat verisi yoksa veya varlık nakit/repo ise nötr katsayı (1.0) dön
            return BigDecimal.ONE;
        }

        try {
            // Ham fiyat oranı: P_i(t-1) / P_i(t_0)
            BigDecimal priceRatio = previousClose.divide(reportPrice, CALC_SCALE, RoundingMode.HALF_UP);

            if (!isShort) {
                // Standart Long pozisyon: G_i = priceRatio
                return priceRatio;
            } else {
                // Short pozisyon: G_i = 2.0 - priceRatio (0'ın altına düşemez)
                BigDecimal shortRatio = BigDecimal.valueOf(2.0).subtract(priceRatio);
                return shortRatio.compareTo(BigDecimal.ZERO) < 0 ? BigDecimal.ZERO : shortRatio;
            }
        } catch (Exception e) {
            log.warn("[WeightDriftService] Fiyat büyüme katsayısı hesaplanırken hata oluştu: reportPrice={}, previousClose={}. Nötr (1.0) kabul edildi.",
                    reportPrice, previousClose);
            return BigDecimal.ONE;
        }
    }
}
