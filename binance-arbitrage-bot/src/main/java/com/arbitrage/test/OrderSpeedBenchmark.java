package com.arbitrage.test;

import com.arbitrage.config.ApiConfig;
import com.arbitrage.config.AppConfig;
import com.arbitrage.model.Ticker;
import com.arbitrage.trading.BinanceApiClient;
import com.arbitrage.util.Log;
import com.arbitrage.websocket.BinanceWebSocketClient;
import com.arbitrage.websocket.PriceUpdateHandler;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * OrderSpeedBenchmark - Suite de benchmarks para medir rendimiento de la API de Binance.
 *
 * Proposito: Evaluar la latencia y throughput de las operaciones criticas del bot
 * para identificar cuellos de botella y validar que el rendimiento es adecuado
 * para trading de alta frecuencia.
 *
 * Se activa con: testserver=true en la configuracion del bot (solo testnet).
 *
 * Pruebas que ejecuta (en orden):
 * 1. API Round-Trip Time: 100 muestras de latencia GET /api/v3/time
 *    - Mide min, avg, max, p50, p95, p99
 * 2. LotSize Adjustment: 5000 ejecuciones de adjustQuantityToLotSize()
 *    - Mide throughput (ops/ms) para ajuste de cantidades
 * 3. Price Tick Adjustment: 5000 ejecuciones de adjustPriceToTickSize()
 *    - Mide throughput para ajuste de precios
 * 4. HMAC-SHA256 Signature: 5000 firmas criptograficas
 *    - Mide velocidad de generacion de firmas para requests firmados
 * 5. MARKET BUY Order: 3 ordenes reales de compra en testnet
 *    - Mide latencia end-to-end de colocacion de orden
 * 6. LIMIT order + Cancel: 3 ordenes LIMIT + cancelacion
 *    - Mide latencia de colocacion y cancelacion por separado
 * 7. Query Order: 10 consultas de estado de orden
 *    - Mide latencia de GET /api/v3/order
 * 8. Load ExchangeInfo Filters: carga completa de filtros
 *    - Mide tiempo de parseo de exchangeInfo
 * 9. WebSocket Connection & Throughput: conexion + 5s de mensajes
 *    - Mide tiempo de conexion y msgs/s recibidos
 *
 * Formato de salida: Tabla con colores ANSI mostrando resultado de cada prueba.
 *
 * @see AppConfig Configuracion del bot (testserver=true activa benchmark)
 */
public class OrderSpeedBenchmark {

    /** Tag para logging interno del benchmark. */
    private static final String TAG = "BENCH";

    // =====================================================================
    // COLORES ANSI PARA FORMATO DE SALIDA
    // =====================================================================
    private static final String ANSI_RESET = "\u001B[0m";
    private static final String ANSI_CYAN = "\u001B[36m";
    private static final String ANSI_GREEN = "\u001B[32m";
    private static final String ANSI_YELLOW = "\u001B[33m";
    private static final String ANSI_RED = "\u001B[31m";
    private static final String ANSI_WHITE = "\u001B[37m";

    /** Cliente REST de Binance para las pruebas. */
    private final BinanceApiClient apiClient;
    /** Configuracion global del bot. */
    private final AppConfig config;
    /** Configuracion de API keys. */
    private final ApiConfig apiConfig;
    /** Lista acumulada de resultados de cada prueba. */
    private final List<BenchResult> results;
    /** Timestamp de inicio del benchmark completo (para tiempo total). */
    private long totalStartTime;

    /**
     * Constructor del benchmark.
     *
     * @param apiClient Cliente REST de Binance para ejecutar las pruebas
     * @param config    Configuracion global del bot
     * @param apiConfig Configuracion de API keys (para verificar que es testnet)
     */
    public OrderSpeedBenchmark(BinanceApiClient apiClient, AppConfig config, ApiConfig apiConfig) {
        this.apiClient = apiClient;
        this.config = config;
        this.apiConfig = apiConfig;
        this.results = new ArrayList<>();
    }

