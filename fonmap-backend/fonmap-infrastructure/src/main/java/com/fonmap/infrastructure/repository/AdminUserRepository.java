package com.fonmap.infrastructure.repository;

import com.fonmap.domain.entity.AdminUser;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

/**
 * Admin kullanıcılarının veritabanı işlemlerini yönetir.
 * Spring Security'nin UserDetailsService katmanı tarafından çağrılır.
 */
@Repository
public interface AdminUserRepository extends JpaRepository<AdminUser, UUID> {

    /**
     * Kullanıcı adı ile admin arar.
     * Login (giriş) esnasında şifre ve rol kontrolü için kullanılır.
     */
    @Query("SELECT u FROM AdminUser u WHERE u.username = :username")
    Optional<AdminUser> findByUsername(@Param("username") String username);

    /**
     * Yeni bir admin eklerken aynı kullanıcı adının sistemde olup olmadığını kontrol eder.
     */
    @Query("SELECT CASE WHEN COUNT(u) > 0 THEN true ELSE false END FROM AdminUser u WHERE u.username = :username")
    boolean existsByUsername(@Param("username") String username);
}
