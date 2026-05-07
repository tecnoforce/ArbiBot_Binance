package com.arbitrage.model;

import lombok.Builder;
import lombok.Data;

/**
 * Representa un par de trading en Binance.
 * Ejemplo: BTCUSDT (BTC es base, USDT es quote)
 */
@Data
@Builder
public class TradingPair {
    private String symbol;       // Simbolo completo (ej: "ETHUSDT")
    private String baseAsset;    // Moneda base (ej: "ETH")
    private String quoteAsset;  // Moneda quote (ej: "USDT")
    
    // Precios actuales (bid = venta, ask = compra)
    private double bidPrice;
    private double askPrice;
    private double bidQty;
    private double askQty;
}