package com.arbitrage.model;

import lombok.Builder;
import lombok.Data;

/**
 * Representa una oportunidad de arbitraje encontrada.
 * Contiene toda la informacion de un triangulo rentable.
 */
@Data
@Builder
public class ArbitrageOpportunity {
    // =====================================================================
    // TRIANGULO
    // =====================================================================
    private Triangle triangle;                 // Triangulo que forma la oportunidad
    
    // =====================================================================
    // PROFIT
    // =====================================================================
    private double profitPct;              // Profit porcentual (ej: 0.15%)
    
    // =====================================================================
    // BALANCES
    // =====================================================================
    private double balanceUsed;            // Balance usado
    private double finalBalance;            // Balance final despues del arbitraje
    
    // =====================================================================
    // OPERACION 1 (Compra 1)
    // =====================================================================
    private double step1Qty;    // Cantidad comprada
    private double step1Price;   // Precio de compra
    
    // =====================================================================
    // OPERACION 2 (Compra 2)
    // =====================================================================
    private double step2Qty;    // Cantidad comprada
    private double step2Price;   // Precio de compra
    
    // =====================================================================
    // OPERACION 3 (Venta)
    // =====================================================================
    private double step3Qty;   // Cantidad Vendida
    private double step3Price;  // Precio de venta
    
    // =====================================================================
    // TIMESTAMP
    // =====================================================================
    private long timestamp;               // Cuando se detecto

    // =====================================================================
    // GETTERS ALTERNATIVOS
    // =====================================================================
    public double getQty1() { return step1Qty; }
    public double getPrice1() { return step1Price; }
    public double getQty2() { return step2Qty; }
    public double getPrice2() { return step2Price; }
    public double getQty3() { return step3Qty; }
    public double getPrice3() { return step3Price; }
}