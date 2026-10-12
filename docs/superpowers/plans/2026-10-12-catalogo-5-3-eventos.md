# Catálogo 5.3: aplicar eventos de forma idempotente — Plan de implementación

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Que `catalog-service` sepa leer un evento `InventoryStockChanged` (tolerante y validado) y aplicarlo a la vista de disponibilidad de forma idempotente y atómica: deduplica por `eventId`, descarta versiones viejas o fuera de orden y registra la latencia, todo en una sola transacción y sin tocar RabbitMQ.

**Architecture:** Paquete `stockevent/`: `StockEventReader` (bytes → `StockChangedEvent` validado, o `InvalidEventException`), `ApplyStockChangeService` (transaccional; devuelve `ApplyResult`) y la entidad `ProcessedEvent` con su repositorio. La clave primaria de `processed_events` es la red de seguridad de la idempotencia. El listener de RabbitMQ (5.4) solo orquestará estas piezas.

**Tech Stack:** Spring Boot 4.1.1 (Spring Data JPA, `@Transactional`), Jackson 3 (`tools.jackson` más las anotaciones `com.fasterxml.jackson.annotation`), Bean Validation, PostgreSQL 17 con Testcontainers, Mockito (`@MockitoBean`, `@MockitoSpyBean`).

**Spec:** `docs/superpowers/specs/2026-10-12-catalogo-design.md` (secciones 5, 6 y 7). Contrato del evento: `docs/contracts/events/inventory-stock-changed.schema.json` y sus ejemplos.

## Global Constraints

- Spring Boot `4.1.1`, Java `25`, Maven solo con el wrapper `./mvnw` desde `catalog-service/`. Paquete base `com.retail.catalog`.
- **Nombres completos, sin abreviaturas** (salvo `SKU`, `JWT`, `UUID`, `DTO`, `HTTP`, `JSON`, `DLQ` y unidades). Código en inglés (incluidas tablas y columnas); comentarios, Javadoc, comentarios SQL y documentación en español.
- Commits en inglés, breves, Conventional Commits, con la línea `Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>`.
- La ruta del repositorio contiene espacios: toda ruta en comandos va entre comillas dobles.
- `V3__create_processed_events.sql`: `processed_events(event_id UUID PRIMARY KEY, processed_at TIMESTAMPTZ NOT NULL)`.
- Aplicación (`ApplyStockChangeService.apply`, **una sola transacción**): (1) `eventId` ya registrado → `DUPLICATE`, sin cambios; (2) SKU inexistente → `UnknownProductException` (error **permanente**) y **no** se registra el `eventId`; (3) `stockVersion` **≤** la versión guardada → `STALE` (se **registra** el `eventId`, no se toca el stock); (4) si no, `product.applyStock(...)` y se registra el `eventId` → `APPLIED`. La guarda es estricta: se aplica solo si `stockVersion` es **mayor** que la guardada (no "guardada + 1").
- Lectura del evento: consumidor **tolerante** (ignora propiedades desconocidas); se valida lo que el Catálogo usa: `eventId` UUID válido, `sku` no vacío (máximo 64), `newStock` ≥ 0, `stockVersion` ≥ 1 y `occurredAt` interpretable como instante. `channel` es informativo y no se valida. JSON corrupto, vacío o con campos faltantes/inválidos → `InvalidEventException` (permanente).
- Consumo sin RabbitMQ: nada de AMQP en esta sub-tarea (llega en la 5.4). Solo `StockEventListener` usará `org.springframework.amqp`.
- Pruebas de integración (`*IntegrationTest`, `./mvnw verify`, requieren Docker) comparten una sola base: **usan productos `TEST-<UUID en mayúsculas>`, solo vacían `processed_events` (`TRUNCATE`) y nunca vacían `products` ni modifican los SKU sembrados**, porque `SeedDataIntegrationTest` y `ProductEndpointIntegrationTest` dependen de la semilla intacta.
- Línea base antes de empezar: `./mvnw test` → 25 pruebas; `./mvnw verify` → 25 + 16 de integración.

## Review Focus

1. **Cuerpos que no son eventos** (`""`, `null`, `[]`, `{}`, texto no JSON, `eventId` que no es UUID, fecha ilegible, `sku` en blanco, `newStock` como texto): siempre `InvalidEventException`, nunca `NullPointerException` ni otra excepción que el listener tomaría por transitoria. Se fija en `StockEventReaderTest` (Tarea 2).
2. **Evento con la misma versión que la guardada o menor** (reentrega tardía, escenario 9): `STALE`, el stock no retrocede y el `eventId` queda registrado. Se fija en `ApplyStockChangeServiceIntegrationTest` (Tarea 3).
3. **SKU desconocido**: error permanente y el `eventId` **no** se registra (así reprocesar la DLQ tras sembrar el producto funciona). Se fija en la Tarea 3.
4. **Fallo al registrar el `eventId`**: el cambio de stock se revierte (ni stock ni latencia a medias). Se fija en la Tarea 3 con `@MockitoSpyBean`.
5. **El mismo `eventId` dos veces a nivel de base**: falla por la clave primaria (no se sobrescribe en silencio). Se fija en `ProcessedEventRepositoryIntegrationTest` (Tarea 1).

