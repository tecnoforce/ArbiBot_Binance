package com.arbitrage.trading;

import com.arbitrage.config.ApiConfig;
import com.arbitrage.util.Log;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Cliente REST API de Binance.
 * Maneja todas las llamadas a la API REST:
 *   - Consultas de cuenta (balances)
 *   - Precios de simbolos
 *   - Colocar ordenes
 *   - Sincronizacion de tiempo
 */
public class BinanceApiClient {
    private static final String TAG = "API";
    
    // recvWindow para requests signed (60 segundos)
    private static final long RECV_WINDOW = 60000L;

    // =====================================================================
    // CONFIGURACION
    // =====================================================================
    private final ApiConfig apiConfig;
    private final okhttp3.OkHttpClient httpClient;
    private final com.fasterxml.jackson.databind.ObjectMapper objectMapper;
    
    // =====================================================================
    // SINCRONIZACION DE TIEMPO
    // =====================================================================
    // Offset entre tiempo local y servidor Binance
    private long serverTimeOffset = 0;
    
    // Scheduler para sincronizacion periodica
    private final ScheduledExecutorService scheduler;

    /**
     * Constructor.
     * @param apiConfig Credenciales API
     */
    public BinanceApiClient(ApiConfig apiConfig) {
        this.apiConfig = apiConfig;
        
        // Cliente HTTP con timeouts
        this.httpClient = new okhttp3.OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(10, TimeUnit.SECONDS)
                .build();
        
        this.objectMapper = new com.fasterxml.jackson.databind.ObjectMapper();
        
        // Scheduler para sincronizacion
        this.scheduler = Executors.newSingleThreadScheduledExecutor();
        
        // Sincroniza tiempo inmediatamente
        syncServerTime();
        
        // Inicia sincronizacion periodica
        startPeriodicSync();
    }

    /**
     * Inicia sincronizacion cada 5 minutos.
     */
    private void startPeriodicSync() {
        scheduler.scheduleAtFixedRate(() -> {
            syncServerTime();
        }, 5, 5, TimeUnit.MINUTES);
    }

    /**
     * Sincroniza tiempo local con servidor Binance.
     * Calcula offset para corregir timestamps.
     */
    private void syncServerTime() {
        try {
            // Consulta tiempo del servidor
            String response = makeRequest("/api/v3/time", "");
            if (response != null) {
                var json = objectMapper.readTree(response);
                long serverTime = json.get("serverTime").asLong();
                long localTime = System.currentTimeMillis();
                
                // Calcula offset
                serverTimeOffset = serverTime - localTime;
                Log.info("Sincronizado con servidor. (Offset: " + serverTimeOffset + "ms)");
            }
        } catch (Exception e) {
            Log.warn(TAG, "No se pudo sincronizar tiempo: " + e.getMessage());
        }
    }

    /**
     * Obtiene timestamp corregido para requests.
     * @return Timestamp con offset aplicado
     */
    private long getCorrectedTimestamp() {
        long localTime = System.currentTimeMillis();
        long corrected = localTime + serverTimeOffset;
        
        // Si offset es muy grande, usa tiempo local
        if (Math.abs(serverTimeOffset) > 60000) {
            Log.warn(TAG, "Offset muy grande (" + serverTimeOffset + "ms), usando timestamp local sin corregir");
            corrected = localTime;
        }
        
        Log.debug(TAG, "Timestamp: local=" + localTime + ", offset=" + serverTimeOffset + ", corrected=" + corrected);
        return corrected;
    }

    /**
     * Obtiene balances de la cuenta.
     * @return Array [USDT, BNB, precio BNB]
     */
    public double[] getAccountBalances() {
        // Valores por defecto si falla
        double usdtBalance = 10000.0;
        double bnbBalance = 1.0;
        double bnbPrice = 0.0;

        try {
            // Prepara parametros
            Map<String, String> params = new HashMap<>();
            params.put("timestamp", String.valueOf(getCorrectedTimestamp()));
            params.put("recvWindow", String.valueOf(RECV_WINDOW));
            
            // Request firmado
            String response = makeSignedRequest("/api/v3/account", params);
            
            if (response != null && !response.isEmpty()) {
                var accountJson = objectMapper.readTree(response);
                var balances = accountJson.get("balances");
                
                if (balances != null && balances.isArray()) {
                    for (var balance : balances) {
                        String asset = balance.get("asset").asText();
                        String free = balance.get("free").asText();
                        double amount = Double.parseDouble(free);
                        
                        if ("USDT".equals(asset)) {
                            usdtBalance = amount;
                        } else if ("BNB".equals(asset)) {
                            bnbBalance = amount;
                        }
                    }
                }
            }
            
            // Obtiene precio BNB
            bnbPrice = getSymbolPrice("BNBUSDT");
            Log.debug(TAG, "Saldos - USDT: " + usdtBalance + ", BNB: " + bnbBalance + ", BNB Price: " + bnbPrice);
            
        } catch (Exception e) {
            Log.warn(TAG, "Error al obtener saldos: " + e.getMessage());
        }

        return new double[]{usdtBalance, bnbBalance, bnbPrice};
    }

