package com.arbitrage.engine;

import com.arbitrage.config.AppConfig;
import com.arbitrage.model.ArbitrageOpportunity;
import com.arbitrage.model.Ticker;
import com.arbitrage.model.Triangle;
import com.arbitrage.util.Log;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * Motor de arbitraje triangular.
 * Escanea precios cada 100ms y busca oportunidades de arbitraje
 * entre tres pares de criptomonedas en un triangulo.
 * 
 * Ejemplo de triangulo: BTC->ETH->USDT -> BTC
 *   1. Vender BTC por USDT (BTCUSDT)
 *   2. Comprar ETH con USDT (ETHUSDT)
 *   3. Vender ETH por BTC (ETHBTC)
 *   Si el resultado es > 0, hay oportunidad de arbitraje.
 */
public class ArbitrageEngine {
    private static final String TAG = "Engine";

    // =====================================================================
    // DEPENDENCIAS Y CONFIGURACION
    // =====================================================================
    private final AppConfig config;                              // Configuracion
    private final ProfitCalculator profitCalculator;            // Calcula profit
    
    // =====================================================================
    // EJECUTOR DE TAREAS
    // =====================================================================
    private final ScheduledExecutorService scheduler;          // Scheduler para escaneo
    
    // =====================================================================
    // DATOS COMPARTIDOS
    // =====================================================================
    private final ConcurrentHashMap<String, Ticker> priceMap;    // Precios en tiempo real
    private final List<Triangle> triangles;                   // Triangulos a evaluar
    private final ConcurrentHashMap<String, Double> lastProfitByTriangle; // Profit anterior
    private final Consumer<ArbitrageOpportunity> opportunityConsumer; // Callback
    
    // =====================================================================
    // ESTADISTICAS
    // =====================================================================
    private final AtomicInteger scanCount;        // Contador de escaneos
    private final AtomicInteger opportunityCount; // Contador de oportunidades
    private volatile boolean running = false;   // Flag de control

    /**
     * Constructor del motor de arbitraje.
     * @param config Configuracion de la aplicacion
     * @param triangles Lista de triangulos validados
     * @param priceMap Mapa de precios (compartido con WebSocket)
     * @param opportunityConsumer Callback cuando encuentra oportunidad
     */
    public ArbitrageEngine(
            AppConfig config,
            List<Triangle> triangles,
            ConcurrentHashMap<String, Ticker> priceMap,
            Consumer<ArbitrageOpportunity> opportunityConsumer
    ) {
        this.config = config;
        this.priceMap = priceMap;
        
        // Usar triangulos directamente (ya validados en Main)
        this.triangles = triangles;
        
        // Inicializa calculadora de profit
        this.profitCalculator = new ProfitCalculator(config);
        
        // Mapa para recordar ultimo profit (evitar duplicados)
        this.lastProfitByTriangle = new ConcurrentHashMap<>();
        
        // Callback para ejecutar oportunidades
        this.opportunityConsumer = opportunityConsumer;
        
        // Pool de threads - uno por nucleo
        this.scheduler = Executors.newScheduledThreadPool(config.getCores());
        
        // Contadores atomicos
        this.scanCount = new AtomicInteger(0);
        this.opportunityCount = new AtomicInteger(0);

        Log.info("Inicializado con " + triangles.size() + " triangulos");
    }

    /**
     * Inicia el escaneo automatico cada 100ms.
     * Programa una tarea que se ejecuta cada 100ms:
     *   1. Itera sobre todos los triangulos en paralelo
     *   2. Para cada triangulo, verifica si hay oportunidad
     *   3. Si hay oportunidad nueva, ejecuta el callback
     */
    public void start() {
        running = true;
        
        // Solo muestra mensaje de inicio si NO esta en modo SCAN silencioso
        String currentLevel = Log.getCurrentLevel();
        if (!"SCAN".equals(currentLevel)) {
            Log.info(TAG, "Iniciando escaneo cada 100ms...");
        }
        
        // Programa tarea cada 100ms
        scheduler.scheduleAtFixedRate(() -> {
            if (!running) return;
            
            // Incrementa contador de escaneos
            scanCount.incrementAndGet();
            
            try {
                // Escanea todos los triangulos en paralelo (parallelStream)
                triangles.parallelStream().forEach(this::checkTriangle);
            } catch (Exception e) {
                Log.debug(TAG, "Error en scan: " + e.getMessage());
            }
        }, 0, 100, TimeUnit.MILLISECONDS);
    }

