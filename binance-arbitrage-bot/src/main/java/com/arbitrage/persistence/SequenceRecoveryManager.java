package com.arbitrage.persistence;

import com.arbitrage.config.AppConfig;
import com.arbitrage.model.*;
import com.arbitrage.trading.BinanceApiClient;
import com.arbitrage.trading.OrderExecutor;
import com.arbitrage.util.Log;

import java.util.List;

public class SequenceRecoveryManager {
    private static final String TAG = "RECOVERY";
    
    private final BinanceApiClient apiClient;
    private final SequenceFileManager fileManager;
    private final OrderExecutor orderExecutor;
    private final AppConfig config;
    
    public SequenceRecoveryManager(BinanceApiClient apiClient, SequenceFileManager fileManager, 
                                   OrderExecutor orderExecutor, AppConfig config) {
        this.apiClient = apiClient;
        this.fileManager = fileManager;
        this.orderExecutor = orderExecutor;
        this.config = config;
    }
    
    public void loadAndRecoverSequences() {
        Log.info(TAG, "=== Starting Sequence Recovery ===");
        
        try {
            List<TradingSequence> openSequences = fileManager.findOpenLegacy();
            
            if (openSequences.isEmpty()) {
                Log.info(TAG, "No open sequences found in legacy file");
                return;
            }
            
            Log.info(TAG, "Found " + openSequences.size() + " open sequences to recover");
            
            int recovered = 0;
            int failed = 0;
            
            for (TradingSequence seq : openSequences) {
                try {
                    boolean success = recoverSequence(seq);
                    if (success) {
                        recovered++;
                    } else {
                        failed++;
                    }
                } catch (Exception e) {
                    Log.error(TAG, "Error recovering sequence: " + e.getMessage());
                    handleSequenceError(seq, "Recovery error: " + e.getMessage());
                    failed++;
                }
            }
            
            Log.info(TAG, "Recovery complete: " + recovered + " recovered, " + failed + " failed");
            
        } catch (Exception e) {
            Log.error(TAG, "Fatal error during recovery: " + e.getMessage());
        }
    }
    
    private boolean recoverSequence(TradingSequence seq) {
        String seqIdDisplay = seq.getSeqIdString() != null ? seq.getSeqIdString() : "#" + seq.getSeqId();
        Log.info(TAG, "Processing sequence: " + seqIdDisplay);
        
        int nextOp = seq.getNextPendingOpIndex();
        Log.info(TAG, "Next operation to process: OP" + nextOp);
        
        if (nextOp > 3) {
            Log.warn(TAG, "Sequence already completed: " + seqIdDisplay);
            seq.close(seq.getProfitRealizado());
            moveToEvents(seq);
            return true;
        }
        
        switch (nextOp) {
            case 1:
                return recoverOp1(seq);
            case 2:
                return recoverOp2(seq);
            case 3:
                return recoverOp3(seq);
            default:
                Log.error(TAG, "Invalid next operation: " + nextOp);
                return false;
        }
    }
    
    private boolean recoverOp1(TradingSequence seq) {
        String seqIdDisplay = seq.getSeqIdString() != null ? seq.getSeqIdString() : "#" + seq.getSeqId();
        
        if (!seq.hasOp1BeenSent()) {
            Log.warn(TAG, "OP1 never sent to Binance for: " + seqIdDisplay);
            Log.warn(TAG, "binanceOrderId is null - marking as error");
            handleSequenceError(seq, "OP1 never sent to Binance");
            return false;
        }
        
        String orderId = seq.getOp1().getBinanceOrderId();
        String symbol = seq.getOp1().getSymbol();
        
        Log.info(TAG, "Checking OP1 status in Binance: orderId=" + orderId + " symbol=" + symbol);
        
        try {
            OrderResult status = apiClient.queryOrder(symbol, orderId);
            String orderStatus = status.getStatus();
            
            Log.info(TAG, "OP1 current status: " + orderStatus);
            
            if ("FILLED".equals(orderStatus)) {
                seq.getOp1().setOrderStatus(EstadoOrden.FILLED);
                seq.getOp1().setCantidadEjecutada(status.getExecutedQty());
                seq.getOp1().setPrecioEjecutado(status.getPrice());
                seq.getOp1().setTiempoTranscurridoMs(status.getElapsedTime());
                
                Log.info(TAG, "OP1 already FILLED, continuing to OP2");
                return recoverOp2(seq);
            }
            
            if ("CANCELED".equals(orderStatus) || "REJECTED".equals(orderStatus) || "EXPIRED".equals(orderStatus)) {
                Log.warn(TAG, "OP1 final state: " + orderStatus);
                handleSequenceError(seq, "OP1 " + orderStatus);
                return false;
            }
            
            if ("NEW".equals(orderStatus) || "OPEN".equals(orderStatus)) {
                Log.info(TAG, "OP1 still OPEN, waiting for fill...");
                return continuePolling(seq, 1, orderId, symbol);
            }
            
            Log.warn(TAG, "Unknown OP1 status: " + orderStatus);
            handleSequenceError(seq, "Unknown OP1 status: " + orderStatus);
            return false;
            
        } catch (Exception e) {
            Log.error(TAG, "Error querying OP1: " + e.getMessage());
            handleSequenceError(seq, "Error querying OP1: " + e.getMessage());
            return false;
        }
    }
    
