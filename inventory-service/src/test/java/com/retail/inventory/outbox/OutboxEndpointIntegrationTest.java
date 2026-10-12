package com.retail.inventory.outbox;

import static com.retail.inventory.support.ContractAssertions.assertMatchesSchema;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import com.retail.inventory.support.PostgresIntegrationTest;
import com.retail.inventory.support.TestTokens;

@SpringBootTest(properties = "inventory.jwt.secret=" + TestTokens.SECRET)
@AutoConfigureMockMvc
class OutboxEndpointIntegrationTest extends PostgresIntegrationTest {

    private static final String PATH = "/api/v1/inventory/outbox";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private OutboxRepository outboxRepository;

    @Autowired
    private JsonMapper jsonMapper;

    @BeforeEach
    void emptyOutbox() {
        // La base es compartida y cada venta deja una fila: el orden y el límite solo son deterministas con la tabla vacía.
        jdbcTemplate.execute("TRUNCATE outbox");
    }

    private UUID insert(String sku, boolean published) {
        UUID eventId = UUID.randomUUID();
        OutboxEvent event = new OutboxEvent(eventId, sku, "InventoryStockChanged", "{}",
                Instant.parse("2026-10-12T10:00:00.123Z"));
        if (published) {
            event.markPublished(Instant.parse("2026-10-12T10:00:01.456Z"));
        }
        outboxRepository.saveAndFlush(event);
        return eventId;
    }

    private JsonNode fetchJson(String path) throws Exception {
        return jsonMapper.readTree(mockMvc.perform(get(path))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andReturn().getResponse().getContentAsString());
    }

    @Test
    void listsTheMostRecentEventsFirstWithoutAnyToken() throws Exception {
        UUID oldest = insert("A", true);
        insert("B", false);
        UUID newest = insert("C", false);

        JsonNode events = fetchJson(PATH);

        assertThat(events).hasSize(3);
        assertThat(events.get(0).get("eventId").asString()).isEqualTo(newest.toString());
        assertThat(events.get(2).get("eventId").asString()).isEqualTo(oldest.toString());
        events.forEach(event -> assertMatchesSchema(event.toString(), "OutboxEvent"));
        assertThat(events.get(2).get("status").asString()).isEqualTo("PUBLISHED");
        assertThat(events.get(2).get("publishedAt").asString()).isEqualTo("2026-10-12T10:00:01.456Z");
        assertThat(events.get(0).get("publishedAt").isNull()).isTrue();
        assertThat(events.get(0).get("lastError").isNull()).isTrue();
    }

    @Test
    void returnsAtMostFiftyEvents() throws Exception {
        for (int index = 0; index < 55; index++) {
            insert("SKU-" + index, false);
        }

        assertThat(fetchJson(PATH)).hasSize(50);
    }

    @Test
    void filtersByStatus() throws Exception {
        insert("A", true);
        insert("B", false);
        insert("C", false);

        assertThat(fetchJson(PATH + "?status=PENDING")).hasSize(2);
        JsonNode published = fetchJson(PATH + "?status=PUBLISHED");
        assertThat(published).hasSize(1);
        assertThat(published.get(0).get("sku").asString()).isEqualTo("A");
    }

    @Test
    void anUnknownStatusIsAValidationError() throws Exception {
        for (String invalid : new String[] {"DONE", "pending"}) {
            String body = mockMvc.perform(get(PATH + "?status=" + invalid))
                    .andExpect(status().isBadRequest())
                    .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                    .andExpect(jsonPath("$.type").value("https://api.retail-demo.local/problems/validation-error"))
                    .andExpect(jsonPath("$.errors[0].field").value("status"))
                    .andReturn().getResponse().getContentAsString();

            assertMatchesSchema(body, "ValidationProblem");
        }
    }

    @Test
    void anEmptyOutboxIsAnEmptyList() throws Exception {
        assertThat(fetchJson(PATH)).isEmpty();
    }
}
