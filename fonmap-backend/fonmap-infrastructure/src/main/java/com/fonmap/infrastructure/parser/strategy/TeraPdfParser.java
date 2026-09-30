package com.fonmap.infrastructure.parser.strategy;

import com.fonmap.domain.enums.AssetClass;
import com.fonmap.infrastructure.parser.dto.ParsedHoldingDto;
import com.fonmap.infrastructure.parser.dto.ParsedPortfolioReportDto;
import com.fonmap.infrastructure.parser.util.PdfParsingUtils;
import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Tera Portföy Yatay (Landscape) Formatlı KAP PDF Rapor Ayrıştırıcısı.
 * 
 * =========================================================================================
 * 🎯 NE İŞE YARAR? (JUNIOR MÜHENDİS REHBERİ):
 * =========================================================================================
 * Bu sınıf; Tera Portföy Yönetimi A.Ş.'ye ait fonların (THF, TLY, TMV, DOH)
 * SPK standartlarındaki yatay (Landscape) formatlı aylık "Portföy Dağılım Raporu"
 * PDF dosyalarını satır satır okuyan ilk somut Strateji (Strategy) uzmanımızdır.
 * 
 * 🔍 TERA FORMATININ KARAKTERİSTİK ÖZELLİKLERİ:
 * 1. Sayı Formatı: Türkçe standart (Nokta binlik ayracı, virgül ondalık ayracı: 74.819.592.001,69).
 * 2. Tablo Sütun Düzeni:
 *    [MENKUL KIYMET] [İHRAÇÇI] [VADE] [ISIN KODU] [NOMİNAL DEĞER] [ALIŞ FİYATI] [RAYİÇ DEĞER] [TOPLAM TUTAR] [GRUP %] [PORTFÖY %]
 * 3. Bölüm Başlıkları:
 *    - "A) PAYLAR" -> BIST Hisse Senetleri (EQUITY)
 *    - "B) BORÇLANMA ARAÇLARI" / "ÖZEL SEKTÖR TAHVİL/BONO" -> Sabit Getirili (BOND)
 *    - "C) KİRA SERTİFİKALARI / SUKUK" -> Kira Sertifikaları (BOND)
 *    - "D) PARA PİYASASI / TERS REPO" -> Gecelik Faiz Getirisi (DEPOSIT)
 *    - "E) TÜREV ARAÇLAR / VİOP" -> Vadeli Kontratlar (VIOP)
 *    - "F) FON KATILMA PAYLARI" -> Diğer Yatırım Fonları (FUND)
 */
@Component
@Slf4j
public class TeraPdfParser implements PdfParserStrategy {

    private static final String STRATEGY_NAME = "TERA_LANDSCAPE_STRATEGY";

    /**
     * Bu stratejinin doğrudan sorumlu olduğu Tera fon kodları listesi.
     */
    private static final Set<String> SUPPORTED_FUNDS = Set.of("THF", "TLY", "TMV", "DOH");

    /**
     * Tarih arama deseni: "31/08/2026" veya "31.08.2026"
     */
    private static final Pattern DATE_PATTERN = Pattern.compile("\\b(\\d{1,2}[./]\\d{1,2}[./]\\d{4})\\b");

    /**
     * Fon toplam değeri / portföy büyüklüğü satırı arama deseni
     */
    private static final Pattern TNV_PATTERN = Pattern.compile(
            "(?:FON\\s+(?:TOPLAM|PORTFÖY)\\s+DEĞERİ|PORTFÖY\\s+DEĞERİ|NET\\s+VARLIK\\s+DEĞERİ)\\s*[:=-]?\\s*([0-9.,]+)",
            Pattern.CASE_INSENSITIVE
    );

    @Override
    public boolean supports(String fundCode, String managerName) {
        if (fundCode != null && SUPPORTED_FUNDS.contains(fundCode.toUpperCase().trim())) {
            return true;
        }
        return managerName != null && managerName.toUpperCase().contains("TERA");
    }

