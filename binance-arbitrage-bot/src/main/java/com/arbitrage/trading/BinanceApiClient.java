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
import java.util.concurrent.ConcurrentHashMap;
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

    // Cache de filtros LOT_SIZE
    private final Map<String, Map<String, Double>> lotSizeFilters = new ConcurrentHashMap<>();
    private final Map<String, Double> minNotionalFilters = new ConcurrentHashMap<>();
    private final Map<String, Double> priceTickSizes = new ConcurrentHashMap<>();
    private volatile long lastFiltersLoad = 0;
    private static final long FILTERS_TTL_MS = 300000;
    
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
        double usdtBalance = 11100.0;
        double bnbBalance = 1.1;
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
        return placeOrder(symbol, side, orderType, quantity, price, 0, false);
    }

    /**
     * Coloca orden con quoteOrderQty (para MARKET orders).
     */
    public com.arbitrage.model.OrderResult placeOrder(String symbol, String side, String orderType,
                                                      double quantity, double price, double quoteOrderQty, boolean realOrder) {
        if (realOrder && !apiConfig.getCurrentApiKey().isEmpty()) {
            return placeRealOrder(symbol, side, orderType, quantity, price, quoteOrderQty);
        } else {
            long elapsed = 48;
            String status = apiConfig.isTestnet() ? "TESTNET_FILLED" : "SIMULATED";
            String orderId = String.valueOf(1000000L + (long)(Math.random() * 9000000L));

            return com.arbitrage.model.OrderResult.builder()
                    .symbol(symbol)
                    .side(side)
                    .orderType(orderType)
                    .quantity(quantity)
                    .price(price)
                    .orderId(orderId)
                    .status(status)
                    .success(true)
                    .executedQty(quantity)
                    .elapsedTime(elapsed)
                    .build();
        }
    }

    /**
     * Coloca orden real (solo si API key configurada).
     */
    private com.arbitrage.model.OrderResult placeRealOrder(String symbol, String side, String orderType,
                                                          double quantity, double price, double quoteOrderQty) {
        try {
            long timestamp = getCorrectedTimestamp();

            Map<String, String> params = new HashMap<>();
            params.put("symbol", symbol);
            params.put("side", side);
            params.put("type", orderType);
            params.put("timestamp", String.valueOf(timestamp));
            params.put("recvWindow", String.valueOf(RECV_WINDOW));

            if ("MARKET".equalsIgnoreCase(orderType)) {
                params.put("quoteOrderQty", String.format("%.8f", quoteOrderQty));
            } else if ("LIMIT".equalsIgnoreCase(orderType)) {
                params.put("quantity", String.format("%.8f", quantity));
                params.put("price", String.format("%.8f", price));
                params.put("timeInForce", "GTC");
            } else {
                params.put("quantity", String.format("%.8f", quantity));
            }

            Log.debug(TAG, "=== ORDER REQUEST ===");
            Log.debug(TAG, "Symbol: " + symbol);
            Log.debug(TAG, "Side: " + side);
            Log.debug(TAG, "Type: " + orderType);
            Log.debug(TAG, "quoteOrderQty: " + String.format("%.8f", quoteOrderQty));
            Log.debug(TAG, "quantity: " + String.format("%.8f", quantity));
            Log.debug(TAG, "price: " + String.format("%.8f", price));
            Log.debug(TAG, "Params: " + params);
            Log.debug(TAG, "========================");

String endpoint = "/api/v3/order";
            String response = makeSignedRequest(endpoint, params, true);

            String realOrderId = null;
            if (response != null && !response.isEmpty()) {
                try {
                    var json = objectMapper.readTree(response);
                    realOrderId = String.valueOf(json.get("orderId").asLong());
                    Log.debug(TAG, "Order placed. orderId: " + realOrderId);
                } catch (Exception e) {
                    Log.warn(TAG, "Could not parse order response: " + e.getMessage());
                }
            }

            Log.debug(TAG, "Orden ejecutada: " + side + " " + quantity + " " + symbol + " @ " + price);

            String orderStatus = "NEW";
            double executedQty = 0;
            double filledPrice = price;
            long transactTime = 0;
            long updateTime = 0;
            String commissionAsset = null;
            double commissionAmount = 0;
            
            if (response != null && !response.isEmpty()) {
                try {
                    var json = objectMapper.readTree(response);
                    orderStatus = json.get("status").asText();
                    executedQty = json.has("executedQty") ? json.get("executedQty").asDouble() : 0;
                    
                    if (json.has("transactTime")) {
                        transactTime = json.get("transactTime").asLong();
                    }
                    if (json.has("updateTime")) {
                        updateTime = json.get("updateTime").asLong();
                    }
                    if (json.has("price") && !json.get("price").isNull()) {
                        filledPrice = json.get("price").asDouble();
                    }
                    if ("MARKET".equalsIgnoreCase(orderType) && executedQty > 0) {
                        if (json.has("cummulativeQuoteQty")) {
                            double quoteQty = json.get("cummulativeQuoteQty").asDouble();
                            filledPrice = quoteQty / executedQty;
                        }
                    }
                    if (json.has("commissionAsset") && !json.get("commissionAsset").isNull()) {
                        commissionAsset = json.get("commissionAsset").asText();
                        commissionAmount = json.has("commission") ? json.get("commission").asDouble() : 0;
                    }
                } catch (Exception e) {
                    Log.warn(TAG, "Could not parse order status: " + e.getMessage());
                }
            }

            return com.arbitrage.model.OrderResult.builder()
                    .symbol(symbol)
                    .side(side)
                    .orderType(orderType)
                    .quantity(quantity)
                    .price(filledPrice)
                    .orderId(realOrderId)
                    .status(orderStatus)
                    .success(true)
                    .executedQty(executedQty)
                    .transactTime(transactTime)
                    .updateTime(updateTime)
                    .commissionAsset(commissionAsset)
                    .commissionAmount(commissionAmount)
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
        return makeRequestWithMethod(endpoint, queryString, false);
    }

    public String makeRequestWithMethod(String endpoint, String queryString, boolean isPost) throws Exception {
        String baseUrl = apiConfig.getCurrentBaseUrl();
        String url = baseUrl + endpoint;
        if (!queryString.isEmpty()) {
            url += "?" + queryString;
        }

        okhttp3.Request.Builder builder = new okhttp3.Request.Builder()
                .url(url)
                .addHeader("X-MBX-APIKEY", apiConfig.getCurrentApiKey());

        if (isPost) {
            builder.post(okhttp3.RequestBody.create("", null));
        }

        okhttp3.Request request = builder.build();

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
        return makeSignedRequest(endpoint, params, false);
    }

    /**
     * hace request firmado con metodo especificado.
     * @param endpoint Endpoint API
     * @param params Parametros
     * @param forcePost true para forzar POST (para placeOrder)
     * @return Response JSON
     */
    public String makeSignedRequest(String endpoint, Map<String, String> params, boolean forcePost) throws Exception {
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

        boolean isPost = forcePost;
        return makeRequestWithMethod(endpoint, queryString, isPost);
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

    public void loadExchangeInfoFilters() {
        if (System.currentTimeMillis() - lastFiltersLoad < FILTERS_TTL_MS && !lotSizeFilters.isEmpty()) {
            return;
        }

        try {
            String response = makeRequest("/api/v3/exchangeInfo", "");
            var json = objectMapper.readTree(response);
            var symbols = json.get("symbols");

            for (var symNode : symbols) {
                String symbol = symNode.get("symbol").asText();
                var filters = symNode.get("filters");

                if (filters != null) {
                    for (var filter : filters) {
                        String filterType = filter.get("filterType").asText();
                        
                        if ("LOT_SIZE".equals(filterType)) {
                            Map<String, Double> f = new HashMap<>();
                            f.put("minQty", Double.parseDouble(filter.get("minQty").asText()));
                            f.put("maxQty", Double.parseDouble(filter.get("maxQty").asText()));
                            f.put("stepSize", Double.parseDouble(filter.get("stepSize").asText()));
                            lotSizeFilters.put(symbol, f);
                        } else if ("MIN_NOTIONAL".equals(filterType)) {
                            double minNotional = Double.parseDouble(filter.get("minNotional").asText());
                            minNotionalFilters.put(symbol, minNotional);
                        } else if ("NOTIONAL".equals(filterType)) {
                            double minNotional = Double.parseDouble(filter.get("minNotional").asText());
                            minNotionalFilters.put(symbol, minNotional);
                        } else if ("PRICE_FILTER".equals(filterType)) {
                            double tickSize = Double.parseDouble(filter.get("tickSize").asText());
                            priceTickSizes.put(symbol, tickSize);
                        }
                    }
                }
            }
            lastFiltersLoad = System.currentTimeMillis();
            Log.debug(TAG, "Loaded LOT_SIZE for " + lotSizeFilters.size() + " symbols, MIN_NOTIONAL for " + minNotionalFilters.size() + " symbols, PRICE_FILTER for " + priceTickSizes.size() + " symbols");
        } catch (Exception e) {
            Log.error(TAG, "Error loading LOT_SIZE filters: " + e.getMessage());
        }
    }

    public double adjustQuantityToLotSize(String symbol, double qty) {
        loadExchangeInfoFilters();

        if (symbol == null) {
            return qty;
        }

        Map<String, Double> f = lotSizeFilters.get(symbol);
        if (f == null) {
            Log.warn(TAG, "No LOT_SIZE for " + symbol);
            return qty;
        }

        double minQty = f.get("minQty");
        double stepSize = f.get("stepSize");

        if (qty < minQty) {
            Log.debug(TAG, symbol + ": qty " + qty + " < min " + minQty + ", using min");
            return minQty;
        }

        return Math.floor(qty / stepSize) * stepSize;
    }

    public double adjustQuantityRaw(double qty) {
        if (qty < 0.00001) {
            return 0.00001;
        }
        return Math.floor(qty / 0.00001) * 0.00001;
    }

    public double adjustPriceToTickSize(String symbol, double price) {
        loadExchangeInfoFilters();
        if (symbol == null) {
            return price;
        }
        Double tickSize = priceTickSizes.get(symbol);
        if (tickSize == null || tickSize <= 0) {
            return price;
        }
        return Math.round(price / tickSize) * tickSize;
    }

    public double getTickSize(String symbol) {
        loadExchangeInfoFilters();
        Double tickSize = priceTickSizes.get(symbol);
        return tickSize != null ? tickSize : 0.00000001;
    }

    public double getMinNotional(String symbol) {
        loadExchangeInfoFilters();
        Double minNotional = minNotionalFilters.get(symbol);
        return minNotional != null ? minNotional : 10.0;
    }

    public double getMinNotionalOrZero(String symbol) {
        loadExchangeInfoFilters();
        Double minNotional = minNotionalFilters.get(symbol);
        if (minNotional != null) {
            return minNotional;
        }
        return 10.0;
    }

    /**
     * Obtiene el balance disponible de un asset específico.
     * @param asset Nombre del asset (ej: "BNB", "USDT", "SUI")
     * @return Balance free del asset, 0 si falla
     */
    public double getAssetBalance(String asset) {
        try {
            Map<String, String> params = new HashMap<>();
            params.put("timestamp", String.valueOf(getCorrectedTimestamp()));
            params.put("recvWindow", String.valueOf(RECV_WINDOW));

            String response = makeSignedRequest("/api/v3/account", params);

            if (response != null && !response.isEmpty()) {
                var accountJson = objectMapper.readTree(response);
                var balances = accountJson.get("balances");

                if (balances != null && balances.isArray()) {
                    for (var balance : balances) {
                        if (asset.equals(balance.get("asset").asText())) {
                            double free = Double.parseDouble(balance.get("free").asText());
                            Log.debug(TAG, "Balance " + asset + ": " + free);
                            return free;
                        }
                    }
                }
            }
            Log.warn(TAG, "Asset " + asset + " not found in account");
        } catch (Exception e) {
            Log.warn(TAG, "Error getting balance for " + asset + ": " + e.getMessage());
        }
        return 0.0;
    }

    /**
     * Extrae el base asset de un símbolo Binance.
     * Ej: "BNBUSDT" → "BNB", "SUIBNB" → "SUI", "ETHBTC" → "ETH"
     * @param symbol Símbolo Binance
     * @return Base asset
     */
    public String extractBaseAsset(String symbol) {
        if (symbol == null || symbol.isEmpty()) {
            return "";
        }
        if (symbol.endsWith("USDT")) {
            return symbol.substring(0, symbol.length() - 4);
        }
        if (symbol.endsWith("BUSD")) {
            return symbol.substring(0, symbol.length() - 4);
        }
        if (symbol.endsWith("USDC")) {
            return symbol.substring(0, symbol.length() - 4);
        }
        if (symbol.endsWith("BNB")) {
            return symbol.substring(0, symbol.length() - 3);
        }
        if (symbol.endsWith("ETH")) {
            return symbol.substring(0, symbol.length() - 3);
        }
        if (symbol.endsWith("BTC")) {
            return symbol.substring(0, symbol.length() - 3);
        }
        return symbol;
    }

    /**
     * Cierra recursos.
     */
    public void shutdown() {
        if (scheduler != null && !scheduler.isShutdown()) {
            scheduler.shutdown();
        }
    }

    /**
     * Obtiene simbolos USDT ordenados por volumen.
     * Intenta obtener de /api/v3/ticker/24hr, si falla usa fallback con getAllSymbols.
     * @param limit Numero maximo de simbolos
     * @return Lista de simbolos USDT
     */
    public List<String> getUsdtSymbolsByVolume(int limit) {
        List<String> symbols = new ArrayList<>();
        List<SymbolVolume> volumes = new ArrayList<>();

        // === INTENTO 1: Sin parámetros ===
        try {
            String response = makeRequest("/api/v3/ticker/24hr", "");

            if (response != null && !response.isEmpty()) {
                var json = objectMapper.readTree(response);

                if (json.isArray()) {
                    for (var ticker : json) {
                        String symbol = ticker.get("symbol").asText();
                        if (symbol.endsWith("USDT") && !symbol.equals("USDTUSDT")) {
                            double quoteVolume = ticker.get("quoteVolume").asDouble();
                            volumes.add(new SymbolVolume(symbol, quoteVolume));
                        }
                    }
                }
            }

            Log.info("Intento 1 (sin parametros): " + volumes.size() + " simbolos");

        } catch (Exception e) {
            Log.error("Intento 1 fallido (ticker/24hr): " + e.getMessage());
        }

        // === FALLBACK: getAllSymbols filtrado por USDT ===
        if (volumes.isEmpty()) {
            Log.info("Usando fallback: getAllSymbols filtrado por USDT");
            Set<String> allSymbols = getAllSymbols();

            for (String sym : allSymbols) {
                if (sym.endsWith("USDT") && !sym.equals("USDTUSDT")) {
                    volumes.add(new SymbolVolume(sym, 0.0)); // Sin volumen
                }
                if (volumes.size() >= limit) break;
            }

            Log.info("Fallback: " + volumes.size() + " simbolos USDT");
        }

        // Ordenar por volumen descendente (los de volumen 0 irán al final)
        volumes.sort((a, b) -> Double.compare(b.volume, a.volume));

        // Limitar resultados
        int count = 0;
        for (var sv : volumes) {
            if (count >= limit) break;
            symbols.add(sv.symbol);
            count++;
        }

        Log.info("Cargados " + symbols.size() + " simbolos USDT por volumen");
        return symbols;
    }

    private static class SymbolVolume {
        String symbol;
        double volume;
        SymbolVolume(String symbol, double volume) {
            this.symbol = symbol;
            this.volume = volume;
        }
    }

    /**
     * Consulta el estado de una orden existente.
     * @param symbol Simbolo de la orden
     * @param orderId ID de la orden en Binance
     * @return OrderResult con estado actual
     */
    public com.arbitrage.model.OrderResult queryOrder(String symbol, String orderId) {
        try {
            long timestamp = getCorrectedTimestamp();
            
            Map<String, String> params = new HashMap<>();
            params.put("symbol", symbol);
            params.put("orderId", orderId);
            params.put("timestamp", String.valueOf(timestamp));
            params.put("recvWindow", String.valueOf(RECV_WINDOW));
            
            String endpoint = "/api/v3/order";
            String response = makeSignedRequest(endpoint, params, false);
            
            if (response != null && !response.isEmpty()) {
                var json = objectMapper.readTree(response);

                double filledPrice = json.get("price").asDouble();
                if ("MARKET".equalsIgnoreCase(json.get("type").asText())) {
                    double executedQty = json.get("executedQty").asDouble();
                    if (executedQty > 0 && json.has("cummulativeQuoteQty")) {
                        double quoteQty = json.get("cummulativeQuoteQty").asDouble();
                        filledPrice = quoteQty / executedQty;
                    }
                }

                return com.arbitrage.model.OrderResult.builder()
                        .symbol(json.get("symbol").asText())
                        .side(json.get("side").asText())
                        .orderId(String.valueOf(json.get("orderId").asLong()))
                        .price(filledPrice)
                        .quantity(json.get("origQty").asDouble())
                        .executedQty(json.get("executedQty").asDouble())
                        .status(json.get("status").asText())
                        .orderType(json.get("type").asText())
                        .success(true)
                        .transactTime(json.has("transactTime") ? json.get("transactTime").asLong() : 0)
                        .updateTime(json.has("updateTime") ? json.get("updateTime").asLong() : 0)
                        .commissionAsset(json.has("commissionAsset") && !json.get("commissionAsset").isNull() ? json.get("commissionAsset").asText() : null)
                        .commissionAmount(json.has("commission") ? json.get("commission").asDouble() : 0)
                        .build();
            }
        } catch (Exception e) {
            Log.error(TAG, "Error al consultar orden: " + e.getMessage());
        }
        
        return com.arbitrage.model.OrderResult.builder()
                .symbol(symbol)
                .orderId(orderId)
                .status("ERROR")
                .success(false)
                .errorMessage("Order not found")
                .build();
    }

    /**
     * Cancela una orden existente.
     * @param symbol Simbolo de la orden
     * @param orderId ID de la orden en Binance
     * @return OrderResult con estado de cancelacion
     */
    public com.arbitrage.model.OrderResult cancelOrder(String symbol, String orderId) {
        try {
            long timestamp = getCorrectedTimestamp();
            
            Map<String, String> params = new HashMap<>();
            params.put("symbol", symbol);
            params.put("orderId", orderId);
            params.put("timestamp", String.valueOf(timestamp));
            params.put("recvWindow", String.valueOf(RECV_WINDOW));
            
            TreeMap<String, String> sortedParams = new TreeMap<>(params);
            StringBuilder queryBuilder = new StringBuilder();
            for (Map.Entry<String, String> entry : sortedParams.entrySet()) {
                if (queryBuilder.length() > 0) queryBuilder.append("&");
                queryBuilder.append(entry.getKey()).append("=").append(entry.getValue());
            }
            String queryString = queryBuilder.toString();
            String signature = generateSignature(queryString);
            queryString += "&signature=" + signature;
            
            String baseUrl = apiConfig.getCurrentBaseUrl();
            String url = baseUrl + "/api/v3/order?" + queryString;
            
            okhttp3.Request request = new okhttp3.Request.Builder()
                    .url(url)
                    .delete()
                    .addHeader("X-MBX-APIKEY", apiConfig.getCurrentApiKey())
                    .build();

            try (okhttp3.Response response = httpClient.newCall(request).execute()) {
                if (response.isSuccessful() && response.body() != null) {
                    String body = response.body().string();
                    var json = objectMapper.readTree(body);
                    
                    return com.arbitrage.model.OrderResult.builder()
                            .symbol(json.get("symbol").asText())
                            .side(json.get("side").asText())
                            .orderId(String.valueOf(json.get("orderId").asLong()))
                            .status("CANCELED")
                            .success(true)
                            .build();
                }
            }
        } catch (Exception e) {
            Log.error(TAG, "Error al cancelar orden: " + e.getMessage());
        }
        
        return com.arbitrage.model.OrderResult.builder()
                .symbol(symbol)
                .orderId(orderId)
                .status("ERROR")
                .success(false)
                .errorMessage("Cancel failed")
                .build();
    }

    /**
     * Coloca una orden usando bandera realOrder.
     * @param symbol Simbolo
     * @param side BUY o SELL
     * @param orderType MARKET o LIMIT
     * @param quantity Cantidad
     * @param price Precio
     * @param realOrder true para orden real, false para simulado
     * @return OrderResult
     */
    public com.arbitrage.model.OrderResult placeOrder(String symbol, String side, String orderType, 
                                          double quantity, double price, boolean realOrder) {
        return placeOrder(symbol, side, orderType, quantity, price, 0.0, realOrder);
    }

    public String extractQuoteAsset(String symbol) {
        if (symbol == null || symbol.isEmpty()) {
            return "";
        }
        if (symbol.endsWith("USDT")) {
            return "USDT";
        }
        if (symbol.endsWith("BUSD")) {
            return "BUSD";
        }
        if (symbol.endsWith("USDC")) {
            return "USDC";
        }
        if (symbol.endsWith("BNB")) {
            return "BNB";
        }
        if (symbol.endsWith("ETH")) {
            return "ETH";
        }
        if (symbol.endsWith("BTC")) {
            return "BTC";
        }
        return "";
    }
}