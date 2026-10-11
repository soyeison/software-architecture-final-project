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
