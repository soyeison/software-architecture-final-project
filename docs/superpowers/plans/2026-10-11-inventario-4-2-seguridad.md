# Inventario 4.2: seguridad JWT — Plan de implementación

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Proteger `inventory-service` con JWT HS256 según la matriz de acceso de la spec, responder 401/403 en `application/problem+json` y entregar `scripts/generate-token.sh` para obtener tokens de demo.

**Architecture:** Resource Server de Spring Security sin sesión, con reglas por ruta seguras por defecto (`SecurityConfiguration` en `security/`). Las respuestas 401/403 las escribe un único `ProblemResponseWriter` (en `error/`) con los mismos `ProblemType` del manejador global. Mientras no existan los controladores reales, un controlador solo de pruebas expone las rutas del contrato.

**Tech Stack:** Spring Boot 4.1.1 (`spring-boot-starter-security-oauth2-resource-server`), Spring Security 7, Nimbus JOSE (incluido en el starter), Jackson 3, bash y openssl.

**Spec:** `docs/superpowers/specs/2026-10-11-inventario-design.md` (sección 5 y "Pendientes heredados"). Contrato: `docs/contracts/openapi/inventory-service.yaml` y spec de contratos §7.

## Global Constraints

- Spring Boot `4.1.1`, Java `25`, Maven solo con el wrapper `./mvnw` desde `inventory-service/`. Paquete base `com.retail.inventory`.
- **Nombres completos, sin abreviaturas** (salvo `SKU`, `JWT`, `UUID`, `DTO`, `HTTP`, `JSON`, `DLQ` y unidades). Código en inglés; comentarios, Javadoc y documentación en español.
- Commits en inglés, breves, Conventional Commits, con la línea `Co-Authored-By: Claude <modelo> <noreply@anthropic.com>`.
- La ruta del repositorio contiene espacios: toda ruta en comandos va entre comillas dobles.
- JWT: **solo HS256** con el secreto de `JWT_SECRET`; emisor `retail-demo-auth`; expiración validada; claim `scope` con permisos separados por espacio (`inventory:sell`, `inventory:read`).
- Matriz de acceso (spec §5): `POST /api/v1/inventory/{sku}/sales` → `SCOPE_inventory:sell`; `GET /api/v1/inventory/outbox` → pública (y su regla va **antes** que la de `{sku}`); `GET /api/v1/inventory/{sku}` → `SCOPE_inventory:read`; `/actuator/health`, Swagger UI, `/v3/api-docs/swagger-config`, `/openapi/inventory-service.yaml` y `/error` → públicas; cualquier otra, incluido `/v3/api-docs` → denegada.
- 401 con detalle `A valid bearer token is required` y cabecera `WWW-Authenticate: Bearer`; 403 con detalle `The token lacks the required scope`. Tipos `https://api.retail-demo.local/problems/unauthorized` y `.../forbidden`.
- Perfiles de `scripts/generate-token.sh`: `pos` (`pos-store-001`, `inventory:sell`), `online` (`online-store`, `inventory:sell`), `operator` (`operator`, `inventory:read`), `no-scope` (`test-client`, vacío); `exp` a 24 h; imprime solo el token. Secreto: `JWT_SECRET`, luego `.env`, luego `.env.example`.
- Las pruebas de integración se nombran `*IntegrationTest` (failsafe, `./mvnw verify`); las demás corren con `./mvnw test`.

## Review Focus

1. **El orden de las reglas `outbox` / `{sku}`.** Si se invierte, `GET /api/v1/inventory/outbox` exigiría el scope de lectura (401 sin token). Se espera que la prueba lo detecte. Se fija con la mutación de la Tarea 2, paso 8.
2. **Un cuerpo 401/403 con `"properties": null`.** Ocurre si el `JsonMapper` no tiene la mezcla de Jackson para `ProblemDetail` (la registra Spring Boot). Se espera un cuerpo limpio que cumpla el schema `Problem`. Se fija con `ProblemResponseWriterTest` (Tarea 1) y con la aserción `doesNotContain("\"properties\"")` de la Tarea 2.
3. **Tokens inválidos** (firma con otro secreto, otro emisor, expirados, mal formados). Se espera siempre 401, nunca 403 ni 500. Se fija en `SecurityIntegrationTest` (Tarea 2).
4. **Rutas no declaradas y `/v3/api-docs`.** Se espera 401 sin token y 403 con token (no 404), y que el OpenAPI generado por springdoc no se publique. Se fija en `SecurityIntegrationTest` (Tarea 2).
5. **El script sin `JWT_SECRET` en el entorno.** Se espera que tome el secreto de `.env.example` y que el contenedor del Compose acepte sus tokens. Se fija con la prueba de punta a punta de la Tarea 4, paso 5.

---

## Estructura de archivos

