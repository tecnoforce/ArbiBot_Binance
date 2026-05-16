package com.arbitrage.module;

import com.arbitrage.config.AppConfig;
import com.arbitrage.model.*;
import com.arbitrage.trading.BinanceApiClient;
import com.arbitrage.persistence.SequenceFileManager;
import com.arbitrage.util.Log;
import com.arbitrage.util.StatsManager;
import lombok.Builder;

import lombok.Data;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * ExecutionEngine - Orquestacion de ejecucion de ordenes de arbitraje.
 *
 * Responsabilidades:
 * - Coordinar todos los modulos para ejecutar una oportunidad de arbitraje
 * - Ejecutar secuencias de 3 pasos (comprar -> convertir -> vender)
 * - Validar con RiskManager antes de cada ejecucion
 * - Usar RepricingEngine para ajustar precios maker en ordenes LIMIT
 * - Monitorear fills con FillTracker (estado de cada orden)
 * - Persistir secuencias en JSON mediante SequenceFileManager
 * - Soportar ejecucion real (REST API) y simulada
 *
 * Flujo por oportunidad:
 *   1. Validar limite de trades abiertos y RiskManager
 *   2. Crear TradingSequence y persistir
 *   3. Ajustar cantidades/precios a lot size / tick size
 *   4. Ejecutar OP1 (BUY), monitorear hasta FILLED
 *   5. Recalcular cadena con cantidad ejecutada real
 *   6. Ejecutar OP2 (BUY/SELL), monitorear hasta FILLED
 *   7. Ejecutar OP3 (SELL), monitorear hasta FILLED
 *   8. Calcular profit realizado y persistir resultado
 *
 * Backward compatible: coexiste con OrderExecutor original.
 */
public class ExecutionEngine {
    /** Tag para logging */
    private static final String TAG = "ExecEngine";

    /** Buffer de seguridad para evitar errores de insufficient funds.
     * Usamos 95% del balance para contemplar fees, truncamiento y slippage.
     * Recomendacion del chat: buffer del 20%-100%, usamos 95% para estabilidad. */
    private static final double SAFETY_BUFFER_FACTOR = 0.95;

    /** Interfaz para mostrar estado de secuencias en UI */
    public interface SequenceDisplay {
        void showStart(int sequenceId, long timestamp, double profitPct, boolean live);
        void showOrderPending(int seqId, int opNum, String symbol, String side, double qty, double price, String orderType);
        void showOrderFilled(int seqId, int opNum, com.arbitrage.model.OrderResult r);
        void showOrderStatus(int seqId, int opNum, String symbol, String side, double qty, double price,
                            String status, long elapsedMs, String orderId, String orderType);
        void showSequenceEstado(int seqId, String estado);
        void showEnd(int sequenceId, boolean success, double profitPct);
        void printSequenceAtomic(int sequenceId, long timestamp, boolean live,
                                java.util.List<com.arbitrage.model.OrderResult> orders, double profitPct);
    }

    /** Interfaz para mostrar oportunidades detectadas en la UI */
    public interface OpportunityDisplay {
        void show(int orderId, long timestamp,
                 com.arbitrage.model.OrderResult op1, com.arbitrage.model.OrderResult op2, com.arbitrage.model.OrderResult op3,
                 double profitPct, boolean live);
    }

    /** Configuración global de la aplicación */
    private final AppConfig config;
    /** Cliente REST de la API de Binance */
    private final BinanceApiClient apiClient;
    /** Manejador de persistencia JSON */
    private final SequenceFileManager fileManager;

    /** Módulo de control de riesgo */
    private final RiskManager riskManager;
    /** Módulo de ajuste de precios maker */
    private final RepricingEngine repricingEngine;
    /** Módulo de tracking de fills */
    private final FillTracker fillTracker;
    /** Módulo de estadísticas persistentes */
    private final StatsManager statsManager;

    /** Display para mostrar estado de secuencias */
    private SequenceDisplay sequenceDisplay;

    /** Executor para tareas de ejecución (virtual threads) */
    private final ExecutorService executor;
    /** Scheduler para polling de estado de órdenes */
    private final ScheduledExecutorService pollingExecutor;
    /** Contador secuencial de IDs de secuencia */
    private final AtomicInteger sequenceCounter;
    /** Contador de trades actualmente abiertos */
    private final AtomicInteger openTrades;

    /** Mapa compartido de precios en tiempo real */
    private final ConcurrentHashMap<String, Ticker> priceMap;
    /** Timestamp del último warning de límite alcanzado (control de spam) */
    private volatile long lastWarningTime = 0;
    /** Intervalo mínimo entre warnings de límite (ms) */
    private static final long WARNING_INTERVAL_MS = 5000;

    /** Timestamp de la última alerta de BNB bajo (control de spam) */
    private volatile long lastBNBAlertTime = 0;
    /** Intervalo mínimo entre alertas de BNB bajo (ms) */
    private static final long BNB_ALERT_INTERVAL_MS = 60000;

    /**
     * Inyecta la interfaz de display para actualizar la UI de consola.
     */
    public void setSequenceDisplay(SequenceDisplay display) {
        this.sequenceDisplay = display;
    }

    /**
     * Constructor del motor de ejecución.
     * Inicializa todos los submódulos y los pools de threads.
     *
     * @param config          Configuración global
     * @param apiClient       Cliente REST de Binance
     * @param fileManager     Manejador de persistencia JSON
     * @param priceMap        Mapa compartido de precios
     * @param riskManager     Módulo de control de riesgo
     * @param repricingEngine Módulo de ajuste de precios maker
     * @param fillTracker     Módulo de tracking de fills
     */
    public ExecutionEngine(
            AppConfig config,
            BinanceApiClient apiClient,
            SequenceFileManager fileManager,
            ConcurrentHashMap<String, Ticker> priceMap,
            RiskManager riskManager,
            RepricingEngine repricingEngine,
            FillTracker fillTracker,
            StatsManager statsManager
    ) {
        this.config = config;
        this.apiClient = apiClient;
        this.fileManager = fileManager;
        this.priceMap = priceMap;
        this.riskManager = riskManager;
        this.repricingEngine = repricingEngine;
        this.fillTracker = fillTracker;
        this.statsManager = statsManager;

        // Use virtual threads for I/O-bound execution tasks
        this.executor = Executors.newVirtualThreadPerTaskExecutor();
        // Pool para polling de estado de órdenes (hasta 4 consultas simultáneas)
        this.pollingExecutor = Executors.newScheduledThreadPool(4);
        this.sequenceCounter = new AtomicInteger(1);
        this.openTrades = new AtomicInteger(0);
    }

    /**
     * Punto de entrada para ejecutar una oportunidad de arbitraje real.
     *
     * Validaciones pre-ejecución:
     *   1. Límite de trades abiertos (maxOpenTrades)
     *   2. RiskManager.validate() (exposición, pérdida diaria, emergency stop)
     *
     * Si pasa validaciones, asigna un seqId único y lanza la ejecución
     * en un virtual thread, decrementando openTrades al finalizar.
     *
     * @param opportunity Oportunidad de arbitraje a ejecutar
     */
    public void execute(ArbitrageOpportunity opportunity) {
        // Verificar límite de trades abiertos
        if (openTrades.get() >= config.getMaxOpenTrades()) {
            long now = System.currentTimeMillis();
            if (now - lastWarningTime > WARNING_INTERVAL_MS) {
                Log.debug(TAG, "Max open trades reached. Skipping.");
                lastWarningTime = now;
            }
            return;
        }

        // Validar con RiskManager
        if (riskManager != null) {
            RiskManager.ValidationResult validation = riskManager.validate(opportunity);
            if (!validation.isApproved()) {
                if (Log.isDebugEnabled()) {
                    Log.debug(TAG, "RiskManager rejected: " + validation.getViolations());
                }
                return;
            }
        }

        // Verificar balance de BNB para descuento de comisiones
        checkAndAlertBNBBalance();

        // Asignar ID de secuencia y marcar trade como abierto
        int seqIdNum = sequenceCounter.getAndIncrement();
        openTrades.incrementAndGet();

        // Ejecutar en virtual thread (no bloquea el hilo principal)
        executor.submit(() -> {
            try {
                executeRealSequence(opportunity, seqIdNum);
            } catch (Exception e) {
                Log.error(TAG, "Execution error: " + e.getMessage());
            } finally {
                openTrades.decrementAndGet();
            }
        });
    }

    /**
     * Ejecuta una simulación de oportunidad (sin llamar a la API real).
     * Útil para testing y validación de la lógica sin arriesgar capital.
     * Simula delays aleatorios para emular latencia de red.
     *
     * @param opportunity Oportunidad a simular
     */
    public void executeSimulated(ArbitrageOpportunity opportunity) {
        int seqIdNum = sequenceCounter.getAndIncrement();
        openTrades.incrementAndGet();

        executor.submit(() -> {
            try {
                executeSimulatedSequence(opportunity, seqIdNum);
            } catch (Exception e) {
                Log.error(TAG, "Simulation error: " + e.getMessage());
            } finally {
                openTrades.decrementAndGet();
            }
        });
    }

