package com.arbitrage.display;

import com.arbitrage.config.AppConfig;
import com.arbitrage.model.OrderResult;
import com.arbitrage.trading.OrderExecutor;
import com.arbitrage.util.Log;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Display de consola para el bot de arbitraje.
 * Muestra dashboard y resultados de operaciones.
 * Implementa OpportunityDisplay para recibir Ordenes.
 */
public class ConsoleDisplay implements OrderExecutor.OpportunityDisplay {

    // =====================================================================
    // CODIGOS ANSI PARA COLORES
    // =====================================================================
    private static final String ANSI_YELLOW = "\u001B[33m";
    private static final String ANSI_GREEN = "\u001B[32m";
    private static final String ANSI_WHITE = "\u001B[37m";
    private static final String ANSI_CYAN = "\u001B[36m";
    private static final String ANSI_RESET = "\u001B[0m";

    // Formato de hora
    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm:ss");

    // =====================================================================
    // ESTADO
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

    /**
     * Muestra el dashboard inicial.
     * @param config Configuracion
     * @param connectionActive Si esta conectado
     * @param isTestnet Si es testnet
     * @param balanceUSDT Balance USDT
     * @param balanceBNB Balance BNB
     * @param bnbPrice Precio BNB
     * @param logLevel Nivel de log
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
        System.out.println(ANSI_CYAN + "  Binance Triangular Arbitrage Bot v1.0.0" + ANSI_RESET);
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
        System.out.println("SeqId (#" + sequenceId + ") --> " + time + " (" + mode + ")");
    }

    /**
     * Muestra orden en estado PENDING.
     * Formato:   BTCUSDT  BUY   Qty:   .00001225 Price:81634.45000000 Status:PENDING  ElapsedTime:--       OrderId ------    MARKET
     */
    public void showOrderPending(int opNum, String symbol, String side, double qty, double price, String orderType) {
        String qtyStr = formatQuantity(qty);
        String priceStr = formatPrice(price);
        
        System.out.println("  " + String.format("%-8s", symbol) + " " + String.format("%-4s", side) + 
            "   Qty: " + String.format("%12s", qtyStr) + " Price: " + String.format("%14s", priceStr) + 
            " Status:PENDING" + "   ElapsedTime:--       " + " OrderId ------    " + orderType);
    }

    /**
     * Muestra orden en estado FILLED.
     * Formato:   BTCUSDT  BUY   Qty:   .00001225 Price:81634.45000000 Status:FILLED   ElapsedTime:48ms       OrderId 9978256    MARKET
     */
    public void showOrderFilled(OrderResult r) {
        String qtyStr = formatQuantity(r.getQuantity());
        String priceStr = formatPrice(r.getPrice());
        String statusStr = r.getStatus();
        String statusColor = "FILLED".equals(statusStr) ? ANSI_GREEN : (("CANCELED".equals(statusStr) || "REJECTED".equals(statusStr) || "EXPIRED".equals(statusStr)) ? ANSI_YELLOW : ANSI_WHITE);
        
        System.out.println("  " + String.format("%-8s", r.getSymbol()) + " " + String.format("%-4s", r.getSide()) + 
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
            for (OrderResult r : orders) {
                showOrderFilled(r);
            }
            showEnd(sequenceId, true, profitPct);
        } finally {
            lock.unlock();
            sequenceLocks.remove(sequenceId);
        }
    }

    // =====================================================================
    // SETTERS
    // =====================================================================
    public void setConnectionStatus(boolean active) {
        this.connectionActive = active;
    }

    public void setTestnet(boolean testnet) {
        this.isTestnet = testnet;
    }

    public void setBalances(double usdt, double bnb, double bnbPrice) {
        this.balanceUSDT = usdt;
        this.balanceBNB = bnb;
        this.bnbPrice = bnbPrice;
    }
}