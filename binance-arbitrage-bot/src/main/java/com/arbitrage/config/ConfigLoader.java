package com.arbitrage.config;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Clase utilitaria para cargar archivos de configuracion.
 * Lee archivos de texto plano y los convierte en objetos de configuracion:
 * - AppConfig: parametros generales del trading
 * - ApiConfig: credenciales y URLs de Binance
 * - Coins: lista de pares a monitorear
 */
public class ConfigLoader {

    /**
     * Carga configuracion de la aplicacion desde archivo.
     * Formato esperado (cada linea): clave valor
     *   basecurrency USDT
     *   minprofit 0.1
     *   feeop1 0.1
     *   balancepertrade 100
     *   realorder false
     *   loglevel INFO
     * @param configFilePath Ruta del archivo de configuracion
     * @return Objeto AppConfig con los parametros cargados
     * @throws IOException Si no puede leer el archivo
     */
    public static AppConfig loadAppConfig(String configFilePath) throws IOException {
        // Builder Lombok para construir objeto AppConfig
        AppConfig.AppConfigBuilder builder = AppConfig.builder();

        try (BufferedReader reader = new BufferedReader(new FileReader(configFilePath))) {
            String line;
            while ((line = reader.readLine()) != null) {
                // Elimina espacios al inicio/final
                line = line.trim();
                // Ignora lineas vacias o comentarios
                if (line.isEmpty() || line.startsWith("#")) continue;

                // Divide en clave y valor (separados por espacios)
                String[] parts = line.split("\\s+");
                if (parts.length < 2) continue;

                // Extrae clave y valor
                String key = parts[0];
                String value = parts[1];

                // Mapea cada clave al campo correspondiente
                switch (key.toLowerCase()) {
                    case "basecurrency":
                        builder.baseCurrency(value);
                        break;
                    case "minprofit":
                        builder.minProfit(Double.parseDouble(value));
                        break;
                    case "maxprofit":
                        builder.maxProfit(Double.parseDouble(value));
                        break;
                    case "feeop1":
                        builder.feeOp1(Double.parseDouble(value));
                        break;
                    case "feeop2":
                        builder.feeOp2(Double.parseDouble(value));
                        break;
                    case "feeop3":
                        builder.feeOp3(Double.parseDouble(value));
                        break;
                    case "safetyvolume":
                        builder.safetyVolume(Double.parseDouble(value));
                        break;
                    case "cores":
                        builder.cores(Integer.parseInt(value));
                        break;
                    case "balancepertrade":
                        builder.balancePerTrade(Double.parseDouble(value));
                        break;
                    case "stoplossfactor":
                        builder.stoplossFactor(Double.parseDouble(value));
                        break;
                    case "strategy":
                        builder.strategy(value);
                        break;
                    case "logs":
                        builder.logs(value);
                        break;
                    case "maxopentrades":
                        builder.maxOpenTrades(Integer.parseInt(value));
                        break;
                    case "profitreversalop1":
                        builder.profitReversalOp1(Double.parseDouble(value));
                        break;
                    case "blacklist":
                        builder.blacklist(value);
                        break;
                    case "modehf":
                        builder.modeHF(Boolean.parseBoolean(value));
                        break;
                    case "typeop1":
                        builder.typeOp1(value);
                        break;
                    case "typeop2":
                        builder.typeOp2(value);
                        break;
                    case "typeop3":
                        builder.typeOp3(value);
                        break;
                    case "realorder":
                        builder.realorder(Boolean.parseBoolean(value));
                        break;
                    case "loglevel":
                        builder.logLevel(value);
                        break;
                    case "pollinginterval":
                        builder.pollingIntervalMs(Long.parseLong(value));
                        break;
                    case "ordertimeout":
                        builder.orderTimeoutMs(Long.parseLong(value));
                        break;
                    case "walletsyncinterval":
                        builder.walletSyncIntervalMs(Long.parseLong(value));
                        break;
                }
            }
        }

        return builder.build();
    }

