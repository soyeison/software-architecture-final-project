# Inventario 4.1: esqueleto ejecutable — Plan de implementación

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Crear el servicio `inventory-service` como un esqueleto Spring Boot que arranca dentro de Compose, valida su configuración, responde errores en `application/problem+json`, publica el contrato en Swagger UI y hace cumplir las reglas de arquitectura con una prueba.

**Architecture:** Proyecto Maven independiente en `inventory-service/` con capas por funcionalidad (spec de Inventario §2-§3). Esta sub-tarea crea solo los paquetes transversales `config/` y `error/`; las funcionalidades (`stock/`, `sale/`, `outbox/`, `security/`) llegan en 4.2-4.6. Los archivos de contrato se copian desde `docs/contracts` al compilar (fuente única de verdad) y la imagen Docker se construye con la raíz del repositorio como contexto.

**Tech Stack:** Java 25, Spring Boot 4.1.1 (starters modulares: `spring-boot-starter-webmvc`, etc.), Maven wrapper, springdoc-openapi 3.1.1, ArchUnit 1.5.1, networknt json-schema-validator 3.0.8 (Jackson 3), JUnit 5, Docker (`eclipse-temurin:25-jdk` y `eclipse-temurin:25-jre-alpine`).

**Spec:** `docs/superpowers/specs/2026-10-11-inventario-design.md` (secciones 2, 3, 4 y 9). Depende de `docs/superpowers/specs/2026-10-10-contratos-design.md` y `docs/superpowers/specs/2026-10-10-infraestructura-design.md`.

## Global Constraints

- Spring Boot `4.1.1`, Java `25`, Maven con el wrapper `./mvnw` (no hay Maven instalado en el equipo). `groupId` `com.retail`, `artifactId` `inventory-service`, paquete base `com.retail.inventory`, clase principal `InventoryApplication`.
- Versiones fijas: springdoc-openapi `3.1.1`, ArchUnit `1.5.1`, networknt json-schema-validator `3.0.8`. Las demás las administra el parent de Spring Boot.
- **Nombres completos, sin abreviaturas** (salvo siglas de dominio o estándares como `SKU`, `JWT`, `UUID`, `DTO`, `HTTP`, `JSON`, `DLQ` y unidades como `ms`). Código en inglés; comentarios, Javadoc y documentación en español.
- Commits en inglés, breves, Conventional Commits, con la línea `Co-Authored-By: Claude <modelo> <noreply@anthropic.com>`.
- La ruta del repositorio contiene espacios ("Arquitectura de Software"): toda ruta en comandos y scripts va entre comillas dobles. Los comandos `./mvnw` se ejecutan desde `inventory-service/`.
- Esta sub-tarea **no cambia ningún contrato** (la cabecera `WWW-Authenticate` llega en 4.2).
- El secreto de demo del JWT es exactamente `retail-demo-jwt-secret-not-for-production-use` (45 bytes) y vive solo en `docker-compose.yml` y `.env.example`; el código no tiene valor por defecto.
- `.env.example` debe coincidir con los valores por defecto del Compose: `scripts/verify-infra.sh` lo comprueba.
- Las pruebas de integración se nombran `*IntegrationTest` y solo corren con `./mvnw verify`; `./mvnw test` corre las unitarias y la de arquitectura.

## Review Focus

Modos de fallo que la spec implica y que este plan fija con pruebas:

1. **`JWT_SECRET` ausente o menor de 32 bytes.** Se espera que el servicio no arranque y diga por qué. Se fija con `InventoryPropertiesTest` (Tarea 1) y con el arranque del contenedor con un secreto corto (Tarea 5, paso 7).
2. **Ruta o método inexistente.** Se espera `application/problem+json` (no una página HTML ni el JSON de error por defecto de Spring), con 404 o 405. Se fija en `GlobalExceptionHandlerTest` (Tarea 2).
3. **Excepción inesperada.** Se espera `internal-error` sin el mensaje interno de la excepción. Se fija en `GlobalExceptionHandlerTest` (Tarea 2).
4. **Imagen construida sin el YAML del contrato** (por ejemplo, un contexto sin `docs/contracts`): Maven no falla si el directorio no existe y las pruebas no corren dentro de Docker. Se espera que la construcción falle. Se fija con la comprobación del `Dockerfile` y su mutación (Tarea 5, pasos 2 y 8).
5. **Las pruebas de integración corren en la fase equivocada** (surefire las toma porque `*IntegrationTest` termina en `Test`, y failsafe no busca ese sufijo por defecto). Se espera que `./mvnw test` no las ejecute y `./mvnw verify` sí. Se fija comprobando los informes de ambos plugins (Tarea 3, paso 5).

---

## Estructura de archivos

