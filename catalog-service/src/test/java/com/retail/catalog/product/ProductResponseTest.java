package com.retail.catalog.product;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;

import org.junit.jupiter.api.Test;

class ProductResponseTest {

    private static final Instant NOW = Instant.parse("2026-10-12T10:00:00Z");

    @Test
    void inStockIsDerivedFromTheAvailableStock() {
        Product available = new Product("A", "Producto A", "Descripción", 1000, "COP", 1, NOW);
        Product soldOut = new Product("B", "Producto B", "Descripción", 1000, "COP", 0, NOW);

        assertThat(ProductResponse.from(available).inStock()).isTrue();
        assertThat(ProductResponse.from(soldOut).inStock()).isFalse();
    }

    @Test
    void copiesTheProductFieldsAndKeepsTheLatencyNullUntilAnEventArrives() {
        Product product = new Product("TENIS-RUNNER-42", "Tenis Runner talla 42", "Tenis para correr", 289900,
                "COP", 5, NOW);

        ProductResponse response = ProductResponse.from(product);

        assertThat(response.sku()).isEqualTo("TENIS-RUNNER-42");
        assertThat(response.name()).isEqualTo("Tenis Runner talla 42");
        assertThat(response.price()).isEqualTo(289900);
        assertThat(response.currency()).isEqualTo("COP");
        assertThat(response.availableStock()).isEqualTo(5);
        assertThat(response.updatedAt()).isEqualTo(NOW);
        assertThat(response.lastEventLatencyMs()).isNull();
    }
}
