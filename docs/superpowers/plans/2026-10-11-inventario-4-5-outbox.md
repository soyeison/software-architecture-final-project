# Inventario 4.5: outbox transaccional — Plan de implementación

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Que cada venta confirmada escriba, en la misma transacción que descuenta el stock, un evento `InventoryStockChanged` PENDING en la tabla `outbox`, con el JSON validado contra el contrato; sin publicarlo todavía (eso es la 4.6).

**Architecture:** Paquete `outbox/`: migración y entidad `OutboxEvent`, `StockEventFactory` (serializa un `StockChange` a JSON con `occurredAt` de exactamente 3 decimales) y `OutboxRecorder` (`@Transactional(MANDATORY)`, impone la atomicidad por diseño). `RegisterSaleService` llama a `OutboxRecorder.record(change)` dentro de la transacción de cada intento, justo después de `saveAndFlush`; si el outbox falla, la venta entera se revierte.

**Tech Stack:** Spring Boot 4.1.1 (Spring Data JPA, `@Transactional`), Jackson 3 (`tools.jackson`), PostgreSQL 17 con Testcontainers, networknt json-schema-validator, JUnit 5, Mockito (`@MockitoSpyBean`).

**Spec:** `docs/superpowers/specs/2026-10-11-inventario-design.md` (sección 8, parte "4.5", y sección 3 sobre dependencias entre paquetes). Contrato del evento: `docs/contracts/events/inventory-stock-changed.schema.json` (y sus ejemplos).

## Global Constraints

- Spring Boot `4.1.1`, Java `25`, Maven solo con el wrapper `./mvnw` desde `inventory-service/`. Paquete base `com.retail.inventory`.
- **Nombres completos, sin abreviaturas** (salvo `SKU`, `JWT`, `UUID`, `DTO`, `HTTP`, `JSON`, `DLQ` y unidades). Código en inglés (incluidas tablas y columnas); comentarios, Javadoc, comentarios SQL y documentación en español.
- Commits en inglés, breves, Conventional Commits, con la línea `Co-Authored-By: Claude <modelo> <noreply@anthropic.com>`.
- La ruta del repositorio contiene espacios: toda ruta en comandos va entre comillas dobles.
- Tabla `outbox`: `sequence_number BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY`, `event_id UUID NOT NULL UNIQUE`, `sku VARCHAR(64) NOT NULL`, `event_type VARCHAR(64) NOT NULL`, `payload TEXT NOT NULL` (el JSON ya serializado), `status VARCHAR(16) NOT NULL` (`PENDING` | `PUBLISHED`), `attempts INTEGER NOT NULL DEFAULT 0`, `last_error TEXT`, `occurred_at TIMESTAMPTZ NOT NULL`, `published_at TIMESTAMPTZ`; índice parcial `outbox_pending_index ON outbox (sequence_number) WHERE status = 'PENDING'`.
- `StockEventFactory` formatea `occurredAt` en UTC con **exactamente 3 decimales**, aunque los milisegundos sean cero (`.000Z`). `eventType` = `InventoryStockChanged`. `eventId` del evento = `saleId` de la venta.
- `OutboxRecorder.record(change)` es `@Transactional(propagation = MANDATORY)`: sin transacción abierta falla de inmediato.
- Dependencias permitidas entre paquetes: `sale → stock`, `sale → outbox`, `outbox → stock`. Solo `RabbitStockEventSender` (4.6) usa `org.springframework.amqp`. Los controladores no usan repositorios.
- La 4.5 **no publica** nada ni agrega propiedades de relay; no toca RabbitMQ.
- Las pruebas de integración se nombran `*IntegrationTest` (failsafe, `./mvnw verify`, requieren Docker); las demás corren con `./mvnw test`. Las pruebas que venden usan SKU de `TestStocks.insert`; el outbox acumula filas de todas las pruebas, así que cada prueba consulta solo las de su SKU.
- Línea base antes de empezar: `./mvnw test` → 39 pruebas; `./mvnw verify` → 39 + 77 de integración.

## Review Focus

1. **Una falla del outbox después del descuento**: la venta se revierte entera (stock, versión y evento), no queda stock descontado sin evento. Se fija en `OutboxAtomicityIntegrationTest` (Tarea 3).
2. **`record` llamado sin transacción** (por ejemplo desde código futuro de la 4.6): falla de inmediato con `IllegalTransactionStateException` y no escribe nada. Se fija en `OutboxRecordingIntegrationTest` (Tarea 3).
3. **Ventas rechazadas** (stock insuficiente, SKU inexistente, cuerpo inválido): no dejan evento. Se fija en la Tarea 3.
4. **Milisegundos en cero o con ceros finales** (`15:04:05Z`, `.100Z`): el JSON conserva exactamente 3 decimales y valida contra el schema. Se fija en `StockEventFactoryTest` (Tarea 2).
5. **Orden por SKU bajo concurrencia**: un evento por venta confirmada y, para un mismo SKU, versiones estrictamente crecientes en orden de `sequence_number`. Se fija en la ráfaga de `SaleConcurrencyIntegrationTest` (Tarea 3).

