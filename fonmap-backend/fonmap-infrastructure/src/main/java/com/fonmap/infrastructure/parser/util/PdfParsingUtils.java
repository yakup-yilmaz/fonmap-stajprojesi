package com.fonmap.infrastructure.parser.util;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * KAP Portföy Raporları PDF Metin ve Sayı Ayrıştırma Yardımcı Motoru.
 * 
 * =========================================================================================
 * 🎯 NE İŞE YARAR? (JUNIOR MÜHENDİS REHBERİ):
 * =========================================================================================
 * Apache PDFBox bir PDF belgesini okuduğunda bize matematiksel sayılar değil, saf ham metinler
 * (String) döner. Örneğin ekranda "74.819.592.001,69 TL" gibi görünen bir para tutarı bize
 * boşluklar, satır sonları veya para birimi simgeleriyle karışık bir metin olarak gelir.
 * 
 * Bu sınıf; PDF'lerden gelen bu kirli metinleri temizleyen, iki farklı dil formatını (Türkçe vs. İngilizce)
 * hatasız ayırt eden, ISIN kodlarını regex ile cımbızlayan ve negatif/short pozisyonları
 * otomatik saptayan finansal matematik araç kutumuzdur.
 * 
 * 🔍 ÇÖZDÜĞÜ TEMEL SORUNLAR:
 * 1. İki Dilli Sayı Formatı Sorunu:
 *    - Tera (THF, TLY) ve Pardus (KHA): Türkçe format (Nokta binlik, virgül ondalık: 74.819.592.001,69).
 *    - İş Portföy (TTE): Uluslararası İngilizce format (Virgül binlik, nokta ondalık: 3,867,888,879.64).
 * 2. Finansal Parantezli Negatiflik:
 *    - Muhasebe standartlarında negatif sayılar bazen eksi işaretiyle "-1.344.966,00", bazen de
 *      parantez içinde "(1.344.966,00)" gösterilir. Bu sınıf her iki durumu da negatif algılar.
 * 3. Yüzde Oranı Ölçekleme:
 *    - PDF'teki "%8,20" veya "8,20" ifadesini doğrudan 8.20 olarak bırakmaz; matematiksel getiri
 *      formüllerinde doğrudan çarpılabilmesi için 100'e bölerek "0.082000" (scale 6) yapar.
 */
public final class PdfParsingUtils {

    /**
     * Uluslararası 12 Haneli ISIN Kodu Deseni (Regex).
     * Format: 2 harf ülke kodu (TR) + 9 alfasayısal karakter + 1 kontrol hanesi.
     * Örnek: TRATHYAO91M5, TRFTRYBE2614
     */
    private static final Pattern ISIN_PATTERN = Pattern.compile("\\b([A-Z]{2}[A-Z0-9]{9}[0-9])\\b");

    /**
     * Parantez içi negatif sayı deseni (Muhasebe formatı: "(1.250,50)" veya "(1,250.50)").
     */
    private static final Pattern PARENTHESES_NEGATIVE_PATTERN = Pattern.compile("^\\s*\\((.*)\\)\\s*$");

    /**
     * Sık kullanılan tarih formatlayıcıları.
     */
    private static final DateTimeFormatter[] DATE_FORMATTERS = new DateTimeFormatter[]{
            DateTimeFormatter.ofPattern("dd/MM/yyyy"),
            DateTimeFormatter.ofPattern("dd.MM.yyyy"),
            DateTimeFormatter.ofPattern("yyyy-MM-dd"),
            DateTimeFormatter.ofPattern("d/M/yyyy"),
            DateTimeFormatter.ofPattern("d.M.yyyy")
    };

    /**
     * Yardımcı statik sınıf olduğu için dışarıdan 'new' ile örneklenemez.
     */
    private PdfParsingUtils() {
        throw novelUnsupportedOperationException();
    }

    private static UnsupportedOperationException novelUnsupportedOperationException() {
        return new UnsupportedOperationException("Utility sınıfı doğrudan örneklenemez.");
    }

    // =====================================================================================
    // 1. SAYI VE ONDALIK AYRIŞTIRMA (DECIMAL PARSING)
    // =====================================================================================

