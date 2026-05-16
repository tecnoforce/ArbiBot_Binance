package com.arbitrage.model;

import lombok.Builder;
import lombok.Data;

/**
 * Resultado de una orden ejecutada devuelto por la API REST de Binance.
 * <p>
 * Contiene toda la información devuelta por Binance tras crear o consultar
 * una orden: símbolo, lado, IDs, cantidades, estado, comisiones y tiempos.
 * Se usa como DTO (Data Transfer Object) entre {@code BinanceApiClient}
 * y el motor de arbitraje.
 * </p>
 */
@Data
@Builder
public class OrderResult {
    /** Símbolo del par (ej: "BTCUSDT") */
    private String symbol;

    /** Lado de la orden: "BUY" o "SELL" */
    private String side;

    /** ID de orden asignado por Binance */
    private String orderId;

    /** Precio de la orden (0 para MARKET) */
    private double price;

    /** Cantidad solicitada en la orden */
    private double quantity;

    /** Cantidad realmente ejecutada (puede ser parcial) */
    private double executedQty;

    /** Estado devuelto por Binance: "NEW", "PARTIALLY_FILLED", "FILLED", "CANCELED", etc. */
    private String status;

    /** Tiempo que tomó ejecutar la orden en milisegundos */
    private long elapsedTime;

    /** Tipo de orden: "MARKET", "LIMIT", "STOP_LOSS", etc. */
    private String orderType;

    /** Indica si la orden se ejecutó sin errores */
    private boolean success;

    /** Mensaje de error si la orden falló (campo human-readable) */
    private String errorMessage;

    /** Timestamp de creación de la orden devuelto por Binance (transactTime) */
    private long transactTime;

    /** Timestamp de la última actualización de la orden (updateTime) */
    private long updateTime;

    /** Asset usado para pagar la comisión (ej: "BNB", "USDT", "BTC") */
    private String commissionAsset;

    /** Monto de la comisión cobrada */
    private double commissionAmount;
}