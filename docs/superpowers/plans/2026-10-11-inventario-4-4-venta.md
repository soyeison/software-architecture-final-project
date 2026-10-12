# Inventario 4.4: venta con concurrencia — Plan de implementación

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Implementar `POST /api/v1/inventory/{sku}/sales` en `inventory-service`: descontar stock de forma segura bajo concurrencia (bloqueo optimista con reintento acotado), con entrada estricta y errores `insufficient-stock` / `concurrent-modification` según el contrato, y cerrar los pendientes heredados de la 4.4.

**Architecture:** Paquete `sale/` (controlador → `RegisterSaleService` → `StockRepository`/`Stock`). El servicio no usa `@Transactional`: cada intento corre en su propia transacción con `TransactionTemplate` y `OptimisticRetry` (clase aparte, genérica) envuelve la transacción y reintenta solo ante `OptimisticLockingFailureException`. `StockChange` (en `stock/`) es el resultado que la 4.5 convertirá en evento del outbox; la 4.4 no escribe el evento.

**Tech Stack:** Spring Boot 4.1.1 (Spring Data JPA, `TransactionTemplate`), Jackson 3, Hibernate 7, PostgreSQL 17 con Testcontainers, JUnit 5, Mockito, MockMvc.

**Spec:** `docs/superpowers/specs/2026-10-11-inventario-design.md` (sección 7 y "Pendientes heredados"). Contrato: `docs/contracts/openapi/inventory-service.yaml` (schemas `SaleRequest`, `SaleResponse`, `InsufficientStockProblem`, `Problem`, `ValidationProblem`).

## Global Constraints

- Spring Boot `4.1.1`, Java `25`, Maven solo con el wrapper `./mvnw` desde `inventory-service/`. Paquete base `com.retail.inventory`.
- **Nombres completos, sin abreviaturas** (salvo `SKU`, `JWT`, `UUID`, `DTO`, `HTTP`, `JSON`, `DLQ` y unidades). Código en inglés; comentarios, Javadoc y documentación en español.
- Commits en inglés, breves, Conventional Commits, con la línea `Co-Authored-By: Claude <modelo> <noreply@anthropic.com>`.
- La ruta del repositorio contiene espacios: toda ruta en comandos va entre comillas dobles.
- `RegisterSaleService` **no es `@Transactional`**; reintenta solo el conflicto de versión, hasta `inventory.sale.max-attempts` (por defecto `5`); al agotarlos lanza `StockConflictException` (409 `concurrent-modification`). Si en el reintento el stock ya no alcanza, el resultado es `InsufficientStockException` (409 `insufficient-stock`) y no se repite.
- `saveAndFlush` fuerza la comprobación de versión dentro del intento; la versión devuelta es la posterior al guardado (la de la respuesta y la del futuro evento). `saleId` = `eventId` del `StockChange`.
- Entrada estricta: Jackson rechaza propiedades desconocidas y no convierte `1.5` ni `"1"` en entero.
- Dependencias permitidas entre paquetes: `sale → stock`, `sale → outbox`, `outbox → stock`; `error` y `security` pueden usar las excepciones de las funcionalidades. Los controladores no usan repositorios. `stock/` y `sale/` (salvo sus controladores) no dependen de `org.springframework.web` ni de `RabbitStockEventSender`.
- Las pruebas de integración se nombran `*IntegrationTest` (failsafe, `./mvnw verify`, requieren Docker); las demás corren con `./mvnw test`. Toda prueba que venda usa un SKU creado con `TestStocks.insert` (nunca un SKU de la semilla).
- Línea base antes de empezar: `./mvnw test` → 28 pruebas; `./mvnw verify` → 28 + 49 de integración.

## Review Focus

1. **Carrera de la última unidad** (stock 1, dos ventas simultáneas): exactamente una `201` y una `409 insufficient-stock`, nunca dos `201` ni stock negativo. Se fija en `SaleConcurrencyIntegrationTest` (Tarea 4), repetida 20 veces.
2. **Ráfaga sobre el mismo SKU** (20 hilos, stock 10): stock final = inicial − ventas exitosas, nunca negativo, sin 5xx. Se fija en la Tarea 4.
3. **Cuerpos de entrada degenerados** (`quantity` 0, negativo, `1.5`, `"1"`, desbordado, nulo, canal desconocido, propiedad extra, `{}`, texto que no es JSON, cuerpo vacío): siempre `400 validation-error` y el stock intacto. Se fija en `SaleEndpointIntegrationTest` (Tarea 2).
4. **Reintentos agotados**: `409 concurrent-modification` válido contra el schema `Problem` (no 500) y `saveAndFlush` intentado exactamente `maxAttempts` veces. Se fija en `RegisterSaleServiceTest` y `GlobalExceptionHandlerTest` (Tarea 2).
5. **Violación de clase en el cuerpo** (restricción que no pertenece a un campo): `400 validation-error` con `errors` bien formado (`field` y `message` presentes). Se fija en `GlobalExceptionHandlerTest` (Tarea 3).

---

## Estructura de archivos

| Archivo | Responsabilidad |
|---|---|
| `inventory-service/src/main/java/com/retail/inventory/stock/Channel.java` | Enum `POS` / `ONLINE` |
| `.../stock/StockChange.java` | Resultado de un cambio de stock (vocabulario común `sale` ↔ `outbox`) |
| `.../sale/OptimisticRetry.java` | Reintento acotado ante conflicto de versión |
| `.../sale/StockConflictException.java` | Reintentos agotados |
| `.../sale/SaleConfiguration.java` | Bean `OptimisticRetry` según `inventory.sale.max-attempts` |
| `.../sale/RegisterSaleService.java` | Caso de uso de venta (transacción por intento) |
| `.../sale/SaleRequest.java`, `SaleResponse.java` | DTO de entrada y salida |
| `.../sale/SaleController.java` | `POST /api/v1/inventory/{sku}/sales` |
| `.../config/InventoryProperties.java` | Agrega `Sale(maxAttempts)` |
| `.../error/ProblemType.java`, `GlobalExceptionHandler.java` | Nuevos tipos 409 y violaciones de clase |
| `.../resources/application.yml` | Jackson estricto |
| `src/test/.../sale/*Test.java` | Pruebas de la funcionalidad |
| `src/test/.../error/ProbeController.java`, `GlobalExceptionHandlerTest.java` | Sondas y pruebas de errores |
| `src/test/.../security/SecuredProbeController.java`, `SecurityIntegrationTest.java`, `GenerateTokenScriptIntegrationTest.java` | Retiran la sonda de venta y usan SKU propios |
| `README.md`, spec de Inventario | Documentación al día |

---

### Task 1: Vocabulario de venta, `OptimisticRetry` y configuración

