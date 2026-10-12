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
