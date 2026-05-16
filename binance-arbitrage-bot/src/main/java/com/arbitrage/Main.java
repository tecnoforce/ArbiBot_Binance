package com.arbitrage;

import com.arbitrage.config.ApiConfig;
import com.arbitrage.config.AppConfig;
import com.arbitrage.config.ConfigLoader;
import com.arbitrage.config.NetworkEndpoints;
import com.arbitrage.display.ConsoleDisplay;
import com.arbitrage.engine.ArbitrageEngine;
import com.arbitrage.engine.TriangleCalculator;
import com.arbitrage.test.OrderSpeedBenchmark;
import com.arbitrage.model.OrderResult;
import com.arbitrage.model.Ticker;
import com.arbitrage.model.Triangle;
import com.arbitrage.trading.BinanceApiClient;
import com.arbitrage.module.ExecutionEngine;
import com.arbitrage.module.FillTracker;
import com.arbitrage.module.RepricingEngine;
import com.arbitrage.module.RiskManager;
import com.arbitrage.trading.WalletSyncManager;
import com.arbitrage.util.Log;
import com.arbitrage.util.StatsManager;
import com.arbitrage.websocket.BinanceWebSocketClient;
import com.arbitrage.websocket.PriceUpdateHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Punto de entrada principal del Bot de Arbitraje de Binance.
 * Este es EL entry point del sistema. Su flujo de arranque es:
 * <ol>
 *   <li>Carga y valida archivos de configuraci&oacute;n ({@code .config}, {@code .apiConfig}, {@code .coins})</li>
 *   <li>Inicializa logging, cliente API REST y gestor de wallets</li>
 *   <li>Carga s&iacute;mbolos: desde archivo en MAINNET, desde API o archivo en TESTNET</li>
 *   <li>Construye tri&aacute;ngulos de arbitraje con TriangleCalculator</li>
 *   <li>Conecta WebSocket para precios en tiempo real</li>
 *   <li>Arranca el motor de arbitraje (ArbitrageEngine), el sincronizador de wallet y el display</li>
 *   <li>Instala un shutdown hook para cierre ordenado</li>
 * </ol>
 */
public class Main {
    private static final Logger logger = LoggerFactory.getLogger(Main.class);

    /**
     * Obtiene el directorio donde se encuentra fisicamente el JAR ejecutado.
     * Fallback a user.dir si no se puede detectar (ej. ejecucion desde IDE).
     */
    private static String getJarDirectory() {
        try {
            String jarPath = Main.class.getProtectionDomain()
                    .getCodeSource().getLocation().toURI().getPath();
            
            jarPath = java.net.URLDecoder.decode(jarPath, java.nio.charset.StandardCharsets.UTF_8);
            
            File jarFile = new File(jarPath);
            
            if (jarFile.isDirectory()) {
                return jarFile.getAbsolutePath();
            }
            
            String parentDir = jarFile.getParentFile().getAbsolutePath();
            Log.info("MAIN", "Directorio JAR detectado: " + parentDir);
            return parentDir;
            
        } catch (Exception e) {
            Log.warn("MAIN", "No se pudo detectar directorio del JAR, usando user.dir");
            return System.getProperty("user.dir");
        }
    }

