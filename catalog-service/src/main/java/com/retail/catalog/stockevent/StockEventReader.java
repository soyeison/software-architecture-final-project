package com.retail.catalog.stockevent;

import java.time.Instant;
import java.util.Set;
import java.util.stream.Collectors;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;

import org.springframework.stereotype.Component;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.MapperFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * Convierte el cuerpo de un mensaje en un {@link StockChangedEvent} válido. No conoce RabbitMQ: recibe
 * los bytes. Todo lo que no sea un evento utilizable termina en {@link InvalidEventException}.
 */
@Component
public class StockEventReader {

    /**
     * Rango razonable de {@code occurredAt}. Jackson acepta instantes como {@code +300000000-01-01T00:00:00Z},
     * que luego desbordan el cálculo de duración al aplicar el evento; al ser un fallo no clasificado se
     * reintentaría para siempre y bloquearía la cola. Fuera de este rango el evento es inválido.
     */
    static final Instant EARLIEST_OCCURRED_AT = Instant.parse("2000-01-01T00:00:00Z");
    static final Instant LATEST_OCCURRED_AT = Instant.parse("2100-01-01T00:00:00Z");

    private final JsonMapper jsonMapper;
    private final Validator validator;

    public StockEventReader(JsonMapper jsonMapper, Validator validator) {
        // Lectura estricta de tipos, sin tocar el mapper global: 1.5 no se trunca a 1 ni "5" se acepta como 5.
        this.jsonMapper = jsonMapper.rebuild()
                .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
                .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS)
                .build();
        this.validator = validator;
    }

    /** @throws InvalidEventException si el cuerpo no es JSON válido o incumple las validaciones */
    public StockChangedEvent read(byte[] body) {
        if (body == null) {
            throw new InvalidEventException("The message body is empty");
        }
        StockChangedEvent event;
        try {
            event = jsonMapper.readValue(body, StockChangedEvent.class);
        } catch (JacksonException exception) {
            throw new InvalidEventException("The message body is not a valid event: " + exception.getOriginalMessage(),
                    exception);
        }
        if (event == null) {
            throw new InvalidEventException("The message body is empty");
        }
        Set<ConstraintViolation<StockChangedEvent>> violations = validator.validate(event);
        if (!violations.isEmpty()) {
            String details = violations.stream()
                    .map(violation -> violation.getPropertyPath() + " " + violation.getMessage())
                    .sorted()
                    .collect(Collectors.joining("; "));
            throw new InvalidEventException("The event has invalid fields: " + details);
        }
        if (event.occurredAt().isBefore(EARLIEST_OCCURRED_AT) || event.occurredAt().isAfter(LATEST_OCCURRED_AT)) {
            throw new InvalidEventException("The event has invalid fields: occurredAt must be between "
                    + EARLIEST_OCCURRED_AT + " and " + LATEST_OCCURRED_AT);
        }
        return event;
    }
}
