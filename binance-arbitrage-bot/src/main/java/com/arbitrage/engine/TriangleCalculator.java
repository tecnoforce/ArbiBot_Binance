package com.arbitrage.engine;

import com.arbitrage.model.Triangle;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Calcula todas las combinaciones triangulares posibles.
 * 
 * Un triangulo de arbitraje es una secuencia de 3 operaciones
 * que empieza y termina en la misma moneda.
 * 
 * Formula: USDT -> MonedaA -> MonedaB -> USDT
 * Ejemplo: USDT -> BTC -> ETH -> USDT
 */
public class TriangleCalculator {
    private final String baseCurrency;
    private static final Set<String> STABLE_COINS = Set.of(
        "USDT", "USDC", "FDUSD", "DAI", "TUSD", "BUSD", "USDP", "PAXG", "EUR", "GBP"
    );

    public TriangleCalculator(String baseCurrency) {
        this.baseCurrency = baseCurrency;
    }

    public List<Triangle> buildTriangles(List<String> coins) {
        return buildTriangles(coins, null);
    }

    public List<Triangle> buildTriangles(List<String> coins, Set<String> validSymbols) {
        List<Triangle> triangles = new ArrayList<>();
        
        List<String> validCoins = new ArrayList<>();
        for (String coin : coins) {
            String c = coin.trim().toUpperCase();
            if (!c.isEmpty() && !STABLE_COINS.contains(c)) {
                validCoins.add(c);
            }
        }
        
        Set<String> processed = new HashSet<>();
        
        for (int i = 0; i < validCoins.size(); i++) {
            for (int j = i + 1; j < validCoins.size(); j++) {
                String coinA = validCoins.get(i);
                String coinB = validCoins.get(j);
                
                String symbol1Forward = coinA + baseCurrency;
                String symbol2Forward = coinA + coinB;
                String symbol3Forward = coinB + baseCurrency;
                
                boolean forwardValid = isTriangleValid(symbol1Forward, symbol2Forward, symbol3Forward, validSymbols);
                
                if (forwardValid) {
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
                
                String symbol1Reverse = coinB + baseCurrency;
                String symbol2Reverse = coinB + coinA;
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

    private boolean isPerpetualFuture(String symbol) {
        if (symbol.contains("_")) return true;
        if (symbol.matches(".*USD\\d+$")) return true;
        if (symbol.contains("USD1") || symbol.contains("USD2") || symbol.contains("USD3")) return true;
        if (symbol.matches(".*BTCUSD\\d+.*")) return true;
        if (symbol.matches(".*ETHUSD\\d+.*")) return true;
        if (symbol.matches(".*SOLUSD\\d+.*")) return true;
        return false;
    }
}