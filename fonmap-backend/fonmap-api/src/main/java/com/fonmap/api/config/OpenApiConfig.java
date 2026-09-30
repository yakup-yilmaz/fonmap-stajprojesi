package com.fonmap.api.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.servers.Server;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * =========================================================================================
 * 📖 SWAGGER / OPENAPI 3.0 DOKÜMANTASYON YAPILANDIRMASI (OpenApiConfig)
 * =========================================================================================
 *
 * 📌 NE İŞE YARAR? (JUNIOR MÜHENDİS REHBERİ):
 * Bir backend geliştiricisinin en önemli görevi, yazdığı API'leri Frontend ekibine
 * veya üçüncü parti entegratörlere eksiksiz ve anlaşılır bir şekilde sunmaktır.
 *
 * Bu sınıf; SpringDoc OpenAPI kütüphanesini yapılandırarak projemiz ayağa kalktığında
 * tarayıcıdan http://localhost:8080/swagger-ui.html adresine girildiğinde
 * tüm Controller'ları, DTO modellerini ve uç noktaları interaktif bir test arayüzü
 * olarak sunar.
 *
 * 🏗️ MİMARİDEKİ ROLÜ:
 * - Geliştiricilerin Postman olmadan doğrudan tarayıcı üzerinden API'leri test etmesini sağlar ("Try it out").
 * - API başlığı, versiyonu, geliştirici iletişim bilgileri ve SPK yasal uyarısını tanımlar.
 */
@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI customOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("🚀 FONMAP — BIST & TEFAS Fon Portföy Ayrıştırma ve Canlı Değerleme API")
                        .version("1.0.0")
                        .description("""
                                ### Fonmap Platformu REST API Dokümantasyonu
                                
                                Bu API kümesi, Kamuyu Aydınlatma Platformu'nda (KAP) yayımlanan aylık portföy dağılım raporlarını
                                Apache PDFBox ile ayrıştırır, BIST seansı boyunca hisse fiyat hareketlerine göre dinamik ağırlık kaymasını
                                (Weight Drift) hesaplar ve fonların gün içi anlık tahmini TL fiyatlarını (%+ getiri) sunar.
                                
                                **Temel Modüller:**
                                * **1. Fon Kataloğu:** Takip edilen 7 fonun genel kartları ve künye detayları.
                                * **2. Canlı Tahminler:** Anlık TL fiyatı, yüzde getiri, kapsama oranı ve Günün Liderleri/Baskılayanları.
                                * **3. Yönetim & Operasyon (Admin):** Manuel PDF yükleme, geçmiş doğruluk (Backtest) ve mutabakat tetikleme.
                                
                                > *Yasal Uyarı: Burada sunulan veriler ekonometrik model tahminleridir. Yatırım tavsiyesi niteliği taşımaz.*
                                """)
                        .contact(new Contact()
                                .name("Fonmap Geliştirme Ekibi")
                                .email("destek@fonmap.com")
                                .url("https://fonmap.com"))
                        .license(new License()
                                .name("Telif Hakkı © 2026 Fonmap A.Ş. — Tüm Hakları Saklıdır.")
                                .url("https://fonmap.com/license")))
                .servers(List.of(
                        new Server().url("http://localhost:8080").description("Yerel Geliştirme Sunucusu (Local Dev)"),
                        new Server().url("https://api.fonmap.com").description("Canlı Üretim Sunucusu (Production)")
                ));
    }
}
