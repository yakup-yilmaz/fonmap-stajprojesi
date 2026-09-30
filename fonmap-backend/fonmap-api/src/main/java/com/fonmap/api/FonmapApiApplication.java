package com.fonmap.api;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.util.TimeZone;

/**
 * =========================================================================================
 * 🚀 FONMAP REST API & BACKEND ANA ÇALIŞTIRICI SINIFI (FonmapApiApplication)
 * =========================================================================================
 *
 * 📌 NE İŞE YARAR? (JUNIOR MÜHENDİS REHBERİ):
 * Bu sınıf, tüm Fonmap backend projesinin "Kontak Anahtarı"dır (Entry Point).
 * Spring Boot konteynerini ayağa kaldırır, Tomcat gömülü web sunucusunu başlatır ve
 * projenin 4 modülünü (domain, infrastructure, application ve api) bir araya getirerek
 * birbirleriyle konuşturur.
 *
 * 🏗️ MİMARİDEKİ ROLÜ VE ANOTASYONLARIN SIRRI:
 *
 * 1. @SpringBootApplication(scanBasePackages = "com.fonmap"):
 *    - Çok modüllü (Multi-Module) bir Maven projesinde olduğumuz için varsayılan paket taraması
 *      yalnızca 'com.fonmap.api' altını görür. 'scanBasePackages = "com.fonmap"' diyerek
 *      'fonmap-application' ve 'fonmap-infrastructure' içindeki tüm @Service, @Component,
 *      ve @Repository bean'lerinin Spring tarafından otomatik keşfedilmesini sağlıyoruz.
 *    - 'exclude = SecurityAutoConfiguration.class': JWT güvenlik filtrelerimizi ve login
 *      akışımızı yazana kadar Spring Security'nin tüm uç noktaları otomatik kilitleyip
 *      401 Unauthorized dönmesini engeller; public REST API uçlarımızı erişilebilir tutar.
 *
 * 2. @EntityScan(basePackages = "com.fonmap.domain.entity"):
 *    - Hibernate / JPA'ya PostgreSQL tablolarımıza karşılık gelen varlıklarımızın (Fund, Holding,
 *      FundSnapshot vb.) 'fonmap-domain' modülü altında olduğunu açıkça bildirir.
 *
 * 3. @EnableJpaRepositories(basePackages = "com.fonmap.infrastructure.repository"):
 *    - Spring Data JPA'ya veri tabanı sorgu arayüzlerimizin (FundRepository vb.)
 *      'fonmap-infrastructure' modülü altında olduğunu bildirir.
 *
 * 4. @EnableScheduling & @EnableAsync:
 *    - Faz 5'te devreye girecek dakikalık borsa seansı worker'ları, sabah 09:50 ağırlık kayması
 *      ve gece 23:00 TEFAS mutabakatı gibi otomatik arka plan görevlerinin (Cron Jobs)
 *      ve asenkron metotların (@Async) çalışabilmesi için Spring altyapısını aktif eder.
 *
 * 5. Saat Dilimi Standardizasyonu (TimeZone.setDefault):
 *    - BIST ve TEFAS saatlerine tam uyum için sunucu nerede çalışırsa çalışsın (AWS, Docker, Local)
 *      uygulama saat dilimini kesin olarak "Europe/Istanbul" (UTC+3) yapar.
 */
@SpringBootApplication(scanBasePackages = "com.fonmap")
@EntityScan(basePackages = "com.fonmap.domain.entity")
@EnableJpaRepositories(basePackages = "com.fonmap.infrastructure.repository")
@EnableScheduling
@EnableAsync
public class FonmapApiApplication {

    public static void main(String[] args) {
        // 1. ADIM: Sunucu saat dilimini BIST/TEFAS ile eşitle (İstanbul Saati: UTC+3)
        TimeZone.setDefault(TimeZone.getTimeZone("Europe/Istanbul"));

        // 2. ADIM: Spring Boot uygulamasını ve gömülü Tomcat web sunucusunu başlat
        SpringApplication.run(FonmapApiApplication.class, args);
    }
}
