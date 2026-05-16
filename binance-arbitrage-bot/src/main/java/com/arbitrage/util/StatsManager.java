package com.arbitrage.util;

import com.arbitrage.model.TradingStats;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

/**
 * StatsManager - Gestor de estadisticas persistentes del bot de arbitraje.
 *
 * Proposito: Mantener y persistir estadisticas acumuladas de todas las
 * operaciones realizadas, permitiendo continuidad entre reinicios del bot.
 * Los datos se almacenan en un archivo JSON con escritura atomica.
 *
 * Responsabilidades principales:
 * - Acumular profit total (valor absoluto y porcentaje sobre inversion inicial)
 * - Contar eventos completados, cancelados y stoploss
 * - Calcular promedios de profit por operacion (cantidad y porcentaje)
 * - Persistir en archivo JSON con escritura atomica (temp file + rename)
 * - Cargar estadisticas previas al iniciar para mantener continuidad historica
 *
 * Concurrencia:
 * - Metodos sincronizados (synchronized) para evitar condiciones de carrera
 * - ReentrantLock en save() para escritura atomica thread-safe al archivo
 * - Las lecturas (getStats) no requieren lock por ser referencia inmutable
 *
 * Integracion:
 * - OrderExecutor.recordCompleted() -> StatsManager.recordCompleted()
 * - OrderExecutor.onCancelled()     -> StatsManager.recordCancelled()
 * - Los datos persisten en disco para dashboard persistente y analisis posterior
 *
 * Formato archivo: JSON sin indentacion (minificado) para ahorrar espacio
 * Ruta tipica: ./stats/stats_USDTNORMAL2.json
 */
public class StatsManager {
    // Tag de 8 caracteres para logging interno del gestor de estadisticas
    private static final String TAG = "STATS_MGR";

    // Ruta completa al archivo JSON de estadisticas en disco
    private final Path statsPath;
    // ObjectMapper Jackson para serializacion/deserializacion JSON
    private final ObjectMapper objectMapper;
    // Lock reentrante para proteger escritura atomica del archivo
    private final ReentrantLock fileLock;
    // Timestamp de creacion para calcular tiempo de ejecucion total
    private final long startTime;
    // Executor para escritura a disco en background (no bloquea el hot path)
    private final ExecutorService saveExecutor;

    // Objeto en memoria con todas las estadisticas acumuladas
    private TradingStats stats;

    /**
     * Constructor del gestor de estadisticas.
     * Configura la ruta del archivo, inicializa Jackson ObjectMapper (sin
     * indentacion para archivos mas pequenos), y carga estadisticas previas
     * del disco si existen. Si no hay archivo previo, inicializa un nuevo
     * objeto TradingStats con valores en cero e inversion incial.
     *
     * @param basePath Directorio base para almacenar el archivo (ej. "./stats")
     * @param fileName Nombre del archivo JSON (ej. "stats_USDTNORMAL2.json")
     * @param baseCurrency Moneda base de las operaciones (ej. "USDT", "BNB")
     * @param initialInvestment Inversion inicial en la moneda base para calcular % de profit
     */
    public StatsManager(String basePath, String fileName, String baseCurrency, double initialInvestment) {
        this.statsPath = Paths.get(basePath, fileName);
        this.objectMapper = new ObjectMapper();
        this.objectMapper.disable(SerializationFeature.INDENT_OUTPUT);
        this.fileLock = new ReentrantLock();
        this.startTime = System.currentTimeMillis();
        this.saveExecutor = Executors.newSingleThreadExecutor();

        // Intentar cargar stats previos del disco
        this.stats = load();
        if (this.stats == null) {
            // No hay stats previos o archivo corrupto -> inicializar nuevo
            this.stats = TradingStats.builder()
                    .totalProfit(0.0)
                    .totalProfitPercent(0.0)
                    .eventosCompletados(0)
                    .eventosPorCompletar(0)
                    .timeElapsed(0)
                    .eventosStoploss(0)
                    .averageProfitQty(0.0)
                    .averageProfitPercent(0.0)
                    .baseCurrency(baseCurrency)
                    .initialInvestment(initialInvestment)
                    .eventsFinalizados(0)
                    .build();
        }

        Log.info(TAG, "StatsManager init. file=" + statsPath.getFileName() + ", initialInvestment=" + initialInvestment + " " + baseCurrency);

        // Persistir inmediatamente para crear el archivo .stats si no existe
        saveExecutor.submit(this::save);
    }

