package com.arbitrage.persistence;

import com.arbitrage.model.SequenceOrder;
import com.arbitrage.model.TradingSequence;
import com.arbitrage.util.Log;
import com.arbitrage.util.MiniIdGenerator;
import com.arbitrage.util.SequenceFormatter;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.locks.ReentrantLock;
import java.util.TimeZone;

/**
 * SequenceFileManager - Gestor de persistencia de secuencias de trading en JSON.
 *
 * Proposito: Almacenar y recuperar el estado de las secuencias de arbitraje
 * (TradingSequence) en archivos JSON para permitir recuperacion tras caidas
 * del bot (crash, reinicio, actualizacion).
 *
 * Archivos que maneja:
 * - sequences.seq: Mapa JSON indexado por miniId con las secuencias activas.
 *   Formato: { "A3K9X2M7": { TradingSequence }, "B7L2Y5N1": { ... } }
 * - sequences.events: Archivo de texto plano con eventos historicos formateados.
 *   Cada evento es un bloque de texto delimitado por separadores.
 *
 * Caracteristicas de seguridad:
 * - Escritura atomica: escribe a un archivo .tmp y luego renombra (atomic move).
 *   Si el proceso se interrumpe durante la escritura, el archivo original queda intacto.
 * - Locking con ReentrantLock: protege contra condiciones de carrera cuando
 *   multiples hilos (OP1, OP2, OP3) intentan actualizar la secuencia simultaneamente.
 * - Graceful degradation: si el archivo esta corrupto o no existe, retorna un
 *   mapa vacio en lugar de lanzar excepcion.
 *
 * Soporte de doble identificacion:
 * - Las secuencias se indexan por miniId (clave del mapa JSON).
 * - Tambien se puede buscar por seqId (entero secuencial) con findBySeqId().
 * - Al cargar, sincroniza el campo miniId interno si es null (compatibilidad).
 *
 * Integracion:
 * - ExecutionEngine: inserta secuencias nuevas, actualiza ordenes, elimina al cerrar
 * - SequenceRecoveryManager: carga secuencias abiertas al iniciar el bot
 * - SequenceFormatter: formatea secuencias para el archivo de eventos
 *
 * @see TradingSequence Modelo de secuencia de trading
 * @see SequenceRecoveryManager Recuperacion de secuencias al iniciar
 */
public class SequenceFileManager {

    /** Tag para logging del gestor de persistencia. */
    private static final String TAG = "SEQ_FILE";

    /** Directorio base donde se almacenan los archivos de secuencias.
     * Tipicamente es el directorio de trabajo del bot (System.getProperty("user.dir")). */
    private final String basePath;

    /** Nombre del archivo de secuencias activas (por defecto: "sequences.seq"). */
    private final String seqFileName;

    /** Nombre del archivo de eventos historicos (por defecto: "sequences.events"). */
    private final String eventsFileName;

    /** ObjectMapper de Jackson para serializacion/deserializacion JSON.
     * Configurado con:
     * - FAIL_ON_UNKNOWN_PROPERTIES disabled: ignora campos nuevos en JSON
     * - INDENT_OUTPUT enabled: JSON legible (no minificado)
     * - TimeZone UTC: timestamps consistentes internacionalmente */
    private final ObjectMapper objectMapper;

    /** Lock reentrante para proteger operaciones de lectura/escritura.
     * ReentrantLock permite que el mismo hilo adquiera el lock multiples veces
     * (aunque en esta implementacion no se usa reentrada). */
    private final ReentrantLock fileLock;

    /**
     * Constructor con nombres de archivo por defecto.
     *
     * @param basePath Directorio base para los archivos de persistencia
     */
    public SequenceFileManager(String basePath) {
        this(basePath, "sequences.seq", "sequences.events");
    }