    /**
     * Türkçe Sayı Formatını BigDecimal'e Çevirir.
     * 
     * Kural: Nokta (.) binlik ayracıdır, Virgül (,) ondalık ayracıdır.
     * Örnek Girdiler:
     * - "74.819.592.001,69" -> 74819592001.69
     * - "-1.344.966,00"     -> -1344966.00
     * - "(50.000,50)"       -> -50000.50
     * - "285,50 TL"         -> 285.50
     * 
     * @param text PDF'ten okunan ham metin
     * @return Temizlenmiş ve ölçeklenmiş BigDecimal
     */
    public static BigDecimal parseTurkishDecimal(String text) {
        if (text == null || text.isBlank()) {
            return BigDecimal.ZERO;
        }

        String cleaned = text.trim();
        boolean isNegative = isNegativeOrParenthesized(cleaned);

        // Parantezleri, eksi işaretlerini ve para birimlerini temizle
        cleaned = cleanNumberCharacters(cleaned);

        // Türkçe format: Noktaları (binlik) sil, Virgülü (ondalık) noktaya çevir
        cleaned = cleaned.replace(".", "").replace(",", ".");

        if (cleaned.isBlank() || "-".equals(cleaned)) {
            return BigDecimal.ZERO;
        }

        try {
            BigDecimal result = new BigDecimal(cleaned);
            return isNegative ? result.abs().negate() : result;
        } catch (NumberFormatException e) {
            return BigDecimal.ZERO;
        }
    }

    /**
     * Uluslararası / İngilizce Sayı Formatını BigDecimal'e Çevirir (İş Portföy PDF'leri İçin).
     * 
     * Kural: Virgül (,) binlik ayracıdır, Nokta (.) ondalık ayracıdır.
     * Örnek Girdiler:
     * - "3,867,888,879.64" -> 3867888879.64
     * - "115.500000"       -> 115.500000
     * - "(1,250.00)"       -> -1250.00
     * 
     * @param text PDF'ten okunan ham metin
     * @return Temizlenmiş BigDecimal
     */
    public static BigDecimal parseEnglishDecimal(String text) {
        if (text == null || text.isBlank()) {
            return BigDecimal.ZERO;
        }

        String cleaned = text.trim();
        boolean isNegative = isNegativeOrParenthesized(cleaned);

        // Parantezleri, para birimlerini temizle
        cleaned = cleanNumberCharacters(cleaned);

        // İngilizce format: Virgülleri (binlik) tamamen sil, noktayı koru
        cleaned = cleaned.replace(",", "");

        if (cleaned.isBlank() || "-".equals(cleaned)) {
            return BigDecimal.ZERO;
        }

        try {
            BigDecimal result = new BigDecimal(cleaned);
            return isNegative ? result.abs().negate() : result;
        } catch (NumberFormatException e) {
            return BigDecimal.ZERO;
        }
    }

    /**
     * Akıllı Otomatik Format Algılayıcı (Auto Decimal Parser).
     * 
     * Eğer ayrıştırıcının hangi dilde olduğunu bilmediğimiz serbest bir metin varsa,
     * virgül ve noktanın pozisyonuna bakarak Türkçe mi İngilizce mi olduğuna karar verir:
     * 1. Hem nokta hem virgül varsa: En sondaki ayraç ondalık ayracıdır.
     *    - "1.250,50" -> Son ayraç virgül -> Türkçe format.
     *    - "1,250.50" -> Son ayraç nokta   -> İngilizce format.
     * 2. Sadece virgül varsa ("285,50") -> Türkçe format.
     * 3. Sadece nokta varsa:
     *    - Birden fazla nokta varsa ("1.250.000") -> Türkçe binlik.
     *    - Tek nokta varsa ("285.50")            -> Standart İngilizce/Java formatı.
     */
    public static BigDecimal parseAutoDecimal(String text) {
        if (text == null || text.isBlank()) {
            return BigDecimal.ZERO;
        }

        String trimmed = text.trim();
        boolean hasComma = trimmed.contains(",");
        boolean hasDot = trimmed.contains(".");

        if (hasComma && hasDot) {
            int lastCommaIndex = trimmed.lastIndexOf(',');
            int lastDotIndex = trimmed.lastIndexOf('.');
            if (lastCommaIndex > lastDotIndex) {
                // "1.234,56" -> Türkçe
                return parseTurkishDecimal(trimmed);
            } else {
                // "1,234.56" -> İngilizce
                return parseEnglishDecimal(trimmed);
            }
        } else if (hasComma) {
            return parseTurkishDecimal(trimmed);
        } else if (hasDot) {
            // "1.234.567" gibi birden fazla nokta varsa Türkçedir
            long dotCount = trimmed.chars().filter(ch -> ch == '.').count();
            if (dotCount > 1) {
                return parseTurkishDecimal(trimmed);
            }
            return parseEnglishDecimal(trimmed);
        }

        // Nokta veya virgül yoksa düz tam sayı gibi çevir
        return parseEnglishDecimal(trimmed);
    }

