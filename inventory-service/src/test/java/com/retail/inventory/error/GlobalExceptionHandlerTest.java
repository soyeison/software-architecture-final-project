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
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Prueba solo el manejo de errores: los filtros de seguridad se desactivan y la seguridad se prueba
 * aparte (spec de Inventario §5).
 */
@WebMvcTest(controllers = ProbeController.class)
@AutoConfigureMockMvc(addFilters = false)
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
    void exhaustedRetriesReturnConcurrentModificationConflict() throws Exception {
        String body = mockMvc.perform(get("/probe/conflict"))
                .andExpect(status().isConflict())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.type").value(PROBLEM_TYPE_BASE + "concurrent-modification"))
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
}
