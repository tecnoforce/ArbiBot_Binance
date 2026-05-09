package com.arbitrage;

import com.arbitrage.config.ApiConfig;
import com.arbitrage.config.ConfigLoader;
import com.arbitrage.trading.BinanceApiClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import java.util.*;

public class GetOrderHistory {
    public static void main(String[] args) {
        try {
            ApiConfig apiConfig = ConfigLoader.loadApiConfig("../user.apiConfig");
            BinanceApiClient client = new BinanceApiClient(apiConfig);
            
            System.out.println("=== HISTORIAL DE TRADES (TESTNET) ===\n");
            
            String[] symbols = {
                "TRXUSDT", "BTCUSDT", "ETHUSDT", "BNBUSDT", "LTCUSDT", "SOLUSDT",
                "ADAUSDT", "XRPUSDT", "DOGEUSDT", "DOTUSDT", "AVAXUSDT", "MATICUSDT",
                "LINKUSDT", "UNIUSDT", "ATOMUSDT", "LTCUSDT", "NEARUSDT", "FILUSDT",
                "APTUSDT", "ARBUSDT", "OPUSDT", "SUIUSDT", "WBTCUSDT", "WBETHUSDT",
                "YFIUSDT", "PAXGUSDT", "BCHUSDT"
            };
            
            ObjectMapper mapper = new ObjectMapper();
            Set<String> printedTrades = new LinkedHashSet<>();
            int totalTrades = 0;
            
            for (String symbol : symbols) {
                try {
                    Map<String, String> params = new HashMap<>();
                    params.put("symbol", symbol);
                    params.put("timestamp", String.valueOf(System.currentTimeMillis()));
                    params.put("limit", "100");
                    params.put("recvWindow", "60000");
                    
                    String response = client.makeSignedRequest("/api/v3/myTrades", params);
                    
                    if (response != null && !response.isEmpty()) {
                        var trades = (ArrayNode) mapper.readTree(response);
                        
                        for (var trade : trades) {
                            String tradeId = trade.get("id").asText();
                            if (!printedTrades.contains(tradeId)) {
                                printedTrades.add(tradeId);
                                
                                String sym = trade.get("symbol").asText();
                                String side = trade.get("isBuyer").asBoolean() ? "BUY" : "SELL";
                                double price = trade.get("price").asDouble();
                                double qty = trade.get("qty").asDouble();
                                double commission = trade.get("commission").asDouble();
                                String commAsset = trade.get("commissionAsset").asText();
                                long time = trade.get("time").asLong();
                                String date = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss")
                                    .format(new Date(time));
                                
                                double total = price * qty;
                                System.out.println(date + " | " + sym + " | " + side + " | " + 
                                    String.format("%.4f", qty) + " @ " + String.format("%.4f", price) + 
                                    " | Total: " + String.format("%.2f", total) + " | Fee: " + 
                                    String.format("%.4f", commission) + " " + commAsset);
                                
                                totalTrades++;
                            }
                        }
                    }
                } catch (Exception e) {
                    // Skip symbol if fails
                }
            }
            
            System.out.println("\n=== Total trades: " + totalTrades + " ===");
            
            client.shutdown();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}