| Archivo | Responsabilidad |
|---|---|
| `inventory-service/pom.xml` | Dependencias, copia de contratos, separación unitarias/integración |
| `inventory-service/mvnw`, `mvnw.cmd`, `.mvn/wrapper/maven-wrapper.properties` | Maven wrapper (generado por Spring Initializr) |
| `inventory-service/.gitignore`, `.gitattributes` | Generados por Spring Initializr |
| `src/main/java/com/retail/inventory/InventoryApplication.java` | Arranque y escaneo de `@ConfigurationProperties` |
| `.../config/InventoryProperties.java` | Configuración propia validada (`inventory.jwt.*`) |
| `.../config/ClockConfiguration.java` | Bean `Clock` en UTC |
| `.../error/ProblemType.java` | Tipos de problema del contrato (código, título, estado) |
| `.../error/FieldViolation.java` | Elemento de `errors` en `validation-error` |
| `.../error/GlobalExceptionHandler.java` | Traducción de excepciones a `application/problem+json` |
| `src/main/resources/application.yml` | Configuración del servicio |
| `src/test/java/com/retail/inventory/support/ContractAssertions.java` | Valida cuerpos JSON contra los schemas del OpenAPI |
| `.../support/ContractAssertionsTest.java` | Prueba del ayudante de contrato |
| `.../config/InventoryPropertiesTest.java` | Arranque con secreto válido, ausente y corto |
| `.../error/ProbeController.java` | Controlador solo de pruebas para ejercitar los errores |
| `.../error/GlobalExceptionHandlerTest.java` | 400, 404, 405 y 500 en formato del contrato |
| `.../architecture/ArchitectureTest.java` | Reglas de dependencia de la spec §3 |
| `.../InventoryApplicationIntegrationTest.java` | Health, contrato servido, Swagger UI, reloj y MDC |
| `inventory-service/Dockerfile` | Imagen en dos etapas |
| `.dockerignore` | Lista de permitidos del contexto de construcción |
| `docker-compose.yml` | Servicio `inventory-service` |
| `.env.example` | `INVENTORY_SERVICE_PORT` y `JWT_SECRET` |
| `scripts/verify-infra.sh` | Levanta solo la infraestructura |
| `README.md`, `CLAUDE.md` | Documentación del servicio |

---

### Task 1: Proyecto Maven, configuración validada y reloj

**Files:**
- Create: `inventory-service/` (wrapper, `.gitignore` y `.gitattributes` generados por Spring Initializr)
- Create: `inventory-service/pom.xml`
- Create: `inventory-service/src/main/java/com/retail/inventory/InventoryApplication.java`
- Create: `inventory-service/src/main/java/com/retail/inventory/config/InventoryProperties.java`
- Create: `inventory-service/src/main/java/com/retail/inventory/config/ClockConfiguration.java`
- Create: `inventory-service/src/main/resources/application.yml`
- Test: `inventory-service/src/test/java/com/retail/inventory/config/InventoryPropertiesTest.java`

**Interfaces:**
- Consumes: `docs/contracts/` (fase 2), que el `pom.xml` copia al compilar.
- Produces: `InventoryProperties` (`record` con `jwt()` → `Jwt(String secret, String issuer)`, prefijo `inventory`), bean `java.time.Clock` en UTC, `application.yml` (las tareas 3 agregan secciones), y el `pom.xml` completo de la sub-tarea con todas sus dependencias, la copia de `docs/contracts/openapi/inventory-service.yaml` a `static/openapi/` y de `docs/contracts/` a `contracts/` del classpath de pruebas.

- [ ] **Step 1: Generar el proyecto base con Spring Initializr**

Descargar en un directorio temporal nuevo y vacío (es contenido descargado) y copiar solo el wrapper y los archivos de Git:

```bash
SCRATCH="$(mktemp -d)"
curl -s --max-time 60 -o "$SCRATCH/starter.zip" "https://start.spring.io/starter.zip?type=maven-project&language=java&bootVersion=4.1.1&groupId=com.retail&artifactId=inventory-service&name=inventory-service&packageName=com.retail.inventory&javaVersion=25&dependencies=web,validation,actuator"
unzip -q "$SCRATCH/starter.zip" -d "$SCRATCH/project"
mkdir -p inventory-service
cp -r "$SCRATCH/project/.mvn" "$SCRATCH/project/mvnw" "$SCRATCH/project/mvnw.cmd" "$SCRATCH/project/.gitignore" "$SCRATCH/project/.gitattributes" inventory-service/
rm -rf "$SCRATCH"
```

Expected: `inventory-service/` contiene `mvnw`, `mvnw.cmd`, `.mvn/wrapper/maven-wrapper.properties`, `.gitignore` y `.gitattributes` (este último con `/mvnw text eol=lf` y `*.cmd text eol=crlf`). No se copian `HELP.md`, `src/` ni el `pom.xml` generados.

- [ ] **Step 2: Escribir el `pom.xml`**

`inventory-service/pom.xml`:

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
	<artifactId>inventory-service</artifactId>
	<version>0.0.1-SNAPSHOT</version>
	<name>inventory-service</name>
	<description>Servicio de Inventario: única fuente de verdad del stock</description>

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
										<include>inventory-service.yaml</include>
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

- [ ] **Step 3: Escribir la prueba de configuración (falla)**

`inventory-service/src/test/java/com/retail/inventory/config/InventoryPropertiesTest.java`:

