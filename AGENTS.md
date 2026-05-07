# Binance Arbitrage Bot - AGENTS.md

Este archivo define las instrucciones para agentes de IA que trabajan en el proyecto de Binance Arbitrage Bot.

## 📋 Comandos de Construcción y Ejecución

```powershell
# Eliminar JAR antigua (OBLIGATORIO antes de recompilar)
Remove-Item D:\ARBITRAJE_BOT\BOT_Nuevo_V1\binance-arbitrage-bot\target\*.jar -ErrorAction SilentlyContinue

# Compilar
mvn package -DskipTests -f D:\ARBITRAJE_BOT\BOT_Nuevo_V1\binance-arbitrage-bot\pom.xml

# Ejecutar
java -jar D:\ARBITRAJE_BOT\BOT_Nuevo_V1\binance-arbitrage-bot\target\binance-arbitrage-bot-1.0.0.jar
```

**Nota crítica**: Maven puede cachear el JAR viejo. Siempre eliminarlo antes de compilar.

## 🏗 Arquitectura y Estructura

### Capas del Proyecto

| Capa | Archivos | Responsabilidad |
|------|----------|-----------------|
| `config/` | [ConfigLoader.java](binance-arbitrage-bot/src/main/java/com/arbitrage/config/ConfigLoader.java), [AppConfig.java](binance-arbitrage-bot/src/main/java/com/arbitrage/config/AppConfig.java) | Parsing de archivos `.config` en formato `key value` |
| `model/` | [TradingSequence.java](binance-arbitrage-bot/src/main/java/com/arbitrage/model/TradingSequence.java), [SequenceOrder.java](binance-arbitrage-bot/src/main/java/com/arbitrage/model/SequenceOrder.java) | POJOs con Lombok, enums para estados |
| `engine/` | [ArbitrageEngine.java](binance-arbitrage-bot/src/main/java/com/arbitrage/engine/ArbitrageEngine.java), [TriangleCalculator.java](binance-arbitrage-bot/src/main/java/com/arbitrage/engine/TriangleCalculator.java) | Detección de oportunidades, cálculo de profits |
| `trading/` | [OrderExecutor.java](binance-arbitrage-bot/src/main/java/com/arbitrage/trading/OrderExecutor.java), [BinanceApiClient.java](binance-arbitrage-bot/src/main/java/com/arbitrage/trading/BinanceApiClient.java) | Ejecución de órdenes REST API |
| `persistence/` | [SequenceFileManager.java](binance-arbitrage-bot/src/main/java/com/arbitrage/persistence/SequenceFileManager.java) | JSON file con locks atómicos |
| `websocket/` | [BinanceWebSocketClient.java](binance-arbitrage-bot/src/main/java/com/arbitrage/websocket/BinanceWebSocketClient.java) | Actualización de precios en tiempo real |
| `display/` | [ConsoleDisplay.java](binance-arbitrage-bot/src/main/java/com/arbitrage/display/ConsoleDisplay.java) | UI de consola |
| `util/` | [Log.java](binance-arbitrage-bot/src/main/java/com/arbitrage/util/Log.java) | Sistema de logging custom |

### Flujo de Ejecución

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

## ⚙️ Convenciones del Proyecto

### Configuración (NO properties file)
- **Formato**: `key value` (texto plano, separados por espacios)
- **Parser**: `line.split("\\s+")` → case-insensitive (`toLowerCase()`)
- **Comentarios**: Líneas con `#`
- **Archivos en root**:
  - [USDTNORMAL2.config](USDTNORMAL2.config): Parámetros de trading
  - [user.apiConfig](user.apiConfig): Credenciales + `testnet true/false`
  - [USDTNORMAL2.coins](USDTNORMAL2.coins): Lista de symbols para MAINNET

### Logging (CUSTOM, NO SLF4J)
- **Niveles**: `ERROR`, `WARN`, `INFO`, `SCAN`, `DEBUG`
- **Tags**: 3-4 caracteres (`API`, `Engine`, `ORDER_EXEC`, `SEQ_FILE`)
- **Implementación**: [Log.java](binance-arbitrage-bot/src/main/java/com/arbitrage/util/Log.java) (no SLF4J/Logback)

### Modelos
- **Annotaciones**: `@Data @Builder @NoArgsConstructor @AllArgsConstructor` (Lombok)
- **Factory methods**: `TradingSequence.create()`, `SequenceOrder.create()`
- **Métodos computados**: `@JsonIgnore` (ej. `getOrdenes()`, `isTodasFilled()`)