    /**
     * Carga las estadisticas desde el archivo JSON en disco.
     *
     * Flujo de carga:
     * 1. Verificar si el archivo existe -> si no, retornar null (inicio limpio)
     * 2. Leer todo el contenido como String
     * 3. Verificar que no este vacio
     * 4. Deserializar con Jackson ObjectMapper a TradingStats
     * 5. Si hay error en cualquier paso, loggear advertencia y retornar null
     *
     * @return TradingStats deserializado, o null si no existe/error/vacio
     */
    private TradingStats load() {
        if (!Files.exists(statsPath)) {
            Log.debug(TAG, "Stats file does not exist, starting clean");
            return null;
        }

        try {
            String content = new String(Files.readAllBytes(statsPath));
            if (content.trim().isEmpty()) {
                return null;
            }
            TradingStats loaded = objectMapper.readValue(content, TradingStats.class);
            Log.info(TAG, "Stats loaded: completed=" + loaded.getEventosCompletados() +
                    ", totalProfit=" + String.format("%.4f", loaded.getTotalProfit()));
            return loaded;
        } catch (Exception e) {
            Log.warn(TAG, "Error reading stats file: " + e.getMessage() + ", starting clean");
            return null;
        }
    }

    /**
     * Registra una operacion completada con exito.
     * Acumula el profit realizado al total, incrementa contadores de eventos,
     * recalcula el porcentaje total sobre la inversion inicial, actualiza
     * promedios y tiempo transcurrido. Finalmente persiste en disco.
     *
     * Metodo sincronizado para evitar condiciones de carrera cuando multiples
     * hilos de ejecucion completan operaciones simultaneamente.
     *
     * @param profitRealizado Profit obtenido en esta operacion (en USDT)
     */
    public synchronized void recordCompleted(double profitRealizado) {
        // Acumular profit al total general
        stats.setTotalProfit(stats.getTotalProfit() + profitRealizado);
        // Incrementar contadores de eventos
        stats.setEventosCompletados(stats.getEventosCompletados() + 1);
        stats.setEventsFinalizados(stats.getEventsFinalizados() + 1);

        // Recalcular profit porcentual sobre la inversion inicial
        if (stats.getInitialInvestment() > 0) {
            double newTotalPct = (stats.getTotalProfit() / stats.getInitialInvestment()) * 100.0;
            stats.setTotalProfitPercent(newTotalPct);
        }

        // Recalcular promedios y tiempo de ejecucion
        stats.computeAverages();
        stats.setTimeElapsed(System.currentTimeMillis() - startTime);

        Log.debug(TAG, "Stats recorded completed: profit=" + String.format("%.4f", profitRealizado) +
                ", total=" + String.format("%.4f", stats.getTotalProfit()) +
                ", completed=" + stats.getEventosCompletados());

        saveExecutor.submit(this::save);
    }

    /**
     * Registra una operacion cancelada (no ejecutada por el exchange).
     * Incrementa el contador de eventos por completar y eventos finalizados,
     * actualiza el tiempo de ejecucion y persiste en disco.
     * No acumula profit porque la orden fue rechazada o cancelada.
     *
     * Metodo sincronizado para seguridad en concurrencia.
     */
    public synchronized void recordCancelled() {
        stats.setEventosPorCompletar(stats.getEventosPorCompletar() + 1);
        stats.setEventsFinalizados(stats.getEventsFinalizados() + 1);
        stats.setTimeElapsed(System.currentTimeMillis() - startTime);

        Log.debug(TAG, "Stats recorded cancelled: porcCompletar=" + stats.getEventosPorCompletar());

        saveExecutor.submit(this::save);
    }

