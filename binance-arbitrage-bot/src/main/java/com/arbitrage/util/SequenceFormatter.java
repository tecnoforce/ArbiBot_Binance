package com.arbitrage.util;

import com.arbitrage.model.SequenceOrder;
import com.arbitrage.model.TradingSequence;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.TimeZone;

public class SequenceFormatter {
    private static final String SEPARATOR = "================================================================================";
    private static final SimpleDateFormat DATE_FORMAT;

    static {
        DATE_FORMAT = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
        DATE_FORMAT.setTimeZone(TimeZone.getTimeZone("UTC"));
    }

    public static String formatEvent(TradingSequence seq) {
        StringBuilder sb = new StringBuilder();

        String timestamp = epochToString(seq.getTimestampInicio());

        sb.append(SEPARATOR).append("\n");
        sb.append(String.format("Seq #%d | %s | %s | %s\n",
                seq.getSeqId(),
                seq.getEstado(),
                seq.getModo(),
                timestamp));
        sb.append(String.format("  Triangle : %s\n", seq.getTriangleId()));
        sb.append(String.format("  Base     : %s %.4f\n", seq.getMonedaBase(), seq.getMontoBase()));
        sb.append(String.format("  Profit   : Expected=%.4f%% | Realized=%.4f%%\n",
                seq.getProfitEsperado(), seq.getProfitRealizado()));

        sb.append("\n");
        if (seq.getOp1() != null) sb.append(formatOrder(seq.getOp1())).append("\n");
        if (seq.getOp2() != null) sb.append(formatOrder(seq.getOp2())).append("\n");
        if (seq.getOp3() != null) sb.append(formatOrder(seq.getOp3())).append("\n");

        sb.append(SEPARATOR).append("\n");

        return sb.toString();
    }

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

        return String.format("  [op%d] %s %s %s  qty=%s @ %s | %s | %s",
                op.getOpIndice(),
                padRight(symbol, 8),
                padRight(side, 5),
                padRight(type, 6),
                padRight(executedQty, 12),
                padRight(executedPrice, 8),
                padRight(status, 8),
                elapsed);
    }

    private static String epochToString(long epochMs) {
        if (epochMs <= 0) return "---";
        return DATE_FORMAT.format(new Date(epochMs)) + " UTC";
    }

    private static String padRight(String s, int length) {
        if (s == null) s = "";
        if (s.length() >= length) return s.substring(0, length);
        return s + " ".repeat(length - s.length());
    }
}
