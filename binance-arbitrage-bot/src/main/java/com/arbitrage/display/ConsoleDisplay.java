package com.arbitrage.display;

import com.arbitrage.config.AppConfig;
import com.arbitrage.model.OrderResult;
import com.arbitrage.trading.OrderExecutor;
import com.arbitrage.trading.WalletSyncManager;
import com.arbitrage.util.Log;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * ConsoleDisplay - Interfaz de usuario de consola para el bot de arbitraje triangular.
 *
 * Responsabilidades principales:
 * - Mostrar el dashboard principal con estado de conexion, balances y configuracion
 * - Mostrar resultados de operaciones en formato tabular con colores ANSI
 * - Mostrar secuencias de trading independientes con impresion atomica (sin mezcla entre hilos)
 * - Filtrar salida segun el nivel de log (SCAN=modo silencioso, INFO=modo detallado)
 *
 * Formato de la UI:
 *   ENCABEZADO:
 *     "Binance Triangular Arbitrage Bot v1.4.1"
 *     Log Level, Connection Status (TESTNET/MAINNET), Balance USDT/BNB, BalancePerOp, MinProfit
 *   ORDEN:
 *     [OpN] SYMBOL  SIDE  Qty:XXXXXXXXXX Price:XXXXXXXXXXXXXX Status:XXXXXX ElapsedTime:XXXms OrderId XXXXXXXXXX TYPE
 *   PROFIT:
 *     profit= X.XXXX%   (verde=positivo, amarillo=negativo, blanco=cero)
 *
 * Colores ANSI por estado:
 *   Verde  (ANSI_GREEN)  -> Conexion activa, orden FILLED, profit positivo
 *   Amarillo(ANSI_YELLOW)-> Profit negativo, modo MAINNET, estados no-FILLED
 *   Cyan   (ANSI_CYAN)   -> Bordes decorativos, encabezado, modo TESTNET
 *   Blanco (ANSI_WHITE)  -> Texto neutral, etiquetas descriptivas
 *
 * Thread-safety:
 *   Usa ConcurrentHashMap&lt;Integer, ReentrantLock&gt; para imprimir secuencias
 *   de forma atomica sin mezclar lineas de diferentes hilos de ejecucion.
 *
 * Implementa OrderExecutor.OpportunityDisplay para recibir callbacks
 * asincronos desde el ejecutor de ordenes.
 */
public class ConsoleDisplay implements OrderExecutor.OpportunityDisplay {

    // =====================================================================
    // CODIGOS ANSI PARA COLORES DE CONSOLA
    // Secuencias de escape ANSI para colorear la salida en terminal.
    // Soportado en: PowerShell 7, Cmder, VS Code terminal, Windows Terminal.
    //
    //   ANSI_YELLOW - Profit negativo, modo MAINNET, estados PENDING/CANCELED
    //   ANSI_GREEN  - Conexion activa, orden FILLED, profit positivo
    //   ANSI_WHITE  - Texto neutral, etiquetas de campo
    //   ANSI_CYAN   - Bordes decorativos (=====), encabezado, modo TESTNET
    //   ANSI_RESET  - Restablece al color por defecto de la terminal
    // =====================================================================
    private static final String ANSI_YELLOW = "\u001B[33m";
    private static final String ANSI_GREEN = "\u001B[32m";
    private static final String ANSI_WHITE = "\u001B[37m";
    private static final String ANSI_CYAN = "\u001B[36m";
    private static final String ANSI_RESET = "\u001B[0m";

    // Formato de hora para timestamps en dashboard (ej. "14:30:15")
    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm:ss");