| Archivo | Responsabilidad |
|---|---|
| `inventory-service/src/main/java/com/retail/inventory/error/ProblemType.java` | Agrega `UNAUTHORIZED` y `FORBIDDEN` |
| `.../error/ProblemResponseWriter.java` | Escribe `application/problem+json` fuera de los controladores |
| `.../security/SecurityConfiguration.java` | Cadena de filtros, reglas por ruta y decodificador HS256 |
| `.../security/ProblemAuthenticationEntryPoint.java` | 401 con `WWW-Authenticate: Bearer` |
| `.../security/ProblemAccessDeniedHandler.java` | 403 |
| `inventory-service/pom.xml` | Starter del Resource Server y su starter de pruebas |
| `src/test/.../error/ProblemResponseWriterTest.java` | Cuerpo del 401 contra el contrato |
| `src/test/.../error/GlobalExceptionHandlerTest.java` | Desactiva los filtros de seguridad |
| `src/test/.../support/TestTokens.java` | Firma tokens de prueba, válidos e inválidos |
| `src/test/.../security/SecuredProbeController.java` | Rutas del contrato, solo para pruebas |
| `src/test/.../security/SecurityIntegrationTest.java` | Matriz de acceso |
| `src/test/.../security/GenerateTokenScriptIntegrationTest.java` | El script y el validador están de acuerdo |
| `scripts/generate-token.sh` | Tokens de demo |
| `docs/contracts/openapi/inventory-service.yaml` | Cabecera `WWW-Authenticate` en el 401 |
| `README.md`, `CLAUDE.md` | Uso de los tokens |

---

### Task 1: Respuestas 401 y 403 en formato del contrato

**Files:**
- Modify: `inventory-service/src/main/java/com/retail/inventory/error/ProblemType.java`
- Create: `inventory-service/src/main/java/com/retail/inventory/error/ProblemResponseWriter.java`
- Test: `inventory-service/src/test/java/com/retail/inventory/error/ProblemResponseWriterTest.java`

**Interfaces:**
- Consumes: `ProblemType.toProblemDetail(String detail, String requestPath)` y `ProblemType.status()` (4.1); `ContractAssertions.assertMatchesSchema(String json, String schemaName)` (4.1).
- Produces: `ProblemType.UNAUTHORIZED` y `ProblemType.FORBIDDEN`; `ProblemResponseWriter` (`@Component`, constructor `ProblemResponseWriter(tools.jackson.databind.json.JsonMapper)`, método `void write(HttpServletRequest request, HttpServletResponse response, ProblemType problemType, String detail) throws IOException`), que usará la Tarea 2.

- [ ] **Step 1: Escribir la prueba (falla)**

`inventory-service/src/test/java/com/retail/inventory/error/ProblemResponseWriterTest.java`:

```java
package com.retail.inventory.error;

import static com.retail.inventory.support.ContractAssertions.assertMatchesSchema;
import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.json.ProblemDetailJacksonMixin;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import tools.jackson.databind.json.JsonMapper;

class ProblemResponseWriterTest {

    /** Igual que el JsonMapper de Spring Boot: registra la mezcla que aplana ProblemDetail (RFC 9457). */
    private final ProblemResponseWriter writer = new ProblemResponseWriter(
            JsonMapper.builder().addMixIn(ProblemDetail.class, ProblemDetailJacksonMixin.class).build());

    @Test
    void writesUnauthorizedProblemMatchingTheContract() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/inventory/TENIS-RUNNER-42/sales");
        MockHttpServletResponse response = new MockHttpServletResponse();

        writer.write(request, response, ProblemType.UNAUTHORIZED, "A valid bearer token is required");

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getContentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        String body = response.getContentAsString();
        assertThat(body).contains("\"type\":\"https://api.retail-demo.local/problems/unauthorized\"")
                .contains("\"instance\":\"/api/v1/inventory/TENIS-RUNNER-42/sales\"")
                .doesNotContain("\"properties\"");
        assertMatchesSchema(body, "Problem");
    }
}
```

- [ ] **Step 2: Ejecutar y verificar que falla**

Run (desde `inventory-service/`): `./mvnw -q -B test -Dtest=ProblemResponseWriterTest`
Expected: error de compilación (`cannot find symbol`: `ProblemResponseWriter` y `ProblemType.UNAUTHORIZED`).

- [ ] **Step 3: Agregar los tipos de problema**

En `ProblemType.java`, reemplazar:

```java
    VALIDATION_ERROR("validation-error", "Validation error", HttpStatus.BAD_REQUEST),
    NOT_FOUND("not-found", "Not found", HttpStatus.NOT_FOUND),
```

por:

```java
    VALIDATION_ERROR("validation-error", "Validation error", HttpStatus.BAD_REQUEST),
    UNAUTHORIZED("unauthorized", "Unauthorized", HttpStatus.UNAUTHORIZED),
    FORBIDDEN("forbidden", "Forbidden", HttpStatus.FORBIDDEN),
    NOT_FOUND("not-found", "Not found", HttpStatus.NOT_FOUND),
```

- [ ] **Step 4: Escribir el escritor de respuestas**

`inventory-service/src/main/java/com/retail/inventory/error/ProblemResponseWriter.java`:

```java
package com.retail.inventory.error;

import java.io.IOException;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.stereotype.Component;

import tools.jackson.databind.json.JsonMapper;

/**
 * Escribe una respuesta {@code application/problem+json} directamente sobre la respuesta HTTP. Lo usan
 * los componentes que responden antes de llegar a los controladores (los filtros de seguridad), con
 * los mismos {@link ProblemType} que el {@link GlobalExceptionHandler}.
 *
 * <p>Usa el {@link JsonMapper} de Spring Boot, que registra la mezcla de Jackson para
 * {@code ProblemDetail}; sin ella el cuerpo incluiría un campo {@code properties} ajeno al contrato.
 */
@Component
public class ProblemResponseWriter {

    private final JsonMapper jsonMapper;

    public ProblemResponseWriter(JsonMapper jsonMapper) {
        this.jsonMapper = jsonMapper;
    }

    public void write(HttpServletRequest request, HttpServletResponse response, ProblemType problemType,
            String detail) throws IOException {
        ProblemDetail problem = problemType.toProblemDetail(detail, request.getRequestURI());
        response.setStatus(problemType.status().value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        jsonMapper.writeValue(response.getOutputStream(), problem);
    }
}
```

