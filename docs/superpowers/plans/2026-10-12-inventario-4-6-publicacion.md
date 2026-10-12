# Inventario 4.6: publicación del outbox a RabbitMQ — Plan de implementación

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Publicar los eventos PENDING del outbox en RabbitMQ con confirmación del broker (at-least-once, en orden), exponer `GET /api/v1/inventory/outbox` para la demo, sobrevivir a una caída de RabbitMQ (escenario 8) y cerrar la fase 4 con una prueba de humo en Compose.

**Architecture:** `OutboxRelay` (lógica del lote) lee los PENDING por `sequence_number`, los envía a través de la interfaz `StockEventSender` y actualiza el estado en transacciones cortas; `OutboxRelayScheduler` lo dispara cada 500 ms (se puede apagar). `RabbitStockEventSender` es la única clase que conoce `org.springframework.amqp`: publica con *publisher confirms* y `mandatory`, y solo cuenta como enviado si el broker confirma y no devuelve el mensaje. `OutboxController` lista los eventos para diagnóstico.

**Tech Stack:** Spring Boot 4.1.1 (`spring-boot-starter-amqp`, Spring AMQP, `@Scheduled`), Spring Data JPA, RabbitMQ 4.3 (Testcontainers `testcontainers-rabbitmq` cargando `infra/rabbitmq/definitions.json`), Awaitility (viene con `spring-boot-starter-test`), PostgreSQL 17, bash/curl/jq.

**Spec:** `docs/superpowers/specs/2026-10-11-inventario-design.md` (sección 8, parte "4.6", y "Pendientes heredados"). Contratos: `docs/contracts/openapi/inventory-service.yaml` (ruta `listOutboxEvents`, schema `OutboxEvent`) y `infra/rabbitmq/definitions.json` (exchange `inventory.events`, cola `catalog.inventory-stock-changed`).

## Global Constraints

- Spring Boot `4.1.1`, Java `25`, Maven solo con el wrapper `./mvnw` desde `inventory-service/`. Paquete base `com.retail.inventory`.
- **Nombres completos, sin abreviaturas** (salvo `SKU`, `JWT`, `UUID`, `DTO`, `HTTP`, `JSON`, `DLQ` y unidades). Código en inglés; comentarios, Javadoc, documentación y mensajes de los scripts en español.
- Commits en inglés, breves, Conventional Commits, con la línea `Co-Authored-By: Claude <modelo> <noreply@anthropic.com>`.
- La ruta del repositorio contiene espacios: toda ruta en comandos va entre comillas dobles.
- Relay: `@Scheduled` con **espera fija** (`fixedDelay`, por defecto 500 ms, nunca solapado); lee hasta `batch-size` (50) eventos PENDING por `sequence_number`; por cada uno envía y bloquea hasta la confirmación; ok → marca PUBLISHED (transacción corta); falla → `attempts + 1`, `last_error`, sigue PENDING y **detiene el lote**. **Nunca una transacción abierta durante el envío por red.**
- `StockEventSender` es la única interfaz de mensajería del servicio. `RabbitStockEventSender` envía los bytes del JSON tal cual, con `message_id` = `eventId`, `type`, `content_type` `application/json` y entrega persistente, al exchange `inventory.events` con la clave `inventory.stock.changed`; usa *publisher confirms* y `mandatory`: solo cuenta como enviado si el broker confirma **y** el mensaje no fue devuelto por no tener cola. Es la única clase que usa `org.springframework.amqp`.
- Tiempo de espera de la confirmación de 5 s y de conexión de 2 s. Propiedades: `inventory.outbox.poll-interval` (500 ms), `batch-size` (50), `confirm-timeout` (5 s) y `relay-enabled` (`true`).
- `GET /api/v1/inventory/outbox`: público, máximo 50 eventos, los más recientes primero, filtro opcional `status` (`PENDING` | `PUBLISHED`); un valor desconocido responde `400 validation-error`. Cuerpo: arreglo de `OutboxEvent` del contrato (`eventId`, `sku`, `status`, `attempts`, `lastError`, `occurredAt`, `publishedAt`; `lastError` y `publishedAt` presentes, con `null` si no aplican).
- Una caída de RabbitMQ no debe marcar el servicio como no sano: el indicador de salud de RabbitMQ se desactiva (`management.health.rabbit.enabled: false`). El contenedor corre con un usuario sin privilegios.
- Dependencias permitidas entre paquetes: `sale → stock`, `sale → outbox`, `outbox → stock`. Solo `RabbitStockEventSender` usa `org.springframework.amqp`; `stock/` y `sale/` no dependen de ella.
- Las pruebas de integración se nombran `*IntegrationTest` (failsafe, `./mvnw verify`, requieren Docker). **Todas las pruebas comparten una sola base**, y cada venta deja una fila en `outbox`: las pruebas del relay y de `GET /outbox` vacían la tabla (`TRUNCATE outbox`) en `@BeforeEach`. El publicador automático está **apagado en todas las pruebas** (`src/test/resources/application.properties`); las que lo necesitan lo encienden con `@SpringBootTest(properties = ...)`.
- Línea base antes de empezar: `./mvnw test` → 45 pruebas; `./mvnw verify` → 45 + 85 de integración.

## Review Focus

1. **RabbitMQ caído**: la venta responde `201`, `/actuator/health` sigue `UP`, el primer evento se reintenta (`attempts` crece, `lastError` informado) sin tocar los siguientes, y al volver el broker se publican todos **en orden**. Se fija en `OutboxPublishingIntegrationTest` (Tarea 3) y en la verificación de la Tarea 5.
2. **Mensaje sin cola destino**: el broker lo devuelve (`basic.return`) y no se cuenta como enviado. Se fija en `RabbitStockEventSenderIntegrationTest` (Tarea 1).
3. **Estado desconocido en `GET /outbox`** (`?status=DONE`, también en minúsculas): `400 validation-error` con `errors[0].field = status`, no 500 ni lista vacía. Se fija en `OutboxEndpointIntegrationTest` (Tarea 4).
4. **Aislamiento entre clases de prueba**: el relay y el listado nunca ven filas de otras clases (la tabla se vacía) y el publicador automático no corre en las pruebas que no lo piden. Se fija en las Tareas 2, 3 y 4 con `TRUNCATE outbox` y en `src/test/resources/application.properties` (Tarea 1).
5. **Mensaje de error enorme** (un fallo con traza larga): `last_error` queda acotado a 500 caracteres. Se fija en `OutboxRelayTest` (Tarea 2).

---

## Estructura de archivos

| Archivo | Responsabilidad |
|---|---|
| `inventory-service/pom.xml` | `spring-boot-starter-amqp`, `testcontainers-rabbitmq`, copia de la topología de RabbitMQ al classpath de pruebas |
| `.../resources/application.yml` | Conexión a RabbitMQ (por defecto `localhost`), confirms/returns/mandatory, salud de RabbitMQ desactivada |
| `.../config/InventoryProperties.java` | Agrega `Outbox(pollInterval, batchSize, confirmTimeout, relayEnabled)` |
| `.../outbox/StockEventSender.java`, `EventPublishException.java` | Interfaz de envío y su error |
| `.../outbox/RabbitStockEventSender.java` | Envío con confirms y `mandatory` (única clase con `org.springframework.amqp`) |
| `.../outbox/OutboxEvent.java`, `OutboxRepository.java` | Mutadores `markPublished`/`recordFailure` y consultas del relay y del listado |
| `.../outbox/OutboxRelay.java` | Lote de publicación |
| `.../outbox/OutboxRelayScheduler.java` | Disparo periódico, apagable |
| `.../outbox/OutboxEventResponse.java`, `ListOutboxEventsService.java`, `OutboxController.java` | `GET /outbox` |
| `src/test/resources/application.properties` | Relay apagado y ruido de reconexión silenciado en las pruebas |
| `src/test/.../support/RabbitIntegrationTest.java` | Base con RabbitMQ real y la topología de Compose |
| `src/test/.../outbox/*Test.java` | Pruebas del emisor, del relay, de la publicación y del listado |
| `inventory-service/Dockerfile` | Usuario sin privilegios |
| `docker-compose.yml` | Variables `SPRING_RABBITMQ_*` del servicio |
| `scripts/smoke-inventory-service.sh` | Prueba de humo del cierre de la fase 4 |
| `README.md`, `CLAUDE.md`, spec de Inventario | Documentación al día |

---

### Task 1: Emisor a RabbitMQ y configuración

