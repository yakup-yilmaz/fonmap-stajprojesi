package com.fonmap.infrastructure.client.exception;

/**
 * Fiyat sağlayıcıları (Yahoo Finance, Bigpara, TCMB) veri çekerken
 * bir ağ veya API hatası aldığında fırlatılan özel çalışma zamanı istisnası.
 * 
 * Bu istisna fırlatıldığında Resilience4j devreye girer (Retry yapar veya Circuit Breaker'ı tetikler).
 */
public class PriceProviderException extends RuntimeException {

    private final String providerName;
    private final String symbol;

    public PriceProviderException(String providerName, String symbol, String message) {
        super(String.format("[%s] '%s' için fiyat çekilemedi: %s", providerName, symbol, message));
        this.providerName = providerName;
        this.symbol = symbol;
    }

    public PriceProviderException(String providerName, String symbol, String message, Throwable cause) {
        super(String.format("[%s] '%s' için fiyat çekilemedi: %s", providerName, symbol, message), cause);
        this.providerName = providerName;
        this.symbol = symbol;
    }

    public String getProviderName() {
        return providerName;
    }

    public String getSymbol() {
        return symbol;
    }
}
