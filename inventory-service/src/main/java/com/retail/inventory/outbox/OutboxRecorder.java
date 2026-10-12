package com.retail.inventory.outbox;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.retail.inventory.stock.StockChange;

/**
 * Escribe el evento en el outbox. Exige una transacción abierta ({@code MANDATORY}): la atomicidad
 * entre el cambio de stock y su evento queda impuesta por el diseño, no por la disciplina de quien llama.
 */
@Service
public class OutboxRecorder {

    private static final Logger LOGGER = LoggerFactory.getLogger(OutboxRecorder.class);

    private final OutboxRepository outboxRepository;
    private final StockEventFactory stockEventFactory;

    public OutboxRecorder(OutboxRepository outboxRepository, StockEventFactory stockEventFactory) {
        this.outboxRepository = outboxRepository;
        this.stockEventFactory = stockEventFactory;
    }

    /** @throws org.springframework.transaction.IllegalTransactionStateException si no hay transacción abierta */
    @Transactional(propagation = Propagation.MANDATORY)
    public void record(StockChange change) {
        outboxRepository.save(new OutboxEvent(change.eventId(), change.sku(), StockEventFactory.EVENT_TYPE,
                stockEventFactory.create(change), change.occurredAt()));
        LOGGER.info("outbox_recorded eventId={} sku={} stockVersion={}", change.eventId(), change.sku(),
                change.stockVersion());
    }
}
