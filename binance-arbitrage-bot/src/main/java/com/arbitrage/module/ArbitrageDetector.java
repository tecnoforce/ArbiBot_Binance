package com.arbitrage.module;

import com.arbitrage.config.AppConfig;
import com.arbitrage.engine.PrecisionAdjuster;
import com.arbitrage.model.ArbitrageOpportunity;
import com.arbitrage.model.Ticker;
import com.arbitrage.model.Triangle;
import com.arbitrage.util.Log;
import lombok.Builder;
import lombok.Data;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

public class ArbitrageDetector {
    private static final String TAG = "ArbDetect";

    private final AppConfig config;
    private final ProfitCalculator profitCalculator;
    private final PrecisionAdjuster precisionAdjuster;
    private final ConcurrentHashMap<String, Ticker> priceMap;
    private final List<Triangle> triangles;
    private final ConcurrentHashMap<String, Double> lastProfitByTriangle;
    private final Consumer<ArbitrageOpportunity> opportunityConsumer;

    private ScheduledExecutorService scheduler;
    private volatile boolean running = false;
    private volatile long lastDebugLog = 0;
    private volatile long lastPriceMapLog = 0;
    private int logCounter = 0;

    private final AtomicLong scanCount;
    private final AtomicLong opportunityCount;
    private final AtomicLong opportunityEmitted;

    private final int scanIntervalMs;
    private final boolean emitAllProfitable;

    public ArbitrageDetector(
            AppConfig config,
            List<Triangle> triangles,
            ConcurrentHashMap<String, Ticker> priceMap,
            PrecisionAdjuster precisionAdjuster,
            Consumer<ArbitrageOpportunity> opportunityConsumer
    ) {
        this(config, triangles, priceMap, precisionAdjuster, opportunityConsumer, 100, true);
    }

    public ArbitrageDetector(
            AppConfig config,
            List<Triangle> triangles,
            ConcurrentHashMap<String, Ticker> priceMap,
            PrecisionAdjuster precisionAdjuster,
            Consumer<ArbitrageOpportunity> opportunityConsumer,
            int scanIntervalMs,
            boolean emitAllProfitable
    ) {
        this.config = config;
        this.priceMap = priceMap;
        this.precisionAdjuster = precisionAdjuster;
        this.triangles = triangles;
        this.opportunityConsumer = opportunityConsumer;
        this.scanIntervalMs = scanIntervalMs;
        this.emitAllProfitable = emitAllProfitable;

        this.profitCalculator = new ProfitCalculator(config);
        this.profitCalculator.setPrecisionAdjuster(precisionAdjuster);

        this.lastProfitByTriangle = new ConcurrentHashMap<>();

        this.scanCount = new AtomicLong(0);
        this.opportunityCount = new AtomicLong(0);
        this.opportunityEmitted = new AtomicLong(0);

        Log.info(TAG, "ArbitrageDetector initialized with " + triangles.size() + " triangles");
    }

    public void start() {
        if (running) {
            Log.warn(TAG, "Already running");
            return;
        }

        running = true;

        int cores = config.getCores();
        this.scheduler = Executors.newScheduledThreadPool(cores);

        String currentLevel = Log.getCurrentLevel();
        if (!"SCAN".equals(currentLevel)) {
            Log.info(TAG, "Starting scan every " + scanIntervalMs + "ms with " + cores + " threads");
        }

        scheduler.scheduleAtFixedRate(() -> {
            if (!running) return;
            scanCount.incrementAndGet();
            try {
                triangles.parallelStream().forEach(this::checkTriangle);
            } catch (Exception e) {
                Log.debug(TAG, "Scan error: " + e.getMessage());
            }
        }, 0, scanIntervalMs, TimeUnit.MILLISECONDS);
    }

    public void stop() {
        running = false;
        if (scheduler != null) {
            scheduler.shutdown();
        }
        Log.info(TAG, "Stopped. Scans=" + scanCount.get() + ", Opportunities=" + opportunityCount.get());
    }

    public boolean isRunning() {
        return running;
    }

    private void checkTriangle(Triangle triangle) {
        try {
            long now = System.currentTimeMillis();
            logDebugStatus(now);

            Ticker t1 = priceMap.get(triangle.getSymbol1());
            Ticker t2 = priceMap.get(triangle.getSymbol2());
            Ticker t3 = priceMap.get(triangle.getSymbol3());

            if (t1 == null || t2 == null || t3 == null) {
                return;
            }

            ArbitrageOpportunity opportunity = profitCalculator.calculate(
                    triangle,
                    config.getBalancePerTrade(),
                    t1, t2, t3
            );

            if (opportunity == null) {
                return;
            }

            if (!isOpportunityValid(opportunity)) {
                return;
            }

            emitIfNew(opportunity);

        } catch (Exception e) {
            String msg = e.getMessage();
            if (msg != null && msg.contains("Unexpected token")) {
                Log.error(TAG, "JSON parse error in profit calculation");
            } else {
                Log.debug(TAG, "Error triangle " + triangle.getId() + ": " + msg);
            }
        }
    }

