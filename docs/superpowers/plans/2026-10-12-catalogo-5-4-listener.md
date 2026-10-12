# Catálogo 5.4: listener de RabbitMQ y cierre de la fase 5 — Plan de implementación

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Que `catalog-service` consuma de verdad los eventos `InventoryStockChanged` de RabbitMQ: un listener con confirmación manual que clasifica los fallos (permanentes → DLQ, transitorios → reintento con espera creciente), y una prueba de humo que demuestre en Compose el flujo venta → Catálogo.

**Architecture:** `StockEventListener` es la única clase que conoce RabbitMQ: lee el cuerpo con `StockEventReader`, aplica con `ApplyStockChangeService` y hace `basicAck`/`basicNack`. Un solo consumidor con prefetch 1 (configurado en `application.yml`) conserva el orden y hace segura la aplicación sin bloqueos. La espera creciente (`BackoffPolicy`) toma sus tiempos de `CatalogProperties`. Spring AMQP solo entrega mensajes: no hay interceptores de reintento (spec §8).

**Tech Stack:** Spring Boot 4.1.1 (`spring-boot-starter-amqp`, Spring AMQP, cliente `com.rabbitmq`), Testcontainers 2 (`testcontainers-rabbitmq`, `rabbitmq:4.3-management` con `infra/rabbitmq/definitions.json`), Awaitility (incluido en el starter de pruebas), Mockito, ArchUnit.

**Spec:** `docs/superpowers/specs/2026-10-12-catalogo-design.md` (secciones 3, 4, 6, 7 y Pendientes). Topología: `infra/rabbitmq/definitions.json` (el servicio **no declara** colas ni exchanges).

## Global Constraints

- Spring Boot `4.1.1`, Java `25`, Maven solo con el wrapper `./mvnw` desde `catalog-service/`. Paquete base `com.retail.catalog`. La ruta del repositorio contiene espacios: toda ruta en comandos va entre comillas dobles.
- **Nombres completos, sin abreviaturas** (salvo `SKU`, `JWT`, `UUID`, `DTO`, `HTTP`, `JSON`, `DLQ` y unidades como `ms`). Código en inglés (incluidas propiedades, colas y logs); comentarios, Javadoc, comentarios YAML/SQL y documentación en español.
- Commits en inglés, breves, Conventional Commits, con la línea `Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>`.
- Cola `catalog.inventory-stock-changed`, exchange de letra muerta `catalog.dlx`, DLQ `catalog.inventory-stock-changed.dlq` (las declara la infraestructura). Propiedades: `catalog.consumer.queue`, `catalog.consumer.initial-backoff` (1 s), `catalog.consumer.maximum-backoff` (30 s).
- Listener: `acknowledge-mode: manual`, `concurrency: 1`, `prefetch: 1`, `missing-queues-fatal: false`, arranque automático. Lee los bytes del cuerpo y los interpreta con Jackson (no depende del `content_type`).
- Clasificación: `InvalidEventException` y `UnknownProductException` → `basicNack(requeue=false)` (a la DLQ por `catalog.dlx`) y `WARN event_rejected reason=…`; cualquier otra excepción (incluida `DataIntegrityViolationException`) es **transitoria**: reintento dentro del listener con espera 1 s, 2 s, 4 s… hasta 30 s, **sin límite de intentos**, sin confirmar, y nunca va a la DLQ por esa causa; interrupción del hilo (apagado) → dejar de reintentar **sin confirmar ni rechazar** (el mensaje vuelve a la cola).
- `management.health.rabbit.enabled=false`: sin broker el servicio sigue sano, sirve consultas y el listener se reconecta solo.
- ArchUnit regla 2: solo `StockEventListener` (por **nombre completo**) usa `org.springframework.amqp` y `com.rabbitmq`.
- Pruebas de integración (`*IntegrationTest`, `./mvnw verify`, requieren Docker) comparten una base: **usan productos `TEST-<UUID en mayúsculas>`, solo vacían `processed_events` (`TRUNCATE`) y nunca vacían `products` ni tocan los SKU sembrados**. Un contexto con el listener vivo usa `@DirtiesContext(AFTER_CLASS)`.
- Línea base antes de empezar: `./mvnw test` → 48 pruebas rápidas; `./mvnw verify` → 48 + 26 de integración.

## Review Focus

1. **Mensajes que nunca podrán procesarse** (JSON corrupto, campo faltante, SKU desconocido): deben terminar en la DLQ, sin reencolarse en bucle y sin registrar el `eventId` del SKU desconocido. Se fija en `StockEventListenerTest` (Tarea 3) y `StockEventListenerIntegrationTest` (Tarea 4).
2. **Fallo transitorio** (base caída, carrera por clave duplicada): reintenta sin perder el mensaje, sin DLQ, y los eventos siguientes esperan detrás (orden). Se fija en la Tarea 3 (unitaria) y la Tarea 4 (reintento real y escenario 5 con orden verificado).
3. **Apagar el consumidor en pleno reintento**: el hilo se interrumpe, deja de reintentar sin confirmar y el mensaje sigue en la cola. Se fija en la Tarea 3 (con `@Timeout`, para que no cuelgue) y la Tarea 4 (el log `event_retry_interrupted` y la cola con 1 mensaje).
4. **RabbitMQ caído**: el servicio sigue sano (`/actuator/health` UP), sirve consultas y el listener se reconecta al volver. Se fija en la Tarea 4.
5. **Duplicado y fuera de orden** (escenario 9): el mismo mensaje dos veces se aplica una vez; la versión 3 antes que la 2 deja la 3. Se fija en la Tarea 4.
6. **Configuración peligrosa** (`concurrency`/`prefetch` distintos de 1, backoff incoherente, cola vacía): el servicio no arranca con valores incoherentes (Tarea 1) y una prueba fija 1/1/manual en el contenedor real (Tarea 4).

---

## Estructura de archivos

| Archivo | Responsabilidad |
|---|---|
| `catalog-service/pom.xml` | Starter AMQP, `testcontainers-rabbitmq` y copia de la topología para las pruebas |
| `src/main/resources/application.yml` | RabbitMQ, listener (ack manual, 1 consumidor, prefetch 1), `catalog.consumer.*`, salud |
| `src/test/resources/application.properties` | Listener apagado por defecto en las pruebas |
| `.../config/CatalogProperties.java` | Propiedades validadas del consumidor |
| `.../stockevent/BackoffPolicy.java` | Espera creciente con tope, sin límite de intentos |
| `.../stockevent/ApplyStockChangeService.java` | (modifica) el MDC pasa al listener; `event_applied`/`event_stale` se registran tras el commit |
| `.../stockevent/StockEventListener.java` | Único consumidor de RabbitMQ: ack/nack y reintento |
| `src/test/.../support/RabbitIntegrationTest.java` | Base con RabbitMQ real y la topología de `infra/` |
| `src/test/.../stockevent/*` | Pruebas del listener, la política y las propiedades |
| `scripts/smoke-catalog-service.sh` | Prueba de humo venta → Catálogo en Compose (cierre de la fase 5) |
| `README.md`, `CLAUDE.md`, spec, `scripts/smoke-inventory-service.sh` | Documentación al día |

---

### Task 1: Dependencias, configuración y política de espera

**Files:**
- Modify: `catalog-service/pom.xml`
- Modify: `catalog-service/src/main/resources/application.yml`
- Create: `catalog-service/src/test/resources/application.properties`
- Create: `catalog-service/src/main/java/com/retail/catalog/config/CatalogProperties.java`
- Create: `catalog-service/src/main/java/com/retail/catalog/stockevent/BackoffPolicy.java`
- Test: `catalog-service/src/test/java/com/retail/catalog/config/CatalogPropertiesTest.java`
- Test: `catalog-service/src/test/java/com/retail/catalog/stockevent/BackoffPolicyTest.java`

**Interfaces:**
- Produces:
  - `record CatalogProperties(Consumer consumer)` (`@ConfigurationProperties("catalog")`, `@Validated`; `@ConfigurationPropertiesScan` ya está en `CatalogApplication`) con `record Consumer(String queue, Duration initialBackoff, Duration maximumBackoff)`.
  - `@Component class BackoffPolicy` — constructor inyectable `(CatalogProperties)` y constructor `(Duration initialBackoff, Duration maximumBackoff)`; `Duration delayAfter(int attempt)` (el primer intento es 1; lanza `IllegalArgumentException` si es menor que 1).

- [ ] **Step 1: Escribir las pruebas (deben fallar)**

`src/test/java/com/retail/catalog/stockevent/BackoffPolicyTest.java`:

