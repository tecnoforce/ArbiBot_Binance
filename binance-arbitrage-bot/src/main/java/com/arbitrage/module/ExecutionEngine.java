package com.arbitrage.module;

import com.arbitrage.config.AppConfig;
import com.arbitrage.model.*;
import com.arbitrage.trading.BinanceApiClient;
import com.arbitrage.persistence.SequenceFileManager;
import com.arbitrage.util.Log;
import lombok.Builder;
import lombok.Data;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * ExecutionEngine - Orquestacion de ejecucion de ordenes.
 *
 * Responsabilidades:
 * - Coordinar todos los modulos para ejecutar una oportunidad
 * - Validar con RiskManager antes de ejecutar
 * - Usar RepricingEngine para ajustar precios
 * - Monitorear fills con FillTracker
 * - Persistir secuencias
 *
 * Backward compatible: coexiste con OrderExecutor
 */
public class ExecutionEngine {
    private static final String TAG = "ExecEngine";

    private final AppConfig config;
    private final BinanceApiClient apiClient;
    private final SequenceFileManager fileManager;

    private final RiskManager riskManager;
    private final RepricingEngine repricingEngine;
    private final FillTracker fillTracker;

    private final ExecutorService executor;
    private final ScheduledExecutorService pollingExecutor;
    private final AtomicInteger sequenceCounter;
    private final AtomicInteger openTrades;

    private final ConcurrentHashMap<String, Ticker> priceMap;
    private volatile long lastWarningTime = 0;
    private static final long WARNING_INTERVAL_MS = 5000;

    public ExecutionEngine(
            AppConfig config,
            BinanceApiClient apiClient,
            SequenceFileManager fileManager,
            ConcurrentHashMap<String, Ticker> priceMap,
            RiskManager riskManager,
            RepricingEngine repricingEngine,
            FillTracker fillTracker
    ) {
        this.config = config;
        this.apiClient = apiClient;
        this.fileManager = fileManager;
        this.priceMap = priceMap;
        this.riskManager = riskManager;
        this.repricingEngine = repricingEngine;
        this.fillTracker = fillTracker;

        // Use virtual threads for I/O-bound execution tasks
        this.executor = Executors.newVirtualThreadPerTaskExecutor();
        this.pollingExecutor = Executors.newScheduledThreadPool(4);
        this.sequenceCounter = new AtomicInteger(1);
        this.openTrades = new AtomicInteger(0);
    }

