package com.arbitrage;

import com.arbitrage.config.ApiConfig;
import com.arbitrage.config.ConfigLoader;
import com.arbitrage.trading.BinanceApiClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import java.util.HashMap;
import java.util.Map;

public class GetBalances {
    public static void main(String[] args) {
        try {
            ApiConfig apiConfig = ConfigLoader.loadApiConfig("../user.apiConfig");
            BinanceApiClient client = new BinanceApiClient(apiConfig);

            System.out.println("=== SALDOS WALLET TESTNET (mayor a cero) ===\n");

            Map<String, String> params = new HashMap<>();
            params.put("timestamp", String.valueOf(System.currentTimeMillis()));
            params.put("recvWindow", "60000");
            
            String response = client.makeSignedRequest("/api/v3/account", params);

            if (response != null) {
                ObjectMapper mapper = new ObjectMapper();
                var accountJson = mapper.readTree(response);
                var balances = (ArrayNode) accountJson.get("balances");

                for (var balance : balances) {
                    String asset = balance.get("asset").asText();
                    double free = Double.parseDouble(balance.get("free").asText());
                    double locked = Double.parseDouble(balance.get("locked").asText());
                    
                    if (free > 0 || locked > 0) {
                        System.out.println(asset + " | Free: " + free + " | Locked: " + locked);
                    }
                }
            }

            client.shutdown();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}