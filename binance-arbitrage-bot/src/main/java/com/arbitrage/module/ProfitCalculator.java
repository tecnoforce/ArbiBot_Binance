package com.arbitrage.module;

import com.arbitrage.config.AppConfig;
import com.arbitrage.engine.PrecisionAdjuster;
import com.arbitrage.model.ArbitrageOpportunity;
import com.arbitrage.model.Ticker;
import com.arbitrage.model.Triangle;
import com.arbitrage.util.Log;
import lombok.Builder;
import lombok.Data;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * ProfitCalculator - Calculo de profit con fees y slippage.
 *
 * Responsabilidades:
 * - Calcular profit porcentual de triangulos
 * - Modelar comisiones (maker/taker)
 * - Estimar slippage basado en profundidad del book
 * - Validar rangos de profit (filtrar errores)
 *
 * Backward compatible: coexiste con engine/ProfitCalculator
 */
public class ProfitCalculator {
    private static final String TAG = "ProfitCalc";

    private final AppConfig config;
    private PrecisionAdjuster precisionAdjuster;

    private static final double UNIT = 1.0;

    private final AtomicInteger totalCalculated;
    private final AtomicInteger totalNull;
    private final AtomicInteger totalPrecisionFiltered;
    private final AtomicInteger totalInvalidRawProduct;
    private final AtomicInteger totalSlippageFiltered;
    private final AtomicLong startTime;
    private long lastProfitLog = 0;
    private int logCounter = 0;

    private final double defaultSlippagePct;
    private final double slippageThresholdQty;

    public ProfitCalculator(AppConfig config) {
        this(config, 0.1, 1.0);
    }

    public ProfitCalculator(AppConfig config, double defaultSlippage, double slippageThreshold) {
        this.config = config;
        this.defaultSlippagePct = defaultSlippage;
        this.slippageThresholdQty = slippageThreshold;
        this.totalCalculated = new AtomicInteger(0);
        this.totalNull = new AtomicInteger(0);
        this.totalPrecisionFiltered = new AtomicInteger(0);
        this.totalInvalidRawProduct = new AtomicInteger(0);
        this.totalSlippageFiltered = new AtomicInteger(0);
        this.startTime = new AtomicLong(System.currentTimeMillis());
    }

    public void setPrecisionAdjuster(PrecisionAdjuster precisionAdjuster) {
        this.precisionAdjuster = precisionAdjuster;
    }

    public ArbitrageOpportunity calculate(
            Triangle triangle,
            double balance,
            Ticker ticker1,
            Ticker ticker2,
            Ticker ticker3
    ) {
        return calculate(triangle, balance, ticker1, ticker2, ticker3, 0, 0, 0);
    }