    @Override
    public String getStrategyName() {
        return STRATEGY_NAME;
    }

    @Override
    public ParsedPortfolioReportDto parse(byte[] pdfContent, String fundCode) throws Exception {
        if (pdfContent == null || pdfContent.length == 0) {
            throw new IllegalArgumentException("PDF içeriği boş olamaz: " + fundCode);
        }

        log.info("[TeraPdfParser] PDF ayrıştırma başlatılıyor: Fon='{}', Boyut={} bayt", fundCode, pdfContent.length);

        try (PDDocument document = Loader.loadPDF(pdfContent)) {
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setSortByPosition(true); // Tabloları yatay hizada düzgün okuması için kritik!

            String fullText = stripper.getText(document);
            String[] lines = fullText.split("\\r?\\n");

            log.debug("[TeraPdfParser] Toplam {} satır metin ayıklandı.", lines.length);

            // 1. Üst Başlık Bilgilerini Çözümle (Header Extraction)
            LocalDate reportDate = extractReportDate(lines);
            BigDecimal totalNetAssetValue = extractTnv(lines);
            BigDecimal stockRatio = extractRatio(lines, "HİSSE SENEDİ");
            BigDecimal viopCashRatio = extractRatio(lines, "VİOP");

            // 2. Tablo Satırlarını Gezerek Alt Varlıkları Çıkar (Holding Extraction)
            List<ParsedHoldingDto> holdings = extractHoldings(lines);

            log.info("[TeraPdfParser] Ayrıştırma tamamlandı: Fon='{}', Tarih={}, TNV={}, Toplam Varlık={}",
                    fundCode, reportDate, totalNetAssetValue, holdings.size());

            return ParsedPortfolioReportDto.builder()
                    .fundCode(fundCode != null ? fundCode.toUpperCase() : "UNKNOWN")
                    .reportDate(reportDate != null ? reportDate : LocalDate.now())
                    .totalNetAssetValue(totalNetAssetValue)
                    .stockRatio(stockRatio)
                    .viopCashRatio(viopCashRatio)
                    .holdings(holdings)
                    .build();
        } catch (IOException e) {
            log.error("[TeraPdfParser] PDFBox belge okuma hatası: Fon='{}' -> {}", fundCode, e.getMessage(), e);
            throw e;
        }
    }

    // =====================================================================================
    // 🛠️ 1. ÜST BAŞLIK AYRIŞTIRMA METOTLARI (HEADER PARSERS)
    // =====================================================================================

    /**
     * Raporun ait olduğu dönem sonu tarihini metin satırlarından ayıklar.
     */
    private LocalDate extractReportDate(String[] lines) {
        // 1. İlk 10 satırda ay-yıl kontrolü (Örn: "Ağustos-2026")
        for (int i = 0; i < Math.min(lines.length, 10); i++) {
            LocalDate monthYearDate = PdfParsingUtils.parseMonthYear(lines[i]);
            if (monthYearDate != null) {
                return monthYearDate;
            }
        }

        for (int i = 0; i < Math.min(lines.length, 50); i++) {
            String line = lines[i].toUpperCase();
            if (line.contains("RAPOR TARİHİ") || line.contains("DÖNEMİ") || line.contains("TARİH:")) {
                Matcher matcher = DATE_PATTERN.matcher(line);
                if (matcher.find()) {
                    LocalDate date = PdfParsingUtils.parseDate(matcher.group(1));
                    if (date != null) {
                        return date;
                    }
                }
            }
        }
        for (int i = 0; i < Math.min(lines.length, 30); i++) {
            Matcher matcher = DATE_PATTERN.matcher(lines[i]);
            if (matcher.find()) {
                LocalDate date = PdfParsingUtils.parseDate(matcher.group(1));
                if (date != null && date.getYear() >= 2020) {
                    return date;
                }
            }
        }
        return LocalDate.now();
    }

