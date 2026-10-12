package com.retail.catalog.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * Propiedades propias del Catálogo (prefijo {@code catalog}). Sus valores viven en {@code application.yml}
 * y se validan al arrancar: un valor ausente o incoherente impide iniciar el servicio.
 */
@ConfigurationProperties("catalog")
@Validated
public record CatalogProperties(@NotNull @Valid Consumer consumer) {

    /**
     * Consumo de eventos de Inventario.
     *
     * @param queue          cola de la que lee el listener (la crea la infraestructura, no el servicio)
     * @param initialBackoff primera espera al reintentar un fallo transitorio
     * @param maximumBackoff tope de la espera creciente
     */
    public record Consumer(@NotBlank String queue, @NotNull Duration initialBackoff,
            @NotNull Duration maximumBackoff) {

        @AssertTrue(message = "initial-backoff must be positive")
        boolean isInitialBackoffPositive() {
            return initialBackoff == null || (!initialBackoff.isNegative() && !initialBackoff.isZero());
        }

        @AssertTrue(message = "maximum-backoff must not be shorter than initial-backoff")
        boolean isMaximumBackoffAtLeastInitialBackoff() {
            return initialBackoff == null || maximumBackoff == null || maximumBackoff.compareTo(initialBackoff) >= 0;
        }
    }
}
