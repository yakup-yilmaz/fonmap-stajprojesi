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
     * Eğer 'pdfUrl' verilmişse önce canlı KAP sunucusundan indirmeyi dener.
     * İnternet hatası, zaman aşımı veya geçersiz URL durumunda SİSTEMİ ÇÖKERTMEDEN
     * yerel 'samples/' klasöründeki fon örneğine (fallback) geçer.
     *
     * @param fundCode Fon kodu (örn: "THF", "TLY", "TTE")
     * @param pdfUrl   KAP bildirim ekindeki PDF linki (opsiyonel / null olabilir)
     * @return Doldurulmuş ve SHA-256'sı hesaplanmış KapPdfDto nesnesi
     */
    public KapPdfDto fetchPdf(String fundCode, String pdfUrl) {
        if (fundCode == null || fundCode.trim().isEmpty()) {
            throw new IllegalArgumentException("[KapClient] Fon kodu boş olamaz!");
        }

        String normalizedCode = fundCode.trim().toUpperCase();

        // 1. URL verilmişse öncelikle canlı indirmeyi dene
        if (pdfUrl != null && !pdfUrl.trim().isEmpty()) {
            try {
                log.info("[KapClient] Canlı indirme başlatılıyor: Fon='{}', URL='{}'", normalizedCode, pdfUrl);
                return downloadFromUrl(normalizedCode, pdfUrl.trim());
            } catch (Exception e) {
                log.warn("[KapClient] Canlı indirme başarısız oldu ({}). Yerel örnek yedeğine (samples/) geçiliyor...",
                        e.getMessage());
            }
        }

        // 2. Canlı başarısız olduysa veya URL yoksa yerel 'samples/' klasöründen yükle
        log.info("[KapClient] Yerel örnek dosya aranıyor: Fon='{}'", normalizedCode);
        return loadFromLocalSample(normalizedCode);
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
    private String extractFileNameFromUrl(String url, String fundCode) {
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