    /**
     * Fon Toplam Net Aktif Değerini (TNV TL) ayıklar.
     */
    private BigDecimal extractTnv(String[] lines) {
        for (String line : lines) {
            Matcher matcher = TNV_PATTERN.matcher(line);
            if (matcher.find()) {
                BigDecimal tnv = PdfParsingUtils.parseTurkishDecimal(matcher.group(1));
                if (tnv.compareTo(BigDecimal.ZERO) > 0) {
                    return tnv;
                }
            }
        }
        return BigDecimal.ZERO;
    }

    /**
     * Raporun özet tablosundaki genel hisse veya VİOP teminat oranını ayıklar.
     */
    private BigDecimal extractRatio(String[] lines, String keyword) {
        for (String line : lines) {
            String upper = line.toUpperCase();
            if (upper.contains(keyword) && (upper.contains("%") || upper.contains(","))) {
                // Satırdaki son yüzde/ondalık değeri çek
                String[] tokens = upper.split("\\s+");
                for (int i = tokens.length - 1; i >= 0; i--) {
                    if (tokens[i].contains(",") || tokens[i].contains("%")) {
                        BigDecimal ratio = PdfParsingUtils.parsePercentage(tokens[i], true);
                        if (ratio.compareTo(BigDecimal.ZERO) > 0) {
                            return ratio;
                        }
                    }
                }
            }
        }
        return BigDecimal.ZERO;
    }

    // =====================================================================================
    // 🛠️ 2. TABLO VE VARLIK SATIRI AYRIŞTIRMA (HOLDINGS EXTRACTION)
    // =====================================================================================

    /**
     * PDF'in tablo kısımlarını tarayarak her bir menkul kıymeti DTO olarak çıkarır.
     */
    private List<ParsedHoldingDto> extractHoldings(String[] lines) {
        List<ParsedHoldingDto> results = new ArrayList<>();
        AssetClass currentSection = AssetClass.EQUITY; // Varsayılan BIST hisseleri
        boolean inHoldingsTable = false;

        for (int i = 0; i < lines.length; i++) {
            String trimmed = lines[i].trim();
            if (trimmed.isBlank()) {
                continue;
            }

            String upper = trimmed.toUpperCase();

            // Portföy tablosu başlangıcı kontrolü (I ve II. bölümlerdeki özet verileri atlamak için)
            if (upper.contains("PORTFÖY DEĞERİ TABLOSU") || upper.contains("PORTFÖY DAĞILIMI")) {
                inHoldingsTable = true;
                continue;
            }

            if (!inHoldingsTable) {
                continue;
            }

            // Tablo bitişi kontrolü: IV-FON TOPLAM DEĞERİ TABLOSU başladığında menkul kıymet detayları biter!
            if (upper.contains("FON TOPLAM DEĞERİ TABLOSU") || upper.contains("IV-FON") || upper.contains("IV - FON")) {
                inHoldingsTable = false;
                break;
            }

            // 1. Bölüm Değişimlerini Algıla
            if (upper.contains("PAYLAR") || upper.contains("HİSSE SENET")) {
                currentSection = AssetClass.EQUITY;
                continue;
            } else if (upper.contains("TÜREV ARAÇLAR") || upper.contains("VİOP")) {
                currentSection = AssetClass.VIOP;
                continue;
            } else if (upper.contains("TERS REPO") || upper.contains("PARA PİYASASI") || upper.contains("BPP")) {
                currentSection = AssetClass.DEPOSIT;
                continue;
            } else if (upper.contains("TAHVİL") || upper.contains("BONO") || upper.contains("KİRA SERTİFİKA") || upper.contains("SUKUK")) {
                currentSection = AssetClass.BOND;
                continue;
            } else if (upper.contains("YATIRIM FONU") || upper.contains("FON KATILMA PAY")) {
                currentSection = AssetClass.FUND;
                continue;
            }

            // Toplam veya başlık satırlarını atla
            if (isHeaderOrSummaryLine(upper)) {
                continue;
            }

            // 2. Satırı Ayrıştırmayı Dene
            ParsedHoldingDto holding = parseLine(lines, i, currentSection);
            if (holding != null && holding.getWeightRatio() != null && holding.getWeightRatio().compareTo(BigDecimal.ZERO) != 0) {
                results.add(holding);
            }
        }

        return results;
    }

