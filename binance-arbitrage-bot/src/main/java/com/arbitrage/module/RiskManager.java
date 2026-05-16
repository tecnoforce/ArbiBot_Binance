package com.arbitrage.module;

import com.arbitrage.config.AppConfig;
import com.arbitrage.model.ArbitrageOpportunity;
import com.arbitrage.model.SequenceOrder;
import com.arbitrage.model.Triangle;
import com.arbitrage.trading.WalletSyncManager;
import com.arbitrage.util.Log;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

/**
 * RiskManager - Control de riesgo y limites de exposicion.
 *
 * Responsabilidades:
 * - Validar oportunidades antes de ejecucion (gatekeeper)
 * - Tracking de exposicion por activo (evitar sobreconcentracion)
 * - Control de perdida diaria (daily loss limit)
 * - Calculo de drawdown desde el peak de balance
 * - Emergency stop (parada de emergencia manual o automatica)
 * - Sincronizacion con WalletSyncManager para balances reales
 *
 * Validaciones aplicadas (en orden):
 *   1. Oportunidad nula -> INVALID_OPPORTUNITY
 *   2. Emergency stop activo -> EMERGENCY_STOP_ACTIVE
 *   3. Maximo de trades abiertos -> MAX_OPEN_TRADES_EXCEEDED
 *   4. Exposicion por activo > limite -> EXPOSURE_LIMIT_EXCEEDED
 *   5. Perdida diaria > limite -> DAILY_LOSS_LIMIT_EXCEEDED
 *
 * Gestion de dias:
 *   - Al cambiar de dia, resetea dailyPnL, exposure y peakBalance
 *   - Peak balance se actualiza con el balance real o el calculado
 *
 * Integracion: Usado por ExecutionEngine.execute() antes de lanzar cada trade.
 *              Tambien usado por handleError() para registrar perdidas.
 *              WalletSyncManager se inyecta externamente desde Main.java.
 *
 * Backward compatible: puede coexistir con OrderExecutor original sin cambios.
 */
public class RiskManager {
    /** Tag para logging */
    private static final String TAG = "RiskMgr";

    /** Configuración global */
    private final AppConfig config;
    /** Exposición máxima permitida por activo (USD) */
    private final double maxExposurePerAsset;
    /** Límite de pérdida diaria (USD) antes de detener operaciones */
    private final double dailyLossLimit;
    /** Si el control de pérdida diaria está habilitado */
    private final boolean dailyLossCheckEnabled;
    /** Porcentaje máximo de drawdown permitido antes de emergency stop */
    private final double maxDrawdownPct;
    /** Balance mínimo de emergencia (no implementado actualmente) */
    private final double emergencyBalanceThreshold;

    /** Exposición actual por activo (activo → USD expuesto) */
    private final ConcurrentHashMap<String, Double> exposureByAsset;
    /** Posiciones abiertas por activo (activo → cantidad) */
    private final ConcurrentHashMap<String, Double> openPositionByAsset;
    /** PnL acumulado del día */
    private final AtomicReference<Double> dailyPnL;
    /** Balance máximo alcanzado (para calcular drawdown) */
    private final AtomicReference<Double> peakBalance;
    /** Día actual (para detectar cambio de día y resetear) */
    private final AtomicReference<LocalDate> currentDay;
    /** Timestamp de la última violación registrada por tipo */
    private final ConcurrentHashMap<String, Long> lastViolationTime;
    
    /** Sincronizador de wallet (para obtener balance real de Binance) */
    private WalletSyncManager walletSyncManager;

    /** Flag de parada de emergencia (bloquea toda ejecución) */
    private volatile boolean emergencyStop = false;
    /** Timestamp del último chequeo de riesgo */
    private volatile long lastRiskCheck = 0;
    /** Cooldown entre logs de violaciones repetidas (ms) */
    private static final long VIOLATION_COOLDOWN_MS = 5000;

    /**
     * Constructor con valores por defecto: maxExposure 1000, dailyLoss de config, maxDrawdown 5%, emergencyThreshold 0.
     */
    public RiskManager(AppConfig config) {
        this(config, 1000.0, (config != null ? config.getDailyLossLimit() : 50.0), 5.0, 0.0);
    }

