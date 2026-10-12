package com.retail.catalog.stockevent;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;

import org.springframework.data.domain.Persistable;

/**
 * Un evento de Inventario ya procesado. El identificador lo asigna el productor ({@code eventId}), así
 * que la entidad implementa {@link Persistable}: Spring Data usa {@code persist} (INSERT) en lugar de
 * {@code merge}, y un {@code eventId} repetido falla por la clave primaria en vez de sobrescribirse en
 * silencio. Esa clave es la red de seguridad de la idempotencia.
 */
@Entity
@Table(name = "processed_events")
public class ProcessedEvent implements Persistable<UUID> {

    @Id
    @Column(name = "event_id")
    private UUID eventId;

    @Column(name = "processed_at", nullable = false)
    private Instant processedAt;

    @Transient
    private boolean newEntity = true;

    /** Constructor exigido por JPA. */
    protected ProcessedEvent() {
    }

    public ProcessedEvent(UUID eventId, Instant processedAt) {
        this.eventId = eventId;
        this.processedAt = processedAt;
    }

    @Override
    public UUID getId() {
        return eventId;
    }

    @Override
    public boolean isNew() {
        return newEntity;
    }

    @PostLoad
    @PostPersist
    void markNotNew() {
        this.newEntity = false;
    }

    public UUID getEventId() {
        return eventId;
    }

    public Instant getProcessedAt() {
        return processedAt;
    }
}
