# Inventario 4.3: stock, semilla y consulta — Plan de implementación

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Dar a `inventory-service` su base de datos (PostgreSQL + Flyway + JPA), la entidad `Stock` con su regla de venta, la semilla de `docs/seed-data.md` y el endpoint `GET /api/v1/inventory/{sku}`, y cerrar los pendientes heredados de 4.1 y 4.2 que corresponden a esta sub-tarea.

**Architecture:** Capas por funcionalidad en `stock/`: `StockController` → `GetStockService` → `StockRepository` (Spring Data) → `Stock` (entidad JPA con la regla `sell`, sin setters). Flyway es el único dueño del esquema (`ddl-auto=validate`). Todas las pruebas `@SpringBootTest` comparten una sola base PostgreSQL real levantada con Testcontainers.

**Tech Stack:** Spring Boot 4.1.1 (`spring-boot-starter-data-jpa`, `spring-boot-starter-flyway`), Flyway con `flyway-database-postgresql`, driver PostgreSQL, PostgreSQL 17-alpine, Testcontainers 2 (`testcontainers-postgresql`, `testcontainers-junit-jupiter`, versiones gestionadas por Spring Boot), Hibernate 7.

**Spec:** `docs/superpowers/specs/2026-10-11-inventario-design.md` (sección 6 y "Pendientes heredados"). Contrato: `docs/contracts/openapi/inventory-service.yaml` (schema `StockResponse`, `Problem`, `ValidationProblem`). Semilla: `docs/seed-data.md`.

## Global Constraints

- Spring Boot `4.1.1`, Java `25`, Maven solo con el wrapper `./mvnw` desde `inventory-service/`. Paquete base `com.retail.inventory`.
- **Nombres completos, sin abreviaturas** (salvo `SKU`, `JWT`, `UUID`, `DTO`, `HTTP`, `JSON`, `DLQ` y unidades). Código en inglés (incluidas tablas y columnas); comentarios, Javadoc, comentarios SQL y documentación en español.
- Commits en inglés, breves, Conventional Commits, con la línea `Co-Authored-By: Claude <modelo> <noreply@anthropic.com>`.
- La ruta del repositorio contiene espacios: toda ruta en comandos va entre comillas dobles.
- Esquema: tabla `stock(sku VARCHAR(64) PK, quantity INTEGER NOT NULL CHECK (quantity >= 0), version BIGINT NOT NULL, updated_at TIMESTAMPTZ NOT NULL)`. `V1__create_stock.sql` crea; `V2__seed_stock.sql` siembra los 7 SKU de `docs/seed-data.md` con `version = 1`.
- Hibernate con `ddl-auto=validate` y `open-in-view=false`. `Stock` sin setters; el estado cambia solo con `sell(quantity, now)`.
- Dependencias permitidas entre paquetes: `sale → stock`, `sale → outbox`, `outbox → stock`; `error` y `security` pueden usar las excepciones de las funcionalidades. Los controladores no usan repositorios.
- Las pruebas de integración se nombran `*IntegrationTest` (failsafe, `./mvnw verify`, requieren Docker); las demás corren con `./mvnw test`.
- Las pruebas que modifican stock usan SKU propios (`TEST-<uuid>`) y no tocan la semilla.
- Línea base antes de empezar: `./mvnw test` → 17 pruebas; `./mvnw verify` → 17 + 36 de integración.

## Review Focus

1. **Un SKU de más de 64 caracteres o con caracteres raros en la ruta.** Se espera 404 `not-found`, nunca 500 (la columna es `VARCHAR(64)`). Se fija en `StockEndpointIntegrationTest` (Tarea 3).
2. **Que el esquema acepte un stock negativo si el código se equivoca.** Se espera que la base lo rechace (`CHECK`). Se fija en `StockRepositoryIntegrationTest` (Tarea 2).
3. **Semilla desalineada con `docs/seed-data.md`** (otro stock, otro SKU, `version` distinta de 1, SKU sobrante). Se espera que la prueba falle. Se fija en `SeedDataIntegrationTest` (Tarea 1).
4. **Parámetros de consulta faltantes, de tipo incorrecto o que violan una restricción.** Se espera `400 validation-error` con `errors`, no `about:blank` ni 500. Se fija en `GlobalExceptionHandlerTest` (Tarea 4).
5. **Errores HTTP estándar (405, 415, ...) sin `type`.** El contrato exige `type` en `Problem`. Se espera `type` = `…/problems/http-error`. Se fija en `GlobalExceptionHandlerTest` (Tarea 4).

---

## Estructura de archivos

| Archivo | Responsabilidad |
|---|---|
| `inventory-service/pom.xml` | Dependencias de JPA, Flyway, PostgreSQL y Testcontainers; copia de `seed-data.md` al classpath de pruebas |
| `inventory-service/src/main/resources/application.yml` | Datasource (valores locales), JPA `validate`, `open-in-view=false` |
| `.../resources/db/migration/V1__create_stock.sql` | Esquema de la tabla `stock` |
| `.../resources/db/migration/V2__seed_stock.sql` | Los 7 SKU de la semilla |
| `.../stock/Stock.java` | Entidad JPA con la regla `sell` |
| `.../stock/InsufficientStockException.java` | Stock insuficiente (la traduce 4.4) |
| `.../stock/SkuNotFoundException.java` | SKU inexistente |
| `.../stock/StockRepository.java` | Interfaz Spring Data |
| `.../stock/GetStockService.java` | Caso de uso de consulta |
| `.../stock/StockResponse.java` | DTO de respuesta |
| `.../stock/StockController.java` | `GET /api/v1/inventory/{sku}` |
| `.../error/GlobalExceptionHandler.java` | Agrega 404 por SKU, parámetros inválidos y tipo `http-error` |
| `.../error/ProblemType.java` | Agrega `genericHttpErrorType()` |
| `src/test/.../support/PostgresIntegrationTest.java` | Base compartida con el contenedor PostgreSQL |
| `src/test/.../support/TestStocks.java` | Inserta SKU `TEST-<uuid>` con `version = 1` |
| `src/test/.../stock/*Test.java` | Pruebas de la funcionalidad |
| `docker-compose.yml` | Variables `SPRING_DATASOURCE_*` del servicio |
| `README.md`, `CLAUDE.md`, spec de Inventario | Documentación al día |

---

### Task 1: Base de datos, migraciones y semilla

**Files:**
- Modify: `inventory-service/pom.xml`
- Modify: `inventory-service/src/main/resources/application.yml`
- Create: `inventory-service/src/main/resources/db/migration/V1__create_stock.sql`
- Create: `inventory-service/src/main/resources/db/migration/V2__seed_stock.sql`
- Create: `inventory-service/src/test/java/com/retail/inventory/support/PostgresIntegrationTest.java`
- Create: `inventory-service/src/test/java/com/retail/inventory/stock/SeedDataIntegrationTest.java`
- Modify: `inventory-service/src/test/java/com/retail/inventory/InventoryApplicationIntegrationTest.java`
- Modify: `inventory-service/src/test/java/com/retail/inventory/security/SecurityIntegrationTest.java`
- Modify: `inventory-service/src/test/java/com/retail/inventory/security/GenerateTokenScriptIntegrationTest.java`
- Modify: `docker-compose.yml`

