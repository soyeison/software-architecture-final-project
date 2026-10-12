package com.retail.catalog.stockevent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

import com.retail.catalog.support.PostgresIntegrationTest;

@SpringBootTest
class ProcessedEventRepositoryIntegrationTest extends PostgresIntegrationTest {

    private static final Instant PROCESSED_AT = Instant.parse("2026-10-12T10:00:00.000Z");

    @Autowired
    private ProcessedEventRepository processedEventRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void emptyProcessedEvents() {
        // Solo esta tabla se vacía: la semilla de products debe quedar intacta para otras clases de prueba.
        jdbcTemplate.execute("TRUNCATE processed_events");
    }

    @Test
    void persistsAndReadsBackAProcessedEvent() {
        UUID eventId = UUID.randomUUID();

        processedEventRepository.saveAndFlush(new ProcessedEvent(eventId, PROCESSED_AT));

        assertThat(processedEventRepository.existsById(eventId)).isTrue();
        assertThat(processedEventRepository.findById(eventId).orElseThrow().getProcessedAt())
                .isEqualTo(PROCESSED_AT);
    }

    @Test
    void anUnknownEventIdIsNotProcessed() {
        assertThat(processedEventRepository.existsById(UUID.randomUUID())).isFalse();
    }

    /** La clave primaria es la red de seguridad: un eventId repetido falla en vez de sobrescribirse. */
    @Test
    void savingTheSameEventIdTwiceFailsOnThePrimaryKey() {
        UUID eventId = UUID.randomUUID();
        processedEventRepository.saveAndFlush(new ProcessedEvent(eventId, PROCESSED_AT));

        assertThatThrownBy(() -> processedEventRepository.saveAndFlush(
                new ProcessedEvent(eventId, PROCESSED_AT.plusSeconds(1))))
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}
