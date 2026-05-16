package com.arbitrage.util;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * MiniIdGenerator - Generador de IDs cortos alfanumericos de 8 caracteres.
 *
 * Proposito: Generar identificadores unicos y legibles para las secuencias
 * de trading (TradingSequence). Usa un contador atomico como fuente de
 * entropia, codificando el valor en base-32 con el alfabeto CHARS.
 *
 * Caracteristicas:
 * - Thread-safe: usa AtomicInteger para el contador compartido
 * - Deterministico: mismo contador produce mismo ID (util para testing)
 * - Longitud fija: siempre 8 caracteres (ej: "A3K9X2M7")
 * - Alfabeto seguro: solo letras mayusculas y digitos (sin caracteres confusos)
 *
 * Uso tipico: TradingSequence.miniId = MiniIdGenerator.generate()
 *
 * Nota: Este generador NO es criptograficamente seguro. Si se reinicia
 * la JVM, el contador vuelve a 0 y los IDs se repiten. Para IDs unicos
 * globales, usar NanoIdGenerator (que usa SecureRandom).
 *
 * @see NanoIdGenerator Generador de IDs criptograficamente seguro
 */
public class MiniIdGenerator {

    /** Alfabeto de 32 caracteres: 26 letras mayusculas + 6 digitos.
     * Se usa para codificar el contador en base-32. */
    private static final String CHARS = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";

    /** Longitud fija del ID generado (siempre 8 caracteres). */
    private static final int LENGTH = 8;

    /** Contador atomico que se incrementa con cada llamada a generate().
     * Thread-safe: multiple hilos pueden generar IDs simultaneamente sin colisiones. */
    private static final AtomicInteger counter = new AtomicInteger(0);

    /**
     * Genera un nuevo ID unico de 8 caracteres.
     *
     * Algoritmo:
     * 1. Obtiene el siguiente valor del contador (atomico, thread-safe)
     * 2. Para cada posicion (de derecha a izquierda), extrae 5 bits del numero
     *    (32 valores posibles = log2(32) = 5 bits por caracter)
     * 3. Usa los 5 bits como indice en el alfabeto CHARS
     * 4. Concatena los caracteres en orden inverso para mantener monotonicidad
     *
     * @return ID de 8 caracteres (ej: "AAAAAAAA", "AAAAAAAB", ...)
     */
    public static String generate() {
        // Obtiene y incrementa el contador de forma atomica
        int num = counter.getAndIncrement();
        StringBuilder sb = new StringBuilder(LENGTH);
        // Recorre de derecha a izquierda: cada iteracion extrae 5 bits
        for (int i = LENGTH - 1; i >= 0; i--) {
            // Desplaza 5*i bits a la derecha y mascara con 31 (0b11111)
            // para obtener un valor entre 0 y 31 (indice valido en CHARS)
            int index = (num >> (i * 5)) & 31;
            sb.append(CHARS.charAt(index));
        }
        return sb.toString();
    }

    /**
     * Resetea el contador a 0.
     * Util para testing o cuando se quiere reiniciar la secuencia de IDs.
     * Advertencia: despues del reset, los IDs generados se repetiran.
     */
    public static void reset() {
        counter.set(0);
    }
}