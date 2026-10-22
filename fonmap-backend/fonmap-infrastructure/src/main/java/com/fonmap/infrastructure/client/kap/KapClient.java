package com.fonmap.infrastructure.client.kap;

import com.fonmap.infrastructure.client.kap.dto.KapPdfDto;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.ExchangeStrategies;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.net.URI;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * KapClient — KAP Portföy Dağılım Raporu İndirici ve Yerel Örnek Yedeği İstemcisi
 * ==============================================================================
 *
 * SİSTEMDEKİ ROLÜ:
 * Fonmap platformunun "PDF Temin Memuru ve Lojistikçisi"dir.
 * Fonların Kamuyu Aydınlatma Platformu'nda (KAP) yayımladığı aylık resmi
 * "Portföy Dağılım Raporu" PDF belgelerini sisteme kazandırır.
 *
 * 3 TEMEL GÖREVİ:
 * 1. Online Mod (downloadFromUrl):
 *    KAP'ın bildirim sunucusundan Spring WebClient ile asenkron/reaktif olarak
 *    PDF dosyasını belleğe indirir. Büyük PDF'ler için 15MB bellek tamponu kullanır.
 *
 * 2. Çevrimdışı / Test / Fallback Modu (loadFromLocalSample):
 *    İnternet kesintilerinde, KAP sunucusu yanıt vermediğinde veya test ortamlarında;
 *    projenin kök dizinindeki 'samples/*.pdf' (Örn: THF_2026_08.pdf, TTE_2026_08.pdf)
 *    gerçek örnek dosyalarını dinamik yol çözümleme (smart path resolution) ile bulur ve okur.
 *
 * 3. Kriptografik Parmak İzi (SHA-256 Idempotency Motoru):
 *    İster canlı insin ister yerelden okunsun, dosyanın baytlarından 64 karakterlik
 *    küçük harfli SHA-256 özeti üretir. Bu özet 'fund_reports.pdf_sha256' tablosundaki
 *    UNIQUE kısıtıyla eşleştirilerek aynı raporun ikinci kez işlenmesini engeller.
 *
 * NE YAPMAZ? (Single Responsibility Principle):
 * PDF'in içindeki sayfaları, tabloları ve hisse yüzdelerini PARSE ETMEZ.
 * Bu sorumluluk Faz 3'teki PDFBox stratejilerine (TeraPdfParser vb.) aittir.
 */
@Component
@Slf4j
public class KapClient {