    // =====================================================================
    // ESTADO INTERNO DEL DISPLAY
    // Variables que reflejan el estado actual del bot para mostrar en UI.
    // Se actualizan desde otros componentes via setters o callbacks.
    //
    //   connectionActive   - true si WebSocket conectado, false si inactivo
    //   isTestnet          - true=TESTNET, false=MAINNET (cambia endpoint API)
    //   balanceUSDT        - Balance disponible en USDT
    //   balanceBNB         - Balance disponible en BNB
    //   bnbPrice           - Precio de BNB en USDT (para calcular valor total)
    //   balancePerOp       - Cantidad de USDT asignada por operacion (AppConfig)
    //   minProfit          - Profit minimo % para considerar oportunidad valida
    //   opportunityCount   - Contador de oportunidades detectadas
    //   logLevel           - Nivel de logging activo (DEBUG|SCAN|INFO|WARN|ERROR)
    //   sequenceLocks      - Locks por sequenceId para impresion atomica thread-safe
    // =====================================================================
    private boolean connectionActive = false;
    private boolean isTestnet = false;
    private double balanceUSDT = 0.0;
    private double balanceBNB = 0.0;
    private double bnbPrice = 0.0;
    private double balancePerOp = 0.0;
    private double minProfit = 0.0;
    private int opportunityCount = 0;
    private String logLevel = "INFO";
    private final ConcurrentHashMap<Integer, ReentrantLock> sequenceLocks = new ConcurrentHashMap<>();
    private WalletSyncManager walletSyncManager;

    /**
     * Muestra el dashboard inicial del bot en la consola.
     * Imprime el encabezado decorativo con bordes cyan, version del bot,
     * nivel de log, estado de conexion, entorno (TESTNET/MAINNET),
     * balances actuales (USDT y BNB con valor en USDT), cantidad asignada
     * por operacion y profit minimo configurado desde AppConfig.
     *
     * @param config Objeto AppConfig con parametros de configuracion del bot
     * @param connectionActive true si el WebSocket esta conectado
     * @param isTestnet true si opera en testnet de Binance
     * @param balanceUSDT Balance actual disponible en USDT
     * @param balanceBNB Balance actual disponible en BNB
     * @param bnbPrice Precio actual de BNB cotizado en USDT
     * @param logLevel Nivel de logging activo (DEBUG|SCAN|INFO|WARN|ERROR)
     */
    public void showDashboard(AppConfig config, boolean connectionActive, boolean isTestnet, 
                        double balanceUSDT, double balanceBNB, double bnbPrice, String logLevel) {
        // Guarda valores
        this.connectionActive = connectionActive;
        this.isTestnet = isTestnet;
        this.balanceUSDT = balanceUSDT;
        this.balanceBNB = balanceBNB;
        this.bnbPrice = bnbPrice;
        this.balancePerOp = config.getBalancePerTrade();
        this.minProfit = config.getMinProfit();
        this.logLevel = logLevel != null ? logLevel : "INFO";

        // Imprime header
        printDashboardHeader();
        printDashboardSeparator();
    }

    /**
     * Imprime header del dashboard.
     */
    private void printDashboardHeader() {
        String connStatus = connectionActive ? "ACTIVE" : "INACTIVE";
        String connColor = connectionActive ? ANSI_GREEN : ANSI_YELLOW;
        String envStr = isTestnet ? "TESTNET" : "MAINNET";
        String envColor = isTestnet ? ANSI_CYAN : ANSI_YELLOW;

        System.out.println();
        System.out.println(ANSI_CYAN + "================================================================================" + ANSI_RESET);
        System.out.println(ANSI_CYAN + "  Binance Triangular Arbitrage Bot v1.5.1" + ANSI_RESET);
        System.out.println(ANSI_CYAN + "================================================================================" + ANSI_RESET);
        
        System.out.println(ANSI_WHITE + "  Log Level   : " + ANSI_YELLOW + logLevel + ANSI_RESET);
        System.out.println(ANSI_WHITE + "  Dashboard: Top " + opportunityCount + " Opportunities (minProfit: " + String.format("%.4f", minProfit) + "%)");
        System.out.println();
        
        System.out.println(ANSI_WHITE + "  Connection Status: " + connColor + connStatus + ANSI_WHITE + " (" + envColor + envStr + ANSI_WHITE + ")");
        System.out.println(ANSI_WHITE + "  Balance USDT : " + ANSI_GREEN + String.format("%.8f", balanceUSDT) + ANSI_WHITE + " USDT");
        
        // Valor de BNB en USDT
        double bnbValueUsdt = balanceBNB * bnbPrice;
        System.out.println(ANSI_WHITE + "  Balance BNB  : " + ANSI_WHITE + String.format("%.8f", balanceBNB) + ANSI_WHITE + " BNB (" + ANSI_GREEN + "$" + String.format("%.2f", bnbValueUsdt) + ANSI_WHITE + " USDT)");
        
        System.out.println(ANSI_WHITE + "  BalancePerOp: " + ANSI_YELLOW + String.format("%.2f", balancePerOp) + ANSI_WHITE + " USDT");
        
        System.out.println(ANSI_CYAN + "================================================================================" + ANSI_RESET);
    }

