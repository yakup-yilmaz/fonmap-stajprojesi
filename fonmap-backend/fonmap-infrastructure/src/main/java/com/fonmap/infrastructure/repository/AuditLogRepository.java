package com.fonmap.infrastructure.repository;

import com.fonmap.domain.entity.AuditLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

/**
 * Sistemde yapılan kritik değişikliklerin (fiyat güncelleme, fon kapatma, yetki değişimi vb.)
 * güvenlik loglarını sorgulamak için kullanılır.
 */
@Repository
public interface AuditLogRepository extends JpaRepository<AuditLog, Long> {

    /**
     * Belirli bir nesneye ait geçmiş hareketleri kronolojik tersten listeler.
     * Örnek: "Fund" tablosundaki THF fonunda son 1 ayda kim ne değiştirdi?
     */
    @Query("SELECT a FROM AuditLog a WHERE a.entityName = :entityName AND a.entityId = :entityId ORDER BY a.createdAt DESC")
    List<AuditLog> findByEntityNameAndEntityIdOrderByCreatedAtDesc(
            @Param("entityName") String entityName,
            @Param("entityId") String entityId
    );

    /**
     * Belirli bir admin kullanıcısının sistemde gerçekleştirdiği tüm işlemleri listeler.
     */
    @Query("SELECT a FROM AuditLog a WHERE a.userId = :userId ORDER BY a.createdAt DESC")
    List<AuditLog> findByUserIdOrderByCreatedAtDesc(@Param("userId") UUID userId);
}