```java
package com.retail.catalog.stockevent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class BackoffPolicyTest {

    private final BackoffPolicy policy = new BackoffPolicy(Duration.ofSeconds(1), Duration.ofSeconds(30));

    @ParameterizedTest
    @CsvSource({"1,1", "2,2", "3,4", "4,8", "5,16", "6,30", "7,30", "50,30"})
    void doublesTheDelayUpToTheMaximum(int attempt, long expectedSeconds) {
        assertThat(policy.delayAfter(attempt)).isEqualTo(Duration.ofSeconds(expectedSeconds));
    }

    /** No hay límite de intentos: un número enorme de intentos sigue dando el tope, sin desbordar. */
    @Test
    void neverGivesUpNorOverflows() {
        assertThat(policy.delayAfter(Integer.MAX_VALUE)).isEqualTo(Duration.ofSeconds(30));
    }

    @Test
    void aMaximumEqualToTheInitialDelayKeepsItConstant() {
        BackoffPolicy constant = new BackoffPolicy(Duration.ofMillis(50), Duration.ofMillis(50));

        assertThat(constant.delayAfter(1)).isEqualTo(Duration.ofMillis(50));
        assertThat(constant.delayAfter(9)).isEqualTo(Duration.ofMillis(50));
    }

    @Test
    void attemptsStartAtOne() {
        assertThatThrownBy(() -> policy.delayAfter(0)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsIncoherentConfiguration() {
        assertThatThrownBy(() -> new BackoffPolicy(Duration.ZERO, Duration.ofSeconds(1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new BackoffPolicy(Duration.ofSeconds(2), Duration.ofSeconds(1)))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
```

`src/test/java/com/retail/catalog/config/CatalogPropertiesTest.java`:

```java
package com.retail.catalog.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.validation.autoconfigure.ValidationAutoConfiguration;
import org.springframework.context.annotation.Configuration;

class CatalogPropertiesTest {

    @Configuration
    @EnableConfigurationProperties(CatalogProperties.class)
    static class PropertiesConfiguration {
    }

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(ValidationAutoConfiguration.class))
            .withUserConfiguration(PropertiesConfiguration.class);

    private static final String[] VALID = { "catalog.consumer.queue=catalog.inventory-stock-changed",
            "catalog.consumer.initial-backoff=1s", "catalog.consumer.maximum-backoff=30s" };

    @Test
    void bindsTheConsumerProperties() {
        runner.withPropertyValues(VALID).run(context -> {
            CatalogProperties.Consumer consumer = context.getBean(CatalogProperties.class).consumer();
            assertThat(consumer.queue()).isEqualTo("catalog.inventory-stock-changed");
            assertThat(consumer.initialBackoff()).isEqualTo(Duration.ofSeconds(1));
            assertThat(consumer.maximumBackoff()).isEqualTo(Duration.ofSeconds(30));
        });
    }

    @Test
    void failsToStartWithoutTheConsumerSection() {
        runner.run(context -> assertThat(context).hasFailed());
    }

    @Test
    void failsToStartWithABlankQueue() {
        runner.withPropertyValues(VALID).withPropertyValues("catalog.consumer.queue= ")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void failsToStartWithANonPositiveInitialBackoff() {
        runner.withPropertyValues(VALID).withPropertyValues("catalog.consumer.initial-backoff=0s")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void failsToStartWhenTheMaximumIsShorterThanTheInitialBackoff() {
        runner.withPropertyValues(VALID).withPropertyValues("catalog.consumer.maximum-backoff=500ms")
                .run(context -> assertThat(context).hasFailed());
    }
}
```

- [ ] **Step 2: Ejecutarlas y verificar que fallan**

Run (desde `catalog-service/`): `./mvnw test -Dtest='BackoffPolicyTest,CatalogPropertiesTest'`
Expected: FAIL de compilación (`BackoffPolicy` y `CatalogProperties` no existen).

- [ ] **Step 3: Dependencias en `pom.xml`**

En `<dependencies>`, justo después de `flyway-database-postgresql`:

```xml
		<dependency>
			<groupId>org.springframework.boot</groupId>
			<artifactId>spring-boot-starter-amqp</artifactId>
		</dependency>
```

Justo después de la dependencia `testcontainers-postgresql` (scope `test`):

```xml
		<dependency>
			<groupId>org.testcontainers</groupId>
			<artifactId>testcontainers-rabbitmq</artifactId>
			<scope>test</scope>
		</dependency>
```

En `maven-resources-plugin`, como última `<execution>` (después de `copy-seed-data-for-tests`):

```xml
					<!-- La topología real de RabbitMQ (definitions.json) se carga en el broker de las pruebas -->
					<execution>
						<id>copy-rabbitmq-topology-for-tests</id>
						<phase>process-test-resources</phase>
						<goals>
							<goal>copy-resources</goal>
						</goals>
						<configuration>
							<outputDirectory>${project.build.testOutputDirectory}/infra/rabbitmq</outputDirectory>
							<resources>
								<resource>
									<directory>${project.basedir}/../infra/rabbitmq</directory>
									<includes>
										<include>definitions.json</include>
										<include>rabbitmq.conf</include>
									</includes>
								</resource>
							</resources>
						</configuration>
					</execution>
```

- [ ] **Step 4: Configuración**

`src/main/resources/application.yml` queda **exactamente** así (agrega `rabbitmq`, `catalog.consumer` y `management.health.rabbit`; el resto no cambia):

```yaml
spring:
  application:
    name: catalog-service
  datasource:
    # Valores para correr desde el IDE; Compose los sobrescribe con SPRING_DATASOURCE_*.
    url: jdbc:postgresql://localhost:5442/catalog
    username: catalog
    password: catalog-demo
  jpa:
    open-in-view: false
    hibernate:
      # Flyway es el único dueño del esquema: una entidad desalineada impide el arranque.
      ddl-auto: validate
  rabbitmq:
    # Valores para correr desde el IDE; Compose los sobrescribe con SPRING_RABBITMQ_*.
    host: localhost
    port: 5672
    username: retail
    password: retail-demo
    # Una caída de RabbitMQ no debe bloquear el arranque ni las consultas.
    connection-timeout: 2s
    listener:
      simple:
        # Ack manual: el listener confirma o rechaza cada mensaje explícitamente.
        acknowledge-mode: manual
        # UN solo consumidor con prefetch 1: la corrección de ApplyStockChangeService (no bloquea la fila del
        # producto) y el orden por producto dependen de que los eventos se apliquen uno a uno. No cambiar.
        concurrency: 1
        max-concurrency: 1
        prefetch: 1
        # El servicio arranca aunque RabbitMQ o la cola no estén disponibles y consume cuando aparezcan.
        missing-queues-fatal: false
        auto-startup: true

catalog:
  consumer:
    # La cola y su cola de mensajes rechazados (DLQ) las declara la infraestructura (infra/rabbitmq).
    queue: catalog.inventory-stock-changed
    # Espera creciente al reintentar un fallo transitorio: 1 s, 2 s, 4 s... hasta el tope.
    initial-backoff: 1s
    maximum-backoff: 30s

server:
  port: 8082

management:
  health:
    rabbit:
      # Una caída de RabbitMQ no debe marcar el servicio como no sano: las consultas siguen funcionando
      # y el listener se reconecta solo.
      enabled: false
  endpoints:
    web:
      exposure:
        include: health

# Swagger UI muestra el contrato escrito a mano (contrato primero), no el generado desde el código.
springdoc:
  swagger-ui:
    url: /openapi/catalog-service.yaml

logging:
  pattern:
    # Claves del MDC para seguir un evento de punta a punta.
    level: "%5p [sku=%X{sku:-}] [eventId=%X{eventId:-}]"
```

`src/test/resources/application.properties` (archivo nuevo):

```properties
# El listener de RabbitMQ se apaga por defecto en las pruebas (la mayoría solo usa PostgreSQL y no hay broker);
# StockEventListenerIntegrationTest lo enciende con su propio valor.
spring.rabbitmq.listener.simple.auto-startup=false
# Al detener RabbitMQ a propósito, el cliente AMQP registra cada conexión caída; no aporta nada a las pruebas.
logging.level.com.rabbitmq.client.impl.ForgivingExceptionHandler=ERROR
```

- [ ] **Step 5: Implementar**

`src/main/java/com/retail/catalog/config/CatalogProperties.java`:

```java
package com.retail.catalog.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * Propiedades propias del Catálogo (prefijo {@code catalog}). Sus valores viven en {@code application.yml}
 * y se validan al arrancar: un valor ausente o incoherente impide iniciar el servicio.
 */
@ConfigurationProperties("catalog")
@Validated
public record CatalogProperties(@NotNull @Valid Consumer consumer) {

    /**
     * Consumo de eventos de Inventario.
     *
     * @param queue          cola de la que lee el listener (la crea la infraestructura, no el servicio)
     * @param initialBackoff primera espera al reintentar un fallo transitorio
     * @param maximumBackoff tope de la espera creciente
     */
    public record Consumer(@NotBlank String queue, @NotNull Duration initialBackoff,
            @NotNull Duration maximumBackoff) {

        @AssertTrue(message = "initial-backoff must be positive")
        boolean isInitialBackoffPositive() {
            return initialBackoff == null || (!initialBackoff.isNegative() && !initialBackoff.isZero());
        }

        @AssertTrue(message = "maximum-backoff must not be shorter than initial-backoff")
        boolean isMaximumBackoffAtLeastInitialBackoff() {
            return initialBackoff == null || maximumBackoff == null || maximumBackoff.compareTo(initialBackoff) >= 0;
        }
    }
}
```

