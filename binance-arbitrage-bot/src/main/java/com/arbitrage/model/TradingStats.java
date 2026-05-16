package com.arbitrage.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.IOException;

/**
 * Estadísticas acumuladas de trading durante la sesión del bot.
 * <p>
 * Almacena métricas globales como profit total, cantidad de eventos
 * completados/pendientes, tiempo transcurrido, eventos de stop-loss,
 * promedios, y la inversión inicial. Se usa en la UI de consola
 * ({@code ConsoleDisplay}) para mostrar el rendimiento del bot.
 * </p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TradingStats {
    /** Profit total acumulado en unidades de moneda base */
    @JsonSerialize(using = FourDecimalDoubleSerializer.class)
    private double totalProfit;
    /** Profit total acumulado en porcentaje */
    @JsonSerialize(using = FourDecimalDoubleSerializer.class)
    private double totalProfitPercent;
    /** Número de secuencias completadas exitosamente */
    private int eventosCompletados;
    /** Número de secuencias aún abiertas o pendientes */
    private int eventosPorCompletar;
    /** Tiempo total transcurrido desde el inicio de la sesión en ms */
    private long timeElapsed;
    /** Número de eventos que activaron stop-loss */
    private int eventosStoploss;
    /** Profit promedio por evento en unidades de moneda base */
    @JsonSerialize(using = FourDecimalDoubleSerializer.class)
    private double averageProfitQty;
    /** Profit promedio por evento en porcentaje */
    @JsonSerialize(using = FourDecimalDoubleSerializer.class)
    private double averageProfitPercent;
    /** Moneda base utilizada (ej: "USDT") */
    private String baseCurrency;
    /** Inversión inicial en la moneda base (siempre 2 decimales) */
    @JsonSerialize(using = TwoDecimalDoubleSerializer.class)
    private double initialInvestment;
    /** Número total de eventos finalizados (completados + cancelados + error) */
    private int eventsFinalizados;

    /**
     * Serializer custom para escribir doubles con exactamente 2 decimales.
     */
    public static class TwoDecimalDoubleSerializer extends JsonSerializer<Double> {
        @Override
        public void serialize(Double value, JsonGenerator gen, SerializerProvider serializers) throws IOException {
            if (value != null) {
                gen.writeNumber(String.format("%.2f", value));
            } else {
                gen.writeNumber(0.00);
            }
        }
    }

    /**
     * Serializer custom para escribir doubles con exactamente 4 decimales.
     */
    public static class FourDecimalDoubleSerializer extends JsonSerializer<Double> {
        @Override
        public void serialize(Double value, JsonGenerator gen, SerializerProvider serializers) throws IOException {
            if (value != null) {
                gen.writeNumber(String.format("%.4f", value));
            } else {
                gen.writeNumber(0.0000);
            }
        }
    }

    /**
     * Calcula los promedios de profit por evento completado.
     * <p>
     * Si no hay eventos completados, ambos promedios se establecen a 0.
     * Se usa al finalizar cada evento para mantener las métricas actualizadas.
     * </p>
     */
    @JsonIgnore
    public void computeAverages() {
        if (eventosCompletados > 0) {
            averageProfitQty = totalProfit / eventosCompletados;
            averageProfitPercent = totalProfitPercent / eventosCompletados;
        } else {
            averageProfitQty = 0.0;
            averageProfitPercent = 0.0;
        }
    }
}
