package com.arbitrage.websocket;

import com.arbitrage.model.Ticker;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ConcurrentHashMap;

/**
 * Procesa los mensajes JSON de precio recibidos desde el WebSocket de Binance
 * y actualiza el {@link ConcurrentHashMap} de tickers compartido con el motor de arbitraje.
 * <p>
 * Esta clase es el núcleo de la ingestión de datos de mercado. Cada mensaje del stream
 * {@code bookTicker} contiene el mejor bid/ask del order book para un símbolo.
 * <p>
 * Formato esperado del mensaje:
 * <pre>
 *   {"s":"BTCUSDT","b":"50000.00","a":"50001.00","B":"1.5","A":"1.5"}
 * </pre>
 * Donde:
 * <ul>
 *   <li><b>s</b> = símbolo del par (ej. BTCUSDT)</li>
 *   <li><b>b</b> = bid price (mejor precio de compra)</li>
 *   <li><b>a</b> = ask price (mejor precio de venta)</li>
 *   <li><b>B</b> = bid quantity (cantidad disponible al bid)</li>
 *   <li><b>A</b> = ask quantity (cantidad disponible al ask)</li>
 * </ul>
 * <p>
 * También soporta mensajes envueltos en un objeto {@code data} (formato combinado):
 * <pre>
 *   {"data":{"s":"BTCUSDT","b":"50000.00","a":"50001.00","B":"1.5","A":"1.5"}}
 * </pre>
 * <p>
 * NOTA sobre logging: aunque esta clase importa SLF4J {@link Logger}, el proyecto
 * usa principalmente el sistema de logging custom ({@link com.arbitrage.util.Log}).
 * El SLF4J aquí es vestigial y no interfiere con el logging principal.
 *
 * @see com.arbitrage.model.Ticker
 * @see java.util.concurrent.ConcurrentHashMap
 */
public class PriceUpdateHandler {
    private static final Logger logger = LoggerFactory.getLogger(PriceUpdateHandler.class);
    
    /** Parser JSON de Jackson para deserializar los mensajes del WebSocket */
    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * Mapa de precios compartido con {@link com.arbitrage.engine.ArbitrageEngine}.
     * Clave: símbolo (ej. "BTCUSDT"), Valor: {@link Ticker} con bid/ask actualizados.
     * Es un {@link ConcurrentHashMap} para soporte thread-safe
     * (escrituras desde WebSocket + lecturas desde el motor de arbitraje).
     */
    private final ConcurrentHashMap<String, Ticker> priceMap;

    /**
     * Constructor.
     * @param priceMap Mapa donde almacenar precios (compartido)
     */
    public PriceUpdateHandler(ConcurrentHashMap<String, Ticker> priceMap) {
        this.priceMap = priceMap;
    }

    /**
     * Procesa un mensaje de actualización de precio proveniente del WebSocket.
     * <p>
     * Parsea el JSON, extrae los campos del bookTicker y actualiza el mapa de precios
     * compartido ({@link #priceMap}) para que el motor de arbitraje tenga datos frescos.
     * Este método es invocado desde {@link BinanceWebSocketClient#connectAndSubscribe}
     * (vía OkHttp) y desde {@link NettyWebSocketClient.WebSocketClientHandler#handleTextFrame}
     * (vía Netty).
     *
     * @param jsonMessage Mensaje JSON crudo recibido del WebSocket
     * @throws Exception Si el JSON es inválido o los campos numéricos no son parseables
     */
    public void handlePriceUpdate(String jsonMessage) throws Exception {
        // Parsea el mensaje JSON completo a un árbol de nodos de Jackson
        JsonNode root = objectMapper.readTree(jsonMessage);

        // Binance a veces envuelve el payload en un campo "data" (streams combinados).
        // Si existe "data", se extrae su contenido; si no, se usa la raíz directamente.
        JsonNode dataNode = root.has("data") ? root.get("data") : root;

        // Verifica que el mensaje contenga al menos símbolo (s), bid (b) y ask (a)
        if (dataNode.has("s") && dataNode.has("b") && dataNode.has("a")) {
            // Extrae el símbolo del par (ej. "BTCUSDT")
            String symbol = dataNode.get("s").asText();
            // Mejor precio de compra (bid) del order book
            double bidPrice = Double.parseDouble(dataNode.get("b").asText());
            // Mejor precio de venta (ask) del order book
            double askPrice = Double.parseDouble(dataNode.get("a").asText());
            // Cantidad disponible al bid (opcional: 0 si no está presente)
            double bidQty = dataNode.has("B") ? Double.parseDouble(dataNode.get("B").asText()) : 0.0;
            // Cantidad disponible al ask (opcional: 0 si no está presente)
            double askQty = dataNode.has("A") ? Double.parseDouble(dataNode.get("A").asText()) : 0.0;

            // Construye el objeto Ticker usando el patrón Builder de Lombok
            Ticker ticker = Ticker.builder()
                    .symbol(symbol)
                    .bidPrice(bidPrice)
                    .askPrice(askPrice)
                    .bidQty(bidQty)
                    .askQty(askQty)
                    .build();

            // Actualiza el mapa compartido con el nuevo ticker.
            // ConcurrentHashMap.put() es thread-safe: puede ser llamado desde
            // el thread del WebSocket mientras el motor de arbitraje lee desde otro thread.
            priceMap.put(symbol, ticker);
        }
    }
}