    public ArbitrageOpportunity calculate(
            Triangle triangle,
            double balance,
            Ticker ticker1,
            Ticker ticker2,
            Ticker ticker3,
            double slippageOp1,
            double slippageOp2,
            double slippageOp3
    ) {
        totalCalculated.incrementAndGet();

        double feeOp1 = config.getFeeOp1();
        double feeOp2 = config.getFeeOp2();
        double feeOp3 = config.getFeeOp3();

        boolean isForward = triangle.isForward();

        double price1 = ticker1.getAskPrice();
        double price2 = isForward ? ticker2.getAskPrice() : ticker2.getBidPrice();
        double price3 = ticker3.getBidPrice();

        if (price1 <= 0 || price2 <= 0 || price3 <= 0) {
            totalNull.incrementAndGet();
            return null;
        }

        double effectivePrice1 = applySlippage(price1, slippageOp1, true);
        double effectivePrice2 = applySlippage(price2, slippageOp2, !isForward);
        double effectivePrice3 = applySlippage(price3, slippageOp3, false);

        double rawProduct;

        if (isForward) {
            rawProduct = (effectivePrice2 * effectivePrice3) / effectivePrice1;
        } else {
            rawProduct = effectivePrice3 / (effectivePrice1 * effectivePrice2);
        }

        if (rawProduct <= 0 || rawProduct > 10_000_000_000L || Double.isInfinite(rawProduct) || Double.isNaN(rawProduct)) {
            totalInvalidRawProduct.incrementAndGet();
            totalNull.incrementAndGet();
            logWarning(triangle, "invalid rawProduct=" + rawProduct, price1, price2, price3);
            return null;
        }

        double feeFactor = (1.0 - feeOp1 / 100.0) * (1.0 - feeOp2 / 100.0) * (1.0 - feeOp3 / 100.0);
        double profitPct = (rawProduct * feeFactor - 1.0) * 100.0;

        if (profitPct > 100.0 || profitPct < -10.0) {
            totalPrecisionFiltered.incrementAndGet();
            logDebug(triangle, "filtered profitPct=" + profitPct);
            return null;
        }

        double step1Qty, step2Qty, step2AfterFee, step3Qty, step3Price;

        step1Qty = balance / effectivePrice1;
        double step1AfterFee = step1Qty * (1.0 - feeOp1 / 100.0);

        if (isForward) {
            step2Qty = (step1AfterFee / effectivePrice1) * effectivePrice2;
            step2AfterFee = step2Qty * (1.0 - feeOp2 / 100.0);
            step3Qty = step2AfterFee / effectivePrice3;
        } else {
            step2Qty = (step1AfterFee / effectivePrice1) * effectivePrice2;
            step2AfterFee = step2Qty * (1.0 - feeOp2 / 100.0);
            step3Qty = step2AfterFee / effectivePrice3;
        }

        step3Price = effectivePrice3;

        double finalQty = step3Qty * step3Price;
        double finalAfterFee = finalQty * (1.0 - feeOp3 / 100.0);

        ArbitrageOpportunity result = ArbitrageOpportunity.builder()
                .triangle(triangle)
                .profitPct(profitPct)
                .balanceUsed(balance)
                .finalBalance(balance * (1.0 + profitPct / 100.0))
                .step1Qty(step1Qty)
                .step1Price(effectivePrice1)
                .step2Qty(step2Qty)
                .step2Price(effectivePrice2)
                .step3Qty(step3Qty)
                .step3Price(step3Price)
                .timestamp(System.currentTimeMillis())
                .build();

        if (precisionAdjuster != null) {
            ArbitrageOpportunity adjusted = precisionAdjuster.adjust(result, triangle);
            if (adjusted == null) {
                totalPrecisionFiltered.incrementAndGet();
                totalNull.incrementAndGet();
            }
            return adjusted;
        }
        return result;
    }

    public double calculateSlippage(String symbol, String side, double qty, Ticker ticker) {
        if (ticker == null || qty <= 0) {
            return defaultSlippagePct;
        }

        double availableQty = "SELL".equalsIgnoreCase(side) ? ticker.getBidQty() : ticker.getAskQty();
        double price = "SELL".equalsIgnoreCase(side) ? ticker.getBidPrice() : ticker.getAskPrice();

        if (availableQty <= 0 || price <= 0) {
            return defaultSlippagePct;
        }

        if (qty <= availableQty) {
            return 0.0;
        }

        double ratio = qty / availableQty;
        double slippagePct;

        if (ratio <= 1.5) {
            slippagePct = 0.05;
        } else if (ratio <= 2.0) {
            slippagePct = 0.15;
        } else if (ratio <= 3.0) {
            slippagePct = 0.3;
        } else {
            slippagePct = Math.min(2.0, defaultSlippagePct * (ratio - 1));
        }

        return slippagePct;
    }

    private double applySlippage(double price, double slippagePct, boolean adverse) {
        if (slippagePct <= 0) {
            return price;
        }

        double factor = slippagePct / 100.0;

        if (adverse) {
            return price * (1.0 + factor);
        } else {
            return price * (1.0 - factor * 0.5);
        }
    }

    private void logWarning(Triangle triangle, String msg, double p1, double p2, double p3) {
        long now = System.currentTimeMillis();
        if (now - lastProfitLog > 10000 && logCounter < 5 && !Log.isScanEnabled()) {
            lastProfitLog = now;
            logCounter++;
            System.out.println("[WARN] " + triangle.getId() + " " + msg + " (p1=" + p1 + " p2=" + p2 + " p3=" + p3 + ")");
        }
    }

    private void logDebug(Triangle triangle, String msg) {
        long now = System.currentTimeMillis();
        if (now - startTime.get() > 30000 && now - lastProfitLog > 15000 && logCounter < 5 && !Log.isScanEnabled()) {
            lastProfitLog = now;
            logCounter++;
            System.out.println("[DEBUG] Profit " + triangle.getId() + ": " + msg);
        }
    }

