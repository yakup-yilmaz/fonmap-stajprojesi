package com.fonmap.domain.entity;

import com.fonmap.domain.enums.AssetClass;
import jakarta.persistence.*;
import jakarta.validation.constraints.*;
import lombok.*;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Instrument — Finansal Enstrüman Kanonik Kataloğu Entity'si
 * ============================================================
 *
 * Veritabanı Tablosu: instruments (Proje Kılavuzu Madde 4)
 *
 * SİSTEMDEKİ ROLÜ:
 * Piyasalarda işlem gören tüm varlıkların (hisse, döviz, emtia, bono, VİOP kontratı)
 * tek ve standart (kanonik) kataloğudur. Sistemdeki her varlığın TEK BİR kanonik kaydı
 * bu tabloda bulunur.
 *
 * NEDEN BU ENTITY VAR?
 * Farklı fonların portföyünde aynı hisse (Örn: THYAO) yer alır. Price Worker servisi
 * THYAO için fiyatı her fon için ayrı ayrı çekmez; bu tablodaki TEK kanonik kayıt
 * üzerinden 1 kez çeker. Böylece:
 * 1. API kotası boşa harcanmaz (7 fonda THYAO varsa 7 değil 1 istek atılır).
 * 2. Aynı hissenin farklı fonlarda farklı fiyata sahip olma paradoksu engellenir.
 *
 * 3 KADEMELİ EŞLEME ALGORİTMASI (Cascade Matching):
 * PDF'ten okunan bir menkul kıymet ismi şu sırayla eşleştirilir:
 * 1. Aşama: ticker alanıyla doğrudan eşleşme ("THYAO" → "THYAO") — %95 başarı oranı
 * 2. Aşama: isin_code alanıyla eşleşme ("TRATHYAO91M5" → "THYAO")
 * 3. Aşama: instrument_aliases tablosuyla fuzzy eşleşme
 *
 * İLİŞKİLER:
 * Bu entity de kök (root) entity'dir. Hiçbir tabloya FK vermez.
 * Kendisine FK veren tablolar:
 * - holdings (1-N): Bir enstrüman birden çok fonun portföyünde bulunabilir.
 * - instrument_aliases (1-N): Bir enstrümanın PDF'lerde farklı yazılmış isimleri olabilir.
 * - price_quotes (1-N): Bir enstrümanın dakika dakika fiyat geçmişi saklanır.
 * - estimate_details (1-N): Bir enstrümanın farklı tahminlerdeki katkı kayıtları.
 */
@Entity
@Table(name = "instruments")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Instrument {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    /**
     * Resmi borsa işlem sembolü (ticker).
     * Örnek: "THYAO", "AKBNK", "ASELS", "USDTRY", "XU100"
     *
     * unique = true: Aynı ticker ikinci kez eklenemez.
     * length = 20: BIST hisse kodları 3-6 karakter, VİOP kontratları ~12 karakter olur.
     *
     * Yahoo Finance'te fiyat çekmek için bu kodun arkasına ".IS" eklenir:
     *   THYAO → THYAO.IS (Borsa İstanbul suffix'i)
     *   USDTRY → USDTRY=X (Yahoo döviz formatı)
     */
    @Column(name = "ticker", nullable = false, unique = true, length = 20)
    @NotBlank(message = "Ticker kodu boş olamaz")
    private String ticker;

    /**
     * Uluslararası Menkul Kıymet Kimlik Numarası (ISIN - International Securities Identification Number).
     * Örnek: "TRATHYAO91M5" (Türk Hava Yolları'nın uluslararası kodu)
     *
     * 12 karakter uzunluğunda standart bir koddur. PDF'lerdeki 4. sütunda yer alır.
     * Eşleme algoritmasının 2. aşamasında (ticker bulunamazsa) kullanılır.
     * Nullable: Döviz kurları veya repo gibi varlıkların ISIN kodu olmayabilir.
     */
    @Column(name = "isin_code", length = 12)
    private String isinCode;

    /**
     * Varlığın resmi tam adı.
     * Örnek: "Türk Hava Yolları Anonim Ortaklığı", "Aselsan Elektronik Sanayi ve Ticaret A.Ş."
     */
    @Column(name = "title")
    private String title;

    /**
     * Varlık sınıfı — AssetClass enum'u ile eşleşir.
     *
     * @Enumerated(EnumType.STRING):
     * Enum değerini veritabanına METİN olarak yazar ("EQUITY", "VIOP", "BOND" gibi).
     * Alternatif olan EnumType.ORDINAL sayısal index (0, 1, 2...) yazar; ancak
     * enum'a yeni değer eklendiğinde veya sıra değiştiğinde tüm veriler bozulur!
     * Bu yüzden prodüksiyon sistemlerinde HER ZAMAN EnumType.STRING kullanılır.
     *
     * Calculation Engine bu alana bakarak fiyat çekme ve getiri hesaplama
     * stratejisini belirler (Strategy Pattern):
     *   EQUITY → Yahoo Finance canlı fiyat
     *   DEPOSIT → Günlük faiz tahakkuku
     *   BOND → Nötr (r=0)
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "asset_class", nullable = false, length = 20)
    @NotNull(message = "Varlık sınıfı boş olamaz")
    private AssetClass assetClass;

    /**
     * Varlığın işlem gördüğü para birimi (ISO 4217 kodu).
     * Örnek: "TRY" (Türk Lirası), "USD" (Amerikan Doları), "EUR" (Euro)
     * Döviz kuru dönüşümü gereken varlıklarda (yabancı hisseler) bu alan kullanılır.
     */
    @Column(name = "currency", nullable = false, length = 3)
    @NotBlank(message = "Para birimi boş olamaz")
    @Builder.Default
    private String currency = "TRY";

    // ==================== AUDIT ALANLARI ====================

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        this.createdAt = LocalDateTime.now();
        this.updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    protected void onUpdate() {
        this.updatedAt = LocalDateTime.now();
    }
}
