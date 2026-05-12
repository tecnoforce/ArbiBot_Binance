package com.arbitrage.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TradingStats {
    private double totalProfit;
    private double totalProfitPercent;
    private int eventosCompletados;
    private int eventosPorCompletar;
    private long timeElapsed;
    private int eventosStoploss;
    private double averageProfitQty;
    private double averageProfitPercent;
    private String baseCurrency;
    private double initialInvestment;
    private int eventsFinalizados;

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
