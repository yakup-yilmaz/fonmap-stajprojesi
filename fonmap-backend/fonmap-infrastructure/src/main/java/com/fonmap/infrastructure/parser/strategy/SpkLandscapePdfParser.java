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
 * =========================================================================================
 * 🏛️ STANDART SPK YATAY (LANDSCAPE) PORTFÖY RAPORU AYRIŞTIRICISI
 * =========================================================================================
 * 
 * 📌 NE İŞE YARAR? (JUNIOR MÜHENDİS REHBERİ):
 * Sermaye Piyasası Kurulu (SPK) standartlarına göre hazırlanan ve Türkiye'deki
 * portföy
 * yönetim şirketlerinin yaklaşık %90'ı tarafından kullanılan resmi yatay
 * (Landscape)
 * portföy dağılım raporlarını çözen ana ayrıştırıcı stratejimizdir.
 * 
 * 🔍 BU STRATEJİNİN KAPSAMI:
 * - Tera Portföy (THF, TLY, TMV, DOH...)
 * - Ak Portföy (ICZ...)
 * - İş Portföy Yatay Fonları (TI2...)
 * 
 * 📐 BİÇİMSEL VE MATEMATİKSEL İMZA:
 * 1. Sayfa Oryantasyonu: Yatay (Landscape - En > Boy).
 * 2. Sayı Düzeni: Türkçe standart (Binlik ayracı nokta '.', ondalık ayracı
 * virgül ',').
 * 3. Tablo Sütun Düzeni:
 * [MENKUL KIYMET] [İHRAÇÇI] [VADE] [ISIN] [NOMİNAL DEĞER] [ALIŞ FİYATI] [RAYİÇ
 * DEĞER] [TOPLAM TUTAR] [GRUP %] [PORTFÖY %]
 */
@Component
@Slf4j
public class SpkLandscapePdfParser implements PdfParserStrategy {

    public static final String STRATEGY_NAME = "SPK_LANDSCAPE_STRATEGY";

    /**
     * Tarih arama deseni: "31/08/2026" veya "31.08.2026"
     */
    private static final Pattern DATE_PATTERN = Pattern.compile("\\b(\\d{1,2}[./]\\d{1,2}[./]\\d{4})\\b");

    /**
     * Fon toplam değeri / portföy büyüklüğü satırı arama deseni
     */
    private static final Pattern TNV_PATTERN = Pattern.compile(
            "(?:FON\\s+(?:TOPLAM|PORTFÖY)\\s+DEĞERİ|PORTFÖY\\s+DEĞERİ|NET\\s+VARLIK\\s+DEĞERİ|(?:D\\.\\s*)?TOPLAM\\s+DEĞER)\\s*(?:\\(TL\\))?\\s*[:=-]?\\s*([0-9.,]+)",
            Pattern.CASE_INSENSITIVE);

    @Override
    public boolean supports(String fundCode, String managerName) {
        // Standart SPK Yatay Ayrıştırıcısı herhangi bir yönetici veya fon koduna
        // sabitlenmez.
        // Tüm yönlendirme sayfa oryantasyonu ve SPK tablo başlıkları üzerinden dinamik
        // olarak
        // Content Sniffing (sniffScore) ile gerçekleştirilir.
        return false;
    }