    /**
     * Tek bir tablo satırını menkul kıymet DTO'suna dönüştürür.
     */
    private ParsedHoldingDto parseLine(String[] lines, int currentIndex, AssetClass sectionHint) {
        String line = lines[currentIndex].trim();
        String[] tokens = line.split("\\s+");
        if (tokens.length < 4) {
            return null;
        }

        // Satırdaki ISIN kodunu ara; yoksa alt satırlara (şirket unvanı kaymalarına) bak
        Optional<String> isinOpt = PdfParsingUtils.extractIsin(line);
        if (isinOpt.isEmpty()) {
            for (int k = 1; k <= 4 && currentIndex + k < lines.length; k++) {
                String nextLine = lines[currentIndex + k];
                if (nextLine.contains("TL") || nextLine.startsWith("TOPLAM") || nextLine.contains("PORTFÖY")) {
                    break;
                }
                Optional<String> lookaheadIsin = PdfParsingUtils.extractIsin(nextLine);
                if (lookaheadIsin.isPresent()) {
                    isinOpt = lookaheadIsin;
                    break;
                }
            }
        }
        String isinCode = isinOpt.orElse(null);

        // Satırdaki ilk belirteç genellikle Menkul Kıymet Ticker kodudur (Örn: "THYAO", "AKBNK")
        String tickerCandidate = tokens[0].replaceAll("[^A-Z0-9_]", "").trim();
        if (tickerCandidate.length() < 2 || tickerCandidate.matches("^\\d+$")
                || "GRUP".equals(tickerCandidate) || "TOPLAM".equals(tickerCandidate) || "GENEL".equals(tickerCandidate)) {
            return null;
        }

        // Satırın en sonundaki belirteç "Fon Toplam Değerine Oranı (%)" sütunudur.
        // Sondan başa doğru geçerli bir portföy ağırlığı (0 < |w| <= 1.0 yani en fazla %100) ara.
        // Borsa ref kodları veya milyonluk TL tutarlarının ağırlık sanılmasını önler.
        BigDecimal weightRatio = BigDecimal.ZERO;
        for (int k = tokens.length - 1; k >= Math.max(0, tokens.length - 4); k--) {
            BigDecimal candidate = PdfParsingUtils.parsePercentage(tokens[k], true);
            if (candidate.compareTo(BigDecimal.ZERO) != 0 && candidate.abs().compareTo(BigDecimal.ONE) <= 0) {
                weightRatio = candidate;
                break;
            }
        }

        if (weightRatio.compareTo(BigDecimal.ZERO) == 0) {
            return null;
        }

        // Satır içindeki sayısal değerleri topla (Nominal Değer, Alış Fiyatı, Kapanış Fiyatı, Toplam Değer)
        List<BigDecimal> numbers = extractNumbersFromTokens(tokens);

        BigDecimal nominalAmount = BigDecimal.ZERO;
        BigDecimal unitCost = BigDecimal.ZERO;
        BigDecimal reportPrice = BigDecimal.ZERO;
        BigDecimal totalValue = BigDecimal.ZERO;

        // Borsa ref kodları (Örn: "80100511") hariç tutulduğunda:
        // [0] = Nominal Lot, [1] = Alış Fiyatı, [2] = Kapanış Fiyatı (P0), [3] = Toplam Rayiç Değer
        if (numbers.size() >= 4) {
            nominalAmount = numbers.get(0);
            unitCost = numbers.get(1);
            reportPrice = numbers.get(2);
            totalValue = numbers.get(3);
        } else if (numbers.size() >= 2) {
            nominalAmount = numbers.get(0);
            totalValue = numbers.get(numbers.size() - 1);
            if (nominalAmount.compareTo(BigDecimal.ZERO) > 0 && reportPrice.compareTo(BigDecimal.ZERO) == 0) {
                reportPrice = totalValue.divide(nominalAmount, 6, RoundingMode.HALF_UP);
            }
        }

        // Negatif lot veya short tespiti
        boolean isShort = nominalAmount.compareTo(BigDecimal.ZERO) < 0
                || PdfParsingUtils.isNegativeOrParenthesized(tokens[1])
                || (tokens.length > 2 && PdfParsingUtils.isNegativeOrParenthesized(tokens[2]));

        if (nominalAmount.compareTo(BigDecimal.ZERO) < 0) {
            nominalAmount = nominalAmount.abs();
            isShort = true;
        }

        AssetClass effectiveClass = sectionHint;
        String upperLine = line.toUpperCase();
        if (upperLine.contains("TEMİNAT") || upperLine.contains("TEMINAT") || upperLine.contains("TAKASBANK") || upperLine.contains("REPO")) {
            effectiveClass = AssetClass.DEPOSIT;
        } else if (upperLine.contains("F_") || upperLine.contains("VİOP") || upperLine.contains("VIOP")) {
            effectiveClass = AssetClass.VIOP;
        }

        return ParsedHoldingDto.builder()
                .ticker(tickerCandidate)
                .securityName(PdfParsingUtils.cleanSecurityName(line))
                .isinCode(isinCode)
                .nominalAmount(nominalAmount)
                .unitCost(unitCost)
                .reportPrice(reportPrice)
                .totalValue(totalValue)
                .weightRatio(weightRatio)
                .isShort(isShort)
                .assetClassHint(effectiveClass)
                .build();
    }

