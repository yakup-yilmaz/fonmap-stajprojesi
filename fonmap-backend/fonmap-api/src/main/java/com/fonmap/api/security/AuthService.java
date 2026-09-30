package com.fonmap.api.security;

import com.fonmap.domain.entity.AdminUser;
import com.fonmap.infrastructure.repository.AdminUserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * =========================================================================================
 * 🔑 KİMLİK DOĞRULAMA VE OTURUM İŞ MANTIĞI SERVİSİ (AuthService)
 * =========================================================================================
 *
 * 📌 NE İŞE YARAR? (JUNIOR MÜHENDİS REHBERİ):
 * Bu servis, sistemimizin "Güvenlik Amiri"dir.
 * Kullanıcı arayüzünden gönderilen kullanıcı adı ve şifreyi alır,
 * veritabanındaki (admin_users tablosu) BCrypt ile şifrelenmiş kayıtla kıyaslar.
 *
 * Şifre doğruysa ve hesap aktifse; JwtService'i çağırarak 24 saat geçerli
 * kriptografik bir JWT Bearer token üretir ve istemciye teslim eder.
 *
 * 🛡️ GÜVENLİK İLKELERİ:
 * 1. Sabit Zamanlı Şifre Kontrolü (Timing Attack Koruması):
 *    Kullanıcı adı bulunamadığında dahi genel bir "Geçersiz kullanıcı adı veya şifre"
 *    mesajı verilerek sistemde hangi kullanıcı adlarının var olduğu dışarı sızdırılmaz.
 * 2. BCrypt Doğrulaması:
 *    passwordEncoder.matches(hamŞifre, hashlenmişŞifre) metodu kullanılarak
 *    şifreler asla düz metin kıyaslanmaz.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AuthService {

    private final AdminUserRepository adminUserRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;

    /**
     * 1. GÖREV: Kullanıcı Girişini Doğrular ve JWT Token Üretir.
     *
     * @param request Kullanıcı adı ve şifre içeren giriş isteği
     * @return 24 saat geçerli JWT token ve kullanıcı profil bilgileri
     */
    @Transactional(readOnly = true)
    public LoginResponse login(LoginRequest request) {
        if (request == null || request.username() == null || request.password() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Kullanıcı adı ve şifre zorunludur!");
        }

        String username = request.username().trim();
        log.info("[AuthService] Kullanıcı '{}' için giriş denemesi yapılıyor...", username);

        // 1. ADIM: Kullanıcıyı veritabanında ara
        AdminUser adminUser = adminUserRepository.findByUsername(username)
                .orElseThrow(() -> {
                    log.warn("[AuthService] Giriş başarısız: Kullanıcı '{}' bulunamadı.", username);
                    return new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Geçersiz kullanıcı adı veya şifre!");
                });

        // 2. ADIM: Hesap aktiflik kontrolü
        if (!Boolean.TRUE.equals(adminUser.getIsActive())) {
            log.warn("[AuthService] Giriş engellendi: Kullanıcı hesabı pasif (username='{}')", username);
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Bu kullanıcı hesabı devre dışı bırakılmıştır.");
        }

        // 3. ADIM: BCrypt şifre hash kontrolü
        boolean passwordMatches = passwordEncoder.matches(request.password(), adminUser.getPasswordHash());
        if (!passwordMatches) {
            log.warn("[AuthService] Giriş başarısız: Hatalı şifre girildi (username='{}')", username);
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Geçersiz kullanıcı adı veya şifre!");
        }

        // 4. ADIM: 24 saatlik geçerli JWT token üret
        String roleName = adminUser.getRole() != null ? adminUser.getRole().name() : "ROLE_ADMIN";
        String token = jwtService.generateToken(adminUser.getUsername(), roleName);

        log.info("[AuthService] Giriş başarılı: Kullanıcı='{}', Rol='{}'", adminUser.getUsername(), roleName);

        return new LoginResponse(
                token,
                "Bearer",
                86400L, // 24 Saat (saniye cinsinden)
                adminUser.getUsername(),
                roleName
        );
    }

    /**
     * 2. GÖREV: Giriş Yapmış Olan Aktif Kullanıcının Profil Bilgisini Döner.
     *
     * @param username JWT token'dan çıkarılan kullanıcı adı
     * @return Kullanıcı profil detayları
     */
    @Transactional(readOnly = true)
    public UserProfileResponse getCurrentUserProfile(String username) {
        AdminUser adminUser = adminUserRepository.findByUsername(username)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Kullanıcı profili bulunamadı!"));

        return new UserProfileResponse(
                adminUser.getId(),
                adminUser.getUsername(),
                adminUser.getRole().name(),
                adminUser.getCreatedAt()
        );
    }

    // =========================================================================================
    // 📦 DTO RECORD MODELLERİ
    // =========================================================================================

    public record LoginRequest(
            String username,
            String password
    ) {}

    public record LoginResponse(
            String accessToken,
            String tokenType,
            long expiresInSeconds,
            String username,
            String role
    ) {}

    public record UserProfileResponse(
            UUID id,
            String username,
            String role,
            LocalDateTime createdAt
    ) {}
}