    /**
     * Ejecuta OP1 (compra del primer activo) para una secuencia dada.
     *
     * @param sequence Secuencia de trading
     * @param symbol1  Símbolo de OP1
     * @param side1    Lado de OP1 (siempre BUY)
     * @param orderType1 Tipo de orden
     * @param adjQty1  Cantidad ajustada
     * @param adjPrice1 Precio ajustado
     * @param seqId    ID de secuencia para logging
     * @return OrderResult con el resultado de la ejecución
     */
    private OrderResult executeOp1(TradingSequence sequence, String symbol1, String side1,
                                    String orderType1, double adjQty1, double adjPrice1, int seqId) throws Exception {
        long op1StartTime = System.currentTimeMillis();
        SequenceOrder order1 = sequence.getOp1();

        order1.setOrderStatus(EstadoOrden.PENDING);
        order1.setTimestampCreacion(System.currentTimeMillis());
        if (fileManager != null) fileManager.updateOrderByMiniId(sequence.getMiniId(), 1, order1);

        if (fillTracker != null) {
            fillTracker.trackOrderSent(seqId, 1, symbol1, side1, adjQty1);
        }

        double makerPrice1 = adjPrice1;
        if (repricingEngine != null && "LIMIT".equalsIgnoreCase(orderType1)) {
            makerPrice1 = repricingEngine.calculateMakerPrice(symbol1, side1, adjPrice1);
        }

        double notional1 = adjQty1 * makerPrice1;
        double minNotional1 = apiClient.getMinNotionalOrZero(symbol1);
        if (minNotional1 > 0 && notional1 < minNotional1) {
            throw new RuntimeException("OP1 notional too low: " + String.format("%.2f", notional1) + " < " + String.format("%.2f", minNotional1) + " USDT for " + symbol1);
        }

        OrderResult result1 = placeAndMonitorOrder(seqId, symbol1, side1, orderType1, adjQty1, makerPrice1, 1);
        long op1Elapsed = System.currentTimeMillis() - op1StartTime;

        if (sequenceDisplay != null) {
            sequenceDisplay.showOrderStatus(seqId, 1, symbol1, side1, result1.getExecutedQty(), result1.getPrice(),
                result1.getStatus(), op1Elapsed, result1.getOrderId(), orderType1);
        }

        if (fillTracker != null && "FILLED".equals(result1.getStatus())) {
            fillTracker.trackOrderFilled(seqId, 1, result1);
        }

        if (!"FILLED".equals(result1.getStatus())) {
            throw new RuntimeException("OP1 not filled: " + result1.getStatus());
        }

        order1.setOrderStatus(EstadoOrden.FILLED);
        order1.setCantidadEjecutada(result1.getExecutedQty());
        order1.setPrecioEjecutado(result1.getPrice());
        order1.setBinanceOrderId(result1.getOrderId());
        if (fileManager != null) fileManager.updateOrderByMiniId(sequence.getMiniId(), 1, order1);

        return result1;
    }

    /**
     * Ejecuta OP2 (conversión al segundo activo) para una secuencia dada.
     *
     * @param sequence Secuencia de trading
     * @param symbol2  Símbolo de OP2
     * @param side2    Lado de OP2 (BUY o SELL)
     * @param orderType2 Tipo de orden
     * @param adjQty2  Cantidad ajustada
     * @param adjPrice2 Precio ajustado
     * @param seqId    ID de secuencia para logging
     * @return OrderResult con el resultado de la ejecución
     */
    private OrderResult executeOp2(TradingSequence sequence, String symbol2, String side2,
                                    String orderType2, double adjQty2, double adjPrice2, int seqId) throws Exception {
        long op2StartTime = System.currentTimeMillis();
        SequenceOrder order2 = sequence.getOp2();

        if (sequenceDisplay != null) {
            sequenceDisplay.showOrderStatus(seqId, 2, symbol2, side2, adjQty2, adjPrice2, "OPENED", 0, "------", orderType2);
        }

        order2.setQuantity(adjQty2);
        order2.setOrderStatus(EstadoOrden.PENDING);
        order2.setTimestampCreacion(System.currentTimeMillis());
        if (fileManager != null) fileManager.updateOrderByMiniId(sequence.getMiniId(), 2, order2);

        if (fillTracker != null) {
            fillTracker.trackOrderSent(seqId, 2, symbol2, side2, adjQty2);
        }

        double makerPrice2 = adjPrice2;
        if (repricingEngine != null && "LIMIT".equalsIgnoreCase(orderType2)) {
            makerPrice2 = repricingEngine.calculateMakerPrice(symbol2, side2, adjPrice2);
        }

        double notional2 = adjQty2 * makerPrice2;
        double minNotional2 = apiClient.getMinNotionalOrZero(symbol2);
        if (makerPrice2 <= 0.0) {
            throw new RuntimeException("OP2 price is 0 for " + symbol2 + ", cannot place order");
        }
        if (minNotional2 > 0 && notional2 < minNotional2) {
            throw new RuntimeException("OP2 notional too low: " + String.format("%.4f", notional2) + " < " + String.format("%.2f", minNotional2) + " USDT for " + symbol2);
        }

        OrderResult result2 = placeAndMonitorOrder(seqId, symbol2, side2, orderType2, adjQty2, makerPrice2, 2);
        long op2Elapsed = System.currentTimeMillis() - op2StartTime;

        if (sequenceDisplay != null) {
            sequenceDisplay.showOrderStatus(seqId, 2, symbol2, side2, result2.getExecutedQty(), result2.getPrice(),
                result2.getStatus(), op2Elapsed, result2.getOrderId(), orderType2);
        }

        if (fillTracker != null && "FILLED".equals(result2.getStatus())) {
            fillTracker.trackOrderFilled(seqId, 2, result2);
        }

        if (!"FILLED".equals(result2.getStatus())) {
            throw new RuntimeException("OP2 not filled: " + result2.getStatus());
        }

        order2.setOrderStatus(EstadoOrden.FILLED);
        order2.setCantidadEjecutada(result2.getExecutedQty());
        order2.setPrecioEjecutado(result2.getPrice());
        order2.setBinanceOrderId(result2.getOrderId());
        if (fileManager != null) fileManager.updateOrderByMiniId(sequence.getMiniId(), 2, order2);

        return result2;
    }

    /**
     * Ejecuta OP3 (venta del activo final para volver a USDT) para una secuencia dada.
     *
     * @param sequence Secuencia de trading
     * @param symbol3  Símbolo de OP3
     * @param side3    Lado de OP3 (siempre SELL)
     * @param orderType3 Tipo de orden
     * @param adjQty3  Cantidad ajustada
     * @param adjPrice3 Precio ajustado
     * @param seqId    ID de secuencia para logging
     * @return OrderResult con el resultado de la ejecución
     */
    private OrderResult executeOp3(TradingSequence sequence, String symbol3, String side3,
                                    String orderType3, double adjQty3, double adjPrice3, int seqId) throws Exception {
        long op3StartTime = System.currentTimeMillis();
        SequenceOrder order3 = sequence.getOp3();

        if (sequenceDisplay != null) {
            sequenceDisplay.showOrderStatus(seqId, 3, symbol3, side3, adjQty3, adjPrice3, "OPENED", 0, "------", orderType3);
        }

        order3.setQuantity(adjQty3);
        order3.setOrderStatus(EstadoOrden.PENDING);
        order3.setTimestampCreacion(System.currentTimeMillis());
        if (fileManager != null) fileManager.updateOrderByMiniId(sequence.getMiniId(), 3, order3);

        if (fillTracker != null) {
            fillTracker.trackOrderSent(seqId, 3, symbol3, side3, adjQty3);
        }

        double makerPrice3 = adjPrice3;
        if (repricingEngine != null && "LIMIT".equalsIgnoreCase(orderType3)) {
            makerPrice3 = repricingEngine.calculateMakerPrice(symbol3, side3, adjPrice3);
        }

        double notional3 = adjQty3 * makerPrice3;
        double minNotional3 = apiClient.getMinNotionalOrZero(symbol3);
        if (makerPrice3 <= 0.0) {
            throw new RuntimeException("OP3 price is 0 for " + symbol3 + ", cannot place order");
        }
        if (minNotional3 > 0 && notional3 < minNotional3) {
            throw new RuntimeException("OP3 notional too low: " + String.format("%.4f", notional3) + " < " + String.format("%.2f", minNotional3) + " USDT for " + symbol3);
        }

        OrderResult result3 = placeAndMonitorOrder(seqId, symbol3, side3, orderType3, adjQty3, makerPrice3, 3);
        long op3Elapsed = System.currentTimeMillis() - op3StartTime;

        if (sequenceDisplay != null) {
            sequenceDisplay.showOrderStatus(seqId, 3, symbol3, side3, result3.getExecutedQty(), result3.getPrice(),
                result3.getStatus(), op3Elapsed, result3.getOrderId(), orderType3);
        }

        if (fillTracker != null && "FILLED".equals(result3.getStatus())) {
            fillTracker.trackOrderFilled(seqId, 3, result3);
        }

        if (!"FILLED".equals(result3.getStatus())) {
            throw new RuntimeException("OP3 not filled: " + result3.getStatus());
        }

        order3.setOrderStatus(EstadoOrden.FILLED);
        order3.setCantidadEjecutada(result3.getExecutedQty());
        order3.setPrecioEjecutado(result3.getPrice());
        order3.setBinanceOrderId(result3.getOrderId());
        if (fileManager != null) fileManager.updateOrderByMiniId(sequence.getMiniId(), 3, order3);

        return result3;
    }

