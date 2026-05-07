package com.arbitrage.util;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;

/**
 * Sistema de logging personalizado.
 * Provee 5 niveles de log con control por configuracion.
 */
public class Log {

    // =====================================================================
    // NIVELES DE LOG
    // =====================================================================
    private static final Map<String, Integer> LEVELS = new HashMap<>();
    private static String LEVEL = "INFO";
    private static boolean initialized = false;
    
    // Formato de timestamp
    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm:ss.SSS");

    // =====================================================================
    // DEFINICION DE NIVELES
    // =====================================================================
    static {
        LEVELS.put("ERROR", 0);   // Solo errores
        LEVELS.put("WARN", 1);    // Warnings+
        LEVELS.put("INFO", 2);     // Info+ (default)
        LEVELS.put("SCAN", 3);    // Scan+ (muestra oportunidades)
        LEVELS.put("DEBUG", 4);   // Todo
    }

    /**
     * Inicializa el sistema de logs.
     * @param level Nivel de log (DEBUG, SCAN, INFO, WARN, ERROR)
     */
    public static void init(String level) {
        LEVEL = (level != null && !level.isEmpty()) ? level.toUpperCase() : "INFO";
        initialized = true;
    }

    /**
     * Obtiene valor numerico del nivel actual.
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
     * Imprime con nivel pero sin timestamp (mas limpio para SCAN mode).
     */
    private static void print(String level, String msg) {
        int msgLevel = LEVELS.getOrDefault(level, 2);
        if (msgLevel <= getLevelValue()) {
            System.out.println(msg);
        }
    }

    /**
     * Imprime raw (sin timestamp) si el nivel lo permite.
     */
    public static void printRaw(String msg) {
        if (getLevelValue() >= LEVELS.get("DEBUG")) {
            System.out.println(msg);
        }
    }

    /**
     * Obtiene nivel actual.
     */
    public static String getCurrentLevel() {
        return LEVEL;
    }
}