    /**
     * Ejecuta todas las pruebas de benchmark en secuencia.
     *
     * Orquesta las 9 pruebas y muestra un resumen final con formato tabular.
     * Verifica primero que se este ejecutando en testnet (requisito de seguridad).
     */
    public void runAll() {
        totalStartTime = System.nanoTime();

        printHeader();

        if (!apiConfig.isTestnet()) {
            printError("ERROR: testserver=true requiere testnet=true en user.apiConfig");
            printError("El benchmark solo debe ejecutarse contra testnet.");
            return;
        }

        printRestHeader();
        testApiLatency();
        testLotSizeAdjustment();
        testPriceAdjustment();
        testSignatureSpeed();
        testPlaceMarketOrder();
        testPlaceLimitAndCancel();
        testQueryOrder();
        testLoadExchangeInfo();

        printWsHeader();
        testWebSocketLatency();

        printSummary();
    }

    /**
     * Imprime el encabezado principal del benchmark.
     */
    private void printHeader() {
        System.out.println();
        System.out.println(ANSI_CYAN + "================================================================================" + ANSI_RESET);
        System.out.println(ANSI_CYAN + "  ORDER SPEED BENCHMARK v1.5.1" + ANSI_RESET);
        System.out.println(ANSI_CYAN + "  Midiendo latencia de API y velocidad de ejecucion de ordenes" + ANSI_RESET);
        System.out.println(ANSI_CYAN + "================================================================================" + ANSI_RESET);
        System.out.println();
    }

    /**
     * Imprime el resumen final con todos los resultados en formato tabular.
     * Muestra: nombre de prueba, resultado, throughput (ops/ms).
     * Colorea en verde los exitos y en rojo los fallos.
     */
    private void printSummary() {
        long totalNanos = System.nanoTime() - totalStartTime;
        double totalSecs = totalNanos / 1_000_000_000.0;

        System.out.println();
        System.out.println(ANSI_CYAN + "================================================================================" + ANSI_RESET);
        System.out.println(ANSI_CYAN + "  RESUMEN DE BENCHMARK" + ANSI_RESET);
        System.out.println(ANSI_CYAN + "================================================================================" + ANSI_RESET);
        System.out.println(ANSI_WHITE + "  Tiempo total: " + ANSI_YELLOW + String.format("%.2f", totalSecs) + "s" + ANSI_RESET);
        System.out.println();
        System.out.println(ANSI_CYAN + "  " + padRight("PRUEBA", 40) + " " +
                padRight("RESULTADO", 20) + " " +
                padRight("OPS/MS", 12) + ANSI_RESET);
        System.out.println(ANSI_CYAN + "  " + String.join("", java.util.Collections.nCopies(72, "-")) + ANSI_RESET);

        for (BenchResult r : results) {
            String color = r.ok ? ANSI_GREEN : ANSI_RED;
            System.out.println("  " + color + padRight(r.name, 40) + " " +
                    padRight(r.value, 20) + " " +
                    (r.ok ? padRight(r.throughput, 12) : padRight("FAIL", 12)) +
                    ANSI_RESET);
        }
        System.out.println();
    }

    /**
     * Agrega un resultado de prueba a la lista acumulada.
     *
     * @param name       Nombre descriptivo de la prueba
     * @param value      Resultado obtenido (ej: "45.23ms")
     * @param throughput Throughput calculado (ej: "22.1 ops/ms")
     * @param ok         true si la prueba fue exitosa
     */
    private void addResult(String name, String value, String throughput, boolean ok) {
        results.add(new BenchResult(name, value, throughput, ok));
    }

