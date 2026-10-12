package com.retail.inventory.stock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.Optional;

import org.junit.jupiter.api.Test;

class GetStockServiceTest {

    private final StockRepository stockRepository = mock(StockRepository.class);
    private final GetStockService service = new GetStockService(stockRepository);

    @Test
    void returnsTheStockOfAnExistingSku() {
        Stock stock = new Stock("TENIS-RUNNER-42", 5, Instant.parse("2026-10-11T10:00:00Z"));
        when(stockRepository.findById("TENIS-RUNNER-42")).thenReturn(Optional.of(stock));

        assertThat(service.getBySku("TENIS-RUNNER-42")).isSameAs(stock);
    }

    @Test
    void throwsSkuNotFoundForAnUnknownSku() {
        when(stockRepository.findById("UNKNOWN")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getBySku("UNKNOWN"))
                .isInstanceOf(SkuNotFoundException.class)
                .hasMessage("SKU UNKNOWN does not exist");
    }
}
