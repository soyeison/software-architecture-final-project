package com.retail.inventory.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;

import com.retail.inventory.support.PostgresIntegrationTest;
import com.retail.inventory.support.TestTokens;

@SpringBootTest(properties = "inventory.jwt.secret=" + TestTokens.SECRET)
class OutboxEventMappingIntegrationTest extends PostgresIntegrationTest {

    private static final Instant OCCURRED_AT = Instant.parse("2026-10-11T12:00:00.123Z");

    @Autowired
    private OutboxRepository outboxRepository;

    private static OutboxEvent newEvent(UUID eventId) {
        return new OutboxEvent(eventId, "TEST-" + UUID.randomUUID(), "InventoryStockChanged", "{\"k\":1}",
                OCCURRED_AT);
    }

    @Test
    void persistsAPendingEventWithoutAttempts() {
        UUID eventId = UUID.randomUUID();

        OutboxEvent saved = outboxRepository.saveAndFlush(newEvent(eventId));
        OutboxEvent found = outboxRepository.findById(saved.getSequenceNumber()).orElseThrow();

        assertThat(found.getEventId()).isEqualTo(eventId);
        assertThat(found.getStatus()).isEqualTo(OutboxStatus.PENDING);
        assertThat(found.getAttempts()).isZero();
        assertThat(found.getLastError()).isNull();
        assertThat(found.getPublishedAt()).isNull();
        assertThat(found.getPayload()).isEqualTo("{\"k\":1}");
        assertThat(found.getOccurredAt()).isEqualTo(OCCURRED_AT);
    }

    @Test
    void sequenceNumbersIncreaseInInsertionOrder() {
        OutboxEvent first = outboxRepository.saveAndFlush(newEvent(UUID.randomUUID()));
        OutboxEvent second = outboxRepository.saveAndFlush(newEvent(UUID.randomUUID()));

        assertThat(second.getSequenceNumber()).isGreaterThan(first.getSequenceNumber());
    }

    @Test
    void eventIdIsUnique() {
        UUID eventId = UUID.randomUUID();
        outboxRepository.saveAndFlush(newEvent(eventId));

        assertThatThrownBy(() -> outboxRepository.saveAndFlush(newEvent(eventId)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}