    /**
     * Constructor completo con nombres de archivo personalizables.
     *
     * Configura el ObjectMapper con las opciones necesarias para
     * serializacion/deserializacion de TradingSequence.
     *
     * @param basePath       Directorio base para los archivos
     * @param seqFileName    Nombre del archivo de secuencias activas
     * @param eventsFileName Nombre del archivo de eventos historicos
     */
    public SequenceFileManager(String basePath, String seqFileName, String eventsFileName) {
        this.basePath = basePath;
        this.seqFileName = seqFileName;
        this.eventsFileName = eventsFileName;
        this.objectMapper = new ObjectMapper();
        // Ignorar propiedades desconocidas en JSON (compatibilidad hacia adelante)
        this.objectMapper.disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        // JSON con indentacion para facilitar lectura manual y debugging
        this.objectMapper.enable(SerializationFeature.INDENT_OUTPUT);
        // Timestamps en UTC para consistencia internacional
        this.objectMapper.setTimeZone(TimeZone.getTimeZone("UTC"));
        // Lock para proteger acceso concurrente al archivo
        this.fileLock = new ReentrantLock();
    }

    /**
     * Obtiene la ruta completa del archivo de secuencias activas.
     *
     * @return Path al archivo sequences.seq (o el nombre configurado)
     */
    private Path getSeqFilePath() {
        return Paths.get(basePath, seqFileName);
    }

    /**
     * Obtiene la ruta completa del archivo de eventos historicos.
     *
     * @return Path al archivo sequences.events (o el nombre configurado)
     */
    private Path getEventsFilePath() {
        return Paths.get(basePath, eventsFileName);
    }

    /**
     * Inserta una nueva secuencia en el archivo de persistencia.
     *
     * La secuencia se indexa por su miniId (clave del mapa JSON).
     * Si ya existe una secuencia con el mismo miniId, se sobrescribe.
     *
     * Flujo:
     * 1. Adquiere el lock para evitar escritura concurrente
     * 2. Carga todas las secuencias existentes desde el archivo
     * 3. Agrega/actualiza la secuencia en el mapa
     * 4. Guarda el mapa completo de forma atomica (tmp + rename)
     * 5. Libera el lock
     *
     * @param sequence Secuencia de trading a insertar
     * @throws IOException si falla la escritura al archivo
     */
    public void insert(TradingSequence sequence) throws IOException {
        fileLock.lock();
        try {
            Log.debug(TAG, "Inserting sequence: " + sequence.getMiniId() + " (Seq #" + sequence.getSeqId() + ")");
            Map<String, TradingSequence> sequences = loadAll();
            sequences.put(sequence.getMiniId(), sequence);
            saveAll(sequences);
            Log.debug(TAG, "Inserted sequence: " + sequence.getMiniId() + " (Seq #" + sequence.getSeqId() + ") - Total sequences: " + sequences.size());
        } finally {
            fileLock.unlock();
        }
    }

    /**
     * Actualiza una orden individual dentro de una secuencia.
     *
     * Busca la secuencia por miniId, actualiza la orden correspondiente
     * (op1, op2 o op3 segun opIndice), y guarda el archivo.
     *
     * @param miniId   MiniId de la secuencia a actualizar
     * @param opIndice Indice de la orden (1, 2 o 3)
     * @param order    Nueva orden que reemplaza la existente
     * @throws IOException si falla la escritura al archivo
     */
    public void updateOrder(String miniId, int opIndice, SequenceOrder order) throws IOException {
        fileLock.lock();
        try {
            Map<String, TradingSequence> sequences = loadAll();
            TradingSequence seq = sequences.get(miniId);
            if (seq == null) {
                Log.warn(TAG, "Sequence not found for update: " + miniId);
                return;
            }

            switch (opIndice) {
                case 1: seq.setOp1(order); break;
                case 2: seq.setOp2(order); break;
                case 3: seq.setOp3(order); break;
            }

            sequences.put(miniId, seq);
            saveAll(sequences);
            Log.debug(TAG, "Updated order " + opIndice + " in sequence: " + miniId);
        } finally {
            fileLock.unlock();
        }
    }