    private boolean recoverOp2(TradingSequence seq) {
        String seqIdDisplay = seq.getSeqIdString() != null ? seq.getSeqIdString() : "#" + seq.getSeqId();
        
        if (seq.getOp2() == null) {
            Log.info(TAG, "OP2 was never created, creating now for: " + seqIdDisplay);
            return continueExecution(seq, 2);
        }
        
        if (seq.getOp2().getBinanceOrderId() == null || seq.getOp2().getBinanceOrderId().isEmpty()) {
            Log.warn(TAG, "OP2 never sent to Binance");
            return continueExecution(seq, 2);
        }
        
        String orderId = seq.getOp2().getBinanceOrderId();
        String symbol = seq.getOp2().getSymbol();
        
        try {
            OrderResult status = apiClient.queryOrder(symbol, orderId);
            String orderStatus = status.getStatus();
            
            Log.info(TAG, "OP2 current status: " + orderStatus);
            
            if ("FILLED".equals(orderStatus)) {
                seq.getOp2().setOrderStatus(EstadoOrden.FILLED);
                seq.getOp2().setCantidadEjecutada(status.getExecutedQty());
                seq.getOp2().setPrecioEjecutado(status.getPrice());
                return recoverOp3(seq);
            }
            
            if ("CANCELED".equals(orderStatus) || "REJECTED".equals(orderStatus) || "EXPIRED".equals(orderStatus)) {
                handleSequenceError(seq, "OP2 " + orderStatus);
                return false;
            }
            
            return continuePolling(seq, 2, orderId, symbol);
            
        } catch (Exception e) {
            Log.error(TAG, "Error querying OP2: " + e.getMessage());
            return continueExecution(seq, 2);
        }
    }
    
    private boolean recoverOp3(TradingSequence seq) {
        String seqIdDisplay = seq.getSeqIdString() != null ? seq.getSeqIdString() : "#" + seq.getSeqId();
        
        if (seq.getOp3() == null) {
            Log.info(TAG, "OP3 was never created, creating now for: " + seqIdDisplay);
            return continueExecution(seq, 3);
        }
        
        if (seq.getOp3().getBinanceOrderId() == null || seq.getOp3().getBinanceOrderId().isEmpty()) {
            Log.warn(TAG, "OP3 never sent to Binance");
            return continueExecution(seq, 3);
        }
        
        String orderId = seq.getOp3().getBinanceOrderId();
        String symbol = seq.getOp3().getSymbol();
        
        try {
            OrderResult status = apiClient.queryOrder(symbol, orderId);
            String orderStatus = status.getStatus();
            
            Log.info(TAG, "OP3 current status: " + orderStatus);
            
            if ("FILLED".equals(orderStatus)) {
                completeSequence(seq, status);
                return true;
            }
            
            if ("CANCELED".equals(orderStatus) || "REJECTED".equals(orderStatus) || "EXPIRED".equals(orderStatus)) {
                handleSequenceError(seq, "OP3 " + orderStatus);
                return false;
            }
            
            return continuePolling(seq, 3, orderId, symbol);
            
        } catch (Exception e) {
            Log.error(TAG, "Error querying OP3: " + e.getMessage());
            return continueExecution(seq, 3);
        }
    }
    