    /**
     * Constructor completo del gestor de riesgo.
     *
     * @param config              Configuración global
     * @param maxExposure         Exposición máxima por activo (USD)
     * @param dailyLoss           Límite de pérdida diaria (USD)
     * @param maxDrawdown         Porcentaje máximo de drawdown
     * @param emergencyThreshold  Balance mínimo de emergencia
     */
    public RiskManager(AppConfig config, double maxExposure, double dailyLoss, double maxDrawdown, double emergencyThreshold) {
        this.config = config;
        this.maxExposurePerAsset = maxExposure;
        this.dailyLossLimit = dailyLoss;
        this.dailyLossCheckEnabled = config != null && config.isDailyLossCheckEnabled();
        this.maxDrawdownPct = maxDrawdown;
        this.emergencyBalanceThreshold = emergencyThreshold;
        this.exposureByAsset = new ConcurrentHashMap<>();
        this.openPositionByAsset = new ConcurrentHashMap<>();
        this.dailyPnL = new AtomicReference<>(0.0);
        this.peakBalance = new AtomicReference<>(0.0);
        this.currentDay = new AtomicReference<>(LocalDate.now());
        this.lastViolationTime = new ConcurrentHashMap<>();

        // Initialize peakBalance to 0 - will be synchronized with real wallet balance in Main.java
        this.peakBalance.set(0.0);
    }

    /**
     * Valida una oportunidad contra todas las reglas de riesgo definidas.
     *
     * Validaciones secuenciales:
     *   1. Oportunidad y triángulo no nulos
     *   2. Emergency stop no activo
     *   3. Límite de trades abiertos no excedido (maxOpenTrades)
     *   4. Exposición por activo dentro del límite (maxExposurePerAsset)
     *   5. Pérdida diaria dentro del límite (dailyLossLimit)
     *
     * NOTA: MAX_DRAWDOWN_EXCEEDED y EMERGENCY_BALANCE_THRESHOLD están
     * temporalmente deshabilitados para testing.
     *
     * @param opportunity Oportunidad a validar
     * @return ValidationResult con resultado y lista de violaciones
     */
    public ValidationResult validate(ArbitrageOpportunity opportunity) {
        List<String> violations = new ArrayList<>();

        // Validación 1: Oportunidad y triángulo deben existir
        if (opportunity == null || opportunity.getTriangle() == null) {
            violations.add("INVALID_OPPORTUNITY");
            return new ValidationResult(false, violations);
        }

        // Validación 2: Emergency stop
        if (emergencyStop) {
            violations.add("EMERGENCY_STOP_ACTIVE");
        }

        // Validación 3: Límite de trades abiertos
        int currentOpen = (int) openPositionByAsset.values().stream().mapToDouble(v -> v > 0 ? 1 : 0).count();
        if (config != null && currentOpen >= config.getMaxOpenTrades()) {
            violations.add("MAX_OPEN_TRADES_EXCEEDED");
        }

        // Validación 4: Exposición por activo
        String[] assets = extractAssets(opportunity);
        for (String asset : assets) {
            if (asset == null || asset.isEmpty()) continue;
            double currentExposure = exposureByAsset.getOrDefault(asset, 0.0);
            if (currentExposure >= maxExposurePerAsset) {
                violations.add("EXPOSURE_LIMIT_EXCEEDED:" + asset + "(" + String.format("%.2f", currentExposure) + ")");
            }
        }

        // Validación 5: Límite de pérdida diaria
        double daily = dailyPnL.get();
        if (dailyLossCheckEnabled && daily <= -dailyLossLimit) {
            violations.add("DAILY_LOSS_LIMIT_EXCEEDED:" + String.format("%.2f", daily));
        }

        // TEMPORARILY DISABLED: All risk validation removed for testing
        // MAX_DRAWDOWN_EXCEEDED and EMERGENCY_BALANCE_THRESHOLD disabled
        boolean approved = violations.isEmpty();

        // Log de violaciones con control de frecuencia (cada 5s)
        if (!approved && shouldLogViolation()) {
            Log.warn(TAG, "Risk validation FAILED: " + String.join(", ", violations));
        }

        return new ValidationResult(approved, violations);
    }