    /**
     * Actualiza una orden individual buscando la secuencia por seqId (entero).
     *
     * Variante de updateOrder() para cuando se conoce el seqId secuencial
     * en lugar del miniId alfanumerico. Hace un scan lineal del mapa
     * para encontrar la secuencia con el seqId matching.
     *
     * @param seqId    ID secuencial de la secuencia
     * @param opIndice Indice de la orden (1, 2 o 3)
     * @param order    Nueva orden que reemplaza la existente
     * @throws IOException si falla la escritura al archivo
     */
    public void updateOrderBySeqId(int seqId, int opIndice, SequenceOrder order) throws IOException {
        fileLock.lock();
        try {
            Map<String, TradingSequence> sequences = loadAll();
            TradingSequence seq = null;
            // Busqueda lineal: recorre todas las secuencias hasta encontrar el seqId
            for (TradingSequence s : sequences.values()) {
                if (s.getSeqId() == seqId) {
                    seq = s;
                    break;
                }
            }
            if (seq == null) {
                Log.warn(TAG, "Sequence not found for update: #" + seqId);
                return;
            }

            switch (opIndice) {
                case 1: seq.setOp1(order); break;
                case 2: seq.setOp2(order); break;
                case 3: seq.setOp3(order); break;
            }

            sequences.put(seq.getMiniId(), seq);
            saveAll(sequences);
            Log.debug(TAG, "Updated order " + opIndice + " in sequence: #" + seqId);
        } finally {
            fileLock.unlock();
        }
    }

    /**
     * Elimina una secuencia del archivo de persistencia por miniId.
     *
     * Se usa cuando una secuencia se completa exitosamente o se cancela.
     * La secuencia se elimina del archivo activo (sequences.seq) y
     * normalmente se mueve al archivo de eventos (sequences.events).
     *
     * @param miniId MiniId de la secuencia a eliminar
     * @throws IOException si falla la escritura al archivo
     */
    public void delete(String miniId) throws IOException {
        fileLock.lock();
        try {
            Map<String, TradingSequence> sequences = loadAll();
            if (sequences.remove(miniId) != null) {
                saveAll(sequences);
                Log.debug(TAG, "Deleted sequence: " + miniId);
            } else {
                Log.warn(TAG, "Sequence not found for delete: " + miniId);
            }
        } finally {
            fileLock.unlock();
        }
    }

    /**
     * Elimina una secuencia del archivo de persistencia por seqId (entero).
     *
     * Variante de delete() para cuando se conoce el seqId secuencial.
     * Hace un scan lineal del mapa para encontrar y eliminar la secuencia.
     *
     * @param seqId ID secuencial de la secuencia a eliminar
     * @throws IOException si falla la escritura al archivo
     */
    public void deleteBySeqId(int seqId) throws IOException {
        fileLock.lock();
        try {
            Map<String, TradingSequence> sequences = loadAll();
            String foundKey = null;
            // Busqueda lineal: encuentra la clave del mapa que corresponde al seqId
            for (Map.Entry<String, TradingSequence> entry : sequences.entrySet()) {
                if (entry.getValue().getSeqId() == seqId) {
                    foundKey = entry.getKey();
                    break;
                }
            }
            if (foundKey != null) {
                sequences.remove(foundKey);
                saveAll(sequences);
                Log.debug(TAG, "Deleted sequence: #" + seqId);
            } else {
                Log.warn(TAG, "Sequence not found for delete: #" + seqId);
            }
        } finally {
            fileLock.unlock();
        }
    }

