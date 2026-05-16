package com.arbitrage.trading;

import com.arbitrage.model.OrderResult;

/**
 * @deprecated Usar {@link com.arbitrage.module.ExecutionEngine} en su lugar.
 * Esta clase existe solo para compatibilidad con código existente.
 */
@Deprecated
public class OrderExecutor {
    /**
     * @deprecated Usar {@link com.arbitrage.module.ExecutionEngine.SequenceDisplay} en su lugar.
     */
    @Deprecated
    public interface SequenceDisplay {
        void showStart(int sequenceId, long timestamp, double profitPct, boolean live);
        void showOrderStatus(int seqId, int opNum, String symbol, String side, double qty, double price,
                            String status, long elapsedMs, String orderId, String orderType);
        void showSequenceEstado(int seqId, String estado);
        void showEnd(int sequenceId, boolean success, double profitPct);
    }

    /**
     * @deprecated Usar {@link com.arbitrage.module.ExecutionEngine.OpportunityDisplay} en su lugar.
     */
    @Deprecated
    public interface OpportunityDisplay {
        void show(int orderId, long timestamp,
                 OrderResult op1, OrderResult op2, OrderResult op3,
                 double profitPct, boolean live);
    }
}