    /**
     * Imprime separador.
     */
    private void printDashboardSeparator() {
        System.out.println(ANSI_CYAN + "================================================================================" + ANSI_RESET);
    }

    /**
     * Actualiza contador de oportunidades.
     */
    public void updateOpportunityCount(int count) {
        this.opportunityCount = count;
    }

    /**
     * Muestra resultado de una oportunidad.
     * Implementa OpportunityDisplay.show()
     * 
     * @param orderId ID de la operacion
     * @param timestamp Cuando se ejecuto
     * @param op1 Resultado primera orden
     * @param op2 Resultado segunda orden
     * @param op3 Resultado tercera orden
     * @param profitPct Profit porcentual
     * @param live Si es orden real
     */
    public void show(int orderId, long timestamp,
                    OrderResult op1, OrderResult op2, OrderResult op3,
                    double profitPct, boolean live) {

        // Filtro por nivel de log:
        // - SCAN: solo muestra profit > 0 (oportunidades positivas)
        // - INFO: muestra todo (profit > 0 y profit <= 0)
        
        String currentLevel = Log.getCurrentLevel();
        boolean isScanMode = "SCAN".equals(currentLevel);
        
        // En modo SCAN: solo profit positivo
        if (isScanMode) {
            if (profitPct <= 0) {
                return;  // Silencia profits negativos o cero
            }
        } else {
            // En modo INFO (o superior): mostrar todo
            if (!Log.isInfoEnabled()) {
                return;
            }
        }

        // Convierte timestamp a hora local
        LocalDateTime dateTime = LocalDateTime.ofInstant(
            Instant.ofEpochMilli(timestamp),
            ZoneId.systemDefault()
        );

        // Formatea
        String mode = live ? "LIVE" : "SIMULATED";
        String profitColor = profitPct > 0 ? ANSI_GREEN : (profitPct < 0 ? ANSI_YELLOW : ANSI_WHITE);
        String formattedTime = dateTime.format(TIME_FORMAT);

        // Imprime header de orden
        System.out.println();
        System.out.println(ANSI_YELLOW + "  OrderId (#" + orderId + ") --> " + formattedTime + " (" + mode + ")" + ANSI_RESET);
        
        // Imprime cada operacion
        System.out.println(ANSI_WHITE + formatOrderLine(op1) + ANSI_RESET);
        System.out.println(ANSI_WHITE + formatOrderLine(op2) + ANSI_RESET);
        System.out.println(ANSI_WHITE + formatOrderLine(op3) + ANSI_RESET);
        
        // Imprime profit
        System.out.println(ANSI_WHITE + "  profit= " + profitColor + String.format("%.4f", profitPct) + "%" + ANSI_RESET);
        System.out.println();
    }

