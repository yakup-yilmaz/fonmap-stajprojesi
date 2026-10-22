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
 * 🏛️ GENERIC DİKEY PDF AYRIŞTIRICI STRATEJİSİ (GenericVerticalPdfParser)
 * -----------------------------------------------------------------------------------------
 * Türkiye portföy yönetim şirketlerinin KAP'a yüklediği standart dışı dikey
 * (Portrait)
 * PDF raporlarını esnek sütun ve sayı tarama kurallarıyla çözen genel amaçlı
 * fallback stratejisidir.
 *
 * ÖZELLİKLER:
 * 1. 'PdfParsingUtils.parseAutoDecimal' kullanarak hem Türkçe (1.250,50) hem
 * İngilizce (1,250.50)
 * sayı formatlarını otomatik çözer.
 * 2. Hisse, VİOP kontratları (F_), Ters Repo/BPP ve Tahvil/Bono varlık
 * sınıflarını otomatik ayrıştırır.
 * 3. SIFIR TOLERANS GÜVENLİK KALKANI: Eğer tablodan okunan veri eksik kalırsa
 * (örn: satırlar kaçmışsa),
 * sistemi zehirlememek için çökmeden açıklayıcı [UNSUPPORTED_LAYOUT] logu basar
 * ve reddeder.
 */
@Component
@Slf4j
public class GenericVerticalPdfParser implements PdfParserStrategy {

    private static final Pattern DATE_PATTERN = Pattern.compile("(\\d{2})[./-](\\d{2})[./-](\\d{4})");

    @Override
    public boolean supports(String fundCode, String managerName) {
        // Doğrudan kod eşleşmesi için değil, dinamik Content Sniffing fallback için
        // çalışır.
        return false;
    }

    @Override
    public int sniffScore(byte[] pdfContent, String textSample, boolean isLandscape) {
        // 1. Yatay (Landscape) PDF'ler kesinlikle SPK Landscape Parser'a bırakılır
        if (isLandscape) {
            return 0;
        }

        if (textSample == null || textSample.isBlank()) {
            return 0;
        }

        String upper = textSample.toUpperCase(Locale.ROOT);
 
        // 1.5. SPK Tablo III yasal başlığı varsa bu kesinlikle yatay SPK formatıdır; GenericVertical araya girmemelidir
        if (upper.contains("III-FON PORTFÖY DEĞERİ TABLOSU") || upper.contains("FON PORTFÖY DEĞERİ TABLOSU")) {
            return 10;
        }

        // 2. Eğer bilinen özel dikey şablonlar (İş Portföy, Pardus, Atlas) varsa,
        // onların kendi özel uzman parser'larının (100+ puan) öncelik alması için düşük
        // puan ver
        if (upper.contains("İŞ PORTFÖY") || upper.contains("IS PORTFOY")
                || upper.contains("PARDUS PORTFÖY") || upper.contains("ATLAS PORTFÖY")) {
            return 30; // Özel parser'lar eşleşmezse yine devreye girebilir
        }

        // 3. Genel Portföy Dağılım Raporu anahtar kelimelerini ara
        int score = 0;
        if (upper.contains("PORTFÖY DEĞERİ") || upper.contains("PORTFOY DEGERI") || upper.contains("PORTFÖY DAĞILIM")) {
            score += 30;
        }
        if (upper.contains("MENKUL KIYMET") || upper.contains("FON PORTFÖY")) {
            score += 20;
        }
        if (upper.contains("NOMİNAL") || upper.contains("NOMINAL") || upper.contains("RAYİÇ")
                || upper.contains("RAYIC")) {
            score += 20;
        }

        // Skor 60-70 bandında çıkar. Özel parser eşleşmediğinde (0 puan) en yüksek
        // puanı alıp seçilir!
        return score;
    }

