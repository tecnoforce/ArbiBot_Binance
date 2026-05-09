package com.arbitrage;

import com.arbitrage.config.ApiConfig;
import com.arbitrage.config.ConfigLoader;
import com.arbitrage.trading.BinanceApiClient;
import com.arbitrage.model.OrderResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import java.util.HashSet;
import java.util.Set;

public class SellAllSmallBalances {
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
    
    public static void main(String[] args) {
        try {
            ApiConfig apiConfig = ConfigLoader.loadApiConfig("../user.apiConfig");
            BinanceApiClient client = new BinanceApiClient(apiConfig);
            
            System.out.println("=== VENDER TODOS LOS SALDOS < 10000 (TESTNET) ===\n");
            
            ObjectMapper mapper = new ObjectMapper();
            java.util.Map<String, String> params = new java.util.HashMap<>();
            params.put("timestamp", String.valueOf(System.currentTimeMillis()));
            params.put("recvWindow", "60000");
            
            String response = client.makeSignedRequest("/api/v3/account", params);
            
            if (response != null) {
                var accountJson = mapper.readTree(response);
                var balances = (ArrayNode) accountJson.get("balances");
                
                int successCount = 0;
                int failCount = 0;
                double totalSold = 0;
                
                for (var balance : balances) {
                    String asset = balance.get("asset").asText();
                    double free = Double.parseDouble(balance.get("free").asText());
                    double locked = Double.parseDouble(balance.get("locked").asText());
                    double total = free + locked;
                    
                    if (total > 0 && total < 10000 && !STABLECOINS.contains(asset) && !EXCLUDE.contains(asset)) {
                        String symbol = asset + "USDT";
                        double price = client.getSymbolPrice(symbol);
                        
                        if (price <= 0) {
                            System.out.println("SKIP " + asset + " - Sin precio USDT");
                            continue;
                        }
                        
                        System.out.println("Vendiendo " + asset + " (" + total + ") @ " + price + " = " + (total * price) + " USDT");
                        
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