    /**
     * Carga todas las secuencias desde el archivo JSON.
     *
     * Si el archivo no existe o esta vacio, retorna un mapa vacio.
     * Si el archivo esta corrupto (JSON invalido), loggea una advertencia
     * y retorna un mapa vacio (graceful degradation).
     *
     * Sincronizacion de miniId: despues de cargar, verifica que cada
     * TradingSequence tenga su campo miniId sincronizado con la clave
     * del mapa. Si es null o vacio, lo establece desde la clave.
     * Esto es necesario para compatibilidad con secuencias legacy.
     *
     * @return Mapa de miniId → TradingSequence (nunca null)
     */
    public Map<String, TradingSequence> loadAll() {
        Path seqPath = getSeqFilePath();
        if (!Files.exists(seqPath)) {
            Log.debug(TAG, "Sequence file does not exist, starting clean");
            return new HashMap<>();
        }

        try {
            String content = new String(Files.readAllBytes(seqPath));
            if (content.trim().isEmpty()) {
                return new HashMap<>();
            }
            Map<String, TradingSequence> sequences = objectMapper.readValue(content,
                objectMapper.getTypeFactory().constructMapType(Map.class, String.class, TradingSequence.class));

            // Sincronizar miniId desde las keys del mapa si el campo interno es null
            for (Map.Entry<String, TradingSequence> entry : sequences.entrySet()) {
                TradingSequence seq = entry.getValue();
                if (seq.getMiniId() == null || seq.getMiniId().isEmpty()) {
                    seq.setMiniId(entry.getKey());
                }
            }

            return sequences;
        } catch (Exception e) {
            Log.warn(TAG, "Error reading sequence file: " + e.getMessage() + ", starting clean");
            return new HashMap<>();
        }
    }

    /**
     * Guarda todas las secuencias en el archivo JSON de forma atomica.
     *
     * Algoritmo de escritura atomica (previene corrupcion por cortes de energia):
     * 1. Serializa el mapa completo a JSON
     * 2. Escribe el JSON a un archivo temporal (.tmp en el mismo directorio)
     * 3. Si el archivo original existe, lo elimina
     * 4. Renombra (mueve atomicamente) el temporal al nombre definitivo
     * 5. Si falla en cualquier paso, elimina el archivo temporal
     *
     * El archivo .tmp se crea en el mismo directorio para asegurar que el
     * rename sea atomico (en el mismo filesystem).
     *
     * @param sequences Mapa completo de secuencias a guardar
     * @throws IOException si falla la escritura (el archivo original queda intacto)
     */
    private void saveAll(Map<String, TradingSequence> sequences) throws IOException {
        Path seqPath = getSeqFilePath();
        Path tempPath = Paths.get(seqPath.toString() + ".tmp");

        try {
            String json = objectMapper.writeValueAsString(sequences);
            Log.debug(TAG, "saveAll: JSON size=" + json.length() + " bytes, sequences=" + sequences.size());
            Files.write(tempPath, json.getBytes());

            if (Files.exists(seqPath)) {
                Files.delete(seqPath);
            }
            Files.move(tempPath, seqPath, StandardCopyOption.ATOMIC_MOVE);

            Log.debug(TAG, "Saved " + sequences.size() + " sequences to " + seqPath);
        } catch (Exception e) {
            // Si falla, limpiar el archivo temporal para no dejar basura
            if (Files.exists(tempPath)) {
                Files.delete(tempPath);
            }
            throw new IOException("Failed to save sequences: " + e.getMessage(), e);
        }
    }

    /**
     * Agrega un evento formateado al archivo de eventos historicos.
     *
     * El evento se escribe en formato de texto plano usando SequenceFormatter,
     * que genera un bloque delimitado por separadores con toda la informacion
     * de la secuencia (estado, profit, ordenes, timestamps).
     *
     * Se usa append (StandardOpenOption.APPEND) para no sobrescribir eventos
     * anteriores. Cada evento se agrega al final del archivo.
     *
     * @param sequence Secuencia de trading a registrar como evento
     * @throws IOException si falla la escritura al archivo
     */
    public void appendEvent(TradingSequence sequence) throws IOException {
        fileLock.lock();
        try {
            Path eventsPath = getEventsFilePath();
            String formatted = SequenceFormatter.formatEvent(sequence);

            try (BufferedWriter writer = new BufferedWriter(
                    new OutputStreamWriter(Files.newOutputStream(eventsPath,
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND),
                        java.nio.charset.StandardCharsets.UTF_8))) {
                writer.write(formatted);
            }

            Log.debug(TAG, "Appended event for sequence: " + sequence.getMiniId() + " (Seq #" + sequence.getSeqId() + ")");
        } finally {
            fileLock.unlock();
        }
    }

