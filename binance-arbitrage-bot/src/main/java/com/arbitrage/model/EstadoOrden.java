package com.arbitrage.model;

/**
 * Estados posibles de una orden en el sistema de arbitraje.
 * <p>
 * Mapea los estados que Binance devuelve en sus respuestas API
 * (NEW, PARTIALLY_FILLED, FILLED, CANCELED, REJECTED, EXPIRED)
 * más estados internos del bot: PENDING (antes de enviar a Binance)
 * y ERROR (fallo interno no reportado por Binance).
 * </p>
 */
public enum EstadoOrden {
    /** Pendiente de envío a Binance (estado local, no existe en Binance) */
    PENDING,
    /** Orden aceptada por Binance pero aún no ejecutada */
    NEW,
    /** Orden ejecutada parcialmente (parte de la cantidad ya se cubrió) */
    PARTIALLY_FILLED,
    /** Orden completamente ejecutada */
    FILLED,
    /** Orden cancelada por el usuario o por el sistema */
    CANCELED,
    /** Orden rechazada por Binance (saldo insuficiente, filtros, etc.) */
    REJECTED,
    /** Orden expirada por tiempo (GTC, IOC, FOK) */
    EXPIRED,
    /** Error interno del bot al procesar la orden (no es estado de Binance) */
    ERROR
}