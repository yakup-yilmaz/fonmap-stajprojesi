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
import java.time.LocalDate;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Pardus Portföy KAP PDF Rapor Ayrıştırıcısı (KHA ve Pardus Fonları).
 * 
 * =========================================================================================
 * 🎯 NE İŞE YARAR? (JUNIOR MÜHENDİS REHBERİ):
 * =========================================================================================
 * Bu sınıf; Pardus Portföy Yönetimi A.Ş.'ye ait 'KHA' (Pardus Portföy Birinci Hisse Senedi
 * Serbest Fon) raporlarını satır satır okuyan uzman Strateji sınıfımızdır.
 * 
 * 🔍 PARDUS FORMATININ ÖZELLİKLERİ:
 * 1. Sayı Formatı: Türkçe standart (Nokta binlik, virgül ondalık).
 * 2. Menkul Kıymetler: A1CAP, A1YEN, ALARK, ARCLK, DOHOL, ENJSA, HALKB, MAVI gibi hisseler
 *    ve Borsa Dışı Ters Repo pozisyonları.
 * 3. Bölüm Yapısı: Hisse senetleri ve Borsa Dışı Repo blokları halinde sıralanır.
 */
@Component
@Slf4j
public class PardusPdfParser implements PdfParserStrategy {

    private static final String STRATEGY_NAME = "PARDUS_STRATEGY";

    private static final Set<String> SUPPORTED_FUNDS = Set.of("KHA");

    private static final Pattern DATE_PATTERN = Pattern.compile("\\b(\\d{1,2}[./]\\d{1,2}[./]\\d{4})\\b");

    private static final Pattern TNV_PATTERN = Pattern.compile(
            "(?:FON\\s+(?:TOPLAM|PORTFÖY)\\s+DEĞERİ|PORTFÖY\\s+DEĞERİ|NET\\s+VARLIK\\s+DEĞERİ)\\s*[:=-]?\\s*([0-9.,]+)",
            Pattern.CASE_INSENSITIVE
    );

