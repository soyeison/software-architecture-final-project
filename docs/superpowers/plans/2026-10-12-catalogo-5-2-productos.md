# Catálogo 5.2: productos y consulta REST — Plan de implementación

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Que `catalog-service` guarde sus productos en PostgreSQL (Flyway + JPA), siembre los 7 SKU de `docs/seed-data.md` y exponga `GET /api/v1/catalog/products` y `/{sku}` según el contrato, cerrando los pendientes heredados de la 5.1.

**Architecture:** Paquete `product/` por capas (`ProductController` → `ProductQueryService` → `ProductRepository` → `Product`). `Product` es una entidad JPA sin setters con la regla `applyStock` (la usará la 5.3). Flyway es el único dueño del esquema (`ddl-auto=validate`); todas las pruebas `@SpringBootTest` comparten una base PostgreSQL real de Testcontainers, igual que en Inventario.

**Tech Stack:** Spring Boot 4.1.1 (`spring-boot-starter-data-jpa`, `spring-boot-starter-flyway`), Flyway con `flyway-database-postgresql`, driver PostgreSQL, PostgreSQL 17-alpine, Testcontainers 2 (`testcontainers-postgresql`, `testcontainers-junit-jupiter`), Hibernate 7.

**Spec:** `docs/superpowers/specs/2026-10-12-catalogo-design.md` (secciones 3, 5 y 7 con su tabla de pendientes). Contrato: `docs/contracts/openapi/catalog-service.yaml` (`listProducts`, `getProduct`, schemas `Product` y `Problem`). Semilla: `docs/seed-data.md`.

## Global Constraints

- Spring Boot `4.1.1`, Java `25`, Maven solo con el wrapper `./mvnw` desde `catalog-service/`. Paquete base `com.retail.catalog`.
- **Nombres completos, sin abreviaturas** (salvo `SKU`, `JWT`, `UUID`, `DTO`, `HTTP`, `JSON`, `DLQ` y unidades). Código en inglés (incluidas tablas y columnas); comentarios, Javadoc, comentarios SQL y documentación en español.
- Commits en inglés, breves, Conventional Commits, con la línea `Co-Authored-By: Claude <modelo> <noreply@anthropic.com>`.
- La ruta del repositorio contiene espacios: toda ruta en comandos va entre comillas dobles.
- Tabla `products(sku VARCHAR(64) PK, name VARCHAR(120), description TEXT, price INTEGER CHECK >= 0, currency VARCHAR(3), available_stock INTEGER CHECK >= 0, stock_version BIGINT, updated_at TIMESTAMPTZ, last_event_latency_ms BIGINT NULL)`. La semilla (`V2__seed_products.sql`) usa los 7 SKU de `docs/seed-data.md` con `stock_version = 0`, `updated_at` truncado a milisegundos y `last_event_latency_ms = NULL`. La tabla `processed_events` **no** se crea aquí: llega en la 5.3 (`V3__create_processed_events.sql`).
- `Product` sin setters; la disponibilidad cambia solo con `applyStock(newStock, stockVersion, occurredAt, now)`: fija stock y versión, `updatedAt = now` y `lastEventLatencyMs = max(0, now − occurredAt)` en ms. `inStock` no se guarda: se deriva (`availableStock > 0`).
- Consulta pública sin token: `GET /api/v1/catalog/products` (todos, ordenados por `sku`, sin paginación) y `GET /api/v1/catalog/products/{sku}` (404 `not-found` si no existe, también para SKU con formato raro o demasiado largo, sin validación extra).
- Hibernate con `ddl-auto=validate` y `open-in-view=false`. Datasource por defecto para el IDE: `jdbc:postgresql://localhost:5442/catalog`, usuario `catalog`, clave `catalog-demo`; Compose lo sobrescribe con `SPRING_DATASOURCE_*` (ya definidas).
- Dependencias entre paquetes: `stockevent → product`; `product` nunca depende de `stockevent`; los controladores no usan repositorios. **Ninguna regla de ArchUnit permite evaluación vacía** (esta sub-tarea retira el permiso de las reglas 3 y 4).
- El contrato publicado es el escrito a mano (Swagger UI); `/v3/api-docs` es la vista generada del código, informativa (se aclara en el README, sin cambiar código).
- Las pruebas de integración se nombran `*IntegrationTest` (failsafe, `./mvnw verify`, requieren Docker); las demás corren con `./mvnw test`. Los productos que crean las pruebas usan SKU `TEST-<UUID en mayúsculas>` y se borran al terminar; las pruebas que listan no asumen una tabla vacía.
- Línea base antes de empezar: `./mvnw test` → 11 pruebas; `./mvnw verify` → 11 + 5 de integración.

## Review Focus

1. **Un SKU con formato raro o demasiado largo** (`lower-case sku`, 200 caracteres): `404 not-found` en `application/problem+json`, nunca 500. Se fija en `ProductEndpointIntegrationTest` (Tarea 3).
2. **Una ruta con `[` o `]`** (que `URI.create` rechaza) en el manejador de errores: el `instance` del problema se codifica en lugar de lanzar. Se fija en `ProblemTypeTest` (Tarea 3).
3. **Semilla desalineada con `docs/seed-data.md`** (nombre, precio, stock, SKU sobrante o faltante): la prueba falla. Se fija en `SeedDataIntegrationTest` (Tarea 1).
4. **Stock negativo** (por un bug futuro del consumidor): `applyStock` lo rechaza sin tocar el producto y la base lo rechaza por `CHECK`. Se fija en `ProductTest` y `ProductRepositoryIntegrationTest` (Tarea 2).
5. **Relojes desfasados** (`occurredAt` posterior a `now`): la latencia queda en 0, nunca negativa (el contrato exige `minimum: 0`). Se fija en `ProductTest` (Tarea 2).

---

## Estructura de archivos

