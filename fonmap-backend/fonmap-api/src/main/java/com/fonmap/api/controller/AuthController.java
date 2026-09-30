package com.fonmap.api.controller;

import com.fonmap.api.security.AuthService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.security.Principal;

/**
 * =========================================================================================
 * 🔑 KİMLİK DOĞRULAMA VE GİRİŞ REST CONTROLLER (AuthController)
 * =========================================================================================
 *
 * 📌 NE İŞE YARAR? (JUNIOR MÜHENDİS REHBERİ):
 * Bu Controller, sistemimizin "Giriş Kapısı ve Danışma Masası"dır.
 * Admin kullanıcılarının web arayüzünden kullanıcı adı ve şifresiyle sisteme
 * giriş yapmasını sağlar ve başarılı giriş sonrasında 24 saat geçerli bir JWT
 * Bearer Token teslim eder.
 *
 * 🏗️ MİMARİDEKİ ROLÜ:
 * 1. POST /api/v1/auth/login:
 *    Herkese açıktır (SecurityConfig tarafından izin verilir). Kullanıcı adı ve şifreyi
 *    AuthService'e gönderir. Şifre doğruysa JWT döner.
 * 2. GET /api/v1/auth/me:
 *    JWT token ile korunan bir uçtur. Giriş yapmış adminin oturum durumunu ve
 *    profil bilgilerini doğrular.
 */
@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
@Slf4j
@CrossOrigin(origins = "*") // Frontend Next.js için CORS izni
@Tag(name = "0. Kimlik Doğrulama (Auth Controller)", description = "Admin girişi ve JWT token temin uç noktaları")
public class AuthController {

    private final AuthService authService;

    /**
     * 1. UÇ NOKTA: Admin Girişi ve 24 Saatlik JWT Token Üretimi
     * -------------------------------------------------------------------------------------
     * Kullanıcı adı ("admin") ve şifre ("admin123") ile çağrılır.
     *
     * Örnek İstek Gövdesi:
     * {
     *   "username": "admin",
     *   "password": "admin123"
     * }
     *
     * Örnek Yanıt: 200 OK
     * {
     *   "accessToken": "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9...",
     *   "tokenType": "Bearer",
     *   "expiresInSeconds": 86400,
     *   "username": "admin",
     *   "role": "ROLE_ADMIN"
     * }
     *
     * @param request Kullanıcı adı ve şifre
     * @return 24 saat geçerli JWT Bearer token
     */
    @PostMapping("/login")
    @Operation(
            summary = "Admin Girişi ve JWT Token Alma",
            description = "Kullanıcı adı ve şifreyle giriş yaparak korumalı /api/v1/admin uçlarına erişim sağlayan 24 saat geçerli JWT döner."
    )
    public ResponseEntity<AuthService.LoginResponse> login(@RequestBody AuthService.LoginRequest request) {
        log.info("[AuthController] POST /api/v1/auth/login isteği alındı (Kullanıcı: '{}')",
                request != null ? request.username() : "boş");

        AuthService.LoginResponse response = authService.login(request);
        return ResponseEntity.ok(response);
    }

    /**
     * 2. UÇ NOKTA: Aktif Giriş Yapmış Admin Profil Bilgisi
     * -------------------------------------------------------------------------------------
     * İstemci 'Authorization: Bearer <token>' başlığıyla bu uca istek attığında,
     * token'ın kime ait olduğunu ve yetki seviyesini doğrular.
     *
     * @param principal Spring Security bağlamındaki onaylanmış kullanıcı nesnesi
     * @return Kullanıcı profil bilgisi
     */
    @GetMapping("/me")
    @Operation(
            summary = "Mevcut Kullanıcı Profil Bilgisi",
            description = "Gönderilen JWT token'ın kime ait olduğunu ve geçerliliğini doğrular.",
            security = @SecurityRequirement(name = "BearerAuth")
    )
    public ResponseEntity<AuthService.UserProfileResponse> getCurrentUser(Principal principal) {
        if (principal == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Oturum açılmamış!");
        }

        log.info("[AuthController] GET /api/v1/auth/me sorgulandı: Kullanıcı='{}'", principal.getName());
        AuthService.UserProfileResponse profile = authService.getCurrentUserProfile(principal.getName());
        return ResponseEntity.ok(profile);
    }
}
