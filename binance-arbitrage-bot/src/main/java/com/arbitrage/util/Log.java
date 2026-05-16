package com.arbitrage.util;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;

/**
 * Log - Sistema de logging personalizado para el bot de arbitraje.
 *
 * A diferencia de SLF4J/Logback (deliberadamente no usados en este proyecto),
 * implementa un logging ligero con 5 niveles jerarquicos sin dependencias externas.
 *
 * Jerarquia de niveles (cada nivel incluye todos los inferiores):
 *   ERROR (0) - Solo errores criticos que requieren atencion inmediata
 *   WARN  (1) - Advertencias y errores recuperables
 *   INFO  (2) - Informacion general de operacion (nivel por defecto)
 *   SCAN  (3) - Oportunidades de arbitraje detectadas (modo silencioso)
 *   DEBUG (4) - Mensajes de depuracion detallados para desarrollo
 *
 * Formato de salida:
 *   [TAG] mensaje
 * Donde TAG es un identificador de 3-4 caracteres que indica el componente
 * emisor (API, Engine, ORDER_EXEC, SEQ_FILE, STATS_MGR, etc.).
 *
 * Modo SCAN:
 *   Disenado especificamente para este bot de arbitraje. Cuando el nivel
 *   se establece a SCAN, solo se muestran oportunidades con profit positivo.
 *   Los profits <= 0 se silencian, dando una interfaz limpia y enfocada.
 *
 * Configuracion:
 *   El nivel se define en el archivo .config con la clave "logLevel".
 *   Valores aceptados: DEBUG, SCAN, INFO, WARN, ERROR (case-insensitive).
 *   Si no se especifica, el nivel por defecto es INFO.
 *
 * NOTA: Los metodos print() y printRaw() tienen comportamiento especial:
 *   print()     - Imprime siempre, sin filtro de nivel
 *   printRaw()  - Imprime solo si el nivel activo es DEBUG
 */
public class Log {

    // =====================================================================
    // NIVELES DE LOG - Mapa nivel->valor numerico para comparacion jerarquica
    // =====================================================================
    private static final Map<String, Integer> LEVELS = new HashMap<>();
    private static String LEVEL = "INFO";          // Nivel activo actual (default: INFO)
    private static boolean initialized = false;     // Flag de inicializacion

    // Formato de timestamp para mensajes (ej. "14:30:15.123")
    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm:ss.SSS");

    // =====================================================================
    // DEFINICION DE NIVELES JERARQUICOS
    // Valores numericos: mayor numero = mayor verbosidad (incluye niveles inferiores)
    //   ERROR=0  -> Solo muestra ERROR
    //   WARN=1   -> ERROR + WARN
    //   INFO=2   -> ERROR + WARN + INFO (default)
    //   SCAN=3   -> ERROR + WARN + INFO + SCAN
    //   DEBUG=4  -> ERROR + WARN + INFO + SCAN + DEBUG (todo)
    // =====================================================================
    static {
        LEVELS.put("ERROR", 0);
        LEVELS.put("WARN", 1);
        LEVELS.put("INFO", 2);
        LEVELS.put("SCAN", 3);
        LEVELS.put("DEBUG", 4);
    }

    /**
     * Inicializa el sistema de logs con el nivel especificado.
     * Convierte el nivel a mayusculas y valida que no sea nulo/vacio.
     * Si el nivel es invalido o nulo, usa INFO como predeterminado.
     *
     * @param level Nivel de log deseado (DEBUG, SCAN, INFO, WARN, ERROR)
     */
    public static void init(String level) {
        LEVEL = (level != null && !level.isEmpty()) ? level.toUpperCase() : "INFO";
        initialized = true;
    }

    /**
     * Obtiene el valor numerico asociado al nivel de log actual.
     * Permite comparar niveles usando operadores >= y <=.
     * Si el nivel no existe en el mapa, retorna 2 (equivalente a INFO).
     *
     * @return Valor entero del nivel activo (0-4)
     */
    private static int getLevelValue() {
        return LEVELS.getOrDefault(LEVEL, 2);
    }