    // =====================================================================================
    // 2. YÜZDE ORANI AYRIŞTIRMA (PERCENTAGE PARSING)
    // =====================================================================================

    /**
     * Yüzde Değerini Matematiksel Orana Çevirir (Scale: 6, RoundingMode.HALF_UP).
     * 
     * Örnek:
     * - "%8,20" veya "8,20" -> 0.082000 (8.20 / 100)
     * - "%100,00"          -> 1.000000
     * - "%0,1569"          -> 0.001569
     * 
     * @param text Ham yüzde metni
     * @param isTurkish Türkçe format mı? (true: virgül ondalık, false: nokta ondalık)
     * @return 100'e bölünmüş 6 basamaklı oran
     */
    public static BigDecimal parsePercentage(String text, boolean isTurkish) {
        if (text == null || text.isBlank()) {
            return BigDecimal.ZERO;
        }

        BigDecimal rawValue = isTurkish ? parseTurkishDecimal(text) : parseEnglishDecimal(text);
        if (rawValue.compareTo(BigDecimal.ZERO) == 0) {
            return BigDecimal.ZERO;
        }

        // Tablo sütununda "%" olsun veya olmasın ("0,16" veya "%8,20"),
        // tüm yüzde değerleri matematiksel orana dönüştürülmek için 100'e bölünür.
        // Örnek: "0,16" -> 0.001600 (%0.16), "8,20" -> 0.082000 (%8.20)
        return rawValue.divide(BigDecimal.valueOf(100), 6, RoundingMode.HALF_UP);
    }

    // =====================================================================================
    // 3. ISIN KODU AYIKLAMA (REGEX EXTRACTION)
    // =====================================================================================

    /**
     * Karışık bir metin içerisinden 12 Haneli Standart ISIN Kodunu Çeker.
     * 
     * Örnek:
     * - "THYAO TRATHYAO91M5 50.000" -> "TRATHYAO91M5"
     * - "ISIN: TRFTRYBE2614 Vade: ..." -> "TRFTRYBE2614"
     * 
     * @param text Aranacak metin
     * @return Varsa ISIN kodu, yoksa Optional.empty()
     */
    public static Optional<String> extractIsin(String text) {
        if (text == null || text.isBlank()) {
            return Optional.empty();
        }

        Matcher matcher = ISIN_PATTERN.matcher(text.toUpperCase());
        if (matcher.find()) {
            return Optional.of(matcher.group(1));
        }

        return Optional.empty();
    }

    // =====================================================================================
    // 4. METİN VE ŞİRKET UNVANI TEMİZLEME (TEXT NORMALIZATION)
    // =====================================================================================

    /**
     * PDF'ten Çıkan Kirli Şirket / Menkul Kıymet Unvanını Temizler ve Standartlaştırır.
     * 
     * Yapılan İşlemler:
     * 1. Satır başı/sonu (CR, LF, Tab) karakterlerini temizler.
     * 2. Ardışık çoklu boşlukları ("   ") tek boşluğa (" ") indirir.
     * 3. Kenarlardaki tırnak işaretlerini (", ', `) kaldırır.
     * 4. Baş ve sondaki gereksiz boşlukları kırpar (trim).
     * 
     * @param rawName Ham unvan
     * @return Temiz tek satır metin
     */
    public static String cleanSecurityName(String rawName) {
        if (rawName == null || rawName.isBlank()) {
            return "";
        }

        return rawName.replaceAll("[\\r\\n\\t]+", " ")
                .replaceAll("[\"\'`]", "")
                .replaceAll("\\s{2,}", " ")
                .trim();
    }

