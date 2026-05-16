package com.arbitrage.module;

import com.arbitrage.model.*;
import com.arbitrage.persistence.SequenceFileManager;
import com.arbitrage.util.Log;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

/**
 * FillTracker - Seguimiento de ejecucion de ordenes y cola de resultados.
 *
 * Responsabilidades:
 * - Monitorear estado de secuencias activas (3 ordenes por secuencia)
 * - Tracking individual de cada orden: envio, filled, error
 * - Calcular latencia de fills (tiempo entre envio y ejecucion)
 * - Mantener cola de resultados (FillRecord) para consumo externo
 * - Persistir secuencias completadas y eventos via SequenceFileManager
 * - Proveer estadisticas: fills totales, errores, tasa de exito, latencia promedio
 *
 * Estados de una secuencia: IN_PROGRESS -> COMPLETED | FAILED | CANCELLED
 * Cada orden pasa por: SENT -> FILLED | ERROR
 *
 * Integracion: Usado por ExecutionEngine para trackear cada paso de la secuencia.
 *              FillTracker.trackOrderSent() antes de enviar, trackOrderFilled() al recibir confirmacion.
 *
 * Backward compatible: puede coexistir con OrderExecutor
 */
public class FillTracker {
    /** Tag para logging */
    private static final String TAG = "FillTracker";

    /** Manejador de persistencia JSON (opcional) */
    private final SequenceFileManager fileManager;
    /** Cola thread-safe de registros de fills completados */
    private final ConcurrentLinkedQueue<FillRecord> fillQueue;
    /** Mapa de secuencias actualmente activas (seqId → estado) */
    private final ConcurrentHashMap<Integer, SequenceState> activeSequences;
    /** Contador total de fills exitosos */
    private final AtomicInteger totalFills;
    /** Contador total de errores en órdenes */
    private final AtomicInteger totalErrors;
    /** Acumulador de latencia total de fills (ms), para calcular promedio */
    private final AtomicLong totalFillTimeMs;

    /**
     * Constructor con manejador de persistencia.
     *
     * @param fileManager Manejador de persistencia JSON (puede ser null)
     */
    public FillTracker(SequenceFileManager fileManager) {
        this.fileManager = fileManager;
        this.fillQueue = new ConcurrentLinkedQueue<>();
        this.activeSequences = new ConcurrentHashMap<>();
        this.totalFills = new AtomicInteger(0);
        this.totalErrors = new AtomicInteger(0);
        this.totalFillTimeMs = new AtomicLong(0);
        Log.info(TAG, "FillTracker initialized");
    }

    /**
     * Constructor sin persistencia (solo tracking en memoria).
     */
    public FillTracker() {
        this(null);
    }

    // =====================================================================
    // TRACKING DE SECUENCIAS ACTIVAS
    // =====================================================================

    /**
     * Comienza a trackear una nueva secuencia.
     * Crea un SequenceState con estado IN_PROGRESS y lo registra en el mapa activo.
     *
     * @param seqId          ID de la secuencia
     * @param triangle       Triángulo asociado
     * @param expectedProfit Profit esperado (%)
     */
    public void trackSequenceStart(int seqId, Triangle triangle, double expectedProfit) {
        SequenceState state = SequenceState.builder()
            .seqId(seqId)
            .triangleId(triangle.getId())
            .expectedProfit(expectedProfit)
            .startTime(System.currentTimeMillis())
            .status(SequenceStatus.IN_PROGRESS)
            .build();
        activeSequences.put(seqId, state);
        Log.debug(TAG, "Tracking sequence #" + seqId);
    }

    /**
     * Registra el envío de una orden individual.
     * Crea un OrderState con timestamp y lo asigna a la operación correspondiente.
     *
     * @param seqId   ID de la secuencia
     * @param opIndex Índice de la operación (1, 2 o 3)
     * @param symbol  Símbolo de la orden
     * @param side    "BUY" o "SELL"
     * @param qty     Cantidad enviada
     */
    public void trackOrderSent(int seqId, int opIndex, String symbol, String side, double qty) {
        SequenceState state = activeSequences.get(seqId);
        if (state == null) return;

        OrderState order = new OrderState();
        order.opIndex = opIndex;
        order.symbol = symbol;
        order.side = side;
        order.sentQty = qty;
        order.sentTime = System.currentTimeMillis();
        order.status = "SENT";

        // Asignar a la operación correspondiente (OP1, OP2, OP3)
        switch (opIndex) {
            case 1: state.op1 = order; break;
            case 2: state.op2 = order; break;
            case 3: state.op3 = order; break;
        }

        Log.debug(TAG, "Seq #" + seqId + " Op" + opIndex + " SENT: " + symbol + " " + side + " qty=" + qty);
    }

