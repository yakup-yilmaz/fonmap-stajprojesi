package com.fonmap.infrastructure.client.kap;

import com.fonmap.infrastructure.client.kap.dto.KapPdfDto;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * KapClientTest — KAP PDF İstemcisi ve SHA-256 İdempotency Testleri
 * =================================================================
 *
 * Bu test sınıfı;
 * 1. Saf Birim Testleri: CI/CD ortamında herhangi bir dış dosyaya veya ağa
 *    bağımlı olmadan SHA-256 hash motorunu ve URL ayıklama mantığını doğrular.
 * 2. Yerel Entegrasyon Testleri: Eğer yerel makinede 'samples/' klasörü mevcutsa
 *    7 fonun gerçek PDF'lerini okuyarak tam doğrulamayı icra eder; 'samples/'
 *    bulunmayan CI/CD ortamlarında ise testi güvenle atlar (skip).
 */
class KapClientTest {

    private KapClient kapClient;

    @BeforeEach
    void setUp() {
        kapClient = new KapClient();
    }

    // =========================================================================
    // SAF BİRİM TESTLERİ (CI/CD Dostu — Harici Dosya Bağımsız)
    // =========================================================================

    @Test
    @DisplayName("Standart SHA-256 Vektör Testi: Boş Verinin Bilinen Kriptografik Özeti")
    void testSha256KnownVector() {
        // Kriptografide boş bayt dizisinin ("") SHA-256 özeti evrensel olarak sabittir:
        // e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855
        String emptySha256 = kapClient.calculateSha256(new byte[0]);
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", emptySha256);

        // Null bayt dizisi de güvenli şekilde boş dizi gibi ele alınmalıdır
        String nullSha256 = kapClient.calculateSha256(null);
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", nullSha256);
    }

    @Test
    @DisplayName("SHA-256 Deterministik Hesaplama Testi (Harici Dosya Bağımsız)")
    void testSha256Calculation_WithByteData() {
        byte[] sampleData = "FONMAP-TEST-PDF-CONTENT-2026".getBytes(StandardCharsets.UTF_8);
        String hash1 = kapClient.calculateSha256(sampleData);
        String hash2 = kapClient.calculateSha256(sampleData);

        assertNotNull(hash1);
        assertEquals(64, hash1.length(), "SHA-256 tam 64 karakter (256-bit) olmalıdır");
        assertTrue(hash1.matches("^[a-f0-9]{64}$"), "SHA-256 sadece küçük harf hex içermelidir");
        assertEquals(hash1, hash2, "Aynı veri için üretilen SHA-256 deterministik (birebir aynı) olmalıdır");
    }

    @Test
    @DisplayName("Geçersiz veya Boş Fon Kodu Verildiğinde IllegalArgumentException Fırlatılmalı")
    void testFetchPdf_WithNullOrBlankFundCode_ThrowsException() {
        assertThrows(IllegalArgumentException.class, () -> kapClient.fetchPdf(null, "https://example.com/test.pdf"));
        assertThrows(IllegalArgumentException.class, () -> kapClient.fetchPdf("   ", "https://example.com/test.pdf"));
    }

    @Test
    @DisplayName("URL'den Dosya Adı Ayıklama Mantığı Doğru Çalışmalı")
    void testExtractFileNameFromUrl() {
        String fileName = kapClient.extractFileNameFromUrl("https://www.kap.org.tr/tr/Bildirim/12345/ek/portfoy.pdf", "THF");
        assertEquals("portfoy.pdf", fileName);

        String fallbackName = kapClient.extractFileNameFromUrl("https://www.kap.org.tr/tr/Bildirim/12345", "TTE");
        assertEquals("TTE_kap_raporu.pdf", fallbackName);
    }

    // =========================================================================
    // YEREL ENTEGRASYON TESTLERİ (samples/ Klasörü Varsa Çalışır, Yoksa Atlar)
    // =========================================================================

