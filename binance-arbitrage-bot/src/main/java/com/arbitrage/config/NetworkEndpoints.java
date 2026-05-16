package com.arbitrage.config;

/**
 * Constantes y metodos utilitarios para obtener las URLs de conexion a Binance.
 * <p>
 * Centraliza todos los endpoints de red en un solo lugar, diferenciando entre:
 * <ul>
 *   <li><b>MAINNET</b> — entorno de produccion real (api.binance.com)</li>
 *   <li><b>TESTNET</b> — entorno de pruebas/simulacion (testnet.binance.vision)</li>
 * </ul>
 * Usar {@link #getRestBaseUrl(boolean)} y {@link #getWsBaseUrl(boolean)} para
 * obtener la URL correspondiente segun el entorno activo.
 */
public class NetworkEndpoints {

    // =====================================================================
    // URLs DE MAINNET (Produccion — dinero real)
    // =====================================================================
    // Endpoint REST: todas las consultas de mercado, saldos y ejecucion de ordenes
    public static final String MAINNET_REST_URL = "https://api.binance.com";
    // Endpoint WebSocket: suscripcion a actualizaciones de precios en tiempo real
    public static final String MAINNET_WS_URL = "wss://stream.binance.com";
    // Path para conexiones multiplexadas: permite varios streams en un solo socket
    public static final String MAINNET_WS_STREAM = "/stream";

    // =====================================================================
    // URLs DE TESTNET (Pruebas — dinero simulado)
    // =====================================================================
    // Endpoint REST del entorno de pruebas de Binance
    public static final String TESTNET_REST_URL = "https://testnet.binance.vision";
    // Endpoint WebSocket del entorno de pruebas
    public static final String TESTNET_WS_URL = "wss://stream.testnet.binance.vision";
    // Path para streams multiplexados en testnet (mismo formato que mainnet)
    public static final String TESTNET_WS_STREAM = "/stream";

    /**
     * Obtiene la URL base de la API REST de Binance segun el entorno activo.
     * Usado por {@link com.arbitrage.trading.BinanceApiClient} para hacer llamadas REST.
     *
     * @param testnet {@code true} = devuelve URL de testnet; {@code false} = mainnet
     * @return URL base REST (ej. "https://api.binance.com" o "https://testnet.binance.vision")
     */
    public static String getRestBaseUrl(boolean testnet) {
        return testnet ? TESTNET_REST_URL : MAINNET_REST_URL;
    }

    /**
     * Obtiene la URL base del WebSocket de Binance segun el entorno activo.
     * Usado por {@link com.arbitrage.websocket.BinanceWebSocketClient} para suscribirse a precios.
     *
     * @param testnet {@code true} = testnet; {@code false} = mainnet
     * @return URL base WebSocket (ej. "wss://stream.binance.com" o "wss://stream.testnet.binance.vision")
     */
    public static String getWsBaseUrl(boolean testnet) {
        return testnet ? TESTNET_WS_URL : MAINNET_WS_URL;
    }

    /**
     * Obtiene el path del endpoint WebSocket para streams multiplexados.
     * Actualmente tanto mainnet como testnet usan el mismo path ({@code /stream}),
     * pero se mantiene parametrizado por si cambia en el futuro.
     *
     * @param testnet {@code true} = testnet; {@code false} = mainnet
     * @return Path del stream (actualmente siempre "/stream")
     */
    public static String getWsStreamPath(boolean testnet) {
        return testnet ? TESTNET_WS_STREAM : MAINNET_WS_STREAM;
    }

    /**
     * Construye la URL completa para la conexion WebSocket multiplexada,
     * incluyendo la lista de streams a los que suscribirse.
     * <p>
     * Ejemplo de URL generada:
     * <pre>
     * wss://stream.binance.com/stream?streams=btcusdt@bookticker/ethusdt@bookticker
     * </pre>
     *
     * @param testnet {@code true} = usar URLs de testnet; {@code false} = mainnet
     * @param streams Lista de streams separados por {@code /} (ej. "btcusdt@bookticker/ethusdt@bookticker")
     * @return URL completa del WebSocket lista para conectar
     */
    public static String buildWebSocketUrl(boolean testnet, String streams) {
        return getWsBaseUrl(testnet) + getWsStreamPath(testnet) + "?streams=" + streams;
    }

    /**
     * Retorna una cadena legible que identifica el entorno actual.
     * Util para mensajes de log y display en consola.
     *
     * @param testnet {@code true} = "TESTNET"; {@code false} = "MAINNET"
     * @return "TESTNET" o "MAINNET"
     */
    public static String getEnvironmentName(boolean testnet) {
        return testnet ? "TESTNET" : "MAINNET";
    }
}