`src/main/java/com/retail/catalog/stockevent/BackoffPolicy.java`:

```java
package com.retail.catalog.stockevent;

import java.time.Duration;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.retail.catalog.config.CatalogProperties;

/**
 * Espera creciente entre reintentos de un fallo transitorio: la primera espera es {@code initialBackoff}
 * y cada intento la duplica hasta {@code maximumBackoff}. No limita el número de intentos: mientras la
 * causa persista (por ejemplo, la base caída) el listener sigue esperando y reintentando.
 */
@Component
public class BackoffPolicy {

    private final Duration initialBackoff;
    private final Duration maximumBackoff;

    @Autowired
    public BackoffPolicy(CatalogProperties properties) {
        this(properties.consumer().initialBackoff(), properties.consumer().maximumBackoff());
    }

    public BackoffPolicy(Duration initialBackoff, Duration maximumBackoff) {
        if (initialBackoff.isNegative() || initialBackoff.isZero()) {
            throw new IllegalArgumentException("initialBackoff must be positive");
        }
        if (maximumBackoff.compareTo(initialBackoff) < 0) {
            throw new IllegalArgumentException("maximumBackoff must not be shorter than initialBackoff");
        }
        this.initialBackoff = initialBackoff;
        this.maximumBackoff = maximumBackoff;
    }

    /** Espera antes de reintentar tras el fallo número {@code attempt} (el primero es 1). */
    public Duration delayAfter(int attempt) {
        if (attempt < 1) {
            throw new IllegalArgumentException("attempt must be at least 1");
        }
        Duration delay = initialBackoff;
        for (int index = 1; index < attempt && delay.compareTo(maximumBackoff) < 0; index++) {
            delay = delay.multipliedBy(2);
        }
        return delay.compareTo(maximumBackoff) > 0 ? maximumBackoff : delay;
    }
}
```

- [ ] **Step 6: Verificar todo**

Run: `./mvnw verify`
Expected: BUILD SUCCESS, 0 fallos. Cifras de referencia: 65 pruebas rápidas (48 + 12 de `BackoffPolicyTest` + 5 de `CatalogPropertiesTest`) y 26 de integración. En particular `CatalogApplicationIntegrationTest.healthEndpointReportsUp` sigue en verde **sin broker** (el indicador de salud de RabbitMQ está desactivado).

- [ ] **Step 7: Commit**

```bash
git add catalog-service/pom.xml catalog-service/src
git commit -m "feat: add AMQP dependency, consumer properties and backoff policy

Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>"
```

---

### Task 2: Registro tras el commit y MDC fuera del servicio

**Files:**
- Modify: `catalog-service/src/main/java/com/retail/catalog/stockevent/ApplyStockChangeService.java`
- Modify (test): `catalog-service/src/test/java/com/retail/catalog/stockevent/ApplyStockChangeServiceIntegrationTest.java`

**Interfaces:**
- Consumes: `ApplyStockChangeService` (5.3).
- Produces: el mismo contrato (`apply(StockChangedEvent): ApplyResult`), pero (a) ya no maneja el MDC —lo hará el listener— y (b) `event_applied` y `event_stale` se registran **después de confirmar la transacción**, de modo que un cambio revertido nunca aparece como aplicado. `event_duplicate` no cambia datos y se registra de inmediato.

- [ ] **Step 1: Escribir las pruebas**

En `ApplyStockChangeServiceIntegrationTest.java` agrega los imports (donde correspondan al orden existente):

```java
import static org.mockito.Mockito.doAnswer;

import jakarta.persistence.EntityManager;

import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.dao.DataIntegrityViolationException;
```

Anota la clase con `@ExtendWith(OutputCaptureExtension.class)` (debajo de `@SpringBootTest`), agrega el campo (junto a `jdbcTemplate`):

```java
    @Autowired
    private EntityManager entityManager;
```

y, antes de la llave final de la clase, estas dos pruebas:

```java

    @Test
    void theAppliedEventIsLoggedWithPreviousAndNewStockAndItsLatency(CapturedOutput output) {
        UUID eventId = UUID.randomUUID();

        service.apply(event(eventId, 3, 2));

        assertThat(output).contains("event_applied sku=" + sku + " eventId=" + eventId
                + " previous=5 new=3 version=2 latencyMs=400");
    }

    /**
     * Carrera por el mismo eventId: otro consumidor lo registra justo antes de que esta transacción confirme. El
     * INSERT falla en el commit con la clave primaria; como el registro se emite después de confirmar, no queda un
     * "aplicado" en el log, y el stock no cambia.
     */
    @Test
    void aDuplicateKeyRaceFailsAtCommitAndIsNeverLoggedAsApplied(CapturedOutput output) {
        UUID eventId = UUID.randomUUID();
        doAnswer(invocation -> {
            jdbcTemplate.update("INSERT INTO processed_events (event_id, processed_at) VALUES (?, now())", eventId);
            // Equivale al save real de una entidad nueva: persist en la transacción del servicio (INSERT al confirmar).
            Object entity = invocation.getArgument(0);
            entityManager.persist(entity);
            return entity;
        }).when(processedEventRepository).save(any());

        assertThatThrownBy(() -> service.apply(event(eventId, 3, 2)))
                .isInstanceOf(DataIntegrityViolationException.class);

        assertThat(output).doesNotContain("event_applied sku=" + sku);
        assertThat(stored().getAvailableStock()).isEqualTo(5);
    }
```

- [ ] **Step 2: Ejecutarlas**

Run: `./mvnw verify -Dit.test=ApplyStockChangeServiceIntegrationTest -Dtest=NoSuchTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: PASS con el código actual de la 5.3 en la primera prueba; la segunda también pasa con el registro previo solo si el log se emite después de confirmar, así que **debe FALLAR** con el código de la 5.3 (que registra antes de confirmar: aparece `event_applied` aunque el commit falle). Anota en el informe qué ocurrió.

- [ ] **Step 3: Implementar**

`src/main/java/com/retail/catalog/stockevent/ApplyStockChangeService.java` queda **exactamente** así:

```java
package com.retail.catalog.stockevent;

import java.time.Clock;
import java.time.Instant;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.retail.catalog.product.Product;
import com.retail.catalog.product.ProductRepository;

/**
 * Aplica un evento de Inventario a la vista de disponibilidad. Todo ocurre en una sola transacción: si
 * algo falla, ni el stock ni el registro del {@code eventId} quedan a medias. No conoce RabbitMQ.
 *
 * <p>Los registros de {@code APPLIED} y {@code STALE} se emiten <b>después de confirmar la transacción</b>:
 * si la confirmación falla no queda en el log un "aplicado" que en realidad se revirtió. El MDC
 * ({@code sku}, {@code eventId}) lo maneja quien invoca (el listener); el mensaje ya repite ambos valores.
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
        if (processedEventRepository.existsById(event.eventId())) {
            LOGGER.info("event_duplicate sku={} eventId={}", event.sku(), event.eventId());
            return ApplyResult.DUPLICATE;
        }
        Product product = productRepository.findById(event.sku())
                .orElseThrow(() -> new UnknownProductException(event.sku()));
        Instant now = clock.instant();
        if (event.stockVersion() <= product.getStockVersion()) {
            processedEventRepository.save(new ProcessedEvent(event.eventId(), now));
            long storedVersion = product.getStockVersion();
            logAfterCommit(() -> LOGGER.info("event_stale sku={} eventId={} eventVersion={} storedVersion={}",
                    event.sku(), event.eventId(), event.stockVersion(), storedVersion));
            return ApplyResult.STALE;
        }
        int previousStock = product.getAvailableStock();
        product.applyStock(event.newStock(), event.stockVersion(), event.occurredAt(), now);
        processedEventRepository.save(new ProcessedEvent(event.eventId(), now));
        int newStock = product.getAvailableStock();
        Long latencyMs = product.getLastEventLatencyMs();
        logAfterCommit(() -> LOGGER.info("event_applied sku={} eventId={} previous={} new={} version={} latencyMs={}",
                event.sku(), event.eventId(), previousStock, newStock, event.stockVersion(), latencyMs));
        return ApplyResult.APPLIED;
    }

    private static void logAfterCommit(Runnable log) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                log.run();
            }
        });
    }
}
```

- [ ] **Step 4: Verificar todo**

Run: `./mvnw verify`
Expected: BUILD SUCCESS. Cifras de referencia: 65 pruebas rápidas y 28 de integración (26 + 2).

- [ ] **Step 5: Mutación de comprobación (no se commitea)**

En `ApplyStockChangeService`, reemplaza el cuerpo de `logAfterCommit` por `log.run();` (registro inmediato) y ejecuta `./mvnw verify -Dit.test=ApplyStockChangeServiceIntegrationTest -Dtest=NoSuchTest -Dsurefire.failIfNoSpecifiedTests=false`: debe FALLAR `aDuplicateKeyRaceFailsAtCommitAndIsNeverLoggedAsApplied`. Restaura y confirma con `git status` que no quedan cambios de la mutación.

- [ ] **Step 6: Commit**

```bash
git add catalog-service/src
git commit -m "refactor: log applied events after commit and leave the MDC to the caller

Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>"
```

---

### Task 3: `StockEventListener` y la regla de arquitectura

**Files:**
- Create: `catalog-service/src/main/java/com/retail/catalog/stockevent/StockEventListener.java`
- Modify (test): `catalog-service/src/test/java/com/retail/catalog/architecture/ArchitectureTest.java`
- Test: `catalog-service/src/test/java/com/retail/catalog/stockevent/StockEventListenerTest.java`

**Interfaces:**
- Consumes: `StockEventReader#read(byte[])`, `ApplyStockChangeService#apply(StockChangedEvent): ApplyResult`, `BackoffPolicy#delayAfter(int)`, `InvalidEventException`, `UnknownProductException`.
- Produces: `@Component class StockEventListener` — constante pública `LISTENER_ID = "stockEventListener"`; constructor `(StockEventReader, ApplyStockChangeService, BackoffPolicy)`; `@RabbitListener(id = LISTENER_ID, queues = "${catalog.consumer.queue}") void onMessage(org.springframework.amqp.core.Message, com.rabbitmq.client.Channel) throws IOException`.

- [ ] **Step 1: Escribir la prueba (debe fallar)**

`src/test/java/com/retail/catalog/stockevent/StockEventListenerTest.java`:

```java
package com.retail.catalog.stockevent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.slf4j.MDC;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.dao.DataIntegrityViolationException;

import com.rabbitmq.client.Channel;

import jakarta.validation.Validation;
import tools.jackson.databind.json.JsonMapper;

/** Clasificación de fallos y confirmación del listener, con un servicio y un canal falsos (sin RabbitMQ). */
class StockEventListenerTest {