**Files:**
- Create: `inventory-service/src/main/java/com/retail/inventory/stock/Channel.java`
- Create: `inventory-service/src/main/java/com/retail/inventory/stock/StockChange.java`
- Create: `inventory-service/src/main/java/com/retail/inventory/sale/StockConflictException.java`
- Create: `inventory-service/src/main/java/com/retail/inventory/sale/OptimisticRetry.java`
- Create: `inventory-service/src/main/java/com/retail/inventory/sale/SaleConfiguration.java`
- Modify: `inventory-service/src/main/java/com/retail/inventory/config/InventoryProperties.java`
- Test: `inventory-service/src/test/java/com/retail/inventory/sale/OptimisticRetryTest.java`
- Modify (test): `inventory-service/src/test/java/com/retail/inventory/config/InventoryPropertiesTest.java`

**Interfaces:**
- Produces:
  - `enum Channel { POS, ONLINE }` (`stock/`).
  - `record StockChange(UUID eventId, String sku, int newStock, long stockVersion, Channel channel, Instant occurredAt)` (`stock/`).
  - `class StockConflictException extends RuntimeException` — `StockConflictException(String sku, int attempts, Throwable cause)` (`sale/`).
  - `class OptimisticRetry` — `OptimisticRetry(int maxAttempts)` (lanza `IllegalArgumentException` si < 1); `<T> T run(Supplier<T> operation)`; `int maxAttempts()` (`sale/`). Reintenta solo `OptimisticLockingFailureException`; tras `maxAttempts` intentos relanza la última.
  - `InventoryProperties.Sale(int maxAttempts)` con `@DefaultValue("5") @Min(1)`; `InventoryProperties.sale()` nunca es nulo (valor por defecto).
  - Bean `OptimisticRetry` (`SaleConfiguration`).

- [ ] **Step 1: Escribir las pruebas (deben fallar)**

`inventory-service/src/test/java/com/retail/inventory/sale/OptimisticRetryTest.java`:

```java
package com.retail.inventory.sale;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.springframework.dao.OptimisticLockingFailureException;

class OptimisticRetryTest {

    private final OptimisticRetry retry = new OptimisticRetry(3);

    @Test
    void returnsTheResultOnTheFirstAttempt() {
        AtomicInteger calls = new AtomicInteger();

        String result = retry.run(() -> {
            calls.incrementAndGet();
            return "done";
        });

        assertThat(result).isEqualTo("done");
        assertThat(calls).hasValue(1);
    }

    @Test
    void succeedsAfterSomeConflicts() {
        AtomicInteger calls = new AtomicInteger();

        String result = retry.run(() -> {
            if (calls.incrementAndGet() < 3) {
                throw new OptimisticLockingFailureException("conflict");
            }
            return "done";
        });

        assertThat(result).isEqualTo("done");
        assertThat(calls).hasValue(3);
    }

    @Test
    void givesUpAfterTheMaximumAttemptsAndRethrowsTheConflict() {
        AtomicInteger calls = new AtomicInteger();

        assertThatThrownBy(() -> retry.run(() -> {
            calls.incrementAndGet();
            throw new OptimisticLockingFailureException("conflict");
        })).isInstanceOf(OptimisticLockingFailureException.class);

        assertThat(calls).hasValue(3);
    }

    @Test
    void doesNotRetryOtherExceptions() {
        AtomicInteger calls = new AtomicInteger();

        assertThatThrownBy(() -> retry.run(() -> {
            calls.incrementAndGet();
            throw new IllegalStateException("boom");
        })).isInstanceOf(IllegalStateException.class);

        assertThat(calls).hasValue(1);
    }

    @Test
    void rejectsLessThanOneAttempt() {
        assertThatThrownBy(() -> new OptimisticRetry(0)).isInstanceOf(IllegalArgumentException.class);
    }
}
```

En `config/InventoryPropertiesTest.java`, antes de `failsToStartWhenSecretIsMissing`, agregar:

```java
    @Test
    void saleMaxAttemptsDefaultsToFive() {
        contextRunner.withPropertyValues("inventory.jwt.secret=" + SECRET_OF_32_BYTES)
                .run(context -> assertThat(context.getBean(InventoryProperties.class).sale().maxAttempts())
                        .isEqualTo(5));
    }

    @Test
    void saleMaxAttemptsCanBeConfiguredButNotBelowOne() {
        contextRunner.withPropertyValues("inventory.jwt.secret=" + SECRET_OF_32_BYTES, "inventory.sale.max-attempts=3")
                .run(context -> assertThat(context.getBean(InventoryProperties.class).sale().maxAttempts())
                        .isEqualTo(3));
        contextRunner.withPropertyValues("inventory.jwt.secret=" + SECRET_OF_32_BYTES, "inventory.sale.max-attempts=0")
                .run(context -> assertThat(context).hasFailed());
    }
```

- [ ] **Step 2: Ejecutarlas y verificar que fallan**

Run (desde `inventory-service/`): `./mvnw test -Dtest='OptimisticRetryTest,InventoryPropertiesTest'`
Expected: FAIL de compilación (`OptimisticRetry` y `sale()` no existen).

- [ ] **Step 3: Implementar**

`stock/Channel.java`:

```java
package com.retail.inventory.stock;

/** Canal de venta que origina un cambio de stock (schema {@code Channel} del contrato). */
public enum Channel {
    POS,
    ONLINE
}
```

`stock/StockChange.java`:

```java
package com.retail.inventory.stock;

import java.time.Instant;
import java.util.UUID;

/**
 * Resultado de un cambio de stock: el vocabulario común entre {@code sale} (que lo produce) y
 * {@code outbox} (que lo consume). {@code eventId} es el {@code saleId} de la respuesta y
 * {@code stockVersion} es la versión posterior al guardado.
 */
public record StockChange(UUID eventId, String sku, int newStock, long stockVersion, Channel channel,
        Instant occurredAt) {
}
```

`sale/StockConflictException.java`:

```java
package com.retail.inventory.sale;

/**
 * Se agotaron los reintentos por modificación concurrente del mismo SKU. No conoce HTTP: el manejador
 * global de errores la traduce.
 */
public class StockConflictException extends RuntimeException {

    public StockConflictException(String sku, int attempts, Throwable cause) {
        super("Stock of SKU " + sku + " changed concurrently, please retry (gave up after " + attempts
                + " attempts)", cause);
    }
}
```

`sale/OptimisticRetry.java`:

```java
package com.retail.inventory.sale;

import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.OptimisticLockingFailureException;

/**
 * Repite una operación cuando falla por bloqueo optimista (otra transacción cambió el registro entre
 * la lectura y el guardado). Solo reintenta ese conflicto: cualquier otra excepción se propaga sin
 * reintentos. La operación debe abrir su propia transacción en cada intento.
 */
public class OptimisticRetry {

    private static final Logger LOGGER = LoggerFactory.getLogger(OptimisticRetry.class);

    private final int maxAttempts;

    public OptimisticRetry(int maxAttempts) {
        if (maxAttempts < 1) {
            throw new IllegalArgumentException("maxAttempts must be at least 1");
        }
        this.maxAttempts = maxAttempts;
    }

    /**
     * Ejecuta la operación hasta {@code maxAttempts} veces.
     *
     * @throws OptimisticLockingFailureException la última, si se agotaron los intentos
     */
    public <T> T run(Supplier<T> operation) {
        int attempt = 1;
        while (true) {
            try {
                return operation.get();
            } catch (OptimisticLockingFailureException exception) {
                if (attempt >= maxAttempts) {
                    throw exception;
                }
                LOGGER.warn("optimistic_lock_retry attempt={} maxAttempts={}", attempt, maxAttempts);
                attempt++;
            }
        }
    }

    public int maxAttempts() {
        return maxAttempts;
    }
}
```

`sale/SaleConfiguration.java`:

```java
package com.retail.inventory.sale;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.retail.inventory.config.InventoryProperties;

@Configuration
public class SaleConfiguration {

    @Bean
    OptimisticRetry optimisticRetry(InventoryProperties properties) {
        return new OptimisticRetry(properties.sale().maxAttempts());
    }
}
```

En `config/InventoryProperties.java`:
- Cambiar la declaración del record por `public record InventoryProperties(@Valid @NotNull Jwt jwt, @Valid @DefaultValue Sale sale) {`.
- Agregar los imports `jakarta.validation.constraints.Min` (junto a los demás `jakarta.validation.constraints`) y `org.springframework.boot.context.properties.bind.DefaultValue` (junto a `ConfigurationProperties`).
- Antes del Javadoc de `Jwt`, agregar:

```java
    /**
     * Parámetros de la venta: intentos máximos ante conflictos de versión (bloqueo optimista).
     */
    public record Sale(@DefaultValue("5") @Min(1) int maxAttempts) {
    }

```

- [ ] **Step 4: Verificar**

Run: `./mvnw test`
Expected: PASS — 35 pruebas (28 + 5 de `OptimisticRetryTest` + 2 de propiedades), incluida `ArchitectureTest` (los ciclos entre paquetes siguen en cero: `sale → config`, `sale → stock`).

- [ ] **Step 5: Commit**

```bash
git add inventory-service/src
git commit -m "feat: add sale vocabulary, optimistic retry and sale properties

Co-Authored-By: Claude <modelo> <noreply@anthropic.com>"
```

---

### Task 2: `POST /api/v1/inventory/{sku}/sales`

**Files:**
- Create: `inventory-service/src/main/java/com/retail/inventory/sale/RegisterSaleService.java`
- Create: `inventory-service/src/main/java/com/retail/inventory/sale/SaleRequest.java`
- Create: `inventory-service/src/main/java/com/retail/inventory/sale/SaleResponse.java`
- Create: `inventory-service/src/main/java/com/retail/inventory/sale/SaleController.java`
- Modify: `inventory-service/src/main/java/com/retail/inventory/error/ProblemType.java`
- Modify: `inventory-service/src/main/java/com/retail/inventory/error/GlobalExceptionHandler.java`
- Modify: `inventory-service/src/main/resources/application.yml`
- Test: `inventory-service/src/test/java/com/retail/inventory/sale/RegisterSaleServiceTest.java`
- Test: `inventory-service/src/test/java/com/retail/inventory/sale/SaleEndpointIntegrationTest.java`
- Modify (test): `.../error/ProbeController.java`, `.../error/GlobalExceptionHandlerTest.java`
- Modify (test): `.../security/SecuredProbeController.java`, `.../security/SecurityIntegrationTest.java`, `.../security/GenerateTokenScriptIntegrationTest.java`

**Interfaces:**
- Consumes (Tarea 1 y 4.3): `Channel`, `StockChange`, `StockConflictException(String, int, Throwable)`, `OptimisticRetry#run/maxAttempts`, `Stock#sell(int, Instant)`, `Stock#getQuantity()/getVersion()`, `StockRepository` (`findById`, `saveAndFlush`), `SkuNotFoundException`, `InsufficientStockException#getAvailableStock()/getRequestedQuantity()`, `TestStocks.insert(JdbcTemplate, int)`, `Clock`, `TransactionTemplate` (lo provee Spring Boot).
- Produces:
  - `RegisterSaleService.register(String sku, int quantity, Channel channel): StockChange`. Lanza `SkuNotFoundException`, `InsufficientStockException`, `StockConflictException`.
  - `SaleRequest(Integer quantity, Channel channel)` (`@NotNull @Min(1)`, `@NotNull`); `SaleResponse(UUID saleId, String sku, int quantity, Channel channel, int remainingStock, long version, Instant occurredAt)` con `SaleResponse.from(SaleRequest, StockChange)`.
  - `ProblemType.INSUFFICIENT_STOCK` (`insufficient-stock`, 409) y `ProblemType.CONCURRENT_MODIFICATION` (`concurrent-modification`, 409).

- [ ] **Step 1: Escribir las pruebas del servicio y del manejador (deben fallar)**

`inventory-service/src/test/java/com/retail/inventory/sale/RegisterSaleServiceTest.java`:

```java
package com.retail.inventory.sale;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import com.retail.inventory.stock.Channel;
import com.retail.inventory.stock.Stock;
import com.retail.inventory.stock.StockChange;
import com.retail.inventory.stock.StockRepository;

class RegisterSaleServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-11T12:00:00Z");

    private final StockRepository stockRepository = mock(StockRepository.class);
    private final TransactionTemplate transactionTemplate = mock(TransactionTemplate.class);
    private final RegisterSaleService service = new RegisterSaleService(stockRepository, transactionTemplate,
            new OptimisticRetry(3), Clock.fixed(NOW, ZoneOffset.UTC));

    @SuppressWarnings("unchecked")
    private void runCallbacksInline() {
        when(transactionTemplate.execute(any())).thenAnswer(invocation ->
                ((TransactionCallback<Object>) invocation.getArgument(0)).doInTransaction(mock(TransactionStatus.class)));
    }

    @Test
    void persistentVersionConflictEndsInStockConflictAfterAllAttempts() {
        runCallbacksInline();
        Stock stock = new Stock("TENIS-RUNNER-42", 5, NOW);
        when(stockRepository.findById("TENIS-RUNNER-42")).thenReturn(Optional.of(stock));
        when(stockRepository.saveAndFlush(any())).thenThrow(new ObjectOptimisticLockingFailureException(Stock.class, "TENIS-RUNNER-42"));

        assertThatThrownBy(() -> service.register("TENIS-RUNNER-42", 1, Channel.POS))
                .isInstanceOf(StockConflictException.class);

        verify(stockRepository, times(3)).saveAndFlush(any());
    }

    @Test
    void conflictThatClearsOnRetrySucceeds() {
        runCallbacksInline();
        // Cada intento lee un registro nuevo, como la base real. Un Stock sin guardar no tiene versión
        // (la asigna JPA): se simula la posterior al guardado.
        when(stockRepository.findById("TENIS-RUNNER-42")).thenAnswer(invocation -> {
            Stock stock = spy(new Stock("TENIS-RUNNER-42", 5, NOW));
            doReturn(2L).when(stock).getVersion();
            return Optional.of(stock);
        });
        when(stockRepository.saveAndFlush(any()))
                .thenThrow(new ObjectOptimisticLockingFailureException(Stock.class, "TENIS-RUNNER-42"))
                .thenAnswer(invocation -> invocation.getArgument(0));

        StockChange change = service.register("TENIS-RUNNER-42", 2, Channel.ONLINE);

        assertThat(change.newStock()).isEqualTo(3);
        assertThat(change.stockVersion()).isEqualTo(2L);
        assertThat(change.channel()).isEqualTo(Channel.ONLINE);
        assertThat(change.occurredAt()).isEqualTo(NOW);
        assertThat(change.eventId()).isNotNull();
    }
}
```