    /**
     * Registra que una orden fue filled exitosamente.
     * Actualiza el OrderState con datos reales de ejecución, calcula latencia,
     * y si es la tercera operación, marca la secuencia como COMPLETED.
     *
     * @param seqId   ID de la secuencia
     * @param opIndex Índice de la operación (1, 2 o 3)
     * @param result  Resultado de la orden desde la API de Binance
     */
    public void trackOrderFilled(int seqId, int opIndex, OrderResult result) {
        SequenceState state = activeSequences.get(seqId);
        if (state == null) return;

        // Obtener el OrderState correspondiente
        OrderState order;
        switch (opIndex) {
            case 1: order = state.op1; break;
            case 2: order = state.op2; break;
            case 3: order = state.op3; break;
            default: return;
        }

        if (order == null) return;

        // Actualizar con datos reales del fill
        order.filledTime = System.currentTimeMillis();
        order.filledQty = result.getExecutedQty();
        order.filledPrice = result.getPrice();
        order.status = "FILLED";
        order.orderId = result.getOrderId();
        order.fillLatencyMs = order.filledTime - order.sentTime;

        // Actualizar estadísticas globales
        totalFills.incrementAndGet();
        totalFillTimeMs.addAndGet(order.fillLatencyMs);

        // Si se completaron las 3 operaciones, la secuencia terminó
        state.completedOps++;
        if (state.completedOps == 3) {
            state.endTime = System.currentTimeMillis();
            state.status = SequenceStatus.COMPLETED;
            state.realizedProfit = state.expectedProfit;
        }

        Log.debug(TAG, "Seq #" + seqId + " Op" + opIndex + " FILLED: " + order.fillLatencyMs + "ms");
    }

    /**
     * Registra un error en una orden específica.
     * Marca la orden como ERROR y toda la secuencia como FAILED.
     *
     * @param seqId   ID de la secuencia
     * @param opIndex Índice de la operación (1, 2 o 3)
     * @param reason  Descripción del error
     */
    public void trackOrderError(int seqId, int opIndex, String reason) {
        SequenceState state = activeSequences.get(seqId);
        if (state == null) return;

        // Obtener el OrderState correspondiente
        OrderState order;
        switch (opIndex) {
            case 1: order = state.op1; break;
            case 2: order = state.op2; break;
            case 3: order = state.op3; break;
            default: return;
        }

        if (order == null) return;

        // Marcar orden como error y secuencia como fallida
        order.filledTime = System.currentTimeMillis();
        order.status = "ERROR";
        order.errorReason = reason;

        state.endTime = System.currentTimeMillis();
        state.status = SequenceStatus.FAILED;
        state.realizedProfit = 0;

        totalErrors.incrementAndGet();

        Log.warn(TAG, "Seq #" + seqId + " Op" + opIndex + " ERROR: " + reason);
    }

    /**
     * Finaliza el tracking de una secuencia (exitosa o fallida).
     * Actualiza el estado y encola un FillRecord para consumo externo.
     *
     * @param seqId   ID de la secuencia
     * @param success true si la secuencia completó exitosamente
     * @param profit  Profit realizado (puede ser 0 si falló)
     */
    public void trackSequenceEnd(int seqId, boolean success, double profit) {
        SequenceState state = activeSequences.get(seqId);
        if (state != null) {
            state.endTime = System.currentTimeMillis();
            state.status = success ? SequenceStatus.COMPLETED : SequenceStatus.FAILED;
            state.realizedProfit = profit;
        }

        // Encolar resultado para que otros módulos puedan consumirlo
        enqueueFill(seqId, profit, success);
    }

    /**
     * Remueve una secuencia del tracking activo.
     *
     * @param seqId ID de la secuencia a remover
     */
    public void removeSequence(int seqId) {
        activeSequences.remove(seqId);
    }

    // =====================================================================
    // FILL QUEUE
    // =====================================================================