    private static final long DELIVERY_TAG = 7L;
    private static final UUID EVENT_ID = UUID.fromString("7f3c9a2e-5b1d-4c8e-9f0a-2d6b8e1c4a73");
    private static final StockChangedEvent EVENT = new StockChangedEvent(EVENT_ID, "TENIS-RUNNER-42", 4, 8L, "POS",
            Instant.parse("2026-10-10T15:04:05.123Z"));
    private static final String BODY = "{\"eventId\":\"" + EVENT_ID + "\",\"sku\":\"TENIS-RUNNER-42\","
            + "\"newStock\":4,\"stockVersion\":8,\"channel\":\"POS\",\"occurredAt\":\"2026-10-10T15:04:05.123Z\"}";

    private final StockEventReader reader = new StockEventReader(JsonMapper.builder().build(),
            Validation.buildDefaultValidatorFactory().getValidator());
    private final ApplyStockChangeService applyService = mock(ApplyStockChangeService.class);
    private final Channel channel = mock(Channel.class);
    // Esperas mínimas: los reintentos son reales pero no hacen lenta la prueba.
    private final StockEventListener listener = new StockEventListener(reader, applyService,
            new BackoffPolicy(Duration.ofMillis(1), Duration.ofMillis(4)));

    private static Message message(String body) {
        MessageProperties properties = new MessageProperties();
        properties.setDeliveryTag(DELIVERY_TAG);
        return new Message(body.getBytes(StandardCharsets.UTF_8), properties);
    }

    @BeforeEach
    @AfterEach
    void clearInterruptionAndMdc() {
        Thread.interrupted();
        MDC.clear();
    }

    @Test
    void anAppliedEventIsAcknowledged() throws IOException {
        when(applyService.apply(EVENT)).thenReturn(ApplyResult.APPLIED);

        listener.onMessage(message(BODY), channel);

        verify(channel).basicAck(DELIVERY_TAG, false);
        verify(channel, never()).basicNack(any(Long.class), any(Boolean.class), any(Boolean.class));
    }

    @Test
    void duplicateAndStaleEventsAreAlsoAcknowledged() throws IOException {
        when(applyService.apply(EVENT)).thenReturn(ApplyResult.DUPLICATE, ApplyResult.STALE);

        listener.onMessage(message(BODY), channel);
        listener.onMessage(message(BODY), channel);

        verify(channel, times(2)).basicAck(DELIVERY_TAG, false);
    }

    @Test
    void anUnreadableBodyIsRejectedWithoutRequeueAndNeverReachesTheService() throws IOException {
        listener.onMessage(message("not json"), channel);

        verify(channel).basicNack(DELIVERY_TAG, false, false);
        verify(channel, never()).basicAck(any(Long.class), any(Boolean.class));
        verifyNoInteractions(applyService);
    }

    @Test
    void anUnknownSkuIsRejectedWithoutRequeueAndWithoutRetrying() throws IOException {
        when(applyService.apply(EVENT)).thenThrow(new UnknownProductException("TENIS-RUNNER-42"));

        listener.onMessage(message(BODY), channel);

        verify(applyService, times(1)).apply(EVENT);
        verify(channel).basicNack(DELIVERY_TAG, false, false);
        verify(channel, never()).basicAck(any(Long.class), any(Boolean.class));
    }

    @Test
    void aTransientFailureIsRetriedUntilItSucceedsAndOnlyThenAcknowledged() throws IOException {
        when(applyService.apply(EVENT))
                .thenThrow(new IllegalStateException("database down"))
                .thenThrow(new IllegalStateException("database still down"))
                .thenReturn(ApplyResult.APPLIED);

        listener.onMessage(message(BODY), channel);

        verify(applyService, times(3)).apply(EVENT);
        verify(channel, times(1)).basicAck(DELIVERY_TAG, false);
        verify(channel, never()).basicNack(any(Long.class), any(Boolean.class), any(Boolean.class));
    }

    /** Una carrera por el mismo eventId falla por la clave primaria: se reintenta y el segundo intento ve DUPLICATE. */
    @Test
    void aDuplicateKeyRaceIsTransientAndEndsAsDuplicate() throws IOException {
        when(applyService.apply(EVENT))
                .thenThrow(new DataIntegrityViolationException("processed_events_pkey"))
                .thenReturn(ApplyResult.DUPLICATE);

        listener.onMessage(message(BODY), channel);

        verify(applyService, times(2)).apply(EVENT);
        verify(channel).basicAck(DELIVERY_TAG, false);
    }

    /** Apagado: se deja de reintentar sin confirmar ni rechazar, para que el broker devuelva el mensaje a la cola. */
    @Test
    @Timeout(value = 10, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    void aShutdownInterruptionStopsRetryingWithoutAcknowledgingNorRejecting() throws IOException {
        when(applyService.apply(EVENT)).thenThrow(new IllegalStateException("database down"));
        Thread.currentThread().interrupt();

        listener.onMessage(message(BODY), channel);

        verify(applyService, times(1)).apply(EVENT);
        verify(channel, never()).basicAck(any(Long.class), any(Boolean.class));
        verify(channel, never()).basicNack(any(Long.class), any(Boolean.class), any(Boolean.class));
        assertThat(Thread.currentThread().isInterrupted()).isTrue();
    }

    @Test
    void theMdcCarriesSkuAndEventIdDuringTheRetriesAndIsClearedAfterwards() throws IOException {
        List<String> seen = new ArrayList<>();
        when(applyService.apply(EVENT)).thenAnswer(invocation -> {
            seen.add(MDC.get("sku") + "/" + MDC.get("eventId"));
            if (seen.size() < 2) {
                throw new IllegalStateException("database down");
            }
            return ApplyResult.APPLIED;
        });

        listener.onMessage(message(BODY), channel);

        assertThat(seen).containsExactly("TENIS-RUNNER-42/" + EVENT_ID, "TENIS-RUNNER-42/" + EVENT_ID);
        assertThat(MDC.get("sku")).isNull();
        assertThat(MDC.get("eventId")).isNull();
    }

    @Test
    void ifTheAcknowledgementFailsTheErrorPropagatesAndTheMessageIsNotLost() throws IOException {
        when(applyService.apply(EVENT)).thenReturn(ApplyResult.APPLIED);
        doThrow(new IOException("channel closed")).when(channel).basicAck(DELIVERY_TAG, false);

        assertThatThrownBy(() -> listener.onMessage(message(BODY), channel)).isInstanceOf(IOException.class);
    }
}
```

- [ ] **Step 2: Ejecutarla y verificar que falla**

Run: `./mvnw test -Dtest=StockEventListenerTest`
Expected: FAIL de compilación (`StockEventListener` no existe).

- [ ] **Step 3: Implementar**

`src/main/java/com/retail/catalog/stockevent/StockEventListener.java`:

```java
package com.retail.catalog.stockevent;

import java.io.IOException;
import java.time.Duration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

import com.rabbitmq.client.Channel;

/**
 * Consume los eventos {@code InventoryStockChanged} de la cola del Catálogo con confirmación manual. Es
 * la única clase que conoce RabbitMQ; la lectura y la aplicación del evento están en
 * {@link StockEventReader} y {@link ApplyStockChangeService}.
 *
 * <p>Clasificación de fallos:
 * <ul>
 *   <li><b>Permanente</b> ({@link InvalidEventException}, {@link UnknownProductException}): reintentar no
 *       lo arregla, así que {@code basicNack} sin reencolar y el broker lo manda a la DLQ.</li>
 *   <li><b>Transitorio</b> (cualquier otra excepción, p. ej. la base caída o un duplicado simultáneo):
 *       reintenta dentro del listener con espera creciente, sin confirmar, hasta lograrlo. Nunca va a la
 *       DLQ por esta causa.</li>
 * </ul>
 *
 * <p>La corrección de {@link ApplyStockChangeService} (no bloquea la fila del producto) depende de que
 * haya <b>un solo consumidor con prefetch 1</b>, fijados en {@code application.yml}: con ellos los eventos
 * se aplican uno a uno y en orden, también mientras se reintenta.
 *
 * <p>El MDC ({@code sku}, {@code eventId}) lo maneja este listener durante todo el procesamiento, reintentos
 * incluidos.
 */
@Component
public class StockEventListener {

