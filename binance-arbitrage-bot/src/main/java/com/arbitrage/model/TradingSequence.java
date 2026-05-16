package com.arbitrage.model;

import com.arbitrage.util.MiniIdGenerator;
import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.util.ArrayList;
import java.util.List;

/**
 * Secuencia completa de trading para una operación de arbitraje triangular.
 * <p>
 * Una TradingSequence representa las 3 órdenes (op1, op2, op3) que forman
 * un ciclo de arbitraje. Almacena el estado global de la operación, los
 * profits esperados/realizados, y las marcas de tiempo de inicio y fin.
 * </p>
 * <p>
 * El flujo típico es: crear con {@link #create(String, String, double, String, double)},
 * asignar las órdenes con setOp1/setOp2/setOp3, y finalmente llamar a
 * {@link #close(double)}, {@link #markError()} o {@link #markCancelled()}.
 * </p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TradingSequence {
    /** Mini ID alfanumérico de 8 caracteres para archivos .seq y .events */
    private String miniId;

    /** Identificador numérico único de la secuencia (para display en consola) */
    private int seqId;

    /** Contador estático para generar seqId secuenciales */
    private static int idCounter = 0;

    /**
     * Genera un nuevo ID secuencial de forma sincronizada (thread-safe).
     * @return nuevo seqId
     */
    private static synchronized int generateSeqId() {
        return ++idCounter;
    }

    /**
     * Establece el contador de seqId para continuar la numeración después de una recuperación.
     * @param value Valor al que establecer el contador (las nuevas secuencias empezarán en value + 1)
     */
    public static synchronized void setCounter(int value) {
        idCounter = value;
    }

    /** UUID legacy para compatibilidad con formatos anteriores de persistencia */
    @JsonIgnore
    private String seqIdString;
    /** Identificador del triángulo al que pertenece esta secuencia */
    private String triangleId;
    /** Modo de operación (ej: "SIMULATION", "LIVE", "TEST") */
    private String modo;
    /** Estado actual de la secuencia (ABIERTA, CERRADA, CANCELADA, ERROR) */
    private EstadoSecuencia estado;
    /** Timestamp en ms de cuando se inició la secuencia */
    private long timestampInicio;
    /** Timestamp en ms de cuando finalizó la secuencia (0 si sigue abierta) */
    private long timestampFin;
    /** Profit esperado al momento de detectar la oportunidad */
    private double profitEsperado;
    /** Profit realmente obtenido al cerrar la secuencia */
    private double profitRealizado;
    /** Moneda base con la que se inició el ciclo (ej: "USDT") */
    private String monedaBase;
    /** Monto de moneda base invertido en la operación */
    private double montoBase;
    /** Primera orden del triángulo de arbitraje */
    private SequenceOrder op1;
    /** Segunda orden del triángulo de arbitraje */
    private SequenceOrder op2;
    /** Tercera orden del triángulo de arbitraje */
    private SequenceOrder op3;

    /**
     * Crea una nueva TradingSequence con valores iniciales por defecto.
     * <p>
     * El ID se genera automáticamente, el estado se inicializa como ABIERTA,
     * y el timestamp de inicio se establece al momento actual.
     * </p>
     *
     * @param triangleId     identificador del triángulo
     * @param modo           modo de operación
     * @param profitEsperado profit esperado de la operación
     * @param monedaBase     moneda base del ciclo
     * @param montoBase      monto invertido
     * @return nueva instancia de TradingSequence
     */
    public static TradingSequence create(String triangleId, String modo, double profitEsperado,
                                   String monedaBase, double montoBase) {
        String miniId = MiniIdGenerator.generate();
        int seqId = generateSeqId();
        long now = System.currentTimeMillis();

        return TradingSequence.builder()
                .miniId(miniId)
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

    /** Asigna la primera orden del triángulo */
    public void setOp1(SequenceOrder op) { this.op1 = op; }
    /** Asigna la segunda orden del triángulo */
    public void setOp2(SequenceOrder op) { this.op2 = op; }
    /** Asigna la tercera orden del triángulo */
    public void setOp3(SequenceOrder op) { this.op3 = op; }

    /**
     * Obtiene las órdenes no nulas como lista iterable.
     * @return lista con op1, op2, op3 (solo las que no sean null)
     */
    @JsonIgnore
    public List<SequenceOrder> getOrdenes() {
        List<SequenceOrder> ordenes = new ArrayList<>();
        if (op1 != null) ordenes.add(op1);
        if (op2 != null) ordenes.add(op2);
        if (op3 != null) ordenes.add(op3);
        return ordenes;
    }

    /**
     * Obtiene una orden por su índice (1-indexed).
     * @param indice número de orden (1, 2 o 3)
     * @return la orden correspondiente, o null si el índice es inválido
     */
    @JsonIgnore
    public SequenceOrder getOrden(int indice) {
        switch (indice) {
            case 1: return op1;
            case 2: return op2;
            case 3: return op3;
            default: return null;
        }
    }

    /**
     * Verifica si una orden específica fue completamente ejecutada (FILLED).
     * @param indice número de orden (1, 2 o 3)
     * @return true si la orden existe y su estado es FILLED
     */
    @JsonIgnore
    public boolean isOpFilled(int indice) {
        SequenceOrder orden = getOrden(indice);
        return orden != null && orden.isFilled();
    }

    /**
     * Verifica si las 3 órdenes del triángulo fueron ejecutadas completamente.
     * @return true si op1, op2 y op3 están todas en estado FILLED
     */
    @JsonIgnore
    public boolean isTodasFilled() {
        return isOpFilled(1) && isOpFilled(2) && isOpFilled(3);
    }

    /**
     * Cierra la secuencia como exitosa, registrando el profit realizado.
     * @param profitRealizado profit final obtenido en la operación
     */
    public void close(double profitRealizado) {
        this.estado = EstadoSecuencia.CERRADA;
        this.timestampFin = System.currentTimeMillis();
        this.profitRealizado = profitRealizado;
    }

    /** Marca la secuencia como fallida con error */
    public void markError() {
        this.estado = EstadoSecuencia.ERROR;
        this.timestampFin = System.currentTimeMillis();
    }

    /** Marca la secuencia como cancelada (ej: por stop-loss o fallo en op2/op3) */
    public void markCancelled() {
        this.estado = EstadoSecuencia.CANCELADA;
        this.timestampFin = System.currentTimeMillis();
    }

    /**
     * Retorna el Mini ID de la secuencia (NanoID de 8 caracteres).
     * Usado como clave principal en archivos .seq y .events para enlazar
     * las órdenes (Op1, Op2, Op3) con su secuencia padre.
     * @return miniId (NanoID)
     */
    @JsonIgnore
    public String getMiniId() {
        return miniId;
    }

    /** @deprecated usar seqId directamente */
    @Deprecated
    public void setSeqIdString(String seqId) {
        this.seqIdString = seqId;
    }

    /** @deprecated usar seqId directamente */
    @Deprecated
    public String getSeqIdString() {
        return seqIdString;
    }

    /**
     * Obtiene el ID efectivo de la secuencia, ya sea el numérico o el hash del UUID legacy.
     * @return seqId si es positivo, sino hashCode del seqIdString, o 0 si ambos están vacíos
     */
    @JsonIgnore
    public int getEffectiveSeqId() {
        return seqId > 0 ? seqId : (seqIdString != null ? seqIdString.hashCode() : 0);
    }

    /**
     * Indica si esta secuencia usa el formato legacy (UUID string en lugar de ID numérico).
     * @return true si solo tiene seqIdString y seqId es 0
     */
    @JsonIgnore
    public boolean isLegacyFormat() {
        return seqIdString != null && seqId == 0;
    }

    /**
     * Encuentra el índice de la siguiente orden pendiente por ejecutar.
     * <p>
     * Útil para reanudar una secuencia después de una interrupción:
     * si op1 está filled, devuelve 2; si op1 y op2 están filled, devuelve 3;
     * si todas están filled, devuelve 4.
     * </p>
     * @return índice (1-3) de la próxima orden, o 4 si todas están completadas
     */
    @JsonIgnore
    public int getNextPendingOpIndex() {
        if (op1 == null || !op1.isFilled()) return 1;
        if (op2 == null || !op2.isFilled()) return 2;
        if (op3 == null || !op3.isFilled()) return 3;
        return 4;
    }

    /**
     * Verifica si la primera orden ya fue enviada a Binance.
     * @return true si op1 existe y tiene un binanceOrderId asignado
     */
    public boolean hasOp1BeenSent() {
        return op1 != null && op1.getBinanceOrderId() != null && !op1.getBinanceOrderId().isEmpty();
    }
}