| Archivo | Responsabilidad |
|---|---|
| `catalog-service/pom.xml` | JPA, Flyway, PostgreSQL y Testcontainers; copia de `seed-data.md` al classpath de pruebas |
| `.../resources/application.yml` | Datasource (valores locales), JPA `validate`, `open-in-view=false` |
| `.../resources/db/migration/V1__create_products.sql`, `V2__seed_products.sql` | Esquema y semilla |
| `.../product/Product.java` | Entidad con la regla `applyStock` |
| `.../product/ProductRepository.java` | Interfaz Spring Data |
| `.../product/ProductNotFoundException.java` | SKU inexistente |
| `.../product/ProductQueryService.java` | Casos de uso de consulta |
| `.../product/ProductResponse.java` | DTO de respuesta |
| `.../product/ProductController.java` | `GET /api/v1/catalog/products` y `/{sku}` |
| `.../error/GlobalExceptionHandler.java`, `ProblemType.java` | 404 por producto y `instance` robusto |
| `src/test/.../support/PostgresIntegrationTest.java` | Base compartida con el contenedor PostgreSQL |
| `src/test/.../product/*Test.java`, `error/ProblemTypeTest.java` | Pruebas de la funcionalidad |
| `README.md`, spec del Catálogo | Documentación al día |

---

### Task 1: Base de datos, migraciones y semilla

**Files:**
- Modify: `catalog-service/pom.xml`
- Modify: `catalog-service/src/main/resources/application.yml`
- Create: `catalog-service/src/main/resources/db/migration/V1__create_products.sql`
- Create: `catalog-service/src/main/resources/db/migration/V2__seed_products.sql`
- Create: `catalog-service/src/test/java/com/retail/catalog/support/PostgresIntegrationTest.java`
- Test: `catalog-service/src/test/java/com/retail/catalog/product/SeedDataIntegrationTest.java`
- Modify (test): `catalog-service/src/test/java/com/retail/catalog/CatalogApplicationIntegrationTest.java`

**Interfaces:**
- Produces: `PostgresIntegrationTest` (clase abstracta; las `@SpringBootTest` la extienden y reciben `spring.datasource.*` de un PostgreSQL real); tabla `products` sembrada; el servicio arranca con base de datos.

- [ ] **Step 1: Dependencias y copia de la semilla en el `pom.xml`**

En `<dependencies>`, justo **antes** de la dependencia `org.springdoc:springdoc-openapi-starter-webmvc-ui`:

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

y **antes** de la dependencia `com.tngtech.archunit:archunit-junit5`:

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

En el `maven-resources-plugin`, dentro de `<executions>` y después de `copy-contracts-for-tests`:

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

- [ ] **Step 2: Datasource y JPA en `application.yml`**

