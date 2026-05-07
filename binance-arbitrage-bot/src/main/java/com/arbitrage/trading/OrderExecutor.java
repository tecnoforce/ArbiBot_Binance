package com.arbitrage.trading;

import com.arbitrage.config.AppConfig;
import com.arbitrage.model.*;
import com.arbitrage.persistence.SequenceFileManager;
import com.arbitrage.util.Log;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

public class OrderExecutor {
    private static final String TAG = "ORDER_EXEC";

    private final AppConfig config;
    private final BinanceApiClient apiClient;
    private final SequenceFileManager fileManager;
    
    private final ExecutorService executor;
    private final ScheduledExecutorService pollingExecutor;
    
    private final AtomicInteger sequenceCounter;
    private final AtomicInteger openTrades;
    
    private final Random random;
    private final boolean realOrder;

    private SequenceDisplay sequenceDisplay;
    private volatile long lastWarningTime = 0;
    private static final long WARNING_INTERVAL_MS = 5000;

    public OrderExecutor(AppConfig config, BinanceApiClient apiClient) {
        this(config, apiClient, null);
    }

    public OrderExecutor(AppConfig config, BinanceApiClient apiClient, SequenceFileManager fileManager) {
        this.config = config;
        this.apiClient = apiClient;
        this.fileManager = fileManager;
        this.realOrder = config.isRealorder();
        
        this.executor = Executors.newCachedThreadPool();
        this.pollingExecutor = Executors.newScheduledThreadPool(4);
        
        this.sequenceCounter = new AtomicInteger(1);
        this.openTrades = new AtomicInteger(0);
        this.random = new Random();

        if (realOrder) {
            apiClient.loadExchangeInfoFilters();
        }

        Log.info(TAG, "OrderExecutor init. realOrder=" + realOrder + ", pollingInterval=" + config.getPollingIntervalMs() + "ms");
    }

    public void setSequenceDisplay(SequenceDisplay display) {
        this.sequenceDisplay = display;
    }

    public void execute(ArbitrageOpportunity opportunity) {
        if (openTrades.get() >= config.getMaxOpenTrades()) {
            long now = System.currentTimeMillis();
            if (now - lastWarningTime > WARNING_INTERVAL_MS) {
                Log.warn(TAG, "Max open trades reached. Skipping opportunity.");
                lastWarningTime = now;
            }
            return;
        }

        if (sequenceDisplay == null) {
            return;
        }

        int seqIdNum = sequenceCounter.getAndIncrement();
        openTrades.incrementAndGet();

        Thread sequenceThread = new Thread(() -> {
            try {
                if (realOrder && fileManager != null) {
                    executeSequenceReal(opportunity, seqIdNum);
                } else {
                    executeSequenceSimulated(opportunity, seqIdNum);
                }
            } catch (Exception e) {
                Log.error(TAG, "Error executing sequence: " + e.getMessage());
            } finally {
                openTrades.decrementAndGet();
            }
        });
        sequenceThread.start();
    }

    private void executeSequenceSimulated(ArbitrageOpportunity opportunity, int seqId) {
        long startTime = opportunity.getTimestamp();
        double profitPct = opportunity.getProfitPct();
        
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
        
        String orderType1 = config.getTypeOp1();
        String orderType2 = config.getTypeOp2();
        String orderType3 = config.getTypeOp3();
        
        String side1 = "BUY";
        String side2 = isForward ? "BUY" : "SELL";
        String side3 = "SELL";

        if (sequenceDisplay != null) {
            sequenceDisplay.showStart(seqId, startTime, profitPct, false);
            sequenceDisplay.showOrderPending(1, symbol1, side1, qty1, price1, orderType1);
            sequenceDisplay.showOrderPending(2, symbol2, side2, qty2, price2, orderType2);
            sequenceDisplay.showOrderPending(3, symbol3, side3, qty3, price3, orderType3);
        }

        OrderResult r1 = executeSimulatedOrder(symbol1, side1, orderType1, qty1, price1);
        OrderResult r2 = executeSimulatedOrder(symbol2, side2, orderType2, qty2, price2);
        OrderResult r3 = executeSimulatedOrder(symbol3, side3, orderType3, qty3, price3);

        List<OrderResult> results = new ArrayList<>();
        results.add(r1);
        results.add(r2);
        results.add(r3);

        if (sequenceDisplay != null) {
            sequenceDisplay.printSequenceAtomic(seqId, startTime, false, results, profitPct);
        }
    }

