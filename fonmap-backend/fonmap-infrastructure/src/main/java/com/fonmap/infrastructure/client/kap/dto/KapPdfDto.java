package com.fonmap.infrastructure.client.kap.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

import java.time.LocalDateTime;

/**
 * KapPdfDto — KAP Portföy Dağılım Raporu PDF Veri Taşıma Nesnesi (DTO)
 * ====================================================================
 *
 * SİSTEMDEKİ ROLÜ:
 * Bu DTO; KAP'tan (Kamuyu Aydınlatma Platformu) WebClient ile canlı indirilen
 * veya test/çevrimdışı ortamda projedeki 'samples/*.pdf' klasöründen okunan
 * bir fon portföy dağılım raporunun:
 * 1. Ham bayt içeriğini (byte[] content)
 * 2. Dosya adını (fileName)
 * 3. Hangi fona ait olduğunu (fundCode: THF, TLY vb.)
 * 4. Kriptografik parmak izini (sha256Hash)
 * 5. Nereden temin edildiğini (source: KAP_ONLINE veya LOCAL_SAMPLE)
 * 6. İndirilme/okunma anını (downloadedAt)
 * tek bir tip güvenli (type-safe) pakette toplar.
 *
 * NEDEN SHA-256 VAR? (Idempotency - Mükerrerlik Koruması):
 * Dosyanın adı veya URL'si değişse bile, içerik baytlarından üretilen 64 karakterlik
 * SHA-256 hash'i ASLA DEĞİŞMEZ. Veritabanımızdaki 'fund_reports.pdf_sha256' sütunu
 * UNIQUE kısıtına sahiptir. Bu DTO üzerinden alınan hash ile veritabanında sorgulama
 * yapılır ve aynı dosyanın tekrar tekrar parse edilerek sunucu kaynaklarını yorması
 * ve mükerrer snapshot üretmesi %100 engellenir.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@ToString(exclude = "content") // Loglara on binlerce byte'lık binary basılmasını engeller
public class KapPdfDto {

    /**
     * İlgili fonun resmi 3-4 harfli TEFAS/KAP kodu.
     * Örnek: "THF", "TLY", "TMV", "TTE"
     */
    private String fundCode;

    /**
     * Dosyanın adı veya KAP bildirim eki dosya adı.
     * Örnek: "THF_2026_08.pdf" veya "4028328c8_ek.pdf"
     */
    private String fileName;

    /**
     * PDF belgesinin ham ikili (binary) bayt dizisi.
     * Bu baytlar Apache PDFBox tarafından bellek içinde doğrudan açılarak
     * tablolara ve hisse satırlarına dönüştürülecektir.
     */
    private byte[] content;

    /**
     * PDF içeriğinin 64 karakterlik küçük harfli (lowercase) SHA-256 kriptografik özeti.
     * Örnek: "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"
     */
    private String sha256Hash;

    /**
     * Dosyanın temin edildiği kaynak.
     * Değerler:
     * - "KAP_ONLINE": KAP resmi web sunucusundan canlı olarak indirildi.
     * - "LOCAL_SAMPLE": Test/çevrimdışı amacıyla 'samples/' klasöründen okundu.
     */
    private String source;

    /**
     * Dosyanın indirildiği veya yerel diskten okunduğu sistem zamanı.
     */
    private LocalDateTime downloadedAt;

    /**
     * PDF'in bayt cinsinden büyüklüğünü döner.
     */
    public int getSizeInBytes() {
        return content != null ? content.length : 0;
    }

    /**
     * PDF'in Kilobyte (KB) cinsinden büyüklüğünü döner (ondalıklı).
     */
    public double getSizeInKb() {
        return getSizeInBytes() / 1024.0;
    }

    /**
     * Dosya içeriğinin boş olup olmadığını denetler.
     */
    public boolean isEmpty() {
        return content == null || content.length == 0;
    }
}
