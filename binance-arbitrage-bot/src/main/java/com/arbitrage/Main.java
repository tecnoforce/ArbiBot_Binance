package com.arbitrage;

import com.arbitrage.config.ApiConfig;
import com.arbitrage.config.AppConfig;
import com.arbitrage.config.ConfigLoader;
import com.arbitrage.config.NetworkEndpoints;
import com.arbitrage.display.ConsoleDisplay;
import com.arbitrage.engine.ArbitrageEngine;
import com.arbitrage.engine.TriangleCalculator;
import com.arbitrage.model.OrderResult;
import com.arbitrage.model.Ticker;
import com.arbitrage.model.Triangle;
import com.arbitrage.trading.BinanceApiClient;
import com.arbitrage.trading.OrderExecutor;
import com.arbitrage.util.Log;
import com.arbitrage.websocket.BinanceWebSocketClient;
import com.arbitrage.websocket.PriceUpdateHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

public class Main {
    private static final Logger logger = LoggerFactory.getLogger(Main.class);

    public static void main(String[] args) {
        String configFile = "USDTNORMAL2.config";
        String apiConfigFile = "user.apiConfig";
        String coinsFile = "USDTNORMAL2.coins";

        for (int i = 0; i < args.length; i++) {
            if (args[i].equals("--config") && i + 1 < args.length) {
                configFile = args[i + 1];
            }
        }

        System.out.println("=== VALIDANDO ARCHIVOS DE CONFIGURACION ===");
        String basePath = System.getProperty("user.dir");
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

        ConsoleDisplay display = new ConsoleDisplay();
        ConcurrentHashMap<String, Ticker> priceMap = new ConcurrentHashMap<>();

        try {
            AppConfig config = ConfigLoader.loadAppConfig(configFile);

            String logLevel = config.getLogLevel();
            Log.init((logLevel != null && !logLevel.isEmpty()) ? logLevel : "INFO");
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
            
            ApiConfig apiConfig = ConfigLoader.loadApiConfig(apiConfigFile);
            boolean isTestnet = apiConfig.isTestnet();
            String envName = NetworkEndpoints.getEnvironmentName(isTestnet);
            
            if (!isScanMode) {
                System.out.println("Entorno detectado: " + envName);
            }

            // Crear apiClient para llamadas API
            BinanceApiClient apiClient = new BinanceApiClient(apiConfig);
            Set<String> allBinanceSymbols = apiClient.getAllSymbols();
            
            // Cargar simbolos:不同的 lógica para TESTNET vs MAINNET
            List<String> coins = new ArrayList<>();
            String finalBaseCurrency = config.getBaseCurrency();
            
            if (isTestnet) {
                String testnetCoinsFile = basePath + File.separator + "testnet.coins";
                File testnetFile = new File(testnetCoinsFile);
                
                if (testnetFile.exists()) {
                    // Cargar desde archivo existente
                    System.out.println("Cargando desde testnet.coins...");
                    List<String> pairList = ConfigLoader.loadCoins(testnetCoinsFile);
                    coins = new ArrayList<>();
                    for (String pair : pairList) {
                        if (pair.length() > 4 && !pair.equals("USDTUSDT") && !pair.equals("BTCBTC") && !pair.equals("BNBBNB")) {
                            String base = pair.substring(pair.length() - 4);
                            if (base.equals("USDT") || base.equals("BTC") || base.equals("BNB")) {
                                String coin = pair.substring(0, pair.length() - 4);
                                if (!coin.isEmpty()) coins.add(coin);
                            }
                        }
                    }
                    System.out.println("Cargadas " + coins.size() + " monedas desde testnet.coins");
                } else {
                    // Generar desde Binance API
                    System.out.println("testnet.coins no existe - Generando desde Binance...");
                    
                    int requestedCoins = apiConfig.getTestnetCoins();
                    List<String> usdtSymbols = apiClient.getUsdtSymbolsByVolume(requestedCoins);
                    System.out.println("Obtenidos " + usdtSymbols.size() + " símbolos USDT por volumen");
                    
                    // Filtrar stablecoins
                    Set<String> stableCoins = new HashSet<>(Set.of(
                        "USDT", "USDC", "FDUSD", "DAI", "TUSD", 
                        "BUSD", "USDP", "PAXG", "EUR", "GBP"
                    ));
                    
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
                    System.out.println("Filtradas a " + coins.size() + " monedas (streams: " + (effectiveCoins + effectiveCoins * (effectiveCoins - 1) / 2) + ")");
                }
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
            
            // Validar que las monedas tengan pares existentes con las diferentes bases
            List<String> validCoins = new ArrayList<>();
            for (String coin : coins) {
                boolean hasPair = false;
                for (String base : Arrays.asList("USDT", "BTC", "BNB")) {
                    if (allBinanceSymbols.contains(coin + base)) {
                        hasPair = true;
                        break;
                    }
                }
                if (hasPair) validCoins.add(coin);
            }
            coins = validCoins;
            System.out.println("Monedas válidas después de validar pares: " + coins.size());
            
            // Fallback: intentar con USDT, luego BTC, luego BNB
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
            
            // Si ninguna base funciona, usar archivo de mainnet
            if (triangles.isEmpty()) {
                System.out.println("WARNING: Sin triángulos con monedas generadas - usando archivo mainnet");
                List<String> pairList = ConfigLoader.loadCoins(coinsFile);
                coins = new ArrayList<>();
                for (String pair : pairList) {
                    if (pair.endsWith("USDT") && !pair.equals("USDTUSDT")) {
                        String coin = pair.replace("USDT", "");
                        if (!coin.isEmpty()) coins.add(coin);
                    }
                }
                // Intentar de nuevo
                for (String base : baseCurrencies) {
                    TriangleCalculator calculator = new TriangleCalculator(base);
                    triangles = calculator.buildTriangles(coins, allBinanceSymbols);
                    if (!triangles.isEmpty()) {
                        finalBaseCurrency = base;
                        System.out.println("Triángulos encontrados con base " + base + " (fallback): " + triangles.size());
                        break;
                    }
                }
            }
            
            if (!isScanMode) {
                System.out.println("DEBUG: baseCurrency=" + finalBaseCurrency);
                System.out.println("Triangulos construidos: " + triangles.size());
            }

            // Guardar testnet.coins con la base que funcionó
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
                    System.out.println("Monedas testnet guardadas en: " + testnetCoinsFile + " (base: " + finalBaseCurrency + ")");
                } catch (IOException e) {
                    System.out.println("Error guardando monedas testnet: " + e.getMessage());
                }
            }
            
            // Guardar triangulos en archivo triangulos.txt
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
                System.out.println("Triangulos guardados en: " + trianglesFile);
            } catch (IOException e) {
                System.out.println("Error guardando triangulos: " + e.getMessage());
            }

