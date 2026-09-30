package com.fonmap.api.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Collections;

/**
 * =========================================================================================
 * 🛡️ SPRING SECURITY 6 VE JWT FİLTRE YAPILANDIRMASI (SecurityConfig)
 * =========================================================================================
 *
 * 📌 NE İŞE YARAR? (JUNIOR MÜHENDİS REHBERİ):
 * Bu sınıf, sistemimizin "Genel Güvenlik Protokolü ve Turnike Sistemi"dir.
 * Spring Security'nin modern (Spring Boot 3 / Security 6) standartlarına göre:
 * 1. Kimlerin şifresiz geçebileceğini (Public uçlar: Fon listesi, canlı tahminler, Swagger).
 * 2. Kimlerin JWT pasaportu göstermek zorunda olduğunu (/api/v1/admin/** uçları).
 * 3. Şifrelerin hangi algoritmayla kontrol edileceğini (BCrypt).
 * belirler.
 *
 * 🏗️ MİMARİDEKİ ROLÜ:
 * - REST API olduğu için CSRF (Cross-Site Request Forgery) korumasını kapatır.
 * - SessionCreationPolicy.STATELESS ile sunucu belleğinde oturum tutulmasını engeller.
 * - İçinde barındırdığı 'JwtAuthenticationFilter' sayesinde gelen her HTTP isteğindeki
 *   'Authorization: Bearer <token>' başlığını inceler ve geçerliyse Spring Security Context'e
 *   kullanıcıyı onaylı olarak yerleştirir.
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
@RequiredArgsConstructor
@Slf4j
public class SecurityConfig {

    private final JwtService jwtService;

    /**
     * 1. GÖREV: Şifre Kriptolama Motoru (BCrypt Password Encoder).
     * Admin şifrelerini veritabanında asla açık tutmaz; 10 turluk güvenli tuzlama yapar.
     */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    /**
     * 2. GÖREV: Kimlik Doğrulama Yöneticisi (Authentication Manager).
     */
    @Bean
    public AuthenticationManager authenticationManager(AuthenticationConfiguration config) throws Exception {
        return config.getAuthenticationManager();
    }

    /**
     * 3. GÖREV: Güvenlik Filtre Zinciri (Security Filter Chain).
     * Hangi URL'in hangi yetkiyle açılacağını belirler.
     */
    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                // 1. REST API için CSRF gereksizdir, kapat
                .csrf(AbstractHttpConfigurer::disable)

                // 2. Frontend (Next.js) için CORS izni ver
                .cors(Customizer.withDefaults())

                // 3. Durumsuz (Stateless) oturum yönetimi — Cookie/Session kullanılmaz
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))

                // 4. Yetkilendirme Kuralları (Authorization Rules)
                .authorizeHttpRequests(auth -> auth
                        // A. Giriş (Login) uç noktası herkese açık
                        .requestMatchers("/api/v1/auth/**").permitAll()

                        // B. Müşteriye ve Dashboard'a açık GET uçları (Fonlar ve Tahminler)
                        .requestMatchers(HttpMethod.GET, "/api/v1/funds/**").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/v1/estimates/**").permitAll()

                        // C. Swagger OpenAPI Dokümantasyonu ve Actuator Sağlık Uçları
                        .requestMatchers("/swagger-ui/**", "/swagger-ui.html", "/v3/api-docs/**").permitAll()
                        .requestMatchers("/actuator/**").permitAll()

                        // D. Yönetim Uçları: YALNIZCA ADMIN YETKİSİ OLANLAR GİREBİLİR!
                        .requestMatchers("/api/v1/admin/**").hasRole("ADMIN")

                        // Geri kalan her istek kimlik doğrulaması gerektirir
                        .anyRequest().authenticated()
                )

                // 5. Bizim JWT Filtremizi Spring'in standart kullanıcı filtresinden ÖNCE çalıştır
                .addFilterBefore(new JwtAuthenticationFilter(jwtService), UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }

    // =========================================================================================
    // 🛡️ İÇ SINIF: JWT İSTEK FİLTRESİ (OncePerRequestFilter)
    // =========================================================================================

    /**
     * Her gelen HTTP isteğinde tam 1 kez çalışarak 'Authorization' başlığını denetler.
     */
    @Slf4j
    public static class JwtAuthenticationFilter extends OncePerRequestFilter {

        private final JwtService jwtService;

        public JwtAuthenticationFilter(JwtService jwtService) {
            this.jwtService = jwtService;
        }

        @Override
        protected void doFilterInternal(HttpServletRequest request,
                                        HttpServletResponse response,
                                        FilterChain filterChain) throws ServletException, IOException {

            String authHeader = request.getHeader("Authorization");

            // Eğer başlıkta "Bearer <token>" varsa token'ı ayıkla
            if (authHeader != null && authHeader.startsWith("Bearer ")) {
                String token = authHeader.substring(7).trim();

                // Token imzası doğru ve süresi geçerli mi?
                if (jwtService.validateToken(token)) {
                    String username = jwtService.extractUsername(token);
                    String role = jwtService.extractRole(token);

                    // Spring Security formatına çevir (Örn: "ROLE_ADMIN")
                    String authority = role.startsWith("ROLE_") ? role : "ROLE_" + role;
                    SimpleGrantedAuthority grantedAuthority = new SimpleGrantedAuthority(authority);

                    UsernamePasswordAuthenticationToken authToken = new UsernamePasswordAuthenticationToken(
                            username,
                            null,
                            Collections.singletonList(grantedAuthority)
                    );

                    authToken.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));

                    // Güvenlik bağlamına yerleştir (Artık Spring kullanıcının admin olduğunu bilir)
                    SecurityContextHolder.getContext().setAuthentication(authToken);
                    log.debug("[JwtAuthenticationFilter] İstek yetkilendirildi: Kullanıcı='{}', Rol='{}'", username, authority);
                }
            }

            // İsteği zincirdeki bir sonraki filtreye veya Controller'a aktar
            filterChain.doFilter(request, response);
        }
    }
}
