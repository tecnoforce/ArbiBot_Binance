package com.arbitrage.module;

import com.arbitrage.model.Ticker;
import com.arbitrage.trading.BinanceApiClient;
import com.arbitrage.util.Log;
import lombok.Builder;
import lombok.Data;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * RepricingEngine - Gestion de precios maker para ordenes LIMIT.
 *
 * Responsabilidades:
 * - Calcular el mejor precio maker para cada orden (bid para SELL, ask para BUY)
 * - Ajustar precios al tick size del simbolo en Binance
 * - Detectar cuando una orden necesita re-pricing (precio de mercado se aleja)
 * - Limitar reintentos de reprice por simbolo (evitar cancelar/reenviar en loop)
 * - Estimar slippage basado en profundidad del order book
 * - Proveer metricas de profundidad (spread, cantidad disponible)
 *
 * Cuando se activa un reprice:
 *   |precioActual - precioOptimo| > precioActual * (threshold / 100)
 *   Y no se ha superado el maximo de intentos
 *   Y ha pasado suficiente tiempo desde el ultimo reprice
 *
 * Integracion: Usado por ExecutionEngine.executeRealSequence() para calcular
 *              precios maker antes de enviar ordenes LIMIT a Binance.
 *              Lee del priceMap compartido para obtener bid/ask actualizados.
 *
 * Backward compatible: puede coexistir con OrderExecutor original sin cambios.
 */
public class RepricingEngine {
    /** Tag para logging */
    private static final String TAG = "Reprice";

    /** Cliente REST de Binance (para ajuste a tick size) */
    private final BinanceApiClient apiClient;
    /** Mapa compartido de precios en tiempo real */
    private final ConcurrentHashMap<String, Ticker> priceMap;

    /** Umbral porcentual para determinar si se necesita reprice (ej: 0.1 = 0.1%) */
    private final double repriceThresholdPct;
    /** Intervalo mínimo entre reprices del mismo símbolo (ms) */
    private final long minRepriceIntervalMs;
    /** Número máximo de intentos de reprice por símbolo */
    private final long maxRepriceAttempts;

    /** Timestamp del último reprice por símbolo */
    private final ConcurrentHashMap<String, Long> lastRepriceTime;
    /** Contador de reprices realizados por símbolo */
    private final ConcurrentHashMap<String, AtomicInteger> repriceCount;
    /** Cola de tareas de reprice pendientes */
    private final ConcurrentLinkedQueue<RepriceTask> repriceQueue;

    /**
     * Constructor con valores por defecto: threshold 0.1%, intervalo 500ms, max 3 intentos.
     */
    public RepricingEngine(BinanceApiClient apiClient, ConcurrentHashMap<String, Ticker> priceMap) {
        this(apiClient, priceMap, 0.1, 500, 3);
    }

    /**
     * Constructor completo del motor de re-pricing.
     *
     * @param apiClient    Cliente REST de Binance (para ajuste a tick size)
     * @param priceMap     Mapa compartido de precios
     * @param threshold    Umbral de diferencia porcentual para activar reprice
     * @param minInterval  Intervalo mínimo entre reprices del mismo símbolo (ms)
     * @param maxAttempts  Número máximo de intentos de reprice por símbolo
     */
    public RepricingEngine(BinanceApiClient apiClient, ConcurrentHashMap<String, Ticker> priceMap,
                           double threshold, long minInterval, int maxAttempts) {
        this.apiClient = apiClient;
        this.priceMap = priceMap;
        this.repriceThresholdPct = threshold;
        this.minRepriceIntervalMs = minInterval;
        this.maxRepriceAttempts = maxAttempts;
        this.lastRepriceTime = new ConcurrentHashMap<>();
        this.repriceCount = new ConcurrentHashMap<>();
        this.repriceQueue = new ConcurrentLinkedQueue<>();
    }

