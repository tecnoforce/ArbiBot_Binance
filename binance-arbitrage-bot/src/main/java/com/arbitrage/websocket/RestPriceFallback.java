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
 * Fallback a API REST para obtener precios de símbolos que no llegan por WebSocket.
 * <p>
 * <strong>CLASE TEMPORAL — Eliminar después de pruebas.</strong>
 * <p>
 * Problema que resuelve: En testnet de Binance, algunos símbolos usados en los
 * triángulos de arbitraje no están disponibles en los streams WebSocket. Esto causa
 * que el {@code priceMap} tenga menos entradas de las necesarias, resultando en
 * triángulos incompletos o profit calculado incorrectamente.
 * <p>
 * Funcionamiento:
 * <ol>
 *   <li>Recibe el conjunto de símbolos objetivo (los que necesita el motor de arbitraje)</li>
 *   <li>En cada ciclo de polling, itera sobre los símbolos que AÚN NO están en el priceMap</li>
 *   <li>Para cada símbolo faltante, llama a {@link BinanceApiClient#getSymbolPrice(String)}</li>
 *   <li>Si la llamada es exitosa, inserta el {@link Ticker} en el mapa compartido</li>
 * </ol>
 * <p>
 * Limitaciones:
 * <ul>
 *   <li>Usa el mismo precio para bid y ask (no hay spread real)</li>
 *   <li>Las cantidades (bidQty/askQty) se fijan a 0.0</li>
 *   <li>Es polling, no streaming — hay latencia entre ciclos</li>
 * </ul>
 * <p>
 * <strong>En producción, el WebSocket debe cubrir todos los símbolos.
 * Esta clase es un parche temporal para pruebas solamente.</strong>
 *
 * @see BinanceApiClient   # Cliente REST para obtener precios
 * @see PriceUpdateHandler # Fuente principal de precios (WebSocket)
 * @see com.arbitrage.engine.ArbitrageEngine # Consumidor de los precios
 */
public class RestPriceFallback {
    private static final String TAG = "REST_FALLBACK";

    /** Cliente REST de Binance para obtener precios por símbolo */
    private final BinanceApiClient apiClient;
    /** Mapa de precios compartido (se rellenan los símbolos faltantes aquí) */
    private final ConcurrentHashMap<String, Ticker> priceMap;
    /** Conjunto de símbolos objetivo que el motor de arbitraje necesita */
    private final Set<String> targetSymbols;
    /** Configuración de la aplicación (intervalo de polling, feature flag, etc.) */
    private final AppConfig config;
    /** Scheduler para ejecutar el polling periódico en un solo thread */
    private final ScheduledExecutorService scheduler;
    /** Bandera volátil que indica si el fallback está en ejecución */
    private volatile boolean running = false;

    /**
     * Constructor del fallback REST.
     *
     * @param apiClient     Cliente REST de Binance para consultar precios
     * @param priceMap      Mapa de precios compartido con el motor de arbitraje
     * @param targetSymbols Símbolos objetivo que necesita el motor de arbitraje
     * @param config        Configuración de la aplicación (intervalo, feature flags)
     */
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
        // Scheduler con un solo thread para evitar ejecuciones concurrentes
        this.scheduler = Executors.newSingleThreadScheduledExecutor();
    }

    /**
     * Inicia el polling periódico de precios vía REST API.
     * <p>
     * Respeta el feature flag {@code dailyLossCheckEnabled} en el config:
     * si está deshabilitado, no arranca.
     * <p>
     * Una vez iniciado, ejecuta {@link #fetchMissingPrices()} en un intervalo fijo.
     * El primer ciclo se ejecuta inmediatamente ({@code initialDelay = 0}).
     */
    public void start() {
        // Verifica el feature flag: si está deshabilitado, no hace nada
        if (!config.isDailyLossCheckEnabled()) {
            Log.info(TAG, "REST fallback DISABLED (feature flag)");
            return;
        }

        running = true;
        // Intervalo de polling desde la configuración (ms)
        long interval = config.getDailyLossCheckIntervalMs();

        Log.info(TAG, "REST fallback ENABLED - polling interval: " + interval + "ms");
        Log.info(TAG, "Target symbols: " + targetSymbols.size());

        // Programa la tarea a intervalos fijos, comenzando inmediatamente
        scheduler.scheduleAtFixedRate(() -> {
            try {
                fetchMissingPrices();
            } catch (Exception e) {
                // Error no crítico: solo se loguea y continúa el siguiente ciclo
                Log.debug(TAG, "Error fetching prices: " + e.getMessage());
            }
        }, 0, interval, TimeUnit.MILLISECONDS);
    }

    /**
     * Itera sobre todos los símbolos objetivo y consulta vía REST aquellos
     * que aún no están presentes en el {@code priceMap}.
     * <p>
     * Solo rellena los huecos: si un símbolo ya está en el mapa (porque el WebSocket
     * lo está alimentando), se salta la consulta REST.
     */
    private void fetchMissingPrices() {
        int fetched = 0;   // Contador de símbolos recién obtenidos
        int missing = 0;   // Contador de símbolos que no se pudieron obtener

        for (String symbol : targetSymbols) {
            // Solo consulta si el símbolo NO está en el mapa WebSocket
            if (!priceMap.containsKey(symbol)) {
                Ticker ticker = fetchSymbolPrice(symbol);
                if (ticker != null) {
                    // Éxito: inserta el ticker en el mapa compartido
                    priceMap.put(symbol, ticker);
                    fetched++;
                } else {
                    // No se pudo obtener: se cuenta como missing
                    missing++;
                }
            }
        }

        if (fetched > 0 || missing > 0) {
            Log.debug(TAG, "REST fetch: " + fetched + " new, " + missing + " not found, priceMap=" + priceMap.size());
        }
    }

    /**
     * Consulta el precio de un símbolo vía API REST de Binance.
     * <p>
     * NOTA: Como la REST API solo devuelve un precio único (sin bid/ask diferenciado),
     * se usa el mismo valor para ambos. Las cantidades se fijan a 0.0.
     * Esto es suficiente para el cálculo de arbitraje pero no refleja el spread real.
     *
     * @param symbol Símbolo a consultar (ej. "BTCUSDT")
     * @return Ticker con el precio obtenido, o null si hay error
     */
    private Ticker fetchSymbolPrice(String symbol) {
        try {
            // Llama a la REST API de Binance: GET /api/v3/ticker/price?symbol=BTCUSDT
            double price = apiClient.getSymbolPrice(symbol);
            if (price > 0) {
                // Crea un Ticker con bid=ask (mismo precio) y cantidades en 0
                return Ticker.builder()
                        .symbol(symbol)
                        .bidPrice(price)
                        .askPrice(price)
                        .bidQty(0.0)
                        .askQty(0.0)
                        .build();
            }
            return null;
        } catch (Exception e) {
            // Error de red, símbolo inválido, etc. — se loguea y retorna null
            Log.debug(TAG, "Error fetching price for " + symbol + ": " + e.getMessage());
            return null;
        }
    }
}
