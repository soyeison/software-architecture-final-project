# Spec — Diseño interno del servicio de Inventario (`inventory-service`)

- **Fecha:** 2026-10-11
- **Fase:** 4 (servicio de Inventario)
- **Estado:** pendiente de revisión
- **Depende de:** [Spec global](2026-10-09-solucion-global-design.md), [Spec de contratos](2026-10-10-contratos-design.md) y [Spec de infraestructura](2026-10-10-infraestructura-design.md)

## 1. Objetivo y alcance

Definir la estructura interna y las decisiones de diseño del servicio de Inventario: único
responsable del stock, expone la API REST de los contratos y publica el evento
`InventoryStockChanged` mediante *Transactional Outbox*.

Esta spec **no cambia ningún contrato**: los OpenAPI, el evento, la topología de RabbitMQ, las
variables de entorno y los datos semilla ya están fijados. De ella salen seis planes cortos, uno por
sub-tarea (sección 9), cada uno ejecutado con SDD.

### Fuera de alcance (YAGNI)

Observabilidad avanzada (Micrometer, Prometheus, trazado distribuido con OpenTelemetry, paneles),
límite de peticiones, paginación, servidor de autenticación, varios relays del outbox en paralelo
(`SKIP LOCKED`) y purga del outbox.

La observabilidad básica **sí está incluida**: logs con `sku` y `eventId` (sección 4),
`/actuator/health` y el diagnóstico del outbox (`GET /outbox`). La métrica del documento final, la
latencia de propagación, no depende de métricas del servicio: Inventario pone `occurredAt` en el
evento, el Catálogo calcula `updatedAt − occurredAt` (campo `lastEventLatencyMs` y logs) y
`e2e/measure-latency.sh` (fase 6) la resume.

## 2. Principios de diseño

Arquitectura **por capas organizada por funcionalidad**, sin arquitectura hexagonal ni limpia: el
servicio es pequeño y esa ceremonia pesaría más que el beneficio. La simplicidad no sacrifica
desacoplamiento ni responsabilidad única:

1. **Una responsabilidad por clase.** Cada clase tiene un solo motivo de cambio y se puede describir
   sin usar "y".
2. **Dependencias hacia abajo:** `web → caso de uso → dominio y persistencia`. Nunca al revés; el
   dominio no conoce HTTP ni RabbitMQ.
3. **Inversión de dependencias solo donde compra algo:** una interfaz para el envío de eventos
   (`StockEventSender`) y el reloj (`Clock`). En el resto no se crea una interfaz por clase.
4. **Inyección por constructor** y DTOs inmutables (`record`).
5. **KISS y YAGNI:** nada de patrones ni capas que ningún escenario necesite.
6. **Nombres completos, sin abreviaturas** (convención del proyecto), salvo siglas del dominio o de
   estándares y unidades estándar.

### Decisión: las reglas de negocio viven en la entidad

`Stock` es una entidad JPA que contiene la regla `sell` como método simple. La anotación `@Entity` es
metadato declarativo sin lógica; la clase cambia por una sola razón (cómo se descuenta el stock) y
se prueba con `new`, sin Spring ni base de datos. No se separa un modelo de dominio puro de la
entidad: eso duplicaría las clases y exigiría un mapeo para un servicio con dos reglas.

## 3. Estructura de paquetes

Proyecto Maven independiente en `inventory-service/` (sin módulo compartido, como dicta la spec
global §3.8). `groupId` `com.retail`, paquete base `com.retail.inventory`.

```
com.retail.inventory
├── InventoryApplication
├── config/     InventoryProperties, Clock
├── error/      GlobalExceptionHandler, ProblemType, ProblemResponseWriter
├── security/   configuración JWT y manejadores de 401/403
├── stock/      Stock, StockChange, Channel, StockRepository,
│               GetStockService, StockController, StockResponse,
│               SkuNotFoundException, InsufficientStockException
├── sale/       SaleController, SaleRequest, SaleResponse, RegisterSaleService,
│               OptimisticRetry, StockConflictException
└── outbox/     OutboxEvent, OutboxStatus, OutboxRepository, OutboxRecorder,
                StockEventFactory, StockChangedEvent, OutboxRelay,
                StockEventSender, RabbitStockEventSender, EventPublishException,
                OutboxController, OutboxEventResponse
```