    @Override
    public boolean supports(String fundCode, String managerName) {
        if (fundCode != null && SUPPORTED_FUNDS.contains(fundCode.toUpperCase().trim())) {
            return true;
        }
        return managerName != null && managerName.toUpperCase().contains("PARDUS");
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

        log.info("[PardusPdfParser] Pardus Portföy PDF ayrıştırma başlatılıyor: Fon='{}'", fundCode);

        try (PDDocument document = Loader.loadPDF(pdfContent)) {
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setSortByPosition(true);

            String fullText = stripper.getText(document);
            String[] lines = fullText.split("\\r?\\n");

            // 1. Üst Başlık Bilgileri
            LocalDate reportDate = extractReportDate(lines);
            BigDecimal totalNetAssetValue = extractTnv(lines);
            BigDecimal stockRatio = extractRatio(lines, "HİSSE");
            BigDecimal viopCashRatio = extractRatio(lines, "VİOP");

            // 2. Alt Varlıkları Çıkar
            List<ParsedHoldingDto> holdings = extractHoldings(lines);

            log.info("[PardusPdfParser] Ayrıştırma tamamlandı: Fon='{}', Tarih={}, TNV={}, Toplam Varlık={}",
                    fundCode, reportDate, totalNetAssetValue, holdings.size());

            return ParsedPortfolioReportDto.builder()
                    .fundCode(fundCode != null ? fundCode.toUpperCase() : "KHA")
                    .reportDate(reportDate != null ? reportDate : LocalDate.now())
                    .totalNetAssetValue(totalNetAssetValue)
                    .stockRatio(stockRatio)
                    .viopCashRatio(viopCashRatio)
                    .holdings(holdings)
                    .build();
        } catch (IOException e) {
            log.error("[PardusPdfParser] PDF okuma hatası: Fon='{}' -> {}", fundCode, e.getMessage(), e);
            throw e;
        }
    }

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
            if (line.contains("RAPOR TARİHİ") || line.contains("DÖNEM") || line.contains("TARİH:")) {
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

    private BigDecimal extractRatio(String[] lines, String keyword) {
        for (String line : lines) {
            String upper = line.toUpperCase();
            if (upper.contains(keyword) && (upper.contains("%") || upper.contains(","))) {
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

    private List<ParsedHoldingDto> extractHoldings(String[] lines) {
        List<ParsedHoldingDto> results = new ArrayList<>();
        AssetClass currentSection = AssetClass.EQUITY;
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

            // Tablo bitişi kontrolü: IV-FON TOPLAM DEĞERİ veya FON TOPLAM DEĞERİ başladığında detaylar biter!
            if (upper.contains("FON TOPLAM DEĞERİ")
                    || upper.contains("FON PORTFÖY DEĞERİ:")
                    || upper.contains("IV-FON")
                    || upper.contains("IV - FON")
                    || upper.contains("IV. FON")
                    || upper.contains("ÖDÜNÇ PAY")
                    || upper.contains("ÖDÜNÇ İŞLEMLERİ")) {
                inHoldingsTable = false;
                break;
            }

            // Bölüm tespiti
            if (upper.contains("PAYLAR") || upper.contains("HİSSE SENET")) {
                currentSection = AssetClass.EQUITY;
                continue;
            } else if (upper.contains("TÜREV") || upper.contains("VİOP")) {
                currentSection = AssetClass.VIOP;
                continue;
            } else if (upper.contains("TERS REPO") || upper.contains("PARA PİYASASI") || upper.contains("BORSA DIŞI REPO")) {
                currentSection = AssetClass.DEPOSIT;
                continue;
            } else if (upper.contains("TAHVİL") || upper.contains("BONO")) {
                currentSection = AssetClass.BOND;
                continue;
            } else if (upper.contains("FON KATILMA")) {
                currentSection = AssetClass.FUND;
                continue;
            }

            if (isHeaderOrSummaryLine(upper)) {
                continue;
            }

            ParsedHoldingDto holding = parseLine(lines, i, currentSection);
            if (holding != null && holding.getWeightRatio() != null && holding.getWeightRatio().compareTo(BigDecimal.ZERO) != 0) {
                results.add(holding);
            }
        }

        return results;
    }

    private ParsedHoldingDto parseLine(String[] lines, int currentIndex, AssetClass sectionHint) {
        String line = lines[currentIndex].trim();
        String[] tokens = line.split("\\s+");
        if (tokens.length < 4) {
            return null;
        }

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

        String tickerCandidate = tokens[0].replaceAll("[^A-Z0-9_]", "").trim();
        if (tickerCandidate.length() < 2 || tickerCandidate.matches("^\\d+$")) {
            return null;
        }

        if (tickerCandidate.equals("VADEY") || tickerCandidate.equals("VADEYE") || tickerCandidate.equals("NOMINAL")
                || tickerCandidate.equals("MENKUL") || tickerCandidate.equals("HISSE") || tickerCandidate.equals("GRUP")
                || tickerCandidate.equals("FON") || tickerCandidate.equals("BORCLAR") || tickerCandidate.equals("EBORLAR")
                || tickerCandidate.equals("ALACAKLAR") || tickerCandidate.equals("CALACAKLAR") || tickerCandidate.equals("TOPLAM")
                || tickerCandidate.equals("GENEL")) {
            return null;
        }

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
                reportPrice = totalValue.divide(nominalAmount, 6, java.math.RoundingMode.HALF_UP);
            }
        }

        // Değeri ve adedi 0 olan satırlar varlık değildir
        if (nominalAmount.compareTo(BigDecimal.ZERO) <= 0 && totalValue.compareTo(BigDecimal.ZERO) <= 0) {
            return null;
        }

        boolean isShort = nominalAmount.compareTo(BigDecimal.ZERO) < 0
                || PdfParsingUtils.isNegativeOrParenthesized(tokens[1]);

        if (nominalAmount.compareTo(BigDecimal.ZERO) < 0) {
            nominalAmount = nominalAmount.abs();
            isShort = true;
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
                .assetClassHint(sectionHint)
                .build();
    }

    private List<BigDecimal> extractNumbersFromTokens(String[] tokens) {
        List<BigDecimal> numbers = new ArrayList<>();
        for (String token : tokens) {
            if (token.matches("^[0-9]{7,}$")) {
                continue;
            }
            if (token.matches("^-?[0-9]{1,3}(?:\\.[0-9]{3})*(?:,[0-9]+)?$") || token.matches("^-?[0-9]+(?:,[0-9]+)?$")) {
                BigDecimal val = PdfParsingUtils.parseTurkishDecimal(token);
                if (val.compareTo(BigDecimal.ZERO) != 0) {
                    numbers.add(val);
                }
            }
        }
        return numbers;
    }

    private boolean isHeaderOrSummaryLine(String line) {
        return line.startsWith("MENKUL KIYMET")
                || line.startsWith("TOPLAM")
                || line.startsWith("GRUP TOPLAMI")
                || line.startsWith("GENEL TOPLAM")
                || line.contains("BİRİM ALIŞ")
                || line.contains("RAYİÇ DEĞER")
                || line.contains("NOMİNAL DEĞER")
                || line.contains("İHRAÇCI KURUM")
                || line.contains("VADEYE KALAN")
                || line.contains("FON PORTFÖY DEĞERİ TABLOSU");
    }
}