    /**
     * Satırdaki Türkçe formatlı tüm sayısal değerleri ayıklar.
     * Borsa ve Takasbank referans kodları (Örn: 80100511 gibi 7+ basamaklı düz tamsayılar) elenir.
     */
    private List<BigDecimal> extractNumbersFromTokens(String[] tokens) {
        List<BigDecimal> numbers = new ArrayList<>();
        for (String token : tokens) {
            // 7 veya daha fazla basamaklı düz tamsayılar Borsa/Takasbank referans numarasıdır, fiyat/lot değildir
            if (token.matches("^[0-9]{7,}$")) {
                continue;
            }
            // Sadece sayı, nokta, virgül ve eksi içerenleri dene
            if (token.matches("^-?[0-9]{1,3}(?:\\.[0-9]{3})*(?:,[0-9]+)?$") || token.matches("^-?[0-9]+(?:,[0-9]+)?$")) {
                BigDecimal val = PdfParsingUtils.parseTurkishDecimal(token);
                if (val.compareTo(BigDecimal.ZERO) != 0) {
                    numbers.add(val);
                }
            }
        }
        return numbers;
    }

    /**
     * Tablo başlığı veya ara toplam satırı olup olmadığını kontrol eder.
     */
    private boolean isHeaderOrSummaryLine(String line) {
        String clean = line.trim();
        return clean.startsWith("MENKUL KIYMET")
                || clean.contains("TOPLAM")
                || clean.contains("GRUP TOPLAMI")
                || clean.contains("GENEL TOPLAM")
                || clean.contains("FON PORTFÖY DEĞERİ")
                || clean.contains("BİRİM ALIŞ")
                || clean.contains("RAYİÇ DEĞER")
                || clean.contains("NOMİNAL DEĞER")
                || clean.contains("İHRAÇCI KURUM")
                || clean.contains("VADEYE KALAN")
                || clean.contains("PORTFÖY DEĞERİ TABLOSU");
    }
}