    /**
     * Busca una secuencia por su miniId.
     *
     * @param miniId MiniId de la secuencia a buscar
     * @return TradingSequence si existe, o null si no se encuentra
     */
    public TradingSequence findByMiniId(String miniId) {
        Map<String, TradingSequence> sequences = loadAll();
        return sequences.get(miniId);
    }

    /**
     * Busca una secuencia por su seqId (entero secuencial).
     *
     * Hace un scan lineal del mapa porque las secuencias se indexan
     * por miniId, no por seqId. Para busquedas frecuentes por seqId,
     * considerar mantener un indice secundario.
     *
     * @param seqId ID secuencial de la secuencia a buscar
     * @return TradingSequence si existe, o null si no se encuentra
     */
    public TradingSequence findBySeqId(int seqId) {
        for (TradingSequence seq : loadAll().values()) {
            if (seq.getSeqId() == seqId) {
                return seq;
            }
        }
        return null;
    }

    /**
     * Actualiza una orden buscando por miniId (alias de updateOrder).
     *
     * @param miniId   MiniId de la secuencia
     * @param opIndice Indice de la orden (1, 2 o 3)
     * @param order    Nueva orden
     * @throws IOException si falla la escritura
     */
    public void updateOrderByMiniId(String miniId, int opIndice, SequenceOrder order) throws IOException {
        fileLock.lock();
        try {
            Map<String, TradingSequence> sequences = loadAll();
            TradingSequence seq = sequences.get(miniId);
            if (seq == null) {
                Log.warn(TAG, "Sequence not found for update: " + miniId);
                return;
            }

            switch (opIndice) {
                case 1: seq.setOp1(order); break;
                case 2: seq.setOp2(order); break;
                case 3: seq.setOp3(order); break;
            }

            sequences.put(miniId, seq);
            saveAll(sequences);
            Log.debug(TAG, "Updated order " + opIndice + " in sequence: " + miniId + " (Seq #" + seq.getSeqId() + ")");
        } finally {
            fileLock.unlock();
        }
    }

    /**
     * Elimina una secuencia por miniId (alias de delete).
     *
     * @param miniId MiniId de la secuencia a eliminar
     * @throws IOException si falla la escritura
     */
    public void deleteByMiniId(String miniId) throws IOException {
        fileLock.lock();
        try {
            Map<String, TradingSequence> sequences = loadAll();
            if (sequences.remove(miniId) != null) {
                saveAll(sequences);
                Log.debug(TAG, "Deleted sequence: " + miniId);
            } else {
                Log.warn(TAG, "Sequence not found for delete: " + miniId);
            }
        } finally {
            fileLock.unlock();
        }
    }

    /**
     * Retorna todas las secuencias almacenadas como lista.
     *
     * @return Lista con todas las TradingSequence del archivo
     */
    public List<TradingSequence> listAll() {
        return new ArrayList<>(loadAll().values());
    }

    /**
     * Retorna solo las secuencias con estado ABIERTA.
     *
     * Se usa en SequenceRecoveryManager al iniciar el bot para encontrar
     * secuencias pendientes de completar tras un reinicio.
     *
     * @return Lista de TradingSequence con estado ABIERTA
     */
    public List<TradingSequence> findOpen() {
        List<TradingSequence> open = new ArrayList<>();
        for (TradingSequence seq : loadAll().values()) {
            if (seq.getEstado() == com.arbitrage.model.EstadoSecuencia.ABIERTA) {
                open.add(seq);
            }
        }
        return open;
    }

    /**
     * Migracion de formato legacy (ya no necesaria).
     *
     * En versiones anteriores, las secuencias usaban un formato diferente
     * para los IDs. Desde que se adopto el sistema dual miniId/seqId,
     * todas las secuencias usan el formato actual y no se requiere migracion.
     *
     * Se mantiene este metodo por compatibilidad con codigo que lo invoque.
     *
     * @throws IOException nunca lanza en la implementacion actual
     */
    public void migrateLegacyToCurrent() throws IOException {
        Log.info(TAG, "Legacy migration no longer needed - all sequences use current format");
    }
}