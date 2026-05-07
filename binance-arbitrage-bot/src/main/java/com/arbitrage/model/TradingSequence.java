package com.arbitrage.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.util.ArrayList;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TradingSequence {
    private String seqId;
    private String triangleId;
    private String modo;
    private EstadoSecuencia estado;
    private long timestampInicio;
    private long timestampFin;
    private double profitEsperado;
    private double profitRealizado;
    private String monedaBase;
    private double montoBase;
    private SequenceOrder op1;
    private SequenceOrder op2;
    private SequenceOrder op3;

    public static TradingSequence create(String triangleId, String modo, double profitEsperado, 
                                   String monedaBase, double montoBase) {
        String seqId = java.util.UUID.randomUUID().toString();
        long now = System.currentTimeMillis();
        
        return TradingSequence.builder()
                .seqId(seqId)
                .triangleId(triangleId)
                .modo(modo)
                .estado(EstadoSecuencia.ABIERTA)
                .timestampInicio(now)
                .timestampFin(0)
                .profitEsperado(profitEsperado)
                .profitRealizado(0.0)
                .monedaBase(monedaBase)
                .montoBase(montoBase)
                .build();
    }

    public void setOp1(SequenceOrder op) { this.op1 = op; }
    public void setOp2(SequenceOrder op) { this.op2 = op; }
    public void setOp3(SequenceOrder op) { this.op3 = op; }

    @JsonIgnore
    public List<SequenceOrder> getOrdenes() {
        List<SequenceOrder> ordenes = new ArrayList<>();
        if (op1 != null) ordenes.add(op1);
        if (op2 != null) ordenes.add(op2);
        if (op3 != null) ordenes.add(op3);
        return ordenes;
    }

    @JsonIgnore
    public SequenceOrder getOrden(int indice) {
        switch (indice) {
            case 1: return op1;
            case 2: return op2;
            case 3: return op3;
            default: return null;
        }
    }

    @JsonIgnore
    public boolean isOpFilled(int indice) {
        SequenceOrder orden = getOrden(indice);
        return orden != null && orden.isFilled();
    }

    @JsonIgnore
    public boolean isTodasFilled() {
        return isOpFilled(1) && isOpFilled(2) && isOpFilled(3);
    }

    public void close(double profitRealizado) {
        this.estado = EstadoSecuencia.CERRADA;
        this.timestampFin = System.currentTimeMillis();
        this.profitRealizado = profitRealizado;
    }

    public void markError() {
        this.estado = EstadoSecuencia.ERROR;
        this.timestampFin = System.currentTimeMillis();
    }
}