    /**
     * Calcula el mejor precio maker para una orden.
     * SELL → usa bid price (mejor precio de venta)
     * BUY → usa ask price (mejor precio de compra)
     *
     * @param symbol Simbolo (ej: "BTCUSDT")
     * @param side "BUY" o "SELL"
     * @param basePrice Precio calculado original
     * @return Mejor precio maker ajustado a tick size
     */
    /**
     * Calcula el mejor precio maker para una orden LIMIT.
     *
     * Estrategia:
     * - SELL: usa el bid price (mejor precio de venta disponible)
     * - BUY: usa el ask price (mejor precio de compra disponible)
     * - Si no hay ticker disponible, usa el precio base calculado originalmente
     * - Siempre ajusta al tick size del símbolo
     *
     * @param symbol   Símbolo (ej: "BTCUSDT")
     * @param side     "BUY" o "SELL"
     * @param basePrice Precio calculado original (fallback si no hay ticker)
     * @return Precio maker ajustado a tick size
     */
    public double calculateMakerPrice(String symbol, String side, double basePrice) {
        Ticker ticker = priceMap.get(symbol);

        double makerPrice;
        if (ticker != null && ticker.getBidPrice() > 0 && ticker.getAskPrice() > 0) {
            // Usar precio del libro de órdenes según el lado
            if ("SELL".equalsIgnoreCase(side)) {
                makerPrice = ticker.getBidPrice();  // Vender al bid (precio de compra del mercado)
            } else {
                makerPrice = ticker.getAskPrice();  // Comprar al ask (precio de venta del mercado)
            }

            // Ajustar al tick size permitido por Binance
            makerPrice = apiClient.adjustPriceToTickSize(symbol, makerPrice);

            Log.debug(TAG, "Maker price for " + symbol + " " + side + ": " + makerPrice
                + " (bid=" + ticker.getBidPrice() + " ask=" + ticker.getAskPrice() + ")");
        } else {
            // Fallback: usar precio base si no hay datos del book
            makerPrice = apiClient.adjustPriceToTickSize(symbol, basePrice);
            Log.debug(TAG, "No ticker for " + symbol + ", using base price: " + makerPrice);
        }

        return makerPrice;
    }

    /**
     * Determina si una orden necesita re-pricing.
     *
     * @param symbol Simbolo
     * @param side BUY/SELL
     * @param currentOrderPrice Precio de la orden actual
     * @param basePrice Precio original calculado
     * @return true si necesita re-price
     */
    /**
     * Determina si una orden necesita re-pricing basado en el precio actual del mercado.
     *
     * Condiciones para reprice:
     *   1. Ha pasado suficiente tiempo desde el último reprice (minRepriceIntervalMs)
     *   2. No se ha superado el máximo de intentos (maxRepriceAttempts)
     *   3. Hay datos de precio disponibles en el book
     *   4. |precioOptimo - precioActual| > precioActual * (threshold / 100)
     *
     * @param symbol            Símbolo
     * @param side              "BUY" o "SELL"
     * @param currentOrderPrice Precio actual de la orden en el book
     * @param basePrice         Precio base calculado originalmente
     * @return true si la orden necesita ser re-priceda
     */
    public boolean shouldReprice(String symbol, String side, double currentOrderPrice, double basePrice) {
        long now = System.currentTimeMillis();

        // Verificar cooldown: no repricear demasiado seguido
        Long lastTime = lastRepriceTime.get(symbol);
        if (lastTime != null && (now - lastTime) < minRepriceIntervalMs) {
            Log.debug(TAG, "Skipping reprice for " + symbol + ": too soon since last reprice");
            return false;
        }

        // Verificar límite de intentos
        AtomicInteger count = repriceCount.get(symbol);
        if (count != null && count.get() >= maxRepriceAttempts) {
            Log.debug(TAG, "Skipping reprice for " + symbol + ": max attempts reached");
            return false;
        }

        // Obtener precio óptimo actual del mercado
        Ticker ticker = priceMap.get(symbol);
        if (ticker == null) {
            return false;
        }

        double optimalPrice = calculateMakerPrice(symbol, side, basePrice);

        // Calcular diferencia y umbral
        double diff = Math.abs(optimalPrice - currentOrderPrice);
        double threshold = currentOrderPrice * (repriceThresholdPct / 100.0);

        boolean should = diff > threshold;

        if (should) {
            Log.debug(TAG, "Reprice check " + symbol + ": current=" + currentOrderPrice
                + " optimal=" + optimalPrice + " diff=" + String.format("%.4f", diff)
                + " threshold=" + String.format("%.4f", threshold) + " → " + (should ? "REPRICE" : "KEEP"));
        }

        return should;
    }

    /**
     * Registra que se hizo un reprice para un simbolo.
     */
    /**
     * Registra que se realizó un reprice para un símbolo.
     * Actualiza timestamp y contador de intentos.
     *
     * @param symbol Símbolo que fue re-pricedo
     */
    public void recordReprice(String symbol) {
        lastRepriceTime.put(symbol, System.currentTimeMillis());
        repriceCount.computeIfAbsent(symbol, k -> new AtomicInteger(0)).incrementAndGet();
    }

