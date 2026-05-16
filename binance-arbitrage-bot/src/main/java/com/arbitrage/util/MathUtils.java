package com.arbitrage.util;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * MathUtils - Utilidades matematicas y de formateo numerico.
 *
 * Proporciona metodos estaticos para operaciones comunes en el bot:
 *
 * - Redondeo con BigDecimal: precision exacta evitando errores de punto
 *   flotante inherentes a double (ej. 0.1 + 0.2 != 0.3 en IEEE 754).
 *   Usa RoundingMode.HALF_UP (redondeo comercial estandar).
 *
 * - Division segura: evita DivisionByZeroException retornando 0 cuando
 *   el denominador es 0 (comun en calculos de profit cuando no hay base).
 *
 * - Comparacion con epsilon: usa tolerancia 1e-10 para manejar imprecisiones
 *   de calculos de punto flotante en las validaciones.
 *
 * - Formateo de cantidades: remueve el cero inicial para valores < 1
 *   (ej. muestra ".00001225" en vez de "0.00001225") para mejor legibilidad.
 *
 * Usado principalmente por:
 *   TriangleCalculator - para redondear profits calculados
 *   ConsoleDisplay     - para formatear cantidades y precios en UI
 *   OrderExecutor      - para validar montos antes de enviar ordenes a Binance
 */
public class MathUtils {

    /**
     * Redondea un valor double a un numero especifico de decimales.
     *
     * Usa BigDecimal internamente para evitar errores de precision
     * inherentes al tipo double (IEEE 754). Por ejemplo, 0.1 + 0.2
     * en double da 0.30000000000000004, pero con BigDecimal se
     * obtiene el resultado exacto.
     *
     * Usa RoundingMode.HALF_UP (redondeo comercial estandar):
     * - Si el digito siguiente >= 5, redondea hacia arriba
     * - Si el digito siguiente < 5, redondea hacia abajo
     *
     * @param value Valor a redondear
     * @param decimalPlaces Numero de decimales deseado (0-8 tipico)
     * @return Valor redondeado con la precision especificada
     */
    public static double round(double value, int decimalPlaces) {
        return BigDecimal.valueOf(value)
                .setScale(decimalPlaces, RoundingMode.HALF_UP)
                .doubleValue();
    }

    /**
     * Redondea un valor a 8 decimales.
     *
     * Conveniencia para cantidades de criptomonedas, que en Binance
     * suelen tener precision de hasta 8 decimales (ej: 0.00123456 BTC).
     *
     * @param value Valor a redondear
     * @return Valor redondeado a 8 decimales
     */
    public static double roundTo8(double value) {
        return round(value, 8);
    }

    /**
     * Redondea un valor a 4 decimales.
     *
     * Conveniencia para precios de criptomonedas de bajo valor
     * o porcentajes que requieren 4 decimales de precision.
     *
     * @param value Valor a redondear
     * @return Valor redondeado a 4 decimales
     */
    public static double roundTo4(double value) {
        return round(value, 4);
    }

    /**
     * Division segura que evita ArithmeticException por division por cero.
     *
     * En el contexto del bot, esto es comun cuando se calculan ratios
     * de profit y el denominador (balance, cantidad) es 0. En lugar
     * de lanzar una excepcion, retorna 0 como valor seguro.
     *
     * @param numerator   Numerador de la division
     * @param denominator Denominador de la division
     * @return numerator/denominator, o 0 si denominator es 0
     */
    public static double safeDivide(double numerator, double denominator) {
        if (denominator == 0) {
            return 0;
        }
        return numerator / denominator;
    }

    /**
     * Verifica si un valor double es efectivamente cero.
     *
     * Usa una tolerancia epsilon de 1e-10 para manejar las imprecisiones
     * inherentes de los calculos de punto flotante. Por ejemplo, un
     * calculo que deberia dar 0 podria dar 0.0000000000001 debido a
     * errores de redondeo acumulados.
     *
     * @param value Valor a verificar
     * @return true si |value| < 1e-10 (efectivamente cero)
     */
    public static boolean isZero(double value) {
        return Math.abs(value) < 1e-10;
    }

    /**
     * Verifica si un valor double es estrictamente positivo.
     *
     * Usa la misma tolerancia epsilon que isZero() para evitar
     * falsos positivos con valores muy cercanos a cero.
     *
     * @param value Valor a verificar
     * @return true si value > 1e-10 (positivo significativo)
     */
    public static boolean isPositive(double value) {
        return value > 1e-10;
    }

    /**
     * Formatea una cantidad para mostrar en la UI de consola.
     *
     * Reglas de formateo:
     * - Si qty >= 1: muestra con 8 decimales completo (ej: "1.00000000")
     * - Si qty < 1: remueve el cero inicial para mejor legibilidad
     *   (ej: ".00001225" en vez de "0.00001225")
     *
     * Este formato se usa en ConsoleDisplay y SequenceFormatter para
     * mostrar cantidades de criptomonedas de forma compacta.
     *
     * @param qty Cantidad a formatear
     * @return String formateado con 8 decimales (sin cero inicial si < 1)
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
     * Formatea un precio para mostrar en la UI de consola.
     *
     * Siempre usa 8 decimales para mantener consistencia con la
     * precision de Binance. No remueve el cero inicial (a diferencia
     * de formatQuantity) porque los precios siempre se muestran
     * con el formato estandar.
     *
     * @param price Precio a formatear
     * @return String formateado con 8 decimales (ej: "50000.00000000")
     */
    public static String formatPrice(double price) {
        return String.format("%.8f", price);
    }
}