En `error/ProbeController.java` agregar el import `com.retail.inventory.sale.StockConflictException` (como último bloque de imports, tras `org.springframework...`, separado por una línea en blanco) y, antes de `failure()`:

```java
    @GetMapping("/probe/conflict")
    String conflict() {
        throw new StockConflictException("TENIS-RUNNER-42", 5, new IllegalStateException("version conflict"));
    }
```

En `error/GlobalExceptionHandlerTest.java`, antes de `unexpectedExceptionReturnsInternalErrorWithoutInternalDetails`, agregar:

```java
    @Test
    void exhaustedRetriesReturnConcurrentModificationConflict() throws Exception {
        String body = mockMvc.perform(get("/probe/conflict"))
                .andExpect(status().isConflict())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.type").value(PROBLEM_TYPE_BASE + "concurrent-modification"))
                .andReturn().getResponse().getContentAsString();

        assertMatchesSchema(body, "Problem");
    }
```

- [ ] **Step 2: Escribir la prueba de integración del endpoint**

`inventory-service/src/test/java/com/retail/inventory/sale/SaleEndpointIntegrationTest.java`:

```java
package com.retail.inventory.sale;

import static com.retail.inventory.support.ContractAssertions.assertMatchesSchema;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import com.retail.inventory.support.PostgresIntegrationTest;
import com.retail.inventory.support.TestStocks;
import com.retail.inventory.support.TestTokens;

@SpringBootTest(properties = "inventory.jwt.secret=" + TestTokens.SECRET)
@AutoConfigureMockMvc
class SaleEndpointIntegrationTest extends PostgresIntegrationTest {

    private static final String PROBLEM_TYPE_BASE = "https://api.retail-demo.local/problems/";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private ResultActions sell(String sku, String body) throws Exception {
        return mockMvc.perform(post("/api/v1/inventory/" + sku + "/sales")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + TestTokens.withScope("inventory:sell"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    private int storedQuantity(String sku) {
        return jdbcTemplate.queryForObject("SELECT quantity FROM stock WHERE sku = ?", Integer.class, sku);
    }

    @Test
    void sellDeductsStockBumpsVersionAndMatchesTheContract() throws Exception {
        String sku = TestStocks.insert(jdbcTemplate, 5);

        String body = sell(sku, "{\"quantity\": 2, \"channel\": \"POS\"}")
                .andExpect(status().isCreated())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.sku").value(sku))
                .andExpect(jsonPath("$.quantity").value(2))
                .andExpect(jsonPath("$.channel").value("POS"))
                .andExpect(jsonPath("$.remainingStock").value(3))
                .andExpect(jsonPath("$.version").value(2))
                .andReturn().getResponse().getContentAsString();

        assertMatchesSchema(body, "SaleResponse");
        assertThat(storedQuantity(sku)).isEqualTo(3);
    }

    @Test
    void sellingTheWholeStockLeavesZero() throws Exception {
        String sku = TestStocks.insert(jdbcTemplate, 1);

        sell(sku, "{\"quantity\": 1, \"channel\": \"ONLINE\"}")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.remainingStock").value(0));
        assertThat(storedQuantity(sku)).isZero();
    }

    @Test
    void insufficientStockIsAConflictWithTheAvailableQuantityAndLeavesStockIntact() throws Exception {
        String sku = TestStocks.insert(jdbcTemplate, 1);

        String body = sell(sku, "{\"quantity\": 2, \"channel\": \"POS\"}")
                .andExpect(status().isConflict())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.type").value(PROBLEM_TYPE_BASE + "insufficient-stock"))
                .andExpect(jsonPath("$.availableStock").value(1))
                .andExpect(jsonPath("$.requestedQuantity").value(2))
                .andReturn().getResponse().getContentAsString();

        assertMatchesSchema(body, "InsufficientStockProblem");
        assertThat(storedQuantity(sku)).isEqualTo(1);
    }

    @Test
    void sellingFromEmptyStockIsAConflict() throws Exception {
        String sku = TestStocks.insert(jdbcTemplate, 0);

        sell(sku, "{\"quantity\": 1, \"channel\": \"POS\"}")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.availableStock").value(0));
    }

    @Test
    void unknownSkuIsNotFound() throws Exception {
        String body = sell("DOES-NOT-EXIST", "{\"quantity\": 1, \"channel\": \"POS\"}")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.type").value(PROBLEM_TYPE_BASE + "not-found"))
                .andReturn().getResponse().getContentAsString();

        assertMatchesSchema(body, "Problem");
    }

    @Test
    void invalidBodiesAreRejectedAsValidationErrorsAndDoNotTouchTheStock() throws Exception {
        String sku = TestStocks.insert(jdbcTemplate, 5);
        String[] invalidBodies = {
                "{\"quantity\": 0, \"channel\": \"POS\"}",
                "{\"quantity\": -1, \"channel\": \"POS\"}",
                "{\"quantity\": 1, \"channel\": \"PHONE\"}",
                "{\"quantity\": 1}",
                "{\"channel\": \"POS\"}",
                "{\"quantity\": null, \"channel\": \"POS\"}",
                "{\"quantity\": 1, \"channel\": \"POS\", \"discount\": 10}",
                "{\"quantity\": 1.5, \"channel\": \"POS\"}",
                "{\"quantity\": \"1\", \"channel\": \"POS\"}",
                "{\"quantity\": 99999999999, \"channel\": \"POS\"}",
                "{}",
                "not json"
        };

        for (String invalidBody : invalidBodies) {
            String body = sell(sku, invalidBody)
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.type").value(PROBLEM_TYPE_BASE + "validation-error"))
                    .andReturn().getResponse().getContentAsString();
            assertMatchesSchema(body, "ValidationProblem");
        }
        assertThat(storedQuantity(sku)).isEqualTo(5);
    }

    @Test
    void emptyBodyIsAValidationError() throws Exception {
        String sku = TestStocks.insert(jdbcTemplate, 5);

        mockMvc.perform(post("/api/v1/inventory/" + sku + "/sales")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + TestTokens.withScope("inventory:sell"))
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value(PROBLEM_TYPE_BASE + "validation-error"));
    }
}
```

