# Catálogo 5.1: esqueleto del servicio — Plan de implementación

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Crear `catalog-service` como proyecto Spring Boot independiente que arranca dentro de Compose, sirve Swagger UI con el contrato escrito a mano, responde errores en `application/problem+json` y hace cumplir sus reglas de arquitectura, sin endpoints de negocio todavía.

**Architecture:** Mismo esqueleto por capas que `inventory-service` (verificado en las fases 4.1 a 4.6): `config/` (reloj) y `error/` (manejador global y tipos de problema), contratos copiados por Maven desde `docs/contracts` (nunca duplicados) y una prueba ArchUnit. Sin base de datos, sin RabbitMQ y sin seguridad en esta sub-tarea: cada dependencia llega con la sub-tarea que la usa (5.2 base de datos, 5.4 AMQP).

**Tech Stack:** Spring Boot 4.1.1 (`spring-boot-starter-webmvc`, `-validation`, `-actuator`), springdoc 3.1.1, ArchUnit 1.5.1, networknt json-schema-validator 3.0.8, Maven wrapper (`./mvnw`), Docker (eclipse-temurin 25), Docker Compose.

**Spec:** `docs/superpowers/specs/2026-10-12-catalogo-design.md` (secciones 1 a 4 y 7). Contrato: `docs/contracts/openapi/catalog-service.yaml`.

## Global Constraints

- Spring Boot `4.1.1`, Java `25`, Maven solo con el wrapper `./mvnw` desde `catalog-service/` (no hay Maven instalado). Paquete base `com.retail.catalog`, `groupId` `com.retail`, `artifactId` `catalog-service`.
- **Nombres completos, sin abreviaturas** (salvo `SKU`, `JWT`, `UUID`, `DTO`, `HTTP`, `JSON`, `DLQ` y unidades). Código en inglés; comentarios, Javadoc y documentación en español.
- Commits en inglés, breves, Conventional Commits, con la línea `Co-Authored-By: Claude <modelo> <noreply@anthropic.com>`.
- La ruta del repositorio contiene espacios: toda ruta en comandos va entre comillas dobles.
- Puerto `8082`. Sin Spring Security (consultas públicas). Errores en `application/problem+json` (RFC 9457) con los tipos `https://api.retail-demo.local/problems/not-found`, `.../internal-error` y el genérico `.../http-error` para los errores estándar de Spring MVC. El Catálogo no define `validation-error`: su contrato no tiene 400 ni recibe cuerpos o parámetros.
- Los contratos se **copian** desde `docs/contracts` al compilar (nunca hay una copia a mano): el OpenAPI del Catálogo a `static/openapi/` (Swagger UI) y todo `docs/contracts` al classpath de pruebas bajo `contracts/`. Swagger UI apunta a `/openapi/catalog-service.yaml`.
- Dependencias de 5.1: web, validación, actuator, springdoc y, para pruebas, ArchUnit y el validador de JSON Schema. **Nada de Data JPA, Flyway, PostgreSQL, AMQP ni Testcontainers** (llegan en 5.2 y 5.4).
- Reloj en UTC con precisión de milisegundos (`Clock.tick(Clock.systemUTC(), Duration.ofMillis(1))`).
- Logging con las claves del MDC `sku` y `eventId` en el patrón de nivel: `"%5p [sku=%X{sku:-}] [eventId=%X{eventId:-}]"`.
- Pruebas rápidas (`./mvnw test`, unitarias y arquitectura) frente a integración (`*IntegrationTest`, `./mvnw verify`, failsafe).
- Contenedor con usuario sin privilegios. El contexto de construcción de Docker es la **raíz** del repositorio (con `.dockerignore` de lista de permitidos).
- Línea base antes de empezar: `catalog-service/` no existe; `inventory-service` y `scripts/verify-infra.sh` están en verde.

## Review Focus

1. **Una ruta inexistente o un método no permitido** (`/does-not-exist`, `DELETE`): `application/problem+json` válido contra el schema `Problem`, con `type` presente (`not-found` / `http-error`); Spring omite `type` por defecto. Se fija en `GlobalExceptionHandlerTest` (Tarea 2).
2. **Una excepción inesperada**: `internal-error` sin filtrar el mensaje interno. Se fija en `GlobalExceptionHandlerTest` (Tarea 2).
3. **El contrato no llega al jar o al classpath de pruebas** (contexto de Docker sin `docs/contracts`): la construcción de la imagen falla en vez de producir un servicio sin Swagger UI. Se fija con el `RUN test -f ...` del Dockerfile y las pruebas de `CatalogApplicationIntegrationTest` (Tareas 1 y 3).
4. **El servicio dentro de Compose**: llega a `healthy`, corre sin root y `scripts/verify-infra.sh` sigue en verde con el servicio nuevo en el Compose. Se fija en la verificación de la Tarea 3.
5. **Regla de arquitectura que deja de evaluar nada**: las reglas de ArchUnit sobre paquetes que aún no existen permiten evaluación vacía solo hasta 5.2; no pueden quedarse así. Se fija con el comentario de `ArchitectureTest` y la fila de pendientes de la spec (Tarea 3).

---

## Estructura de archivos

