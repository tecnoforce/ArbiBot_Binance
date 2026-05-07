package com.arbitrage.model;

import lombok.Builder;
import lombok.Data;

/**
 * Representa los precios de un simbolo.
 * Contiene los mejores precios de compra y venta:
 *   - Bid: mejor precio de venta (vendes a este precio)
 *   - Ask: mejor precio de compra (compras a este precio)
 */
@Data
@Builder
public class Ticker {
    // Simbolo (ej: "BTCUSDT")
    private String symbol;
    
    // Mejor precio de venta (vender)
    private double bidPrice;
    
    // Mejor precio de compra (comprar)
    private double askPrice;
    
    // Cantidad disponible en bid
    private double bidQty;
    
    // Cantidad disponible en ask
    private double askQty;
}