    @Override
    public ParsedPortfolioReportDto parse(byte[] pdfContent, String fundCode) throws Exception {
        if (pdfContent == null || pdfContent.length == 0) {
            throw new IllegalArgumentException("PDF içeriği boş olamaz: " + fundCode);
        }

        log.info("[GenericVerticalPdfParser] Dikey PDF ayrıştırma başlatılıyor: Fon='{}', Boyut={} bayt",
                fundCode, pdfContent.length);

        try (PDDocument document = Loader.loadPDF(pdfContent)) {
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setSortByPosition(true);

            String fullText = stripper.getText(document);
            String[] lines = fullText.split("\\r?\\n");

            log.debug("[GenericVerticalPdfParser] Toplam {} satır metin ayıklandı.", lines.length);

            // 1. Üst Başlık Bilgilerini Ayıkla
            LocalDate reportDate = extractReportDate(lines);
            BigDecimal totalNetAssetValue = extractTnv(lines);
            BigDecimal stockRatio = extractRatio(lines, "HİSSE SENEDİ");
            if (stockRatio == null) {
                stockRatio = extractRatio(lines, "PAY");
            }
            BigDecimal viopCashRatio = extractRatio(lines, "VİOP");

            // 2. Tablo Satırlarını Esnek Tarama ile Çıkar
            List<ParsedHoldingDto> holdings = extractHoldings(lines);

            // 3. Sıfır Tolerans Güvenlik Doğrulaması (Sanity Check)
            BigDecimal totalWeight = BigDecimal.ZERO;
            for (ParsedHoldingDto h : holdings) {
                if (h.getWeightRatio() != null) {
                    totalWeight = totalWeight.add(h.getWeightRatio());
                }
            }

            log.info(
                    "[GenericVerticalPdfParser] Ön analiz: Fon='{}', Bulunan Varlık={}, Toplam Ağırlık=%{}, Rapor Hisse Beyanı=%{}",
                    fundCode, holdings.size(),
                    totalWeight.multiply(BigDecimal.valueOf(100)).setScale(2, RoundingMode.HALF_UP),
                    stockRatio != null ? stockRatio.multiply(BigDecimal.valueOf(100)).setScale(2, RoundingMode.HALF_UP)
                            : "YOK");

            // Güvenlik Kilidi: Eğer 3'ten az varlık çıkmışsa veya toplam ağırlık %40'ın
            // altındaysa tablo okunamamıştır!
            if (holdings.size() < 3 || totalWeight.compareTo(new BigDecimal("0.40")) < 0) {
                log.warn(
                        "[GenericVerticalPdfParser] [UNSUPPORTED_LAYOUT] Fon='{}' dikey tablosu güvenli bir şekilde ayrıştırılamadı. "
                                +
                                "Bulunan Varlık: {} adet, Toplam Ağırlık: %{}. Sisteme zehirli veri girmemesi için işlem reddedildi.",
                        fundCode, holdings.size(),
                        totalWeight.multiply(BigDecimal.valueOf(100)).setScale(2, RoundingMode.HALF_UP));
                throw new IllegalStateException(String.format(
                        "PDF formatı Generic Vertical Parser tarafından güvenli bir şekilde çözümlenemedi (Varlık: %d adet, Ağırlık: %%%.2f).",
                        holdings.size(), totalWeight.multiply(BigDecimal.valueOf(100))));
            }

            return ParsedPortfolioReportDto.builder()
                    .fundCode(fundCode != null ? fundCode.toUpperCase(Locale.ROOT) : "UNKNOWN")
                    .reportDate(reportDate)
                    .totalNetAssetValue(totalNetAssetValue)
                    .stockRatio(stockRatio)
                    .viopCashRatio(viopCashRatio)
                    .holdings(holdings)
                    .build();

        } catch (IOException e) {
            log.error("[GenericVerticalPdfParser] PDFBox belge okuma hatası: Fon='{}' -> {}", fundCode, e.getMessage(),
                    e);
            throw e;
        }
    }

    // =====================================================================================
    // 🛠️ TABLO VE VARLIK AYRIŞTIRMA (HOLDING EXTRACTION)
    // =====================================================================================

    private List<ParsedHoldingDto> extractHoldings(String[] lines) {
        List<ParsedHoldingDto> results = new ArrayList<>();
        boolean inHoldingsTable = false;
        AssetClass currentSection = AssetClass.EQUITY;

        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].trim();
            if (line.isEmpty()) {
                continue;
            }
            String upper = line.toUpperCase(Locale.ROOT);

            // Tablo başlangıç göstergeleri
            if (upper.contains("PORTFÖY DEĞERİ") || upper.contains("PORTFÖY DAĞILIMI")
                    || upper.contains("MENKUL KIYMET")) {
                inHoldingsTable = true;
                continue;
            }

            // Tablo bitiş göstergeleri
            if (inHoldingsTable && (upper.contains("TOPLAM PORTFÖY DEĞERİ") || upper.contains("FON TOPLAM DEĞERİ")
                    || upper.contains("IV-FON") || upper.contains("DİĞER VARLIKLAR"))) {
                if (upper.contains("FON TOPLAM DEĞERİ TABLOSU") || upper.contains("IV-FON")) {
                    break;
                }
            }

            if (!inHoldingsTable) {
                if (looksLikeHoldingLine(line)) {
                    inHoldingsTable = true;
                } else {
                    continue;
                }
            }

