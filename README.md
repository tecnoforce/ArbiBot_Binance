# ArbiBot_Binance

Bot de arbitraje triangular para Binance Spot Market.

## Características

- Detección en tiempo real de oportunidades de arbitraje triangular
- Ejecución de órdenes en 3 pasos: BUY → TRADE → SELL
- Display en consola con colores: FILLED (verde), OPENED (cyan), WAITING (blanco), CANCELED/ERROR (rojo)
- Tags de estado `(CANCELED)` / `(CERRADA)` en headers de secuencia
- Soporte para Testnet y Mainnet
- Persistencia de secuencias en JSON

## Requisitos

- Java 11+
- Maven 3.x
- Cuenta en Binance (Testnet o Mainnet)

## Configuración

1. Crear archivo `user.apiConfig` con las credenciales de API:
```
apiKey TU_API_KEY
secretKey TU_SECRET_KEY
testnet true
```

2. Crear archivo `USDTNORMAL2.config` con los parámetros de trading

3. Crear archivo `USDTNORMAL2.coins` con los symbols a monitorear (uno por línea)

## Compilación

```powershell
Remove-Item binance-arbitrage-bot\target\*.jar -ErrorAction SilentlyContinue
mvn package -DskipTests -f binance-arbitrage-bot\pom.xml
```

## Ejecución

```powershell
java -jar binance-arbitrage-bot\target\binance-arbitrage-bot-1.4.1.jar
```

## Estructura del proyecto

```
binance-arbitrage-bot/
├── src/main/java/com/arbitrage/
│   ├── config/          # Configuración y loaders
│   ├── engine/          # ArbitrageEngine, TriangleCalculator
│   ├── model/           # POJOs (TradingSequence, SequenceOrder, etc.)
│   ├── trading/         # BinanceApiClient, OrderExecutor
│   ├── websocket/       # WebSocket client para precios
│   ├── persistence/     # SequenceFileManager (JSON)
│   ├── display/         # ConsoleDisplay
│   └── util/            # Log, MathUtils
├── pom.xml
```

## Arquitectura

```
WebSocket (precios) → ConcurrentHashMap<Ticker>
                         ↓
                    ArbitrageEngine (scan 100ms)
                         ↓
                  ArbitrageOpportunity callback
                         ↓
                    OrderExecutor (ejecuta)
                         ↓
                  BinanceApiClient (REST API)
                         ↓
                  SequenceFileManager (JSON + locks)
```

## Seguridad

**IMPORTANTE**: No commitees el archivo `user.apiConfig`. Contiene tus API keys.