    private OrderResult executeSimulatedOrder(String symbol, String side, String orderType, 
                                               double qty, double price) {
        int orderIdNum = 1000000 + random.nextInt(9000000);
        String orderId = String.valueOf(orderIdNum);
        long elapsed = 48 + random.nextInt(8);
        
        try {
            Thread.sleep(elapsed);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        
        return OrderResult.builder()
                .symbol(symbol)
                .side(side)
                .orderId(orderId)
                .quantity(qty)
                .price(price)
                .executedQty(qty)
                .status("FILLED")
                .elapsedTime(elapsed)
                .orderType(orderType.toUpperCase())
                .success(true)
                .build();
    }

    private void executeSequenceReal(ArbitrageOpportunity opportunity, int seqIdNum) {
        long startTime = System.currentTimeMillis();
        double profitPct = opportunity.getProfitPct();
        
        String triangleId = opportunity.getTriangle().getId();
        String modo = "TESTNET";
        
        String symbol1 = opportunity.getTriangle().getSymbol1();
        String symbol2 = opportunity.getTriangle().getSymbol2();
        String symbol3 = opportunity.getTriangle().getSymbol3();
        boolean isForward = opportunity.getTriangle().isForward();
        
        double qty1 = opportunity.getStep1Qty();
        double qty2 = opportunity.getStep2Qty();
        double qty3 = opportunity.getStep3Qty();
        
        String orderType1 = config.getTypeOp1();
        String orderType2 = config.getTypeOp2();
        String orderType3 = config.getTypeOp3();
        
        String side1 = "BUY";
        String side2 = isForward ? "BUY" : "SELL";
        String side3 = "SELL";
        
        TradingSequence sequence = TradingSequence.create(
            triangleId, modo, profitPct, 
            config.getBaseCurrency(), config.getBalancePerTrade()
        );
        
        String seqId = sequence.getSeqId();
        
        if (sequenceDisplay != null) {
            sequenceDisplay.showStart(seqIdNum, startTime, profitPct, true);
        }
        
        try {
            fileManager.insert(sequence);
            Log.debug(TAG, "Created sequence: " + seqId);
            
            SequenceOrder order1 = SequenceOrder.create(seqId, 1, symbol1, side1, orderType1, qty1, 0);
            sequence.setOp1(order1);
            fileManager.updateOrder(seqId, 1, order1);

            double adjQty1 = apiClient.adjustQuantityToLotSize(symbol1, qty1);
            double adjQty2 = apiClient.adjustQuantityToLotSize(symbol2, qty2);
            double adjQty3 = apiClient.adjustQuantityToLotSize(symbol3, qty3);

            if (realOrder) {
                double[] adjustedQty = recalculateChainForLotSize(adjQty1, adjQty2, adjQty3,
                    opportunity.getStep1Price(), opportunity.getStep2Price(), opportunity.getStep3Price(), isForward);
                if (adjustedQty == null) {
                    handleSequenceError(seqId, "LOT_SIZE adjustment too aggressive, skipping sequence");
                    return;
                }
                adjQty1 = adjustedQty[0];
                adjQty2 = adjustedQty[1];
                adjQty3 = adjustedQty[2];
            }

            if (sequenceDisplay != null) {
                sequenceDisplay.showOrderPending(1, symbol1, side1, adjQty1, 0, orderType1);
            }

            OrderResult result1 = placeAndMonitorOrder(symbol1, side1, orderType1, adjQty1, 0, seqId, 1);
            
            if (!"FILLED".equals(result1.getStatus())) {
                handleSequenceError(seqId, "OP1 not filled: " + result1.getStatus());
                return;
            }
            
            SequenceOrder order2 = SequenceOrder.create(seqId, 2, symbol2, side2, orderType2, adjQty2, 0);
            sequence.setOp2(order2);
            fileManager.updateOrder(seqId, 2, order2);

            if (sequenceDisplay != null) {
                sequenceDisplay.showOrderPending(2, symbol2, side2, adjQty2, 0, orderType2);
            }

            OrderResult result2 = placeAndMonitorOrder(symbol2, side2, orderType2, adjQty2, 0, seqId, 2);
            
            if (!"FILLED".equals(result2.getStatus())) {
                handleSequenceError(seqId, "OP2 not filled: " + result2.getStatus());
                return;
            }
            
            SequenceOrder order3 = SequenceOrder.create(seqId, 3, symbol3, side3, orderType3, adjQty3, 0);
            sequence.setOp3(order3);
            fileManager.updateOrder(seqId, 3, order3);

            if (sequenceDisplay != null) {
                sequenceDisplay.showOrderPending(3, symbol3, side3, adjQty3, 0, orderType3);
            }

            OrderResult result3 = placeAndMonitorOrder(symbol3, side3, orderType3, adjQty3, 0, seqId, 3);
            
            if (!"FILLED".equals(result3.getStatus())) {
                handleSequenceError(seqId, "OP3 not filled: " + result3.getStatus());
                return;
            }
            
            double finalQty = result3.getExecutedQty();
            double finalPrice = result3.getPrice();
            double receivedUsdt = finalQty * finalPrice;
            double profitRealizado = receivedUsdt - config.getBalancePerTrade();
            
            sequence.close(profitRealizado);
            
            fileManager.delete(seqId);
            fileManager.appendEvent(sequence);
            
            Log.debug(TAG, "Sequence closed: " + seqId + ", profit=" + profitRealizado);
            
            if (sequenceDisplay != null) {
                List<OrderResult> results = new ArrayList<>();
                results.add(result1);
                results.add(result2);
                results.add(result3);
                sequenceDisplay.printSequenceAtomic(seqIdNum, startTime, true, results, profitPct);
            }
            
        } catch (Exception e) {
            Log.error(TAG, "Error in sequence: " + e.getMessage());
            handleSequenceError(seqId, e.getMessage());
        }
    }

    private double[] recalculateChainForLotSize(double adjQty1, double adjQty2, double adjQty3,
                                                double price1, double price2, double price3,
                                                boolean isForward) {
        double feeOp1 = config.getFeeOp1() / 100.0;
        double feeOp2 = config.getFeeOp2() / 100.0;
        double feeOp3 = config.getFeeOp3() / 100.0;

        try {
            if (isForward) {
                double afterFee1 = adjQty1 * (1 - feeOp1);
                double step2Raw = afterFee1 / price2;
                double adjStep2 = apiClient.adjustQuantityRaw(step2Raw);
                if (adjStep2 < step2Raw * 0.5) {
                    Log.warn(TAG, "LOT_SIZE step 2 adjustment too aggressive: " + step2Raw + " -> " + adjStep2);
                    return null;
                }

                double afterFee2 = adjStep2 * (1 - feeOp2);
                double step3Raw = afterFee2 * price3;
                double adjStep3 = apiClient.adjustQuantityRaw(step3Raw);
                if (adjStep3 < step3Raw * 0.5) {
                    Log.warn(TAG, "LOT_SIZE step 3 adjustment too aggressive: " + step3Raw + " -> " + adjStep3);
                    return null;
                }
                return new double[]{adjQty1, adjStep2, adjStep3};
            } else {
                double afterFee1 = adjQty1 * (1 - feeOp1);
                double step2Raw = afterFee1 * price2;
                double adjStep2 = apiClient.adjustQuantityRaw(step2Raw);
                if (adjStep2 < step2Raw * 0.5) {
                    Log.warn(TAG, "LOT_SIZE step 2 adjustment too aggressive: " + step2Raw + " -> " + adjStep2);
                    return null;
                }

                double afterFee2 = adjStep2 * (1 - feeOp2);
                double step3Raw = afterFee2 * (1 - feeOp3);
                double adjStep3 = apiClient.adjustQuantityRaw(step3Raw);
                if (adjStep3 < step3Raw * 0.5) {
                    Log.warn(TAG, "LOT_SIZE step 3 adjustment too aggressive: " + step3Raw + " -> " + adjStep3);
                    return null;
                }
                return new double[]{adjQty1, adjStep2, adjStep3};
            }
        } catch (Exception e) {
            Log.warn(TAG, "Error recalculating chain: " + e.getMessage());
            return null;
        }
    }

    private OrderResult placeAndMonitorOrder(String symbol, String side, String orderType,
                                          double qty, double price, String seqId, int opIndice) 
            throws Exception {
        long startTime = System.currentTimeMillis();
        
        OrderResult placed = apiClient.placeOrder(symbol, side, orderType, qty, price, realOrder);
        
        if (!placed.isSuccess()) {
            Log.error(TAG, "Failed to place order: " + placed.getErrorMessage());
            return placed;
        }
        
        String orderId = placed.getOrderId();
        
        if (sequenceDisplay != null) {
            sequenceDisplay.showOrderFilled(placed);
        }

        // Si no es orden real, ya está fill (simulada) - no hacer polling
        if (!realOrder) {
            placed.setStatus("FILLED");
            placed.setExecutedQty(qty);
            return placed;
        }
        
        long pollingInterval = config.getPollingIntervalMs();
        long timeout = config.getOrderTimeoutMs();
        
        while (true) {
            long elapsed = System.currentTimeMillis() - startTime;
            
            if (elapsed > timeout) {
                Log.warn(TAG, "Order timeout, canceling: " + orderId);
                apiClient.cancelOrder(symbol, orderId);
                placed.setStatus("TIMEOUT");
                return placed;
            }
            
            Thread.sleep(pollingInterval);
            
            OrderResult status = apiClient.queryOrder(symbol, orderId);
            String orderStatus = status.getStatus();
            
            Log.debug(TAG, "Polling order " + orderId + ": " + orderStatus);
            
            if ("FILLED".equals(orderStatus)) {
                placed.setStatus("FILLED");
                placed.setExecutedQty(status.getExecutedQty());
                placed.setPrice(status.getPrice());
                return placed;
            }
            
            if ("CANCELED".equals(orderStatus) || "REJECTED".equals(orderStatus) || "EXPIRED".equals(orderStatus)) {
                placed.setStatus(orderStatus);
                return placed;
            }
        }
    }

    private void handleSequenceError(String seqId, String errorMessage) {
        try {
            TradingSequence sequence = fileManager.findById(seqId);
            if (sequence != null) {
                sequence.markError();
                fileManager.appendEvent(sequence);
                fileManager.delete(seqId);
            }
            Log.error(TAG, "Sequence error handled: " + seqId + " - " + errorMessage);
        } catch (Exception e) {
            Log.error(TAG, "Error handling sequence error: " + e.getMessage());
        }
    }

    public int getOpenTrades() {
        return openTrades.get();
    }

    public void shutdown() {
        executor.shutdown();
        pollingExecutor.shutdown();
    }

    public interface SequenceDisplay {
        void showStart(int sequenceId, long timestamp, double profitPct, boolean live);
        void showOrderPending(int opNum, String symbol, String side, double qty, double price, String orderType);
        void showOrderFilled(OrderResult r);
        void showEnd(int sequenceId, boolean success, double profitPct);
        void printSequenceAtomic(int sequenceId, long timestamp, boolean live, List<OrderResult> orders, double profitPct);
    }

    public interface OpportunityDisplay {
        void show(int orderId, long timestamp,
                 OrderResult op1, OrderResult op2, OrderResult op3,
                 double profitPct, boolean live);
    }
}