**Files:**
- Modify: `inventory-service/pom.xml`
- Modify: `inventory-service/src/main/resources/application.yml`
- Modify: `inventory-service/src/main/java/com/retail/inventory/config/InventoryProperties.java`
- Create: `inventory-service/src/main/java/com/retail/inventory/outbox/EventPublishException.java`
- Create: `inventory-service/src/main/java/com/retail/inventory/outbox/StockEventSender.java`
- Create: `inventory-service/src/main/java/com/retail/inventory/outbox/RabbitStockEventSender.java`
- Create: `inventory-service/src/test/resources/application.properties`
- Create: `inventory-service/src/test/java/com/retail/inventory/support/RabbitIntegrationTest.java`
- Test: `inventory-service/src/test/java/com/retail/inventory/outbox/RabbitStockEventSenderIntegrationTest.java`
- Modify (test): `inventory-service/src/test/java/com/retail/inventory/config/InventoryPropertiesTest.java`

**Interfaces:**
- Consumes: `PostgresIntegrationTest`, `TestTokens.SECRET`, `InventoryProperties`.
- Produces:
  - `InventoryProperties.Outbox(Duration pollInterval, int batchSize, Duration confirmTimeout, boolean relayEnabled)` con valores por defecto `500ms`, `50` (`@Min(1)`), `5s`, `true`; `InventoryProperties.outbox()` nunca es nulo. El constructor del record pasa a `InventoryProperties(Jwt jwt, Sale sale, Outbox outbox)`.
  - `interface StockEventSender { void send(UUID eventId, String eventType, String payload); }` (lanza `EventPublishException`).
  - `class EventPublishException extends RuntimeException` — `(String)` y `(String, Throwable)`.
  - `@Component class RabbitStockEventSender implements StockEventSender` — constructor `(RabbitTemplate, InventoryProperties)`; constantes de paquete `EXCHANGE = "inventory.events"`, `ROUTING_KEY = "inventory.stock.changed"`.
  - `abstract class RabbitIntegrationTest extends PostgresIntegrationTest` — contenedor compartido `RABBIT`, constante `QUEUE = "catalog.inventory-stock-changed"`, `stopBroker()` / `startBroker()` (`rabbitmqctl stop_app` / `start_app`).

- [ ] **Step 1: Dependencias y topología de pruebas en el `pom.xml`**

En `<dependencies>`, antes de la dependencia `org.postgresql:postgresql`, agregar:

```xml
		<dependency>
			<groupId>org.springframework.boot</groupId>
			<artifactId>spring-boot-starter-amqp</artifactId>
		</dependency>
```

y antes de `org.testcontainers:testcontainers-junit-jupiter`:

```xml
		<dependency>
			<groupId>org.testcontainers</groupId>
			<artifactId>testcontainers-rabbitmq</artifactId>
			<scope>test</scope>
		</dependency>
```

En el `maven-resources-plugin`, dentro de `<executions>` y después de la ejecución `copy-seed-data-for-tests`, agregar:

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

- [ ] **Step 2: Propiedades del publicador**

En `config/InventoryProperties.java`:
- Cambiar la declaración por:

```java
public record InventoryProperties(@Valid @NotNull Jwt jwt, @Valid @DefaultValue Sale sale,
        @Valid @DefaultValue Outbox outbox) {
```

- Agregar el import `java.time.Duration` (junto a `java.nio.charset.StandardCharsets`).
- Antes del Javadoc de `Jwt`, agregar:

```java
    /**
     * Parámetros del publicador del outbox.
     *
     * @param pollInterval cada cuánto busca eventos pendientes (espera entre ejecuciones)
     * @param batchSize máximo de eventos que lee por ejecución
     * @param confirmTimeout tiempo máximo de espera de la confirmación del broker
     * @param relayEnabled apaga el publicador automático (las pruebas controlan cuándo corre)
     */
    public record Outbox(@DefaultValue("500ms") Duration pollInterval, @DefaultValue("50") @Min(1) int batchSize,
            @DefaultValue("5s") Duration confirmTimeout, @DefaultValue("true") boolean relayEnabled) {
    }

```

En `application.yml`:
- Bajo `spring:`, después de `jpa:` y antes de `jackson:`, agregar:

```yaml
  rabbitmq:
    # Valores para correr desde el IDE; Compose los sobrescribe con SPRING_RABBITMQ_*.
    host: localhost
    port: 5672
    username: retail
    password: retail-demo
    # Una caída de RabbitMQ no debe bloquear el ciclo del publicador.
    connection-timeout: 2s
    # Publisher confirms y mandatory: solo cuenta como enviado si el broker confirma y no devuelve el mensaje.
    publisher-confirm-type: correlated
    publisher-returns: true
    template:
      mandatory: true
```

- Bajo `management:`, antes de `endpoints:`, agregar:

```yaml
  health:
    rabbit:
      # Una caída de RabbitMQ (escenario 8) no debe marcar el servicio como no sano: la venta sigue funcionando.
      enabled: false
```

- [ ] **Step 3: Pruebas de propiedades (deben fallar)**

En `config/InventoryPropertiesTest.java`, antes de `failsToStartWhenSecretIsMissing`, agregar:

```java
    @Test
    void outboxDefaultsMatchTheSpec() {
        contextRunner.withPropertyValues("inventory.jwt.secret=" + SECRET_OF_32_BYTES)
                .run(context -> {
                    InventoryProperties.Outbox outbox = context.getBean(InventoryProperties.class).outbox();
                    assertThat(outbox.pollInterval()).isEqualTo(java.time.Duration.ofMillis(500));
                    assertThat(outbox.batchSize()).isEqualTo(50);
                    assertThat(outbox.confirmTimeout()).isEqualTo(java.time.Duration.ofSeconds(5));
                    assertThat(outbox.relayEnabled()).isTrue();
                });
    }

    @Test
    void outboxBatchSizeMustBeAtLeastOne() {
        contextRunner.withPropertyValues("inventory.jwt.secret=" + SECRET_OF_32_BYTES, "inventory.outbox.batch-size=0")
                .run(context -> assertThat(context).hasFailed());
    }
```

(Si se ejecutan antes del Paso 2 fallan por compilación; con el Paso 2 aplicado pasan.)

- [ ] **Step 4: Interfaz, error y emisor**

`outbox/EventPublishException.java`:

```java
package com.retail.inventory.outbox;

/**
 * El evento no pudo entregarse al broker (conexión caída, tiempo de espera, rechazo o mensaje devuelto
 * por no tener cola). No conoce HTTP: solo la ve el publicador del outbox.
 */
public class EventPublishException extends RuntimeException {

    public EventPublishException(String message) {
        super(message);
    }

    public EventPublishException(String message, Throwable cause) {
        super(message, cause);
    }
}
```

`outbox/StockEventSender.java`:

```java
package com.retail.inventory.outbox;

import java.util.UUID;

/**
 * Envío de un evento al broker. Es la única interfaz de mensajería del servicio: el publicador del
 * outbox no conoce RabbitMQ.
 */
public interface StockEventSender {

    /**
     * Envía el evento y bloquea hasta que el broker lo confirme.
     *
     * @throws EventPublishException si el broker no lo confirmó o el mensaje no tuvo destino
     */
    void send(UUID eventId, String eventType, String payload);
}
```

`outbox/RabbitStockEventSender.java`:

```java
package com.retail.inventory.outbox;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.springframework.amqp.AmqpException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

import com.retail.inventory.config.InventoryProperties;

/**
 * Envía los bytes del JSON tal cual al exchange {@code inventory.events}. Solo cuenta como enviado si el
 * broker confirma (<i>publisher confirms</i>) y el mensaje no fue devuelto por no tener cola
 * ({@code mandatory}). Es la única clase que usa {@code org.springframework.amqp}.
 */
@Component
public class RabbitStockEventSender implements StockEventSender {

    static final String EXCHANGE = "inventory.events";
    static final String ROUTING_KEY = "inventory.stock.changed";

    private final RabbitTemplate rabbitTemplate;
    private final Duration confirmTimeout;

    public RabbitStockEventSender(RabbitTemplate rabbitTemplate, InventoryProperties properties) {
        this.rabbitTemplate = rabbitTemplate;
        this.confirmTimeout = properties.outbox().confirmTimeout();
    }

    @Override
    public void send(UUID eventId, String eventType, String payload) {
        MessageProperties messageProperties = new MessageProperties();
        messageProperties.setMessageId(eventId.toString());
        messageProperties.setType(eventType);
        messageProperties.setContentType(MessageProperties.CONTENT_TYPE_JSON);
        messageProperties.setDeliveryMode(MessageDeliveryMode.PERSISTENT);
        Message message = new Message(payload.getBytes(StandardCharsets.UTF_8), messageProperties);
        CorrelationData correlationData = new CorrelationData(eventId.toString());
        try {
            rabbitTemplate.send(EXCHANGE, ROUTING_KEY, message, correlationData);
            CorrelationData.Confirm confirm = correlationData.getFuture()
                    .get(confirmTimeout.toMillis(), TimeUnit.MILLISECONDS);
            if (!confirm.isAck()) {
                throw new EventPublishException("Broker rejected the message: " + confirm.getReason());
            }
            // El broker devuelve el mensaje (basic.return) antes de confirmarlo si no hay cola destino.
            if (correlationData.getReturned() != null) {
                throw new EventPublishException("Message returned by the broker: "
                        + correlationData.getReturned().getReplyText());
            }
        } catch (AmqpException exception) {
            throw new EventPublishException("Could not send the message: " + exception.getMessage(), exception);
        } catch (TimeoutException exception) {
            throw new EventPublishException("The broker did not confirm within " + confirmTimeout, exception);
        } catch (ExecutionException exception) {
            throw new EventPublishException("Confirmation failed: " + exception.getMessage(), exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new EventPublishException("Interrupted while waiting for the broker confirmation", exception);
        }
    }
}
```