---

## Estructura de archivos

| Archivo | Responsabilidad |
|---|---|
| `catalog-service/src/main/resources/db/migration/V3__create_processed_events.sql` | Tabla `processed_events` |
| `.../stockevent/ProcessedEvent.java`, `ProcessedEventRepository.java` | Registro de eventos procesados (entidad `Persistable`) |
| `.../stockevent/InvalidEventException.java`, `UnknownProductException.java` | Errores permanentes |
| `.../stockevent/StockChangedEvent.java` | Evento tolerante y validado (`record`) |
| `.../stockevent/StockEventReader.java` | Bytes → evento válido |
| `.../stockevent/ApplyResult.java`, `ApplyStockChangeService.java` | Aplicación idempotente y atómica |
| `src/test/.../support/ContractAssertions.java` | Agrega `assertMatchesEventSchema` |
| `src/test/.../stockevent/*Test.java` | Pruebas de la funcionalidad |
| `README.md`, spec del Catálogo | Documentación al día |

---

### Task 1: Tabla `processed_events` y su entidad

**Files:**
- Create: `catalog-service/src/main/resources/db/migration/V3__create_processed_events.sql`
- Create: `catalog-service/src/main/java/com/retail/catalog/stockevent/ProcessedEvent.java`
- Create: `catalog-service/src/main/java/com/retail/catalog/stockevent/ProcessedEventRepository.java`
- Test: `catalog-service/src/test/java/com/retail/catalog/stockevent/ProcessedEventRepositoryIntegrationTest.java`

**Interfaces:**
- Consumes: `PostgresIntegrationTest` (5.2).
- Produces:
  - `public class ProcessedEvent implements Persistable<UUID>`: `ProcessedEvent(UUID eventId, Instant processedAt)`, `getEventId(): UUID`, `getProcessedAt(): Instant`. `isNew()` es verdadero hasta que se persiste o se carga, de modo que Spring Data hace INSERT y un `eventId` repetido falla por la clave primaria.
  - `interface ProcessedEventRepository extends JpaRepository<ProcessedEvent, UUID>` (se usan `existsById` y `save`).

- [ ] **Step 1: Escribir la prueba (debe fallar)**

`src/test/java/com/retail/catalog/stockevent/ProcessedEventRepositoryIntegrationTest.java`:

```java
package com.retail.catalog.stockevent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

import com.retail.catalog.support.PostgresIntegrationTest;

@SpringBootTest
class ProcessedEventRepositoryIntegrationTest extends PostgresIntegrationTest {

    private static final Instant PROCESSED_AT = Instant.parse("2026-10-12T10:00:00.000Z");

    @Autowired
    private ProcessedEventRepository processedEventRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void emptyProcessedEvents() {
        // Solo esta tabla se vacía: la semilla de products debe quedar intacta para otras clases de prueba.
        jdbcTemplate.execute("TRUNCATE processed_events");
    }

    @Test
    void persistsAndReadsBackAProcessedEvent() {
        UUID eventId = UUID.randomUUID();

        processedEventRepository.saveAndFlush(new ProcessedEvent(eventId, PROCESSED_AT));

        assertThat(processedEventRepository.existsById(eventId)).isTrue();
        assertThat(processedEventRepository.findById(eventId).orElseThrow().getProcessedAt())
                .isEqualTo(PROCESSED_AT);
    }

    @Test
    void anUnknownEventIdIsNotProcessed() {
        assertThat(processedEventRepository.existsById(UUID.randomUUID())).isFalse();
    }

    /** La clave primaria es la red de seguridad: un eventId repetido falla en vez de sobrescribirse. */
    @Test
    void savingTheSameEventIdTwiceFailsOnThePrimaryKey() {
        UUID eventId = UUID.randomUUID();
        processedEventRepository.saveAndFlush(new ProcessedEvent(eventId, PROCESSED_AT));

        assertThatThrownBy(() -> processedEventRepository.saveAndFlush(
                new ProcessedEvent(eventId, PROCESSED_AT.plusSeconds(1))))
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}
```

- [ ] **Step 2: Ejecutarla y verificar que falla**

Run (desde `catalog-service/`): `./mvnw verify -Dit.test=ProcessedEventRepositoryIntegrationTest -Dtest=NoSuchTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: FAIL de compilación (`ProcessedEvent` no existe).

- [ ] **Step 3: Migración, entidad y repositorio**

`src/main/resources/db/migration/V3__create_processed_events.sql`:

```sql
-- Eventos de Inventario ya procesados: base de la idempotencia del consumidor (deduplicación por eventId).
-- Se escribe en la misma transacción que aplica el evento; la clave primaria es la red de seguridad
-- frente a duplicados simultáneos.
CREATE TABLE processed_events (
    event_id     UUID         PRIMARY KEY,
    processed_at TIMESTAMPTZ  NOT NULL
);
```

`src/main/java/com/retail/catalog/stockevent/ProcessedEvent.java`:

```java
package com.retail.catalog.stockevent;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;

