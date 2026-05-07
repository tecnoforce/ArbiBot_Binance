package com.arbitrage.websocket;

import com.arbitrage.model.Ticker;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ConcurrentHashMap;

/**
 * Procesa mensajes de precios recibidos del WebSocket.
 * Formato esperado:
 *   {"s":"BTCUSDT","b":"50000.00","a":"50001.00","B":"1.5","A":"1.5"}
 * Donde:
 *   s = simbolo
 *   b = bid price (precio de venta)
 *   a = ask price (precio de compra)
 *   B = bid qty (cantidad bid)
 *   A = ask qty (cantidad ask)
 * 
 * Tambien puede llegar envuelto:
 *   {"data":{...}}
 */
public class PriceUpdateHandler {
    private static final Logger logger = LoggerFactory.getLogger(PriceUpdateHandler.class);
    
    // Parser JSON
    private final ObjectMapper objectMapper = new ObjectMapper();
    
    // Mapa de precios (compartido con ArbitrageEngine)
    private final ConcurrentHashMap<String, Ticker> priceMap;

    /**
     * Constructor.
     * @param priceMap Mapa donde almacenar precios (compartido)
     */
    public PriceUpdateHandler(ConcurrentHashMap<String, Ticker> priceMap) {
        this.priceMap = priceMap;
    }

    /**
     * Procesa un mensaje de actualizacion de precio.
     * @param jsonMessage Mensaje JSON del WebSocket
     * @throws Exception Si hay error al parsear
     */
    public void handlePriceUpdate(String jsonMessage) throws Exception {
        // Parsea JSON
        JsonNode root = objectMapper.readTree(jsonMessage);

        // Extrae nodo data (si existe wrapper) o usa root
        JsonNode dataNode = root.has("data") ? root.get("data") : root;

        // Verifica que tenga campos necesarios
        if (dataNode.has("s") && dataNode.has("b") && dataNode.has("a")) {
            // Extrae datos
            String symbol = dataNode.get("s").asText();
            double bidPrice = Double.parseDouble(dataNode.get("b").asText());
            double askPrice = Double.parseDouble(dataNode.get("a").asText());
            double bidQty = dataNode.has("B") ? Double.parseDouble(dataNode.get("B").asText()) : 0.0;
            double askQty = dataNode.has("A") ? Double.parseDouble(dataNode.get("A").asText()) : 0.0;

            // Crea ticker
            Ticker ticker = Ticker.builder()
                    .symbol(symbol)
                    .bidPrice(bidPrice)
                    .askPrice(askPrice)
                    .bidQty(bidQty)
                    .askQty(askQty)
                    .build();
            
            // Actualiza mapa
            priceMap.put(symbol, ticker);
        }
    }
}