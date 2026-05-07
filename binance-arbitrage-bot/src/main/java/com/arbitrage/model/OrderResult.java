package com.arbitrage.model;

import lombok.Builder;
import lombok.Data;

/**
 * Resultado de una orden ejecutada.
 * Contiene toda la informacion de una orden.
 */
@Data
@Builder
public class OrderResult {
    // Simbolo (ej: "BTCUSDT")
    private String symbol;
    
    // Lado (BUY o SELL)
    private String side;
    
    // ID de orden en Binance
    private String orderId;
    
    // Precio de la orden
    private double price;
    
    // Cantidad ordenada
    private double quantity;
    
    // Cantidad ejecutada
    private double executedQty;
    
    // Estado (NEW, PARTIALLY_FILLED, FILLED, CANCELED, etc.)
    private String status;
    
    // Tiempo de ejecucion en ms
    private long elapsedTime;
    
    // Tipo de orden (MARKET, LIMIT, STOP_LOSS)
    private String orderType;
    
    // Si fue exitosa
    private boolean success;
    
    // Mensaje de error si fallo
    private String errorMessage;
}