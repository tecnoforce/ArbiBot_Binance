package com.arbitrage.engine;

import com.arbitrage.config.AppConfig;
import com.arbitrage.model.ArbitrageOpportunity;
import com.arbitrage.model.Ticker;
import com.arbitrage.model.Triangle;

/**
 * Calcula el profit de una oportunidad de arbitraje triangular aplicando
 * el producto de los precios y las comisiones de Binance.
 *
 * ## Formula general (reverse como ejemplo)
 * {@code
 *   rawProduct = bid3 / (ask1 * ask2)
 *   profitPct = (rawProduct * feeFactor - 1.0) * 100.0
 * }
 *
 * donde:
 * - {@code rawProduct} es el producto bruto de los 3 precios sin comisiones.
 * - {@code feeFactor = (1 - feeOp1/100) * (1 - feeOp2/100) * (1 - feeOp3/100)}
 *   representa la penalizacion por comisiones del exchange.
 *
 * ## Forward vs Reverse
 * - **Forward**: rawProduct = (bid2 * bid3) / ask1
 *   (COMPRA monedaA, VENDE monedaA por monedaB, VENDE monedaB por base)
 * - **Reverse**: rawProduct = bid3 / (ask1 * ask2)
 *   (COMPRA monedaB, COMPRA monedaA con monedaB, VENDE monedaA por base)
 *
 * ## Interpretacion del resultado
 * - profitPct > 0  → hay ganancia potencial (antes de ajustes reales)
 * - profitPct < 0  → perdida, no rentable
 * - profitPct = 0  → punto de equilibrio
 *
 * @see PrecisionAdjuster  Ajusta cantidades a reglas LOT_SIZE/TICK_SIZE
 * @see ArbitrageEngine    Consume el resultado para decidir si ejecutar
 */
public class ProfitCalculator {
    private final AppConfig config;

    // Constante nominal de 1 unidad de moneda base para calculo teorico.
    // El profit porcentual es invariante respecto a la cantidad, por lo que
    // usamos UNIT=1.0 para simplificar. Luego PrecisionAdjuster recalcula
    // con cantidades reales ajustadas a LOT_SIZE.
    private static final double UNIT = 1.0;

    /**
     * Constructor.
     * @param config Configuracion con comisiones por operacion (feeOp1, feeOp2, feeOp3)
     */
    public ProfitCalculator(AppConfig config) {
        this.config = config;
    }