    /**
     * Incrementa el contador de secuencias pendientes cuando se crea una nueva secuencia.
     * Se llama desde ExecutionEngine cuando se inserta una secuencia en el archivo .seq
     */
    public synchronized void incrementPending() {
        stats.setEventosPorCompletar(stats.getEventosPorCompletar() + 1);
        Log.debug(TAG, "Pending incremented: " + stats.getEventosPorCompletar());
        saveExecutor.submit(this::save);
    }

    /**
     * Decrementa el contador de secuencias pendientes cuando se elimina una secuencia.
     * Se llama desde ExecutionEngine cuando se completa/cancela una secuencia del archivo .seq
     */
    public synchronized void decrementPending() {
        if (stats.getEventosPorCompletar() > 0) {
            stats.setEventosPorCompletar(stats.getEventosPorCompletar() - 1);
        }
        Log.debug(TAG, "Pending decremented: " + stats.getEventosPorCompletar());
        saveExecutor.submit(this::save);
    }

    /**
     * Persiste las estadisticas actuales en disco con escritura atomica.
     *
     * Flujo de escritura atomica (previene corrupcion por cortes de energia):
     * 1. Serializar TradingStats a JSON con Jackson
     * 2. Escribir JSON a un archivo temporal (.tmp en el mismo directorio)
     * 3. Si el archivo original existe, eliminarlo
     * 4. Renombrar (mover atomicamente) el temporal al nombre definitivo
     * 5. Si falla en cualquier paso, el archivo original queda intacto
     *
     * Thread-safe: protegido por ReentrantLock para evitar escrituras simultaneas
     * de diferentes hilos de ejecucion de ordenes.
     */
    public synchronized void save() {
        fileLock.lock();
        try {
            // Crear ruta para archivo temporal en el mismo directorio
            Path tempPath = Paths.get(statsPath.toString() + ".tmp");

            // Serializar a JSON y escribir a temporal
            String json = objectMapper.writeValueAsString(stats);
            Files.write(tempPath, json.getBytes());

            // Reemplazar archivo original con el temporal (operacion atomica)
            if (Files.exists(statsPath)) {
                Files.delete(statsPath);
            }
            Files.move(tempPath, statsPath, StandardCopyOption.ATOMIC_MOVE);

            Log.debug(TAG, "Stats saved atomically: " + statsPath.getFileName());
        } catch (Exception e) {
            Log.error(TAG, "Failed to save stats: " + e.getMessage());
        } finally {
            fileLock.unlock();
        }
    }

    /**
     * Obtiene el objeto de estadisticas actual en memoria.
     * Contiene todos los acumulados: profit total, contadores, promedios, etc.
     *
     * @return TradingStats con las estadisticas actuales (nunca null)
     */
    public TradingStats getStats() {
        return stats;
    }

    /**
     * Obtiene el tiempo total transcurrido desde la creacion del gestor.
     * Calculado como diferencia entre el momento actual y startTime.
     *
     * @return Milisegundos transcurridos desde la inicializacion
     */
    public long getTimeElapsed() {
        return System.currentTimeMillis() - startTime;
    }

    /**
     * Finaliza el executor y asegura que todas las escrituras pendientes
     * se completen antes de retornar. Debe llamarse en el shutdown hook.
     */
    public synchronized void flush() {
        saveExecutor.submit(this::save);
        saveExecutor.shutdown();
        try {
            saveExecutor.awaitTermination(10, TimeUnit.SECONDS);
            Log.debug(TAG, "Stats flushed to disk successfully");
        } catch (InterruptedException e) {
            Log.warn(TAG, "Stats flush interrupted: " + e.getMessage());
            Thread.currentThread().interrupt();
        }
    }
}
