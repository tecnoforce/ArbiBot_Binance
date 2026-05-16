package com.arbitrage.test;

import com.arbitrage.config.ApiConfig;
import com.arbitrage.trading.BinanceApiClient;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * Herramienta independiente para consultar saldos y filtros de precisi&oacute;n
 * en la testnet de Binance.
 * <p>
 * Uso: ejecutar directamente. Contiene credenciales hardcodeadas para testnet.
 * Obtiene los filtros LOT_SIZE y PRICE_FILTER de exchangeInfo, consulta los
 * balances de la cuenta, y para cada activo con saldo &gt; 0 muestra su
 * precisi&oacute;n de lote (stepSize) y precio (tickSize) con sus decimales.
 &Uacute;til para conocer la precisi&oacute;n exacta requerida al enviar &oacute;rdenes.
 * </p>
 */
public class TestnetBalanceChecker {

    // URL base de la API REST de testnet de Binance
    private static final String TESTNET_REST_URL = "https://testnet.binance.vision";
    // API Key hardcodeada para testnet
    private static final String TESTNET_API_KEY = "Yws67cYTj9MKyasVwsqBDZktJQ3tjxojkae2dvgobJSQDgDggmTv2mHrGCLP5jbt";
    // Secret Key hardcodeada para testnet
    private static final String TESTNET_SECRET = "9eQuZl6FuMNLBANTiywgJm5PlLJeMgPSP7tk1fyTwqTjuPDRahJJdIYHkvpO6MJK";
    // Ventana de recepcion para las solicitudes firmadas (60 segundos)
    private static final long RECV_WINDOW = 60000L;

    /**
     * Punto de entrada. Crea una instancia y ejecuta el chequeo.
     *
     * @param args Argumentos (no utilizados)
     */
    public static void main(String[] args) {
        System.out.println("=== BINANCE TESTNET - SALDOS Y PRECISION ===\n");

        TestnetBalanceChecker checker = new TestnetBalanceChecker();
        checker.run();
    }