| Archivo | Responsabilidad |
|---|---|
| `catalog-service/pom.xml`, `mvnw`, `mvnw.cmd`, `.mvn/`, `.gitignore`, `.gitattributes` | Proyecto Maven independiente (el wrapper y los archivos de módulo se copian de `inventory-service`) |
| `catalog-service/src/main/resources/application.yml` | Puerto 8082, salud, Swagger UI, patrón de log |
| `.../catalog/CatalogApplication.java` | Punto de entrada |
| `.../catalog/config/ClockConfiguration.java` | Reloj UTC en milisegundos |
| `.../catalog/error/ProblemType.java`, `GlobalExceptionHandler.java` | Tipos de problema y manejo global de errores |
| `src/test/.../support/ContractAssertions.java` (+ test) | Valida cuerpos contra los schemas del OpenAPI del Catálogo |
| `src/test/.../architecture/ArchitectureTest.java` | Reglas de dependencia |
| `src/test/.../error/ProbeController.java`, `GlobalExceptionHandlerTest.java` | Prueba del manejo de errores con una sonda |
| `src/test/.../CatalogApplicationIntegrationTest.java` | Arranque, salud, contrato, Swagger UI, reloj, MDC |
| `catalog-service/Dockerfile` | Imagen en dos etapas, usuario sin privilegios |
| `docker-compose.yml`, `.dockerignore`, `.env.example`, `scripts/verify-infra.sh` | Servicio en Compose, contexto de construcción, puerto configurable |
| `README.md`, `CLAUDE.md`, spec del Catálogo | Documentación al día |

---

### Task 1: Proyecto Maven, configuración, reloj y ayudante de contrato

**Files:**
- Create: `catalog-service/pom.xml`
- Create (copiar de `inventory-service/`): `catalog-service/mvnw`, `catalog-service/mvnw.cmd`, `catalog-service/.mvn/wrapper/maven-wrapper.properties`, `catalog-service/.gitignore`, `catalog-service/.gitattributes`
- Create: `catalog-service/src/main/resources/application.yml`
- Create: `catalog-service/src/main/java/com/retail/catalog/CatalogApplication.java`
- Create: `catalog-service/src/main/java/com/retail/catalog/config/ClockConfiguration.java`
- Test: `catalog-service/src/test/java/com/retail/catalog/config/ClockConfigurationTest.java`
- Create (test): `catalog-service/src/test/java/com/retail/catalog/support/ContractAssertions.java`
- Test: `catalog-service/src/test/java/com/retail/catalog/support/ContractAssertionsTest.java`
- Test: `catalog-service/src/test/java/com/retail/catalog/CatalogApplicationIntegrationTest.java`

**Interfaces:**
- Produces:
  - `ClockConfiguration#clock(): Clock` (bean) — UTC, precisión de 1 ms.
  - `ContractAssertions.assertMatchesSchema(String json, String schemaName)` — lanza `AssertionError` si el JSON no cumple `#/components/schemas/<schemaName>` de `catalog-service.yaml`.
  - Un servicio Spring Boot que arranca en `8082` con `/actuator/health`, Swagger UI en `/swagger-ui/index.html` y el contrato en `/openapi/catalog-service.yaml`.

- [ ] **Step 1: Andamiaje del proyecto**

Desde la raíz del repositorio, cada comando por separado:

```bash
mkdir -p "catalog-service/.mvn" "catalog-service/src/main/java/com/retail/catalog/config" "catalog-service/src/main/java/com/retail/catalog/error" "catalog-service/src/main/resources" "catalog-service/src/test/java/com/retail/catalog/config" "catalog-service/src/test/java/com/retail/catalog/error" "catalog-service/src/test/java/com/retail/catalog/architecture" "catalog-service/src/test/java/com/retail/catalog/support"
cp -r "inventory-service/.mvn/wrapper" "catalog-service/.mvn/"
cp "inventory-service/mvnw" "inventory-service/mvnw.cmd" "inventory-service/.gitignore" "inventory-service/.gitattributes" "catalog-service/"
```

Verificar que `catalog-service/mvnw` conserva el permiso de ejecución (`ls -l`).

