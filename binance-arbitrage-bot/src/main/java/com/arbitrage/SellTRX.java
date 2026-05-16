package com.arbitrage;

import com.arbitrage.config.ApiConfig;
import com.arbitrage.config.ConfigLoader;
import com.arbitrage.trading.BinanceApiClient;
import com.arbitrage.model.OrderResult;

/**
 * Herramienta independiente para vender un saldo fijo de TRX (1450)
 * contra USDT en TESTNET usando una orden MARKET.
 * <p>
 * Uso: ejecutar directamente. Requiere {@code ../user.apiConfig}.
 * Muestra el detalle completo de la orden ejecutada: precio, cantidad,
 * estado, ID de orden y &eacute;xito/error.
 * </p>
 */
public class SellTRX {
    /**
     * Punto de entrada. Carga credenciales, consulta el precio TRX/USDT,
     * ejecuta una orden MARKET de venta de 1450 TRX y muestra el resultado.
     *
     * @param args Argumentos (no utilizados)
     */
    public static void main(String[] args) {
        try {
            // Cargar credenciales y crear cliente API
            ApiConfig apiConfig = ConfigLoader.loadApiConfig("../user.apiConfig");
            BinanceApiClient client = new BinanceApiClient(apiConfig);
            
            System.out.println("=== VENDER TRX A USDT (TESTNET) ===\n");
            // Mostrar informacion de la configuracion actual (testnet, api key parcial)
            System.out.println("API Config - Testnet: " + apiConfig.isTestnet());
            System.out.println("API Key: " + apiConfig.getCurrentApiKey().substring(0, 10) + "...");
            
            // Saldo fijo de TRX a vender
            double trxBalance = 1450.0;
            // Obtener precio actual del par TRX/USDT
            double trxPrice = client.getSymbolPrice("TRXUSDT");
            System.out.println("Precio actual TRX/USDT: " + trxPrice);
            System.out.println("Balance TRX: " + trxBalance);
            System.out.println("Valor estimado: " + (trxBalance * trxPrice) + " USDT\n");
            
            // Ejecutar orden MARKET de venta: 1450 TRX a USDT
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