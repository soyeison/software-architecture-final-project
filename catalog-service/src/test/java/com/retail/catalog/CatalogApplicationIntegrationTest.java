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