**Interfaces:**
- Produces: `PostgresIntegrationTest` (clase abstracta de pruebas; las `@SpringBootTest` la extienden y reciben `spring.datasource.*` de un PostgreSQL real); tabla `stock` sembrada; el servicio arranca con base de datos.

- [ ] **Step 1: Agregar dependencias al `pom.xml`**

En `<dependencies>`, justo **antes** de la dependencia `org.springdoc:springdoc-openapi-starter-webmvc-ui`, agregar:

```xml
		<dependency>
			<groupId>org.springframework.boot</groupId>
			<artifactId>spring-boot-starter-data-jpa</artifactId>
		</dependency>
		<dependency>
			<groupId>org.springframework.boot</groupId>
			<artifactId>spring-boot-starter-flyway</artifactId>
		</dependency>
		<dependency>
			<groupId>org.flywaydb</groupId>
			<artifactId>flyway-database-postgresql</artifactId>
		</dependency>
		<dependency>
			<groupId>org.postgresql</groupId>
			<artifactId>postgresql</artifactId>
			<scope>runtime</scope>
		</dependency>
```

Y **antes** de la dependencia `com.tngtech.archunit:archunit-junit5`:

```xml
		<dependency>
			<groupId>org.springframework.boot</groupId>
			<artifactId>spring-boot-starter-data-jpa-test</artifactId>
			<scope>test</scope>
		</dependency>
		<dependency>
			<groupId>org.testcontainers</groupId>
			<artifactId>testcontainers-postgresql</artifactId>
			<scope>test</scope>
		</dependency>
		<dependency>
			<groupId>org.testcontainers</groupId>
			<artifactId>testcontainers-junit-jupiter</artifactId>
			<scope>test</scope>
		</dependency>
```

En el `maven-resources-plugin`, dentro de `<executions>` y después de la ejecución `copy-contracts-for-tests`, agregar:

```xml
					<!-- La prueba de la semilla compara la migración con docs/seed-data.md -->
					<execution>
						<id>copy-seed-data-for-tests</id>
						<phase>process-test-resources</phase>
						<goals>
							<goal>copy-resources</goal>
						</goals>
						<configuration>
							<outputDirectory>${project.build.testOutputDirectory}</outputDirectory>
							<resources>
								<resource>
									<directory>${project.basedir}/../docs</directory>
									<includes>
										<include>seed-data.md</include>
									</includes>
								</resource>
							</resources>
						</configuration>
					</execution>
```

- [ ] **Step 2: Configurar el datasource y JPA en `application.yml`**

Reemplazar el bloque inicial `spring:` (hoy solo tiene `application.name`) por:

```yaml
spring:
  application:
    name: inventory-service
  datasource:
    # Valores para correr desde el IDE; Compose los sobrescribe con SPRING_DATASOURCE_*.
    url: jdbc:postgresql://localhost:5441/inventory
    username: inventory
    password: inventory-demo
  jpa:
    open-in-view: false
    hibernate:
      # Flyway es el único dueño del esquema: una entidad desalineada impide el arranque.
      ddl-auto: validate
```

- [ ] **Step 3: Crear la base compartida de las pruebas de integración**

`inventory-service/src/test/java/com/retail/inventory/support/PostgresIntegrationTest.java`:

```java
package com.retail.inventory.support;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Base de las pruebas de integración: una sola base PostgreSQL real por ejecución de Maven,
 * compartida por todos los contextos de Spring. El contenedor se detiene solo al terminar la JVM
 * (Ryuk, de Testcontainers).
 */
public abstract class PostgresIntegrationTest {

    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine");

    static {
        POSTGRES.start();
    }

    @DynamicPropertySource
    static void configureDatasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }
}
```

- [ ] **Step 4: Hacer que las tres pruebas `@SpringBootTest` existentes la extiendan**

En cada archivo cambiar la declaración de la clase y agregar el import de `com.retail.inventory.support.PostgresIntegrationTest` (junto a los demás imports `com.retail...`; en `InventoryApplicationIntegrationTest`, que no tiene ninguno, va como último bloque tras `org.springframework.test.web.servlet.MockMvc`, separado por una línea en blanco):

- `InventoryApplicationIntegrationTest`: `class InventoryApplicationIntegrationTest extends PostgresIntegrationTest {`
- `SecurityIntegrationTest`: `class SecurityIntegrationTest extends PostgresIntegrationTest {`
- `GenerateTokenScriptIntegrationTest`: `class GenerateTokenScriptIntegrationTest extends PostgresIntegrationTest {`

- [ ] **Step 5: Escribir la prueba de la semilla (debe fallar)**

`inventory-service/src/test/java/com/retail/inventory/stock/SeedDataIntegrationTest.java`:

```java
package com.retail.inventory.stock;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;

import com.retail.inventory.support.PostgresIntegrationTest;
import com.retail.inventory.support.TestTokens;

/**
 * La semilla de Flyway debe coincidir con {@code docs/seed-data.md}, el documento que también sigue
 * el Catálogo: así Inventario y Catálogo no se desalinean.
 */
@SpringBootTest(properties = "inventory.jwt.secret=" + TestTokens.SECRET)
class SeedDataIntegrationTest extends PostgresIntegrationTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void seededStockMatchesTheSeedDataDocument() throws IOException {
        Map<String, Integer> documented = readDocumentedStock();

        Map<String, Integer> seeded = new TreeMap<>();
        jdbcTemplate.query("SELECT sku, quantity FROM stock WHERE sku NOT LIKE 'TEST-%'",
                resultSet -> {
                    seeded.put(resultSet.getString("sku"), resultSet.getInt("quantity"));
                });

        assertThat(documented).hasSize(7);
        assertThat(seeded).isEqualTo(documented);
    }

    @Test
    void everySeededSkuStartsAtVersionOne() {
        List<Long> versions = jdbcTemplate.queryForList(
                "SELECT version FROM stock WHERE sku NOT LIKE 'TEST-%'", Long.class);

        assertThat(versions).isNotEmpty().containsOnly(1L);
    }

    /** Lee la tabla de SKU del documento: columna 1 = SKU, columna 4 = stock inicial. */
    private static Map<String, Integer> readDocumentedStock() throws IOException {
        Map<String, Integer> documented = new TreeMap<>();
        try (InputStream input = new ClassPathResource("seed-data.md").getInputStream()) {
            for (String line : new String(input.readAllBytes(), StandardCharsets.UTF_8).split("\n")) {
                if (!line.startsWith("| `")) {
                    continue;
                }
                String[] cells = line.split("\\|");
                documented.put(cells[1].trim().replace("`", ""), Integer.parseInt(cells[4].trim()));
            }
        }
        return documented;
    }
}
```

- [ ] **Step 6: Ejecutarla y verificar que falla**

Run (desde `inventory-service/`): `./mvnw verify -Dit.test=SeedDataIntegrationTest`
Expected: FAIL — la tabla `stock` no existe (`relation "stock" does not exist`). Si falla por otra causa (dependencia sin resolver, Docker apagado), corregir eso primero.

- [ ] **Step 7: Crear las migraciones**

`inventory-service/src/main/resources/db/migration/V1__create_stock.sql`:

```sql
-- Stock actual por SKU. Inventario es el único dueño de esta tabla.
CREATE TABLE stock (
    sku        VARCHAR(64)  PRIMARY KEY,
    -- Segunda defensa: la regla de negocio ya impide el stock negativo.
    quantity   INTEGER      NOT NULL CHECK (quantity >= 0),
    -- Versión para el bloqueo optimista (JPA @Version).
    version    BIGINT       NOT NULL,
    updated_at TIMESTAMPTZ  NOT NULL
);
```

`inventory-service/src/main/resources/db/migration/V2__seed_stock.sql`:

```sql
-- Datos semilla de docs/seed-data.md (los mismos SKU que siembra el Catálogo).
INSERT INTO stock (sku, quantity, version, updated_at) VALUES
    ('TENIS-RUNNER-42',      5, 1, now()),
    ('BALON-FUTBOL-5',       1, 1, now()),
    ('CAMISETA-DRYFIT-M',    0, 1, now()),
    ('MOCHILA-TRAIL-20L',   10, 1, now()),
    ('BOTELLA-TERMICA-750', 10, 1, now()),
    ('GORRA-SPORT-NEGRA',   50, 1, now()),
    ('CALCETINES-PACK-3',   20, 1, now());
