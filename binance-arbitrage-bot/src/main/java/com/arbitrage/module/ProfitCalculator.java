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
 * ProfitCalculator - Calculo preciso de rentabilidad en oportunidades de arbitraje.
 *
 * Responsabilidades:
 * - Calcular profit porcentual neto de un triangulo (2 rutas: forward e inverse)
 * - Aplicar comisiones (maker/taker) configuradas en cada paso (Op1, Op2, Op3)
 * - Estimar slippage basado en profundidad del order book
 * - Ajustar cantidades a precision de Binance (precisionAdjuster)
 * - Validar rangos de profit (filtrar resultados invalidos o extremos)
 *
 * Formula forward: rawProduct = (price2 * price3) / price1
 * Formula inverse: rawProduct = price3 / (price1 * price2)
 * profitPct = (rawProduct * feeFactor - 1.0) * 100.0
 * feeFactor = (1-fee1)*(1-fee2)*(1-fee3)
 *
 * Filtros de seguridad aplicados:
 * - Precios <= 0 -> null
 * - rawProduct > 10B, infinito, NaN -> null
 * - profitPct > 100% o < -10% -> filtrado por precision
 * - Ajuste de precision devuelve null si la cantidad es cero
 *
 * Integracion: Usado por ArbitrageDetector para evaluar cada triangulo.
 *              Tambien usado por ProfitCalculator.calculateDetailed() para
 *              desglose completo de costos (slippage + fees).
 *
 * Backward compatible: coexiste con engine/ProfitCalculator
 */
public class ProfitCalculator {
    /** Tag para logging */
    private static final String TAG = "ProfitCalc";

    /** Configuración global (fees, balance, etc.) */
    private final AppConfig config;
    /** Ajustador de precisión para lot sizes y tick sizes */
    private PrecisionAdjuster precisionAdjuster;

    /** Unidad base para cálculos (1.0 = 100%) */
    private static final double UNIT = 1.0;

    /** Contador total de cálculos realizados */
    private final AtomicInteger totalCalculated;
    /** Contador de cálculos que resultaron null (inválidos) */
    private final AtomicInteger totalNull;
    /** Contador de cálculos filtrados por precisión */
    private final AtomicInteger totalPrecisionFiltered;
    /** Contador de cálculos con rawProduct inválido */
    private final AtomicInteger totalInvalidRawProduct;
    /** Contador de cálculos filtrados por slippage excesivo */
    private final AtomicInteger totalSlippageFiltered;
    /** Timestamp de inicio (para control de logging) */
    private final AtomicLong startTime;
    /** Timestamp del último log de profit (control de frecuencia) */
    private long lastProfitLog = 0;
    /** Contador de logs emitidos (limitar a 5) */
    private int logCounter = 0;

    /** Porcentaje de slippage por defecto cuando no hay datos de book */
    private final double defaultSlippagePct;
    /** Umbral de cantidad para considerar slippage */
    private final double slippageThresholdQty;

    /**
     * Constructor con valores por defecto de slippage: 0.1%, umbral 1.0
     */
    public ProfitCalculator(AppConfig config) {
        this(config, 0.1, 1.0);
    }

    /**
     * Constructor completo con configuración de slippage.
     *
     * @param config              Configuración global
     * @param defaultSlippage     Slippage por defecto (%) cuando no hay datos de book
     * @param slippageThreshold   Umbral de cantidad para considerar slippage
     */
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

    /**
     * Establece el ajustador de precisión para validar cantidades contra lot size.
     *
     * @param precisionAdjuster Ajustador de precisión de Binance
     */
    public void setPrecisionAdjuster(PrecisionAdjuster precisionAdjuster) {
        this.precisionAdjuster = precisionAdjuster;
    }

    /**
     * Calcula oportunidad de arbitraje sin slippage adicional.
     * Delega al método completo con slippage=0 para las 3 operaciones.
     *
     * @param triangle Triángulo a evaluar
     * @param balance  Capital disponible para la operación
     * @param ticker1  Ticker del primer símbolo
     * @param ticker2  Ticker del segundo símbolo
     * @param ticker3  Ticker del tercer símbolo
     * @return ArbitrageOpportunity o null si es inválida
     */
    public ArbitrageOpportunity calculate(
            Triangle triangle,
            double balance,
            Ticker ticker1,
            Ticker ticker2,
            Ticker ticker3
    ) {
        return calculate(triangle, balance, ticker1, ticker2, ticker3, 0, 0, 0);
    }