    /**
     * Control de frecuencia de logs de violaciones.
     * Solo loggea una violación cada VIOLATION_COOLDOWN_MS (5s) para evitar spam.
     *
     * @return true si debe loggearse la violación
     */
    private boolean shouldLogViolation() {
        long now = System.currentTimeMillis();
        long lastLog = lastViolationTime.getOrDefault("last", 0L);
        if (now - lastLog > VIOLATION_COOLDOWN_MS) {
            lastViolationTime.put("last", now);
            return true;
        }
        return false;
    }

    /**
     * Extrae los 3 activos involucrados en una oportunidad para validación de exposición.
     *
     * @param opportunity Oportunidad de arbitraje
     * @return String[]{intermediateCurrency, targetCurrency, baseCurrency}
     */
    private String[] extractAssets(ArbitrageOpportunity opportunity) {
        Triangle t = opportunity.getTriangle();
        return new String[]{
            t.getIntermediateCurrency(),
            t.getTargetCurrency(),
            t.getBaseCurrency()
        };
    }

    /**
     * Sincroniza currentBalance y peakBalance con balance real desde wallet.
     * Llamar desde Main.java después de crear RiskManager y obtener balance real.
     * @param realBalance Balance real disponible en wallet
     */
    public void syncWithWalletBalance(double realBalance) {
        if (realBalance > 0) {
            this.peakBalance.set(realBalance);
            Log.info(TAG, "RiskManager synchronized: peakBalance set to " + String.format("%.2f", realBalance));
        }
    }

    /**
     * Obtiene balance actual desde walletSyncManager (real balance)
     */
    private double getCurrentBalance() {
        if (walletSyncManager != null) {
            return walletSyncManager.getUsdtBalance();
        }
        return 0.0;
    }

    /**
     * Registra la apertura de una posición en el seguimiento de exposición.
     * Acumula la cantidad en exposureByAsset y openPositionByAsset.
     * Solo registra si la operación es de compra (BUY).
     *
     * @param order Orden que se abrió
     */
    public void recordOpen(SequenceOrder order) {
        if (order == null) return;

        String asset = extractBaseAsset(order.getSymbol());
        double qty = order.getCantidadEjecutada();
        String side = order.getSide();

        if ("BUY".equalsIgnoreCase(side)) {
            exposureByAsset.merge(asset, qty, Double::sum);
            openPositionByAsset.merge(asset, qty, Double::sum);
            Log.debug(TAG, "Position OPEN: " + asset + " qty=" + String.format("%.4f", qty));
        }
    }

    /**
     * Registra un fill (sinónimo de recordOpen para compatibilidad).
     *
     * @param order Orden que se llenó
     */
    public void recordFill(SequenceOrder order) {
        recordOpen(order);
    }

    /**
     * Registra el cierre de una posición.
     * Reduce la posición abierta, elimina la exposición del activo,
     * y registra el PnL realizado.
     *
     * @param symbol      Símbolo cerrado
     * @param qty         Cantidad ejecutada
     * @param realizedPnL PnL realizado en USD
     */
    public void recordClose(String symbol, double qty, double realizedPnL) {
        String asset = extractBaseAsset(symbol);

        // Reducir posición abierta
        double current = openPositionByAsset.getOrDefault(asset, 0.0);
        double remaining = Math.max(0, current - qty);
        if (remaining > 0) {
            openPositionByAsset.put(asset, remaining);
        } else {
            openPositionByAsset.remove(asset);
        }

        // Liberar exposición completa del activo
        exposureByAsset.remove(asset);

        // Registrar PnL
        recordPnL(realizedPnL);

        Log.debug(TAG, "Position CLOSED: " + asset + " qty=" + String.format("%.4f", qty) + " pnl=" + String.format("%.4f", realizedPnL));
    }

