package com.fonmap.api.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import static org.junit.jupiter.api.Assertions.*;

class JwtServiceTest {

    @Test
    @DisplayName("Dev profilinde secret verilmezse rastgele geçici secret üretmeli ve token geçerli olmalı")
    void testDevSecretGenerationAndTokenLifecycle() {
        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles("dev");

        JwtService jwtService = new JwtService(env);
        jwtService.ensureSecret();

        String token = jwtService.generateToken("adminUser", "ROLE_ADMIN");
        assertNotNull(token);
        assertTrue(jwtService.validateToken(token));
        assertEquals("adminUser", jwtService.extractUsername(token));
        assertEquals("ROLE_ADMIN", jwtService.extractRole(token));
    }

    @Test
    @DisplayName("Prod profilinde secret boş ise IllegalStateException fırlatmalı")
    void testProdSecretMissing_ThrowsException() {
        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles("prod");

        JwtService jwtService = new JwtService(env);
        assertThrows(IllegalStateException.class, jwtService::ensureSecret);
    }
}