- [ ] **Step 5: Soporte de pruebas con RabbitMQ real**

`src/test/resources/application.properties`:

```properties
# El publicador automático del outbox se apaga en las pruebas: ellas controlan cuándo corre
# (OutboxRelay.publishPending). OutboxPublishingIntegrationTest lo enciende con su propio valor.
inventory.outbox.relay-enabled=false
# Al detener RabbitMQ a propósito, el cliente AMQP registra cada conexión caída; no aporta nada a las pruebas.
logging.level.com.rabbitmq.client.impl.ForgivingExceptionHandler=ERROR
```

`src/test/java/com/retail/inventory/support/RabbitIntegrationTest.java`:

```java
package com.retail.inventory.support;

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

    protected static final String QUEUE = "catalog.inventory-stock-changed";

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

- [ ] **Step 6: Prueba de integración del emisor**

`src/test/java/com/retail/inventory/outbox/RabbitStockEventSenderIntegrationTest.java`:

```java
package com.retail.inventory.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import com.retail.inventory.support.RabbitIntegrationTest;
import com.retail.inventory.support.TestTokens;

@SpringBootTest(properties = "inventory.jwt.secret=" + TestTokens.SECRET)
class RabbitStockEventSenderIntegrationTest extends RabbitIntegrationTest {

    private static final String PAYLOAD = "{\"eventId\":\"x\",\"eventType\":\"InventoryStockChanged\"}";

    @Autowired
    private RabbitStockEventSender sender;

    @Autowired
    private RabbitTemplate rabbitTemplate;

    @AfterEach
    void restartBrokerAndDrainQueue() throws Exception {
        startBroker();
        while (rabbitTemplate.receive(QUEUE, 200) != null) {
            // vacía la cola para no contaminar otras pruebas
        }
    }

    @Test
    void deliversTheExactBodyWithItsPropertiesToTheCatalogQueue() {
        UUID eventId = UUID.randomUUID();

        sender.send(eventId, "InventoryStockChanged", PAYLOAD);

        Message message = rabbitTemplate.receive(QUEUE, 5000);
        assertThat(message).isNotNull();
        assertThat(new String(message.getBody(), StandardCharsets.UTF_8)).isEqualTo(PAYLOAD);
        assertThat(message.getMessageProperties().getMessageId()).isEqualTo(eventId.toString());
        assertThat(message.getMessageProperties().getType()).isEqualTo("InventoryStockChanged");
        assertThat(message.getMessageProperties().getContentType()).isEqualTo("application/json");
        assertThat(message.getMessageProperties().getReceivedDeliveryMode()).isEqualTo(MessageDeliveryMode.PERSISTENT);
    }

    /** Sin cola enlazada a la clave, el broker devuelve el mensaje: no debe contarse como enviado. */
    @Test
    void aMessageReturnedForHavingNoQueueIsNotCountedAsSent() {
        RabbitAdmin admin = new RabbitAdmin(rabbitTemplate);
        Binding binding = BindingBuilder.bind(new Queue(QUEUE)).to(new TopicExchange("inventory.events"))
                .with("inventory.stock.changed");
        admin.removeBinding(binding);
        try {
            assertThatThrownBy(() -> sender.send(UUID.randomUUID(), "InventoryStockChanged", PAYLOAD))
                    .isInstanceOf(EventPublishException.class)
                    .hasMessageContaining("returned");
        } finally {
            admin.declareBinding(binding);
        }
    }

    @Test
    void failsWhileTheBrokerIsDownAndWorksAgainWhenItReturns() throws Exception {
        stopBroker();

        assertThatThrownBy(() -> sender.send(UUID.randomUUID(), "InventoryStockChanged", PAYLOAD))
                .isInstanceOf(EventPublishException.class);

        startBroker();
        sender.send(UUID.randomUUID(), "InventoryStockChanged", PAYLOAD);
        assertThat(rabbitTemplate.receive(QUEUE, 5000)).isNotNull();
    }
}
```

- [ ] **Step 7: Verificar**

Run (desde `inventory-service/`): `./mvnw verify`
Expected: BUILD SUCCESS, 0 fallos. Cifras de referencia: 47 rápidas (45 + 2) y 88 de integración (85 + 3). `ArchitectureTest` en verde (solo `RabbitStockEventSender` usa `org.springframework.amqp`). `InventoryApplicationIntegrationTest.healthEndpointReportsUp` pasa **sin RabbitMQ** en ese contexto: es la prueba de regresión del indicador de salud.

- [ ] **Step 8: Mutaciones de comprobación (no se commitean)**

Para cada una, ejecutar el comando indicado, ver que FALLA y restaurar el archivo:
1. En `application.yml`, cambiar `management.health.rabbit.enabled` a `true` y ejecutar `./mvnw verify -Dit.test=InventoryApplicationIntegrationTest -Dtest=NoSuchTest -Dsurefire.failIfNoSpecifiedTests=false` → falla `healthEndpointReportsUp` (el indicador cuenta a RabbitMQ y no hay broker en ese contexto).
2. En `RabbitStockEventSender`, quitar el bloque `if (correlationData.getReturned() != null) { ... }` y ejecutar `./mvnw verify -Dit.test=RabbitStockEventSenderIntegrationTest -Dtest=NoSuchTest -Dsurefire.failIfNoSpecifiedTests=false` → falla `aMessageReturnedForHavingNoQueueIsNotCountedAsSent`.
`git status` debe quedar sin cambios de esas mutaciones antes del commit.

- [ ] **Step 9: Commit**

```bash
git add inventory-service/pom.xml inventory-service/src
git commit -m "feat: add RabbitMQ stock event sender with publisher confirms and mandatory

Co-Authored-By: Claude <modelo> <noreply@anthropic.com>"
```

---

### Task 2: Relay del outbox

**Files:**
- Modify: `inventory-service/src/main/java/com/retail/inventory/outbox/OutboxEvent.java`
- Modify: `inventory-service/src/main/java/com/retail/inventory/outbox/OutboxRepository.java`
- Create: `inventory-service/src/main/java/com/retail/inventory/outbox/OutboxRelay.java`
- Create: `inventory-service/src/main/java/com/retail/inventory/outbox/OutboxRelayScheduler.java`
- Create (test): `inventory-service/src/test/java/com/retail/inventory/outbox/FakeStockEventSender.java`
- Test: `inventory-service/src/test/java/com/retail/inventory/outbox/OutboxRelayTest.java`
- Test: `inventory-service/src/test/java/com/retail/inventory/outbox/OutboxRelayIntegrationTest.java`

**Interfaces:**
- Consumes: `StockEventSender`, `EventPublishException`, `InventoryProperties.outbox()` (Tarea 1); `OutboxEvent`, `OutboxStatus`, `OutboxRepository` (4.5); `Clock`.
- Produces:
  - `OutboxEvent#markPublished(Instant now)` (status → PUBLISHED, `publishedAt` = now) y `OutboxEvent#recordFailure(String error)` (`attempts + 1`, `lastError` acotado a 500 caracteres, el estado no cambia). Sin setters.
  - `OutboxRepository#findPending(Limit limit): List<OutboxEvent>` (JPQL con el literal `PENDING`, orden por `sequenceNumber`).
  - `@Component OutboxRelay` — constructor `(OutboxRepository, StockEventSender, Clock, InventoryProperties)`; `int publishPending()` (devuelve cuántos quedaron PUBLISHED).
  - `@Configuration OutboxRelayScheduler` — dispara `OutboxRelay#publishPending` con `@Scheduled(fixedDelayString = "${inventory.outbox.poll-interval:500ms}")`; existe solo si `inventory.outbox.relay-enabled` no es `false`.

- [ ] **Step 1: Escribir las pruebas unitarias (deben fallar)**

`src/test/java/com/retail/inventory/outbox/FakeStockEventSender.java`:

```java
package com.retail.inventory.outbox;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Emisor falso de las pruebas unitarias: registra lo enviado y puede simular un broker caído. */
class FakeStockEventSender implements StockEventSender {

    final List<UUID> sent = new ArrayList<>();
    boolean failing;

    @Override
    public void send(UUID eventId, String eventType, String payload) {
        if (failing) {
            throw new EventPublishException("Connection refused");
        }
        sent.add(eventId);
    }
}
```

