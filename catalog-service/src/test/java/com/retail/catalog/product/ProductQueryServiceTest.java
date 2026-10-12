package com.retail.catalog.product;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Sort;

class ProductQueryServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-12T10:00:00Z");

    private final ProductRepository productRepository = mock(ProductRepository.class);
    private final ProductQueryService service = new ProductQueryService(productRepository);

    @Test
    void listsTheProductsOrderedBySku() {
        List<Product> products = List.of(new Product("A", "Producto A", "Descripción", 1000, "COP", 1, NOW));
        when(productRepository.findAll(Sort.by("sku"))).thenReturn(products);

        assertThat(service.list()).isSameAs(products);
    }

    @Test
    void returnsTheProductOfAnExistingSku() {
        Product product = new Product("A", "Producto A", "Descripción", 1000, "COP", 1, NOW);
        when(productRepository.findById("A")).thenReturn(Optional.of(product));

        assertThat(service.getBySku("A")).isSameAs(product);
    }

    @Test
    void throwsProductNotFoundForAnUnknownSku() {
        when(productRepository.findById("UNKNOWN")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getBySku("UNKNOWN"))
                .isInstanceOf(ProductNotFoundException.class)
                .hasMessage("SKU UNKNOWN does not exist");
    }
}