**Dependencias permitidas** (un solo sentido, sin ciclos): `sale → stock`, `sale → outbox`,
`outbox → stock`. `error` y `security` solo se usan desde el borde web.

- `StockChange` y `Channel` viven en `stock/`. `StockChange` es un `record` (`eventId`, `sku`,
  `newStock`, `stockVersion`, `channel`, `occurredAt`) y es el vocabulario común: `sale` lo produce
  y `outbox` lo consume. Si `Channel` viviera en `sale/`, habría un ciclo.
- `RegisterSaleService` no conoce el interior del outbox: solo llama a `OutboxRecorder.record`.

### Prueba de arquitectura (ArchUnit)

Una clase de prueba en la sub-tarea 4.1 hace cumplir las reglas y falla si alguien las rompe:

1. No hay ciclos entre los paquetes de funcionalidad.
2. Solo `RabbitStockEventSender` usa `org.springframework.amqp`.
3. Los controladores no usan repositorios directamente: pasan por un caso de uso.
4. `stock/` y `sale/` no dependen de `RabbitStockEventSender` ni de `org.springframework.web`, salvo
   sus propios controladores.

## 4. Configuración, errores, logging y reloj (sub-tarea 4.1)

### Configuración

- `InventoryProperties` (`@ConfigurationProperties`, validada) agrupa lo propio del servicio: secreto
  y emisor del JWT y los parámetros del outbox. Lo demás (`SPRING_DATASOURCE_*`, `SPRING_RABBITMQ_*`,
  `SERVER_PORT`) lo enlaza Spring directamente.
- Los valores por defecto de `application.yml` apuntan a `localhost` (puertos 5441 y 5672), de modo
  que arrancar desde el IDE funciona sin configurar nada; Compose los sobrescribe con variables de
  entorno.
- **`JWT_SECRET` no tiene valor por defecto en el código.** Si falta o mide menos de 32 bytes, el
  servicio no arranca y explica por qué. El valor de demo vive solo en el Compose y `.env.example`.

### Manejo de errores

- Un único `GlobalExceptionHandler` produce `application/problem+json` (RFC 9457). Los tipos de
  problema (`insufficient-stock`, `not-found`, etc.) se definen en un solo lugar, el enum
  `ProblemType` (código, título, estado). Agregar un error es sumar un valor al enum y un método al
  handler.
- Las excepciones de negocio viven en su funcionalidad y no conocen HTTP; el handler es el único que
  las traduce.
- Un error inesperado responde `internal-error` sin detalles internos y se registra completo. Un
  JSON mal formado responde `validation-error` con `errors: []`.
- Las excepciones estándar de Spring MVC (405, 415, ...) conservan su estado y reciben el tipo genérico `http-error`; los parámetros faltantes, de tipo incorrecto o que violan una restricción responden `validation-error` con `errors`.
- Las violaciones de clase (no de un campo) de un cuerpo inválido se reportan en `errors` con el nombre del objeto como `field`, y una restricción sin texto usa el mensaje `is invalid`.

### Logging

SLF4J con Logback. Mensajes de una línea con formato `evento clave=valor`
(`sale_registered sku=… channel=… saleId=… remaining=…`). Dos claves en el MDC, `sku` y `eventId`,
para seguir una venta de punta a punta. `INFO` para hechos de negocio, `WARN` para reintentos y
fallos de publicación, `ERROR` para lo inesperado. Nunca se registran tokens ni secretos.

### Reloj

Un bean `Clock` (UTC), inyectado por constructor en quien necesite la hora. Los instantes se
truncan a milisegundos para cumplir el contrato.

### A confirmar en el plan de 4.1 (Spring Boot 4 es reciente)

- Cómo servir Swagger UI con el YAML estático del contrato (springdoc u otra opción compatible).
- Los nombres exactos de las propiedades de Jackson 3 y de los starters.
- La librería para validar respuestas contra el contrato: **`networknt json-schema-validator`**
  (soporta JSON Schema 2020-12, el dialecto de OpenAPI 3.1).

