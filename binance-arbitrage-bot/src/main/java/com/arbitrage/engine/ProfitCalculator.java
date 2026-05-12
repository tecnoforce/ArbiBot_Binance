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

        boolean isForward = triangle.isForward();
        
        double price1, price2, price3, rawProduct, step1Qty, step2Qty, step3Qty, step3Price, step2AfterFee;
        
        if (isForward) {
            // Forward: USDT -> A -> B -> USDT (BUY, SELL, SELL)
            price1 = ticker1.getAskPrice(); // BUY A
            price2 = ticker2.getBidPrice(); // SELL A to get B
            price3 = ticker3.getBidPrice(); // SELL B to get USDT
            
            rawProduct = (price2 * price3) / price1;
            
            step1Qty = balance / price1; // Quantity of A
            double step1AfterFee = step1Qty * (1.0 - feeOp1 / 100.0);
            
            step2Qty = step1AfterFee; // Quantity of A we are selling
            double bnbReceived = step2Qty * price2; // Amount of B we get
            step2AfterFee = bnbReceived * (1.0 - feeOp2 / 100.0);
            
            step3Qty = step2AfterFee; // Quantity of B we are selling
            step3Price = price3;
        } else {
            // Reverse: USDT -> B -> A -> USDT (BUY, BUY, SELL)
            price1 = ticker1.getAskPrice(); // BUY B
            price2 = ticker2.getAskPrice(); // BUY A using B
            price3 = ticker3.getBidPrice(); // SELL A to get USDT
            
            rawProduct = price3 / (price1 * price2);
            
            step1Qty = balance / price1; // Quantity of B
            double step1AfterFee = step1Qty * (1.0 - feeOp1 / 100.0);
            
            step2Qty = step1AfterFee / price2; // Quantity of A we are buying
            step2AfterFee = step2Qty * (1.0 - feeOp2 / 100.0);
            
            step3Qty = step2AfterFee; // Quantity of A we are selling
            step3Price = price3;
        }

        double feeFactor = (1.0 - feeOp1 / 100.0) * (1.0 - feeOp2 / 100.0) * (1.0 - feeOp3 / 100.0);
        double profitPct = (rawProduct * feeFactor - 1.0) * 100.0;

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