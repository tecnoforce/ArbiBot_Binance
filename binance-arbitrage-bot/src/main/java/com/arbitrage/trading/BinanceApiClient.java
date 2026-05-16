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
 * CLIENTE REST API DE BINANCE.
 * 
 * Capa de comunicación directa con los endpoints REST de Binance.
 * Maneja:
 *   - Requests públicos (precios, exchangeInfo)
 *   - Requests firmados con HMAC-SHA256 (balances, órdenes)
 *   - Sincronización de tiempo (serverTime offset)
 *   - Caché de filtros LOT_SIZE, MIN_NOTIONAL, PRICE_FILTER
 *   - Colocación, consulta y cancelación de órdenes
 *   - Extracción de base/quote assets de símbolos
 *
 * ARQUITECTURA DE SEGURIDAD:
 *   - Todas las requests firmadas incluyen recvWindow=60000ms
 *   - La firma HMAC-SHA256 se genera con la Secret Key
 *   - El timestamp se corrige con el offset del servidor
 *   - Si el offset supera 60s, se usa timestamp local
 *
 * @see com.arbitrage.config.ApiConfig
 */
public class BinanceApiClient {
    private static final String TAG = "API";
    
    /** Ventana de recepción para requests firmados (60 segundos) */
    private static final long RECV_WINDOW = 60000L;

    // =====================================================================
    // CONFIGURACIÓN
    // =====================================================================
    private final ApiConfig apiConfig;
    private final okhttp3.OkHttpClient httpClient;
    private final com.fasterxml.jackson.databind.ObjectMapper objectMapper;

    /** Caché de filtros LOT_SIZE por símbolo: {minQty, maxQty, stepSize} */
    private final Map<String, Map<String, Double>> lotSizeFilters = new ConcurrentHashMap<>();
    /** Caché de MIN_NOTIONAL (valor mínimo de la orden) por símbolo */
    private final Map<String, Double> minNotionalFilters = new ConcurrentHashMap<>();
    /** Caché de tickSize (PRICE_FILTER) por símbolo */
    private final Map<String, Double> priceTickSizes = new ConcurrentHashMap<>();
    /** Timestamp de última carga de filtros (para TTL) */
    private volatile long lastFiltersLoad = 0;
    /** TTL del caché de filtros: 5 minutos */
    private static final long FILTERS_TTL_MS = 300000;
    
    // =====================================================================
    // SINCRONIZACIÓN DE TIEMPO
    // =====================================================================
    /** Diferencia entre tiempo del servidor Binance y tiempo local */
    private long serverTimeOffset = 0;
    
    /** Scheduler para sincronización periódica de tiempo */
    private final ScheduledExecutorService scheduler;

    /**
     * Constructor. Inicializa cliente HTTP, sincroniza tiempo y precarga filtros.
     * @param apiConfig Credenciales API (API Key, Secret Key, endpoints)
     */
    public BinanceApiClient(ApiConfig apiConfig) {
        this.apiConfig = apiConfig;
        
        // Cliente HTTP con timeouts de 10s (conexión y lectura)
        this.httpClient = new okhttp3.OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(10, TimeUnit.SECONDS)
                .build();
        
        this.objectMapper = new com.fasterxml.jackson.databind.ObjectMapper();
        
        // Scheduler para sincronización periódica de tiempo
        this.scheduler = Executors.newSingleThreadScheduledExecutor();
        
        // Sincroniza tiempo inmediatamente al arrancar
        syncServerTime();
        
        // Inicia sincronización periódica cada 5 minutos
        startPeriodicSync();
    }

    /**
     * Inicia sincronización de tiempo cada 5 minutos.
     * Compensa el drift del reloj local contra el servidor de Binance.
     */
    private void startPeriodicSync() {
        scheduler.scheduleAtFixedRate(() -> {
            syncServerTime();
        }, 5, 5, TimeUnit.MINUTES);
    }

