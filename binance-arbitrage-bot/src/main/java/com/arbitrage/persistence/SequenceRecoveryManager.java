package com.arbitrage.persistence;

import com.arbitrage.config.AppConfig;
import com.arbitrage.model.*;
import com.arbitrage.trading.BinanceApiClient;
import com.arbitrage.trading.OrderExecutor;
import com.arbitrage.util.Log;
import com.arbitrage.util.StatsManager;

import java.util.ArrayList;
import java.util.List;

public class SequenceRecoveryManager {
    private static final String TAG = "RECOVERY";

    private final BinanceApiClient apiClient;
    private final SequenceFileManager fileManager;
    private final OrderExecutor orderExecutor;
    private final AppConfig config;
    private final StatsManager statsManager;

    public SequenceRecoveryManager(BinanceApiClient apiClient, SequenceFileManager fileManager,
                                   OrderExecutor orderExecutor, AppConfig config, StatsManager statsManager) {
        this.apiClient = apiClient;
        this.fileManager = fileManager;
        this.orderExecutor = orderExecutor;
        this.config = config;
        this.statsManager = statsManager;
    }

    public List<TradingSequence> loadAndRecoverSequences() {
        Log.info(TAG, "=== Loading Sequences from Persistence ===");
        List<TradingSequence> pendingSequences = new ArrayList<>();

        try {
            List<TradingSequence> current = fileManager.findOpen();
            if (!current.isEmpty()) {
                Log.info(TAG, "Found " + current.size() + " open sequences (current format)");
                for (TradingSequence seq : current) {
                    moveToEventsIfFinal(seq);
                    if (seq.getEstado() == EstadoSecuencia.ABIERTA) {
                        pendingSequences.add(seq);
                    }
                }
            }

            List<TradingSequence> legacy = fileManager.findOpenLegacy();
            if (!legacy.isEmpty()) {
                Log.info(TAG, "Found " + legacy.size() + " open sequences (legacy format)");
                for (TradingSequence seq : legacy) {
                    moveToEventsIfFinal(seq);
                    if (seq.getEstado() == EstadoSecuencia.ABIERTA) {
                        pendingSequences.add(seq);
                    }
                }
            }

            Log.info(TAG, "Total pending sequences to recover: " + pendingSequences.size());

        } catch (Exception e) {
            Log.error(TAG, "Fatal error loading sequences: " + e.getMessage());
        }

        return pendingSequences;
    }

    private void moveToEventsIfFinal(TradingSequence seq) {
        if (seq.getEstado() != EstadoSecuencia.ABIERTA) {
            Log.info(TAG, "Sequence #" + seq.getSeqId() + " already final (" + seq.getEstado() + "), moving to events");
            moveToEvents(seq);
        }
    }

    private void moveToEvents(TradingSequence seq) {
        try {
            int seqId = seq.getSeqId();

            if (seq.getEstado() == EstadoSecuencia.CERRADA) {
                if (statsManager != null) {
                    statsManager.recordCompleted(seq.getProfitRealizado());
                }
                Log.info(TAG, "[RECOVERY] #" + seqId + " Closed sequence moved to events, profit="
                        + String.format("%.4f", seq.getProfitRealizado()));
            } else {
                if (statsManager != null) {
                    statsManager.recordCancelled();
                }
                Log.info(TAG, "[RECOVERY] #" + seqId + " Non-open sequence moved to events");
            }

            fileManager.delete(seqId);
            fileManager.appendEvent(seq);
        } catch (Exception e) {
            Log.error(TAG, "Error moving sequence to events: " + e.getMessage());
        }
    }
}
