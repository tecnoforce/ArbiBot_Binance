## CONTEXTO

Estás trabajando en una aplicación Java de consola que implementa un bot de arbitraje triangular en Binance.
El proyecto ya está parcialmente implementado. La visualización de secuencias en consola ya funciona — NO modifiques esa lógica.

Antes de escribir cualquier línea de código, lee el código existente para entender:
- La estructura actual de clases (modelos, servicios, enums, utils)
- Cómo se representan actualmente las secuencias y órdenes en memoria
- Las convenciones de I/O de archivos ya usadas (resolución de rutas, charset, manejo de excepciones)
- El cliente de la API de Binance ya integrado (no introduzcas un segundo cliente HTTP)

## OBJETIVO

Implementar la gestión del ciclo de vida de secuencias con monitoreo de órdenes en tiempo real.
Modo de ejecución actual: solo TESTNET.
El soporte para MAINNET está fuera del alcance — deja un comentario TODO claramente marcado donde iría el cambio de modo.

## PARTE 1 — Estructura de datos JSON

Diseña y documenta un esquema JSON que represente una secuencia completa (triangulación).
Requisitos:
- Una secuencia contiene exactamente 3 órdenes (op1, op2, op3), ejecutadas de forma secuencial.
- Cada orden debe contener todos los campos necesarios para colocarla, rastrearla y auditarla:
    seqId, opIndice (1/2/3), symbol, lado (BUY/SELL), tipo, quantity, price,
    binanceOrderId, clientOrderId, orderStatus, cantidadEjecutada, precioEjecutado,
    comisionAsset, comisionMonto,
    timestampCreacion, timestampEjecucion, tiempoTranscurridoMs
- La secuencia en sí debe contener:
    seqId (UUID), triangleId (ej. "ETHUSDT&DOTETH&DOTUSDT"), modo (TESTNET/MAINNET),
    estado (ABIERTA/CERRADA/ERROR), timestampInicio, timestampFin,
    profitEsperado, profitRealizado, monedaBase, montoBase
- Mapea este esquema a las clases Java del modelo existente.
  Extiende o crea clases según sea necesario — prefiere extender antes que reescribir.
  Sigue las convenciones de nombres y paquetes ya presentes en el proyecto.

## PARTE 2 — Archivos de persistencia

Archivo de secuencias abiertas (.seq)
- Ubicación: el mismo directorio que los archivos de datos existentes (detéctalo del código base).
- Formato: objeto JSON indexado por seqId → payload de la secuencia.
- Operaciones requeridas:
    INSERTAR  — agregar una nueva secuencia cuando se crea.
    ACTUALIZAR — actualizar los campos de una orden específica en su lugar (por seqId + opIndice).
    ELIMINAR  — remover una secuencia completada o con error (por seqId).
- Las lecturas y escrituras deben ser atómicas: escribe en un archivo temporal y luego renómbralo.
  Esto evita corrupción si el proceso muere a mitad de una escritura.

Archivo de secuencias cerradas (.events)
- Log de solo-agregar (append-only) de cada secuencia que alcanza el estado CERRADA o ERROR.
- Cada entrada es un objeto JSON completo en su propia línea (formato NDJSON / JSON-Lines).
- Nunca eliminar ni sobreescribir entradas existentes.
- Escribe el snapshot final de la secuencia (las 3 órdenes completamente pobladas) al cerrar.

## PARTE 3 — Flujo de ejecución de la secuencia

Implementa la siguiente máquina de estados secuencial.
Cada transición debe registrarse en consola (reutiliza la lógica de visualización existente):

  1. CREAR secuencia
     - Asignar un nuevo seqId (UUID).
     - Persistir en el archivo .seq (INSERTAR).
     - Mostrar la secuencia en consola (método existente — no lo toques).

  2. COLOCAR op1
     - Llamar a la API TESTNET de Binance para colocar la orden.
     - Almacenar el binanceOrderId y el orderStatus inicial devueltos por la API.
     - Persistir los campos actualizados de op1 en el archivo .seq (ACTUALIZAR).
     - Iniciar el bucle de polling para op1.

  3. MONITOREAR op1 → FILLED
     - Consultar Binance GET /api/v3/order a un intervalo configurable (por defecto: 500 ms).
     - Al recibir FILLED: registrar cantidadEjecutada, precioEjecutado, comisión, timestampEjecucion, tiempoTranscurridoMs.
     - Persistir op1 actualizado en el archivo .seq (ACTUALIZAR).
     - Continuar al paso 4.

  4. COLOCAR op2 (se ejecuta solo después de que op1 esté FILLED)
     - Mismo patrón que el paso 2.

  5. MONITOREAR op2 → FILLED
     - Mismo patrón que el paso 3.
     - Continuar al paso 6.

  6. COLOCAR op3 (se ejecuta solo después de que op2 esté FILLED)
     - Mismo patrón que el paso 2.

  7. MONITOREAR op3 → FILLED
     - Mismo patrón que el paso 3.
     - Calcular profitRealizado = (cantidadEjecutada op3 * precioEjecutado op3) - montoBase.
     - Establecer estado de secuencia = CERRADA, timestampFin = ahora.
     - Continuar al paso 8.

  8. CERRAR secuencia
     - Agregar el snapshot final al archivo .events.
     - Eliminar la secuencia del archivo .seq (ELIMINAR por seqId).

## PARTE 4 — Manejo de errores

Maneja explícitamente los siguientes escenarios de fallo:
- La API de Binance devuelve un error al colocar la orden → establecer estado = ERROR,
  persistir en .events, eliminar del .seq, registrar el error con contexto completo.
- Una orden permanece en NEW/PARTIALLY_FILLED más allá de un timeout configurable
  (por defecto: 60 s) → cancelar la orden vía API de Binance, luego aplicar el flujo de error.
- El archivo .seq está ausente o malformado al iniciar → registrar advertencia y comenzar limpio;
  NO crashear la aplicación.
- Cualquier IOException durante la escritura → reintentar una vez, luego aplicar el flujo de error.

## PARTE 5 — Configuración

Todos los valores configurables deben ir en el mecanismo de configuración existente (no agregues uno nuevo):
- URL base de TESTNET (ej. https://testnet.binance.vision)
- Intervalo de polling en ms (por defecto 500)
- Timeout de orden en ms (por defecto 60000)
- Rutas de archivos para .seq y .events

## RESTRICCIONES

- NO refactorices ni renombres código existente que ya funciona, salvo que sea estrictamente necesario para la integración.
- NO introduzcas nuevas dependencias sin aprobación explícita — revisa el pom.xml/build.gradle primero.
- Todos los métodos públicos nuevos deben tener un comentario Javadoc breve.
- Sigue el estilo de código existente (indentación, estilo de llaves, orden de imports).
- Usa la librería estándar de Java para I/O de archivos (java.nio.file). No agregues Apache Commons IO.
- El bucle de polling debe correr en un hilo de fondo; no debe bloquear el hilo principal.
- Seguridad de hilos: el acceso al archivo .seq debe protegerse con un lock (ReentrantLock o bloque synchronized).

## ENTREGABLES

Por cada archivo que crees o modifiques, indica:
1. Ruta completa relativa a la raíz del proyecto.
2. Qué cambió y por qué.
3. Cualquier suposición que hayas tenido que hacer sobre el código existente.

No generes implementaciones de relleno (stubs).
Cada método debe ser completamente funcional y compilable.

