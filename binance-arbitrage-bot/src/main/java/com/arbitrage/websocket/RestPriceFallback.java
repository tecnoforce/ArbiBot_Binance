package com.arbitrage.websocket;

import com.arbitrage.config.AppConfig;
import com.arbitrage.model.Ticker;
import com.arbitrage.trading.BinanceApiClient;
import com.arbitrage.util.Log;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * TODO_REMOVE_AFTER_TESTING: REST fallback for fetching prices via REST API.
 * This is a TEMPORARY feature for testing purposes only.
 * 
 * Purpose: Fetch prices for symbols that are not received via WebSocket.
 * Problem it solves: Some symbols in triangles may not be available in testnet WebSocket,
 * causing priceMap to have fewer symbols than expected.
 * 
 * IMPORTANT: Remove this class after testing is complete!
 */
public class RestPriceFallback {
    private static final String TAG = "REST_FALLBACK";

    private final BinanceApiClient apiClient;
    private final ConcurrentHashMap<String, Ticker> priceMap;
    private final Set<String> targetSymbols;
    private final AppConfig config;
    private final ScheduledExecutorService scheduler;
    private volatile boolean running = false;

    public RestPriceFallback(
            BinanceApiClient apiClient,
            ConcurrentHashMap<String, Ticker> priceMap,
            Set<String> targetSymbols,
            AppConfig config
    ) {
        this.apiClient = apiClient;
        this.priceMap = priceMap;
        this.targetSymbols = targetSymbols;
        this.config = config;
        this.scheduler = Executors.newSingleThreadScheduledExecutor();
    }

    public void start() {
        if (!config.isRestFallbackEnabled()) {
            Log.info(TAG, "REST fallback DISABLED (feature flag)");
            return;
        }

        running = true;
        long interval = config.getRestFallbackIntervalMs();

        Log.info(TAG, "REST fallback ENABLED - polling interval: " + interval + "ms");
        Log.info(TAG, "Target symbols: " + targetSymbols.size());

        scheduler.scheduleAtFixedRate(() -> {
            try {
                fetchMissingPrices();
            } catch (Exception e) {
                Log.debug(TAG, "Error fetching prices: " + e.getMessage());
            }
        }, 0, interval, TimeUnit.MILLISECONDS);
    }

    private void fetchMissingPrices() {
        int fetched = 0;
        int missing = 0;

        for (String symbol : targetSymbols) {
            if (!priceMap.containsKey(symbol)) {
                Ticker ticker = fetchSymbolPrice(symbol);
                if (ticker != null) {
                    priceMap.put(symbol, ticker);
                    fetched++;
                } else {
                    missing++;
                }
            }
        }

        if (fetched > 0 || missing > 0) {
            Log.debug(TAG, "REST fetch: " + fetched + " new, " + missing + " not found, priceMap=" + priceMap.size());
        }
    }

    private Ticker fetchSymbolPrice(String symbol) {
        try {
            double price = apiClient.getSymbolPrice(symbol);
            if (price > 0) {
                return Ticker.builder()
                        .symbol(symbol)
                        .bidPrice(price)
                        .askPrice(price)
                        .bidQty(0.0)
                        .askQty(0.0)
                        .build();
            }
        } catch (Exception e) {
            // Silently ignore - symbol may not exist
        }
        return null;
    }

    public void stop() {
        running = false;
        scheduler.shutdown();
        Log.info(TAG, "REST fallback stopped");
    }

    public int getMissingCount() {
        int missing = 0;
        for (String symbol : targetSymbols) {
            if (!priceMap.containsKey(symbol)) {
                missing++;
            }
        }
        return missing;
    }

    public int getFetchedCount() {
        int fetched = 0;
        for (String symbol : targetSymbols) {
            if (priceMap.containsKey(symbol)) {
                fetched++;
            }
        }
        return fetched;
    }
}