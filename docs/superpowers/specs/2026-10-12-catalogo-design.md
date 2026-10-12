# Servicio de Catálogo — Especificación de diseño

**Fecha:** 2026-10-12
**Fase:** 5 (Catálogo), una spec para el servicio y un plan por sub-tarea (5.1 a 5.4)
**Contexto:** [spec global](2026-10-09-solucion-global-design.md) §3.2, §4 y §4.3 ·
[contratos](2026-10-10-contratos-design.md) · [infraestructura](2026-10-10-infraestructura-design.md) ·
[spec de Inventario](2026-10-11-inventario-design.md) (mismas convenciones y arquitectura)

## 1. Objetivo y alcance

`catalog-service` es el **consumidor** del flujo. Es dueño de los datos de producto (nombre,
descripción, precio) y de una **vista de disponibilidad de solo lectura** que se actualiza
exclusivamente con los eventos `InventoryStockChanged` de RabbitMQ. Nunca llama a Inventario ni lee su
base de datos. Expone una consulta REST pública.

Cumple las reglas de arquitectura del proyecto: coordinación por coreografía, consumidor idempotente
(deduplicación por `eventId`), cola durable, ack manual y DLQ.

### Contratos que implementa (sin cambios)

- API REST: [`docs/contracts/openapi/catalog-service.yaml`](../../contracts/openapi/catalog-service.yaml)
  (`listProducts`, `getProduct`, schema `Product`).
- Evento consumido: [`docs/contracts/events/inventory-stock-changed.schema.json`](../../contracts/events/inventory-stock-changed.schema.json)
  y sus ejemplos. **Consumidor tolerante:** ignora campos desconocidos.
- Topología: `infra/rabbitmq/definitions.json` (cola `catalog.inventory-stock-changed`, exchange de
  letra muerta `catalog.dlx`, cola `catalog.inventory-stock-changed.dlq`). El servicio **no declara**
  topología: la infraestructura es la dueña.
- Semilla: [`docs/seed-data.md`](../../seed-data.md).

### Fuera de alcance (YAGNI)

Autenticación en el Catálogo (las consultas son públicas), paginación y filtros, alta o modificación
de productos por REST, purga de `processed_events`, reprocesamiento de la DLQ (se menciona en el
documento como práctica operativa), métricas y trazas, y la prueba o demo de "base de datos del
Catálogo caída" (el comportamiento se implementa, no se muestra).

## 2. Principios de diseño

Los mismos que Inventario (spec de Inventario §2): arquitectura **por capas organizada por
funcionalidad**, sin hexagonal ni limpia; una responsabilidad por clase; dependencias hacia abajo;
inyección por constructor y DTO inmutables (`record`); KISS y YAGNI; nombres completos sin
abreviaturas; código en inglés y documentación y comentarios en español. La entidad `Product`
contiene la regla de aplicar un cambio de stock (`applyStock`) y se prueba con `new`, sin Spring.

## 3. Estructura de paquetes

Proyecto Maven independiente en `catalog-service/` (sin módulo compartido), `groupId` `com.retail`,
paquete base `com.retail.catalog`. Cada servicio tiene su propia clase del evento.

```
com.retail.catalog
├── CatalogApplication
├── config/       CatalogProperties, Clock
├── error/        GlobalExceptionHandler, ProblemType
├── product/      Product, ProductRepository, ProductQueryService, ProductController,
│                 ProductResponse, ProductNotFoundException
└── stockevent/   StockChangedEvent, StockEventReader, StockEventListener, ApplyStockChangeService,
                  ApplyResult,
                  ProcessedEvent, ProcessedEventRepository,
                  InvalidEventException, UnknownProductException
```

**Dependencias permitidas:** `stockevent → product`. `product` nunca depende de `stockevent`.
`error` puede usar las excepciones de las funcionalidades.

### Prueba de arquitectura (ArchUnit)

