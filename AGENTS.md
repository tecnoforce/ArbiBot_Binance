# Binance Arbitrage Bot - AGENTS.md

Este archivo define las instrucciones para agentes de IA que trabajan en el proyecto de Binance Arbitrage Bot.

## 📋 Comandos de Construcción y Ejecución

```powershell
# Eliminar JAR antigua (OBLIGATORIO antes de recompilar)
Remove-Item D:\ARBITRAJE_BOT\BOT_Nuevo_V5-J21\binance-arbitrage-bot\target\*.jar -ErrorAction SilentlyContinue

# Compilar
mvn package -DskipTests -f D:\ARBITRAJE_BOT\BOT_Nuevo_V5-J21\binance-arbitrage-bot\pom.xml

# Ejecutar
java -jar D:\ARBITRAJE_BOT\BOT_Nuevo_V5-J21\binance-arbitrage-bot\target\binance-arbitrage-bot-1.5.1.jar
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

### Módulos Principales (`module/`)

| Módulo | Responsabilidad |
|--------|-----------------|
| **ArbitrageDetector** | Escanea triángulos cada 100ms, emite oportunidades via `ScheduledExecutorService` |
| **ExecutionEngine** | Orquesta 3 pasos (BUY → CONVERT → SELL) con validaciones de RiskManager |
| **FillTracker** | Seguimiento de órdenes ejecutadas y estados de fill |
| **MarketDataEngine** | Actualización de precios desde WebSocket (ConcurrentHashMap thread-safe) |
| **ProfitCalculator** | Cálculo de ganancias netas con comisiones |
| **RepricingEngine** | Reajuste dinámico de precios si hay cambios de mercado |
| **RiskManager** | Validaciones de riesgo, máximo de trades abiertos, limites de balance |

### Flujo de Ejecución

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
```

## 🚀 Puntos de Entrada

### Punto de Entrada Principal
- **Main.java**: Inicia config, WebSocket, carga símbolos, arranca motors de arbitraje y ejecución

### Utilidades Adicionales (ejecutables independientes)
- **GetBalances.java**: Obtiene balances actuales de la cuenta
- **GetOrderHistory.java**: Historial de órdenes ejecutadas
- **GetTRXPrice.java**: Consulta precio actual de TRX
- **SellAllSmallBalances.java**: Vende saldos pequeños (útil para limpiar polvo)
- **SellTRX.java**: Vende posición de TRX específica

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

### Convenciones de Código
- **Modelos**: `@Data @Builder @NoArgsConstructor @AllArgsConstructor` (Lombok)
- **Factory methods**: `TradingSequence.create()`, `SequenceOrder.create()`
- **Métodos computados**: `@JsonIgnore` (ej. `getOrdenes()`, `isTodasFilled()`)
- **Concurrencia**: `ConcurrentHashMap` para `priceMap` (thread-safe entre WebSocket y árbitro)
- **Threading**: `ScheduledExecutorService` con `cores` threads (de `.config`) en lugar de threads ad-hoc

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

- **Java**: 21 (requerido, LTS)
- **Maven Plugins**: `maven-shade-plugin` (Fat JAR), `maven-jar-plugin`
- **Librerías**: OkHttp 4.12.0, Jackson 2.17.1, Lombok 1.18.34
- **Log.java custom**: Logging con tags (3-4 caracteres). Nota: SLF4J/Logback en pom.xml son residuales, se usa [Log.java](binance-arbitrage-bot/src/main/java/com/arbitrage/util/Log.java)

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

## Memory Rules

Siempre busca en memoria antes de:
- coding
- architecture decisions
- refactoring
- debugging
- API integration

Siempre salva:
- important decisions
- architecture
- conventions
- fixes
- project preferences
- TODOs
- infrastructure notes

# Memorias del Proyecto

# Arnés de Agentes

- Usa esto como la capa base de memoria del proyecto.
- Antes de codificar, recupera el contexto actual del almacén de memorias local via MCP (`get_context`) o CLI (`memories recall`).
- Las memorias son locales-primero: los registros de proyecto + global provienen de tu base de datos local; sincronización en la nube refleja ese estado.
- Cuando hay conflictos de reglas, prefiere reglas de ámbito de ruta, luego reglas de proyecto, luego reglas globales.

## Lista de Verificación en Runtime

- Inicia tareas con recuperación de contexto (`memories recall --json`).
- Persiste decisiones importantes con `memories add` o MCP `add_memory`.
- Edita memorias de origen en lugar de editar manualmente archivos de integración generados.

## Memorias Almacenadas

- Sin memorias almacenadas aún. Agrega una con `memories add --rule "..."`.
<!-- Generado por memories.sh en 2026-05-07T23:50:56.019Z -->
<!-- Generado por memories.sh en 2026-05-07T23:50:56.024Z -->