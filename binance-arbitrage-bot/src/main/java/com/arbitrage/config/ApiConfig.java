package com.arbitrage.config;

import lombok.Builder;
import lombok.Data;

/**
 * Credenciales y URLs de conexion a la API de Binance.
 * <p>
 * Almacena dos conjuntos completos de credenciales: uno para <b>Mainnet</b>
 * (produccion real) y otro para <b>Testnet</b> (pruebas). La propiedad
 * {@link #testnet} determina cual de los dos conjuntos se usa en ejecucion.
 * <p>
 * Los metodos {@code getCurrent*()} delegan automaticamente al conjunto activo
 * segun el valor de {@code testnet}, evitando tener que preguntar la bandera
 * en cada punto del codigo que necesita las credenciales.
 */
@Data
@Builder
public class ApiConfig {
    // =====================================================================
    // CREDENCIALES MAINNET — se usan cuando testnet=false
    // =====================================================================
    private String apiKey;             // API Key de Binance para mainnet (cadena alfanumerica)
    private String secretKey;          // Secret Key de Binance para mainnet (firma HMAC SHA256)
    private String baseUrl;            // URL base REST personalizada (opcional, tiene default en NetworkEndpoints)
    private String wsUrl;              // URL base WebSocket personalizada (opcional, tiene default)

    // =====================================================================
    // BANDERA SELECTORA DE ENTORNO
    // true  → se usan las credenciales testnet_*
    // false → se usan las credenciales apiKey/secretKey (mainnet)
    // =====================================================================
    private boolean testnet;

    // =====================================================================
    // CREDENCIALES TESTNET — se usan cuando testnet=true
    // =====================================================================
    private String testnetApiKey;      // API Key para el entorno de pruebas de Binance
    private String testnetSecretKey;   // Secret Key para testnet
    private String testnetBaseUrl;     // URL REST de testnet (opcional, tiene default)
    private String testnetWsUrl;       // URL WebSocket de testnet (opcional)
    private int testnetCoins;          // Limite opcional de monedas a cargar en testnet (0 = sin limite)

    /**
     * Indica si el bot esta configurado para operar en el entorno de pruebas.
     *
     * @return {@code true} si usa Testnet, {@code false} si usa Mainnet
     */
    public boolean isTestnet() {
        return testnet;
    }

    /**
     * Obtiene la API Key del entorno actualmente activo.
     * Delega automaticamente a mainnet o testnet segun {@link #testnet}.
     *
     * @return API Key del entorno activo (nunca {@code null} pero puede ser cadena vacia si no se configuro)
     */
    public String getCurrentApiKey() {
        return testnet ? testnetApiKey : apiKey;
    }

    /**
     * Obtiene la Secret Key del entorno actualmente activo.
     * Se usa para firmar las peticiones REST a Binance (HMAC-SHA256).
     *
     * @return Secret Key del entorno activo
     */
    public String getCurrentSecretKey() {
        return testnet ? testnetSecretKey : secretKey;
    }

    /**
     * Obtiene la URL base REST del entorno actualmente activo.
     * Si no se configuro una URL personalizada, devuelve el valor por defecto
     * de {@link NetworkEndpoints}.
     *
     * @return URL base REST del entorno activo (ej. "https://api.binance.com")
     */
    public String getCurrentBaseUrl() {
        return testnet ? testnetBaseUrl : baseUrl;
    }

    /**
     * Obtiene la URL base WebSocket del entorno actualmente activo.
     * Si no se configuro una URL personalizada, devuelve el valor por defecto
     * de {@link NetworkEndpoints}.
     *
     * @return URL WebSocket del entorno activo (ej. "wss://stream.binance.com")
     */
    public String getCurrentWsUrl() {
        return testnet ? testnetWsUrl : wsUrl;
    }

    /**
     * Retorna el nombre legible del entorno activo para mostrar en logs y consola.
     *
     * @return "TESTNET" si {@link #testnet} es {@code true}, "MAINNET" en caso contrario
     */
    public String getEnvironment() {
        return testnet ? "TESTNET" : "MAINNET";
    }

    /**
     * Obtiene el limite de monedas a cargar cuando se usa Testnet.
     * Sirve para reducir la cantidad de pares a escanear en pruebas y acelerar la deteccion.
     *
     * @return Numero maximo de monedas a cargar, o {@code 0} si no hay limite (cargar todas)
     */
    public int getTestnetCoins() {
        return testnetCoins;
    }
}