1. No hay ciclos entre los paquetes de funcionalidad.
2. Solo `StockEventListener` usa `org.springframework.amqp`.
3. Los controladores no usan repositorios directamente: pasan por un caso de uso.
4. `product/` no depende de `stockevent/`.

Las reglas 3 y 4 permiten evaluación vacía en 5.1 (no hay clases que evaluar) y la 5.2 retira ese permiso.

## 4. Configuración, errores, logging y reloj

### Configuración

- `CatalogProperties` (`@ConfigurationProperties`, validada) agrupa lo propio del servicio:
  `catalog.consumer.queue` (por defecto `catalog.inventory-stock-changed`),
  `catalog.consumer.initial-backoff` (1 s) y `catalog.consumer.maximum-backoff` (30 s). Lo demás
  (`SPRING_DATASOURCE_*`, `SPRING_RABBITMQ_*`, `SERVER_PORT=8082`) lo enlaza Spring. `CatalogProperties` llega en 5.4, con sus primeras propiedades reales; antes no habría nada que validar.
- Los valores por defecto de `application.yml` apuntan a `localhost` (puertos 5442 y 5672), de modo
  que arrancar desde el IDE funciona sin configurar nada; Compose los sobrescribe con variables de
  entorno.
- `spring.jpa.hibernate.ddl-auto=validate` y `open-in-view=false`: **Flyway es el único dueño del
  esquema**.
- Sin Spring Security (consultas públicas).

### Manejo de errores

Un único `GlobalExceptionHandler` produce `application/problem+json` (RFC 9457) con el enum
`ProblemType` como única fuente de tipos (`not-found`, `internal-error`, y el genérico `http-error`
para los errores estándar de Spring MVC, como el 405). `ProductNotFoundException` responde `404`. Un
error inesperado responde `internal-error` sin detalles internos y se registra completo. El mismo
patrón de Inventario se reutiliza. El Catálogo no define `validation-error`: su contrato no tiene 400 y no recibe cuerpos ni parámetros de consulta.

### Logging

SLF4J con Logback, mensajes de una línea `evento clave=valor`. Dos claves en el MDC, `sku` y
`eventId`. `INFO` para hechos de negocio, `WARN` para eventos rechazados y reintentos, `ERROR` para lo
inesperado. Nunca se registran secretos.

### Reloj

Un bean `Clock` (UTC) con precisión de milisegundos (`Clock.tick(..., 1 ms)`), inyectado por
constructor. `updated_at` y la latencia usan este reloj.

### Salud

El indicador de salud de RabbitMQ se desactiva (`management.health.rabbit.enabled=false`; la propiedad se configura en 5.4, al agregar AMQP): si el
broker cae, el Catálogo sigue sirviendo consultas y el listener se reconecta solo. La salud de la base
de datos permanece.

## 5. Datos y consulta (sub-tarea 5.2)

### Esquema (migraciones Flyway, comentarios en español)

```sql
CREATE TABLE products (
  sku                   VARCHAR(64)  PRIMARY KEY,
  name                  VARCHAR(120) NOT NULL,
  description           TEXT         NOT NULL,
  price                 INTEGER      NOT NULL CHECK (price >= 0),   -- COP, sin decimales
  currency              VARCHAR(3)   NOT NULL,
  available_stock       INTEGER      NOT NULL CHECK (available_stock >= 0),
  stock_version         BIGINT       NOT NULL,   -- versión interna del stock; la semilla usa 0
  updated_at            TIMESTAMPTZ  NOT NULL,
  last_event_latency_ms BIGINT                   -- nulo si nunca recibió un evento
);
```

La 5.2 crea `products` (`V1__create_products.sql`) y su semilla (`V2__seed_products.sql`); `processed_events` se crea en la 5.3 (`V3__create_processed_events.sql`), cuando el código la usa:

```sql
CREATE TABLE processed_events (
  event_id     UUID         PRIMARY KEY,
  processed_at TIMESTAMPTZ  NOT NULL
);
```