    /** Identificador del contenedor del listener: las pruebas lo detienen y lo reanudan con él. */
    public static final String LISTENER_ID = "stockEventListener";

    private static final Logger LOGGER = LoggerFactory.getLogger(StockEventListener.class);

    private final StockEventReader reader;
    private final ApplyStockChangeService applyService;
    private final BackoffPolicy backoffPolicy;

    public StockEventListener(StockEventReader reader, ApplyStockChangeService applyService,
            BackoffPolicy backoffPolicy) {
        this.reader = reader;
        this.applyService = applyService;
        this.backoffPolicy = backoffPolicy;
    }

    @RabbitListener(id = StockEventListener.LISTENER_ID, queues = "${catalog.consumer.queue}")
    public void onMessage(Message message, Channel channel) throws IOException {
        long deliveryTag = message.getMessageProperties().getDeliveryTag();
        try {
            StockChangedEvent event = reader.read(message.getBody());
            try (MDC.MDCCloseable skuKey = MDC.putCloseable("sku", event.sku());
                    MDC.MDCCloseable eventIdKey = MDC.putCloseable("eventId", event.eventId().toString())) {
                if (applyWithRetries(event)) {
                    channel.basicAck(deliveryTag, false);
                }
            }
        } catch (InvalidEventException | UnknownProductException exception) {
            LOGGER.warn("event_rejected reason={}", exception.getMessage());
            channel.basicNack(deliveryTag, false, false);
        }
    }

    /**
     * Aplica el evento reintentando los fallos transitorios.
     *
     * @return {@code true} si se aplicó y hay que confirmar; {@code false} si el hilo fue interrumpido
     *         (apagado) y el mensaje debe quedar sin confirmar para que el broker lo devuelva a la cola
     */
    private boolean applyWithRetries(StockChangedEvent event) {
        for (int attempt = 1;; attempt++) {
            try {
                applyService.apply(event);
                return true;
            } catch (InvalidEventException | UnknownProductException exception) {
                throw exception;
            } catch (RuntimeException exception) {
                Duration delay = backoffPolicy.delayAfter(attempt);
                LOGGER.warn("event_retry attempt={} delayMs={} cause={}", attempt, delay.toMillis(),
                        exception.toString());
                try {
                    Thread.sleep(delay);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    LOGGER.info("event_retry_interrupted attempt={}", attempt);
                    return false;
                }
            }
        }
    }
}
```

- [ ] **Step 4: Endurecer la regla 2 de ArchUnit**

En `ArchitectureTest.java`, reemplaza la regla `onlyStockEventListenerUsesAmqp` por:

```java
    @ArchTest
    static final ArchRule onlyStockEventListenerUsesAmqp = noClasses()
            .that().doNotHaveFullyQualifiedName("com.retail.catalog.stockevent.StockEventListener")
            .should().dependOnClassesThat().resideInAnyPackage("org.springframework.amqp..", "com.rabbitmq..");
```

- [ ] **Step 5: Verificar todo**

Run: `./mvnw verify`
Expected: BUILD SUCCESS. Cifras de referencia: 74 pruebas rápidas (65 + 9 de `StockEventListenerTest`) y 28 de integración. Los contextos de Spring cargan el listener pero **no lo arrancan** (`auto-startup=false` en las pruebas), así que no hace falta broker.

- [ ] **Step 6: Mutaciones de comprobación (no se commitean)**

1. En el listener, cambia `catch (InterruptedException interrupted)` para que no restaure la interrupción ni retorne (deja solo un log): `aShutdownInterruptionStopsRetryingWithoutAcknowledgingNorRejecting` debe FALLAR por tiempo (`@Timeout`), no colgar la ejecución. Restaura.
2. En `StockEventReader`, agrega un método `Object probe() { return org.springframework.amqp.core.Message.class; }` y ejecuta `./mvnw test -Dtest=ArchitectureTest`: `onlyStockEventListenerUsesAmqp` debe FALLAR. (Una variable local sin uso no sirve: el compilador la descarta.) Restaura.
Confirma con `git status` que no quedan cambios de las mutaciones.

- [ ] **Step 7: Commit**

```bash
git add catalog-service/src
git commit -m "feat: add stock event listener with manual ack and retry classification

Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>"
```

---

### Task 4: Pruebas con RabbitMQ real

**Files:**
- Create (test): `catalog-service/src/test/java/com/retail/catalog/support/RabbitIntegrationTest.java`
- Test: `catalog-service/src/test/java/com/retail/catalog/stockevent/StockEventListenerIntegrationTest.java`

**Interfaces:**
- Consumes: todo lo anterior; `PostgresIntegrationTest` (5.2); topología copiada a `target/test-classes/infra/rabbitmq` (Tarea 1).
- Produces: `abstract class RabbitIntegrationTest extends PostgresIntegrationTest` con constantes `EXCHANGE`, `ROUTING_KEY`, `QUEUE`, `DEAD_LETTER_QUEUE`, `RABBIT` y los métodos `stopBroker()`/`startBroker()`.

- [ ] **Step 1: Base con RabbitMQ real**

`src/test/java/com/retail/catalog/support/RabbitIntegrationTest.java`:

```java
package com.retail.catalog.support;

import java.io.IOException;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.rabbitmq.RabbitMQContainer;
import org.testcontainers.utility.MountableFile;

/**
 * Base de las pruebas con RabbitMQ real: un solo broker por ejecución, con la topología de
 * {@code infra/rabbitmq/definitions.json} (exchange, colas, bindings y usuario {@code retail}), igual
 * que en Compose.
 */
public abstract class RabbitIntegrationTest extends PostgresIntegrationTest {

    protected static final String EXCHANGE = "inventory.events";
    protected static final String ROUTING_KEY = "inventory.stock.changed";
    protected static final String QUEUE = "catalog.inventory-stock-changed";
    protected static final String DEAD_LETTER_QUEUE = "catalog.inventory-stock-changed.dlq";

    protected static final RabbitMQContainer RABBIT = new RabbitMQContainer("rabbitmq:4.3-management")
            .withCopyFileToContainer(MountableFile.forClasspathResource("infra/rabbitmq/definitions.json"),
                    "/etc/rabbitmq/definitions.json")
            .withCopyFileToContainer(MountableFile.forClasspathResource("infra/rabbitmq/rabbitmq.conf"),
                    "/etc/rabbitmq/conf.d/20-definitions.conf");

    static {
        RABBIT.start();
    }

    @DynamicPropertySource
    static void configureRabbit(DynamicPropertyRegistry registry) {
        registry.add("spring.rabbitmq.host", RABBIT::getHost);
        registry.add("spring.rabbitmq.port", RABBIT::getAmqpPort);
        registry.add("spring.rabbitmq.username", () -> "retail");
        registry.add("spring.rabbitmq.password", () -> "retail-demo");
    }

    /** Detiene la aplicación de RabbitMQ dentro del contenedor: conexiones rechazadas, puertos intactos. */
    protected static void stopBroker() throws IOException, InterruptedException {
        RABBIT.execInContainer("rabbitmqctl", "stop_app");
    }