    /**
     * Libera exposición de un activo sin cerrar la posición completamente.
     * Útil para ajustes parciales.
     *
     * @param asset Activo a liberar
     * @param qty   Cantidad a liberar
     */
    public void releaseExposure(String asset, double qty) {
        double current = exposureByAsset.getOrDefault(asset, 0.0);
        double released = Math.min(current, qty);
        exposureByAsset.compute(asset, (k, v) -> {
            double newVal = (v == null ? 0 : v) - released;
            return newVal > 0 ? newVal : null;
        });
    }

    /**
     * @param asset Activo
     * @return Exposición actual del activo en USD
     */
    public double getExposure(String asset) {
        return exposureByAsset.getOrDefault(asset, 0.0);
    }

    /**
     * @return Copia del mapa de exposiciones por activo
     */
    public Map<String, Double> getAllExposure() {
        return new HashMap<>(exposureByAsset);
    }

    /**
     * Registra un PnL (positivo o negativo) en el contador diario.
     * Si cambió el día calendario, resetea todas las métricas diarias:
     * dailyPnL, peakBalance, exposure, open positions.
     * Actualiza el peak balance si el nuevo balance es mayor.
     *
     * @param amount Monto del PnL en USD
     */
    public void recordPnL(double amount) {
        dailyPnL.updateAndGet(v -> v + amount);

        // Detectar cambio de día para resetear métricas diarias
        LocalDate today = LocalDate.now();
        if (!today.equals(currentDay.get())) {
            currentDay.set(today);
            dailyPnL.set(0.0);
            peakBalance.set(getFallbackBalance());
            exposureByAsset.clear();
            openPositionByAsset.clear();
            Log.info(TAG, "Daily reset: new day=" + today);
        }

        // Actualizar peak balance si el actual es mayor
        double current = getFallbackBalance();
        peakBalance.updateAndGet(currentPeak -> Math.max(currentPeak, current));
    }

    /**
     * @return PnL acumulado del día en USD
     */
    public double getDailyPnL() {
        return dailyPnL.get();
    }

    /**
     * Obtiene balance actual desde walletSyncManager (balance real de Binance)
     * o calcula un balance estimado si no hay walletSyncManager.
     *
     * @return Balance actual disponible
     */
    private double getFallbackBalance() {
        if (walletSyncManager != null) {
            return walletSyncManager.getUsdtBalance();
        }
        return config.getBalancePerTrade() + dailyPnL.get();
    }

    /**
     * Activa la parada de emergencia. Bloquea todas las nuevas ejecuciones.
     */
    public void triggerEmergencyStop() {
        emergencyStop = true;
        Log.error(TAG, "======== EMERGENCY STOP TRIGGERED ========");
        Log.error(TAG, "All new opportunities blocked. Check logs.");
    }

    /**
     * Activa la parada de emergencia con una razón específica.
     *
     * @param reason Razón de la parada de emergencia
     */
    public void triggerEmergencyStop(String reason) {
        emergencyStop = true;
        Log.error(TAG, "======== EMERGENCY STOP: " + reason + " ========");
    }

    /**
     * Resetea la parada de emergencia (operación manual).
     */
    public void resetEmergencyStop() {
        if (emergencyStop) {
            emergencyStop = false;
            Log.info(TAG, "Emergency stop RESET by operator");
        }
    }

    /**
     * @return true si la parada de emergencia está activa
     */
    public boolean isEmergencyStop() {
        return emergencyStop;
    }

    /**
     * Actualiza el peak balance si el nuevo valor es mayor.
     *
     * @param newPeak Nuevo valor candidato a peak
     */
    public void updatePeakBalance(double newPeak) {
        peakBalance.updateAndGet(current -> Math.max(current, newPeak));
    }

    /**
     * Establece el balance actual y actualiza el peak si es mayor.
     *
     * @param balance Balance actual
     */
    public void setCurrentBalance(double balance) {
        peakBalance.updateAndGet(current -> Math.max(current, balance));
    }

