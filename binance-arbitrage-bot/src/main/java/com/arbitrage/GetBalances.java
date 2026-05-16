package com.arbitrage;

import com.arbitrage.config.ApiConfig;
import com.arbitrage.config.ConfigLoader;
import com.arbitrage.trading.BinanceApiClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import java.util.HashMap;
import java.util.Map;

/**
 * Herramienta independiente para consultar y mostrar los saldos de la wallet
 * en TESTNET.
 * <p>
 * Uso: ejecutar directamente desde l&iacute;nea de comandos. Requiere
 * {@code ../user.apiConfig} con credenciales de testnet v&aacute;lidas.
 * Llama al endpoint {@code /api/v3/account} y muestra todos los activos
 * con saldo disponible ({@code free}) o bloqueado ({@code locked}) mayor a cero.
 * </p>
 */
public class GetBalances {
    /**
     * Punto de entrada. Carga credenciales, obtiene la informacion de la cuenta
     * desde Binance y muestra cada activo con saldo > 0.
     *
     * @param args Argumentos (no utilizados)
     */
    public static void main(String[] args) {
        try {
            // Cargar credenciales desde el archivo user.apiConfig
            ApiConfig apiConfig = ConfigLoader.loadApiConfig("../user.apiConfig");
            // Cliente HTTP para llamadas REST firmadas
            BinanceApiClient client = new BinanceApiClient(apiConfig);

            System.out.println("=== SALDOS WALLET TESTNET (mayor a cero) ===\n");

            // Parametros para la llamada firmada al endpoint /api/v3/account
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