```java
package com.retail.inventory.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

class InventoryPropertiesTest {

    private static final String SECRET_OF_32_BYTES = "0123456789abcdef0123456789abcdef";

    @Configuration
    @EnableConfigurationProperties(InventoryProperties.class)
    static class PropertiesConfiguration {
    }

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(PropertiesConfiguration.class)
            .withPropertyValues("inventory.jwt.issuer=retail-demo-auth");

    @Test
    void startsWithSecretOfAtLeast32Bytes() {
        contextRunner.withPropertyValues("inventory.jwt.secret=" + SECRET_OF_32_BYTES)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(InventoryProperties.class).jwt().issuer()).isEqualTo("retail-demo-auth");
                });
    }

    @Test
    void failsToStartWhenSecretIsMissing() {
        contextRunner.withPropertyValues("inventory.jwt.secret=")
                .run(context -> assertThat(context).hasFailed()
                        .getFailure().rootCause().hasMessageContaining("jwt.secret"));
    }

    @Test
    void failsToStartWhenSecretIsShorterThan32Bytes() {
        contextRunner.withPropertyValues("inventory.jwt.secret=" + SECRET_OF_32_BYTES.substring(1))
                .run(context -> assertThat(context).hasFailed()
                        .getFailure().rootCause().hasMessageContaining("at least 32 bytes"));
    }
}
```

- [ ] **Step 4: Ejecutar la prueba y verificar que falla**

Run (desde `inventory-service/`): `./mvnw -q -B test -Dtest=InventoryPropertiesTest`
Expected: error de compilación porque `InventoryProperties` no existe (`cannot find symbol`). La primera ejecución descarga Maven y las dependencias.

- [ ] **Step 5: Escribir la aplicación, la configuración y el reloj**

`inventory-service/src/main/java/com/retail/inventory/InventoryApplication.java`:

```java
package com.retail.inventory;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * Servicio de Inventario: única fuente de verdad del stock.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class InventoryApplication {

    public static void main(String[] arguments) {
        SpringApplication.run(InventoryApplication.class, arguments);
    }
}
```

`inventory-service/src/main/java/com/retail/inventory/config/InventoryProperties.java`:

```java
package com.retail.inventory.config;

import java.nio.charset.StandardCharsets;

import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Configuración propia del servicio (prefijo {@code inventory}). Se valida al arrancar: si un valor
 * es inválido, el servicio no inicia.
 */
@Validated
@ConfigurationProperties(prefix = "inventory")
public record InventoryProperties(@Valid @NotNull Jwt jwt) {

    /**
     * Parámetros del JWT. El secreto llega por la variable de entorno {@code JWT_SECRET}.
     */
    public record Jwt(@NotBlank String secret, @NotBlank String issuer) {

        static final int MINIMUM_SECRET_BYTES = 32;

        /** HS256 exige un secreto de al menos 256 bits (32 bytes). */
        @AssertTrue(message = "must be at least 32 bytes long (set JWT_SECRET)")
        public boolean isSecretLongEnough() {
            return secret != null && secret.getBytes(StandardCharsets.UTF_8).length >= MINIMUM_SECRET_BYTES;
        }
    }
}
```

`inventory-service/src/main/java/com/retail/inventory/config/ClockConfiguration.java`:

```java
package com.retail.inventory.config;

import java.time.Clock;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Reloj del servicio en UTC. Se inyecta por constructor para que las pruebas puedan fijar la hora.
 */
@Configuration
public class ClockConfiguration {

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
```

`inventory-service/src/main/resources/application.yml`:

```yaml
spring:
  application:
    name: inventory-service

server:
  port: 8081

inventory:
  jwt:
    # Sin valor por defecto: si falta JWT_SECRET el servicio no arranca.
    secret: ${JWT_SECRET:}
    issuer: retail-demo-auth

management:
  endpoints:
    web:
      exposure:
        include: health
```

- [ ] **Step 6: Ejecutar la prueba y verificar que pasa**

Run: `./mvnw -q -B test -Dtest=InventoryPropertiesTest`
Expected: `Tests run: 3, Failures: 0, Errors: 0` en `target/surefire-reports/com.retail.inventory.config.InventoryPropertiesTest.txt`, exit 0.

- [ ] **Step 7: Commit**

```bash
git add inventory-service
git commit -m "feat: scaffold inventory service with validated configuration"
```

---

### Task 2: Manejo de errores y ayudante de contrato

**Files:**
- Create: `inventory-service/src/main/java/com/retail/inventory/error/ProblemType.java`
- Create: `inventory-service/src/main/java/com/retail/inventory/error/FieldViolation.java`
- Create: `inventory-service/src/main/java/com/retail/inventory/error/GlobalExceptionHandler.java`
- Create: `inventory-service/src/test/java/com/retail/inventory/support/ContractAssertions.java`
- Test: `inventory-service/src/test/java/com/retail/inventory/support/ContractAssertionsTest.java`
- Test: `inventory-service/src/test/java/com/retail/inventory/error/ProbeController.java`
- Test: `inventory-service/src/test/java/com/retail/inventory/error/GlobalExceptionHandlerTest.java`

**Interfaces:**
- Consumes: el `pom.xml` de la Tarea 1 (copia de `docs/contracts` a `contracts/` del classpath de pruebas).
- Produces: `ProblemType` (enum con `VALIDATION_ERROR`, `NOT_FOUND`, `INTERNAL_ERROR`; métodos `HttpStatus status()` y `ProblemDetail toProblemDetail(String detail, String requestPath)`; las sub-tareas 4.2-4.4 agregan valores), `FieldViolation(String field, String message)`, `GlobalExceptionHandler` (las sub-tareas siguientes agregan métodos `@ExceptionHandler`), y el ayudante de pruebas `ContractAssertions.assertMatchesSchema(String json, String schemaName)`, que valida contra `#/components/schemas/<schemaName>` de `inventory-service.yaml`.

- [ ] **Step 1: Escribir la prueba del ayudante de contrato (falla)**