- [ ] **Step 5: Ejecutar y verificar que pasa**

Run: `./mvnw -q -B test -Dtest=ProblemResponseWriterTest`
Expected: `Tests run: 1, Failures: 0, Errors: 0` en `target/surefire-reports/com.retail.inventory.error.ProblemResponseWriterTest.txt`.

Nota: la prueba construye el `JsonMapper` con `ProblemDetailJacksonMixin`, igual que Spring Boot. Con un `JsonMapper` simple el cuerpo incluiría `"properties":null` y la prueba fallaría; por eso el servicio siempre inyecta el `JsonMapper` de Boot.

- [ ] **Step 6: Commit**

```bash
git add inventory-service/src
git commit -m "feat: add problem response writer for 401 and 403"
```

---

### Task 2: Configuración de seguridad y matriz de acceso

**Files:**
- Modify: `inventory-service/pom.xml` (dos dependencias)
- Create: `inventory-service/src/main/java/com/retail/inventory/security/SecurityConfiguration.java`
- Create: `inventory-service/src/main/java/com/retail/inventory/security/ProblemAuthenticationEntryPoint.java`
- Create: `inventory-service/src/main/java/com/retail/inventory/security/ProblemAccessDeniedHandler.java`
- Modify: `inventory-service/src/test/java/com/retail/inventory/error/GlobalExceptionHandlerTest.java`
- Create: `inventory-service/src/test/java/com/retail/inventory/support/TestTokens.java`
- Create: `inventory-service/src/test/java/com/retail/inventory/security/SecuredProbeController.java`
- Test: `inventory-service/src/test/java/com/retail/inventory/security/SecurityIntegrationTest.java`

**Interfaces:**
- Consumes: `ProblemResponseWriter` y `ProblemType.UNAUTHORIZED` / `FORBIDDEN` (Tarea 1); `InventoryProperties.jwt().secret()` e `.issuer()` (4.1).
- Produces: el bean `SecurityFilterChain` y el bean `JwtDecoder`; las constantes `SecurityConfiguration.SELL_AUTHORITY` (`SCOPE_inventory:sell`) y `READ_AUTHORITY` (`SCOPE_inventory:read`); el ayudante de pruebas `TestTokens` (`SECRET`, `ISSUER`, `withScope(String scope)`, `sign(String secret, String issuer, String scope, Instant expiresAt)`) y `SecuredProbeController` (rutas `POST /api/v1/inventory/{sku}/sales` → 201, `GET /api/v1/inventory/outbox` → 200 `outbox`, `GET /api/v1/inventory/{sku}` → 200), que usará la Tarea 3. Las sub-tareas 4.3, 4.4 y 4.6 eliminan `SecuredProbeController` a medida que llegan los controladores reales.

- [ ] **Step 1: Agregar las dependencias**

En `inventory-service/pom.xml`, insertar antes del bloque de `springdoc-openapi-starter-webmvc-ui`:

```xml
		<dependency>
			<groupId>org.springframework.boot</groupId>
			<artifactId>spring-boot-starter-security-oauth2-resource-server</artifactId>
		</dependency>
```

e insertar antes del bloque de `archunit-junit5`:

```xml
		<dependency>
			<groupId>org.springframework.boot</groupId>
			<artifactId>spring-boot-starter-security-oauth2-resource-server-test</artifactId>
			<scope>test</scope>
		</dependency>
```

- [ ] **Step 2: Escribir el ayudante de tokens, el controlador de prueba y la matriz**

`inventory-service/src/test/java/com/retail/inventory/support/TestTokens.java`:

```java
package com.retail.inventory.support;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;

/**
 * Firma tokens JWT HS256 para las pruebas, incluidos los inválidos (otro secreto, otro emisor, expirados).
 */
public final class TestTokens {

    /** Secreto de las pruebas (al menos 32 bytes). Se pasa como {@code inventory.jwt.secret}. */
    public static final String SECRET = "integration-test-secret-of-32-bytes!";
    public static final String ISSUER = "retail-demo-auth";

    private TestTokens() {
    }

    /** Token válido con los permisos indicados (separados por espacio; vacío para ninguno). */
    public static String withScope(String scope) {
        return sign(SECRET, ISSUER, scope, Instant.now().plus(1, ChronoUnit.HOURS));
    }

    public static String sign(String secret, String issuer, String scope, Instant expiresAt) {
        Instant issuedAt = expiresAt.minus(2, ChronoUnit.HOURS);
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .issuer(issuer)
                .subject("test-client")
                .claim("scope", scope)
                .issueTime(Date.from(issuedAt))
                .expirationTime(Date.from(expiresAt))
                .build();
        SignedJWT token = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claims);
        try {
            token.sign(new MACSigner(secret.getBytes(StandardCharsets.UTF_8)));
        } catch (JOSEException exception) {
            throw new IllegalStateException("Could not sign test token", exception);
        }
        return token.serialize();
    }
}
```

`inventory-service/src/test/java/com/retail/inventory/security/SecuredProbeController.java`:

