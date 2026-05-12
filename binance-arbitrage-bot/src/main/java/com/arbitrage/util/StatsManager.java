package com.arbitrage.util;

import com.arbitrage.model.TradingStats;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.concurrent.locks.ReentrantLock;

public class StatsManager {
    private static final String TAG = "STATS_MGR";

    private final Path statsPath;
    private final ObjectMapper objectMapper;
    private final ReentrantLock fileLock;
    private final long startTime;

    private TradingStats stats;

    public StatsManager(String basePath, String fileName, String baseCurrency, double initialInvestment) {
        this.statsPath = Paths.get(basePath, fileName);
        this.objectMapper = new ObjectMapper();
        this.objectMapper.disable(SerializationFeature.INDENT_OUTPUT);
        this.fileLock = new ReentrantLock();
        this.startTime = System.currentTimeMillis();

        this.stats = load();
        if (this.stats == null) {
            this.stats = TradingStats.builder()
                    .totalProfit(0.0)
                    .totalProfitPercent(0.0)
                    .eventosCompletados(0)
                    .eventosPorCompletar(0)
                    .timeElapsed(0)
                    .eventosStoploss(0)
                    .averageProfitQty(0.0)
                    .averageProfitPercent(0.0)
                    .baseCurrency(baseCurrency)
                    .initialInvestment(initialInvestment)
                    .eventsFinalizados(0)
                    .build();
        }

        Log.info(TAG, "StatsManager init. file=" + statsPath.getFileName() + ", initialInvestment=" + initialInvestment + " " + baseCurrency);
    }

    private TradingStats load() {
        if (!Files.exists(statsPath)) {
            Log.debug(TAG, "Stats file does not exist, starting clean");
            return null;
        }

        try {
            String content = new String(Files.readAllBytes(statsPath));
            if (content.trim().isEmpty()) {
                return null;
            }
            TradingStats loaded = objectMapper.readValue(content, TradingStats.class);
            Log.info(TAG, "Stats loaded: completed=" + loaded.getEventosCompletados() + 
                    ", totalProfit=" + String.format("%.4f", loaded.getTotalProfit()));
            return loaded;
        } catch (Exception e) {
            Log.warn(TAG, "Error reading stats file: " + e.getMessage() + ", starting clean");
            return null;
        }
    }

    public synchronized void recordCompleted(double profitRealizado) {
        stats.setTotalProfit(stats.getTotalProfit() + profitRealizado);
        stats.setEventosCompletados(stats.getEventosCompletados() + 1);
        stats.setEventsFinalizados(stats.getEventsFinalizados() + 1);

        if (stats.getInitialInvestment() > 0) {
            double newTotalPct = (stats.getTotalProfit() / stats.getInitialInvestment()) * 100.0;
            stats.setTotalProfitPercent(newTotalPct);
        }

        stats.computeAverages();
        stats.setTimeElapsed(System.currentTimeMillis() - startTime);

        save();
        Log.debug(TAG, "Stats recorded completed: profit=" + String.format("%.4f", profitRealizado) +
                ", total=" + String.format("%.4f", stats.getTotalProfit()) +
                ", completed=" + stats.getEventosCompletados());
    }

    public synchronized void recordCancelled() {
        stats.setEventosPorCompletar(stats.getEventosPorCompletar() + 1);
        stats.setEventsFinalizados(stats.getEventsFinalizados() + 1);
        stats.setTimeElapsed(System.currentTimeMillis() - startTime);

        save();
        Log.debug(TAG, "Stats recorded cancelled: porcCompletar=" + stats.getEventosPorCompletar());
    }

    public synchronized void save() {
        fileLock.lock();
        try {
            Path tempPath = Paths.get(statsPath.toString() + ".tmp");

            String json = objectMapper.writeValueAsString(stats);
            Files.write(tempPath, json.getBytes());

            if (Files.exists(statsPath)) {
                Files.delete(statsPath);
            }
            Files.move(tempPath, statsPath, StandardCopyOption.ATOMIC_MOVE);

            Log.debug(TAG, "Stats saved atomically: " + statsPath.getFileName());
        } catch (Exception e) {
            Log.error(TAG, "Failed to save stats: " + e.getMessage());
        } finally {
            fileLock.unlock();
        }
    }

    public TradingStats getStats() {
        return stats;
    }

    public long getTimeElapsed() {
        return System.currentTimeMillis() - startTime;
    }
}