`catalog-service/pom.xml`:

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
	xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
	<modelVersion>4.0.0</modelVersion>
	<parent>
		<groupId>org.springframework.boot</groupId>
		<artifactId>spring-boot-starter-parent</artifactId>
		<version>4.1.1</version>
		<relativePath/>
	</parent>
	<groupId>com.retail</groupId>
	<artifactId>catalog-service</artifactId>
	<version>0.0.1-SNAPSHOT</version>
	<name>catalog-service</name>
	<description>Servicio de Catálogo: datos de producto y vista de disponibilidad alimentada por eventos</description>

	<properties>
		<java.version>25</java.version>
		<springdoc.version>3.1.1</springdoc.version>
		<archunit.version>1.5.1</archunit.version>
		<json-schema-validator.version>3.0.8</json-schema-validator.version>
		<!-- Contratos del repositorio: fuente única de verdad, se copian al compilar -->
		<contracts.directory>${project.basedir}/../docs/contracts</contracts.directory>
	</properties>

	<dependencies>
		<dependency>
			<groupId>org.springframework.boot</groupId>
			<artifactId>spring-boot-starter-webmvc</artifactId>
		</dependency>
		<dependency>
			<groupId>org.springframework.boot</groupId>
			<artifactId>spring-boot-starter-validation</artifactId>
		</dependency>
		<dependency>
			<groupId>org.springframework.boot</groupId>
			<artifactId>spring-boot-starter-actuator</artifactId>
		</dependency>
		<dependency>
			<groupId>org.springdoc</groupId>
			<artifactId>springdoc-openapi-starter-webmvc-ui</artifactId>
			<version>${springdoc.version}</version>
		</dependency>

		<dependency>
			<groupId>org.springframework.boot</groupId>
			<artifactId>spring-boot-starter-webmvc-test</artifactId>
			<scope>test</scope>
		</dependency>
		<dependency>
			<groupId>org.springframework.boot</groupId>
			<artifactId>spring-boot-starter-validation-test</artifactId>
			<scope>test</scope>
		</dependency>
		<dependency>
			<groupId>org.springframework.boot</groupId>
			<artifactId>spring-boot-starter-actuator-test</artifactId>
			<scope>test</scope>
		</dependency>
		<dependency>
			<groupId>com.tngtech.archunit</groupId>
			<artifactId>archunit-junit5</artifactId>
			<version>${archunit.version}</version>
			<scope>test</scope>
		</dependency>
		<dependency>
			<groupId>com.networknt</groupId>
			<artifactId>json-schema-validator</artifactId>
			<version>${json-schema-validator.version}</version>
			<scope>test</scope>
		</dependency>
	</dependencies>

	<build>
		<plugins>
			<plugin>
				<groupId>org.springframework.boot</groupId>
				<artifactId>spring-boot-maven-plugin</artifactId>
			</plugin>
			<plugin>
				<groupId>org.apache.maven.plugins</groupId>
				<artifactId>maven-resources-plugin</artifactId>
				<executions>
					<!-- El OpenAPI del contrato se sirve como recurso estático para Swagger UI -->
					<execution>
						<id>copy-openapi-contract</id>
						<phase>process-resources</phase>
						<goals>
							<goal>copy-resources</goal>
						</goals>
						<configuration>
							<outputDirectory>${project.build.outputDirectory}/static/openapi</outputDirectory>
							<resources>
								<resource>
									<directory>${contracts.directory}/openapi</directory>
									<includes>
										<include>catalog-service.yaml</include>
									</includes>
								</resource>
							</resources>
						</configuration>
					</execution>
					<!-- Las pruebas leen los contratos desde el classpath -->
					<execution>
						<id>copy-contracts-for-tests</id>
						<phase>process-test-resources</phase>
						<goals>
							<goal>copy-resources</goal>
						</goals>
						<configuration>
							<outputDirectory>${project.build.testOutputDirectory}/contracts</outputDirectory>
							<resources>
								<resource>
									<directory>${contracts.directory}</directory>
								</resource>
							</resources>
						</configuration>
					</execution>
				</executions>
			</plugin>
			<!-- Pruebas rápidas (unitarias y de arquitectura): ./mvnw test -->
			<plugin>
				<groupId>org.apache.maven.plugins</groupId>
				<artifactId>maven-surefire-plugin</artifactId>
				<configuration>
					<excludes>
						<exclude>**/*IntegrationTest.java</exclude>
					</excludes>
				</configuration>
			</plugin>
			<!-- Pruebas de integración (*IntegrationTest): ./mvnw verify -->
			<plugin>
				<groupId>org.apache.maven.plugins</groupId>
				<artifactId>maven-failsafe-plugin</artifactId>
				<configuration>
					<includes>
						<include>**/*IntegrationTest.java</include>
					</includes>
				</configuration>
				<executions>
					<execution>
						<goals>
							<goal>integration-test</goal>
							<goal>verify</goal>
						</goals>
					</execution>
				</executions>
			</plugin>
		</plugins>
	</build>
</project>
```

`catalog-service/src/main/resources/application.yml`:

```yaml
spring:
  application:
    name: catalog-service

server:
  port: 8082

management:
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

`catalog-service/src/main/java/com/retail/catalog/CatalogApplication.java`:

```java
package com.retail.catalog;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * Servicio de Catálogo: datos de producto y vista de disponibilidad alimentada por eventos.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class CatalogApplication {

    public static void main(String[] arguments) {
        SpringApplication.run(CatalogApplication.class, arguments);
    }
}
```

- [ ] **Step 2: Escribir las pruebas (deben fallar)**

`src/test/java/com/retail/catalog/config/ClockConfigurationTest.java`:

```java
package com.retail.catalog.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.ZoneOffset;

import org.junit.jupiter.api.Test;

class ClockConfigurationTest {

    @Test
    void clockIsUtcAndTruncatesInstantsToMilliseconds() {
        Clock clock = new ClockConfiguration().clock();

        assertThat(clock.getZone()).isEqualTo(ZoneOffset.UTC);
        for (int i = 0; i < 1000; i++) {
            assertThat(clock.instant().getNano() % 1_000_000).isZero();
        }
    }
}
```

`src/test/java/com/retail/catalog/support/ContractAssertionsTest.java`:

