package com.arbitrage.model;

import lombok.Builder;
import lombok.Data;

/**
 * Representa un triangulo de arbitraje.
 * Un triangulo es una secuencia de 3 simbolos que forma un ciclo:
 *   Ejemplo: BTCUSDT -> ETHBTC -> ETHUSDT -> BTCUSDT
 *   
 * La secuencia puede ser:
 *   - Forward: base -> coin -> btc -> base (vender base, comprar coin, vender coin)
 *   - Reverse: coin -> btc -> base -> coin (vender coin, comprar btc, vender btc)
 */
@Data
@Builder
public class Triangle {
    // =====================================================================
    // IDENTIFICACION
    // =====================================================================
    private String id;                 // ID unico (ej: "BTCUSDT->ETHBTC->ETHUSDT")
    
    // =====================================================================
    // SIMBOLOS DEL TRIANGULO
    // =====================================================================
    private String symbol1;          // Primer simbolo (ej: "BTCUSDT")
    private String symbol2;         // Segundo simbolo (ej: "ETHBTC")
    private String symbol3;          // Tercer simbolo (ej: "ETHUSDT")
    
    // =====================================================================
    // MONEDAS
    // =====================================================================
    private String baseCurrency;       // Moneda base (ej: "USDT")
    private String intermediateCurrency; // Moneda intermedia (ej: "BTC")
    private String targetCurrency;    // Moneda objetivo (ej: "ETH")
    
    // =====================================================================
    // DIRECCION
    // =====================================================================
    private boolean forward;      // true = forward, false = reverse

    /**
     * Obtiene nombre legible del triangulo.
     * @return String con secuencia
     */
    public String getName() {
        if (forward) {
            return symbol1 + " -> " + symbol2 + " -> " + symbol3;
        } else {
            return symbol3 + " -> " + symbol2 + " -> " + symbol1;
        }
    }
}