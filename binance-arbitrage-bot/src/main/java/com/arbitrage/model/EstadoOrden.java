package com.arbitrage.model;

public enum EstadoOrden {
    PENDING,
    NEW,
    PARTIALLY_FILLED,
    FILLED,
    CANCELED,
    REJECTED,
    EXPIRED,
    ERROR
}