            if (!isScanMode) {
                System.out.println("Construidos " + triangles.size() + " triangulos para moneda base: " + finalBaseCurrency);
                System.out.println("Conectando a WebSocket de Binance " + envName + "...");
            }

            PriceUpdateHandler priceHandler = new PriceUpdateHandler(priceMap);
            BinanceWebSocketClient wsClient = new BinanceWebSocketClient(apiConfig, priceHandler);
            
            // Construir lista de streams solo con simbolos de triangulos validos
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
            
            wsClient.connectAndSubscribe(streamsList);
            Thread.sleep(2000);
            boolean connected = wsClient.isConnected();
            
            if (!isScanMode) {
                System.out.println("Conectado al stream de Binance! WebSocket abierto");
            }
            
            display.setConnectionStatus(connected);
            display.setTestnet(isTestnet);

            // apiClient ya fue creado arriba para cargar simbolos
            double[] balances = apiClient.getAccountBalances();
            double usdtBalance = balances[0];
            double bnbBalance = balances[1];
            double bnbPrice = balances[2];

            display.setBalances(usdtBalance, bnbBalance, bnbPrice);
            display.updateOpportunityCount(triangles.size());
            display.showDashboard(config, connected, isTestnet, usdtBalance, bnbBalance, bnbPrice, config.getLogLevel());

            if (!isScanMode) {
                System.out.println();
                System.out.println("WebSocket conectado. Suscrito a " + coins.size() + " streams.");
                
                String streams = coins.stream()
                    .map(c -> c.toLowerCase() + "@bookTicker")
                    .collect(Collectors.joining("/"));
                String wsEndpoint = NetworkEndpoints.buildWebSocketUrl(isTestnet, streams);
                String tradeMode = isTestnet ? "TESTNET" : (config.isRealorder() ? "MAINNET-LIVE" : "MAINNET-SIMULATED");
                
                System.out.println("Endpoint WebSocket: " + wsEndpoint.substring(0, Math.min(60, wsEndpoint.length())) + "...");
                System.out.println("Modo: " + tradeMode + " | Balance por trade: " + config.getBalancePerTrade() + " " + config.getBaseCurrency() + " | Min profit: " + config.getMinProfit() + "%");
                System.out.println("Motor de arbitraje iniciado. Buscando oportunidades...");
            }

            // Crear SequenceFileManager solo para ordenes reales
            com.arbitrage.persistence.SequenceFileManager seqFileManager = null;
            if (config.isRealorder()) {
                seqFileManager = new com.arbitrage.persistence.SequenceFileManager(basePath);
                Log.info("Sequence persistence enabled for real orders");
            }

            OrderExecutor orderExecutor = new OrderExecutor(config, apiClient, seqFileManager);
            orderExecutor.setSequenceDisplay(new OrderExecutor.SequenceDisplay() {
                @Override
                public void showStart(int sequenceId, long timestamp, double profitPct, boolean live) {
                    display.showStart(sequenceId, timestamp, profitPct, live);
                }
                
                @Override
                public void showOrderPending(int opNum, String symbol, String side, double qty, double price, String orderType) {
                    display.showOrderPending(opNum, symbol, side, qty, price, orderType);
                }
                
                @Override
                public void showOrderFilled(OrderResult r) {
                    display.showOrderFilled(r);
                }
                
                @Override
                public void showEnd(int sequenceId, boolean success, double profitPct) {
                    display.showEnd(sequenceId, success, profitPct);
                }

                @Override
                public void printSequenceAtomic(int sequenceId, long timestamp, boolean live, java.util.List<OrderResult> orders, double profitPct) {
                    display.printSequenceAtomic(sequenceId, timestamp, live, orders, profitPct);
                }
            });

            ArbitrageEngine engine = new ArbitrageEngine(
                config,
                triangles,
                priceMap,
                opportunity -> orderExecutor.execute(opportunity)
            );
            engine.start();

            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                Log.print("=== SHUTDOWN ===");
                engine.stop();
                wsClient.close();
                orderExecutor.shutdown();
                apiClient.shutdown();
            }));

            Thread.currentThread().join();

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (IOException e) {
            logger.error("Error al cargar configuracion", e);
        }
    }
}