import org.springframework.data.domain.Persistable;

/**
 * Un evento de Inventario ya procesado. El identificador lo asigna el productor ({@code eventId}), así
 * que la entidad implementa {@link Persistable}: Spring Data usa {@code persist} (INSERT) en lugar de
 * {@code merge}, y un {@code eventId} repetido falla por la clave primaria en vez de sobrescribirse en
 * silencio. Esa clave es la red de seguridad de la idempotencia.
 */
@Entity
@Table(name = "processed_events")
public class ProcessedEvent implements Persistable<UUID> {

    @Id
    @Column(name = "event_id")
    private UUID eventId;

    @Column(name = "processed_at", nullable = false)
    private Instant processedAt;

    @Transient
    private boolean newEntity = true;

    /** Constructor exigido por JPA. */
    protected ProcessedEvent() {
    }

    public ProcessedEvent(UUID eventId, Instant processedAt) {
        this.eventId = eventId;
        this.processedAt = processedAt;
    }

    @Override
    public UUID getId() {
        return eventId;
    }

    @Override
    public boolean isNew() {
        return newEntity;
    }

    @PostLoad
    @PostPersist
    void markNotNew() {
        this.newEntity = false;
    }

    public UUID getEventId() {
        return eventId;
    }

    public Instant getProcessedAt() {
        return processedAt;
    }
}
```

`src/main/java/com/retail/catalog/stockevent/ProcessedEventRepository.java`:

```java
package com.retail.catalog.stockevent;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface ProcessedEventRepository extends JpaRepository<ProcessedEvent, UUID> {
}
```

- [ ] **Step 4: Verificar**

Run: `./mvnw verify`
Expected: BUILD SUCCESS, 0 fallos. Cifras de referencia: 25 pruebas rápidas y 19 de integración (16 + 3). `ddl-auto=validate` pasa con la entidad nueva.

- [ ] **Step 5: Commit**

```bash
git add catalog-service/src
git commit -m "feat: add processed_events table and entity for event deduplication

Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>"
```

---

### Task 2: Lectura tolerante y validada del evento

**Files:**
- Create: `catalog-service/src/main/java/com/retail/catalog/stockevent/InvalidEventException.java`
- Create: `catalog-service/src/main/java/com/retail/catalog/stockevent/StockChangedEvent.java`
- Create: `catalog-service/src/main/java/com/retail/catalog/stockevent/StockEventReader.java`
- Modify (test): `catalog-service/src/test/java/com/retail/catalog/support/ContractAssertions.java`
- Test: `catalog-service/src/test/java/com/retail/catalog/stockevent/StockEventReaderTest.java`

**Interfaces:**
- Produces:
  - `class InvalidEventException extends RuntimeException` — `(String)` y `(String, Throwable)`.
  - `record StockChangedEvent(UUID eventId, String sku, Integer newStock, Long stockVersion, String channel, Instant occurredAt)` con `@JsonIgnoreProperties(ignoreUnknown = true)` y las restricciones de Bean Validation. (Los tipos `Integer`/`Long` son envoltorios para detectar campos ausentes.)
  - `@Component class StockEventReader` — constructor `(tools.jackson.databind.json.JsonMapper, jakarta.validation.Validator)`; `StockChangedEvent read(byte[] body)` (lanza `InvalidEventException`).
  - `ContractAssertions.assertMatchesEventSchema(String json)` (lanza `AssertionError` si no cumple `inventory-stock-changed.schema.json`).

- [ ] **Step 1: Escribir la prueba (debe fallar)**

`src/test/java/com/retail/catalog/stockevent/StockEventReaderTest.java`:

```java
package com.retail.catalog.stockevent;

import static com.retail.catalog.support.ContractAssertions.assertMatchesEventSchema;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.core.io.ClassPathResource;

import jakarta.validation.Validation;
import tools.jackson.databind.json.JsonMapper;

class StockEventReaderTest {

    private static final String EXAMPLES = "contracts/events/examples/";

    private final StockEventReader reader = new StockEventReader(JsonMapper.builder().build(),
            Validation.buildDefaultValidatorFactory().getValidator());

    private static byte[] example(String name) throws IOException {
        return new ClassPathResource(EXAMPLES + name).getInputStream().readAllBytes();
    }