---

## Estructura de archivos

| Archivo | Responsabilidad |
|---|---|
| `inventory-service/src/main/resources/db/migration/V3__create_outbox.sql` | Tabla `outbox` e índice de pendientes |
| `.../outbox/OutboxStatus.java` | `PENDING` / `PUBLISHED` |
| `.../outbox/OutboxEvent.java` | Entidad JPA de la fila (sin setters; el publicador la modifica en 4.6) |
| `.../outbox/OutboxRepository.java` | Interfaz Spring Data |
| `.../outbox/StockChangedEvent.java` | Cuerpo del evento (record del paquete) |
| `.../outbox/StockEventFactory.java` | `StockChange` → JSON del contrato |
| `.../outbox/OutboxRecorder.java` | Escribe el evento exigiendo una transacción |
| `.../sale/RegisterSaleService.java` | Llama a `OutboxRecorder.record` dentro de la transacción |
| `src/test/.../support/ContractAssertions.java` | Agrega `assertMatchesEventSchema` |
| `src/test/.../outbox/*Test.java` | Pruebas del outbox |
| `README.md` | Documentación al día |

---

### Task 1: Tabla `outbox`, entidad y repositorio

**Files:**
- Create: `inventory-service/src/main/resources/db/migration/V3__create_outbox.sql`
- Create: `inventory-service/src/main/java/com/retail/inventory/outbox/OutboxStatus.java`
- Create: `inventory-service/src/main/java/com/retail/inventory/outbox/OutboxEvent.java`
- Create: `inventory-service/src/main/java/com/retail/inventory/outbox/OutboxRepository.java`
- Test: `inventory-service/src/test/java/com/retail/inventory/outbox/OutboxEventMappingIntegrationTest.java`

**Interfaces:**
- Consumes: `PostgresIntegrationTest`, `TestTokens.SECRET`.
- Produces:
  - `enum OutboxStatus { PENDING, PUBLISHED }`.
  - `class OutboxEvent` — constructor público `OutboxEvent(UUID eventId, String sku, String eventType, String payload, Instant occurredAt)` (nace `PENDING`, `attempts` 0); getters `Long getSequenceNumber()`, `UUID getEventId()`, `String getSku()`, `String getEventType()`, `String getPayload()`, `OutboxStatus getStatus()`, `int getAttempts()`, `String getLastError()`, `Instant getOccurredAt()`, `Instant getPublishedAt()`. Sin setters.
  - `interface OutboxRepository extends JpaRepository<OutboxEvent, Long>`.

- [ ] **Step 1: Escribir la prueba de integración (debe fallar)**

`inventory-service/src/test/java/com/retail/inventory/outbox/OutboxEventMappingIntegrationTest.java`:

```java
package com.retail.inventory.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;

import com.retail.inventory.support.PostgresIntegrationTest;
import com.retail.inventory.support.TestTokens;

@SpringBootTest(properties = "inventory.jwt.secret=" + TestTokens.SECRET)
class OutboxEventMappingIntegrationTest extends PostgresIntegrationTest {

    private static final Instant OCCURRED_AT = Instant.parse("2026-10-11T12:00:00.123Z");

    @Autowired
    private OutboxRepository outboxRepository;

    private static OutboxEvent newEvent(UUID eventId) {
        return new OutboxEvent(eventId, "TEST-" + UUID.randomUUID(), "InventoryStockChanged", "{\"k\":1}",
                OCCURRED_AT);
    }

    @Test
    void persistsAPendingEventWithoutAttempts() {
        UUID eventId = UUID.randomUUID();

        OutboxEvent saved = outboxRepository.saveAndFlush(newEvent(eventId));
        OutboxEvent found = outboxRepository.findById(saved.getSequenceNumber()).orElseThrow();

        assertThat(found.getEventId()).isEqualTo(eventId);
        assertThat(found.getStatus()).isEqualTo(OutboxStatus.PENDING);
        assertThat(found.getAttempts()).isZero();
        assertThat(found.getLastError()).isNull();
        assertThat(found.getPublishedAt()).isNull();
        assertThat(found.getPayload()).isEqualTo("{\"k\":1}");
        assertThat(found.getOccurredAt()).isEqualTo(OCCURRED_AT);
    }

    @Test
    void sequenceNumbersIncreaseInInsertionOrder() {
        OutboxEvent first = outboxRepository.saveAndFlush(newEvent(UUID.randomUUID()));
        OutboxEvent second = outboxRepository.saveAndFlush(newEvent(UUID.randomUUID()));

        assertThat(second.getSequenceNumber()).isGreaterThan(first.getSequenceNumber());
    }

    @Test
    void eventIdIsUnique() {
        UUID eventId = UUID.randomUUID();
        outboxRepository.saveAndFlush(newEvent(eventId));

        assertThatThrownBy(() -> outboxRepository.saveAndFlush(newEvent(eventId)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}
```

- [ ] **Step 2: Ejecutarla y verificar que falla**

