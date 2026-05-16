package com.arbitrage.trading;

import com.arbitrage.util.Log;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * SINCRONIZADOR DE BALANCES DE WALLET.
 * 
 * Mantiene los balances de USDT y BNB actualizados periódicamente
 * consultando la API REST de Binance.
 *
 * PROPÓSITO:
 *   El bot necesita conocer los balances en todo momento para:
 *   1. Verificar que hay suficiente USDT antes de OP1 (executeSequenceReal)
 *   2. Verificar que hay suficiente base asset antes de OP2 y OP3
 *   3. Calcular el valor de BNB para estimar comisiones
 *
 * ARQUITECTURA:
 *   - Ejecuta en un thread único (daemon) con scheduler programado
 *   - Usa CopyOnWriteArrayList para listeners (thread-safe sin locks)
 *   - Los balances son volátiles para visibilidad entre threads
 *   - Implementa patrón Observer: notifica a listeners cuando cambian balances
 *
 * @see BinanceApiClient#getAccountBalances()
 */
public class WalletSyncManager {

    private static final String TAG = "WALLET_SYNC";

    /** Cliente API para consultar balances */
    private final BinanceApiClient apiClient;
    /** Scheduler para ejecución periódica de la sincronización */
    private final ScheduledExecutorService scheduler;
    /** Intervalo de sincronización en ms (configurable) */
    private final long syncIntervalMs;
    /** Lista de listeners para notificar cambios de balance */
    private final List<BalanceListener> listeners;

    /** Balance actual de USDT (volátil para visibilidad entre threads) */
    private volatile double usdtBalance;
    /** Balance actual de BNB (volátil para visibilidad entre threads) */
    private volatile double bnbBalance;
    /** Precio actual de BNB en USDT */
    private volatile double bnbPrice;
    /** Indica si el sincronizador está corriendo */
    private volatile boolean running;

    /**
     * Interfaz para recibir notificaciones de actualización de balances.
     * Implementada por componentes que necesitan conocer los balances en tiempo real.
     */
    public interface BalanceListener {
        /**
         * Invocado cuando los balances se actualizan.
         * @param usdt    Balance de USDT
         * @param bnb     Balance de BNB
         * @param bnbPrice Precio de BNB en USDT
         */
        void onBalancesUpdated(double usdt, double bnb, double bnbPrice);
    }

    /**
     * Constructor. Inicia una sincronización inmediata.
     *
     * @param apiClient       Cliente API de Binance
     * @param syncIntervalMs  Intervalo de sincronización en milisegundos
     */
    public WalletSyncManager(BinanceApiClient apiClient, long syncIntervalMs) {
        this.apiClient = apiClient;
        this.syncIntervalMs = syncIntervalMs;
        // Thread daemon: no impide que la JVM se cierre
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "wallet-sync");
            t.setDaemon(true);
            return t;
        });
        this.listeners = new CopyOnWriteArrayList<>();

        // Sincronizar inmediatamente al crear la instancia
        syncNow();
        Log.info(TAG, "WalletSyncManager init. interval=" + syncIntervalMs + "ms");
    }

    /**
     * Registra un listener para recibir actualizaciones de balance.
     * @param listener Listener a registrar
     */
    public void addListener(BalanceListener listener) {
        if (listener != null) {
            listeners.add(listener);
        }
    }

    /**
     * Elimina un listener registrado.
     * @param listener Listener a eliminar
     */
    public void removeListener(BalanceListener listener) {
        listeners.remove(listener);
    }

    /**
     * Inicia la sincronización periódica de balances.
     * Usa scheduleAtFixedRate para ejecutar syncNow cada syncIntervalMs.
     * Si ya está corriendo, no hace nada.
     */
    public void start() {
        if (running) return;
        running = true;

        scheduler.scheduleAtFixedRate(this::syncNow,
                syncIntervalMs, syncIntervalMs, TimeUnit.MILLISECONDS);

        Log.info(TAG, "Wallet sync started. interval=" + syncIntervalMs + "ms");
    }

    /**
     * Detiene la sincronización periódica.
     * Espera hasta 2 segundos para que termine el thread actual.
     */
    public void stop() {
        running = false;
        scheduler.shutdown();
        try {
            if (!scheduler.awaitTermination(2, TimeUnit.SECONDS)) {
                scheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            scheduler.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    /**
     * EJECUTA UNA SINCRONIZACIÓN INMEDIATA de balances.
     * 
     * Consulta la API de Binance (getAccountBalances) y actualiza
     * los valores locales de USDT, BNB y precio de BNB.
     * Luego notifica a todos los listeners registrados.
     */
    public void syncNow() {
        try {
            double[] balances = apiClient.getAccountBalances();
            this.usdtBalance = balances[0];
            this.bnbBalance = balances[1];
            this.bnbPrice = balances[2];

            notifyListeners();
        } catch (Exception e) {
            Log.warn(TAG, "Sync error: " + e.getMessage());
        }
    }

    /**
     * Notifica a todos los listeners registrados con los balances actuales.
     * Cada listener se invoca en su propio try-catch para que un error
     * en uno no afecte a los demás.
     */
    private void notifyListeners() {
        for (BalanceListener listener : listeners) {
            try {
                listener.onBalancesUpdated(usdtBalance, bnbBalance, bnbPrice);
            } catch (Exception e) {
                Log.warn(TAG, "Listener error: " + e.getMessage());
            }
        }
    }

    /**
     * Retorna el balance actual de USDT.
     * @return Balance USDT
     */
    public double getUsdtBalance() {
        return usdtBalance;
    }

    /**
     * Retorna el balance actual de BNB.
     * @return Balance BNB
     */
    public double getBnbBalance() {
        return bnbBalance;
    }

    /**
     * Retorna el precio actual de BNB en USDT.
     * @return Precio BNB/USDT
     */
    public double getBnbPrice() {
        return bnbPrice;
    }
}