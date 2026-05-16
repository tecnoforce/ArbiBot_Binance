package com.arbitrage.engine;

import com.arbitrage.model.ArbitrageOpportunity;
import com.arbitrage.model.Triangle;
import com.arbitrage.trading.BinanceApiClient;

/**
 * Ajusta las cantidades y precios de una oportunidad de arbitraje a las
 * reglas de precision del exchange (LOT_SIZE, TICK_SIZE, MIN_NOTIONAL).
 *
 * ## �Por que es necesario?
 * Binance impone restricciones en cada par:
 * - {@code LOT_SIZE}:    la cantidad debe ser multiplo del stepSize y estar
 *                        dentro de [minQty, maxQty].
 * - {@code TICK_SIZE}:   el precio debe ser multiplo del tickSize.
 * - {@code MIN_NOTIONAL}: el valor nocional (qty * price) debe superar un
 *                        minimo (usamos una fraccion {@link #MIN_NOTIONAL_SCALE}
 *                        como margen de seguridad).
 *
 * ## Flujo de ajuste
 *  1) Se obtienen las cantidades brutas de {@link ProfitCalculator}.
 *  2) Se redondean contra LOT_SIZE y TICK_SIZE via {@link BinanceApiClient}.
 *  3) Se re-aplican las comisiones sobre las cantidades ajustadas.
 *  4) Se re-calculan las cantidades de los pasos 2 y 3 con precision real.
 *  5) Si algun paso no supera MIN_NOTIONAL, la oportunidad se descarta (null).
 *
 * @see BinanceApiClient#adjustQuantityToLotSize(String, double)
 * @see BinanceApiClient#adjustPriceToTickSize(String, double)
 * @see BinanceApiClient#getMinNotionalOrZero(String)
 */

public class PrecisionAdjuster {
    // Cliente API para consultar reglas LOT_SIZE, TICK_SIZE y MIN_NOTIONAL de cada simbolo
    private final BinanceApiClient apiClient;

    // Factor de seguridad para MIN_NOTIONAL: usamos 100% del minimo real como umbral.
    // Esto evita errores de "insufficient funds" por fees, truncamiento y cambios de precio.
    // El chat de IA recomienda buffer del 20%-100%, usamos 100% para maxima seguridad.
    private static final double MIN_NOTIONAL_SCALE = 1.0;

    // Comisiones de cada operacion del triangulo (en porcentaje, ej: 0.1 = 0.1%)
    private final double feeOp1;
    private final double feeOp2;
    private final double feeOp3;

    /**
     * Constructor.
     *
     * @param apiClient      Cliente API de Binance (para consultar LOT_SIZE, TICK_SIZE, MIN_NOTIONAL)
     * @param balancePerTrade Balance por operacion (no usado directamente,预留 para futuros calculos)
     * @param feeOp1         Comision de la primera operacion en porcentaje (ej: 0.1 = 0.1%)
     * @param feeOp2         Comision de la segunda operacion
     * @param feeOp3         Comision de la tercera operacion
     */
    public PrecisionAdjuster(BinanceApiClient apiClient, double balancePerTrade, double feeOp1, double feeOp2, double feeOp3) {
        this.apiClient = apiClient;
        this.feeOp1 = feeOp1;
        this.feeOp2 = feeOp2;
        this.feeOp3 = feeOp3;
    }