    private static byte[] json(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void readsTheContractExample() throws IOException {
        assertMatchesEventSchema(new String(example("valid.json"), StandardCharsets.UTF_8));

        StockChangedEvent event = reader.read(example("valid.json"));

        assertThat(event.eventId()).isEqualTo(UUID.fromString("7f3c9a2e-5b1d-4c8e-9f0a-2d6b8e1c4a73"));
        assertThat(event.sku()).isEqualTo("TENIS-RUNNER-42");
        assertThat(event.newStock()).isEqualTo(4);
        assertThat(event.stockVersion()).isEqualTo(8L);
        assertThat(event.channel()).isEqualTo("POS");
        assertThat(event.occurredAt()).isEqualTo(Instant.parse("2026-10-10T15:04:05.123Z"));
    }

    /** Consumidor tolerante: un campo que el productor agregue después no rompe al Catálogo. */
    @Test
    void toleratesFieldsItDoesNotKnow() throws IOException {
        StockChangedEvent event = reader.read(example("invalid-extra-field.json"));

        assertThat(event.sku()).isEqualTo("TENIS-RUNNER-42");
        assertThat(event.stockVersion()).isEqualTo(13L);
    }

    /**
     * El Catálogo no usa el canal ni exige milisegundos en la hora: lo que el contrato del productor
     * rechaza, el consumidor lo tolera (principio de robustez).
     */
    @ParameterizedTest
    @ValueSource(strings = {"invalid-unknown-channel.json", "invalid-missing-millis.json"})
    void toleratesWhatOnlyTheProducerContractForbids(String name) throws IOException {
        assertThat(reader.read(example(name)).sku()).isEqualTo("TENIS-RUNNER-42");
    }

    @ParameterizedTest
    @ValueSource(strings = {"invalid-negative-stock.json", "invalid-missing-field.json",
            "invalid-zero-version.json"})
    void rejectsEventsWithInvalidOrMissingFields(String name) throws IOException {
        byte[] body = example(name);

        assertThatThrownBy(() -> reader.read(body)).isInstanceOf(InvalidEventException.class);
    }

    @Test
    void rejectsTruncatedJson() throws IOException {
        byte[] body = example("invalid-malformed.txt");

        assertThatThrownBy(() -> reader.read(body)).isInstanceOf(InvalidEventException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "",
            "null",
            "[]",
            "{}",
            "not json",
            "{\"eventId\":\"no-es-un-uuid\",\"sku\":\"A\",\"newStock\":1,\"stockVersion\":1,\"occurredAt\":\"2026-10-10T15:04:05.123Z\"}",
            "{\"eventId\":\"7f3c9a2e-5b1d-4c8e-9f0a-2d6b8e1c4a73\",\"sku\":\"A\",\"newStock\":1,\"stockVersion\":1,\"occurredAt\":\"ayer\"}",
            "{\"eventId\":\"7f3c9a2e-5b1d-4c8e-9f0a-2d6b8e1c4a73\",\"sku\":\"   \",\"newStock\":1,\"stockVersion\":1,\"occurredAt\":\"2026-10-10T15:04:05.123Z\"}",
            "{\"eventId\":\"7f3c9a2e-5b1d-4c8e-9f0a-2d6b8e1c4a73\",\"sku\":\"A\",\"newStock\":\"muchos\",\"stockVersion\":1,\"occurredAt\":\"2026-10-10T15:04:05.123Z\"}"
    })
    void rejectsBodiesThatAreNotUsableEvents(String body) {
        assertThatThrownBy(() -> reader.read(json(body))).isInstanceOf(InvalidEventException.class);
    }

    @Test
    void theErrorMessageNamesTheOffendingFields() throws IOException {
        byte[] body = example("invalid-negative-stock.json");

        assertThatThrownBy(() -> reader.read(body))
                .isInstanceOf(InvalidEventException.class)
                .hasMessageContaining("newStock");
    }
}
```

- [ ] **Step 2: Ejecutarla y verificar que falla**

Run: `./mvnw test -Dtest=StockEventReaderTest`
Expected: FAIL de compilación (`StockEventReader`, `StockChangedEvent`, `InvalidEventException` y `assertMatchesEventSchema` no existen).

- [ ] **Step 3: Ayudante de contrato del evento**

En `src/test/java/com/retail/catalog/support/ContractAssertions.java`, junto a la constante `OPENAPI_CONTRACT`, agregar:

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

- [ ] **Step 4: Implementar el evento, el error y el lector**

`src/main/java/com/retail/catalog/stockevent/InvalidEventException.java`:

```java
package com.retail.catalog.stockevent;

/**
 * El mensaje no es un evento utilizable (JSON corrupto o campo faltante o inválido). Es un error
 * <b>permanente</b>: reintentar no lo arregla, así que el consumidor lo manda a la DLQ.
 */
public class InvalidEventException extends RuntimeException {

    public InvalidEventException(String message) {
        super(message);
    }

    public InvalidEventException(String message, Throwable cause) {
        super(message, cause);
    }
}
```

`src/main/java/com/retail/catalog/stockevent/StockChangedEvent.java`:

```java
package com.retail.catalog.stockevent;

import java.time.Instant;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Evento {@code InventoryStockChanged} tal como lo lee el Catálogo. <b>Consumidor tolerante:</b> ignora
 * las propiedades que no necesita ({@code eventType}, campos nuevos del productor). Solo se valida lo
 * que el Catálogo usa; {@code channel} es informativo (para el log) y no se valida.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record StockChangedEvent(
        @NotNull UUID eventId,
        @NotBlank @Size(max = 64) String sku,
        @NotNull @Min(0) Integer newStock,
        @NotNull @Min(1) Long stockVersion,
        String channel,
        @NotNull Instant occurredAt) {
}
```

`src/main/java/com/retail/catalog/stockevent/StockEventReader.java`:

```java
package com.retail.catalog.stockevent;

import java.util.Set;
import java.util.stream.Collectors;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;

import org.springframework.stereotype.Component;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/**
 * Convierte el cuerpo de un mensaje en un {@link StockChangedEvent} válido. No conoce RabbitMQ: recibe
 * los bytes. Todo lo que no sea un evento utilizable termina en {@link InvalidEventException}.
 */
@Component
public class StockEventReader {

