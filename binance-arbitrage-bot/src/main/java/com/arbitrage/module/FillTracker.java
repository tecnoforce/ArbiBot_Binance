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
 * FillTracker - Tracking de fills y queue de ordenes.
 *
 * Responsabilidades:
 * - Monitorear estado de ordenes en ejecucion
 * - Tracking de fills completados
 * - Persistencia de historial de secuencias
 * - Estadisticas de fills (latencia, fill rate, etc.)
 *
 * Backward compatible: puede coexistir con OrderExecutor
 */
public class FillTracker {
    private static final String TAG = "FillTracker";

    private final SequenceFileManager fileManager;
    private final ConcurrentLinkedQueue<FillRecord> fillQueue;
    private final ConcurrentHashMap<Integer, SequenceState> activeSequences;
    private final AtomicInteger totalFills;
    private final AtomicInteger totalErrors;
    private final AtomicLong totalFillTimeMs;

    public FillTracker(SequenceFileManager fileManager) {
        this.fileManager = fileManager;
        this.fillQueue = new ConcurrentLinkedQueue<>();
        this.activeSequences = new ConcurrentHashMap<>();
        this.totalFills = new AtomicInteger(0);
        this.totalErrors = new AtomicInteger(0);
        this.totalFillTimeMs = new AtomicLong(0);
        Log.info(TAG, "FillTracker initialized");
    }

    public FillTracker() {
        this(null);
    }

    // =====================================================================
    // TRACKING DE SECUENCIAS ACTIVAS
    // =====================================================================

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

        switch (opIndex) {
            case 1: state.op1 = order; break;
            case 2: state.op2 = order; break;
            case 3: state.op3 = order; break;
        }

        Log.debug(TAG, "Seq #" + seqId + " Op" + opIndex + " SENT: " + symbol + " " + side + " qty=" + qty);
    }

    public void trackOrderFilled(int seqId, int opIndex, OrderResult result) {
        SequenceState state = activeSequences.get(seqId);
        if (state == null) return;

        OrderState order;
        switch (opIndex) {
            case 1: order = state.op1; break;
            case 2: order = state.op2; break;
            case 3: order = state.op3; break;
            default: return;
        }

        if (order == null) return;

        order.filledTime = System.currentTimeMillis();
        order.filledQty = result.getExecutedQty();
        order.filledPrice = result.getPrice();
        order.status = "FILLED";
        order.orderId = result.getOrderId();
        order.fillLatencyMs = order.filledTime - order.sentTime;

        totalFills.incrementAndGet();
        totalFillTimeMs.addAndGet(order.fillLatencyMs);

        state.completedOps++;
        if (state.completedOps == 3) {
            state.endTime = System.currentTimeMillis();
            state.status = SequenceStatus.COMPLETED;
            state.realizedProfit = state.expectedProfit;
        }

        Log.debug(TAG, "Seq #" + seqId + " Op" + opIndex + " FILLED: " + order.fillLatencyMs + "ms");
    }

    public void trackOrderError(int seqId, int opIndex, String reason) {
        SequenceState state = activeSequences.get(seqId);
        if (state == null) return;

        OrderState order;
        switch (opIndex) {
            case 1: order = state.op1; break;
            case 2: order = state.op2; break;
            case 3: order = state.op3; break;
            default: return;
        }

        if (order == null) return;

        order.filledTime = System.currentTimeMillis();
        order.status = "ERROR";
        order.errorReason = reason;

        state.endTime = System.currentTimeMillis();
        state.status = SequenceStatus.FAILED;
        state.realizedProfit = 0;

        totalErrors.incrementAndGet();

        Log.warn(TAG, "Seq #" + seqId + " Op" + opIndex + " ERROR: " + reason);
    }

    public void trackSequenceEnd(int seqId, boolean success, double profit) {
        SequenceState state = activeSequences.get(seqId);
        if (state != null) {
            state.endTime = System.currentTimeMillis();
            state.status = success ? SequenceStatus.COMPLETED : SequenceStatus.FAILED;
            state.realizedProfit = profit;
        }

        enqueueFill(seqId, profit, success);
    }

    public void removeSequence(int seqId) {
        activeSequences.remove(seqId);
    }

    // =====================================================================
    // FILL QUEUE
    // =====================================================================

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

    public FillRecord dequeueFill() {
        return fillQueue.poll();
    }

    public List<FillRecord> getRecentFills(int limit) {
        List<FillRecord> result = new ArrayList<>();
        int count = 0;
        for (FillRecord record : fillQueue) {
            if (count++ >= limit) break;
            result.add(record);
        }
        return result;
    }

    public int getQueueSize() {
        return fillQueue.size();
    }

    // =====================================================================
    // PERSISTENCIA
    // =====================================================================

    public void saveSequenceToFile(TradingSequence sequence) {
        if (fileManager != null) {
            try {
                fileManager.insert(sequence);
            } catch (Exception e) {
                Log.error(TAG, "Error saving sequence to file: " + e.getMessage());
            }
        }
    }

    public void appendSequenceEvent(TradingSequence sequence) {
        if (fileManager != null) {
            try {
                fileManager.appendEvent(sequence);
            } catch (Exception e) {
                Log.error(TAG, "Error appending sequence event: " + e.getMessage());
            }
        }
    }

    public List<TradingSequence> getActiveSequencesFromFile() {
        if (fileManager != null) {
            return fileManager.findOpen();
        }
        return Collections.emptyList();
    }

    // =====================================================================
    // ESTADISTICAS
    // =====================================================================

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

    public SequenceState getSequenceState(int seqId) {
        return activeSequences.get(seqId);
    }

    public List<SequenceState> getActiveSequenceStates() {
        return activeSequences.values().stream().collect(Collectors.toList());
    }

    public Map<Integer, SequenceState> getAllActiveSequences() {
        return new HashMap<>(activeSequences);
    }

    public void clear() {
        activeSequences.clear();
        fillQueue.clear();
        Log.info(TAG, "FillTracker cleared");
    }

    // =====================================================================
    // INTERNAL CLASSES
    // =====================================================================

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
        private int completedOps;
        private OrderState op1;
        private OrderState op2;
        private OrderState op3;

        public long getDurationMs() {
            return endTime > 0 ? endTime - startTime : System.currentTimeMillis() - startTime;
        }
    }

    public static class OrderState {
        public int opIndex;
        public String symbol;
        public String side;
        public double sentQty;
        public double filledQty;
        public double filledPrice;
        public long sentTime;
        public long filledTime;
        public long fillLatencyMs;
        public String status;
        public String orderId;
        public String errorReason;
    }

    public enum SequenceStatus {
        IN_PROGRESS,
        COMPLETED,
        FAILED,
        CANCELLED
    }

    @Data
    @Builder
    public static class FillRecord {
        private int seqId;
        private double profit;
        private boolean success;
        private long timestamp;
    }

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