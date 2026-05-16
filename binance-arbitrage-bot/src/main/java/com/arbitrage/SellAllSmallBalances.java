package com.arbitrage;

import com.arbitrage.config.ApiConfig;
import com.arbitrage.config.ConfigLoader;
import com.arbitrage.trading.BinanceApiClient;
import com.arbitrage.model.OrderResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import java.util.HashSet;
import java.util.Set;

/**
 * Herramienta independiente para vender todos los saldos peque&ntilde;os
 * (menores a 10000 unidades) de la wallet TESTNET a USDT.
 * <p>
 * Uso: ejecutar directamente. Requiere {@code ../user.apiConfig}.
 * Obtiene todos los balances de la cuenta, filtra activos con saldo &gt; 0
 * y &lt; 10000, excluye stablecoins y monedas fiduciarias, y ejecuta
 * una orden MARKET de venta por cada uno contra USDT.
 * Incluye una pausa de 200ms entre &oacute;rdenes para evitar rate limiting.
 * </p>
 */
public class SellAllSmallBalances {
    // Lista de stablecoins conocidas que NO deben venderse (se usan como base de trading)
    private static final Set<String> STABLECOINS = new HashSet<>();
    
    static {
        STABLECOINS.add("USDT");
        STABLECOINS.add("USDC");
        STABLECOINS.add("TUSD");
        STABLECOINS.add("FDUSD");
        STABLECOINS.add("USD");
        STABLECOINS.add("USD1");
        STABLECOINS.add("USDP");
        STABLECOINS.add("BUSD");
        STABLECOINS.add("USDE");
        STABLECOINS.add("EURI");
        STABLECOINS.add("FRAX");
        STABLECOINS.add("USDS");
        STABLECOINS.add("DAI");
        STABLECOINS.add("USDK");
        STABLECOINS.add("BKRW");
        STABLECOINS.add("IDRT");
        STABLECOINS.add("BVND");
        STABLECOINS.add("VAI");
        STABLECOINS.add("UST");
        STABLECOINS.add("USTC");
        STABLECOINS.add("XUSD");
        STABLECOINS.add("BFUSD");
        STABLECOINS.add("RLUSD");
    }
    
    // Lista de monedas fiduciarias y otros activos a excluir de la venta
    private static final Set<String> EXCLUDE = new HashSet<>();
    
    static {
        EXCLUDE.add("???");
        EXCLUDE.add("456");
        EXCLUDE.add("TRY");
        EXCLUDE.add("ZAR");
        EXCLUDE.add("PLN");
        EXCLUDE.add("ARS");
        EXCLUDE.add("COP");
        EXCLUDE.add("MXN");
        EXCLUDE.add("JPY");
        EXCLUDE.add("EUR");
        EXCLUDE.add("UAH");
        EXCLUDE.add("BRL");
        EXCLUDE.add("NGN");
        EXCLUDE.add("KES");
        EXCLUDE.add("EGP");
        EXCLUDE.add("AED");
        EXCLUDE.add("SAR");
        EXCLUDE.add("KWD");
        EXCLUDE.add("QAR");
        EXCLUDE.add("BHD");
        EXCLUDE.add("OMR");
        EXCLUDE.add("JOD");
        EXCLUDE.add("LBP");
        EXCLUDE.add("SYP");
        EXCLUDE.add("IQD");
        EXCLUDE.add("DZD");
        EXCLUDE.add("TND");
        EXCLUDE.add("MAD");
        EXCLUDE.add("GEL");
        EXCLUDE.add("AMD");
        EXCLUDE.add("AZN");
        EXCLUDE.add("KZT");
        EXCLUDE.add("UZS");
        EXCLUDE.add("KGS");
        EXCLUDE.add("TJS");
        EXCLUDE.add("MNT");
        EXCLUDE.add("MMK");
        EXCLUDE.add("KHR");
        EXCLUDE.add("LAK");
        EXCLUDE.add("MKP");
        EXCLUDE.add("NPR");
        EXCLUDE.add("PKR");
        EXCLUDE.add("LKR");
        EXCLUDE.add("BDT");
        EXCLUDE.add("VND");
        EXCLUDE.add("THB");
        EXCLUDE.add("MYR");
        EXCLUDE.add("SGD");
        EXCLUDE.add("PHP");
        EXCLUDE.add("IDR");
        EXCLUDE.add("RUB");
        EXCLUDE.add("RWF");
        EXCLUDE.add("ETB");
        EXCLUDE.add("XOF");
        EXCLUDE.add("XAF");
        EXCLUDE.add("ZMW");
        EXCLUDE.add("MZN");
        EXCLUDE.add("BWP");
        EXCLUDE.add("ZAR");
    }
    