    /**
     * Calcula oportunidad de arbitraje con slippage configurable.
     *
     * Algoritmo:
     *   1. Obtener precios: price1=ask, price2=ask/bid según dirección, price3=bid
     *   2. Aplicar slippage a cada precio
     *   3. Calcular rawProduct según la dirección del triángulo
     *      - Forward: rawProduct = (price2 * price3) / price1
     *      - Inverse: rawProduct = price3 / (price1 * price2)
     *   4. Aplicar factor de comisiones: feeFactor = (1-fee1)*(1-fee2)*(1-fee3)
     *   5. profitPct = (rawProduct * feeFactor - 1.0) * 100.0
     *   6. Calcular cantidades de cada paso para la oportunidad
     *   7. Validar rangos y aplicar precisionAdjuster
     *
     * @param triangle    Triángulo a evaluar
     * @param balance     Capital disponible
     * @param ticker1     Ticker símbolo 1
     * @param ticker2     Ticker símbolo 2
     * @param ticker3     Ticker símbolo 3
     * @param slippageOp1 Slippage estimado para OP1 (%)
     * @param slippageOp2 Slippage estimado para OP2 (%)
     * @param slippageOp3 Slippage estimado para OP3 (%)
     * @return ArbitrageOpportunity o null si es inválida
     */
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

        // Obtener comisiones configuradas para cada operación
        double feeOp1 = config.getFeeOp1();
        double feeOp2 = config.getFeeOp2();
        double feeOp3 = config.getFeeOp3();

        // Dirección del triángulo: forward o inverse
        boolean isForward = triangle.isForward();

        // Precios base: OP1 siempre compra (ask), OP3 siempre vende (bid)
        double price1 = ticker1.getAskPrice();
        // OP2: ask si es forward (compra), bid si es inverse (venta)
        double price2 = isForward ? ticker2.getAskPrice() : ticker2.getBidPrice();
        double price3 = ticker3.getBidPrice();

        // Validar que todos los precios sean positivos
        if (price1 <= 0 || price2 <= 0 || price3 <= 0) {
            totalNull.incrementAndGet();
            return null;
        }

        // Aplicar slippage a cada precio
        double effectivePrice1 = applySlippage(price1, slippageOp1, true);
        double effectivePrice2 = applySlippage(price2, slippageOp2, !isForward);
        double effectivePrice3 = applySlippage(price3, slippageOp3, false);

        // Calcular el producto raw según la dirección del triángulo
        double rawProduct;

        if (isForward) {
            // Triángulo forward: se multiplican price2 y price3, se divide por price1
            rawProduct = (effectivePrice2 * effectivePrice3) / effectivePrice1;
        } else {
            // Triángulo inverse: se divide price3 por (price1 * price2)
            rawProduct = effectivePrice3 / (effectivePrice1 * effectivePrice2);
        }

        // Validar rawProduct: debe ser positivo, finito, y menor a 10 mil millones
        if (rawProduct <= 0 || rawProduct > 10_000_000_000L || Double.isInfinite(rawProduct) || Double.isNaN(rawProduct)) {
            totalInvalidRawProduct.incrementAndGet();
            totalNull.incrementAndGet();
            logWarning(triangle, "invalid rawProduct=" + rawProduct, price1, price2, price3);
            return null;
        }

        // Aplicar factor de comisiones combinado
        double feeFactor = (1.0 - feeOp1 / 100.0) * (1.0 - feeOp2 / 100.0) * (1.0 - feeOp3 / 100.0);
        double profitPct = (rawProduct * feeFactor - 1.0) * 100.0;

        // Filtrar profits extremos (probablemente datos corruptos)
        if (profitPct > 100.0 || profitPct < -10.0) {
            totalPrecisionFiltered.incrementAndGet();
            logDebug(triangle, "filtered profitPct=" + profitPct);
            return null;
        }

        // ===== CALCULAR CANTIDADES DE CADA PASO =====
        double step1Qty, step2Qty, step2AfterFee, step3Qty, step3Price;

        // OP1: cantidad del primer activo que se compra con el balance
        step1Qty = balance / effectivePrice1;
        double step1AfterFee = step1Qty * (1.0 - feeOp1 / 100.0);

