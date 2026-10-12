package com.retail.inventory.outbox;

import static com.retail.inventory.support.ContractAssertions.assertMatchesEventSchema;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import tools.jackson.databind.json.JsonMapper;

import com.retail.inventory.support.RabbitIntegrationTest;
import com.retail.inventory.support.TestStocks;
import com.retail.inventory.support.TestTokens;

/**
 * Venta → outbox → publicador automático → cola del Catálogo, con RabbitMQ real, incluida su caída
 * (escenario 8: cero eventos perdidos y en orden).
 */
@SpringBootTest(properties = { "inventory.jwt.secret=" + TestTokens.SECRET,
        "inventory.outbox.relay-enabled=true", "inventory.outbox.poll-interval=100ms" })
@AutoConfigureMockMvc
class OutboxPublishingIntegrationTest extends RabbitIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private RabbitTemplate rabbitTemplate;

    @Autowired
    private JsonMapper jsonMapper;

    @BeforeEach
    @AfterEach
    void cleanUp() throws Exception {
        startBroker();
        // Sin vaciar la tabla, el publicador enviaría eventos de otras clases a la cola de esta prueba.
        jdbcTemplate.execute("TRUNCATE outbox");
        while (rabbitTemplate.receive(QUEUE, 300) != null) {
            // vacía la cola
        }
    }

    private String sell(String sku) throws Exception {
        return mockMvc.perform(post("/api/v1/inventory/" + sku + "/sales")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + TestTokens.withScope("inventory:sell"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"quantity\": 1, \"channel\": \"POS\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
    }

    private Map<String, Object> outboxRow(String sku, long stockVersion) {
        return jdbcTemplate.queryForMap("SELECT status, attempts, last_error, published_at, payload FROM outbox"
                + " WHERE sku = ? AND (payload::jsonb ->> 'stockVersion')::bigint = ?", sku, stockVersion);
    }

    @Test
    void aSaleReachesTheCatalogQueueWithoutAnyoneCallingThePublisher() throws Exception {
        String sku = TestStocks.insert(jdbcTemplate, 5);

        String saleId = jsonMapper.readTree(sell(sku)).get("saleId").asString();

        await().atMost(Duration.ofSeconds(15)).untilAsserted(
                () -> assertThat(outboxRow(sku, 2L).get("status")).isEqualTo("PUBLISHED"));
        Message message = rabbitTemplate.receive(QUEUE, 5000);
        assertThat(message).isNotNull();
        String body = new String(message.getBody(), StandardCharsets.UTF_8);
        assertMatchesEventSchema(body);
        assertThat(message.getMessageProperties().getMessageId()).isEqualTo(saleId);
        assertThat(body).isEqualTo(outboxRow(sku, 2L).get("payload"));
        assertThat(outboxRow(sku, 2L).get("published_at")).isNotNull();
    }

    @Test
    void whenRabbitMqIsDownSalesStillWorkAndTheEventsAreDeliveredInOrderOnceItReturns() throws Exception {
        String sku = TestStocks.insert(jdbcTemplate, 5);
        stopBroker();

        sell(sku);
        sell(sku);
        sell(sku);

        // El publicador reintenta el primero (attempts crece) y no toca los siguientes.
        await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> {
            Map<String, Object> first = outboxRow(sku, 2L);
            assertThat(first.get("status")).isEqualTo("PENDING");
            assertThat((Integer) first.get("attempts")).isGreaterThanOrEqualTo(2);
            assertThat(first.get("last_error")).isNotNull();
        });
        assertThat(outboxRow(sku, 3L).get("attempts")).isEqualTo(0);
        mockMvc.perform(get("/actuator/health")).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("UP"));

        startBroker();

        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
            assertThat(outboxRow(sku, 2L).get("status")).isEqualTo("PUBLISHED");
            assertThat(outboxRow(sku, 3L).get("status")).isEqualTo("PUBLISHED");
            assertThat(outboxRow(sku, 4L).get("status")).isEqualTo("PUBLISHED");
        });
        List<Long> versions = new ArrayList<>();
        Message message;
        while ((message = rabbitTemplate.receive(QUEUE, 2000)) != null) {
            versions.add(jsonMapper.readTree(new String(message.getBody(), StandardCharsets.UTF_8))
                    .get("stockVersion").asLong());
        }
        assertThat(versions).containsExactly(2L, 3L, 4L);
    }
}