    /**
     * Obtiene precio de un simbolo.
     * @param symbol Simbolo (ej: "BNBUSDT")
     * @return Precio actual
     */
    public double getSymbolPrice(String symbol) {
        try {
            String endpoint = "/api/v3/ticker/price";
            String url = apiConfig.getCurrentBaseUrl() + endpoint + "?symbol=" + symbol;
            
            okhttp3.Request request = new okhttp3.Request.Builder()
                    .url(url)
                    .addHeader("X-MBX-APIKEY", apiConfig.getCurrentApiKey())
                    .build();

            try (okhttp3.Response response = httpClient.newCall(request).execute()) {
                if (response.isSuccessful() && response.body() != null) {
                    String body = response.body().string();
                    var priceJson = objectMapper.readTree(body);
                    double price = priceJson.get("price").asDouble();
                    Log.debug(TAG, "Precio " + symbol + ": " + price);
                    return price;
                }
            }
        } catch (Exception e) {
            Log.debug(TAG, "Error precio " + symbol + ": " + e.getMessage());
        }
        return 0.0;
    }

    /**
     * Coloca una orden.
     * @param symbol Simbolo
     * @param side BUY o SELL
     * @param orderType MARKET o LIMIT
     * @param quantity Cantidad
     * @param price Precio (solo para LIMIT)
     * @return Resultado de la orden
     */
    public com.arbitrage.model.OrderResult placeOrder(String symbol, String side, String orderType, double quantity, double price) {
        boolean isTestnet = apiConfig.isTestnet();
        
        // Si no es testnet y hay API key, ejecuta real
        if (!apiConfig.isTestnet() && !apiConfig.getCurrentApiKey().isEmpty()) {
            return placeRealOrder(symbol, side, orderType, quantity, price);
        } else {
            // Simula orden
            return com.arbitrage.model.OrderResult.builder()
                    .symbol(symbol)
                    .side(side)
                    .orderType(orderType)
                    .quantity(quantity)
                    .price(price)
                    .status(isTestnet ? "TESTNET_FILLED" : "SIMULATED")
                    .success(true)
                    .build();
        }
    }

    /**
     * Coloca orden real (solo si API key configurada).
     */
    private com.arbitrage.model.OrderResult placeRealOrder(String symbol, String side, String orderType, double quantity, double price) {
        try {
            long timestamp = getCorrectedTimestamp();
            
            Map<String, String> params = new HashMap<>();
            params.put("symbol", symbol);
            params.put("side", side);
            params.put("type", orderType);
            params.put("quantity", String.valueOf(quantity));
            params.put("timestamp", String.valueOf(timestamp));
            params.put("recvWindow", String.valueOf(RECV_WINDOW));
            
            if ("LIMIT".equals(orderType)) {
                params.put("price", String.valueOf(price));
                params.put("timeInForce", "GTC");
            }

            String endpoint = "/api/v3/order";
            makeSignedRequest(endpoint, params);
            
            Log.info(TAG, "Orden ejecutada: " + side + " " + quantity + " " + symbol + " @ " + price);
            
            return com.arbitrage.model.OrderResult.builder()
                    .symbol(symbol)
                    .side(side)
                    .orderType(orderType)
                    .quantity(quantity)
                    .price(price)
                    .status("FILLED")
                    .success(true)
                    .build();
        } catch (Exception e) {
            Log.error(TAG, "Error al ejecutar orden: " + e.getMessage());
            return com.arbitrage.model.OrderResult.builder()
                    .symbol(symbol)
                    .side(side)
                    .orderType(orderType)
                    .quantity(quantity)
                    .price(price)
                    .status("ERROR")
                    .success(false)
                    .errorMessage(e.getMessage())
                    .build();
        }
    }