```java
package com.retail.inventory.security;

import org.springframework.boot.test.context.TestComponent;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Controlador solo de pruebas con las rutas reales del contrato, mientras no existen sus
 * controladores (llegan en 4.3, 4.4 y 4.6, que lo eliminan). {@link TestComponent} lo excluye del
 * escaneo de {@code @SpringBootTest}; se importa explícitamente.
 */
@TestComponent
@RestController
class SecuredProbeController {

    @PostMapping("/api/v1/inventory/{sku}/sales")
    @ResponseStatus(HttpStatus.CREATED)
    String registerSale(@PathVariable String sku) {
        return "sale-accepted";
    }

    @GetMapping("/api/v1/inventory/outbox")
    String listOutboxEvents() {
        return "outbox";
    }

    @GetMapping("/api/v1/inventory/{sku}")
    String getStock(@PathVariable String sku) {
        return "stock";
    }
}
```

`inventory-service/src/test/java/com/retail/inventory/security/SecurityIntegrationTest.java`:

```java
package com.retail.inventory.security;

import static com.retail.inventory.support.ContractAssertions.assertMatchesSchema;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import com.retail.inventory.support.TestTokens;

/**
 * Matriz de acceso de la spec de Inventario §5, con la configuración de seguridad real.
 */
@SpringBootTest(properties = "inventory.jwt.secret=" + TestTokens.SECRET)
@AutoConfigureMockMvc
@Import(SecuredProbeController.class)
class SecurityIntegrationTest {

    private static final String PROBLEM_TYPE_BASE = "https://api.retail-demo.local/problems/";
    private static final String SALES_PATH = "/api/v1/inventory/TENIS-RUNNER-42/sales";
    private static final String STOCK_PATH = "/api/v1/inventory/TENIS-RUNNER-42";
    private static final String SALE_BODY = "{\"quantity\": 1, \"channel\": \"POS\"}";

    @Autowired
    private MockMvc mockMvc;

    @Test
    void saleWithoutTokenIsUnauthorizedWithBearerChallenge() throws Exception {
        String body = mockMvc.perform(post(SALES_PATH).contentType(MediaType.APPLICATION_JSON).content(SALE_BODY))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, "Bearer"))
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.type").value(PROBLEM_TYPE_BASE + "unauthorized"))
                .andExpect(jsonPath("$.instance").value(SALES_PATH))
                .andReturn().getResponse().getContentAsString();

        assertMatchesSchema(body, "Problem");
        assertThat(body).doesNotContain("\"properties\"");
    }

    @Test
    void saleWithMalformedTokenIsUnauthorized() throws Exception {
        mockMvc.perform(sale("not-a-jwt")).andExpect(status().isUnauthorized());
    }

    @Test
    void saleWithTokenSignedByAnotherSecretIsUnauthorized() throws Exception {
        String token = TestTokens.sign("another-secret-of-at-least-32-bytes!", TestTokens.ISSUER, "inventory:sell",
                Instant.now().plus(1, ChronoUnit.HOURS));

        mockMvc.perform(sale(token)).andExpect(status().isUnauthorized());
    }

    @Test
    void saleWithExpiredTokenIsUnauthorized() throws Exception {
        String token = TestTokens.sign(TestTokens.SECRET, TestTokens.ISSUER, "inventory:sell",
                Instant.now().minus(1, ChronoUnit.HOURS));

        mockMvc.perform(sale(token)).andExpect(status().isUnauthorized());
    }

    @Test
    void saleWithAnotherIssuerIsUnauthorized() throws Exception {
        String token = TestTokens.sign(TestTokens.SECRET, "another-issuer", "inventory:sell",
                Instant.now().plus(1, ChronoUnit.HOURS));

        mockMvc.perform(sale(token)).andExpect(status().isUnauthorized());
    }

    @Test
    void saleWithReadScopeIsForbidden() throws Exception {
        String body = mockMvc.perform(sale(TestTokens.withScope("inventory:read")))
                .andExpect(status().isForbidden())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.type").value(PROBLEM_TYPE_BASE + "forbidden"))
                .andReturn().getResponse().getContentAsString();

        assertMatchesSchema(body, "Problem");
    }

    @Test
    void saleWithSellScopeIsAllowed() throws Exception {
        mockMvc.perform(sale(TestTokens.withScope("inventory:sell"))).andExpect(status().isCreated());
    }

    @Test
    void stockWithReadScopeIsAllowed() throws Exception {
        mockMvc.perform(get(STOCK_PATH).header(HttpHeaders.AUTHORIZATION, bearer(TestTokens.withScope("inventory:read"))))
                .andExpect(status().isOk());
    }

    @Test
    void stockWithSellScopeIsForbidden() throws Exception {
        mockMvc.perform(get(STOCK_PATH).header(HttpHeaders.AUTHORIZATION, bearer(TestTokens.withScope("inventory:sell"))))
                .andExpect(status().isForbidden());
    }

    @Test
    void stockWithoutTokenIsUnauthorized() throws Exception {
        mockMvc.perform(get(STOCK_PATH)).andExpect(status().isUnauthorized());
    }

    @Test
    void outboxDiagnosticIsPublicAndNotTakenAsASku() throws Exception {
        mockMvc.perform(get("/api/v1/inventory/outbox"))
                .andExpect(status().isOk())
                .andExpect(content().string("outbox"));
    }

    @Test
    void unknownRouteIsUnauthorizedWithoutToken() throws Exception {
        mockMvc.perform(get("/does-not-exist")).andExpect(status().isUnauthorized());
    }

    @Test
    void unknownRouteIsForbiddenWithValidToken() throws Exception {
        mockMvc.perform(get("/does-not-exist")
                        .header(HttpHeaders.AUTHORIZATION, bearer(TestTokens.withScope("inventory:read inventory:sell"))))
                .andExpect(status().isForbidden());
    }

    @Test
    void generatedApiDocsAreDenied() throws Exception {
        mockMvc.perform(get("/v3/api-docs")).andExpect(status().isUnauthorized());
    }

    @ParameterizedTest
    @ValueSource(strings = {"/actuator/health", "/swagger-ui/index.html", "/v3/api-docs/swagger-config",
            "/openapi/inventory-service.yaml"})
    void documentationAndHealthArePublic(String path) throws Exception {
        mockMvc.perform(get(path)).andExpect(status().isOk());
    }

    private static org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder sale(String token) {
        return post(SALES_PATH)
                .header(HttpHeaders.AUTHORIZATION, bearer(token))
                .contentType(MediaType.APPLICATION_JSON)
                .content(SALE_BODY);
    }

    private static String bearer(String token) {
        return "Bearer " + token;
    }
}
```