## 5. Seguridad JWT (sub-tarea 4.2)

### Validación

- Resource Server de Spring Security, sin sesión y con CSRF desactivado (API con bearer token, sin
  cookies).
- Decodificador **solo HS256** con el secreto de `JWT_SECRET`; exige el emisor `retail-demo-auth` y
  valida la expiración. Spring convierte el claim `scope` en autoridades `SCOPE_<permiso>`.
- Reglas seguras por defecto (lo que no se declara se deniega):

| Ruta | Regla |
|---|---|
| `POST /api/v1/inventory/{sku}/sales` | `SCOPE_inventory:sell` |
| `GET /api/v1/inventory/outbox` | pública (diagnóstico de la demo) |
| `GET /api/v1/inventory/{sku}` | `SCOPE_inventory:read` |
| `/actuator/health`, Swagger UI, su configuración (`/v3/api-docs/swagger-config`) y el YAML del contrato | públicas |
| `/error` (ruta interna de Spring para errores del contenedor) | pública |
| cualquier otra, incluido `/v3/api-docs` | denegada |

La regla de `outbox` va **antes** que la de `{sku}`; si no, exigiría el scope de lectura. Se fija con
una prueba.

- **`/v3/api-docs` se deniega a propósito.** Es el OpenAPI que springdoc genera desde el código; no se
  puede apagar sin apagar Swagger UI, y publicaría un segundo contrato distinto del escrito a mano.
  Swagger UI sigue funcionando con su configuración y el YAML del contrato.
- **Una ruta inexistente responde 401 (sin token) o 403 (con token), no 404.** Es la consecuencia de
  denegar por defecto y es intencional: no se revela qué rutas existen. Un SKU inexistente en una ruta
  válida sigue respondiendo 404.

### Respuestas 401 y 403

Un único `ProblemResponseWriter` escribe el `application/problem+json` y lo usan el
`AuthenticationEntryPoint` (401) y el `AccessDeniedHandler` (403), con los mismos `ProblemType` del
resto de errores. El 401 envía además `WWW-Authenticate: Bearer`; esa cabecera se agrega a la
respuesta 401 del OpenAPI en esta sub-tarea (cambio compatible del contrato).

### Generación de tokens: `scripts/generate-token.sh <perfil>`

- Bash y openssl, sin dependencias. Perfiles `pos`, `online`, `operator` y `no-scope`, con las claims
  de la spec de contratos (`iss`, `sub`, `scope`, `exp` a 24 h). Imprime solo el token.
- Lee `JWT_SECRET` del entorno y, si no está, de `.env` y luego de `.env.example`, de modo que
  coincide con el Compose sin configurar nada.

### Pruebas

- Una prueba ejecuta el script real y usa el token que produce contra la configuración de seguridad:
  verifica que el script y el validador están de acuerdo.
- Matriz: sin token y token inválido → 401; sin el scope → 403; con el scope → pasa; token expirado
  y emisor incorrecto → 401; `outbox` público.
- En 4.2 aún no hay endpoints reales: se usa un controlador de prueba solo en `src/test` con las
  mismas rutas. La 4.3 retiró la ruta `GET /{sku}`, la 4.4 retiró la de `POST …/sales` y la 4.6 la de `GET /outbox`; las pruebas pasan a los endpoints reales.
- Las pruebas del manejador de errores (de 4.1) desactivan los filtros de seguridad
  (`addFilters = false`): prueban solo el manejo de errores, y la seguridad se prueba aparte con su
  propia matriz.

## 6. Stock y datos (sub-tarea 4.3)

### Esquema (migraciones Flyway, comentarios en español)

```sql
CREATE TABLE stock (
  sku        VARCHAR(64)  PRIMARY KEY,
  quantity   INTEGER      NOT NULL CHECK (quantity >= 0),
  version    BIGINT       NOT NULL,
  updated_at TIMESTAMPTZ  NOT NULL
);
```