    /**
     * Punto de entrada. Obtiene todos los balances, filtra activos peque&ntilde;os
     * no-stablecoin no-fiat, y ejecuta una orden MARKET de venta por cada uno.
     *
     * @param args Argumentos (no utilizados)
     */
    public static void main(String[] args) {
        try {
            // Cargar credenciales y crear cliente API
            ApiConfig apiConfig = ConfigLoader.loadApiConfig("../user.apiConfig");
            BinanceApiClient client = new BinanceApiClient(apiConfig);
            
            System.out.println("=== VENDER TODOS LOS SALDOS < 10000 (TESTNET) ===\n");
            
            // Mapper para parsear la respuesta JSON del endpoint /api/v3/account
            ObjectMapper mapper = new ObjectMapper();
            // Parametros para la llamada firmada
            java.util.Map<String, String> params = new java.util.HashMap<>();
            params.put("timestamp", String.valueOf(System.currentTimeMillis()));
            params.put("recvWindow", "60000");
            
            String response = client.makeSignedRequest("/api/v3/account", params);
            
            // Procesar la respuesta y filtrar activos con saldo > 0 y < 10000
            if (response != null) {
                var accountJson = mapper.readTree(response);
                var balances = (ArrayNode) accountJson.get("balances");
                
                // Contadores para el resumen final
                int successCount = 0;
                int failCount = 0;
                double totalSold = 0;
                
                // Iterar sobre todos los balances de la cuenta
                for (var balance : balances) {
                    String asset = balance.get("asset").asText();
                    double free = Double.parseDouble(balance.get("free").asText());
                    double locked = Double.parseDouble(balance.get("locked").asText());
                    double total = free + locked;
                    
                    // Filtrar: saldo positivo, < 10000, no stablecoin, no fiat
                    if (total > 0 && total < 10000 && !STABLECOINS.contains(asset) && !EXCLUDE.contains(asset)) {
                        String symbol = asset + "USDT";
                        // Obtener precio actual del par contra USDT
                        double price = client.getSymbolPrice(symbol);
                        
                        // Si no tiene precio USDT, saltar
                        if (price <= 0) {
                            System.out.println("SKIP " + asset + " - Sin precio USDT");
                            continue;
                        }
                        
                        System.out.println("Vendiendo " + asset + " (" + total + ") @ " + price + " = " + (total * price) + " USDT");
                        
                        // Ejecutar orden MARKET de venta del saldo completo
                        try {
                            OrderResult result = client.placeOrder(symbol, "SELL", "MARKET", total, price, total * price, true);
                            
                            if (result.isSuccess()) {
                                System.out.println("  -> OK: " + result.getStatus() + " OrderID: " + result.getOrderId());
                                successCount++;
                                totalSold += total * price;
                            } else {
                                System.out.println("  -> ERROR: " + result.getErrorMessage());
                                failCount++;
                            }
                        } catch (Exception e) {
                            System.out.println("  -> ERROR: " + e.getMessage());
                            failCount++;
                        }
                        
                        // Pausa de 200ms entre ordenes para evitar rate limiting de Binance
                        Thread.sleep(200);
                    }
                }
                
                System.out.println("\n=== RESUMEN ===");
                System.out.println("Vendidos exitosamente: " + successCount);
                System.out.println("Fallidos: " + failCount);
                System.out.println("Total recibidas en USDT: " + totalSold);
            }
            
            client.shutdown();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}