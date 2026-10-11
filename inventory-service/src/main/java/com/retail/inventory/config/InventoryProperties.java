package com.retail.inventory.config;

import java.nio.charset.StandardCharsets;

import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Configuración propia del servicio (prefijo {@code inventory}). Se valida al arrancar: si un valor
 * es inválido, el servicio no inicia.
 */
@Validated
@ConfigurationProperties(prefix = "inventory")
public record InventoryProperties(@Valid @NotNull Jwt jwt) {

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
    }
}