`src/test/java/com/retail/inventory/outbox/OutboxRelayTest.java`:

```java
package com.retail.inventory.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Limit;

import com.retail.inventory.config.InventoryProperties;

class OutboxRelayTest {

    private static final Instant NOW = Instant.parse("2026-10-12T10:00:00Z");

    private final OutboxRepository outboxRepository = mock(OutboxRepository.class);
    private final FakeStockEventSender sender = new FakeStockEventSender();
    private final InventoryProperties properties = new InventoryProperties(
            new InventoryProperties.Jwt("0123456789abcdef0123456789abcdef", "retail-demo-auth"),
            new InventoryProperties.Sale(5),
            new InventoryProperties.Outbox(java.time.Duration.ofMillis(500), 50, java.time.Duration.ofSeconds(5), true));
    private final OutboxRelay relay = new OutboxRelay(outboxRepository, sender, Clock.fixed(NOW, ZoneOffset.UTC),
            properties);

    private static OutboxEvent event(String sku) {
        return new OutboxEvent(UUID.randomUUID(), sku, "InventoryStockChanged", "{}", NOW);
    }

    @Test
    void publishesThePendingEventsInOrderAndMarksThemPublished() {
        OutboxEvent first = event("A");
        OutboxEvent second = event("B");
        when(outboxRepository.findPending(any(Limit.class))).thenReturn(List.of(first, second));

        int published = relay.publishPending();

        assertThat(published).isEqualTo(2);
        assertThat(sender.sent).containsExactly(first.getEventId(), second.getEventId());
        assertThat(first.getStatus()).isEqualTo(OutboxStatus.PUBLISHED);
        assertThat(first.getPublishedAt()).isEqualTo(NOW);
        assertThat(second.getStatus()).isEqualTo(OutboxStatus.PUBLISHED);
        verify(outboxRepository).save(first);
        verify(outboxRepository).save(second);
    }

    @Test
    void aFailureCountsTheAttemptKeepsTheEventPendingAndStopsTheBatch() {
        OutboxEvent first = event("A");
        OutboxEvent second = event("B");
        when(outboxRepository.findPending(any(Limit.class))).thenReturn(List.of(first, second));
        sender.failing = true;

        int published = relay.publishPending();

        assertThat(published).isZero();
        assertThat(first.getStatus()).isEqualTo(OutboxStatus.PENDING);
        assertThat(first.getAttempts()).isEqualTo(1);
        assertThat(first.getLastError()).isEqualTo("Connection refused");
        assertThat(second.getAttempts()).isZero();
        assertThat(sender.sent).isEmpty();
        verify(outboxRepository).save(first);
        verify(outboxRepository, never()).save(second);
    }

    @Test
    void afterRecoveringItPublishesThePendingEventsInOrder() {
        OutboxEvent first = event("A");
        OutboxEvent second = event("B");
        when(outboxRepository.findPending(any(Limit.class))).thenReturn(List.of(first, second));
        sender.failing = true;
        relay.publishPending();
        relay.publishPending();
        assertThat(first.getAttempts()).isEqualTo(2);

        sender.failing = false;
        int published = relay.publishPending();

        assertThat(published).isEqualTo(2);
        assertThat(sender.sent).containsExactly(first.getEventId(), second.getEventId());
        assertThat(first.getStatus()).isEqualTo(OutboxStatus.PUBLISHED);
        assertThat(first.getAttempts()).isEqualTo(2);
    }

    @Test
    void doesNothingWhenThereAreNoPendingEvents() {
        when(outboxRepository.findPending(any(Limit.class))).thenReturn(List.of());

        assertThat(relay.publishPending()).isZero();
        assertThat(sender.sent).isEmpty();
    }

    @Test
    void theStoredErrorIsBoundedSoAStackTraceNeverFillsTheColumn() {
        OutboxEvent event = event("A");

        event.recordFailure("x".repeat(2000));

        assertThat(event.getLastError()).hasSize(500);
    }
}
```

- [ ] **Step 2: Ejecutarlas y verificar que fallan**

Run: `./mvnw test -Dtest=OutboxRelayTest`
Expected: FAIL de compilación (`OutboxRelay`, `findPending`, `recordFailure` no existen).

- [ ] **Step 3: Mutadores de la entidad**

En `outbox/OutboxEvent.java`:
- Reemplazar el Javadoc de la clase por:

```java
/**
 * Fila del outbox: un evento pendiente de publicar o ya publicado. Sin setters: el estado cambia solo con
 * {@link #markPublished} y {@link #recordFailure}. No tiene {@code @Version}: el publicador corre en una
 * sola instancia con {@code fixedDelay}, así que nadie más modifica la fila a la vez.
 */
```

- Agregar la constante, justo antes del campo `sequenceNumber` (antes de su `@Id`):

```java
    static final int MAXIMUM_ERROR_LENGTH = 500;

```

- Agregar estos métodos antes de `getSequenceNumber()`:

```java
    /** El broker confirmó el evento. */
    public void markPublished(Instant now) {
        this.status = OutboxStatus.PUBLISHED;
        this.publishedAt = now;
    }

    /** Un intento de publicación falló: suma el intento y guarda el motivo (acotado) sin cambiar el estado. */
    public void recordFailure(String error) {
        this.attempts++;
        this.lastError = error.length() > MAXIMUM_ERROR_LENGTH ? error.substring(0, MAXIMUM_ERROR_LENGTH) : error;
    }

```

- [ ] **Step 4: Consulta de pendientes**

Reemplazar `outbox/OutboxRepository.java` por:

```java
package com.retail.inventory.outbox;

import java.util.List;

import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface OutboxRepository extends JpaRepository<OutboxEvent, Long> {

    /**
     * Los pendientes más antiguos primero. El literal {@code PENDING} (y no un parámetro) permite a
     * PostgreSQL usar el índice parcial {@code outbox_pending_index}.
     */
    @Query("select e from OutboxEvent e where e.status = com.retail.inventory.outbox.OutboxStatus.PENDING"
            + " order by e.sequenceNumber")
    List<OutboxEvent> findPending(Limit limit);
}
```

- [ ] **Step 5: Relay y programador**

`outbox/OutboxRelay.java`:

```java
package com.retail.inventory.outbox;

import java.time.Clock;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Component;

import com.retail.inventory.config.InventoryProperties;

/**
 * Publica los eventos pendientes del outbox en orden de {@code sequence_number}. Por cada uno envía y
 * bloquea hasta la confirmación del broker; si confirma lo marca PUBLISHED, y si falla suma el intento,
 * guarda el error, lo deja PENDING y <b>detiene el lote</b> (conserva el orden y no insiste con el resto
 * si RabbitMQ está caído). Nunca mantiene una transacción abierta durante el envío por red: cada
 * actualización de estado es una transacción corta e independiente.
 */
@Component
public class OutboxRelay {

    private static final Logger LOGGER = LoggerFactory.getLogger(OutboxRelay.class);

    private final OutboxRepository outboxRepository;
    private final StockEventSender stockEventSender;
    private final Clock clock;
    private final int batchSize;

    public OutboxRelay(OutboxRepository outboxRepository, StockEventSender stockEventSender, Clock clock,
            InventoryProperties properties) {
        this.outboxRepository = outboxRepository;
        this.stockEventSender = stockEventSender;
        this.clock = clock;
        this.batchSize = properties.outbox().batchSize();
    }

    /** @return cuántos eventos quedaron PUBLISHED en esta ejecución */
    public int publishPending() {
        List<OutboxEvent> pending = outboxRepository.findPending(Limit.of(batchSize));
        int published = 0;
        for (OutboxEvent event : pending) {
            try (MDC.MDCCloseable skuKey = MDC.putCloseable("sku", event.getSku());
                    MDC.MDCCloseable eventIdKey = MDC.putCloseable("eventId", event.getEventId().toString())) {
                try {
                    stockEventSender.send(event.getEventId(), event.getEventType(), event.getPayload());
                } catch (EventPublishException exception) {
                    event.recordFailure(exception.getMessage());
                    outboxRepository.save(event);
                    LOGGER.warn("outbox_publish_failed eventId={} attempts={} error={}", event.getEventId(),
                            event.getAttempts(), exception.getMessage());
                    return published;
                }
                event.markPublished(clock.instant());
                outboxRepository.save(event);
                published++;
                LOGGER.info("outbox_published eventId={} sku={}", event.getEventId(), event.getSku());
            }
        }
        return published;
    }
}
```

`outbox/OutboxRelayScheduler.java`:

