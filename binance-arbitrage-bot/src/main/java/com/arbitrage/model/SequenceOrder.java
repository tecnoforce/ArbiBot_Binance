package com.arbitrage.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SequenceOrder {
    private String seqId;
    private int opIndice;
    private String symbol;
    private String side;
    private String type;
    private double quantity;
    private double price;
    private String binanceOrderId;
    private String clientOrderId;
    private EstadoOrden orderStatus;
    private double cantidadEjecutada;
    private double precioEjecutado;
    private String comisionAsset;
    private double comisionMonto;
    private long timestampCreacion;
    private long timestampEjecucion;
    private long tiempoTranscurridoMs;

    public static SequenceOrder create(String seqId, int opIndice, String symbol, String side, 
                              String type, double quantity, double price) {
        return SequenceOrder.builder()
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

    public boolean isFilled() {
        return orderStatus == EstadoOrden.FILLED;
    }

    public boolean isFinal() {
        return orderStatus == EstadoOrden.FILLED || 
               orderStatus == EstadoOrden.CANCELED ||
               orderStatus == EstadoOrden.REJECTED ||
               orderStatus == EstadoOrden.EXPIRED ||
               orderStatus == EstadoOrden.ERROR;
    }
}