    // =====================================================================================
    // 5. TARİH AYRIŞTIRMA (DATE PARSING)
    // =====================================================================================

    /**
     * PDF İçindeki Tarihleri (Vade Tarihi, Rapor Tarihi) Güvenle Çözer.
     * 
     * Desteklenen Formatlar: "31/08/2026", "31.08.2026", "2026-08-31"
     * 
     * @param text Tarih metni
     * @return Çözümlenen LocalDate veya null
     */
    public static LocalDate parseDate(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }

        String cleaned = text.trim();
        for (DateTimeFormatter formatter : DATE_FORMATTERS) {
            try {
                return LocalDate.parse(cleaned, formatter);
            } catch (DateTimeParseException ignored) {
                // Sıradaki formatı dene
            }
        }

        return parseMonthYear(cleaned);
    }

    /**
     * Türkçe Ay-Yıl Formatlarını Çözer (Örn: "EYLÜL 2026", "Ağustos-2026", "A-2026").
     * Ayın son gününü (end of month) LocalDate olarak döner.
     */
    public static LocalDate parseMonthYear(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }

        String upper = text.toUpperCase(new Locale("tr", "TR"))
                .replace("İ", "I")
                .replace("Ş", "S")
                .replace("Ğ", "G")
                .replace("Ü", "U")
                .replace("Ö", "O")
                .replace("Ç", "C");

        Matcher yearMatcher = Pattern.compile("\\b(20\\d{2})\\b").matcher(upper);
        if (!yearMatcher.find()) {
            return null;
        }
        int year = Integer.parseInt(yearMatcher.group(1));
        if (year < 2020) {
            return null; // Eski kuruluş tarihleri (örn: 2001) rapor tarihi olamaz
        }

        int month = 0;
        if (upper.contains("OCAK")) month = 1;
        else if (upper.contains("SUBAT")) month = 2;
        else if (upper.contains("MART")) month = 3;
        else if (upper.contains("NISAN")) month = 4;
        else if (upper.contains("MAYIS")) month = 5;
        else if (upper.contains("HAZIRAN")) month = 6;
        else if (upper.contains("TEMMUZ") || upper.contains("T-" + year)) month = 7;
        else if (upper.contains("AGUSTOS") || upper.contains("A-" + year) || upper.contains("A-2026")) month = 8;
        else if (upper.contains("EYLUL") || upper.contains("E-" + year)) month = 9;
        else if (upper.contains("EKIM")) month = 10;
        else if (upper.contains("KASIM")) month = 11;
        else if (upper.contains("ARALIK")) month = 12;

        if (month > 0) {
            return java.time.YearMonth.of(year, month).atEndOfMonth();
        }

        return null;
    }

    // =====================================================================================
    // 6. YARDIMCI KONTROL METOTLARI (PRIVATE HELPERS)
    // =====================================================================================

    /**
     * Metnin negatif veya parantezli bir sayı olup olmadığını belirler.
     * Örnek: "-1.500", "(1.500)", " - 50 "
     */
    public static boolean isNegativeOrParenthesized(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        String trimmed = text.trim();
        if (trimmed.startsWith("-")) {
            return true;
        }
        Matcher matcher = PARENTHESES_NEGATIVE_PATTERN.matcher(trimmed);
        return matcher.matches();
    }

    /**
     * Sayı metninden para birimlerini, harfleri ve parantezleri temizler.
     */
    private static String cleanNumberCharacters(String text) {
        Matcher parenMatcher = PARENTHESES_NEGATIVE_PATTERN.matcher(text.trim());
        String inner = parenMatcher.matches() ? parenMatcher.group(1) : text;

        // Harfleri (TL, TRY, USD, EUR), '%' işaretini ve parantezleri temizle; nokta, virgül, eksi ve rakamları tut
        return inner.replaceAll("[^0-9.,\\-]", "").trim();
    }
}