```java
package com.retail.inventory.outbox;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * Dispara el publicador cada {@code inventory.outbox.poll-interval} (espera fija entre ejecuciones, así
 * que nunca se solapan). Se apaga con {@code inventory.outbox.relay-enabled=false}; las pruebas lo
 * apagan para controlar cuándo corre {@link OutboxRelay}.
 */
@Configuration
@EnableScheduling
@ConditionalOnProperty(prefix = "inventory.outbox", name = "relay-enabled", havingValue = "true",
        matchIfMissing = true)
public class OutboxRelayScheduler {

    private static final Logger LOGGER = LoggerFactory.getLogger(OutboxRelayScheduler.class);

    private final OutboxRelay outboxRelay;

    public OutboxRelayScheduler(OutboxRelay outboxRelay) {
        this.outboxRelay = outboxRelay;
    }

    @Scheduled(fixedDelayString = "${inventory.outbox.poll-interval:500ms}")
    void run() {
        try {
            outboxRelay.publishPending();
        } catch (RuntimeException exception) {
            // Un fallo inesperado (p. ej., la base) no debe detener el ciclo: se reintenta en la siguiente ejecución.
            LOGGER.error("outbox_relay_failed", exception);
        }
    }
}
```

- [ ] **Step 6: Prueba de integración del relay con PostgreSQL**

`src/test/java/com/retail/inventory/outbox/OutboxRelayIntegrationTest.java`:

```java
package com.retail.inventory.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import com.retail.inventory.support.PostgresIntegrationTest;
import com.retail.inventory.support.TestTokens;

/** El publicador contra PostgreSQL real, con el emisor de RabbitMQ sustituido por un doble. */
@SpringBootTest(properties = { "inventory.jwt.secret=" + TestTokens.SECRET, "inventory.outbox.batch-size=2" })
class OutboxRelayIntegrationTest extends PostgresIntegrationTest {

    @Autowired
    private OutboxRelay relay;

    @Autowired
    private OutboxRepository outboxRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @MockitoBean
    private RabbitStockEventSender sender;

    @BeforeEach
    void emptyOutbox() {
        // Todas las pruebas comparten la base y cada venta deja una fila PENDING: sin vaciarla, el publicador
        // enviaría eventos de otras clases.
        jdbcTemplate.execute("TRUNCATE outbox");
        reset(sender);
    }

    private UUID pendingEvent(String sku) {
        UUID eventId = UUID.randomUUID();
        outboxRepository.saveAndFlush(new OutboxEvent(eventId, sku, "InventoryStockChanged", "{}",
                Instant.parse("2026-10-12T10:00:00.000Z")));
        return eventId;
    }

    private Map<String, Object> row(UUID eventId) {
        return jdbcTemplate.queryForMap("SELECT status, attempts, last_error, published_at FROM outbox"
                + " WHERE event_id = ?", eventId);
    }

    @Test
    void publishesAtMostOneBatchPerRunInOrderAndPersistsTheNewState() {
        UUID first = pendingEvent("A");
        UUID second = pendingEvent("B");
        UUID third = pendingEvent("C");
        doNothing().when(sender).send(any(), any(), any());

        int published = relay.publishPending();

        assertThat(published).isEqualTo(2);
        assertThat(row(first).get("status")).isEqualTo("PUBLISHED");
        assertThat(row(first).get("published_at")).isNotNull();
        assertThat(row(second).get("status")).isEqualTo("PUBLISHED");
        assertThat(row(third).get("status")).isEqualTo("PENDING");

        assertThat(relay.publishPending()).isEqualTo(1);
        assertThat(row(third).get("status")).isEqualTo("PUBLISHED");
    }

    @Test
    void aBrokerFailurePersistsTheAttemptAndTheErrorAndLeavesTheNextOneUntouched() {
        UUID first = pendingEvent("A");
        UUID second = pendingEvent("B");
        doThrow(new EventPublishException("Connection refused")).when(sender).send(any(), any(), any());

        assertThat(relay.publishPending()).isZero();
        assertThat(relay.publishPending()).isZero();

        assertThat(row(first).get("status")).isEqualTo("PENDING");
        assertThat(row(first).get("attempts")).isEqualTo(2);
        assertThat(row(first).get("last_error")).isEqualTo("Connection refused");
        assertThat(row(second).get("attempts")).isEqualTo(0);
    }

    @Test
    void afterTheBrokerRecoversTheEventsAreDeliveredInSequenceOrder() {
        UUID first = pendingEvent("A");
        UUID second = pendingEvent("B");
        doThrow(new EventPublishException("down")).when(sender).send(any(), any(), any());
        relay.publishPending();
        reset(sender);

        int published = relay.publishPending();

        assertThat(published).isEqualTo(2);
        List<String> statuses = jdbcTemplate.queryForList("SELECT status FROM outbox ORDER BY sequence_number",
                String.class);
        assertThat(statuses).containsExactly("PUBLISHED", "PUBLISHED");
        assertThat(row(first).get("attempts")).isEqualTo(1);
        assertThat(row(second).get("attempts")).isEqualTo(0);
    }
}
```

- [ ] **Step 7: Verificar**

Run: `./mvnw verify`
Expected: BUILD SUCCESS, 0 fallos. Cifras de referencia: 52 rápidas (47 + 5 de `OutboxRelayTest`) y 91 de integración (88 + 3). El publicador automático no corre en ninguna prueba (`relay-enabled=false`).

- [ ] **Step 8: Commit**

```bash
git add inventory-service/src
git commit -m "feat: add outbox relay that publishes pending events in order

Co-Authored-By: Claude <modelo> <noreply@anthropic.com>"
```

---

### Task 3: Publicación automática de punta a punta y caída de RabbitMQ

**Files:**
- Test: `inventory-service/src/test/java/com/retail/inventory/outbox/OutboxPublishingIntegrationTest.java`

**Interfaces:**
- Consumes: `OutboxRelayScheduler`, `OutboxRelay`, `RabbitStockEventSender` (Tareas 1 y 2); `RabbitIntegrationTest` (`QUEUE`, `stopBroker`, `startBroker`); `TestStocks.insert`; `ContractAssertions.assertMatchesEventSchema`; el endpoint de venta.
- Produces: la evidencia de los escenarios 8 y de la publicación automática. No hay código de producción nuevo.

- [ ] **Step 1: Escribir la prueba**

`src/test/java/com/retail/inventory/outbox/OutboxPublishingIntegrationTest.java`:

```java
package com.retail.inventory.outbox;

import static com.retail.inventory.support.ContractAssertions.assertMatchesEventSchema;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import tools.jackson.databind.json.JsonMapper;

import com.retail.inventory.support.RabbitIntegrationTest;
import com.retail.inventory.support.TestStocks;
import com.retail.inventory.support.TestTokens;

/**
 * Venta → outbox → publicador automático → cola del Catálogo, con RabbitMQ real, incluida su caída
 * (escenario 8: cero eventos perdidos y en orden).
 */
@SpringBootTest(properties = { "inventory.jwt.secret=" + TestTokens.SECRET,
        "inventory.outbox.relay-enabled=true", "inventory.outbox.poll-interval=100ms" })
@AutoConfigureMockMvc
class OutboxPublishingIntegrationTest extends RabbitIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private RabbitTemplate rabbitTemplate;

    @Autowired
    private JsonMapper jsonMapper;

    @BeforeEach
    @AfterEach
    void cleanUp() throws Exception {
        startBroker();
        // Sin vaciar la tabla, el publicador enviaría eventos de otras clases a la cola de esta prueba.
        jdbcTemplate.execute("TRUNCATE outbox");
        while (rabbitTemplate.receive(QUEUE, 300) != null) {
            // vacía la cola
        }
    }

    private String sell(String sku) throws Exception {
        return mockMvc.perform(post("/api/v1/inventory/" + sku + "/sales")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + TestTokens.withScope("inventory:sell"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"quantity\": 1, \"channel\": \"POS\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
    }

    private Map<String, Object> outboxRow(String sku, long stockVersion) {
        return jdbcTemplate.queryForMap("SELECT status, attempts, last_error, published_at, payload FROM outbox"
                + " WHERE sku = ? AND (payload::jsonb ->> 'stockVersion')::bigint = ?", sku, stockVersion);
    }

    @Test
    void aSaleReachesTheCatalogQueueWithoutAnyoneCallingThePublisher() throws Exception {
        String sku = TestStocks.insert(jdbcTemplate, 5);

        String saleId = jsonMapper.readTree(sell(sku)).get("saleId").asString();

        await().atMost(Duration.ofSeconds(15)).untilAsserted(
                () -> assertThat(outboxRow(sku, 2L).get("status")).isEqualTo("PUBLISHED"));
        Message message = rabbitTemplate.receive(QUEUE, 5000);
        assertThat(message).isNotNull();
        String body = new String(message.getBody(), StandardCharsets.UTF_8);
        assertMatchesEventSchema(body);
        assertThat(message.getMessageProperties().getMessageId()).isEqualTo(saleId);
        assertThat(body).isEqualTo(outboxRow(sku, 2L).get("payload"));
        assertThat(outboxRow(sku, 2L).get("published_at")).isNotNull();
    }

    @Test
    void whenRabbitMqIsDownSalesStillWorkAndTheEventsAreDeliveredInOrderOnceItReturns() throws Exception {
        String sku = TestStocks.insert(jdbcTemplate, 5);
        stopBroker();

        sell(sku);
        sell(sku);
        sell(sku);

        // El publicador reintenta el primero (attempts crece) y no toca los siguientes.
        await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> {
            Map<String, Object> first = outboxRow(sku, 2L);
            assertThat(first.get("status")).isEqualTo("PENDING");
            assertThat((Integer) first.get("attempts")).isGreaterThanOrEqualTo(2);
            assertThat(first.get("last_error")).isNotNull();
        });
        assertThat(outboxRow(sku, 3L).get("attempts")).isEqualTo(0);
        mockMvc.perform(get("/actuator/health")).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("UP"));

        startBroker();

        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
            assertThat(outboxRow(sku, 2L).get("status")).isEqualTo("PUBLISHED");
            assertThat(outboxRow(sku, 3L).get("status")).isEqualTo("PUBLISHED");
            assertThat(outboxRow(sku, 4L).get("status")).isEqualTo("PUBLISHED");
        });
        List<Long> versions = new ArrayList<>();
        Message message;
        while ((message = rabbitTemplate.receive(QUEUE, 2000)) != null) {
            versions.add(jsonMapper.readTree(new String(message.getBody(), StandardCharsets.UTF_8))
                    .get("stockVersion").asLong());
        }
        assertThat(versions).containsExactly(2L, 3L, 4L);
    }
}
```

