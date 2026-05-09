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
    private int seqId;
    
    private static int idCounter = 0;
    private static synchronized int generateSeqId() {
        return ++idCounter;
    }
    
    @JsonIgnore
    private String seqIdString;  // Para compatibilidad con formato legacy (UUID)
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
        int seqId = generateSeqId();
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

    public void markCancelled() {
        this.estado = EstadoSecuencia.CANCELADA;
        this.timestampFin = System.currentTimeMillis();
    }
    
    public void setSeqIdString(String seqId) {
        this.seqIdString = seqId;
    }
    
    public String getSeqIdString() {
        return seqIdString;
    }
    
    @JsonIgnore
    public int getEffectiveSeqId() {
        return seqId > 0 ? seqId : (seqIdString != null ? seqIdString.hashCode() : 0);
    }
    
    @JsonIgnore
    public boolean isLegacyFormat() {
        return seqIdString != null && seqId == 0;
    }
    
    @JsonIgnore
    public int getNextPendingOpIndex() {
        if (op1 == null || !op1.isFilled()) return 1;
        if (op2 == null || !op2.isFilled()) return 2;
        if (op3 == null || !op3.isFilled()) return 3;
        return 4;  // Todas completadas
    }
    
    public boolean hasOp1BeenSent() {
        return op1 != null && op1.getBinanceOrderId() != null && !op1.getBinanceOrderId().isEmpty();
    }
}