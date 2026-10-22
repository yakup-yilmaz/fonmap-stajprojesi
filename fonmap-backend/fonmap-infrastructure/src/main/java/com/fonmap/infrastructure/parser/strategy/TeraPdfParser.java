package com.fonmap.infrastructure.parser.strategy;

/**
 * =========================================================================================
 * ⚠️ TERA PDF AYRIŞTIRICI GERİYE DÖNÜK UYUMLULUK KÖPRÜSÜ (DEPRECATED BRIDGE)
 * =========================================================================================
 * 
 * Bu sınıf geriye dönük test ve miras uyumluluğu için tutulmaktadır.
 * Tüm SPK yatay tablo ayrıştırma mantığı artık {@link SpkLandscapePdfParser} sınıfındadır.
 * 
 * @deprecated Yeni geliştirmelerde doğrudan {@link SpkLandscapePdfParser} kullanınız.
 */
@Deprecated
public class TeraPdfParser extends SpkLandscapePdfParser {

    public static final String STRATEGY_NAME = "TERA_LANDSCAPE_STRATEGY";

    @Override
    public String getStrategyName() {
        return STRATEGY_NAME;
    }
}