    /**
     * Sincroniza el tiempo local con el servidor de Binance.
     * Consulta /api/v3/time y calcula el offset (serverTime - localTime).
     * Este offset se usa en getCorrectedTimestamp() para firmar requests.
     */
    private void syncServerTime() {
        try {
            // Consulta tiempo del servidor
            String response = makeRequest("/api/v3/time", "");
            if (response != null) {
                var json = objectMapper.readTree(response);
                long serverTime = json.get("serverTime").asLong();
                long localTime = System.currentTimeMillis();
                
                // Calcula offset (puede ser positivo o negativo)
                serverTimeOffset = serverTime - localTime;
                Log.info("Sincronizado con servidor. (Offset: " + serverTimeOffset + "ms)");
            }
        } catch (Exception e) {
            Log.warn(TAG, "No se pudo sincronizar tiempo: " + e.getMessage());
        }
    }

    /**
     * Obtiene el timestamp corregido para usar en requests firmados.
     * Aplica el offset calculado en syncServerTime().
     * Si el offset es > 60 segundos, usa timestamp local sin corregir
     * (por seguridad, para no enviar timestamps muy desviados).
     * @return Timestamp en ms con offset aplicado
     */
    private long getCorrectedTimestamp() {
        long localTime = System.currentTimeMillis();
        long corrected = localTime + serverTimeOffset;
        
        // Safety: Si offset es muy grande, no corregir
        if (Math.abs(serverTimeOffset) > 60000) {
            Log.warn(TAG, "Offset muy grande (" + serverTimeOffset + "ms), usando timestamp local sin corregir");
            corrected = localTime;
        }
        
        Log.debug(TAG, "Timestamp: local=" + localTime + ", offset=" + serverTimeOffset + ", corrected=" + corrected);
        return corrected;
    }

