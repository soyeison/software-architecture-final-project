package com.retail.inventory.outbox;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * Dispara el publicador cada {@code inventory.outbox.poll-interval} (espera fija entre ejecuciones, así
 * que nunca se solapan). Se apaga con {@code inventory.outbox.relay-enabled=false}; las pruebas lo
 * apagan para controlar cuándo corre {@link OutboxRelay}.
 */
@Configuration
@EnableScheduling
@ConditionalOnProperty(prefix = "inventory.outbox", name = "relay-enabled", havingValue = "true",
        matchIfMissing = true)
public class OutboxRelayScheduler {

    private static final Logger LOGGER = LoggerFactory.getLogger(OutboxRelayScheduler.class);

    private final OutboxRelay outboxRelay;

    public OutboxRelayScheduler(OutboxRelay outboxRelay) {
        this.outboxRelay = outboxRelay;
    }

    @Scheduled(fixedDelayString = "${inventory.outbox.poll-interval:500ms}")
    void run() {
        try {
            outboxRelay.publishPending();
        } catch (RuntimeException exception) {
            // Un fallo inesperado (p. ej., la base) no debe detener el ciclo: se reintenta en la siguiente ejecución.
            LOGGER.error("outbox_relay_failed", exception);
        }
    }
}
