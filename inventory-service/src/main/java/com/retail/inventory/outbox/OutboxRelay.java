package com.retail.inventory.outbox;

import java.time.Clock;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Component;

import com.retail.inventory.config.InventoryProperties;

/**
 * Publica los eventos pendientes del outbox en orden de {@code sequence_number}. Por cada uno envía y
 * bloquea hasta la confirmación del broker; si confirma lo marca PUBLISHED, y si falla suma el intento,
 * guarda el error, lo deja PENDING y <b>detiene el lote</b> (conserva el orden y no insiste con el resto
 * si RabbitMQ está caído). Nunca mantiene una transacción abierta durante el envío por red: cada
 * actualización de estado es una transacción corta e independiente.
 */
@Component
public class OutboxRelay {

    private static final Logger LOGGER = LoggerFactory.getLogger(OutboxRelay.class);

    private final OutboxRepository outboxRepository;
    private final StockEventSender stockEventSender;
    private final Clock clock;
    private final int batchSize;

    public OutboxRelay(OutboxRepository outboxRepository, StockEventSender stockEventSender, Clock clock,
            InventoryProperties properties) {
        this.outboxRepository = outboxRepository;
        this.stockEventSender = stockEventSender;
        this.clock = clock;
        this.batchSize = properties.outbox().batchSize();
    }

    /** @return cuántos eventos quedaron PUBLISHED en esta ejecución */
    public int publishPending() {
        List<OutboxEvent> pending = outboxRepository.findPending(Limit.of(batchSize));
        int published = 0;
        for (OutboxEvent event : pending) {
            try (MDC.MDCCloseable skuKey = MDC.putCloseable("sku", event.getSku());
                    MDC.MDCCloseable eventIdKey = MDC.putCloseable("eventId", event.getEventId().toString())) {
                try {
                    stockEventSender.send(event.getEventId(), event.getEventType(), event.getPayload());
                } catch (EventPublishException exception) {
                    event.recordFailure(exception.getMessage());
                    outboxRepository.save(event);
                    LOGGER.warn("outbox_publish_failed eventId={} attempts={} error={}", event.getEventId(),
                            event.getAttempts(), exception.getMessage());
                    return published;
                }
                event.markPublished(clock.instant());
                try {
                    outboxRepository.save(event);
                } catch (RuntimeException exception) {
                    LOGGER.warn("outbox_mark_published_failed eventId={} sku={} (will be re-sent)",
                            event.getEventId(), event.getSku(), exception);
                    throw exception;
                }
                published++;
                LOGGER.info("outbox_published eventId={} sku={}", event.getEventId(), event.getSku());
            }
        }
        return published;
    }
}
