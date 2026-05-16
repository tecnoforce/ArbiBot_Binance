package com.arbitrage.module;

import com.arbitrage.config.AppConfig;
import com.arbitrage.engine.PrecisionAdjuster;
import com.arbitrage.model.ArbitrageOpportunity;
import com.arbitrage.model.Ticker;
import com.arbitrage.model.Triangle;
import com.arbitrage.util.Log;
import lombok.Builder;
import lombok.Data;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * ArbitrageDetector - Motor de detección de oportunidades de arbitraje.
 *
 * Responsabilidades:
 * - Escanear periódicamente todos los triángulos calculados
 * - Calcular profit de cada triángulo usando ProfitCalculator
 * - Filtrar oportunidades válidas (rangos de profit)
 * - Emitir oportunidades al consumidor (OrderExecutor/ExecutionEngine)
 * - Mantener historial de últimos profits para evitar duplicados
 * - Proveer estadísticas de escaneo (métricas de rendimiento)
 *
 * Integración: Recibe precios de MarketDataEngine via priceMap ConcurrentHashMap,
 *              emite oportunidades a través del callback opportunityConsumer,
 *              usado por ArbitrageEngine en el loop principal.
 */
public class ArbitrageDetector {
    /** Tag para logging */
    private static final String TAG = "ArbDetect";

    /** Configuración global de la aplicación */
    private final AppConfig config;
    /** Calculador de profits con fees y slippage */
    private final ProfitCalculator profitCalculator;
    /** Ajustador de precisión para cantidades y precios */
    private final PrecisionAdjuster precisionAdjuster;
    /** Mapa compartido de precios en tiempo real (symbol → Ticker) */
    private final ConcurrentHashMap<String, Ticker> priceMap;
    /** Lista de triángulos a escanear */
    private final List<Triangle> triangles;
    /** Último profit registrado por triángulo para evitar re-emisiones */
    private final ConcurrentHashMap<String, Double> lastProfitByTriangle;
    /** Callback para emitir oportunidades detectadas */
    private final Consumer<ArbitrageOpportunity> opportunityConsumer;

    /** Scheduler para ejecución periódica del escaneo */
    private ScheduledExecutorService scheduler;
    /** Flag de estado: true si el detector está corriendo */
    private volatile boolean running = false;
    /** Timestamp del último log de debug (control de frecuencia) */
    private volatile long lastDebugLog = 0;
    /** Timestamp del último log del priceMap */
    private volatile long lastPriceMapLog = 0;
    /** Contador de logs para limitar salida inicial */
    private int logCounter = 0;

    /** Contador total de escaneos realizados */
    private final AtomicLong scanCount;
    /** Contador de oportunidades encontradas (sin filtrar) */
    private final AtomicLong opportunityCount;
    /** Contador de oportunidades efectivamente emitidas */
    private final AtomicLong opportunityEmitted;

    /** Intervalo de escaneo en milisegundos */
    private final int scanIntervalMs;
    /** Si true, emite toda oportunidad profitable (no solo las que superan minProfit) */
    private final boolean emitAllProfitable;

    /**
     * Constructor simplificado con valores por defecto.
     * scanIntervalMs = 100ms, emitAllProfitable = true
     */
    public ArbitrageDetector(
            AppConfig config,
            List<Triangle> triangles,
            ConcurrentHashMap<String, Ticker> priceMap,
            PrecisionAdjuster precisionAdjuster,
            Consumer<ArbitrageOpportunity> opportunityConsumer
    ) {
        this(config, triangles, priceMap, precisionAdjuster, opportunityConsumer, 100, true);
    }

