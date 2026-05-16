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
 * MarketDataEngine - Gestion centralizada de datos de mercado en tiempo real.
 *
 * Responsabilidades:
 * - Conectar a WebSocket de Binance para recibir precios en tiempo real
 * - Mantener un ConcurrentHashMap<String, Ticker> actualizado constantemente
 * - Proveer fallback REST para simbolos que no llegan por WebSocket
 * - Abstraer acceso a precios: bid, ask, mid, frescura de datos
 * - Tracking de calidad de conexion: simbolos disponibles, ausentes, stale
 * - Permitir suscripcion/cancelacion dinamica de simbolos
 *
 * Flujo de datos:
 *   WebSocket (PriceUpdateHandler) -> priceMap
 *   REST Fallback (RestPriceFallback) -> priceMap (simbolos faltantes)
 *   MarketDataEngine.getTicker() / getPrice() -> consumidores
 *
 * Integracion: Componente central que provee datos de precios a ArbitrageDetector,
 *              ProfitCalculator, ExecutionEngine y RepricingEngine.
 *              Todos los modulos leen del mismo priceMap compartido.
 *
 * Backward compatible: coexiste con BinanceWebSocketClient existente
 */
public class MarketDataEngine {
    /** Tag para logging */
    private static final String TAG = "MktData";

    /** Configuración de API keys y endpoints */
    private final ApiConfig apiConfig;
    /** Cliente REST de Binance (usado para fallback y consultas directas) */
    private final BinanceApiClient apiClient;
    /** Mapa compartido de precios (symbol → Ticker), actualizado por WS y REST */
    private final ConcurrentHashMap<String, Ticker> priceMap;

    /** Cliente WebSocket de Binance */
    private BinanceWebSocketClient wsClient;
    /** Manejador de actualizaciones de precio vía WebSocket */
    private PriceUpdateHandler priceHandler;
    /** Mecanismo de fallback por REST para símbolos sin WebSocket */
    private RestPriceFallback restFallback;

    /** Conjunto de símbolos suscritos actualmente */
    private final Set<String> subscribedSymbols;
    /** Contador de mensajes recibidos (WebSocket + actualizaciones manuales) */
    private final AtomicInteger messageCount;
    /** Timestamp de la última actualización de precio recibida */
    private final AtomicLong lastUpdateTime;
    /** Flag de conexión WebSocket activa */
    private volatile boolean connected = false;
    /** Flag de inicialización completada */
    private volatile boolean initialized = false;

    /** Intervalo de polling del fallback REST (ms) */
    private final long restFallbackIntervalMs;
    /** Si el fallback REST está habilitado */
    private final boolean restFallbackEnabled;

    /**
     * Constructor simplificado con priceMap nuevo, fallback cada 2000ms habilitado.
     */
    public MarketDataEngine(ApiConfig apiConfig, BinanceApiClient apiClient) {
        this(apiConfig, apiClient, new ConcurrentHashMap<>(), 2000, true);
    }

