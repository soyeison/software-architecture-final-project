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