    /** Vuelve a arrancar la aplicación de RabbitMQ; la topología ya está persistida en el nodo. */
    protected static void startBroker() throws IOException, InterruptedException {
        RABBIT.execInContainer("rabbitmqctl", "start_app");
    }
}
```

- [ ] **Step 2: Escribir la prueba de integración**

`src/test/java/com/retail/catalog/stockevent/StockEventListenerIntegrationTest.java`:

```java
package com.retail.catalog.stockevent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.core.MessageListenerContainer;
import org.springframework.amqp.core.AcknowledgeMode;
import org.springframework.amqp.rabbit.listener.RabbitListenerEndpointRegistry;
import org.springframework.amqp.rabbit.listener.SimpleMessageListenerContainer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;

import com.retail.catalog.product.Product;
import com.retail.catalog.product.ProductRepository;
import com.retail.catalog.support.RabbitIntegrationTest;

/**
 * El listener contra RabbitMQ y PostgreSQL reales, con la topología de {@code infra/rabbitmq}. Usa productos
 * {@code TEST-<UUID>} y solo vacía {@code processed_events}. El contexto tiene el listener vivo, así que no
 * sobrevive a la clase ({@code @DirtiesContext}). Esperas de reintento cortas para no alargar las pruebas.
 */
@SpringBootTest(properties = {
        "spring.rabbitmq.listener.simple.auto-startup=true",
        "catalog.consumer.initial-backoff=50ms",
        "catalog.consumer.maximum-backoff=200ms" })
@AutoConfigureMockMvc
@ExtendWith(OutputCaptureExtension.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class StockEventListenerIntegrationTest extends RabbitIntegrationTest {

    private static final Duration PATIENCE = Duration.ofSeconds(20);

    @Autowired
    private RabbitTemplate rabbitTemplate;

    @Autowired
    private RabbitAdmin rabbitAdmin;

    @Autowired
    private RabbitListenerEndpointRegistry registry;

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private ProcessedEventRepository processedEventRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private MockMvc mockMvc;

    @MockitoSpyBean
    private ApplyStockChangeService applyService;

    private String sku;

    @BeforeEach
    void createATestProduct() {
        reset(applyService);
        jdbcTemplate.execute("TRUNCATE processed_events");
        rabbitAdmin.purgeQueue(QUEUE, false);
        rabbitAdmin.purgeQueue(DEAD_LETTER_QUEUE, false);
        sku = "TEST-" + UUID.randomUUID().toString().toUpperCase();
        productRepository.saveAndFlush(new Product(sku, "Producto de prueba", "Descripción", 1000, "COP", 5,
                Instant.parse("2026-10-12T10:00:00.000Z")));
        container().start();
    }

    @AfterEach
    void restoreBrokerAndCleanUp() throws Exception {
        startBroker();
        reset(applyService);
        container().start();
        productRepository.deleteById(sku);
    }

    private MessageListenerContainer container() {
        return registry.getListenerContainer(StockEventListener.LISTENER_ID);
    }

    private static String eventJson(UUID eventId, String sku, int newStock, long stockVersion, String extra) {
        return "{\"eventId\":\"%s\",\"eventType\":\"InventoryStockChanged\",\"sku\":\"%s\",\"newStock\":%d,"
                .formatted(eventId, sku, newStock)
                + "\"stockVersion\":%d,\"channel\":\"POS\",\"occurredAt\":\"%s\"%s}".formatted(stockVersion,
                        Instant.now().minusMillis(30), extra);
    }

    private void publish(String body) {
        MessageProperties properties = new MessageProperties();
        properties.setContentType("application/json");
        properties.setDeliveryMode(MessageDeliveryMode.PERSISTENT);
        rabbitTemplate.send(EXCHANGE, ROUTING_KEY, new Message(body.getBytes(StandardCharsets.UTF_8), properties));
    }

    private Product stored() {
        return productRepository.findById(sku).orElseThrow();
    }

    private int queueDepth(String queue) {
        return (int) rabbitAdmin.getQueueInfo(queue).getMessageCount();
    }

    /** La corrección de ApplyStockChangeService depende de esta configuración (ver application.yml). */
    @Test
    void theListenerRunsWithManualAcknowledgementOneConsumerAndPrefetchOne() {
        SimpleMessageListenerContainer simple = (SimpleMessageListenerContainer) container();

        assertThat(simple.getAcknowledgeMode()).isEqualTo(AcknowledgeMode.MANUAL);
        assertThat(ReflectionTestUtils.getField(simple, "concurrentConsumers")).isEqualTo(1);
        assertThat(ReflectionTestUtils.getField(simple, "maxConcurrentConsumers")).isIn(1, null);
        assertThat(ReflectionTestUtils.getField(simple, "prefetchCount")).isEqualTo(1);
    }

    @Test
    void aPublishedEventIsAppliedAndAcknowledged() {
        UUID eventId = UUID.randomUUID();

        publish(eventJson(eventId, sku, 3, 1, ""));

        await().atMost(PATIENCE).untilAsserted(() -> assertThat(stored().getAvailableStock()).isEqualTo(3));
        assertThat(stored().getStockVersion()).isEqualTo(1L);
        assertThat(stored().getLastEventLatencyMs()).isNotNull();
        assertThat(processedEventRepository.existsById(eventId)).isTrue();
        await().atMost(PATIENCE).untilAsserted(() -> assertThat(queueDepth(QUEUE)).isZero());
        assertThat(queueDepth(DEAD_LETTER_QUEUE)).isZero();
    }

    @Test
    void anUnknownExtraFieldInTheEventIsTolerated() {
        publish(eventJson(UUID.randomUUID(), sku, 2, 1, ",\"region\":\"CO-ANT\""));

        await().atMost(PATIENCE).untilAsserted(() -> assertThat(stored().getAvailableStock()).isEqualTo(2));
    }

    /** Escenario 5: con el consumidor detenido los eventos se acumulan; al volver se procesan todos y en orden. */
    @Test
    void eventsPiledUpWhileTheConsumerIsStoppedAreProcessedInOrderWhenItReturns() {
        container().stop();
        for (int version = 1; version <= 4; version++) {
            publish(eventJson(UUID.randomUUID(), sku, 5 - version, version, ""));
        }
        await().atMost(PATIENCE).untilAsserted(() -> assertThat(queueDepth(QUEUE)).isEqualTo(4));
        assertThat(stored().getStockVersion()).isZero();

        container().start();

        await().atMost(PATIENCE).untilAsserted(() -> assertThat(stored().getStockVersion()).isEqualTo(4L));
        assertThat(stored().getAvailableStock()).isEqualTo(1);
        ArgumentCaptor<StockChangedEvent> applied = ArgumentCaptor.forClass(StockChangedEvent.class);
        verify(applyService, times(4)).apply(applied.capture());
        assertThat(applied.getAllValues()).extracting(StockChangedEvent::stockVersion).containsExactly(1L, 2L, 3L, 4L);
        await().atMost(PATIENCE).untilAsserted(() -> assertThat(queueDepth(QUEUE)).isZero());
    }

    /** Escenario 9: la versión 3 llega antes que la 2; el estado final es el de la 3 y no hay mensajes perdidos. */
    @Test
    void anOutOfOrderEventDoesNotRollTheStockBack() {
        UUID third = UUID.randomUUID();
        UUID second = UUID.randomUUID();

        publish(eventJson(third, sku, 2, 3, ""));
        publish(eventJson(second, sku, 4, 2, ""));

        await().atMost(PATIENCE).untilAsserted(() -> assertThat(processedEventRepository.existsById(second)).isTrue());
        assertThat(stored().getAvailableStock()).isEqualTo(2);
        assertThat(stored().getStockVersion()).isEqualTo(3L);
        assertThat(processedEventRepository.existsById(third)).isTrue();
        assertThat(queueDepth(DEAD_LETTER_QUEUE)).isZero();
    }

    @Test
    void aDuplicatedMessageIsAppliedOnlyOnce() {
        String body = eventJson(UUID.randomUUID(), sku, 3, 1, "");

        publish(body);
        publish(body);

        await().atMost(PATIENCE).untilAsserted(() -> verify(applyService, times(2)).apply(any()));
        await().atMost(PATIENCE).untilAsserted(() -> assertThat(queueDepth(QUEUE)).isZero());
        assertThat(stored().getAvailableStock()).isEqualTo(3);
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM processed_events", Integer.class)).isEqualTo(1);
        assertThat(queueDepth(DEAD_LETTER_QUEUE)).isZero();
    }

    @Test
    void messagesThatCanNeverBeProcessedEndInTheDeadLetterQueue() {
        UUID unknownSkuEventId = UUID.randomUUID();
        publish("{ this is not json");
        publish("{\"eventId\":\"" + UUID.randomUUID() + "\",\"sku\":\"" + sku + "\",\"stockVersion\":1}");
        publish(eventJson(unknownSkuEventId, "TEST-NO-EXISTE", 3, 1, ""));

        await().atMost(PATIENCE).untilAsserted(() -> assertThat(queueDepth(DEAD_LETTER_QUEUE)).isEqualTo(3));
        assertThat(queueDepth(QUEUE)).isZero();
        assertThat(stored().getStockVersion()).isZero();
        assertThat(processedEventRepository.existsById(unknownSkuEventId)).isFalse();
        Message first = rabbitTemplate.receive(DEAD_LETTER_QUEUE, 5000);
        assertThat(first).isNotNull();
        assertThat(new String(first.getBody(), StandardCharsets.UTF_8)).isEqualTo("{ this is not json");
    }

    @Test
    void aTransientFailureIsRetriedAndTheEventIsAppliedWithoutReachingTheDeadLetterQueue() {
        doThrow(new IllegalStateException("database down")).doThrow(new IllegalStateException("database down"))
                .doCallRealMethod().when(applyService).apply(any());

        publish(eventJson(UUID.randomUUID(), sku, 3, 1, ""));

        await().atMost(PATIENCE).untilAsserted(() -> assertThat(stored().getAvailableStock()).isEqualTo(3));
        verify(applyService, times(3)).apply(any());
        assertThat(queueDepth(DEAD_LETTER_QUEUE)).isZero();
    }

    /** Apagar el consumidor en pleno reintento no pierde el mensaje: vuelve a la cola y se procesa al regresar. */
    @Test
    void stoppingTheConsumerWhileRetryingDoesNotLoseTheMessage(CapturedOutput output) {
        doThrow(new IllegalStateException("database down")).when(applyService).apply(any());
        publish(eventJson(UUID.randomUUID(), sku, 3, 1, ""));
        await().atMost(PATIENCE).untilAsserted(() -> verify(applyService, atLeast(2)).apply(any()));

        container().stop();

        // El apagado interrumpe el hilo: el listener deja de reintentar sin confirmar ni rechazar.
        await().atMost(PATIENCE).untilAsserted(() -> assertThat(output).contains("event_retry_interrupted"));
        await().atMost(PATIENCE).untilAsserted(() -> assertThat(queueDepth(QUEUE)).isEqualTo(1));
        doCallRealMethod().when(applyService).apply(any());
        container().start();
        await().atMost(PATIENCE).untilAsserted(() -> assertThat(stored().getAvailableStock()).isEqualTo(3));
    }

    /** Escenario 8 visto desde el Catálogo: sin broker sigue sano y sirviendo consultas, y reconecta solo. */
    @Test
    void withoutTheBrokerTheServiceStaysHealthyAndReconnectsByItself() throws Exception {
        stopBroker();

        mockMvc.perform(get("/actuator/health")).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("UP"));
        mockMvc.perform(get("/api/v1/catalog/products/" + sku)).andExpect(status().isOk());

        startBroker();
        await().atMost(Duration.ofSeconds(60)).ignoreExceptions().untilAsserted(() -> {
            publish(eventJson(UUID.randomUUID(), sku, 3, 1, ""));
            assertThat(stored().getAvailableStock()).isEqualTo(3);
        });
    }
}
```

- [ ] **Step 3: Ejecutarla**

Run: `./mvnw verify -Dit.test=StockEventListenerIntegrationTest -Dtest=NoSuchTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: PASS, 10 pruebas (el listener ya existe desde la Tarea 3; esta tarea es la prueba de punta a punta). Tarda unos 40 s.

- [ ] **Step 4: Verificar todo**

Run: `./mvnw verify`
Expected: BUILD SUCCESS. Cifras de referencia: 74 pruebas rápidas y 38 de integración (28 + 10).

- [ ] **Step 5: Mutaciones de comprobación (no se commitean)**

Ejecuta cada una con `./mvnw verify -Dit.test=StockEventListenerIntegrationTest -Dtest=NoSuchTest -Dsurefire.failIfNoSpecifiedTests=false`, confirma el fallo indicado y restaura:
1. `basicNack(deliveryTag, false, false)` → `basicNack(deliveryTag, false, true)` en el listener: deben FALLAR `messagesThatCanNeverBeProcessedEndInTheDeadLetterQueue` y `anOutOfOrderEventDoesNotRollTheStockBack` (mensajes reencolados en bucle).
2. `management.health.rabbit.enabled: false` → `true` en `application.yml`: debe FALLAR `withoutTheBrokerTheServiceStaysHealthyAndReconnectsByItself`.
3. En el listener, sustituye el cuerpo del `catch (InterruptedException interrupted)` por solo un log (sin `interrupt()` ni `return false`): debe FALLAR `stoppingTheConsumerWhileRetryingDoesNotLoseTheMessage` (no aparece `event_retry_interrupted`).
4. `prefetch: 1` → `prefetch: 5` en `application.yml`: debe FALLAR `theListenerRunsWithManualAcknowledgementOneConsumerAndPrefetchOne`.
Confirma con `git status` que no quedan cambios de las mutaciones.

- [ ] **Step 6: Commit**

```bash
git add catalog-service/src
git commit -m "test: cover the stock event listener against a real RabbitMQ

Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>"
```

---

### Task 5: Prueba de humo, documentación y cierre de la fase 5

**Files:**
- Create: `scripts/smoke-catalog-service.sh` (ejecutable)
- Modify: `scripts/smoke-inventory-service.sh` (solo el comentario de cabecera)
- Modify: `README.md`, `CLAUDE.md`
- Modify: `docs/superpowers/specs/2026-10-12-catalogo-design.md`

**Interfaces:**
- Consumes: todo lo anterior; Compose con `inventory-service` y `catalog-service`; `scripts/generate-token.sh pos`.
- Produces: el cierre de la fase 5 de la spec (§7 "Cierre de la fase 5").

- [ ] **Step 1: Script de humo**

`scripts/smoke-catalog-service.sh` (luego `chmod +x`):

```bash
#!/usr/bin/env bash
# Prueba de humo del flujo completo venta -> Catálogo dentro de Compose (cierre de la fase 5, spec del
# Catálogo §7): una venta en Inventario aparece en la consulta del Catálogo en pocos segundos con su
# lastEventLatencyMs, las colas quedan vacías (sin mensajes en la DLQ) y el contenedor del Catálogo no corre
# como root. Requiere Docker, curl y jq; la primera vez construye las imágenes (internet y unos minutos).
set -uo pipefail   # sin -e a propósito: se ejecutan todos los checks y se agrega el resultado

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT" || exit 1
command -v curl >/dev/null && command -v jq >/dev/null || { echo "FAIL: se requieren curl y jq en el equipo"; exit 1; }

# Proyecto y puertos propios: no toca ni choca con un stack de desarrollo ni con otras pruebas de humo.
export COMPOSE_PROJECT_NAME="retail-smoke-catalog"
export RABBITMQ_AMQP_PORT=37672 RABBITMQ_MGMT_PORT=37673 INVENTORY_DB_PORT=37441 CATALOG_DB_PORT=37442
export INVENTORY_SERVICE_PORT=37081 CATALOG_SERVICE_PORT=37082
export INVENTORY_DB_PASSWORD="inventory-demo" CATALOG_DB_PASSWORD="catalog-demo"
INVENTORY_URL="http://127.0.0.1:${INVENTORY_SERVICE_PORT}"
CATALOG_URL="http://127.0.0.1:${CATALOG_SERVICE_PORT}"
QUEUE="catalog.inventory-stock-changed"
DEAD_LETTER_QUEUE="catalog.inventory-stock-changed.dlq"
SKU="TENIS-RUNNER-42"

status=0
cleanup() { docker compose down -v --remove-orphans >/dev/null 2>&1 || true; }
trap cleanup EXIT

check() { # check <descripción> <valor obtenido> <valor esperado>
  if [[ "$2" == "$3" ]]; then echo "PASS: $1"; else echo "FAIL: $1 (obtenido: $2, esperado: $3)"; status=1; fi
}

queue_field() { # queue_field <cola> <columna: messages|consumers>
  docker compose exec -T rabbitmq rabbitmqctl -q list_queues name "$2" | awk -v q="$1" '$1 == q {print $2}' | tr -d '\r'
}

echo "== Arranque =="
if up_output="$(docker compose up -d --wait --wait-timeout 300 --build inventory-service catalog-service 2>&1)"; then
  echo "PASS: inventory-service y catalog-service llegan a healthy"
else
  echo "FAIL: los servicios no llegaron a healthy"; echo "$up_output"
  docker compose logs --tail 50 inventory-service catalog-service; exit 1
fi
container_user="$(docker compose exec -T catalog-service id -u | tr -d '\r')"
check "el contenedor del Catálogo no corre como root" "$([[ "$container_user" =~ ^[0-9]+$ && "$container_user" != "0" ]] && echo yes || echo no)" "yes"
check "el Catálogo tiene un consumidor en la cola $QUEUE" "$(queue_field "$QUEUE" consumers)" "1"

echo "== Estado inicial =="
stock_before="$(curl -s "$CATALOG_URL/api/v1/catalog/products/$SKU" | jq -r '.availableStock')"
check "el Catálogo sirve $SKU con la semilla" "$stock_before" "5"

echo "== Venta =="
token="$("$ROOT/scripts/generate-token.sh" pos)"
http_status="$(curl -s -o /dev/null -w '%{http_code}' -X POST "$INVENTORY_URL/api/v1/inventory/$SKU/sales" \
  -H "Authorization: Bearer $token" -H 'Content-Type: application/json' -d '{"quantity":1,"channel":"POS"}')"
check "la venta responde 201" "$http_status" "201"

echo "== Propagación al Catálogo =="
stock_after="$stock_before"; latency="null"
for _ in $(seq 1 30); do
  product="$(curl -s "$CATALOG_URL/api/v1/catalog/products/$SKU")"
  stock_after="$(jq -r '.availableStock' <<<"$product")"
  latency="$(jq -r '.lastEventLatencyMs' <<<"$product")"
  [[ "$stock_after" == "$((stock_before - 1))" ]] && break
  sleep 1
done
check "el Catálogo refleja la venta (stock $((stock_before - 1)))" "$stock_after" "$((stock_before - 1))"
check "lastEventLatencyMs se informa" "$([[ "$latency" =~ ^[0-9]+$ ]] && echo yes || echo no)" "yes"
echo "INFO: latencia venta -> Catálogo: ${latency} ms"

echo "== Colas =="
check "la cola $QUEUE queda vacía" "$(queue_field "$QUEUE" messages)" "0"
check "la DLQ $DEAD_LETTER_QUEUE queda vacía" "$(queue_field "$DEAD_LETTER_QUEUE" messages)" "0"

[[ $status -eq 0 ]] && echo "Prueba de humo OK" || echo "Prueba de humo FALLÓ"
exit $status
```

- [ ] **Step 2: Ejecutarlo**

Run (desde la raíz del repositorio, con Docker): `scripts/smoke-catalog-service.sh`
Expected: todo `PASS`, termina con `Prueba de humo OK`. Tarda unos minutos la primera vez (construye imágenes). Usa su propio proyecto Compose (`retail-smoke-catalog`) y sus puertos, así que no choca con un stack en marcha. Anota en el informe la línea `INFO: latencia venta -> Catálogo: … ms`.

- [ ] **Step 3: Comentario de `smoke-inventory-service.sh`**

En la cabecera de `scripts/smoke-inventory-service.sh`, reemplaza las dos líneas:

```bash
# Mientras el Catálogo no consuma la cola (fase 5), el mensaje permanece en ella; cuando exista, la
# comprobación de la cola dependerá de que el Catálogo esté detenido.
```

por:

```bash
# Este script levanta solo Inventario y sus dependencias, no el Catálogo: nadie consume la cola, así que
# el mensaje permanece en ella y la comprobación de la cola es válida. El flujo completo hasta el Catálogo
# lo prueba scripts/smoke-catalog-service.sh.
```

- [ ] **Step 4: Spec del Catálogo**

En `docs/superpowers/specs/2026-10-12-catalogo-design.md` (lee antes el texto exacto; español, mismo estilo; si los números de línea cambiaron, busca por el texto):
1. §3 "Prueba de arquitectura", regla 2: reemplaza "Solo `StockEventListener` usa `org.springframework.amqp`." por "Solo `StockEventListener` (por nombre completo) usa `org.springframework.amqp` y `com.rabbitmq`."
2. §4 "Configuración": reemplaza "`CatalogProperties` llega en 5.4, con sus primeras propiedades reales; antes no habría nada que validar." por "`CatalogProperties` se valida al arrancar (cola no vacía, esperas positivas y tope no menor que la espera inicial) y sus valores viven en `application.yml`."
3. §4 "Salud": reemplaza "(`management.health.rabbit.enabled=false`; la propiedad se configura en 5.4, al agregar AMQP)" por "(`management.health.rabbit.enabled=false`)".
4. §6 "Listener": reemplaza el último punto de la lista ("Los registros llevan `sku` y `eventId` en el MDC. `event_applied` incluye el stock previo y el nuevo …") por dos puntos:
   - "El **listener** maneja el MDC (`sku`, `eventId`) durante todo el procesamiento, reintentos incluidos; `ApplyStockChangeService` no lo toca y sus mensajes repiten ambos valores. `event_applied` y `event_stale` se registran **después de confirmar la transacción** (un cambio revertido nunca figura como aplicado); `event_applied` incluye el stock previo y el nuevo (`previous=… new=… version=… latencyMs=…`), usando el valor guardado como "previo"."
   - "`acknowledge-mode: manual`, `concurrency: 1` y `prefetch: 1` están fijados en `application.yml` con un comentario: la corrección de `ApplyStockChangeService` (no bloquea la fila de `Product`) y el orden por producto dependen de ellos. Una carrera por el mismo `eventId` falla por la clave primaria con `DataIntegrityViolationException`, que se trata como transitoria (el segundo intento responde `DUPLICATE`)."
5. §7 "Pendientes": elimina las tres filas de la 5.4 (regla 2 de ArchUnit, `concurrency`/`prefetch`, MDC y log). Quedan solo las dos filas de Inventario.
6. §7 "Cierre de la fase 5": agrega al final del párrafo: "El script existe: `scripts/smoke-catalog-service.sh` (proyecto Compose y puertos propios)."

- [ ] **Step 5: README y CLAUDE.md**

`README.md` (lee antes el texto y respeta su estilo y ancho):
- En "Servicio de Catálogo": reemplaza la frase que dice que el consumidor llega en una sub-tarea posterior (el párrafo agregado en la 5.3 sobre la aplicación de eventos) por un párrafo que explique que el Catálogo **ya consume** los eventos de la cola `catalog.inventory-stock-changed` con confirmación manual: los mensajes imposibles de procesar (JSON corrupto, campo faltante, SKU desconocido) van a la DLQ `catalog.inventory-stock-changed.dlq`, y los fallos transitorios se reintentan con espera creciente sin perder el mensaje; si RabbitMQ cae, el servicio sigue respondiendo consultas y se reconecta solo.
- En la lista de scripts de verificación (el bloque de código con `scripts/smoke-inventory-service.sh   # …`) agrega la línea `scripts/smoke-catalog-service.sh    # flujo completo venta -> Catálogo en Compose (cierre de la fase 5)` con la misma alineación.

`CLAUDE.md`, sección "Comandos": después de la línea de `scripts/smoke-inventory-service.sh`, agrega:

```
- `scripts/smoke-catalog-service.sh` prueba de humo del flujo venta → Catálogo dentro de Compose (requiere Docker, curl y jq).
```

- [ ] **Step 6: Verificación final**

Cada comando por separado, desde la raíz del repositorio (con los puertos por defecto libres):

```bash
cd catalog-service && ./mvnw verify
```
Expected: BUILD SUCCESS, 74 pruebas rápidas y 38 de integración.

```bash
scripts/smoke-catalog-service.sh
scripts/smoke-inventory-service.sh
```
Expected: ambos terminan en `Prueba de humo OK`.

Con un stack normal (`docker compose up -d --wait --build`): `docker compose stop rabbitmq`, comprueba que `curl -s http://localhost:8082/actuator/health` sigue en `UP` y que la consulta de un producto responde; luego `docker compose start rabbitmq` y comprueba (en unos segundos) en `docker compose logs --tail 20 catalog-service` que el listener se reconectó. Termina con `docker compose down -v`.

- [ ] **Step 7: Commit**

```bash
git add scripts README.md CLAUDE.md docs/superpowers/specs/2026-10-12-catalogo-design.md
git commit -m "docs: add catalog smoke test and document the stock event listener

Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>"
```

---

## Autorrevisión

- **Cobertura de la spec (5.4):** listener con ack manual, un consumidor, prefetch 1 y `missing-queues-fatal=false` (T1 yml, T3, T4); clasificación permanente/transitoria y DLQ por `catalog.dlx` (T3, T4); espera creciente 1 s…30 s sin límite de intentos, tomada de `CatalogProperties` (T1, T3); apagado sin pérdida (T3, T4); `CatalogProperties` validada (T1); salud sin RabbitMQ (T1, T4); regla 2 de ArchUnit afinada (T3); escenarios 5 y 9, DLQ, reintento real, campo extra tolerado, duplicado (T4); prueba de humo del flujo venta → Catálogo con `lastEventLatencyMs` y contenedor sin root (T5).
- **Pendientes de la 5.3 resueltos:** `concurrency`/`prefetch` fijados y comentados, y `DataIntegrityViolationException` como transitoria (T1, T3); MDC en el listener y `event_applied` tras el commit (T2, T3); ArchUnit por nombre completo (T3). La T5 los retira de la spec.
- **Decisión de diseño aclarada:** el log de aplicación sigue en el servicio (tiene el stock previo y la latencia) pero se emite tras el commit; el MDC lo maneja el listener, que es quien conoce el ciclo de vida del mensaje y los reintentos.
- **Consistencia de tipos:** `StockEventListener(StockEventReader, ApplyStockChangeService, BackoffPolicy)`; `BackoffPolicy#delayAfter(int): Duration`; `CatalogProperties.Consumer(queue, initialBackoff, maximumBackoff)`; `StockEventListener.LISTENER_ID`; `RabbitIntegrationTest.{EXCHANGE, ROUTING_KEY, QUEUE, DEAD_LETTER_QUEUE}`.
- **Conteos de referencia:** rápidas 48 → 65 (T1) → 74 (T3); integración 26 → 28 (T2) → 38 (T4).
- **Verificado en una copia temporal** antes de escribir el plan: 74 rápidas + 38 de integración en verde (la última prueba de configuración se añadió y se comprobó aparte); mutaciones comprobadas (requeue, interrupción ignorada, salud de RabbitMQ habilitada, prefetch 5, dependencia AMQP fuera del listener); y `scripts/smoke-catalog-service.sh` corrió completo en Compose con latencia de ~470 ms.