- [ ] **Step 3: Ejecutarlas y verificar que fallan**

Run: `./mvnw test -Dtest='RegisterSaleServiceTest,GlobalExceptionHandlerTest'`
Expected: FAIL de compilación (`RegisterSaleService` no existe).

- [ ] **Step 4: Implementar servicio, DTO y controlador**

`sale/RegisterSaleService.java`:

```java
package com.retail.inventory.sale;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import com.retail.inventory.stock.Channel;
import com.retail.inventory.stock.SkuNotFoundException;
import com.retail.inventory.stock.Stock;
import com.retail.inventory.stock.StockChange;
import com.retail.inventory.stock.StockRepository;

/**
 * Caso de uso: registrar una venta. No usa {@code @Transactional}: cada intento corre en su propia
 * transacción con {@link TransactionTemplate} y el reintento envuelve la transacción (una anotación
 * en el mismo bean se ignoraría si un método se llamara a sí mismo).
 */
@Service
public class RegisterSaleService {

    private static final Logger LOGGER = LoggerFactory.getLogger(RegisterSaleService.class);

    private final StockRepository stockRepository;
    private final TransactionTemplate transactionTemplate;
    private final OptimisticRetry optimisticRetry;
    private final Clock clock;

    public RegisterSaleService(StockRepository stockRepository, TransactionTemplate transactionTemplate,
            OptimisticRetry optimisticRetry, Clock clock) {
        this.stockRepository = stockRepository;
        this.transactionTemplate = transactionTemplate;
        this.optimisticRetry = optimisticRetry;
        this.clock = clock;
    }

    /**
     * Descuenta {@code quantity} unidades del SKU.
     *
     * @throws SkuNotFoundException si el SKU no existe
     * @throws com.retail.inventory.stock.InsufficientStockException si no hay unidades suficientes
     * @throws StockConflictException si el conflicto de versión persiste tras agotar los reintentos
     */
    public StockChange register(String sku, int quantity, Channel channel) {
        MDC.put("sku", sku);
        try {
            StockChange change = optimisticRetry.run(
                    () -> transactionTemplate.execute(status -> sell(sku, quantity, channel)));
            LOGGER.info("sale_registered sku={} channel={} saleId={} remaining={}", sku, channel, change.eventId(),
                    change.newStock());
            return change;
        } catch (OptimisticLockingFailureException exception) {
            LOGGER.warn("sale_conflict sku={} attempts={}", sku, optimisticRetry.maxAttempts());
            throw new StockConflictException(sku, optimisticRetry.maxAttempts(), exception);
        } finally {
            MDC.remove("sku");
        }
    }

    private StockChange sell(String sku, int quantity, Channel channel) {
        Stock stock = stockRepository.findById(sku).orElseThrow(() -> new SkuNotFoundException(sku));
        Instant now = clock.instant();
        stock.sell(quantity, now);
        // saveAndFlush fuerza la comprobación de versión dentro del intento y devuelve la versión posterior.
        Stock saved = stockRepository.saveAndFlush(stock);
        return new StockChange(UUID.randomUUID(), sku, saved.getQuantity(), saved.getVersion(), channel, now);
    }
}
```

`sale/SaleRequest.java`:

```java
package com.retail.inventory.sale;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

import com.retail.inventory.stock.Channel;

/** Cuerpo de {@code POST /api/v1/inventory/{sku}/sales} (schema {@code SaleRequest} del contrato). */
public record SaleRequest(@NotNull @Min(1) Integer quantity, @NotNull Channel channel) {
}
```

`sale/SaleResponse.java`:

```java
package com.retail.inventory.sale;

import java.time.Instant;
import java.util.UUID;

import com.retail.inventory.stock.Channel;
import com.retail.inventory.stock.StockChange;

/** Respuesta de la venta (schema {@code SaleResponse} del contrato). */
public record SaleResponse(UUID saleId, String sku, int quantity, Channel channel, int remainingStock,
        long version, Instant occurredAt) {

    public static SaleResponse from(SaleRequest request, StockChange change) {
        return new SaleResponse(change.eventId(), change.sku(), request.quantity(), change.channel(),
                change.newStock(), change.stockVersion(), change.occurredAt());
    }
}
```

`sale/SaleController.java`:

```java
package com.retail.inventory.sale;

import jakarta.validation.Valid;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.retail.inventory.stock.StockChange;

@RestController
@RequestMapping("/api/v1/inventory")
public class SaleController {

    private final RegisterSaleService registerSaleService;

    public SaleController(RegisterSaleService registerSaleService) {
        this.registerSaleService = registerSaleService;
    }

    @PostMapping("/{sku}/sales")
    @ResponseStatus(HttpStatus.CREATED)
    public SaleResponse registerSale(@PathVariable String sku, @Valid @RequestBody SaleRequest request) {
        StockChange change = registerSaleService.register(sku, request.quantity(), request.channel());
        return SaleResponse.from(request, change);
    }
}
```

- [ ] **Step 5: Tipos de problema 409 y su traducción**

En `error/ProblemType.java`, después de `NOT_FOUND(...)`, agregar:

```java
    INSUFFICIENT_STOCK("insufficient-stock", "Insufficient stock", HttpStatus.CONFLICT),
    CONCURRENT_MODIFICATION("concurrent-modification", "Concurrent modification", HttpStatus.CONFLICT),
```

En `error/GlobalExceptionHandler.java` agregar los imports `com.retail.inventory.sale.StockConflictException` y `com.retail.inventory.stock.InsufficientStockException` (en el bloque `com.retail...`, en orden alfabético con `SkuNotFoundException`) y estos métodos justo antes de `handleExceptionInternal`:

```java
    @ExceptionHandler(InsufficientStockException.class)
    public ResponseEntity<ProblemDetail> handleInsufficientStock(InsufficientStockException exception,
            HttpServletRequest request) {
        ProblemDetail problem = ProblemType.INSUFFICIENT_STOCK
                .toProblemDetail(exception.getMessage(), request.getRequestURI());
        problem.setProperty("availableStock", exception.getAvailableStock());
        problem.setProperty("requestedQuantity", exception.getRequestedQuantity());
        return ResponseEntity.status(problem.getStatus()).body(problem);
    }

    @ExceptionHandler(StockConflictException.class)
    public ResponseEntity<ProblemDetail> handleStockConflict(StockConflictException exception,
            HttpServletRequest request) {
        ProblemDetail problem = ProblemType.CONCURRENT_MODIFICATION
                .toProblemDetail("Stock changed concurrently, please retry", request.getRequestURI());
        return ResponseEntity.status(problem.getStatus()).body(problem);
    }
```