- [ ] **Step 3: Ejecutar y verificar que falla**

Run: `./mvnw -q -B verify`
Expected: exit distinto de 0. Con el starter y sin configuración propia, Spring Boot protege todo con su configuración por defecto: fallan pruebas de `SecurityIntegrationTest` (por ejemplo, la venta con scope de venta no devuelve 201 y el outbox no es público) y las 5 de `GlobalExceptionHandlerTest` (reciben 401/403 en vez de 400/404/405/500).

- [ ] **Step 4: Escribir los manejadores de 401 y 403**

`inventory-service/src/main/java/com/retail/inventory/security/ProblemAuthenticationEntryPoint.java`:

```java
package com.retail.inventory.security;

import java.io.IOException;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.http.HttpHeaders;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;

import com.retail.inventory.error.ProblemResponseWriter;
import com.retail.inventory.error.ProblemType;

/**
 * Responde 401 {@code unauthorized} cuando falta el token o es inválido (firma, emisor o expiración).
 */
@Component
class ProblemAuthenticationEntryPoint implements AuthenticationEntryPoint {

    private final ProblemResponseWriter problemResponseWriter;

    ProblemAuthenticationEntryPoint(ProblemResponseWriter problemResponseWriter) {
        this.problemResponseWriter = problemResponseWriter;
    }

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response,
            AuthenticationException exception) throws IOException {
        // RFC 6750: toda respuesta 401 de un recurso protegido por bearer token indica el esquema.
        response.setHeader(HttpHeaders.WWW_AUTHENTICATE, "Bearer");
        problemResponseWriter.write(request, response, ProblemType.UNAUTHORIZED, "A valid bearer token is required");
    }
}
```

`inventory-service/src/main/java/com/retail/inventory/security/ProblemAccessDeniedHandler.java`:

```java
package com.retail.inventory.security;

import java.io.IOException;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;

import com.retail.inventory.error.ProblemResponseWriter;
import com.retail.inventory.error.ProblemType;

/**
 * Responde 403 {@code forbidden} cuando el token es válido pero no tiene el permiso requerido.
 */
@Component
class ProblemAccessDeniedHandler implements AccessDeniedHandler {

    private final ProblemResponseWriter problemResponseWriter;

    ProblemAccessDeniedHandler(ProblemResponseWriter problemResponseWriter) {
        this.problemResponseWriter = problemResponseWriter;
    }

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response,
            AccessDeniedException exception) throws IOException {
        problemResponseWriter.write(request, response, ProblemType.FORBIDDEN, "The token lacks the required scope");
    }
}
```

- [ ] **Step 5: Escribir la configuración de seguridad**

`inventory-service/src/main/java/com/retail/inventory/security/SecurityConfiguration.java`:

```java
package com.retail.inventory.security;

import java.nio.charset.StandardCharsets;

import javax.crypto.spec.SecretKeySpec;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.web.SecurityFilterChain;

import com.retail.inventory.config.InventoryProperties;

/**
 * Seguridad del servicio (spec de Inventario §5): JWT HS256 sin sesión y reglas por ruta, seguras por
 * defecto (lo que no se declara se deniega).
 */
@Configuration
public class SecurityConfiguration {

    static final String SELL_AUTHORITY = "SCOPE_inventory:sell";
    static final String READ_AUTHORITY = "SCOPE_inventory:read";

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, ProblemAuthenticationEntryPoint authenticationEntryPoint,
            ProblemAccessDeniedHandler accessDeniedHandler) throws Exception {
        http
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(authorize -> authorize
                        // El diagnóstico del outbox va ANTES que la regla de {sku}: si no, exigiría el scope de lectura.
                        .requestMatchers(HttpMethod.GET, "/api/v1/inventory/outbox").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/v1/inventory/*/sales").hasAuthority(SELL_AUTHORITY)
                        .requestMatchers(HttpMethod.GET, "/api/v1/inventory/*").hasAuthority(READ_AUTHORITY)
                        .requestMatchers(HttpMethod.GET, "/actuator/health", "/swagger-ui.html", "/swagger-ui/**",
                                "/v3/api-docs/swagger-config", "/openapi/inventory-service.yaml").permitAll()
                        .requestMatchers("/error").permitAll()
                        // Incluye /v3/api-docs: el contrato publicado es solo el escrito a mano.
                        .anyRequest().denyAll())
                .oauth2ResourceServer(resourceServer -> resourceServer
                        .jwt(Customizer.withDefaults())
                        .authenticationEntryPoint(authenticationEntryPoint)
                        .accessDeniedHandler(accessDeniedHandler))
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint(authenticationEntryPoint)
                        .accessDeniedHandler(accessDeniedHandler));
        return http.build();
    }

    /** Solo HS256 con el secreto de {@code JWT_SECRET}; valida emisor y expiración. */
    @Bean
    JwtDecoder jwtDecoder(InventoryProperties properties) {
        SecretKeySpec key = new SecretKeySpec(
                properties.jwt().secret().getBytes(StandardCharsets.UTF_8), "HmacSHA256");
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withSecretKey(key).macAlgorithm(MacAlgorithm.HS256).build();
        decoder.setJwtValidator(JwtValidators.createDefaultWithIssuer(properties.jwt().issuer()));
        return decoder;
    }
}
```

