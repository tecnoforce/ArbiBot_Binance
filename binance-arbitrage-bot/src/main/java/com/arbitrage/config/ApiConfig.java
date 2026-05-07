package com.arbitrage.config;

import lombok.Builder;
import lombok.Data;

/**
 * Configuracion de credenciales API de Binance.
 * Almacena claves para Mainnet y Testnet, y URLs personalizadas.
 */
@Data
@Builder
public class ApiConfig {
    // =====================================================================
    // CREDENCIALES MAINNET
    // =====================================================================
    private String apiKey;           // Clave API para mainnet
    private String secretKey;       // Clave secreta para mainnet
    private String baseUrl;        // URL REST API personalizada
    private String wsUrl;          // URL WebSocket personalizada

    // =====================================================================
    // BANDERA DE ENTORNO
    // =====================================================================
    private boolean testnet;       // true = usar testnet, false = mainnet

    // =====================================================================
    // CREDENCIALES TESTNET
    // =====================================================================
    private String testnetApiKey;     // Clave API para testnet
    private String testnetSecretKey;   // Clave secreta para testnet
    private String testnetBaseUrl;    // URL REST API testnet
    private String testnetWsUrl;       // URL WebSocket testnet
    private int testnetCoins;         // Limite de monedas para testnet

    /**
     * Determina si esta configurado para testnet.
     * @return true si usa testnet
     */
    public boolean isTestnet() {
        return testnet;
    }

    /**
     * Obtiene la clave API actual segun entorno.
     * @return API key del entorno activo
     */
    public String getCurrentApiKey() {
        return testnet ? testnetApiKey : apiKey;
    }

    /**
     * Obtiene la clave secreta actual segun entorno.
     * @return Secret key del entorno activo
     */
    public String getCurrentSecretKey() {
        return testnet ? testnetSecretKey : secretKey;
    }

    /**
     * Obtiene la URL REST actual segun entorno.
     * @return Base URL del entorno activo
     */
    public String getCurrentBaseUrl() {
        return testnet ? testnetBaseUrl : baseUrl;
    }

    /**
     * Obtiene la URL WebSocket actual segun entorno.
     * @return WebSocket URL del entorno activo
     */
    public String getCurrentWsUrl() {
        return testnet ? testnetWsUrl : wsUrl;
    }

    /**
     * Obtiene nombre del entorno activo.
     * @return "TESTNET" o "MAINNET"
     */
    public String getEnvironment() {
        return testnet ? "TESTNET" : "MAINNET";
    }

    /**
     * Obtiene limite de monedas para testnet.
     * @return Numero de monedas a cargar en testnet (0 = todas)
     */
    public int getTestnetCoins() {
        return testnetCoins;
    }
}