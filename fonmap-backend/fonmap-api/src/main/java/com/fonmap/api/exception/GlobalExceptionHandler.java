package com.fonmap.api.exception;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;

/**
 * =========================================================================================
 * 🛡️ MERKEZİ HATA YÖNETİMİ VE GÜVENLİK DUVARI (GlobalExceptionHandler)
 * =========================================================================================
 *
 * 📌 NE İŞE YARAR? (JUNIOR MÜHENDİS REHBERİ):
 * Bir REST API projesinde istemciye (Frontend veya mobil uygulama) asla çirkin
 * Java yığın izleri (Stack Trace) veya Spring'in varsayılan "Whitelabel Error Page"
 * HTML sayfaları dönülmemelidir!
 *
 * Bu sınıf; sistemdeki tüm Controller'larda meydana gelebilecek hataları havada yakalar,
 * kullanıcıya anlaşılır, standart bir JSON nesnesine (ApiErrorResponse) dönüştürür
 * ve doğru HTTP durum koduyla (400, 404, 500 vb.) geri yansıtır.
 *
 * 🏗️ MİMARİDEKİ ROLÜ (@RestControllerAdvice):
 * Spring AOP (Aspect-Oriented Programming) mantığıyla tüm @RestController sınıflarını
 * bir emniyet kemeri gibi çevreler. Controller içinde 'try-catch' kalabalığı yapmadan
 * temiz kod (Clean Code) yazmamızı sağlar.
 */
@RestControllerAdvice
@Slf4j
public class GlobalExceptionHandler {

    /**
     * 1. HATA YAKALAYICI: HTTP Durum İstisnaları (ResponseStatusException)
     * -------------------------------------------------------------------------------------
     * Controller'larda fırlatılan özel durum kodlarını (Örn: 404 NOT_FOUND) yakalar.
     */
    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<ApiErrorResponse> handleResponseStatusException(
            ResponseStatusException ex, HttpServletRequest request) {

        log.warn("[GlobalExceptionHandler] HTTP {} Hatası: URL='{}' -> {}",
                ex.getStatusCode(), request.getRequestURI(), ex.getReason());

        ApiErrorResponse error = new ApiErrorResponse(
                LocalDateTime.now(),
                ex.getStatusCode().value(),
                ex.getStatusCode().toString(),
                ex.getReason() != null ? ex.getReason() : ex.getMessage(),
                request.getRequestURI()
        );

        return ResponseEntity.status(ex.getStatusCode()).body(error);
    }

    /**
     * 2. HATA YAKALAYICI: Geçersiz Argüman İstisnaları (IllegalArgumentException)
     * -------------------------------------------------------------------------------------
     * Kullanıcı veya istemci geçersiz bir fon kodu veya parametre gönderdiğinde yakalar.
     * HTTP 400 Bad Request döner.
     */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiErrorResponse> handleIllegalArgumentException(
            IllegalArgumentException ex, HttpServletRequest request) {

        log.warn("[GlobalExceptionHandler] Geçersiz İstek (400): URL='{}' -> {}",
                request.getRequestURI(), ex.getMessage());

        ApiErrorResponse error = new ApiErrorResponse(
                LocalDateTime.now(),
                HttpStatus.BAD_REQUEST.value(),
                HttpStatus.BAD_REQUEST.getReasonPhrase(),
                ex.getMessage(),
                request.getRequestURI()
        );

        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(error);
    }

    /**
     * 3. HATA YAKALAYICI: İş Mantığı Durum Hatası (IllegalStateException)
     * -------------------------------------------------------------------------------------
     * İstenen fonun henüz bir snapshot'ı veya fiyatı bulunamadığında yakalanır.
     * HTTP 422 Unprocessable Entity (İşlenemeyen Varlık) döner.
     */
    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<ApiErrorResponse> handleIllegalStateException(
            IllegalStateException ex, HttpServletRequest request) {

        log.warn("[GlobalExceptionHandler] İş Mantığı Durum Hatası (422): URL='{}' -> {}",
                request.getRequestURI(), ex.getMessage());

        ApiErrorResponse error = new ApiErrorResponse(
                LocalDateTime.now(),
                HttpStatus.UNPROCESSABLE_ENTITY.value(),
                HttpStatus.UNPROCESSABLE_ENTITY.getReasonPhrase(),
                ex.getMessage(),
                request.getRequestURI()
        );

        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(error);
    }

    /**
     * 4. HATA YAKALAYICI: Parametre Tip Uyuşmazlığı (MethodArgumentTypeMismatchException)
     * -------------------------------------------------------------------------------------
     * Örneğin bir sayı veya UUID beklenen yere alakasız karakterler girildiğinde tetiklenir.
     */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiErrorResponse> handleTypeMismatch(
            MethodArgumentTypeMismatchException ex, HttpServletRequest request) {

        String message = String.format("Parametre '%s' için geçersiz değer: '%s'",
                ex.getName(), ex.getValue());

        log.warn("[GlobalExceptionHandler] Tip Uyuşmazlığı (400): {}", message);

        ApiErrorResponse error = new ApiErrorResponse(
                LocalDateTime.now(),
                HttpStatus.BAD_REQUEST.value(),
                HttpStatus.BAD_REQUEST.getReasonPhrase(),
                message,
                request.getRequestURI()
        );

        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(error);
    }

    /**
     * 5. SON KALE HATA YAKALAYICI: Beklenmeyen Genel Hatalar (Exception)
     * -------------------------------------------------------------------------------------
     * Öngörülemeyen tüm sunucu içi istisnaları yakalar. Güvenlik açığı yaratmamak için
     * veritabanı şifreleri veya sistem detayları dışarı sızdırılmaz; temiz bir
     * "Sunucu içi beklenmeyen bir hata oluştu" mesajı verilir. Loglara ise tam iz yazılır.
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiErrorResponse> handleGenericException(
            Exception ex, HttpServletRequest request) {

        log.error("[GlobalExceptionHandler] Kritik Beklenmeyen Hata (500): URL='{}'",
                request.getRequestURI(), ex);

        ApiErrorResponse error = new ApiErrorResponse(
                LocalDateTime.now(),
                HttpStatus.INTERNAL_SERVER_ERROR.value(),
                HttpStatus.INTERNAL_SERVER_ERROR.getReasonPhrase(),
                "Sunucu içi beklenmeyen bir hata meydana geldi. Lütfen sistem yöneticisiyle iletişime geçiniz.",
                request.getRequestURI()
        );

        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(error);
    }

    // =========================================================================================
    // 📦 STANDART HATA YANIT DTO'SU (RECORD)
    // =========================================================================================

    /**
     * Frontend'e dönülen standart hata JSON gövdesi.
     */
    @Schema(description = "Standart API Hata Yanıt Modeli")
    public record ApiErrorResponse(
            @Schema(description = "Hatanın gerçekleştiği zaman damgası")
            LocalDateTime timestamp,

            @Schema(description = "HTTP durum kodu (Örn: 400, 404, 500)")
            int status,

            @Schema(description = "HTTP durum başlığı")
            String error,

            @Schema(description = "Kullanıcıya yönelik açıklayıcı hata mesajı")
            String message,

            @Schema(description = "Hatanın oluştuğu REST API endpoint yolu")
            String path
    ) {}
}
