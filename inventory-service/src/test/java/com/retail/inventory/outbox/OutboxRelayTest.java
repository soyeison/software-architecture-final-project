package com.retail.inventory.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Limit;

import com.retail.inventory.config.InventoryProperties;

class OutboxRelayTest {

    private static final Instant NOW = Instant.parse("2026-10-12T10:00:00Z");

    private final OutboxRepository outboxRepository = mock(OutboxRepository.class);
    private final FakeStockEventSender sender = new FakeStockEventSender();
    private final InventoryProperties properties = new InventoryProperties(
            new InventoryProperties.Jwt("0123456789abcdef0123456789abcdef", "retail-demo-auth"),
            new InventoryProperties.Sale(5),
            new InventoryProperties.Outbox(java.time.Duration.ofMillis(500), 50, java.time.Duration.ofSeconds(5), true));
    private final OutboxRelay relay = new OutboxRelay(outboxRepository, sender, Clock.fixed(NOW, ZoneOffset.UTC),
            properties);

    private static OutboxEvent event(String sku) {
        return new OutboxEvent(UUID.randomUUID(), sku, "InventoryStockChanged", "{}", NOW);
    }

    @Test
    void publishesThePendingEventsInOrderAndMarksThemPublished() {
        OutboxEvent first = event("A");
        OutboxEvent second = event("B");
        when(outboxRepository.findPending(any(Limit.class))).thenReturn(List.of(first, second));

        int published = relay.publishPending();

        assertThat(published).isEqualTo(2);
        assertThat(sender.sent).containsExactly(first.getEventId(), second.getEventId());
        assertThat(first.getStatus()).isEqualTo(OutboxStatus.PUBLISHED);
        assertThat(first.getPublishedAt()).isEqualTo(NOW);
        assertThat(second.getStatus()).isEqualTo(OutboxStatus.PUBLISHED);
        verify(outboxRepository).save(first);
        verify(outboxRepository).save(second);
    }

    @Test
    void aFailureCountsTheAttemptKeepsTheEventPendingAndStopsTheBatch() {
        OutboxEvent first = event("A");
        OutboxEvent second = event("B");
        when(outboxRepository.findPending(any(Limit.class))).thenReturn(List.of(first, second));
        sender.failing = true;

        int published = relay.publishPending();

        assertThat(published).isZero();
        assertThat(first.getStatus()).isEqualTo(OutboxStatus.PENDING);
        assertThat(first.getAttempts()).isEqualTo(1);
        assertThat(first.getLastError()).isEqualTo("Connection refused");
        assertThat(second.getAttempts()).isZero();
        assertThat(sender.sent).isEmpty();
        verify(outboxRepository).save(first);
        verify(outboxRepository, never()).save(second);
    }

    @Test
    void afterRecoveringItPublishesThePendingEventsInOrder() {
        OutboxEvent first = event("A");
        OutboxEvent second = event("B");
        when(outboxRepository.findPending(any(Limit.class))).thenReturn(List.of(first, second));
        sender.failing = true;
        relay.publishPending();
        relay.publishPending();
        assertThat(first.getAttempts()).isEqualTo(2);

        sender.failing = false;
        int published = relay.publishPending();

        assertThat(published).isEqualTo(2);
        assertThat(sender.sent).containsExactly(first.getEventId(), second.getEventId());
        assertThat(first.getStatus()).isEqualTo(OutboxStatus.PUBLISHED);
        assertThat(first.getAttempts()).isEqualTo(2);
    }

    @Test
    void doesNothingWhenThereAreNoPendingEvents() {
        when(outboxRepository.findPending(any(Limit.class))).thenReturn(List.of());

        assertThat(relay.publishPending()).isZero();
        assertThat(sender.sent).isEmpty();
    }

    @Test
    void theStoredErrorIsBoundedSoAStackTraceNeverFillsTheColumn() {
        OutboxEvent event = event("A");

        event.recordFailure("x".repeat(2000));

        assertThat(event.getLastError()).hasSize(500);
    }
}
