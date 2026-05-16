package com.arbitrage.model;

/**
 * Estados posibles del ciclo completo de una secuencia de arbitraje triangular.
 * <p>
 * Define el ciclo de vida de una TradingSequence desde que se detecta la
 * oportunidad (ABIERTA) hasta que finaliza, ya sea exitosamente (CERRADA),
 * por fallo en medio del ciclo (CANCELADA), o por error interno (ERROR).
 * </p>
 */
public enum EstadoSecuencia {
    /** Secuencia en progreso — se está ejecutando el ciclo de 3 órdenes */
    ABIERTA,
    /** Secuencia completada exitosamente — las 3 órdenes se ejecutaron y se cerró el ciclo */
    CERRADA,
    /** Secuencia cancelada — falló en Op2 u Op3 pero se tienen datos parciales */
    CANCELADA,
    /** Error interno — mantenido por compatibilidad con versiones legacy del sistema */
    ERROR
}