    /**
     * Formatea una linea de orden.
     */
    private String formatOrderLine(OrderResult order) {
        // Formatea cada campo
        String symbol = String.format("%-8s", order.getSymbol());
        String side = String.format("%-4s", order.getSide());
        String qty = formatQuantity(order.getQuantity());
        qty = String.format("%12s", qty);
        String price = formatPrice(order.getPrice());
        price = String.format("%14s", price);
        
        // Estado con color
        String status = order.getStatus();
        String statusColored = "FILLED".equals(status) ? ANSI_GREEN + status + ANSI_WHITE : status;
        
        // Tiempo y orden
        String elapsed = order.getElapsedTime() + "ms";
        elapsed = String.format("%-10s", elapsed);
        String orderId = String.format("%-10s", order.getOrderId());
        String orderType = order.getOrderType();

        return "  " + symbol + " " + side + "  Qty:" + qty + " Price:" + price +
               " Status:" + statusColored + "   ElapsedTime:" + elapsed + " OrderId " + orderId + " " + orderType;
    }

    /**
     * Formatea cantidad (quita 0 inicial si es < 1).
     */
    private String formatQuantity(double qty) {
        String formatted = String.format("%.8f", qty);
        if (formatted.startsWith("0.")) {
            return formatted.substring(1);
        }
        return formatted;
    }

    /**
     * Formatea precio.
     */
    private String formatPrice(double price) {
        return String.format("%.8f", price);
    }

    /**
     * Formatea tiempo.
     */
    private String formatTime(long timestamp) {
        LocalDateTime dateTime = LocalDateTime.ofInstant(
            Instant.ofEpochMilli(timestamp),
            ZoneId.systemDefault()
        );
        return dateTime.format(TIME_FORMAT);
    }

    // =====================================================================
    // NUEVOS METODOS PARA SECUENCIAS INDEPENDIENTES
    // =====================================================================

    /**
     * Muestra inicio de secuencia.
     * Formato: SeqId (#5) --> 09:37:09 (SIMULATED)
     */
    public void showStart(int sequenceId, long timestamp, double profitPct, boolean live) {
        String time = formatTime(timestamp);
        String mode = live ? "LIVE" : "SIMULATED";
        
        System.out.println();
        System.out.println("Seq #" + sequenceId + " --> " + time + " (" + mode + ")");
    }

    /**
     * Muestra orden en estado PENDING.
     * Formato:   [Seq:#1] BTCUSDT  BUY   Qty:   .00001225 Price:81634.45000000 Status:PENDING  ElapsedTime:--       OrderId ------    MARKET
     */
    public void showOrderPending(int seqId, int opNum, String symbol, String side, double qty, double price, String orderType) {
        String qtyStr = formatQuantity(qty);
        String priceStr = formatPrice(price);
        
        System.out.println("  [Op" + opNum + "] " + String.format("%-8s", symbol) + " " + String.format("%-4s", side) + 
            "   Qty: " + String.format("%12s", qtyStr) + " Price: " + String.format("%14s", priceStr) + 
            " Status:PENDING" + "   ElapsedTime:--       " + " OrderId ------    " + orderType);
    }

    /**
     * Muestra orden en estado FILLED.
     * Formato:   [Seq:#1] BTCUSDT  BUY   Qty:   .00001225 Price:81634.45000000 Status:FILLED   ElapsedTime:48ms       OrderId 9978256    MARKET
     */
    public void showOrderFilled(int seqId, int opNum, OrderResult r) {
        String qtyStr = formatQuantity(r.getQuantity());
        String priceStr = formatPrice(r.getPrice());
        String statusStr = r.getStatus();
        String statusColor = "FILLED".equals(statusStr) ? ANSI_GREEN : (("CANCELED".equals(statusStr) || "REJECTED".equals(statusStr) || "EXPIRED".equals(statusStr)) ? ANSI_YELLOW : ANSI_WHITE);
        
        System.out.println("  [Op" + opNum + "] " + String.format("%-8s", r.getSymbol()) + " " + String.format("%-4s", r.getSide()) + 
            "   Qty: " + String.format("%12s", qtyStr) + " Price: " + String.format("%14s", priceStr) + 
            " Status:" + statusColor + statusStr + ANSI_WHITE + 
            "   ElapsedTime:" + r.getElapsedTime() + "ms       " + " OrderId " + 
            String.format("%-10s", r.getOrderId()) + "    " + r.getOrderType());
    }