    /**
     * Encola un registro de fill completado para consumo externo.
     * Útil para que otros módulos (Display, Analytics) procesen resultados.
     *
     * @param seqId   ID de la secuencia
     * @param profit  Profit realizado
     * @param success Si la operación fue exitosa
     */
    public void enqueueFill(int seqId, double profit, boolean success) {
        FillRecord record = FillRecord.builder()
            .seqId(seqId)
            .profit(profit)
            .success(success)
            .timestamp(System.currentTimeMillis())
            .build();
        fillQueue.offer(record);
        Log.debug(TAG, "Enqueued fill: #" + seqId + " profit=" + profit);
    }

    /**
     * Desencola el siguiente fill de la cola (FIFO).
     *
     * @return FillRecord o null si la cola está vacía
     */
    public FillRecord dequeueFill() {
        return fillQueue.poll();
    }

    /**
     * Obtiene los N fills más recientes sin desencolarlos.
     *
     * @param limit Número máximo de registros
     * @return Lista de FillRecord
     */
    public List<FillRecord> getRecentFills(int limit) {
        List<FillRecord> result = new ArrayList<>();
        int count = 0;
        for (FillRecord record : fillQueue) {
            if (count++ >= limit) break;
            result.add(record);
        }
        return result;
    }

    /**
     * @return Tamaño actual de la cola de fills
     */
    public int getQueueSize() {
        return fillQueue.size();
    }

    // =====================================================================
    // PERSISTENCIA
    // =====================================================================

    /**
     * Persiste una secuencia en el archivo JSON.
     *
     * @param sequence Secuencia a guardar
     */
    public void saveSequenceToFile(TradingSequence sequence) {
        if (fileManager != null) {
            try {
                fileManager.insert(sequence);
            } catch (Exception e) {
                Log.error(TAG, "Error saving sequence to file: " + e.getMessage());
            }
        }
    }

    /**
     * Agrega un evento de secuencia al archivo de históricos.
     *
     * @param sequence Secuencia con evento (ej: close, cancel)
     */
    public void appendSequenceEvent(TradingSequence sequence) {
        if (fileManager != null) {
            try {
                fileManager.appendEvent(sequence);
            } catch (Exception e) {
                Log.error(TAG, "Error appending sequence event: " + e.getMessage());
            }
        }
    }

    /**
     * Obtiene secuencias activas desde el archivo de persistencia.
     * Útil para recuperar estado después de un reinicio.
     *
     * @return Lista de secuencias abiertas
     */
    public List<TradingSequence> getActiveSequencesFromFile() {
        if (fileManager != null) {
            return fileManager.findOpen();
        }
        return Collections.emptyList();
    }

    // =====================================================================
    // ESTADISTICAS
    // =====================================================================

    /**
     * Obtiene estadísticas agregadas del tracker.
     * Incluye: secuencias activas, fills totales, errores,
     * tasa de éxito, latencia promedio y tamaño de cola.
     *
     * @return FillStats con todas las métricas
     */
    public FillStats getStats() {
        int active = activeSequences.size();
        int total = totalFills.get();
        int errors = totalErrors.get();
        double avgLatency = total > 0 ? (double) totalFillTimeMs.get() / total : 0;
        double successRate = total > 0 ? ((total - errors) * 100.0 / total) : 0;

        return FillStats.builder()
            .activeSequences(active)
            .totalFills(total)
            .totalErrors(errors)
            .successRate(successRate)
            .averageFillLatencyMs(avgLatency)
            .queueSize(fillQueue.size())
            .timestamp(System.currentTimeMillis())
            .build();
    }

    /**
     * Loggea todas las estadísticas del tracker en formato legible.
     */
    public void logStats() {
        FillStats stats = getStats();
        Log.info(TAG, "=== FillTracker Stats ===");
        Log.info(TAG, "  Active sequences: " + stats.getActiveSequences());
        Log.info(TAG, "  Total fills: " + stats.getTotalFills());
        Log.info(TAG, "  Errors: " + stats.getTotalErrors());
        Log.info(TAG, "  Success rate: " + String.format("%.2f%%", stats.getSuccessRate()));
        Log.info(TAG, "  Avg latency: " + String.format("%.1fms", stats.getAverageFillLatencyMs()));
        Log.info(TAG, "  Queue size: " + stats.getQueueSize());
    }

