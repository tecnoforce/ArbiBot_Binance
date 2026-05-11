package com.arbitrage.module;

import com.arbitrage.config.ApiConfig;
import com.arbitrage.model.Ticker;
import com.arbitrage.trading.BinanceApiClient;
import com.arbitrage.util.Log;
import com.arbitrage.websocket.BinanceWebSocketClient;
import com.arbitrage.websocket.PriceUpdateHandler;
import lombok.Builder;
import lombok.Data;

import java.util.*;
import java.util.stream.Collectors;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * MarketDataEngine - Gestiona todos los datos de mercado.
 *
 * Responsabilidades:
 * - Conectar a Binance WebSocket para precios en tiempo real
 * - Proveer fallback REST para simbolos faltantes
 * - Abstraer acceso a datos de precios para otros modulos
 * - Tracking de frescura y calidad de datos
 *
 * Backward compatible: coexiste con BinanceWebSocketClient existente
 */
public class MarketDataEngine {
    private static final String TAG = "MktData";

    private final ApiConfig apiConfig;
    private final BinanceApiClient apiClient;
    private final ConcurrentHashMap<String, Ticker> priceMap;

    private BinanceWebSocketClient wsClient;
    private PriceUpdateHandler priceHandler;
    private RestPriceFallback restFallback;

    private final Set<String> subscribedSymbols;
    private final AtomicInteger messageCount;
    private final AtomicLong lastUpdateTime;
    private volatile boolean connected = false;
    private volatile boolean initialized = false;

    private final long restFallbackIntervalMs;
    private final boolean restFallbackEnabled;

    public MarketDataEngine(ApiConfig apiConfig, BinanceApiClient apiClient) {
        this(apiConfig, apiClient, new ConcurrentHashMap<>(), 2000, true);
    }

    public MarketDataEngine(ApiConfig apiConfig, BinanceApiClient apiClient,
                            ConcurrentHashMap<String, Ticker> priceMap,
                            long restFallbackInterval, boolean restFallbackEnabled) {
        this.apiConfig = apiConfig;
        this.apiClient = apiClient;
        this.priceMap = priceMap;
        this.restFallbackIntervalMs = restFallbackInterval;
        this.restFallbackEnabled = restFallbackEnabled;
        this.subscribedSymbols = ConcurrentHashMap.newKeySet();
        this.messageCount = new AtomicInteger(0);
        this.lastUpdateTime = new AtomicLong(0);
    }