    /**
     * Convierte bytes a string hexadecimal.
     */
    private static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    /**
     * Genera firma HMAC-SHA256.
     * @param queryString Query a firmar
     * @return Firma en hex
     */
    public String generateSignature(String queryString) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            SecretKeySpec secretKeySpec = new SecretKeySpec(
                    apiConfig.getCurrentSecretKey().getBytes(StandardCharsets.UTF_8),
                    "HmacSHA256"
            );
            mac.init(secretKeySpec);
            byte[] hash = mac.doFinal(queryString.getBytes(StandardCharsets.UTF_8));
            return bytesToHex(hash);
        } catch (Exception e) {
            Log.error(TAG, "Error al generar firma: " + e.getMessage());
            return "";
        }
    }

    /**
     * hace request GET simple.
     * @param endpoint Endpoint API
     * @param queryString Query params
     * @return Response JSON
     */
    public String makeRequest(String endpoint, String queryString) throws Exception {
        String baseUrl = apiConfig.getCurrentBaseUrl();
        String url = baseUrl + endpoint;
        if (!queryString.isEmpty()) {
            url += "?" + queryString;
        }

        okhttp3.Request request = new okhttp3.Request.Builder()
                .url(url)
                .addHeader("X-MBX-APIKEY", apiConfig.getCurrentApiKey())
                .build();

        try (okhttp3.Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                String errorBody = response.body() != null ? response.body().string() : "Sin cuerpo";
                Log.error(TAG, "Error API: " + response.code() + " - " + errorBody);
                throw new Exception("Solicitud API fallida: " + response.code() + " - " + errorBody);
            }
            return response.body().string();
        }
    }

    /**
     * hace request firmado (HMAC-SHA256).
     * @param endpoint Endpoint API
     * @param params Parametros
     * @return Response JSON
     */
    public String makeSignedRequest(String endpoint, Map<String, String> params) throws Exception {
        // Ordena parametros alfabeticamente
        TreeMap<String, String> sortedParams = new TreeMap<>(params);
        StringBuilder queryBuilder = new StringBuilder();

        for (Map.Entry<String, String> entry : sortedParams.entrySet()) {
            if (queryBuilder.length() > 0) {
                queryBuilder.append("&");
            }
            queryBuilder.append(entry.getKey()).append("=").append(entry.getValue());
        }

        String queryString = queryBuilder.toString();
        String signature = generateSignature(queryString);
        queryString += "&signature=" + signature;

        return makeRequest(endpoint, queryString);
    }

    /**
     * Obtiene lista de simbolos USDT desde Binance.
     * Solo simbolos con status=TRADING.
     * @return Lista de simbolos USDT
     */
    public List<String> getExchangeSymbols() {
        List<String> symbols = new ArrayList<>();
        
        try {
            // Obtener exchange info
            String response = makeRequest("/api/v3/exchangeInfo", "");
            
            if (response != null && !response.isEmpty()) {
                var json = objectMapper.readTree(response);
                var symbolsJson = json.get("symbols");
                
                if (symbolsJson != null && symbolsJson.isArray()) {
                    for (var symbolNode : symbolsJson) {
                        String symbol = symbolNode.get("symbol").asText();
                        String status = symbolNode.get("status").asText();
                        
                        // Solo simbolos USDT con status TRADING
                        if (symbol.endsWith("USDT") && "TRADING".equals(status)) {
                            symbols.add(symbol);
                        }
                    }
                }
            }
            
Log.info("Cargados " + symbols.size() + " simbolos USDT de Binance");
            
        } catch (Exception e) {
            Log.error("Error al obtener simbolos: " + e.getMessage());
        }
        
        return symbols;
    }
    
    /**
     * Obtiene TODOS los simbolos de Binance.
     * @return Set de todos los simbolos con status TRADING
     */
    public Set<String> getAllSymbols() {
        Set<String> symbols = new HashSet<>();
        
        try {
            String response = makeRequest("/api/v3/exchangeInfo", "");
            
            if (response != null && !response.isEmpty()) {
                var json = objectMapper.readTree(response);
                var symbolsJson = json.get("symbols");
                
                if (symbolsJson != null && symbolsJson.isArray()) {
                    for (var symbolNode : symbolsJson) {
                        String symbol = symbolNode.get("symbol").asText();
                        String status = symbolNode.get("status").asText();
                        
                        if ("TRADING".equals(status)) {
                            symbols.add(symbol);
                        }
                    }
                }
            }
            
            Log.info("Cargados " + symbols.size() + " simbolos de Binance");
            
        } catch (Exception e) {
            Log.error("Error al obter symboles: " + e.getMessage());
        }
        
        return symbols;
    }
    
    /**
     * Cierra recursos.
     */
    public void shutdown() {
        if (scheduler != null && !scheduler.isShutdown()) {
            scheduler.shutdown();
        }
    }
}