```java
package com.retail.catalog.support;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class ContractAssertionsTest {

    @Test
    void acceptsBodyThatMatchesSchema() {
        String problem = """
                {"type":"https://api.retail-demo.local/problems/not-found","title":"Not found",
                 "status":404,"detail":"missing","instance":"/api/v1/catalog/products/UNKNOWN"}
                """;

        assertThatCode(() -> ContractAssertions.assertMatchesSchema(problem, "Problem")).doesNotThrowAnyException();
    }

    @Test
    void rejectsBodyThatViolatesSchema() {
        String problemWithTextStatus = """
                {"type":"https://api.retail-demo.local/problems/not-found","title":"Not found","status":"404"}
                """;

        assertThatThrownBy(() -> ContractAssertions.assertMatchesSchema(problemWithTextStatus, "Problem"))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("Problem");
    }

    @Test
    void acceptsAProductAndRejectsOneWithANegativeStock() {
        String product = """
                {"sku":"TENIS-RUNNER-42","name":"Tenis Runner talla 42","description":"Tenis para correr",
                 "price":289900,"currency":"COP","availableStock":4,"inStock":true,
                 "updatedAt":"2026-10-10T15:04:05.512Z","lastEventLatencyMs":389}
                """;
        String negativeStock = product.replace("\"availableStock\":4", "\"availableStock\":-1");

        assertThatCode(() -> ContractAssertions.assertMatchesSchema(product, "Product")).doesNotThrowAnyException();
        assertThatThrownBy(() -> ContractAssertions.assertMatchesSchema(negativeStock, "Product"))
                .isInstanceOf(AssertionError.class);
    }
}
```

`src/test/java/com/retail/catalog/CatalogApplicationIntegrationTest.java`:

```java
package com.retail.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Clock;
import java.time.ZoneOffset;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@ExtendWith(OutputCaptureExtension.class)
class CatalogApplicationIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private Clock clock;

    @Test
    void healthEndpointReportsUp() throws Exception {
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
    }

    @Test
    void servesTheHandWrittenOpenApiContract() throws Exception {
        mockMvc.perform(get("/openapi/catalog-service.yaml"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("title: Catalog Service API")));
    }

    @Test
    void swaggerUiPointsToTheContract() throws Exception {
        mockMvc.perform(get("/swagger-ui/index.html")).andExpect(status().isOk());
        mockMvc.perform(get("/v3/api-docs/swagger-config"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.url").value("/openapi/catalog-service.yaml"));
    }

    @Test
    void clockUsesUtc() {
        assertThat(clock.getZone()).isEqualTo(ZoneOffset.UTC);
    }

    @Test
    void logLinesIncludeTheMdcKeys(CapturedOutput output) {
        MDC.put("sku", "TENIS-RUNNER-42");
        MDC.put("eventId", "7f3c9a2e-5b1d-4c8e-9f0a-2d6b8e1c4a73");
        try {
            LoggerFactory.getLogger(CatalogApplicationIntegrationTest.class).info("mdc_probe");
        } finally {
            MDC.clear();
        }

        assertThat(output).contains("[sku=TENIS-RUNNER-42] [eventId=7f3c9a2e-5b1d-4c8e-9f0a-2d6b8e1c4a73]")
                .contains("mdc_probe");
    }
}
```

- [ ] **Step 3: Ejecutarlas y verificar que fallan**

Run (desde `catalog-service/`): `./mvnw test`
Expected: FAIL de compilación (`ClockConfiguration` y `ContractAssertions` no existen). La primera ejecución descarga dependencias (necesita internet).

- [ ] **Step 4: Implementar el reloj y el ayudante de contrato**

`src/main/java/com/retail/catalog/config/ClockConfiguration.java`:

```java
package com.retail.catalog.config;

import java.time.Clock;
import java.time.Duration;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Reloj del servicio en UTC. Avanza en milisegundos, de modo que todo instante queda truncado a
 * milisegundos (spec §4). Se inyecta por constructor para que las pruebas puedan fijar la hora.
 */
@Configuration
public class ClockConfiguration {

    @Bean
    public Clock clock() {
        return Clock.tick(Clock.systemUTC(), Duration.ofMillis(1));
    }
}
```

`src/test/java/com/retail/catalog/support/ContractAssertions.java`:

```java
package com.retail.catalog.support;

import java.util.List;
import java.util.stream.Collectors;

import com.networknt.schema.Error;
import com.networknt.schema.InputFormat;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaLocation;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SchemaRegistryConfig;
import com.networknt.schema.SpecificationVersion;

/**
 * Valida cuerpos JSON contra los schemas del contrato OpenAPI del servicio. El contrato se copia al
 * classpath de pruebas desde {@code docs/contracts} al compilar (fuente única de verdad).
 */
public final class ContractAssertions {

    private static final String OPENAPI_CONTRACT = "classpath:contracts/openapi/catalog-service.yaml";

    private static final SchemaRegistry SCHEMA_REGISTRY = SchemaRegistry.withDefaultDialect(
            SpecificationVersion.DRAFT_2020_12,
            builder -> builder.schemaRegistryConfig(
                    SchemaRegistryConfig.builder().formatAssertionsEnabled(true).build()));

    private ContractAssertions() {
    }

    /**
     * Falla si {@code json} no cumple el schema {@code #/components/schemas/<schemaName>} del contrato.
     */
    public static void assertMatchesSchema(String json, String schemaName) {
        Schema schema = SCHEMA_REGISTRY.getSchema(
                SchemaLocation.of(OPENAPI_CONTRACT + "#/components/schemas/" + schemaName));
        List<Error> errors = schema.validate(json, InputFormat.JSON);
        if (!errors.isEmpty()) {
            String messages = errors.stream().map(Error::getMessage).collect(Collectors.joining("; "));
            throw new AssertionError("Response does not match contract schema " + schemaName + ": " + messages
                    + System.lineSeparator() + json);
        }
    }
}
```