Run (desde `inventory-service/`): `./mvnw verify -Dit.test=OutboxEventMappingIntegrationTest`
Expected: FAIL de compilación (`OutboxEvent` no existe).

- [ ] **Step 3: Migración**

`inventory-service/src/main/resources/db/migration/V3__create_outbox.sql`:

```sql
-- Outbox transaccional: el evento se escribe en la misma transacción que el cambio de stock y un
-- publicador interno lo envía después al broker (patrón Transactional Outbox).
CREATE TABLE outbox (
    -- Fija el orden de publicación.
    sequence_number BIGINT       GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    event_id        UUID         NOT NULL UNIQUE,
    sku             VARCHAR(64)  NOT NULL,
    event_type      VARCHAR(64)  NOT NULL,
    -- El JSON del evento, ya serializado.
    payload         TEXT         NOT NULL,
    -- PENDING | PUBLISHED
    status          VARCHAR(16)  NOT NULL,
    attempts        INTEGER      NOT NULL DEFAULT 0,
    last_error      TEXT,
    occurred_at     TIMESTAMPTZ  NOT NULL,
    published_at    TIMESTAMPTZ
);

-- El publicador solo lee los pendientes, en orden.
CREATE INDEX outbox_pending_index ON outbox (sequence_number) WHERE status = 'PENDING';
```

- [ ] **Step 4: Entidad, estado y repositorio**

`outbox/OutboxStatus.java`:

```java
package com.retail.inventory.outbox;

/** Estado de un evento del outbox. */
public enum OutboxStatus {
    /** Escrito, aún no confirmado por el broker. */
    PENDING,
    /** El broker confirmó la recepción. */
    PUBLISHED
}
```

`outbox/OutboxEvent.java`:

```java
package com.retail.inventory.outbox;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * Fila del outbox: un evento pendiente de publicar o ya publicado. Sin setters; los cambios de estado
 * del publicador llegan en la sub-tarea 4.6.
 */
@Entity
@Table(name = "outbox")
public class OutboxEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "sequence_number")
    private Long sequenceNumber;

    @Column(name = "event_id", nullable = false, unique = true)
    private UUID eventId;

    @Column(nullable = false, length = 64)
    private String sku;

    @Column(name = "event_type", nullable = false, length = 64)
    private String eventType;

    @Column(nullable = false)
    private String payload;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private OutboxStatus status;

    @Column(nullable = false)
    private int attempts;

    @Column(name = "last_error")
    private String lastError;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    @Column(name = "published_at")
    private Instant publishedAt;

    /** Constructor exigido por JPA. */
    protected OutboxEvent() {
    }

    /** Evento nuevo: nace PENDING y sin intentos. */
    public OutboxEvent(UUID eventId, String sku, String eventType, String payload, Instant occurredAt) {
        this.eventId = eventId;
        this.sku = sku;
        this.eventType = eventType;
        this.payload = payload;
        this.status = OutboxStatus.PENDING;
        this.attempts = 0;
        this.occurredAt = occurredAt;
    }

    public Long getSequenceNumber() {
        return sequenceNumber;
    }

    public UUID getEventId() {
        return eventId;
    }

    public String getSku() {
        return sku;
    }

    public String getEventType() {
        return eventType;
    }

    public String getPayload() {
        return payload;
    }

    public OutboxStatus getStatus() {
        return status;
    }

    public int getAttempts() {
        return attempts;
    }

    public String getLastError() {
        return lastError;
    }

    public Instant getOccurredAt() {
        return occurredAt;
    }

    public Instant getPublishedAt() {
        return publishedAt;
    }
}
```

`outbox/OutboxRepository.java`:

```java
package com.retail.inventory.outbox;

import org.springframework.data.jpa.repository.JpaRepository;

public interface OutboxRepository extends JpaRepository<OutboxEvent, Long> {
}
```

- [ ] **Step 5: Verificar**

Run: `./mvnw verify`
Expected: BUILD SUCCESS, 0 fallos. Cifras de referencia: 39 rápidas y 80 de integración (77 + 3). `ArchitectureTest` sigue en verde (`outbox` aún no depende de nadie).

- [ ] **Step 6: Commit**

```bash
git add inventory-service/src
git commit -m "feat: add outbox table, entity and repository

Co-Authored-By: Claude <modelo> <noreply@anthropic.com>"
```

---

### Task 2: `StockEventFactory` y validación del evento contra el contrato

**Files:**
- Create: `inventory-service/src/main/java/com/retail/inventory/outbox/StockChangedEvent.java`
- Create: `inventory-service/src/main/java/com/retail/inventory/outbox/StockEventFactory.java`
- Modify (test): `inventory-service/src/test/java/com/retail/inventory/support/ContractAssertions.java`
- Modify (test): `inventory-service/src/test/java/com/retail/inventory/support/ContractAssertionsTest.java`
- Test: `inventory-service/src/test/java/com/retail/inventory/outbox/StockEventFactoryTest.java`

