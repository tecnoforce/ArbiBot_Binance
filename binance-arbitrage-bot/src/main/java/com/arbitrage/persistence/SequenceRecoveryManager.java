package com.arbitrage.persistence;

import com.arbitrage.config.AppConfig;
import com.arbitrage.model.*;
import com.arbitrage.trading.BinanceApiClient;
import com.arbitrage.module.ExecutionEngine;
import com.arbitrage.util.Log;
import com.arbitrage.util.StatsManager;

import java.util.ArrayList;
import java.util.List;

/**
 * SequenceRecoveryManager - Gestor de recuperacion de secuencias tras reinicio.
 *
 * Proposito: Cuando el bot se reinicia (crash, actualizacion, Ctrl+C), este
 * manager carga las secuencias pendientes desde el archivo de persistencia
 * (sequences.seq) y las clasifica segun su estado:
 *
 * - ABIERTA: se reanudan para continuar la ejecucion desde donde quedaron
 * - CERRADA: se mueven al archivo de eventos historicos (sequences.events)
 * - CANCELADA/ERROR: se mueven al archivo de eventos historicos
 *
 * Flujo de recuperacion en Main.java:
 *   1. Main crea SequenceRecoveryManager con las dependencias necesarias
 *   2. Llama a loadAndRecoverSequences() que retorna lista de secuencias ABIERTAS
 *   3. OrderExecutor.launchRecovery() reanuda cada secuencia pendiente
 *
 * Integracion:
 * - Lee desde SequenceFileManager.findOpen() (secuencias con estado ABIERTA)
 * - Usa ExecutionEngine para reanudar secuencias pendientes
 * - Usa StatsManager para registrar profits de secuencias cerradas encontradas
 *
 * @see SequenceFileManager Lectura/escritura del archivo de persistencia
 * @see ExecutionEngine Reanudacion de secuencias pendientes
 */
public class SequenceRecoveryManager {

    /** Tag para logging del modulo de recuperacion. */
    private static final String TAG = "RECOVERY";

    /** Cliente API de Binance (para consultar estado de ordenes en Binance). */
    private final BinanceApiClient apiClient;

    /** Gestor de persistencia JSON (lee/escribe sequences.seq). */
    private final SequenceFileManager fileManager;

    /** Motor de ejecucion (reanuda secuencias pendientes). */
    private final ExecutionEngine executionEngine;

    /** Configuracion global del bot. */
    private final AppConfig config;

    /** Gestor de estadisticas (registra profits de secuencias recuperadas). */
    private final StatsManager statsManager;

    /**
     * Constructor del gestor de recuperacion.
     *
     * @param apiClient       Cliente REST de Binance
     * @param fileManager     Gestor de persistencia JSON
     * @param executionEngine Motor de ejecucion para reanudar secuencias
     * @param config          Configuracion global del bot
     * @param statsManager    Gestor de estadisticas
     */
    public SequenceRecoveryManager(BinanceApiClient apiClient, SequenceFileManager fileManager,
                                   ExecutionEngine executionEngine, AppConfig config, StatsManager statsManager) {
        this.apiClient = apiClient;
        this.fileManager = fileManager;
        this.executionEngine = executionEngine;
        this.config = config;
        this.statsManager = statsManager;
    }

    /**
     * Carga las secuencias abiertas desde persistencia y las clasifica.
     *
     * Flujo:
     * 1. Lee todas las secuencias con estado ABIERTA desde el archivo JSON
     * 2. Para cada secuencia:
     *    - Si ya esta en estado final (CERRADA, CANCELADA, ERROR) → mueve a eventos
     *    - Si sigue ABIERTA → la agrega a la lista de pendientes para reanudar
     * 3. Retorna la lista de secuencias ABIERTAS que necesitan reanudarse
     *
     * Manejo de errores: Si hay un error fatal leyendo el archivo, retorna
     * una lista vacia (el bot continuara sin recuperar secuencias).
     *
     * @return Lista de TradingSequence con estado ABIERTA para reanudar
     */
    public List<TradingSequence> loadAndRecoverSequences() {
        Log.info(TAG, "=== Loading Sequences from Persistence ===");

        try {
            List<TradingSequence> pendingSequences = new ArrayList<>();

            // Obtener todas las secuencias abiertas desde el archivo JSON
            List<TradingSequence> open = fileManager.findOpen();
            if (!open.isEmpty()) {
                Log.info(TAG, "Found " + open.size() + " open sequences to recover");
                for (TradingSequence seq : open) {
                    // Si la secuencia ya esta en estado final, moverla a eventos
                    moveToEventsIfFinal(seq);
                    // Si sigue abierta, agregarla a la lista de pendientes
                    if (seq.getEstado() == EstadoSecuencia.ABIERTA) {
                        pendingSequences.add(seq);
                    }
                }
            } else {
                Log.info(TAG, "No open sequences found");
            }

            Log.info(TAG, "Total pending sequences to recover: " + pendingSequences.size());
            return pendingSequences;

        } catch (Exception e) {
            Log.error(TAG, "Fatal error loading sequences: " + e.getMessage());
            return new ArrayList<>();
        }
    }

    /**
     * Mueve una secuencia a eventos si ya esta en estado final.
     *
     * Una secuencia se considera "final" cuando su estado es diferente de ABIERTA.
     * Esto puede ocurrir si el bot se cayo justo despues de cerrar la secuencia
     * pero antes de moverla al archivo de eventos.
     *
     * @param seq Secuencia a verificar y posiblemente mover
     */
    private void moveToEventsIfFinal(TradingSequence seq) {
        if (seq.getEstado() != EstadoSecuencia.ABIERTA) {
            Log.info(TAG, "Sequence #" + seq.getSeqId() + " already final (" + seq.getEstado() + "), moving to events");
            moveToEvents(seq);
        }
    }

    /**
     * Mueve una secuencia al archivo de eventos historicos.
     *
     * Proceso:
     * 1. Si la secuencia esta CERRADA → registra el profit en StatsManager
     * 2. Si esta en otro estado final → registra como cancelada en StatsManager
     * 3. Elimina la secuencia del archivo activo (sequences.seq)
     * 4. Agrega la secuencia al archivo de eventos (sequences.events)
     *
     * Esto asegura que las estadisticas se mantengan coherentes tras un reinicio
     * y que el archivo activo solo contenga secuencias realmente pendientes.
     *
     * @param seq Secuencia a mover a eventos
     */
    private void moveToEvents(TradingSequence seq) {
        try {
            int seqId = seq.getSeqId();

            if (seq.getEstado() == EstadoSecuencia.CERRADA) {
                // Secuencia completada exitosamente: registrar profit
                if (statsManager != null) {
                    statsManager.recordCompleted(seq.getProfitRealizado());
                }
                Log.info(TAG, "[RECOVERY] #" + seqId + " Closed sequence moved to events, profit="
                        + String.format("%.4f", seq.getProfitRealizado()));
            } else {
                // Secuencia cancelada o con error: registrar como cancelada
                if (statsManager != null) {
                    statsManager.recordCancelled();
                }
                Log.info(TAG, "[RECOVERY] #" + seqId + " Non-open sequence moved to events");
            }

            // Eliminar del archivo activo y agregar al archivo de eventos
            fileManager.deleteBySeqId(seqId);
            fileManager.appendEvent(seq);
        } catch (Exception e) {
            Log.error(TAG, "Error moving sequence to events: " + e.getMessage());
        }
    }
}