- `CHECK (quantity >= 0)` es una segunda defensa frente a errores del código.
- `V2__seed_stock.sql` inserta los 7 SKU de `docs/seed-data.md` con `version = 1`.
- Hibernate usa `ddl-auto=validate`: **Flyway es el único dueño del esquema** y una entidad desalineada
  impide el arranque. `open-in-view=false`.

### Entidad `Stock`

Campos `sku` (id), `quantity`, `version` (`@Version`) y `updatedAt` (`Instant`). **Sin setters:** el
estado cambia solo con `sell(quantity, now)`, que lanza `InsufficientStockException` si no alcanza.
El constructor sin argumentos exigido por JPA no es público. `StockRepository` es una interfaz de
Spring Data sin consultas propias.

### Consulta

`GetStockService` busca o lanza `SkuNotFoundException`; `StockController` devuelve un `StockResponse`
(`record`) creado con `StockResponse.from(stock)`, sin librería de mapeo. Un SKU que no cumple el
patrón responde 404 porque no existe en la tabla, sin validación extra.

### Pruebas

- *Unitarias:* `Stock.sell` (descuenta, rechaza, cantidades límite) sin Spring.
- *Integración (PostgreSQL real):*
  - mapeo de `@Version`: dos instancias del mismo registro y la segunda en guardar falla con
    `OptimisticLockingFailureException` (el mecanismo del escenario 3);
  - `GET /{sku}`: 200 con forma validada contra el contrato, 404 y 401/403;
  - **la semilla coincide con `docs/seed-data.md`**: una prueba lee la tabla del documento y la compara
    con lo sembrado, para evitar el desalineamiento entre Inventario y Catálogo.
- Los tests que modifican stock usan **sus propios SKU** (`TEST-<UUID en mayúsculas>`, para cumplir el patrón del contrato) y no tocan la semilla.

## 7. Venta y concurrencia (sub-tarea 4.4)

### Flujo de `POST /sales`

```
SaleController          valida el cuerpo (cantidad ≥ 1, canal válido) y llama al servicio
   └─► RegisterSaleService.register(sku, quantity, channel)
          └─► OptimisticRetry.run( () ->
                 transactionTemplate.execute( () -> {
                    stock = repository.findById(sku)  → SkuNotFoundException
                    stock.sell(quantity, now)         → InsufficientStockException
                    repository.saveAndFlush(stock)    → conflicto de versión aquí
                    return StockChange(...)           // saleId = eventId
                 }))
```

### Decisiones

- **`RegisterSaleService` no es `@Transactional`.** Cada intento corre en su propia transacción con
  `TransactionTemplate`. El reintento debe envolver la transacción, y una anotación en el mismo bean
  se ignora si un método se llama a sí mismo.
- **`OptimisticRetry` es una clase aparte y genérica.** Reintenta solo ante
  `OptimisticLockingFailureException`, hasta `inventory.sale.max-attempts` (por defecto 5), y lanza
  `StockConflictException` (409 `concurrent-modification`) al agotarlos. Se prueba sola.
- Solo se reintenta el conflicto de versión. Si en el reintento el stock ya no alcanza, el resultado es
  `InsufficientStockException` (409 `insufficient-stock`) y no se repite: es el escenario 3.
- `saveAndFlush` fuerza la comprobación de versión dentro del intento; **la versión devuelta es la
  posterior al guardado**, la que va en la respuesta y en el evento.
- El servicio devuelve un `StockChange`; el controlador arma el `SaleResponse` con la petición y ese
  resultado, sin clase intermedia.
- **Entrada estricta, como dice el contrato:** Jackson rechaza propiedades desconocidas y no convierte
  `1.5` ni `"1"` en entero ni acepta números como valor de un enum (`"channel": 0`); se configura con cuatro opciones de Jackson en application.yml (fail-on-unknown-properties, accept-float-as-int, allow-coercion-of-scalars y datatype.enum.fail-on-numbers-for-enums, que en Jackson 3 es una EnumFeature), las cuatro necesarias.
- **4.4 no escribe el evento:** la sub-tarea 4.5 agrega la llamada a `OutboxRecorder` dentro de la misma
  transacción.

### Pruebas