- [ ] **Step 5: Verificar**

Run: `./mvnw verify`
Expected: BUILD SUCCESS, 0 fallos. Cifras de referencia: 4 pruebas rápidas (1 del reloj + 3 del contrato) y 5 de integración (`CatalogApplicationIntegrationTest`).

- [ ] **Step 6: Commit**

```bash
git add catalog-service
git commit -m "feat: add catalog-service Maven project with config, clock and contract helper

Co-Authored-By: Claude <modelo> <noreply@anthropic.com>"
```

---

### Task 2: Manejo de errores y prueba de arquitectura

**Files:**
- Create: `catalog-service/src/main/java/com/retail/catalog/error/ProblemType.java`
- Create: `catalog-service/src/main/java/com/retail/catalog/error/GlobalExceptionHandler.java`
- Create (test): `catalog-service/src/test/java/com/retail/catalog/error/ProbeController.java`
- Test: `catalog-service/src/test/java/com/retail/catalog/error/GlobalExceptionHandlerTest.java`
- Test: `catalog-service/src/test/java/com/retail/catalog/architecture/ArchitectureTest.java`

**Interfaces:**
- Consumes: `ContractAssertions.assertMatchesSchema` (Tarea 1).
- Produces:
  - `enum ProblemType { NOT_FOUND, INTERNAL_ERROR }` con `static URI genericHttpErrorType()` y `ProblemDetail toProblemDetail(String detail, String requestPath)`. (5.2 usa `NOT_FOUND` para `ProductNotFoundException`.)
  - `@RestControllerAdvice GlobalExceptionHandler extends ResponseEntityExceptionHandler`: ruta inexistente → 404 `not-found`; errores estándar de Spring MVC (405, 415, ...) con `type` `http-error`; cualquier otra excepción → 500 `internal-error` sin detalles.
  - Reglas ArchUnit de dependencias (las reglas 3 y 4 permiten evaluación vacía hasta 5.2).

- [ ] **Step 1: Escribir las pruebas (deben fallar)**

`src/test/java/com/retail/catalog/error/ProbeController.java`:

```java
package com.retail.catalog.error;

import org.springframework.boot.test.context.TestComponent;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Controlador solo de pruebas para ejercitar el manejo global de errores mientras no existen
 * endpoints reales (llegan en 5.2). {@link TestComponent} lo excluye del escaneo de
 * {@code @SpringBootTest}.
 */
@TestComponent
@RestController
class ProbeController {

    @GetMapping("/probe/failure")
    String failure() {
        throw new IllegalStateException("internal detail that must not leak");
    }
}
```

`src/test/java/com/retail/catalog/error/GlobalExceptionHandlerTest.java`:

```java
package com.retail.catalog.error;

import static com.retail.catalog.support.ContractAssertions.assertMatchesSchema;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/** Prueba solo el manejo de errores, con la sonda de pruebas como único controlador. */
@WebMvcTest(controllers = ProbeController.class)
@Import(ProbeController.class)
class GlobalExceptionHandlerTest {

    private static final String PROBLEM_TYPE_BASE = "https://api.retail-demo.local/problems/";

    @Autowired
    private MockMvc mockMvc;

    @Test
    void unknownRouteReturnsNotFoundProblem() throws Exception {
        String body = mockMvc.perform(get("/does-not-exist"))
                .andExpect(status().isNotFound())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.type").value(PROBLEM_TYPE_BASE + "not-found"))
                .andReturn().getResponse().getContentAsString();

        assertMatchesSchema(body, "Problem");
    }

    @Test
    void unexpectedExceptionReturnsInternalErrorWithoutInternalDetails() throws Exception {
        String body = mockMvc.perform(get("/probe/failure"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.type").value(PROBLEM_TYPE_BASE + "internal-error"))
                .andExpect(jsonPath("$.detail").value("An unexpected error occurred"))
                .andReturn().getResponse().getContentAsString();

        assertThat(body).doesNotContain("internal detail that must not leak");
        assertMatchesSchema(body, "Problem");
    }

    @Test
    void unsupportedMethodKeepsItsHttpStatusAndHasTheGenericType() throws Exception {
        String body = mockMvc.perform(delete("/probe/failure"))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.type").value(PROBLEM_TYPE_BASE + "http-error"))
                .andReturn().getResponse().getContentAsString();

        assertMatchesSchema(body, "Problem");
    }
}
```

`src/test/java/com/retail/catalog/architecture/ArchitectureTest.java`:

