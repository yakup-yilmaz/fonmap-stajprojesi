package com.fonmap.infrastructure.client.tcmb;

import com.fonmap.infrastructure.client.PriceProvider;
import com.fonmap.infrastructure.client.dto.MarketPriceDto;
import com.fonmap.infrastructure.client.exception.PriceProviderException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * 3. Resmi Gösterge Döviz Sağlayıcısı: TCMB (Türkiye Cumhuriyet Merkez Bankası) İstemcisi.
 * 
 * TCMB'nin halka açık 'https://www.tcmb.gov.tr/kurlar/today.xml' bültenini okur.
 * TEFAS fon mutabakatlarında kullanılan resmi USD/TRY, EUR/TRY kurlarını çeker.
 */
@Slf4j
@Component("tcmbPriceProvider")
public class TcmbPriceProvider implements PriceProvider {

    private static final String PROVIDER_NAME = "TCMB";
    private static final String TCMB_URL = "https://www.tcmb.gov.tr/kurlar/today.xml";
    private static final String TCMB_HOME_URL = "https://www.tcmb.gov.tr";
    private static final String USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36";

    private static final java.util.regex.Pattern POLICY_RATE_PATTERN = 
            java.util.regex.Pattern.compile("repo ihale faiz oranının yüzde\\s+([0-9]+(?:,[0-9]+)?)", java.util.regex.Pattern.CASE_INSENSITIVE);
    private static final java.util.regex.Pattern FALLBACK_RATE_PATTERN = 
            java.util.regex.Pattern.compile("politika faizi(?: olan [^.]+)? yüzde\\s+([0-9]+(?:,[0-9]+)?)", java.util.regex.Pattern.CASE_INSENSITIVE);
    private static final BigDecimal DEFAULT_POLICY_RATE = new BigDecimal("0.370000");

    private final RestClient restClient;

    public TcmbPriceProvider() {
        this.restClient = RestClient.builder()
                .defaultHeader(HttpHeaders.USER_AGENT, USER_AGENT)
                .defaultHeader(HttpHeaders.ACCEPT, MediaType.APPLICATION_XML_VALUE, MediaType.TEXT_XML_VALUE, MediaType.TEXT_HTML_VALUE, "*/*")
                .build();
    }

    @Override
    public MarketPriceDto getPrice(String symbol) {
        if (isRepoOrInterestSymbol(symbol)) {
            return fetchPolicyRate();
        }

        String currencyCode = normalizeSymbol(symbol);
        log.debug("[{}] '{}' (Kod: '{}') için TCMB kuru çekiliyor...", PROVIDER_NAME, symbol, currencyCode);

        Map<String, MarketPriceDto> allRates = fetchAllRates();
        MarketPriceDto dto = allRates.get(currencyCode);

        if (dto == null) {
            throw new PriceProviderException(PROVIDER_NAME, symbol, 
                    String.format("TCMB bülteninde '%s' para birimi bulunamadı.", currencyCode));
        }

        return dto;
    }

    @Override
    public List<MarketPriceDto> getPrices(List<String> symbols) {
        if (symbols == null || symbols.isEmpty()) {
            return List.of();
        }

        // XML bültenini tek bir HTTP isteği ile alıp tüm kurları hafızaya yükler (N istek atmaz)
        Map<String, MarketPriceDto> allRates = fetchAllRates();
        List<MarketPriceDto> result = new ArrayList<>();
        MarketPriceDto policyRateDto = null;

        for (String symbol : symbols) {
            if (isRepoOrInterestSymbol(symbol)) {
                if (policyRateDto == null) {
                    policyRateDto = fetchPolicyRate();
                }
                result.add(policyRateDto);
                continue;
            }

            String currencyCode = normalizeSymbol(symbol);
            MarketPriceDto dto = allRates.get(currencyCode);
            if (dto != null) {
                result.add(dto);
            } else {
                log.warn("[{}] '{}' sembolü TCMB bülteninde bulunamadı.", PROVIDER_NAME, symbol);
            }
        }

        return result;
    }

    /**
     * TCMB'nin güncel politika (1 hafta vadeli repo ihale) faiz oranını çeker.
     * Şartname Madde 4.5 uyarınca günlük tahakkuk oranını (gunluk_getiri = yillik_faiz / 365) hesaplar.
     */
    public MarketPriceDto fetchPolicyRate() {
        log.debug("[{}] TCMB politika ve repo faiz oranı çekiliyor...", PROVIDER_NAME);
        try {
            String html = restClient.get()
                    .uri(TCMB_HOME_URL)
                    .retrieve()
                    .body(String.class);

            if (html != null && !html.isBlank()) {
                java.util.regex.Matcher matcher = POLICY_RATE_PATTERN.matcher(html);
                if (!matcher.find()) {
                    matcher = FALLBACK_RATE_PATTERN.matcher(html);
                }

                if (matcher.find()) {
                    String rateStr = matcher.group(1).replace(',', '.').trim();
                    double ratePercent = Double.parseDouble(rateStr);
                    // Yıllık oran: %37 -> 0.37
                    BigDecimal annualRate = BigDecimal.valueOf(ratePercent)
                            .divide(BigDecimal.valueOf(100), 6, java.math.RoundingMode.HALF_UP);

                    log.info("[{}] Güncel politika faizi TCMB'den otomatik çekildi: %{} (Yıllık: {})", 
                            PROVIDER_NAME, rateStr, annualRate);
                    return MarketPriceDto.ofRepo("REPO", annualRate, PROVIDER_NAME);
                }
            }
        } catch (Exception e) {
            log.warn("[{}] TCMB faiz oranı çekilemedi, varsayılan faiz ({}) kullanılacak: {}", 
                    PROVIDER_NAME, DEFAULT_POLICY_RATE, e.getMessage());
        }

        log.info("[{}] Varsayılan politika/repo faiz oranı kullanılıyor: {}", PROVIDER_NAME, DEFAULT_POLICY_RATE);
        return MarketPriceDto.ofRepo("REPO", DEFAULT_POLICY_RATE, PROVIDER_NAME);
    }

