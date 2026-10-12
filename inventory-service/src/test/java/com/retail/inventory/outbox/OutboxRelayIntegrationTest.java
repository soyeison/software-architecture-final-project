package com.retail.inventory.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import com.retail.inventory.support.PostgresIntegrationTest;
import com.retail.inventory.support.TestTokens;

/** El publicador contra PostgreSQL real, con el emisor de RabbitMQ sustituido por un doble. */
@SpringBootTest(properties = { "inventory.jwt.secret=" + TestTokens.SECRET, "inventory.outbox.batch-size=2" })
class OutboxRelayIntegrationTest extends PostgresIntegrationTest {

    @Autowired
    private OutboxRelay relay;

    @Autowired
    private OutboxRepository outboxRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @MockitoBean
    private RabbitStockEventSender sender;

    @BeforeEach
    void emptyOutbox() {
        // Todas las pruebas comparten la base y cada venta deja una fila PENDING: sin vaciarla, el publicador
        // enviaría eventos de otras clases.
        jdbcTemplate.execute("TRUNCATE outbox");
        reset(sender);
    }

    private UUID pendingEvent(String sku) {
        UUID eventId = UUID.randomUUID();
        outboxRepository.saveAndFlush(new OutboxEvent(eventId, sku, "InventoryStockChanged", "{}",
                Instant.parse("2026-10-12T10:00:00.000Z")));
        return eventId;
    }

    private Map<String, Object> row(UUID eventId) {
        return jdbcTemplate.queryForMap("SELECT status, attempts, last_error, published_at FROM outbox"
                + " WHERE event_id = ?", eventId);
    }

    @Test
    void publishesAtMostOneBatchPerRunInOrderAndPersistsTheNewState() {
        UUID first = pendingEvent("A");
        UUID second = pendingEvent("B");
        UUID third = pendingEvent("C");
        doNothing().when(sender).send(any(), any(), any());

        int published = relay.publishPending();

        assertThat(published).isEqualTo(2);
        assertThat(row(first).get("status")).isEqualTo("PUBLISHED");
        assertThat(row(first).get("published_at")).isNotNull();
        assertThat(row(second).get("status")).isEqualTo("PUBLISHED");
        assertThat(row(third).get("status")).isEqualTo("PENDING");

        assertThat(relay.publishPending()).isEqualTo(1);
        assertThat(row(third).get("status")).isEqualTo("PUBLISHED");
    }

    @Test
    void aBrokerFailurePersistsTheAttemptAndTheErrorAndLeavesTheNextOneUntouched() {
        UUID first = pendingEvent("A");
        UUID second = pendingEvent("B");
        doThrow(new EventPublishException("Connection refused")).when(sender).send(any(), any(), any());

        assertThat(relay.publishPending()).isZero();
        assertThat(relay.publishPending()).isZero();

        assertThat(row(first).get("status")).isEqualTo("PENDING");
        assertThat(row(first).get("attempts")).isEqualTo(2);
        assertThat(row(first).get("last_error")).isEqualTo("Connection refused");
        assertThat(row(second).get("attempts")).isEqualTo(0);
    }

    @Test
    void afterTheBrokerRecoversTheEventsAreDeliveredInSequenceOrder() {
        UUID first = pendingEvent("A");
        UUID second = pendingEvent("B");
        doThrow(new EventPublishException("down")).when(sender).send(any(), any(), any());
        relay.publishPending();
        reset(sender);

        int published = relay.publishPending();

        assertThat(published).isEqualTo(2);
        List<String> statuses = jdbcTemplate.queryForList("SELECT status FROM outbox ORDER BY sequence_number",
                String.class);
        assertThat(statuses).containsExactly("PUBLISHED", "PUBLISHED");
        assertThat(row(first).get("attempts")).isEqualTo(1);
        assertThat(row(second).get("attempts")).isEqualTo(0);
    }
}