    private boolean continuePolling(TradingSequence seq, int opIndex, String orderId, String symbol) {
        Log.info(TAG, "Continuing polling for OP" + opIndex + " until FILLED or TIMEOUT");
        
        long pollingInterval = config.getPollingIntervalMs();
        long timeout = config.getOrderTimeoutMs();
        long startTime = System.currentTimeMillis();
        
        try {
            while (true) {
                long elapsed = System.currentTimeMillis() - startTime;
                
                if (elapsed > timeout) {
                    Log.warn(TAG, "Polling timeout for OP" + opIndex);
                    apiClient.cancelOrder(symbol, orderId);
                    handleSequenceError(seq, "OP" + opIndex + " timeout");
                    return false;
                }
                
                Thread.sleep(pollingInterval);
                
                OrderResult status = apiClient.queryOrder(symbol, orderId);
                String orderStatus = status.getStatus();
                
                Log.debug(TAG, "Polling OP" + opIndex + ": " + orderStatus);
                
                if ("FILLED".equals(orderStatus)) {
                    SequenceOrder orden = seq.getOrden(opIndex);
                    if (orden != null) {
                        orden.setOrderStatus(EstadoOrden.FILLED);
                        orden.setCantidadEjecutada(status.getExecutedQty());
                        orden.setPrecioEjecutado(status.getPrice());
                        orden.setTiempoTranscurridoMs(status.getElapsedTime());
                    }
                    
                    Log.info(TAG, "OP" + opIndex + " FILLED after " + elapsed + "ms");
                    
                    if (opIndex == 3) {
                        completeSequence(seq, status);
                        return true;
                    } else {
                        return recoverSequence(seq);
                    }
                }
                
                if ("CANCELED".equals(orderStatus) || "REJECTED".equals(orderStatus) || "EXPIRED".equals(orderStatus)) {
                    handleSequenceError(seq, "OP" + opIndex + " " + orderStatus);
                    return false;
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            handleSequenceError(seq, "Polling interrupted");
            return false;
        } catch (Exception e) {
            Log.error(TAG, "Error during polling: " + e.getMessage());
            handleSequenceError(seq, "Polling error: " + e.getMessage());
            return false;
        }
    }
    
    private boolean continueExecution(TradingSequence seq, int opIndex) {
        String seqIdDisplay = seq.getSeqIdString() != null ? seq.getSeqIdString() : "#" + seq.getSeqId();
        Log.info(TAG, "Continuing execution at OP" + opIndex + " for: " + seqIdDisplay);
        
        Log.warn(TAG, "Need to implement continueExecution - creating new order for OP" + opIndex);
        handleSequenceError(seq, "Manual recovery needed for OP" + opIndex);
        return false;
    }
    
    private void completeSequence(TradingSequence seq, OrderResult lastResult) {
        String seqIdDisplay = seq.getSeqIdString() != null ? seq.getSeqIdString() : "#" + seq.getSeqId();
        
        double finalQty = lastResult.getExecutedQty();
        double finalPrice = lastResult.getPrice();
        double receivedUsdt = finalQty * finalPrice;
        double profitRealizado = receivedUsdt - config.getBalancePerTrade();
        
        seq.close(profitRealizado);
        
        Log.info(TAG, "Sequence completed: " + seqIdDisplay + " profit=" + profitRealizado);
        
        moveToEvents(seq);
    }
    
    private void handleSequenceError(TradingSequence seq, String errorMessage) {
        try {
            seq.markError();
            moveToEvents(seq);
            Log.error(TAG, "Sequence error: " + errorMessage);
        } catch (Exception e) {
            Log.error(TAG, "Error handling sequence error: " + e.getMessage());
        }
    }
    
    private void moveToEvents(TradingSequence seq) {
        try {
            int seqId = seq.getSeqId();
            fileManager.delete(seqId);
            fileManager.appendEvent(seq);
        } catch (Exception e) {
            Log.error(TAG, "Error moving sequence to events: " + e.getMessage());
        }
    }
}