`inventory-service/src/test/java/com/retail/inventory/support/ContractAssertionsTest.java`:

```java
package com.retail.inventory.support;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class ContractAssertionsTest {

    @Test
    void acceptsBodyThatMatchesSchema() {
        String problem = """
                {"type":"https://api.retail-demo.local/problems/not-found","title":"Not found",
                 "status":404,"detail":"missing","instance":"/api/v1/inventory/UNKNOWN"}
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
    void rejectsValidationProblemWithoutErrors() {
        String validationProblemWithoutErrors = """
                {"type":"https://api.retail-demo.local/problems/validation-error","title":"Validation error",
                 "status":400}
                """;

        assertThatThrownBy(() -> ContractAssertions.assertMatchesSchema(validationProblemWithoutErrors,
                "ValidationProblem")).isInstanceOf(AssertionError.class);
    }
}
```

- [ ] **Step 2: Ejecutar y verificar que falla**

Run: `./mvnw -q -B test -Dtest=ContractAssertionsTest`
Expected: error de compilación porque `ContractAssertions` no existe.

- [ ] **Step 3: Escribir el ayudante de contrato**

`inventory-service/src/test/java/com/retail/inventory/support/ContractAssertions.java`:

```java
package com.retail.inventory.support;

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

    private static final String OPENAPI_CONTRACT = "classpath:contracts/openapi/inventory-service.yaml";

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

- [ ] **Step 4: Ejecutar y verificar que pasa**

Run: `./mvnw -q -B test -Dtest=ContractAssertionsTest`
Expected: `Tests run: 3, Failures: 0, Errors: 0`. Esto confirma que el contrato llegó al classpath de pruebas y que el ayudante detecta tanto cuerpos válidos como inválidos.

- [ ] **Step 5: Escribir el controlador de prueba y las pruebas del manejador (fallan)**

`inventory-service/src/test/java/com/retail/inventory/error/ProbeController.java`:

```java
package com.retail.inventory.error;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

import org.springframework.boot.test.context.TestComponent;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Controlador solo de pruebas para ejercitar el manejo global de errores mientras no existen
 * endpoints reales. {@link TestComponent} lo excluye del escaneo de {@code @SpringBootTest}.
 */
@TestComponent
@RestController
class ProbeController {

    record ProbeRequest(@NotNull @Min(1) Integer quantity) {
    }

    @PostMapping("/probe/echo")
    ProbeRequest echo(@Valid @RequestBody ProbeRequest request) {
        return request;
    }

    @GetMapping("/probe/failure")
    String failure() {
        throw new IllegalStateException("internal detail that must not leak");
    }
}
```

`inventory-service/src/test/java/com/retail/inventory/error/GlobalExceptionHandlerTest.java`:

```java
package com.retail.inventory.error;

import static com.retail.inventory.support.ContractAssertions.assertMatchesSchema;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest
@Import(ProbeController.class)
class GlobalExceptionHandlerTest {

    private static final String PROBLEM_TYPE_BASE = "https://api.retail-demo.local/problems/";

    @Autowired
    private MockMvc mockMvc;

    @Test
    void invalidFieldReturnsValidationProblemListingTheField() throws Exception {
        String body = mockMvc.perform(post("/probe/echo")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"quantity\": 0}"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.type").value(PROBLEM_TYPE_BASE + "validation-error"))
                .andExpect(jsonPath("$.instance").value("/probe/echo"))
                .andExpect(jsonPath("$.errors[0].field").value("quantity"))
                .andReturn().getResponse().getContentAsString();

        assertMatchesSchema(body, "ValidationProblem");
    }

    @Test
    void malformedJsonReturnsValidationProblemWithEmptyErrors() throws Exception {
        String body = mockMvc.perform(post("/probe/echo")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"quantity\": "))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value(PROBLEM_TYPE_BASE + "validation-error"))
                .andExpect(jsonPath("$.errors").isEmpty())
                .andReturn().getResponse().getContentAsString();

        assertMatchesSchema(body, "ValidationProblem");
    }

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
    void unsupportedMethodKeepsItsHttpStatus() throws Exception {
        mockMvc.perform(delete("/probe/echo"))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON));
    }
}
```

- [ ] **Step 6: Ejecutar y verificar que fallan**

Run: `./mvnw -q -B test -Dtest=GlobalExceptionHandlerTest`
Expected: varias pruebas fallan porque, sin el manejador, Spring responde con su formato por defecto (sin `type` del contrato, sin `errors` y con estado 500 en vez de `internal-error`).

- [ ] **Step 7: Escribir los tipos de problema y el manejador**

`inventory-service/src/main/java/com/retail/inventory/error/ProblemType.java`:

```java
package com.retail.inventory.error;

import java.net.URI;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;

/**
 * Tipos de problema (RFC 9457) del contrato. Es el único lugar donde se definen sus códigos, títulos
 * y estados HTTP.
 */
public enum ProblemType {

    VALIDATION_ERROR("validation-error", "Validation error", HttpStatus.BAD_REQUEST),
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