**Interfaces:**
- Consumes: `StockChange(UUID eventId, String sku, int newStock, long stockVersion, Channel channel, Instant occurredAt)` y `Channel` (paquete `stock`).
- Produces:
  - `record StockChangedEvent(String eventId, String eventType, String sku, int newStock, long stockVersion, Channel channel, String occurredAt)` (visibilidad de paquete).
  - `@Component class StockEventFactory` — constructor `StockEventFactory(tools.jackson.databind.json.JsonMapper)`; `String create(StockChange change)`; constante de paquete `EVENT_TYPE = "InventoryStockChanged"`.
  - `ContractAssertions.assertMatchesEventSchema(String json)` (lanza `AssertionError` si no cumple `inventory-stock-changed.schema.json`).

- [ ] **Step 1: Escribir las pruebas (deben fallar)**

`inventory-service/src/test/java/com/retail/inventory/outbox/StockEventFactoryTest.java`:

```java
package com.retail.inventory.outbox;

import static com.retail.inventory.support.ContractAssertions.assertMatchesEventSchema;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import com.retail.inventory.stock.Channel;
import com.retail.inventory.stock.StockChange;

class StockEventFactoryTest {

    private static final UUID EVENT_ID = UUID.fromString("7f3c9a2e-5b1d-4c8e-9f0a-2d6b8e1c4a73");

    private final JsonMapper jsonMapper = JsonMapper.builder().build();
    private final StockEventFactory factory = new StockEventFactory(jsonMapper);

    private static StockChange change(Instant occurredAt) {
        return new StockChange(EVENT_ID, "TENIS-RUNNER-42", 4, 8L, Channel.POS, occurredAt);
    }

    @Test
    void createsAnEventThatMatchesTheContractSchema() {
        String json = factory.create(change(Instant.parse("2026-10-10T15:04:05.123Z")));

        assertMatchesEventSchema(json);
        JsonNode event = jsonMapper.readTree(json);
        assertThat(event.get("eventId").asString()).isEqualTo(EVENT_ID.toString());
        assertThat(event.get("eventType").asString()).isEqualTo("InventoryStockChanged");
        assertThat(event.get("sku").asString()).isEqualTo("TENIS-RUNNER-42");
        assertThat(event.get("newStock").asInt()).isEqualTo(4);
        assertThat(event.get("stockVersion").asLong()).isEqualTo(8L);
        assertThat(event.get("channel").asString()).isEqualTo("POS");
        assertThat(event.get("occurredAt").asString()).isEqualTo("2026-10-10T15:04:05.123Z");
    }

    @Test
    void keepsThreeDecimalsWhenTheMillisecondsAreZero() {
        String json = factory.create(change(Instant.parse("2026-10-10T15:04:05Z")));

        assertMatchesEventSchema(json);
        assertThat(jsonMapper.readTree(json).get("occurredAt").asString()).isEqualTo("2026-10-10T15:04:05.000Z");
    }

    @Test
    void keepsThreeDecimalsWhenOnlyOneDecimalIsSignificant() {
        String json = factory.create(change(Instant.parse("2026-10-10T15:04:05.100Z")));

        assertThat(jsonMapper.readTree(json).get("occurredAt").asString()).isEqualTo("2026-10-10T15:04:05.100Z");
    }

    @Test
    void producesTheOnlyPropertiesTheContractAllows() {
        JsonNode event = jsonMapper.readTree(factory.create(change(Instant.parse("2026-10-10T15:04:05.123Z"))));

        assertThat(event.propertyNames()).containsExactlyInAnyOrder("eventId", "eventType", "sku", "newStock",
                "stockVersion", "channel", "occurredAt");
    }
}
```

En `support/ContractAssertionsTest.java`, antes de la llave de cierre de la clase, agregar:

```java
    @Test
    void acceptsTheContractExampleOfTheEvent() throws Exception {
        String valid = new String(getClass().getResourceAsStream("/contracts/events/examples/valid.json")
                .readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);

        assertThatCode(() -> ContractAssertions.assertMatchesEventSchema(valid)).doesNotThrowAnyException();
    }

    @Test
    void rejectsEventWithoutMilliseconds() throws Exception {
        String invalid = new String(getClass()
                .getResourceAsStream("/contracts/events/examples/invalid-missing-millis.json").readAllBytes(),
                java.nio.charset.StandardCharsets.UTF_8);

        assertThatThrownBy(() -> ContractAssertions.assertMatchesEventSchema(invalid))
                .isInstanceOf(AssertionError.class);
    }
```

- [ ] **Step 2: Ejecutarlas y verificar que fallan**

Run: `./mvnw test -Dtest='StockEventFactoryTest,ContractAssertionsTest'`
Expected: FAIL de compilación (`StockEventFactory` y `assertMatchesEventSchema` no existen).

- [ ] **Step 3: Helper de contrato del evento**

En `support/ContractAssertions.java`, junto a la constante `OPENAPI_CONTRACT`, agregar:

```java
    private static final String EVENT_CONTRACT = "classpath:contracts/events/inventory-stock-changed.schema.json";
```

