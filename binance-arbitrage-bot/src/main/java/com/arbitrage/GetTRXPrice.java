package com.arbitrage;

import com.arbitrage.config.ApiConfig;
import com.arbitrage.config.ConfigLoader;
import com.arbitrage.trading.BinanceApiClient;

/**
 * Herramienta independiente para obtener el precio actual de TRX/USDT
 * y calcular el valor equivalente de un saldo fijo de TRX (1450).
 * <p>
 * Uso: ejecutar directamente. Requiere {@code ../user.apiConfig}.
 * Utiliza el m&eacute;todo {@link BinanceApiClient#getSymbolPrice(String)}
 * para consultar el precio actual via API REST.
 * </p>
 */
public class GetTRXPrice {
    /**
     * Punto de entrada. Obtiene el precio TRX/USDT, calcula el valor
     * en USDT de 1450 TRX y muestra los resultados en consola.
     *
     * @param args Argumentos (no utilizados)
     */
    public static void main(String[] args) {
        try {
            // Cargar credenciales y crear cliente API
            ApiConfig apiConfig = ConfigLoader.loadApiConfig("../user.apiConfig");
            BinanceApiClient client = new BinanceApiClient(apiConfig);
            
            // Obtener precio actual del par TRX/USDT desde la API REST
            double trxPrice = client.getSymbolPrice("TRXUSDT");
            // Saldo fijo de ejemplo (1450 TRX)
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