    public HttpStatus status() {
        return status;
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

`inventory-service/src/main/java/com/retail/inventory/error/FieldViolation.java`:

```java
package com.retail.inventory.error;

/**
 * Un campo inválido dentro de un problema {@code validation-error}.
 */
public record FieldViolation(String field, String message) {
}
```

`inventory-service/src/main/java/com/retail/inventory/error/GlobalExceptionHandler.java`:

```java
package com.retail.inventory.error;

import java.util.List;

import jakarta.servlet.http.HttpServletRequest;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * Traduce excepciones a respuestas {@code application/problem+json} (RFC 9457). Las excepciones
 * estándar de Spring MVC conservan su estado HTTP (405, 415, ...) gracias a
 * {@link ResponseEntityExceptionHandler}; las del contrato usan {@link ProblemType}.
 */
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException exception,
            HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        List<FieldViolation> violations = exception.getBindingResult().getFieldErrors().stream()
                .map(error -> new FieldViolation(error.getField(), error.getDefaultMessage()))
                .toList();
        ProblemDetail problem = ProblemType.VALIDATION_ERROR
                .toProblemDetail("The request has invalid fields", requestPath(request));
        problem.setProperty("errors", violations);
        return ResponseEntity.status(problem.getStatus()).body(problem);
    }

    @Override
    protected ResponseEntity<Object> handleHttpMessageNotReadable(HttpMessageNotReadableException exception,
            HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        ProblemDetail problem = ProblemType.VALIDATION_ERROR
                .toProblemDetail("The request body is malformed or has invalid values", requestPath(request));
        problem.setProperty("errors", List.of());
        return ResponseEntity.status(problem.getStatus()).body(problem);
    }

    @Override
    protected ResponseEntity<Object> handleNoResourceFoundException(NoResourceFoundException exception,
            HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        ProblemDetail problem = ProblemType.NOT_FOUND
                .toProblemDetail("The requested resource does not exist", requestPath(request));
        return ResponseEntity.status(problem.getStatus()).body(problem);
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

- [ ] **Step 8: Ejecutar y verificar que pasan**

Run: `./mvnw -q -B test -Dtest='GlobalExceptionHandlerTest,ContractAssertionsTest'`
Expected: `Tests run: 5` y `Tests run: 3`, sin fallos, exit 0.

- [ ] **Step 9: Commit**

```bash
git add inventory-service/src
git commit -m "feat: add problem details error handling and contract assertions"
```

---

### Task 3: Swagger UI con el contrato, logging y prueba de integración

**Files:**
- Modify: `inventory-service/src/main/resources/application.yml` (se agregan las secciones `springdoc` y `logging` al final)
- Test: `inventory-service/src/test/java/com/retail/inventory/InventoryApplicationIntegrationTest.java`

**Interfaces:**
- Consumes: `InventoryApplication`, `ClockConfiguration` (Tarea 1); `GlobalExceptionHandler` (Tarea 2).
- Produces: Swagger UI en `/swagger-ui/index.html` apuntando a `/openapi/inventory-service.yaml`; patrón de log con las claves del MDC `sku` y `eventId` (las usan 4.4-4.6).

- [ ] **Step 1: Escribir la prueba de integración (falla)**

`inventory-service/src/test/java/com/retail/inventory/InventoryApplicationIntegrationTest.java`:

```java
package com.retail.inventory;

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

@SpringBootTest(properties = "inventory.jwt.secret=integration-test-secret-of-32-bytes!")
@AutoConfigureMockMvc
@ExtendWith(OutputCaptureExtension.class)
class InventoryApplicationIntegrationTest {

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
        mockMvc.perform(get("/openapi/inventory-service.yaml"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("title: Inventory Service API")));
    }

    @Test
    void swaggerUiPointsToTheContract() throws Exception {
        mockMvc.perform(get("/swagger-ui/index.html")).andExpect(status().isOk());
        mockMvc.perform(get("/v3/api-docs/swagger-config"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.url").value("/openapi/inventory-service.yaml"));
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
            LoggerFactory.getLogger(InventoryApplicationIntegrationTest.class).info("mdc_probe");
        } finally {
            MDC.clear();
        }

        assertThat(output).contains("[sku=TENIS-RUNNER-42] [eventId=7f3c9a2e-5b1d-4c8e-9f0a-2d6b8e1c4a73]")
                .contains("mdc_probe");
    }
}
```

- [ ] **Step 2: Ejecutar y verificar que falla**

Run: `./mvnw -q -B verify`
Expected: fallan `swaggerUiPointsToTheContract` (la configuración de Swagger UI no apunta al contrato) y `logLinesIncludeTheMdcKeys` (el patrón de log no incluye el MDC). Las demás pasan.

- [ ] **Step 3: Agregar la configuración de Swagger UI y del log**

Agregar al final de `inventory-service/src/main/resources/application.yml`:

```yaml
# Swagger UI muestra el contrato escrito a mano (contrato primero), no el generado desde el código.
springdoc:
  swagger-ui:
    url: /openapi/inventory-service.yaml

logging:
  pattern:
    # Claves del MDC para seguir una venta de punta a punta.
    level: "%5p [sku=%X{sku:-}] [eventId=%X{eventId:-}]"
```

Nota: no se usa `springdoc.api-docs.enabled: false`. Desactivar la generación de documentación también desactiva Swagger UI, porque su configuración (`/v3/api-docs/swagger-config`) depende de ella. La UI muestra igualmente el contrato escrito a mano gracias a `swagger-ui.url`.

- [ ] **Step 4: Ejecutar y verificar que pasa**

Run: `./mvnw -q -B verify`
Expected: exit 0.

- [ ] **Step 5: Verificar que cada plugin corre sus pruebas**

Run:

```bash
ls target/surefire-reports | grep -c IntegrationTest
ls target/failsafe-reports | grep -c 'InventoryApplicationIntegrationTest.txt'
grep -h "Tests run" target/failsafe-reports/com.retail.inventory.InventoryApplicationIntegrationTest.txt
```

Expected: `0` (surefire no corrió la de integración), `1` (failsafe sí) y `Tests run: 5, Failures: 0, Errors: 0`.

- [ ] **Step 6: Commit**

```bash
git add inventory-service/src
git commit -m "feat: serve the OpenAPI contract in Swagger UI and add MDC log keys"
```

---

### Task 4: Prueba de arquitectura

**Files:**
- Test: `inventory-service/src/test/java/com/retail/inventory/architecture/ArchitectureTest.java`

**Interfaces:**
- Consumes: el código principal de las tareas 1-3.
- Produces: las cuatro reglas de la spec §3, que todas las sub-tareas siguientes deben cumplir. Las reglas sobre paquetes que aún no existen usan `allowEmptyShould(true)`; sin eso, ArchUnit falla cuando una regla no evalúa ninguna clase.

- [ ] **Step 1: Escribir la prueba de arquitectura**

`inventory-service/src/test/java/com/retail/inventory/architecture/ArchitectureTest.java`:

```java
package com.retail.inventory.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

/**
 * Hace cumplir las reglas de dependencia de la spec de Inventario (sección 3). Algunas reglas
 * describen paquetes que llegan en sub-tareas posteriores; mientras no existan, se cumplen sin clases.
 */
@AnalyzeClasses(packages = "com.retail.inventory", importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {

    @ArchTest
    static final ArchRule featurePackagesAreFreeOfCycles = slices()
            .matching("com.retail.inventory.(*)..")
            .should().beFreeOfCycles()
            .allowEmptyShould(true);

    @ArchTest
    static final ArchRule onlyRabbitStockEventSenderUsesAmqp = noClasses()
            .that().doNotHaveSimpleName("RabbitStockEventSender")
            .should().dependOnClassesThat().resideInAPackage("org.springframework.amqp..")
            .allowEmptyShould(true);

    @ArchTest
    static final ArchRule controllersDoNotUseRepositories = noClasses()
            .that().haveSimpleNameEndingWith("Controller")
            .should().dependOnClassesThat().haveSimpleNameEndingWith("Repository")
            .allowEmptyShould(true);

    @ArchTest
    static final ArchRule stockAndSaleStayIndependentOfWebAndMessaging = noClasses()
            .that().resideInAnyPackage("com.retail.inventory.stock..", "com.retail.inventory.sale..")
            .and().haveSimpleNameNotEndingWith("Controller")
            .should().dependOnClassesThat().resideInAPackage("org.springframework.web..")
            .orShould().dependOnClassesThat().haveSimpleName("RabbitStockEventSender")
            .allowEmptyShould(true);
}
```

- [ ] **Step 2: Ejecutar y verificar que pasa**

Run: `./mvnw -q -B test -Dtest=ArchitectureTest`
Expected: `Tests run: 4, Failures: 0, Errors: 0`.

- [ ] **Step 3: Mutación: una clase de `stock/` que depende de Spring Web debe hacer fallar la prueba**

Crear temporalmente `inventory-service/src/main/java/com/retail/inventory/stock/LeakyStockService.java`:

```java
package com.retail.inventory.stock;

import org.springframework.web.client.RestClient;

class LeakyStockService {
    RestClient client;
}
```

Run: `./mvnw -q -B test -Dtest=ArchitectureTest`
Expected: falla con `Architecture Violation` en la regla `no classes that reside in any package ['com.retail.inventory.stock..', 'com.retail.inventory.sale..'] ... should depend on classes that reside in a package 'org.springframework.web..'`.

Borrar el directorio `inventory-service/src/main/java/com/retail/inventory/stock` y volver a ejecutar. Expected: `Tests run: 4, Failures: 0`.

- [ ] **Step 4: Ejecutar todas las pruebas rápidas**

Run: `./mvnw -q -B test`
Expected: exit 0 con 15 pruebas (`InventoryPropertiesTest` 3, `ContractAssertionsTest` 3, `GlobalExceptionHandlerTest` 5, `ArchitectureTest` 4).

- [ ] **Step 5: Commit**

```bash
git add inventory-service/src/test/java/com/retail/inventory/architecture
git commit -m "test: enforce architecture rules with ArchUnit"
```

---

### Task 5: Imagen Docker y servicio en el Compose

**Files:**
- Modify: `scripts/verify-infra.sh` (dos `up` y la función `compose_defaults`)
- Create: `inventory-service/Dockerfile`
- Create: `.dockerignore`
- Modify: `docker-compose.yml` (servicio `inventory-service` antes de la sección `networks:`)
- Modify: `.env.example` (al final)

**Interfaces:**
- Consumes: el servicio de las tareas 1-4; `infra` y `docs/contracts` del repositorio.
- Produces: servicio `inventory-service` en el Compose (redes `messaging` e `inventory-net`, puerto `127.0.0.1:${INVENTORY_SERVICE_PORT:-8081}:8081`, `depends_on` sano de `inventory-db` y `rabbitmq`, healthcheck con `wget` sobre `/actuator/health`), variables `INVENTORY_SERVICE_PORT` y `JWT_SECRET` en `.env.example`. Las sub-tareas 4.3 y 4.6 agregan las variables `SPRING_DATASOURCE_*` y `SPRING_RABBITMQ_*` al bloque `environment`.

- [ ] **Step 1: Ajustar la verificación de infraestructura para que levante solo la infraestructura**

En `scripts/verify-infra.sh`:

1. En la función `compose_defaults`, reemplazar la línea

```bash
      -u INVENTORY_DB_PASSWORD -u CATALOG_DB_PASSWORD \
```

por

```bash
      -u INVENTORY_DB_PASSWORD -u CATALOG_DB_PASSWORD -u INVENTORY_SERVICE_PORT -u JWT_SECRET \
```

2. Reemplazar las **dos** apariciones de `docker compose up -d --wait --wait-timeout 180` (sección 2 y sección 8) por `docker compose up -d --wait --wait-timeout 180 rabbitmq inventory-db catalog-db`. Las líneas de las secciones 6 y 7 ya nombran un servicio y no cambian.

Run: `grep -n "compose up" scripts/verify-infra.sh`
Expected: cuatro líneas, todas con nombres de servicio (`rabbitmq inventory-db catalog-db`, `rabbitmq`, `inventory-db`, `rabbitmq inventory-db catalog-db`).

- [ ] **Step 2: Escribir el `Dockerfile` y el `.dockerignore`**

`inventory-service/Dockerfile`:

```dockerfile
# syntax=docker/dockerfile:1
# Imagen del servicio de Inventario. El contexto de construcción es la RAÍZ del repositorio,
# porque la compilación copia los contratos desde docs/contracts (fuente única de verdad).

# Etapa 1: compilación con JDK 25 (sin pruebas: se ejecutan con ./mvnw verify fuera de Docker).
FROM eclipse-temurin:25-jdk AS build
WORKDIR /workspace
COPY docs/contracts docs/contracts
COPY inventory-service/.mvn inventory-service/.mvn
COPY inventory-service/mvnw inventory-service/pom.xml inventory-service/
COPY inventory-service/src inventory-service/src
WORKDIR /workspace/inventory-service
RUN --mount=type=cache,target=/root/.m2 ./mvnw -B -q -DskipTests package
# Falla la construcción si el contrato no llegó al jar (p. ej., contexto sin docs/contracts).
RUN test -f target/classes/static/openapi/inventory-service.yaml

# Etapa 2: ejecución con un JRE 25 liviano.
FROM eclipse-temurin:25-jre-alpine
WORKDIR /app
COPY --from=build /workspace/inventory-service/target/inventory-service-*.jar app.jar
EXPOSE 8081
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
```

`.dockerignore` (en la raíz del repositorio):

```text
# Lista de permitidos: solo entra al contexto de construcción lo que las imágenes necesitan.
*
!docs/contracts/
!inventory-service/
inventory-service/target/
```

- [ ] **Step 3: Agregar el servicio al Compose**

En `docker-compose.yml`, insertar este bloque inmediatamente **antes** de la línea `networks:` del nivel superior (después del servicio `catalog-db`), con la misma indentación de dos espacios que los demás servicios:

```yaml
  inventory-service:
    build:
      context: .
      dockerfile: inventory-service/Dockerfile
    environment:
      SERVER_PORT: "8081"
      JWT_SECRET: ${JWT_SECRET:-retail-demo-jwt-secret-not-for-production-use}
    ports:
      - "127.0.0.1:${INVENTORY_SERVICE_PORT:-8081}:8081"
    depends_on:
      inventory-db:
        condition: service_healthy
      rabbitmq:
        condition: service_healthy
    healthcheck:
      test: ["CMD", "wget", "-q", "-O", "-", "http://localhost:8081/actuator/health"]
      interval: 5s
      timeout: 5s
      retries: 24
      start_period: 10s
    networks:
      - messaging
      - inventory-net
```

- [ ] **Step 4: Agregar las variables a `.env.example`**

Agregar al final de `.env.example`:

```bash
INVENTORY_SERVICE_PORT=8081

# Secreto del JWT del servicio de Inventario (al menos 32 bytes). Valor de DEMO.
JWT_SECRET=retail-demo-jwt-secret-not-for-production-use
```

Run: `docker compose config -q && echo "compose válido"`
Expected: `compose válido`.

- [ ] **Step 5: Ejecutar la verificación de infraestructura**

Run (desde la raíz): `scripts/verify-infra.sh | grep -c '^PASS'; docker images -q retail-verify-inventory-service | wc -l`
Expected: `32` (incluye que `.env.example` coincide con los valores por defecto del Compose) y `0` (la verificación no construyó la imagen del servicio). Requiere `curl` y `jq`.

- [ ] **Step 6: Levantar el stack completo y comprobar el servicio**

Requiere los puertos 5672, 15672, 5441, 5442 y 8081 libres. Run:

```bash
docker compose up -d --wait --wait-timeout 300 --build; echo "up exit=$?"
docker compose ps --format '{{.Service}}:{{.Health}}'
curl -s -o /dev/null -w '%{http_code}\n' http://localhost:8081/actuator/health
curl -s -o /dev/null -w '%{http_code}\n' http://localhost:8081/swagger-ui/index.html
curl -s http://localhost:8081/v3/api-docs/swagger-config | jq -r .url
curl -s http://localhost:8081/openapi/inventory-service.yaml | grep -c 'title: Inventory Service API'
curl -s http://localhost:8081/does-not-exist | jq -r .type
```

Expected: `up exit=0` (construye la imagen; la primera vez tarda alrededor de un minuto, después usa la caché); los cuatro servicios `healthy` (`catalog-db`, `inventory-db`, `inventory-service`, `rabbitmq`); `200`; `200`; `/openapi/inventory-service.yaml`; `1`; `https://api.retail-demo.local/problems/not-found`.

- [ ] **Step 7: Comprobar que el contenedor no arranca con un secreto corto**

Run:

```bash
docker compose run --rm --no-deps -e JWT_SECRET=short inventory-service > inventory-service/target/short-secret.log 2>&1; echo "exit=$?"
grep -c 'must be at least 32 bytes long (set JWT_SECRET)' inventory-service/target/short-secret.log
docker compose down -v
```

Expected: `exit=1`, `1` (el mensaje aparece) y el stack se baja.

- [ ] **Step 8: Mutación: un contexto sin los contratos debe hacer fallar la construcción**

Run:

```bash
cp .dockerignore inventory-service/target/dockerignore.backup
sed -i '/!docs\/contracts\//d' .dockerignore
docker compose build inventory-service > inventory-service/target/build.log 2>&1; echo "exit=$?"
grep -c '"/docs/contracts": not found' inventory-service/target/build.log
cp inventory-service/target/dockerignore.backup .dockerignore
git diff --stat .dockerignore
```

Expected: `exit=1`, `1` y `git diff` sin cambios en `.dockerignore`. (La comprobación `RUN test -f ...` del `Dockerfile` cubre además el caso de que el YAML no llegue al jar por un cambio en la copia de recursos.)

- [ ] **Step 9: Commit**

```bash
git add inventory-service/Dockerfile .dockerignore docker-compose.yml .env.example scripts/verify-infra.sh
git commit -m "feat: run inventory service in Docker Compose"
```

---

### Task 6: Documentación

**Files:**
- Modify: `README.md`
- Modify: `CLAUDE.md`

**Interfaces:**
- Consumes: todo lo anterior.
- Produces: instrucciones para ejecutar y probar el servicio.

- [ ] **Step 1: Actualizar el README**

En `README.md`:

1. En el bloque de "Estructura", agregar después de la línea de `infra/rabbitmq/`:

```
inventory-service/        Servicio de Inventario (Spring Boot)
```

2. Reemplazar el comentario del primer comando de "Infraestructura":

```bash
docker compose up -d --wait   # levanta RabbitMQ y las dos bases de datos
```

por:

```bash
docker compose up -d --wait --build   # levanta la infraestructura y los servicios, reconstruyendo sus imágenes
```

3. Reemplazar la frase `Para levantar solo la infraestructura (cuando los servicios ya estén en el Compose):` por `Para levantar solo la infraestructura:`.

4. Agregar al final de la tabla de componentes (debajo de la fila "Base de Catálogo"):

```markdown
| Servicio de Inventario | http://localhost:8081 (Swagger UI en `/swagger-ui/index.html`) | `inventory-service:8081` | JWT (desde la sub-tarea 4.2) |
```

5. Agregar esta sección inmediatamente **antes** de `## Verificación`:

````markdown
## Servicio de Inventario

Código en `inventory-service/` (Java 25, Spring Boot 4, Maven wrapper). Diseño en la
[spec de Inventario](docs/superpowers/specs/2026-10-11-inventario-design.md).

```bash
cd inventory-service
./mvnw test     # pruebas unitarias y de arquitectura (rápidas)
./mvnw verify   # además, las pruebas de integración (*IntegrationTest)
```

Para correrlo desde el IDE o con `./mvnw spring-boot:run`, detener antes su contenedor
(`docker compose stop inventory-service`) y definir `JWT_SECRET` con al menos 32 bytes, por ejemplo
el valor de `.env.example`. Sin `JWT_SECRET` válido el servicio no arranca, a propósito.
````

- [ ] **Step 2: Actualizar los comandos de `CLAUDE.md`**

En `CLAUDE.md`, sección `## Comandos`, agregar al final de la lista:

```markdown
- `cd inventory-service && ./mvnw test` (unitarias y arquitectura) y `./mvnw verify` (además
  integración). El servicio necesita `JWT_SECRET` de al menos 32 bytes para arrancar.
- Tras cambiar el código de un servicio, `docker compose up -d --wait --build`: sin `--build`,
  Compose reutiliza la imagen anterior.
```

- [ ] **Step 3: Verificar enlaces y contenido**

Run:

```bash
grep -c 'inventory-service/        Servicio de Inventario' README.md
grep -c '## Servicio de Inventario' README.md
grep -c 'docs/superpowers/specs/2026-10-11-inventario-design.md' README.md
test -f docs/superpowers/specs/2026-10-11-inventario-design.md && echo "enlace ok"
grep -c 'cd inventory-service && ./mvnw test' CLAUDE.md
```

Expected: `1`, `1`, `1`, `enlace ok`, `1`. Además, `grep -c 'up -d --wait --build' README.md CLAUDE.md` debe dar `1` en cada archivo.

- [ ] **Step 4: Commit**

```bash
git add README.md CLAUDE.md
git commit -m "docs: document how to run and test the inventory service"
```
