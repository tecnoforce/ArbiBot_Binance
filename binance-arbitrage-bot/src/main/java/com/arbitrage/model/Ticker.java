package com.arbitrage.model;

import lombok.Builder;
import lombok.Data;

/**
 * Representa los precios actuales de un símbolo en el libro de órdenes de Binance.
 * <p>
 * Contiene los mejores precios bid (compra) y ask (venta) obtenidos del
 * WebSocket de bookTicker o de la API REST. Es la fuente principal de
 * datos para el cálculo de oportunidades de arbitraje.
 * </p>
 * <p>
 * Nota: En el contexto del bot, se usa bidPrice para vender y askPrice para
 * comprar, simulando la ejecución contra el libro de órdenes.
 * </p>
 */
@Data
@Builder
public class Ticker {
    /** Símbolo del par (ej: "BTCUSDT") */
    private String symbol;

    /** Mejor precio bid (comprador) — precio al que se puede vender */
    private double bidPrice;

    /** Mejor precio ask (vendedor) — precio al que se puede comprar */
    private double askPrice;

    /** Cantidad disponible al mejor precio bid */
    private double bidQty;

    /** Cantidad disponible al mejor precio ask */
    private double askQty;
}