```

- [ ] **Step 8: Variables de base de datos en el Compose**

En `docker-compose.yml`, servicio `inventory-service`, bajo `environment:` y entre `SERVER_PORT: "8081"` y `JWT_SECRET`, agregar:

```yaml
      SPRING_DATASOURCE_URL: jdbc:postgresql://inventory-db:5432/inventory
      SPRING_DATASOURCE_USERNAME: inventory
      SPRING_DATASOURCE_PASSWORD: ${INVENTORY_DB_PASSWORD:-inventory-demo}
```

- [ ] **Step 9: Verificar todo en verde**

Run: `./mvnw verify`
Expected: BUILD SUCCESS. 17 pruebas rápidas y 38 de integración (36 existentes + 2 de la semilla), todas con PostgreSQL de Testcontainers.

- [ ] **Step 10: Commit**

```bash
git add inventory-service/pom.xml inventory-service/src docker-compose.yml
git commit -m "feat: add PostgreSQL, Flyway migrations and seed data to inventory service

Co-Authored-By: Claude <modelo> <noreply@anthropic.com>"
```

---

### Task 2: Entidad `Stock` y repositorio

**Files:**
- Create: `inventory-service/src/main/java/com/retail/inventory/stock/Stock.java`
- Create: `inventory-service/src/main/java/com/retail/inventory/stock/InsufficientStockException.java`
- Create: `inventory-service/src/main/java/com/retail/inventory/stock/StockRepository.java`
- Test: `inventory-service/src/test/java/com/retail/inventory/stock/StockTest.java`
- Test: `inventory-service/src/test/java/com/retail/inventory/stock/StockRepositoryIntegrationTest.java`

**Interfaces:**
- Consumes: `PostgresIntegrationTest` (Tarea 1), tabla `stock`.
- Produces:
  - `public class Stock`: `public Stock(String sku, int quantity, Instant updatedAt)`; `public void sell(int quantityToSell, Instant now)`; `String getSku()`, `int getQuantity()`, `Long getVersion()` (nulo hasta el primer guardado), `Instant getUpdatedAt()`.
  - `public class InsufficientStockException extends RuntimeException`: `(String sku, int availableStock, int requestedQuantity)`; `getSku()`, `getAvailableStock()`, `getRequestedQuantity()`; mensaje `SKU <sku> has <available> units available, <requested> requested`. (4.4 la traducirá a 409.)
  - `public interface StockRepository extends JpaRepository<Stock, String>`.

- [ ] **Step 1: Escribir la prueba unitaria de la regla (debe fallar)**

`inventory-service/src/test/java/com/retail/inventory/stock/StockTest.java`:

```java
package com.retail.inventory.stock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;

import org.junit.jupiter.api.Test;

class StockTest {

    private static final Instant CREATED_AT = Instant.parse("2026-10-11T10:00:00Z");
    private static final Instant NOW = Instant.parse("2026-10-11T11:00:00Z");

    @Test
    void sellDeductsTheQuantityAndRefreshesTheTimestamp() {
        Stock stock = new Stock("TENIS-RUNNER-42", 5, CREATED_AT);

        stock.sell(2, NOW);

        assertThat(stock.getQuantity()).isEqualTo(3);
        assertThat(stock.getUpdatedAt()).isEqualTo(NOW);
    }

    @Test
    void sellingTheLastUnitLeavesZero() {
        Stock stock = new Stock("BALON-FUTBOL-5", 1, CREATED_AT);

        stock.sell(1, NOW);

        assertThat(stock.getQuantity()).isZero();
    }

    @Test
    void sellingMoreThanAvailableIsRejectedAndLeavesTheStockUntouched() {
        Stock stock = new Stock("TENIS-RUNNER-42", 5, CREATED_AT);

        assertThatThrownBy(() -> stock.sell(6, NOW))
                .isInstanceOfSatisfying(InsufficientStockException.class, exception -> {
                    assertThat(exception.getSku()).isEqualTo("TENIS-RUNNER-42");
                    assertThat(exception.getAvailableStock()).isEqualTo(5);
                    assertThat(exception.getRequestedQuantity()).isEqualTo(6);
                    assertThat(exception.getMessage()).isEqualTo("SKU TENIS-RUNNER-42 has 5 units available, 6 requested");
                });
        assertThat(stock.getQuantity()).isEqualTo(5);
        assertThat(stock.getUpdatedAt()).isEqualTo(CREATED_AT);
    }

    @Test
    void sellingFromEmptyStockIsRejected() {
        Stock stock = new Stock("CAMISETA-DRYFIT-M", 0, CREATED_AT);

        assertThatThrownBy(() -> stock.sell(1, NOW)).isInstanceOf(InsufficientStockException.class);
    }

    @Test
    void nonPositiveQuantitiesAreRejected() {
        Stock stock = new Stock("TENIS-RUNNER-42", 5, CREATED_AT);

        assertThatThrownBy(() -> stock.sell(0, NOW)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> stock.sell(-1, NOW)).isInstanceOf(IllegalArgumentException.class);
        assertThat(stock.getQuantity()).isEqualTo(5);
    }
}
```

- [ ] **Step 2: Ejecutarla y verificar que falla**

Run: `./mvnw test -Dtest=StockTest`
Expected: FAIL de compilación (`Stock` no existe).

- [ ] **Step 3: Implementar la excepción y la entidad**

`inventory-service/src/main/java/com/retail/inventory/stock/InsufficientStockException.java`:

```java
package com.retail.inventory.stock;