- [ ] **Step 6: Desactivar los filtros de seguridad en las pruebas del manejador de errores**

En `GlobalExceptionHandlerTest.java`, agregar el import:

```java
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
```

y reemplazar:

```java
@WebMvcTest
@Import(ProbeController.class)
class GlobalExceptionHandlerTest {
```

por:

```java
/**
 * Prueba solo el manejo de errores: los filtros de seguridad se desactivan y la seguridad se prueba
 * aparte (spec de Inventario §5).
 */
@WebMvcTest
@AutoConfigureMockMvc(addFilters = false)
@Import(ProbeController.class)
class GlobalExceptionHandlerTest {
```

- [ ] **Step 7: Ejecutar y verificar que pasa**

Run: `./mvnw -q -B verify`
Expected: exit 0. En `target/failsafe-reports/`: `SecurityIntegrationTest` con `Tests run: 18, Failures: 0`, e `InventoryApplicationIntegrationTest` con `Tests run: 5, Failures: 0` (health, Swagger y el contrato siguen públicos). En `target/surefire-reports/`: `GlobalExceptionHandlerTest` con 5 sin fallos.

- [ ] **Step 8: Mutación: invertir el orden de las reglas `outbox` y `{sku}` debe hacer fallar la matriz**

En `SecurityConfiguration.java`, intercambiar temporalmente las líneas:

```java
                        .requestMatchers(HttpMethod.GET, "/api/v1/inventory/outbox").permitAll()
```

y

```java
                        .requestMatchers(HttpMethod.GET, "/api/v1/inventory/*").hasAuthority(READ_AUTHORITY)
```

(que la de `{sku}` quede antes que la de `outbox`).

Run: `./mvnw -q -B verify -Dit.test=SecurityIntegrationTest`
Expected: exit distinto de 0, con una sola prueba fallida: `SecurityIntegrationTest.outboxDiagnosticIsPublicAndNotTakenAsASku ... Status expected:<200> but was:<401>`.

Restaurar el orden original y confirmar que `git diff -- inventory-service/src/main` solo muestra los archivos nuevos de esta tarea (sin cambios en el orden de las reglas); volver a ejecutar `./mvnw -q -B verify`. Expected: exit 0.

- [ ] **Step 9: Commit**

```bash
git add inventory-service/pom.xml inventory-service/src
git commit -m "feat: secure inventory service with HS256 JWT and route rules"
```

---

### Task 3: Script de tokens de demo

**Files:**
- Create: `scripts/generate-token.sh`
- Test: `inventory-service/src/test/java/com/retail/inventory/security/GenerateTokenScriptIntegrationTest.java`

**Interfaces:**
- Consumes: `TestTokens.SECRET` y `SecuredProbeController` (Tarea 2); la configuración de seguridad (Tarea 2).
- Produces: `scripts/generate-token.sh <pos|online|operator|no-scope>`, que imprime solo el token y sale con código 2 ante un perfil desconocido; lo usarán la documentación, la Tarea 4 y los scripts E2E de la fase 6.

- [ ] **Step 1: Escribir la prueba (falla)**

`inventory-service/src/test/java/com/retail/inventory/security/GenerateTokenScriptIntegrationTest.java`:

