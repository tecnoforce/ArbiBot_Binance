package com.arbitrage.websocket;

import com.arbitrage.config.ApiConfig;
import com.arbitrage.config.NetworkEndpoints;
import com.arbitrage.util.Log;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

/**
 * Cliente WebSocket para conectar a Binance y recibir precios.
 * Usa la libreria OkHttp para conexion WebSocket.
 * Recibe actualizaciones de precios @ bookTicker (mejor precio bid/ask).
 */
public class BinanceWebSocketClient {
    private static final String TAG = "WebSocket";

    // =====================================================================
    // CONFIGURACION
    // =====================================================================
    private final ApiConfig apiConfig;              // Credenciales
    private final PriceUpdateHandler priceHandler;  // Procesador de mensajes
    
    // =====================================================================
    // THREADS Y CONEXION
    // =====================================================================
    private final ExecutorService executor;            // Para reconexiones
    private final CopyOnWriteArrayList<String> subscriptions;  // Streams suscritos
    private volatile boolean connected = false;         // Estado de conexion
    private volatile okhttp3.WebSocket wsSocket;   // Socket OkHttp
    private okhttp3.OkHttpClient wsClient;       // Cliente OkHttp
    
    // =====================================================================
    // RECONEXION
    // =====================================================================
    private int reconnectAttempts = 0;
    private static final int MAX_RECONNECT_ATTEMPTS = 10;
    private static int messageCount = 0;      // Contador de mensajes

    /**
     * Constructor.
     * @param apiConfig Credenciales API
     * @param priceHandler Handler para procesar precios
     */
    public BinanceWebSocketClient(ApiConfig apiConfig, PriceUpdateHandler priceHandler) {
        this.apiConfig = apiConfig;
        this.priceHandler = priceHandler;
        
        // Configura cliente OkHttp
        this.wsClient = new okhttp3.OkHttpClient.Builder()
                .readTimeout(0, TimeUnit.MILLISECONDS)  // Sin timeout lectura
                .pingInterval(30, TimeUnit.SECONDS)     // PING cada 30s
                .build();
        
        // Executor para tareas asincronas
        this.executor = Executors.newSingleThreadExecutor();
        
        // Lista thread-safe de suscripciones
        this.subscriptions = new CopyOnWriteArrayList<>();
    }

    /**
     * Conecta al WebSocket y suscribe a streams.
     * @param symbols Lista de simbolos (ej: ["BTCUSDT", "ETHUSDT"])
     */
    public void connectAndSubscribe(Iterable<String> symbols) {
        // =====================================================================
        // CONSTRUIR PARAMETROS DE STREAM
        // =====================================================================
        StringBuilder streamParams = new StringBuilder();

        for (String symbol : symbols) {
            if (streamParams.length() > 0) {
                streamParams.append("/");
            }
            // Formato: btcusdt@bookticker
            streamParams.append(symbol.toLowerCase()).append("@bookTicker");
            subscriptions.add(symbol.toUpperCase());
        }

        String streams = streamParams.toString();
        boolean isTestnet = apiConfig.isTestnet();
        
        // Construye URL completa
        String wsUrl = NetworkEndpoints.buildWebSocketUrl(isTestnet, streams);

        Log.info("Conectando a: Binance...");

        try {
            // =====================================================================
            // CREAR REQUEST
            // =====================================================================
            okhttp3.Request request = new okhttp3.Request.Builder()
                    .url(wsUrl)
                    .build();

            // =====================================================================
            // CONECTAR CON LISTENER
            // =====================================================================
            wsSocket = wsClient.newWebSocket(request, new okhttp3.WebSocketListener() {
                // =====================================================================
                // ON OPEN - Conexion establecida
                // =====================================================================
                @Override
                public void onOpen(okhttp3.WebSocket webSocket, okhttp3.Response response) {
                    connected = true;
                    reconnectAttempts = 0;
                    String env = apiConfig.isTestnet() ? "TESTNET" : "MAINNET";
                    Log.info("WebSocket conectado exitosamente.");
                    Log.info("Suscrito a (" + subscriptions.size() + " streams)");
                }

                // =====================================================================
                // ON MESSAGE - Mensaje recibido
                // =====================================================================
                @Override
                public void onMessage(okhttp3.WebSocket webSocket, String text) {
                    messageCount++;
                    try {
                        // Procesa mensaje JSON de precio
                        priceHandler.handlePriceUpdate(text);
                        if (Log.isDebugEnabled()) {
                            Log.debug(TAG, "Recibido #" + messageCount + ": " + text.substring(0, Math.min(60, text.length())) + "...");
                        }
                    } catch (Exception e) {
                        Log.debug(TAG, "Error parseando mensaje: " + e.getMessage());
                    }
                }

                // =====================================================================
                // ON CLOSING - Cerrando conexion
                // =====================================================================
                @Override
                public void onClosing(okhttp3.WebSocket webSocket, int code, String reason) {
                    connected = false;
                    Log.warn(TAG, "Cerrando: " + code + " - " + reason);
                }

                // =====================================================================
                // ON CLOSED - Conexion cerrada
                // =====================================================================
                @Override
                public void onClosed(okhttp3.WebSocket webSocket, int code, String reason) {
                    connected = false;
                    Log.warn(TAG, " Cerrado: " + code + " - " + reason);
                    handleDisconnect();
                }

                // =====================================================================
                // ON FAILURE - Error de conexion
                // =====================================================================
                @Override
                public void onFailure(okhttp3.WebSocket webSocket, Throwable t, okhttp3.Response response) {
                    connected = false;
                    Log.error(TAG, "Fallo: " + t.getMessage());
                    handleDisconnect();
                }
            });
        } catch (Exception e) {
            Log.error(TAG, "Error al conectar: " + e.getMessage());
            scheduleReconnect(symbols);
        }
    }

    /**
     * Maneja desconexion y programa reconexion.
     */
    private void handleDisconnect() {
        connected = false;
        if (reconnectAttempts >= MAX_RECONNECT_ATTEMPTS) {
            Log.error(TAG, "Max intentos de reconexion alcanzados");
            return;
        }
        scheduleReconnect(subscriptions);
    }

    /**
     * Programa intento de reconexion con delay exponencial.
     * @param symbols Simbolos para re-suscribir
     */
    private void scheduleReconnect(Iterable<String> symbols) {
        // Delay: min(500ms * 2^intentos, 4000ms)
        long delay = Math.min(500L * (1L << reconnectAttempts), 4000L);
        reconnectAttempts++;

        Log.warn(TAG, "Reintentando conexion (intento " + reconnectAttempts + ") en " + delay + "ms...");

        executor.submit(() -> {
            try {
                Thread.sleep(delay);
                connectAndSubscribe(symbols);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
    }

    /**
     * Verifica si esta conectado.
     * @return true si conexion activa
     */
    public boolean isConnected() {
        return connected;
    }

    /**
     * Obtiene numero de mensajes recibidos.
     * @return Contador de mensajes
     */
    public int getMessageCount() {
        return messageCount;
    }

    /**
     * Resetea contador de mensajes.
     */
    public void resetMessageCount() {
        messageCount = 0;
    }

    /**
     * Cierra conexion WebSocket.
     */
    public void close() {
        connected = false;
        if (wsSocket != null) {
            wsSocket.close(1000, "Cerrando");
        }
        wsClient.dispatcher().executorService().shutdown();
        executor.shutdown();
    }
}