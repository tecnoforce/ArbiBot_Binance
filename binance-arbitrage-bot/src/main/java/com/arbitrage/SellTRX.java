package com.arbitrage;

import com.arbitrage.config.ApiConfig;
import com.arbitrage.config.ConfigLoader;
import com.arbitrage.trading.BinanceApiClient;
import com.arbitrage.model.OrderResult;

public class SellTRX {
    public static void main(String[] args) {
        try {
            ApiConfig apiConfig = ConfigLoader.loadApiConfig("../user.apiConfig");
            BinanceApiClient client = new BinanceApiClient(apiConfig);
            
            System.out.println("=== VENDER TRX A USDT (TESTNET) ===\n");
            System.out.println("API Config - Testnet: " + apiConfig.isTestnet());
            System.out.println("API Key: " + apiConfig.getCurrentApiKey().substring(0, 10) + "...");
            
            double trxBalance = 1450.0;
            double trxPrice = client.getSymbolPrice("TRXUSDT");
            System.out.println("Precio actual TRX/USDT: " + trxPrice);
            System.out.println("Balance TRX: " + trxBalance);
            System.out.println("Valor estimado: " + (trxBalance * trxPrice) + " USDT\n");
            
            OrderResult result = client.placeOrder("TRXUSDT", "SELL", "MARKET", trxBalance, trxPrice, trxBalance * trxPrice, true);
            
            System.out.println("=== RESULTADO ===");
            System.out.println("Symbol: " + result.getSymbol());
            System.out.println("Side: " + result.getSide());
            System.out.println("Type: " + result.getOrderType());
            System.out.println("Quantity: " + result.getQuantity());
            System.out.println("Price: " + result.getPrice());
            System.out.println("Status: " + result.getStatus());
            System.out.println("Order ID: " + result.getOrderId());
            System.out.println("Success: " + result.isSuccess());
            if (result.getErrorMessage() != null) {
                System.out.println("Error: " + result.getErrorMessage());
            }
            
            client.shutdown();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}