    /**
     * Carga credenciales API desde archivo.
     * Formato esperado:
     *   testnet true
     *   apikey TU_API_KEY
     *   secret TU_SECRET_KEY
     *   baseUrl https://api.binance.com
     *   wsUrl wss://stream.binance.com
     *   testnet_apikey KEY_TESTNET
     *   testnet_secret SECRET_TESTNET
     * @param apiConfigFilePath Ruta del archivo de credenciales
     * @return Objeto ApiConfig con credenciales cargadas
     * @throws IOException Si no puede leer el archivo
     */
    public static ApiConfig loadApiConfig(String apiConfigFilePath) throws IOException {
        // Valores por defecto para Mainnet
        String apiKey = "";
        String secretKey = "";
        String baseUrl = NetworkEndpoints.MAINNET_REST_URL;
        String wsUrl = NetworkEndpoints.MAINNET_WS_URL;
        boolean testnet = false;
        
        // Valores por defecto para Testnet
        String testnetApiKey = "";
        String testnetSecretKey = "";
        String testnetBaseUrl = NetworkEndpoints.TESTNET_REST_URL;
        String testnetWsUrl = NetworkEndpoints.TESTNET_WS_URL;
        int testnetCoins = 0;  // Por defecto carga todas

        try (BufferedReader reader = new BufferedReader(new FileReader(apiConfigFilePath))) {
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) continue;

                String[] parts = line.split("\\s+");
                if (parts.length < 2) continue;

                String key = parts[0];
                String value = parts[1];

                switch (key) {
                    case "apikey":
                    case "apiKey":
                        apiKey = value;
                        break;
                    case "secret":
                    case "secretKey":
                        secretKey = value;
                        break;
                    case "baseUrl":
                        baseUrl = value;
                        break;
                    case "wsUrl":
                        wsUrl = value;
                        break;
                    case "testnet":
                        testnet = Boolean.parseBoolean(value);
                        break;
                    case "testnet_apikey":
                    case "testnetApiKey":
                        testnetApiKey = value;
                        break;
                    case "testnet_secret":
                    case "testnetSecretKey":
                        testnetSecretKey = value;
                        break;
                    case "testnet_baseUrl":
                        testnetBaseUrl = value;
                        break;
                    case "testnet_wsUrl":
                        testnetWsUrl = value;
                        break;
                    case "testnet_coins":
                    case "testnetCoins":
                        testnetCoins = Integer.parseInt(value);
                        break;
                }
            }
        }

        // Retorna objeto ApiConfig con todas las credenciales
        return ApiConfig.builder()
                .apiKey(apiKey)
                .secretKey(secretKey)
                .baseUrl(baseUrl)
                .wsUrl(wsUrl)
                .testnet(testnet)
                .testnetApiKey(testnetApiKey)
                .testnetSecretKey(testnetSecretKey)
                .testnetBaseUrl(testnetBaseUrl)
                .testnetWsUrl(testnetWsUrl)
                .testnetCoins(testnetCoins)
                .build();
    }

    /**
     * Carga lista de simbolos/monedas a monitorear.
     * Formato: una moneda por linea (ej: ETH, BTC, SOL)
     * Se generan automaticamente los pares:
     *   - MONEDA + baseCurrency (ej: ETHUSDT)
     *   - MONEDA + BTC (ej: ETHBTC)
     * @param coinsFilePath Ruta del archivo de monedas
     * @return Lista de simbolos (ej: ["ETHUSDT", "ETHBTC", "BTCUSDT"])
     * @throws IOException Si no puede leer el archivo
     */
    public static List<String> loadCoins(String coinsFilePath) throws IOException {
        List<String> coins = new ArrayList<>();
        String baseCurrency = "USDT";  // Moneda base por defecto

        try (BufferedReader reader = new BufferedReader(new FileReader(coinsFilePath))) {
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) continue;

                String[] parts = line.split("\\s+");
                for (String part : parts) {
                    part = part.trim().toUpperCase();
                    if (part.isEmpty()) continue;
                    
                    // Evita duplicados y la propia base (ej: USDT)
                    if (!part.equals("BTC") && !part.equals(baseCurrency)) {
                        // Agrega par con USDT y con BTC
                        coins.add(part + baseCurrency);
                        coins.add(part + "BTC");
                    }
                }
            }
            
            // Siempre agrega BTCUSDT si no existe
            if (!coins.contains("BTC" + baseCurrency)) {
                coins.add("BTC" + baseCurrency);
            }
        }

        return coins;
    }
}