/**
 * Se intentó vender más unidades de las disponibles. No conoce HTTP: el manejador global de errores
 * la traduce.
 */
public class InsufficientStockException extends RuntimeException {

    private final String sku;
    private final int availableStock;
    private final int requestedQuantity;

    public InsufficientStockException(String sku, int availableStock, int requestedQuantity) {
        super("SKU " + sku + " has " + availableStock + " units available, " + requestedQuantity + " requested");
        this.sku = sku;
        this.availableStock = availableStock;
        this.requestedQuantity = requestedQuantity;
    }

    public String getSku() {
        return sku;
    }

    public int getAvailableStock() {
        return availableStock;
    }

    public int getRequestedQuantity() {
        return requestedQuantity;
    }
}
```

`inventory-service/src/main/java/com/retail/inventory/stock/Stock.java`:

```java
package com.retail.inventory.stock;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

/**
 * Stock actual de un SKU. Contiene la regla de negocio de descontar unidades; el estado solo cambia
 * mediante {@link #sell}, por eso no tiene setters. {@code version} es el bloqueo optimista: si dos
 * transacciones modifican el mismo registro, la segunda en guardar falla.
 */
@Entity
@Table(name = "stock")
public class Stock {

    @Id
    @Column(length = 64)
    private String sku;

    @Column(nullable = false)
    private int quantity;

    /** Nulo hasta el primer guardado; JPA lo asigna y lo incrementa en cada actualización. */
    @Version
    private Long version;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /** Constructor exigido por JPA. */
    protected Stock() {
    }

    public Stock(String sku, int quantity, Instant updatedAt) {
        this.sku = sku;
        this.quantity = quantity;
        this.updatedAt = updatedAt;
    }

    /**
     * Descuenta unidades del stock.
     *
     * @throws IllegalArgumentException si la cantidad no es positiva
     * @throws InsufficientStockException si no hay unidades suficientes
     */
    public void sell(int quantityToSell, Instant now) {
        if (quantityToSell < 1) {
            throw new IllegalArgumentException("quantity must be at least 1");
        }
        if (quantityToSell > quantity) {
            throw new InsufficientStockException(sku, quantity, quantityToSell);
        }
        quantity -= quantityToSell;
        updatedAt = now;
    }

    public String getSku() {
        return sku;
    }

    public int getQuantity() {
        return quantity;
    }