    /**
     * Calcula el profit de un triangulo de arbitraje en su direccion correspondiente.
     *
     * ## Logica de precios segun direccion
     *
     * ### Forward (base -> coinA -> coinB -> base)
     * - ask1 = precio al que COMPRAMOS coinA (pagamos base)
     * - bid2 = precio al que VENDEMOS coinA para obtener coinB
     * - bid3 = precio al que VENDEMOS coinB para recuperar base
     * - Formula: rawProduct = (bid2 * bid3) / ask1
     *
     * ### Reverse (base -> coinB -> coinA -> base)
     * - ask1 = precio al que COMPRAMOS coinB (pagamos base)
     * - ask2 = precio al que COMPRAMOS coinA usando coinB
     * - bid3 = precio al que VENDEMOS coinA para recuperar base
     * - Formula: rawProduct = bid3 / (ask1 * ask2)
     *
     * ## Comisiones
     * Cada operacion paga una comision (feeOp1, feeOp2, feeOp3) en porcentaje.
     * feeFactor = (1 - feeOp1/100) * (1 - feeOp2/100) * (1 - feeOp3/100)
     * El profit neto final es: profitPct = (rawProduct * feeFactor - 1) * 100
     *
     * @param triangle Triangulo a evaluar (contiene direccion forward/reverse)
     * @param balance  Cantidad de moneda base a invertir (ej: 10 USDT)
     * @param ticker1  Ticker del primer simbolo del triangulo
     * @param ticker2  Ticker del segundo simbolo
     * @param ticker3  Ticker del tercer simbolo
     * @return ArbitrageOpportunity con profitPct, cantidades por paso, o null si no aplica
     */
    public ArbitrageOpportunity calculate(
            Triangle triangle,
            double balance,
            Ticker ticker1,
            Ticker ticker2,
            Ticker ticker3
    ) {
        // =====================================================================
        // OBTENER COMISIONES DESDE CONFIG
        // =====================================================================
        // Cada operacion del triangulo paga comision.
        // feeOp1, feeOp2, feeOp3 vienen de AppConfig (ej: 0.1 para 0.1%).
        double feeOp1 = config.getFeeOp1();
        double feeOp2 = config.getFeeOp2();
        double feeOp3 = config.getFeeOp3();

        boolean isForward = triangle.isForward();

        double price1, price2, price3, rawProduct, step1Qty, step2Qty, step3Qty, step3Price, step2AfterFee;

        if (isForward) {
            // =============================================================
            // FORWARD: base -> coinA -> coinB -> base
            // Ejemplo concreto: USDT -> BTC -> ETH -> USDT
            //
            // Paso 1: COMPRAR BTC con USDT en BTCUSDT (usamos ASK: lo que pagamos)
            // Paso 2: VENDER BTC por ETH en ETHBTC     (usamos BID: lo que recibimos)
            // Paso 3: VENDER ETH por USDT en ETHUSDT   (usamos BID: lo que recibimos)
            // =============================================================
            price1 = ticker1.getAskPrice(); // Precio de compra de coinA (ej: BTCUSDT ask)
            price2 = ticker2.getBidPrice(); // Precio de venta de coinA por coinB (ej: ETHBTC bid)
            price3 = ticker3.getBidPrice(); // Precio de venta de coinB por base (ej: ETHUSDT bid)

            // Producto bruto: cuantas unidades base obtenemos por 1 unidad base invertida (sin fees)
            // En forward: compramos coinA, la vendemos por coinB, vendemos coinB
            // rawProduct = (BTC recibido por 1 USDT) * (ETH recibido por ese BTC) * (USDT recibido por ese ETH)
            //            = (1/ask1) * bid2 * bid3 = (bid2 * bid3) / ask1
            rawProduct = (price2 * price3) / price1;

            // Cantidad de coinA comprada con el balance (ej: BTC comprados con 10 USDT)
            step1Qty = balance / price1;
            // Despues de comision, cuanto coinA nos queda realmente
            double step1AfterFee = step1Qty * (1.0 - feeOp1 / 100.0);

            // Paso 2: vendemos toda la coinA que tenemos (despues de comision) para obtener coinB
            step2Qty = step1AfterFee; // CoinA que vendemos en el par coinA/coinB
            double bnbReceived = step2Qty * price2; // CoinB recibido bruto
            step2AfterFee = bnbReceived * (1.0 - feeOp2 / 100.0); // CoinB neto (despues de comision)

            // Paso 3: vendemos toda la coinB para recuperar base
            step3Qty = step2AfterFee; // CoinB que vendemos
            step3Price = price3;
        } else {
            // =============================================================
            // REVERSE: base -> coinB -> coinA -> base
            // Ejemplo concreto: USDT -> ETH -> BTC -> USDT
            //
            // Paso 1: COMPRAR ETH con USDT en ETHUSDT  (usamos ASK: lo que pagamos)
            // Paso 2: COMPRAR BTC con ETH en ETHBTC     (usamos ASK: lo que pagamos)
            // Paso 3: VENDER BTC por USDT en BTCUSDT    (usamos BID: lo que recibimos)
            // =============================================================
            price1 = ticker1.getAskPrice(); // Precio de compra de coinB (ej: ETHUSDT ask)
            price2 = ticker2.getAskPrice(); // Precio de compra de coinA con coinB (ej: ETHBTC ask)
            price3 = ticker3.getBidPrice(); // Precio de venta de coinA por base (ej: BTCUSDT bid)

            // Producto bruto: cuantas unidades base obtenemos por 1 unidad base invertida (sin fees)
            // En reverse: compramos coinB, compramos coinA con coinB, vendemos coinA
            // rawProduct = (ETH comprado con 1 USDT) * (BTC comprado con ese ETH) * (USDT recibido por ese BTC)
            //            = (1/ask1) * (1/ask2) * bid3 = bid3 / (ask1 * ask2)
            rawProduct = price3 / (price1 * price2);

            // Cantidad de coinB comprada con el balance (ej: ETH comprados con 10 USDT)
            step1Qty = balance / price1;
            double step1AfterFee = step1Qty * (1.0 - feeOp1 / 100.0);

            // Paso 2: usamos toda la coinB (despues de comision) para comprar coinA
            step2Qty = step1AfterFee / price2; // CoinA comprada bruta
            step2AfterFee = step2Qty * (1.0 - feeOp2 / 100.0); // CoinA neta (despues de comision)

            // Paso 3: vendemos toda la coinA para recuperar base
            step3Qty = step2AfterFee;
            step3Price = price3;
        }

        // =====================================================================
        // CALCULO DEL PROFIT NETO CON COMISIONES
        // =====================================================================
        // feeFactor: factor compuesto de las 3 comisiones.
        // Cada comision esta en porcentaje (ej: 0.1 = 0.1%).
        // feeFactor = (1 - 0.001) * (1 - 0.001) * (1 - 0.001) ≈ 0.997
        double feeFactor = (1.0 - feeOp1 / 100.0) * (1.0 - feeOp2 / 100.0) * (1.0 - feeOp3 / 100.0);

        // profitPct: porcentaje de ganancia o perdida respecto al balance inicial.
        // Si rawProduct = 1.01 y feeFactor = 0.997:
        //   profitPct = (1.01 * 0.997 - 1.0) * 100 = (1.00697 - 1.0) * 100 = 0.697%
        // Esto significa que por cada 100 USDT invertidos, ganamos ~0.697 USDT netos.
        double profitPct = (rawProduct * feeFactor - 1.0) * 100.0;

        // =====================================================================
        // CONSTRUIR Y RETORNAR OPORTUNIDAD
        // =====================================================================
        // Construye el objeto oportunidad con todos los datos calculados.
        // NOTA: las cantidades aqui son TEORICAS (sin ajustar a LOT_SIZE/TICK_SIZE).
        // PrecisionAdjuster.hacerAdjust() las redondeara a valores reales ejecutables.
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