- *Unitarias:* `OptimisticRetry` (éxito al primer intento, éxito tras k fallos, agotamiento, no
  reintenta otras excepciones).
- *Integración (PostgreSQL real):* venta correcta (stock −q, versión +1, respuesta válida contra el
  contrato); `insufficient-stock` con `availableStock` y `requestedQuantity` y stock intacto; SKU
  inexistente; validaciones (cantidad 0, canal inválido, campo extra, decimal); 401/403.
- **Carrera de la última unidad:** stock 1 y dos hilos vendiendo a la vez, repetido 20 veces; siempre
  exactamente un 201 y un 409 `insufficient-stock`.
- **Invariante bajo ráfaga:** 20 hilos sobre un stock de 10; el stock final es el inicial menos las
  ventas exitosas, nunca queda negativo y no hay errores 5xx. Se verifica el invariante y no un número
  exacto de éxitos, porque algún reintento puede agotarse bajo mucha contención.

## 8. Outbox y publicación (sub-tareas 4.5 y 4.6)

### 4.5: escribir el evento en la misma transacción

```sql
CREATE TABLE outbox (
  sequence_number BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  event_id        UUID         NOT NULL UNIQUE,
  sku             VARCHAR(64)  NOT NULL,
  event_type      VARCHAR(64)  NOT NULL,
  payload         TEXT         NOT NULL,      -- el JSON ya serializado
  status          VARCHAR(16)  NOT NULL,      -- PENDING | PUBLISHED
  attempts        INTEGER      NOT NULL DEFAULT 0,
  last_error      TEXT,
  occurred_at     TIMESTAMPTZ  NOT NULL,
  published_at    TIMESTAMPTZ
);
CREATE INDEX outbox_pending_index ON outbox (sequence_number) WHERE status = 'PENDING';
```

- `sequence_number` fija el orden de publicación. Para un mismo SKU el orden es el de confirmación: la
  segunda venta espera la fila de stock de la primera, así que su inserción ocurre después. Entre SKU
  distintos el orden no importa, porque el Catálogo guarda la versión por producto.
- `StockEventFactory` genera el JSON a partir del `StockChange` y **formatea `occurredAt` con exactamente
  3 decimales** en UTC, aunque los milisegundos sean cero.
- `OutboxRecorder.record(change)` es `@Transactional(propagation = MANDATORY)`: si se llama sin una
  transacción abierta, falla de inmediato. La atomicidad queda impuesta por el diseño.

### 4.6: publicar

```
OutboxRelay (@Scheduled fixedDelay 500 ms)
  lee hasta 50 eventos PENDING por sequence_number
  por cada uno:  sender.send(...)  → bloquea hasta la confirmación del broker
       ok    → marca PUBLISHED (transacción corta)
       falla → attempts+1, last_error, sigue PENDING, y DETIENE el lote
```

- `fixedDelay` evita ejecuciones solapadas. Detener el lote ante el primer fallo conserva el orden y no
  insiste con el resto si RabbitMQ está caído.
- **Nunca se mantiene una transacción abierta durante el envío por red;** las actualizaciones de estado
  son transacciones cortas e independientes.
- **`StockEventSender` es la única interfaz del servicio** (además de `Clock`). `RabbitStockEventSender`
  envía los bytes del JSON tal cual, con `message_id = eventId`, `type`, `content_type` y entrega
  persistente, al exchange `inventory.events` con la clave `inventory.stock.changed`. Usa *publisher
  confirms* y `mandatory`: **solo cuenta como enviado si el broker confirma y el mensaje no fue
  devuelto** por no tener cola.
- Tiempo de espera de la confirmación de 5 s y de conexión de 2 s, para que una caída de RabbitMQ no
  bloquee el ciclo.
- La ventana de *at-least-once* (RabbitMQ confirma pero el servicio cae antes de marcar) produce un
  duplicado, que absorbe la idempotencia del Catálogo.
- **`GET /outbox`** devuelve como máximo 50 eventos, los más recientes primero, con filtro opcional
  `status`. Un valor desconocido responde `validation-error`.