    /**
     * Detiene el motor de arbitraje.
     * Cancela el scheduler y espera terminacion.
     */
    public void stop() {
        running = false;
        Log.info(TAG, "Detenido. Scans totales: " + scanCount.get());
        scheduler.shutdown();
    }

    /**
     * Verifica si un triangulo tiene oportunidad de arbitraje.
     * @param triangle Triangulo a evaluar
     */
    private void checkTriangle(Triangle triangle) {
        try {
            // Obtiene los tres tickers del triangulo
            Ticker t1 = priceMap.get(triangle.getSymbol1());
            Ticker t2 = priceMap.get(triangle.getSymbol2());
            Ticker t3 = priceMap.get(triangle.getSymbol3());

            // Si falta alguno, sale
            if (t1 == null || t2 == null || t3 == null) {
                return;
            }

            // Calcula profit del triangulo
            ArbitrageOpportunity opportunity = profitCalculator.calculate(
                triangle,
                config.getBalancePerTrade(),
                t1, t2, t3
            );

            // Valida y emite si es nueva
            if (opportunity != null && isOpportunityValid(opportunity)) {
                emitIfNew(opportunity);
            }
        } catch (Exception e) {
            Log.debug(TAG, "Error calculando triangulo " + triangle.getId() + ": " + e.getMessage());
        }
    }

    /**
     * Valida si una oportunidad esta dentro de rangos esperados.
     * @param opportunity Oportunidad a validar
     * @return true si es valida
     */
    private boolean isOpportunityValid(ArbitrageOpportunity opportunity) {
        double profitPct = opportunity.getProfitPct();
        // Debe estar entre -1% (perdida maxima) y maxProfit
        return profitPct >= -1.0 && profitPct <= config.getMaxProfit();
    }

    /**
     * Emite oportunidad solo si el profit cambio significativamente.
     * Usa unthreshold para evitar spam de oportunidades similares.
     * @param opportunity Oportunidad a evaluar
     */
    private void emitIfNew(ArbitrageOpportunity opportunity) {
        String triangleId = opportunity.getTriangle().getId();
        double profitPct = opportunity.getProfitPct();
        
        // Solo ejecutar si profit >= minProfit configurado
        if (profitPct < config.getMinProfit()) {
            return;
        }
        
        // Obtiene profit anterior de este triangulo
        Double lastProfit = lastProfitByTriangle.get(triangleId);

        // Si es nuevo o cambio > 0.01%, lo reporta
        if (lastProfit == null || Math.abs(profitPct - lastProfit) > 0.0001) {
            // Actualiza profit
            lastProfitByTriangle.put(triangleId, profitPct);
            
            // Incrementa contador
            opportunityCount.incrementAndGet();
            
            // Logea la oportunidad
            String currentLevel = Log.getCurrentLevel();
            String prefix = profitPct > 0 ? "[+]" : "[-]";
            String logMsg = "OPORTUNIDAD: " + prefix + " " + triangleId + " | Profit: " + String.format("%.4f", profitPct) + "%";
            
            if ("SCAN".equals(currentLevel)) {
                Log.scan(TAG, logMsg);
            } else {
                Log.info(TAG, logMsg);
            }
            
            // Ejecuta la oportunidad (ya filtrada por profit >= minProfit)
            opportunityConsumer.accept(opportunity);
        }
    }

    // =====================================================================
    // GETTERS PARA ESTADISTICAS
    // =====================================================================
    public List<Triangle> getTriangles() {
        return triangles;
    }

    public int getTriangleCount() {
        return triangles.size();
    }

    public int getScanCount() {
        return scanCount.get();
    }

    public int getOpportunityCount() {
        return opportunityCount.get();
    }
}