package com.fonmap.infrastructure.client;

import com.fonmap.infrastructure.client.dto.MarketPriceDto;

import java.util.List;

/**
 * Fiyat Sağlayıcı Ortak Arayüzü (Provider Pattern).
 * 
 * Dış dünyadaki tüm piyasa veri kaynakları (Yahoo Finance, Bigpara, TCMB)
 * bu sözleşmeyi uygular.
 * 
 * Bu sayede hesaplama motoru (Calculation Engine), verinin Yahoo'dan mı
 * yoksa yedek sağlayıcı Bigpara'dan mı geldiğini bilmeden standart şekilde çalışır.
 */
public interface PriceProvider {

    /**
     * Tek bir varlığın anlık piyasa fiyatını ve dünkü kapanışını çeker.
     * 
     * @param symbol Varlık sembolü (Örn: "THYAO", "USDTRY", "XU100")
     * @return Standart piyasa fiyat DTO'su
     */
    MarketPriceDto getPrice(String symbol);

    /**
     * Birden fazla varlığın piyasa fiyatlarını toplu olarak çeker.
     * 
     * @param symbols Varlık sembolleri listesi (Örn: ["THYAO", "ASELS", "BIMAS"])
     * @return Her varlığın fiyat DTO'sunu içeren liste
     */
    List<MarketPriceDto> getPrices(List<String> symbols);

    /**
     * Sağlayıcının adını döner.
     * Loglama, denetim ve hangi kaynağın devrede olduğunu bilmek için kullanılır.
     * 
     * @return Örn: "YAHOO_FINANCE", "BIGPARA", "TCMB_XML"
     */
    String getProviderName();
}
