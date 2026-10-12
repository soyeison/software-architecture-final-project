package com.retail.catalog.stockevent;

import java.util.Set;
import java.util.stream.Collectors;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;

import org.springframework.stereotype.Component;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/**
 * Convierte el cuerpo de un mensaje en un {@link StockChangedEvent} válido. No conoce RabbitMQ: recibe
 * los bytes. Todo lo que no sea un evento utilizable termina en {@link InvalidEventException}.
 */
@Component
public class StockEventReader {

    private final JsonMapper jsonMapper;
    private final Validator validator;

    public StockEventReader(JsonMapper jsonMapper, Validator validator) {
        this.jsonMapper = jsonMapper;
        this.validator = validator;
    }

    /** @throws InvalidEventException si el cuerpo no es JSON válido o incumple las validaciones */
    public StockChangedEvent read(byte[] body) {
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
        return event;
    }
}
