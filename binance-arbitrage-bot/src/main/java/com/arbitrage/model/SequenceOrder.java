package com.arbitrage.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Representa una orden individual dentro de una secuencia de arbitraje triangular.
 * <p>
 * Cada SequenceOrder corresponde a una de las 3 operaciones del triángulo
 * (compra/venta en un par específico). Almacena toda la información de la
 * orden incluyendo símbolo, lado, cantidad, precio, IDs de Binance, estado
 * de ejecución, comisiones y marcas de tiempo.
 * </p>
 * <p>
 * El flujo típico es: crear con {@link #create(int, int, String, String, String, double, double)},
 * enviar a Binance, y actualizar con la respuesta de la API (binanceOrderId,
 * orderStatus, cantidadEjecutada, etc.).
 * </p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SequenceOrder {
    /** Mini ID de la secuencia padre (para archivos .seq y .events) */
    private String miniId;

    /** ID de la secuencia para display en consola */
    private int seqId;
    /** Índice de la orden dentro del triángulo (1, 2 o 3) */
    private int opIndice;
    /** Símbolo del par en Binance (ej: "BTCUSDT") */
    private String symbol;
    /** Lado de la orden: "BUY" o "SELL" */
    private String side;
    /** Tipo de orden: "MARKET", "LIMIT", etc. */
    private String type;
    /** Cantidad solicitada en la orden */
    private double quantity;
    /** Precio límite de la orden (0 para MARKET) */
    private double price;
    /** ID de la orden asignado por Binance */
    private String binanceOrderId;
    /** ID de cliente asignado localmente (clientOrderId) */
    private String clientOrderId;
    /** Estado actual de la orden según Binance */
    private EstadoOrden orderStatus;
    /** Cantidad realmente ejecutada (puede ser parcial) */
    private double cantidadEjecutada;
    /** Precio promedio al que se ejecutó */
    private double precioEjecutado;
    /** Asset usado para pagar la comisión (ej: "BNB", "USDT") */
    private String comisionAsset;
    /** Monto de la comisión cobrada */
    private double comisionMonto;
    /** Timestamp en ms de creación de la orden */
    private long timestampCreacion;
    /** Timestamp en ms de ejecución completa */
    private long timestampEjecucion;
    /** Tiempo total transcurrido desde creación hasta ejecución en ms */
    private long tiempoTranscurridoMs;

    /**
     * Crea una nueva SequenceOrder con estado inicial PENDING.
     * <p>
     * El timestamp de creación se asigna automáticamente al momento actual.
     * </p>
     *
     * @param miniId   Mini ID de la secuencia padre (para archivos)
     * @param seqId    ID de la secuencia para display
     * @param opIndice índice de la orden (1, 2 o 3)
     * @param symbol   símbolo del par
     * @param side     "BUY" o "SELL"
     * @param type     tipo de orden ("MARKET", "LIMIT", etc.)
     * @param quantity cantidad a operar
     * @param price    precio (0 para MARKET)
     * @return nueva instancia de SequenceOrder
     */
    public static SequenceOrder create(String miniId, int seqId, int opIndice, String symbol, String side,
                              String type, double quantity, double price) {
        return SequenceOrder.builder()
                .miniId(miniId)
                .seqId(seqId)
                .opIndice(opIndice)
                .symbol(symbol)
                .side(side)
                .type(type)
                .quantity(quantity)
                .price(price)
                .orderStatus(EstadoOrden.PENDING)
                .timestampCreacion(System.currentTimeMillis())
                .build();
    }

    /**
     * Verifica si la orden fue completamente ejecutada (FILLED).
     * @return true si orderStatus es FILLED
     */
    public boolean isFilled() {
        return orderStatus == EstadoOrden.FILLED;
    }

    /**
     * Verifica si la orden está en un estado terminal (ya no se puede modificar).
     * <p>
     * Los estados terminales son: FILLED, CANCELED, REJECTED, EXPIRED y ERROR.
     * </p>
     * @return true si la orden ya no puede cambiar de estado
     */
    public boolean isFinal() {
        return orderStatus == EstadoOrden.FILLED ||
               orderStatus == EstadoOrden.CANCELED ||
               orderStatus == EstadoOrden.REJECTED ||
               orderStatus == EstadoOrden.EXPIRED ||
               orderStatus == EstadoOrden.ERROR;
    }

    /**
     * Retorna el Mini ID de la secuencia padre (NanoID de 8 caracteres).
     * Usado para enlazar esta orden con su secuencia (Op1, Op2, Op3).
     * @return miniId (NanoID)
     */
    public String getMiniId() {
        return miniId;
    }
}