    /**
     * Obtiene los balances de la cuenta (USDT y BNB) y el precio de BNB.
     * Usa endpoint firmado GET /api/v3/account.
     * 
     * @return Array de 3 doubles: [balanceUSDT, balanceBNB, precioBNB]
     */
    public double[] getAccountBalances() {
        // Valores por defecto si falla la consulta
        double usdtBalance = 0.0;
        double bnbBalance = 0.0;
        double bnbPrice = 0.0;

        try {
            // Preparar parámetros para request firmado
            Map<String, String> params = new HashMap<>();
            params.put("timestamp", String.valueOf(getCorrectedTimestamp()));
            params.put("recvWindow", String.valueOf(RECV_WINDOW));
            
            // Request firmado a /api/v3/account
            String response = makeSignedRequest("/api/v3/account", params);
            
            if (response != null && !response.isEmpty()) {
                var accountJson = objectMapper.readTree(response);
                var balances = accountJson.get("balances");
                
                // Iterar balances buscando USDT y BNB
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
            
            // Obtener precio actual de BNB para calcular valor de comisiones
            bnbPrice = getSymbolPrice("BNBUSDT");
            Log.debug(TAG, "Saldos - USDT: " + usdtBalance + ", BNB: " + bnbBalance + ", BNB Price: " + bnbPrice);
            
        } catch (Exception e) {
            Log.warn(TAG, "Error al obtener saldos: " + e.getMessage());
        }

        return new double[]{usdtBalance, bnbBalance, bnbPrice};
    }

    /**
     * Obtiene el precio actual de un símbolo via endpoint público.
     * GET /api/v3/ticker/price?symbol=XXX
     *
     * @param symbol Símbolo (ej: "BNBUSDT")
     * @return Precio actual, 0.0 si falla
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
     * Coloca una orden (sobrecarga sin quoteOrderQty ni realOrder).
     * @param symbol   Símbolo
     * @param side     "BUY" o "SELL"
     * @param orderType "MARKET" o "LIMIT"
     * @param quantity Cantidad
     * @param price    Precio (para LIMIT)
     * @return OrderResult
     */
    public com.arbitrage.model.OrderResult placeOrder(String symbol, String side, String orderType, double quantity, double price) {
        return placeOrder(symbol, side, orderType, quantity, price, 0, false);
    }

    /**
     * Coloca una orden (pública, con quoteOrderQty y bandera realOrder).
     *
     * DIFERENCIA ENTRE MODO REAL Y SIMULADO:
     * - realOrder=true y API key presente → placeRealOrder() (envía a Binance)
     * - realOrder=false → genera OrderResult simulado (FILLED instantáneo)
     *
     * @param symbol        Símbolo
     * @param side          "BUY" o "SELL"
     * @param orderType     "MARKET" o "LIMIT"
     * @param quantity      Cantidad del base asset
     * @param price         Precio límite (solo LIMIT)
     * @param quoteOrderQty Monto en quote asset para MARKET BUY
     * @param realOrder     true = orden real, false = simulación
     * @return OrderResult con estado de la orden
     */
    public com.arbitrage.model.OrderResult placeOrder(String symbol, String side, String orderType,
                                                       double quantity, double price, double quoteOrderQty, boolean realOrder) {
        if (realOrder && !apiConfig.getCurrentApiKey().isEmpty()) {
            return placeRealOrder(symbol, side, orderType, quantity, price, quoteOrderQty);
        } else {
            // Modo simulación: generar resultado ficticio
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
     * COLOCA UNA ORDEN REAL EN BINANCE.
     * 
     * Endpoint: POST /api/v3/order (firmado)
     *
     * CONSTRUCCIÓN DE LA ORDEN SEGÚN TIPO:
     *   MARKET:
     *     - Usa quoteOrderQty (monto en USDT a gastar)
     *     - No necesita quantity ni price
     *     - Binance calcula la cantidad automáticamente
     *
     *   LIMIT:
     *     - Usa quantity + price
     *     - timeInForce = GTC (Good Til Cancelled)
     *     - La orden permanece en el libro hasta ser llenada o cancelada
     *
     * PARSEO DE LA RESPUESTA:
     *   - Extrae orderId, status, executedQty, price
     *   - Para MARKET: calcula filledPrice = cummulativeQuoteQty / executedQty
     *   - Extrae commissionAsset y commissionAmount (para registro de fees)
     *
     * @param symbol        Símbolo
     * @param side          "BUY" o "SELL"
     * @param orderType     "MARKET" o "LIMIT"
     * @param quantity      Cantidad (solo LIMIT)
     * @param price         Precio (solo LIMIT)
     * @param quoteOrderQty Monto en quote asset (solo MARKET)
     * @return OrderResult con datos de la orden
     */
    private com.arbitrage.model.OrderResult placeRealOrder(String symbol, String side, String orderType,
                                                           double quantity, double price, double quoteOrderQty) {
        try {
            long timestamp = getCorrectedTimestamp();

            // Construir parámetros según tipo de orden
            Map<String, String> params = new HashMap<>();
            params.put("symbol", symbol);
            params.put("side", side);
            params.put("type", orderType);
            params.put("timestamp", String.valueOf(timestamp));
            params.put("recvWindow", String.valueOf(RECV_WINDOW));

            if ("MARKET".equalsIgnoreCase(orderType)) {
                // MARKET BUY: usar quoteOrderQty (cantidad de USDT a gastar)
                params.put("quoteOrderQty", String.format("%.8f", quoteOrderQty));
            } else if ("LIMIT".equalsIgnoreCase(orderType)) {
                // LIMIT: cantidad + precio + timeInForce=GTC
                params.put("quantity", String.format("%.8f", quantity));
                params.put("price", String.format("%.8f", price));
                params.put("timeInForce", "GTC");
            } else {
                // Otros tipos (STOP_LOSS, etc.): solo quantity
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

            // Extraer orderId de la respuesta
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

            // Parsear respuesta completa de Binance
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
                    // Para MARKET orders: precio real = quoteQty / executedQty
                    if ("MARKET".equalsIgnoreCase(orderType) && executedQty > 0) {
                        if (json.has("cummulativeQuoteQty")) {
                            double quoteQty = json.get("cummulativeQuoteQty").asDouble();
                            filledPrice = quoteQty / executedQty;
                        }
                    }
                    // Extraer datos de comisión (puede ser en BNB, USDT, o el asset mismo)
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
     * Convierte un array de bytes a string hexadecimal.
     * Usado para convertir la firma HMAC-SHA256 a string.
     */
    private static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    /**
     * Genera la firma HMAC-SHA256 para autenticar requests.
     * La firma se calcula sobre el queryString completo ordenado alfabéticamente.
     * 
     * @param queryString Query string a firmar (key=value&key=value...)
     * @return Firma HMAC-SHA256 en hexadecimal
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
     * Ejecuta una request GET simple (sin firma).
     * @param endpoint    Endpoint API (ej: "/api/v3/time")
     * @param queryString Query string (ej: "symbol=BNBUSDT")
     * @return Response body como string JSON
     * @throws Exception Si la request falla o el status code no es 2xx
     */
    public String makeRequest(String endpoint, String queryString) throws Exception {
        return makeRequestWithMethod(endpoint, queryString, false);
    }

    /**
     * Ejecuta una request HTTP GET o POST sin firma.
     * Incluye header X-MBX-APIKEY en todas las requests.
     * 
     * @param endpoint    Endpoint API
     * @param queryString Query string
     * @param isPost      true para POST, false para GET
     * @return Response body como string JSON
     * @throws Exception Si la API retorna error
     */
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
     * Ejecuta una request firmada (HMAC-SHA256) con método GET por defecto.
     * @param endpoint Endpoint API
     * @param params   Parámetros de la request (incluyendo timestamp)
     * @return Response body como string JSON
     * @throws Exception Si la API retorna error
     */
    public String makeSignedRequest(String endpoint, Map<String, String> params) throws Exception {
        return makeSignedRequest(endpoint, params, false);
    }

    /**
     * Ejecuta una request firmada (HMAC-SHA256).
     * 
     * PROCESO DE FIRMA:
     * 1. Ordena parámetros alfabéticamente (TreeMap)
     * 2. Construye queryString: key1=value1&key2=value2...
     * 3. Genera firma HMAC-SHA256 del queryString
     * 4. Añade &signature=XXXX al queryString
     * 5. Ejecuta request GET o POST
     *
     * @param endpoint   Endpoint API
     * @param params     Parámetros (timestamp, recvWindow, etc.)
     * @param forcePost  true para forzar POST (usado en placeOrder)
     * @return Response body como string JSON
     * @throws Exception Si la API retorna error
     */
    public String makeSignedRequest(String endpoint, Map<String, String> params, boolean forcePost) throws Exception {
        // Ordenar parámetros alfabéticamente (requisito de Binance para la firma)
        TreeMap<String, String> sortedParams = new TreeMap<>(params);
        StringBuilder queryBuilder = new StringBuilder();

        for (Map.Entry<String, String> entry : sortedParams.entrySet()) {
            if (queryBuilder.length() > 0) {
                queryBuilder.append("&");
            }
            queryBuilder.append(entry.getKey()).append("=").append(entry.getValue());
        }

        String queryString = queryBuilder.toString();
        // Generar firma HMAC-SHA256
        String signature = generateSignature(queryString);
        queryString += "&signature=" + signature;

        boolean isPost = forcePost;
        return makeRequestWithMethod(endpoint, queryString, isPost);
    }

    /**
     * Obtiene la lista de símbolos USDT con status=TRADING desde Binance.
     * Consulta GET /api/v3/exchangeInfo y filtra por símbolos que terminan en USDT.
     * 
     * @return Lista de símbolos USDT (ej: ["BNBUSDT", "ETHUSDT", ...])
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
                        
                        // Solo símbolos USDT con status TRADING
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
     * Obtiene TODOS los símbolos de Binance con status=TRADING.
     * Útil para conocer todos los pares disponibles (no solo USDT).
     *
     * @return Set de todos los símbolos activos
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
     * Resetea el caché de filtros para forzar una recarga en la próxima llamada.
     */
    public void resetFiltersCache() {
        lastFiltersLoad = 0;
        Log.debug(TAG, "Exchange info cache reset");
    }

    /**
     * CARGA LOS FILTROS DE BINANCE EN CACHÉ (LOT_SIZE, MIN_NOTIONAL, PRICE_FILTER).
     * 
     * Este método es crítico para el funcionamiento del bot. Sin estos filtros,
     * las órdenes serían rechazadas por Binance.
     *
     * FILTROS CARGADOS:
     *   LOT_SIZE: {minQty, maxQty, stepSize} → para ajustar cantidades
     *   MIN_NOTIONAL / NOTIONAL: {minNotional} → valor mínimo de la orden
     *   PRICE_FILTER: {tickSize} → para ajustar precios
     *
     * El caché tiene TTL de 5 minutos (FILTERS_TTL_MS).
     * Los filtros se cargan bajo demanda desde loadExchangeInfoFilters()
     * y también explícitamente al inicializar OrderExecutor.
     */
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
                            // LOT_SIZE: controla cantidad mínima, máxima y step de redondeo
                            Map<String, Double> f = new HashMap<>();
                            f.put("minQty", Double.parseDouble(filter.get("minQty").asText()));
                            f.put("maxQty", Double.parseDouble(filter.get("maxQty").asText()));
                            f.put("stepSize", Double.parseDouble(filter.get("stepSize").asText()));
                            lotSizeFilters.put(symbol, f);
                        } else if ("MIN_NOTIONAL".equals(filterType)) {
                            // MIN_NOTIONAL: valor nominal mínimo de la orden
                            double minNotional = Double.parseDouble(filter.get("minNotional").asText());
                            minNotionalFilters.put(symbol, minNotional);
                        } else if ("NOTIONAL".equals(filterType)) {
                            // NOTIONAL: reemplazo moderno de MIN_NOTIONAL
                            double minNotional = Double.parseDouble(filter.get("minNotional").asText());
                            minNotionalFilters.put(symbol, minNotional);
                        } else if ("PRICE_FILTER".equals(filterType)) {
                            // PRICE_FILTER: tickSize para redondeo de precios
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

    /**
     * Carga filtros de exchangeInfo solo para los símbolos dados.
     * Optimizado para cargar solo los filtros de los símbolos triangulares.
     * 
     * @param symbols Conjunto de símbolos a cargar (ej: BTCUSDT, ETHBTC, ETHUSDT)
     */
    public void loadExchangeInfoFiltersForSymbols(Set<String> symbols) {
        if (symbols == null || symbols.isEmpty()) {
            return;
        }
        loadExchangeInfoFilters();
        
        Map<String, Double> filteredNotional = new HashMap<>();
        for (String s : symbols) {
            Double minNotional = minNotionalFilters.get(s);
            if (minNotional != null) {
                filteredNotional.put(s, minNotional);
            }
        }
        minNotionalFilters.clear();
        minNotionalFilters.putAll(filteredNotional);
    }

    /**
     * AJUSTA UNA CANTIDAD AL LOT_SIZE DEL SÍMBOLO.
     * 
     * LOT_SIZE es un filtro de Binance que define:
     *   minQty: cantidad mínima permitida
     *   maxQty: cantidad máxima permitida
     *   stepSize: incremento mínimo (redondeo)
     *
     * El ajuste se hace con Math.floor(qty / stepSize) * stepSize
     * para asegurar que la cantidad NO exceda el máximo permitido.
     * Si qty < minQty, se retorna minQty.
     *
     * @param symbol Símbolo (ej: "BNBUSDT")
     * @param qty    Cantidad a ajustar
     * @return Cantidad ajustada al LOT_SIZE
     */
    public double adjustQuantityToLotSize(String symbol, double qty) {
        // Asegurar que los filtros estén cargados
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

        // Si la cantidad es menor al mínimo, usar el mínimo
        if (qty < minQty) {
            Log.debug(TAG, symbol + ": qty " + qty + " < min " + minQty + ", using min");
            return minQty;
        }

        // Redondear hacia abajo al stepSize más cercano
        return Math.floor(qty / stepSize) * stepSize;
    }

    /**
     * Ajuste de cantidad genérico (sin LOT_SIZE).
     * Redondea a 5 decimales (0.00001).
     * Útil para cantidades muy pequeñas.
     */
    public double adjustQuantityRaw(double qty) {
        if (qty < 0.00001) {
            return 0.00001;
        }
        return Math.floor(qty / 0.00001) * 0.00001;
    }

    /**
     * AJUSTA UN PRECIO AL TICK SIZE DEL SÍMBOLO (PRICE_FILTER).
     * 
     * PRICE_FILTER define el tickSize: incremento mínimo en el precio.
     * Ej: si tickSize=0.01, el precio debe ser múltiplo de 0.01.
     * 
     * Usa Math.round() en lugar de Math.floor() porque el precio
     * puede redondearse hacia arriba o abajo.
     *
     * @param symbol Símbolo
     * @param price  Precio a ajustar
     * @return Precio ajustado al tickSize
     */
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

    /**
     * Retorna el tickSize (PRICE_FILTER) de un símbolo.
     * @param symbol Símbolo
     * @return tickSize, o 0.00000001 por defecto
     */
    public double getTickSize(String symbol) {
        loadExchangeInfoFilters();
        Double tickSize = priceTickSizes.get(symbol);
        return tickSize != null ? tickSize : 0.00000001;
    }

    /**
     * Retorna el MIN_NOTIONAL de un símbolo (valor mínimo de orden).
     * Si no encuentra el filtro, retorna 10.0 USDT como valor seguro.
     * @param symbol Símbolo
     * @return Min notional en USDT
     */
    public double getMinNotional(String symbol) {
        loadExchangeInfoFilters();
        Double minNotional = minNotionalFilters.get(symbol);
        return minNotional != null ? minNotional : 10.0;
    }

    /**
     * Retorna MIN_NOTIONAL o 0 si no existe (para cálculos comparativos).
     * @param symbol Símbolo
     * @return Min notional, o 10.0 por defecto
     */
    public double getMinNotionalOrZero(String symbol) {
        loadExchangeInfoFilters();
        Double minNotional = minNotionalFilters.get(symbol);
        if (minNotional != null && minNotional > 0) {
            return minNotional;
        }
        return 10.0;
    }

    /**
     * Obtiene el balance disponible (free) de un asset específico.
     * Consulta GET /api/v3/account (firmado) y busca el asset.
     *
     * @param asset Nombre del asset (ej: "BNB", "USDT", "SUI")
     * @return Balance free del asset, 0 si no se encuentra o falla
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
     * Obtiene el balance disponible de BNB específicamente.
     * Usa getAssetBalance("BNB") internamente.
     *
     * @return Balance free de BNB, 0 si falla
     */
    public double getBNBBalance() {
        return getAssetBalance("BNB");
    }

    /**
     * Extrae el BASE ASSET de un símbolo Binance.
     * 
     * Lógica: el base asset es la primera parte del par.
     * Ejemplos:
     *   "BNBUSDT" → "BNB"  (base=BNB, quote=USDT)
     *   "SUIBNB"  → "SUI"  (base=SUI, quote=BNB)
     *   "ETHBTC"  → "ETH"  (base=ETH, quote=BTC)
     *
     * @param symbol Símbolo Binance
     * @return Base asset del símbolo
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
     * Cierra el scheduler de sincronización de tiempo.
     */
    public void shutdown() {
        if (scheduler != null && !scheduler.isShutdown()) {
            scheduler.shutdown();
        }
    }

    /**
     * Obtiene símbolos USDT ordenados por volumen de trading (descendente).
     * 
     * ESTRATEGIA DE OBTENCIÓN:
     *   Intento 1: GET /api/v3/ticker/24hr (sin symbol) → obtiene todos los tickers
     *              con quoteVolume. Filtra USDT, ordena por volumen.
     *   Fallback:  Si el intento 1 falla (respuesta vacía), usa getAllSymbols()
     *              filtrado por USDT (sin datos de volumen, van al final).
     *
     * @param limit Número máximo de símbolos a retornar
     * @return Lista de símbolos USDT ordenados por volumen descendente
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

    /**
     * Clase interna para almacenar símbolo + volumen de trading.
     */
    private static class SymbolVolume {
        String symbol;
        double volume;
        SymbolVolume(String symbol, double volume) {
            this.symbol = symbol;
            this.volume = volume;
        }
    }

    /**
     * CONSULTA EL ESTADO DE UNA ORDEN EXISTENTE EN BINANCE.
     * 
     * Endpoint: GET /api/v3/order?symbol=X&orderId=Y (firmado)
     *
     * Para MARKET orders: calcula el precio real como cummulativeQuoteQty / executedQty
     * ya que Binance devuelve price=0.0 en MARKET fills.
     *
     * @param symbol  Símbolo de la orden
     * @param orderId ID de la orden en Binance
     * @return OrderResult con estado actual (FILLED, CANCELED, NEW, PARTIALLY_FILLED, etc.)
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

                // Para MARKET orders: el precio real se calcula del quoteQty
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
     * CANCELA UNA ORDEN EXISTENTE EN BINANCE.
     * 
     * Endpoint: DELETE /api/v3/order?symbol=X&orderId=Y (firmado)
     * 
     * Usa HTTP DELETE con query string firmado.
     * Solo cancela órdenes en estado NEW o PARTIALLY_FILLED.
     *
     * @param symbol  Símbolo de la orden
     * @param orderId ID de la orden en Binance
     * @return OrderResult con estado "CANCELED" si éxito, "ERROR" si falla
     */
    public com.arbitrage.model.OrderResult cancelOrder(String symbol, String orderId) {
        try {
            long timestamp = getCorrectedTimestamp();
            
            Map<String, String> params = new HashMap<>();
            params.put("symbol", symbol);
            params.put("orderId", orderId);
            params.put("timestamp", String.valueOf(timestamp));
            params.put("recvWindow", String.valueOf(RECV_WINDOW));
            
            // Construir query string firmado
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
            
            // DELETE request con query string firmado
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
     * Coloca una orden (sobrecarga con bandera realOrder).
     * @param symbol    Símbolo
     * @param side      "BUY" o "SELL"
     * @param orderType "MARKET" o "LIMIT"
     * @param quantity  Cantidad
     * @param price     Precio
     * @param realOrder true para orden real, false para simulación
     * @return OrderResult
     */
    public com.arbitrage.model.OrderResult placeOrder(String symbol, String side, String orderType, 
                                          double quantity, double price, boolean realOrder) {
        return placeOrder(symbol, side, orderType, quantity, price, 0.0, realOrder);
    }

    /**
     * Extrae el QUOTE ASSET de un símbolo Binance.
     * Es la segunda parte del par (la moneda en la que se cotiza).
     * Ej: "BNBUSDT" → "USDT", "SUIBNB" → "BNB"
     * 
     * @param symbol Símbolo Binance
     * @return Quote asset, o "" si no se reconoce
     */
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