    /**
     * Inicializa el motor de datos de mercado.
     * Crea WebSocket client y suscribe a simbolos dados.
     *
     * @param symbols Simbolos a suscribir
     */
    public void initialize(Collection<String> symbols) {
        if (initialized) {
            Log.warn(TAG, "Already initialized");
            return;
        }

        Log.info(TAG, "Initializing MarketDataEngine with " + (symbols != null ? symbols.size() : 0) + " symbols");

        priceHandler = new PriceUpdateHandler(priceMap);
        wsClient = new BinanceWebSocketClient(apiConfig, priceHandler);

        if (symbols != null && !symbols.isEmpty()) {
            for (String symbol : symbols) {
                subscribedSymbols.add(symbol.toUpperCase());
            }
            wsClient.connectAndSubscribe(symbols);

            try {
                Thread.sleep(2000);
                connected = wsClient.isConnected();
                initialized = true;
                Log.info(TAG, "MarketDataEngine initialized. Connected: " + connected);

                if (connected) {
                    logSubscriptionStatus();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                Log.error(TAG, "Interrupted during initialization");
            }
        }

        if (restFallbackEnabled && apiClient != null && !subscribedSymbols.isEmpty()) {
            startRestFallback(subscribedSymbols);
        }
    }

    /**
     * Inicia fallback REST para simbolos faltantes.
     */
    private void startRestFallback(Set<String> symbols) {
        restFallback = new RestPriceFallback(apiClient, priceMap, symbols, restFallbackIntervalMs);
        restFallback.start();
        Log.info(TAG, "REST fallback started for " + symbols.size() + " symbols");
    }

    /**
     * Obtiene ticker para un simbolo.
     *
     * @param symbol Simbolo (ej: "BTCUSDT")
     * @return Ticker o null si no existe
     */
    public Ticker getTicker(String symbol) {
        return priceMap.get(symbol);
    }

    /**
     * Obtiene todos los tickers disponibles.
     *
     * @return Mapa de simbolos a tickers
     */
    public Map<String, Ticker> getAllTickers() {
        return new HashMap<>(priceMap);
    }

    /**
     * Obtiene precio (mid) para un simbolo.
     *
     * @param symbol Simbolo
     * @return Precio promedio o 0 si no existe
     */
    public double getPrice(String symbol) {
        Ticker ticker = priceMap.get(symbol);
        if (ticker == null) return 0.0;
        return (ticker.getBidPrice() + ticker.getAskPrice()) / 2.0;
    }

    /**
     * Obtiene mejor bid para un simbolo.
     */
    public double getBidPrice(String symbol) {
        Ticker ticker = priceMap.get(symbol);
        return ticker != null ? ticker.getBidPrice() : 0.0;
    }

    /**
     * Obtiene mejor ask para un simbolo.
     */
    public double getAskPrice(String symbol) {
        Ticker ticker = priceMap.get(symbol);
        return ticker != null ? ticker.getAskPrice() : 0.0;
    }

    /**
     * Verifica si hay datos frescos para un simbolo.
     *
     * @param symbol Simbolo
     * @param maxAgeMs Maxima edad en ms para considerar fresco
     * @return true si datos son frescos
     */
    public boolean hasFreshData(String symbol, long maxAgeMs) {
        Ticker ticker = priceMap.get(symbol);
        if (ticker == null) return false;
        long age = System.currentTimeMillis() - lastUpdateTime.get();
        return age < maxAgeMs;
    }

    /**
     * Obtiene simbolos con datos disponibles.
     *
     * @return Lista de simbolos
     */
    public Set<String> getAvailableSymbols() {
        return new HashSet<>(priceMap.keySet());
    }

    /**
     * Obtiene precio via REST API (sin WebSocket).
     *
     * @param symbol Simbolo
     * @return Precio o 0 si falla
     */
    public double getPriceRest(String symbol) {
        if (apiClient == null) return 0.0;
        return apiClient.getSymbolPrice(symbol);
    }

    /**
     * Actualiza precio manualmente (para datos externos).
     *
     * @param symbol Simbolo
     * @param bid Precio bid
     * @param ask Precio ask
     */
    public void updatePrice(String symbol, double bid, double ask) {
        Ticker ticker = Ticker.builder()
            .symbol(symbol)
            .bidPrice(bid)
            .askPrice(ask)
            .bidQty(0)
            .askQty(0)
            .build();
        priceMap.put(symbol, ticker);
        lastUpdateTime.set(System.currentTimeMillis());
        messageCount.incrementAndGet();
    }

    /**
     * Suscribe a simbolos adicionales.
     *
     * @param newSymbols Simbolos a agregar
     */
    public void subscribe(Collection<String> newSymbols) {
        subscribedSymbols.addAll(newSymbols.stream().map(String::toUpperCase).collect(Collectors.toList()));
        Log.info(TAG, "Subscribed to " + newSymbols.size() + " new symbols. Total: " + subscribedSymbols.size());
    }

    /**
     * Cancela suscripcion a simbolos.
     *
     * @param symbols Simbolos a remover
     */
    public void unsubscribe(Collection<String> symbols) {
        symbols.forEach(s -> subscribedSymbols.remove(s.toUpperCase()));
    }

    /**
     * Obtiene el price map directamente (para modulos que lo requieren).
     *
     * @return ConcurrentHashMap de simbolos a tickers
     */
    public ConcurrentHashMap<String, Ticker> getPriceMap() {
        return priceMap;
    }

    /**
     * Verifica si esta conectado al WebSocket.
     */
    public boolean isConnected() {
        return connected && wsClient != null && wsClient.isConnected();
    }

    /**
     * Obtiene estadisticas del motor.
     */
    public MarketDataStatus getStatus() {
        return MarketDataStatus.builder()
            .connected(isConnected())
            .initialized(initialized)
            .subscribedCount(subscribedSymbols.size())
            .availableCount(priceMap.size())
            .messageCount(messageCount.get())
            .lastUpdateTime(lastUpdateTime.get())
            .restFallbackEnabled(restFallbackEnabled)
            .missingSymbols(getMissingSymbols())
            .staleSymbols(getStaleSymbols(10000))
            .build();
    }

    private Set<String> getMissingSymbols() {
        Set<String> missing = new HashSet<>();
        for (String symbol : subscribedSymbols) {
            if (!priceMap.containsKey(symbol)) {
                missing.add(symbol);
            }
        }
        return missing;
    }

    private Set<String> getStaleSymbols(long maxAgeMs) {
        Set<String> stale = new HashSet<>();
        long now = System.currentTimeMillis();
        for (Map.Entry<String, Ticker> entry : priceMap.entrySet()) {
            if (now - lastUpdateTime.get() > maxAgeMs) {
                stale.add(entry.getKey());
            }
        }
        return stale;
    }

    private void logSubscriptionStatus() {
        int total = subscribedSymbols.size();
        int available = priceMap.size();
        double coverage = total > 0 ? (available * 100.0 / total) : 0;

        Log.info(TAG, "=== Subscription Status ===");
        Log.info(TAG, "  Subscribed: " + total);
        Log.info(TAG, "  Available: " + available);
        Log.info(TAG, "  Coverage: " + String.format("%.1f%%", coverage));

        if (restFallbackEnabled && restFallback != null) {
            int fetched = restFallback.getFetchedCount();
            int missing = restFallback.getMissingCount();
            Log.info(TAG, "  REST Fallback: fetched=" + fetched + ", missing=" + missing);
        }
    }

    /**
     * Fuerza actualizacion via REST para simbolos faltantes.
     */
    public void forceRestRefresh() {
        if (restFallback != null) {
            restFallback.refresh();
        }
    }

    /**
     * Cierra el motor de datos de mercado.
     */
    public void shutdown() {
        Log.info(TAG, "Shutting down MarketDataEngine");

        if (wsClient != null) {
            wsClient.close();
        }

        if (restFallback != null) {
            restFallback.stop();
        }

        initialized = false;
        connected = false;
        Log.info(TAG, "MarketDataEngine shutdown complete");
    }

    public int getMessageCount() {
        return messageCount.get();
    }

    public Set<String> getSubscribedSymbols() {
        return new HashSet<>(subscribedSymbols);
    }

    @Data
    @Builder
    public static class MarketDataStatus {
        private boolean connected;
        private boolean initialized;
        private int subscribedCount;
        private int availableCount;
        private int messageCount;
        private long lastUpdateTime;
        private boolean restFallbackEnabled;
        private Set<String> missingSymbols;
        private Set<String> staleSymbols;
    }

    /**
     * Wrapper para fallback REST (usado internamente).
     */
    private static class RestPriceFallback {
        private static final String TAG = "REST_FALLBACK";
        private final BinanceApiClient apiClient;
        private final ConcurrentHashMap<String, Ticker> priceMap;
        private final Set<String> targetSymbols;
        private final long intervalMs;
        private final java.util.concurrent.ScheduledExecutorService scheduler;
        private volatile boolean running = false;

        public RestPriceFallback(BinanceApiClient apiClient, ConcurrentHashMap<String, Ticker> priceMap,
                                 Set<String> symbols, long intervalMs) {
            this.apiClient = apiClient;
            this.priceMap = priceMap;
            this.targetSymbols = symbols;
            this.intervalMs = intervalMs;
            this.scheduler = java.util.concurrent.Executors.newSingleThreadScheduledExecutor();
        }

        public void start() {
            running = true;
            scheduler.scheduleAtFixedRate(this::fetchMissingPrices, 0, intervalMs,
                java.util.concurrent.TimeUnit.MILLISECONDS);
        }

        private void fetchMissingPrices() {
            if (!running) return;

            int fetched = 0;
            for (String symbol : targetSymbols) {
                if (!priceMap.containsKey(symbol)) {
                    try {
                        double price = apiClient.getSymbolPrice(symbol);
                        if (price > 0) {
                            priceMap.put(symbol, Ticker.builder()
                                .symbol(symbol)
                                .bidPrice(price)
                                .askPrice(price)
                                .bidQty(0)
                                .askQty(0)
                                .build());
                            fetched++;
                        }
                    } catch (Exception e) {
                        // ignore
                    }
                }
            }

            if (fetched > 0) {
                Log.debug(TAG, "REST fetched " + fetched + " missing prices");
            }
        }

        public void stop() {
            running = false;
            scheduler.shutdown();
        }

        public int getFetchedCount() {
            int count = 0;
            for (String symbol : targetSymbols) {
                if (priceMap.containsKey(symbol)) count++;
            }
            return count;
        }

        public int getMissingCount() {
            int count = 0;
            for (String symbol : targetSymbols) {
                if (!priceMap.containsKey(symbol)) count++;
            }
            return count;
        }

        public void refresh() {
            fetchMissingPrices();
        }
    }
}