    /**
     * Constructor completo con configuración del detector.
     *
     * @param config           Configuración global
     * @param triangles        Lista de triángulos a escanear
     * @param priceMap         Mapa compartido de precios
     * @param precisionAdjuster Ajustador de precisión
     * @param opportunityConsumer Callback para emitir oportunidades
     * @param scanIntervalMs   Intervalo de escaneo en ms
     * @param emitAllProfitable Si true, emite todas las oportunidades positivas
     */
    public ArbitrageDetector(
            AppConfig config,
            List<Triangle> triangles,
            ConcurrentHashMap<String, Ticker> priceMap,
            PrecisionAdjuster precisionAdjuster,
            Consumer<ArbitrageOpportunity> opportunityConsumer,
            int scanIntervalMs,
            boolean emitAllProfitable
    ) {
        this.config = config;
        this.priceMap = priceMap;
        this.precisionAdjuster = precisionAdjuster;
        this.triangles = triangles;
        this.opportunityConsumer = opportunityConsumer;
        this.scanIntervalMs = scanIntervalMs;
        this.emitAllProfitable = emitAllProfitable;

        // Inicializa calculadora de profits con el ajustador de precisión
        this.profitCalculator = new ProfitCalculator(config);
        this.profitCalculator.setPrecisionAdjuster(precisionAdjuster);

        // Historial de últimos profits por triángulo (evita re-emitir el mismo valor)
        this.lastProfitByTriangle = new ConcurrentHashMap<>();

        // Contadores atómicos para estadísticas
        this.scanCount = new AtomicLong(0);
        this.opportunityCount = new AtomicLong(0);
        this.opportunityEmitted = new AtomicLong(0);

        Log.info(TAG, "ArbitrageDetector initialized with " + triangles.size() + " triangles");
    }

    /**
     * Inicia el escaneo periódico de triángulos.
     * Crea un ScheduledThreadPool con el número de cores configurado
     * y ejecuta checkTriangle() en paralelo para todos los triángulos
     * cada scanIntervalMs milisegundos.
     */
    public void start() {
        if (running) {
            Log.warn(TAG, "Already running");
            return;
        }

        running = true;

        int cores = config.getCores();
        this.scheduler = Executors.newScheduledThreadPool(cores);

        String currentLevel = Log.getCurrentLevel();
        if (!"SCAN".equals(currentLevel)) {
            Log.info(TAG, "Starting scan every " + scanIntervalMs + "ms with " + cores + " threads");
        }

        // Tarea periódica: escanea todos los triángulos en paralelo vía parallelStream()
        scheduler.scheduleAtFixedRate(() -> {
            if (!running) return;
            scanCount.incrementAndGet();
            try {
                triangles.parallelStream().forEach(this::checkTriangle);
            } catch (Exception e) {
                Log.debug(TAG, "Scan error: " + e.getMessage());
            }
        }, 0, scanIntervalMs, TimeUnit.MILLISECONDS);
    }

    /**
     * Detiene el escaneo periódico y apaga el scheduler.
     * Muestra estadísticas acumuladas al detenerse.
     */
    public void stop() {
        running = false;
        if (scheduler != null) {
            scheduler.shutdown();
        }
        Log.info(TAG, "Stopped. Scans=" + scanCount.get() + ", Opportunities=" + opportunityCount.get());
    }

    /**
     * @return true si el detector está actualmente en ejecución
     */
    public boolean isRunning() {
        return running;
    }

    /**
     * Evalúa un triángulo individual para detectar oportunidad de arbitraje.
     * Flujo:
     *   1. Obtener los 3 tickers del priceMap (symbol1, symbol2, symbol3)
     *   2. Si falta algún ticker, salir (datos no disponibles)
     *   3. Calcular profit con ProfitCalculator.calculate()
     *   4. Validar que el profit esté en rango [-1%, maxProfit%]
     *   5. Emitir la oportunidad si es nueva o supera el umbral de cambio
     *
     * @param triangle Triángulo a evaluar
     */
    private void checkTriangle(Triangle triangle) {
        try {
            long now = System.currentTimeMillis();
            // Log de diagnóstico periódico (cada 5s)
            logDebugStatus(now);

            // Obtener precios actuales de los 3 símbolos del triángulo
            Ticker t1 = priceMap.get(triangle.getSymbol1());
            Ticker t2 = priceMap.get(triangle.getSymbol2());
            Ticker t3 = priceMap.get(triangle.getSymbol3());

            // Si algún precio no está disponible, saltar este triángulo
            if (t1 == null || t2 == null || t3 == null) {
                return;
            }

            // Calcular oportunidad: profit%, cantidades, precios ajustados
            ArbitrageOpportunity opportunity = profitCalculator.calculate(
                    triangle,
                    config.getBalancePerTrade(),
                    t1, t2, t3
            );

            // ProfitCalculator devolvió null (datos inválidos o fuera de rango)
            if (opportunity == null) {
                return;
            }

            // Validar que el profit esté dentro de rangos aceptables
            if (!isOpportunityValid(opportunity)) {
                return;
            }

            // Emitir solo si el profit cambió significativamente desde la última vez
            emitIfNew(opportunity);

        } catch (Exception e) {
            // Manejo específico para errores de parsing JSON (de API)
            String msg = e.getMessage();
            if (msg != null && msg.contains("Unexpected token")) {
                Log.error(TAG, "JSON parse error in profit calculation");
            } else {
                Log.debug(TAG, "Error triangle " + triangle.getId() + ": " + msg);
            }
        }
    }