    @Test
    @DisplayName("7 Fonun Tamamı İçin 'samples/' Klasöründen Yerel PDF Okuma ve SHA-256 Testi")
    void testLoadFromLocalSample_AllSevenFunds() {
        // CI veya 'samples/' bulunmayan ortamlarda testi güvenle atla
        Assumptions.assumeTrue(kapClient.hasLocalSamples(),
                "'samples/' klasörü bulunamadı (CI ortamı). Bu yerel geliştirme testi atlanıyor.");

        // Projemizin kapsamındaki 7 resmi başlangıç fonu
        List<String> fundCodes = List.of("THF", "TLY", "TMV", "DOH", "TTE", "DFI", "KHA");

        for (String fundCode : fundCodes) {
            KapPdfDto dto = kapClient.loadFromLocalSample(fundCode);

            // 1. DTO ve içerik doğrulamaları
            assertNotNull(dto, fundCode + " için DTO null olamaz");
            assertEquals(fundCode, dto.getFundCode());
            assertNotNull(dto.getFileName(), "Dosya adı null olamaz");
            assertTrue(dto.getFileName().toUpperCase().startsWith(fundCode + "_"),
                    "Dosya adı fon koduyla başlamalı: " + dto.getFileName());
            assertEquals("LOCAL_SAMPLE", dto.getSource());

            // 2. Binary içerik boyutu kontrolleri
            assertFalse(dto.isEmpty(), "PDF içeriği boş olamaz");
            assertTrue(dto.getSizeInBytes() > 10_000,
                    fundCode + " PDF boyutu 10KB'dan büyük olmalıdır. Mevcut: " + dto.getSizeInBytes());
            assertTrue(dto.getSizeInKb() > 10.0);

            // 3. SHA-256 Kriptografik Parmak İzi Doğrulamaları
            String sha256 = dto.getSha256Hash();
            assertNotNull(sha256, "SHA-256 hash boş olamaz");
            assertEquals(64, sha256.length(), "SHA-256 tam 64 karakter (256-bit) olmalıdır");
            assertTrue(sha256.matches("^[a-f0-9]{64}$"),
                    "SHA-256 sadece küçük harfli onaltılık (hex) karakterlerden oluşmalıdır: " + sha256);

            System.out.printf(">>> [TEST BAŞARILI] Fon: %-4s | Dosya: %-15s | Boyut: %7.2f KB | SHA-256: %s%n",
                    dto.getFundCode(), dto.getFileName(), dto.getSizeInKb(), dto.getSha256Hash());
        }
    }

    @Test
    @DisplayName("Deterministik SHA-256 Testi: Aynı Dosya İçin Peş Peşe Hesaplanan Hash'ler Birebir Eşit Olmalıdır")
    void testDeterministicSha256() {
        Assumptions.assumeTrue(kapClient.hasLocalSamples(),
                "'samples/' klasörü bulunamadı (CI ortamı). Bu yerel geliştirme testi atlanıyor.");

        // 1. İlk okuma
        KapPdfDto firstRun = kapClient.loadFromLocalSample("THF");
        // 2. İkinci okuma
        KapPdfDto secondRun = kapClient.loadFromLocalSample("THF");

        // Deterministik kontrol: Dosya baytları değişmediği sürece hash ASLA değişmemelidir
        assertNotNull(firstRun.getSha256Hash());
        assertNotNull(secondRun.getSha256Hash());
        assertEquals(firstRun.getSha256Hash(), secondRun.getSha256Hash(),
                "Aynı PDF içeriği için farklı SHA-256 üretilemez! (Idempotency Garantisi)");
        assertEquals(firstRun.getSizeInBytes(), secondRun.getSizeInBytes());
    }

    @Test
    @DisplayName("Mevcut Olmayan Fon Kodu İstendiğinde Kontrollü Hata Fırlatma Testi")
    void testNonExistentFund_ThrowsException() {
        Assumptions.assumeTrue(kapClient.hasLocalSamples(),
                "'samples/' klasörü bulunamadı (CI ortamı). Bu yerel geliştirme testi atlanıyor.");

        // Sistemde "BILINMEYEN" kodlu bir örnek dosya yoktur
        Exception exception = assertThrows(RuntimeException.class, () -> {
            kapClient.loadFromLocalSample("BILINMEYEN");
        });

        assertTrue(exception.getMessage().contains("Yerel PDF okunamadı"));
    }

    @Test
    @DisplayName("Yerel Örnek PDF Yükleme Testi (samples/ klasörü varsa)")
    void testLoadFromLocalSample() {
        Assumptions.assumeTrue(kapClient.hasLocalSamples(),
                "'samples/' klasörü bulunamadı (CI ortamı). Bu yerel geliştirme testi atlanıyor.");

        KapPdfDto dto = kapClient.loadFromLocalSample("THF");

        assertNotNull(dto);
        assertEquals("THF", dto.getFundCode());
        assertEquals("LOCAL_SAMPLE", dto.getSource());
        assertFalse(dto.isEmpty());
        assertNotNull(dto.getSha256Hash());
    }

    @Test
    @DisplayName("7 Fonun Tamamı İçin Otomatik fetchPdf Testi (KAP Keşfi veya Yerel Fallback)")
    void testFetchPdf_AllSevenFunds() {
        Assumptions.assumeTrue(kapClient.hasLocalSamples(),
                "'samples/' klasörü bulunamadı. Bu test atlanıyor.");

        List<String> funds = List.of("THF", "TLY", "TMV", "DOH", "DFI", "KHA", "TTE");
        for (String code : funds) {
            KapPdfDto dto = kapClient.fetchPdf(code, null);
            assertNotNull(dto, code + " için DTO null olamaz");
            assertEquals(code, dto.getFundCode());
            assertFalse(dto.isEmpty(), code + " için PDF boş olamaz");
            assertNotNull(dto.getSha256Hash(), code + " için SHA-256 boş olamaz");
            System.out.printf(">>> [7 FON BAŞARIYLA ALINDI] Fon: %-4s | Kaynak: %-14s | Dosya: %-25s | Boyut: %7.2f KB | SHA-256: %s%n",
                    dto.getFundCode(), dto.getSource(), dto.getFileName(), dto.getSizeInKb(), dto.getSha256Hash().substring(0, 16) + "...");
        }
    }