y, antes de la llave de cierre de la clase:

```java

    /** Falla si {@code json} no cumple el JSON Schema del evento {@code InventoryStockChanged}. */
    public static void assertMatchesEventSchema(String json) {
        Schema schema = SCHEMA_REGISTRY.getSchema(SchemaLocation.of(EVENT_CONTRACT));
        List<Error> errors = schema.validate(json, InputFormat.JSON);
        if (!errors.isEmpty()) {
            String messages = errors.stream().map(Error::getMessage).collect(Collectors.joining("; "));
            throw new AssertionError("Event does not match the InventoryStockChanged schema: " + messages
                    + System.lineSeparator() + json);
        }
    }
```

- [ ] **Step 4: Implementar la fábrica**

`outbox/StockChangedEvent.java`:

```java
package com.retail.inventory.outbox;

import com.retail.inventory.stock.Channel;

/**
 * Cuerpo del evento {@code InventoryStockChanged} (schema {@code inventory-stock-changed.schema.json}).
 * {@code occurredAt} ya viene formateado: el contrato exige exactamente 3 decimales.
 */
record StockChangedEvent(String eventId, String eventType, String sku, int newStock, long stockVersion,
        Channel channel, String occurredAt) {
}
```

`outbox/StockEventFactory.java`:

```java
package com.retail.inventory.outbox;

import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

import org.springframework.stereotype.Component;

import tools.jackson.databind.json.JsonMapper;

import com.retail.inventory.stock.StockChange;

/** Construye el JSON del evento {@code InventoryStockChanged} a partir de un {@link StockChange}. */
@Component
public class StockEventFactory {

    static final String EVENT_TYPE = "InventoryStockChanged";

    /** UTC con exactamente 3 decimales, también cuando los milisegundos son cero ({@code .000Z}). */
    private static final DateTimeFormatter OCCURRED_AT_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC);

    private final JsonMapper jsonMapper;

    public StockEventFactory(JsonMapper jsonMapper) {
        this.jsonMapper = jsonMapper;
    }

    public String create(StockChange change) {
        StockChangedEvent event = new StockChangedEvent(change.eventId().toString(), EVENT_TYPE, change.sku(),
                change.newStock(), change.stockVersion(), change.channel(),
                OCCURRED_AT_FORMAT.format(change.occurredAt()));
        return jsonMapper.writeValueAsString(event);
    }
}
```

- [ ] **Step 5: Verificar**

Run: `./mvnw test`
Expected: PASS — 45 pruebas rápidas (39 + 4 de la fábrica + 2 del helper).

- [ ] **Step 6: Commit**

```bash
git add inventory-service/src
git commit -m "feat: add stock event factory validated against the event contract

Co-Authored-By: Claude <modelo> <noreply@anthropic.com>"
```

---

### Task 3: `OutboxRecorder` dentro de la transacción de la venta

**Files:**
- Create: `inventory-service/src/main/java/com/retail/inventory/outbox/OutboxRecorder.java`
- Modify: `inventory-service/src/main/java/com/retail/inventory/sale/RegisterSaleService.java`
- Modify (test): `inventory-service/src/test/java/com/retail/inventory/sale/RegisterSaleServiceTest.java`
- Modify (test): `inventory-service/src/test/java/com/retail/inventory/sale/SaleConcurrencyIntegrationTest.java`
- Test: `inventory-service/src/test/java/com/retail/inventory/outbox/OutboxRecordingIntegrationTest.java`
- Test: `inventory-service/src/test/java/com/retail/inventory/outbox/OutboxAtomicityIntegrationTest.java`

**Interfaces:**
- Consumes: `OutboxRepository`, `OutboxEvent` (Tarea 1); `StockEventFactory#create`, `EVENT_TYPE` (Tarea 2); `ContractAssertions.assertMatchesEventSchema`; `TestStocks.insert`.
- Produces:
  - `@Service class OutboxRecorder` — constructor `OutboxRecorder(OutboxRepository, StockEventFactory)`; `void record(StockChange change)` con `@Transactional(propagation = Propagation.MANDATORY)`.
  - `RegisterSaleService` — nuevo constructor `RegisterSaleService(StockRepository, TransactionTemplate, OptimisticRetry, OutboxRecorder, Clock)`; `sell(...)` llama a `outboxRecorder.record(change)` tras `saveAndFlush`.

- [ ] **Step 1: Escribir las pruebas (deben fallar)**

`inventory-service/src/test/java/com/retail/inventory/outbox/OutboxRecordingIntegrationTest.java`:

