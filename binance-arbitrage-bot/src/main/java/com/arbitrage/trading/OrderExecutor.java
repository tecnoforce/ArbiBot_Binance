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
    private WalletSyncManager walletSyncManager;
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

    public void setWalletSyncManager(WalletSyncManager wsm) {
        this.walletSyncManager = wsm;
    }

    public void execute(ArbitrageOpportunity opportunity) {
        if (openTrades.get() >= config.getMaxOpenTrades()) {
            long now = System.currentTimeMillis();
            if (now - lastWarningTime > WARNING_INTERVAL_MS) {
                Log.debug(TAG, "Max open trades reached. Skipping opportunity.");
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

        long op1Sent = System.currentTimeMillis() + 50 + random.nextInt(20);
        long op2Sent, op3Sent;

        if (sequenceDisplay != null) {
            sequenceDisplay.showStart(seqId, startTime, profitPct, false);
            sequenceDisplay.showOrderStatus(seqId, 1, symbol1, side1, qty1, price1, "WAITING", -1, "------", orderType1);
            sequenceDisplay.showOrderStatus(seqId, 2, symbol2, side2, qty2, price2, "WAITING", -1, "------", orderType2);
            sequenceDisplay.showOrderStatus(seqId, 3, symbol3, side3, qty3, price3, "WAITING", -1, "------", orderType3);
        }

        OrderResult r1 = executeSimulatedOrder(symbol1, side1, orderType1, qty1, price1);
        op2Sent = System.currentTimeMillis() + 50 + random.nextInt(20);
        OrderResult r2 = executeSimulatedOrder(symbol2, side2, orderType2, qty2, price2);
        op3Sent = System.currentTimeMillis() + 50 + random.nextInt(20);
        OrderResult r3 = executeSimulatedOrder(symbol3, side3, orderType3, qty3, price3);

        if (sequenceDisplay != null) {
            long now = System.currentTimeMillis();
            sequenceDisplay.showSequenceEstado(seqId, "CERRADA");
            sequenceDisplay.showOrderStatus(seqId, 1, symbol1, side1, r1.getExecutedQty(), r1.getPrice(), "FILLED", now - op1Sent, r1.getOrderId(), orderType1);
            sequenceDisplay.showOrderStatus(seqId, 2, symbol2, side2, r2.getExecutedQty(), r2.getPrice(), "FILLED", now - op2Sent, r2.getOrderId(), orderType2);
            sequenceDisplay.showOrderStatus(seqId, 3, symbol3, side3, r3.getExecutedQty(), r3.getPrice(), "FILLED", now - op3Sent, r3.getOrderId(), orderType3);
            sequenceDisplay.showEnd(seqId, true, profitPct);
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

    private String calcSide2(String symbol1, String symbol2) {
        String heldAsset = apiClient.extractBaseAsset(symbol1);
        String baseAsset2 = apiClient.extractBaseAsset(symbol2);
        return heldAsset.equals(baseAsset2) ? "SELL" : "BUY";
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
        
        double price1 = opportunity.getStep1Price();
        double price2 = opportunity.getStep2Price();
        double price3 = opportunity.getStep3Price();
        
        String orderType1 = config.getTypeOp1();
        String orderType2 = config.getTypeOp2();
        String orderType3 = config.getTypeOp3();
        
        String side1 = "BUY";
        String side2 = calcSide2(symbol1, symbol2);
        String side3 = "SELL";
        
        TradingSequence sequence = TradingSequence.create(
            triangleId, modo, profitPct, 
            config.getBaseCurrency(), config.getBalancePerTrade()
        );
        
        int seqId = sequence.getSeqId();
        
if (sequenceDisplay != null) {
            sequenceDisplay.showStart(seqIdNum, startTime, profitPct, true);
        }

        long op1SentTime = 0, op2SentTime = 0, op3SentTime = 0;

        try {
            fileManager.insert(sequence);
            Log.debug(TAG, "Created sequence: #" + seqId);

            double adjQty1 = apiClient.adjustQuantityToLotSize(symbol1, qty1);
            double adjQty2 = apiClient.adjustQuantityToLotSize(symbol2, qty2);
            double adjQty3 = apiClient.adjustQuantityToLotSize(symbol3, qty3);

            double adjPrice1 = price1;
            double adjPrice2 = apiClient.adjustPriceToTickSize(symbol2, price2);
            double adjPrice3 = apiClient.adjustPriceToTickSize(symbol3, price3);

            if (sequenceDisplay != null) {
                sequenceDisplay.showOrderStatus(seqIdNum, 1, symbol1, side1, adjQty1, adjPrice1, "WAITING", -1, "------", orderType1);
                sequenceDisplay.showOrderStatus(seqIdNum, 2, symbol2, side2, adjQty2, adjPrice2, "WAITING", -1, "------", orderType2);
                sequenceDisplay.showOrderStatus(seqIdNum, 3, symbol3, side3, adjQty3, adjPrice3, "WAITING", -1, "------", orderType3);
            }

            double availableUsdt = apiClient.getAssetBalance("USDT");
            if (availableUsdt < config.getBalancePerTrade()) {
                String error = "Insufficient USDT for Op1. Need: " + String.format("%.8f", config.getBalancePerTrade()) + ", Have: " + String.format("%.8f", availableUsdt);
                Log.warn(TAG, error);
                handleSequenceError(seqIdNum, error);
                return;
            }

            SequenceOrder order1 = SequenceOrder.create(seqIdNum, 1, symbol1, side1, orderType1, qty1, 0);
            sequence.setOp1(order1);
            fileManager.updateOrder(seqIdNum, 1, order1);

            double quoteOrderQty = config.getBalancePerTrade();

            Log.debug(TAG, "=== SEQUENCE VALIDATION ===");
            Log.debug(TAG, "balancePerTrade: " + String.format("%.2f", quoteOrderQty) + " USDT");
            Log.debug(TAG, "Symbol1: " + symbol1 + " | minNotional: " + String.format("%.2f", apiClient.getMinNotional(symbol1)) + " USDT");
            Log.debug(TAG, "Symbol2: " + symbol2 + " | minNotional: " + String.format("%.2f", apiClient.getMinNotional(symbol2)) + " USDT");
            Log.debug(TAG, "Symbol3: " + symbol3 + " | minNotional: " + String.format("%.2f", apiClient.getMinNotional(symbol3)) + " USDT");
            Log.debug(TAG, "============================");

            long elapsed1;
            String orderId1 = "------";

            op1SentTime = System.currentTimeMillis();
            if (sequenceDisplay != null) {
                elapsed1 = 0;
                sequenceDisplay.showOrderStatus(seqIdNum, 1, symbol1, side1, adjQty1, adjPrice1, "OPENED", elapsed1, orderId1, orderType1);
                sequenceDisplay.showOrderStatus(seqIdNum, 2, symbol2, side2, adjQty2, adjPrice2, "WAITING", -1, "------", orderType2);
                sequenceDisplay.showOrderStatus(seqIdNum, 3, symbol3, side3, adjQty3, adjPrice3, "WAITING", -1, "------", orderType3);
            }

            OrderResult result1 = placeAndMonitorOrder(seqIdNum, symbol1, side1, orderType1, adjQty1, price1, seqIdNum, 1, quoteOrderQty);

            elapsed1 = System.currentTimeMillis() - op1SentTime;
            orderId1 = result1.getOrderId();

            if (!"FILLED".equals(result1.getStatus())) {
                if (sequenceDisplay != null) {
                    sequenceDisplay.showOrderStatus(seqIdNum, 1, symbol1, side1, adjQty1, adjPrice1, result1.getStatus(), elapsed1, orderId1, orderType1);
                }
                handleSequenceError(seqIdNum, "OP1 not filled: " + result1.getStatus());
                return;
            }

            if (sequenceDisplay != null) {
                sequenceDisplay.showOrderStatus(seqIdNum, 1, symbol1, side1, result1.getExecutedQty(), result1.getPrice(), "FILLED", elapsed1, orderId1, orderType1);
            }

            order1.setOrderStatus(EstadoOrden.FILLED);
            order1.setCantidadEjecutada(result1.getExecutedQty());
            order1.setPrecioEjecutado(result1.getPrice());
            order1.setBinanceOrderId(result1.getOrderId());
            order1.setTimestampEjecucion(result1.getUpdateTime());
            order1.setTiempoTranscurridoMs(result1.getUpdateTime() > 0 && result1.getTransactTime() > 0
                ? result1.getUpdateTime() - result1.getTransactTime()
                : result1.getElapsedTime());
            order1.setComisionAsset(result1.getCommissionAsset());
            order1.setComisionMonto(result1.getCommissionAmount());
            fileManager.updateOrder(seqIdNum, 1, order1);

            double executedQty1 = result1.getExecutedQty();
            double[] recalc = recalculateChainAfterOp1(executedQty1, symbol2, symbol3, price2, price3, isForward);
            if (recalc != null) {
                adjQty2 = recalc[0];
                adjQty3 = recalc[1];
            }

            String baseAsset2 = apiClient.extractBaseAsset(symbol2);
            double availableBase2 = apiClient.getAssetBalance(baseAsset2);
            double minRequired = adjQty2 * 1.05;
            if (minRequired > availableBase2) {
                String error = "Insufficient " + baseAsset2 + " for Op2. Need: " + String.format("%.8f", minRequired) + ", Have: " + String.format("%.8f", availableBase2);
                Log.warn(TAG, error);
                handleSequenceError(seqIdNum, error);
                return;
            }

            SequenceOrder order2 = SequenceOrder.create(seqIdNum, 2, symbol2, side2, orderType2, adjQty2, 0);
            sequence.setOp2(order2);
            fileManager.updateOrder(seqIdNum, 2, order2);

            long elapsed2;
            String orderId2 = "------";

            op2SentTime = System.currentTimeMillis();
            if (sequenceDisplay != null) {
                elapsed2 = 0;
                sequenceDisplay.showOrderStatus(seqIdNum, 2, symbol2, side2, adjQty2, adjPrice2, "OPENED", elapsed2, orderId2, orderType2);
                sequenceDisplay.showOrderStatus(seqIdNum, 3, symbol3, side3, adjQty3, adjPrice3, "WAITING", -1, "------", orderType3);
            }

            OrderResult result2 = placeAndMonitorOrder(seqIdNum, symbol2, side2, orderType2, adjQty2, adjPrice2, seqIdNum, 2, quoteOrderQty);

            elapsed2 = System.currentTimeMillis() - op2SentTime;
            orderId2 = result2.getOrderId();

            if (!"FILLED".equals(result2.getStatus())) {
                if (sequenceDisplay != null) {
                    sequenceDisplay.showOrderStatus(seqIdNum, 2, symbol2, side2, adjQty2, adjPrice2, result2.getStatus(), elapsed2, orderId2, orderType2);
                }
                handleSequenceError(seqIdNum, "OP2 not filled: " + result2.getStatus());
                return;
            }

            if (sequenceDisplay != null) {
                sequenceDisplay.showOrderStatus(seqIdNum, 2, symbol2, side2, result2.getExecutedQty(), result2.getPrice(), "FILLED", elapsed2, orderId2, orderType2);
            }

            order2.setOrderStatus(EstadoOrden.FILLED);
            order2.setCantidadEjecutada(result2.getExecutedQty());
            order2.setPrecioEjecutado(result2.getPrice());
            order2.setBinanceOrderId(result2.getOrderId());
            order2.setTimestampEjecucion(result2.getUpdateTime());
            order2.setTiempoTranscurridoMs(result2.getUpdateTime() > 0 && result2.getTransactTime() > 0
                ? result2.getUpdateTime() - result2.getTransactTime()
                : result2.getElapsedTime());
            order2.setComisionAsset(result2.getCommissionAsset());
            order2.setComisionMonto(result2.getCommissionAmount());
            fileManager.updateOrder(seqIdNum, 2, order2);

            double executedQty2 = result2.getExecutedQty();
            double fillPrice2 = result2.getPrice();
            if (isForward) {
                double afterFee2 = executedQty2 * (1 - config.getFeeOp2() / 100.0);
                adjQty3 = apiClient.adjustQuantityToLotSize(symbol3, afterFee2);
            } else {
                double bnbReceived = executedQty2 * fillPrice2;
                double afterFee2 = bnbReceived * (1 - config.getFeeOp2() / 100.0);
                adjQty3 = apiClient.adjustQuantityToLotSize(symbol3, afterFee2);
            }

            String baseAsset3 = apiClient.extractBaseAsset(symbol3);
            double availableBalance = apiClient.getAssetBalance(baseAsset3);
            if (adjQty3 > availableBalance) {
                Log.warn(TAG, "Insufficient " + baseAsset3 + " for OP3. Available: " + String.format("%.8f", availableBalance) + ", Required: " + String.format("%.8f", adjQty3));
                handleSequenceError(seqIdNum, "OP3 not possible: insufficient " + baseAsset3 + " balance (have " + String.format("%.4f", availableBalance) + " need " + String.format("%.4f", adjQty3) + ")");
                return;
            }

            SequenceOrder order3 = SequenceOrder.create(seqIdNum, 3, symbol3, side3, orderType3, adjQty3, 0);
            sequence.setOp3(order3);
            fileManager.updateOrder(seqIdNum, 3, order3);

            long elapsed3;
            String orderId3 = "------";

            op3SentTime = System.currentTimeMillis();
            if (sequenceDisplay != null) {
                elapsed3 = 0;
                sequenceDisplay.showOrderStatus(seqIdNum, 3, symbol3, side3, adjQty3, adjPrice3, "OPENED", elapsed3, orderId3, orderType3);
            }

            OrderResult result3 = placeAndMonitorOrder(seqIdNum, symbol3, side3, orderType3, adjQty3, adjPrice3, seqIdNum, 3, quoteOrderQty);

            elapsed3 = System.currentTimeMillis() - op3SentTime;
            orderId3 = result3.getOrderId();

            if (!"FILLED".equals(result3.getStatus())) {
                if (sequenceDisplay != null) {
                    sequenceDisplay.showOrderStatus(seqIdNum, 3, symbol3, side3, adjQty3, adjPrice3, result3.getStatus(), elapsed3, orderId3, orderType3);
                }
                handleSequenceError(seqIdNum, "OP3 not filled: " + result3.getStatus());
                return;
            }

            if (sequenceDisplay != null) {
                sequenceDisplay.showOrderStatus(seqIdNum, 3, symbol3, side3, result3.getExecutedQty(), result3.getPrice(), "FILLED", elapsed3, orderId3, orderType3);
            }

            order3.setOrderStatus(EstadoOrden.FILLED);
            order3.setCantidadEjecutada(result3.getExecutedQty());
            order3.setPrecioEjecutado(result3.getPrice());
            order3.setBinanceOrderId(result3.getOrderId());
            order3.setTimestampEjecucion(result3.getUpdateTime());
            order3.setTiempoTranscurridoMs(result3.getUpdateTime() > 0 && result3.getTransactTime() > 0
                ? result3.getUpdateTime() - result3.getTransactTime()
                : result3.getElapsedTime());
            order3.setComisionAsset(result3.getCommissionAsset());
            order3.setComisionMonto(result3.getCommissionAmount());
            fileManager.updateOrder(seqIdNum, 3, order3);

            double finalQty = result3.getExecutedQty();
            double finalPrice = result3.getPrice();
            double receivedUsdt = finalQty * finalPrice;
            double profitRealizado = receivedUsdt - config.getBalancePerTrade();

            sequence.close(profitRealizado);

            fileManager.delete(seqIdNum);
            fileManager.appendEvent(sequence);

            Log.debug(TAG, "Sequence closed: #" + seqIdNum + ", profit=" + profitRealizado);

            if (sequenceDisplay != null) {
                sequenceDisplay.showSequenceEstado(seqIdNum, "CERRADA");
                sequenceDisplay.showEnd(seqIdNum, true, profitPct);
            }

        } catch (Exception e) {
            Log.error(TAG, "Error in sequence: " + e.getMessage());
            handleSequenceError(seqIdNum, e.getMessage());
        }
    }

    private double[] recalculateChainForLotSize(double adjQty1, double adjQty2, double adjQty3,
                                                String symbol2, String symbol3,
                                                double price1, double price2, double price3,
                                                boolean isForward) {
        double feeOp1 = config.getFeeOp1() / 100.0;
        double feeOp2 = config.getFeeOp2() / 100.0;

        try {
            if (isForward) {
                double afterFee1 = adjQty1 * (1 - feeOp1);
                double step2Raw = afterFee1 / price2;
                double adjStep2 = apiClient.adjustQuantityToLotSize(symbol2, step2Raw);
                if (adjStep2 < step2Raw * 0.5) {
                    Log.warn(TAG, "LOT_SIZE step 2 adjustment too aggressive: " + step2Raw + " -> " + adjStep2);
                    return null;
                }

                double minNotional2 = apiClient.getMinNotionalOrZero(symbol2);
                if (minNotional2 > 0.0 && adjStep2 * price2 < minNotional2) {
                    double minStep2 = apiClient.adjustQuantityToLotSize(symbol2, minNotional2 / price2);
                    adjStep2 = Math.max(adjStep2, minStep2);
                }

                double afterFee2 = adjStep2 * (1 - feeOp2);
                double adjStep3 = apiClient.adjustQuantityToLotSize(symbol3, afterFee2);
                if (adjStep3 < afterFee2 * 0.5) {
                    Log.warn(TAG, "LOT_SIZE step 3 adjustment too aggressive: " + afterFee2 + " -> " + adjStep3);
                    return null;
                }

                double minNotional3 = apiClient.getMinNotional(symbol3);
                if (minNotional3 > 0.0 && adjStep3 * price3 < minNotional3) {
                    double minStep3 = apiClient.adjustQuantityToLotSize(symbol3, minNotional3 / price3);
                    adjStep3 = Math.max(adjStep3, minStep3);
                }

                return new double[]{adjQty1, adjStep2, adjStep3};
            } else {
                double afterFee1 = adjQty1 * (1 - feeOp1);
                double adjStep2 = apiClient.adjustQuantityToLotSize(symbol2, afterFee1);
                if (adjStep2 < afterFee1 * 0.5) {
                    Log.warn(TAG, "LOT_SIZE step 2 adjustment too aggressive: " + afterFee1 + " -> " + adjStep2);
                    return null;
                }

                double minNotional2 = apiClient.getMinNotionalOrZero(symbol2);
                if (minNotional2 > 0.0 && adjStep2 * price2 * price3 < minNotional2) {
                    double minStep2 = apiClient.adjustQuantityToLotSize(symbol2, minNotional2 / (price2 * price3));
                    adjStep2 = Math.max(adjStep2, minStep2);
                }

                double bnbReceived = adjStep2 * price2;
                double afterFee2 = bnbReceived * (1 - feeOp2);
                double adjStep3 = apiClient.adjustQuantityToLotSize(symbol3, afterFee2);
                if (adjStep3 < afterFee2 * 0.5) {
                    Log.warn(TAG, "LOT_SIZE step 3 adjustment too aggressive: " + afterFee2 + " -> " + adjStep3);
                    return null;
                }

                double minNotional3 = apiClient.getMinNotional(symbol3);
                if (minNotional3 > 0.0 && adjStep3 * price3 < minNotional3) {
                    double minStep3 = apiClient.adjustQuantityToLotSize(symbol3, minNotional3 / price3);
                    adjStep3 = Math.max(adjStep3, minStep3);
                }

                return new double[]{adjQty1, adjStep2, adjStep3};
            }
        } catch (Exception e) {
            Log.warn(TAG, "Error recalculating chain: " + e.getMessage());
            return null;
        }
    }

    private double[] recalculateChainAfterOp1(double executedQty1, String symbol2, String symbol3,
                                              double price2, double price3, boolean isForward) {
        double feeOp1 = config.getFeeOp1() / 100.0;
        double feeOp2 = config.getFeeOp2() / 100.0;

        try {
            if (isForward) {
                double afterFee1 = executedQty1 * (1 - feeOp1);
                double step2Raw = afterFee1 / price2;
                double adjStep2 = apiClient.adjustQuantityToLotSize(symbol2, step2Raw);
                if (adjStep2 < step2Raw * 0.5) {
                    Log.warn(TAG, "AfterOp1 step 2 adjustment too aggressive: " + step2Raw + " -> " + adjStep2);
                    return null;
                }

                double minNotional2 = apiClient.getMinNotionalOrZero(symbol2);
                if (minNotional2 > 0.0 && adjStep2 * price2 < minNotional2) {
                    double requiredQty = apiClient.adjustQuantityToLotSize(symbol2, (minNotional2 * 3.0) / price2);
                    if (adjStep2 < requiredQty) {
                        adjStep2 = requiredQty;
                    }
                }

                double afterFee2 = adjStep2 * (1 - feeOp2);
                double adjStep3 = apiClient.adjustQuantityToLotSize(symbol3, afterFee2);
                if (adjStep3 < afterFee2 * 0.5) {
                    Log.warn(TAG, "AfterOp1 step 3 adjustment too aggressive: " + afterFee2 + " -> " + adjStep3);
                    return null;
                }

                double minNotional3 = apiClient.getMinNotionalOrZero(symbol3);
                if (minNotional3 > 0.0 && adjStep3 * price3 < minNotional3) {
                    double requiredQty3 = apiClient.adjustQuantityToLotSize(symbol3, (minNotional3 * 3.0) / price3);
                    if (adjStep3 < requiredQty3) {
                        adjStep3 = requiredQty3;
                    }
                }

                return new double[]{adjStep2, adjStep3};
            } else {
                double afterFee1 = executedQty1 * (1 - feeOp1);
                double adjStep2 = apiClient.adjustQuantityToLotSize(symbol2, afterFee1);
                if (adjStep2 < afterFee1 * 0.5) {
                    Log.warn(TAG, "AfterOp1 step 2 adjustment too aggressive: " + afterFee1 + " -> " + adjStep2);
                    return null;
                }

                double minNotional2 = apiClient.getMinNotionalOrZero(symbol2);
                if (minNotional2 > 0.0 && adjStep2 * price2 * price3 < minNotional2) {
                    double requiredQty = apiClient.adjustQuantityToLotSize(symbol2, (minNotional2 * 3.0) / (price2 * price3));
                    if (adjStep2 < requiredQty) {
                        adjStep2 = requiredQty;
                    }
                }

                double bnbReceived = adjStep2 * price2;
                double afterFee2 = bnbReceived * (1 - feeOp2);
                double adjStep3 = apiClient.adjustQuantityToLotSize(symbol3, afterFee2);
                if (adjStep3 < afterFee2 * 0.5) {
                    Log.warn(TAG, "AfterOp1 step 3 adjustment too aggressive: " + afterFee2 + " -> " + adjStep3);
                    return null;
                }

                double minNotional3 = apiClient.getMinNotionalOrZero(symbol3);
                if (minNotional3 > 0.0 && adjStep3 * price3 < minNotional3) {
                    double requiredQty3 = apiClient.adjustQuantityToLotSize(symbol3, (minNotional3 * 3.0) / price3);
                    if (adjStep3 < requiredQty3) {
                        adjStep3 = requiredQty3;
                    }
                }

                return new double[]{adjStep2, adjStep3};
            }
        } catch (Exception e) {
            Log.warn(TAG, "Error recalculating after OP1: " + e.getMessage());
            return null;
        }
    }

    private OrderResult placeAndMonitorOrder(int seqIdNum, String symbol, String side, String orderType,
                                          double qty, double price, int seqId, int opIndice, double quoteOrderQty)
            throws Exception {
        long startTime = System.currentTimeMillis();

        OrderResult placed = apiClient.placeOrder(symbol, side, orderType, qty, price, quoteOrderQty, realOrder);
        
        if (!placed.isSuccess()) {
            Log.error(TAG, "[Seq:#" + seqIdNum + "] Failed to place order: " + placed.getErrorMessage());
            return placed;
        }
        
        String orderId = placed.getOrderId();
        
        Log.debug(TAG, "[Seq:#" + seqIdNum + "] OP" + opIndice + " placed: " + symbol + " " + side + " " + orderType + " orderId=" + orderId);
        
        // Mostrar orden como OPEN/NEW mientras se espera el fill
        if (sequenceDisplay != null) {
            placed.setStatus("OPEN");
            sequenceDisplay.showOrderFilled(seqIdNum, opIndice, placed);
        }

        // Si no es orden real, ya está fill (simulada) - no hacer polling
        if (!realOrder) {
            placed.setStatus("FILLED");
            placed.setExecutedQty(qty);
            if (sequenceDisplay != null) {
                sequenceDisplay.showOrderFilled(seqIdNum, opIndice, placed);
            }
            return placed;
        }
        
        long pollingInterval = config.getPollingIntervalMs();
        long timeout = config.getOrderTimeoutMs();
        
        while (true) {
            long elapsed = System.currentTimeMillis() - startTime;
            
            if (elapsed > timeout) {
                Log.warn(TAG, "[Seq:#" + seqIdNum + "] OP" + opIndice + " timeout, canceling: " + orderId);
                apiClient.cancelOrder(symbol, orderId);
                placed.setStatus("TIMEOUT");
                return placed;
            }
            
            Thread.sleep(pollingInterval);
            
            OrderResult status = apiClient.queryOrder(symbol, orderId);
            String orderStatus = status.getStatus();
            
            Log.debug(TAG, "[Seq:#" + seqIdNum + "] Polling orderId=" + orderId + " status=" + orderStatus);
            
            if ("FILLED".equals(orderStatus)) {
                placed.setStatus("FILLED");
                placed.setExecutedQty(status.getExecutedQty());
                placed.setPrice(status.getPrice());
                Log.debug(TAG, "[Seq:#" + seqIdNum + "] OP" + opIndice + " FILLED in " + elapsed + "ms");
                if (sequenceDisplay != null) {
                    sequenceDisplay.showOrderFilled(seqIdNum, opIndice, placed);
                }
                return placed;
            }
            
            if ("CANCELED".equals(orderStatus) || "REJECTED".equals(orderStatus) || "EXPIRED".equals(orderStatus)) {
                placed.setStatus(orderStatus);
                if (sequenceDisplay != null) {
                    sequenceDisplay.showOrderFilled(seqIdNum, opIndice, placed);
                }
                return placed;
            }
        }
    }

    private void handleSequenceError(int seqId, String errorMessage) {
        if (sequenceDisplay != null) {
            sequenceDisplay.showSequenceEstado(seqId, "CANCELED");
        }
        try {
            TradingSequence sequence = fileManager.findById(seqId);
            if (sequence != null) {
                completeSequenceData(sequence, errorMessage);
                sequence.markCancelled();
                fileManager.appendEvent(sequence);
                fileManager.delete(seqId);
            }
            Log.error(TAG, "Sequence error handled: #" + seqId + " - " + errorMessage);
        } catch (Exception e) {
            Log.error(TAG, "Error handling sequence error: " + e.getMessage());
        }
    }

    private void completeSequenceData(TradingSequence sequence, String errorMessage) {
        long now = System.currentTimeMillis();
        long timestampCreacion = sequence.getTimestampInicio() + 100;

        if (sequence.getOp1() == null) {
            sequence.setOp1(createCancelledOrder(sequence.getSeqId(), 1, null, null, null, 0, 0, timestampCreacion, now));
        } else if (!sequence.getOp1().isFinal()) {
            sequence.getOp1().setOrderStatus(EstadoOrden.ERROR);
            sequence.getOp1().setTimestampEjecucion(now);
        }

        if (sequence.getOp2() == null) {
            sequence.setOp2(createCancelledOrder(sequence.getSeqId(), 2, null, null, null, 0, 0, timestampCreacion + 200, now));
        } else if (!sequence.getOp2().isFinal()) {
            sequence.getOp2().setOrderStatus(EstadoOrden.ERROR);
            sequence.getOp2().setPrecioEjecutado(0.0);
            sequence.getOp2().setCantidadEjecutada(0.0);
            sequence.getOp2().setTimestampEjecucion(now);
            sequence.getOp2().setTiempoTranscurridoMs(now - sequence.getOp2().getTimestampCreacion());
        }

        if (sequence.getOp3() == null) {
            sequence.setOp3(createCancelledOrder(sequence.getSeqId(), 3, null, null, null, 0, 0, timestampCreacion + 400, now));
        } else if (!sequence.getOp3().isFinal()) {
            sequence.getOp3().setOrderStatus(EstadoOrden.ERROR);
            sequence.getOp3().setPrecioEjecutado(0.0);
            sequence.getOp3().setCantidadEjecutada(0.0);
            sequence.getOp3().setTimestampEjecucion(now);
            sequence.getOp3().setTiempoTranscurridoMs(now - sequence.getOp3().getTimestampCreacion());
        }

        sequence.setProfitRealizado(0.0);
    }

    private SequenceOrder createCancelledOrder(int seqId, int opIndice, String symbol, String side,
                                            String type, double quantity, double price,
                                            long timestampCreacion, long timestampEjecucion) {
        return SequenceOrder.builder()
                .seqId(seqId)
                .opIndice(opIndice)
                .symbol(symbol)
                .side(side)
                .type(type)
                .quantity(quantity)
                .price(price)
                .orderStatus(EstadoOrden.CANCELED)
                .cantidadEjecutada(0.0)
                .precioEjecutado(0.0)
                .comisionAsset(null)
                .comisionMonto(0.0)
                .timestampCreacion(timestampCreacion)
                .timestampEjecucion(timestampEjecucion)
                .tiempoTranscurridoMs(timestampEjecucion - timestampCreacion)
                .build();
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
        void showOrderPending(int seqId, int opNum, String symbol, String side, double qty, double price, String orderType);
        void showOrderFilled(int seqId, int opNum, OrderResult r);
        void showEnd(int sequenceId, boolean success, double profitPct);
        void printSequenceAtomic(int sequenceId, long timestamp, boolean live, List<OrderResult> orders, double profitPct);
        void showOrderStatus(int seqId, int opNum, String symbol, String side, double qty, double price, String status, long elapsedMs, String orderId, String orderType);
        void showSequenceEstado(int seqId, String estado);
    }

    public interface OpportunityDisplay {
        void show(int orderId, long timestamp,
                 OrderResult op1, OrderResult op2, OrderResult op3,
                 double profitPct, boolean live);
    }
}