    // =====================================================================
    // CHECKS DE NIVEL
    // =====================================================================
    public static boolean isDebugEnabled() {
        return getLevelValue() >= LEVELS.get("DEBUG");
    }

    public static boolean isScanEnabled() {
        return getLevelValue() >= LEVELS.get("SCAN");
    }

    public static boolean isInfoEnabled() {
        return getLevelValue() >= LEVELS.get("INFO");
    }

    public static boolean isWarnEnabled() {
        return getLevelValue() >= LEVELS.get("WARN");
    }

    public static boolean isErrorEnabled() {
        return getLevelValue() >= LEVELS.get("ERROR");
    }

    // =====================================================================
    // METODOS DE LOG
    // =====================================================================
    
    // DEBUG
    public static void debug(String msg) {
        if (isDebugEnabled()) {
            print("DEBUG", msg);
        }
    }

    public static void debug(String tag, String msg) {
        if (isDebugEnabled()) {
            print("DEBUG", "[" + tag + "] " + msg);
        }
    }

    // SCAN (oportunidades)
    public static void scan(String msg) {
        if (isScanEnabled()) {
            print("SCAN", msg);
        }
    }

    public static void scan(String tag, String msg) {
        if (isScanEnabled()) {
            print("SCAN", "[" + tag + "] " + msg);
        }
    }

    // INFO
    public static void info(String msg) {
        if (isInfoEnabled()) {
            print("INFO", msg);
        }
    }

    public static void info(String tag, String msg) {
        if (isInfoEnabled()) {
            print("INFO", "[" + tag + "] " + msg);
        }
    }

    // WARN
    public static void warn(String msg) {
        if (isWarnEnabled()) {
            print("WARN", msg);
        }
    }

    public static void warn(String tag, String msg) {
        if (isWarnEnabled()) {
            print("WARN", "[" + tag + "] " + msg);
        }
    }

    // ERROR
    public static void error(String msg) {
        if (isErrorEnabled()) {
            print("ERROR", msg);
        }
    }

    public static void error(String tag, String msg) {
        if (isErrorEnabled()) {
            print("ERROR", "[" + tag + "] " + msg);
        }
    }

    // =====================================================================
    // OUTPUT
    // =====================================================================
    
    /**
     * Imprime sin formato.
     */
    public static void print(String msg) {
        System.out.println(msg);
    }

    /**
     * Imprime un mensaje con el nivel especificado, sin timestamp.
     * El mensaje solo se muestra si el nivel del mensaje es menor o igual
     * al nivel activo (ej. con nivel INFO, solo pasan ERROR, WARN, INFO).
     * Disenado asi para modo SCAN: mensajes limpios sin clutter de timestamp.
     *
     * @param level Nivel del mensaje (ERROR|WARN|INFO|SCAN|DEBUG)
     * @param msg Contenido del mensaje a imprimir
     */
    private static void print(String level, String msg) {
        int msgLevel = LEVELS.getOrDefault(level, 2);
        if (msgLevel <= getLevelValue()) {
            System.out.println(msg);
        }
    }

    /**
     * Imprime un mensaje sin formato solo si el nivel activo es DEBUG.
     * Util para depuracion temporal sin modificar callers existentes.
     *
     * @param msg Mensaje a imprimir (solo visible en modo DEBUG)
     */
    public static void printRaw(String msg) {
        if (getLevelValue() >= LEVELS.get("DEBUG")) {
            System.out.println(msg);
        }
    }

    /**
     * Obtiene el nivel de log activo actual.
     *
     * @return String del nivel activo (DEBUG|SCAN|INFO|WARN|ERROR)
     */
    public static String getCurrentLevel() {
        return LEVEL;
    }
}