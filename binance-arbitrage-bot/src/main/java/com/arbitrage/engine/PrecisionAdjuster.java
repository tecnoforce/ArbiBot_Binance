package com.arbitrage.engine;

import com.arbitrage.model.ArbitrageOpportunity;
import com.arbitrage.model.Triangle;
import com.arbitrage.trading.BinanceApiClient;

public class PrecisionAdjuster {
    private final BinanceApiClient apiClient;
    private static final double MIN_NOTIONAL_SCALE = 0.0001;
    private final double feeOp1;
    private final double feeOp2;
    private final double feeOp3;

    public PrecisionAdjuster(BinanceApiClient apiClient, double balancePerTrade, double feeOp1, double feeOp2, double feeOp3) {
        this.apiClient = apiClient;
        this.feeOp1 = feeOp1;
        this.feeOp2 = feeOp2;
        this.feeOp3 = feeOp3;
    }

    public ArbitrageOpportunity adjust(ArbitrageOpportunity raw, Triangle triangle) {
        String symbol1 = triangle.getSymbol1();
        String symbol2 = triangle.getSymbol2();
        String symbol3 = triangle.getSymbol3();

        double qty1 = raw.getStep1Qty();
        double qty2 = raw.getStep2Qty();
        double qty3 = raw.getStep3Qty();
        double price1 = raw.getStep1Price();
        double price2 = raw.getStep2Price();
        double price3 = raw.getStep3Price();

        double adjQty1 = apiClient.adjustQuantityToLotSize(symbol1, qty1);
        double adjPrice2 = apiClient.adjustPriceToTickSize(symbol2, price2);
        double adjQty2 = apiClient.adjustQuantityToLotSize(symbol2, qty2);
        double adjPrice3 = apiClient.adjustPriceToTickSize(symbol3, price3);
        double adjQty3 = apiClient.adjustQuantityToLotSize(symbol3, qty3);

        boolean isForward = triangle.isForward();

        if (!validateStep(symbol2, adjQty2, adjPrice2) ||
            !validateStep(symbol3, adjQty3, adjPrice3)) {
            return null;
        }

        if (isForward) {
            double afterFee1 = adjQty1 * (1 - feeOp1 / 100.0);
            double step2Raw = afterFee1 / price2;
            double adjStep2 = apiClient.adjustQuantityToLotSize(symbol2, step2Raw);

            if (!validateStep(symbol2, adjStep2, adjPrice2)) {
                return null;
            }

            double afterFee2 = adjStep2 * (1 - feeOp2 / 100.0);
            double step3Raw = afterFee2 * price3;
            double adjStep3 = apiClient.adjustQuantityToLotSize(symbol3, step3Raw);

            if (!validateStep(symbol3, adjStep3, adjPrice3)) {
                return null;
            }

            adjQty2 = adjStep2;
            adjQty3 = adjStep3;
        } else {
            double afterFee1 = adjQty1 * (1 - feeOp1 / 100.0);
            double step2Raw = afterFee1 * price2;
            double adjStep2 = apiClient.adjustQuantityToLotSize(symbol2, step2Raw);

            double adjPrice2Check = apiClient.adjustPriceToTickSize(symbol2, price2);
            if (!validateStep(symbol2, adjStep2, adjPrice2Check)) {
                return null;
            }

            double afterFee2 = adjStep2 * (1 - feeOp2 / 100.0);
            double step3Raw = afterFee2;
            double adjStep3 = apiClient.adjustQuantityToLotSize(symbol3, step3Raw);

            if (!validateStep(symbol3, adjStep3, adjPrice3)) {
                return null;
            }

            adjQty2 = adjStep2;
            adjQty3 = adjStep3;
        }

        return ArbitrageOpportunity.builder()
                .triangle(raw.getTriangle())
                .profitPct(raw.getProfitPct())
                .balanceUsed(raw.getBalanceUsed())
                .finalBalance(raw.getFinalBalance())
                .step1Qty(adjQty1)
                .step1Price(raw.getStep1Price())
                .step2Qty(adjQty2)
                .step2Price(adjPrice2)
                .step3Qty(adjQty3)
                .step3Price(adjPrice3)
                .timestamp(raw.getTimestamp())
                .build();
    }

    private boolean validateStep(String symbol, double qty, double price) {
        double threshold = getMinNotionalThreshold(symbol);
        if (threshold > 0) {
            double notional = qty * price;
            if (notional < threshold) {
                return false;
            }
        }
        return true;
    }

    private double getMinNotionalThreshold(String symbol) {
        double minNotional = apiClient.getMinNotionalOrZero(symbol);
        if (minNotional <= 0) {
            return 0;
        }
        return minNotional * MIN_NOTIONAL_SCALE;
    }
}