# ArbiBot_Binance

Bot de arbitraje triangular para Binance Spot Market. v1.5.1

## Características

- Detección en tiempo real de oportunidades de arbitraje triangular (~100ms scan)
- Ejecución de órdenes en 3 pasos: BUY → TRADE → SELL con validaciones de riesgo
- WebSocket en tiempo real para actualización de precios (OkHttp)
- Display en consola con colores: FILLED (verde), OPENED (cyan), WAITING (blanco), CANCELED/ERROR (rojo)
- Repricing dinámico ante cambios de mercado
- Fill tracking con seguimiento de estado de órdenes
- Wallet sync para balances precisos
- Estadísticas persistentes con 4 decimales de precisión
- Persistencia de secuencias en JSON con escritura atómica
- Soporte para Testnet y Mainnet
- Order speed benchmark integrado

## Requisitos

- Java 21 (LTS requerido)
- Maven 3.x
- Cuenta en Binance (Testnet o Mainnet)

## Configuración

1. Crear archivo `user.apiConfig` con las credenciales de API:
```
apiKey TU_API_KEY
secretKey TU_SECRET_KEY
testnet true
```
2. Crear archivo `USDTNORMAL2.config` con los parámetros de trading (formato `key value`)
3. Crear archivo `USDTNORMAL2.coins` con los symbols a monitorear (uno por línea, formato `SYMBOLUSDT`)

## Compilación

```powershell
Remove-Item binance-arbitrage-bot\target\*.jar -ErrorAction SilentlyContinue
mvn package -DskipTests -f binance-arbitrage-bot\pom.xml
```

## Ejecución

```powershell
java -jar binance-arbitrage-bot\target\binance-arbitrage-bot-1.5.1.jar
```

## Estructura del proyecto

```
binance-arbitrage-bot/
├── src/main/java/com/arbitrage/
│   ├── config/          # ConfigLoader, AppConfig, NetworkEndpoints, ApiConfig
│   ├── engine/          # ArbitrageEngine, TriangleCalculator, ProfitCalculator, PrecisionAdjuster
│   ├── model/           # POJOs con Lombok (TradingSequence, SequenceOrder, TradingStats, etc.)
│   ├── trading/         # BinanceApiClient, OrderExecutor, WalletSyncManager
│   ├── websocket/       # BinanceWebSocketClient (OkHttp), PriceUpdateHandler, RestPriceFallback
│   ├── persistence/     # SequenceFileManager (JSON + atomic locks), SequenceRecoveryManager
│   ├── module/          # Módulos funcionales plugables
│   │   ├── ArbitrageDetector   # Escaneo cíclico de triángulos
│   │   ├── ExecutionEngine     # Orquestación de órdenes 3-pasos
│   │   ├── FillTracker         # Seguimiento de fills
│   │   ├── MarketDataEngine    # Mapa de precios thread-safe
│   │   ├── ProfitCalculator    # Cálculo de ganancias netas
│   │   ├── RepricingEngine     # Reajuste dinámico de precios
│   │   └── RiskManager         # Validaciones de riesgo y límites
│   ├── display/         # ConsoleDisplay (UI de consola)
│   ├── test/            # OrderSpeedBenchmark, TestnetBalanceChecker
│   └── util/            # Log (custom), MathUtils, StatsManager, SequenceFormatter
├── test/                # Tests unitarios
│   └── java/com/arbitrage/
└── pom.xml
```

## Arquitectura

```
WebSocket (precios) → ConcurrentHashMap<Ticker>
                         ↓
                    MarketDataEngine (actualiza)
                         ↓
                    ArbitrageDetector (scan 100ms)
                         ↓
                  ArbitrageOpportunity callback
                         ↓
                    ExecutionEngine + RiskManager
                         ↓
                    BinanceApiClient (REST API)
                         ↓
                  SequenceFileManager (JSON + atomic locks)
                         ↓
                     StatsManager (.stats JSON)
```

## Módulos del Sistema

| Módulo | Responsabilidad |
|--------|----------------|
| ArbitrageDetector | Escanea triángulos cada 100ms |
| ExecutionEngine | Orquesta BUY → CONVERT → SELL con validaciones |
| FillTracker | Seguimiento de órdenes y estados de fill |
| MarketDataEngine | Precios en ConcurrentHashMap thread-safe |
| ProfitCalculator | Ganancias netas con comisiones |
| RepricingEngine | Reajuste dinámico ante cambios de mercado |
| RiskManager | Límites de balance, máx. trades abiertos |

## Testnet vs Mainnet

| Modo | Config | Symbols |
|------|--------|---------|
| TESTNET | `user.apiConfig.testnet = true` | ~190 symbols desde API |
| MAINNET | `user.apiConfig.testnet = false` | ~42 symbols de `USDTNORMAL2.coins` |

## Seguridad

**IMPORTANTE**: No commitees el archivo `user.apiConfig`. Contiene tus API keys.

## Logging

Sistema de logging custom con tags de 3-4 caracteres. Niveles: `ERROR`, `WARN`, `INFO`, `SCAN`, `DEBUG`. No usa SLF4J/Logback.

## Dependencias

- Java 21 requerido
- OkHttp 4.12.0 (WebSocket + REST)
- Jackson 2.17.1 (JSON serialization)
- Lombok 1.18.34 (modelos)
- Maven shade plugin (Fat JAR)