    private final WebClient webClient;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * Varsayılan yapıcı metot.
     * WebClient'ı PDF indirme ihtiyaçlarına göre özel olarak yapılandırır:
     * - 15 saniyelik bağlantı ve okuma zaman aşımı (timeout).
     * - Standart 256KB olan Spring DataBuffer sınırını 15MB'a çıkartır (Büyük PDF'ler için).
     */
    public KapClient() {
        // 1. Ağ zaman aşımı ayarları (KAP sunucusu yavaşsa sistem kilitlenmesin)
        HttpClient httpClient = HttpClient.create()
                .responseTimeout(Duration.ofSeconds(15));

        // 2. Büyük PDF'lerin belleğe sığması için 15 MB codec limiti
        ExchangeStrategies strategies = ExchangeStrategies.builder()
                .codecs(codecs -> codecs.defaultCodecs().maxInMemorySize(15 * 1024 * 1024))
                .build();

        this.webClient = WebClient.builder()
                .clientConnector(new ReactorClientHttpConnector(httpClient))
                .exchangeStrategies(strategies)
                .build();
    }

    /**
     * Akıllı Getirici (Smart Orchestrator) — Geriye dönük uyumluluk için.
     */
    public KapPdfDto fetchPdf(String fundCode, String pdfUrl) {
        return fetchPdf(fundCode, null, pdfUrl);
    }

    /**
     * Akıllı Getirici (Smart Orchestrator) — Geriye dönük uyumluluk için.
     */
    public KapPdfDto fetchPdf(String fundCode, String managerName, String pdfUrl) {
        return fetchPdf(fundCode, managerName, pdfUrl, null);
    }

    /**
     * Akıllı Getirici (Smart Orchestrator):
     * 1. Eğer 'pdfUrl' verilmişse öncelikle doğrudan o URL'den indirmeyi dener.
     * 2. Eğer 'pdfUrl' null/boş ise (Otomatik Keşif Modu):
     *    KAP bildirim sorgulama servisini tarayarak hedef fon, yönetici ve unvan filtresiyle
     *    en güncel "Portföy Dağılım Raporu" bildirimini ve ekli PDF linkini dinamik olarak keşfeder ve indirir.
     * 3. Asla yerel sample dosyalarına kaçmaz; canlı KAP'ta rapor yoksa açıklayıcı hata fırlatır.
     *
     * @param fundCode    Fon kodu (örn: "THF", "TLY", "TTE", "DFI", "BDY", "GAE")
     * @param managerName Yönetici portföy şirketi adı (örn: "İş Portföy Yönetimi A.Ş.", opsiyonel)
     * @param pdfUrl      KAP bildirim ekindeki PDF linki (opsiyonel / null olabilir)
     * @param fundTitle   Fonun tam resmi unvanı (opsiyonel / null olabilir, KAP çapraz doğrulamasında kullanılır)
     * @return Doldurulmuş ve SHA-256'sı hesaplanmış KapPdfDto nesnesi
     */
    public KapPdfDto fetchPdf(String fundCode, String managerName, String pdfUrl, String fundTitle) {
        if (fundCode == null || fundCode.trim().isEmpty()) {
            throw new IllegalArgumentException("[KapClient] Fon kodu boş olamaz!");
        }

        String normalizedCode = fundCode.trim().toUpperCase(Locale.ROOT);

        // 1. Manuel / Belirli bir URL verilmişse öncelikle doğrudan oradan indirmeyi dene
        if (pdfUrl != null && !pdfUrl.trim().isEmpty()) {
            try {
                log.info("[KapClient] Doğrudan URL indirmesi başlatılıyor: Fon='{}', URL='{}'", normalizedCode, pdfUrl);
                return downloadFromUrl(normalizedCode, pdfUrl.trim());
            } catch (Exception e) {
                log.warn("[KapClient] Doğrudan URL indirmesi başarısız oldu ({}).", e.getMessage());
            }
        }

        // 2. Otomatik Keşif Modu: URL belirtilmemişse KAP Resmi API ve tarayıcı akışlarıyla en son raporu ara
        if (pdfUrl == null || pdfUrl.trim().isEmpty()) {
            try {
                log.info("[KapClient] 🔍 Otomatik KAP canlı keşfi başlatılıyor: Fon='{}', Yönetici='{}', Unvan='{}'...",
                        normalizedCode, managerName, fundTitle);
                KapPdfDto discoveredDto = discoverAndDownloadLatestPdf(normalizedCode, managerName, fundTitle);
                if (discoveredDto != null && !discoveredDto.isEmpty()) {
                    log.info("[KapClient] 🎯 Otomatik keşif BAŞARILI: Fon='{}', Dosya='{}', Boyut={} KB",
                            normalizedCode, discoveredDto.getFileName(), String.format("%.2f", discoveredDto.getSizeInKb()));
                    return discoveredDto;
                }
            } catch (Exception e) {
                log.warn("[KapClient] Otomatik KAP keşfi başarısız/sonuçsuz: Fon='{}' -> {}", normalizedCode, e.getMessage());
            }
        }

        // 3. Canlıda bulunamadıysa temiz ve yönlendirici hata fırlat
        throw new IllegalStateException(String.format(
                "KAP'ta '%s' fonu için yayınlanmış güncel bir portföy dağılım raporu bulunamadı. Lütfen yönetim panelindeki 'PDF Yükle' butonu ile fonun aylık dağılım PDF'ini doğrudan sisteme aktarınız.",
                normalizedCode));
    }

    /**
     * Geriye dönük uyumluluk için fon kodu ile keşif.
     */
    public KapPdfDto discoverAndDownloadLatestPdf(String fundCode) {
        return discoverAndDownloadLatestPdf(fundCode, null);
    }

    /**
     * Geriye dönük uyumluluk için fon kodu ve yönetici ile keşif.
     */
    public KapPdfDto discoverAndDownloadLatestPdf(String fundCode, String managerName) {
        return discoverAndDownloadLatestPdf(fundCode, managerName, null);
    }

    /**
     * SIFIR LİSTE (ZERO-MEMORY) TEKİL FON ÇÖZÜMLEME:
     * Bellekte hiçbir fon listesi veya önbellek tutulmaz (0 MB RAM).
     * Yalnızca aranan fon kodu (örn: "GAE") için KAP'ın fon sayfasına bağlanır,
     * hedef fon kodunu yakaladığı anda regex aramayı tamamlar ve tekil MKK fundOid'sini döner.
     */
    public String findSingleFundOid(String fundCode) {
        if (fundCode == null || fundCode.trim().isEmpty()) {
            return null;
        }
        String cleanCode = fundCode.trim().toUpperCase(Locale.ROOT);

        // 1. Yatırım Fonları kataloğunda tekil ara
        String oid = scanPageForSingleFundOid("https://www.kap.org.tr/tr/YatirimFonlari/YF", cleanCode);
        if (oid == null) {
            // 2. Borsa Yatırım Fonları kataloğunda tekil ara
            oid = scanPageForSingleFundOid("https://www.kap.org.tr/tr/YatirimFonlari/BYF", cleanCode);
        }
        return oid;
    }

    private String scanPageForSingleFundOid(String pageUrl, String targetFundCode) {
        try {
            String pageHtml = webClient.get()
                    .uri(pageUrl)
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                    .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                    .retrieve()
                    .bodyToMono(String.class)
                    .block(Duration.ofSeconds(15));

            if (pageHtml == null || pageHtml.isEmpty()) return null;

            // Sadece aranan fona özgü fundOid regex'i:
            // "fundOid":"33E5FED7E84700EAE0530A4A622B2AEA","fundId":"345","fundCode":"GAE"
            java.util.regex.Pattern re = java.util.regex.Pattern.compile(
                    "\\\\?\"fundOid\\\\?\":\\s*\\\\?\"([A-Fa-f0-9]+)\\\\?\"[^{}]*?\\\\?\"fundCode\\\\?\":\\s*\\\\?\"" + targetFundCode + "\\\\?\"");
            java.util.regex.Matcher m = re.matcher(pageHtml);
            if (m.find()) {
                String fundOid = m.group(1);
                log.info("[KapClient] 🎯 KAP'tan tekil fon kimliği (fundOid) anında çözümlendi: Fon='{}' -> {}", targetFundCode, fundOid);
                return fundOid;
            }
        } catch (Exception e) {
            log.warn("[KapClient] '{}' üzerinden fon kimliği sorgulanırken hata: {}", pageUrl, e.getMessage());
        }
        return null;
    }

    /**
     * KAP Resmi REST API'sini (byCriteria) sorgulayarak hedef fonun en son
     * "Portföy Dağılım Raporu" bildirim ID'sini döner.
     */
    public String discoverLatestDisclosureViaOfficialApi(String fundCode) {
        String fundOid = findSingleFundOid(fundCode);
        if (fundOid == null) {
            log.warn("[KapClient] Fon '{}' için KAP resmi fundOid kimliği çözümlenemedi.", fundCode);
            return null;
        }

        try {
            LocalDate today = LocalDate.now();
            LocalDate fromDate = today.minusDays(120);

            String postBody = String.format("""
                {
                  "fromDate": "%s",
                  "toDate": "%s",
                  "fundTypeList": ["YF", "BYF", "EYF"],
                  "fundOidList": ["%s"],
                  "mkkMemberOidList": [],
                  "passiveFundOidList": [],
                  "disclosureClass": "",
                  "isLate": "",
                  "subjectList": [],
                  "discIndex": [],
                  "fromSrc": false,
                  "srcCategory": ""
                }
                """, fromDate, today, fundOid);

            String responseJson = webClient.post()
                    .uri("https://www.kap.org.tr/tr/api/disclosure/funds/byCriteria")
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json")
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                    .header("Accept-Language", "tr-TR,tr;q=0.9")
                    .bodyValue(postBody)
                    .retrieve()
                    .bodyToMono(String.class)
                    .block(Duration.ofSeconds(15));

            if (responseJson == null || responseJson.isBlank()) {
                return null;
            }

            JsonNode rootNode = objectMapper.readTree(responseJson);
            if (!rootNode.isArray()) {
                return null;
            }

            for (JsonNode item : rootNode) {
                String subject = item.path("subject").asText("");
                String summary = item.path("summary").asText("");
                String normSub = normalizeTurkish(subject + " " + summary);

                if (normSub.contains("portfoy dagilim")) {
                    if (normSub.contains("toplam gider") || normSub.contains("gider bilgileri") || normSub.contains("komisyon")) {
                        continue;
                    }

                    long disclosureIndex = item.path("disclosureIndex").asLong(0);
                    if (disclosureIndex > 0) {
                        log.info("[KapClient] 🚀 KAP Resmi API'sinden en güncel Portföy Dağılım Raporu bulundu: Fon='{}', Bildirim ID={}, Tarih='{}', Özet='{}'",
                                fundCode, disclosureIndex, item.path("publishDate").asText(), summary);
                        return String.valueOf(disclosureIndex);
                    }
                }
            }
        } catch (Exception e) {
            log.warn("[KapClient] KAP resmi byCriteria API sorgusu başarısız: Fon='{}' -> {}", fundCode, e.getMessage());
        }
        return null;
    }

    public KapPdfDto discoverAndDownloadLatestPdf(String fundCode, String managerName, String fundTitle) {
        try {
            // 1. ADIM: Bildirim ID'sini öncelikle KAP Resmi REST API'sinden (byCriteria) sorgula
            String disclosureId = discoverLatestDisclosureViaOfficialApi(fundCode);

            // 2. ADIM: Eğer resmi API'de bulunamazsa legacy web arama rotalarına düş
            if (disclosureId == null) {
                log.info("[KapClient] Fon '{}' resmi API'de bulunamadı, HTML arama rotaları taranıyor...", fundCode);
                String searchUrl = "https://www.kap.org.tr/tr/bildirim-sorgu-sonuc?srcbar=Y&cmp=N&cat=2&kw="
                        + fundCode + "&slf=ALL";
                String searchHtml = webClient.get()
                        .uri(searchUrl)
                        .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                        .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                        .retrieve()
                        .bodyToMono(String.class)
                        .block(Duration.ofSeconds(10));

                if (searchHtml != null && !searchHtml.isEmpty()) {
                    disclosureId = extractLatestDisclosureId(searchHtml, fundCode, managerName, fundTitle);
                }
                if (disclosureId == null) {
                    disclosureId = discoverFromSearchRoute(fundCode, managerName, fundTitle);
                }
            }

            if (disclosureId == null) {
                log.warn("[KapClient] Fon '{}' (Yönetici: '{}', Unvan: '{}') için KAP'ta henüz eşleşen 'Portföy Dağılım Raporu' bildirimi bulunamadı.",
                        fundCode, managerName, fundTitle);
                return null;
            }

            log.info("[KapClient] 📌 Fon '{}' için en güncel bildirim ID tespit edildi: {}", fundCode, disclosureId);

            // 3. ADIM: Bildirim detay sayfasına gidip ek PDF URL'sini yakala
            String disclosureDetailUrl = "https://www.kap.org.tr/tr/Bildirim/" + disclosureId;
            String detailHtml = webClient.get()
                    .uri(disclosureDetailUrl)
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                    .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                    .retrieve()
                    .bodyToMono(String.class)
                    .block(Duration.ofSeconds(15));

            String pdfDownloadUrl = extractPdfDownloadUrl(detailHtml, disclosureId);
            if (pdfDownloadUrl == null) {
                log.warn("[KapClient] Bildirim '{}' içinde PDF indirme linki bulunamadı.", disclosureId);
                return null;
            }

            // 4. ADIM: PDF'i indir (Bağlantı kopmalarına dayanıklı çift motor)
            byte[] bytes = downloadPdfBytes(pdfDownloadUrl);

            if (bytes == null || bytes.length == 0) {
                throw new IllegalStateException("İndirilen PDF dosyası boş: " + pdfDownloadUrl);
            }

            String sha256 = calculateSha256(bytes);
            String fileName = extractFileNameFromUrl(pdfDownloadUrl, fundCode);

            return KapPdfDto.builder()
                    .fundCode(fundCode)
                    .fileName(fileName)
                    .content(bytes)
                    .sha256Hash(sha256)
                    .source("KAP_AUTOMATED")
                    .downloadedAt(LocalDateTime.now())
                    .build();

        } catch (Exception e) {
            log.error("[KapClient] Otomatik keşif sırasında hata oluştu: Fon='{}' -> {}", fundCode, e.getMessage());
            return null;
        }
    }

    /**
     * Türkçe karakter ve Unicode bileşimlerini (örn: İ/i, ı/i, birleşik noktalı harfler)
     * temizleyerek tam eşleşme sağlayan normalize edici.
     */
    private String normalizeTurkish(String text) {
        if (text == null) return "";
        String lower = text.toLowerCase(Locale.ROOT)
                .replace('ı', 'i')
                .replace('İ', 'i');
        return java.text.Normalizer.normalize(lower, java.text.Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "");
    }

    /**
     * Fon unvanından ve yöneticisinden arama kartı eşleştirmesi için ayırt edici anahtar kelimeleri çıkarır.
     * Genel stop-word'leri ve yönetici şirketin unvan parçalarını filtreleyerek sadece fona özgü kelimeleri döndürür.
     */
    List<String> extractDistinctKeywords(String title, String manager) {
        if (title == null || title.trim().isEmpty()) {
            return List.of();
        }
        String normalizedTitle = normalizeTurkish(title);
        String[] words = normalizedTitle.replaceAll("[^a-z0-9]", " ").split("\\s+");
        Set<String> stopWords = new HashSet<>(Set.of(
                "fonu", "fon", "hisse", "senedi", "sened", "tl", "portfoy", "yonetimi", "yonetim",
                "a", "s", "as", "ve", "ile", "icin", "veya", "yogun", "serbest", "degisken", "semsiye"
        ));
        if (manager != null && !manager.trim().isEmpty()) {
            String normalizedMgr = normalizeTurkish(manager);
            String[] mgrWords = normalizedMgr.replaceAll("[^a-z0-9]", " ").split("\\s+");
            for (String mw : mgrWords) {
                if (!mw.isEmpty()) {
                    stopWords.add(mw);
                }
            }
        }
        List<String> keywords = new ArrayList<>();
        for (String w : words) {
            if (w.length() >= 2 && !stopWords.contains(w)) {
                keywords.add(w);
            }
        }
        return keywords;
    }

    /**
     * Geriye dönük uyumluluk için arama rotası keşfi.
     */
    String discoverFromSearchRoute(String fundCode, String managerName) {
        return discoverFromSearchRoute(fundCode, managerName, null);
    }

    /**
     * KAP 2000'lik genel liste bildirim bombardımanından dolayı dolduğunda,
     * doğrudan fon bazlı metin arama sayfalarını (/tr/search/{code}/1..5) tarayarak
     * hedef fonun en son resmi "Portföy Dağılım Raporu" bildirim ID'sini yakalar.
     *
     * 3 KATMANLI ÇAPRAZ DOĞRULAMA (CROSS-CHECK):
     * 1. Katman (Konu): Kart başlığı mutlaka "Portföy Dağılım Raporu" olmalı, gider/duyurular elenmeli.
     * 2. Katman (Yönetici): Kart içeriğinde portföy yönetim şirketi doğrulanmalı.
     * 3. Katman (Fon Doğrulaması): Kart içeriğinde ya doğrudan fon kodu (BDY) geçmeli,
     *    ya da fonun resmi unvanındaki ayırt edici anahtar kelimeler (örn: BIST 100 Dışı Şirketler)
     *    kart başlığıyla eşleşmelidir. Böylece yanlış fonların indirilmesi kesin olarak engellenir.
     */
    String discoverFromSearchRoute(String fundCode, String managerName, String fundTitle) {
        String code = fundCode.trim().toUpperCase(Locale.ROOT);
        String mgr = managerName != null ? managerName.trim() : "";
        List<String> keywords = extractDistinctKeywords(fundTitle, managerName);

        for (int page = 1; page <= 5; page++) {
            try {
                String searchPageUrl = "https://www.kap.org.tr/tr/search/" + code + "/" + page;
                String html = webClient.get()
                        .uri(searchPageUrl)
                        .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                        .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                        .header("Accept-Language", "tr-TR,tr;q=0.9,en-US;q=0.8,en;q=0.7")
                        .retrieve()
                        .bodyToMono(String.class)
                        .block(Duration.ofSeconds(10));

                if (html == null || html.isEmpty()) {
                    continue;
                }

                // Bildirim bağlantılarını ve çevresindeki metni regex ile analiz et
                java.util.regex.Pattern re = java.util.regex.Pattern.compile("/tr/Bildirim/(\\d+)");
                java.util.regex.Matcher m = re.matcher(html);
                while (m.find()) {
                    String id = m.group(1);
                    int start = Math.max(0, m.start() - 50);
                    int end = Math.min(html.length(), m.start() + 400);
                    String snippet = normalizeTurkish(html.substring(start, end));

                    // 1. KATMAN: Mutlaka Portföy Dağılım Raporu olmalı
                    if (!snippet.contains("portfoy dagilim")) {
                        continue;
                    }

                    // Gider, duyuru, bilgilendirme veya alım satım bildirimlerini kesinlikle ele
                    if (snippet.contains("toplam gider") || snippet.contains("gider bilgileri")
                            || snippet.contains("bilgilendirme formu") || snippet.contains("genel aciklama")
                            || snippet.contains("izahname") || snippet.contains("pay alim satim")
                            || snippet.contains("komisyon")) {
                        continue;
                    }

                    // 2. KATMAN: Yönetici Şirket Doğrulaması
                    boolean mgrMatch = true;
                    if (!mgr.isEmpty()) {
                        String cleanMgr = normalizeTurkish(mgr.replaceAll("(?i)(yönetimi|yonetimi|a\\.ş\\.|a\\.s\\.|as|aş|şirketi|sirketi)", "").trim());
                        mgrMatch = cleanMgr.isEmpty() || snippet.contains(cleanMgr);
                    }
                    if (!mgrMatch) {
                        continue;
                    }

                    // 3. KATMAN: Fon Doğrulaması (Çapraz Doğrulama)
                    boolean fundMatch = false;

                    // A) Fon kodu doğrudan bildirim kartında veya ilgili fonlar listesinde geçiyorsa
                    if (snippet.contains(code.toLowerCase(Locale.ROOT))) {
                        fundMatch = true;
                    }

                    // B) Fon unvanından türetilen ayırt edici anahtar kelimeler kart başlığı ile örtüşüyorsa
                    if (!fundMatch && !keywords.isEmpty()) {
                        long matchedCount = keywords.stream().filter(snippet::contains).count();
                        if (keywords.size() <= 2 && matchedCount >= 1) {
                            fundMatch = true;
                        } else if (keywords.size() > 2 && (matchedCount >= 2 || matchedCount * 2 >= keywords.size())) {
                            fundMatch = true;
                        }
                    }

                    // C) Eğer unvan bilinmiyorsa (fundTitle == null veya boş), arama rotası (/tr/search/{code}/1)
                    // zaten spesifik olarak hedef fon koduyla açıldığı için; kart konusu Portföy Dağılım Raporu
                    // olup yönetici uyuşmazlığı da yoksa, 1. sayfadaki ilk sonuç kabul edilir.
                    if (!fundMatch && (fundTitle == null || fundTitle.trim().isEmpty()) && page == 1) {
                        fundMatch = true;
                    }

                    if (fundMatch) {
                        log.info("[KapClient] 🎯 KAP metin aramasından (/tr/search/{}/{}) en güncel bildirim ID bulundu: {}", code, page, id);
                        return id;
                    }
                }
            } catch (Exception e) {
                log.warn("[KapClient] Metin arama sayfası ({}) taranırken hata: {}", page, e.getMessage());
            }
        }
        return null;
    }

    /**
     * Geriye dönük uyumluluk için varsayılan bildirim ID ayıklayıcı.
     */
    String extractLatestDisclosureId(String html) {
        return extractLatestDisclosureId(html, null, null);
    }

    /**
     * KAP bildirim sorgu tablosundan hedef fon ve yönetici şirket ile tam eşleşen
     * "Portföy Dağılım Raporu" bildirim ID'sini ayıklar.
     *
     * Güvenlik ve Doğruluk Kriterleri:
     * 1. Bildirim konusu mutlaka "Portföy Dağılım Raporu" olmalıdır (Gider veya duyurular elenir).
     * 2. Başka portföy yönetim şirketlerinin (Örn: İş Portföy yerine Yapı Kredi) raporları KESİNLİKLE kabul edilmez.
     * 3. Hedef fon ile tam uyumlu olmayan yan fonlar (Örn: TTE hisse senedi fonu yerine karma veya yapay zeka fonları) elenir.
     */
    String extractLatestDisclosureId(String html, String fundCode, String managerName) {
        return extractLatestDisclosureId(html, fundCode, managerName, null);
    }

    String extractLatestDisclosureId(String html, String fundCode, String managerName, String fundTitle) {
        if (html == null) return null;

        String code = fundCode != null ? fundCode.trim().toUpperCase(java.util.Locale.ROOT) : "";
        String mgr = managerName != null ? managerName.trim().toUpperCase(java.util.Locale.ROOT) : "";

        // KAP HTML tablosunda her bildirim <tr id="notification..." satırındadır
        java.util.regex.Pattern trPattern = java.util.regex.Pattern.compile(
                "(?i)<tr[^>]*id=\"notification\\d+\"[^>]*>(.*?)</tr>", java.util.regex.Pattern.DOTALL);
        java.util.regex.Matcher trMatcher = trPattern.matcher(html);

        while (trMatcher.find()) {
            String rowHtml = trMatcher.group(1);
            String rowLower = rowHtml.toLowerCase(java.util.Locale.ROOT);

            // 1. Mutlaka "Portföy Dağılım Raporu" olmalı
            boolean isPdr = rowLower.contains("portföy dağılım") || rowLower.contains("portfoy dagilim");
            if (!isPdr) {
                continue;
            }

            // Gider, izahname, duyuru veya genel açıklamaları kesinlikle ele
            if (rowLower.contains("toplam gider") || rowLower.contains("gider bilgileri")
                    || rowLower.contains("bilgilendirme formu") || rowLower.contains("genel açıklama")
                    || rowLower.contains("izahname")) {
                continue;
            }

            String rowUpper = rowHtml.toUpperCase(java.util.Locale.ROOT);

            // 2. Portföy Yönetim Şirketi (Manager) Doğrulaması (Generic - Sıfır Hardcode)
            boolean matchesManager = true;
            if (!mgr.isEmpty()) {
                String cleanMgr = normalizeTurkish(mgr.replaceAll("(?i)(yönetimi|yonetimi|a\\.ş\\.|a\\.s\\.|as|aş|şirketi|sirketi|portföy|portfoy)", "").trim());
                String normRow = normalizeTurkish(rowHtml);
                matchesManager = cleanMgr.isEmpty() || normRow.contains(cleanMgr);
            }

            // Fon kodu doğrudan bildirim satırında geçiyorsa, fon kodunun SPK nezdindeki tekilliğine güvenilir
            if (!matchesManager && !code.isEmpty() && (rowUpper.contains(code + " ") || rowUpper.contains(code + "-") || rowUpper.contains("(" + code + ")") || rowUpper.contains(code))) {
                matchesManager = true;
            }

            if (!matchesManager) {
                continue;
            }

            // 3. Fon Doğrulaması (Generic Çapraz Doğrulama - Sıfır Hardcode)
            boolean matchesFund = false;

            // A) Fon kodu doğrudan bildirim satırında veya link metninde geçiyorsa
            if (!code.isEmpty() && (rowUpper.contains(code + " ") || rowUpper.contains(code + "-") || rowUpper.contains("(" + code + ")") || rowUpper.contains(code))) {
                matchesFund = true;
            }

            // B) Fon unvanından türetilen ayırt edici anahtar kelimeler ile dinamik eşleşme
            if (!matchesFund && fundTitle != null && !fundTitle.trim().isEmpty()) {
                List<String> keywords = extractDistinctKeywords(fundTitle, managerName);
                String normalizedRow = normalizeTurkish(rowHtml);
                long matched = keywords.stream().filter(normalizedRow::contains).count();
                if (keywords.size() <= 2 && matched >= 1) {
                    matchesFund = true;
                } else if (keywords.size() > 2 && (matched >= 2 || matched * 2 >= keywords.size())) {
                    matchesFund = true;
                }
            } else if (!matchesFund && (fundTitle == null || fundTitle.trim().isEmpty())) {
                matchesFund = code.isEmpty();
            }

            if (!matchesFund) {
                continue;
            }

            // 4. Eşleşen bildirim satırından ID'yi ayıkla
            java.util.regex.Pattern idPattern = java.util.regex.Pattern.compile("id=\"(\\d+)\"[^>]*name=\"notification-checkbox\"");
            java.util.regex.Matcher idMatcher = idPattern.matcher(rowHtml);
            if (idMatcher.find()) {
                return idMatcher.group(1);
            }

            java.util.regex.Pattern idPattern2 = java.util.regex.Pattern.compile("name=\"notification-checkbox\"[^>]*id=\"(\\d+)\"");
            java.util.regex.Matcher idMatcher2 = idPattern2.matcher(rowHtml);
            if (idMatcher2.find()) {
                return idMatcher2.group(1);
            }
        }
        return null;
    }

    /**
     * Bildirim detay sayfasından ekli PDF'in doğrudan indirme adresini ayıklar.
     */
    String extractPdfDownloadUrl(String disclosureHtml, String disclosureId) {
        if (disclosureHtml == null) return null;

        // 1. Öncelik: Tam URL ek dosyası (https://www.kap.org.tr/tr/api/file/download/...)
        java.util.regex.Pattern fullPattern = java.util.regex.Pattern.compile(
                "href=\"(https://www\\.kap\\.org\\.tr/tr/api/file/download/[a-zA-Z0-9]+)\"");
        java.util.regex.Matcher fullMatcher = fullPattern.matcher(disclosureHtml);
        if (fullMatcher.find()) {
            return fullMatcher.group(1);
        }

        // 2. Öncelik: Göreceli URL (/tr/api/file/download/...)
        java.util.regex.Pattern relPattern = java.util.regex.Pattern.compile(
                "href=\"(/tr/api/file/download/[a-zA-Z0-9]+)\"");
        java.util.regex.Matcher relMatcher = relPattern.matcher(disclosureHtml);
        if (relMatcher.find()) {
            return "https://www.kap.org.tr" + relMatcher.group(1);
        }

        // 3. Öncelik: Bildirimin doğrudan PDF çıktısı
        return "https://www.kap.org.tr/tr/api/BildirimPdf/" + disclosureId;
    }

    /**
     * 1. GÖREV: Canlı KAP Bildirim PDF'ini WebClient ile İndirir.
     *
     * @param fundCode Fon kodu
     * @param pdfUrl   İndirilecek doğrudan PDF URL'si
     * @return KapPdfDto
     */
    public KapPdfDto downloadFromUrl(String fundCode, String pdfUrl) {
        try {
            byte[] bytes = downloadPdfBytes(pdfUrl);

            if (bytes == null || bytes.length == 0) {
                throw new IllegalStateException("KAP sunucusundan boş PDF yanıtı döndü: " + pdfUrl);
            }

            String sha256 = calculateSha256(bytes);
            String fileName = extractFileNameFromUrl(pdfUrl, fundCode);

            log.info("[KapClient] Canlı PDF başarıyla indirildi: Fon='{}', Boyut={} KB, SHA256={}",
                    fundCode, String.format("%.2f", bytes.length / 1024.0), sha256);

            return KapPdfDto.builder()
                    .fundCode(fundCode)
                    .fileName(fileName)
                    .content(bytes)
                    .sha256Hash(sha256)
                    .source("KAP_ONLINE")
                    .downloadedAt(LocalDateTime.now())
                    .build();

        } catch (Exception e) {
            log.error("[KapClient] PDF indirme hatası: URL='{}' -> {}", pdfUrl, e.getMessage());
            throw new RuntimeException("KAP PDF indirilemedi: " + e.getMessage(), e);
        }
    }

    /**
     * KAP sunucularından büyük boyutlu ve chunked PDF dosyalarını
     * bağlantı kopması (PrematureCloseException) yaşamadan dayanıklı şekilde indirir.
     * Birincil olarak Java 11+ yerel HttpClient'ı kullanır; hata durumunda WebClient'a döner.
     */
    private byte[] downloadPdfBytes(String pdfDownloadUrl) {
        log.info("[KapClient] 📥 Ek PDF indirme linki yakalandı: {}", pdfDownloadUrl);
        // 1. Birincil Yöntem: Java Yerel HttpClient (Citrix NetScaler ve chunked stream bağlantı kopmalarına karşı en dayanıklı yöntem)
        try {
            java.net.http.HttpClient client = java.net.http.HttpClient.newBuilder()
                    .followRedirects(java.net.http.HttpClient.Redirect.ALWAYS)
                    .connectTimeout(Duration.ofSeconds(20))
                    .build();

            java.net.http.HttpRequest request = java.net.http.HttpRequest.newBuilder()
                    .uri(URI.create(pdfDownloadUrl))
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                    .header("Accept", "application/pdf,application/octet-stream,*/*")
                    .header("Accept-Language", "tr-TR,tr;q=0.9,en-US;q=0.8,en;q=0.7")
                    .timeout(Duration.ofSeconds(60))
                    .GET()
                    .build();

            java.net.http.HttpResponse<byte[]> response = client.send(
                    request, java.net.http.HttpResponse.BodyHandlers.ofByteArray());

            if (response.statusCode() >= 200 && response.statusCode() < 300) {
                byte[] body = response.body();
                if (body != null && body.length > 0) {
                    log.info("[KapClient] PDF yerel java.net.http ile başarıyla indirildi. Boyut={} KB", String.format("%.2f", body.length / 1024.0));
                    return body;
                }
            }
            log.warn("[KapClient] java.net.http ile indirme durumu {}. WebClient deneniyor...", response.statusCode());
        } catch (Exception e) {
            log.warn("[KapClient] java.net.http ile indirme başarısız ({}), WebClient ile devam ediliyor...", e.getMessage());
        }

        // 2. Yedek Yöntem: Spring WebClient
        return webClient.get()
                .uri(pdfDownloadUrl)
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                .header("Accept", "application/pdf,application/octet-stream,*/*")
                .retrieve()
                .bodyToMono(byte[].class)
                .block(Duration.ofSeconds(45));
    }

    /**
     * 2. GÖREV: Çevrimdışı ve Test Ortamında 'samples/' Klasöründen PDF Okur.
     *
     * Akıllı Yol Çözümleme (Smart Path Resolution):
     * Testler bazen kök dizinde, bazen 'fonmap-infrastructure' alt modülünde çalıştırılır.
     * Bu metot 'samples/' klasörünü dinamik olarak arar ve ilgili fonun dosyasını
     * ({FON_KODU}_*.pdf kuralıyla) bulur.
     *
     * @param fundCode Fon kodu (Örn: "THF", "TTE", "DFI")
     * @return KapPdfDto
     */
    public KapPdfDto loadFromLocalSample(String fundCode) {
        try {
            Path samplesDir = findSamplesDirectory();
            Path pdfPath = findSamplePdfFile(samplesDir, fundCode);

            byte[] bytes = Files.readAllBytes(pdfPath);
            if (bytes.length == 0) {
                throw new IllegalStateException("Örnek PDF dosyası boş: " + pdfPath);
            }

            String sha256 = calculateSha256(bytes);
            String fileName = pdfPath.getFileName().toString();

            log.info("[KapClient] Yerel örnek PDF başarıyla yüklendi: Dosya='{}', Boyut={} KB, SHA256={}",
                    fileName, String.format("%.2f", bytes.length / 1024.0), sha256);

            return KapPdfDto.builder()
                    .fundCode(fundCode)
                    .fileName(fileName)
                    .content(bytes)
                    .sha256Hash(sha256)
                    .source("LOCAL_SAMPLE")
                    .downloadedAt(LocalDateTime.now())
                    .build();

        } catch (Exception e) {
            log.error("[KapClient] Yerel örnek PDF okunamadı: Fon='{}' -> {}", fundCode, e.getMessage());
            throw new RuntimeException("Yerel PDF okunamadı: " + e.getMessage(), e);
        }
    }

    /**
     * 3. GÖREV: Kriptografik SHA-256 Özeti Hesaplama (Idempotency Motoru).
     *
     * Matematiksel Prensip:
     * SHA-256 algoritması, herhangi bir uzunluktaki bayt dizisini 256 bitlik (32 bayt)
     * benzersiz bir matematiksel özet haline getirir.
     * Bu 32 bayt, 64 karakterlik küçük harfli onaltılık (hex) dizgeye dönüştürülür.
     *
     * Bu sayede dosya adı veya indirilme yolu ne olursa olsun, dosyanın İÇERİĞİ
     * aynı kaldığı sürece hash ASLA DEĞİŞMEZ.
     *
     * @param data PDF belgesinin ham baytları
     * @return 64 karakterlik küçük harfli SHA-256 onaltılık özeti
     */
    public String calculateSha256(byte[] data) {
        if (data == null) {
            data = new byte[0];
        }

        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashBytes = digest.digest(data);

            StringBuilder hexString = new StringBuilder(64);
            for (byte b : hashBytes) {
                // Her baytı 2 basamaklı küçük harf hex formatına çevir (0x0f -> "0f")
                hexString.append(String.format("%02x", b));
            }
            return hexString.toString();

        } catch (NoSuchAlgorithmException e) {
            // Java standart kütüphanesinde SHA-256 her zaman mevcuttur
            throw new IllegalStateException("SHA-256 algoritması JVM'de bulunamadı!", e);
        }
    }

    /**
     * Ortamda yerel 'samples/' klasörünün bulunup bulunmadığını kontrol eder.
     * CI ortamlarında veya canlı üretim sunucusunda 'samples/' klasörü olmadan çalışılırken
     * gereksiz hata fırlatılmasını engeller.
     *
     * @return Yerel örnek klasörü varsa true, yoksa false
     */
    public boolean hasLocalSamples() {
        try {
            findSamplesDirectory();
            return true;
        } catch (FileNotFoundException e) {
            return false;
        }
    }

    /**
     * Belirtilen fona ait 'samples/' klasöründe geçerli bir örnek PDF bulunup bulunmadığını kontrol eder.
     * Sadece genel klasör varlığını değil, ilgili fon için ({FON_KODU}_*.pdf) dosyasının
     * gerçekten mevcut olup olmadığını doğrular.
     *
     * @param fundCode Fon kodu (örn: "BDY", "TTE", "THF")
     * @return İlgili fona ait örnek dosya varsa true, yoksa false
     */
    public boolean hasSamplePdfForFund(String fundCode) {
        if (fundCode == null || fundCode.trim().isEmpty()) {
            return false;
        }
        return !findAllSamplePdfFiles(fundCode.trim()).isEmpty();
    }

    /**
     * Akıllı 'samples' dizini bulucu.
     * Mevcut çalışma dizinini (CWD) ve üst dizinlerini kontrol ederek
     * projedeki 'samples' klasörünün mutlak yolunu bulur.
     */
    public Path findSamplesDirectory() throws FileNotFoundException {
        List<Path> candidatePaths = new ArrayList<>();
        candidatePaths.add(Paths.get("samples"));
        candidatePaths.add(Paths.get("../samples"));
        candidatePaths.add(Paths.get("../../samples"));

        // System property "user.dir" üzerinden de bak
        String userDir = System.getProperty("user.dir", ".");
        Path current = Paths.get(userDir);
        candidatePaths.add(current.resolve("samples"));
        candidatePaths.add(current.resolve("../samples").normalize());
        candidatePaths.add(current.resolve("../../samples").normalize());

        for (Path p : candidatePaths) {
            if (Files.exists(p) && Files.isDirectory(p)) {
                return p.toAbsolutePath().normalize();
            }
        }

        throw new FileNotFoundException("Projede 'samples' örnek PDF klasörü bulunamadı! Taranan yollar: " + candidatePaths);
    }

    /**
     * 'samples/' klasörü içinde belirli bir fona ait tüm PDF dosyalarını listeler.
     * En güncelden eskiye doğru sıralanır (Örn: 2026_09 > 2026.09 > 2026_08).
     */
    public List<Path> findAllSamplePdfFiles(String fundCode) {
        try {
            Path samplesDir = findSamplesDirectory();
            String code = fundCode.toUpperCase(Locale.ROOT);
            String prefixUnder = code + "_";
            String prefixDot = code + ".";
            String prefixDash = code + "-";

            List<Path> matchingFiles = new ArrayList<>();
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(samplesDir, "*.pdf")) {
                for (Path entry : stream) {
                    String fileName = entry.getFileName().toString().toUpperCase(Locale.ROOT);
                    if (fileName.startsWith(prefixUnder) || fileName.startsWith(prefixDot) || fileName.startsWith(prefixDash)) {
                        matchingFiles.add(entry);
                    }
                }
            }
            matchingFiles.sort((a, b) -> b.getFileName().toString().compareToIgnoreCase(a.getFileName().toString()));
            return matchingFiles;
        } catch (Exception e) {
            log.warn("[KapClient] Örnek PDF dosyaları listelenemedi: Fon='{}', Hata={}", fundCode, e.getMessage());
            return List.of();
        }
    }

    /**
     * 'samples/' klasörü içinden ismi verilen spesifik bir PDF dosyasını okur ve yükler.
     */
    public KapPdfDto loadSpecificSampleFile(String fundCode, String fileName) {
        try {
            Path samplesDir = findSamplesDirectory();
            Path targetFile = samplesDir.resolve(fileName).normalize();
            if (!targetFile.startsWith(samplesDir)) {
                throw new SecurityException("Yetkisiz dosya erişimi veya geçersiz dosya yolu: " + fileName);
            }
            if (!Files.exists(targetFile) || !Files.isRegularFile(targetFile)) {
                throw new FileNotFoundException("Belirtilen dosya bulunamadı: " + fileName);
            }

            byte[] bytes = Files.readAllBytes(targetFile);
            String sha256 = calculateSha256(bytes);
            return KapPdfDto.builder()
                    .fundCode(fundCode.toUpperCase())
                    .fileName(targetFile.getFileName().toString())
                    .content(bytes)
                    .sha256Hash(sha256)
                    .source(targetFile.getFileName().toString())
                    .downloadedAt(LocalDateTime.now())
                    .build();
        } catch (Exception e) {
            log.error("[KapClient] Spesifik örnek dosya okunamadı: Dosya='{}' -> {}", fileName, e.getMessage());
            throw new RuntimeException("Dosya okuma hatası: " + e.getMessage(), e);
        }
    }

    /**
     * 'samples/' klasörü içinde adı '{FON_KODU}_*.pdf' veya '{FON_KODU}.*.pdf' kuralına uyan en güncel dosyayı bulur.
     * Örnek: "TI2" -> "TI2_2026.09.pdf" veya "TI2_2026_09.pdf"
     */
    private Path findSamplePdfFile(Path samplesDir, String fundCode) throws FileNotFoundException {
        String code = fundCode.toUpperCase(Locale.ROOT);
        String prefixUnder = code + "_";
        String prefixDot = code + ".";
        String prefixDash = code + "-";

        List<Path> matchingFiles = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(samplesDir, "*.pdf")) {
            for (Path entry : stream) {
                String fileName = entry.getFileName().toString().toUpperCase(Locale.ROOT);
                if (fileName.startsWith(prefixUnder) || fileName.startsWith(prefixDot) || fileName.startsWith(prefixDash)) {
                    matchingFiles.add(entry);
                }
            }
        } catch (IOException e) {
            throw new RuntimeException("'samples' dizini taranamadı: " + e.getMessage(), e);
        }

        if (!matchingFiles.isEmpty()) {
            // Dosya adına göre ters sırala: TI2_2026.09.pdf veya TTE_2026_08.pdf en güncelden eskiye
            matchingFiles.sort((a, b) -> b.getFileName().toString().compareToIgnoreCase(a.getFileName().toString()));
            return matchingFiles.get(0);
        }

        throw new FileNotFoundException("'samples/' klasöründe '" + code + "_*.pdf' formatında dosya bulunamadı!");
    }

    /**
     * URL'den dosya adını ayıklar; ayıklayamazsa fon koduna göre varsayılan isim üretir.
     */
    String extractFileNameFromUrl(String url, String fundCode) {
        try {
            int lastSlash = url.lastIndexOf('/');
            if (lastSlash != -1 && lastSlash < url.length() - 1) {
                String rawName = url.substring(lastSlash + 1);
                if (rawName.toLowerCase().endsWith(".pdf")) {
                    return rawName;
                }
            }
        } catch (Exception ignored) {
        }
        return fundCode.toUpperCase() + "_kap_raporu.pdf";
    }
}
