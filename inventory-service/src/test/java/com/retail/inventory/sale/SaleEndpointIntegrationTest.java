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