    @Override
    public int sniffScore(byte[] pdfContent, String textSample, boolean isLandscape) {
        // Eğer hem İş Portföy hem de 16-22 haneli Takasbank saklama kodları varsa IsPortfoyPdfParser'a bırak
        if (textSample != null && (textSample.contains("İŞ PORTFÖY") || textSample.contains("IS PORTFOY"))) {
            if (textSample.matches("(?s).*\\b\\d{16,22}\\b.*")) {
                return 20;
            }
        }


        boolean hasSpkHeader = textSample != null && (
                textSample.contains("III-FON PORTFÖY DEĞERİ TABLOSU")
                || textSample.contains("III - FON PORTFÖY DEĞERİ TABLOSU")
                || (textSample.contains("MENKUL KIYMET") && textSample.contains("RAYİÇ DEĞER") && textSample.contains("GRUP TOPLAMI"))
        );

        // Sayfa oryantasyonu dikey (portrait/950x1200) olsa dahi SPK Tablo III yasal başlığı varsa
        // bu dosya kesinlikle SPK standart yatay tablosudur!
        if (!isLandscape && !hasSpkHeader) {
            return 0;
        }

        int score = hasSpkHeader ? 120 : (isLandscape ? 50 : 20);

        // 2. Kriter: SPK Resmi Tablo Yasal Başlıkları (Türkiye standart şablonu)
        if (textSample != null) {
            if (hasSpkHeader) {
                score += 50; // En güçlü SPK yasal imza (Toplam 170)
            }
            if (textSample.contains("MENKUL KIYMET") || textSample.contains("RAYİÇ DEĞER")
                    || textSample.contains("GRUP TOPLAMI") || textSample.contains("PORTFÖY DAĞILIM")
                    || textSample.contains("FON PORTFÖY DEĞERİ")) {
                score += 40;
            }
            // 3. Kriter: Türkçe Sayı Formatı (Nokta binlik, virgül ondalık)
            if (textSample.matches("(?s).*\\d{1,3}\\.\\d{3},\\d{2}.*") || textSample.contains(",00")
                    || textSample.contains(",50")) {
                score += 20;
            }
        }
        return score;
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

        log.info("[SpkLandscapePdfParser] PDF ayrıştırma başlatılıyor: Fon='{}', Boyut={} bayt", fundCode,
                pdfContent.length);

        try (PDDocument document = Loader.loadPDF(pdfContent)) {
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setSortByPosition(true); // Tabloları yatay hizada doğru satır-sütun sırasıyla okuması için şart!

            String fullText = stripper.getText(document);
            String[] lines = fullText.split("\\r?\\n");

            log.debug("[SpkLandscapePdfParser] Toplam {} satır metin ayıklandı.", lines.length);

            // 1. Üst Başlık Bilgilerini Çözümle (Header Extraction)
            LocalDate reportDate = extractReportDate(lines);
            BigDecimal totalNetAssetValue = extractTnv(lines);
            BigDecimal stockRatio = null; // SPK standart yatay tablolarda hisse oranı tablodaki tüm varlık satırlarından net toplanır
            BigDecimal viopCashRatio = null;

            // 2. Tablo Satırlarını Gezerek Alt Varlıkları Çıkar (Holding Extraction)
            List<ParsedHoldingDto> holdings = extractHoldings(lines, totalNetAssetValue);

            log.info("[SpkLandscapePdfParser] Ayrıştırma tamamlandı: Fon='{}', Tarih={}, TNV={}, Toplam Varlık={}",
                    fundCode, reportDate, totalNetAssetValue, holdings.size());

            return ParsedPortfolioReportDto.builder()
                    .fundCode(fundCode != null ? fundCode.toUpperCase(Locale.ROOT) : "UNKNOWN")
                    .reportDate(reportDate)
                    .totalNetAssetValue(totalNetAssetValue)
                    .stockRatio(stockRatio)
                    .viopCashRatio(viopCashRatio)
                    .holdings(holdings)
                    .build();
        } catch (IOException e) {
            log.error("[SpkLandscapePdfParser] PDFBox belge okuma hatası: Fon='{}' -> {}", fundCode, e.getMessage(), e);
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
            String line = lines[i].toUpperCase(Locale.ROOT);
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
        return null;
    }

    /**
     * Fon Toplam Net Aktif Değerini (TNV TL) ayıklar. Hem Türkçe hem İngilizce formatı destekler.
     */
    private BigDecimal extractTnv(String[] lines) {
        for (String line : lines) {
            Matcher matcher = TNV_PATTERN.matcher(line);
            if (matcher.find()) {
                BigDecimal tnv = PdfParsingUtils.parseAutoDecimal(matcher.group(1));
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
        boolean inSummarySection = false;
        for (String line : lines) {
            String upper = line.toUpperCase(Locale.ROOT);
            if (upper.contains("II-") || upper.contains("II -") || upper.contains("PORTFÖY DEĞERİ BİLGİSİ") || upper.contains("PORTFÖY DAĞILIMI")) {
                inSummarySection = true;
            }
            if (upper.contains("III-") || upper.contains("III -") || upper.contains("PORTFÖY DEĞERİ TABLOSU")) {
                break; // Portföy detay tablosuna girdik, özet bitti
            }
            if (inSummarySection && upper.contains(keyword) && (upper.contains("%") || upper.contains("ORAN"))) {
                String[] tokens = upper.split("\\s+");
                for (int i = tokens.length - 1; i >= 0; i--) {
                    if (tokens[i].contains("%") || tokens[i].contains(",") || tokens[i].contains(".")) {
                        BigDecimal ratio = PdfParsingUtils.parsePercentage(tokens[i], false);
                        if (ratio.compareTo(new BigDecimal("0.05")) >= 0 && ratio.compareTo(BigDecimal.ONE) <= 0) {
                            return ratio;
                        }
                    }
                }
            }
        }
        return null;
    }

    // =====================================================================================
    // 🛠️ 2. TABLO VE VARLIK SATIRI AYRIŞTIRMA (HOLDINGS EXTRACTION)
    // =====================================================================================

    /**
     * PDF'in tablo kısımlarını tarayarak her bir menkul kıymeti DTO olarak çıkarır.
     */
    private List<ParsedHoldingDto> extractHoldings(String[] lines, BigDecimal tnv) {
        Map<String, ParsedHoldingDto> consolidated = new LinkedHashMap<>();
        AssetClass currentSection = AssetClass.EQUITY; // Varsayılan BIST hisseleri
        boolean inHoldingsTable = false;

        for (int i = 0; i < lines.length; i++) {
            String trimmed = lines[i].trim();
            if (trimmed.isBlank()) {
                continue;
            }

            String upper = trimmed.toUpperCase(Locale.ROOT);

            // Portföy tablosu başlangıcı kontrolü (III-FON PORTFÖY DEĞERİ TABLOSU veya PORTFÖY DAĞILIMI)
            if (upper.contains("PORTFÖY DEĞERİ TABLOSU") || upper.contains("PORTFOY DEGERI TABLOSU")
                    || upper.contains("PORTFÖY DAĞILIMI") || upper.contains("III-FON") || upper.contains("III - FON")) {
                inHoldingsTable = true;
                continue;
            }

            if (!inHoldingsTable) {
                continue;
            }

            // Tablo bitişi kontrolü: IV-FON TOPLAM DEĞERİ TABLOSU veya VII-PORTFÖYDEN SATIŞLAR başladığında
            // menkul kıymet detayları biter (396 sayfalık alım-satım defteri taranmadan erken çıkılır).
            String normalizedUpper = upper
                    .replace('\u00A0', ' ')
                    .replaceAll("\\s+", " ")
                    .replace("İ", "I").replace("ı", "I")
                    .replace("Ğ", "G").replace("ğ", "G")
                    .replace("Ü", "U").replace("ü", "U")
                    .replace("Ş", "S").replace("ş", "S")
                    .replace("Ö", "O").replace("ö", "O")
                    .replace("Ç", "C").replace("ç", "C");

            if (normalizedUpper.contains("IV-FON") || normalizedUpper.contains("IV - FON") || normalizedUpper.contains("IV- FON") || normalizedUpper.contains("IV. FON")
                    || normalizedUpper.contains("FON TOPLAM DEGERI") || normalizedUpper.contains("FON TOPLAM")
                    || normalizedUpper.contains("VII-PORTFOY") || normalizedUpper.contains("VII - PORTFOY")
                    || normalizedUpper.contains("PORTFOYDEN SATIS") || normalizedUpper.contains("PORTFOYE ALIS")) {
                log.info("[SpkLandscapePdfParser] 🛑 Portföy dağılım tablosu sonu tespit edildi. İşlem defteri atlanıyor.");
                break;
            }

            // 1. Toplam veya başlık satırlarını atla (Sütun başlıklarının bölüm sanılmasını engeller)
            if (isHeaderOrSummaryLine(upper)) {
                continue;
            }

            // 2. Bölüm Değişimlerini Algıla (Genişletilmiş sözlük: T.REPO, Y.Fonu hibrit desteği)
            if (upper.contains("PAYLAR") || upper.contains("HİSSE SENET") || upper.contains("HISSE SENET")
                    || upper.equals("A.PAY") || upper.startsWith("A.PAY ")) {
                currentSection = AssetClass.EQUITY;
                continue;
            } else if (upper.contains("TÜREV ARAÇLAR") || upper.contains("VİOP") || upper.contains("VIOP")) {
                currentSection = AssetClass.VIOP;
                continue;
            } else if (upper.contains("T.REPO") || upper.contains("T. REPO") || upper.contains("TERS REPO")
                    || upper.contains("PARA PİYASASI") || upper.contains("BPP")) {
                currentSection = AssetClass.DEPOSIT;
                continue;
            } else if (upper.contains("TAHVİL") || upper.contains("BONO") || upper.contains("KİRA SERTİFİKA")
                    || upper.contains("SUKUK") || upper.contains("BORÇLANMA ARAÇLARI") || upper.contains("BORCLANMA ARACLARI")) {
                currentSection = AssetClass.BOND;
                continue;
            } else if (upper.contains("Y.FONU") || upper.contains("YATIRIM FONU") || upper.contains("FON KATILMA PAY")) {
                currentSection = AssetClass.FUND;
                continue;
            }

            // 3. Satırı Ayrıştırmayı Dene
            ParsedHoldingDto holding = parseLine(lines, i, currentSection, tnv);
            if (holding != null && holding.getWeightRatio() != null
                    && holding.getWeightRatio().compareTo(BigDecimal.ZERO) > 0) {
                String aggKey = holding.getTicker() + "_" + holding.getAssetClassHint();
                ParsedHoldingDto existing = consolidated.get(aggKey);
                if (existing == null) {
                    consolidated.put(aggKey, holding);
                } else {
                    existing.setTotalValue(existing.getTotalValue().add(holding.getTotalValue()));
                    existing.setWeightRatio(existing.getWeightRatio().add(holding.getWeightRatio()));
                    if (holding.getNominalAmount() != null) {
                        existing.setNominalAmount(existing.getNominalAmount() != null
                                ? existing.getNominalAmount().add(holding.getNominalAmount())
                                : holding.getNominalAmount());
                    }
                }
            }
        }

        return new ArrayList<>(consolidated.values());
    }

    /**
     * Tek bir tablo satırını menkul kıymet DTO'suna dönüştürür.
     */
    private ParsedHoldingDto parseLine(String[] lines, int currentIndex, AssetClass sectionHint, BigDecimal tnv) {
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

        // Satırdaki ilk belirteç genellikle Menkul Kıymet Ticker kodudur (Örn: "THYAO", "AKBNK", "AKBNK.E")
        String rawTicker = tokens[0].trim();
        if (rawTicker.contains(".E") || rawTicker.contains(".e")) {
            rawTicker = rawTicker.replaceAll("(?i)\\.E$", "");
        }
        String tickerCandidate = rawTicker.replaceAll("[^A-Z0-9_]", "").trim();
        if (tickerCandidate.length() < 2 || tickerCandidate.matches("^\\d+$")
                || "GRUP".equals(tickerCandidate) || "TOPLAM".equals(tickerCandidate)
                || "GENEL".equals(tickerCandidate) || "VADEY".equals(tickerCandidate)
                || "FON".equals(tickerCandidate) || "PORTFOY".equals(tickerCandidate)
                || "PORTFOYU".equals(tickerCandidate) || "BORCLAR".equals(tickerCandidate)
                || "ALACAKLAR".equals(tickerCandidate) || "HAZIR".equals(tickerCandidate)) {
            return null;
        }

        String normLine = line.toUpperCase(Locale.ROOT)
                .replace('\u00A0', ' ')
                .replaceAll("\\s+", " ")
                .replace("İ", "I").replace("ı", "I");
        if (normLine.contains("FON PORTFOY") || normLine.contains("FON TOPLAM")) {
            return null;
        }

        // Sayısal değerleri topla (Nominal Değer, Alış Fiyatı, Kapanış Fiyatı, Toplam Değer)
        List<BigDecimal> numbers = extractNumbersFromTokens(tokens);

        BigDecimal nominalAmount = BigDecimal.ZERO;
        BigDecimal unitCost = BigDecimal.ZERO;
        BigDecimal reportPrice = BigDecimal.ZERO;
        BigDecimal totalValue = BigDecimal.ZERO;

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

        // 🛡️ 1. ADIM: Pozisyonel Sütun Kilidi (Son 3 sütunda resmi yüzde alanını tara)
        BigDecimal parsedWeight = null;
        int lastIndex = tokens.length - 1;

        for (int offset = 0; offset <= 2 && (lastIndex - offset) >= 0; offset++) {
            String token = tokens[lastIndex - offset];
            if (token.contains("%")) {
                BigDecimal candidate = PdfParsingUtils.parsePercentage(token, false);
                if (candidate != null) {
                    parsedWeight = candidate;
                    break; // En sağdaki resmi yüzde sütunu yakalandı
                }
            } else if (token.matches("^-?[0-9]{1,3}(?:[.,][0-9]{3})*(?:[.,][0-9]+)?$")) {
                BigDecimal candidate = PdfParsingUtils.parsePercentage(token, false);
                if (candidate != null && candidate.compareTo(BigDecimal.ZERO) > 0 && candidate.compareTo(BigDecimal.ONE) <= 0) {
                    parsedWeight = candidate;
                    break;
                }
            }
        }

        // 🛡️ 2. ADIM: Matematiksel Çapraz Sağlama Sigortası (Sanity Cross-Check)
        // Eğer totalValue ve TNV mevcutsa: teorikAğırlık = totalValue / TNV
        if (totalValue.compareTo(BigDecimal.ZERO) > 0 && tnv != null && tnv.compareTo(BigDecimal.ZERO) > 0) {
            BigDecimal theoreticalWeight = totalValue.divide(tnv, 6, RoundingMode.HALF_UP);

            // Eğer okunan oran %0 ise veya okunan oran matematiksel orandan bariz sapmışsa matematiksel gerçeği esas al
            if (parsedWeight == null || parsedWeight.compareTo(BigDecimal.ZERO) == 0
                    || parsedWeight.subtract(theoreticalWeight).abs().compareTo(new BigDecimal("0.05")) > 0) {
                parsedWeight = theoreticalWeight;
            }
        }

        if (parsedWeight == null || parsedWeight.compareTo(BigDecimal.ZERO) <= 0) {
            return null; // Sıfır veya negatif oranlı küsurat satırlarını portföye dahil etme
        }

        BigDecimal weightRatio = parsedWeight;

        // Negatif lot veya short tespiti
        boolean isShort = nominalAmount.compareTo(BigDecimal.ZERO) < 0
                || PdfParsingUtils.isNegativeOrParenthesized(tokens[1])
                || (tokens.length > 2 && PdfParsingUtils.isNegativeOrParenthesized(tokens[2]));

        if (nominalAmount.compareTo(BigDecimal.ZERO) < 0) {
            nominalAmount = nominalAmount.abs();
            isShort = true;
        }

        AssetClass effectiveClass = sectionHint;
        String upperLine = line.toUpperCase(Locale.ROOT);
        if (upperLine.contains("TEMİNAT") || upperLine.contains("TEMINAT") || upperLine.contains("TAKASBANK")
                || upperLine.contains("TERS REPO") || upperLine.contains("T.REPO")) {
            effectiveClass = AssetClass.DEPOSIT;
        } else if (upperLine.contains("F_") || upperLine.contains("VİOP") || upperLine.contains("VIOP")) {
            effectiveClass = AssetClass.VIOP;
        }

        // TRT ile başlayan Hazine bonosu/tahvilleri repo veya tahvil sınıfına atanmalıdır, asla hisse olamaz
        if (tickerCandidate.startsWith("TRT") || (isinCode != null && isinCode.startsWith("TRT"))) {
            effectiveClass = (effectiveClass == AssetClass.DEPOSIT) ? AssetClass.DEPOSIT : AssetClass.BOND;
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

    private List<BigDecimal> extractNumbersFromTokens(String[] tokens) {
        List<BigDecimal> numbers = new ArrayList<>();
        for (String token : tokens) {
            if (token.matches("^[0-9]{7,}$")) {
                continue;
            }
            // Hem Türkçe ("12.123.757,50") hem İngilizce ("12,123,757.50") hem de yalın ("44.70" / "44,70") sayıları tanı
            if (token.matches("^-?[0-9]{1,3}(?:[.,][0-9]{3})*(?:[.,][0-9]+)?$")
                    || token.matches("^-?[0-9]+(?:[.,][0-9]+)?$")) {
                BigDecimal val = PdfParsingUtils.parseAutoDecimal(token);
                if (val.compareTo(BigDecimal.ZERO) != 0) {
                    numbers.add(val);
                }
            }
        }
        return numbers;
    }

    private boolean isHeaderOrSummaryLine(String line) {
        String clean = line.toUpperCase(Locale.ROOT)
                .replace('\u00A0', ' ')
                .replaceAll("\\s+", " ")
                .replace("İ", "I").replace("ı", "I")
                .replace("Ğ", "G").replace("ğ", "G")
                .replace("Ü", "U").replace("ü", "U")
                .replace("Ş", "S").replace("ş", "S")
                .replace("Ö", "O").replace("ö", "O")
                .replace("Ç", "C").replace("ç", "C");

        return clean.startsWith("MENKUL KIYMET")
                || clean.contains("GRUP TOPLAM")
                || clean.contains("GENEL TOPLAM")
                || clean.contains("FON PORTFOY")
                || clean.contains("FON TOPLAM")
                || clean.contains("HAZIR DEGER")
                || clean.contains("ALACAKLAR")
                || clean.contains("BORCLAR")
                || clean.contains("BIRIM ALIS")
                || clean.contains("RAYIC DEGER")
                || clean.contains("NOMINAL DEGER")
                || clean.contains("IHRACCI KURUM")
                || clean.contains("VADEYE KALAN")
                || clean.contains("PORTFOY DEGERI TABLOSU")
                || clean.contains("ISIN KODU")
                || clean.contains("SOZLESM")
                || clean.contains("ISKONTO");
    }
}