Sirve para deduplicar por `eventId` en la misma transacción que aplica el evento; la clave primaria es la
red de seguridad frente a duplicados simultáneos (§6). Crece sin límite; para la demo no se purga (YAGNI).

- `V2__seed_products.sql` siembra los 7 SKU de `docs/seed-data.md` con nombre, precio y stock;
  `stock_version = 0` (de modo que cualquier primer evento, con `stockVersion >= 1`, se aplique),
  `updated_at` igual a la hora del sembrado y `last_event_latency_ms = NULL`. Las **descripciones** solo
  viven en la migración (el documento no las trae).

### Entidad `Product`

Campos `sku` (id), `name`, `description`, `price`, `currency`, `availableStock`, `stockVersion`,
`updatedAt` y `lastEventLatencyMs`. **Sin setters.** El estado de disponibilidad cambia solo con
`applyStock(newStock, stockVersion, occurredAt, now)`, que fija el stock y la versión, `updatedAt = now`
y `lastEventLatencyMs = max(0, now − occurredAt)` en milisegundos. `inStock` se deriva
(`availableStock > 0`), no se guarda. El constructor sin argumentos exigido por JPA no es público.

### Consulta

- `ProductController` (`GET /api/v1/catalog/products` y `/{sku}`) llama a `ProductQueryService`
  (`list()` ordenada por `sku` y `getBySku(sku)`, que lanza `ProductNotFoundException`).
- `ProductResponse` (`record`) se crea con `ProductResponse.from(product)`, sin librería de mapeo.
  `price` es entero en COP y `currency` es `COP`.
- Un SKU que no cumple el patrón responde `404` porque no existe en la tabla, sin validación extra.
- Sin paginación ni filtros, como dice el contrato.

## 6. Consumo de eventos (sub-tareas 5.3 y 5.4)

### Aplicación (`ApplyStockChangeService`, una sola transacción)

`apply(StockChangedEvent event): ApplyResult` (`APPLIED` | `DUPLICATE` | `STALE`). No conoce RabbitMQ.

1. Si `eventId` ya está en `processed_events` → `DUPLICATE`, sin cambios.
2. Busca el producto por `sku`. Si no existe → `UnknownProductException` (error **permanente**); no se
   registra el `eventId`, de modo que reprocesar la DLQ tras sembrar el producto funciona.
3. Si `stockVersion` ≤ la versión guardada → `STALE` (evento viejo o fuera de orden, escenario 9): se
   registra el `eventId` y no se toca el stock.
4. Si no, `product.applyStock(...)`, se registra el `eventId` → `APPLIED`.

La unicidad de la clave primaria de `processed_events` es la red de seguridad: con un solo consumidor no
debería haber carreras, pero un duplicado simultáneo falla por clave y se reintenta como transitorio, y
el segundo intento lo encuentra y responde `DUPLICATE`.

### Evento (`StockChangedEvent`)

`record` propio del Catálogo con `eventId` (UUID), `sku`, `newStock`, `stockVersion`, `channel` y
`occurredAt` (`Instant`). **Tolerante:** Jackson ignora propiedades desconocidas (solo para el
consumidor; el REST no recibe cuerpos). Se valida lo que el Catálogo necesita: `eventId` UUID válido,
`sku` no vacío, `newStock` ≥ 0, `stockVersion` ≥ 1 y `occurredAt` interpretable como instante UTC. Cualquier
incumplimiento, o un JSON corrupto, es `InvalidEventException` (permanente).
La lectura la hace `StockEventReader` (recibe los bytes del cuerpo, usa Jackson y Bean Validation y no
conoce RabbitMQ); el `record` lleva `@JsonIgnoreProperties(ignoreUnknown = true)`, de modo que la
tolerancia no depende de la configuración global de Jackson. `channel` es informativo y no se valida.

### Listener (`StockEventListener`)

