package com.retail.inventory.error;

/**
 * Un campo inválido dentro de un problema {@code validation-error}.
 */
public record FieldViolation(String field, String message) {
}