    /**
     * Cierra una secuencia calculando el profit realizado y persistiendo el resultado.
     *
     * @param sequence Secuencia de trading
     * @param result3  Resultado de OP3
     * @param seqId    ID de secuencia para logging
     * @param profitPct Profit esperado (para display)
     */
    private void closeSequence(TradingSequence sequence, OrderResult result3, int seqId, double profitPct) {
        double price1Exec = sequence.getOp1().getPrecioEjecutado();
        double price2Exec = sequence.getOp2().getPrecioEjecutado();
        double price3Exec = sequence.getOp3().getPrecioEjecutado();

        boolean isForward = sequence.getOp1().getSymbol().endsWith("USDT");

        double rawProduct;
        if (isForward) {
            rawProduct = (price2Exec * price3Exec) / price1Exec;
        } else {
            rawProduct = price3Exec / (price1Exec * price2Exec);
        }

        double feeOp1 = config.getFeeOp1() / 100.0;
        double feeOp2 = config.getFeeOp2() / 100.0;
        double feeOp3 = config.getFeeOp3() / 100.0;
        double feeFactor = (1.0 - feeOp1) * (1.0 - feeOp2) * (1.0 - feeOp3);

        double profitPctRealizado = (rawProduct * feeFactor - 1.0) * 100.0;

        double balanceInicial = config.getBalancePerTrade() * SAFETY_BUFFER_FACTOR;
        double profitRealizado = balanceInicial * (profitPctRealizado / 100.0);

        Log.debug(TAG, "Sequence #" + seqId + " pricesExec: p1=" + price1Exec + ", p2=" + price2Exec + ", p3=" + price3Exec +
                ", isForward=" + isForward + ", rawProduct=" + rawProduct +
                ", profitPctExpected=" + profitPct + ", profitPctReal=" + profitPctRealizado);

        sequence.close(profitRealizado);

        if (sequenceDisplay != null) {
            sequenceDisplay.showEnd(seqId, true, profitPct);
        }

        if (riskManager != null) {
            riskManager.recordPnL(profitRealizado);
            riskManager.recordClose(sequence.getTriangleId(), result3.getExecutedQty(), profitRealizado);
        }

        if (fileManager != null) {
            try {
                fileManager.deleteByMiniId(sequence.getMiniId());
                fileManager.appendEvent(sequence);
            } catch (java.io.IOException ioe) {
                Log.error(TAG, "Error persisting completed Seq #" + seqId + ": " + ioe.getMessage());
            }
        }

        if (statsManager != null) {
            statsManager.decrementPending();
            statsManager.recordCompleted(profitRealizado);
        }

        Log.debug(TAG, "Sequence #" + seqId + " COMPLETED profit=" + profitRealizado);
    }

    /**
     * Ejecuta una secuencia real de 3 órdenes en Binance.
     *
     * Flujo completo:
     *   1. Crear TradingSequence y persistir
     *   2. Ajustar cantidades a lot size y precios a tick size
     *   3. Verificar balance USDT disponible
     *   4. OP1: BUY del primer activo (MARKET o LIMIT)
     *   5. Recalcular cadena con cantidad ejecutada real
     *   6. OP2: convertir al segundo activo (BUY o SELL según el triángulo)
     *   7. OP3: SELL del activo final para volver a USDT
     *   8. Calcular profit realizado, persistir y notificar a RiskManager
     *
     * @param opportunity Oportunidad a ejecutar
     * @param seqIdNum    ID numérico de la secuencia
     */
    private void executeRealSequence(ArbitrageOpportunity opportunity, int seqIdNum) {
        long startTime = System.currentTimeMillis();
        double profitPct = opportunity.getProfitPct();

        String triangleId = opportunity.getTriangle().getId();
        String symbol1 = opportunity.getTriangle().getSymbol1();
        String symbol2 = opportunity.getTriangle().getSymbol2();
        String symbol3 = opportunity.getTriangle().getSymbol3();
        boolean isForward = opportunity.getTriangle().isForward();

        double qty1 = opportunity.getStep1Qty();
        double qty2 = opportunity.getStep2Qty();
        double qty3 = opportunity.getStep3Qty();

        double price1 = opportunity.getStep1Price();
        double price2 = opportunity.getStep2Price();
        double price3 = opportunity.getStep3Price();

        String side1 = "BUY";
        String side2 = calcSide2(symbol1, symbol2);
        String side3 = "SELL";

        String orderType1 = config.getTypeOp1();
        String orderType2 = config.getTypeOp2();
        String orderType3 = config.getTypeOp3();

        double effectiveBalance = config.getBalancePerTrade() * SAFETY_BUFFER_FACTOR;

        TradingSequence sequence = TradingSequence.create(
            triangleId, "MAINNET", profitPct,
            config.getBaseCurrency(), effectiveBalance
        );

        int seqId = sequence.getSeqId();

        if (fillTracker != null) {
            fillTracker.trackSequenceStart(seqId, opportunity.getTriangle(), profitPct);
        }

        try {
            double adjQty1 = apiClient.adjustQuantityToLotSize(symbol1, qty1);
            double adjQty2 = apiClient.adjustQuantityToLotSize(symbol2, qty2);
            double adjQty3 = apiClient.adjustQuantityToLotSize(symbol3, qty3);

            double adjPrice1 = price1;
            double adjPrice2 = apiClient.adjustPriceToTickSize(symbol2, price2);
            double adjPrice3 = apiClient.adjustPriceToTickSize(symbol3, price3);

            if (adjPrice2 <= 0.0) {
                try {
                    double marketPrice2 = apiClient.getSymbolPrice(symbol2);
                    adjPrice2 = apiClient.adjustPriceToTickSize(symbol2, marketPrice2);
                    Log.info(TAG, "OP2 price was 0, fetched market price for " + symbol2 + ": " + adjPrice2);
                } catch (Exception e) {
                    handleError(seqId, null, "OP2 price is 0 and could not fetch market price for " + symbol2 + ": " + e.getMessage());
                    return;
                }
            }
            if (adjPrice3 <= 0.0) {
                try {
                    double marketPrice3 = apiClient.getSymbolPrice(symbol3);
                    adjPrice3 = apiClient.adjustPriceToTickSize(symbol3, marketPrice3);
                    Log.info(TAG, "OP3 price was 0, fetched market price for " + symbol3 + ": " + adjPrice3);
                } catch (Exception e) {
                    handleError(seqId, null, "OP3 price is 0 and could not fetch market price for " + symbol3 + ": " + e.getMessage());
                    return;
                }
            }

            double availableUsdt = apiClient.getAssetBalance("USDT");
            if (availableUsdt < effectiveBalance) {
                handleError(seqId, null, "Insufficient USDT: " + String.format("%.2f", availableUsdt) + " < " + String.format("%.2f", effectiveBalance) + " (95% of " + config.getBalancePerTrade() + ")");
                return;
            }

            SequenceOrder order1 = SequenceOrder.create(sequence.getMiniId(), seqId, 1, symbol1, side1, orderType1, adjQty1, 0);
            SequenceOrder order2 = SequenceOrder.create(sequence.getMiniId(), seqId, 2, symbol2, side2, orderType2, adjQty2, 0);
            SequenceOrder order3 = SequenceOrder.create(sequence.getMiniId(), seqId, 3, symbol3, side3, orderType3, adjQty3, 0);

            sequence.setOp1(order1);
            sequence.setOp2(order2);
            sequence.setOp3(order3);

            if (fileManager != null) {
                fileManager.insert(sequence);
                Log.info(TAG, "Sequence #" + seqId + " persisted COMPLETE with 3 orders");
            }

            if (statsManager != null) {
                statsManager.incrementPending();
            }

            OrderResult result1 = executeOp1(sequence, symbol1, side1, orderType1, adjQty1, adjPrice1, seqId);

            if (sequenceDisplay != null) {
                sequenceDisplay.showStart(seqId, startTime, profitPct, true);
                sequenceDisplay.showOrderStatus(seqId, 2, symbol2, side2, adjQty2, adjPrice2, "WAITING", -1, "------", orderType2);
                sequenceDisplay.showOrderStatus(seqId, 3, symbol3, side3, adjQty3, adjPrice3, "WAITING", -1, "------", orderType3);
            }

            double executedQty1 = result1.getExecutedQty();
            double[] recalc = recalculateChain(executedQty1, symbol2, symbol3, price2, price3, side2);
            if (recalc != null) {
                adjQty2 = recalc[0];
                adjQty3 = recalc[1];
            }

            String baseAsset2 = apiClient.extractBaseAsset(symbol2);
            double availableBase2 = apiClient.getAssetBalance(baseAsset2);
            double feeOp1Decimal = config.getFeeOp1() / 100.0;
            double minRequired = executedQty1 * (1.0 - feeOp1Decimal) * 1.05;

            if (minRequired > availableBase2) {
                handleError(seqId, sequence.getMiniId(), "Insufficient " + baseAsset2 + " for Op2");
                return;
            }

            OrderResult result2 = executeOp2(sequence, symbol2, side2, orderType2, adjQty2, adjPrice2, seqId);

            double executedQty2 = result2.getExecutedQty();
            double fillPrice2 = result2.getPrice();

            String baseAsset3 = apiClient.extractBaseAsset(symbol3);
            double availableBase3 = apiClient.getAssetBalance(baseAsset3);
            double feeOp2Decimal = config.getFeeOp2() / 100.0;

            if ("BUY".equals(side2)) {
                double afterFee2 = executedQty2 * (1.0 - feeOp2Decimal);
                adjQty3 = apiClient.adjustQuantityToLotSize(symbol3, afterFee2);
            } else {
                double quoteAssetQty = executedQty2 * fillPrice2;
                double afterFee2 = quoteAssetQty * (1.0 - feeOp2Decimal);
                adjQty3 = apiClient.adjustQuantityToLotSize(symbol3, afterFee2);
            }

            if (adjQty3 > availableBase3) {
                adjQty3 = apiClient.adjustQuantityToLotSize(symbol3, availableBase3 * 0.995);
            }

            OrderResult result3 = executeOp3(sequence, symbol3, side3, orderType3, adjQty3, adjPrice3, seqId);

            closeSequence(sequence, result3, seqId, profitPct);

        } catch (Exception e) {
            Log.error(TAG, "Error in sequence #" + seqId + ": " + e.getMessage());
            handleError(seqId, sequence.getMiniId(), e.getMessage());
            if (fillTracker != null) {
                fillTracker.trackSequenceEnd(seqId, false, 0);
            }
        }
    }

