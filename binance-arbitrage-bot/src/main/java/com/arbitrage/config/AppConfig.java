package com.arbitrage.config;

import lombok.Builder;
import lombok.Data;

/**
 * Configuracion de la aplicacion de arbitraje.
 * Parametros generales para el trading y motor de arbitraje.
 */
@Data
@Builder
public class AppConfig {
    // =====================================================================
    // CONFIGURACION DE MONEDA
    // =====================================================================
    private String baseCurrency;     // Moneda base para arbitraje (ej: "USDT")
    
    // =====================================================================
    // UMBRALES DE PROFITABILIDAD
    // =====================================================================
    private double minProfit;         // Profit minimo para considerar oportunidad (%)
    private double maxProfit;       // Profit maximo esperado (para filtrar errores)
    
    // =====================================================================
    // COMISIONES DE CADA OPERACION
    // =====================================================================
    private double feeOp1;          // Comision (%) operacion 1 (ej: 0.1)
    private double feeOp2;          // Comision (%) operacion 2
    private double feeOp3;          // Comision (%) operacion 3
    
    // =====================================================================
    // PARAMETROS DE TRADING
    // =====================================================================
    private double safetyVolume;      // Volumen minimo en libro de ordenes
    private int cores;            // Numero de cores para paralelismo
    private double balancePerTrade; // Balance usado por operacion de arbitraje
    private double stoplossFactor;  // Factor para stop loss
    
    // =====================================================================
    // ESTRATEGIA Y LOGS
    // =====================================================================
    private String strategy;         // Estrategia de trading (reservado)
    private String logs;          // Configuracion de logs (reservado)
    private int maxOpenTrades;    // Maximo de operaciones simultaneas
    private double profitReversalOp1; // Profit reversal para op1 (reservado)
    private String blacklist;      // Lista de simbolos a ignorar
    
    // =====================================================================
    // MODOS ESPECIALES
    // =====================================================================
    private boolean modeHF;       // Modo High Frequency (reservado)
    private String typeOp1;      // Tipo orden op1 (MARKET, LIMIT)
    private String typeOp2;       // Tipo orden op2
    private String typeOp3;       // Tipo orden op3
    
    // =====================================================================
    // MODO REAL/SIMULADO
    // =====================================================================
    private boolean realorder;      // true = ordenes reales, false = simulado
    private String logLevel;       // Nivel de log (DEBUG, SCAN, INFO, WARN, ERROR)
    
    // =====================================================================
    // SECUENCIA/PERSISTENCIA
    // =====================================================================
    @Builder.Default
    private long pollingIntervalMs = 500;     // Intervalo de polling en ms
    @Builder.Default
    private long orderTimeoutMs = 60000;  // Timeout de orden en ms
    @Builder.Default
    private String sequencesFile = "sequences.seq";
    @Builder.Default
    private String eventsFile = "sequences.events";
}