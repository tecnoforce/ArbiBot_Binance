package com.arbitrage.model;

import lombok.Builder;
import lombok.Data;

/**
 * Representa una oportunidad de arbitraje triangular detectada por el motor.
 * <p>
 * Es el DTO que {@code ArbitrageEngine} produce cuando encuentra un triángulo
 * rentable. Contiene el triángulo, el profit calculado, los balances involucrados,
 * y las cantidades/precios estimados para cada uno de los 3 pasos.
 * </p>
 * <p>
 * Esta oportunidad es consumida por {@code OrderExecutor} para ejecutar
 * las órdenes reales en Binance.
 * </p>
 */
@Data
@Builder
public class ArbitrageOpportunity {
    // =====================================================================
    // TRIANGULO
    // =====================================================================
    /** Triángulo que forma la oportunidad de arbitraje */
    private Triangle triangle;

    // =====================================================================
    // PROFIT
    // =====================================================================
    /** Profit porcentual estimado (ej: 0.15 representa 0.15%) */
    private double profitPct;

    // =====================================================================
    // BALANCES
    // =====================================================================
    /** Balance de moneda base usado para la operación */
    private double balanceUsed;
    /** Balance estimado después de completar el ciclo de arbitraje */
    private double finalBalance;

    // =====================================================================
    // OPERACION 1 — Primera compra
    // =====================================================================
    /** Cantidad a comprar en el paso 1 */
    private double step1Qty;
    /** Precio estimado de compra para el paso 1 */
    private double step1Price;

    // =====================================================================
    // OPERACION 2 — Segunda compra
    // =====================================================================
    /** Cantidad a comprar en el paso 2 */
    private double step2Qty;
    /** Precio estimado de compra para el paso 2 */
    private double step2Price;

    // =====================================================================
    // OPERACION 3 — Venta final
    // =====================================================================
    /** Cantidad a vender en el paso 3 */
    private double step3Qty;
    /** Precio estimado de venta para el paso 3 */
    private double step3Price;

    // =====================================================================
    // TIMESTAMP
    // =====================================================================
    /** Timestamp en ms de cuando se detectó la oportunidad */
    private long timestamp;

    // =====================================================================
    // GETTERS ALTERNATIVOS (nombres cortos para acceso rápido)
    // =====================================================================
    /** @return cantidad del paso 1 */
    public double getQty1() { return step1Qty; }
    /** @return precio del paso 1 */
    public double getPrice1() { return step1Price; }
    /** @return cantidad del paso 2 */
    public double getQty2() { return step2Qty; }
    /** @return precio del paso 2 */
    public double getPrice2() { return step2Price; }
    /** @return cantidad del paso 3 */
    public double getQty3() { return step3Qty; }
    /** @return precio del paso 3 */
    public double getPrice3() { return step3Price; }
}