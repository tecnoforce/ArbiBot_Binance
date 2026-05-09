package com.arbitrage;

import com.arbitrage.config.ApiConfig;
import com.arbitrage.config.ConfigLoader;
import com.arbitrage.trading.BinanceApiClient;

public class GetTRXPrice {
    public static void main(String[] args) {
        try {
            ApiConfig apiConfig = ConfigLoader.loadApiConfig("../user.apiConfig");
            BinanceApiClient client = new BinanceApiClient(apiConfig);
            
            double trxPrice = client.getSymbolPrice("TRXUSDT");
            double trxBalance = 1450.0;
            double usdtValue = trxPrice * trxBalance;
            
            System.out.println("TRX Price: $" + trxPrice);
            System.out.println("TRX Balance: " + trxBalance);
            System.out.println("Equivalent USDT: $" + usdtValue);
            
            client.shutdown();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}