`@RabbitListener` sobre `catalog.consumer.queue` con `acknowledge-mode: manual`, **un solo consumidor**
(`concurrency: 1`) y `prefetch: 1`. Lee los bytes del cuerpo y los interpreta con Jackson (no depende
del `content_type`). Clasifica cada fallo según su causa:

| Resultado | Acción |
|---|---|
| `APPLIED`, `DUPLICATE`, `STALE` | `basicAck` y log (`event_applied`, `event_duplicate`, `event_stale`) |
| **Permanente:** `InvalidEventException`, `UnknownProductException` | `basicNack(requeue=false)` → el broker lo manda a la DLQ por `catalog.dlx`; `WARN event_rejected reason=…` |
| **Transitorio:** cualquier otra excepción (p. ej., la base caída) | Reintenta **dentro del listener** con espera creciente 1 s, 2 s, 4 s… hasta 30 s, sin confirmar; confirma al lograrlo. No va nunca a la DLQ por esta causa |
| Interrupción del hilo (apagado) | Deja de reintentar sin confirmar: el mensaje vuelve a la cola, sin pérdida |

- La política de espera es una clase pequeña y probada sola (esperas crecientes con tope, sin límite de
  intentos), con los tiempos tomados de `CatalogProperties`.
- Con la cola en pausa y un solo consumidor se conserva el orden: los eventos siguientes esperan detrás
  del que se reintenta. Una caída de la base se comporta como el escenario 5: se acumulan y el Catálogo
  converge al volver.
- El listener se configura con `missing-queues-fatal=false` y arranque automático: el servicio inicia
  aunque RabbitMQ no esté disponible y empieza a consumir cuando vuelve.
- **Límite conocido:** RabbitMQ cierra el canal si un mensaje permanece sin confirmar más de
  `consumer_timeout` (30 min por defecto); en una caída más larga el mensaje vuelve a la cola y se
  redelivera sin pérdida.
- Los registros llevan `sku` y `eventId` en el MDC. `event_applied` incluye el stock previo y el nuevo
  (`previous=… new=… version=… latencyMs=…`), usando el valor guardado como "previo".

### Notas heredadas de la fase 4 (para el consumidor)

- Las duplicaciones pueden llegar **tarde**, después de eventos más nuevos (p. ej., tras un tiempo de
  confirmación agotado en el productor): la deduplicación por `eventId` y la guarda `>` las absorben.
- La guarda de versión aplica un evento si su `stockVersion` es **mayor** que la guardada, no si es
  exactamente la guardada más uno.
- Los mensajes llevan una cabecera extra de Spring (`spring_returned_message_correlation`) y su
  `content_type` es `application/json` sin charset: el consumidor las ignora.
- El orden está garantizado **por producto**, no entre productos distintos.

## 7. Pruebas, empaquetado y entrega por sub-tarea

### Pruebas: dos velocidades

- `./mvnw test`: unitarias y prueba de arquitectura.
- `./mvnw verify`: además las de integración (`*IntegrationTest`, `maven-failsafe-plugin`) con
  PostgreSQL y RabbitMQ reales (Testcontainers; RabbitMQ carga `infra/rabbitmq/definitions.json`).
- Una base de pruebas compartida por ejecución (`PostgresIntegrationTest`) y otra con RabbitMQ
  (`RabbitIntegrationTest`), como en Inventario. Las clases que dependen de filas ajenas las vacían
  (`TRUNCATE`) en `@BeforeEach` (cada clase vacía solo la tabla que le corresponde, por ejemplo
  `processed_events`, nunca `products` ni los SKU sembrados; ver la regla de 5.3 y 5.4); un contexto con el listener activo que no deba sobrevivir a su clase usa
  `@DirtiesContext`.
- Las pruebas de integración de 5.3 y 5.4 usan productos `TEST-<UUID>` y solo vacían `processed_events`
  (`TRUNCATE`); nunca vacían `products` ni modifican los SKU sembrados, porque `SeedDataIntegrationTest` y
  `ProductEndpointIntegrationTest` dependen de la semilla intacta y todas las clases comparten una base.