- Propiedades: `inventory.outbox.poll-interval` (500 ms), `batch-size` (50), `confirm-timeout` (5 s) y
  `relay-enabled` (por defecto `true`; las pruebas lo apagan para controlar cuándo corre el relay).

### Pruebas

- *4.5, unitarias:* el JSON del `StockEventFactory` valida contra `inventory-stock-changed.schema.json`
  con `networknt`, incluido el caso `.000Z`.
- *4.5, integración (PostgreSQL):* una venta deja exactamente una fila PENDING con `eventId = saleId` y
  payload válido; si el outbox falla, la venta se revierte entera (stock y versión intactos y sin
  fila); una venta rechazada no deja evento; `record` sin transacción falla.
- *4.6, unitarias con un sender falso:* publica en orden y marca PUBLISHED; ante un fallo suma el
  intento, guarda el error, mantiene PENDING y no intenta los siguientes; tras recuperarse publica los
  pendientes en orden.
- *4.6, integración con RabbitMQ real* (Testcontainers cargando `definitions.json`): el mensaje llega a
  `catalog.inventory-stock-changed` con sus propiedades y su cuerpo exactos; una clave sin cola produce
  una devolución y el evento no queda como publicado; al detener RabbitMQ el evento queda PENDING con
  `attempts` creciendo y al volver se publica (escenario 8).
- *`GET /outbox`:* orden, límite, filtro, 400 por estado inválido y acceso público.

## 9. Pruebas, empaquetado y entrega por sub-tarea

### Pruebas: dos velocidades

- `./mvnw test`: unitarias y prueba de arquitectura (segundos), el ciclo rápido de TDD.
- `./mvnw verify` (requiere Docker): además las de integración (`*IntegrationTest`, con `maven-failsafe-plugin`) contra
  PostgreSQL y RabbitMQ reales con Testcontainers, levantados una sola vez por ejecución.
- Sin meta numérica de cobertura: cada comportamiento de las secciones 4 a 8 tiene al menos una prueba
  que lo demuestra.

### Una sola fuente de verdad para los archivos de contrato

El servicio necesita archivos que viven fuera de su carpeta: el OpenAPI, el JSON Schema del evento,
`definitions.json` y `docs/seed-data.md`. Maven los **copia desde `docs/` e `infra/` al `target/`**
(`maven-resources-plugin`); nunca hay una copia a mano en el repositorio. Las pruebas los leen como
recursos del classpath y funcionan desde cualquier directorio.

### Empaquetado y Compose (sub-tarea 4.1)

- `inventory-service/Dockerfile` en dos etapas (compila con JDK 25, ejecuta con un JRE). Como necesita
  los archivos de contrato de fuera de su carpeta, el contexto de construcción es la **raíz del
  repositorio**, con un `.dockerignore` que excluye `.git`, `.claude` y los `target/`.
- Entrada en el Compose: redes `messaging` e `inventory-net`, `depends_on` con `service_healthy` para
  su base de datos y RabbitMQ, variables de la spec de infraestructura y puerto `127.0.0.1:8081`;
  healthcheck con `/actuator/health`.
- **Impacto en `scripts/verify-infra.sh`:** hoy hace `docker compose up -d --wait` de todo. Con el
  servicio dentro, construiría una imagen y chocaría con el puerto 8081 de un stack en marcha. Se ajusta
  para levantar solo `rabbitmq`, `inventory-db` y `catalog-db`, y se agrega `INVENTORY_SERVICE_PORT`
  como variable configurable (y en `.env.example`).

### Sub-tareas

| | Entrega | Cómo se comprueba | Escenarios |
|---|---|---|---|
| **4.1** | Servicio que arranca en Compose, Swagger UI con el contrato, errores base, logging, reloj, configuración validada, ayudante de validación de contrato y prueba de arquitectura | `docker compose up`, `./mvnw verify` | — |
| **4.2** | Seguridad JWT y `scripts/generate-token.sh` | Matriz 401/403 y el script contra el validador | 10 |
| **4.3** | Stock, semilla y `GET /{sku}` | Respuesta contra el contrato; semilla igual al documento | consulta |
| **4.4** | Venta con concurrencia | Carrera de la última unidad e invariante bajo ráfaga | 1–4 (lado Inventario) |
| **4.5** | Outbox transaccional | Atomicidad y validez contra el schema | — |
| **4.6** | Relay, `GET /outbox` y publicación | RabbitMQ real, incluida su caída | 8 |