    /**
     * Prueba 1: API Round-Trip Time.
     *
     * Ejecuta 100 solicitudes GET /api/v3/time y mide la latencia de cada una.
     * Calcula estadisticas: min, avg, max, p50, p95, p99.
     * Estas metricas indican la latencia de red base entre el bot y Binance.
     */
    private void testApiLatency() {
        int samples = 100;
        double[] latencies = new double[samples];
        int successCount = 0;

        printSubHeader("1. API Round-Trip Time (" + samples + " muestras)");

        for (int i = 0; i < samples; i++) {
            long start = System.nanoTime();
            try {
                String response = apiClient.makeRequest("/api/v3/time", "");
                if (response != null && !response.isEmpty()) {
                    long end = System.nanoTime();
                    latencies[successCount++] = (end - start) / 1_000_000.0;
                }
            } catch (Exception e) {
            }
        }

        if (successCount > 0) {
            double[] data = Arrays.copyOf(latencies, successCount);
            Arrays.sort(data);
            double sum = 0;
            for (double d : data) sum += d;
            double avg = sum / data.length;
            double min = data[0];
            double max = data[data.length - 1];
            double p50 = percentile(data, 50);
            double p95 = percentile(data, 95);
            double p99 = percentile(data, 99);

            System.out.println("    min  : " + ANSI_GREEN + String.format("%.2f", min) + "ms" + ANSI_RESET);
            System.out.println("    avg  : " + ANSI_YELLOW + String.format("%.2f", avg) + "ms" + ANSI_RESET);
            System.out.println("    max  : " + ANSI_RED + String.format("%.2f", max) + "ms" + ANSI_RESET);
            System.out.println("    p50  : " + String.format("%.2f", p50) + "ms");
            System.out.println("    p95  : " + String.format("%.2f", p95) + "ms");
            System.out.println("    p99  : " + String.format("%.2f", p99) + "ms");

            addResult("API RTT (avg)", String.format("%.2f", avg) + "ms",
                    String.format("%.1f", 1000.0 / avg), true);
        } else {
            addResult("API RTT", "FAIL", "-", false);
        }
    }

    /**
     * Prueba 2: LotSize Adjustment.
     *
     * Ejecuta 5000 llamadas a adjustQuantityToLotSize() con diferentes simbolos
     * y cantidades. Mide el throughput (operaciones por milisegundo).
     * Esta operacion es critica porque se ejecuta antes de cada orden real.
     */
    private void testLotSizeAdjustment() {
        int samples = 5000;
        String[] symbols = {"BTCUSDT", "ETHUSDT", "LINKUSDT", "SOLUSDT", "ADAUSDT"};
        long totalNanos = 0;

        printSubHeader("2. LotSize Adjustment (" + samples + " ejecuciones)");

        long start = System.nanoTime();
        int executed = 0;
        for (int i = 0; i < samples; i++) {
            String symbol = symbols[i % symbols.length];
            double qty = 0.001 + (i % 100) * 0.001;
            try {
                apiClient.adjustQuantityToLotSize(symbol, qty);
                executed++;
            } catch (Exception e) {
            }
        }
        long end = System.nanoTime();
        totalNanos = end - start;

        double totalMs = totalNanos / 1_000_000.0;
        double opsPerMs = executed / Math.max(totalMs, 0.001);

        System.out.println("    total : " + String.format("%.2f", totalMs) + "ms");
        System.out.println("    ops/ms: " + ANSI_GREEN + String.format("%.1f", opsPerMs) + ANSI_RESET);

        addResult("LotSize adjust", String.format("%.2f", totalMs) + "ms / " + executed + " ops",
                String.format("%.1f", opsPerMs), true);
    }