```java
package com.retail.inventory.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import com.retail.inventory.support.TestTokens;

/**
 * Comprueba que el script real {@code scripts/generate-token.sh} y el validador del servicio están de
 * acuerdo (algoritmo, emisor, claims y permisos de cada perfil).
 */
@SpringBootTest(properties = "inventory.jwt.secret=" + TestTokens.SECRET)
@AutoConfigureMockMvc
@Import(SecuredProbeController.class)
class GenerateTokenScriptIntegrationTest {

    /** Maven ejecuta las pruebas desde inventory-service/; el script vive en la raíz del repositorio. */
    private static final Path SCRIPT = Path.of("..", "scripts", "generate-token.sh");
    private static final String SALES_PATH = "/api/v1/inventory/TENIS-RUNNER-42/sales";
    private static final String STOCK_PATH = "/api/v1/inventory/TENIS-RUNNER-42";
    private static final String SALE_BODY = "{\"quantity\": 1, \"channel\": \"ONLINE\"}";

    @Autowired
    private MockMvc mockMvc;

    @Test
    void posAndOnlineTokensCanSell() throws Exception {
        for (String profile : new String[] {"pos", "online"}) {
            mockMvc.perform(post(SALES_PATH).header(HttpHeaders.AUTHORIZATION, "Bearer " + generate(profile).output())
                            .contentType(MediaType.APPLICATION_JSON).content(SALE_BODY))
                    .andExpect(status().isCreated());
        }
    }

    @Test
    void operatorTokenCanReadButNotSell() throws Exception {
        String token = generate("operator").output();

        mockMvc.perform(get(STOCK_PATH).header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk());
        mockMvc.perform(post(SALES_PATH).header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content(SALE_BODY))
                .andExpect(status().isForbidden());
    }

    @Test
    void noScopeTokenIsValidButForbidden() throws Exception {
        mockMvc.perform(post(SALES_PATH).header(HttpHeaders.AUTHORIZATION, "Bearer " + generate("no-scope").output())
                        .contentType(MediaType.APPLICATION_JSON).content(SALE_BODY))
                .andExpect(status().isForbidden());
    }

    @Test
    void unknownProfileIsRejectedWithUsage() throws Exception {
        ScriptResult result = generate("admin");

        assertThat(result.exitCode()).isEqualTo(2);
        assertThat(result.output()).isEmpty();
    }

    private record ScriptResult(int exitCode, String output) {
    }

    private static ScriptResult generate(String profile) throws IOException, InterruptedException {
        ProcessBuilder builder = new ProcessBuilder("bash", SCRIPT.toString(), profile);
        builder.environment().put("JWT_SECRET", TestTokens.SECRET);
        builder.redirectError(ProcessBuilder.Redirect.DISCARD);
        Process process = builder.start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
        if (!process.waitFor(10, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new IllegalStateException("generate-token.sh did not finish");
        }
        return new ScriptResult(process.exitValue(), output);
    }
}
```

- [ ] **Step 2: Ejecutar y verificar que falla**

Run: `./mvnw -q -B verify -Dit.test=GenerateTokenScriptIntegrationTest`
Expected: exit distinto de 0. Sin el script, el token sale vacío: las pruebas reciben 401 en vez de 201/200/403, y `unknownProfileIsRejectedWithUsage` recibe el código 127 de bash en vez de 2.

- [ ] **Step 3: Escribir el script**

`scripts/generate-token.sh`:

```bash
#!/usr/bin/env bash
# Genera un JWT HS256 de DEMO para el servicio de Inventario (spec de Inventario §5).
# Uso: scripts/generate-token.sh <pos|online|operator|no-scope>
# El secreto sale de JWT_SECRET; si no está definido, de .env y luego de .env.example, de modo que
# coincide con el Compose sin configurar nada. Imprime solo el token.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ISSUER="retail-demo-auth"
VALIDITY_SECONDS=86400

case "${1:-}" in
  pos)      subject="pos-store-001"; scope="inventory:sell" ;;
  online)   subject="online-store";  scope="inventory:sell" ;;
  operator) subject="operator";      scope="inventory:read" ;;
  no-scope) subject="test-client";   scope="" ;;
  *)
    echo "Uso: $0 <pos|online|operator|no-scope>" >&2
    exit 2
    ;;
esac

secret="${JWT_SECRET:-}"
if [[ -z "$secret" ]]; then
  for environment_file in "$ROOT/.env" "$ROOT/.env.example"; do
    if [[ -f "$environment_file" ]]; then
      secret="$(grep -E '^JWT_SECRET=' "$environment_file" | tail -n 1 | cut -d '=' -f 2-)"
      [[ -n "$secret" ]] && break
    fi
  done
fi
if [[ -z "$secret" ]]; then
  echo "No se encontró JWT_SECRET (ni en el entorno, ni en .env, ni en .env.example)" >&2
  exit 1
fi

base64url() { openssl base64 -A | tr '+/' '-_' | tr -d '='; }

issued_at="$(date +%s)"
expires_at=$((issued_at + VALIDITY_SECONDS))
header='{"alg":"HS256","typ":"JWT"}'
payload="$(printf '{"iss":"%s","sub":"%s","scope":"%s","iat":%d,"exp":%d}' \
  "$ISSUER" "$subject" "$scope" "$issued_at" "$expires_at")"

unsigned_token="$(printf '%s' "$header" | base64url).$(printf '%s' "$payload" | base64url)"
signature="$(printf '%s' "$unsigned_token" | openssl dgst -sha256 -hmac "$secret" -binary | base64url)"
printf '%s.%s\n' "$unsigned_token" "$signature"
```

Run (desde la raíz): `chmod +x scripts/generate-token.sh`

- [ ] **Step 4: Ejecutar y verificar que pasa**

Run (desde `inventory-service/`): `./mvnw -q -B verify -Dit.test=GenerateTokenScriptIntegrationTest`
Expected: exit 0 y `Tests run: 4, Failures: 0` en `target/failsafe-reports/com.retail.inventory.security.GenerateTokenScriptIntegrationTest.txt`.

- [ ] **Step 5: Comprobar el script a mano**

Run (desde la raíz):

```bash
scripts/generate-token.sh pos | tr '.' '\n' | sed -n '2p' | tr '_-' '/+' | base64 -d 2>/dev/null; echo
scripts/generate-token.sh admin; echo "exit=$?"
```

Expected: un JSON con `"iss":"retail-demo-auth"`, `"sub":"pos-store-001"` y `"scope":"inventory:sell"` (puede faltar la última llave por el relleno base64; es solo para leerlo); luego el mensaje `Uso: ...` y `exit=2`.

- [ ] **Step 6: Commit**

```bash
git add scripts/generate-token.sh inventory-service/src/test
git commit -m "feat: add demo token generation script"
```

---