- [ ] **Step 2: Ejecutarla**

Run: `./mvnw verify -Dit.test=OutboxPublishingIntegrationTest -Dtest=NoSuchTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: PASS (2 pruebas, ~20 s). Estas pruebas verifican código ya implementado en las Tareas 1 y 2: no hay paso rojo previo; la mutación del paso siguiente demuestra que muerden.

- [ ] **Step 3: Mutación de comprobación (no se commitea)**

En `OutboxRelay.publishPending`, reemplazar `return published;` dentro del `catch (EventPublishException ...)` por `continue;` (no detener el lote) y ejecutar el mismo comando: debe FALLAR en `whenRabbitMqIsDown...` (el segundo evento acumula intentos: `attempts` distinto de 0). Restaurar y confirmar con `git status` que no quedan cambios.

- [ ] **Step 4: Verificar todo**

Run: `./mvnw verify`
Expected: BUILD SUCCESS. Cifras de referencia: 52 rápidas y 93 de integración (91 + 2). Repetir `./mvnw verify -Dit.test=OutboxPublishingIntegrationTest -Dtest=NoSuchTest -Dsurefire.failIfNoSpecifiedTests=false` una vez más: debe pasar siempre.

- [ ] **Step 5: Commit**

```bash
git add inventory-service/src
git commit -m "test: verify automatic publishing and RabbitMQ outage recovery

Co-Authored-By: Claude <modelo> <noreply@anthropic.com>"
```

---

### Task 4: `GET /api/v1/inventory/outbox`

**Files:**
- Modify: `inventory-service/src/main/java/com/retail/inventory/outbox/OutboxRepository.java`
- Create: `inventory-service/src/main/java/com/retail/inventory/outbox/OutboxEventResponse.java`
- Create: `inventory-service/src/main/java/com/retail/inventory/outbox/ListOutboxEventsService.java`
- Create: `inventory-service/src/main/java/com/retail/inventory/outbox/OutboxController.java`
- Delete (test): `inventory-service/src/test/java/com/retail/inventory/security/SecuredProbeController.java`
- Modify (test): `inventory-service/src/test/java/com/retail/inventory/security/SecurityIntegrationTest.java`
- Modify (test): `inventory-service/src/test/java/com/retail/inventory/security/GenerateTokenScriptIntegrationTest.java`
- Test: `inventory-service/src/test/java/com/retail/inventory/outbox/OutboxEndpointIntegrationTest.java`

**Interfaces:**
- Consumes: `OutboxEvent` (getters y `markPublished`), `OutboxStatus`, `OutboxRepository` (Tareas 2 y 4.5); la regla de seguridad existente que deja pública `GET /api/v1/inventory/outbox` (antes de la regla de `{sku}`); el manejo de `TypeMismatchException` de la 4.3.
- Produces:
  - `OutboxRepository#findAllByOrderBySequenceNumberDesc(Limit)` y `#findByStatusOrderBySequenceNumberDesc(OutboxStatus, Limit)`.
  - `OutboxEventResponse(UUID eventId, String sku, OutboxStatus status, int attempts, String lastError, Instant occurredAt, Instant publishedAt)` con `OutboxEventResponse.from(OutboxEvent)`.
  - `ListOutboxEventsService#list(OutboxStatus status): List<OutboxEvent>` (máximo 50, más recientes primero; `status` nulo = ambos estados).
  - Fin de la sonda: la ruta del outbox ya es real, así que `SecuredProbeController` desaparece.

- [ ] **Step 1: Escribir la prueba de integración (debe fallar)**

`src/test/java/com/retail/inventory/outbox/OutboxEndpointIntegrationTest.java`:

```java
package com.retail.inventory.outbox;

import static com.retail.inventory.support.ContractAssertions.assertMatchesSchema;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import com.retail.inventory.support.PostgresIntegrationTest;
import com.retail.inventory.support.TestTokens;

@SpringBootTest(properties = "inventory.jwt.secret=" + TestTokens.SECRET)
@AutoConfigureMockMvc
class OutboxEndpointIntegrationTest extends PostgresIntegrationTest {

    private static final String PATH = "/api/v1/inventory/outbox";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private OutboxRepository outboxRepository;

    @Autowired
    private JsonMapper jsonMapper;

    @BeforeEach
    void emptyOutbox() {
        // La base es compartida y cada venta deja una fila: el orden y el límite solo son deterministas con la tabla vacía.
        jdbcTemplate.execute("TRUNCATE outbox");
    }

    private UUID insert(String sku, boolean published) {
        UUID eventId = UUID.randomUUID();
        OutboxEvent event = new OutboxEvent(eventId, sku, "InventoryStockChanged", "{}",
                Instant.parse("2026-10-12T10:00:00.123Z"));
        if (published) {
            event.markPublished(Instant.parse("2026-10-12T10:00:01.456Z"));
        }
        outboxRepository.saveAndFlush(event);
        return eventId;
    }

    private JsonNode fetchJson(String path) throws Exception {
        return jsonMapper.readTree(mockMvc.perform(get(path))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andReturn().getResponse().getContentAsString());
    }

    @Test
    void listsTheMostRecentEventsFirstWithoutAnyToken() throws Exception {
        UUID oldest = insert("A", true);
        insert("B", false);
        UUID newest = insert("C", false);

        JsonNode events = fetchJson(PATH);

        assertThat(events).hasSize(3);
        assertThat(events.get(0).get("eventId").asString()).isEqualTo(newest.toString());
        assertThat(events.get(2).get("eventId").asString()).isEqualTo(oldest.toString());
        events.forEach(event -> assertMatchesSchema(event.toString(), "OutboxEvent"));
        assertThat(events.get(2).get("status").asString()).isEqualTo("PUBLISHED");
        assertThat(events.get(2).get("publishedAt").asString()).isEqualTo("2026-10-12T10:00:01.456Z");
        assertThat(events.get(0).get("publishedAt").isNull()).isTrue();
        assertThat(events.get(0).get("lastError").isNull()).isTrue();
    }

    @Test
    void returnsAtMostFiftyEvents() throws Exception {
        for (int index = 0; index < 55; index++) {
            insert("SKU-" + index, false);
        }

        assertThat(fetchJson(PATH)).hasSize(50);
    }

    @Test
    void filtersByStatus() throws Exception {
        insert("A", true);
        insert("B", false);
        insert("C", false);

        assertThat(fetchJson(PATH + "?status=PENDING")).hasSize(2);
        JsonNode published = fetchJson(PATH + "?status=PUBLISHED");
        assertThat(published).hasSize(1);
        assertThat(published.get(0).get("sku").asString()).isEqualTo("A");
    }

    @Test
    void anUnknownStatusIsAValidationError() throws Exception {
        for (String invalid : new String[] {"DONE", "pending"}) {
            String body = mockMvc.perform(get(PATH + "?status=" + invalid))
                    .andExpect(status().isBadRequest())
                    .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                    .andExpect(jsonPath("$.type").value("https://api.retail-demo.local/problems/validation-error"))
                    .andExpect(jsonPath("$.errors[0].field").value("status"))
                    .andReturn().getResponse().getContentAsString();

            assertMatchesSchema(body, "ValidationProblem");
        }
    }

    @Test
    void anEmptyOutboxIsAnEmptyList() throws Exception {
        assertThat(fetchJson(PATH)).isEmpty();
    }
}
```

- [ ] **Step 2: Ejecutarla y verificar que falla**

