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
