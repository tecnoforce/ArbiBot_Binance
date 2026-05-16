package com.arbitrage.util;

import com.arbitrage.model.SequenceOrder;
import com.arbitrage.model.TradingSequence;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.TimeZone;

/**
 * SequenceFormatter - Formateador de secuencias de trading para exportacion.
 *
 * Proposito: Generar representaciones de texto plano y estructurado de las
 * secuencias de trading (TradingSequence) para persistencia en archivos de
 * reporte, depuracion post-mortem o visualizacion fuera del dashboard.
 *
 * Diferencia clave con ConsoleDisplay:
 *   ConsoleDisplay      -> Tiempo real, colores ANSI, animacion, consola
 *   SequenceFormatter   -> Texto plano, archivos, exportacion, post-analisis
 *
 * Formato de salida tipico:
 *   ================================================================================
 *   Seq #ID | ESTADO | MODO | timestamp UTC
 *     Triangle : BTCUSDT-ETHUSDT-ETHBTC
 *     Base     : USDT 100.0000
 *     Profit   : Expected=0.5000% | Realized=0.4500%
 *
 *   [op1] SYMBOL   SIDE   TYPE   qty=X.XXXXXX @ Y.YYYYYY | STATUS   | ZZZms
 *   [op2] ...
 *   [op3] ...
 *   ================================================================================
 *
 * Campos nulos:
 *   Se reemplazan por "------" para mantener la alineacion de columnas
 *   incluso cuando la orden no tiene datos completos (ej. orden no ejecutada).
 *
 * Zona horaria:
 *   Todos los timestamps se formatean en UTC para consistencia internacional.
 */
public class SequenceFormatter {
    // Separador decorativo de 80 caracteres que delimita cada evento
    private static final String SEPARATOR = "================================================================================";
    // Formato de fecha estandar ISO para timestamps en UTC
    private static final SimpleDateFormat DATE_FORMAT;

    static {
        DATE_FORMAT = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
        DATE_FORMAT.setTimeZone(TimeZone.getTimeZone("UTC"));
    }

    /**
     * Formatea una secuencia de trading completa como texto estructurado.
     * Construye un bloque delimitado por separadores que incluye:
     * - Cabecera: ID de secuencia, estado, modo (LIVE/SIMULATED), timestamp UTC
     * - Datos del triangulo: identificador del triangulo detectado
     * - Moneda base y monto invertido
     * - Profit esperado (calculado por TriangleCalculator) y realizado
     * - Detalle de cada orden (op1, op2, op3) si existen
     *
     * @param seq Secuencia de trading a formatear (no debe ser null)
     * @return String con el evento completo formateado para exportacion
     */
    public static String formatEvent(TradingSequence seq) {
        StringBuilder sb = new StringBuilder();

        // Convertir timestamp de milisegundos epoch a formato legible UTC
        String timestamp = epochToString(seq.getTimestampInicio());

        // Construir bloque: separador + cabecera + datos + ordenes + separador
        sb.append(SEPARATOR).append("\n");
        sb.append(String.format("Seq: %s | %s | %s | %s\n",
                seq.getMiniId(),
                seq.getEstado(),
                seq.getModo(),
                timestamp));
        sb.append(String.format("  Triangle : %s\n", seq.getTriangleId()));
        sb.append(String.format("  Base     : %s %.4f\n", seq.getMonedaBase(), seq.getMontoBase()));
        sb.append(String.format("  Profit   : Expected=%.4f%% | Realized=%.4f%%\n",
                seq.getProfitEsperado(), seq.getProfitRealizado()));

        sb.append("\n");
        // Formatear cada orden si existe (pueden ser null si no se ejecutaron)
        if (seq.getOp1() != null) sb.append(formatOrder(seq.getOp1())).append("\n");
        if (seq.getOp2() != null) sb.append(formatOrder(seq.getOp2())).append("\n");
        if (seq.getOp3() != null) sb.append(formatOrder(seq.getOp3())).append("\n");

        sb.append(SEPARATOR).append("\n");

        return sb.toString();
    }

    /**
     * Formatea una orden individual dentro de una secuencia de trading.
     * Columnas formateadas: indice de operacion, symbol, side (BUY/SELL),
     * type (MARKET/LIMIT), cantidad ejecutada, precio ejecutado, estado,
     * y tiempo transcurrido en milisegundos.
     * Los campos nulos se reemplazan por "------" para mantener la alineacion.
     *
     * @param op Orden de la secuencia a formatear (SequenceOrder)
     * @return String con la linea de orden formateada y alineada
     */
    private static String formatOrder(SequenceOrder op) {
        String symbol = op.getSymbol() != null ? op.getSymbol() : "------";
        String side = op.getSide() != null ? op.getSide() : "---";
        String type = op.getType() != null ? op.getType() : "----";
        String status = op.getOrderStatus() != null ? op.getOrderStatus().toString() : "------";
        String executedQty = String.format("%.6f", op.getCantidadEjecutada());
        String executedPrice = op.getPrecioEjecutado() > 0 ? String.format("%.6f", op.getPrecioEjecutado()) : "0.0";
        String qty = String.format("%.6f", op.getQuantity());
        String price = op.getPrice() > 0 ? String.format("%.6f", op.getPrice()) : "0.0";
        String elapsed = op.getTiempoTranscurridoMs() + "ms";

        return String.format("  [op%d] | %s %s %s  qty=%s @ %s | %s | %s",
                op.getOpIndice(),
                padRight(symbol, 8),
                padRight(side, 5),
                padRight(type, 6),
                padRight(executedQty, 12),
                padRight(executedPrice, 8),
                padRight(status, 8),
                elapsed);
    }

    /**
     * Convierte un timestamp en milisegundos desde epoch (1970-01-01) a una
     * cadena legible en formato "yyyy-MM-dd HH:mm:ss UTC".
     * Si el timestamp es <= 0 (invalido o no establecido), retorna "---".
     *
     * @param epochMs Milisegundos desde el epoch de Unix
     * @return Fecha formateada en UTC o "---" si es invalido
     */
    private static String epochToString(long epochMs) {
        if (epochMs <= 0) return "---";
        return DATE_FORMAT.format(new Date(epochMs)) + " UTC";
    }

    /**
     * Rellena un String con espacios en blanco a la derecha hasta alcanzar
     * el largo especificado (alineacion de columnas para salida tabular).
     * Si el String es mas largo que el length solicitado, lo trunca.
     * Si es null, lo trata como cadena vacia.
     *
     * @param s String a alinear (puede ser null)
     * @param length Largo minimo deseado de la cadena resultante
     * @return String con al menos 'length' caracteres (espacios a la derecha)
     */
    private static String padRight(String s, int length) {
        if (s == null) s = "";
        if (s.length() >= length) return s.substring(0, length);
        return s + " ".repeat(length - s.length());
    }
}