Run: `./mvnw verify -Dit.test=OutboxEndpointIntegrationTest -Dtest=NoSuchTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: FAIL — con la sonda de prueba fuera del contexto (esta clase no la importa), `GET /outbox` responde `404 not-found` (la toma `/{sku}`) en lugar de la lista.

- [ ] **Step 3: Implementar**

En `outbox/OutboxRepository.java` agregar, debajo de `findPending`:

```java

    /** Los eventos más recientes primero (diagnóstico). */
    List<OutboxEvent> findAllByOrderBySequenceNumberDesc(Limit limit);

    List<OutboxEvent> findByStatusOrderBySequenceNumberDesc(OutboxStatus status, Limit limit);
```

`outbox/OutboxEventResponse.java`:

```java
package com.retail.inventory.outbox;

import java.time.Instant;
import java.util.UUID;

/** Un evento del outbox para diagnóstico (schema {@code OutboxEvent} del contrato). */
public record OutboxEventResponse(UUID eventId, String sku, OutboxStatus status, int attempts, String lastError,
        Instant occurredAt, Instant publishedAt) {

    public static OutboxEventResponse from(OutboxEvent event) {
        return new OutboxEventResponse(event.getEventId(), event.getSku(), event.getStatus(), event.getAttempts(),
                event.getLastError(), event.getOccurredAt(), event.getPublishedAt());
    }
}
```

`outbox/ListOutboxEventsService.java`:

```java
package com.retail.inventory.outbox;

import java.util.List;

import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Caso de uso: listar los eventos más recientes del outbox, con filtro opcional por estado. */
@Service
public class ListOutboxEventsService {

    static final int MAXIMUM_EVENTS = 50;

    private final OutboxRepository outboxRepository;

    public ListOutboxEventsService(OutboxRepository outboxRepository) {
        this.outboxRepository = outboxRepository;
    }

    /** @param status si no es nulo, solo los eventos en ese estado */
    @Transactional(readOnly = true)
    public List<OutboxEvent> list(OutboxStatus status) {
        Limit limit = Limit.of(MAXIMUM_EVENTS);
        return status == null
                ? outboxRepository.findAllByOrderBySequenceNumberDesc(limit)
                : outboxRepository.findByStatusOrderBySequenceNumberDesc(status, limit);
    }
}
```

`outbox/OutboxController.java`:

```java
package com.retail.inventory.outbox;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Diagnóstico para la demo (escenario "RabbitMQ caído"). Es público por diseño: en un entorno real
 * estaría protegido o no existiría.
 */
@RestController
@RequestMapping("/api/v1/inventory/outbox")
public class OutboxController {

    private final ListOutboxEventsService listOutboxEventsService;

    public OutboxController(ListOutboxEventsService listOutboxEventsService) {
        this.listOutboxEventsService = listOutboxEventsService;
    }