    /**
     * Ajusta una oportunidad de arbitraje "teorica" a valores reales ejecutables
     * respetando las reglas de precision del exchange.
     *
     * ## Proceso
     *  1) Obtiene cantidades y precios brutos de {@link ProfitCalculator}.
     *  2) Redondea cada cantidad contra LOT_SIZE del simbolo.
     *  3) Redondea cada precio contra TICK_SIZE del simbolo.
     *  4) Recalcula los pasos 2 y 3 usando cantidades ajustadas (porque el
     *     redondeo puede cambiar el nocional y afectar pasos siguientes).
     *  5) Para FORWARD:  paso2 = adjQty1 * (1-fee1) / precio2 (cuantas monedas B compramos)
     *                    paso3 = adjStep2 * (1-fee2) * precio3 (cuanta base recuperamos)
     *     Para REVERSE:  paso2 = adjQty1 * (1-fee1) * precio2 (cuantas monedas A compramos)
     *                    paso3 = adjStep2 * (1-fee2) (misma cantidad A, la vendemos)
     *  6) Valida que cada paso cumpla MIN_NOTIONAL. Si no, retorna null (oportunidad inviable).
     *
     * @param raw      Oportunidad calculada por ProfitCalculator (valores teoricos)
     * @param triangle Triangulo asociado (para obtener simbolos y direccion)
     * @return Oportunidad ajustada con cantidades reales, o null si no es ejecutable
     */
    public ArbitrageOpportunity adjust(ArbitrageOpportunity raw, Triangle triangle) {
        // =====================================================================
        // EXTRAER SIMBOLOS DEL TRIANGULO
        // =====================================================================
        String symbol1 = triangle.getSymbol1();
        String symbol2 = triangle.getSymbol2();
        String symbol3 = triangle.getSymbol3();

        // =====================================================================
        // EXTRAER CANTIDADES Y PRECIOS TEORICOS
        // =====================================================================
        // Estos valores vienen de ProfitCalculator.calculate() y son calculos
        // ideales sin redondeo. Necesitan ser truncados/redondeados a los limites
        // que impone Binance (LOT_SIZE para cantidad, TICK_SIZE para precio).
        double qty1 = raw.getStep1Qty();
        double qty2 = raw.getStep2Qty();
        double qty3 = raw.getStep3Qty();
        double price1 = raw.getStep1Price();
        double price2 = raw.getStep2Price();
        double price3 = raw.getStep3Price();

        // =====================================================================
        // AJUSTE INICIAL A LOT_SIZE Y TICK_SIZE
        // =====================================================================
        // Redondeamos cantidad1 contra LOT_SIZE del simbolo1 (cantidad de coinA/monedaBase).
        // Redondeamos precio2 a TICK_SIZE (precio al que venderemos coinA por coinB).
        // Redondeamos cantidad2 contra LOT_SIZE (cantidad de coinA/coinB).
        // Redondeamos precio3 a TICK_SIZE (precio al que venderemos coinB por base).
        // Redondeamos cantidad3 contra LOT_SIZE.
        // NOTA: price1 (ask de compra) NO se ajusta porque no fijamos ese precio;
        //       es el precio de mercado al que compramos (market order).
        double adjQty1 = apiClient.adjustQuantityToLotSize(symbol1, qty1);
        double adjPrice2 = apiClient.adjustPriceToTickSize(symbol2, price2);
        double adjQty2 = apiClient.adjustQuantityToLotSize(symbol2, qty2);
        double adjPrice3 = apiClient.adjustPriceToTickSize(symbol3, price3);
        double adjQty3 = apiClient.adjustQuantityToLotSize(symbol3, qty3);

        boolean isForward = triangle.isForward();

        // Validacion rapida: si tras el primer redondeo las cantidades de los
        // pasos 2 y 3 no alcanzan el MIN_NOTIONAL, la oportunidad no es viable.
        // Ejemplo: si adjQty2 * adjPrice2 < umbral, la orden de venta seria rechazada.
        if (!validateStep(symbol2, adjQty2, adjPrice2) ||
            !validateStep(symbol3, adjQty3, adjPrice3)) {
            return null;
        }

        // =====================================================================
        // RE-CALCULO CON PRECISION REAL
        // =====================================================================
        // El redondeo a LOT_SIZE altera las cantidades. Esto tiene un "efecto
        // domino": si compramos menos coinA de la teorica, recibiremos menos
        // coinB, y por tanto menos base al final. Recalculamos cada paso con
        // las cantidades ya ajustadas para obtener valores reales ejecutables.
        if (isForward) {
            // --- FORWARD: base -> coinA -> coinB -> base ---
            // Ej: USDT -> BTC -> ETH -> USDT

            // Paso 1: compramos adjQty1 de coinA, pagamos comision feeOp1
            double afterFee1 = adjQty1 * (1 - feeOp1 / 100.0);

            // Paso 2: vendemos coinA (despues de comision) por coinB
            // Formula: coinB recibido = coinA neto / precio2 (bid de coinA/coinB)
            double step2Raw = afterFee1 / price2;
            // Ajustamos la cantidad de coinB a LOT_SIZE del par coinA/coinB
            double adjStep2 = apiClient.adjustQuantityToLotSize(symbol2, step2Raw);

            if (!validateStep(symbol2, adjStep2, adjPrice2)) {
                return null;
            }

            // Paso 3: vendemos coinB (despues de comision) para recuperar base
            double afterFee2 = adjStep2 * (1 - feeOp2 / 100.0);
            // Formula: base recibida = coinB neto * precio3 (bid de coinB/base)
            double step3Raw = afterFee2 * price3;
            double adjStep3 = apiClient.adjustQuantityToLotSize(symbol3, step3Raw);

            if (!validateStep(symbol3, adjStep3, adjPrice3)) {
                return null;
            }

            adjQty2 = adjStep2;
            adjQty3 = adjStep3;
        } else {
            // --- REVERSE: base -> coinB -> coinA -> base ---
            // Ej: USDT -> ETH -> BTC -> USDT

            // Paso 1: compramos adjQty1 de coinB, pagamos comision feeOp1
            double afterFee1 = adjQty1 * (1 - feeOp1 / 100.0);

            // Paso 2: compramos coinA usando coinB (despues de comision)
            // Formula: coinA comprada = coinB neto * precio2 (ask de coinA/coinB)
            // En reverse, price2 es el ASK (lo que pagamos), asi que lo usamos
            // como multiplicador para convertir de coinB a coinA.
            double step2Raw = afterFee1 * price2;
            double adjStep2 = apiClient.adjustQuantityToLotSize(symbol2, step2Raw);

            // En reverse validamos con el precio ajustado a TICK_SIZE del paso 2
            double adjPrice2Check = apiClient.adjustPriceToTickSize(symbol2, price2);
            if (!validateStep(symbol2, adjStep2, adjPrice2Check)) {
                return null;
            }

            // Paso 3: vendemos coinA (despues de comision) para recuperar base
            double afterFee2 = adjStep2 * (1 - feeOp2 / 100.0);
            // En reverse, paso 3 vende la misma cantidad de coinA que tenemos
            // (no hay multiplicacion por precio, price3 = bid de coinA/base)
            double step3Raw = afterFee2;
            double adjStep3 = apiClient.adjustQuantityToLotSize(symbol3, step3Raw);

            if (!validateStep(symbol3, adjStep3, adjPrice3)) {
                return null;
            }

            adjQty2 = adjStep2;
            adjQty3 = adjStep3;
        }

        // =====================================================================
        // CONSTRUIR OPORTUNIDAD CON VALORES AJUSTADOS
        // =====================================================================
        // Retornamos una nueva oportunidad con cantidades y precios ajustados.
        // El profitPct se mantiene del calculo teorico (el redondeo afecta
        // ligeramente el profit real, pero se considera despreciable).
        // price1 NO se ajusta porque es el ask de mercado (no controlable).
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

    /**
     * Valida que el valor nocional de una operacion supere el umbral minimo
     * exigido por Binance ({@code MIN_NOTIONAL}).
     *
     * El valor nocional {@code qty * price} representa el valor en USD (o moneda
     * base) de la orden. Si es menor al minimo permitido, Binance rechaza la orden.
     *
     * Usamos un umbral reducido ({@code minNotional * MIN_NOTIONAL_SCALE}) como
     * margen de seguridad para evitar falsos positivos por redondeo en LOT_SIZE.
     *
     * @param symbol Simbolo del par (ej: "ETHBTC") para consultar su MIN_NOTIONAL
     * @param qty    Cantidad ajustada a LOT_SIZE
     * @param price  Precio ajustado a TICK_SIZE
     * @return true si el nocional supera el umbral minimo, false si debe descartarse
     */
    private boolean validateStep(String symbol, double qty, double price) {
        // Obtiene el umbral minimo (con margen de seguridad) para este simbolo
        double threshold = getMinNotionalThreshold(symbol);
        if (threshold > 0) {
            // Calcula el valor nocional real: cantidad * precio
            double notional = qty * price;
            // Si no alcanza el minimo, la orden seria rechazada por Binance
            if (notional < threshold) {
                return false;
            }
        }
        // Si no hay restriccion (threshold == 0), siempre es valido
        return true;
    }

    /**
     * Calcula el umbral de valor nocional minimo para un simbolo, aplicando
     * un factor de escala como margen de seguridad.
     *
## Por que un factor de escala?
      * El MIN_NOTIONAL de Binance es el valor minimo ABSOLUTO. Si redondeamos
      * una cantidad hacia abajo por LOT_SIZE, podriamos quedar por debajo del
      * minimo y la orden seria rechazada. Tambien debemos considerar fees, slippage
      * y cambios de precio entre ordenes. Multiplicamos por
      * {@code MIN_NOTIONAL_SCALE = 1.0} (100%) para tener un colchon completo.
      *
      * Ejemplo: Si minNotional = 10 USDT, el umbral efectivo es 10 * 1.0 = 10 USDT.
      * Esto significa que la orden debe valer al menos 10 USDT, igual al minimo real.
      * Recomendacion del chat: buffer del 20%-100%, usamos 100% para maxima estabilidad.
     *
     * @param symbol Simbolo del par (ej: "BTCUSDT")
     * @return Umbral minimo con margen, o 0.0 si el simbolo no tiene restriccion
     */
    private double getMinNotionalThreshold(String symbol) {
        // Consulta el MIN_NOTIONAL real definido por Binance para este simbolo
        double minNotional = apiClient.getMinNotionalOrZero(symbol);
        // Si retorna 0 o negativo, el simbolo no tiene restriccion MIN_NOTIONAL
        if (minNotional <= 0) {
            return 0;
        }
        // Aplica el factor de seguridad para evitar falsos rechazos por redondeo
        return minNotional * MIN_NOTIONAL_SCALE;
    }
}