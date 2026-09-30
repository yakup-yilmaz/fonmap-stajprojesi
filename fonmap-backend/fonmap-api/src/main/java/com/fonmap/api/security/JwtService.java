package com.fonmap.api.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;

/**
 * =========================================================================================
 * 🔐 JWT TOKEN ÜRETİM VE DOĞRULAMA SERVİSİ (JwtService)
 * =========================================================================================
 *
 * 📌 NE İŞE YARAR? (JUNIOR MÜHENDİS REHBERİ):
 * Bu servis, sistemimizin "Kriptografik Pasaport Memuru"dur.
 * Admin kullanıcısı giriş yaptığında (Login) ona 24 saat geçerli, şifrelenmiş dijital
 * bir kimlik kartı (JSON Web Token - JWT) üretir.
 *
 * Daha sonra admin /api/v1/admin/... uç noktalarına her istek attığında, bu servis
 * gelen token'ın imzasını doğrular, süresinin dolup dolmadığını kontrol eder ve
 * kullanıcının yetki rolünü (ROLE_ADMIN) ayıklar.
 *
 * 🏗️ MİMARİDEKİ ROLÜ (STATELESS REST GÜVENLİĞİ):
 * Klasik web uygulamalarındaki Session (Oturum) mantığı yerine REST API standardı olan
 * "Durumsuz" (Stateless) yapıyı kurar. Sunucu belleğinde oturum tutulmaz; tüm yetki
 * bilinci kriptografik olarak imzalanmış bu token içinde taşınır.
 *
 * 🛡️ KULLANILAN TEKNOLOJİ (JJWT 0.12+):
 * HMAC-SHA256 algoritması ile 256-bit gizli anahtar (Secret Key) üzerinden
 * kriptografik imzalama yapar.
 */
@Service
@Slf4j
public class JwtService {

    /**
     * JWT imzalamada kullanılan 256-bitlik (en az 32 karakter) süper gizli anahtar.
     * application.properties'ten okunur; yoksa güvenli bir varsayılan kullanılır.
     */
    @Value("${fonmap.jwt.secret:FonmapSuperSecretKeyForJwtAuthenticationMustBeAtLeast256BitsLong2026!}")
    private String jwtSecret;

    /**
     * Token geçerlilik süresi (Varsayılan: 24 Saat = 86.400.000 milisaniye).
     * Refresh token karmaşasına girmeden admin için konforlu bir çalışma süresi sağlar.
     */
    @Value("${fonmap.jwt.expiration-ms:86400000}")
    private long jwtExpirationMs;

    /**
     * 1. GÖREV: Başarılı Giriş Yapan Admin İçin Yeni JWT Token Üretir.
     *
     * @param username Kullanıcı adı (Örn: "admin")
     * @param role     Yetki rolü (Örn: "ROLE_ADMIN")
     * @return 3 parçalı (Header.Payload.Signature) Base64 URL kodlu JWT karakter dizisi
     */
    public String generateToken(String username, String role) {
        Date now = new Date();
        Date expiryDate = new Date(now.getTime() + jwtExpirationMs);

        String token = Jwts.builder()
                .subject(username)
                .claim("role", role)
                .issuedAt(now)
                .expiration(expiryDate)
                .signWith(getSigningKey())
                .compact();

        log.info("[JwtService] Kullanıcı '{}' (Rol: {}) için 24 saatlik yeni JWT token üretildi.", username, role);
        return token;
    }

    /**
     * 2. GÖREV: İstemciden Gelen Token'ın Geçerliliğini ve İmzasını Denetler.
     *
     * @param token Bearer başlığından çıkarılan JWT
     * @return İmza doğru ve süre geçerli ise true; aksi halde false
     */
    public boolean validateToken(String token) {
        if (token == null || token.isBlank()) {
            return false;
        }

        try {
            Jwts.parser()
                    .verifyWith(getSigningKey())
                    .build()
                    .parseSignedClaims(token);
            return true;
        } catch (JwtException | IllegalArgumentException e) {
            log.warn("[JwtService] Geçersiz veya süresi dolmuş JWT token: {}", e.getMessage());
            return false;
        }
    }

    /**
     * 3. GÖREV: Token İçinden Kullanıcı Adını (Subject) Ayıklar.
     *
     * @param token Geçerli JWT
     * @return Kullanıcı adı (Örn: "admin")
     */
    public String extractUsername(String token) {
        return extractAllClaims(token).getSubject();
    }

    /**
     * 4. GÖREV: Token İçinden Yetki Rolünü (Role Claim) Ayıklar.
     *
     * @param token Geçerli JWT
     * @return Rol adı (Örn: "ROLE_ADMIN")
     */
    public String extractRole(String token) {
        Object role = extractAllClaims(token).get("role");
        return role != null ? role.toString() : "ROLE_USER";
    }

    /**
     * Yardımcı Metot: Gizli anahtarı HMAC-SHA formatına dönüştürür.
     */
    private SecretKey getSigningKey() {
        byte[] keyBytes = jwtSecret.getBytes(StandardCharsets.UTF_8);
        return Keys.hmacShaKeyFor(keyBytes);
    }

    /**
     * Yardımcı Metot: Token'ı çözerek içindeki Claims (İddialar/Veriler) yükünü döner.
     */
    private Claims extractAllClaims(String token) {
        return Jwts.parser()
                .verifyWith(getSigningKey())
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }
}