    public CalculatedProfit calculateDetailed(
            Triangle triangle,
            double balance,
            Ticker t1, Ticker t2, Ticker t3
    ) {
        double slip1 = calculateSlippage(triangle.getSymbol1(), "BUY", balance / t1.getAskPrice(), t1);
        double slip2 = calculateSlippage(triangle.getSymbol2(), "SELL", 0, t2);
        double slip3 = calculateSlippage(triangle.getSymbol3(), "SELL", 0, t3);

        ArbitrageOpportunity opp = calculate(triangle, balance, t1, t2, t3, slip1, slip2, slip3);

        if (opp == null) {
            return CalculatedProfit.builder()
                    .valid(false)
                    .rawProfit(0)
                    .netProfit(0)
                    .slippageCost(0)
                    .feeCost(0)
                    .build();
        }

        double feeCost = balance * (config.getFeeOp1() + config.getFeeOp2() + config.getFeeOp3()) / 100.0 * 3;
        double slippageCost = balance * (slip1 + slip2 + slip3) / 100.0;
        double netProfit = balance * (opp.getProfitPct() / 100.0) - feeCost - slippageCost;

        return CalculatedProfit.builder()
                .valid(true)
                .opportunity(opp)
                .rawProfit(opp.getProfitPct())
                .netProfit(netProfit)
                .slippageCost(slippageCost)
                .feeCost(feeCost)
                .slippage1(slip1)
                .slippage2(slip2)
                .slippage3(slip3)
                .timestamp(System.currentTimeMillis())
                .build();
    }

    public int getTotalCalculated() { return totalCalculated.get(); }
    public int getTotalNull() { return totalNull.get(); }
    public int getTotalPrecisionFiltered() { return totalPrecisionFiltered.get(); }
    public int getTotalInvalidRawProduct() { return totalInvalidRawProduct.get(); }
    public int getTotalSlippageFiltered() { return totalSlippageFiltered.get(); }

    public void resetStats() {
        totalCalculated.set(0);
        totalNull.set(0);
        totalPrecisionFiltered.set(0);
        totalInvalidRawProduct.set(0);
        totalSlippageFiltered.set(0);
        startTime.set(System.currentTimeMillis());
    }

    public ProfitStats getStats() {
        int total = totalCalculated.get();
        int nulls = totalNull.get();
        double successRate = total > 0 ? ((total - nulls) * 100.0 / total) : 0;

        return ProfitStats.builder()
            .totalCalculated(total)
            .totalValid(total - nulls)
            .totalFiltered(nulls)
            .successRate(successRate)
            .invalidRawProduct(totalInvalidRawProduct.get())
            .precisionFiltered(totalPrecisionFiltered.get())
            .slippageFiltered(totalSlippageFiltered.get())
            .build();
    }

    public void logStats() {
        ProfitStats stats = getStats();
        Log.info(TAG, "=== ProfitCalculator Stats ===");
        Log.info(TAG, "  Calculated: " + stats.getTotalCalculated());
        Log.info(TAG, "  Valid: " + stats.getTotalValid());
        Log.info(TAG, "  Filtered: " + stats.getTotalFiltered());
        Log.info(TAG, "  Success rate: " + String.format("%.2f%%", stats.getSuccessRate()));
    }

    @Data
    @Builder
    public static class CalculatedProfit {
        private boolean valid;
        private ArbitrageOpportunity opportunity;
        private double rawProfit;
        private double netProfit;
        private double slippageCost;
        private double feeCost;
        private double slippage1;
        private double slippage2;
        private double slippage3;
        private long timestamp;
    }

    @Data
    @Builder
    public static class ProfitStats {
        private int totalCalculated;
        private int totalValid;
        private int totalFiltered;
        private double successRate;
        private int invalidRawProduct;
        private int precisionFiltered;
        private int slippageFiltered;

        public int getTotalCalculated() { return totalCalculated; }
        public int getTotalValid() { return totalValid; }
        public int getTotalFiltered() { return totalFiltered; }
        public double getSuccessRate() { return successRate; }
    }
}