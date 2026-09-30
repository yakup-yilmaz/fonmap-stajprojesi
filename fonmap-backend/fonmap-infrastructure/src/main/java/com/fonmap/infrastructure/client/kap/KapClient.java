package com.fonmap.infrastructure.client.kap;

import com.fonmap.infrastructure.client.kap.dto.KapPdfDto;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.ExchangeStrategies;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

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
     * Akıllı Getirici (Smart Orchestrator):
     * 1. Eğer 'pdfUrl' verilmişse doğrudan o URL'den indirir.
     * 2. Eğer 'pdfUrl' null/boş ise (Otomatik Keşif Modu - T3.1/T3.2):
     *    KAP bildirim sorgulama servisini tarayarak en güncel "Portföy Dağılım Raporu"
     *    bildirimini ve ekli PDF linkini dinamik olarak keşfeder ve indirir.
     * 3. KAP erişilemezse veya bildirim bulunamazsa yerel 'samples/' klasöründeki
     *    örnek yedeğe (fallback) geçer.
     *
     * @param fundCode Fon kodu (örn: "THF", "TLY", "TTE", "DFI")
     * @param pdfUrl   KAP bildirim ekindeki PDF linki (opsiyonel / null olabilir)
     * @return Doldurulmuş ve SHA-256'sı hesaplanmış KapPdfDto nesnesi
     */
    public KapPdfDto fetchPdf(String fundCode, String pdfUrl) {
        if (fundCode == null || fundCode.trim().isEmpty()) {
            throw new IllegalArgumentException("[KapClient] Fon kodu boş olamaz!");
        }

        String normalizedCode = fundCode.trim().toUpperCase();

        // 1. Manuel / Belirli bir URL verilmişse öncelikle doğrudan oradan indirmeyi dene
        if (pdfUrl != null && !pdfUrl.trim().isEmpty()) {
            try {
                log.info("[KapClient] Doğrudan URL indirmesi başlatılıyor: Fon='{}', URL='{}'", normalizedCode, pdfUrl);
                return downloadFromUrl(normalizedCode, pdfUrl.trim());
            } catch (Exception e) {
                log.warn("[KapClient] Doğrudan indirme başarısız oldu ({}). Otomatik keşfe geçiliyor...", e.getMessage());
            }
        }

        // 2. Otomatik Keşif Modu (T3.1 & T3.2): KAP'tan fon koduna göre en son raporu bul ve indir
        try {
            log.info("[KapClient] 🔍 Otomatik KAP keşfi başlatılıyor: Fon='{}'...", normalizedCode);
            KapPdfDto discoveredDto = discoverAndDownloadLatestPdf(normalizedCode);
            if (discoveredDto != null && !discoveredDto.isEmpty()) {
                log.info("[KapClient] 🎯 Otomatik keşif BAŞARILI: Fon='{}', Dosya='{}', Boyut={} KB",
                        normalizedCode, discoveredDto.getFileName(), String.format("%.2f", discoveredDto.getSizeInKb()));
                return discoveredDto;
            }
        } catch (Exception e) {
            log.warn("[KapClient] Otomatik KAP keşfi başarısız/sonuçsuz ({}). Yerel yedeğe (samples/) geçiliyor...",
                    e.getMessage());
        }

        // 3. Canlı başarısız olduysa veya KAP erişilemezse yerel 'samples/' klasöründen yükle (Fallback)
        if (hasLocalSamples()) {
            log.info("[KapClient] Canlı veri temin edilemedi, yerel örnek dosya aranıyor: Fon='{}'", normalizedCode);
            return loadFromLocalSample(normalizedCode);
        }

        throw new IllegalStateException(String.format(
                "[KapClient] '%s' fonu için PDF temin edilemedi. Canlı URL: %s, yerel 'samples/' klasörü mevcut değil.",
                normalizedCode, (pdfUrl != null ? pdfUrl : "otomatik")));
    }

    /**
     * T3.1 & T3.2: Tam Otomatik KAP Bildirim Keşif ve İndirme Motoru
     * =============================================================
     * 1. KAP'ın bildirim sorgulama servisine fon kodu ile istek atar.
     * 2. Gelen HTML tablosundan en güncel "Portföy Dağılım Raporu" bildirim ID'sini yakalar.
     * 3. Bildirim detay sayfasından resmi ek dosya (/api/file/download/...) indirme linkini bulur.
     * 4. PDF'i indirir, kriptografik SHA-256 özetini üretir.
     *
     * @param fundCode Fon kodu (örn: "DFI", "THF")
     * @return KapPdfDto veya bulunamazsa null
     */
    public KapPdfDto discoverAndDownloadLatestPdf(String fundCode) {
        String searchUrl = "https://www.kap.org.tr/tr/bildirim-sorgu-sonuc?srcbar=Y&cmp=N&cat=2&kw="
                + fundCode + "&slf=ALL";

        try {
            // 1. ADIM: Bildirim listesi sayfasını çek (Tarayıcı kimliğiyle)
            String searchHtml = webClient.get()
                    .uri(searchUrl)
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                    .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                    .header("Accept-Language", "tr-TR,tr;q=0.9,en-US;q=0.8,en;q=0.7")
                    .retrieve()
                    .bodyToMono(String.class)
                    .block(Duration.ofSeconds(15));

            if (searchHtml == null || searchHtml.isEmpty()) {
                log.warn("[KapClient] KAP sorgu yanıtı boş döndü: {}", searchUrl);
                return null;
            }

            // 2. ADIM: HTML tablosundan en son "Portföy Dağılım Raporu" bildirim ID'sini ayıkla
            String disclosureId = extractLatestDisclosureId(searchHtml);
            if (disclosureId == null) {
                log.warn("[KapClient] Fon '{}' için 'Portföy Dağılım Raporu' bildirimi bulunamadı.", fundCode);
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

            log.info("[KapClient] 📥 Ek PDF indirme linki yakalandı: {}", pdfDownloadUrl);

            // 4. ADIM: PDF'i indir
            byte[] bytes = webClient.get()
                    .uri(pdfDownloadUrl)
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                    .retrieve()
                    .bodyToMono(byte[].class)
                    .block(Duration.ofSeconds(25));

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
     * KAP bildirim sorgu tablosundan "Portföy Dağılım Raporu" satırındaki bildirim ID'sini ayıklar.
     */
    String extractLatestDisclosureId(String html) {
        if (html == null) return null;

        // KAP HTML tablosunda her bildirim <tr id="notification..." satırındadır
        java.util.regex.Pattern trPattern = java.util.regex.Pattern.compile(
                "(?i)<tr[^>]*id=\"notification\\d+\"[^>]*>(.*?)</tr>", java.util.regex.Pattern.DOTALL);
        java.util.regex.Matcher trMatcher = trPattern.matcher(html);

        while (trMatcher.find()) {
            String rowHtml = trMatcher.group(1);
            // Satırda "Portföy Dağılım Raporu" veya "portf" geçiyor mu?
            if (rowHtml.toLowerCase().contains("portf")) {
                // <input id="1664251" ... name="notification-checkbox"
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
            byte[] bytes = webClient.get()
                    .uri(pdfUrl)
                    .retrieve()
                    .bodyToMono(byte[].class)
                    .block(Duration.ofSeconds(20));

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
     * Akıllı 'samples' dizini bulucu.
     * Mevcut çalışma dizinini (CWD) ve üst dizinlerini kontrol ederek
     * projedeki 'samples' klasörünün mutlak yolunu bulur.
     */
    private Path findSamplesDirectory() throws FileNotFoundException {
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
     * 'samples/' klasörü içinde adı '{FON_KODU}_*.pdf' kuralına uyan ilk dosyayı bulur.
     * Örnek: "THF" -> "THF_2026_08.pdf"
     */
    private Path findSamplePdfFile(Path samplesDir, String fundCode) throws FileNotFoundException {
        String prefix = fundCode.toUpperCase() + "_";

        try (DirectoryStream<Path> stream = Files.newDirectoryStream(samplesDir, "*.pdf")) {
            for (Path entry : stream) {
                String fileName = entry.getFileName().toString().toUpperCase();
                if (fileName.startsWith(prefix)) {
                    return entry;
                }
            }
        } catch (IOException e) {
            throw new RuntimeException("'samples' dizini taranamadı: " + e.getMessage(), e);
        }

        throw new FileNotFoundException("'samples/' klasöründe '" + prefix + "*.pdf' formatında dosya bulunamadı!");
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