    /**
     * Muestra resultado final de secuencia.
     * Formato:   profit= -0.2332%
     */
    public void showEnd(int sequenceId, boolean success, double profitPct) {
        String profitColor = profitPct > 0 ? ANSI_GREEN : (profitPct < 0 ? ANSI_YELLOW : ANSI_WHITE);
        
        System.out.println("  profit= " + profitColor + String.format("%.4f", profitPct) + "%" + ANSI_RESET);
        System.out.println();
    }

    /**
     * Imprime una secuencia completa de forma atomica (sin mezcla con otras secuencias).
     */
    public void printSequenceAtomic(int sequenceId, long timestamp, boolean live,
                                     List<OrderResult> orders, double profitPct) {
        ReentrantLock lock = sequenceLocks.computeIfAbsent(sequenceId, k -> new ReentrantLock());
        
        lock.lock();
        try {
            showStart(sequenceId, timestamp, profitPct, live);
            int opNum = 1;
            for (OrderResult r : orders) {
                showOrderFilled(sequenceId, opNum++, r);
            }
            showEnd(sequenceId, true, profitPct);
        } finally {
            lock.unlock();
            sequenceLocks.remove(sequenceId);
        }
    }

    // =====================================================================
    // WALLET SYNC - Sincronizacion de balances en tiempo real
    // Se registra como listener de WalletSyncManager para recibir
    // actualizaciones push sin necesidad de polling periodico.
    // =====================================================================

    /**
     * Configura el WalletSyncManager y se suscribe a sus actualizaciones.
     * Al asignarlo, sincroniza inmediatamente los balances iniciales.
     * Si wsm es null, simplemente no hay sincronizacion de wallet.
     *
     * @param wsm Instancia de WalletSyncManager, o null si no se usa
     */
    public void setWalletSyncManager(WalletSyncManager wsm) {
        this.walletSyncManager = wsm;
        if (wsm != null) {
            wsm.addListener(this::onBalancesUpdated);
            setBalances(wsm.getUsdtBalance(), wsm.getBnbBalance(), wsm.getBnbPrice());
        }
    }

    /**
     * Callback invocado por WalletSyncManager cuando los balances cambian.
     * Actualiza las variables internas para que el dashboard refleje
     * el estado actual de la wallet en tiempo real, sin polling.
     *
     * @param usdt Nuevo balance de USDT
     * @param bnb Nuevo balance de BNB
     * @param bnbPrice Nuevo precio de BNB en USDT
     */
    private void onBalancesUpdated(double usdt, double bnb, double bnbPrice) {
        this.balanceUSDT = usdt;
        this.balanceBNB = bnb;
        this.bnbPrice = bnbPrice;
    }

    // =====================================================================
    // SETTERS - Metodos para actualizar el estado interno del display
    // desde otros componentes del bot (hilos de WebSocket, engine, etc.)
    // =====================================================================

    /**
     * Actualiza el estado de conexion del WebSocket en el dashboard.
     *
     * @param active true si el WebSocket esta conectado y recibiendo datos
     */
    public void setConnectionStatus(boolean active) {
        this.connectionActive = active;
    }

    /**
     * Configura el modo de operacion mostrado en el dashboard.
     *
     * @param testnet true para mostrar TESTNET, false para MAINNET
     */
    public void setTestnet(boolean testnet) {
        this.isTestnet = testnet;
    }

    /**
     * Actualiza los balances mostrados en el dashboard.
     *
     * @param usdt Balance actual de USDT
     * @param bnb Balance actual de BNB
     * @param bnbPrice Precio actual de BNB en USDT
     */
    public void setBalances(double usdt, double bnb, double bnbPrice) {
        this.balanceUSDT = usdt;
        this.balanceBNB = bnb;
        this.bnbPrice = bnbPrice;
    }
}