package com.retail.inventory.outbox;

import static com.retail.inventory.support.ContractAssertions.assertMatchesEventSchema;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.IllegalTransactionStateException;

import tools.jackson.databind.json.JsonMapper;

import com.retail.inventory.stock.Channel;
import com.retail.inventory.stock.StockChange;
import com.retail.inventory.support.PostgresIntegrationTest;
import com.retail.inventory.support.TestStocks;
import com.retail.inventory.support.TestTokens;

/** La venta y su evento nacen juntos: una venta confirmada deja exactamente un evento PENDING. */
@SpringBootTest(properties = "inventory.jwt.secret=" + TestTokens.SECRET)
@AutoConfigureMockMvc
class OutboxRecordingIntegrationTest extends PostgresIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private OutboxRecorder outboxRecorder;

    @Autowired
    private JsonMapper jsonMapper;

    private String sell(String sku, int quantity, int expectedStatus) throws Exception {
        return mockMvc.perform(post("/api/v1/inventory/" + sku + "/sales")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + TestTokens.withScope("inventory:sell"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"quantity\": " + quantity + ", \"channel\": \"ONLINE\"}"))
                .andExpect(status().is(expectedStatus))
                .andReturn().getResponse().getContentAsString();
    }

    private List<Map<String, Object>> outboxRows(String sku) {
        return jdbcTemplate.queryForList(
                "SELECT event_id, event_type, payload, status, attempts, last_error, published_at FROM outbox"
                        + " WHERE sku = ? ORDER BY sequence_number", sku);
    }

    @Test
    void confirmedSaleLeavesExactlyOnePendingEventWithTheSaleIdAndAValidPayload() throws Exception {
        String sku = TestStocks.insert(jdbcTemplate, 5);

        String saleId = jsonMapper.readTree(sell(sku, 2, 201)).get("saleId").asString();

        List<Map<String, Object>> rows = outboxRows(sku);
        assertThat(rows).hasSize(1);
        Map<String, Object> row = rows.get(0);
        assertThat(row.get("event_id").toString()).isEqualTo(saleId);
        assertThat(row.get("event_type")).isEqualTo("InventoryStockChanged");
        assertThat(row.get("status")).isEqualTo("PENDING");
        assertThat(row.get("attempts")).isEqualTo(0);
        assertThat(row.get("last_error")).isNull();
        assertThat(row.get("published_at")).isNull();
        String payload = (String) row.get("payload");
        assertMatchesEventSchema(payload);
        assertThat(jsonMapper.readTree(payload).get("eventId").asString()).isEqualTo(saleId);
        assertThat(jsonMapper.readTree(payload).get("newStock").asInt()).isEqualTo(3);
        assertThat(jsonMapper.readTree(payload).get("stockVersion").asLong()).isEqualTo(2L);
        assertThat(jsonMapper.readTree(payload).get("channel").asString()).isEqualTo("ONLINE");
    }

    @Test
    void salesOfTheSameSkuAreRecordedInSaleOrder() throws Exception {
        String sku = TestStocks.insert(jdbcTemplate, 5);

        sell(sku, 1, 201);
        sell(sku, 1, 201);
        sell(sku, 1, 201);

        List<Long> versions = outboxRows(sku).stream()
                .map(row -> jsonMapper.readTree((String) row.get("payload")).get("stockVersion").asLong())
                .toList();
        assertThat(versions).containsExactly(2L, 3L, 4L);
    }

    @Test
    void rejectedSalesLeaveNoEvent() throws Exception {
        String sku = TestStocks.insert(jdbcTemplate, 1);

        sell(sku, 2, 409);
        mockMvc.perform(post("/api/v1/inventory/" + sku + "/sales")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + TestTokens.withScope("inventory:sell"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"quantity\": 0, \"channel\": \"POS\"}"))
                .andExpect(status().isBadRequest());
        sell("DOES-NOT-EXIST", 1, 404);

        assertThat(outboxRows(sku)).isEmpty();
        assertThat(outboxRows("DOES-NOT-EXIST")).isEmpty();
    }

    @Test
    void recordingWithoutAnOpenTransactionFailsImmediately() {
        StockChange change = new StockChange(UUID.randomUUID(), "TEST-" + UUID.randomUUID(), 1, 2L, Channel.POS,
                Instant.parse("2026-10-11T12:00:00.000Z"));

        assertThatThrownBy(() -> outboxRecorder.record(change)).isInstanceOf(IllegalTransactionStateException.class);
        assertThat(outboxRows(change.sku())).isEmpty();
    }
}