    private final JsonMapper jsonMapper;
    private final Validator validator;

    public StockEventReader(JsonMapper jsonMapper, Validator validator) {
        this.jsonMapper = jsonMapper;
        this.validator = validator;
    }

    /** @throws InvalidEventException si el cuerpo no es JSON válido o incumple las validaciones */
    public StockChangedEvent read(byte[] body) {
        StockChangedEvent event;
        try {
            event = jsonMapper.readValue(body, StockChangedEvent.class);
        } catch (JacksonException exception) {
            throw new InvalidEventException("The message body is not a valid event: " + exception.getOriginalMessage(),
                    exception);
        }
        if (event == null) {
            throw new InvalidEventException("The message body is empty");
        }
        Set<ConstraintViolation<StockChangedEvent>> violations = validator.validate(event);
        if (!violations.isEmpty()) {
            String details = violations.stream()
                    .map(violation -> violation.getPropertyPath() + " " + violation.getMessage())
                    .sorted()
                    .collect(Collectors.joining("; "));
            throw new InvalidEventException("The event has invalid fields: " + details);
        }
        return event;
    }
}
```

- [ ] **Step 5: Verificar**

Run: `./mvnw test`
Expected: PASS — 43 pruebas rápidas (25 + 18 de `StockEventReaderTest`: 1 + 1 + 2 + 3 + 1 + 9 + 1). `ArchitectureTest` sigue en verde.

- [ ] **Step 6: Commit**

```bash
git add catalog-service/src
git commit -m "feat: add tolerant and validated stock event reader

Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>"
```

---

### Task 3: `ApplyStockChangeService`

**Files:**
- Create: `catalog-service/src/main/java/com/retail/catalog/stockevent/UnknownProductException.java`
- Create: `catalog-service/src/main/java/com/retail/catalog/stockevent/ApplyResult.java`
- Create: `catalog-service/src/main/java/com/retail/catalog/stockevent/ApplyStockChangeService.java`
- Test: `catalog-service/src/test/java/com/retail/catalog/stockevent/ApplyStockChangeServiceIntegrationTest.java`

**Interfaces:**
- Consumes: `StockChangedEvent` (Tarea 2), `ProcessedEvent`/`ProcessedEventRepository` (Tarea 1), `Product#applyStock`, `Product#getStockVersion()`, `ProductRepository` (5.2), `Clock`.
- Produces:
  - `class UnknownProductException extends RuntimeException` — `UnknownProductException(String sku)`.
  - `enum ApplyResult { APPLIED, DUPLICATE, STALE }`.
  - `@Service ApplyStockChangeService` — constructor `(ProductRepository, ProcessedEventRepository, Clock)`; `@Transactional ApplyResult apply(StockChangedEvent event)`.

- [ ] **Step 1: Escribir la prueba de integración (debe fallar)**

`src/test/java/com/retail/catalog/stockevent/ApplyStockChangeServiceIntegrationTest.java`:

```java
package com.retail.catalog.stockevent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import com.retail.catalog.product.Product;
import com.retail.catalog.product.ProductRepository;
import com.retail.catalog.support.PostgresIntegrationTest;

/**
 * El servicio de aplicación contra PostgreSQL real. Usa productos {@code TEST-<UUID>} y solo vacía
 * {@code processed_events}: la semilla de {@code products} debe quedar intacta para otras clases.
 */
@SpringBootTest
class ApplyStockChangeServiceIntegrationTest extends PostgresIntegrationTest {

    private static final Instant NOW = Instant.parse("2026-10-12T12:00:00.500Z");
    private static final Instant OCCURRED_AT = Instant.parse("2026-10-12T12:00:00.100Z");

    @Autowired
    private ApplyStockChangeService service;

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @MockitoBean
    private Clock clock;

    @MockitoSpyBean
    private ProcessedEventRepository processedEventRepository;

    private String sku;

    @BeforeEach
    void createATestProduct() {
        jdbcTemplate.execute("TRUNCATE processed_events");
        reset(processedEventRepository);
        when(clock.instant()).thenReturn(NOW);
        sku = "TEST-" + UUID.randomUUID().toString().toUpperCase();
        productRepository.saveAndFlush(new Product(sku, "Producto de prueba", "Descripción", 1000, "COP", 5,
                Instant.parse("2026-10-12T10:00:00.000Z")));
    }

    @AfterEach
    void deleteTheTestProduct() {
        productRepository.deleteById(sku);
    }

    private StockChangedEvent event(UUID eventId, int newStock, long stockVersion) {
        return new StockChangedEvent(eventId, sku, newStock, stockVersion, "POS", OCCURRED_AT);
    }

    private Product stored() {
        return productRepository.findById(sku).orElseThrow();
    }

    @Test
    void aNewerEventIsAppliedWithItsLatency() {
        UUID eventId = UUID.randomUUID();

        ApplyResult result = service.apply(event(eventId, 3, 2));

        assertThat(result).isEqualTo(ApplyResult.APPLIED);
        Product product = stored();
        assertThat(product.getAvailableStock()).isEqualTo(3);
        assertThat(product.getStockVersion()).isEqualTo(2L);
        assertThat(product.getUpdatedAt()).isEqualTo(NOW);
        assertThat(product.getLastEventLatencyMs()).isEqualTo(400L);
        assertThat(processedEventRepository.existsById(eventId)).isTrue();
    }

    @Test
    void theSameEventTwiceIsAppliedOnlyOnce() {
        UUID eventId = UUID.randomUUID();
        service.apply(event(eventId, 3, 2));
        when(clock.instant()).thenReturn(NOW.plusSeconds(60));

        ApplyResult result = service.apply(event(eventId, 3, 2));

        assertThat(result).isEqualTo(ApplyResult.DUPLICATE);
        assertThat(stored().getUpdatedAt()).isEqualTo(NOW);
    }

    @Test
    void anEventWithTheStoredVersionOrLowerIsStaleButItsIdIsRecorded() {
        service.apply(event(UUID.randomUUID(), 3, 5));
        UUID sameVersion = UUID.randomUUID();
        UUID olderVersion = UUID.randomUUID();

        assertThat(service.apply(event(sameVersion, 99, 5))).isEqualTo(ApplyResult.STALE);
        assertThat(service.apply(event(olderVersion, 99, 4))).isEqualTo(ApplyResult.STALE);

        Product product = stored();
        assertThat(product.getAvailableStock()).isEqualTo(3);
        assertThat(product.getStockVersion()).isEqualTo(5L);
        assertThat(processedEventRepository.existsById(sameVersion)).isTrue();
        assertThat(processedEventRepository.existsById(olderVersion)).isTrue();
    }

    /** Escenario 9: el evento de la versión 3 llega antes que el de la 2; el estado final es el de la 3. */
    @Test
    void anOutOfOrderEventDoesNotRollTheStockBack() {
        service.apply(event(UUID.randomUUID(), 2, 3));

        ApplyResult late = service.apply(event(UUID.randomUUID(), 4, 2));

        assertThat(late).isEqualTo(ApplyResult.STALE);
        assertThat(stored().getAvailableStock()).isEqualTo(2);
        assertThat(stored().getStockVersion()).isEqualTo(3L);
    }

    @Test
    void anUnknownSkuIsAPermanentErrorAndItsEventIdIsNotRecorded() {
        UUID eventId = UUID.randomUUID();
        StockChangedEvent unknown = new StockChangedEvent(eventId, "TEST-NO-EXISTE", 3, 2L, "POS", OCCURRED_AT);

        assertThatThrownBy(() -> service.apply(unknown)).isInstanceOf(UnknownProductException.class);

        assertThat(processedEventRepository.existsById(eventId)).isFalse();
    }

    @Test
    void ifRecordingTheEventFailsTheStockChangeIsRolledBack() {
        UUID eventId = UUID.randomUUID();
        doThrow(new IllegalStateException("processed_events unavailable")).when(processedEventRepository)
                .save(any());

        assertThatThrownBy(() -> service.apply(event(eventId, 3, 2))).isInstanceOf(IllegalStateException.class);

        Product product = stored();
        assertThat(product.getAvailableStock()).isEqualTo(5);
        assertThat(product.getStockVersion()).isZero();
        assertThat(product.getLastEventLatencyMs()).isNull();
        reset(processedEventRepository);
        assertThat(processedEventRepository.existsById(eventId)).isFalse();
    }

    @Test
    void aStockOfZeroIsApplied() {
        ApplyResult result = service.apply(event(UUID.randomUUID(), 0, 2));

        assertThat(result).isEqualTo(ApplyResult.APPLIED);
        assertThat(stored().getAvailableStock()).isZero();
    }
}
```

- [ ] **Step 2: Ejecutarla y verificar que falla**

Run: `./mvnw verify -Dit.test=ApplyStockChangeServiceIntegrationTest -Dtest=NoSuchTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: FAIL de compilación (`ApplyStockChangeService`, `ApplyResult` y `UnknownProductException` no existen).

- [ ] **Step 3: Implementar**

`src/main/java/com/retail/catalog/stockevent/UnknownProductException.java`:

```java
package com.retail.catalog.stockevent;

/**
 * El evento trae un SKU que el Catálogo no conoce. Es un error <b>permanente</b> (el mensaje va a la
 * DLQ); si luego se siembra el producto, reprocesar la DLQ funciona porque el {@code eventId} no se
 * registró.
 */
public class UnknownProductException extends RuntimeException {