```java
package com.retail.catalog.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

/**
 * Hace cumplir las reglas de dependencia de la spec del Catálogo (sección 3). Las reglas 3 y 4 describen
 * paquetes que llegan en la sub-tarea 5.2 y por eso permiten evaluación vacía hasta entonces; la 5.2
 * retira ese permiso. Las reglas 1 y 2 ya evalúan clases reales.
 */
@AnalyzeClasses(packages = "com.retail.catalog", importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {

    @ArchTest
    static final ArchRule featurePackagesAreFreeOfCycles = slices()
            .matching("com.retail.catalog.(*)..")
            .should().beFreeOfCycles();

    @ArchTest
    static final ArchRule onlyStockEventListenerUsesAmqp = noClasses()
            .that().doNotHaveSimpleName("StockEventListener")
            .should().dependOnClassesThat().resideInAPackage("org.springframework.amqp..");

    @ArchTest
    static final ArchRule controllersDoNotUseRepositories = noClasses()
            .that().haveSimpleNameEndingWith("Controller")
            .should().dependOnClassesThat().haveSimpleNameEndingWith("Repository")
            .allowEmptyShould(true);

    @ArchTest
    static final ArchRule productDoesNotDependOnStockEvent = noClasses()
            .that().resideInAPackage("com.retail.catalog.product..")
            .should().dependOnClassesThat().resideInAPackage("com.retail.catalog.stockevent..")
            .allowEmptyShould(true);
}
```

- [ ] **Step 2: Ejecutarlas y verificar que fallan**

Run: `./mvnw test -Dtest=GlobalExceptionHandlerTest`
Expected: FAIL — sin `GlobalExceptionHandler`, la ruta inexistente y la excepción no producen `application/problem+json` con el `type` esperado (la prueba del 405 y del 500 fallan por falta de `type`).

- [ ] **Step 3: Implementar el manejo de errores**

`src/main/java/com/retail/catalog/error/ProblemType.java`:

```java
package com.retail.catalog.error;

import java.net.URI;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;

/**
 * Tipos de problema (RFC 9457) del contrato. Es el único lugar donde se definen sus códigos, títulos
 * y estados HTTP.
 */
public enum ProblemType {

    NOT_FOUND("not-found", "Not found", HttpStatus.NOT_FOUND),
    INTERNAL_ERROR("internal-error", "Internal error", HttpStatus.INTERNAL_SERVER_ERROR);

    private static final String TYPE_BASE_URI = "https://api.retail-demo.local/problems/";

    private final String code;
    private final String title;
    private final HttpStatus status;

    ProblemType(String code, String title, HttpStatus status) {
        this.code = code;
        this.title = title;
        this.status = status;
    }

    /**
     * Tipo para los errores HTTP estándar de Spring MVC (405, 415, ...), que por defecto no traen
     * {@code type}; el contrato lo exige.
     */
    public static URI genericHttpErrorType() {
        return URI.create(TYPE_BASE_URI + "http-error");
    }

    /** Crea el {@link ProblemDetail} de este tipo con el detalle y la ruta que lo originó. */
    public ProblemDetail toProblemDetail(String detail, String requestPath) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setType(URI.create(TYPE_BASE_URI + code));
        problem.setTitle(title);
        problem.setInstance(URI.create(requestPath));
        return problem;
    }
}
```

`src/main/java/com/retail/catalog/error/GlobalExceptionHandler.java`:

```java
package com.retail.catalog.error;

import java.net.URI;

import jakarta.servlet.http.HttpServletRequest;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * Traduce excepciones a respuestas {@code application/problem+json} (RFC 9457). Las excepciones
 * estándar de Spring MVC conservan su estado HTTP (405, 415, ...) gracias a
 * {@link ResponseEntityExceptionHandler}; las del contrato usan {@link ProblemType}. El Catálogo no
 * recibe cuerpos ni parámetros de consulta, así que no tiene errores de validación propios.
 */
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    private static final URI ABOUT_BLANK = URI.create("about:blank");
    private static final Logger LOGGER = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @Override
    protected ResponseEntity<Object> handleNoResourceFoundException(NoResourceFoundException exception,
            HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        ProblemDetail problem = ProblemType.NOT_FOUND
                .toProblemDetail("The requested resource does not exist", requestPath(request));
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

    private static String requestPath(WebRequest request) {
        return ((ServletWebRequest) request).getRequest().getRequestURI();
    }
}
```

- [ ] **Step 4: Verificar**

Run: `./mvnw verify`
Expected: BUILD SUCCESS, 0 fallos. Cifras de referencia: 11 pruebas rápidas (4 + 3 del manejador + 4 de arquitectura) y 5 de integración.

- [ ] **Step 5: Commit**

```bash
git add catalog-service/src
git commit -m "feat: add problem+json error handling and architecture rules to catalog-service

Co-Authored-By: Claude <modelo> <noreply@anthropic.com>"
```

---

### Task 3: Contenedor, Compose, verificación de infraestructura y documentación

**Files:**
- Create: `catalog-service/Dockerfile`
- Modify: `docker-compose.yml`
- Modify: `.dockerignore`
- Modify: `.env.example`
- Modify: `scripts/verify-infra.sh`
- Modify: `README.md`
- Modify: `CLAUDE.md`
- Modify: `docs/superpowers/specs/2026-10-12-catalogo-design.md`

**Interfaces:**
- Consumes: el proyecto de las Tareas 1 y 2; la red `catalog-net`, `catalog-db` y RabbitMQ del Compose existente.
- Produces: el servicio `catalog-service` en el Compose (puerto `127.0.0.1:${CATALOG_SERVICE_PORT:-8082}`), imagen sin privilegios y documentación al día.