    /**
     * Resetea el contador de reprices para un símbolo.
     * Llamar cuando la orden se llena exitosamente o se cancela.
     *
     * @param symbol Símbolo a resetear
     */
    public void resetRepriceCount(String symbol) {
        repriceCount.remove(symbol);
        lastRepriceTime.remove(symbol);
    }

    /**
     * Obtiene el estado actual de repricing para un símbolo.
     * Incluye: contador de reprices, último timestamp, si debería repricear ahora.
     *
     * @param symbol Símbolo a consultar
     * @return RepriceStatus con datos del estado
     */
    public RepriceStatus getStatus(String symbol) {
        AtomicInteger count = repriceCount.get(symbol);
        Long lastTime = lastRepriceTime.get(symbol);

        return RepriceStatus.builder()
            .symbol(symbol)
            .repriceCount(count != null ? count.get() : 0)
            .lastRepriceTime(lastTime != null ? lastTime : 0)
            .shouldReprice(shouldReprice(symbol, "BUY",
                priceMap.get(symbol) != null ? priceMap.get(symbol).getAskPrice() : 0,
                priceMap.get(symbol) != null ? priceMap.get(symbol).getAskPrice() : 0))
            .timestamp(System.currentTimeMillis())
            .build();
    }

    /**
     * Estima el slippage para una orden basado en la profundidad actual del book.
     * Si qty <= availableQty → 0% (sin impacto en precio)
     * Si qty > availableQty → (ratio - 1) * 0.5, máximo 5%
     *
     * @param symbol Símbolo
     * @param side   "BUY" o "SELL"
     * @param qty    Cantidad a operar
     * @return Porcentaje de slippage estimado
     */
    public double estimateSlippage(String symbol, String side, double qty) {
        Ticker ticker = priceMap.get(symbol);
        if (ticker == null) {
            return 0.0;
        }

        double price = "SELL".equalsIgnoreCase(side) ? ticker.getBidPrice() : ticker.getAskPrice();
        double availableQty = "SELL".equalsIgnoreCase(side) ? ticker.getBidQty() : ticker.getAskQty();

        if (price <= 0 || availableQty <= 0) {
            return 0.0;
        }

        // Si la cantidad cabe en el primer nivel, no hay slippage
        if (qty <= availableQty) {
            return 0.0;
        }

        // Slippage progresivo: a mayor exceso, mayor impacto en precio
        double slippagePct = 0.1;

        double ratio = qty / availableQty;
        if (ratio > 1.0) {
            slippagePct = Math.min(5.0, (ratio - 1.0) * 0.5);
        }

        return slippagePct;
    }

    /**
     * Resetea todos los contadores y colas de repricing.
     */
    public void resetAll() {
        lastRepriceTime.clear();
        repriceCount.clear();
        repriceQueue.clear();
        Log.info(TAG, "RepricingEngine reset");
    }

    /**
     * Estado actual de repricing para un símbolo.
     * symbol: símbolo consultado
     * repriceCount: número de reprices realizados
     * lastRepriceTime: timestamp del último reprice
     * shouldReprice: si actualmente debería repricearse
     * timestamp: momento de la consulta
     */
    @Data
    @Builder
    public static class RepriceStatus {
        private String symbol;
        private int repriceCount;
        private long lastRepriceTime;
        private boolean shouldReprice;
        private long timestamp;
    }

    /**
     * Profundidad del libro de órdenes para un símbolo.
     * bidPrice/askPrice: mejores precios
     * bidQty/askQty: cantidad disponible en el mejor nivel
     * spread: diferencia absoluta (ask - bid)
     * spreadPct: spread como porcentaje del precio medio
     */
    @Data
    @Builder
    public static class BookDepth {
        private String symbol;
        private double bidPrice;
        private double bidQty;
        private double askPrice;
        private double askQty;
        private double spread;
        private double spreadPct;
        private long timestamp;
    }

    /**
     * Tarea de reprice pendiente en la cola.
     * symbol: símbolo a repricear
     * orderId: ID de la orden en Binance
     * oldPrice: precio anterior
     * newPrice: nuevo precio calculado
     * timestamp: momento de creación de la tarea
     */
    @Data
    public static class RepriceTask {
        private String symbol;
        private String orderId;
        private double oldPrice;
        private double newPrice;
        private long timestamp;

        public RepriceTask(String symbol, String orderId, double oldPrice, double newPrice) {
            this.symbol = symbol;
            this.orderId = orderId;
            this.oldPrice = oldPrice;
            this.newPrice = newPrice;
            this.timestamp = System.currentTimeMillis();
        }
    }
}