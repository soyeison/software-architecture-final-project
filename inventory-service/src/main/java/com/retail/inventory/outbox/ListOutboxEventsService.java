package com.retail.inventory.outbox;

import java.util.List;

import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Caso de uso: listar los eventos más recientes del outbox, con filtro opcional por estado. */
@Service
public class ListOutboxEventsService {

    static final int MAXIMUM_EVENTS = 50;

    private final OutboxRepository outboxRepository;

    public ListOutboxEventsService(OutboxRepository outboxRepository) {
        this.outboxRepository = outboxRepository;
    }

    /** @param status si no es nulo, solo los eventos en ese estado */
    @Transactional(readOnly = true)
    public List<OutboxEvent> list(OutboxStatus status) {
        Limit limit = Limit.of(MAXIMUM_EVENTS);
        return status == null
                ? outboxRepository.findAllByOrderBySequenceNumberDesc(limit)
                : outboxRepository.findByStatusOrderBySequenceNumberDesc(status, limit);
    }
}
