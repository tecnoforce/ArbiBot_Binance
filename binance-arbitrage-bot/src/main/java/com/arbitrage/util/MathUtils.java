package com.arbitrage.util;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Utilidades matematicas para el bot.
 * Redondeos y formateo de numeros.
 */
public class MathUtils {

    /**
     * Redondea un valor a decimales especificados.
     * @param value Valor a redondear
     * @param decimalPlaces Numero de decimales
     * @return Valor redondeado
     */
    public static double round(double value, int decimalPlaces) {
        return BigDecimal.valueOf(value)
                .setScale(decimalPlaces, RoundingMode.HALF_UP)
                .doubleValue();
    }

    /**
     * Redondea a 8 decimales.
     */
    public static double roundTo8(double value) {
        return round(value, 8);
    }

    /**
     * Redondea a 4 decimales.
     */
    public static double roundTo4(double value) {
        return round(value, 4);
    }

    /**
     * Division segura (evita division por cero).
     */
    public static double safeDivide(double numerator, double denominator) {
        if (denominator == 0) {
            return 0;
        }
        return numerator / denominator;
    }

    /**
     * Verifica si un valor es cero (con epsilon).
     */
    public static boolean isZero(double value) {
        return Math.abs(value) < 1e-10;
    }

    /**
     * Verifica si un valor es positivo.
     */
    public static boolean isPositive(double value) {
        return value > 1e-10;
    }

    /**
     * Formatea cantidad para display.
     * Si es >= 1 usa 8 decimales.
     * Si es < 1 remueve el cero inicial.
     */
    public static String formatQuantity(double qty) {
        if (qty >= 1) {
            return String.format("%.8f", qty);
        } else {
            String formatted = String.format("%.8f", qty);
            if (formatted.startsWith("0.")) {
                return formatted.substring(1);
            }
            return formatted;
        }
    }

    /**
     * Formatea precio para display.
     */
    public static String formatPrice(double price) {
        return String.format("%.8f", price);
    }
}