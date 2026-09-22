package com.fonmap.infrastructure.cache;

import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.jsontype.BasicPolymorphicTypeValidator;
import com.fasterxml.jackson.databind.jsontype.PolymorphicTypeValidator;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.fonmap.infrastructure.client.dto.MarketPriceDto;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.Jackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.StringRedisSerializer;

/**
 * ============================================================================
 * FONMAP — Redis Önbellek Yapılandırması (Redis Configuration)
 * ============================================================================
 * 
 * Bu sınıf, Spring Data Redis altyapısını Fonmap finansal mimarisine göre özelleştirir.
 * 
 * NEDEN ÖZEL BİR REDISCONFIG YAZIYORUZ?
 * ----------------------------------------------------------------------------
 * 1. Varsayılan Java Serileştirici Sorunu:
 *    Spring Boot'un varsayılan ayarları kullanılırsa, Java nesneleri Redis'e
 *    okunamaz ikili bayt dizileri ("\xac\xed\x00\x05sr...") olarak yazılır.
 *    Bu durum 'redis-cli' veya Redis Insight üzerinden veriyi incelemeyi imkansız kılar.
 * 
 * 2. İnsan Tarafından Okunabilir JSON Depolama:
 *    Değerleri (Values) Jackson JSON formatına dönüştürüyoruz.
 *    Böylece Redis'e baktığımızda temiz ve anlaşılır bir JSON görürüz:
 *    {"symbol":"THYAO","currentPrice":293.5,"previousClose":285.5,...}
 * 
 * 3. Java 8 Tarih Desteği (LocalDateTime):
 *    MarketPriceDto içerisindeki 'quoteTime' (LocalDateTime) alanının
 *    ISO-8601 formatında hatasız serileştirilip geri okunabilmesi için
 *    Jackson'a 'JavaTimeModule' ekliyoruz.
 * 
 * 4. Tip Güvenli Template:
 *    MarketPriceDto nesnelerine doğrudan erişebilen 'priceRedisTemplate' bean'i tanımlıyoruz.
 */
@Configuration
public class RedisConfig {

    /**
     * Fiyat verileri (MarketPriceDto) için özelleştirilmiş, tip güvenli RedisTemplate.
     * 
     * Anahtar (Key): String -> "fonmap:price:THYAO", "fonmap:price:USD"
     * Değer (Value): MarketPriceDto nesnesinin JSON çıktısı
     * 
     * @param connectionFactory Spring Boot tarafından otomatik sağlanan Lettuce bağlantı havuzu
     * @return Yapılandırılmış ve kullanıma hazır RedisTemplate
     */
    @Bean
    public RedisTemplate<String, MarketPriceDto> priceRedisTemplate(RedisConnectionFactory connectionFactory) {
        RedisTemplate<String, MarketPriceDto> template = new RedisTemplate<>();
        template.setConnectionFactory(connectionFactory);

        // 1. Anahtarlar (Keys) daima düz metin (String) olarak serileştirilir.
        StringRedisSerializer stringSerializer = new StringRedisSerializer();
        template.setKeySerializer(stringSerializer);
        template.setHashKeySerializer(stringSerializer);

        // 2. Değerler (Values) için MarketPriceDto'ya özel Jackson Serializer oluşturulur.
        ObjectMapper objectMapper = createRedisObjectMapper();
        Jackson2JsonRedisSerializer<MarketPriceDto> priceSerializer = 
                new Jackson2JsonRedisSerializer<>(objectMapper, MarketPriceDto.class);

        template.setValueSerializer(priceSerializer);
        template.setHashValueSerializer(priceSerializer);

        // 3. Yapılandırmayı tamamla ve template'i hazırla
        template.afterPropertiesSet();
        return template;
    }

    /**
     * Genel amaçlı (örneğin fon getiri özetleri veya metinler için) String-Object RedisTemplate.
     * 
     * @param connectionFactory Lettuce bağlantı havuzu
     * @return Genel amaçlı JSON serileştiricili RedisTemplate
     */
    @Bean
    public RedisTemplate<String, Object> genericRedisTemplate(RedisConnectionFactory connectionFactory) {
        RedisTemplate<String, Object> template = new RedisTemplate<>();
        template.setConnectionFactory(connectionFactory);

        StringRedisSerializer stringSerializer = new StringRedisSerializer();
        template.setKeySerializer(stringSerializer);
        template.setHashKeySerializer(stringSerializer);

        // Polimorfik JSON serileştirici (nesne tip bilgisini @class olarak JSON içine gömer)
        ObjectMapper objectMapper = createRedisObjectMapper();
        PolymorphicTypeValidator ptv = BasicPolymorphicTypeValidator.builder()
                .allowIfBaseType(Object.class)
                .build();
        objectMapper.activateDefaultTyping(ptv, ObjectMapper.DefaultTyping.NON_FINAL, JsonTypeInfo.As.PROPERTY);

        GenericJackson2JsonRedisSerializer genericSerializer = new GenericJackson2JsonRedisSerializer(objectMapper);
        template.setValueSerializer(genericSerializer);
        template.setHashValueSerializer(genericSerializer);

        template.afterPropertiesSet();
        return template;
    }

    /**
     * Redis için LocalDateTime ve BigDecimal alanlarını en doğru biçimde işleyen ObjectMapper üretir.
     */
    private ObjectMapper createRedisObjectMapper() {
        ObjectMapper mapper = new ObjectMapper();
        // Java 8+ Tarih ve Saat (LocalDate, LocalDateTime) desteği
        mapper.registerModule(new JavaTimeModule());
        // Tarihleri timestamp (sayı) yerine ISO-8601 String ("2026-09-22T01:30:00") olarak yazar
        mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        return mapper;
    }
}
