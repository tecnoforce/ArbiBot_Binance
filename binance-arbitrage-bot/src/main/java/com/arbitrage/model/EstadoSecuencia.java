package com.arbitrage.model;

public enum EstadoSecuencia {
    ABIERTA,      // En progreso
    CERRADA,      // Completada exitosamente
    CANCELADA,    // Falló en Op2 o Op3 (datos completos)
    ERROR         // Legacy - mantenido por compatibilidad
}