    public void execute(ArbitrageOpportunity opportunity) {
        if (openTrades.get() >= config.getMaxOpenTrades()) {
            long now = System.currentTimeMillis();
            if (now - lastWarningTime > WARNING_INTERVAL_MS) {
                Log.debug(TAG, "Max open trades reached. Skipping.");
                lastWarningTime = now;
            }
            return;
        }

        if (riskManager != null) {
            RiskManager.ValidationResult validation = riskManager.validate(opportunity);
            if (!validation.isApproved()) {
                if (Log.isDebugEnabled()) {
                    Log.debug(TAG, "RiskManager rejected: " + validation.getViolations());
                }
                return;
            }
        }

        int seqIdNum = sequenceCounter.getAndIncrement();
        openTrades.incrementAndGet();

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

    private void executeRealSequence(ArbitrageOpportunity opportunity, int seqIdNum) {
        long startTime = System.currentTimeMillis();
        double profitPct = opportunity.getProfitPct();

        String triangleId = opportunity.getTriangle().getId();
        String symbol1 = opportunity.getTriangle().getSymbol1();
        String symbol2 = opportunity.getTriangle().getSymbol2();
        String symbol3 = opportunity.getTriangle().getSymbol3();

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

        TradingSequence sequence = TradingSequence.create(
            triangleId, "MAINNET", profitPct,
            config.getBaseCurrency(), config.getBalancePerTrade()
        );

        int seqId = sequence.getSeqId();

        if (fillTracker != null) {
            fillTracker.trackSequenceStart(seqId, opportunity.getTriangle(), profitPct);
        }

        try {
            if (fileManager != null) {
                fileManager.insert(sequence);
            }

            double adjQty1 = apiClient.adjustQuantityToLotSize(symbol1, qty1);
            double adjQty2 = apiClient.adjustQuantityToLotSize(symbol2, qty2);
            double adjQty3 = apiClient.adjustQuantityToLotSize(symbol3, qty3);

            double adjPrice1 = price1;
            double adjPrice2 = apiClient.adjustPriceToTickSize(symbol2, price2);
            double adjPrice3 = apiClient.adjustPriceToTickSize(symbol3, price3);

            double availableUsdt = apiClient.getAssetBalance("USDT");
            if (availableUsdt < config.getBalancePerTrade()) {
                handleError(seqId, "Insufficient USDT");
                return;
            }

            SequenceOrder order1 = SequenceOrder.create(seqId, 1, symbol1, side1, orderType1, adjQty1, 0);
            sequence.setOp1(order1);
            if (fileManager != null) fileManager.updateOrder(seqId, 1, order1);

            if (fillTracker != null) {
                fillTracker.trackOrderSent(seqId, 1, symbol1, side1, adjQty1);
            }

            double makerPrice1 = adjPrice1;
            if (repricingEngine != null && "LIMIT".equalsIgnoreCase(orderType1)) {
                makerPrice1 = repricingEngine.calculateMakerPrice(symbol1, side1, adjPrice1);
            }

            OrderResult result1 = placeAndMonitorOrder(seqId, symbol1, side1, orderType1, adjQty1, makerPrice1, 1);

            if (fillTracker != null && "FILLED".equals(result1.getStatus())) {
                fillTracker.trackOrderFilled(seqId, 1, result1);
            }

            if (!"FILLED".equals(result1.getStatus())) {
                handleError(seqId, "OP1 not filled: " + result1.getStatus());
                return;
            }

            order1.setOrderStatus(EstadoOrden.FILLED);
            order1.setCantidadEjecutada(result1.getExecutedQty());
            order1.setPrecioEjecutado(result1.getPrice());
            order1.setBinanceOrderId(result1.getOrderId());

            double executedQty1 = result1.getExecutedQty();
            double[] recalc = recalculateChain(executedQty1, symbol2, symbol3, price2, price3);
            if (recalc != null) {
                adjQty2 = recalc[0];
                adjQty3 = recalc[1];
            }

            String baseAsset2 = apiClient.extractBaseAsset(symbol2);
            double availableBase2 = apiClient.getAssetBalance(baseAsset2);
            double feeOp1Decimal = config.getFeeOp1() / 100.0;
            double minRequired = executedQty1 * (1.0 - feeOp1Decimal) * 1.05;

            if (minRequired > availableBase2) {
                handleError(seqId, "Insufficient " + baseAsset2 + " for Op2");
                return;
            }

            SequenceOrder order2 = SequenceOrder.create(seqId, 2, symbol2, side2, orderType2, adjQty2, 0);
            sequence.setOp2(order2);
            if (fileManager != null) fileManager.updateOrder(seqId, 2, order2);

            if (fillTracker != null) {
                fillTracker.trackOrderSent(seqId, 2, symbol2, side2, adjQty2);
            }

            double makerPrice2 = adjPrice2;
            if (repricingEngine != null && "LIMIT".equalsIgnoreCase(orderType2)) {
                makerPrice2 = repricingEngine.calculateMakerPrice(symbol2, side2, adjPrice2);
            }

            OrderResult result2 = placeAndMonitorOrder(seqId, symbol2, side2, orderType2, adjQty2, makerPrice2, 2);

            if (fillTracker != null && "FILLED".equals(result2.getStatus())) {
                fillTracker.trackOrderFilled(seqId, 2, result2);
            }

            if (!"FILLED".equals(result2.getStatus())) {
                handleError(seqId, "OP2 not filled: " + result2.getStatus());
                return;
            }

            order2.setOrderStatus(EstadoOrden.FILLED);
            order2.setCantidadEjecutada(result2.getExecutedQty());
            order2.setPrecioEjecutado(result2.getPrice());
            order2.setBinanceOrderId(result2.getOrderId());

            double executedQty2 = result2.getExecutedQty();
            double fillPrice2 = result2.getPrice();

            String baseAsset3 = apiClient.extractBaseAsset(symbol3);
            double availableBase3 = apiClient.getAssetBalance(baseAsset3);
            double feeOp2Decimal = config.getFeeOp2() / 100.0;

            double btcFromOp2 = executedQty2 * fillPrice2;
            double afterFee2 = btcFromOp2 * (1.0 - feeOp2Decimal);
            adjQty3 = apiClient.adjustQuantityToLotSize(symbol3, afterFee2);

            if (adjQty3 > availableBase3) {
                adjQty3 = apiClient.adjustQuantityToLotSize(symbol3, availableBase3 * 0.995);
            }

            SequenceOrder order3 = SequenceOrder.create(seqId, 3, symbol3, side3, orderType3, adjQty3, 0);
            sequence.setOp3(order3);
            if (fileManager != null) fileManager.updateOrder(seqId, 3, order3);

            if (fillTracker != null) {
                fillTracker.trackOrderSent(seqId, 3, symbol3, side3, adjQty3);
            }

            double makerPrice3 = adjPrice3;
            if (repricingEngine != null && "LIMIT".equalsIgnoreCase(orderType3)) {
                makerPrice3 = repricingEngine.calculateMakerPrice(symbol3, side3, adjPrice3);
            }

            OrderResult result3 = placeAndMonitorOrder(seqId, symbol3, side3, orderType3, adjQty3, makerPrice3, 3);

            if (fillTracker != null && "FILLED".equals(result3.getStatus())) {
                fillTracker.trackOrderFilled(seqId, 3, result3);
            }

            if (!"FILLED".equals(result3.getStatus())) {
                handleError(seqId, "OP3 not filled: " + result3.getStatus());
                return;
            }

            order3.setOrderStatus(EstadoOrden.FILLED);
            order3.setCantidadEjecutada(result3.getExecutedQty());
            order3.setPrecioEjecutado(result3.getPrice());
            order3.setBinanceOrderId(result3.getOrderId());

            double finalQty = result3.getExecutedQty();
            double finalPrice = result3.getPrice();
            double receivedUsdt = finalQty * finalPrice;
            double profitRealizado = receivedUsdt - config.getBalancePerTrade();

            sequence.close(profitRealizado);

            if (riskManager != null) {
                riskManager.recordPnL(profitRealizado);
                riskManager.recordClose(symbol3, finalQty, profitRealizado);
            }

            if (fileManager != null) {
                fileManager.delete(seqId);
                fileManager.appendEvent(sequence);
            }

            if (fillTracker != null) {
                fillTracker.trackSequenceEnd(seqId, true, profitRealizado);
            }

            Log.debug(TAG, "Sequence #" + seqId + " COMPLETED profit=" + profitRealizado);

        } catch (Exception e) {
            Log.error(TAG, "Error in sequence #" + seqId + ": " + e.getMessage());
            handleError(seqId, e.getMessage());
            if (fillTracker != null) {
                fillTracker.trackSequenceEnd(seqId, false, 0);
            }
        }
    }

    private void executeSimulatedSequence(ArbitrageOpportunity opportunity, int seqIdNum) {
        long startTime = System.currentTimeMillis();
        double profitPct = opportunity.getProfitPct();

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
            Thread.sleep(50 + new Random().nextInt(20));
            Thread.sleep(50 + new Random().nextInt(20));
            Thread.sleep(50 + new Random().nextInt(20));

            Log.debug(TAG, "Simulated sequence #" + seqIdNum + " completed, profit=" + profitPct + "%");

            if (riskManager != null) {
                riskManager.recordPnL(config.getBalancePerTrade() * profitPct / 100.0);
            }

            if (fillTracker != null) {
                fillTracker.trackSequenceEnd(seqIdNum, true, profitPct);
            }

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private OrderResult placeAndMonitorOrder(int seqId, String symbol, String side, String orderType,
                                              double qty, double price, int opIndex) throws Exception {
        long startTime = System.currentTimeMillis();

        OrderResult placed = apiClient.placeOrder(symbol, side, orderType, qty, price,
            config.getBalancePerTrade(), true);

        if (!placed.isSuccess()) {
            Log.error(TAG, "[Seq:#" + seqId + "] Failed to place order: " + placed.getErrorMessage());
            return placed;
        }

        Log.debug(TAG, "[Seq:#" + seqId + "] OP" + opIndex + " placed: " + symbol + " " + side);

        if (!config.isRealorder()) {
            placed.setStatus("FILLED");
            placed.setExecutedQty(qty);
            return placed;
        }

        long pollingInterval = config.getPollingIntervalMs();

        while (true) {
            long elapsed = System.currentTimeMillis() - startTime;

            Thread.sleep(pollingInterval);

            OrderResult status = apiClient.queryOrder(symbol, placed.getOrderId());
            String orderStatus = status.getStatus();

            if ("FILLED".equals(orderStatus)) {
                placed.setStatus("FILLED");
                placed.setExecutedQty(status.getExecutedQty());
                placed.setPrice(status.getPrice());
                Log.debug(TAG, "[Seq:#" + seqId + "] OP" + opIndex + " FILLED in " + elapsed + "ms");
                return placed;
            }

            if ("CANCELED".equals(orderStatus) || "REJECTED".equals(orderStatus) || "EXPIRED".equals(orderStatus)) {
                placed.setStatus(orderStatus);
                return placed;
            }
        }
    }

    private String calcSide2(String symbol1, String symbol2) {
        String heldAsset = apiClient.extractBaseAsset(symbol1);
        String baseAsset2 = apiClient.extractBaseAsset(symbol2);
        return heldAsset.equals(baseAsset2) ? "SELL" : "BUY";
    }

    private double[] recalculateChain(double executedQty1, String symbol2, String symbol3,
                                       double price2, double price3) {
        double feeOp1 = config.getFeeOp1() / 100.0;
        double feeOp2 = config.getFeeOp2() / 100.0;

        try {
            double afterFee1 = executedQty1 * (1.0 - feeOp1);
            double step2Raw = afterFee1 * price2;
            double adjStep2 = apiClient.adjustQuantityToLotSize(symbol2, step2Raw);
            if (adjStep2 < step2Raw * 0.5) return null;

            double afterFee2 = adjStep2 * (1.0 - feeOp2);
            double adjStep3 = apiClient.adjustQuantityToLotSize(symbol3, afterFee2);
            if (adjStep3 < afterFee2 * 0.5) return null;

            return new double[]{adjStep2, adjStep3};
        } catch (Exception e) {
            return null;
        }
    }

    private void handleError(int seqId, String message) {
        Log.error(TAG, "Sequence error #" + seqId + ": " + message);

        if (fileManager != null) {
            try {
                TradingSequence sequence = fileManager.findById(seqId);
                if (sequence != null) {
                    sequence.markCancelled();
                    fileManager.appendEvent(sequence);
                    fileManager.delete(seqId);
                }
            } catch (Exception e) {
                Log.error(TAG, "Error persisting failure: " + e.getMessage());
            }
        }

        if (riskManager != null) {
            riskManager.recordPnL(-config.getBalancePerTrade());
        }
    }

    public int getOpenTrades() {
        return openTrades.get();
    }

    public ExecutionStats getStats() {
        return ExecutionStats.builder()
            .openTrades(openTrades.get())
            .totalSequences(sequenceCounter.get() - 1)
            .timestamp(System.currentTimeMillis())
            .build();
    }

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
}