    /**
     * Ejecuta una secuencia simulada (sin API real).
     * Simula 3 delays aleatorios (50-70ms c/u) para emular la latencia
     * de las 3 órdenes, y notifica a los módulos de riesgo y tracking.
     *
     * @param opportunity Oportunidad a simular
     * @param seqIdNum    ID de secuencia asignado
     */
    private void executeSimulatedSequence(ArbitrageOpportunity opportunity, int seqIdNum) {
        long startTime = System.currentTimeMillis();
        double profitPct = opportunity.getProfitPct();

        // Extraer datos del triángulo (solo para logging)
        String symbol1 = opportunity.getTriangle().getSymbol1();
        String symbol2 = opportunity.getTriangle().getSymbol2();
        String symbol3 = opportunity.getTriangle().getSymbol3();

        double qty1 = opportunity.getStep1Qty();
        double qty2 = opportunity.getStep2Qty();
        double qty3 = opportunity.getStep3Qty();

        double price1 = opportunity.getStep1Price();
        double price2 = opportunity.getStep2Price();
        double price3 = opportunity.getStep3Price();

        String orderType1 = config.getTypeOp1();
        String orderType2 = config.getTypeOp2();
        String orderType3 = config.getTypeOp3();

        String side1 = "BUY";
        String side2 = calcSide2(symbol1, symbol2);
        String side3 = "SELL";

        try {
            // Simular 3 operaciones con delays aleatorios
            Thread.sleep(50 + new Random().nextInt(20));  // OP1 delay
            Thread.sleep(50 + new Random().nextInt(20));  // OP2 delay
            Thread.sleep(50 + new Random().nextInt(20));  // OP3 delay

            Log.debug(TAG, "Simulated sequence #" + seqIdNum + " completed, profit=" + profitPct + "%");

            // Registrar PnL simulado
            if (riskManager != null) {
                riskManager.recordPnL(config.getBalancePerTrade() * profitPct / 100.0);
            }

            // Notificar fin de secuencia simulada
            if (fillTracker != null) {
                fillTracker.trackSequenceEnd(seqIdNum, true, profitPct);
            }

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Envía una orden a Binance y monitorea su estado hasta que se complete
     * o falle. Realiza polling periódico del estado de la orden.
     *
     * Si config.isRealorder() es false (modo paper trading / simulación),
     * la orden se marca como FILLED inmediatamente sin consultar API.
     *
     * @param seqId    ID de la secuencia
     * @param symbol   Símbolo (ej: "BTCUSDT")
     * @param side     "BUY" o "SELL"
     * @param orderType "MARKET" o "LIMIT"
     * @param qty      Cantidad ajustada a lot size
     * @param price    Precio (para LIMIT) o 0 (para MARKET)
     * @param opIndex  Índice de la operación (1, 2 o 3)
     * @return OrderResult con estado final (FILLED, CANCELED, REJECTED, EXPIRED)
     * @throws Exception si hay error de conexión o timeout
     */
    private OrderResult placeAndMonitorOrder(int seqId, String symbol, String side, String orderType,
                                              double qty, double price, int opIndex) throws Exception {
        long startTime = System.currentTimeMillis();

        // Enviar orden a la API de Binance
        OrderResult placed = apiClient.placeOrder(symbol, side, orderType, qty, price,
            config.getBalancePerTrade(), true);

        if (!placed.isSuccess()) {
            Log.error(TAG, "[Seq:#" + seqId + "] Failed to place order: " + placed.getErrorMessage());
            return placed;
        }

        Log.debug(TAG, "[Seq:#" + seqId + "] OP" + opIndex + " placed: " + symbol + " " + side);

        // Modo paper trading: marcar como FILLED inmediatamente
        if (!config.isRealorder()) {
            placed.setStatus("FILLED");
            placed.setExecutedQty(qty);
            return placed;
        }

        // Modo real: hacer polling hasta que la orden se ejecute o falle
        long pollingInterval = config.getPollingIntervalMs();

        while (true) {
            long elapsed = System.currentTimeMillis() - startTime;

            Thread.sleep(pollingInterval);

            // Consultar estado actual de la orden
            OrderResult status = apiClient.queryOrder(symbol, placed.getOrderId());
            String orderStatus = status.getStatus();

            // Si se llenó, devolver éxito con datos reales de ejecución
            if ("FILLED".equals(orderStatus)) {
                placed.setStatus("FILLED");
                placed.setExecutedQty(status.getExecutedQty());
                placed.setPrice(status.getPrice());
                Log.debug(TAG, "[Seq:#" + seqId + "] OP" + opIndex + " FILLED in " + elapsed + "ms");
                return placed;
            }

            // Si fue cancelada, rechazada o expiró, devolver error
            if ("CANCELED".equals(orderStatus) || "REJECTED".equals(orderStatus) || "EXPIRED".equals(orderStatus)) {
                placed.setStatus(orderStatus);
                return placed;
            }
        }
    }

    /**
     * Calcula el lado de la segunda operación basado en los activos involucrados.
     * Si el activo base de OP2 es el mismo que el activo recibido de OP1,
     * entonces se SELL (se vende el activo recibido). Si es diferente,
     * se BUY (se compra el nuevo activo).
     *
     * Ejemplo: BTCUSDT → ETHBTC (held=BTC, base2=ETH) → BUY ETH con BTC
     * Ejemplo: ETHBTC → ETHUSDT (held=ETH, base2=ETH) → SELL ETH por USDT
     *
     * @param symbol1 Símbolo de OP1 (ej: "BTCUSDT")
     * @param symbol2 Símbolo de OP2 (ej: "ETHBTC")
     * @return "BUY" o "SELL"
     */
    private String calcSide2(String symbol1, String symbol2) {
        String heldAsset = apiClient.extractBaseAsset(symbol1);
        String baseAsset2 = apiClient.extractBaseAsset(symbol2);
        return heldAsset.equals(baseAsset2) ? "SELL" : "BUY";
    }

    /**
     * Recalcula las cantidades de OP2 y OP3 basado en la cantidad real
     * ejecutada en OP1. Aplica comisiones y ajusta a lot size.
     *
     * Fórmula:
     *   afterFee1 = executedQty1 * (1 - feeOp1)
     *   step2Raw  = afterFee1 / price2 (si BUY) o afterFee1 (si SELL)
     *   adjStep2  = step2Raw ajustado a lot size
     *   adjStep3  = afterFee2 ajustado a lot size de symbol3
     *
     * Si la cantidad ajustada es menos del 50% de la raw, retorna null
     * (indica que el ajuste de precisión redujo demasiado la cantidad).
     *
     * @param executedQty1 Cantidad real ejecutada en OP1
     * @param symbol2      Símbolo de OP2
     * @param symbol3      Símbolo de OP3
     * @param price2       Precio de OP2
     * @param price3       Precio de OP3
     * @param side2        Lado de OP2 ("BUY" o "SELL")
     * @return double[]{adjStep2, adjStep3} o null si el ajuste es inviable
     */
    private double[] recalculateChain(double executedQty1, String symbol2, String symbol3,
                                       double price2, double price3, String side2) {
        double feeOp1 = config.getFeeOp1() / 100.0;
        double feeOp2 = config.getFeeOp2() / 100.0;

        try {
            // Validar precios antes de calcular
            if (price2 <= 0.0) {
                Log.warn(TAG, "recalculateChain: price2 is 0 for " + symbol2);
                return null;
            }
            if (price3 <= 0.0) {
                Log.warn(TAG, "recalculateChain: price3 is 0 for " + symbol3);
                return null;
            }

            // Aplicar comisión de OP1 y calcular cantidad para OP2
            double afterFee1 = executedQty1 * (1.0 - feeOp1);
            double step2Raw;
            
            // Calcular step2Raw según el lado de OP2
            if ("SELL".equals(side2)) {
                // SELL: vendemos el asset que tenemos directamente
                step2Raw = afterFee1;
            } else {
                // BUY: convertimos el asset que tenemos al nuevo asset
                step2Raw = afterFee1 / price2;
            }
            
            double adjStep2 = apiClient.adjustQuantityToLotSize(symbol2, step2Raw);
            // Si el ajuste reduce la cantidad a menos del 50%, la operación no es viable
            if (adjStep2 < step2Raw * 0.5) return null;

            // Aplicar comisión de OP2 y ajustar para OP3 según dirección del triángulo
            double adjStep3;
            if ("BUY".equals(side2)) {
                // OP2 es BUY: recibimos el asset base de symbol2 (cantidad directa)
                double afterFee2 = adjStep2 * (1.0 - feeOp2);
                adjStep3 = apiClient.adjustQuantityToLotSize(symbol3, afterFee2);
            } else {
                // OP2 es SELL: recibimos el asset quote de symbol2 (cantidad * precio)
                double quoteAssetQty = adjStep2 * price2;
                double afterFee2 = quoteAssetQty * (1.0 - feeOp2);
                adjStep3 = apiClient.adjustQuantityToLotSize(symbol3, afterFee2);
            }
            if (adjStep3 < step2Raw * 0.5) return null;

            return new double[]{adjStep2, adjStep3};
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Maneja un error durante la ejecución de una secuencia.
     * Persiste la secuencia como cancelada y registra la pérdida
     * del capital comprometido en RiskManager.
     *
     * @param seqId   ID de la secuencia que falló
     * @param message Descripción del error
     */
    private void handleError(int seqId, String miniId, String message) {
        Log.error(TAG, "Sequence error #" + seqId + ": " + message);

        // Mostrar secuencia como cancelada
        if (sequenceDisplay != null) {
            sequenceDisplay.showSequenceEstado(seqId, "CANCELED");
            sequenceDisplay.showEnd(seqId, false, 0.0);
        }

        // Persistir la secuencia como cancelada y mover a históricos
        if (fileManager != null) {
            try {
                TradingSequence sequence = miniId != null ? fileManager.findByMiniId(miniId) : fileManager.findBySeqId(seqId);
                if (sequence != null) {
                    sequence.markCancelled();
                    
                    // Verificar si todas las órdenes están en estado final
                    boolean op1Final = sequence.getOp1() != null && sequence.getOp1().isFinal();
                    boolean op2Final = sequence.getOp2() != null && sequence.getOp2().isFinal();
                    boolean op3Final = sequence.getOp3() != null && sequence.getOp3().isFinal();
                    boolean allFinal = op1Final && op2Final && op3Final;
                    
                    if (allFinal) {
                        // Todas las órdenes en estado final: mover a eventos y eliminar de .seq
                        fileManager.appendEvent(sequence);
                        fileManager.deleteByMiniId(sequence.getMiniId());
                        if (statsManager != null) {
                            statsManager.decrementPending();
                        }
                        Log.debug(TAG, "Sequence #" + seqId + " all orders final, moved to events");
                    } else {
                        // Hay órdenes pendientes: mantener en .seq para recuperación
                        fileManager.updateOrderByMiniId(sequence.getMiniId(), 1, sequence.getOp1());
                        fileManager.updateOrderByMiniId(sequence.getMiniId(), 2, sequence.getOp2());
                        fileManager.updateOrderByMiniId(sequence.getMiniId(), 3, sequence.getOp3());
                        Log.info(TAG, "Sequence #" + seqId + " has pending orders, kept in .seq for recovery (OP1=" + sequence.getOp1().getOrderStatus() + ", OP2=" + sequence.getOp2().getOrderStatus() + ", OP3=" + sequence.getOp3().getOrderStatus() + ")");
                    }
                } else {
                    Log.error(TAG, "Sequence #" + seqId + " not found in fileManager for error handling");
                }
            } catch (Exception e) {
                Log.error(TAG, "Error persisting failure: " + e.getMessage());
            }
        }

        // Registrar la pérdida del capital total comprometido (usando buffer de seguridad)
        if (riskManager != null) {
            riskManager.recordPnL(-config.getBalancePerTrade() * SAFETY_BUFFER_FACTOR);
        }
    }

    /**
     * @return Número de trades actualmente abiertos
     */
    public int getOpenTrades() {
        return openTrades.get();
    }

    /**
     * @return Estadísticas actuales del motor de ejecución
     */
    public ExecutionStats getStats() {
        return ExecutionStats.builder()
            .openTrades(openTrades.get())
            .totalSequences(sequenceCounter.get() - 1)
            .timestamp(System.currentTimeMillis())
            .build();
    }

    /**
     * Recupera y muestra secuencias pendientes cargadas desde archivos .seq.
     * Consulta Binance API para obtener el estado real de órdenes con binanceOrderId.
     *
     * @param pendingSecuencias Lista de secuencias pendientes a recuperar
     */
    public void recoverPendingSequences(java.util.List<TradingSequence> pendingSecuencias) {
        if (pendingSecuencias == null || pendingSecuencias.isEmpty()) {
            return;
        }

        Log.info(TAG, "=== Recovering " + pendingSecuencias.size() + " pending sequences ===");

        for (TradingSequence seq : pendingSecuencias) {
            int seqId = seq.getSeqId();
            String miniId = seq.getMiniId();
            String triangleId = seq.getTriangleId();

            Log.info(TAG, "Recovering Seq #" + seqId + " [" + triangleId + "] miniId=" + miniId);

            // Mostrar la secuencia en consola
            if (sequenceDisplay != null) {
                sequenceDisplay.showStart(seqId, seq.getTimestampInicio(), seq.getProfitEsperado(), true);
            }

            // Mostrar estado de cada orden
            displayRecoveredOrder(seq, 1, seq.getOp1());
            displayRecoveredOrder(seq, 2, seq.getOp2());
            displayRecoveredOrder(seq, 3, seq.getOp3());

            if (sequenceDisplay != null) {
                sequenceDisplay.showEnd(seqId, true, seq.getProfitEsperado());
            }

            // Determinar acción de recuperación
            recoverSequence(seq);
        }
    }

    /**
     * Muestra una orden recuperada en consola con su estado actual.
     */
    private void displayRecoveredOrder(TradingSequence seq, int opNum, SequenceOrder order) {
        if (order == null) {
            return;
        }

        String symbol = order.getSymbol() != null ? order.getSymbol() : "";
        String side = order.getSide() != null ? order.getSide() : "";
        double qty = order.getQuantity();
        double price = order.getPrice();
        String status = order.getOrderStatus() != null ? order.getOrderStatus().name() : "WAITING";
        String orderId = order.getBinanceOrderId() != null && !order.getBinanceOrderId().isEmpty()
                ? order.getBinanceOrderId() : "------";
        String type = order.getType() != null ? order.getType() : "limit";

        // Si tiene binanceOrderId, consultar estado real de Binance
        if (order.getBinanceOrderId() != null && !order.getBinanceOrderId().isEmpty()) {
            try {
                com.arbitrage.model.OrderResult binanceOrder = apiClient.queryOrder(symbol, order.getBinanceOrderId());
                if (binanceOrder != null && binanceOrder.isSuccess()) {
                    String realStatus = binanceOrder.getStatus();
                    if (realStatus != null) {
                        status = "FILLED".equals(realStatus) ? "FILLED" :
                                 ("CANCELED".equals(realStatus) ? "CANCELED" :
                                  ("NEW".equals(realStatus) || "PARTIALLY_FILLED".equals(realStatus) ? "OPENED" : status));
                    }
                    // Actualizar con datos reales si están disponibles
                    if (binanceOrder.getExecutedQty() > 0) {
                        qty = binanceOrder.getExecutedQty();
                    }
                    if (binanceOrder.getPrice() > 0) {
                        price = binanceOrder.getPrice();
                    }
                    orderId = binanceOrder.getOrderId() != null ? binanceOrder.getOrderId() : orderId;
                }
            } catch (Exception e) {
                Log.warn(TAG, "Could not query Binance for order " + orderId + ": " + e.getMessage());
            }
        }

        long elapsed = System.currentTimeMillis() - seq.getTimestampInicio();
        if (sequenceDisplay != null) {
            sequenceDisplay.showOrderStatus(seq.getSeqId(), opNum, symbol, side, qty, price, status, elapsed, orderId, type);
        }
    }

    /**
     * Sincroniza el estado de una orden con Binance API.
     * Consulta el estado real de la orden en Binance y actualiza el objeto SequenceOrder
     * con los datos reales (status, cantidad ejecutada, precio, orderId).
     *
     * @param order Orden a sincronizar
     * @return true si la orden está FILLED en Binance
     */
    private boolean syncOrderStatusWithBinance(SequenceOrder order) {
        if (order == null || order.getBinanceOrderId() == null || order.getBinanceOrderId().isEmpty()) {
            return false;
        }

        try {
            OrderResult binanceOrder = apiClient.queryOrder(order.getSymbol(), order.getBinanceOrderId());
            if (binanceOrder != null && binanceOrder.isSuccess()) {
                String realStatus = binanceOrder.getStatus();
                if (realStatus != null) {
                    if ("FILLED".equals(realStatus)) {
                        order.setOrderStatus(EstadoOrden.FILLED);
                        if (binanceOrder.getExecutedQty() > 0) {
                            order.setCantidadEjecutada(binanceOrder.getExecutedQty());
                        }
                        if (binanceOrder.getPrice() > 0) {
                            order.setPrecioEjecutado(binanceOrder.getPrice());
                        }
                        if (fileManager != null) {
                            fileManager.updateOrderByMiniId(order.getMiniId(), order.getOpIndice(), order);
                        }
                        return true;
                    } else if ("CANCELED".equals(realStatus) || "REJECTED".equals(realStatus) || "EXPIRED".equals(realStatus)) {
                        order.setOrderStatus(EstadoOrden.valueOf(realStatus));
                        if (fileManager != null) {
                            fileManager.updateOrderByMiniId(order.getMiniId(), order.getOpIndice(), order);
                        }
                        return false;
                    }
                }
            }
        } catch (Exception e) {
            Log.warn(TAG, "Could not query Binance for order " + order.getBinanceOrderId() + ": " + e.getMessage());
        }
        return false;
    }

    /**
     * Ejecuta una secuencia desde un índice de operación específico.
     * Se usa para reanudar secuencias interrumpidas que ya tienen órdenes previas FILLED.
     *
     * @param sequence    Secuencia de trading a reanudar
     * @param startOpIndex Índice de la operación desde la cual continuar (1, 2 o 3)
     */
    private void executeFromOpIndex(TradingSequence sequence, int startOpIndex) {
        int seqId = sequence.getSeqId();
        String miniId = sequence.getMiniId();
        String triangleId = sequence.getTriangleId();

        String symbol1 = sequence.getOp1() != null ? sequence.getOp1().getSymbol() : "";
        String symbol2 = sequence.getOp2() != null ? sequence.getOp2().getSymbol() : "";
        String symbol3 = sequence.getOp3() != null ? sequence.getOp3().getSymbol() : "";

        String side1 = "BUY";
        String side2 = sequence.getOp2() != null ? sequence.getOp2().getSide() : "SELL";
        String side3 = "SELL";

        String orderType1 = config.getTypeOp1();
        String orderType2 = config.getTypeOp2();
        String orderType3 = config.getTypeOp3();

        double price2 = sequence.getOp2() != null ? sequence.getOp2().getPrice() : 0;
        double price3 = sequence.getOp3() != null ? sequence.getOp3().getPrice() : 0;

        try {
            if (startOpIndex == 1) {
                if (fileManager != null) {
                    try {
                        fileManager.updateOrderByMiniId(miniId, 1, sequence.getOp1());
                        Log.info(TAG, "Seq #" + seqId + " persisted with miniId: " + miniId);
                    } catch (java.io.IOException ioe) {
                        Log.error(TAG, "Error persisting Seq #" + seqId + ": " + ioe.getMessage());
                    }
                }

                double adjQty1 = apiClient.adjustQuantityToLotSize(symbol1, sequence.getOp1().getQuantity());
                double adjPrice1 = sequence.getOp1().getPrice();

                if (adjPrice1 <= 0.0) {
                    adjPrice1 = apiClient.getSymbolPrice(symbol1);
                }

                double availableUsdt = apiClient.getAssetBalance("USDT");
                if (availableUsdt < config.getBalancePerTrade()) {
                    Log.error(TAG, "Insufficient USDT to resume Seq #" + seqId + " from Op1");
                    sequence.markCancelled();
                    if (fileManager != null) {
                        try {
                            fileManager.appendEvent(sequence);
                            fileManager.deleteByMiniId(miniId);
                        } catch (java.io.IOException ioe) {
                            Log.error(TAG, "Error persisting cancelled Seq #" + seqId + ": " + ioe.getMessage());
                        }
                    }
                    return;
                }

                OrderResult result1 = executeOp1(sequence, symbol1, side1, orderType1, adjQty1, adjPrice1, seqId);

                double executedQty1 = result1.getExecutedQty();
                double[] recalc = recalculateChain(executedQty1, symbol2, symbol3, price2, price3, side2);
                double adjQty2 = sequence.getOp2().getQuantity();
                double adjQty3 = sequence.getOp3().getQuantity();
                if (recalc != null) {
                    adjQty2 = recalc[0];
                    adjQty3 = recalc[1];
                }

                String baseAsset2 = apiClient.extractBaseAsset(symbol2);
                double availableBase2 = apiClient.getAssetBalance(baseAsset2);
                double feeOp1Decimal = config.getFeeOp1() / 100.0;
                double minRequired = executedQty1 * (1.0 - feeOp1Decimal) * 1.05;

                if (minRequired > availableBase2) {
                    Log.error(TAG, "Insufficient " + baseAsset2 + " for Op2 in Seq #" + seqId);
                    sequence.markCancelled();
                    if (fileManager != null) {
                        try {
                            fileManager.updateOrderByMiniId(miniId, 1, sequence.getOp1());
                        } catch (java.io.IOException ioe) {
                            Log.error(TAG, "Error persisting Seq #" + seqId + ": " + ioe.getMessage());
                        }
                    }
                    return;
                }

                sequence.getOp2().setQuantity(adjQty2);
                OrderResult result2 = executeOp2(sequence, symbol2, side2, orderType2, adjQty2,
                    sequence.getOp2().getPrice() > 0 ? sequence.getOp2().getPrice() : apiClient.getSymbolPrice(symbol2), seqId);

                double executedQty2 = result2.getExecutedQty();
                double fillPrice2 = result2.getPrice();
                String baseAsset3 = apiClient.extractBaseAsset(symbol3);
                double availableBase3 = apiClient.getAssetBalance(baseAsset3);
                double feeOp2Decimal = config.getFeeOp2() / 100.0;

                double finalAdjQty3;
                if ("BUY".equals(side2)) {
                    double afterFee2 = executedQty2 * (1.0 - feeOp2Decimal);
                    finalAdjQty3 = apiClient.adjustQuantityToLotSize(symbol3, afterFee2);
                } else {
                    double quoteAssetQty = executedQty2 * fillPrice2;
                    double afterFee2 = quoteAssetQty * (1.0 - feeOp2Decimal);
                    finalAdjQty3 = apiClient.adjustQuantityToLotSize(symbol3, afterFee2);
                }

                if (finalAdjQty3 > availableBase3) {
                    finalAdjQty3 = apiClient.adjustQuantityToLotSize(symbol3, availableBase3 * 0.995);
                }

                sequence.getOp3().setQuantity(finalAdjQty3);
                OrderResult result3 = executeOp3(sequence, symbol3, side3, orderType3, finalAdjQty3,
                    sequence.getOp3().getPrice() > 0 ? sequence.getOp3().getPrice() : apiClient.getSymbolPrice(symbol3), seqId);

                closeSequence(sequence, result3, seqId, sequence.getProfitEsperado());

            } else if (startOpIndex == 2) {
                SequenceOrder op1 = sequence.getOp1();
                if (op1 == null || !op1.isFilled()) {
                    Log.error(TAG, "Seq #" + seqId + " Op1 not FILLED, cannot resume from Op2");
                    closeAndCleanupFailedSequence(sequence, "Op1 not FILLED");
                    return;
                }

                double executedQty1 = op1.getCantidadEjecutada();
                double[] recalc = recalculateChain(executedQty1, symbol2, symbol3, price2, price3, side2);
                double adjQty2 = sequence.getOp2() != null ? sequence.getOp2().getQuantity() : 0;
                double adjQty3 = sequence.getOp3() != null ? sequence.getOp3().getQuantity() : 0;
                if (recalc != null) {
                    adjQty2 = recalc[0];
                    adjQty3 = recalc[1];
                }

                String baseAsset2 = apiClient.extractBaseAsset(symbol2);
                double availableBase2 = apiClient.getAssetBalance(baseAsset2);
                double feeOp1Decimal = config.getFeeOp1() / 100.0;
                double minRequired = executedQty1 * (1.0 - feeOp1Decimal) * 1.05;

                if (minRequired > availableBase2) {
                    Log.error(TAG, "Insufficient " + baseAsset2 + " for Op2 in Seq #" + seqId);
                    closeAndCleanupFailedSequence(sequence, "Insufficient " + baseAsset2 + " for Op2");
                    return;
                }

                double op2Price = sequence.getOp2().getPrice();
                if (op2Price <= 0.0) {
                    op2Price = apiClient.getSymbolPrice(symbol2);
                    if (op2Price <= 0.0) {
                        Log.error(TAG, "Seq #" + seqId + " Op2 price is 0 and could not fetch market price for " + symbol2);
                        closeAndCleanupFailedSequence(sequence, "Op2 price is 0 for " + symbol2);
                        return;
                    }
                    sequence.getOp2().setPrice(op2Price);
                    Log.info(TAG, "Seq #" + seqId + " fetched market price for Op2: " + op2Price);
                }

                sequence.getOp2().setQuantity(adjQty2);
                OrderResult result2 = executeOp2(sequence, symbol2, side2, orderType2, adjQty2, op2Price, seqId);

                if (!"FILLED".equals(result2.getStatus())) {
                    Log.error(TAG, "Seq #" + seqId + " Op2 not filled: " + result2.getStatus());
                    closeAndCleanupFailedSequence(sequence, "Op2 not filled: " + result2.getStatus());
                    return;
                }

                double executedQty2 = result2.getExecutedQty();
                double fillPrice2 = result2.getPrice();
                String baseAsset3 = apiClient.extractBaseAsset(symbol3);
                double availableBase3 = apiClient.getAssetBalance(baseAsset3);
                double feeOp2Decimal = config.getFeeOp2() / 100.0;

                double finalAdjQty3;
                if ("BUY".equals(side2)) {
                    double afterFee2 = executedQty2 * (1.0 - feeOp2Decimal);
                    finalAdjQty3 = apiClient.adjustQuantityToLotSize(symbol3, afterFee2);
                } else {
                    double quoteAssetQty = executedQty2 * fillPrice2;
                    double afterFee2 = quoteAssetQty * (1.0 - feeOp2Decimal);
                    finalAdjQty3 = apiClient.adjustQuantityToLotSize(symbol3, afterFee2);
                }

                if (finalAdjQty3 > availableBase3) {
                    finalAdjQty3 = apiClient.adjustQuantityToLotSize(symbol3, availableBase3 * 0.995);
                }

                double op3Price = sequence.getOp3().getPrice();
                if (op3Price <= 0.0) {
                    op3Price = apiClient.getSymbolPrice(symbol3);
                    if (op3Price <= 0.0) {
                        Log.error(TAG, "Seq #" + seqId + " Op3 price is 0 and could not fetch market price for " + symbol3);
                        closeAndCleanupFailedSequence(sequence, "Op3 price is 0 for " + symbol3);
                        return;
                    }
                    sequence.getOp3().setPrice(op3Price);
                    Log.info(TAG, "Seq #" + seqId + " fetched market price for Op3: " + op3Price);
                }

                sequence.getOp3().setQuantity(finalAdjQty3);
                OrderResult result3 = executeOp3(sequence, symbol3, side3, orderType3, finalAdjQty3, op3Price, seqId);

                if (!"FILLED".equals(result3.getStatus())) {
                    Log.error(TAG, "Seq #" + seqId + " Op3 not filled: " + result3.getStatus());
                    closeAndCleanupFailedSequence(sequence, "Op3 not filled: " + result3.getStatus());
                    return;
                }

                closeSequence(sequence, result3, seqId, sequence.getProfitEsperado());

            } else if (startOpIndex == 3) {
                SequenceOrder op2 = sequence.getOp2();
                if (op2 == null || !op2.isFilled()) {
                    Log.error(TAG, "Seq #" + seqId + " Op2 not FILLED, cannot resume from Op3");
                    closeAndCleanupFailedSequence(sequence, "Op2 not FILLED");
                    return;
                }

                double executedQty2 = op2.getCantidadEjecutada();
                double fillPrice2 = op2.getPrecioEjecutado();
                String baseAsset3 = apiClient.extractBaseAsset(symbol3);
                double availableBase3 = apiClient.getAssetBalance(baseAsset3);
                double feeOp2Decimal = config.getFeeOp2() / 100.0;

                double finalAdjQty3;
                if ("BUY".equals(side2)) {
                    double afterFee2 = executedQty2 * (1.0 - feeOp2Decimal);
                    finalAdjQty3 = apiClient.adjustQuantityToLotSize(symbol3, afterFee2);
                } else {
                    double quoteAssetQty = executedQty2 * fillPrice2;
                    double afterFee2 = quoteAssetQty * (1.0 - feeOp2Decimal);
                    finalAdjQty3 = apiClient.adjustQuantityToLotSize(symbol3, afterFee2);
                }

                if (finalAdjQty3 > availableBase3) {
                    finalAdjQty3 = apiClient.adjustQuantityToLotSize(symbol3, availableBase3 * 0.995);
                }

                double op3Price = sequence.getOp3().getPrice();
                if (op3Price <= 0.0) {
                    op3Price = apiClient.getSymbolPrice(symbol3);
                    if (op3Price <= 0.0) {
                        Log.error(TAG, "Seq #" + seqId + " Op3 price is 0 and could not fetch market price for " + symbol3);
                        closeAndCleanupFailedSequence(sequence, "Op3 price is 0 for " + symbol3);
                        return;
                    }
                    sequence.getOp3().setPrice(op3Price);
                    Log.info(TAG, "Seq #" + seqId + " fetched market price for Op3: " + op3Price);
                }

                sequence.getOp3().setQuantity(finalAdjQty3);
                OrderResult result3 = executeOp3(sequence, symbol3, side3, orderType3, finalAdjQty3, op3Price, seqId);

                if (!"FILLED".equals(result3.getStatus())) {
                    Log.error(TAG, "Seq #" + seqId + " Op3 not filled: " + result3.getStatus());
                    closeAndCleanupFailedSequence(sequence, "Op3 not filled: " + result3.getStatus());
                    return;
                }

                closeSequence(sequence, result3, seqId, sequence.getProfitEsperado());
            }

        } catch (Exception e) {
            Log.error(TAG, "Error resuming Seq #" + seqId + " from Op" + startOpIndex + ": " + e.getMessage());
            closeAndCleanupFailedSequence(sequence, e.getMessage());
        }
    }

    /**
     * Reanuda una secuencia pendiente que fue interrumpida.
     * Sincroniza el estado de las órdenes con Binance, determina desde qué operación
     * continuar, y ejecuta el resto de la secuencia automáticamente.
     *
     * @param sequence Secuencia de trading a reanudar
     */
    public void resumeSequence(TradingSequence sequence) {
        int seqId = sequence.getSeqId();
        String miniId = sequence.getMiniId();
        String triangleId = sequence.getTriangleId();

        if (miniId == null || miniId.isEmpty()) {
            Log.warn(TAG, "Seq #" + seqId + " has no miniId, cannot resume");
            return;
        }

        Log.info(TAG, "=== Resuming Seq #" + seqId + " [" + triangleId + "] miniId=" + miniId + " ===");

        if (sequenceDisplay != null) {
            sequenceDisplay.showStart(seqId, sequence.getTimestampInicio(), sequence.getProfitEsperado(), true);
        }

        syncOrderStatusWithBinance(sequence.getOp1());
        syncOrderStatusWithBinance(sequence.getOp2());
        syncOrderStatusWithBinance(sequence.getOp3());

        displayRecoveredOrder(sequence, 1, sequence.getOp1());
        displayRecoveredOrder(sequence, 2, sequence.getOp2());
        displayRecoveredOrder(sequence, 3, sequence.getOp3());

        // Si alguna orden tiene estado ERROR después del sync, cerrar limpiamente
        if (hasOrderError(sequence)) {
            Log.error(TAG, "Seq #" + seqId + " has order in ERROR state after sync, closing");
            closeAndCleanupFailedSequence(sequence, "Order ERROR after Binance sync");
            return;
        }

        boolean allFinal = sequence.getOp1() != null && sequence.getOp1().isFinal() &&
                          sequence.getOp2() != null && sequence.getOp2().isFinal() &&
                          sequence.getOp3() != null && sequence.getOp3().isFinal();

        if (allFinal) {
            Log.info(TAG, "Seq #" + seqId + " all orders final after sync, moving to events");
            if (sequence.getOp1().isFilled() && sequence.getOp2().isFilled() && sequence.getOp3().isFilled()) {
                double price1Exec = sequence.getOp1().getPrecioEjecutado();
                double price2Exec = sequence.getOp2().getPrecioEjecutado();
                double price3Exec = sequence.getOp3().getPrecioEjecutado();

                boolean isForward = sequence.getOp1().getSymbol().endsWith("USDT");

                double rawProduct;
                if (isForward) {
                    rawProduct = (price2Exec * price3Exec) / price1Exec;
                } else {
                    rawProduct = price3Exec / (price1Exec * price2Exec);
                }

                double feeOp1 = config.getFeeOp1() / 100.0;
                double feeOp2 = config.getFeeOp2() / 100.0;
                double feeOp3 = config.getFeeOp3() / 100.0;
                double feeFactor = (1.0 - feeOp1) * (1.0 - feeOp2) * (1.0 - feeOp3);

                double profitPctRealizado = (rawProduct * feeFactor - 1.0) * 100.0;
                double profitRealizado = sequence.getMontoBase() * (profitPctRealizado / 100.0);

                sequence.close(profitRealizado);
            } else {
                sequence.markCancelled();
            }
            if (fileManager != null) {
                try {
                    fileManager.appendEvent(sequence);
                    fileManager.deleteByMiniId(miniId);
                } catch (Exception e) {
                    Log.error(TAG, "Error moving Seq #" + seqId + " to events: " + e.getMessage());
                }
            }
            if (sequenceDisplay != null) {
                sequenceDisplay.showEnd(seqId, true, sequence.getProfitEsperado());
            }
            return;
        }

        int nextOpIndex = sequence.getNextPendingOpIndex();
        if (nextOpIndex > 3) {
            Log.info(TAG, "Seq #" + seqId + " all orders completed, moving to events");
            sequence.close(0);
            if (fileManager != null) {
                try {
                    fileManager.appendEvent(sequence);
                    fileManager.deleteByMiniId(miniId);
                } catch (Exception e) {
                    Log.error(TAG, "Error moving Seq #" + seqId + " to events: " + e.getMessage());
                }
            }
            return;
        }

        Log.info(TAG, "Seq #" + seqId + " resuming from Op" + nextOpIndex);

        openTrades.incrementAndGet();
        executor.submit(() -> {
            try {
                executeFromOpIndex(sequence, nextOpIndex);
            } catch (Exception e) {
                Log.error(TAG, "Resume error for Seq #" + seqId + ": " + e.getMessage());
            } finally {
                openTrades.decrementAndGet();
            }
        });
    }

    /**
     * Determina y ejecuta la acción de recuperación para una secuencia pendiente.
     * Re-ejecuta desde la siguiente operación pendiente con precios actuales del mercado.
     */
    private void recoverSequence(TradingSequence seq) {
        int seqId = seq.getSeqId();
        String miniId = seq.getMiniId();

        if (miniId == null || miniId.isEmpty()) {
            Log.warn(TAG, "Seq #" + seqId + " has no miniId, cannot recover");
            return;
        }

        boolean allFinal = seq.getOp1() != null && seq.getOp1().isFinal() &&
                          seq.getOp2() != null && seq.getOp2().isFinal() &&
                          seq.getOp3() != null && seq.getOp3().isFinal();

        if (allFinal) {
            Log.info(TAG, "Seq #" + seqId + " all orders final, moving to events");
            if (fileManager != null) {
                try {
                    fileManager.appendEvent(seq);
                    fileManager.deleteByMiniId(miniId);
                } catch (Exception e) {
                    Log.error(TAG, "Error moving Seq #" + seqId + " to events: " + e.getMessage());
                }
            }
            return;
        }

        // Si alguna orden ya tiene estado ERROR, cerrar la secuencia limpiamente
        if (hasOrderError(seq)) {
            Log.error(TAG, "Seq #" + seqId + " has order in ERROR state, closing sequence");
            closeAndCleanupFailedSequence(seq, "Order in ERROR state during recovery");
            return;
        }

        int nextOp = seq.getNextPendingOpIndex();
        Log.info(TAG, "Seq #" + seqId + " re-executing from Op" + nextOp + " with current market prices");
        resumeSequence(seq);
    }

    /**
     * Verifica si alguna orden de la secuencia tiene estado ERROR.
     */
    private boolean hasOrderError(TradingSequence seq) {
        if (seq.getOp1() != null && seq.getOp1().getOrderStatus() == EstadoOrden.ERROR) return true;
        if (seq.getOp2() != null && seq.getOp2().getOrderStatus() == EstadoOrden.ERROR) return true;
        if (seq.getOp3() != null && seq.getOp3().getOrderStatus() == EstadoOrden.ERROR) return true;
        return false;
    }

    /**
     * Cierra una secuencia fallida limpiamente:
     * 1. Cancela órdenes pendientes en Binance
     * 2. Marca todas las órdenes como ERROR/CANCELLED
     * 3. Marca la secuencia como cancelada
     * 4. Persiste en eventos y elimina de .seq
     * 5. Registra pérdida en RiskManager
     */
    private void closeAndCleanupFailedSequence(TradingSequence seq, String reason) {
        int seqId = seq.getSeqId();
        String miniId = seq.getMiniId();

        Log.error(TAG, "Seq #" + seqId + " closing due to: " + reason);

        // Cancelar órdenes pendientes en Binance
        cancelPendingOrders(seq);

        // Marcar órdenes pendientes como CANCELED
        if (seq.getOp1() != null && !seq.getOp1().isFinal()) {
            seq.getOp1().setOrderStatus(EstadoOrden.CANCELED);
        }
        if (seq.getOp2() != null && !seq.getOp2().isFinal()) {
            seq.getOp2().setOrderStatus(EstadoOrden.CANCELED);
        }
        if (seq.getOp3() != null && !seq.getOp3().isFinal()) {
            seq.getOp3().setOrderStatus(EstadoOrden.CANCELED);
        }

        // Marcar secuencia como cancelada
        seq.markCancelled();

        // Persistir en eventos y eliminar de .seq
        if (fileManager != null) {
            try {
                fileManager.appendEvent(seq);
                fileManager.deleteByMiniId(miniId);
                Log.info(TAG, "Seq #" + seqId + " moved to events after failure");
            } catch (Exception e) {
                Log.error(TAG, "Error persisting failed Seq #" + seqId + ": " + e.getMessage());
            }
        }

        // Mostrar en UI
        if (sequenceDisplay != null) {
            sequenceDisplay.showSequenceEstado(seqId, "CANCELED");
            sequenceDisplay.showEnd(seqId, false, 0.0);
        }

        // Registrar pérdida
        if (riskManager != null) {
            riskManager.recordPnL(-config.getBalancePerTrade() * SAFETY_BUFFER_FACTOR);
        }

        // Registrar en estadísticas: decrementar pending primero, luego marcar como cancelada
        if (statsManager != null) {
            statsManager.decrementPending();
            statsManager.recordCancelled();
        }
    }

    /**
     * Cancela órdenes pendientes en Binance para una secuencia dada.
     */
    private void cancelPendingOrders(TradingSequence seq) {
        String[] symbols = new String[3];
        String[] orderIds = new String[3];
        SequenceOrder[] orders = new SequenceOrder[]{seq.getOp1(), seq.getOp2(), seq.getOp3()};

        for (int i = 0; i < 3; i++) {
            if (orders[i] != null) {
                symbols[i] = orders[i].getSymbol();
                orderIds[i] = orders[i].getBinanceOrderId();
            }
        }

        for (int i = 0; i < 3; i++) {
            if (symbols[i] != null && !symbols[i].isEmpty() && orderIds[i] != null && !orderIds[i].isEmpty()) {
                // Solo cancelar si la orden no está en estado final
                if (orders[i] != null && !orders[i].isFinal()) {
                    try {
                        apiClient.cancelOrder(symbols[i], orderIds[i]);
                        Log.info(TAG, "Cancelled pending order " + orderIds[i] + " for " + symbols[i]);
                    } catch (Exception e) {
                        Log.warn(TAG, "Could not cancel order " + orderIds[i] + " for " + symbols[i] + ": " + e.getMessage());
                    }
                }
            }
        }
    }

    /**
     * Apaga el motor de ejecución, deteniendo todos los pools de threads.
     */
    public void shutdown() {
        executor.shutdown();
        pollingExecutor.shutdown();
        Log.info(TAG, "ExecutionEngine shutdown");
    }

    @Data
    @Builder
    public static class ExecutionStats {
        private int openTrades;
        private int totalSequences;
        private long timestamp;
    }

    /**
     * Verifica el balance de BNB y muestra alerta ROJA si está por debajo del mínimo.
     * Se llama antes de cada triángulo para asegurar que el descuento del 25% está activo.
     * Usa control de spam: solo alerta cada BNB_ALERT_INTERVAL_MS (default: 60s).
     */
    private void checkAndAlertBNBBalance() {
        if (!config.isCheckBNBBalance()) {
            return;
        }

        try {
            double bnbBalance = apiClient.getBNBBalance();
            double minBalance = config.getMinBNBBalance();

            if (bnbBalance < minBalance) {
                long now = System.currentTimeMillis();
                if (now - lastBNBAlertTime > BNB_ALERT_INTERVAL_MS) {
                    String alertMsg = "ALERTA: Balance BNB bajo: " + String.format("%.4f", bnbBalance) + 
                                      " BNB < " + String.format("%.2f", minBalance) + 
                                      " BNB - Descuento 25% en riesgo - Recarga BNB inmediatamente";
                    Log.error("BNB", alertMsg);
                    System.out.println("\u001B[31m[BNB ALERT] " + alertMsg + "\u001B[0m");
                    lastBNBAlertTime = now;
                }
            }
        } catch (Exception e) {
            Log.warn("BNB", "Error al verificar balance BNB: " + e.getMessage());
        }
    }
}
