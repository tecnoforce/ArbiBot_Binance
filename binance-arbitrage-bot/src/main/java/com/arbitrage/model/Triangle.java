package com.arbitrage.model;

import lombok.Builder;
import lombok.Data;

/**
 * Representa un triángulo de arbitraje en el mercado de Binance.
 * <p>
 * Un triángulo es una combinación de 3 pares de trading que forman un ciclo
 * cerrado de conversión de monedas. Por ejemplo, el triángulo
 * {@code BTCUSDT -> ETHBTC -> ETHUSDT} permite convertir
 * USDT → BTC → ETH → USDT.
 * </p>
 * <p>
 * La dirección puede ser:
 * <ul>
 *   <li><b>Forward</b> (directa): se parte de la moneda base, se compra la
 *       intermedia, y se vende para volver a la base.</li>
 *   <li><b>Reverse</b> (inversa): se parte de la moneda objetivo, se compra
 *       la intermedia, y se vende para obtener la base.</li>
 * </ul>
 * </p>
 */
@Data
@Builder
public class Triangle {
    // =====================================================================
    // IDENTIFICACION
    // =====================================================================
    /** ID único del triángulo (ej: "BTCUSDT->ETHBTC->ETHUSDT") */
    private String id;

    // =====================================================================
    // SIMBOLOS DEL TRIANGULO
    // =====================================================================
    /** Primer símbolo del triángulo (ej: "BTCUSDT") */
    private String symbol1;
    /** Segundo símbolo del triángulo (ej: "ETHBTC") */
    private String symbol2;
    /** Tercer símbolo del triángulo (ej: "ETHUSDT") */
    private String symbol3;

    // =====================================================================
    // MONEDAS
    // =====================================================================
    /** Moneda base del ciclo (ej: "USDT") — donde se inicia y termina */
    private String baseCurrency;
    /** Moneda intermedia del ciclo (ej: "BTC") */
    private String intermediateCurrency;
    /** Moneda objetivo o destino (ej: "ETH") */
    private String targetCurrency;

    // =====================================================================
    // DIRECCION
    // =====================================================================
    /** true = forward (directa), false = reverse (inversa) */
    private boolean forward;

    /**
     * Obtiene el nombre legible del triángulo mostrando la secuencia de símbolos.
     * <p>
     * En modo forward muestra symbol1 → symbol2 → symbol3.
     * En modo reverse muestra symbol3 → symbol2 → symbol1 (se invierte el orden).
     * </p>
     * @return cadena con la secuencia formateada (ej: "BTCUSDT -> ETHBTC -> ETHUSDT")
     */
    public String getName() {
        if (forward) {
            return symbol1 + " -> " + symbol2 + " -> " + symbol3;
        } else {
            return symbol3 + " -> " + symbol2 + " -> " + symbol1;
        }
    }
}