    /**
     * Prueba 3: Price Tick Adjustment.
     *
     * Ejecuta 5000 llamadas a adjustPriceToTickSize() con diferentes simbolos
     * y precios. Mide el throughput. Similar a LotSize pero para precios.
     */
    private void testPriceAdjustment() {
        int samples = 5000;
        String[] symbols = {"BTCUSDT", "ETHUSDT", "LINKUSDT", "SOLUSDT", "ADAUSDT"};
        long totalNanos = 0;

        printSubHeader("3. Price Tick Adjustment (" + samples + " ejecuciones)");

        long start = System.nanoTime();
        int executed = 0;
        for (int i = 0; i < samples; i++) {
            String symbol = symbols[i % symbols.length];
            double price = 50000.0 + (i % 100) * 10.0;
            try {
                apiClient.adjustPriceToTickSize(symbol, price);
                executed++;
            } catch (Exception e) {
            }
        }
        long end = System.nanoTime();
        totalNanos = end - start;

        double totalMs = totalNanos / 1_000_000.0;
        double opsPerMs = executed / Math.max(totalMs, 0.001);

        System.out.println("    total : " + String.format("%.2f", totalMs) + "ms");
        System.out.println("    ops/ms: " + ANSI_GREEN + String.format("%.1f", opsPerMs) + ANSI_RESET);

        addResult("Price adjust", String.format("%.2f", totalMs) + "ms / " + executed + " ops",
                String.format("%.1f", opsPerMs), true);
    }

