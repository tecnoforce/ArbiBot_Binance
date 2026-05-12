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
 * RiskManager - Control de riesgo y exposicion.
 *
 * Responsabilidades:
 * - Validar oportunidades antes de ejecucion
 * - Tracking de exposicion por activo
 * - PnL diario y drawdown tracking
 * - Emergency stop controls
 *
 * Backward compatible: puede coexistir con OrderExecutor sin cambios
 */
public class RiskManager {
    private static final String TAG = "RiskMgr";

    private final AppConfig config;
    private final double maxExposurePerAsset;
    private final double dailyLossLimit;
    private final boolean dailyLossCheckEnabled;
    private final double maxDrawdownPct;
    private final double emergencyBalanceThreshold;

    private final ConcurrentHashMap<String, Double> exposureByAsset;
    private final ConcurrentHashMap<String, Double> openPositionByAsset;
    private final AtomicReference<Double> dailyPnL;
    private final AtomicReference<Double> peakBalance;
    private final AtomicReference<LocalDate> currentDay;
    private final ConcurrentHashMap<String, Long> lastViolationTime;
    
    private WalletSyncManager walletSyncManager;

    private volatile boolean emergencyStop = false;
    private volatile long lastRiskCheck = 0;
    private static final long VIOLATION_COOLDOWN_MS = 5000;

    public RiskManager(AppConfig config) {
        this(config, 1000.0, (config != null ? config.getDailyLossLimit() : 50.0), 5.0, 0.0);
    }

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

    public ValidationResult validate(ArbitrageOpportunity opportunity) {
        List<String> violations = new ArrayList<>();

        if (opportunity == null || opportunity.getTriangle() == null) {
            violations.add("INVALID_OPPORTUNITY");
            return new ValidationResult(false, violations);
        }

        if (emergencyStop) {
            violations.add("EMERGENCY_STOP_ACTIVE");
        }

        int currentOpen = (int) openPositionByAsset.values().stream().mapToDouble(v -> v > 0 ? 1 : 0).count();
        if (config != null && currentOpen >= config.getMaxOpenTrades()) {
            violations.add("MAX_OPEN_TRADES_EXCEEDED");
        }

        String[] assets = extractAssets(opportunity);
        for (String asset : assets) {
            if (asset == null || asset.isEmpty()) continue;
            double currentExposure = exposureByAsset.getOrDefault(asset, 0.0);
            if (currentExposure >= maxExposurePerAsset) {
                violations.add("EXPOSURE_LIMIT_EXCEEDED:" + asset + "(" + String.format("%.2f", currentExposure) + ")");
            }
        }

        double daily = dailyPnL.get();
        if (dailyLossCheckEnabled && daily <= -dailyLossLimit) {
            violations.add("DAILY_LOSS_LIMIT_EXCEEDED:" + String.format("%.2f", daily));
        }

        // TEMPORARILY DISABLED: All risk validation removed for testing
        // MAX_DRAWDOWN_EXCEEDED and EMERGENCY_BALANCE_THRESHOLD disabled
        boolean approved = violations.isEmpty();

        if (!approved && shouldLogViolation()) {
            Log.warn(TAG, "Risk validation FAILED: " + String.join(", ", violations));
        }

        return new ValidationResult(approved, violations);
    }

    private boolean shouldLogViolation() {
        long now = System.currentTimeMillis();
        long lastLog = lastViolationTime.getOrDefault("last", 0L);
        if (now - lastLog > VIOLATION_COOLDOWN_MS) {
            lastViolationTime.put("last", now);
            return true;
        }
        return false;
    }

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

    public void recordFill(SequenceOrder order) {
        recordOpen(order);
    }

    public void recordClose(String symbol, double qty, double realizedPnL) {
        String asset = extractBaseAsset(symbol);

        double current = openPositionByAsset.getOrDefault(asset, 0.0);
        double remaining = Math.max(0, current - qty);
        if (remaining > 0) {
            openPositionByAsset.put(asset, remaining);
        } else {
            openPositionByAsset.remove(asset);
        }

        exposureByAsset.remove(asset);

        recordPnL(realizedPnL);

        Log.debug(TAG, "Position CLOSED: " + asset + " qty=" + String.format("%.4f", qty) + " pnl=" + String.format("%.4f", realizedPnL));
    }

    public void releaseExposure(String asset, double qty) {
        double current = exposureByAsset.getOrDefault(asset, 0.0);
        double released = Math.min(current, qty);
        exposureByAsset.compute(asset, (k, v) -> {
            double newVal = (v == null ? 0 : v) - released;
            return newVal > 0 ? newVal : null;
        });
    }

    public double getExposure(String asset) {
        return exposureByAsset.getOrDefault(asset, 0.0);
    }

    public Map<String, Double> getAllExposure() {
        return new HashMap<>(exposureByAsset);
    }

    public void recordPnL(double amount) {
        dailyPnL.updateAndGet(v -> v + amount);

        LocalDate today = LocalDate.now();
        if (!today.equals(currentDay.get())) {
            currentDay.set(today);
            dailyPnL.set(0.0);
            peakBalance.set(getFallbackBalance());
            exposureByAsset.clear();
            openPositionByAsset.clear();
            Log.info(TAG, "Daily reset: new day=" + today);
        }

        double current = getFallbackBalance();
        peakBalance.updateAndGet(currentPeak -> Math.max(currentPeak, current));
    }

    public double getDailyPnL() {
        return dailyPnL.get();
    }

    /**
     * Obtiene balance actual desde walletSyncManager (real balance) o balance por defecto
     */
    private double getFallbackBalance() {
        if (walletSyncManager != null) {
            return walletSyncManager.getUsdtBalance();
        }
        return config.getBalancePerTrade() + dailyPnL.get();
    }

    public void triggerEmergencyStop() {
        emergencyStop = true;
        Log.error(TAG, "======== EMERGENCY STOP TRIGGERED ========");
        Log.error(TAG, "All new opportunities blocked. Check logs.");
    }

    public void triggerEmergencyStop(String reason) {
        emergencyStop = true;
        Log.error(TAG, "======== EMERGENCY STOP: " + reason + " ========");
    }

    public void resetEmergencyStop() {
        if (emergencyStop) {
            emergencyStop = false;
            Log.info(TAG, "Emergency stop RESET by operator");
        }
    }

    public boolean isEmergencyStop() {
        return emergencyStop;
    }

    public void updatePeakBalance(double newPeak) {
        peakBalance.updateAndGet(current -> Math.max(current, newPeak));
    }

    public void setCurrentBalance(double balance) {
        peakBalance.updateAndGet(current -> Math.max(current, balance));
    }

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
            .drawdownPct(drawdown)
            .openPositions(openPositionByAsset.size())
            .exposureMap(new HashMap<>(exposureByAsset))
            .timestamp(System.currentTimeMillis())
            .build();
    }

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

    public void reset() {
        exposureByAsset.clear();
        openPositionByAsset.clear();
        dailyPnL.set(0.0);
        emergencyStop = false;
        Log.info(TAG, "RiskManager RESET");
    }

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

    @Data
    @Builder
    public static class ValidationResult {
        private boolean approved;
        private List<String> violations;

        public boolean isApproved() {
            return approved;
        }
    }

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