package com.retail.catalog.stockevent;

import java.time.Duration;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.retail.catalog.config.CatalogProperties;

/**
 * Espera creciente entre reintentos de un fallo transitorio: la primera espera es {@code initialBackoff}
 * y cada intento la duplica hasta {@code maximumBackoff}. No limita el número de intentos: mientras la
 * causa persista (por ejemplo, la base caída) el listener sigue esperando y reintentando.
 */
@Component
public class BackoffPolicy {

    private final Duration initialBackoff;
    private final Duration maximumBackoff;

    @Autowired
    public BackoffPolicy(CatalogProperties properties) {
        this(properties.consumer().initialBackoff(), properties.consumer().maximumBackoff());
    }

    public BackoffPolicy(Duration initialBackoff, Duration maximumBackoff) {
        if (initialBackoff.isNegative() || initialBackoff.isZero()) {
            throw new IllegalArgumentException("initialBackoff must be positive");
        }
        if (maximumBackoff.compareTo(initialBackoff) < 0) {
            throw new IllegalArgumentException("maximumBackoff must not be shorter than initialBackoff");
        }
        this.initialBackoff = initialBackoff;
        this.maximumBackoff = maximumBackoff;
    }

    /** Espera antes de reintentar tras el fallo número {@code attempt} (el primero es 1). */
    public Duration delayAfter(int attempt) {
        if (attempt < 1) {
            throw new IllegalArgumentException("attempt must be at least 1");
        }
        Duration delay = initialBackoff;
        for (int index = 1; index < attempt && delay.compareTo(maximumBackoff) < 0; index++) {
            delay = delay.multipliedBy(2);
        }
        return delay.compareTo(maximumBackoff) > 0 ? maximumBackoff : delay;
    }
}