    /**
     * Log de diagnóstico periódico para monitorear estado del detector.
     * - Primeros 3 ciclos: imprime claves del priceMap (para verificar carga inicial)
     * - Cada 5s: estadísticas de precios disponibles vs triángulos
     *
     * @param now Timestamp actual en ms
     */
    private void logDebugStatus(long now) {
        // Cada 5s, imprimir claves del priceMap (solo primeros 3 ciclos)
        if (now - lastPriceMapLog > 5000 && priceMap.size() > 0) {
            lastPriceMapLog = now;
            logCounter++;
            if (logCounter <= 3 && !Log.isScanEnabled()) {
                System.out.println("[DEBUG] priceMap keys: " + priceMap.keySet());
            }
        }

        // Cada 5s, loggear cuántos triángulos tienen datos completos
        if (now - lastDebugLog > 5000 && priceMap.size() > 0) {
            lastDebugLog = now;
            int available = 0;
            for (Triangle t : triangles) {
                if (priceMap.containsKey(t.getSymbol1()) &&
                    priceMap.containsKey(t.getSymbol2()) &&
                    priceMap.containsKey(t.getSymbol3())) {
                    available++;
                }
            }
            Log.debug(TAG, "priceMap=" + priceMap.size() + " triangles=" + triangles.size() + " ready=" + available);
        }
    }

    /**
     * Valida que el profit de la oportunidad esté dentro de rangos aceptables.
     * Filtra profits extremos que podrían indicar datos corruptos.
     * Rango: [-1.0%, maxProfit%] — el -1.0% es para escenarios de slippage extremo.
     *
     * @param opportunity Oportunidad a validar
     * @return true si el profit está en rango válido
     */
    private boolean isOpportunityValid(ArbitrageOpportunity opportunity) {
        double profitPct = opportunity.getProfitPct();
        return profitPct >= -1.0 && profitPct <= config.getMaxProfit();
    }

    /**
     * Emite la oportunidad al consumidor si es nueva o cambió significativamente.
     * Evita re-emitir la misma oportunidad si el profit no cambió (tolerancia 0.0001%).
     *
     * Lógica de emisión:
     *   - Si profit >= minProfit → debe ejecutarse (shouldExecute = true)
     *   - Si profit en rango [-1%, maxProfit%] → visible en nivel INFO
     *   - Si profit >= minProfit → visible en nivel SCAN
     *   - Si emitAllProfitable=true → emite aunque no supere minProfit
     *   - Filtro anti-spam: solo emite si |profit - lastProfit| > 0.0001%
     *
     * @param opportunity Oportunidad a emitir
     */
    private void emitIfNew(ArbitrageOpportunity opportunity) {
        String triangleId = opportunity.getTriangle().getId();
        double profitPct = opportunity.getProfitPct();
        double minProfit = config.getMinProfit();

        // Determinar si debe ejecutarse y si debe mostrarse en logs
        boolean shouldExecute = profitPct >= minProfit;
        boolean showInInfo = profitPct >= -1.0 && profitPct <= config.getMaxProfit();
        boolean showInScan = profitPct >= minProfit;

        // Si no debe mostrar en ningún nivel de log, salir
        if (!showInInfo && !showInScan) {
            return;
        }

        // Filtro anti-spam: si el profit no cambió significativamente, re-emitir
        Double lastProfit = lastProfitByTriangle.get(triangleId);
        if (lastProfit != null && Math.abs(profitPct - lastProfit) <= 0.0001) {
            return;
        }

        // Actualizar último profit para este triángulo
        lastProfitByTriangle.put(triangleId, profitPct);
        opportunityCount.incrementAndGet();

        // Formatear mensaje de log
        String currentLevel = Log.getCurrentLevel();
        String prefix = profitPct > 0 ? "[+]" : "[-]";
        String logMsg = "OPORTUNIDAD: " + prefix + " " + triangleId + " | Profit: " + String.format("%.4f", profitPct) + "%";

        // Mostrar dependiendo del nivel de logging
        if ("SCAN".equals(currentLevel) && showInScan) {
            // Silent in SCAN mode
        } else if (!"SCAN".equals(currentLevel) && showInInfo) {
            Log.info(TAG, logMsg);
        }

        // Emitir al consumidor si es ejecutable o si se configuró emitAllProfitable
        if (shouldExecute || emitAllProfitable) {
            opportunityConsumer.accept(opportunity);
            opportunityEmitted.incrementAndGet();
        }
    }

