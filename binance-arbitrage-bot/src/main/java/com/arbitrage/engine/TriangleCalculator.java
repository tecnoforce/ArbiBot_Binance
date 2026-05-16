package com.arbitrage.engine;

import com.arbitrage.model.Triangle;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Genera todas las combinaciones triangulares de arbitraje posibles
 * a partir de una lista de monedas cotizadas contra {@code baseCurrency}.
 *
 * ## �Que es un triangulo de arbitraje?
 * Es una secuencia de 3 operaciones que comienza y termina en la misma
 * moneda base (ej: USDT), pasando por dos monedas intermedias.
 *
 * ## Direcciones
 * - {@code forward}:    base -> coinA -> coinB -> base
 *   Ej: USDT -> BTC -> ETH -> USDT
 *   Operaciones: COMPRA (ask), VENTA (bid), VENTA (bid)
 *
 * - {@code reverse}:    base -> coinB -> coinA -> base
 *   Ej: USDT -> ETH -> BTC -> USDT
 *   Operaciones: COMPRA (ask), COMPRA (ask), VENTA (bid)
 *
 * ## Filtros aplicados
 * - Stablecoins (USDT, USDC, etc.) excluidas como monedas intermedias.
 * - Solo se incluyen triangulos cuyos 3 simbolos existen en Binance.
 * - Se excluyen simbolos de futuros perpetuos (con _, USD#, etc.)
 *
 * @see ArbitrageEngine  Consume los triangulos generados para escanear oportunidades
 */
public class TriangleCalculator {
    // Moneda base contra la que se cotizan todos los pares (ej: "USDT")
    private final String baseCurrency;

    // Stablecoins y monedas fiat que NO deben usarse como intermediarias en el triangulo
    // porque un triangulo USDT -> X -> USDT no tendria sentido (no hay conversion real)
    private static final Set<String> STABLE_COINS = Set.of(
        "USDT", "USDC", "FDUSD", "DAI", "TUSD", "BUSD", "USDP", "PAXG", "EUR", "GBP"
    );

    /**
     * Constructor.
     * @param baseCurrency Moneda base (ej: "USDT", "BTC") contra la que se forman los triangulos
     */
    public TriangleCalculator(String baseCurrency) {
        this.baseCurrency = baseCurrency;
    }

    /**
     * Construye triangulos a partir de una lista de monedas, sin validar contra simbolos reales.
     * {@code validSymbols = null} → no se filtra por existencia en Binance.
     *
     * @param coins Lista de monedas candidatas (ej: ["BTC", "ETH", "BNB", ...])
     * @return Lista de todos los triangulos forward + reverse posibles
     */
    public List<Triangle> buildTriangles(List<String> coins) {
        return buildTriangles(coins, null);
    }

    /**
     * Construye triangulos forward y reverse dado un conjunto de monedas y
     * los simbolos validos reportados por la API de Binance.
     *
     * ## Algoritmo
     *  1) Filtra stablecoins de la lista de monedas (no pueden ser intermediarias).
     *  2) Genera todos los pares (coinA, coinB) con i < j.
     *  3) Para cada par genera 2 triangulos:
     *     - Forward:  base -> coinA -> coinB -> base
     *     - Reverse:  base -> coinB -> coinA -> base
     *  4) Verifica que los 3 simbolos existan en Binance y NO sean futuros perpetuos.
     *  5) Usa un Set{@code <String>} para evitar triangulos duplicados.
     *
     * @param coins Lista de monedas candidatas (ej: obtenidas de USDTNORMAL2.coins o API)
     * @param validSymbols Simbolos validos devueltos por Binance (puede ser null = saltar validacion)
     * @return Lista completa de triangulos listos para escanear
     */
    public List<Triangle> buildTriangles(List<String> coins, Set<String> validSymbols) {
        List<Triangle> triangles = new ArrayList<>();

        // Filtro 1: eliminar stablecoins y monedas vacias/malformadas
        List<String> validCoins = new ArrayList<>();
        for (String coin : coins) {
            String c = coin.trim().toUpperCase();
            if (!c.isEmpty() && !STABLE_COINS.contains(c)) {
                validCoins.add(c);
            }
        }

        // Set para tracking de IDs ya procesados (evita duplicados por permutaciones)
        Set<String> processed = new HashSet<>();

        // Generar todos los pares (coinA, coinB) con combinacion unica (i < j)
        for (int i = 0; i < validCoins.size(); i++) {
            for (int j = i + 1; j < validCoins.size(); j++) {
                String coinA = validCoins.get(i);
                String coinB = validCoins.get(j);

                // =============================================================
                // TRIANGULO FORWARD: base -> coinA -> coinB -> base
                // Simbolos:
                //   symbol1 = coinA + baseCurrency  (ej: BTCUSDT)  - COMPRAR coinA
                //   symbol2 = coinA + coinB         (ej: BTCETH)   - VENDER coinA por coinB
                //   symbol3 = coinB + baseCurrency  (ej: ETHUSDT)  - VENDER coinB por base
                // =============================================================
                String symbol1Forward = coinA + baseCurrency;
                String symbol2Forward = coinA + coinB;
                String symbol3Forward = coinB + baseCurrency;

                boolean forwardValid = isTriangleValid(symbol1Forward, symbol2Forward, symbol3Forward, validSymbols);

                if (forwardValid) {
                    // ID unico: "BTCUSDT->BTCETH->ETHUSDT"
                    String id1 = symbol1Forward + "->" + symbol2Forward + "->" + symbol3Forward;
                    if (!processed.contains(id1)) {
                        processed.add(id1);
                        triangles.add(Triangle.builder()
                            .id(id1)
                            .symbol1(symbol1Forward)
                            .symbol2(symbol2Forward)
                            .symbol3(symbol3Forward)
                            .baseCurrency(baseCurrency)
                            .intermediateCurrency(coinA)
                            .targetCurrency(coinB)
                            .forward(true)
                            .build());
                    }
                }

                // =============================================================
                // TRIANGULO REVERSE: base -> coinB -> coinA -> base
                // Simbolos:
                //   symbol1 = coinB + baseCurrency  (ej: ETHUSDT)  - COMPRAR coinB
                //   symbol2 = coinA + coinB         (ej: BTCETH)   - COMPRAR coinA con coinB
                //   symbol3 = coinA + baseCurrency  (ej: BTCUSDT)  - VENDER coinA por base
                //
                // Nota: symbol2 es el MISMO que en forward (coinA+coinB), solo cambia
                // la interpretacion (bid vs ask) segun la direccion del triangulo.
                // =============================================================
                String symbol1Reverse = coinB + baseCurrency;
                String symbol2Reverse = coinA + coinB;
                String symbol3Reverse = coinA + baseCurrency;

                boolean reverseValid = isTriangleValid(symbol1Reverse, symbol2Reverse, symbol3Reverse, validSymbols);

                if (reverseValid) {
                    String id2 = symbol1Reverse + "->" + symbol2Reverse + "->" + symbol3Reverse;
                    if (!processed.contains(id2)) {
                        processed.add(id2);
                        triangles.add(Triangle.builder()
                            .id(id2)
                            .symbol1(symbol1Reverse)
                            .symbol2(symbol2Reverse)
                            .symbol3(symbol3Reverse)
                            .baseCurrency(baseCurrency)
                            .intermediateCurrency(coinB)
                            .targetCurrency(coinA)
                            .forward(false)
                            .build());
                    }
                }
            }
        }

        return triangles;
    }

    /**
     * Valida que un triangulo de 3 simbolos sea viable:
     *   - Todos los simbolos existen en el conjunto {@code validSymbols} (cuando no es null).
     *   - Ningun simbolo corresponde a un futuro perpetuo (solo nos interesa spot).
     *
     * @param symbol1  Primer par del triangulo
     * @param symbol2  Segundo par del triangulo
     * @param symbol3  Tercer par del triangulo
     * @param validSymbols Simbolos reportados por Binance (null = omitir validacion)
     * @return true si los 3 simbolos existen y son spot
     */
    private boolean isTriangleValid(String symbol1, String symbol2, String symbol3, Set<String> validSymbols) {
        if (validSymbols == null || validSymbols.isEmpty()) {
            return true;
        }
        return validSymbols.contains(symbol1) &&
               validSymbols.contains(symbol2) &&
               validSymbols.contains(symbol3) &&
               !isPerpetualFuture(symbol1) &&
               !isPerpetualFuture(symbol2) &&
               !isPerpetualFuture(symbol3);
    }

    /**
     * Detecta si un simbolo corresponde a un futuro perpetuo (no spot).
     *
     * Binance nombra los futuros perpetuos con patrones especiales:
     *   - Contienen "_" (ej: "BTCUSDT_240329")
     *   - Terminan en USD + digitos (ej: "BTCUSD123")
     *   - Contienen "USD1", "USD2", "USD3"
     *   - Siguen patrones como "BTCUSD\d+", "ETHUSD\d+", "SOLUSD\d+"
     *
     * @param symbol Simbolo a verificar
     * @return true si es futuro perpetuo (debe excluirse)
     */
    private boolean isPerpetualFuture(String symbol) {
        // Futuros con fecha: BTCUSDT_240329
        if (symbol.contains("_")) return true;
        // Termina en USD + digitos: BTCUSD12345
        if (symbol.matches(".*USD\\d+$")) return true;
        // Variantes con USD1, USD2, USD3
        if (symbol.contains("USD1") || symbol.contains("USD2") || symbol.contains("USD3")) return true;
        // Patrones especificos de BTC/ETH/SOL futures
        if (symbol.matches(".*BTCUSD\\d+.*")) return true;
        if (symbol.matches(".*ETHUSD\\d+.*")) return true;
        if (symbol.matches(".*SOLUSD\\d+.*")) return true;
        return false;
    }
}