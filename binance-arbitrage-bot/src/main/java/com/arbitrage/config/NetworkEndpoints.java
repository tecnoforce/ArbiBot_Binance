package com.arbitrage.config;

/**
 * Define los endpoints de red para conectar a Binance.
 * Maneja tanto MAINNET (produccion) como TESTNET (pruebas).
 */
public class NetworkEndpoints {

    // =====================================================================
    // URLs DE MAINNET (Produccion)
    // =====================================================================
    // REST API para consultas y ordenes
    public static final String MAINNET_REST_URL = "https://api.binance.com";
    // WebSocket base para precios en tiempo real
    public static final String MAINNET_WS_URL = "wss://stream.binance.com";
    // Path para streams multiplexados
    public static final String MAINNET_WS_STREAM = "/stream";

    // =====================================================================
    // URLs DE TESTNET (Pruebas)
    // =====================================================================
    // Testnet REST API
    public static final String TESTNET_REST_URL = "https://testnet.binance.vision";
    // Testnet WebSocket
    public static final String TESTNET_WS_URL = "wss://stream.testnet.binance.vision";
    // Testnet stream path
    public static final String TESTNET_WS_STREAM = "/stream";

    /**
     * Obtiene la URL base de la API REST segun entorno.
     * @param testnet true para testnet, false para mainnet
     * @return URL de la API REST
     */
    public static String getRestBaseUrl(boolean testnet) {
        return testnet ? TESTNET_REST_URL : MAINNET_REST_URL;
    }

    /**
     * Obtiene la URL base del WebSocket segun entorno.
     * @param testnet true para testnet, false para mainnet
     * @return URL del WebSocket
     */
    public static String getWsBaseUrl(boolean testnet) {
        return testnet ? TESTNET_WS_URL : MAINNET_WS_URL;
    }

    /**
     * Obtiene el path del stream WebSocket.
     * @param testnet true para testnet, false para mainnet
     * @return Path del stream
     */
    public static String getWsStreamPath(boolean testnet) {
        return testnet ? TESTNET_WS_STREAM : MAINNET_WS_STREAM;
    }

    /**
     * Construye la URL completa del WebSocket con streams.
     * Formato: wss://stream.binance.com/stream?streams=btcusdt@bookticker/ethusdt@bookticker
     * @param testnet Entorno a usar
     * @param streams Streams a suscribirse (separados por /)
     * @return URL completa del WebSocket
     */
    public static String buildWebSocketUrl(boolean testnet, String streams) {
        return getWsBaseUrl(testnet) + getWsStreamPath(testnet) + "?streams=" + streams;
    }

    /**
     * Obtiene nombre legible del entorno.
     * @param testnet true para testnet, false para mainnet
     * @return Nombre del entorno
     */
    public static String getEnvironmentName(boolean testnet) {
        return testnet ? "TESTNET" : "MAINNET";
    }
}