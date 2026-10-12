package com.retail.inventory.config;

import java.nio.charset.StandardCharsets;
import java.time.Duration;

import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * Configuración propia del servicio (prefijo {@code inventory}). Se valida al arrancar: si un valor
 * es inválido, el servicio no inicia.
 */
@Validated
@ConfigurationProperties(prefix = "inventory")
public record InventoryProperties(@Valid @NotNull Jwt jwt, @Valid @DefaultValue Sale sale,
        @Valid @DefaultValue Outbox outbox) {

    /**
     * Parámetros de la venta: intentos máximos ante conflictos de versión (bloqueo optimista).
     */
    public record Sale(@DefaultValue("5") @Min(1) int maxAttempts) {
    }

    /**
     * Parámetros del publicador del outbox.
     *
     * @param pollInterval cada cuánto busca eventos pendientes (espera entre ejecuciones)
     * @param batchSize máximo de eventos que lee por ejecución
     * @param confirmTimeout tiempo máximo de espera de la confirmación del broker
     * @param relayEnabled apaga el publicador automático (las pruebas controlan cuándo corre)
     */
    public record Outbox(@DefaultValue("500ms") Duration pollInterval, @DefaultValue("50") @Min(1) int batchSize,
            @DefaultValue("5s") Duration confirmTimeout, @DefaultValue("true") boolean relayEnabled) {
    }

    /**
     * Parámetros del JWT. El secreto llega por la variable de entorno {@code JWT_SECRET}.
     */
    public record Jwt(@NotBlank String secret, @NotBlank String issuer) {

        static final int MINIMUM_SECRET_BYTES = 32;

        /** HS256 exige un secreto de al menos 256 bits (32 bytes). */
        @AssertTrue(message = "must be at least 32 bytes long (set JWT_SECRET)")
        public boolean isSecretLongEnough() {
            return secret != null && secret.getBytes(StandardCharsets.UTF_8).length >= MINIMUM_SECRET_BYTES;
        }

        /** Oculta el secreto para que nunca aparezca en logs ni en mensajes de error. */
        @Override
        public String toString() {
            return "Jwt[secret=<oculto>, issuer=" + issuer + "]";
        }
    }
}