    public Long getVersion() {
        return version;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
```

`inventory-service/src/main/java/com/retail/inventory/stock/StockRepository.java`:

```java
package com.retail.inventory.stock;

import org.springframework.data.jpa.repository.JpaRepository;

public interface StockRepository extends JpaRepository<Stock, String> {
}
```

- [ ] **Step 4: Verificar que la prueba unitaria pasa**

Run: `./mvnw test -Dtest=StockTest`
Expected: PASS (5 pruebas).

- [ ] **Step 5: Escribir las pruebas de integración del repositorio (deben fallar o pasar según el mapeo)**

`inventory-service/src/test/java/com/retail/inventory/stock/StockRepositoryIntegrationTest.java`:

```java
package com.retail.inventory.stock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;

import com.retail.inventory.support.PostgresIntegrationTest;
import com.retail.inventory.support.TestTokens;

@SpringBootTest(properties = "inventory.jwt.secret=" + TestTokens.SECRET)
class StockRepositoryIntegrationTest extends PostgresIntegrationTest {

    @Autowired
    private StockRepository stockRepository;

    @Test
    void persistsAndReadsBackTheStock() {
        String sku = "TEST-" + UUID.randomUUID();
        stockRepository.saveAndFlush(new Stock(sku, 7, Instant.parse("2026-10-11T10:00:00Z")));

        Stock found = stockRepository.findById(sku).orElseThrow();

        assertThat(found.getQuantity()).isEqualTo(7);
        assertThat(found.getUpdatedAt()).isEqualTo(Instant.parse("2026-10-11T10:00:00Z"));
        assertThat(found.getVersion()).isNotNull();
    }

    /** El mecanismo del escenario 3: dos ventas concurrentes sobre la misma lectura. */
    @Test
    void secondConcurrentUpdateFailsWithOptimisticLockingFailure() {
        String sku = "TEST-" + UUID.randomUUID();
        stockRepository.saveAndFlush(new Stock(sku, 5, Instant.parse("2026-10-11T10:00:00Z")));
        Stock firstCopy = stockRepository.findById(sku).orElseThrow();
        Stock secondCopy = stockRepository.findById(sku).orElseThrow();

        firstCopy.sell(1, Instant.parse("2026-10-11T11:00:00Z"));
        stockRepository.saveAndFlush(firstCopy);
        secondCopy.sell(1, Instant.parse("2026-10-11T11:00:01Z"));

        assertThatThrownBy(() -> stockRepository.saveAndFlush(secondCopy))
                .isInstanceOf(OptimisticLockingFailureException.class);
        assertThat(stockRepository.findById(sku).orElseThrow().getQuantity()).isEqualTo(4);
    }

    @Test
    void databaseRejectsNegativeQuantityEvenIfTheCodeAllowedIt() {
        assertThatThrownBy(() -> stockRepository.saveAndFlush(
                new Stock("TEST-" + UUID.randomUUID(), -1, Instant.parse("2026-10-11T10:00:00Z"))))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void versionIncreasesOnEveryUpdate() {
        String sku = "TEST-" + UUID.randomUUID();
        Stock stock = stockRepository.saveAndFlush(new Stock(sku, 5, Instant.parse("2026-10-11T10:00:00Z")));
        long initialVersion = stock.getVersion();

        stock.sell(1, Instant.parse("2026-10-11T11:00:00Z"));
        Stock updated = stockRepository.saveAndFlush(stock);

        assertThat(updated.getVersion()).isEqualTo(initialVersion + 1);
    }
}
```

- [ ] **Step 6: Ejecutarlas y verificar que pasan**

Run: `./mvnw verify -Dit.test=StockRepositoryIntegrationTest`
Expected: PASS (4 pruebas). Si `secondConcurrentUpdate…` no lanza la excepción, el mapeo de `@Version` está mal: revisar la entidad, no la prueba.

- [ ] **Step 7: Commit**

```bash
git add inventory-service/src
git commit -m "feat: add Stock entity with sell rule and optimistic locking

Co-Authored-By: Claude <modelo> <noreply@anthropic.com>"
```

---

### Task 3: `GET /api/v1/inventory/{sku}`

**Files:**
- Create: `inventory-service/src/main/java/com/retail/inventory/stock/SkuNotFoundException.java`
- Create: `inventory-service/src/main/java/com/retail/inventory/stock/GetStockService.java`
- Create: `inventory-service/src/main/java/com/retail/inventory/stock/StockResponse.java`
- Create: `inventory-service/src/main/java/com/retail/inventory/stock/StockController.java`
- Modify: `inventory-service/src/main/java/com/retail/inventory/error/GlobalExceptionHandler.java`
- Create: `inventory-service/src/test/java/com/retail/inventory/support/TestStocks.java`
- Test: `inventory-service/src/test/java/com/retail/inventory/stock/GetStockServiceTest.java`
- Test: `inventory-service/src/test/java/com/retail/inventory/stock/StockEndpointIntegrationTest.java`
- Modify: `inventory-service/src/test/java/com/retail/inventory/security/SecuredProbeController.java`
- Modify: `inventory-service/src/test/java/com/retail/inventory/error/GlobalExceptionHandlerTest.java`
- Modify: `inventory-service/src/test/java/com/retail/inventory/architecture/ArchitectureTest.java`

**Interfaces:**
- Consumes: `Stock`, `StockRepository` (Tarea 2); `PostgresIntegrationTest` (Tarea 1); `ProblemType.NOT_FOUND`, `TestTokens.withScope`, `ContractAssertions.assertMatchesSchema`.
- Produces:
  - `SkuNotFoundException(String sku)` (mensaje `SKU <sku> does not exist`).
  - `GetStockService.getBySku(String sku): Stock` (lanza `SkuNotFoundException`).
  - `StockResponse(String sku, int stock, long version, Instant updatedAt)` y `StockResponse.from(Stock)`. Requiere un `Stock` ya guardado (`version` no nulo).
  - `TestStocks.insert(JdbcTemplate jdbcTemplate, int quantity): String` — inserta un SKU `TEST-<uuid>` con `version = 1` y devuelve el SKU.

- [ ] **Step 1: Prueba unitaria del caso de uso (debe fallar)**

`inventory-service/src/test/java/com/retail/inventory/stock/GetStockServiceTest.java`:

```java
package com.retail.inventory.stock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.Optional;

import org.junit.jupiter.api.Test;

class GetStockServiceTest {

    private final StockRepository stockRepository = mock(StockRepository.class);
    private final GetStockService service = new GetStockService(stockRepository);

    @Test
    void returnsTheStockOfAnExistingSku() {
        Stock stock = new Stock("TENIS-RUNNER-42", 5, Instant.parse("2026-10-11T10:00:00Z"));
        when(stockRepository.findById("TENIS-RUNNER-42")).thenReturn(Optional.of(stock));

        assertThat(service.getBySku("TENIS-RUNNER-42")).isSameAs(stock);
    }

    @Test
    void throwsSkuNotFoundForAnUnknownSku() {
        when(stockRepository.findById("UNKNOWN")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getBySku("UNKNOWN"))
                .isInstanceOf(SkuNotFoundException.class)
                .hasMessage("SKU UNKNOWN does not exist");
    }
}
```

- [ ] **Step 2: Ejecutarla y verificar que falla**

Run: `./mvnw test -Dtest=GetStockServiceTest`
Expected: FAIL de compilación (`GetStockService` no existe).

- [ ] **Step 3: Implementar excepción, caso de uso, DTO y controlador**

`stock/SkuNotFoundException.java`:

```java
package com.retail.inventory.stock;

/** El SKU no existe en el inventario. No conoce HTTP: el manejador global de errores la traduce. */
public class SkuNotFoundException extends RuntimeException {

    public SkuNotFoundException(String sku) {
        super("SKU " + sku + " does not exist");
    }
}
```

`stock/GetStockService.java`:

```java
package com.retail.inventory.stock;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Caso de uso: consultar el stock actual de un SKU. */
@Service
public class GetStockService {

    private final StockRepository stockRepository;

    public GetStockService(StockRepository stockRepository) {
        this.stockRepository = stockRepository;
    }

    @Transactional(readOnly = true)
    public Stock getBySku(String sku) {
        return stockRepository.findById(sku).orElseThrow(() -> new SkuNotFoundException(sku));
    }
}
```

`stock/StockResponse.java`:

```java
package com.retail.inventory.stock;

import java.time.Instant;

/** Respuesta de {@code GET /api/v1/inventory/{sku}} (schema {@code StockResponse} del contrato). */
public record StockResponse(String sku, int stock, long version, Instant updatedAt) {

    public static StockResponse from(Stock stock) {
        return new StockResponse(stock.getSku(), stock.getQuantity(), stock.getVersion(), stock.getUpdatedAt());
    }
}
```

`stock/StockController.java`:

```java
package com.retail.inventory.stock;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/inventory")
public class StockController {

    private final GetStockService getStockService;

    public StockController(GetStockService getStockService) {
        this.getStockService = getStockService;
    }

    @GetMapping("/{sku}")
    public StockResponse getStock(@PathVariable String sku) {
        return StockResponse.from(getStockService.getBySku(sku));
    }
}
```

- [ ] **Step 4: Traducir `SkuNotFoundException` a 404 en el manejador global**

En `error/GlobalExceptionHandler.java` agregar el import `com.retail.inventory.stock.SkuNotFoundException` (como último bloque de imports, después de `org.springframework...`, separado por una línea en blanco) y este método justo antes del `@ExceptionHandler(Exception.class)`:

```java
    @ExceptionHandler(SkuNotFoundException.class)
    public ResponseEntity<ProblemDetail> handleSkuNotFound(SkuNotFoundException exception,
            HttpServletRequest request) {
        ProblemDetail problem = ProblemType.NOT_FOUND.toProblemDetail(exception.getMessage(), request.getRequestURI());
        return ResponseEntity.status(problem.getStatus()).body(problem);
    }
```

- [ ] **Step 5: `GlobalExceptionHandlerTest` solo debe cargar su controlador**

Con `StockController` presente, `@WebMvcTest` sin argumentos intenta cargarlo y falla (no hay `GetStockService`). En `error/GlobalExceptionHandlerTest.java` cambiar `@WebMvcTest` por:

```java
@WebMvcTest(controllers = ProbeController.class)
```

- [ ] **Step 6: Retirar la ruta de consulta del controlador de prueba de seguridad**

En `security/SecuredProbeController.java` eliminar el método `getStock` y su anotación `@GetMapping("/api/v1/inventory/{sku}")`, y el import de `PathVariable` solo si ya no se usa (se sigue usando en `registerSale`: se conserva). Cambiar el Javadoc de la clase por:

```java
/**
 * Controlador solo de pruebas con las rutas del contrato que aún no tienen controlador real: la de
 * venta llega en 4.4 y la del outbox en 4.6, que las eliminan (la de consulta de stock es real desde
 * 4.3). {@link TestComponent} lo excluye del escaneo de {@code @SpringBootTest}; se importa
 * explícitamente.
 */
```

Las pruebas `SecurityIntegrationTest` y `GenerateTokenScriptIntegrationTest` que consultan `TENIS-RUNNER-42` ahora llegan al controlador real y reciben 200 con la semilla; no requieren cambios.

- [ ] **Step 7: Endurecer ArchUnit**

En `architecture/ArchitectureTest.java`, quitar `.allowEmptyShould(true)` de las reglas 3 (`controllersDoNotUseRepositories`) y 4 (`stockAndSaleStayIndependentOfWebAndMessaging`), de modo que terminen en `.should().dependOnClassesThat().haveSimpleNameEndingWith("Repository");` y `.orShould().dependOnClassesThat().haveSimpleName("RabbitStockEventSender");`. Reemplazar el Javadoc de la clase por:

```java
/**
 * Hace cumplir las reglas de dependencia de la spec de Inventario (sección 3). Ninguna regla permite
 * evaluación vacía: si un paquete deja de tener clases que evaluar, la prueba falla.
 */
```

- [ ] **Step 8: Verificar las pruebas rápidas**

Run: `./mvnw test`
Expected: PASS — `StockTest` (5), `GetStockServiceTest` (2) y las 17 existentes (incluida `ArchitectureTest` sin evaluación vacía): 24 pruebas.

- [ ] **Step 9: Ayudante de pruebas y prueba de integración del endpoint**

`src/test/java/com/retail/inventory/support/TestStocks.java`:

```java
package com.retail.inventory.support;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Crea registros de stock con SKU propios (<code>TEST-&lt;uuid&gt;</code>) para que las pruebas que
 * modifican stock no toquen la semilla. Se inserta con {@code version = 1}, igual que la semilla.
 */
public final class TestStocks {

    private TestStocks() {
    }

    /** Inserta un SKU nuevo con la cantidad indicada y devuelve su código. */
    public static String insert(JdbcTemplate jdbcTemplate, int quantity) {
        String sku = "TEST-" + UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO stock (sku, quantity, version, updated_at) VALUES (?, ?, 1, ?)",
                sku, quantity, Timestamp.from(Instant.now()));
        return sku;
    }
}
```

`src/test/java/com/retail/inventory/stock/StockEndpointIntegrationTest.java`:

```java
package com.retail.inventory.stock;

import static com.retail.inventory.support.ContractAssertions.assertMatchesSchema;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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

import com.retail.inventory.support.PostgresIntegrationTest;
import com.retail.inventory.support.TestStocks;
import com.retail.inventory.support.TestTokens;

@SpringBootTest(properties = "inventory.jwt.secret=" + TestTokens.SECRET)
@AutoConfigureMockMvc
class StockEndpointIntegrationTest extends PostgresIntegrationTest {

    private static final String PROBLEM_TYPE_BASE = "https://api.retail-demo.local/problems/";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private static String readerToken() {
        return "Bearer " + TestTokens.withScope("inventory:read");
    }

    @Test
    void returnsTheCurrentStockMatchingTheContract() throws Exception {
        String sku = TestStocks.insert(jdbcTemplate, 7);

        String body = mockMvc.perform(get("/api/v1/inventory/" + sku).header(HttpHeaders.AUTHORIZATION, readerToken()))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.sku").value(sku))
                .andExpect(jsonPath("$.stock").value(7))
                .andExpect(jsonPath("$.version").value(1))
                .andReturn().getResponse().getContentAsString();

        assertMatchesSchema(body, "StockResponse");
    }

    @Test
    void servesTheSeededSku() throws Exception {
        String body = mockMvc.perform(get("/api/v1/inventory/TENIS-RUNNER-42")
                        .header(HttpHeaders.AUTHORIZATION, readerToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sku").value("TENIS-RUNNER-42"))
                .andReturn().getResponse().getContentAsString();

        assertMatchesSchema(body, "StockResponse");
    }

    @Test
    void unknownSkuIsNotFound() throws Exception {
        String body = mockMvc.perform(get("/api/v1/inventory/DOES-NOT-EXIST")
                        .header(HttpHeaders.AUTHORIZATION, readerToken()))
                .andExpect(status().isNotFound())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.type").value(PROBLEM_TYPE_BASE + "not-found"))
                .andExpect(jsonPath("$.instance").value("/api/v1/inventory/DOES-NOT-EXIST"))
                .andReturn().getResponse().getContentAsString();

        assertMatchesSchema(body, "Problem");
    }

    @Test
    void skuThatDoesNotFollowThePatternIsAlsoNotFound() throws Exception {
        mockMvc.perform(get("/api/v1/inventory/lower-case sku").header(HttpHeaders.AUTHORIZATION, readerToken()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.type").value(PROBLEM_TYPE_BASE + "not-found"));
    }

    @Test
    void skuLongerThanTheColumnIsNotFoundInsteadOfFailing() throws Exception {
        mockMvc.perform(get("/api/v1/inventory/" + "A".repeat(200)).header(HttpHeaders.AUTHORIZATION, readerToken()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.type").value(PROBLEM_TYPE_BASE + "not-found"));
    }

    @Test
    void withoutTokenIsUnauthorized() throws Exception {
        mockMvc.perform(get("/api/v1/inventory/TENIS-RUNNER-42")).andExpect(status().isUnauthorized());
    }

    @Test
    void withoutTheReadScopeIsForbidden() throws Exception {
        mockMvc.perform(get("/api/v1/inventory/TENIS-RUNNER-42")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + TestTokens.withScope("inventory:sell")))
                .andExpect(status().isForbidden());
    }
}
```

- [ ] **Step 10: Verificar todo**

Run: `./mvnw verify`
Expected: BUILD SUCCESS — 24 pruebas rápidas y 45 de integración (38 + 4 del repositorio + 7 del endpoint − 4 que ya estaban contadas… ver nota). Lo que importa: 0 fallos. Cifras de referencia: rápidas 24; integración = Application 5 + Security 25 + Script 6 + Seed 2 + Repository 4 + Endpoint 7 = 49.

- [ ] **Step 11: Commit**

```bash
git add inventory-service/src
git commit -m "feat: add GET /api/v1/inventory/{sku} endpoint

Co-Authored-By: Claude <modelo> <noreply@anthropic.com>"
```

---

### Task 4: Errores estándar de Spring MVC como `validation-error` / `http-error`

**Files:**
- Modify: `inventory-service/src/main/java/com/retail/inventory/error/ProblemType.java`
- Modify (reemplazo completo): `inventory-service/src/main/java/com/retail/inventory/error/GlobalExceptionHandler.java`
- Modify: `inventory-service/src/test/java/com/retail/inventory/error/ProbeController.java`
- Modify: `inventory-service/src/test/java/com/retail/inventory/error/GlobalExceptionHandlerTest.java`

**Interfaces:**
- Consumes: `FieldViolation(String field, String message)`, `ProblemType.VALIDATION_ERROR`, `ProblemType.NOT_FOUND`.
- Produces: `ProblemType.genericHttpErrorType(): URI` (`https://api.retail-demo.local/problems/http-error`); el manejador responde `400 validation-error` con `errors` para parámetro faltante, de tipo incorrecto y violación de restricción de parámetro, y da `type` `http-error` a 405/415/etc.

- [ ] **Step 1: Rutas de prueba con parámetros**

En `error/ProbeController.java` agregar el import `org.springframework.web.bind.annotation.RequestParam` (junto a `RequestBody`) y, antes de `failure()`:

```java
    @GetMapping("/probe/page")
    int page(@RequestParam int number) {
        return number;
    }

    @GetMapping("/probe/limit")
    int limit(@RequestParam @Min(1) int size) {
        return size;
    }
```

- [ ] **Step 2: Escribir las pruebas (deben fallar)**

En `error/GlobalExceptionHandlerTest.java` reemplazar la prueba `unsupportedMethodKeepsItsHttpStatus` por estas cuatro:

```java
    @Test
    void unsupportedMethodKeepsItsHttpStatusAndHasTheGenericType() throws Exception {
        String body = mockMvc.perform(delete("/probe/echo"))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.type").value(PROBLEM_TYPE_BASE + "http-error"))
                .andReturn().getResponse().getContentAsString();

        assertMatchesSchema(body, "Problem");
    }

    @Test
    void missingRequiredParameterReturnsValidationProblemListingIt() throws Exception {
        String body = mockMvc.perform(get("/probe/page"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.type").value(PROBLEM_TYPE_BASE + "validation-error"))
                .andExpect(jsonPath("$.errors[0].field").value("number"))
                .andReturn().getResponse().getContentAsString();

        assertMatchesSchema(body, "ValidationProblem");
    }

    @Test
    void parameterOfTheWrongTypeReturnsValidationProblemListingIt() throws Exception {
        String body = mockMvc.perform(get("/probe/page").param("number", "abc"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value(PROBLEM_TYPE_BASE + "validation-error"))
                .andExpect(jsonPath("$.errors[0].field").value("number"))
                .andReturn().getResponse().getContentAsString();

        assertMatchesSchema(body, "ValidationProblem");
    }

    @Test
    void parameterViolatingAConstraintReturnsValidationProblemListingIt() throws Exception {
        String body = mockMvc.perform(get("/probe/limit").param("size", "0"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value(PROBLEM_TYPE_BASE + "validation-error"))
                .andExpect(jsonPath("$.errors[0].field").value("size"))
                .andReturn().getResponse().getContentAsString();

        assertMatchesSchema(body, "ValidationProblem");
    }
```

- [ ] **Step 3: Ejecutarlas y verificar que fallan**

Run: `./mvnw test -Dtest=GlobalExceptionHandlerTest`
Expected: FAIL — 405 sin `type` (el schema exige `type`) y los otros tres sin `validation-error`.

- [ ] **Step 4: `ProblemType.genericHttpErrorType()`**

En `error/ProblemType.java`, antes de `public HttpStatus status()`:

```java
    /**
     * Tipo para los errores HTTP estándar de Spring MVC (405, 415, ...), que por defecto no traen
     * {@code type}; el contrato lo exige.
     */
    public static URI genericHttpErrorType() {
        return URI.create(TYPE_BASE_URI + "http-error");
    }
```

- [ ] **Step 5: Reemplazar `GlobalExceptionHandler.java` completo**

```java
package com.retail.inventory.error;

import java.net.URI;
import java.util.List;

import jakarta.servlet.http.HttpServletRequest;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.TypeMismatchException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import com.retail.inventory.stock.SkuNotFoundException;

/**
 * Traduce excepciones a respuestas {@code application/problem+json} (RFC 9457). Las excepciones
 * estándar de Spring MVC conservan su estado HTTP (405, 415, ...) gracias a
 * {@link ResponseEntityExceptionHandler}; las del contrato usan {@link ProblemType}.
 */
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    private static final URI ABOUT_BLANK = URI.create("about:blank");
    private static final Logger LOGGER = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException exception,
            HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        List<FieldViolation> violations = exception.getBindingResult().getFieldErrors().stream()
                .map(error -> new FieldViolation(error.getField(), error.getDefaultMessage()))
                .toList();
        return validationProblem("The request has invalid fields", violations, request);
    }

    @Override
    protected ResponseEntity<Object> handleHttpMessageNotReadable(HttpMessageNotReadableException exception,
            HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        return validationProblem("The request body is malformed or has invalid values", List.of(), request);
    }

    @Override
    protected ResponseEntity<Object> handleMissingServletRequestParameter(
            MissingServletRequestParameterException exception, HttpHeaders headers, HttpStatusCode status,
            WebRequest request) {
        return validationProblem("The request is missing a required parameter",
                List.of(new FieldViolation(exception.getParameterName(), "is required")), request);
    }

    @Override
    protected ResponseEntity<Object> handleTypeMismatch(TypeMismatchException exception, HttpHeaders headers,
            HttpStatusCode status, WebRequest request) {
        return validationProblem("The request has a parameter with an invalid value",
                List.of(new FieldViolation(exception.getPropertyName(), "has an invalid value")), request);
    }

    @Override
    protected ResponseEntity<Object> handleHandlerMethodValidationException(
            HandlerMethodValidationException exception, HttpHeaders headers, HttpStatusCode status,
            WebRequest request) {
        List<FieldViolation> violations = exception.getParameterValidationResults().stream()
                .flatMap(result -> result.getResolvableErrors().stream()
                        .map(error -> new FieldViolation(result.getMethodParameter().getParameterName(),
                                error.getDefaultMessage())))
                .toList();
        return validationProblem("The request has invalid parameters", violations, request);
    }

    @Override
    protected ResponseEntity<Object> handleNoResourceFoundException(NoResourceFoundException exception,
            HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        ProblemDetail problem = ProblemType.NOT_FOUND
                .toProblemDetail("The requested resource does not exist", requestPath(request));
        return ResponseEntity.status(problem.getStatus()).body(problem);
    }

    @ExceptionHandler(SkuNotFoundException.class)
    public ResponseEntity<ProblemDetail> handleSkuNotFound(SkuNotFoundException exception,
            HttpServletRequest request) {
        ProblemDetail problem = ProblemType.NOT_FOUND.toProblemDetail(exception.getMessage(), request.getRequestURI());
        return ResponseEntity.status(problem.getStatus()).body(problem);
    }

    /** Da el tipo genérico {@code http-error} a los problemas estándar de Spring que no traen {@code type}. */
    @Override
    protected ResponseEntity<Object> handleExceptionInternal(Exception exception, Object body, HttpHeaders headers,
            HttpStatusCode statusCode, WebRequest request) {
        // Spring MVC pasa body nulo y arma el problema después, a partir de la excepción.
        ProblemDetail problem = body instanceof ProblemDetail existing ? existing
                : exception instanceof ErrorResponse errorResponse ? errorResponse.getBody() : null;
        if (problem != null && (problem.getType() == null || ABOUT_BLANK.equals(problem.getType()))) {
            problem.setType(ProblemType.genericHttpErrorType());
        }
        return super.handleExceptionInternal(exception, body, headers, statusCode, request);
    }

    /** Último recurso: nunca expone detalles internos, pero registra la excepción completa. */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ProblemDetail> handleUnexpected(Exception exception, HttpServletRequest request) {
        LOGGER.error("unexpected_error path={}", request.getRequestURI(), exception);
        ProblemDetail problem = ProblemType.INTERNAL_ERROR
                .toProblemDetail("An unexpected error occurred", request.getRequestURI());
        return ResponseEntity.status(problem.getStatus()).body(problem);
    }

    private static ResponseEntity<Object> validationProblem(String detail, List<FieldViolation> violations,
            WebRequest request) {
        ProblemDetail problem = ProblemType.VALIDATION_ERROR.toProblemDetail(detail, requestPath(request));
        problem.setProperty("errors", violations);
        return ResponseEntity.status(problem.getStatus()).body(problem);
    }

    private static String requestPath(WebRequest request) {
        return ((ServletWebRequest) request).getRequest().getRequestURI();
    }
}
```

Nota: Spring 7 entrega `type` nulo (no `about:blank`) en estos problemas; por eso se comprueban ambos casos.

- [ ] **Step 6: Verificar**

Run: `./mvnw verify`
Expected: BUILD SUCCESS — 27 pruebas rápidas (24 + 3 nuevas del manejador) y 49 de integración, 0 fallos.

- [ ] **Step 7: Commit**

```bash
git add inventory-service/src
git commit -m "fix: return validation-error and http-error problems for Spring MVC exceptions

Co-Authored-By: Claude <modelo> <noreply@anthropic.com>"
```

---

### Task 5: Documentación y verificación de punta a punta

**Files:**
- Modify: `README.md`
- Modify: `CLAUDE.md`
- Modify: `docs/superpowers/specs/2026-10-11-inventario-design.md`

**Interfaces:**
- Consumes: todo lo anterior.
- Produces: documentación al día; verificación del servicio dentro de Compose.

- [ ] **Step 1: README**

En la sección "Servicio de Inventario":

- Reemplazar la línea `./mvnw verify   # además, las pruebas de integración (*IntegrationTest)` por `./mvnw verify   # además, las pruebas de integración (*IntegrationTest); requieren Docker (PostgreSQL con Testcontainers)`.
- Después del párrafo que explica `JWT_SECRET`, agregar: `El servicio usa la base de Inventario: al correrlo fuera de Compose, levantar antes \`docker compose up -d --wait inventory-db\` (puerto 5441). Flyway crea la tabla \`stock\` y la siembra al arrancar.`
- El ejemplo de `curl` ya apunta a `TENIS-RUNNER-42`: tras la 4.3 responde `200` con `{"sku":"TENIS-RUNNER-42","stock":5,"version":1,...}`; agregar bajo el ejemplo la línea: `Un SKU inexistente responde \`404 not-found\`.`

- [ ] **Step 2: CLAUDE.md**

En "Comandos", cambiar `` `./mvnw verify` (además integración) `` por `` `./mvnw verify` (además integración; requiere Docker) ``.

- [ ] **Step 3: Spec de Inventario**

En `docs/superpowers/specs/2026-10-11-inventario-design.md`:

1. §4 "Manejo de errores": agregar un punto: `- Las excepciones estándar de Spring MVC (405, 415, ...) conservan su estado y reciben el tipo genérico \`http-error\`; los parámetros faltantes, de tipo incorrecto o que violan una restricción responden \`validation-error\` con \`errors\`.`
2. §5 "Pruebas": reemplazar `Las sub-tareas 4.3 y 4.4 lo eliminan y mueven esas pruebas a los endpoints reales.` por `La 4.3 retiró la ruta \`GET /{sku}\`, la 4.4 retirará la de \`POST …/sales\` y la 4.6 la de \`GET /outbox\`; las pruebas pasan a los endpoints reales.`
3. §9 tabla "Pendientes heredados": eliminar las tres filas ya resueltas en 4.3 (excepciones de Spring MVC con `about:blank`; `allowEmptyShould` de la regla 3; `allowEmptyShould` de la regla 4) y agregar estas dos filas para la 4.4:
   - `| \`SeedDataIntegrationTest\` asume que ninguna prueba modifica los SKU sembrados: las pruebas de venta deben usar SKU \`TEST-<uuid>\` (\`TestStocks.insert\`); \`GenerateTokenScriptIntegrationTest.posAndOnlineTokensCanSell\` vende \`TENIS-RUNNER-42\` y debe pasar a un SKU propio | 4.4 |`
   - `| \`validation-error\` con \`message\` nulo o con violaciones de clase (ya estaba) | 4.4 |` — solo si esa fila no existe todavía (no duplicar).
4. §9 "Pruebas: dos velocidades": en `./mvnw verify`, añadir `(requiere Docker)` si no está.

- [ ] **Step 4: Verificación completa**

Con los puertos por defecto libres (ver `docker compose ps`; si hay un stack `retail` en marcha, `docker compose down -v` antes), ejecutar desde la raíz del repositorio, cada comando por separado:

```bash
cd inventory-service && ./mvnw verify
```
Expected: BUILD SUCCESS, 27 pruebas rápidas y 49 de integración.

```bash
docker compose up -d --wait --build inventory-service
```
Expected: `retail-inventory-service-1 Healthy`.

```bash
TOKEN="$(scripts/generate-token.sh operator)"; curl -s -H "Authorization: Bearer $TOKEN" http://localhost:8081/api/v1/inventory/TENIS-RUNNER-42
```
Expected: `{"sku":"TENIS-RUNNER-42","stock":5,"version":1,"updatedAt":"…Z"}`.

```bash
curl -s -H "Authorization: Bearer $TOKEN" http://localhost:8081/api/v1/inventory/NOPE
```
Expected: problema `not-found` con estado 404.

```bash
docker compose restart inventory-service
```
Luego repetir el primer `curl`. Expected: mismo `stock: 5` y `version: 1` (Flyway no vuelve a sembrar).

```bash
docker compose down -v
```

- [ ] **Step 5: Commit**

```bash
git add README.md CLAUDE.md docs/superpowers/specs/2026-10-11-inventario-design.md
git commit -m "docs: document stock persistence, tests needing Docker and updated carry-overs

Co-Authored-By: Claude <modelo> <noreply@anthropic.com>"
```

---

## Autorrevisión

- **Cobertura de la spec §6:** esquema y `CHECK` (T1, T2), semilla igual al documento (T1), `ddl-auto=validate` y `open-in-view=false` (T1), entidad sin setters con `sell` (T2), `GetStockService`/`StockController`/`StockResponse.from` (T3), mapeo de `@Version` (T2), contrato 200 / 404 / 401 / 403 (T3), SKU propios `TEST-<uuid>` (T2, T3).
- **Pendientes heredados de 4.3:** `about:blank` → `validation-error` y 405 validado contra `Problem` (T4); `allowEmptyShould` regla 3 (T3) y regla 4 (T3, adelantada porque `stock/` ya tiene clases); Testcontainers en todos los `@SpringBootTest` (T1); retirar solo `GET /{sku}` del probe (T3); `SPRING_DATASOURCE_*` en Compose (T1); README, CLAUDE.md y correcciones de la spec (T5).
- **Consistencia de tipos:** `Stock.getVersion(): Long` (nulo antes de guardar) y `StockResponse.version: long` — `from` solo se usa con registros guardados; `TestStocks.insert(JdbcTemplate, int): String`; `ProblemType.genericHttpErrorType(): URI`.
- **Conteos de referencia:** rápidas 17 → 24 (T3) → 27 (T4); integración 36 → 38 (T1) → 42 (T2: +4) → 49 (T3: +7) → 49 (T4).
