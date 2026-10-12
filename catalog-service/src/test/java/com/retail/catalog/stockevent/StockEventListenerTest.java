package com.retail.catalog.stockevent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.slf4j.MDC;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.dao.DataIntegrityViolationException;

import com.rabbitmq.client.Channel;

import jakarta.validation.Validation;
import tools.jackson.databind.json.JsonMapper;

/** Clasificación de fallos y confirmación del listener, con un servicio y un canal falsos (sin RabbitMQ). */
class StockEventListenerTest {

    private static final long DELIVERY_TAG = 7L;
    private static final UUID EVENT_ID = UUID.fromString("7f3c9a2e-5b1d-4c8e-9f0a-2d6b8e1c4a73");
    private static final StockChangedEvent EVENT = new StockChangedEvent(EVENT_ID, "TENIS-RUNNER-42", 4, 8L, "POS",
            Instant.parse("2026-10-10T15:04:05.123Z"));
    private static final String BODY = "{\"eventId\":\"" + EVENT_ID + "\",\"sku\":\"TENIS-RUNNER-42\","
            + "\"newStock\":4,\"stockVersion\":8,\"channel\":\"POS\",\"occurredAt\":\"2026-10-10T15:04:05.123Z\"}";

    private final StockEventReader reader = new StockEventReader(JsonMapper.builder().build(),
            Validation.buildDefaultValidatorFactory().getValidator());
    private final ApplyStockChangeService applyService = mock(ApplyStockChangeService.class);
    private final Channel channel = mock(Channel.class);
    // Esperas mínimas: los reintentos son reales pero no hacen lenta la prueba.
    private final StockEventListener listener = new StockEventListener(reader, applyService,
            new BackoffPolicy(Duration.ofMillis(1), Duration.ofMillis(4)));

    private static Message message(String body) {
        MessageProperties properties = new MessageProperties();
        properties.setDeliveryTag(DELIVERY_TAG);
        return new Message(body.getBytes(StandardCharsets.UTF_8), properties);
    }

    @BeforeEach
    @AfterEach
    void clearInterruptionAndMdc() {
        Thread.interrupted();
        MDC.clear();
    }

    @Test
    void anAppliedEventIsAcknowledged() throws IOException {
        when(applyService.apply(EVENT)).thenReturn(ApplyResult.APPLIED);

        listener.onMessage(message(BODY), channel);

        verify(channel).basicAck(DELIVERY_TAG, false);
        verify(channel, never()).basicNack(any(Long.class), any(Boolean.class), any(Boolean.class));
    }

    @Test
    void duplicateAndStaleEventsAreAlsoAcknowledged() throws IOException {
        when(applyService.apply(EVENT)).thenReturn(ApplyResult.DUPLICATE, ApplyResult.STALE);

        listener.onMessage(message(BODY), channel);
        listener.onMessage(message(BODY), channel);

        verify(channel, times(2)).basicAck(DELIVERY_TAG, false);
    }

    @Test
    void anUnreadableBodyIsRejectedWithoutRequeueAndNeverReachesTheService() throws IOException {
        listener.onMessage(message("not json"), channel);

        verify(channel).basicNack(DELIVERY_TAG, false, false);
        verify(channel, never()).basicAck(any(Long.class), any(Boolean.class));
        verifyNoInteractions(applyService);
    }

    @Test
    void anUnknownSkuIsRejectedWithoutRequeueAndWithoutRetrying() throws IOException {
        when(applyService.apply(EVENT)).thenThrow(new UnknownProductException("TENIS-RUNNER-42"));

        listener.onMessage(message(BODY), channel);

        verify(applyService, times(1)).apply(EVENT);
        verify(channel).basicNack(DELIVERY_TAG, false, false);
        verify(channel, never()).basicAck(any(Long.class), any(Boolean.class));
    }

    @Test
    void aTransientFailureIsRetriedUntilItSucceedsAndOnlyThenAcknowledged() throws IOException {
        when(applyService.apply(EVENT))
                .thenThrow(new IllegalStateException("database down"))
                .thenThrow(new IllegalStateException("database still down"))
                .thenReturn(ApplyResult.APPLIED);

        listener.onMessage(message(BODY), channel);

        verify(applyService, times(3)).apply(EVENT);
        verify(channel, times(1)).basicAck(DELIVERY_TAG, false);
        verify(channel, never()).basicNack(any(Long.class), any(Boolean.class), any(Boolean.class));
    }

    /** Una carrera por el mismo eventId falla por la clave primaria: se reintenta y el segundo intento ve DUPLICATE. */
    @Test
    void aDuplicateKeyRaceIsTransientAndEndsAsDuplicate() throws IOException {
        when(applyService.apply(EVENT))
                .thenThrow(new DataIntegrityViolationException("processed_events_pkey"))
                .thenReturn(ApplyResult.DUPLICATE);

        listener.onMessage(message(BODY), channel);

        verify(applyService, times(2)).apply(EVENT);
        verify(channel).basicAck(DELIVERY_TAG, false);
    }

    /** Apagado: se deja de reintentar sin confirmar ni rechazar, para que el broker devuelva el mensaje a la cola. */
    @Test
    @Timeout(value = 10, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    void aShutdownInterruptionStopsRetryingWithoutAcknowledgingNorRejecting() throws IOException {
        when(applyService.apply(EVENT)).thenThrow(new IllegalStateException("database down"));
        Thread.currentThread().interrupt();

        listener.onMessage(message(BODY), channel);

        verify(applyService, times(1)).apply(EVENT);
        verify(channel, never()).basicAck(any(Long.class), any(Boolean.class));
        verify(channel, never()).basicNack(any(Long.class), any(Boolean.class), any(Boolean.class));
        assertThat(Thread.currentThread().isInterrupted()).isTrue();
    }

    @Test
    void theMdcCarriesSkuAndEventIdDuringTheRetriesAndIsClearedAfterwards() throws IOException {
        List<String> seen = new ArrayList<>();
        when(applyService.apply(EVENT)).thenAnswer(invocation -> {
            seen.add(MDC.get("sku") + "/" + MDC.get("eventId"));
            if (seen.size() < 2) {
                throw new IllegalStateException("database down");
            }
            return ApplyResult.APPLIED;
        });

        listener.onMessage(message(BODY), channel);

        assertThat(seen).containsExactly("TENIS-RUNNER-42/" + EVENT_ID, "TENIS-RUNNER-42/" + EVENT_ID);
        assertThat(MDC.get("sku")).isNull();
        assertThat(MDC.get("eventId")).isNull();
    }

    @Test
    void ifTheAcknowledgementFailsTheErrorPropagatesAndTheMessageIsNotLost() throws IOException {
        when(applyService.apply(EVENT)).thenReturn(ApplyResult.APPLIED);
        doThrow(new IOException("channel closed")).when(channel).basicAck(DELIVERY_TAG, false);

        assertThatThrownBy(() -> listener.onMessage(message(BODY), channel)).isInstanceOf(IOException.class);
    }
}