    public UnknownProductException(String sku) {
        super("Event refers to unknown SKU " + sku);
    }
}
```

`src/main/java/com/retail/catalog/stockevent/ApplyResult.java`:

```java
package com.retail.catalog.stockevent;

/** Qué hizo {@link ApplyStockChangeService} con un evento. Todos los resultados se confirman (ack). */
public enum ApplyResult {
    /** El evento era nuevo y más reciente: se aplicó el stock. */
    APPLIED,
    /** El {@code eventId} ya se había procesado: no se hizo nada. */
    DUPLICATE,
    /** El evento es viejo o llegó fuera de orden ({@code stockVersion} no mayor que la guardada): no cambió el stock. */
    STALE
}
```

`src/main/java/com/retail/catalog/stockevent/ApplyStockChangeService.java`:

```java
package com.retail.catalog.stockevent;

import java.time.Clock;
import java.time.Instant;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.retail.catalog.product.Product;
import com.retail.catalog.product.ProductRepository;

/**
 * Aplica un evento de Inventario a la vista de disponibilidad. Todo ocurre en una sola transacción: si
 * algo falla, ni el stock ni el registro del {@code eventId} quedan a medias. No conoce RabbitMQ.
 */
@Service
public class ApplyStockChangeService {

    private static final Logger LOGGER = LoggerFactory.getLogger(ApplyStockChangeService.class);