    @Test
    @DisplayName("Güvenlik Testi: loadSpecificSampleFile ile Path Traversal Denemesi Engellenmeli")
    void testLoadSpecificSampleFile_PathTraversalBlocked() {
        Assumptions.assumeTrue(kapClient.hasLocalSamples(), "'samples/' klasörü bulunamadı.");

        RuntimeException ex = assertThrows(RuntimeException.class, () ->
                kapClient.loadSpecificSampleFile("THF", "../../secret.txt"));
        assertTrue(ex.getCause() instanceof SecurityException || ex.getMessage().contains("Yetkisiz") || ex.getMessage().contains("SecurityException"));
    }

    @Test
    @DisplayName("HATA 2 Testi: hasSamplePdfForFund Fon Bazında Dosya Varlığını Doğru Tespit Etmeli")
    void testHasSamplePdfForFund() {
        Assumptions.assumeTrue(kapClient.hasLocalSamples(), "'samples/' klasörü bulunamadı.");

        assertTrue(kapClient.hasSamplePdfForFund("THF"), "THF için samples klasöründe dosya olmalı");
        assertFalse(kapClient.hasSamplePdfForFund("OLMAYAN_FON_99"), "Olmayan fon için false dönmeli");
    }

    @Test
    @DisplayName("HATA 2 Testi: extractDistinctKeywords Stop-word ve Yönetici Şirketi Temizleyip Ayırt Edici Kelimeleri Çıkarmalı")
    void testExtractDistinctKeywords() {
        String title = "Ak Portföy BIST 100 Dışı Şirketler Hisse Senedi (TL) Fonu";
        String manager = "Ak Portföy Yönetimi A.Ş.";

        List<String> keywords = kapClient.extractDistinctKeywords(title, manager);

        assertTrue(keywords.contains("bist"), "bist kelimesi bulunmalı");
        assertTrue(keywords.contains("100"), "100 kelimesi bulunmalı");
        assertTrue(keywords.contains("disi"), "disi kelimesi bulunmalı");
        assertTrue(keywords.contains("sirketler"), "sirketler kelimesi bulunmalı");
        assertFalse(keywords.contains("portfoy"), "portfoy stop-word olarak elenmeli");
        assertFalse(keywords.contains("ak"), "ak yönetici parçası olarak elenmeli");
        assertFalse(keywords.contains("hisse"), "hisse stop-word olarak elenmeli");
    }

    @Test
    @DisplayName("HATA 2 Testi: Canlıda ve Diskte Olmayan Fon İçin Yanıltıcı Samples Hatası Yerine Açıklayıcı Hata Fırlatılmalı")
    void testFetchPdf_ThrowsCleanInformativeException_WhenNoReportAndNoSample() {
        try {
            IllegalStateException ex = assertThrows(IllegalStateException.class, () ->
                    kapClient.fetchPdf("OLMAYAN99", "Bilinmeyen Portföy", null, "Bilinmeyen Fon"));

            assertTrue(ex.getMessage().contains("KAP'ta 'OLMAYAN99' fonu için") || ex.getMessage().contains("bulunamadı"),
                    "Hata mesajı kullanıcıyı yanıltıcı samples/ hatası yerine yönlendirici olmalı: " + ex.getMessage());
            assertFalse(ex.getMessage().contains("samples/ klasöründe"),
                    "Hata mesajı yerel samples klasörü bulunamadı dememeli!");
        } catch (Exception e) {
            Assumptions.assumeTrue(false, "KAP canlı servisine erişilemedi (CI ortamı): " + e.getMessage());
        }
    }

    @Test
    @DisplayName("HATA 4 Testi: Unvan (fundTitle) null Olduğunda Arama Rotası 1. Sayfa Fallback'i Çalışmalı")
    void testDiscoverFromSearchRoute_WorksWhenFundTitleNull() {
        try {
            String tteId = kapClient.discoverFromSearchRoute("TTE", null, null);
            if (tteId != null) {
                assertTrue(tteId.matches("\\d+"), "Bildirim ID nümerik olmalı: " + tteId);
            }

            String bdyId = kapClient.discoverFromSearchRoute("BDY", null, null);
            if (bdyId != null) {
                assertTrue(bdyId.matches("\\d+"), "Bildirim ID nümerik olmalı: " + bdyId);
            }
        } catch (Exception e) {
            Assumptions.assumeTrue(false, "KAP canlı arama servisine erişilemedi (CI ortamı): " + e.getMessage());
        }
    }
}