    private void logDebugStatus(long now) {
        if (now - lastPriceMapLog > 5000 && priceMap.size() > 0) {
            lastPriceMapLog = now;
            logCounter++;
            if (logCounter <= 3 && !Log.isScanEnabled()) {
                System.out.println("[DEBUG] priceMap keys: " + priceMap.keySet());
            }
        }

        if (now - lastDebugLog > 5000 && priceMap.size() > 0) {
            lastDebugLog = now;
            int available = 0;
            for (Triangle t : triangles) {
                if (priceMap.containsKey(t.getSymbol1()) &&
                    priceMap.containsKey(t.getSymbol2()) &&
                    priceMap.containsKey(t.getSymbol3())) {
                    available++;
                }
            }
            Log.debug(TAG, "priceMap=" + priceMap.size() + " triangles=" + triangles.size() + " ready=" + available);
        }
    }

    private boolean isOpportunityValid(ArbitrageOpportunity opportunity) {
        double profitPct = opportunity.getProfitPct();
        return profitPct >= -1.0 && profitPct <= config.getMaxProfit();
    }

    private void emitIfNew(ArbitrageOpportunity opportunity) {
        String triangleId = opportunity.getTriangle().getId();
        double profitPct = opportunity.getProfitPct();
        double minProfit = config.getMinProfit();

        boolean shouldExecute = profitPct >= minProfit;
        boolean showInInfo = profitPct >= -1.0 && profitPct <= config.getMaxProfit();
        boolean showInScan = profitPct >= minProfit;

        if (!showInInfo && !showInScan) {
            return;
        }

        Double lastProfit = lastProfitByTriangle.get(triangleId);
        if (lastProfit != null && Math.abs(profitPct - lastProfit) <= 0.0001) {
            return;
        }

        lastProfitByTriangle.put(triangleId, profitPct);
        opportunityCount.incrementAndGet();

        String currentLevel = Log.getCurrentLevel();
        String prefix = profitPct > 0 ? "[+]" : "[-]";
        String logMsg = "OPORTUNIDAD: " + prefix + " " + triangleId + " | Profit: " + String.format("%.4f", profitPct) + "%";

        if ("SCAN".equals(currentLevel) && showInScan) {
            // Silent in SCAN mode
        } else if (!"SCAN".equals(currentLevel) && showInInfo) {
            Log.info(TAG, logMsg);
        }

        if (shouldExecute || emitAllProfitable) {
            opportunityConsumer.accept(opportunity);
            opportunityEmitted.incrementAndGet();
        }
    }

    public void resetProfitHistory() {
        lastProfitByTriangle.clear();
        Log.info(TAG, "Profit history reset");
    }

    public void addTriangle(Triangle triangle) {
        if (!triangles.contains(triangle)) {
            triangles.add(triangle);
        }
    }

    public void removeTriangle(Triangle triangle) {
        triangles.remove(triangle);
    }

    public List<Triangle> getTriangles() {
        return new ArrayList<>(triangles);
    }

    public DetectorStats getStats() {
        int available = 0;
        for (Triangle t : triangles) {
            if (priceMap.containsKey(t.getSymbol1()) &&
                priceMap.containsKey(t.getSymbol2()) &&
                priceMap.containsKey(t.getSymbol3())) {
                available++;
            }
        }

        return DetectorStats.builder()
            .running(running)
            .scanCount(scanCount.get())
            .opportunityCount(opportunityCount.get())
            .opportunityEmitted(opportunityEmitted.get())
            .triangleCount(triangles.size())
            .trianglesReady(available)
            .priceMapSize(priceMap.size())
            .timestamp(System.currentTimeMillis())
            .build();
    }

    public void logStats() {
        DetectorStats stats = getStats();
        Log.info(TAG, "=== ArbitrageDetector Stats ===");
        Log.info(TAG, "  Running: " + stats.isRunning());
        Log.info(TAG, "  Scans: " + stats.getScanCount());
        Log.info(TAG, "  Opportunities found: " + stats.getOpportunityCount());
        Log.info(TAG, "  Opportunities emitted: " + stats.getOpportunityEmitted());
        Log.info(TAG, "  Triangles: " + stats.getTriangleCount() + " (ready: " + stats.getTrianglesReady() + ")");
        Log.info(TAG, "  PriceMap: " + stats.getPriceMapSize());
    }

    @Data
    @Builder
    public static class DetectorStats {
        private boolean running;
        private long scanCount;
        private long opportunityCount;
        private long opportunityEmitted;
        private int triangleCount;
        private int trianglesReady;
        private int priceMapSize;
        private long timestamp;

        public boolean isRunning() { return running; }
    }
}