    private final ProductRepository productRepository;
    private final ProcessedEventRepository processedEventRepository;
    private final Clock clock;

    public ApplyStockChangeService(ProductRepository productRepository,
            ProcessedEventRepository processedEventRepository, Clock clock) {
        this.productRepository = productRepository;
        this.processedEventRepository = processedEventRepository;
        this.clock = clock;
    }

    /**
     * @throws UnknownProductException si el SKU no existe (error permanente; no registra el {@code eventId})
     */
    @Transactional
    public ApplyResult apply(StockChangedEvent event) {
        try (MDC.MDCCloseable skuKey = MDC.putCloseable("sku", event.sku());
                MDC.MDCCloseable eventIdKey = MDC.putCloseable("eventId", event.eventId().toString())) {
            if (processedEventRepository.existsById(event.eventId())) {
                LOGGER.info("event_duplicate sku={} eventId={}", event.sku(), event.eventId());
                return ApplyResult.DUPLICATE;
            }
            Product product = productRepository.findById(event.sku())
                    .orElseThrow(() -> new UnknownProductException(event.sku()));
            Instant now = clock.instant();
            if (event.stockVersion() <= product.getStockVersion()) {
                processedEventRepository.save(new ProcessedEvent(event.eventId(), now));
                LOGGER.info("event_stale sku={} eventId={} eventVersion={} storedVersion={}", event.sku(),
                        event.eventId(), event.stockVersion(), product.getStockVersion());
                return ApplyResult.STALE;
            }
            int previousStock = product.getAvailableStock();
            product.applyStock(event.newStock(), event.stockVersion(), event.occurredAt(), now);
            processedEventRepository.save(new ProcessedEvent(event.eventId(), now));
            LOGGER.info("event_applied sku={} eventId={} previous={} new={} version={} latencyMs={}", event.sku(),
                    event.eventId(), previousStock, product.getAvailableStock(), product.getStockVersion(),
                    product.getLastEventLatencyMs());
            return ApplyResult.APPLIED;
        }
    }
}
```

- [ ] **Step 4: Verificar todo**

Run: `./mvnw verify`
Expected: BUILD SUCCESS, 0 fallos. Cifras de referencia: 43 pruebas rápidas y 26 de integración (19 + 7).

- [ ] **Step 5: Mutaciones de comprobación (no se commitean)**

1. En `ApplyStockChangeService`, cambiar `event.stockVersion() <= product.getStockVersion()` por `event.stockVersion() < product.getStockVersion()` y ejecutar `./mvnw verify -Dit.test=ApplyStockChangeServiceIntegrationTest -Dtest=NoSuchTest -Dsurefire.failIfNoSpecifiedTests=false`: debe FALLAR `anEventWithTheStoredVersionOrLowerIsStaleButItsIdIsRecorded` (la guarda es estricta). Restaurar.
2. Quitar `@Transactional` de `apply` y ejecutar lo mismo: deben fallar las pruebas de aplicación (el cambio ya no se persiste). Restaurar.
Confirmar con `git status` que no quedan cambios de esas mutaciones.

- [ ] **Step 6: Commit**

```bash
git add catalog-service/src
git commit -m "feat: add idempotent atomic application of stock events

Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>"
```

---

### Task 4: Documentación y verificación de punta a punta

**Files:**
- Modify: `README.md`
- Modify: `docs/superpowers/specs/2026-10-12-catalogo-design.md`

**Interfaces:**
- Consumes: todo lo anterior.
- Produces: documentación al día y la comprobación de que la migración `V3` se aplica sobre la base real de Compose.

- [ ] **Step 1: Spec del Catálogo**

En `docs/superpowers/specs/2026-10-12-catalogo-design.md` (leer antes el texto exacto, español, mismo estilo):
1. §3 árbol de paquetes: agregar `StockEventReader` a la línea de `stockevent/` (`StockChangedEvent, StockEventReader, StockEventListener, ApplyStockChangeService, ApplyResult, ...`).
2. §6 "Evento (`StockChangedEvent`)": agregar al final: "La lectura la hace `StockEventReader` (recibe los bytes del cuerpo, usa Jackson y Bean Validation y no conoce RabbitMQ); el `record` lleva `@JsonIgnoreProperties(ignoreUnknown = true)`, de modo que la tolerancia no depende de la configuración global de Jackson. `channel` es informativo y no se valida."
3. §7 "Contrato del evento": reemplazar la frase "acepta `valid.json`, tolera un campo extra y rechaza los inválidos" por: "acepta `valid.json`; tolera `invalid-extra-field.json` (propiedad desconocida) y también `invalid-unknown-channel.json` e `invalid-missing-millis.json` (el Catálogo no usa el canal ni exige milisegundos: tolerancia deliberada del consumidor); y rechaza `invalid-negative-stock.json`, `invalid-missing-field.json`, `invalid-zero-version.json` y el JSON truncado".
4. §7 "Pruebas": en la frase "Las clases que dependen de filas ajenas las vacían (`TRUNCATE`) en `@BeforeEach`", precisar: "cada clase vacía solo la tabla que le corresponde (por ejemplo `processed_events`), nunca `products` ni los SKU sembrados (ver la regla de 5.3 y 5.4)".
5. §7 tabla de sub-tareas, fila **5.3**: agregar al inicio de la entrega "`StockEventReader`,".

- [ ] **Step 2: README**

En la sección "Servicio de Catálogo", después del párrafo que explica que la consulta ya está disponible, agregar:

```markdown
La aplicación de eventos (deduplicación por `eventId`, descarte de versiones viejas y cálculo de la latencia)
ya está implementada y probada contra PostgreSQL, pero todavía no se alimenta de RabbitMQ: el consumidor
llega en la siguiente sub-tarea.
```

(Mantener el estilo y el ancho de línea del README; leer antes el texto exacto para insertarlo después del párrafo correcto.)

- [ ] **Step 3: Verificación de punta a punta**

Con los puertos por defecto libres (`docker compose ps`; si hay un stack `retail` en marcha, `docker compose down -v` antes), desde la raíz del repositorio y cada comando por separado:

```bash
cd catalog-service && ./mvnw verify
```
Expected: BUILD SUCCESS, 43 pruebas rápidas y 26 de integración.

```bash
docker compose up -d --wait --build catalog-service
```
Expected: `retail-catalog-service-1 Healthy` (sin `--build` Compose reutilizaría una imagen anterior).

```bash
docker compose exec -T catalog-db psql -U catalog -d catalog -c '\d processed_events' -c "select version, description, success from flyway_schema_history order by installed_rank"
curl -s http://localhost:8082/api/v1/catalog/products/TENIS-RUNNER-42 | jq -c '{sku,availableStock}'
```
Expected: la tabla `processed_events` con `event_id` (uuid, PK) y `processed_at` (timestamptz, no nulo); tres migraciones (`create products`, `seed products`, `create processed events`) con `success = t`; y el producto sembrado intacto (`availableStock` 5).

```bash
docker compose down -v
```

- [ ] **Step 4: Commit**

```bash
git add README.md docs/superpowers/specs/2026-10-12-catalogo-design.md
git commit -m "docs: document stock event reading and application in the catalog

Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>"
```

---

## Autorrevisión

- **Cobertura de la spec (5.3):** `processed_events` con clave primaria como red de seguridad (T1); lectura tolerante y validada, `channel` informativo, JSON corrupto/campos inválidos → permanente (T2); deduplicación, SKU desconocido sin registrar, `STALE` registrado, guarda estricta `>`, aplicación con latencia, transacción única y atomicidad (T3); escenario 9 a nivel de servicio (T3); ejemplos del contrato leídos por el consumidor (T2); regla de pruebas de BD compartida (Restricciones globales y T1/T3).
- **Aclaración de la spec:** la frase "rechaza los inválidos" de §7 era más amplia que el comportamiento deseado de un consumidor tolerante; la T4 la corrige (el Catálogo rechaza solo lo que no puede usar).
- **Consistencia de tipos:** `StockChangedEvent(UUID, String, Integer, Long, String, Instant)`; `StockEventReader#read(byte[])`; `ApplyStockChangeService#apply(StockChangedEvent): ApplyResult`; `ProcessedEvent(UUID, Instant)`; `UnknownProductException(String)`; `InvalidEventException(String[, Throwable])`.
- **Conteos de referencia:** rápidas 25 → 43 (T2); integración 16 → 19 (T1) → 26 (T3).
- **Verificado en una copia temporal** antes de escribir el plan: 43 rápidas + 26 de integración en verde; en Compose, la migración `V3` se aplica (`flyway_schema_history` con tres migraciones exitosas), la tabla `processed_events` queda como en la spec y el producto sembrado sigue intacto.
