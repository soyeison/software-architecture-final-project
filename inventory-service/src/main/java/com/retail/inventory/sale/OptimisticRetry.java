package com.retail.inventory.sale;

import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.OptimisticLockingFailureException;

/**
 * Repite una operación cuando falla por bloqueo optimista (otra transacción cambió el registro entre
 * la lectura y el guardado). Solo reintenta ese conflicto: cualquier otra excepción se propaga sin
 * reintentos. La operación debe abrir su propia transacción en cada intento.
 */
public class OptimisticRetry {

    private static final Logger LOGGER = LoggerFactory.getLogger(OptimisticRetry.class);

    private final int maxAttempts;

    public OptimisticRetry(int maxAttempts) {
        if (maxAttempts < 1) {
            throw new IllegalArgumentException("maxAttempts must be at least 1");
        }
        this.maxAttempts = maxAttempts;
    }

    /**
     * Ejecuta la operación hasta {@code maxAttempts} veces.
     *
     * @throws OptimisticLockingFailureException la última, si se agotaron los intentos
     */
    public <T> T run(Supplier<T> operation) {
        int attempt = 1;
        while (true) {
            try {
                return operation.get();
            } catch (OptimisticLockingFailureException exception) {
                if (attempt >= maxAttempts) {
                    throw exception;
                }
                LOGGER.warn("optimistic_lock_retry attempt={} maxAttempts={}", attempt, maxAttempts);
                attempt++;
            }
        }
    }

    public int maxAttempts() {
        return maxAttempts;
    }
}