**Dependencias por sub-tarea:** cada una agrega solo las que necesita (4.1: web, validación, actuator,
springdoc, ArchUnit, el validador de JSON Schema y pruebas; 4.2: seguridad y Resource Server; 4.3: Data
JPA, Flyway, PostgreSQL y Testcontainers; 4.6: AMQP y el módulo de RabbitMQ de Testcontainers). El
validador de JSON Schema llega en 4.1 porque el ayudante de contrato lo usa desde entonces. Los nombres
exactos de los starters de Spring Boot 4 se confirman en cada plan.

### Pendientes heredados de la revisión de 4.1

Hallazgos de la revisión final de 4.1 que no correspondían a esa sub-tarea. Cada uno tiene una
sub-tarea responsable y su plan debe incluirlo.

| Pendiente | Sub-tarea |
|---|---|
| El healthcheck pasa a DOWN si RabbitMQ cae, lo que marcaría el servicio como no sano en el escenario 8 (desactivar el indicador de RabbitMQ o usar un grupo de salud sin él) | 4.6 |
| El contenedor se ejecuta como root (agregar un usuario sin privilegios) | 4.6 |
| Las pruebas de integración comparten una sola base y cada venta exitosa deja una fila `PENDING` en `outbox`: las pruebas del relay y de `GET /outbox` deben vaciar la tabla (`TRUNCATE outbox`) en `@BeforeEach`, o el relay publicaría eventos de otras clases y los resultados del listado no serían deterministas | 4.6 |
| La consulta del relay debe usar el literal `PENDING` (JPQL con el enum o SQL nativo): con un parámetro enlazado, PostgreSQL no puede usar el índice parcial `outbox_pending_index` | 4.6 |
| `OutboxEvent` no tiene mutadores: agregar `markPublished(Instant)` y `recordFailure(String)` (sin setters), con `last_error` acotado (el mensaje, no la traza); documentar que el relay es de una sola instancia (`fixedDelay`), porque la entidad no tiene `@Version` | 4.6 |
| `eventId` en el MDC dentro del relay; `outbox_recorded` se registra antes del commit (reformularlo o bajarlo a `DEBUG`) | 4.6 |
| Agregar `catalog-service` al `.dockerignore` de la raíz | Fase 5 |

### Cierre de la fase 4

Un script de humo, `scripts/smoke-inventory-service.sh`, con el servicio dentro de Compose: una venta
devuelve 201, el outbox pasa a `PUBLISHED` y el mensaje aparece en la cola del Catálogo. Es solo lo
mínimo para dar la fase por terminada; los escenarios completos son de la fase 6.

## 10. Decisiones descartadas

| Alternativa | Motivo |
|---|---|
| Arquitectura hexagonal o limpia | Exigen un puerto, un adaptador y un mapeo por dependencia; para dos casos de uso la ceremonia pesa más que el beneficio |
| Modelo de dominio puro, entidad JPA aparte y mapeador | Duplica las clases y exige mapeo para un servicio con dos reglas; la entidad con comportamiento se prueba igual sin Spring |
| Una interfaz por clase | Indirección sin beneficio; solo hay interfaz donde hay una razón real (`StockEventSender`, `Clock`) |
| `@Transactional` en `RegisterSaleService` con reintento interno | La anotación se ignora en llamadas a un método del mismo bean; el reintento debe envolver la transacción |
| Librería de mapeo entre entidad y DTO | Con un DTO por endpoint, el mapeo es una línea |
| Ordenar el outbox por `occurred_at` o `event_id` | `occurred_at` empata al milisegundo y `event_id` es aleatorio |
| Copias de los archivos de contrato dentro del servicio | Riesgo de desalineamiento; se copian en la compilación desde su fuente |
| Seguridad por anotaciones (`@PreAuthorize`) y roles | Las reglas por ruta son suficientes y más fáciles de leer |