    /**
     * Resetea el historial de profits por triángulo.
     * Útil cuando se reinicia el detector o cambian las condiciones de mercado.
     */
    public void resetProfitHistory() {
        lastProfitByTriangle.clear();
        Log.info(TAG, "Profit history reset");
    }

    /**
     * Agrega un triángulo a la lista de escaneo si no existe ya.
     *
     * @param triangle Triángulo a agregar
     */
    public void addTriangle(Triangle triangle) {
        if (!triangles.contains(triangle)) {
            triangles.add(triangle);
        }
    }

    /**
     * Remueve un triángulo de la lista de escaneo.
     *
     * @param triangle Triángulo a remover
     */
    public void removeTriangle(Triangle triangle) {
        triangles.remove(triangle);
    }

    /**
     * @return Copia defensiva de la lista de triángulos actual
     */
    public List<Triangle> getTriangles() {
        return new ArrayList<>(triangles);
    }

    /**
     * Obtiene estadísticas actuales del detector.
     * Calcula cuántos triángulos están listos (tienen los 3 precios disponibles).
     *
     * @return DetectorStats con todas las métricas
     */
    public DetectorStats getStats() {
        int available = 0;
        for (Triangle t : triangles) {
            if (priceMap.containsKey(t.getSymbol1()) &&
                priceMap.containsKey(t.getSymbol2()) &&
                priceMap.containsKey(t.getSymbol3())) {
                available++;
            }
        }

        return DetectorStats.builder()
            .running(running)
            .scanCount(scanCount.get())
            .opportunityCount(opportunityCount.get())
            .opportunityEmitted(opportunityEmitted.get())
            .triangleCount(triangles.size())
            .trianglesReady(available)   // Triángulos con datos completos
            .priceMapSize(priceMap.size())
            .timestamp(System.currentTimeMillis())
            .build();
    }

    /**
     * Loggea todas las estadísticas del detector en formato legible.
     * Incluye: estado, escaneos, oportunidades, triángulos listos.
     */
    public void logStats() {
        DetectorStats stats = getStats();
        Log.info(TAG, "=== ArbitrageDetector Stats ===");
        Log.info(TAG, "  Running: " + stats.isRunning());
        Log.info(TAG, "  Scans: " + stats.getScanCount());
        Log.info(TAG, "  Opportunities found: " + stats.getOpportunityCount());
        Log.info(TAG, "  Opportunities emitted: " + stats.getOpportunityEmitted());
        Log.info(TAG, "  Triangles: " + stats.getTriangleCount() + " (ready: " + stats.getTrianglesReady() + ")");
        Log.info(TAG, "  PriceMap: " + stats.getPriceMapSize());
    }

    /**
     * Estadísticas internas del detector.
     * running:       true si el detector está activo
     * scanCount:    número de ciclos de escaneo ejecutados
     * opportunityCount: oportunidades detectadas (incluye duplicados)
     * opportunityEmitted: oportunidades emitidas al consumidor
     * triangleCount: total de triángulos en la lista
     * trianglesReady: triángulos con datos de precio completos
     * priceMapSize:  cantidad de símbolos en el mapa de precios
     * timestamp:     momento de la captura
     */
    @Data
    @Builder
    public static class DetectorStats {
        private boolean running;
        private long scanCount;
        private long opportunityCount;
        private long opportunityEmitted;
        private int triangleCount;
        private int trianglesReady;
        private int priceMapSize;
        private long timestamp;

        public boolean isRunning() { return running; }
    }
}