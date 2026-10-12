package com.retail.catalog.product;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;

import org.junit.jupiter.api.Test;

class ProductTest {

    private static final Instant SEEDED_AT = Instant.parse("2026-10-12T10:00:00Z");

    private static Product product() {
        return new Product("TENIS-RUNNER-42", "Tenis Runner talla 42", "Tenis para correr", 289900, "COP", 5,
                SEEDED_AT);
    }

    @Test
    void aNewProductHasNoEventsApplied() {
        Product product = product();

        assertThat(product.getStockVersion()).isZero();
        assertThat(product.getLastEventLatencyMs()).isNull();
        assertThat(product.getUpdatedAt()).isEqualTo(SEEDED_AT);
    }

    @Test
    void applyStockSetsStockVersionTimestampAndLatency() {
        Product product = product();
        Instant occurredAt = Instant.parse("2026-10-12T11:00:00.100Z");
        Instant now = Instant.parse("2026-10-12T11:00:00.489Z");

        product.applyStock(4, 8L, occurredAt, now);

        assertThat(product.getAvailableStock()).isEqualTo(4);
        assertThat(product.getStockVersion()).isEqualTo(8L);
        assertThat(product.getUpdatedAt()).isEqualTo(now);
        assertThat(product.getLastEventLatencyMs()).isEqualTo(389L);
    }

    @Test
    void latencyIsNeverNegativeWhenTheClocksDisagree() {
        Product product = product();
        Instant occurredAt = Instant.parse("2026-10-12T11:00:01Z");
        Instant now = Instant.parse("2026-10-12T11:00:00Z");

        product.applyStock(4, 2L, occurredAt, now);

        assertThat(product.getLastEventLatencyMs()).isZero();
    }

    @Test
    void aNegativeStockIsRejectedAndLeavesTheProductUntouched() {
        Product product = product();

        assertThatThrownBy(() -> product.applyStock(-1, 2L, SEEDED_AT, SEEDED_AT))
                .isInstanceOf(IllegalArgumentException.class);

        assertThat(product.getAvailableStock()).isEqualTo(5);
        assertThat(product.getStockVersion()).isZero();
    }

    @Test
    void stockCanDropToZero() {
        Product product = product();

        product.applyStock(0, 2L, SEEDED_AT, SEEDED_AT);

        assertThat(product.getAvailableStock()).isZero();
    }
}