            // Varlık sınıfı bölüm başlıkları
            if (upper.contains("HİSSE SENEDİ") || upper.contains("PAYLAR") || upper.contains("HİSSELER")) {
                currentSection = AssetClass.EQUITY;
                continue;
            } else if (upper.contains("VİOP") || upper.contains("VIOP") || upper.contains("TÜREV")) {
                currentSection = AssetClass.VIOP;
                continue;
            } else if (upper.contains("TERS REPO") || upper.contains("REPO") || upper.contains("PARA PİYASASI")
                    || upper.contains("BPP")) {
                currentSection = AssetClass.DEPOSIT;
                continue;
            } else if (upper.contains("TAHVİL") || upper.contains("BONO") || upper.contains("KİRA SERTİFİKA")) {
                currentSection = AssetClass.BOND;
                continue;
            }

            // Başlık veya özet satırlarını atla
            if (isHeaderOrSummaryLine(upper)) {
                continue;
            }

            ParsedHoldingDto holding = parseLine(lines, i, currentSection);
            if (holding != null && holding.getWeightRatio() != null
                    && holding.getWeightRatio().compareTo(BigDecimal.ZERO) != 0) {
                results.add(holding);
            }
        }

        return results;
    }

    private ParsedHoldingDto parseLine(String[] lines, int currentIndex, AssetClass sectionHint) {
        String line = lines[currentIndex].trim();
        String[] tokens = line.split("\\s+");
        if (tokens.length < 3) {
            return null;
        }

        // 1. Ticker Kodu Tespiti (İlk 1-2 belirteç)
        String tickerCandidate = tokens[0].replaceAll("[^A-Z0-9_]", "").trim();
        if (tickerCandidate.length() < 2 || tickerCandidate.matches("^\\d+$")) {
            if (tokens.length > 3 && tokens[0].matches("^\\d+$")) {
                tickerCandidate = tokens[1].replaceAll("[^A-Z0-9_]", "").trim();
            } else {
                return null;
            }
        }

        if (tickerCandidate.equals("GRUP") || tickerCandidate.equals("TOPLAM") || tickerCandidate.equals("GENEL")
                || tickerCandidate.equals("FON") || tickerCandidate.equals("PORTFÖY") || tickerCandidate.equals("PORTFOY")
                || tickerCandidate.equals("BORCLAR") || tickerCandidate.equals("ALACAKLAR") || tickerCandidate.equals("HAZIR")) {
            return null;
        }

        // ISIN Arama
        Optional<String> isinOpt = PdfParsingUtils.extractIsin(line);
        String isinCode = isinOpt.orElse(null);

        // Varlık Sınıfı İpucu
        AssetClass effectiveClass = sectionHint;
        String upperLine = line.toUpperCase(Locale.ROOT);
        if (tickerCandidate.startsWith("F_") || upperLine.contains("VİOP") || upperLine.contains("VIOP")) {
            effectiveClass = AssetClass.VIOP;
        } else if (upperLine.contains("BPP") || upperLine.contains("REPO") || upperLine.contains("TAKASBANK")) {
            effectiveClass = AssetClass.DEPOSIT;
        } else if (upperLine.contains("BONO") || upperLine.contains("TAHVIL") || upperLine.contains("TRT")) {
            effectiveClass = AssetClass.BOND;
        }

        // Kısa Pozisyon (Short) Kontrolü
        boolean isShort = upperLine.contains("SHORT") || upperLine.contains("KISA") || line.contains("-");

        // Sayısal Token'ları Dinamik Çöz
        BigDecimal weightRatio = null;
        BigDecimal nominalAmount = null;
        BigDecimal totalValue = null;

        // Satırdaki son 4 belirteç arasında yüzdelik oran ve parasal değerleri ara
        for (int j = tokens.length - 1; j >= Math.max(1, tokens.length - 4); j--) {
            String token = tokens[j].trim();
            if (token.isEmpty() || token.equals("TL") || token.equals("%")) {
                continue;
            }

            BigDecimal parsed = PdfParsingUtils.parseAutoDecimal(token);
            if (parsed.compareTo(BigDecimal.ZERO) == 0) {
                continue;
            }

            // Portföy Oranı: Genellikle sonuncu veya sondan bir önceki sütundur
            if (weightRatio == null) {
                if (parsed.abs().compareTo(BigDecimal.valueOf(100)) <= 0) {
                    if (parsed.abs().compareTo(BigDecimal.ONE) > 0) {
                        weightRatio = parsed.divide(BigDecimal.valueOf(100), 6, RoundingMode.HALF_UP);
                    } else {
                        weightRatio = parsed.setScale(6, RoundingMode.HALF_UP);
                    }
                    continue;
                }
            }

            // Toplam Değer veya Nominal Değer
            if (totalValue == null && parsed.abs().compareTo(BigDecimal.valueOf(100)) > 0) {
                totalValue = parsed;
            } else if (nominalAmount == null && parsed.abs().compareTo(BigDecimal.valueOf(100)) > 0) {
                nominalAmount = parsed;
            }
        }

        if (weightRatio == null || weightRatio.compareTo(BigDecimal.ZERO) == 0) {
            return null;
        }

        BigDecimal reportPrice = BigDecimal.ONE;
        if (totalValue != null && nominalAmount != null && nominalAmount.compareTo(BigDecimal.ZERO) > 0) {
            reportPrice = totalValue.divide(nominalAmount, 6, RoundingMode.HALF_UP);
        }

        return ParsedHoldingDto.builder()
                .ticker(tickerCandidate)
                .securityName(tokens.length > 2 ? tokens[1] : tickerCandidate)
                .isinCode(isinCode)
                .nominalAmount(nominalAmount != null ? nominalAmount : BigDecimal.ONE)
                .totalValue(totalValue != null ? totalValue : BigDecimal.ZERO)
                .reportPrice(reportPrice)
                .weightRatio(weightRatio)
                .assetClassHint(effectiveClass)
                .isShort(isShort)
                .build();
    }

    private boolean looksLikeHoldingLine(String line) {
        String[] tokens = line.trim().split("\\s+");
        if (tokens.length < 3)
            return false;
        String first = tokens[0].replaceAll("[^A-Z0-9_]", "");
        return first.length() >= 3 && first.length() <= 8 && !first.matches("^\\d+$");
    }

    private boolean isHeaderOrSummaryLine(String upper) {
        String clean = upper.trim()
                .replace('\u00A0', ' ')
                .replaceAll("\\s+", " ")
                .replace("İ", "I").replace("ı", "I")
                .replace("Ğ", "G").replace("ğ", "G")
                .replace("Ü", "U").replace("ü", "U")
                .replace("Ş", "S").replace("ş", "S")
                .replace("Ö", "O").replace("ö", "O")
                .replace("Ç", "C").replace("ç", "C");

        return clean.contains("NOMINAL DEGER") || clean.contains("RAYIC DEGER")
                || clean.contains("GRUP TOPLAM") || clean.contains("FON PORTFOY")
                || clean.contains("FON TOPLAM") || clean.contains("MENKUL KIYMET")
                || clean.contains("IHRACCI") || clean.contains("BILGI")
                || clean.contains("SAYFA") || clean.startsWith("TOPLAM")
                || clean.startsWith("GENEL TOPLAM") || clean.contains("HAZIR DEGER")
                || clean.contains("ALACAKLAR") || clean.contains("BORCLAR");
    }

    // =====================================================================================
    // 🛠️ ÜST BAŞLIK AYRIŞTIRICILARI
    // =====================================================================================

    private LocalDate extractReportDate(String[] lines) {
        for (int i = 0; i < Math.min(lines.length, 15); i++) {
            LocalDate monthYear = PdfParsingUtils.parseMonthYear(lines[i]);
            if (monthYear != null) {
                return monthYear;
            }
            Matcher matcher = DATE_PATTERN.matcher(lines[i]);
            if (matcher.find()) {
                int day = Integer.parseInt(matcher.group(1));
                int month = Integer.parseInt(matcher.group(2));
                int year = Integer.parseInt(matcher.group(3));
                return LocalDate.of(year, month, day);
            }
        }
        return null;
    }

    private BigDecimal extractTnv(String[] lines) {
        for (int i = 0; i < Math.min(lines.length, 30); i++) {
            String upper = lines[i].toUpperCase(Locale.ROOT);
            if (upper.contains("FON TOPLAM DEĞERİ") || upper.contains("NET VARLIK DEĞERİ")) {
                String[] tokens = lines[i].split("\\s+");
                for (int j = tokens.length - 1; j >= 0; j--) {
                    BigDecimal val = PdfParsingUtils.parseAutoDecimal(tokens[j]);
                    if (val.compareTo(BigDecimal.valueOf(100_000)) > 0) {
                        return val;
                    }
                }
            }
        }
        return null;
    }

    private BigDecimal extractRatio(String[] lines, String keyword) {
        for (int i = 0; i < Math.min(lines.length, 35); i++) {
            String upper = lines[i].toUpperCase(Locale.ROOT);
            if (upper.contains(keyword)) {
                String[] tokens = lines[i].split("\\s+");
                for (int j = tokens.length - 1; j >= 0; j--) {
                    BigDecimal val = PdfParsingUtils.parseAutoDecimal(tokens[j]);
                    if (val.compareTo(BigDecimal.ZERO) > 0 && val.compareTo(BigDecimal.valueOf(100)) <= 0) {
                        return val.compareTo(BigDecimal.ONE) > 0
                                ? val.divide(BigDecimal.valueOf(100), 6, RoundingMode.HALF_UP)
                                : val.setScale(6, RoundingMode.HALF_UP);
                    }
                }
            }
        }
        return null;
    }

    @Override
    public String getStrategyName() {
        return "GENERIC_VERTICAL_STRATEGY";
    }
}
