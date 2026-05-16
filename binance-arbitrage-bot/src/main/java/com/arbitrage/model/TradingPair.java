package com.arbitrage.model;

import lombok.Builder;
import lombok.Data;

/**
 * Representa un par de trading en Binance con sus precios actuales.
 * <p>
 * Cada par tiene un símbolo (ej: "ETHUSDT"), un asset base (ej: "ETH")
 * y un asset quote (ej: "USDT"). También almacena los precios bid/ask
 * obtenidos en tiempo real para usarlos en los cálculos de arbitraje.
 * </p>
 * <p>
 * Es una versión más completa que {@link Ticker}, agregando la información
 * de los assets base y quote del par.
 * </p>
 */
@Data
@Builder
public class TradingPair {
    /** Símbolo completo del par (ej: "ETHUSDT") */
    private String symbol;
    /** Moneda base del par (ej: "ETH" en ETHUSDT) */
    private String baseAsset;
    /** Moneda quote del par (ej: "USDT" en ETHUSDT) */
    private String quoteAsset;

    /** Mejor precio bid (precio de venta) */
    private double bidPrice;
    /** Mejor precio ask (precio de compra) */
    private double askPrice;
    /** Cantidad disponible al mejor bid */
    private double bidQty;
    /** Cantidad disponible al mejor ask */
    private double askQty;
}