    private boolean isRepoOrInterestSymbol(String symbol) {
        if (symbol == null) return false;
        String s = symbol.trim().toUpperCase(Locale.ROOT);
        return s.equals("REPO") || s.equals("FAIZ") || s.equals("TRY_REPO") 
                || s.equals("OVERNIGHT") || s.equals("OVERNIGHT_REPO") || s.equals("POLICY_RATE");
    }

    @Override
    public String getProviderName() {
        return PROVIDER_NAME;
    }

    /**
     * TCMB today.xml dosyasını indirir ve tüm döviz kurlarını DOM ile ayrıştırıp haritaya dönüştürür.
     */
    public Map<String, MarketPriceDto> fetchAllRates() {
        try {
            String xmlResponse = restClient.get()
                    .uri(TCMB_URL)
                    .retrieve()
                    .body(String.class);

            if (xmlResponse == null || xmlResponse.isBlank()) {
                throw new PriceProviderException(PROVIDER_NAME, "ALL", "TCMB'den boş XML yanıtı alındı.");
            }

            return parseXml(xmlResponse);

        } catch (PriceProviderException ppe) {
            throw ppe;
        } catch (Exception e) {
            log.error("[{}] TCMB kurları indirilirken hata: {}", PROVIDER_NAME, e.getMessage());
            throw new PriceProviderException(PROVIDER_NAME, "ALL", e.getMessage(), e);
        }
    }

    private Map<String, MarketPriceDto> parseXml(String xmlContent) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        // XXE (XML External Entity) açıklarını engelleme
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);

        DocumentBuilder builder = factory.newDocumentBuilder();
        Document doc = builder.parse(new ByteArrayInputStream(xmlContent.getBytes(StandardCharsets.UTF_8)));
        doc.getDocumentElement().normalize();

        NodeList currencyNodes = doc.getElementsByTagName("Currency");
        Map<String, MarketPriceDto> rates = new HashMap<>();

        for (int i = 0; i < currencyNodes.getLength(); i++) {
            Node node = currencyNodes.item(i);
            if (node.getNodeType() == Node.ELEMENT_NODE) {
                Element elem = (Element) node;
                String code = elem.getAttribute("CurrencyCode");
                if (code == null || code.isBlank()) {
                    code = elem.getAttribute("Kod");
                }
                if (code == null || code.isBlank()) {
                    continue;
                }

                String forexSellingStr = getElementText(elem, "ForexSelling");
                String forexBuyingStr = getElementText(elem, "ForexBuying");

                // Eğer ForexSelling boşsa BanknoteSelling dene
                if (forexSellingStr == null || forexSellingStr.isBlank()) {
                    forexSellingStr = getElementText(elem, "BanknoteSelling");
                }
                if (forexBuyingStr == null || forexBuyingStr.isBlank()) {
                    forexBuyingStr = getElementText(elem, "BanknoteBuying");
                }

                if (forexSellingStr != null && !forexSellingStr.isBlank()) {
                    try {
                        BigDecimal forexSelling = new BigDecimal(forexSellingStr.trim());
                        BigDecimal forexBuying = (forexBuyingStr != null && !forexBuyingStr.isBlank())
                                ? new BigDecimal(forexBuyingStr.trim())
                                : forexSelling;

                        // Birim çarpanı (Örn: JPY için Unit = 100)
                        String unitStr = getElementText(elem, "Unit");
                        if (unitStr != null && !unitStr.isBlank()) {
                            BigDecimal unit = new BigDecimal(unitStr.trim());
                            if (unit.compareTo(BigDecimal.ONE) > 0) {
                                forexSelling = forexSelling.divide(unit, 6, java.math.RoundingMode.HALF_UP);
                                forexBuying = forexBuying.divide(unit, 6, java.math.RoundingMode.HALF_UP);
                            }
                        }

                        // Resmi fon değerlemesinde 'ForexSelling' (Döviz Satış Kuru) esastır.
                        // TCMB günlük tek bülten olduğu için dünkü kapanış bilinmediğinde değişim 0'dır.
                        MarketPriceDto dto = MarketPriceDto.of(code, forexSelling, forexSelling, PROVIDER_NAME);
                        rates.put(code.toUpperCase(Locale.ROOT), dto);

                    } catch (NumberFormatException nfe) {
                        log.trace("[{}] '{}' için fiyat parse edilemedi: {}", PROVIDER_NAME, code, forexSellingStr);
                    }
                }
            }
        }

        return rates;
    }

    private String getElementText(Element parent, String tagName) {
        NodeList list = parent.getElementsByTagName(tagName);
        if (list != null && list.getLength() > 0) {
            Node child = list.item(0);
            if (child != null) {
                return child.getTextContent();
            }
        }
        return null;
    }

    /**
     * Sembolü TCMB para birimi koduna dönüştürür.
     * Örn: "USDTRY", "USD/TRY", "USDTRY=X", "USD" -> "USD"
     */
    private String normalizeSymbol(String symbol) {
        if (symbol == null) return "";
        String s = symbol.trim().toUpperCase(Locale.ROOT);
        if (s.endsWith("=X")) {
            s = s.substring(0, s.length() - 2);
        }
        if (s.endsWith("TRY") && s.length() > 3) {
            s = s.substring(0, s.length() - 3);
        }
        if (s.contains("/")) {
            s = s.split("/")[0].trim();
        }
        return s;
    }
}