```java
package com.retail.inventory.outbox;

import static com.retail.inventory.support.ContractAssertions.assertMatchesEventSchema;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.IllegalTransactionStateException;

import tools.jackson.databind.json.JsonMapper;

import com.retail.inventory.stock.Channel;
import com.retail.inventory.stock.StockChange;
import com.retail.inventory.support.PostgresIntegrationTest;
import com.retail.inventory.support.TestStocks;
import com.retail.inventory.support.TestTokens;

/** La venta y su evento nacen juntos: una venta confirmada deja exactamente un evento PENDING. */
@SpringBootTest(properties = "inventory.jwt.secret=" + TestTokens.SECRET)
@AutoConfigureMockMvc
class OutboxRecordingIntegrationTest extends PostgresIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private OutboxRecorder outboxRecorder;

    @Autowired
    private JsonMapper jsonMapper;

    private String sell(String sku, int quantity, int expectedStatus) throws Exception {
        return mockMvc.perform(post("/api/v1/inventory/" + sku + "/sales")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + TestTokens.withScope("inventory:sell"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"quantity\": " + quantity + ", \"channel\": \"ONLINE\"}"))
                .andExpect(status().is(expectedStatus))
                .andReturn().getResponse().getContentAsString();
    }

    private List<Map<String, Object>> outboxRows(String sku) {
        return jdbcTemplate.queryForList(
                "SELECT event_id, event_type, payload, status, attempts, last_error, published_at FROM outbox"
                        + " WHERE sku = ? ORDER BY sequence_number", sku);
    }

    @Test
    void confirmedSaleLeavesExactlyOnePendingEventWithTheSaleIdAndAValidPayload() throws Exception {
        String sku = TestStocks.insert(jdbcTemplate, 5);

        String saleId = jsonMapper.readTree(sell(sku, 2, 201)).get("saleId").asString();

        List<Map<String, Object>> rows = outboxRows(sku);
        assertThat(rows).hasSize(1);
        Map<String, Object> row = rows.get(0);
        assertThat(row.get("event_id").toString()).isEqualTo(saleId);
        assertThat(row.get("event_type")).isEqualTo("InventoryStockChanged");
        assertThat(row.get("status")).isEqualTo("PENDING");
        assertThat(row.get("attempts")).isEqualTo(0);
        assertThat(row.get("last_error")).isNull();
        assertThat(row.get("published_at")).isNull();
        String payload = (String) row.get("payload");
        assertMatchesEventSchema(payload);
        assertThat(jsonMapper.readTree(payload).get("eventId").asString()).isEqualTo(saleId);
        assertThat(jsonMapper.readTree(payload).get("newStock").asInt()).isEqualTo(3);
        assertThat(jsonMapper.readTree(payload).get("stockVersion").asLong()).isEqualTo(2L);
        assertThat(jsonMapper.readTree(payload).get("channel").asString()).isEqualTo("ONLINE");
    }

    @Test
    void salesOfTheSameSkuAreRecordedInSaleOrder() throws Exception {
        String sku = TestStocks.insert(jdbcTemplate, 5);

        sell(sku, 1, 201);
        sell(sku, 1, 201);
        sell(sku, 1, 201);

        List<Long> versions = outboxRows(sku).stream()
                .map(row -> jsonMapper.readTree((String) row.get("payload")).get("stockVersion").asLong())
                .toList();
        assertThat(versions).containsExactly(2L, 3L, 4L);
    }

    @Test
    void rejectedSalesLeaveNoEvent() throws Exception {
        String sku = TestStocks.insert(jdbcTemplate, 1);

        sell(sku, 2, 409);
        mockMvc.perform(post("/api/v1/inventory/" + sku + "/sales")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + TestTokens.withScope("inventory:sell"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"quantity\": 0, \"channel\": \"POS\"}"))
                .andExpect(status().isBadRequest());
        sell("DOES-NOT-EXIST", 1, 404);

        assertThat(outboxRows(sku)).isEmpty();
        assertThat(outboxRows("DOES-NOT-EXIST")).isEmpty();
    }

    @Test
    void recordingWithoutAnOpenTransactionFailsImmediately() {
        StockChange change = new StockChange(UUID.randomUUID(), "TEST-" + UUID.randomUUID(), 1, 2L, Channel.POS,
                Instant.parse("2026-10-11T12:00:00.000Z"));

        assertThatThrownBy(() -> outboxRecorder.record(change)).isInstanceOf(IllegalTransactionStateException.class);
        assertThat(outboxRows(change.sku())).isEmpty();
    }
}
```

`inventory-service/src/test/java/com/retail/inventory/outbox/OutboxAtomicityIntegrationTest.java`:

```java
package com.retail.inventory.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;

import com.retail.inventory.support.PostgresIntegrationTest;
import com.retail.inventory.support.TestStocks;
import com.retail.inventory.support.TestTokens;

/** Si el outbox falla, la venta se revierte entera: ni descuento ni versión nueva ni evento. */
@SpringBootTest(properties = "inventory.jwt.secret=" + TestTokens.SECRET)
@AutoConfigureMockMvc
class OutboxAtomicityIntegrationTest extends PostgresIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @MockitoSpyBean
    private OutboxRepository outboxRepository;

    @Test
    void outboxFailureRollsBackTheWholeSale() throws Exception {
        String sku = TestStocks.insert(jdbcTemplate, 5);
        doThrow(new IllegalStateException("outbox unavailable")).when(outboxRepository).save(any());

        mockMvc.perform(post("/api/v1/inventory/" + sku + "/sales")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + TestTokens.withScope("inventory:sell"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"quantity\": 2, \"channel\": \"POS\"}"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.detail").value("An unexpected error occurred"));

        assertThat(jdbcTemplate.queryForObject("SELECT quantity FROM stock WHERE sku = ?", Integer.class, sku))
                .isEqualTo(5);
        assertThat(jdbcTemplate.queryForObject("SELECT version FROM stock WHERE sku = ?", Long.class, sku))
                .isEqualTo(1L);
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM outbox WHERE sku = ?", Integer.class, sku))
                .isZero();
    }
}
```