### Task 4: Contrato, documentación y prueba de punta a punta

**Files:**
- Modify: `docs/contracts/openapi/inventory-service.yaml` (respuesta `Unauthorized`)
- Modify: `README.md`
- Modify: `CLAUDE.md`

**Interfaces:**
- Consumes: todo lo anterior.
- Produces: el contrato documenta `WWW-Authenticate: Bearer` en el 401; la documentación explica cómo obtener y usar tokens.

- [ ] **Step 1: Documentar la cabecera del 401 en el contrato**

En `docs/contracts/openapi/inventory-service.yaml`, en `components.responses`, reemplazar:

```yaml
    Unauthorized:
      description: Sin token, token inválido o expirado
      content:
```

por:

```yaml
    Unauthorized:
      description: Sin token, token inválido o expirado
      headers:
        WWW-Authenticate:
          description: Esquema de autenticación requerido (RFC 6750)
          schema:
            type: string
            const: Bearer
      content:
```

Run (desde la raíz): `scripts/verify-contracts.sh | tail -1`
Expected: `Contratos: OK`.

- [ ] **Step 2: Actualizar el README**

En `README.md`:

1. En la tabla de componentes, reemplazar la celda `JWT (desde la sub-tarea 4.2)` de la fila "Servicio de Inventario" por `` JWT de demo: `scripts/generate-token.sh <perfil>` ``.

2. En la sección `## Servicio de Inventario`, agregar inmediatamente después del párrafo que termina en `Sin `JWT_SECRET` válido el servicio no arranca, a propósito.`:

````markdown
Tokens de demo (bash y openssl, sin dependencias): `scripts/generate-token.sh pos` u `online` para
vender, `operator` para consultar el stock y `no-scope` para probar el 403. El script toma
`JWT_SECRET` del entorno, de `.env` o de `.env.example`, así que coincide con el Compose sin
configurar nada.

```bash
TOKEN="$(scripts/generate-token.sh operator)"
curl -H "Authorization: Bearer $TOKEN" http://localhost:8081/api/v1/inventory/TENIS-RUNNER-42
```

Las rutas no declaradas responden 401 (sin token) o 403 (con token), no 404: el servicio no revela
qué rutas existen. `/v3/api-docs` también se deniega; el contrato publicado es el de Swagger UI.
````

- [ ] **Step 3: Actualizar `CLAUDE.md`**

En `CLAUDE.md`, sección `## Comandos`, agregar al final de la lista:

```markdown
- `scripts/generate-token.sh <pos|online|operator|no-scope>` genera un JWT de demo para Inventario
  (toma `JWT_SECRET` del entorno, de `.env` o de `.env.example`).
```

- [ ] **Step 4: Ejecutar todas las pruebas del servicio**

Run (desde `inventory-service/`): `./mvnw -q -B verify`
Expected: exit 0. Surefire: `ClockConfigurationTest` 1, `InventoryPropertiesTest` 4, `GlobalExceptionHandlerTest` 5, `ProblemResponseWriterTest` 1, `ContractAssertionsTest` 3, `ArchitectureTest` 4. Failsafe: `InventoryApplicationIntegrationTest` 5, `SecurityIntegrationTest` 18, `GenerateTokenScriptIntegrationTest` 4.

- [ ] **Step 5: Prueba de punta a punta con el Compose**

Requiere los puertos 5672, 15672, 5441, 5442 y 8081 libres y ningún contenedor `retail` en marcha. Run (desde la raíz, **sin** `JWT_SECRET` en el entorno):

```bash
unset JWT_SECRET
docker compose up -d --wait --wait-timeout 300 --build; echo "up exit=$?"
OPERATOR_TOKEN="$(scripts/generate-token.sh operator)"
POS_TOKEN="$(scripts/generate-token.sh pos)"
curl -s -o /dev/null -w '%{http_code}\n' -H "Authorization: Bearer $OPERATOR_TOKEN" http://localhost:8081/api/v1/inventory/TENIS-RUNNER-42
curl -s -o /dev/null -w '%{http_code}\n' -H "Authorization: Bearer $POS_TOKEN" http://localhost:8081/api/v1/inventory/TENIS-RUNNER-42
curl -s -o /dev/null -w '%{http_code} %header{www-authenticate}\n' http://localhost:8081/api/v1/inventory/TENIS-RUNNER-42
curl -s -o /dev/null -w '%{http_code}\n' http://localhost:8081/v3/api-docs
curl -s -o /dev/null -w '%{http_code}\n' http://localhost:8081/v3/api-docs/swagger-config
curl -s -o /dev/null -w '%{http_code}\n' -H "Authorization: Bearer $(JWT_SECRET=another-secret-of-at-least-32-bytes!! scripts/generate-token.sh operator)" http://localhost:8081/api/v1/inventory/TENIS-RUNNER-42
docker compose down -v
```

Expected, en orden: `up exit=0`; `404` (el token del script, sin `JWT_SECRET`, se firmó con el secreto de `.env.example`, el contenedor lo acepta y como aún no hay controlador de stock responde `not-found`; llega en 4.3); `403` (`pos` no puede leer); `401 Bearer`; `401` (`/v3/api-docs` denegado); `200`; `401` (otro secreto). Al final, `docker ps -aq --filter name=retail` no imprime nada.

- [ ] **Step 6: Commit**

```bash
git add docs/contracts/openapi/inventory-service.yaml README.md CLAUDE.md
git commit -m "docs: document demo tokens and the 401 challenge header"
```