- [ ] **Step 1: Dockerfile**

`catalog-service/Dockerfile`:

```dockerfile
# syntax=docker/dockerfile:1
# Imagen del servicio de Catálogo. El contexto de construcción es la RAÍZ del repositorio,
# porque la compilación copia los contratos desde docs/contracts (fuente única de verdad).

# Etapa 1: compilación con JDK 25 (sin pruebas: se ejecutan con ./mvnw verify fuera de Docker).
FROM eclipse-temurin:25-jdk AS build
WORKDIR /workspace
COPY docs/contracts docs/contracts
COPY catalog-service/.mvn catalog-service/.mvn
COPY catalog-service/mvnw catalog-service/pom.xml catalog-service/
COPY catalog-service/src catalog-service/src
WORKDIR /workspace/catalog-service
RUN --mount=type=cache,target=/root/.m2 ./mvnw -B -q -DskipTests package
# Falla la construcción si el contrato no llegó al jar (p. ej., contexto sin docs/contracts).
RUN test -f target/classes/static/openapi/catalog-service.yaml

# Etapa 2: ejecución con un JRE 25 liviano.
FROM eclipse-temurin:25-jre-alpine
WORKDIR /app
COPY --from=build /workspace/catalog-service/target/catalog-service-*.jar app.jar
# El servicio no necesita privilegios: corre con un usuario sin permisos especiales.
RUN addgroup -S app && adduser -S -G app app
USER app
EXPOSE 8082
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
```

- [ ] **Step 2: Compose, contexto de construcción y puerto configurable**

En `docker-compose.yml`, antes de la sección `networks:` (después del servicio `inventory-service`), agregar:

```yaml
  catalog-service:
    build:
      context: .
      dockerfile: catalog-service/Dockerfile
    environment:
      SERVER_PORT: "8082"
      SPRING_DATASOURCE_URL: jdbc:postgresql://catalog-db:5432/catalog
      SPRING_DATASOURCE_USERNAME: catalog
      SPRING_DATASOURCE_PASSWORD: ${CATALOG_DB_PASSWORD:-catalog-demo}
      SPRING_RABBITMQ_HOST: rabbitmq
      SPRING_RABBITMQ_PORT: "5672"
      SPRING_RABBITMQ_USERNAME: retail
      SPRING_RABBITMQ_PASSWORD: retail-demo
    ports:
      - "127.0.0.1:${CATALOG_SERVICE_PORT:-8082}:8082"
    depends_on:
      catalog-db:
        condition: service_healthy
      rabbitmq:
        condition: service_healthy
    healthcheck:
      test: ["CMD", "wget", "-q", "-O", "-", "http://localhost:8082/actuator/health"]
      interval: 5s
      timeout: 5s
      retries: 24
      start_period: 10s
    networks:
      - messaging
      - catalog-net
```

(Las variables `SPRING_DATASOURCE_*` y `SPRING_RABBITMQ_*` aún no las usa el código; están para que 5.2 y 5.4 no tengan que tocar el Compose.)

`.dockerignore`: la lista de permitidos pasa a:

```
# Lista de permitidos: solo entra al contexto de construcción lo que las imágenes necesitan.
*
!docs/contracts/
!inventory-service/
!catalog-service/
inventory-service/target/
catalog-service/target/
```

`.env.example`: agregar `CATALOG_SERVICE_PORT=8082` justo debajo de `INVENTORY_SERVICE_PORT=8081`.

`scripts/verify-infra.sh`: en la función `compose_defaults`, agregar `-u CATALOG_SERVICE_PORT` a la lista de variables que se quitan del entorno (junto a `-u INVENTORY_SERVICE_PORT`), de modo que un `CATALOG_SERVICE_PORT` del equipo de quien desarrolla no altere la comparación con `.env.example`.

- [ ] **Step 3: Documentación**

`README.md` (leer antes el texto exacto):
- En el árbol "Estructura", agregar la línea `catalog-service/          Servicio de Catálogo (Spring Boot)` justo debajo de la de `inventory-service/`.
- En la tabla de puertos, agregar la fila: `| Servicio de Catálogo | http://localhost:8082 (Swagger UI en \`/swagger-ui/index.html\`) | \`catalog-service:8082\` | consultas públicas, sin token |`.
- Después de la sección "Servicio de Inventario" (antes de "Verificación"), agregar:

````markdown
## Servicio de Catálogo

Código en `catalog-service/` (Java 25, Spring Boot 4, Maven wrapper). Diseño en la
[spec del Catálogo](docs/superpowers/specs/2026-10-12-catalogo-design.md).

```bash
cd catalog-service
./mvnw test     # pruebas unitarias y de arquitectura (rápidas)
./mvnw verify   # además, las pruebas de integración (*IntegrationTest)
```

Es el consumidor del flujo: mantiene la vista de disponibilidad con los eventos de Inventario y la
expone por REST sin token. En esta etapa solo está el esqueleto (arranca, sirve Swagger UI con el
contrato y responde errores en `application/problem+json`); la consulta de productos y el consumidor de
eventos llegan en las siguientes sub-tareas.
````