En `sale/RegisterSaleServiceTest.java`:
- Agregar el import `com.retail.inventory.outbox.OutboxRecorder;` (antes de `com.retail.inventory.stock.Channel`).
- Reemplazar la construcción del servicio por:

```java
    private final OutboxRecorder outboxRecorder = mock(OutboxRecorder.class);
    private final RegisterSaleService service = new RegisterSaleService(stockRepository, transactionTemplate,
            new OptimisticRetry(3), outboxRecorder, Clock.fixed(NOW, ZoneOffset.UTC));
```

En `sale/SaleConcurrencyIntegrationTest.java`, en `burstKeepsTheStockInvariant`, tras la última aserción, agregar la llamada `assertOneEventPerSuccessfulSaleInVersionOrder(sku, successes);` y, debajo del método, este nuevo método:

```java
    /** Un evento por venta confirmada y, por SKU, el orden de publicación coincide con el de las versiones. */
    private void assertOneEventPerSuccessfulSaleInVersionOrder(String sku, long successes) {
        List<Long> versions = jdbcTemplate.queryForList(
                "SELECT (payload::jsonb ->> 'stockVersion')::bigint FROM outbox WHERE sku = ?"
                        + " ORDER BY sequence_number", Long.class, sku);
        assertThat(versions).hasSize((int) successes).isSorted();
        assertThat(versions).doesNotHaveDuplicates();
    }
```

- [ ] **Step 2: Ejecutarlas y verificar que fallan**

Run: `./mvnw test -Dtest=RegisterSaleServiceTest`
Expected: FAIL de compilación (`OutboxRecorder` no existe).

- [ ] **Step 3: Implementar `OutboxRecorder`**

`outbox/OutboxRecorder.java`:

```java
package com.retail.inventory.outbox;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.retail.inventory.stock.StockChange;

/**
 * Escribe el evento en el outbox. Exige una transacción abierta ({@code MANDATORY}): la atomicidad
 * entre el cambio de stock y su evento queda impuesta por el diseño, no por la disciplina de quien llama.
 */
@Service
public class OutboxRecorder {

    private static final Logger LOGGER = LoggerFactory.getLogger(OutboxRecorder.class);

    private final OutboxRepository outboxRepository;
    private final StockEventFactory stockEventFactory;

    public OutboxRecorder(OutboxRepository outboxRepository, StockEventFactory stockEventFactory) {
        this.outboxRepository = outboxRepository;
        this.stockEventFactory = stockEventFactory;
    }

    /** @throws org.springframework.transaction.IllegalTransactionStateException si no hay transacción abierta */
    @Transactional(propagation = Propagation.MANDATORY)
    public void record(StockChange change) {
        outboxRepository.save(new OutboxEvent(change.eventId(), change.sku(), StockEventFactory.EVENT_TYPE,
                stockEventFactory.create(change), change.occurredAt()));
        LOGGER.info("outbox_recorded eventId={} sku={} stockVersion={}", change.eventId(), change.sku(),
                change.stockVersion());
    }
}
```

- [ ] **Step 4: Enganchar en la venta**

En `sale/RegisterSaleService.java`:
- Import `com.retail.inventory.outbox.OutboxRecorder;` (antes de `com.retail.inventory.stock.Channel`).
- Campo `private final OutboxRecorder outboxRecorder;` (antes de `clock`), y el constructor pasa a:

```java
    public RegisterSaleService(StockRepository stockRepository, TransactionTemplate transactionTemplate,
            OptimisticRetry optimisticRetry, OutboxRecorder outboxRecorder, Clock clock) {
        this.stockRepository = stockRepository;
        this.transactionTemplate = transactionTemplate;
        this.optimisticRetry = optimisticRetry;
        this.outboxRecorder = outboxRecorder;
        this.clock = clock;
    }
```

- Reemplazar la última línea de `sell(...)` (el `return new StockChange(...)`) por:

```java
        StockChange change = new StockChange(UUID.randomUUID(), sku, saved.getQuantity(), saved.getVersion(),
                channel, now);
        // Misma transacción que el descuento: si el outbox falla, la venta entera se revierte.
        outboxRecorder.record(change);
        return change;
```

- [ ] **Step 5: Verificar**

Run: `./mvnw verify`
Expected: BUILD SUCCESS, 0 fallos. Cifras de referencia: 45 rápidas y 85 de integración (80 + 4 de `OutboxRecordingIntegrationTest` + 1 de `OutboxAtomicityIntegrationTest`). `ArchitectureTest` en verde: `sale → outbox` y `outbox → stock` están permitidas y no hay ciclos.