- Sin meta numérica de cobertura: cada comportamiento de las secciones 4 a 6 tiene al menos una prueba.

**Unitarias:** `Product.applyStock` (stock, versión, latencia, latencia negativa → 0), la política de
espera (crece con tope, sin límite de intentos), la validación del evento y la clasificación de errores
del listener con un servicio y un canal falsos.

**Con PostgreSQL real:** la semilla coincide con `docs/seed-data.md` (SKU, nombre, precio y stock);
aplicar un evento, un duplicado, un evento viejo y fuera de orden, SKU desconocido, atomicidad (si falla
algo, no queda registrado el `eventId`); consulta REST validada contra `catalog-service.yaml` (200, 404,
lista ordenada).

**Con RabbitMQ real:** flujo completo publicar → aplicar → ack; escenario 5 (detener el listener con
`RabbitListenerEndpointRegistry`, publicar varios eventos y reanudar: se procesan todos en orden);
escenario 9 (versión 3 antes que 2 deja la 3); JSON corrupto, campo faltante y SKU desconocido terminan
en la DLQ; un error transitorio simulado reintenta y luego confirma; un campo extra en el evento se
tolera.

**Contrato del evento:** el Catálogo lee los ejemplos de `docs/contracts/events/`: acepta `valid.json`;
tolera `invalid-extra-field.json` (propiedad desconocida) y también `invalid-unknown-channel.json` e
`invalid-missing-millis.json` (el Catálogo no usa el canal ni exige milisegundos: tolerancia deliberada
del consumidor); y rechaza `invalid-negative-stock.json`, `invalid-missing-field.json`,
`invalid-zero-version.json` y el JSON truncado.

### Una sola fuente de verdad para los archivos de contrato

Maven copia desde `docs/` e `infra/` al `target/` (`maven-resources-plugin`): el OpenAPI del Catálogo
(para Swagger UI), los contratos y los ejemplos del evento, `docs/seed-data.md` y la topología de
RabbitMQ. Nunca hay una copia a mano en el repositorio.

### Empaquetado y Compose

- `catalog-service/Dockerfile` en dos etapas (compila con JDK 25, ejecuta con un JRE) y **usuario sin
  privilegios**. El contexto de construcción es la raíz del repositorio; se agrega `catalog-service` al
  `.dockerignore` de la raíz.
- Entrada `catalog-service` en el Compose: redes `messaging` y `catalog-net`, `depends_on` con
  `service_healthy` para `catalog-db` y RabbitMQ, variables de la spec de infraestructura
  (`SERVER_PORT=8082`, `SPRING_DATASOURCE_*` hacia `catalog-db`, `SPRING_RABBITMQ_*`), puerto
  `127.0.0.1:8082` configurable con `CATALOG_SERVICE_PORT` (y en `.env.example`) y healthcheck con
  `/actuator/health`.
- **Impacto en `scripts/verify-infra.sh`:** levanta solo `rabbitmq`, `inventory-db` y `catalog-db`;
  se agregó `CATALOG_SERVICE_PORT` a la lista `-u` de la función `compose_defaults` (variables que se
  quitan del entorno antes de comparar) y `.env.example` sigue alineado con los valores por defecto del
  Compose. No necesita un puerto propio porque el script nunca levanta un servicio propio más allá de
  rabbitmq y las bases de datos.
- **Impacto en `scripts/smoke-inventory-service.sh`:** levanta solo `inventory-service` y sus
  dependencias (no el Catálogo), así que el mensaje sigue acumulándose en la cola y la comprobación de
  la cola se mantiene.

### Sub-tareas