`CLAUDE.md`, sección "Comandos":
- En la línea de `docker compose up -d --wait`, cambiar "levanta RabbitMQ, las bases de datos e inventory-service" por "levanta RabbitMQ, las bases de datos, inventory-service y catalog-service".
- Después de la línea de `cd inventory-service && ./mvnw test`, agregar: `` - `cd catalog-service && ./mvnw test` y `./mvnw verify`: igual que en Inventario. ``

`docs/superpowers/specs/2026-10-12-catalogo-design.md` (leer antes el texto exacto):
1. §7 tabla de sub-tareas, fila **5.1**: reemplazar "Servicio que arranca en Compose, Swagger UI con el contrato, errores base, logging, reloj, configuración validada, salud sin RabbitMQ, Dockerfile sin root, `.dockerignore`, prueba de arquitectura" por "Servicio que arranca en Compose, Swagger UI con el contrato, errores base, logging, reloj, Dockerfile sin root, `.dockerignore`, `CATALOG_SERVICE_PORT` y prueba de arquitectura". En la fila **5.4**, agregar al inicio de la entrega: "`CatalogProperties` (primeras propiedades reales: espera del reintento y nombre de la cola), salud sin RabbitMQ,".
2. §4 "Configuración": agregar al final del primer punto: "`CatalogProperties` llega en 5.4, con sus primeras propiedades reales; antes no habría nada que validar." En §4 "Salud": agregar "(la propiedad se configura en 5.4, al agregar AMQP)". En §4 "Manejo de errores": reemplazar la mención de `validation-error` por "El Catálogo no define `validation-error`: su contrato no tiene 400 y no recibe cuerpos ni parámetros de consulta."; y en §3 (`ProblemType`) la lista es `not-found`, `internal-error` y `http-error`.
3. §3 "Prueba de arquitectura": agregar al final: "Las reglas 3 y 4 permiten evaluación vacía en 5.1 (no hay clases que evaluar) y la 5.2 retira ese permiso."

- [ ] **Step 4: Verificación de punta a punta**

Con los puertos por defecto libres (`docker compose ps`; si hay un stack `retail` en marcha, `docker compose down -v` antes), desde la raíz del repositorio y cada comando por separado:

```bash
cd catalog-service && ./mvnw verify
```
Expected: BUILD SUCCESS, 11 pruebas rápidas y 5 de integración.

```bash
docker compose up -d --wait --build catalog-service
```
Expected: `retail-catalog-service-1 Healthy` (sin `--build` Compose reutilizaría una imagen anterior).

```bash
curl -s http://localhost:8082/actuator/health
```
Expected: `{"groups":["liveness","readiness"],"status":"UP"}`.

```bash
curl -s -o /dev/null -w '%{http_code}\n' http://localhost:8082/swagger-ui/index.html
curl -s http://localhost:8082/openapi/catalog-service.yaml | head -3
curl -s -w ' [%{http_code}]\n' http://localhost:8082/api/v1/catalog/products
docker compose exec -T catalog-service id -u
```
Expected: `200`; el YAML con `title: Catalog Service API`; un problema `not-found` con `[404]` (los productos llegan en 5.2); y un identificador de usuario distinto de `0`.

```bash
docker compose down -v
```

Luego, la infraestructura sigue intacta con el servicio nuevo en el Compose:

```bash
scripts/verify-infra.sh
```
Expected: todas las líneas `PASS` (32 comprobaciones) y código de salida 0. Tarda ~1,5 min.

- [ ] **Step 5: Commit**

```bash
git add catalog-service/Dockerfile docker-compose.yml .dockerignore .env.example scripts/verify-infra.sh README.md CLAUDE.md docs/superpowers/specs/2026-10-12-catalogo-design.md
git commit -m "feat: add catalog-service container, Compose entry and docs

Co-Authored-By: Claude <modelo> <noreply@anthropic.com>"
```

---

## Autorrevisión

- **Cobertura de la spec (sub-tarea 5.1):** proyecto Maven independiente con el contrato copiado (T1); Swagger UI con el contrato escrito a mano (T1, `CatalogApplicationIntegrationTest`); errores base RFC 9457 con `not-found`, `internal-error` y `http-error` (T2); logging con MDC `sku`/`eventId` y reloj (T1); Dockerfile sin root, entrada en Compose, `.dockerignore`, `CATALOG_SERVICE_PORT` y `verify-infra.sh` (T3); prueba de arquitectura (T2); documentación (T3).
- **Desviaciones de la spec, anotadas y corregidas en la spec en T3:** `CatalogProperties` y la propiedad de salud sin RabbitMQ pasan a 5.4 (no hay nada que validar ni indicador que desactivar hasta entonces); el Catálogo no define `validation-error`.
- **Consistencia de tipos:** `ClockConfiguration#clock()`, `ContractAssertions.assertMatchesSchema(String, String)`, `ProblemType#toProblemDetail(String, String)` y `genericHttpErrorType()`, paquete `com.retail.catalog`.
- **Conteos de referencia:** rápidas 4 (T1) → 11 (T2); integración 5 (T1 y T2).
- **Verificado en una copia temporal** antes de escribir el plan: 11 rápidas + 5 de integración en verde; el servicio llega a `healthy` en Compose con usuario no root (uid 100), Swagger UI y el YAML se sirven, `/api/v1/catalog/products` responde el problema `not-found`; y `scripts/verify-infra.sh` pasa sus 32 comprobaciones con el servicio nuevo en el Compose.