    /**
     * Constructor completo del motor de datos de mercado.
     *
     * @param apiConfig            Configuración de API keys
     * @param apiClient            Cliente REST de Binance
     * @param priceMap             Mapa de precios (compartido con otros módulos)
     * @param restFallbackInterval Intervalo de polling REST para fallback (ms)
     * @param restFallbackEnabled  Habilita/deshabilita el fallback REST
     */
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
    /**
     * Inicializa el motor de datos de mercado.
     * Crea WebSocket client, suscribe a símbolos, espera 2s para establecer conexión,
     * e inicia fallback REST si está habilitado.
     *
     * @param symbols Símbolos a suscribir (ej: ["BTCUSDT", "ETHUSDT"])
     */
    public void initialize(Collection<String> symbols) {
        if (initialized) {
            Log.warn(TAG, "Already initialized");
            return;
        }

        Log.info(TAG, "Initializing MarketDataEngine with " + (symbols != null ? symbols.size() : 0) + " symbols");

        // Crear handler y WebSocket client
        priceHandler = new PriceUpdateHandler(priceMap);
        wsClient = new BinanceWebSocketClient(apiConfig, priceHandler);

        if (symbols != null && !symbols.isEmpty()) {
            // Registrar símbolos y conectar WebSocket
            for (String symbol : symbols) {
                subscribedSymbols.add(symbol.toUpperCase());
            }
            wsClient.connectAndSubscribe(symbols);

            // Esperar 2s para que WebSocket establezca conexión y reciba primeros datos
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

        // Iniciar fallback REST para símbolos que no llegan por WebSocket
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
    /**
     * Agrega símbolos a la lista de suscripción.
     * NOTA: No reconecta el WebSocket automáticamente; los nuevos símbolos
     * se obtendrán vía REST fallback hasta la próxima reconexión.
     *
     * @param newSymbols Símbolos a agregar
     */
    public void subscribe(Collection<String> newSymbols) {
        subscribedSymbols.addAll(newSymbols.stream().map(String::toUpperCase).collect(Collectors.toList()));
        Log.info(TAG, "Subscribed to " + newSymbols.size() + " new symbols. Total: " + subscribedSymbols.size());
    }

    /**
     * Remueve símbolos de la suscripción.
     *
     * @param symbols Símbolos a remover
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
    /**
     * Obtiene el estado actual del motor de datos de mercado.
     * Incluye: conectividad, símbolos suscritos/disponibles, mensajes,
     * símbolos faltantes y stale (más de 10s sin actualizar).
     *
     * @return MarketDataStatus con todas las métricas
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

    /**
     * Encuentra símbolos suscritos que aún no tienen datos en el priceMap.
     *
     * @return Conjunto de símbolos faltantes
     */
    private Set<String> getMissingSymbols() {
        Set<String> missing = new HashSet<>();
        for (String symbol : subscribedSymbols) {
            if (!priceMap.containsKey(symbol)) {
                missing.add(symbol);
            }
        }
        return missing;
    }

    /**
     * Encuentra símbolos con datos desactualizados (más de maxAgeMs sin actualizar).
     *
     * @param maxAgeMs Edad máxima permitida sin actualización
     * @return Conjunto de símbolos stale
     */
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

    /**
     * Loggea el estado de suscripción: total suscritos, disponibles, cobertura,
     * y estadísticas del fallback REST (si está habilitado).
     */
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
    /**
     * Fuerza una actualización inmediata vía REST para todos los símbolos faltantes.
     */
    public void forceRestRefresh() {
        if (restFallback != null) {
            restFallback.refresh();
        }
    }

    /**
     * Cierra el motor de datos de mercado.
     * Detiene WebSocket y fallback REST, marca flags como false.
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

    /**
     * @return Total de mensajes/actualizaciones recibidas
     */
    public int getMessageCount() {
        return messageCount.get();
    }

    /**
     * @return Copia del conjunto de símbolos suscritos
     */
    public Set<String> getSubscribedSymbols() {
        return new HashSet<>(subscribedSymbols);
    }

    /**
     * Estado del motor de datos de mercado.
     * connected: conexión WebSocket activa
     * initialized: inicialización completada
     * subscribedCount: símbolos suscritos
     * availableCount: símbolos con datos disponibles
     * messageCount: total de mensajes recibidos
     * lastUpdateTime: timestamp de la última actualización
     * restFallbackEnabled: si el fallback REST está activo
     * missingSymbols: símbolos suscritos sin datos
     * staleSymbols: símbolos con datos desactualizados
     */
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
     * Mecanismo de fallback REST para obtener precios de símbolos que
     * no están llegando por WebSocket.
     *
     * Ejecuta polling periódico (cada intervalMs) para los símbolos objetivo
     * que no están presentes en el priceMap. Cuando encuentra un precio,
     * lo inserta con bid=ask=price (sin spread, ya que es un precio único).
     *
     * Útil para:
     * - Símbolos con baja liquidez que no están en el stream de WebSocket
     * - Recuperación ante caídas del WebSocket
     * - Símbolos agregados después de la conexión inicial
     */
    private static class RestPriceFallback {
        private static final String TAG = "REST_FALLBACK";
        private final BinanceApiClient apiClient;
        private final ConcurrentHashMap<String, Ticker> priceMap;
        private final Set<String> targetSymbols;
        private final long intervalMs;
        private final java.util.concurrent.ScheduledExecutorService scheduler;
        private volatile boolean running = false;

        /**
         * @param apiClient  Cliente REST de Binance
         * @param priceMap   Mapa de precios compartido
         * @param symbols    Símbolos a monitorear
         * @param intervalMs Intervalo de polling (ms)
         */
        public RestPriceFallback(BinanceApiClient apiClient, ConcurrentHashMap<String, Ticker> priceMap,
                                 Set<String> symbols, long intervalMs) {
            this.apiClient = apiClient;
            this.priceMap = priceMap;
            this.targetSymbols = symbols;
            this.intervalMs = intervalMs;
            this.scheduler = java.util.concurrent.Executors.newSingleThreadScheduledExecutor();
        }

        /**
         * Inicia el polling periódico de precios faltantes.
         */
        public void start() {
            running = true;
            scheduler.scheduleAtFixedRate(this::fetchMissingPrices, 0, intervalMs,
                java.util.concurrent.TimeUnit.MILLISECONDS);
        }

        /**
         * Obtiene precios de símbolos que faltan en el priceMap vía REST API.
         * Solo consulta símbolos que aún no tienen datos.
         */
        private void fetchMissingPrices() {
            if (!running) return;

            int fetched = 0;
            for (String symbol : targetSymbols) {
                // Solo consultar si el símbolo no está ya en el mapa
                if (!priceMap.containsKey(symbol)) {
                    try {
                        double price = apiClient.getSymbolPrice(symbol);
                        if (price > 0) {
                            // Insertar con bid=ask (precio único, sin spread)
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

        /**
         * Detiene el polling.
         */
        public void stop() {
            running = false;
            scheduler.shutdown();
        }

        /**
         * @return Cuántos símbolos objetivo tienen datos en el priceMap
         */
        public int getFetchedCount() {
            int count = 0;
            for (String symbol : targetSymbols) {
                if (priceMap.containsKey(symbol)) count++;
            }
            return count;
        }

        /**
         * @return Cuántos símbolos objetivo aún faltan en el priceMap
         */
        public int getMissingCount() {
            int count = 0;
            for (String symbol : targetSymbols) {
                if (!priceMap.containsKey(symbol)) count++;
            }
            return count;
        }

        /**
         * Fuerza una actualización inmediata.
         */
        public void refresh() {
            fetchMissingPrices();
        }
    }
}