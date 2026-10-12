package com.retail.inventory.outbox;

import java.util.List;

import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface OutboxRepository extends JpaRepository<OutboxEvent, Long> {

    /**
     * Los pendientes más antiguos primero. El literal {@code PENDING} (y no un parámetro) permite a
     * PostgreSQL usar el índice parcial {@code outbox_pending_index}.
     */
    @Query("select e from OutboxEvent e where e.status = com.retail.inventory.outbox.OutboxStatus.PENDING"
            + " order by e.sequenceNumber")
    List<OutboxEvent> findPending(Limit limit);
}