Reemplazar el bloque `spring:` (hoy solo tiene `application.name`) por:

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
```

- [ ] **Step 3: Base compartida de las pruebas de integración**

`catalog-service/src/test/java/com/retail/catalog/support/PostgresIntegrationTest.java`:

```java
package com.retail.catalog.support;

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
        // Limita el pool: con muchos contextos de Spring en caché, el valor por defecto agotaría las conexiones de PostgreSQL.
        registry.add("spring.datasource.hikari.maximum-pool-size", () -> 5);
    }
}
```

En `CatalogApplicationIntegrationTest.java`: cambiar la declaración por `class CatalogApplicationIntegrationTest extends PostgresIntegrationTest {` y agregar, después del import de `org.springframework.test.web.servlet.MockMvc`, una línea en blanco y `import com.retail.catalog.support.PostgresIntegrationTest;`.

- [ ] **Step 4: Escribir la prueba de la semilla (debe fallar)**

`catalog-service/src/test/java/com/retail/catalog/product/SeedDataIntegrationTest.java`:

```java
package com.retail.catalog.product;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.TreeMap;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;

import com.retail.catalog.support.PostgresIntegrationTest;

/**
 * La semilla de Flyway debe coincidir con {@code docs/seed-data.md}, el documento que también sigue
 * Inventario: así Catálogo e Inventario no se desalinean.
 */
@SpringBootTest
class SeedDataIntegrationTest extends PostgresIntegrationTest {

    record SeededProduct(String name, int price, int stock) {
    }

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void seededProductsMatchTheSeedDataDocument() throws IOException {
        Map<String, SeededProduct> documented = readDocumentedProducts();

        Map<String, SeededProduct> seeded = new TreeMap<>();
        jdbcTemplate.query("SELECT sku, name, price, available_stock FROM products WHERE sku NOT LIKE 'TEST-%'",
                resultSet -> {
                    seeded.put(resultSet.getString("sku"), new SeededProduct(resultSet.getString("name"),
                            resultSet.getInt("price"), resultSet.getInt("available_stock")));
                });

        assertThat(documented).hasSize(7);
        assertThat(seeded).isEqualTo(documented);
    }

    @Test
    void seededProductsStartWithoutEventsAndInCop() {
        assertThat(jdbcTemplate.queryForList(
                "SELECT stock_version FROM products WHERE sku NOT LIKE 'TEST-%'", Long.class))
                .isNotEmpty().containsOnly(0L);
        assertThat(jdbcTemplate.queryForList(
                "SELECT last_event_latency_ms FROM products WHERE sku NOT LIKE 'TEST-%' AND last_event_latency_ms IS NOT NULL",
                Long.class)).isEmpty();
        assertThat(jdbcTemplate.queryForList("SELECT DISTINCT currency FROM products WHERE sku NOT LIKE 'TEST-%'",
                String.class)).containsExactly("COP");
    }

    /** Lee la tabla del documento: columna 1 = SKU, 2 = producto, 3 = precio (con separador de miles), 4 = stock. */
    private static Map<String, SeededProduct> readDocumentedProducts() throws IOException {
        Map<String, SeededProduct> documented = new TreeMap<>();
        try (InputStream input = new ClassPathResource("seed-data.md").getInputStream()) {
            for (String line : new String(input.readAllBytes(), StandardCharsets.UTF_8).split("\n")) {
                if (!line.startsWith("| `")) {
                    continue;
                }
                String[] cells = line.split("\\|");
                documented.put(cells[1].trim().replace("`", ""),
                        new SeededProduct(cells[2].trim(), Integer.parseInt(cells[3].replaceAll("\\D", "")),
                                Integer.parseInt(cells[4].trim())));
            }
        }
        return documented;
    }
}
```

- [ ] **Step 5: Ejecutarla y verificar que falla**

Run (desde `catalog-service/`): `./mvnw verify -Dit.test=SeedDataIntegrationTest -Dtest=NoSuchTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: FAIL — la tabla `products` no existe (`relation "products" does not exist`). Si falla por otra causa (dependencia sin resolver, Docker apagado), corregir eso primero.

- [ ] **Step 6: Migraciones**

`catalog-service/src/main/resources/db/migration/V1__create_products.sql`:

```sql
-- Productos del catálogo y su vista de disponibilidad. El Catálogo es el único dueño de esta tabla;
-- la disponibilidad solo cambia al aplicar eventos de Inventario.
CREATE TABLE products (
    sku                   VARCHAR(64)  PRIMARY KEY,
    name                  VARCHAR(120) NOT NULL,
    description           TEXT         NOT NULL,
    -- Precio en pesos colombianos, sin decimales.
    price                 INTEGER      NOT NULL CHECK (price >= 0),
    currency              VARCHAR(3)   NOT NULL,
    available_stock       INTEGER      NOT NULL CHECK (available_stock >= 0),
    -- Versión interna del stock (la del último evento aplicado); la semilla usa 0.
    stock_version         BIGINT       NOT NULL,
    updated_at            TIMESTAMPTZ  NOT NULL,
    -- Latencia del último evento aplicado; nulo si nunca recibió un evento.
    last_event_latency_ms BIGINT
);
```

`catalog-service/src/main/resources/db/migration/V2__seed_products.sql`:

```sql
-- Datos semilla de docs/seed-data.md (los mismos SKU que siembra Inventario). La versión interna del
-- stock arranca en 0 para que cualquier primer evento (stockVersion >= 1) se aplique.
INSERT INTO products (sku, name, description, price, currency, available_stock, stock_version, updated_at,
                      last_event_latency_ms) VALUES
    ('TENIS-RUNNER-42',     'Tenis Runner talla 42',         'Tenis para correr, malla transpirable',                   289900, 'COP',  5, 0, date_trunc('milliseconds', now()), NULL),
    ('BALON-FUTBOL-5',      'Balón de fútbol n.º 5',         'Balón de fútbol de tamaño oficial, costuras reforzadas',  119900, 'COP',  1, 0, date_trunc('milliseconds', now()), NULL),
    ('CAMISETA-DRYFIT-M',   'Camiseta Dry-Fit talla M',      'Camiseta deportiva de secado rápido',                      79900, 'COP',  0, 0, date_trunc('milliseconds', now()), NULL),
    ('MOCHILA-TRAIL-20L',   'Mochila Trail 20 L',            'Mochila de 20 litros para senderismo, con espalda ventilada', 189900, 'COP', 10, 0, date_trunc('milliseconds', now()), NULL),
    ('BOTELLA-TERMICA-750', 'Botella térmica 750 ml',        'Botella de acero inoxidable que conserva la temperatura',  59900, 'COP', 10, 0, date_trunc('milliseconds', now()), NULL),
    ('GORRA-SPORT-NEGRA',   'Gorra Sport negra',             'Gorra deportiva negra con ajuste trasero',                 39900, 'COP', 50, 0, date_trunc('milliseconds', now()), NULL),
    ('CALCETINES-PACK-3',   'Calcetines deportivos pack x3', 'Pack de tres pares de calcetines deportivos',              24900, 'COP', 20, 0, date_trunc('milliseconds', now()), NULL);
```

- [ ] **Step 7: Verificar**

Run: `./mvnw verify`
Expected: BUILD SUCCESS, 0 fallos. Cifras de referencia: 11 pruebas rápidas y 7 de integración (5 existentes + 2 de la semilla), todas con PostgreSQL de Testcontainers.

- [ ] **Step 8: Commit**

```bash
git add catalog-service
git commit -m "feat: add PostgreSQL, Flyway migrations and seed data to catalog service

Co-Authored-By: Claude <modelo> <noreply@anthropic.com>"
```

---

### Task 2: Entidad `Product` y repositorio

**Files:**
- Create: `catalog-service/src/main/java/com/retail/catalog/product/Product.java`
- Create: `catalog-service/src/main/java/com/retail/catalog/product/ProductRepository.java`
- Test: `catalog-service/src/test/java/com/retail/catalog/product/ProductTest.java`
- Test: `catalog-service/src/test/java/com/retail/catalog/product/ProductRepositoryIntegrationTest.java`

**Interfaces:**
- Consumes: `PostgresIntegrationTest` y la tabla `products` (Tarea 1).
- Produces:
  - `public class Product`: `Product(String sku, String name, String description, int price, String currency, int availableStock, Instant updatedAt)` (versión de stock 0, latencia nula); `void applyStock(int newStock, long newStockVersion, Instant occurredAt, Instant now)` (lanza `IllegalArgumentException` si `newStock < 0`); getters `getSku()`, `getName()`, `getDescription()`, `getPrice(): int`, `getCurrency()`, `getAvailableStock(): int`, `getStockVersion(): long`, `getUpdatedAt(): Instant`, `getLastEventLatencyMs(): Long` (nulo si nunca se aplicó un evento). Sin setters.
  - `interface ProductRepository extends JpaRepository<Product, String>`.

- [ ] **Step 1: Escribir la prueba unitaria (debe fallar)**

`src/test/java/com/retail/catalog/product/ProductTest.java`:

```java
package com.retail.catalog.product;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;

import org.junit.jupiter.api.Test;

class ProductTest {

    private static final Instant SEEDED_AT = Instant.parse("2026-10-12T10:00:00Z");

    private static Product product() {
        return new Product("TENIS-RUNNER-42", "Tenis Runner talla 42", "Tenis para correr", 289900, "COP", 5,
                SEEDED_AT);
    }

    @Test
    void aNewProductHasNoEventsApplied() {
        Product product = product();

        assertThat(product.getStockVersion()).isZero();
        assertThat(product.getLastEventLatencyMs()).isNull();
        assertThat(product.getUpdatedAt()).isEqualTo(SEEDED_AT);
    }

    @Test
    void applyStockSetsStockVersionTimestampAndLatency() {
        Product product = product();
        Instant occurredAt = Instant.parse("2026-10-12T11:00:00.100Z");
        Instant now = Instant.parse("2026-10-12T11:00:00.489Z");

        product.applyStock(4, 8L, occurredAt, now);

        assertThat(product.getAvailableStock()).isEqualTo(4);
        assertThat(product.getStockVersion()).isEqualTo(8L);
        assertThat(product.getUpdatedAt()).isEqualTo(now);
        assertThat(product.getLastEventLatencyMs()).isEqualTo(389L);
    }

    @Test
    void latencyIsNeverNegativeWhenTheClocksDisagree() {
        Product product = product();
        Instant occurredAt = Instant.parse("2026-10-12T11:00:01Z");
        Instant now = Instant.parse("2026-10-12T11:00:00Z");

        product.applyStock(4, 2L, occurredAt, now);

        assertThat(product.getLastEventLatencyMs()).isZero();
    }

    @Test
    void aNegativeStockIsRejectedAndLeavesTheProductUntouched() {
        Product product = product();

        assertThatThrownBy(() -> product.applyStock(-1, 2L, SEEDED_AT, SEEDED_AT))
                .isInstanceOf(IllegalArgumentException.class);

        assertThat(product.getAvailableStock()).isEqualTo(5);
        assertThat(product.getStockVersion()).isZero();
    }

    @Test
    void stockCanDropToZero() {
        Product product = product();

        product.applyStock(0, 2L, SEEDED_AT, SEEDED_AT);

        assertThat(product.getAvailableStock()).isZero();
    }
}
```

- [ ] **Step 2: Ejecutarla y verificar que falla**

Run: `./mvnw test -Dtest=ProductTest`
Expected: FAIL de compilación (`Product` no existe).

- [ ] **Step 3: Implementar la entidad y el repositorio**

`src/main/java/com/retail/catalog/product/Product.java`:

```java
package com.retail.catalog.product;

import java.time.Duration;
import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * Producto del catálogo con su vista de disponibilidad. La disponibilidad cambia solo con
 * {@link #applyStock}, por eso no tiene setters. No tiene {@code @Version}: la escribe un único
 * consumidor de eventos, que ya descarta duplicados y versiones viejas antes de llamar a
 * {@code applyStock}.
 */
@Entity
@Table(name = "products")
public class Product {

    @Id
    @Column(length = 64)
    private String sku;

    @Column(nullable = false, length = 120)
    private String name;

    @Column(nullable = false)
    private String description;

    @Column(nullable = false)
    private int price;

    @Column(nullable = false, length = 3)
    private String currency;

    @Column(name = "available_stock", nullable = false)
    private int availableStock;

    @Column(name = "stock_version", nullable = false)
    private long stockVersion;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "last_event_latency_ms")
    private Long lastEventLatencyMs;

    /** Constructor exigido por JPA. */
    protected Product() {
    }

    /** Producto nuevo, sin eventos aplicados (versión de stock 0 y sin latencia). */
    public Product(String sku, String name, String description, int price, String currency, int availableStock,
            Instant updatedAt) {
        this.sku = sku;
        this.name = name;
        this.description = description;
        this.price = price;
        this.currency = currency;
        this.availableStock = availableStock;
        this.stockVersion = 0;
        this.updatedAt = updatedAt;
    }

    /**
     * Aplica el stock absoluto de un evento. Fija el stock y la versión, {@code updatedAt = now} y la
     * latencia {@code now − occurredAt} en milisegundos (nunca negativa: si los relojes difieren, 0).
     *
     * @throws IllegalArgumentException si el stock es negativo
     */
    public void applyStock(int newStock, long newStockVersion, Instant occurredAt, Instant now) {
        if (newStock < 0) {
            throw new IllegalArgumentException("newStock must not be negative");
        }
        this.availableStock = newStock;
        this.stockVersion = newStockVersion;
        this.updatedAt = now;
        this.lastEventLatencyMs = Math.max(0, Duration.between(occurredAt, now).toMillis());
    }

    public String getSku() {
        return sku;
    }

    public String getName() {
        return name;
    }

    public String getDescription() {
        return description;
    }

    public int getPrice() {
        return price;
    }

    public String getCurrency() {
        return currency;
    }

    public int getAvailableStock() {
        return availableStock;
    }

    public long getStockVersion() {
        return stockVersion;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public Long getLastEventLatencyMs() {
        return lastEventLatencyMs;
    }
}
```

`src/main/java/com/retail/catalog/product/ProductRepository.java`:

```java
package com.retail.catalog.product;

import org.springframework.data.jpa.repository.JpaRepository;

public interface ProductRepository extends JpaRepository<Product, String> {
}
```

- [ ] **Step 4: Verificar la prueba unitaria**

Run: `./mvnw test -Dtest=ProductTest`
Expected: PASS (5 pruebas).

- [ ] **Step 5: Prueba de integración del repositorio**

`src/test/java/com/retail/catalog/product/ProductRepositoryIntegrationTest.java`:

```java
package com.retail.catalog.product;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;

import com.retail.catalog.support.PostgresIntegrationTest;

@SpringBootTest
class ProductRepositoryIntegrationTest extends PostgresIntegrationTest {

    private static final Instant SEEDED_AT = Instant.parse("2026-10-12T10:00:00.000Z");

    @Autowired
    private ProductRepository productRepository;

    private String sku;

    private Product newProduct(int availableStock) {
        sku = "TEST-" + UUID.randomUUID().toString().toUpperCase();
        return new Product(sku, "Producto de prueba", "Descripción", 1000, "COP", availableStock, SEEDED_AT);
    }

    @AfterEach
    void deleteTheTestProduct() {
        if (sku != null) {
            productRepository.deleteById(sku);
        }
    }

    @Test
    void persistsAndReadsBackTheProduct() {
        productRepository.saveAndFlush(newProduct(7));

        Product found = productRepository.findById(sku).orElseThrow();

        assertThat(found.getName()).isEqualTo("Producto de prueba");
        assertThat(found.getAvailableStock()).isEqualTo(7);
        assertThat(found.getStockVersion()).isZero();
        assertThat(found.getUpdatedAt()).isEqualTo(SEEDED_AT);
        assertThat(found.getLastEventLatencyMs()).isNull();
    }

    @Test
    void anAppliedStockChangeIsPersisted() {
        productRepository.saveAndFlush(newProduct(7));
        Product product = productRepository.findById(sku).orElseThrow();

        product.applyStock(3, 5L, Instant.parse("2026-10-12T11:00:00.000Z"), Instant.parse("2026-10-12T11:00:00.250Z"));
        productRepository.saveAndFlush(product);

        Product reloaded = productRepository.findById(sku).orElseThrow();
        assertThat(reloaded.getAvailableStock()).isEqualTo(3);
        assertThat(reloaded.getStockVersion()).isEqualTo(5L);
        assertThat(reloaded.getLastEventLatencyMs()).isEqualTo(250L);
        assertThat(reloaded.getUpdatedAt()).isEqualTo(Instant.parse("2026-10-12T11:00:00.250Z"));
    }

    @Test
    void databaseRejectsNegativeStockEvenIfTheCodeAllowedIt() {
        assertThatThrownBy(() -> productRepository.saveAndFlush(newProduct(-1)))
                .isInstanceOf(DataIntegrityViolationException.class);
        sku = null;
    }
}
```

- [ ] **Step 6: Verificar todo**

Run: `./mvnw verify`
Expected: BUILD SUCCESS, 0 fallos. Cifras de referencia: 16 pruebas rápidas (11 + 5) y 10 de integración (7 + 3). `ddl-auto=validate` pasa con la entidad (el mapeo coincide con la migración).

- [ ] **Step 7: Commit**

```bash
git add catalog-service/src
git commit -m "feat: add Product entity with applyStock rule and repository

Co-Authored-By: Claude <modelo> <noreply@anthropic.com>"
```

---

### Task 3: Consulta REST de productos y pendientes de la 5.1

**Files:**
- Create: `catalog-service/src/main/java/com/retail/catalog/product/ProductNotFoundException.java`
- Create: `catalog-service/src/main/java/com/retail/catalog/product/ProductQueryService.java`
- Create: `catalog-service/src/main/java/com/retail/catalog/product/ProductResponse.java`
- Create: `catalog-service/src/main/java/com/retail/catalog/product/ProductController.java`
- Modify: `catalog-service/src/main/java/com/retail/catalog/error/GlobalExceptionHandler.java`
- Modify: `catalog-service/src/main/java/com/retail/catalog/error/ProblemType.java`
- Modify (test): `catalog-service/src/test/java/com/retail/catalog/architecture/ArchitectureTest.java`
- Test: `.../product/ProductQueryServiceTest.java`, `.../product/ProductResponseTest.java`, `.../error/ProblemTypeTest.java`, `.../product/ProductEndpointIntegrationTest.java`

**Interfaces:**
- Consumes: `Product`, `ProductRepository` (Tarea 2), `ProblemType.NOT_FOUND`, `ContractAssertions.assertMatchesSchema`, `PostgresIntegrationTest`.
- Produces:
  - `ProductNotFoundException(String sku)` (mensaje `SKU <sku> does not exist`).
  - `ProductQueryService#list(): List<Product>` (ordenada por `sku`) y `#getBySku(String): Product`.
  - `ProductResponse(String sku, String name, String description, int price, String currency, int availableStock, boolean inStock, Instant updatedAt, Long lastEventLatencyMs)` y `ProductResponse.from(Product)`.
  - `GET /api/v1/catalog/products` y `/{sku}`.
  - `ProblemType#toProblemDetail` ya no lanza con rutas que traen `[` o `]` (la `instance` se codifica).

- [ ] **Step 1: Escribir las pruebas unitarias (deben fallar)**

`src/test/java/com/retail/catalog/product/ProductResponseTest.java`:

```java
package com.retail.catalog.product;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;

import org.junit.jupiter.api.Test;

class ProductResponseTest {

    private static final Instant NOW = Instant.parse("2026-10-12T10:00:00Z");

    @Test
    void inStockIsDerivedFromTheAvailableStock() {
        Product available = new Product("A", "Producto A", "Descripción", 1000, "COP", 1, NOW);
        Product soldOut = new Product("B", "Producto B", "Descripción", 1000, "COP", 0, NOW);

        assertThat(ProductResponse.from(available).inStock()).isTrue();
        assertThat(ProductResponse.from(soldOut).inStock()).isFalse();
    }

    @Test
    void copiesTheProductFieldsAndKeepsTheLatencyNullUntilAnEventArrives() {
        Product product = new Product("TENIS-RUNNER-42", "Tenis Runner talla 42", "Tenis para correr", 289900,
                "COP", 5, NOW);

        ProductResponse response = ProductResponse.from(product);

        assertThat(response.sku()).isEqualTo("TENIS-RUNNER-42");
        assertThat(response.name()).isEqualTo("Tenis Runner talla 42");
        assertThat(response.price()).isEqualTo(289900);
        assertThat(response.currency()).isEqualTo("COP");
        assertThat(response.availableStock()).isEqualTo(5);
        assertThat(response.updatedAt()).isEqualTo(NOW);
        assertThat(response.lastEventLatencyMs()).isNull();
    }
}
```

`src/test/java/com/retail/catalog/product/ProductQueryServiceTest.java`:

```java
package com.retail.catalog.product;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Sort;

class ProductQueryServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-12T10:00:00Z");

    private final ProductRepository productRepository = mock(ProductRepository.class);
    private final ProductQueryService service = new ProductQueryService(productRepository);

    @Test
    void listsTheProductsOrderedBySku() {
        List<Product> products = List.of(new Product("A", "Producto A", "Descripción", 1000, "COP", 1, NOW));
        when(productRepository.findAll(Sort.by("sku"))).thenReturn(products);

        assertThat(service.list()).isSameAs(products);
    }

    @Test
    void returnsTheProductOfAnExistingSku() {
        Product product = new Product("A", "Producto A", "Descripción", 1000, "COP", 1, NOW);
        when(productRepository.findById("A")).thenReturn(Optional.of(product));

        assertThat(service.getBySku("A")).isSameAs(product);
    }

    @Test
    void throwsProductNotFoundForAnUnknownSku() {
        when(productRepository.findById("UNKNOWN")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getBySku("UNKNOWN"))
                .isInstanceOf(ProductNotFoundException.class)
                .hasMessage("SKU UNKNOWN does not exist");
    }
}
```

`src/test/java/com/retail/catalog/error/ProblemTypeTest.java`:

```java
package com.retail.catalog.error;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.http.ProblemDetail;

class ProblemTypeTest {

    @Test
    void instanceKeepsAPlainPathAsIs() {
        ProblemDetail problem = ProblemType.NOT_FOUND.toProblemDetail("missing", "/api/v1/catalog/products/A");

        assertThat(problem.getInstance()).hasToString("/api/v1/catalog/products/A");
    }

    @Test
    void aPathWithCharactersThatAreNotValidInAUriDoesNotMakeTheErrorHandlerFail() {
        ProblemDetail problem = ProblemType.NOT_FOUND.toProblemDetail("missing", "/api/v1/catalog/products/a[b]");

        assertThat(problem.getInstance()).hasToString("/api/v1/catalog/products/a%5Bb%5D");
    }
}
```

- [ ] **Step 2: Ejecutarlas y verificar que fallan**

Run: `./mvnw test -Dtest='ProductResponseTest,ProductQueryServiceTest,ProblemTypeTest'`
Expected: FAIL — compilación (`ProductResponse`, `ProductQueryService` no existen) y, una vez compila, `ProblemTypeTest` falla con `IllegalArgumentException` por el `[`.

- [ ] **Step 3: Implementar servicio, DTO, controlador y excepción**

`product/ProductNotFoundException.java`:

```java
package com.retail.catalog.product;

/** El SKU no existe en el catálogo. No conoce HTTP: el manejador global de errores la traduce. */
public class ProductNotFoundException extends RuntimeException {

    public ProductNotFoundException(String sku) {
        super("SKU " + sku + " does not exist");
    }
}
```

`product/ProductQueryService.java`:

```java
package com.retail.catalog.product;

import java.util.List;

import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Caso de uso: consultar productos y su disponibilidad. */
@Service
public class ProductQueryService {

    private final ProductRepository productRepository;

    public ProductQueryService(ProductRepository productRepository) {
        this.productRepository = productRepository;
    }

    /** Todos los productos, ordenados por SKU (sin paginación, como dice el contrato). */
    @Transactional(readOnly = true)
    public List<Product> list() {
        return productRepository.findAll(Sort.by("sku"));
    }

    @Transactional(readOnly = true)
    public Product getBySku(String sku) {
        return productRepository.findById(sku).orElseThrow(() -> new ProductNotFoundException(sku));
    }
}
```

`product/ProductResponse.java`:

```java
package com.retail.catalog.product;

import java.time.Instant;

/** Producto con su disponibilidad (schema {@code Product} del contrato). */
public record ProductResponse(String sku, String name, String description, int price, String currency,
        int availableStock, boolean inStock, Instant updatedAt, Long lastEventLatencyMs) {

    public static ProductResponse from(Product product) {
        return new ProductResponse(product.getSku(), product.getName(), product.getDescription(),
                product.getPrice(), product.getCurrency(), product.getAvailableStock(),
                product.getAvailableStock() > 0, product.getUpdatedAt(), product.getLastEventLatencyMs());
    }
}
```

`product/ProductController.java`:

```java
package com.retail.catalog.product;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Consulta pública de productos y su disponibilidad. */
@RestController
@RequestMapping("/api/v1/catalog/products")
public class ProductController {

    private final ProductQueryService productQueryService;

    public ProductController(ProductQueryService productQueryService) {
        this.productQueryService = productQueryService;
    }

    @GetMapping
    public List<ProductResponse> listProducts() {
        return productQueryService.list().stream().map(ProductResponse::from).toList();
    }

    @GetMapping("/{sku}")
    public ProductResponse getProduct(@PathVariable String sku) {
        return ProductResponse.from(productQueryService.getBySku(sku));
    }
}
```

- [ ] **Step 4: Errores: 404 por producto y `instance` robusto**

En `error/GlobalExceptionHandler.java`: agregar el import `com.retail.catalog.product.ProductNotFoundException;` (en un bloque aparte después de los `org.springframework...`, separado por una línea en blanco) y, justo antes del Javadoc `/** Da el tipo genérico ... */`:

```java
    @ExceptionHandler(ProductNotFoundException.class)
    public ResponseEntity<ProblemDetail> handleProductNotFound(ProductNotFoundException exception,
            HttpServletRequest request) {
        ProblemDetail problem = ProblemType.NOT_FOUND.toProblemDetail(exception.getMessage(), request.getRequestURI());
        return ResponseEntity.status(problem.getStatus()).body(problem);
    }
```

En `error/ProblemType.java`: agregar el import `org.springframework.web.util.UriComponentsBuilder;` (después de `org.springframework.http.ProblemDetail`), cambiar en `toProblemDetail` la línea `problem.setInstance(URI.create(requestPath));` por `problem.setInstance(toInstance(requestPath));` y agregar, antes del Javadoc de `toProblemDetail`:

```java
    /**
     * {@code instance} es un URI, pero la ruta de la petición puede traer caracteres que {@code URI.create}
     * rechaza (p. ej., {@code [}): en ese caso se codifica en lugar de fallar dentro del manejador de errores.
     */
    static URI toInstance(String requestPath) {
        try {
            return URI.create(requestPath);
        } catch (IllegalArgumentException exception) {
            return UriComponentsBuilder.fromPath(requestPath).encode().build().toUri();
        }
    }
```

- [ ] **Step 5: Retirar el permiso de evaluación vacía de ArchUnit**

En `architecture/ArchitectureTest.java`: reemplazar el Javadoc de la clase por

```java
/**
 * Hace cumplir las reglas de dependencia de la spec del Catálogo (sección 3). Ninguna regla permite
 * evaluación vacía: si un paquete deja de tener clases que evaluar, la prueba falla.
 */
```

y quitar `.allowEmptyShould(true)` de las reglas `controllersDoNotUseRepositories` y `productDoesNotDependOnStockEvent`, de modo que terminen en `.should().dependOnClassesThat().haveSimpleNameEndingWith("Repository");` y `.should().dependOnClassesThat().resideInAPackage("com.retail.catalog.stockevent..");`.

- [ ] **Step 6: Verificar las pruebas rápidas**

Run: `./mvnw test`
Expected: PASS — 23 pruebas (16 + 2 de `ProductResponseTest` + 3 de `ProductQueryServiceTest` + 2 de `ProblemTypeTest`), con `ArchitectureTest` sin evaluación vacía.

- [ ] **Step 7: Prueba de integración del endpoint**

`src/test/java/com/retail/catalog/product/ProductEndpointIntegrationTest.java`:

```java
package com.retail.catalog.product;

import static com.retail.catalog.support.ContractAssertions.assertMatchesSchema;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import com.retail.catalog.support.PostgresIntegrationTest;

@SpringBootTest
@AutoConfigureMockMvc
class ProductEndpointIntegrationTest extends PostgresIntegrationTest {

    private static final String PATH = "/api/v1/catalog/products";
    private static final String PROBLEM_TYPE_BASE = "https://api.retail-demo.local/problems/";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JsonMapper jsonMapper;

    @Test
    void listsAllTheSeededProductsOrderedBySkuWithoutAnyToken() throws Exception {
        JsonNode products = jsonMapper.readTree(mockMvc.perform(get(PATH))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andReturn().getResponse().getContentAsString());

        List<String> skus = new ArrayList<>();
        products.forEach(product -> {
            skus.add(product.get("sku").asString());
            assertMatchesSchema(product.toString(), "Product");
        });
        assertThat(skus).contains("TENIS-RUNNER-42", "BALON-FUTBOL-5", "CAMISETA-DRYFIT-M", "MOCHILA-TRAIL-20L",
                "BOTELLA-TERMICA-750", "GORRA-SPORT-NEGRA", "CALCETINES-PACK-3");
        assertThat(skus).isSorted();
    }

    @Test
    void returnsTheProductMatchingTheContract() throws Exception {
        String body = mockMvc.perform(get(PATH + "/TENIS-RUNNER-42"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sku").value("TENIS-RUNNER-42"))
                .andExpect(jsonPath("$.name").value("Tenis Runner talla 42"))
                .andExpect(jsonPath("$.price").value(289900))
                .andExpect(jsonPath("$.currency").value("COP"))
                .andExpect(jsonPath("$.availableStock").value(5))
                .andExpect(jsonPath("$.inStock").value(true))
                .andExpect(jsonPath("$.lastEventLatencyMs").isEmpty())
                .andReturn().getResponse().getContentAsString();

        assertMatchesSchema(body, "Product");
    }

    @Test
    void aSoldOutProductIsNotInStock() throws Exception {
        mockMvc.perform(get(PATH + "/CAMISETA-DRYFIT-M"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.availableStock").value(0))
                .andExpect(jsonPath("$.inStock").value(false));
    }

    @Test
    void unknownSkuIsNotFound() throws Exception {
        String body = mockMvc.perform(get(PATH + "/DOES-NOT-EXIST"))
                .andExpect(status().isNotFound())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.type").value(PROBLEM_TYPE_BASE + "not-found"))
                .andExpect(jsonPath("$.instance").value(PATH + "/DOES-NOT-EXIST"))
                .andReturn().getResponse().getContentAsString();

        assertMatchesSchema(body, "Problem");
    }

    @Test
    void skusThatDoNotFollowThePatternOrAreTooLongAreNotFoundInsteadOfFailing() throws Exception {
        mockMvc.perform(get(PATH + "/lower-case sku"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.type").value(PROBLEM_TYPE_BASE + "not-found"));
        mockMvc.perform(get(PATH + "/" + "A".repeat(200)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.type").value(PROBLEM_TYPE_BASE + "not-found"));
    }

    @Test
    void theCatalogIsReadOnlyOverRest() throws Exception {
        String body = mockMvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.type").value(PROBLEM_TYPE_BASE + "http-error"))
                .andReturn().getResponse().getContentAsString();

        assertMatchesSchema(body, "Problem");
    }
}
```

- [ ] **Step 8: Verificar todo**

Run: `./mvnw verify`
Expected: BUILD SUCCESS, 0 fallos. Cifras de referencia: 23 pruebas rápidas y 16 de integración (10 + 6).

- [ ] **Step 9: Commit**

```bash
git add catalog-service/src
git commit -m "feat: add product query endpoints and harden problem instance

Co-Authored-By: Claude <modelo> <noreply@anthropic.com>"
```

---

### Task 4: Documentación y verificación de punta a punta

**Files:**
- Modify: `README.md`
- Modify: `docs/superpowers/specs/2026-10-12-catalogo-design.md`

**Interfaces:**
- Consumes: todo lo anterior.
- Produces: documentación al día; verificación del servicio dentro de Compose con su base de datos.

- [ ] **Step 1: README**

En la sección "Servicio de Catálogo" (leer antes el texto exacto):
- Reemplazar el párrafo que dice que "En esta etapa solo está el esqueleto ..." por:

````markdown
Es el consumidor del flujo: mantiene la vista de disponibilidad con los eventos de Inventario y la
expone por REST sin token. La consulta ya está disponible (el consumidor de eventos llega en las
siguientes sub-tareas): la base `catalog-db` se siembra con los 7 productos de `docs/seed-data.md`.

```bash
curl http://localhost:8082/api/v1/catalog/products                    # todos, ordenados por SKU
curl http://localhost:8082/api/v1/catalog/products/TENIS-RUNNER-42    # uno (404 si no existe)
```

El contrato publicado es el que muestra Swagger UI (`/swagger-ui/index.html`, escrito a mano);
`/v3/api-docs` es la vista generada del código y solo informativa.

Para correrlo desde el IDE o con `./mvnw spring-boot:run`, detener antes su contenedor
(`docker compose stop catalog-service`) y levantar su base (`docker compose up -d --wait catalog-db`,
puerto 5442). `./mvnw verify` requiere Docker (PostgreSQL con Testcontainers).
````

`docs/superpowers/specs/2026-10-12-catalogo-design.md` (leer antes el texto exacto):
1. §5 "Esquema": agregar, justo debajo del bloque SQL, la frase: "La 5.2 crea `products` (`V1__create_products.sql`) y su semilla (`V2__seed_products.sql`); `processed_events` se crea en la 5.3 (`V3__create_processed_events.sql`), cuando el código la usa." y quitar la tabla `processed_events` del bloque SQL de §5 si aparece ahí (dejar solo `products`; la definición de `processed_events` queda descrita en §6 o en la 5.3).
2. §7 "Pendientes": reemplazar la fila de `URI.create(requestPath)` por: "`URI.create(requestPath)` en `GlobalExceptionHandler` y `ProblemType` de **Inventario** lanza si la ruta trae `[` o `]` (corregido en el Catálogo en la 5.2 con `ProblemType.toInstance`; aplicar lo mismo allí)" con sub-tarea "Cualquier cambio futuro en Inventario".
3. §7 tabla de sub-tareas, fila **5.2**: agregar al final de "Entrega": "; el contrato publicado es el de Swagger UI y `/v3/api-docs` es solo informativo (aclarado en el README); reglas 3 y 4 de ArchUnit sin evaluación vacía".

- [ ] **Step 2: Verificación de punta a punta**

Con los puertos por defecto libres (`docker compose ps`; si hay un stack `retail` en marcha, `docker compose down -v` antes), desde la raíz del repositorio y cada comando por separado:

```bash
cd catalog-service && ./mvnw verify
```
Expected: BUILD SUCCESS, 23 pruebas rápidas y 16 de integración.

```bash
docker compose up -d --wait --build catalog-service
```
Expected: `retail-catalog-service-1 Healthy` (sin `--build` Compose reutilizaría una imagen anterior).

```bash
curl -s http://localhost:8082/api/v1/catalog/products | jq -c '.[] | {sku,availableStock,inStock,price,lastEventLatencyMs}'
curl -s http://localhost:8082/api/v1/catalog/products/TENIS-RUNNER-42 | jq -c .
curl -s -w ' [%{http_code}]\n' http://localhost:8082/api/v1/catalog/products/NOPE
docker compose exec -T catalog-db psql -U catalog -d catalog -c "select count(*) from products"
```
Expected: 7 productos ordenados por SKU (`BALON-FUTBOL-5` primero, `TENIS-RUNNER-42` último; `CAMISETA-DRYFIT-M` con `availableStock` 0 e `inStock` false; todos con `lastEventLatencyMs` nulo); el detalle de `TENIS-RUNNER-42` con `availableStock` 5, `price` 289900 y `currency` `COP`; un problema `not-found` con `[404]`; y `count` = 7.

```bash
docker compose down -v
```

- [ ] **Step 3: Commit**

```bash
git add README.md docs/superpowers/specs/2026-10-12-catalogo-design.md
git commit -m "docs: document catalog product query and update carry-overs

Co-Authored-By: Claude <modelo> <noreply@anthropic.com>"
```

---

## Autorrevisión

- **Cobertura de la spec (5.2):** migraciones y semilla igual al documento (T1); `Product` con `applyStock`, sin setters, latencia nunca negativa (T2); `GET /products` ordenado y `/{sku}` con 404, SKU raro o largo sin fallar, contrato validado (T3); `inStock` derivado y no guardado (T3); sin autenticación ni paginación (T3).
- **Pendientes heredados de la 5.1:** reglas 3 y 4 de ArchUnit sin evaluación vacía (T3); `/v3/api-docs` aclarado en el README (T4); `URI.create` robusto en el Catálogo (T3, con el resto anotado para Inventario en la spec, T4); `ProductNotFoundException` → 404 (T3).
- **Consistencia de tipos:** `Product(String, String, String, int, String, int, Instant)`, `applyStock(int, long, Instant, Instant)`, getters tipados (`getPrice(): int`, `getStockVersion(): long`, `getLastEventLatencyMs(): Long`); `ProductQueryService#list/getBySku`; `ProductResponse.from(Product)`.
- **Conteos de referencia:** rápidas 11 → 16 (T2) → 23 (T3); integración 5 → 7 (T1) → 10 (T2) → 16 (T3).
- **Verificado en una copia temporal** antes de escribir el plan: 23 rápidas + 16 de integración en verde; en Compose, el servicio llega a `healthy`, `/products` devuelve los 7 productos ordenados, el detalle y el 404 son los esperados y la tabla tiene 7 filas.