### Persistencia
- **Formato**: JSON map indexado por `seqId`
- **Escrituras atómicas**: `temp file → rename`
- **Locking**: `ReentrantLock` por operación

## 🌐 Testnet vs Mainnet

| Modo | Configuración | Symbols |
|------|---------------|---------|
| **TESTNET** | `user.apiConfig.testnet = true` | ~190 symbols desde API |
| **MAINNET** | `user.apiConfig.testnet = false` | ~42 symbols de [USDTNORMAL2.coins](USDTNORMAL2.coins) |

**API Endpoints**: Cambian según [NetworkEndpoints.java](binance-arbitrage-bot/src/main/java/com/arbitrage/config/NetworkEndpoints.java)

## ⚠️ Problemas Comunes

| Problema | Causa | Solución |
|----------|-------|----------|
| **JAR cacheado** | Maven reusa JAR viejo | `Remove-Item target/*.jar` antes de `mvn package` |
| **0 triángulos detectados** | Pares no existen en Binance | Verificar con API, actualizar [USDTNORMAL2.coins](USDTNORMAL2.coins) |
| **Archivos config faltantes** | Main.java requiere 3 archivos | Crear [USDTNORMAL2.config](USDTNORMAL2.config), [user.apiConfig](user.apiConfig), [USDTNORMAL2.coins](USDTNORMAL2.coins) |

## 📦 Dependencias

- **Java**: 11 (requerido)
- **Maven Plugins**: `maven-shade-plugin` (Fat JAR), `maven-jar-plugin`
- **Librerías**: OkHttp 4.12.0, Jackson 2.17.1, Lombok 1.18.34
- **NO usado**: SLF4J, Logback (logging custom con [Log.java](binance-arbitrage-bot/src/main/java/com/arbitrage/util/Log.java))

## 🚨 Restricciones de Desarrollo

1. **NO modificar**: [ConsoleDisplay.java](binance-arbitrage-bot/src/main/java/com/arbitrage/display/ConsoleDisplay.java) (UI es parte del contrato)
2. **Leer código existente**: Antes de implementar nuevas features
3. **Seguir patrones de I/O**: Usar el mismo enfoque de [SequenceFileManager.java](binance-arbitrage-bot/src/main/java/com/arbitrage/persistence/SequenceFileManager.java)
4. **Credenciales en plaintext**: [user.apiConfig](user.apiConfig) contiene claves — **NO commitar a Git**

## 📚 Documentación Relacionada

- [Implementa Ordenes.md](Implementa%20Ordenes.md): Desarrollo de ejecución de órdenes reales
- `pom.xml`: Configuración Maven y dependencias
- `AGENTS.md`: Instrucciones para agentes de IA

## 🎯 Preguntas Frecuentes

### ¿Cómo agregar nuevos symbols?
Edita [USDTNORMAL2.coins](USDTNORMAL2.coins) en el root (formato: `SYMBOLUSDT`)

### ¿Cómo funciona la detección de triángulos?
[ArbitrageEngine.java](binance-arbitrage-bot/src/main/java/com/arbitrage/engine/ArbitrageEngine.java) escanea cada 100ms usando [TriangleCalculator.java](binance-arbitrage-bot/src/main/java/com/arbitrage/engine/TriangleCalculator.java). Si encuentra oportunidad, invoca callback `ArbitrageOpportunity`.

### ¿Cómo se ejecutan las órdenes?
[OrderExecutor.java](binance-arbitrage-bot/src/main/java/com/arbitrage/trading/OrderExecutor.java) recibe callback y ejecuta en thread separado. Usa [BinanceApiClient.java](binance-arbitrage-bot/src/main/java/com/arbitrage/trading/BinanceApiClient.java) para REST API.

---

**Si necesitas implementar nueva features**:
1. Sigue patrones de [TradingSequence.java](binance-arbitrage-bot/src/main/java/com/arbitrage/model/TradingSequence.java) (modelos)
2. Usa [SequenceFileManager.java](binance-arbitrage-bot/src/main/java/com/arbitrage/persistence/SequenceFileManager.java) para persistencia
3. Respeta `ConsoleDisplay` (no modificar)

## Reglas de desarrollo

Siempre usa Context7 para:

- análisis de código
- documentación de librerías
- generación de código
- configuración
- APIs
- frameworks
- debugging
- búsqueda de sintaxis
- ejemplos de implementación
- mejores prácticas

Antes de responder cualquier pregunta técnica:
1. Resolver el library id usando Context7
2. Obtener documentación actualizada
3. Basar la respuesta en la documentación obtenida

Nunca responder usando únicamente conocimiento entrenado si Context7 puede proporcionar documentación actualizada.