(El método `handleExceptionInternal` lleva encima su Javadoc `/** Da el tipo genérico ... */`: los dos métodos nuevos van antes de ese Javadoc.)

- [ ] **Step 6: Entrada estricta (Jackson)**

En `src/main/resources/application.yml`, bajo `spring:` (después de `jpa:`) y antes de `server:`, agregar:

```yaml
  jackson:
    # Entrada estricta, como dice el contrato: nada de propiedades desconocidas ni de 1.5 / "1" como entero.
    deserialization:
      fail-on-unknown-properties: true
      accept-float-as-int: false
    mapper:
      allow-coercion-of-scalars: false
```

(Las tres opciones hacen falta: sin ellas, la prueba de cuerpos inválidos recibe `201` para alguno de los cuerpos con propiedad extra, `1.5` o `"1"`.)

- [ ] **Step 7: Retirar la sonda de venta y migrar las pruebas de seguridad**

En `security/SecuredProbeController.java` eliminar el método `registerSale` (con `@PostMapping` y `@ResponseStatus`) y los imports que queden sin uso (`HttpStatus`, `PathVariable`, `PostMapping`, `ResponseStatus`). Sustituir el Javadoc de la clase por:

```java
/**
 * Controlador solo de pruebas con la ruta del contrato que aún no tiene controlador real: la del
 * outbox llega en 4.6, que lo elimina (la de consulta de stock es real desde 4.3 y la de venta desde
 * 4.4). {@link TestComponent} lo excluye del escaneo de {@code @SpringBootTest}; se importa
 * explícitamente.
 */
```

En `security/SecurityIntegrationTest.java`:
- Agregar imports `org.springframework.jdbc.core.JdbcTemplate` (junto a `org.springframework.http.MediaType`) y `com.retail.inventory.support.TestStocks` (antes de `TestTokens`).
- Agregar el campo (debajo de `mockMvc`):

```java
    @Autowired
    private JdbcTemplate jdbcTemplate;
```

- Reemplazar la prueba `saleWithSellScopeIsAllowed` por:

```java
    @Test
    void saleWithSellScopeIsAllowed() throws Exception {
        String sku = TestStocks.insert(jdbcTemplate, 10);

        mockMvc.perform(post("/api/v1/inventory/" + sku + "/sales")
                        .header(HttpHeaders.AUTHORIZATION, bearer(TestTokens.withScope("inventory:sell")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(SALE_BODY))
                .andExpect(status().isCreated());
    }
```

(Las demás pruebas que usan `SALES_PATH` terminan en 401/403 antes de llegar al servicio y no tocan la semilla.)

En `security/GenerateTokenScriptIntegrationTest.java`:
- Mismos imports (`JdbcTemplate`, `TestStocks`) y el mismo campo `jdbcTemplate`.
- Reemplazar `posAndOnlineTokensCanSell` por:

```java
    @Test
    void posAndOnlineTokensCanSell() throws Exception {
        String salesPath = "/api/v1/inventory/" + TestStocks.insert(jdbcTemplate, 10) + "/sales";
        for (String profile : new String[] {"pos", "online"}) {
            mockMvc.perform(post(salesPath).header(HttpHeaders.AUTHORIZATION, "Bearer " + generate(profile).output())
                            .contentType(MediaType.APPLICATION_JSON).content(SALE_BODY))
                    .andExpect(status().isCreated());
        }
    }
```

- [ ] **Step 8: Verificar**

Run: `./mvnw verify`
Expected: BUILD SUCCESS, 0 fallos. Cifras de referencia: 38 pruebas rápidas (35 + 2 de `RegisterSaleServiceTest` + 1 del 409 en el manejador) y 56 de integración (49 + 7 de `SaleEndpointIntegrationTest`). `ArchitectureTest` debe seguir en verde sin `allowEmptyShould`.

- [ ] **Step 9: Commit**

```bash
git add inventory-service/src
git commit -m "feat: add POST /api/v1/inventory/{sku}/sales with optimistic retry

Co-Authored-By: Claude <modelo> <noreply@anthropic.com>"
```

---

### Task 3: `validation-error` con violaciones de clase o sin mensaje

**Files:**
- Modify: `inventory-service/src/main/java/com/retail/inventory/error/GlobalExceptionHandler.java`
- Modify (test): `inventory-service/src/test/java/com/retail/inventory/error/ProbeController.java`
- Modify (test): `inventory-service/src/test/java/com/retail/inventory/error/GlobalExceptionHandlerTest.java`

**Interfaces:**
- Consumes: `FieldViolation(String field, String message)`.
- Produces: el manejador reporta como `FieldViolation` también las violaciones de clase (`field` = nombre del objeto, p. ej. `rangeRequest`) y reemplaza un mensaje nulo por `"is invalid"` (el schema exige `field` y `message`).

- [ ] **Step 1: Sonda con restricción de clase y prueba (debe fallar)**

En `error/ProbeController.java` agregar los imports (en orden, junto a los `jakarta`/`java` existentes): `java.lang.annotation.ElementType`, `java.lang.annotation.Retention`, `java.lang.annotation.RetentionPolicy`, `java.lang.annotation.Target` (bloque `java`, antes de `jakarta`), y `jakarta.validation.Constraint`, `jakarta.validation.ConstraintValidator`, `jakarta.validation.ConstraintValidatorContext`, `jakarta.validation.Payload`. Antes de `record ProbeRequest`, agregar:

```java
    /** Restricción de clase (no de campo), para probar las violaciones a nivel de objeto. */
    @Target(ElementType.TYPE)
    @Retention(RetentionPolicy.RUNTIME)
    @Constraint(validatedBy = RangeValidator.class)
    @interface ValidRange {

        String message() default "from must not be greater than to";

        Class<?>[] groups() default {};

        Class<? extends Payload>[] payload() default {};
    }

    static class RangeValidator implements ConstraintValidator<ValidRange, RangeRequest> {

        @Override
        public boolean isValid(RangeRequest request, ConstraintValidatorContext context) {
            return request.from() <= request.to();
        }
    }

    @ValidRange
    record RangeRequest(int from, int to) {
    }
```

y, antes de `page(...)`:

```java
    @PostMapping("/probe/range")
    RangeRequest range(@Valid @RequestBody RangeRequest request) {
        return request;
    }
```

En `error/GlobalExceptionHandlerTest.java`, antes de `malformedJsonReturnsValidationProblemWithEmptyErrors`, agregar:

```java
    @Test
    void classLevelViolationIsReportedAsValidationProblemWithTheObjectAsField() throws Exception {
        String body = mockMvc.perform(post("/probe/range")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"from\": 5, \"to\": 1}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value(PROBLEM_TYPE_BASE + "validation-error"))
                .andExpect(jsonPath("$.errors[0].field").value("rangeRequest"))
                .andExpect(jsonPath("$.errors[0].message").value("from must not be greater than to"))
                .andReturn().getResponse().getContentAsString();

        assertMatchesSchema(body, "ValidationProblem");
    }
```

