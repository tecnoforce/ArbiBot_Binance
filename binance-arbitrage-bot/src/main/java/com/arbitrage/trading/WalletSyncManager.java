package com.arbitrage.trading;

import com.arbitrage.util.Log;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class WalletSyncManager {

    private static final String TAG = "WALLET_SYNC";

    private final BinanceApiClient apiClient;
    private final ScheduledExecutorService scheduler;
    private final long syncIntervalMs;
    private final List<BalanceListener> listeners;

    private volatile double usdtBalance;
    private volatile double bnbBalance;
    private volatile double bnbPrice;
    private volatile boolean running;

    public interface BalanceListener {
        void onBalancesUpdated(double usdt, double bnb, double bnbPrice);
    }

    public WalletSyncManager(BinanceApiClient apiClient, long syncIntervalMs) {
        this.apiClient = apiClient;
        this.syncIntervalMs = syncIntervalMs;
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "wallet-sync");
            t.setDaemon(true);
            return t;
        });
        this.listeners = new CopyOnWriteArrayList<>();

        syncNow();
        Log.info(TAG, "WalletSyncManager init. interval=" + syncIntervalMs + "ms");
    }

    public void addListener(BalanceListener listener) {
        if (listener != null) {
            listeners.add(listener);
        }
    }

    public void removeListener(BalanceListener listener) {
        listeners.remove(listener);
    }

    public void start() {
        if (running) return;
        running = true;

        scheduler.scheduleAtFixedRate(this::syncNow,
                syncIntervalMs, syncIntervalMs, TimeUnit.MILLISECONDS);

        Log.info(TAG, "Wallet sync started. interval=" + syncIntervalMs + "ms");
    }

    public void stop() {
        running = false;
        scheduler.shutdown();
        try {
            if (!scheduler.awaitTermination(2, TimeUnit.SECONDS)) {
                scheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            scheduler.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    public void syncNow() {
        try {
            double[] balances = apiClient.getAccountBalances();
            this.usdtBalance = balances[0];
            this.bnbBalance = balances[1];
            this.bnbPrice = balances[2];

            notifyListeners();
        } catch (Exception e) {
            Log.warn(TAG, "Sync error: " + e.getMessage());
        }
    }

    private void notifyListeners() {
        for (BalanceListener listener : listeners) {
            try {
                listener.onBalancesUpdated(usdtBalance, bnbBalance, bnbPrice);
            } catch (Exception e) {
                Log.warn(TAG, "Listener error: " + e.getMessage());
            }
        }
    }

    public double getUsdtBalance() {
        return usdtBalance;
    }

    public double getBnbBalance() {
        return bnbBalance;
    }

    public double getBnbPrice() {
        return bnbPrice;
    }
}