    @GetMapping
    public List<OutboxEventResponse> listOutboxEvents(@RequestParam(required = false) OutboxStatus status) {
        return listOutboxEventsService.list(status).stream().map(OutboxEventResponse::from).toList();
    }
}
```

- [ ] **Step 4: Retirar la sonda de pruebas**

1. Eliminar `src/test/java/com/retail/inventory/security/SecuredProbeController.java`.
2. En `security/SecurityIntegrationTest.java`: quitar la anotación `@Import(SecuredProbeController.class)` y el import `org.springframework.context.annotation.Import`; reemplazar la prueba `outboxDiagnosticIsPublicAndNotTakenAsASku` por:

```java
    @Test
    void outboxDiagnosticIsPublicAndNotTakenAsASku() throws Exception {
        mockMvc.perform(get("/api/v1/inventory/outbox"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$").isArray());
    }
```

3. En `security/GenerateTokenScriptIntegrationTest.java`: quitar `@Import(SecuredProbeController.class)` y el import `org.springframework.context.annotation.Import`.

- [ ] **Step 5: Verificar**

Run: `./mvnw verify`
Expected: BUILD SUCCESS, 0 fallos. Cifras de referencia: 52 rápidas y 98 de integración (93 + 5). Todas las pruebas de seguridad del outbox (`outboxWithTrailingSlashIsNotPublic`, `postToOutboxIsDeniedWithoutToken`, etc.) siguen en verde.

- [ ] **Step 6: Commit**

```bash
git add -A inventory-service/src
git commit -m "feat: add GET /api/v1/inventory/outbox diagnostic endpoint

Co-Authored-By: Claude <modelo> <noreply@anthropic.com>"
```

---

### Task 5: Contenedor, Compose, prueba de humo y documentación

**Files:**
- Modify: `inventory-service/Dockerfile`
- Modify: `docker-compose.yml`
- Create: `scripts/smoke-inventory-service.sh`
- Modify: `README.md`
- Modify: `CLAUDE.md`
- Modify: `docs/superpowers/specs/2026-10-11-inventario-design.md`

**Interfaces:**
- Consumes: todo lo anterior; `scripts/generate-token.sh`.
- Produces: imagen sin privilegios, conexión a RabbitMQ dentro de Compose, `scripts/smoke-inventory-service.sh` (cierre de la fase 4) y documentación al día.

- [ ] **Step 1: Usuario sin privilegios en el contenedor**

En `inventory-service/Dockerfile`, etapa de ejecución, entre la línea `COPY --from=build ... app.jar` y `EXPOSE 8081`, agregar:

```dockerfile
# El servicio no necesita privilegios: corre con un usuario sin permisos especiales.
RUN addgroup -S app && adduser -S -G app app
USER app
```

- [ ] **Step 2: Variables de RabbitMQ en el Compose**

En `docker-compose.yml`, servicio `inventory-service`, bajo `environment:` y después de `SPRING_DATASOURCE_PASSWORD`, agregar:

```yaml
      SPRING_RABBITMQ_HOST: rabbitmq
      SPRING_RABBITMQ_PORT: "5672"
      SPRING_RABBITMQ_USERNAME: retail
      SPRING_RABBITMQ_PASSWORD: retail-demo
```

- [ ] **Step 3: Prueba de humo**

`scripts/smoke-inventory-service.sh` (ejecutable: `chmod +x`):

```bash
#!/usr/bin/env bash
# Prueba de humo del Servicio de Inventario dentro de Compose (cierre de la fase 4, spec de Inventario §9):
# una venta devuelve 201, su evento pasa a PUBLISHED en el outbox y el mensaje aparece en la cola del
# Catálogo. También comprueba que el contenedor no corre como root. Requiere Docker, curl y jq; la
# primera vez construye la imagen (internet y unos minutos).
# Mientras el Catálogo no consuma la cola (fase 5), el mensaje permanece en ella; cuando exista, la
# comprobación de la cola dependerá de que el Catálogo esté detenido.
set -uo pipefail   # sin -e a propósito: se ejecutan todos los checks y se agrega el resultado

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT" || exit 1
command -v curl >/dev/null && command -v jq >/dev/null || { echo "FAIL: se requieren curl y jq en el equipo"; exit 1; }

# Proyecto y puertos propios: no toca ni choca con un stack de desarrollo en marcha.
export COMPOSE_PROJECT_NAME="retail-smoke"
export RABBITMQ_AMQP_PORT=36672 RABBITMQ_MGMT_PORT=36673 INVENTORY_DB_PORT=36441 CATALOG_DB_PORT=36442 INVENTORY_SERVICE_PORT=36081
export INVENTORY_DB_PASSWORD="inventory-demo" CATALOG_DB_PASSWORD="catalog-demo"
BASE_URL="http://127.0.0.1:${INVENTORY_SERVICE_PORT}"
QUEUE="catalog.inventory-stock-changed"
SKU="GORRA-SPORT-NEGRA"

status=0
cleanup() { docker compose down -v --remove-orphans >/dev/null 2>&1 || true; }
trap cleanup EXIT

check() { # check <descripción> <valor obtenido> <valor esperado>
  if [[ "$2" == "$3" ]]; then echo "PASS: $1"; else echo "FAIL: $1 (obtenido: $2, esperado: $3)"; status=1; fi
}

echo "== Arranque =="
if up_output="$(docker compose up -d --wait --wait-timeout 300 --build inventory-service 2>&1)"; then
  echo "PASS: inventory-service llega a healthy"
else
  echo "FAIL: inventory-service no llegó a healthy"; echo "$up_output"; docker compose logs --tail 50 inventory-service; exit 1
fi
container_user="$(docker compose exec -T inventory-service id -u | tr -d '\r')"
check "el contenedor no corre como root" "$([[ "$container_user" != "0" ]] && echo yes || echo no)" "yes"

echo "== Venta =="
token="$("$ROOT/scripts/generate-token.sh" pos)"
response_file="$(mktemp)"
http_status="$(curl -s -o "$response_file" -w '%{http_code}' -X POST "$BASE_URL/api/v1/inventory/$SKU/sales" \
  -H "Authorization: Bearer $token" -H 'Content-Type: application/json' -d '{"quantity":1,"channel":"POS"}')"
check "la venta responde 201" "$http_status" "201"
sale_id="$(jq -r '.saleId' "$response_file")"
rm -f "$response_file"

echo "== Publicación =="
published="no"
for _ in $(seq 1 30); do
  published="$(curl -s "$BASE_URL/api/v1/inventory/outbox?status=PUBLISHED" \
    | jq -r --arg id "$sale_id" 'any(.[]; .eventId == $id)')"
  [[ "$published" == "true" ]] && break
  sleep 1
done
check "el evento de la venta pasa a PUBLISHED en el outbox" "$published" "true"

queue_messages="$(docker compose exec -T rabbitmq rabbitmqctl -q list_queues name messages | awk -v q="$QUEUE" '$1 == q {print $2}' | tr -d '\r')"
check "la cola $QUEUE tiene el mensaje" "$([[ "${queue_messages:-0}" -ge 1 ]] && echo yes || echo no)" "yes"

[[ $status -eq 0 ]] && echo "Prueba de humo OK" || echo "Prueba de humo FALLÓ"
exit $status
```

- [ ] **Step 4: Documentación**

`README.md`:
- En el árbol "Estructura", la línea de `scripts/` ya dice "Scripts de verificación y utilidades": sin cambios.
- En la sección "Servicio de Inventario", reemplazar el párrafo que dice "Hasta la sub-tarea 4.6 nada lo publica a RabbitMQ; para verlo:" y el bloque `bash` que le sigue por:

````markdown
Un publicador interno revisa el outbox cada 500 ms y envía los eventos PENDING a RabbitMQ (exchange
`inventory.events`), esperando la confirmación del broker; si RabbitMQ está caído, la venta sigue
funcionando y el evento queda PENDING hasta que vuelva (cero eventos perdidos, en orden). Para verlo
(público, solo para la demo):

```bash
curl -s "http://localhost:8081/api/v1/inventory/outbox?status=PENDING"   # eventos sin publicar
curl -s "http://localhost:8081/api/v1/inventory/outbox"                  # los 50 más recientes
```

Prueba de humo del servicio dentro de Compose (venta → outbox PUBLISHED → mensaje en la cola del
Catálogo; usa su propio proyecto y puertos, requiere Docker, curl y jq):

```bash
scripts/smoke-inventory-service.sh
```
````

- En la tabla de puertos o en "Verificación", agregar bajo los dos scripts existentes la línea `scripts/smoke-inventory-service.sh   # servicio de Inventario en Compose: venta, outbox y cola (fase 4)`.

`CLAUDE.md`, sección "Comandos": después del punto de `scripts/verify-contracts.sh`, agregar: `` - `scripts/smoke-inventory-service.sh` prueba de humo de Inventario dentro de Compose (requiere Docker, curl y jq). ``

`docs/superpowers/specs/2026-10-11-inventario-design.md` (leer antes el texto exacto):
1. §9 "Pendientes heredados": eliminar las cinco filas resueltas en la 4.6 (healthcheck de RabbitMQ; contenedor como root; `TRUNCATE outbox`; consulta con el literal `PENDING`; mutadores de `OutboxEvent`). La fila de `eventId` en el MDC y `outbox_recorded` se reemplaza por esta, porque el MDC del relay se resuelve pero el registro previo al commit no: `| \`outbox_recorded\` se registra antes del commit: reformularlo o bajarlo a \`DEBUG\` (solo informativo) | Opcional |`. Deben quedar esa fila y "Agregar `catalog-service` al `.dockerignore` de la raíz | Fase 5".
2. §8: agregar al final de la sección "4.6: publicar" un punto: `- El relay corre en una sola instancia (\`fixedDelay\`); \`OutboxEvent\` no tiene \`@Version\` porque nadie más modifica la fila. Escalar a varias instancias exigiría \`SELECT ... FOR UPDATE SKIP LOCKED\`.`
3. §9 "Cierre de la fase 4": agregar la frase `El script existe: \`scripts/smoke-inventory-service.sh\`.` al final del párrafo.

- [ ] **Step 5: Verificación completa**

Con los puertos del script libres (usa su propio proyecto) y sin un stack `retail` en marcha en los puertos por defecto (`docker compose ps`; si lo hay, `docker compose down -v` antes), desde la raíz del repositorio y cada comando por separado:

```bash
cd inventory-service && ./mvnw verify
```
Expected: BUILD SUCCESS, 52 pruebas rápidas y 98 de integración.

```bash
scripts/smoke-inventory-service.sh
```
Expected: todas las líneas `PASS` y `Prueba de humo OK` (código de salida 0).

Escenario 8 a mano, reconstruyendo la imagen (sin `--build` Compose reutiliza la anterior):

```bash
docker compose up -d --wait --build inventory-service
TOKEN="$(scripts/generate-token.sh pos)"
docker compose stop rabbitmq
for i in 1 2 3; do curl -s -X POST -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" -d '{"quantity":1,"channel":"POS"}' -o /dev/null -w '%{http_code} ' http://localhost:8081/api/v1/inventory/MOCHILA-TRAIL-20L/sales; done; echo
curl -s http://localhost:8081/actuator/health
curl -s http://localhost:8081/api/v1/inventory/outbox | jq -c '.[] | {status,attempts,lastError}'
docker compose start rabbitmq
```
Expected: `201 201 201`; salud `UP` con RabbitMQ detenido; el evento más antiguo `PENDING` con `attempts` creciendo y `lastError` informado, los otros dos `PENDING` con `attempts` 0. Pasados unos segundos desde el `start`, `curl -s "http://localhost:8081/api/v1/inventory/outbox?status=PENDING"` devuelve `[]`, los tres eventos están `PUBLISHED` y `docker compose exec -T rabbitmq rabbitmqctl -q list_queues name messages` muestra 3 mensajes en `catalog.inventory-stock-changed`. Terminar con `docker compose down -v`.

- [ ] **Step 6: Commit**

```bash
git add inventory-service/Dockerfile docker-compose.yml scripts/smoke-inventory-service.sh README.md CLAUDE.md docs/superpowers/specs/2026-10-11-inventario-design.md
git commit -m "feat: run as non-root, connect to RabbitMQ in Compose and add phase 4 smoke test

Co-Authored-By: Claude <modelo> <noreply@anthropic.com>"
```

---

## Autorrevisión

- **Cobertura de la spec §8 (4.6):** relay con `fixedDelay` 500 ms, lote de 50, orden por `sequence_number`, marca PUBLISHED en transacción corta y detiene el lote ante un fallo (T2); nunca una transacción abierta durante el envío (T2: el relay no es transaccional, cada `save` es corto); `StockEventSender` como única interfaz y `RabbitStockEventSender` con `message_id`, `type`, `content_type`, entrega persistente, exchange/clave, confirms y `mandatory` (T1); tiempos de 5 s y 2 s (T1); at-least-once documentado (spec); `GET /outbox` con límite, orden, filtro y 400 (T4); propiedades `poll-interval`, `batch-size`, `confirm-timeout`, `relay-enabled` (T1); pruebas unitarias con emisor falso (T2), integración con RabbitMQ real incluida la devolución y la caída (T1 y T3), `GET /outbox` (T4).
- **Pendientes heredados de 4.6:** salud de RabbitMQ (T1, con mutación), contenedor no root (T5), `TRUNCATE outbox` (T2, T3, T4), literal `PENDING` en la consulta (T2), mutadores de `OutboxEvent` con error acotado (T2), `eventId` en el MDC del relay (T2). El registro `outbox_recorded` antes del commit queda como fila opcional en la spec (solo informativo).
- **Cierre de la fase 4:** `scripts/smoke-inventory-service.sh` (T5).
- **Consistencia de tipos:** `InventoryProperties(Jwt, Sale, Outbox)`; `StockEventSender#send(UUID, String, String)`; `OutboxRelay#publishPending(): int`; `OutboxRepository#findPending(Limit)`, `findAllByOrderBySequenceNumberDesc(Limit)`, `findByStatusOrderBySequenceNumberDesc(OutboxStatus, Limit)`; `OutboxEvent#markPublished(Instant)` / `recordFailure(String)`.
- **Conteos de referencia:** rápidas 45 → 47 (T1) → 52 (T2); integración 85 → 88 (T1) → 91 (T2) → 93 (T3) → 98 (T4).
- **Verificado en una copia temporal** antes de escribir el plan: 52 rápidas + 98 de integración en verde; las mutaciones (salud de RabbitMQ habilitada) hacen fallar la prueba de salud; la prueba de humo pasa dentro de Compose; y el escenario 8 se comprobó en Compose (RabbitMQ detenido → 201 ×3, salud `UP`, primer evento con intentos crecientes; al volver, los tres `PUBLISHED` y 3 mensajes en la cola).