- [ ] **Step 2: Ejecutarla y verificar que falla**

Run: `./mvnw test -Dtest=GlobalExceptionHandlerTest`
Expected: FAIL — `errors` vacío (`$.errors[0].field` sin valor): hoy el manejador ignora las violaciones que no son de campo.

- [ ] **Step 3: Implementar**

En `error/GlobalExceptionHandler.java`:
- Agregar `import java.util.stream.Stream;` (después de `java.util.List`).
- Reemplazar el cuerpo de `handleMethodArgumentNotValid` por:

```java
        List<FieldViolation> fieldViolations = exception.getBindingResult().getFieldErrors().stream()
                .map(error -> new FieldViolation(error.getField(), messageOrDefault(error.getDefaultMessage())))
                .toList();
        // Violaciones de clase (no de un campo): se reportan con el nombre del objeto como campo.
        List<FieldViolation> objectViolations = exception.getBindingResult().getGlobalErrors().stream()
                .map(error -> new FieldViolation(error.getObjectName(), messageOrDefault(error.getDefaultMessage())))
                .toList();
        List<FieldViolation> violations = Stream.concat(fieldViolations.stream(), objectViolations.stream()).toList();
        return validationProblem("The request has invalid fields", violations, request);
```

- En `handleHandlerMethodValidationException`, cambiar `error.getDefaultMessage()` por `messageOrDefault(error.getDefaultMessage())`.
- Antes de `requestPath`, agregar:

```java
    /** El schema exige {@code message}: si la restricción no trae texto, se usa uno genérico. */
    private static String messageOrDefault(String message) {
        return message != null ? message : "is invalid";
    }
```

- [ ] **Step 4: Verificar**

Run: `./mvnw test`
Expected: PASS — 39 pruebas rápidas (38 + 1).

- [ ] **Step 5: Commit**

```bash
git add inventory-service/src
git commit -m "fix: report class-level and message-less violations as validation errors

Co-Authored-By: Claude <modelo> <noreply@anthropic.com>"
```

---

### Task 4: Pruebas de concurrencia (última unidad y ráfaga)

**Files:**
- Test: `inventory-service/src/test/java/com/retail/inventory/sale/SaleConcurrencyIntegrationTest.java`

**Interfaces:**
- Consumes: el endpoint de la Tarea 2, `TestStocks.insert`, `TestTokens.withScope`, `PostgresIntegrationTest`.
- Produces: la evidencia de concurrencia de los escenarios 1 a 4 (lado Inventario). No hay código de producción nuevo.

- [ ] **Step 1: Escribir las pruebas**

`inventory-service/src/test/java/com/retail/inventory/sale/SaleConcurrencyIntegrationTest.java`:

```java
package com.retail.inventory.sale;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;

import com.retail.inventory.support.PostgresIntegrationTest;
import com.retail.inventory.support.TestStocks;
import com.retail.inventory.support.TestTokens;

/**
 * Concurrencia real contra PostgreSQL: las ventas simultáneas nunca dejan el stock negativo ni
 * pierden descuentos (escenarios 1 a 4, lado Inventario).
 */
@SpringBootTest(properties = "inventory.jwt.secret=" + TestTokens.SECRET)
@AutoConfigureMockMvc
class SaleConcurrencyIntegrationTest extends PostgresIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    /** Resultado de una venta: estado HTTP y, si falló, el tipo de problema (último tramo de {@code type}). */
    record Outcome(int status, String problem) {
    }

    /** Lanza una venta de 1 unidad por hilo, todas a la vez, y devuelve el resultado de cada una. */
    private List<Outcome> sellConcurrently(String sku, int threads) throws Exception {
        String token = "Bearer " + TestTokens.withScope("inventory:sell");
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch go = new CountDownLatch(1);
        try {
            List<Future<Outcome>> futures = new ArrayList<>();
            for (int index = 0; index < threads; index++) {
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    go.await();
                    MockHttpServletResponse response = mockMvc.perform(post("/api/v1/inventory/" + sku + "/sales")
                                    .header(HttpHeaders.AUTHORIZATION, token)
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content("{\"quantity\": 1, \"channel\": \"POS\"}"))
                            .andReturn().getResponse();
                    String body = response.getContentAsString();
                    String problem = response.getStatus() < 400 ? "" : body.replaceAll(".*problems/([a-z-]+).*", "$1");
                    return new Outcome(response.getStatus(), problem);
                }));
            }
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            go.countDown();
            List<Outcome> outcomes = new ArrayList<>();
            for (Future<Outcome> future : futures) {
                outcomes.add(future.get(60, TimeUnit.SECONDS));
            }
            return outcomes;
        } finally {
            executor.shutdownNow();
        }
    }

    private int storedQuantity(String sku) {
        return jdbcTemplate.queryForObject("SELECT quantity FROM stock WHERE sku = ?", Integer.class, sku);
    }

    /** Escenario de la última unidad: exactamente una venta gana y la otra recibe insufficient-stock. */
    @RepeatedTest(20)
    void lastUnitRaceHasExactlyOneWinner() throws Exception {
        String sku = TestStocks.insert(jdbcTemplate, 1);

        List<Outcome> outcomes = sellConcurrently(sku, 2);

        assertThat(outcomes).containsExactlyInAnyOrder(new Outcome(201, ""), new Outcome(409, "insufficient-stock"));
        assertThat(storedQuantity(sku)).isZero();
    }

    /** Invariante bajo ráfaga: stock final = inicial − ventas exitosas, nunca negativo y sin 5xx. */
    @Test
    void burstKeepsTheStockInvariant() throws Exception {
        int initialStock = 10;
        String sku = TestStocks.insert(jdbcTemplate, initialStock);

        List<Outcome> outcomes = sellConcurrently(sku, 20);

        long successes = outcomes.stream().filter(outcome -> outcome.status() == 201).count();
        assertThat(outcomes).allMatch(outcome -> outcome.status() == 201 || outcome.status() == 409);
        assertThat(successes).isBetween(1L, (long) initialStock);
        assertThat(storedQuantity(sku)).isEqualTo(initialStock - (int) successes).isNotNegative();
    }
}
```

- [ ] **Step 2: Ejecutarlas**

Run: `./mvnw verify -Dit.test=SaleConcurrencyIntegrationTest`
Expected: PASS (21 pruebas: 20 repeticiones + la ráfaga). Estas pruebas verifican código ya implementado en la Tarea 2: no hay paso rojo previo; la mutación del paso siguiente demuestra que las pruebas muerden.

- [ ] **Step 3: Mutación de comprobación (no se commitea)**