    /**
     * Metodo principal de la herramienta. Carga los filtros LOT_SIZE y
     * PRICE_FILTER de la API, obtiene los balances de la cuenta y muestra
     * una tabla con: moneda, saldo, stepSize, decimales de lote, tickSize
     * y decimales de precio.
     */
    private void run() {
        try {
            Map<String, Map<String, Double>> lotSizeFilters = new HashMap<>();
            Map<String, Double> priceTickSizes = new HashMap<>();
            loadFilters(lotSizeFilters, priceTickSizes);

            List<AssetInfo> assets = getAccountAssets();

            System.out.println(String.format("%-8s %-20s %-15s %-10s %-15s %-12s",
                "MONEDA", "SALDO", "PRECISION LOT", "DECIMALES", "TICK SIZE", "DECIMALES"));

            for (AssetInfo asset : assets) {
                if (asset.balance > 0) {
                    String symbol = asset.asset + "USDT";
                    if (asset.asset.equals("USDT") || asset.asset.equals("BUSD") || asset.asset.equals("USDC")) {
                        symbol = asset.asset + "USDT";
                    } else {
                        symbol = asset.asset + "USDT";
                    }

                    Map<String, Double> lotInfo = lotSizeFilters.get(symbol);
                    double precision = 8;
                    int lotDecimals = 8;
                    double tickSize = 0.00000001;
                    int tickDecimals = 8;

                    if (lotInfo != null) {
                        double stepSize = lotInfo.get("stepSize");
                        precision = stepSize;
                        lotDecimals = countDecimals(stepSize);

                        Double ts = priceTickSizes.get(symbol);
                        if (ts != null) {
                            tickSize = ts;
                            tickDecimals = countDecimals(ts);
                        }
                    }

                    System.out.println(String.format("%-8s %-20.8f %-15.8f %-10d %-15.8f %-12d",
                        asset.asset, asset.balance, precision, lotDecimals, tickSize, tickDecimals));
                }
            }

        } catch (Exception e) {
            System.err.println("Error: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * Carga los filtros de exchangeInfo de Binance:
     * <ul>
     *   <li>LOT_SIZE: stepSize, minQty, maxQty para cada simbolo</li>
     *   <li>PRICE_FILTER: tickSize para cada simbolo</li>
     * </ul>
     *
     * @param lotSizeFilters Mapa donde se guardan los filtros LOT_SIZE por simbolo
     * @param priceTickSizes Mapa donde se guardan los tick sizes por simbolo
     * @throws Exception Si falla la llamada a la API o el parseo JSON
     */
    private void loadFilters(Map<String, Map<String, Double>> lotSizeFilters, Map<String, Double> priceTickSizes) throws Exception {
        // Llamar al endpoint publico exchangeInfo para obtener todos los filtros
        String response = makeRequest("/api/v3/exchangeInfo", "");

        // Parsear JSON y extraer filtros LOT_SIZE y PRICE_FILTER por simbolo
        if (response != null && !response.isEmpty()) {
            com.fasterxml.jackson.databind.JsonNode json = new com.fasterxml.jackson.databind.ObjectMapper().readTree(response);
            com.fasterxml.jackson.databind.JsonNode symbols = json.get("symbols");

            if (symbols != null && symbols.isArray()) {
                for (com.fasterxml.jackson.databind.JsonNode symNode : symbols) {
                    String symbol = symNode.get("symbol").asText();
                    com.fasterxml.jackson.databind.JsonNode filters = symNode.get("filters");

                    if (filters != null) {
                        for (com.fasterxml.jackson.databind.JsonNode filter : filters) {
                            String filterType = filter.get("filterType").asText();

                            if ("LOT_SIZE".equals(filterType)) {
                                Map<String, Double> f = new HashMap<>();
                                f.put("minQty", Double.parseDouble(filter.get("minQty").asText()));
                                f.put("maxQty", Double.parseDouble(filter.get("maxQty").asText()));
                                f.put("stepSize", Double.parseDouble(filter.get("stepSize").asText()));
                                lotSizeFilters.put(symbol, f);
                            } else if ("PRICE_FILTER".equals(filterType)) {
                                double tickSize = Double.parseDouble(filter.get("tickSize").asText());
                                priceTickSizes.put(symbol, tickSize);
                            }
                        }
                    }
                }
            }
        }
        System.out.println("Loaded " + lotSizeFilters.size() + " LOT_SIZE filters, " + priceTickSizes.size() + " TICK_SIZE filters\n");
    }

    /**
     * Obtiene los balances de la cuenta en testnet usando el endpoint
     * firmado /api/v3/account. Filtra solo activos con saldo total > 0.
     *
     * @return Lista de AssetInfo con activos que tienen saldo positivo
     * @throws Exception Si falla la autenticacion o la llamada HTTP
     */
    private List<AssetInfo> getAccountAssets() throws Exception {
        List<AssetInfo> assets = new ArrayList<>();

        // Construir queryString con timestamp y recvWindow para la solicitud firmada
        long timestamp = System.currentTimeMillis();
        String queryString = "timestamp=" + timestamp + "&recvWindow=" + RECV_WINDOW;
        String signature = generateSignature(queryString);
        queryString += "&signature=" + signature;

        String url = TESTNET_REST_URL + "/api/v3/account?" + queryString;

        // Construir y ejecutar solicitud HTTP GET firmada con API key en header
        okhttp3.Request request = new okhttp3.Request.Builder()
                .url(url)
                .addHeader("X-MBX-APIKEY", TESTNET_API_KEY)
                .build();

        try (okhttp3.Response response = new okhttp3.OkHttpClient().newCall(request).execute()) {
            // Si la respuesta es exitosa, parsear balances y filtrar saldos > 0
            if (response.isSuccessful() && response.body() != null) {
                String body = response.body().string();
                com.fasterxml.jackson.databind.JsonNode json = new com.fasterxml.jackson.databind.ObjectMapper().readTree(body);
                com.fasterxml.jackson.databind.JsonNode balances = json.get("balances");

                if (balances != null && balances.isArray()) {
                    for (com.fasterxml.jackson.databind.JsonNode balance : balances) {
                        String asset = balance.get("asset").asText();
                        double free = Double.parseDouble(balance.get("free").asText());
                        double locked = Double.parseDouble(balance.get("locked").asText());
                        double total = free + locked;

                        // Solo incluir activos con saldo total > 0
                        if (total > 0) {
                            assets.add(new AssetInfo(asset, total));
                        }
                    }
                }
            } else {
                System.err.println("API Error: " + response.code());
                if (response.body() != null) {
                    System.err.println(response.body().string());
                }
            }
        }

        return assets;
    }

    /**
     * Genera una firma HMAC-SHA256 para autenticar solicitudes a la API de Binance.
     *
     * @param queryString La cadena de consulta a firmar (timestamp, parametros)
     * @return Firma hexadecimal HMAC-SHA256
     */
    private String generateSignature(String queryString) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            SecretKeySpec secretKeySpec = new SecretKeySpec(
                    TESTNET_SECRET.getBytes(StandardCharsets.UTF_8),
                    "HmacSHA256"
            );
            mac.init(secretKeySpec);
            byte[] hash = mac.doFinal(queryString.getBytes(StandardCharsets.UTF_8));
            return bytesToHex(hash);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Convierte un arreglo de bytes a su representacion hexadecimal.
     *
     * @param bytes Arreglo de bytes (hash HMAC-SHA256)
     * @return String hexadecimal en minusculas
     */
    private static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    /**
     * Cuenta el numero de decimales de un valor double.
     * Utilizado para determinar la precision de stepSize y tickSize.
     *
     * @param value Valor a analizar
     * @return Cantidad de digitos decimales
     */
    private int countDecimals(double value) {
        if (value >= 1) return 0;
        String s = String.valueOf(value);
        int idx = s.indexOf('.');
        if (idx == -1) return 0;
        return s.length() - idx - 1;
    }

    /**
     * Clase interna simple que representa un activo con su nombre y saldo total.
     */
    private static class AssetInfo {
        String asset;
        double balance;

        AssetInfo(String asset, double balance) {
            this.asset = asset;
            this.balance = balance;
        }
    }

    /**
     * Ejecuta una solicitud HTTP GET contra la API REST de testnet de Binance.
     * Incluye la API key en el header X-MBX-APIKEY. No firma la solicitud
     * (para endpoints publicos como exchangeInfo).
     *
     * @param endpoint    Ruta del endpoint (ej. /api/v3/exchangeInfo)
     * @param queryString Query string (vacio para endpoints sin parametros)
     * @return Cuerpo de la respuesta como String
     * @throws Exception Si la respuesta HTTP no es exitosa
     */
    private String makeRequest(String endpoint, String queryString) throws Exception {
        String url = TESTNET_REST_URL + endpoint;
        if (!queryString.isEmpty()) {
            url += "?" + queryString;
        }

        okhttp3.Request request = new okhttp3.Request.Builder()
                .url(url)
                .addHeader("X-MBX-APIKEY", TESTNET_API_KEY)
                .build();

        try (okhttp3.Response response = new okhttp3.OkHttpClient().newCall(request).execute()) {
            if (response.isSuccessful() && response.body() != null) {
                return response.body().string();
            } else {
                throw new Exception("Request failed: " + response.code());
            }
        }
    }
}