- [ ] **Step 6: Mutaciones de comprobación (no se commitean)**

1. Comentar la línea `outboxRecorder.record(change);` en `RegisterSaleService.sell` y ejecutar `./mvnw verify -Dit.test=OutboxRecordingIntegrationTest -Dtest=NoSuchTest -Dsurefire.failIfNoSpecifiedTests=false`: debe FALLAR (no hay fila). Restaurar la línea.
2. Cambiar `Propagation.MANDATORY` por `Propagation.REQUIRED` en `OutboxRecorder` y ejecutar lo mismo: `recordingWithoutAnOpenTransactionFailsImmediately` debe FALLAR. Restaurar.
3. `git status` debe mostrar solo los archivos del paso anterior sin cambios pendientes.

- [ ] **Step 7: Commit**

```bash
git add inventory-service/src
git commit -m "feat: record stock change events in the outbox within the sale transaction

Co-Authored-By: Claude <modelo> <noreply@anthropic.com>"
```

---

### Task 4: Documentación y verificación de punta a punta

**Files:**
- Modify: `README.md`

**Interfaces:**
- Consumes: todo lo anterior.
- Produces: documentación al día; verificación del servicio dentro de Compose.

- [ ] **Step 1: README**

En la sección "Servicio de Inventario", después del párrafo que explica el endpoint de venta (el que termina con `docker compose down -v`), agregar:

````markdown
Cada venta confirmada escribe, en la misma transacción que descuenta el stock, un evento
`InventoryStockChanged` en la tabla `outbox` con estado `PENDING` (patrón Transactional Outbox). Si el
outbox falla, la venta se revierte entera. Hasta la sub-tarea 4.6 nada lo publica a RabbitMQ; para verlo:

```bash
docker compose exec inventory-db psql -U inventory -d inventory -c "SELECT sequence_number, event_id, sku, status, attempts FROM outbox"
```
````

- [ ] **Step 2: Verificación completa**

Con los puertos por defecto libres (`docker compose ps`; si hay un stack `retail` en marcha, `docker compose down -v` antes), desde la raíz del repositorio y cada comando por separado:

```bash
cd inventory-service && ./mvnw verify
```
Expected: BUILD SUCCESS, 45 pruebas rápidas y 85 de integración.

```bash
docker compose up -d --wait --build inventory-service
```
Expected: `retail-inventory-service-1 Healthy`.

```bash
TOKEN="$(scripts/generate-token.sh pos)"; curl -s -X POST -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" -d '{"quantity":1,"channel":"POS"}' -w ' [%{http_code}]\n' http://localhost:8081/api/v1/inventory/TENIS-RUNNER-42/sales
```
Expected: `201` con `"remainingStock":4` y `"version":2`.

```bash
docker compose exec -T inventory-db psql -U inventory -d inventory -c "SELECT sequence_number, event_id, sku, status, attempts, payload FROM outbox"
```
Expected: una fila con `event_id` igual al `saleId` de la respuesta, `status` `PENDING`, `attempts` 0 y un `payload` con `eventType` `InventoryStockChanged`, `newStock` 4, `stockVersion` 2 y `occurredAt` con tres decimales.

```bash
docker compose down -v
```

- [ ] **Step 3: Commit**

```bash
git add README.md
git commit -m "docs: document the transactional outbox

Co-Authored-By: Claude <modelo> <noreply@anthropic.com>"
```

---

## Autorrevisión

- **Cobertura de la spec §8 (4.5):** tabla con todas sus columnas e índice parcial (T1); `sequence_number` identity y orden por SKU (T1 prueba de secuencia, T3 orden por SKU y ráfaga); `StockEventFactory` con 3 decimales y validación con `networknt` (T2, incluido `.000Z`); `OutboxRecorder` `MANDATORY` (T3); una venta deja exactamente una fila PENDING con `eventId = saleId` y payload válido (T3); si el outbox falla, la venta se revierte (T3 atomicidad); una venta rechazada no deja evento (T3); `record` sin transacción falla (T3).
- **Fuera de alcance (4.6):** relay, `StockEventSender`, `GET /outbox`, propiedades de relay, RabbitMQ.
- **Consistencia de tipos:** `OutboxEvent(UUID, String, String, String, Instant)`; `StockEventFactory#create(StockChange): String` y `EVENT_TYPE`; `OutboxRecorder#record(StockChange)`; `RegisterSaleService` con cinco parámetros `(StockRepository, TransactionTemplate, OptimisticRetry, OutboxRecorder, Clock)`.
- **Conteos de referencia:** rápidas 39 → 45 (T2) ; integración 77 → 80 (T1) → 85 (T3).
- **Verificado en una copia temporal** antes de escribir el plan: 45 rápidas + 85 de integración en verde y, dentro de Compose, una venta dejó una fila `PENDING` con el `saleId` y el JSON esperado.