Run: `./mvnw verify -Dit.test=SaleConcurrencyIntegrationTest -Dinventory.sale.max-attempts=1 -Dtest=NoSuchTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: FAIL en la carrera de la última unidad (con un solo intento el perdedor recibe `concurrent-modification` en lugar de `insufficient-stock`): prueba que la carrera produce conflictos reales y que el reintento los resuelve. Si en cambio pasa, la prueba no está generando contención real: investigar antes de continuar (más hilos o `CountDownLatch` mal usado). Verificar que el árbol de trabajo no cambió (`git status` limpio salvo el archivo de prueba).

- [ ] **Step 4: Verificar todo y repetir**

Run: `./mvnw verify`
Expected: BUILD SUCCESS, 39 rápidas y 77 de integración (56 + 21). Repetir `./mvnw verify -Dit.test=SaleConcurrencyIntegrationTest` dos veces más: debe pasar siempre (una prueba de concurrencia que falla a veces es un defecto, no ruido).

- [ ] **Step 5: Commit**

```bash
git add inventory-service/src
git commit -m "test: add last-unit race and burst invariant tests for sales

Co-Authored-By: Claude <modelo> <noreply@anthropic.com>"
```

---

### Task 5: Documentación y verificación de punta a punta

**Files:**
- Modify: `README.md`
- Modify: `docs/superpowers/specs/2026-10-11-inventario-design.md`

**Interfaces:**
- Consumes: todo lo anterior.
- Produces: documentación al día; verificación del servicio dentro de Compose.

- [ ] **Step 1: README**

En la sección "Servicio de Inventario", después del párrafo que sigue al ejemplo de `curl` de consulta (el que explica `404 not-found`), agregar:

````markdown
Para registrar una venta (token `pos` u `online`):

```bash
TOKEN="$(scripts/generate-token.sh pos)"
curl -X POST -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  -d '{"quantity": 1, "channel": "POS"}' \
  http://localhost:8081/api/v1/inventory/TENIS-RUNNER-42/sales
```

Responde `201` con el `saleId`, el stock restante y la versión. Sin unidades suficientes responde
`409 insufficient-stock`; si varias ventas del mismo SKU chocan y se agotan los reintentos
(`inventory.sale.max-attempts`, 5 por defecto), `409 concurrent-modification`. El cuerpo se valida de
forma estricta: propiedades desconocidas o cantidades que no son enteros responden `400 validation-error`.
Cada venta de la semilla es real: para volver al estado inicial, `docker compose down -v`.
````

- [ ] **Step 2: Spec de Inventario**

En `docs/superpowers/specs/2026-10-11-inventario-design.md` (leer antes el texto exacto):

1. §7 "Decisiones", punto "Entrada estricta, como dice el contrato": agregar al final de la frase `; se configura con tres opciones de Jackson en application.yml (fail-on-unknown-properties, accept-float-as-int y allow-coercion-of-scalars), las tres necesarias`.
2. §4 "Manejo de errores": agregar un punto: `- Las violaciones de clase (no de un campo) de un cuerpo inválido se reportan en \`errors\` con el nombre del objeto como \`field\`, y una restricción sin texto usa el mensaje \`is invalid\`.`
3. §9 "Pendientes heredados": eliminar las dos filas de la 4.4 (la de `validation-error` con `message` nulo o violaciones de clase, y la de `SeedDataIntegrationTest`/`TestStocks`), ya resueltas.
4. §5 "Pruebas": cambiar `la 4.4 retirará la de \`POST …/sales\`` por `la 4.4 retiró la de \`POST …/sales\`` (y que quede solo la 4.6 por retirar la de `GET /outbox`).

- [ ] **Step 3: Verificación completa**

Con los puertos por defecto libres (`docker compose ps`; si hay un stack `retail` en marcha, `docker compose down -v` antes), desde la raíz del repositorio y cada comando por separado:

```bash
cd inventory-service && ./mvnw verify
```
Expected: BUILD SUCCESS, 39 pruebas rápidas y 77 de integración.

```bash
docker compose up -d --wait --build inventory-service
```
Expected: `retail-inventory-service-1 Healthy`.

```bash
TOKEN="$(scripts/generate-token.sh pos)"; curl -s -X POST -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" -d '{"quantity":1,"channel":"POS"}' -w ' [%{http_code}]\n' http://localhost:8081/api/v1/inventory/BALON-FUTBOL-5/sales
```
Expected: `201` con `"remainingStock":0` y `"version":2`.

Repetir el mismo comando. Expected: `409` con `insufficient-stock`, `availableStock` 0 y `requestedQuantity` 1.

```bash
curl -s -X POST -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" -d '{"quantity":1.5,"channel":"POS"}' -w ' [%{http_code}]\n' http://localhost:8081/api/v1/inventory/BALON-FUTBOL-5/sales
```
Expected: `400 validation-error`.

```bash
docker compose down -v
```

- [ ] **Step 4: Commit**

```bash
git add README.md docs/superpowers/specs/2026-10-11-inventario-design.md
git commit -m "docs: document sale endpoint and close 4.4 carry-overs

Co-Authored-By: Claude <modelo> <noreply@anthropic.com>"
```

---

## Autorrevisión

- **Cobertura de la spec §7:** flujo con `TransactionTemplate` y sin `@Transactional` (T2), `OptimisticRetry` aparte con pruebas unitarias (T1), solo conflicto de versión reintentado y `insufficient-stock` sin repetir (T2: servicio + carrera T4), `saveAndFlush` con versión posterior (T2), `StockChange` devuelto y `SaleResponse` armado en el controlador (T2), entrada estricta (T2 pasos 2 y 6), 4.4 no escribe el evento (no hay outbox), carrera de la última unidad ×20 e invariante de ráfaga con 20 hilos sobre 10 (T4), validaciones/404/401/403 (T2 y la matriz existente), `inventory.sale.max-attempts` (T1).
- **Pendientes heredados de 4.4:** `validation-error` con mensaje nulo/violaciones de clase (T3); SKU propios en las dos pruebas que vendían la semilla y retirada de la sonda de venta (T2); `allowEmptyShould` ya retirado en 4.3 (`ArchitectureTest` debe seguir verde con `sale/` existente).
- **Consistencia de tipos:** `register(String, int, Channel): StockChange`; `StockChange.stockVersion(): long`; `SaleResponse.from(SaleRequest, StockChange)`; `OptimisticRetry(int)` / `run(Supplier<T>)` / `maxAttempts()`; `StockConflictException(String, int, Throwable)`; `ProblemType.INSUFFICIENT_STOCK` / `CONCURRENT_MODIFICATION`.
- **Conteos de referencia:** rápidas 28 → 35 (T1) → 38 (T2) → 39 (T3); integración 49 → 56 (T2) → 77 (T4).
- **Verificado en una copia temporal** antes de escribir el plan: 39 rápidas + 77 de integración en verde, venta/409/400 comprobadas contra el servicio en Compose, y la mutación `max-attempts=1` hace fallar 20 de 21 pruebas de concurrencia.