| | Entrega | Cómo se comprueba | Escenarios |
|---|---|---|---|
| **5.1** | Servicio que arranca en Compose, Swagger UI con el contrato, errores base, logging, reloj, Dockerfile sin root, `.dockerignore`, `CATALOG_SERVICE_PORT` y prueba de arquitectura | `docker compose up`, `./mvnw verify` | — |
| **5.2** | Migraciones, semilla y `GET /products` y `/products/{sku}`; el contrato publicado es el de Swagger UI y `/v3/api-docs` es solo informativo (aclarado en el README); reglas 3 y 4 de ArchUnit sin evaluación vacía | Respuesta contra el contrato; semilla igual al documento | consulta |
| **5.3** | `StockEventReader`, `ApplyStockChangeService`, `ProcessedEvent`, evento tolerante y validado | PostgreSQL real: aplicar, duplicado, viejo, SKU desconocido, atomicidad | 9 (a nivel de servicio) |
| **5.4** | `CatalogProperties` (primeras propiedades reales: espera del reintento y nombre de la cola), salud sin RabbitMQ, listener con ack manual, clasificación, reintento con espera creciente, DLQ; prueba de humo del flujo venta → Catálogo | RabbitMQ real: flujo completo, escenarios 5 y 9, DLQ; `scripts/smoke-catalog-service.sh` | 1, 5, 9 (lado Catálogo) |

**Dependencias por sub-tarea:** cada una agrega solo las que necesita (5.1: web, validación, actuator,
springdoc, ArchUnit, el validador de JSON Schema y pruebas; 5.2: Data JPA, Flyway, PostgreSQL y
Testcontainers; 5.4: AMQP y el módulo de RabbitMQ de Testcontainers). Los nombres exactos de los
starters de Spring Boot 4 y las propiedades de Jackson 3 se confirman en cada plan, reutilizando lo ya
verificado en Inventario.

### Pendientes

| Pendiente | Sub-tarea |
|---|---|
| `URI.create(requestPath)` en `GlobalExceptionHandler` y `ProblemType` de **Inventario** lanza si la ruta trae `[` o `]` (corregido en el Catálogo en la 5.2 con `ProblemType.toInstance`, que usa `UriComponentsBuilder.fromPath(...).build().encode()`; aplicar lo mismo en Inventario) | Cualquier cambio futuro en Inventario |
| Endurecer la regla 2 de ArchUnit (`StockEventListener` por nombre simple) restringiéndola también al paquete `stockevent` | 5.4 |
| `ClockConfigurationTest` de Inventario usa la variable de bucle `i` en lugar de `index` | Cualquier cambio futuro en Inventario |

### Cierre de la fase 5

`scripts/smoke-catalog-service.sh`, con todo en Compose: una venta en Inventario aparece en la
consulta del Catálogo en pocos segundos con su `lastEventLatencyMs`, y el contenedor no corre como root.
Es lo mínimo para dar la fase por terminada; los escenarios completos, la medición de latencia y los
scripts E2E son de la fase 6.

## 8. Decisiones descartadas

| Alternativa | Motivo |
|---|---|
| Reintento y clasificación con los interceptores de Spring AMQP | Confirman automáticamente (contra la regla de ack manual) y dejan el reintento configurado "por detrás", más difícil de probar y de explicar |
| Reencolar los fallos transitorios y esperar con colas de reintento con TTL | Reordena los mensajes y exige topología nueva en `infra/`; la espera dentro del listener, con un solo consumidor, conserva el orden |
| Varios consumidores o prefetch alto | Rompe el orden por producto en el reintento y no aporta nada a la demo |
| Hexagonal o limpia | Mismo criterio que Inventario: demasiada ceremonia para un servicio con una regla (`applyStock`) y dos casos de uso |
| Modelo de dominio puro, entidad JPA aparte y mapeador | Duplica clases; la entidad con comportamiento se prueba igual sin Spring |
| Registrar `eventId` solo cuando se aplica | Un duplicado de un evento ya viejo se reevaluaría; registrarlo en `STALE` evita trabajo y lo deja trazable |
| Autenticación en las consultas del Catálogo | La spec global las define públicas; fuera de alcance |
| Guardar `inStock` en la tabla | Es derivable de `available_stock` y se desalinearía; se calcula en la respuesta |