        // OP2 y OP3: varían según dirección del triángulo
        if (isForward) {
            // Forward: OP1→USDT/BNB, OP2→BNB/ETH, OP3→ETH/USDT
            // OP1 BUY base → tenemos base; OP2 SELL base por quote → obtenemos quote
            step2Qty = step1AfterFee;
            step2AfterFee = step2Qty * (1.0 - feeOp2 / 100.0);
            step3Qty = step2AfterFee;
        } else {
            // Inverse: OP1→USDT/ETH, OP2→ETH/BNB, OP3→BNB/USDT
            // OP1 BUY base → tenemos base; OP2 BUY base por quote → obtenemos base
            step2Qty = step1AfterFee / effectivePrice2;
            step2AfterFee = step2Qty * (1.0 - feeOp2 / 100.0);
            step3Qty = step2AfterFee / effectivePrice3;
        }

        step3Price = effectivePrice3;

        double finalQty = step3Qty * step3Price;
        double finalAfterFee = finalQty * (1.0 - feeOp3 / 100.0);

        // Construir objeto de oportunidad con todos los datos calculados
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

        // Ajustar precisión a los requisitos de Binance (lot size, tick size)
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

    /**
     * Calcula el slippage estimado basado en la profundidad del order book.
     * Si qty <= availableQty → 0% (la orden cabe en el primer nivel)
     * Si qty > availableQty → aumenta progresivamente según la relación cantidad/disponible
     *
     * Regla:
     *   ratio <= 1.5 → 0.05%
     *   ratio <= 2.0 → 0.15%
     *   ratio <= 3.0 → 0.30%
     *   ratio > 3.0  → min(2.0, default * (ratio - 1))
     *
     * @param symbol Símbolo para logging
     * @param side   "BUY" o "SELL"
     * @param qty    Cantidad a operar
     * @param ticker Ticker con datos de profundidad (bidQty/askQty)
     * @return Porcentaje de slippage estimado
     */
    public double calculateSlippage(String symbol, String side, double qty, Ticker ticker) {
        if (ticker == null || qty <= 0) {
            return defaultSlippagePct;
        }

        // Obtener cantidad disponible en el primer nivel del book
        double availableQty = "SELL".equalsIgnoreCase(side) ? ticker.getBidQty() : ticker.getAskQty();
        double price = "SELL".equalsIgnoreCase(side) ? ticker.getBidPrice() : ticker.getAskPrice();

        if (availableQty <= 0 || price <= 0) {
            return defaultSlippagePct;
        }

        // Si la cantidad cabe en el primer nivel, no hay slippage
        if (qty <= availableQty) {
            return 0.0;
        }

        // Calcular slippaje progresivo según qué tanto excede la cantidad disponible
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

    /**
     * Aplica slippage a un precio base.
     * Si adverse=true (movimiento adverso): price * (1 + slippage%) → peor precio
     * Si adverse=false: price * (1 - slippage/2%) → ajuste parcial
     *
     * @param price      Precio base
     * @param slippagePct Porcentaje de slippage
     * @param adverse    true si el slippage es adverso (compra con slippage)
     * @return Precio ajustado
     */
    private double applySlippage(double price, double slippagePct, boolean adverse) {
        if (slippagePct <= 0) {
            return price;
        }

        double factor = slippagePct / 100.0;

        if (adverse) {
            // Slippage adverso: empeora el precio (mayor para compras, menor para ventas)
            return price * (1.0 + factor);
        } else {
            // Slippage favorable: mejora parcialmente el precio
            return price * (1.0 - factor * 0.5);
        }
    }

    /**
     * Log de advertencia con control de frecuencia (máx cada 10s, hasta 5 veces).
     * Solo se muestra si el nivel de log no es SCAN.
     */
    private void logWarning(Triangle triangle, String msg, double p1, double p2, double p3) {
        long now = System.currentTimeMillis();
        if (now - lastProfitLog > 10000 && logCounter < 5 && !Log.isScanEnabled()) {
            lastProfitLog = now;
            logCounter++;
            System.out.println("[WARN] " + triangle.getId() + " " + msg + " (p1=" + p1 + " p2=" + p2 + " p3=" + p3 + ")");
        }
    }

    /**
     * Log de debug con control de frecuencia (máx cada 15s, hasta 5 veces, solo después de 30s de inicio).
     */
    private void logDebug(Triangle triangle, String msg) {
        long now = System.currentTimeMillis();
        if (now - startTime.get() > 30000 && now - lastProfitLog > 15000 && logCounter < 5 && !Log.isScanEnabled()) {
            lastProfitLog = now;
            logCounter++;
            System.out.println("[DEBUG] Profit " + triangle.getId() + ": " + msg);
        }
    }

    /**
     * Calcula una oportunidad con desglose detallado de costos.
     * Incluye: profit bruto, costos de slippage, costos de comisiones,
     * y profit neto estimado (realista).
     *
     * Fee cost estimado: balance * (feeOp1 + feeOp2 + feeOp3) / 100 * 3
     * Slippage cost: balance * (slip1 + slip2 + slip3) / 100
     * Net profit = balance * profitPct/100 - feeCost - slippageCost
     *
     * @param triangle Triángulo a evaluar
     * @param balance  Capital disponible
     * @param t1       Ticker del primer símbolo
     * @param t2       Ticker del segundo símbolo
     * @param t3       Ticker del tercer símbolo
     * @return CalculatedProfit con desglose completo (valid=false si no es viable)
     */
    public CalculatedProfit calculateDetailed(
            Triangle triangle,
            double balance,
            Ticker t1, Ticker t2, Ticker t3
    ) {
        // Calcular slippage estimado para cada operación
        double slip1 = calculateSlippage(triangle.getSymbol1(), "BUY", balance / t1.getAskPrice(), t1);
        double slip2 = calculateSlippage(triangle.getSymbol2(), "SELL", 0, t2);
        double slip3 = calculateSlippage(triangle.getSymbol3(), "SELL", 0, t3);

        // Calcular oportunidad con slippage incluido
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

        // Calcular costos detallados
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

    /**
     * @return Total de cálculos realizados
     */
    public int getTotalCalculated() { return totalCalculated.get(); }
    
    /**
     * @return Total de cálculos que resultaron null
     */
    public int getTotalNull() { return totalNull.get(); }
    
    /**
     * @return Total filtrados por precisión
     */
    public int getTotalPrecisionFiltered() { return totalPrecisionFiltered.get(); }
    
    /**
     * @return Total con rawProduct inválido
     */
    public int getTotalInvalidRawProduct() { return totalInvalidRawProduct.get(); }
    
    /**
     * @return Total filtrados por slippage
     */
    public int getTotalSlippageFiltered() { return totalSlippageFiltered.get(); }

    /**
     * Resetea todas las estadísticas a cero y reinicia el timer.
     */
    public void resetStats() {
        totalCalculated.set(0);
        totalNull.set(0);
        totalPrecisionFiltered.set(0);
        totalInvalidRawProduct.set(0);
        totalSlippageFiltered.set(0);
        startTime.set(System.currentTimeMillis());
    }

    /**
     * Obtiene estadísticas agregadas del calculador.
     *
     * @return ProfitStats con métricas
     */
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

    /**
     * Loggea estadísticas del calculador en formato legible.
     */
    public void logStats() {
        ProfitStats stats = getStats();
        Log.info(TAG, "=== ProfitCalculator Stats ===");
        Log.info(TAG, "  Calculated: " + stats.getTotalCalculated());
        Log.info(TAG, "  Valid: " + stats.getTotalValid());
        Log.info(TAG, "  Filtered: " + stats.getTotalFiltered());
        Log.info(TAG, "  Success rate: " + String.format("%.2f%%", stats.getSuccessRate()));
    }

    /**
     * Resultado detallado del cálculo de profit.
     * valid: true si el cálculo fue exitoso
     * opportunity: la oportunidad calculada (si valid=true)
     * rawProfit: profit bruto (%) calculado
     * netProfit: profit neto estimado tras descontar fees y slippage (USD)
     * slippageCost: costo estimado de slippage (USD)
     * feeCost: costo estimado de comisiones (USD)
     * slippage1/2/3: slippage estimado por operación (%)
     * timestamp: momento del cálculo
     */
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

    /**
     * Estadísticas agregadas del ProfitCalculator.
     * totalCalculated: cálculos totales realizados
     * totalValid: cálculos que resultaron en oportunidad válida
     * totalFiltered: cálculos filtrados (null)
     * successRate: porcentaje de éxito
     * invalidRawProduct: filtrados por rawProduct inválido
     * precisionFiltered: filtrados por ajuste de precisión
     * slippageFiltered: filtrados por slippage excesivo
     */
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