    /**
     * Obtiene el estado de una secuencia específica.
     *
     * @param seqId ID de la secuencia
     * @return SequenceState o null si no existe
     */
    public SequenceState getSequenceState(int seqId) {
        return activeSequences.get(seqId);
    }

    /**
     * @return Lista de todos los estados de secuencias activas
     */
    public List<SequenceState> getActiveSequenceStates() {
        return activeSequences.values().stream().collect(Collectors.toList());
    }

    /**
     * @return Copia del mapa de secuencias activas
     */
    public Map<Integer, SequenceState> getAllActiveSequences() {
        return new HashMap<>(activeSequences);
    }

    /**
     * Limpia todos los datos del tracker (secuencias activas y cola).
     */
    public void clear() {
        activeSequences.clear();
        fillQueue.clear();
        Log.info(TAG, "FillTracker cleared");
    }

    // =====================================================================
    // INTERNAL CLASSES
    // =====================================================================

    /**
     * Estado completo de una secuencia en ejecución.
     * Incluye: datos de la secuencia, timestamps, profit esperado/realizado,
     * y las 3 órdenes individuales (op1, op2, op3).
     */
    @Data
    @Builder
    public static class SequenceState {
        private int seqId;
        private String triangleId;
        private double expectedProfit;
        private double realizedProfit;
        private long startTime;
        private long endTime;
        private SequenceStatus status;
        private int completedOps;         // Contador de operaciones completadas (0-3)
        private OrderState op1;
        private OrderState op2;
        private OrderState op3;

        /**
         * @return Duración de la secuencia en ms (desde start hasta end, o hasta ahora si sigue activa)
         */
        public long getDurationMs() {
            return endTime > 0 ? endTime - startTime : System.currentTimeMillis() - startTime;
        }
    }

    /**
     * Estado individual de una orden dentro de una secuencia.
     * Campos públicos para acceso directo desde ExecutionEngine.
     * Registra: envío, fill, latencia, errores.
     */
    public static class OrderState {
        public int opIndex;               // 1, 2 o 3
        public String symbol;             // Símbolo (ej: "BTCUSDT")
        public String side;               // "BUY" o "SELL"
        public double sentQty;            // Cantidad enviada
        public double filledQty;          // Cantidad realmente ejecutada
        public double filledPrice;        // Precio de ejecución
        public long sentTime;             // Timestamp de envío
        public long filledTime;           // Timestamp de fill
        public long fillLatencyMs;        // Latencia = filledTime - sentTime
        public String status;             // "SENT", "FILLED", "ERROR"
        public String orderId;            // Order ID de Binance
        public String errorReason;        // Razón del error (si aplica)
    }

    /**
     * Estados posibles de una secuencia de trading.
     * IN_PROGRESS: ejecutándose actualmente
     * COMPLETED: las 3 órdenes se llenaron exitosamente
     * FAILED: al menos una orden falló
     * CANCELLED: cancelada por el sistema o el usuario
     */
    public enum SequenceStatus {
        IN_PROGRESS,
        COMPLETED,
        FAILED,
        CANCELLED
    }

    /**
     * Registro de fill completado para la cola de resultados.
     * Almacena: seqId, profit, éxito/fallo, timestamp.
     */
    @Data
    @Builder
    public static class FillRecord {
        private int seqId;
        private double profit;
        private boolean success;
        private long timestamp;
    }

    /**
     * Estadísticas agregadas del FillTracker.
     * activeSequences: secuencias actualmente en ejecución
     * totalFills: fills exitosos acumulados
     * totalErrors: órdenes con error
     * successRate: porcentaje de éxito
     * averageFillLatencyMs: latencia promedio de fills
     * queueSize: elementos pendientes en la cola
     * timestamp: momento de captura
     */
    @Data
    @Builder
    public static class FillStats {
        private int activeSequences;
        private int totalFills;
        private int totalErrors;
        private double successRate;
        private double averageFillLatencyMs;
        private int queueSize;
        private long timestamp;

        public int getActiveSequences() { return activeSequences; }
        public int getTotalFills() { return totalFills; }
        public int getTotalErrors() { return totalErrors; }
        public double getSuccessRate() { return successRate; }
        public double getAverageFillLatencyMs() { return averageFillLatencyMs; }
        public int getQueueSize() { return queueSize; }
    }
}