    /**
     * Obtiene el estado completo del gestor de riesgo.
     * Calcula: drawdown actual, balance, posiciones abiertas, exposures.
     * Si cambió el día, resetea métricas automáticamente.
     *
     * @return RiskStatus con todas las métricas
     */
    public RiskStatus getStatus() {
        LocalDate today = LocalDate.now();
        if (!today.equals(currentDay.get())) {
            currentDay.set(today);
            dailyPnL.set(0.0);
        }

        double currentBalance = getFallbackBalance();
        double peak = peakBalance.get();
        double drawdown = peak > 0 ? ((peak - currentBalance) / peak) * 100 : 0;

        return RiskStatus.builder()
            .emergencyStop(emergencyStop)
            .dailyPnL(dailyPnL.get())
            .peakBalance(peak)
            .currentBalance(currentBalance)
            .drawdownPct(drawdown)               // drawdown = (peak - current) / peak * 100
            .openPositions(openPositionByAsset.size())
            .exposureMap(new HashMap<>(exposureByAsset))
            .timestamp(System.currentTimeMillis())
            .build();
    }

    /**
     * Loggea el estado completo del RiskManager en formato legible.
     */
    public void logStatus() {
        RiskStatus status = getStatus();
        Log.info(TAG, "=== Risk Status ===");
        Log.info(TAG, "  Emergency: " + (status.isEmergencyStop() ? "ACTIVE" : "OFF"));
        Log.info(TAG, "  Daily PnL: " + String.format("%.4f", status.getDailyPnL()));
        Log.info(TAG, "  Peak: " + String.format("%.2f", status.getPeakBalance()));
        Log.info(TAG, "  Current: " + String.format("%.2f", status.getCurrentBalance()));
        Log.info(TAG, "  Drawdown: " + String.format("%.2f%%", status.getDrawdownPct()));
        Log.info(TAG, "  Open Positions: " + status.getOpenPositions());
        Log.info(TAG, "  Exposure: " + status.getExposureMap());
        Log.info(TAG, "  MaxExposure/Asset: " + maxExposurePerAsset);
        Log.info(TAG, "  DailyLossLimit: " + dailyLossLimit);
    }

    /**
     * Resetea completo el RiskManager: exposures, PnL, emergency stop.
     */
    public void reset() {
        exposureByAsset.clear();
        openPositionByAsset.clear();
        dailyPnL.set(0.0);
        emergencyStop = false;
        Log.info(TAG, "RiskManager RESET");
    }

    /**
     * Extrae el activo base de un símbolo eliminando el suffix de quote.
     * Ej: "BTCUSDT" → "BTC", "ETHBTC" → "ETH"
     * Busca en orden: USDT, BTC, BNB, ETH, USDC
     *
     * @param symbol Símbolo completo (ej: "BTCUSDT")
     * @return Activo base (ej: "BTC")
     */
    private String extractBaseAsset(String symbol) {
        if (symbol == null || symbol.isEmpty()) return "";
        int len = symbol.length();
        String[] bases = {"USDT", "BTC", "BNB", "ETH", "USDC"};
        for (String base : bases) {
            if (symbol.endsWith(base)) {
                return symbol.substring(0, symbol.length() - base.length());
            }
        }
        return symbol;
    }

    /**
     * Resultado de la validación de riesgo.
     * approved: true si la oportunidad pasa todas las validaciones
     * violations: lista de reglas de riesgo violadas (vacía si approved=true)
     */
    @Data
    @Builder
    public static class ValidationResult {
        private boolean approved;
        private List<String> violations;

        public boolean isApproved() {
            return approved;
        }
    }

    /**
     * Estado completo del RiskManager.
     * emergencyStop: true si la parada de emergencia está activa
     * dailyPnL: PnL acumulado del día (USD)
     * peakBalance: balance máximo alcanzado
     * currentBalance: balance actual estimado
     * drawdownPct: drawdown desde el peak (%)
     * openPositions: número de posiciones actualmente abiertas
     * exposureMap: exposición por activo (asset → USD)
     * timestamp: momento de captura
     */
    @Data
    @Builder
    public static class RiskStatus {
        private boolean emergencyStop;
        private double dailyPnL;
        private double peakBalance;
        private double currentBalance;
        private double drawdownPct;
        private int openPositions;
        private Map<String, Double> exposureMap;
        private long timestamp;
    }
}