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
 * RepricingEngine - Mantiene precios maker para ordenes LIMIT.
 *
 * Responsabilidades:
 * - Calcular mejor precio maker (bid para SELL, ask para BUY)
 * - Ajustar precios a tick size
 * - Detectar cuando es necesario re-price una orden
 * - Coordinar cancelacion y reemplazo de ordenes
 *
 * Backward compatible: puede coexistir con OrderExecutor
 */
public class RepricingEngine {
    private static final String TAG = "Reprice";

    private final BinanceApiClient apiClient;
    private final ConcurrentHashMap<String, Ticker> priceMap;

    private final double repriceThresholdPct;
    private final long minRepriceIntervalMs;
    private final long maxRepriceAttempts;

    private final ConcurrentHashMap<String, Long> lastRepriceTime;
    private final ConcurrentHashMap<String, AtomicInteger> repriceCount;
    private final ConcurrentLinkedQueue<RepriceTask> repriceQueue;

    public RepricingEngine(BinanceApiClient apiClient, ConcurrentHashMap<String, Ticker> priceMap) {
        this(apiClient, priceMap, 0.1, 500, 3);
    }

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
    public double calculateMakerPrice(String symbol, String side, double basePrice) {
        Ticker ticker = priceMap.get(symbol);

        double makerPrice;
        if (ticker != null && ticker.getBidPrice() > 0 && ticker.getAskPrice() > 0) {
            if ("SELL".equalsIgnoreCase(side)) {
                makerPrice = ticker.getBidPrice();
            } else {
                makerPrice = ticker.getAskPrice();
            }

            makerPrice = apiClient.adjustPriceToTickSize(symbol, makerPrice);

            Log.debug(TAG, "Maker price for " + symbol + " " + side + ": " + makerPrice
                + " (bid=" + ticker.getBidPrice() + " ask=" + ticker.getAskPrice() + ")");
        } else {
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
    public boolean shouldReprice(String symbol, String side, double currentOrderPrice, double basePrice) {
        long now = System.currentTimeMillis();

        Long lastTime = lastRepriceTime.get(symbol);
        if (lastTime != null && (now - lastTime) < minRepriceIntervalMs) {
            Log.debug(TAG, "Skipping reprice for " + symbol + ": too soon since last reprice");
            return false;
        }

        AtomicInteger count = repriceCount.get(symbol);
        if (count != null && count.get() >= maxRepriceAttempts) {
            Log.debug(TAG, "Skipping reprice for " + symbol + ": max attempts reached");
            return false;
        }

        Ticker ticker = priceMap.get(symbol);
        if (ticker == null) {
            return false;
        }

        double optimalPrice = calculateMakerPrice(symbol, side, basePrice);

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
    public void recordReprice(String symbol) {
        lastRepriceTime.put(symbol, System.currentTimeMillis());
        repriceCount.computeIfAbsent(symbol, k -> new AtomicInteger(0)).incrementAndGet();
    }

    /**
     * Resetea el contador de reprices para un simbolo.
     * Llamar cuando se llena la orden.
     */
    public void resetRepriceCount(String symbol) {
        repriceCount.remove(symbol);
        lastRepriceTime.remove(symbol);
    }

    /**
     * Obtiene el estado actual del repricing para un simbolo.
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
     * Verifica si el libro de ordenes tiene suficiente profundidad.
     *
     * @param symbol Simbolo
     * @param minQty Cantidad minima المطلوبة
     * @return true si hay suficiente profundidad
     */
    public boolean hasBookDepth(String symbol, double minQty) {
        Ticker ticker = priceMap.get(symbol);
        if (ticker == null) {
            return false;
        }

        double bidQty = ticker.getBidQty();
        double askQty = ticker.getAskQty();

        return (bidQty >= minQty || askQty >= minQty);
    }

    /**
     * Obtiene la profundidad del book para un simbolo.
     */
    public BookDepth getBookDepth(String symbol) {
        Ticker ticker = priceMap.get(symbol);
        if (ticker == null) {
            return null;
        }

        return BookDepth.builder()
            .symbol(symbol)
            .bidPrice(ticker.getBidPrice())
            .bidQty(ticker.getBidQty())
            .askPrice(ticker.getAskPrice())
            .askQty(ticker.getAskQty())
            .spread(calculateSpread(ticker))
            .spreadPct(calculateSpreadPct(ticker))
            .timestamp(System.currentTimeMillis())
            .build();
    }

    private double calculateSpread(Ticker ticker) {
        if (ticker.getAskPrice() <= 0 || ticker.getBidPrice() <= 0) {
            return 0;
        }
        return ticker.getAskPrice() - ticker.getBidPrice();
    }

    private double calculateSpreadPct(Ticker ticker) {
        double mid = (ticker.getAskPrice() + ticker.getBidPrice()) / 2.0;
        if (mid <= 0) {
            return 0;
        }
        return ((ticker.getAskPrice() - ticker.getBidPrice()) / mid) * 100.0;
    }

    /**
     * Calcula slippage estimado basado en el book.
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

        if (qty <= availableQty) {
            return 0.0;
        }

        double slippagePct = 0.1;

        double ratio = qty / availableQty;
        if (ratio > 1.0) {
            slippagePct = Math.min(5.0, (ratio - 1.0) * 0.5);
        }

        return slippagePct;
    }

    public void resetAll() {
        lastRepriceTime.clear();
        repriceCount.clear();
        repriceQueue.clear();
        Log.info(TAG, "RepricingEngine reset");
    }

    @Data
    @Builder
    public static class RepriceStatus {
        private String symbol;
        private int repriceCount;
        private long lastRepriceTime;
        private boolean shouldReprice;
        private long timestamp;
    }

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