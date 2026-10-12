package com.retail.inventory.stock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;

import org.junit.jupiter.api.Test;

class StockTest {

    private static final Instant CREATED_AT = Instant.parse("2026-10-11T10:00:00Z");
    private static final Instant NOW = Instant.parse("2026-10-11T11:00:00Z");

    @Test
    void sellDeductsTheQuantityAndRefreshesTheTimestamp() {
        Stock stock = new Stock("TENIS-RUNNER-42", 5, CREATED_AT);

        stock.sell(2, NOW);

        assertThat(stock.getQuantity()).isEqualTo(3);
        assertThat(stock.getUpdatedAt()).isEqualTo(NOW);
    }

    @Test
    void sellingTheLastUnitLeavesZero() {
        Stock stock = new Stock("BALON-FUTBOL-5", 1, CREATED_AT);

        stock.sell(1, NOW);

        assertThat(stock.getQuantity()).isZero();
    }

    @Test
    void sellingMoreThanAvailableIsRejectedAndLeavesTheStockUntouched() {
        Stock stock = new Stock("TENIS-RUNNER-42", 5, CREATED_AT);

        assertThatThrownBy(() -> stock.sell(6, NOW))
                .isInstanceOfSatisfying(InsufficientStockException.class, exception -> {
                    assertThat(exception.getSku()).isEqualTo("TENIS-RUNNER-42");
                    assertThat(exception.getAvailableStock()).isEqualTo(5);
                    assertThat(exception.getRequestedQuantity()).isEqualTo(6);
                    assertThat(exception.getMessage()).isEqualTo("SKU TENIS-RUNNER-42 has 5 units available, 6 requested");
                });
        assertThat(stock.getQuantity()).isEqualTo(5);
        assertThat(stock.getUpdatedAt()).isEqualTo(CREATED_AT);
    }

    @Test
    void sellingFromEmptyStockIsRejected() {
        Stock stock = new Stock("CAMISETA-DRYFIT-M", 0, CREATED_AT);

        assertThatThrownBy(() -> stock.sell(1, NOW)).isInstanceOf(InsufficientStockException.class);
    }

    @Test
    void nonPositiveQuantitiesAreRejected() {
        Stock stock = new Stock("TENIS-RUNNER-42", 5, CREATED_AT);

        assertThatThrownBy(() -> stock.sell(0, NOW)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> stock.sell(-1, NOW)).isInstanceOf(IllegalArgumentException.class);
        assertThat(stock.getQuantity()).isEqualTo(5);
    }
}
