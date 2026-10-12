package com.retail.inventory.outbox;

import java.time.Instant;
import java.util.UUID;

/** Un evento del outbox para diagnóstico (schema {@code OutboxEvent} del contrato). */
public record OutboxEventResponse(UUID eventId, String sku, OutboxStatus status, int attempts, String lastError,
        Instant occurredAt, Instant publishedAt) {

    public static OutboxEventResponse from(OutboxEvent event) {
        return new OutboxEventResponse(event.getEventId(), event.getSku(), event.getStatus(), event.getAttempts(),
                event.getLastError(), event.getOccurredAt(), event.getPublishedAt());
    }
}
