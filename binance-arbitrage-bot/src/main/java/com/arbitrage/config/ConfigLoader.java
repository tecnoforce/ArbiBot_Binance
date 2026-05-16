package com.arbitrage.config;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Cargador de archivos de configuracion del bot de arbitraje.
 * <p>
 Lee archivos de texto plano con formato {@code clave valor} (separados por espacios)
 y los transforma en objetos tipados: {@link AppConfig}, {@link ApiConfig} y listas de monedas.
 * <p>
 * Los archivos soportan:
 * <ul>
 *   <li>Comentarios: lineas que empiezan con {@code #}</li>
 *   <li>Lineas vacias: ignoradas automaticamente</li>
 *   <li>Claves case-insensitive en AppConfig (convertidas a minusculas)</li>
 * </ul>
 * Las claves desconocidas se ignoran sin error para permitir campos nuevos sin romper versiones viejas.
 */
public class ConfigLoader {

    /**
     * Carga la configuracion general del bot desde un archivo de texto.
     * <p>
     * Formato esperado (una clave por linea, separada por espacios):
     * <pre>
     * basecurrency USDT
     * minprofit 0.1
     * feeop1 0.1
     * balancepertrade 100
     * realorder false
     * loglevel INFO
     * </pre>
     * Las claves son <b>case-insensitive</b>: el parser las convierte a minusculas
     * antes de hacer el match. Claves no reconocidas se ignoran silenciosamente.
     *
     * @param configFilePath Ruta absoluta o relativa del archivo .config
     * @return AppConfig con todos los parametros parseados (usa valores {@code null} o 0 si no se especificaron)
     * @throws IOException si el archivo no existe, es un directorio o no puede leerse
     */
    public static AppConfig loadAppConfig(String configFilePath) throws IOException {
        // Lombok builder: construye el objeto de forma inmutable y fluida
        AppConfig.AppConfigBuilder builder = AppConfig.builder();

        try (BufferedReader reader = new BufferedReader(new FileReader(configFilePath))) {
            String line;
            while ((line = reader.readLine()) != null) {
                // Limpieza: elimina whitespace y salta lineas irrelevantes
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) continue;

                // Split por whitespace: espera exactamente "clave valor"
                String[] parts = line.split("\\s+");
                if (parts.length < 2) continue;

                String key = parts[0];
                String value = parts[1];

                // NOTA: se usa toLowerCase() para que las claves sean case-insensitive
                switch (key.toLowerCase()) {
                    case "basecurrency":
                        builder.baseCurrency(value);
                        break;
                    case "minprofit":
                        builder.minProfit(Double.parseDouble(value));
                        break;
                    // ---------------------------------------------------------------
                    // UMBRALES DE RENTABILIDAD: profit minimo y maximo esperado
                    // ---------------------------------------------------------------
                    case "maxprofit":
                        builder.maxProfit(Double.parseDouble(value));
                        break;
                    case "feeop1":
                        builder.feeOp1(Double.parseDouble(value));
                        break;
                    // ---------------------------------------------------------------
                    // COMISIONES: fee de cada pata del triangulo (Op1, Op2, Op3)
                    // ---------------------------------------------------------------
                    case "feeop2":
                        builder.feeOp2(Double.parseDouble(value));
                        break;
                    case "feeop3":
                        builder.feeOp3(Double.parseDouble(value));
                        break;
                    case "safetyvolume":
                        builder.safetyVolume(Double.parseDouble(value));
                        break;
                    // ---------------------------------------------------------------
                    // PARAMETROS DE TRADING: volumen, paralelismo, balance por trade
                    // ---------------------------------------------------------------
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
                    // ---------------------------------------------------------------
                    // LOGS Y CONTROL DE OPERACIONES: nivel de log, max abiertas, blacklist
                    // ---------------------------------------------------------------
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
                    // ---------------------------------------------------------------
                    // TIPOS DE ORDEN: MARKET o LIMIT para cada operacion del triangulo
                    // ---------------------------------------------------------------
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
                    // ---------------------------------------------------------------
                    // INTERVALOS DE TIEMPO: polling, timeout de orden, sync de wallet
                    // ---------------------------------------------------------------
                    case "pollinginterval":
                        builder.pollingIntervalMs(Long.parseLong(value));
                        break;
                    case "ordertimeout":
                        builder.orderTimeoutMs(Long.parseLong(value));
                        break;
                    case "walletsyncinterval":
                        builder.walletSyncIntervalMs(Long.parseLong(value));
                        break;
                    case "testserver":
                        builder.testserver(Boolean.parseBoolean(value));
                        break;
                    // ---------------------------------------------------------------
                    // VERIFICACION DE BNB PARA DESCUENTO DE COMISIONES
                    // ---------------------------------------------------------------
                    case "checkbnbbalance":
                        builder.checkBNBBalance(Boolean.parseBoolean(value));
                        break;
                    case "minbnbbalance":
                        builder.minBNBBalance(Double.parseDouble(value));
                        break;
                }
            }
        }

        return builder.build();
    }

    /**
     * Carga las credenciales de la API de Binance desde un archivo de configuracion.
     * <p>
     * El archivo puede contener credenciales tanto para <b>Mainnet</b> como para <b>Testnet</b>.
     * Se usa la bandera {@code testnet true/false} para seleccionar cual conjunto activar.
     * <p>
     * Formato esperado (una clave por linea):
     * <pre>
     * testnet true
     * apikey TU_API_KEY_MAINNET
     * secret TU_SECRET_MAINNET
     * baseUrl https://api.binance.com
     * wsUrl wss://stream.binance.com:9443
     * testnet_apikey TU_API_KEY_TESTNET
     * testnet_secret TU_SECRET_TESTNET
     * testnet_coins 50
     * </pre>
     * Las URLs tienen valores por defecto desde {@link NetworkEndpoints} si no se especifican.
     *
     * @param apiConfigFilePath Ruta del archivo de credenciales (usualmente {@code user.apiConfig})
     * @return ApiConfig con credenciales para ambos entornos y la bandera de seleccion
     * @throws IOException si el archivo no existe o no puede leerse
     */
    public static ApiConfig loadApiConfig(String apiConfigFilePath) throws IOException {
        // Valores por defecto para MAINNET: claves vacias + endpoints de produccion
        String apiKey = "";
        String secretKey = "";
        String baseUrl = NetworkEndpoints.MAINNET_REST_URL;
        String wsUrl = NetworkEndpoints.MAINNET_WS_URL;
        boolean testnet = false;  // Por defecto asume mainnet
        
        // Valores por defecto para TESTNET: claves vacias + endpoints de pruebas
        String testnetApiKey = "";
        String testnetSecretKey = "";
        String testnetBaseUrl = NetworkEndpoints.TESTNET_REST_URL;
        String testnetWsUrl = NetworkEndpoints.TESTNET_WS_URL;
        int testnetCoins = 0;  // 0 = cargar todas las monedas disponibles

        try (BufferedReader reader = new BufferedReader(new FileReader(apiConfigFilePath))) {
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) continue;

                String[] parts = line.split("\\s+");
                if (parts.length < 2) continue;

                String key = parts[0];
                String value = parts[1];

                // NOTA: Este switch NO usa toLowerCase() — las claves de API son exactas
                // Cada case acepta dos formatos: "apikey" y "apiKey" por compatibilidad
                switch (key) {
                    // ---------------------------------------------------------------
                    // CREDENCIALES MAINNET
                    // ---------------------------------------------------------------
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

                    // ---------------------------------------------------------------
                    // BANDERA DE ENTORNO: true = testnet, false/ausente = mainnet
                    // ---------------------------------------------------------------
                    case "testnet":
                        testnet = Boolean.parseBoolean(value);
                        break;

                    // ---------------------------------------------------------------
                    // CREDENCIALES TESTNET
                    // ---------------------------------------------------------------
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

        // Construye ApiConfig con ambos conjuntos de credenciales (mainnet + testnet)
        // El entorno activo lo determina la bandera testnet en tiempo de ejecucion
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
     * Carga la lista de monedas a monitorear desde un archivo de texto y genera
     * los pares de trading necesarios para la deteccion de triangulos.
     * <p>
     * Por cada moneda en el archivo (ejemplo {@code ETH}) se generan DOS pares:
     * <ul>
     *   <li>{@code ETH + baseCurrency} (ej. ETHUSDT) — para el lado del triangulo contra USDT</li>
     *   <li>{@code ETH + BTC} (ej. ETHBTC) — para el lado del triangulo contra BTC</li>
     * </ul>
     * BTCUSDT siempre se incluye aunque no este en el archivo.
     *
     * @param coinsFilePath Ruta del archivo .coins (una moneda por linea)
     * @return Lista de simbolos tipo {@code ["ETHUSDT", "ETHBTC", "SOLUSDT", "SOLBTC", "BTCUSDT"]}
     * @throws IOException si el archivo no existe o no puede leerse
     */
    public static List<String> loadCoins(String coinsFilePath) throws IOException {
        Set<String> uniqueCoins = new LinkedHashSet<>();
        String baseCurrency = "USDT";

        try (BufferedReader reader = new BufferedReader(new FileReader(coinsFilePath))) {
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) continue;

                String[] parts = line.split("\\s+");
                for (String part : parts) {
                    part = part.trim().toUpperCase();
                    if (part.isEmpty()) continue;

                    if (!part.equals("BTC") && !part.equals(baseCurrency)) {
                        uniqueCoins.add(part + baseCurrency);
                        uniqueCoins.add(part + "BTC");
                    }
                }
            }

            if (!uniqueCoins.contains("BTC" + baseCurrency)) {
                uniqueCoins.add("BTC" + baseCurrency);
            }
        }

        return new ArrayList<>(uniqueCoins);
    }
}