    /**
     * Prueba 4: HMAC-SHA256 Signature Speed.
     *
     * Ejecuta 5000 firmas criptograficas HMAC-SHA256 sobre un query string tipico.
     * Mide el throughput. La firma es necesaria para todas las requests firmadas
     * (balances, ordenes, etc.), por lo que su velocidad impacta la latencia total.
     */
    private void testSignatureSpeed() {
        int samples = 5000;
        printSubHeader("4. HMAC-SHA256 Signature (" + samples + " ejecuciones)");

        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            SecretKeySpec keySpec = new SecretKeySpec(
                    apiConfig.getCurrentSecretKey().getBytes(StandardCharsets.UTF_8),
                    "HmacSHA256");
            mac.init(keySpec);

            String queryString = "symbol=BTCUSDT&side=BUY&type=MARKET&quoteOrderQty=10.00000000&timestamp=1234567890&recvWindow=60000";

            long start = System.nanoTime();
            for (int i = 0; i < samples; i++) {
                mac.doFinal(queryString.getBytes(StandardCharsets.UTF_8));
            }
            long end = System.nanoTime();

            double totalMs = (end - start) / 1_000_000.0;
            double opsPerMs = samples / Math.max(totalMs, 0.001);

            System.out.println("    total : " + String.format("%.2f", totalMs) + "ms");
            System.out.println("    ops/ms: " + ANSI_GREEN + String.format("%.1f", opsPerMs) + ANSI_RESET);

            addResult("Signature HMAC-SHA256", String.format("%.2f", totalMs) + "ms / " + samples + " ops",
                    String.format("%.1f", opsPerMs), true);
        } catch (Exception e) {
            printError("    ERROR: " + e.getMessage());
            addResult("Signature HMAC-SHA256", "FAIL", "-", false);
        }
    }

    /**
     * Prueba 5: MARKET BUY Order.
     *
     * Ejecuta 3 ordenes MARKET de compra reales en testnet con BTCUSDT.
     * Mide la latencia end-to-end desde el envio hasta la respuesta de Binance.
     * Esta es la metrica mas importante para el bot: cuanto tarda una orden real.
     */
    private void testPlaceMarketOrder() {
        double minNotional = apiClient.getMinNotional("BTCUSDT");
        double quoteQty = Math.max(minNotional * 2, 10.0);
        printSubHeader("5. MARKET BUY Order (quoteOrderQty=" + String.format("%.2f", quoteQty) + " BTCUSDT)");

        if (!apiConfig.isTestnet()) {
            printWarning("    Saltando: solo testnet");
            return;
        }

        int samples = 3;
        List<Long> latencies = new ArrayList<>();
        int successCount = 0;

        for (int i = 0; i < samples; i++) {
            try {
                long start = System.nanoTime();
                var result = apiClient.placeOrder("BTCUSDT", "BUY", "MARKET",
                        0, 0, quoteQty, true);
                long end = System.nanoTime();
                latencies.add((end - start) / 1_000_000L);

                if (result.isSuccess()) {
                    successCount++;
                    System.out.println("    [" + (i + 1) + "/" + samples + "] " +
                            "orderId=" + result.getOrderId() + " " +
                            "status=" + result.getStatus() + " " +
                            "qty=" + result.getExecutedQty() + " " +
                            ANSI_GREEN + result.getElapsedTime() + "ms" + ANSI_RESET);
                } else {
                    System.out.println("    [" + (i + 1) + "/" + samples + "] " +
                            ANSI_RED + "FAIL: " + result.getErrorMessage() + ANSI_RESET);
                }
            } catch (Exception e) {
                System.out.println("    [" + (i + 1) + "/" + samples + "] " +
                        ANSI_RED + "ERROR: " + e.getMessage() + ANSI_RESET);
            }
        }

        if (successCount > 0) {
            double avg = latencies.stream().mapToLong(Long::longValue).average().orElse(0);
            addResult("MARKET BUY (avg)", String.format("%.0f", avg) + "ms",
                    String.format("%.1f", 1000.0 / avg), true);
        } else {
            addResult("MARKET BUY", "FAIL", "-", false);
        }
    }

    /**
     * Prueba 6: LIMIT order + Cancel.
     *
     * Ejecuta 3 ciclos de: colocar orden LIMIT SELL + cancelar.
     * Mide latencia de colocacion y cancelacion por separado.
     * Importante para evaluar el rendimiento de ordenes LIMIT con repricing.
     */
    private void testPlaceLimitAndCancel() {
        printSubHeader("6. LIMIT order + Cancel");

        if (!apiConfig.isTestnet()) {
            printWarning("    Saltando: solo testnet");
            return;
        }

        double currentPrice = apiClient.getSymbolPrice("BTCUSDT");
        if (currentPrice <= 0) {
            printError("    No se pudo obtener precio actual de BTCUSDT");
            return;
        }
        double limitPrice = currentPrice * 1.05;
        limitPrice = apiClient.adjustPriceToTickSize("BTCUSDT", limitPrice);
        System.out.println("    Precio actual BTC: " + String.format("%.2f", currentPrice) +
                " | Limit SELL: " + String.format("%.2f", limitPrice));

        int samples = 3;

        for (int i = 0; i < samples; i++) {
            try {
                long placeStart = System.nanoTime();
                var result = apiClient.placeOrder("BTCUSDT", "SELL", "LIMIT",
                        0.001, limitPrice, 0, true);
                long placeEnd = System.nanoTime();
                long placeMs = (placeEnd - placeStart) / 1_000_000L;

                if (!result.isSuccess()) {
                    System.out.println("    [" + (i + 1) + "/" + samples + "] " +
                            ANSI_RED + "Place FAIL: " + result.getErrorMessage() + ANSI_RESET);
                    continue;
                }

                String orderId = result.getOrderId();

                long cancelStart = System.nanoTime();
                boolean cancelled = apiClient.cancelOrder("BTCUSDT", orderId).isSuccess();
                long cancelEnd = System.nanoTime();
                long cancelMs = (cancelEnd - cancelStart) / 1_000_000L;

                System.out.println("    [" + (i + 1) + "/" + samples + "] " +
                        "orderId=" + orderId + " " +
                        "place=" + ANSI_GREEN + placeMs + "ms" + ANSI_RESET + " " +
                        "cancel=" + ANSI_YELLOW + cancelMs + "ms" + ANSI_RESET);

                if (i == 0) {
                    addResult("LIMIT place", placeMs + "ms", "-", true);
                    addResult("LIMIT cancel", cancelMs + "ms", "-", cancelled);
                }
            } catch (Exception e) {
                System.out.println("    [" + (i + 1) + "/" + samples + "] " +
                        ANSI_RED + "ERROR: " + e.getMessage() + ANSI_RESET);
            }
        }
    }

    /**
     * Prueba 7: Query Order.
     *
     * Ejecuta 10 ciclos de: colocar orden MARKET + consultar su estado.
     * Mide la latencia combinada de colocacion + consulta.
     * El bot usa queryOrder() en el polling loop para verificar fills.
     */
    private void testQueryOrder() {
        printSubHeader("7. Query Order (" + 10 + " muestras)");

        if (!apiConfig.isTestnet()) {
            printWarning("    Saltando: solo testnet");
            return;
        }

        long totalNanos = 0;
        int successCount = 0;

        double minNotional = apiClient.getMinNotional("BTCUSDT");
        double quoteQty = Math.max(minNotional * 2, 10.0);

        for (int i = 0; i < 10; i++) {
            try {
                long start = System.nanoTime();
                var result = apiClient.placeOrder("BTCUSDT", "BUY", "MARKET",
                        0, 0, quoteQty, true);
                if (result.isSuccess()) {
                    var queryResult = apiClient.queryOrder("BTCUSDT", result.getOrderId());
                    long end = System.nanoTime();
                    totalNanos += (end - start);
                    successCount++;
                }
            } catch (Exception e) {
            }
        }

        if (successCount > 0) {
            double avgMs = (totalNanos / 1_000_000.0) / successCount;
            System.out.println("    avg  : " + String.format("%.2f", avgMs) + "ms (" + successCount + " muestras)");
            addResult("Query order (avg)", String.format("%.2f", avgMs) + "ms",
                    String.format("%.1f", 1000.0 / avgMs), true);
        } else {
            addResult("Query order", "FAIL", "-", false);
        }
    }

    /**
     * Prueba 8: Load ExchangeInfo Filters.
     *
     * Mide el tiempo de carga completa de filtros LOT_SIZE, MIN_NOTIONAL,
     * y PRICE_FILTER desde GET /api/v3/exchangeInfo.
     * Esta operacion se ejecuta al iniciar el bot y cada 5 minutos (TTL).
     */
    private void testLoadExchangeInfo() {
        printSubHeader("8. Load ExchangeInfo Filters");

        try {
            apiClient.resetFiltersCache();
            long start = System.nanoTime();
            apiClient.loadExchangeInfoFilters();
            long end = System.nanoTime();
            double ms = (end - start) / 1_000_000.0;

            System.out.println("    total: " + ANSI_GREEN + String.format("%.2f", ms) + "ms" + ANSI_RESET);
            addResult("Load filters", String.format("%.2f", ms) + "ms", "-", true);
        } catch (Exception e) {
            printError("    ERROR: " + e.getMessage());
            addResult("Load filters", "FAIL", "-", false);
        }
    }

    /**
     * Imprime un sub-encabezado para cada prueba individual.
     *
     * @param title Titulo descriptivo de la prueba
     */
    private void printSubHeader(String title) {
        System.out.println();
        System.out.println(ANSI_YELLOW + "  >> " + title + ANSI_RESET);
    }

    /**
     * Imprime un mensaje de advertencia en amarillo.
     */
    private void printWarning(String msg) {
        System.out.println(ANSI_YELLOW + "  " + msg + ANSI_RESET);
    }

    /**
     * Imprime un mensaje de error en rojo.
     */
    private void printError(String msg) {
        System.out.println(ANSI_RED + "  " + msg + ANSI_RESET);
    }

    /**
     * Imprime el encabezado de la seccion REST API.
     */
    private void printRestHeader() {
        System.out.println();
        System.out.println(ANSI_CYAN + "=== Test REST API ===" + ANSI_RESET);
        System.out.println();
    }

    /**
     * Imprime el encabezado de la seccion WebSocket.
     */
    private void printWsHeader() {
        System.out.println();
        System.out.println(ANSI_CYAN + "=== Test WebSocket ===" + ANSI_RESET);
        System.out.println();
    }

    /**
     * Prueba 9: WebSocket Connection & Throughput.
     *
     * 1. Mide el tiempo de conexion al WebSocket de Binance
     * 2. Espera 5 segundos y cuenta los mensajes recibidos
     * 3. Calcula el throughput en mensajes por segundo
     *
     * Esta prueba valida que el WebSocket puede mantener una conexion
     * estable y recibir actualizaciones de precio en tiempo real.
     */
    private void testWebSocketLatency() {
        printSubHeader("9. WebSocket Connection & Throughput");

        ConcurrentHashMap<String, Ticker> priceMap = new ConcurrentHashMap<>();
        PriceUpdateHandler handler = new PriceUpdateHandler(priceMap);
        BinanceWebSocketClient wsClient = new BinanceWebSocketClient(apiConfig, handler);

        try {
            long connectStart = System.nanoTime();
            wsClient.connectAndSubscribe(List.of("BTCUSDT"));

            long timeout = 10000;
            long pollStart = System.currentTimeMillis();
            boolean connected = false;
            while (System.currentTimeMillis() - pollStart < timeout) {
                if (wsClient.isConnected()) {
                    connected = true;
                    break;
                }
                Thread.sleep(50);
            }

            long connectEnd = System.nanoTime();

            if (!connected) {
                printError("    WebSocket connection timeout");
                addResult("WS Connect", "TIMEOUT", "-", false);
                wsClient.close();
                return;
            }

            double connectMs = (connectEnd - connectStart) / 1_000_000.0;
            System.out.println("    Connect: " + ANSI_GREEN + String.format("%.0f", connectMs) + "ms" + ANSI_RESET);

            wsClient.resetMessageCount();
            int sampleSeconds = 5;
            System.out.println("    Midiendo throughput... (" + sampleSeconds + "s)");
            Thread.sleep(sampleSeconds * 1000L);

            int msgCount = wsClient.getMessageCount();
            double msgsPerSec = msgCount / (double) sampleSeconds;
            System.out.println("    Messages: " + msgCount + " en " + sampleSeconds + "s (" +
                    ANSI_GREEN + String.format("%.1f", msgsPerSec) + " msgs/s" + ANSI_RESET + ")");

            addResult("WS Connect", String.format("%.0f", connectMs) + "ms", "-", true);
            addResult("WS Throughput", msgCount + " msgs/" + sampleSeconds + "s",
                    String.format("%.1f", msgsPerSec), true);
        } catch (Exception e) {
            printError("    WebSocket error: " + e.getMessage());
            addResult("WS Test", "FAIL", "-", false);
        } finally {
            wsClient.close();
        }
    }

    /**
     * Rellena un string con espacios a la derecha para alineacion tabular.
     *
     * @param s   String a rellenar
     * @param len Longitud minima deseada
     * @return String con al menos 'len' caracteres
     */
    private String padRight(String s, int len) {
        if (s == null) s = "";
        if (s.length() >= len) return s.substring(0, len);
        StringBuilder sb = new StringBuilder(s);
        while (sb.length() < len) sb.append(' ');
        return sb.toString();
    }

    /**
     * Calcula el percentil de un array ordenado de valores.
     *
     * Usa interpolacion lineal entre los valores inferior y superior
     * cuando el indice del percentil no es entero.
     *
     * @param sorted Array ordenado de valores (ascendente)
     * @param pct    Percentil a calcular (0-100)
     * @return Valor del percentil
     */
    private double percentile(double[] sorted, int pct) {
        if (sorted.length == 0) return 0;
        double idx = (pct / 100.0) * (sorted.length - 1);
        int lower = (int) Math.floor(idx);
        int upper = (int) Math.ceil(idx);
        if (lower == upper) return sorted[lower];
        double frac = idx - lower;
        return sorted[lower] * (1 - frac) + sorted[upper] * frac;
    }

    /**
     * Registro interno de resultado de una prueba de benchmark.
     * Contiene: nombre de la prueba, resultado, throughput, y estado (ok/fail).
     */
    private static class BenchResult {
        /** Nombre descriptivo de la prueba. */
        String name;
        /** Resultado obtenido (ej: "45.23ms"). */
        String value;
        /** Throughput calculado (ej: "22.1 ops/ms"). */
        String throughput;
        /** true si la prueba fue exitosa. */
        boolean ok;

        BenchResult(String name, String value, String throughput, boolean ok) {
            this.name = name;
            this.value = value;
            this.throughput = throughput;
            this.ok = ok;
        }
    }
}
