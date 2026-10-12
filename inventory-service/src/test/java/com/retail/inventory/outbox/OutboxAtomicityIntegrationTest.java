package com.retail.inventory.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;

import com.retail.inventory.support.PostgresIntegrationTest;
import com.retail.inventory.support.TestStocks;
import com.retail.inventory.support.TestTokens;

/** Si el outbox falla, la venta se revierte entera: ni descuento ni versión nueva ni evento. */
@SpringBootTest(properties = "inventory.jwt.secret=" + TestTokens.SECRET)
@AutoConfigureMockMvc
class OutboxAtomicityIntegrationTest extends PostgresIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @MockitoSpyBean
    private OutboxRepository outboxRepository;

    @Test
    void outboxFailureRollsBackTheWholeSale() throws Exception {
        String sku = TestStocks.insert(jdbcTemplate, 5);
        doThrow(new IllegalStateException("outbox unavailable")).when(outboxRepository).save(any());

        mockMvc.perform(post("/api/v1/inventory/" + sku + "/sales")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + TestTokens.withScope("inventory:sell"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"quantity\": 2, \"channel\": \"POS\"}"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.detail").value("An unexpected error occurred"));

        assertThat(jdbcTemplate.queryForObject("SELECT quantity FROM stock WHERE sku = ?", Integer.class, sku))
                .isEqualTo(5);
        assertThat(jdbcTemplate.queryForObject("SELECT version FROM stock WHERE sku = ?", Long.class, sku))
                .isEqualTo(1L);
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM outbox WHERE sku = ?", Integer.class, sku))
                .isZero();
    }
}