    /**
     * M&eacute;todo principal. Orquesta el arranque completo del bot:
     * <ul>
     *   <li>Parseo de argumentos (--config para archivo de configuraci&oacute;n alternativo)</li>
     *   <li>Validaci&oacute;n de archivos obligatorios</li>
     *   <li>Construcci&oacute;n de tri&aacute;ngulos y suscripci&oacute;n WebSocket</li>
     *   <li>Inicio del motor de escaneo y ejecuci&oacute;n de &oacute;rdenes</li>
     * </ul>
     * @param args Argumentos de l&iacute;nea de comandos (soporta {@code --config &lt;file&gt;})
     */
    public static void main(String[] args) {
        // Archivos de configuracion por defecto
        String configFile = "USDTNORMAL2.config";
        String apiConfigFile = "user.apiConfig";
        String coinsFile = "USDTNORMAL2.coins";
        String statsFile = "USDTNORMAL2.stats";

        // Parsear argumentos: --config permite especificar un archivo de configuracion alternativo
        for (int i = 0; i < args.length; i++) {
            if (args[i].equals("--config") && i + 1 < args.length) {
                configFile = args[i + 1];
            }
        }

        // === FASE 1: Validacion de archivos de configuracion ===
        System.out.println("=== VALIDANDO ARCHIVOS DE CONFIGURACION ===");
        String basePath = getJarDirectory();
        File configFileCheck = new File(configFile);
        File apiConfigFileCheck = new File(apiConfigFile);
        File coinsFileCheck = new File(coinsFile);

        boolean configExists = configFileCheck.exists();
        boolean apiConfigExists = apiConfigFileCheck.exists();
        boolean coinsExists = coinsFileCheck.exists();

        System.out.println("Directorio de trabajo: " + basePath);
        System.out.println("Config file    : " + configFile + " [" + (configExists ? "OK" : "FALTA") + "]");
        System.out.println("API Config file: " + apiConfigFile + " [" + (apiConfigExists ? "OK" : "FALTA") + "]");
        System.out.println("Coins file     : " + coinsFile + " [" + (coinsExists ? "OK" : "FALTA") + "]");

        if (!configExists || !apiConfigExists || !coinsExists) {
            System.out.println();
            System.out.println("ERROR: Faltan archivos de configuracion necesarios.");
            System.out.println("Coloca los archivos en el directorio: " + basePath);
            System.exit(1);
        }

        System.out.println("=== VALIDACION COMPLETA ===");
        System.out.println();

        // === FASE 2: Inicializacion de componentes basicos ===
        // Display de consola para mostrar el dashboard en tiempo real
        ConsoleDisplay display = new ConsoleDisplay();
        ConcurrentHashMap<String, Ticker> priceMap = new ConcurrentHashMap<>();

        try {
            // Cargar configuracion principal (parametros de trading, limites, etc.)
            AppConfig config = ConfigLoader.loadAppConfig(configFile);

            // Inicializar logging con el nivel indicado en la configuracion
            String logLevel = config.getLogLevel();
            Log.init((logLevel != null && !logLevel.isEmpty()) ? logLevel : "INFO");
            // SCAN mode: modo silencioso que solo muestra oportunidades sin detalle de ordenes
            boolean isScanMode = "SCAN".equals(Log.getCurrentLevel());

            if (!isScanMode) {
                Log.print("\n=== BINANCE ARBITRAGE BOT ===");
                Log.print("Log Level: " + Log.getCurrentLevel());
            } else {
                System.out.println("=== BINANCE ARBITRAGE BOT | SCAN MODE ===");
            }

            if (!isScanMode) {
                System.out.println("Credenciales API cargadas desde: " + apiConfigFile);
            }

            // Cargar credenciales API (api key, secret, modo testnet/mainnet)
            ApiConfig apiConfig = ConfigLoader.loadApiConfig(apiConfigFile);
            boolean isTestnet = apiConfig.isTestnet();
            String envName = NetworkEndpoints.getEnvironmentName(isTestnet);

            if (!isScanMode) {
                System.out.println("Entorno detectado: " + envName);
            }

            // Crear apiClient para llamadas REST a Binance
            BinanceApiClient apiClient = new BinanceApiClient(apiConfig);
            // Obtener todos los simbolos disponibles en Binance para validar pares
            Set<String> allBinanceSymbols = apiClient.getAllSymbols();

            // Force load de TradingSequence para evitar NoClassDefFoundError en threads secundarios
            try {
                Class.forName("com.arbitrage.model.TradingSequence");
            } catch (ClassNotFoundException e) {
                Log.error("MAIN", "Error pre-cargando TradingSequence: " + e.getMessage());
            }

            // === MODO TEST SERVER: ejecutar benchmarks y salir ===
            if (config.isTestserver()) {
                System.out.println();
                System.out.println("=== MODO TEST SERVER ACTIVADO ===");
                System.out.println("Ejecutando benchmarks de velocidad de ordenes...");

                if (!apiConfig.isTestnet()) {
                    System.out.println("ERROR: testserver=true requiere testnet=true en user.apiConfig");
                    System.out.println("El benchmark solo debe ejecutarse contra testnet.");
                    return;
                }

                display.showDashboard(config, false, isTestnet,
                        0, 0, 0, config.getLogLevel());

                OrderSpeedBenchmark benchmark = new OrderSpeedBenchmark(apiClient, config, apiConfig);
                benchmark.runAll();

                System.out.println();
                System.out.println("=== BENCHMARK COMPLETADO ===");
                return;
            }

            // === FASE 3: Carga de simbolos (monedas) ===
            // TESTNET: desde testnet.coins o generados por volumen desde la API
            // MAINNET: desde archivo USDTNORMAL2.coins
            List<String> coins = new ArrayList<>();
            String finalBaseCurrency = config.getBaseCurrency();

            // --- Rama TESTNET: cargar monedas desde testnet.coins o generarlas desde la API ---
            if (isTestnet) {
                String testnetCoinsFile = basePath + File.separator + "testnet.coins";
                File testnetFile = new File(testnetCoinsFile);

                // Si testnet.coins existe, cargarlo directamente
                if (testnetFile.exists()) {
                    // Cargar desde archivo existente
                    System.out.println("Cargando desde testnet.coins...");
                    List<String> pairList = ConfigLoader.loadCoins(testnetCoinsFile);
                    coins = new ArrayList<>();
                    for (String pair : pairList) {
                        if (pair.length() > 4 && !pair.equals("USDTUSDT") && !pair.equals("BTCBTC")
                                && !pair.equals("BNBBNB")) {
                            String base = pair.substring(pair.length() - 4);
                            if (base.equals("USDT") || base.equals("BTC") || base.equals("BNB")) {
                                String coin = pair.substring(0, pair.length() - 4);
                                if (!coin.isEmpty())
                                    coins.add(coin);
                            }
                        }
                    }
                    System.out.println("Cargadas " + coins.size() + " monedas desde testnet.coins");
                // Si no existe, generar las monedas desde la API de Binance por volumen de trading
                } else {
                    // Generar desde Binance API
                    System.out.println("testnet.coins no existe - Generando desde Binance...");

                    int requestedCoins = apiConfig.getTestnetCoins();
                    List<String> usdtSymbols = apiClient.getUsdtSymbolsByVolume(requestedCoins);
                    System.out.println("Obtenidos " + usdtSymbols.size() + " símbolos USDT por volumen");

                    // Filtrar stablecoins
                    Set<String> stableCoins = new HashSet<>(Set.of(
                            "USDT", "USDC", "FDUSD", "DAI", "TUSD",
                            "BUSD", "USDP", "PAXG", "EUR", "GBP"));

                    List<String> rawCoins = new ArrayList<>();
                    for (String symbol : usdtSymbols) {
                        if (symbol.endsWith("USDT") && !symbol.equals("USDTUSDT")) {
                            String coin = symbol.replace("USDT", "");
                            if (!stableCoins.contains(coin)) {
                                rawCoins.add(coin);
                            }
                        }
                    }

                    // Calcular límite de streams (n + n*(n-1)/2 <= 200)
                    int effectiveCoins = Math.min(requestedCoins, rawCoins.size());
                    while (effectiveCoins > 5 && effectiveCoins + (effectiveCoins * (effectiveCoins - 1)) / 2 > 200) {
                        effectiveCoins--;
                    }
                    effectiveCoins = Math.min(effectiveCoins, rawCoins.size());
                    coins = rawCoins.subList(0, effectiveCoins);
                    System.out.println("Filtradas a " + coins.size() + " monedas (streams: "
                            + (effectiveCoins + effectiveCoins * (effectiveCoins - 1) / 2) + ")");
                }
            // --- Rama MAINNET: cargar monedas desde el archivo .coins ---
            } else {
                // MAINNET: cargar desde archivo
                List<String> pairList = ConfigLoader.loadCoins(coinsFile);
                coins = new ArrayList<>();
                for (String pair : pairList) {
                    if (pair.endsWith("USDT") && !pair.equals("USDTUSDT")) {
                        String coin = pair.replace("USDT", "");
                        if (!coin.isEmpty()) {
                            coins.add(coin);
                        }
                    }
                }
                System.out.println("Cargadas " + coins.size() + " monedas desde " + coinsFile);
            }

            // === FASE 4: Validacion de monedas y construccion de triangulos ===
            int loadedCoins = coins.size();
            List<String> uniqueCoins = new ArrayList<>(new LinkedHashSet<>(coins));
            int duplicatesRemoved = loadedCoins - uniqueCoins.size();
            if (duplicatesRemoved > 0) {
                System.out.println("Monedas duplicadas eliminadas: " + duplicatesRemoved);
            }

            List<String> validCoins = new ArrayList<>();
            int notInBinance = 0;
            for (String coin : uniqueCoins) {
                boolean hasPair = false;
                for (String base : Arrays.asList("USDT", "BTC", "BNB")) {
                    if (allBinanceSymbols.contains(coin + base)) {
                        hasPair = true;
                        break;
                    }
                }
                if (hasPair) {
                    validCoins.add(coin);
                } else {
                    notInBinance++;
                }
            }
            coins = validCoins;
            System.out.println("Monedas válidas: " + coins.size() + " | Duplicadas: " + duplicatesRemoved + " | No existen en Binance: " + notInBinance);

            // Intentar construir triangulos con cada moneda base (USDT > BTC > BNB)
            // Si una base no genera triangulos, probar con la siguiente
            List<String> baseCurrencies = Arrays.asList("USDT", "BTC", "BNB");
            List<Triangle> triangles = new ArrayList<>();

            for (String base : baseCurrencies) {
                TriangleCalculator calculator = new TriangleCalculator(base);
                triangles = calculator.buildTriangles(coins, allBinanceSymbols);

                if (!triangles.isEmpty()) {
                    finalBaseCurrency = base;
                    System.out.println("Triángulos encontrados con base " + base + ": " + triangles.size());
                    break;
                } else {
                    System.out.println("Sin triángulos con base " + base + ", intentando siguiente...");
                }
            }

            // Fallback extremo: si ninguna base funciona, recargar monedas desde el archivo .coins original
            if (triangles.isEmpty()) {
                System.out.println("WARNING: Sin triángulos con monedas generadas - usando archivo mainnet");
                List<String> pairList = ConfigLoader.loadCoins(coinsFile);
                coins = new ArrayList<>();
                for (String pair : pairList) {
                    if (pair.endsWith("USDT") && !pair.equals("USDTUSDT")) {
                        String coin = pair.replace("USDT", "");
                        if (!coin.isEmpty())
                            coins.add(coin);
                    }
                }
                // Intentar de nuevo
                for (String base : baseCurrencies) {
                    TriangleCalculator calculator = new TriangleCalculator(base);
                    triangles = calculator.buildTriangles(coins, allBinanceSymbols);
                    if (!triangles.isEmpty()) {
                        finalBaseCurrency = base;
                        System.out.println(
                                "Triángulos encontrados con base " + base + " (fallback): " + triangles.size());
                        break;
                    }
                }
            }

            if (!isScanMode) {
                System.out.println("DEBUG: baseCurrency=" + finalBaseCurrency);
                System.out.println("Triangulos construidos: " + triangles.size());
            }

            java.util.Set<String> triangularSymbols = new java.util.HashSet<>();
            for (com.arbitrage.model.Triangle t : triangles) {
                triangularSymbols.add(t.getSymbol1());
                triangularSymbols.add(t.getSymbol2());
                triangularSymbols.add(t.getSymbol3());
            }
            apiClient.loadExchangeInfoFiltersForSymbols(triangularSymbols);

            // === FASE 5: Persistencia de configuracion ===
            // Guardar testnet.coins con la base que funcionó para usos futuros
            if (isTestnet && !coins.isEmpty()) {
                String testnetCoinsFile = basePath + File.separator + "testnet.coins";
                try {
                    File file = new File(testnetCoinsFile);
                    if (file.exists()) {
                        file.delete();
                    }
                    PrintWriter writer = new PrintWriter(new FileWriter(testnetCoinsFile));
                    for (String coin : coins) {
                        writer.println(coin);
                    }
                    writer.close();
                    System.out.println(
                            "Monedas testnet guardadas en: " + new File(testnetCoinsFile).getName() + " (base: " + finalBaseCurrency + ")");
                } catch (IOException e) {
                    System.out.println("Error guardando monedas testnet: " + e.getMessage());
                }
            }

            // Guardar lista de triangulos detectados en triangulos.txt para depuracion
            String trianglesFile = basePath + File.separator + "triangulos.txt";
            try {
                File file = new File(trianglesFile);
                // Si existe, borrar primero
                if (file.exists()) {
                    file.delete();
                }
                // Crear y escribir nuevos triangulos
                PrintWriter writer = new PrintWriter(new FileWriter(trianglesFile));
                writer.println("# Triangulos de arbitraje - Generado: " + java.time.LocalDateTime.now());
                writer.println("# Moneda base: " + finalBaseCurrency);
                writer.println("# Total triangulos: " + triangles.size());
                writer.println();
                for (Triangle t : triangles) {
                    writer.println(t.getId());
                }
                writer.close();
                System.out.println("Triangulos guardados en: " + new File(trianglesFile).getName());
            } catch (IOException e) {
                System.out.println("Error guardando triangulos: " + e.getMessage());
            }

            if (!isScanMode) {
                System.out.println(
                        "Construidos " + triangles.size() + " triangulos para moneda base: " + finalBaseCurrency);
                System.out.println("Conectando a WebSocket de Binance " + envName + "...");
            }

            // === FASE 6: Conexion WebSocket ===
            // Handler que actualiza el mapa de precios cuando llegan tickers desde WebSocket
            PriceUpdateHandler priceHandler = new PriceUpdateHandler(priceMap);
            // Cliente WebSocket que se conecta a los streams de Binance
            BinanceWebSocketClient wsClient = new BinanceWebSocketClient(apiConfig, priceHandler);

            // Construir lista de streams solo con los simbolos de los triangulos validos
            Set<String> streamSymbols = new HashSet<>();

            for (Triangle t : triangles) {
                streamSymbols.add(t.getSymbol1());
                streamSymbols.add(t.getSymbol2());
                streamSymbols.add(t.getSymbol3());
            }

            List<String> streamsList = new ArrayList<>(streamSymbols);

            if (!isScanMode) {
                System.out.println("Suscribiendo a " + streamsList.size() + " streams validos...");
            }

            // Conectar y suscribirse a los streams de precios
            wsClient.connectAndSubscribe(streamsList);
            // Esperar 2 segundos para que el WebSocket establezca la conexion
            Thread.sleep(2000);
            boolean connected = wsClient.isConnected();

            if (!isScanMode) {
                System.out.println("Conectado al stream de Binance! WebSocket abierto");
            }

            display.setConnectionStatus(connected);
            display.setTestnet(isTestnet);

            // === FASE 7: Sincronizacion de wallet y estadisticas ===
            // Gestor que sincroniza saldos USDT y BNB periodicamente desde la API
            WalletSyncManager walletSync = new WalletSyncManager(apiClient, config.getWalletSyncIntervalMs());
            display.setWalletSyncManager(walletSync);
            display.updateOpportunityCount(triangles.size());

            // Validar balance USDT obtenido de Binance
            double usdtBalance = walletSync.getUsdtBalance();
            if (usdtBalance <= 0) {
                Log.error("MAIN", "No se pudo obtener balance USDT de Binance. Verifica API, credenciales y conexion.");
                Log.error("MAIN", "El bot no puede iniciar sin un balance USDT valido.");
                System.exit(1);
            }

            Log.info("MAIN", "Balance USDT inicial (initialInvestment): " + String.format("%.2f", usdtBalance));

            // Gestor de estadisticas que persiste el rendimiento del bot en archivo .stats
            StatsManager statsManager = new StatsManager(basePath, statsFile, finalBaseCurrency, usdtBalance);
            display.showDashboard(config, connected, isTestnet,
                    walletSync.getUsdtBalance(), walletSync.getBnbBalance(),
                    walletSync.getBnbPrice(), config.getLogLevel());

            if (!isScanMode) {
                System.out.println();
                System.out.println("WebSocket conectado. Suscrito a " + coins.size() + " streams.");

                String streams = coins.stream()
                        .map(c -> c.toLowerCase() + "@bookTicker")
                        .collect(Collectors.joining("/"));
                String wsEndpoint = NetworkEndpoints.buildWebSocketUrl(isTestnet, streams);
                String tradeMode = isTestnet ? "TESTNET"
                        : (config.isRealorder() ? "MAINNET-LIVE" : "MAINNET-SIMULATED");

                System.out.println(
                        "Endpoint WebSocket: " + wsEndpoint.substring(0, Math.min(60, wsEndpoint.length())) + "...");
                System.out.println("Modo: " + tradeMode + " | Balance por trade: " + config.getBalancePerTrade() + " "
                        + config.getBaseCurrency() + " | Min profit: " + config.getMinProfit() + "%");
                System.out.println("Motor de arbitraje iniciado. Buscando oportunidades...");
            }

            // === FASE 8: Gestor de persistencia de secuencias ===
            // SequenceFileManager guarda el estado de las secuencias de ordenes en archivos JSON
            com.arbitrage.persistence.SequenceFileManager seqFileManager = new com.arbitrage.persistence.SequenceFileManager(
                    basePath);
            if (config.isRealorder()) {
                Log.info("Sequence persistence enabled for real orders");
            }

            // === FASE 9: Ejecutor de ordenes ===
            // ExecutionEngine recibe oportunidades del motor y ejecuta las 3 ordenes de cada triangulo
            RiskManager riskManager = new RiskManager(config);
            RepricingEngine repricingEngine = new RepricingEngine(apiClient, priceMap);
            FillTracker fillTracker = new FillTracker(seqFileManager);

            ExecutionEngine executionEngine = new ExecutionEngine(
                    config,
                    apiClient,
                    seqFileManager,
                    priceMap,
                    riskManager,
                    repricingEngine,
                    fillTracker,
                    statsManager
            );

            // Configurar display de secuencias: muestra en consola el estado de cada orden en tiempo real
            executionEngine.setSequenceDisplay(new ExecutionEngine.SequenceDisplay() {

                class OrderState {
                    String symbol = "";
                    String side = "";
                    double qty = 0;
                    double price = 0;
                    String status = "WAITING";
                    long elapsedMs = 0;
                    String orderId = "------";
                    String orderType = "";
                    long sentTime = 0;
                }

                class SeqState {
                    int seqId;
                    long startTime;
                    OrderState[] orders = new OrderState[3];
                    double profitPct;
                    boolean started;
                    boolean live;
                    String estadoTag = null;
                    boolean estadoTagPrinted = false;
                    {
                        for (int i = 0; i < 3; i++)
                            orders[i] = new OrderState();
                    }
                }

                private final java.util.Map<Integer, SeqState> seqStates = new java.util.HashMap<>();

                private String fmtQty(double qty) {
                    String s = String.format("%.8f", qty);
                    return s.startsWith("0.") ? s.substring(1) : s;
                }

                private String fmtTime(long ts) {
                    if (ts <= 0)
                        return "--";
                    long now = System.currentTimeMillis();
                    long elapsed = now - ts;
                    return elapsed + "ms";
                }

                private String colorForStatus(String status) {
                    switch (status) {
                        case "FILLED":
                            return "\u001B[32m";
                        case "OPENED":
                            return "\u001B[36m";
                        case "CANCELED":
                        case "REJECTED":
                        case "EXPIRED":
                        case "ERROR":
                            return "\u001B[31m";
                        default:
                            return "\u001B[37m";
                    }
                }

                private String formatOrderLine(OrderState o, int opNum) {
                    String qtyStr = fmtQty(o.qty);
                    String priceStr = String.format("%.8f", o.price);
                    String elapsedStr = fmtTime(o.sentTime);
                    String orderIdStr = ("------".equals(o.orderId) || o.orderId == null || o.orderId.isEmpty())
                            ? "------"
                            : o.orderId;
                    String statusColor = colorForStatus(o.status);
                    return statusColor + String.format(
                            "  [Op%d] %-8s %-5s Qty:%12s Price:%14s Status:%-9s ElapsedTime:%s OrderId %-10s %s",
                            opNum, o.symbol, o.side, qtyStr, priceStr, o.status, elapsedStr, orderIdStr, o.orderType)
                            + "\u001B[37m";
                }

                private void printBlock(SeqState s) {
                    java.time.LocalDateTime dt = java.time.LocalDateTime.ofInstant(
                            java.time.Instant.ofEpochMilli(s.startTime), java.time.ZoneId.systemDefault());
                    String timeStr = dt.format(java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss"));
                    String mode = s.live ? "LIVE" : "SIMULATED";
                    String estadoTagStr = (!s.estadoTagPrinted && s.estadoTag != null)
                            ? " (" + s.estadoTag + ")"
                            : "";
                    System.out.println();
                    System.out.println("Seq #" + s.seqId + " --> " + timeStr + " (" + mode + ")" + estadoTagStr);
                    for (int i = 0; i < 3; i++) {
                        System.out.println(formatOrderLine(s.orders[i], i + 1));
                    }
                    String profitColor = s.profitPct > 0 ? "\u001B[32m"
                            : (s.profitPct < 0 ? "\u001B[33m" : "\u001B[37m");
                    System.out.println("profit= " + profitColor + String.format("%.4f", s.profitPct) + "%\u001B[37m");
                    if (!s.estadoTagPrinted && s.estadoTag != null) {
                        s.estadoTagPrinted = true;
                    }
                }

                @Override
                public void showStart(int sequenceId, long timestamp, double profitPct, boolean live) {
                    SeqState s = seqStates.computeIfAbsent(sequenceId, k -> {
                        SeqState ns = new SeqState();
                        ns.seqId = sequenceId;
                        ns.started = false;
                        return ns;
                    });
                    s.startTime = timestamp;
                    s.profitPct = profitPct;
                    s.live = live;
                }

                @Override
                public void showOrderPending(int seqId, int opNum, String symbol, String side, double qty, double price,
                        String orderType) {
                }

                @Override
                public void showOrderFilled(int seqId, int opNum, OrderResult r) {
                    String st = r.getStatus();
                    if ("OPEN".equals(st) || "NEW".equals(st))
                        st = "OPENED";
                    showOrderStatus(seqId, opNum, r.getSymbol(), r.getSide(), r.getQuantity(), r.getPrice(),
                            st, r.getElapsedTime(), r.getOrderId(), r.getOrderType());
                }

                @Override
                public void showEnd(int sequenceId, boolean success, double profitPct) {
                }

                @Override
                public void printSequenceAtomic(int sequenceId, long timestamp, boolean live,
                        java.util.List<OrderResult> orders, double profitPct) {
                }

                @Override
                public void showSequenceEstado(int seqId, String estado) {
                    SeqState s = seqStates.get(seqId);
                    if (s == null)
                        return;
                    s.estadoTag = estado;
                    s.estadoTagPrinted = false;
                }

                @Override
                public void showOrderStatus(int seqId, int opNum, String symbol, String side,
                        double qty, double price, String status, long elapsedMs,
                        String orderId, String orderType) {

                    SeqState s = seqStates.computeIfAbsent(seqId, k -> {
                        SeqState ns = new SeqState();
                        ns.seqId = seqId;
                        ns.started = false;
                        return ns;
                    });

                    int idx = opNum - 1;

                    boolean statusChanged = !s.orders[idx].status.equals(status);
                    boolean orderIdChanged = !s.orders[idx].orderId.equals(orderId);
                    boolean dataChanged = statusChanged || orderIdChanged ||
                            !s.orders[idx].symbol.equals(symbol) ||
                            Math.abs(s.orders[idx].qty - qty) > 1e-10 ||
                            Math.abs(s.orders[idx].price - price) > 1e-10;

                    if (!dataChanged)
                        return;

                    s.orders[idx].symbol = symbol;
                    s.orders[idx].side = side;
                    s.orders[idx].qty = qty;
                    s.orders[idx].price = price;
                    s.orders[idx].status = status;
                    s.orders[idx].orderId = orderId;
                    s.orders[idx].orderType = orderType;

                    if (!status.equals("WAITING")) {
                        long now = System.currentTimeMillis();
                        if (statusChanged) {
                            s.orders[idx].sentTime = now - elapsedMs;
                        }
                    }

                    printBlock(s);
                }
            });

            // === FASE 10: Recuperacion de secuencias pendientes ===
            // SequenceRecoveryManager carga secuencias no finalizadas (por crash previo) y las re-ejecuta
            if (seqFileManager != null) {
                com.arbitrage.persistence.SequenceRecoveryManager recovery = new com.arbitrage.persistence.SequenceRecoveryManager(
                        apiClient, seqFileManager, executionEngine, config, statsManager);
                java.util.List<com.arbitrage.model.TradingSequence> pending = recovery.loadAndRecoverSequences();
                
                if (!pending.isEmpty()) {
                    int maxSeqId = pending.stream()
                        .mapToInt(com.arbitrage.model.TradingSequence::getSeqId)
                        .max()
                        .orElse(0);
                    com.arbitrage.model.TradingSequence.setCounter(maxSeqId);
                    Log.info("RECOVERY", "SeqId counter set to " + maxSeqId + ", next sequence will be #" + (maxSeqId + 1));

                    executionEngine.recoverPendingSequences(pending);
                }
            }

            // === FASE 11: Inicio del motor de arbitraje ===
            // El motor escanea triangulos cada 100ms usando precios del WebSocket
            // Cuando encuentra una oportunidad profitable, invoca el callback que ejecuta las ordenes
            ArbitrageEngine engine = new ArbitrageEngine(
                    config,
                    triangles,
                    priceMap,
                    opportunity -> executionEngine.execute(opportunity));
            engine.start();

            // Iniciar sincronizacion periodica de saldos de wallet
            walletSync.start();

            // === FASE 12: Shutdown hook ===
            // Registra un callback que se ejecuta al detener el proceso (Ctrl+C, kill)
            // Asegura que estadisticas, motor, wallet, WebSocket y ejecutor se cierren ordenadamente
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                Log.print("=== SHUTDOWN ===");
                statsManager.flush();
                engine.stop();
                walletSync.stop();
                wsClient.close();
                executionEngine.shutdown();
                apiClient.shutdown();
            }));

            // Mantener el hilo principal vivo hasta que el proceso sea interrumpido
            Thread.currentThread().join();

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (IOException e) {
            logger.error("Error al cargar configuracion", e);
        }
    }
}