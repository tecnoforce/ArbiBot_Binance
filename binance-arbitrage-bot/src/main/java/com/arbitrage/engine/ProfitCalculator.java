package com.arbitrage.engine;

import com.arbitrage.config.AppConfig;
import com.arbitrage.model.ArbitrageOpportunity;
import com.arbitrage.model.Ticker;
import com.arbitrage.model.Triangle;

/**
 * Calcula el profit de una oportunidad de arbitraje triangular.
 * 
 * Formula de profit:
 *   profitPct = (bid3/(ask1*ask2) * feeFactor - 1) * 100
 * 
 * Donde:
 *   ask1 = precio ask del primer par (compra)
 *   ask2 = precio ask del segundo par (compra)
 *   bid3 = precio bid del tercer par (venta)
 *   feeFactor = (1-fee1) * (1-fee2) * (1-fee3)
 */
public class ProfitCalculator {
    private final AppConfig config;
    
    // Cantidad unitaria para calculo (1 unidad de moneda base)
    private static final double UNIT = 1.0;

    /**
     * Constructor.
     * @param config Configuracion con comisiones
     */
    public ProfitCalculator(AppConfig config) {
        this.config = config;
    }

    /**
     * Calcula profit de un triangulo.
     * @param triangle Triangulo a evaluar
     * @param balance Balance a usar por operacion
     * @param ticker1 Ticker del primer simbolo
     * @param ticker2 Ticker del segundo simbolo
     * @param ticker3 Ticker del tercer simbolo
     * @return Oportunidad con profit calculado o null
     */
    public ArbitrageOpportunity calculate(
            Triangle triangle,
            double balance,
            Ticker ticker1,
            Ticker ticker2,
            Ticker ticker3
    ) {
        // =====================================================================
        // OBTENER COMISIONES
        // =====================================================================
        double feeOp1 = config.getFeeOp1();
        double feeOp2 = config.getFeeOp2();
        double feeOp3 = config.getFeeOp3();

        // =====================================================================
        // DETERMINAR DIRECCION DEL TRIANGULO
        // =====================================================================
        boolean isForward = triangle.isForward();
        
        // =====================================================================
        // OBTENER PRECIOS SEGUN DIRECCION
        // =====================================================================
        // Paso 1: Siempre BUY (usar ask)
        double price1 = ticker1.getAskPrice();
        
        // Paso 2: BUY si forward, SELL si reverse
        double price2 = isForward ? ticker2.getAskPrice() : ticker2.getBidPrice();
        
        // Paso 3: Siempre SELL (usar bid)
        double price3 = ticker3.getBidPrice();

        // =====================================================================
        // CALCULAR PROFIT BRUTO SEGUN DIRECCION
        // =====================================================================
        double rawProduct;
        if (isForward) {
            // Forward: USDT -> A -> B -> USDT
            // BUY A, BUY B, SELL
            rawProduct = price3 / (price1 * price2);
        } else {
            // Reverse: USDT -> B -> A -> USDT
            // BUY B, SELL B, SELL
            rawProduct = (price2 * price3) / price1;
        }
        
        // Factor de comisiones
        double feeFactor = (1.0 - feeOp1 / 100.0) * (1.0 - feeOp2 / 100.0) * (1.0 - feeOp3 / 100.0);
        
        // Profit porcentual
        double profitPct = (rawProduct * feeFactor - 1.0) * 100.0;

        // =====================================================================
        // CALCULAR CANTIDADES SEGUN DIRECCION
        // =====================================================================
        double step1Qty, step2Qty, step2AfterFee, step3Qty, step3Price;
        
        if (isForward) {
            // Forward: BUY -> BUY -> SELL
            step1Qty = UNIT / price1;
            double step1AfterFee = step1Qty * (1.0 - feeOp1 / 100.0);
            
            step2Qty = step1AfterFee / price2;
            step2AfterFee = step2Qty * (1.0 - feeOp2 / 100.0);
            
            step3Qty = step2AfterFee * price3;
            step3Price = price3;
        } else {
            // Reverse: BUY -> SELL -> SELL
            step1Qty = UNIT / price1;
            double step1AfterFee = step1Qty * (1.0 - feeOp1 / 100.0);
            
            // Paso 2: SELL (usar precio de venta)
            step2Qty = step1AfterFee * price2;
            step2AfterFee = step2Qty * (1.0 - feeOp2 / 100.0);
            
            // Paso 3: SELL (usar precio de venta)
            step3Qty = step2AfterFee;
            step3Price = price3;
        }

        double finalQty = step3Qty * step3Price;
        double finalAfterFee = finalQty * (1.0 - feeOp3 / 100.0);

        // =====================================================================
        // RETORNAR OPORTUNIDAD
        // =====================================================================
        return ArbitrageOpportunity.builder()
                .triangle(triangle)
                .profitPct(profitPct)
                .balanceUsed(balance)
                .finalBalance(balance * (1.0 + profitPct / 100.0))
                .step1Qty(step1Qty)
                .step1Price(price1)
                .step2Qty(step2Qty)
                .step2Price(price2)
                .step3Qty(step3Qty)
                .step3Price(step3Price)
                .timestamp(System.currentTimeMillis())
                .build();
    }
}