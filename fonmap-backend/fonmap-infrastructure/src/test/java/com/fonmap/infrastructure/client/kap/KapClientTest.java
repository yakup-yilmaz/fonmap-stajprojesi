package com.fonmap.infrastructure.client.kap;

import com.fonmap.infrastructure.client.kap.dto.KapPdfDto;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * KapClientTest — KAP PDF İstemcisi ve SHA-256 İdempotency Testleri
 * =================================================================
 *
 * Bu test sınıfı; KapClient'ın yerel 'samples/' klasöründeki gerçek PDF'leri
 * doğru bulup okuduğunu, 64 karakterlik kriptografik SHA-256 parmak izini
 * hatasız ve deterministik ürettiğini ve akıllı fallback mekanizmasını doğrular.
 */
class KapClientTest {

    private KapClient kapClient;

    @BeforeEach
    void setUp() {
        kapClient = new KapClient();
    }

    @Test
    @DisplayName("7 Fonun Tamamı İçin 'samples/' Klasöründen Yerel PDF Okuma ve SHA-256 Testi")
    void testLoadFromLocalSample_AllSevenFunds() {
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
    @DisplayName("Mevcut Olmayan Fon Kodu İstendiğinde Kontrollü Hata Fırlatma Testi")
    void testNonExistentFund_ThrowsException() {
        // Sistemde "BİLİNMEYEN_FON" kodlu bir örnek dosya yoktur
        Exception exception = assertThrows(RuntimeException.class, () -> {
            kapClient.loadFromLocalSample("BILINMEYEN");
        });

        assertTrue(exception.getMessage().contains("Yerel PDF okunamadı"));
    }

    @Test
    @DisplayName("Akıllı Fallback Testi: Canlı URL Ulaşılamazsa Sistemin Çökmeyip Yerel Örneğe Geçmesi")
    void testFetchPdfFallback_WhenUrlFails_UsesLocalSample() {
        // Geçersiz bir URL veriyoruz. Sistem hata verip patlamamalı,
        // loga uyarı basıp yerel 'samples/THF_*.pdf' dosyasına fallback yapmalıdır.
        String invalidUrl = "http://localhost:59999/gecersiz_link.pdf";

        KapPdfDto dto = kapClient.fetchPdf("THF", invalidUrl);

        assertNotNull(dto);
        assertEquals("THF", dto.getFundCode());
        assertEquals("LOCAL_SAMPLE", dto.getSource(), "Canlı başarısız olduğunda LOCAL_SAMPLE kaynağına geçmelidir");
        assertFalse(dto.isEmpty());
        assertNotNull(dto.getSha256Hash());
    }
}
