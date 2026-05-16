package com.arbitrage.config;

import lombok.Builder;
import lombok.Data;

/**
 * Modelo que encapsula toda la configuracion general del bot de arbitraje.
 * <p>
 * Se construye mediante {@link ConfigLoader#loadAppConfig(String)} a partir de un
 * archivo de texto con formato {@code clave valor}. Los valores numericos se 
 * parsean como double/int/long segun el campo, y los booleanos con 
 * {@link Boolean#parseBoolean(String)}.
 * <p>
 * Los campos marcados con {@code @Builder.Default} tienen valores predefinidos
 * que se usan cuando la clave no aparece en el archivo de configuracion.
 */
@Data
@Builder
public class AppConfig {
    // =====================================================================
    // MONEDA BASE
    // Define la moneda contra la que se miden todos los profits y balances.
    // Todos los triangulos parten y vuelven a esta moneda. Normalmente USDT.
    // =====================================================================
    private String baseCurrency;     // Ej: "USDT", "BUSD", etc.
    
    // =====================================================================
    // UMBRALES DE PROFITABILIDAD
    // Definen la ventana de profit aceptable para una operacion de arbitraje.
    // minProfit es el piso minimo para ejecutar; maxProfit filtra salidas anomalas.
    // =====================================================================
    private double minProfit;         // Profit minimo (%): si el calculo da menos, se ignora
    private double maxProfit;         // Profit maximo (%): si da mas, se descarta como error de calculo
    
    // =====================================================================
    // COMISIONES DE CADA OPERACION DEL TRIANGULO
    // Se restan del profit bruto para obtener el profit neto real.
    // Cada operacion (Op1, Op2, Op3) puede tener distinta comision segun el par.
    // =====================================================================
    private double feeOp1;          // Comision (%) de la primera pata del triangulo
    private double feeOp2;          // Comision (%) de la segunda pata
    private double feeOp3;          // Comision (%) de la tercera pata
    
    // =====================================================================
    // PARAMETROS DE EJECUCION Y RIESGO
    // Controlan cuanto capital se arriesga por operacion y el volumen de seguridad.
    // =====================================================================
    private double safetyVolume;      // Volumen minimo requerido en el libro de ordenes para ejecutar
    private int cores;                // Hilos de procesamiento para paralelizar el scan de triangulos
    private double balancePerTrade;   // Cantidad de moneda base asignada por operacion de arbitraje
    private double stoplossFactor;    // Factor multiplicador para calcular el stop loss
    
    // =====================================================================
    // ESTRATEGIA Y CONTROL DE OPERACIONES
    // Configuracion de la estrategia activa, limites de concurrencia y blacklist.
    // =====================================================================
    private String strategy;           // Nombre de la estrategia de trading (reservado para expansion futura)
    private String logs;               // Configuracion del sistema de logs (reservado)
    private int maxOpenTrades;         // Maximo de operaciones de arbitraje ejecutandose simultaneamente
    private double profitReversalOp1;  // Profit de reversal para la operacion 1 (reservado)
    private String blacklist;          // Simbolos separados por coma que se excluyen del scan
    
    // =====================================================================
    // MODOS ESPECIALES Y TIPOS DE ORDEN
    // modeHF activa el modo de alta frecuencia. Los typeOp definen si cada
    // operacion del triangulo se ejecuta como MARKET o LIMIT.
    // =====================================================================
    private boolean modeHF;          // Modo High Frequency: escanea mas rapido (reservado)
    private String typeOp1;          // Tipo de orden para la operacion 1: "MARKET" o "LIMIT"
    private String typeOp2;          // Tipo de orden para la operacion 2
    private String typeOp3;          // Tipo de orden para la operacion 3
    
    // =====================================================================
    // ENTORNO DE EJECUCION
    // realorder=false = modo simulacion (no se envian ordenes reales a Binance).
    // logLevel controla la verbosidad de la salida en consola.
    // =====================================================================
    private boolean realorder;       // true = envía ordenes reales a Binance; false = solo simula
    private String logLevel;         // Nivel de logging: DEBUG, SCAN, INFO, WARN, ERROR
    
    // =====================================================================
    // MODO TEST SERVER
    // true = ejecuta benchmarks de velocidad de órdenes contra testnet,
    //        no realiza triangulaciones de arbitraje.
    // false = funcionamiento normal del bot (arbitraje).
    // =====================================================================
    @Builder.Default
    private boolean testserver = false;  // true = benchmark mode, false = normal operation
    
    // =====================================================================
    // SINCRONIZACION DE WALLET
    // Cada tantos ms se consulta el balance actualizado de la wallet en Binance.
    // =====================================================================
    @Builder.Default
    private long walletSyncIntervalMs = 60000; // Cada 60s sincroniza saldos con Binance (ms)

    // =====================================================================
    // PERSISTENCIA DE SECUENCIAS
    // Archivos donde se guarda el estado de las operaciones para recuperacion
    // ante caidas. sequences.seq contiene las ordenes; events.seq los eventos.
    // =====================================================================
    @Builder.Default
    private long pollingIntervalMs = 500;      // Cada 500ms revisa estado de ordenes abiertas (ms)
    @Builder.Default
    private long orderTimeoutMs = 120000;      // Si una orden no se completa en 120s, se cancela (ms)
    @Builder.Default
    private String sequencesFile = "sequences.seq";  // Archivo de persistencia de secuencias
    @Builder.Default
    private String eventsFile = "sequences.events";  // Archivo de persistencia de eventos

    // =====================================================================
    // GESTION DE RIESGO — LIMITE DIARIO DE PERDIDAS
    // Si se alcanza el limite, el bot detiene automaticamente las operaciones
    // para evitar perdidas mayores en un mismo dia.
    // =====================================================================
    @Builder.Default
    private double dailyLossLimit = 50.0;            // Perdida maxima acumulada en un dia (USD)
    @Builder.Default
    private boolean dailyLossCheckEnabled = true;    // Activa/desactiva la verificacion de perdida diaria
    @Builder.Default
    private long dailyLossCheckIntervalMs = 60000;   // Cada 60s verifica si se alcanzo el limite (ms)

    // =====================================================================
    // VERIFICACION DE BNB PARA DESCUENTO DE COMISIONES
    // Si checkBNBBalance=true, el bot verifica antes de cada triangulo
    // que el balance de BNB sea suficiente para mantener el descuento del 25%.
    // Si BNB < minBNBBalance, muestra alerta ROJA pero continua operando.
    // =====================================================================
    @Builder.Default
    private boolean checkBNBBalance = true;          // Activa/desactiva verificacion de BNB
    @Builder.Default
    private double minBNBBalance = 0.01;             // Balance minimo de BNB requerido
}
