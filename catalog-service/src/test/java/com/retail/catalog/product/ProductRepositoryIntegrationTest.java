package com.retail.catalog.product;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;

import com.retail.catalog.support.PostgresIntegrationTest;

@SpringBootTest
class ProductRepositoryIntegrationTest extends PostgresIntegrationTest {

    private static final Instant SEEDED_AT = Instant.parse("2026-10-12T10:00:00.000Z");

    @Autowired
    private ProductRepository productRepository;

    private String sku;

    private Product newProduct(int availableStock) {
        sku = "TEST-" + UUID.randomUUID().toString().toUpperCase();
        return new Product(sku, "Producto de prueba", "Descripción", 1000, "COP", availableStock, SEEDED_AT);
    }

    @AfterEach
    void deleteTheTestProduct() {
        if (sku != null) {
            productRepository.deleteById(sku);
        }
    }

    @Test
    void persistsAndReadsBackTheProduct() {
        productRepository.saveAndFlush(newProduct(7));

        Product found = productRepository.findById(sku).orElseThrow();

        assertThat(found.getName()).isEqualTo("Producto de prueba");
        assertThat(found.getAvailableStock()).isEqualTo(7);
        assertThat(found.getStockVersion()).isZero();
        assertThat(found.getUpdatedAt()).isEqualTo(SEEDED_AT);
        assertThat(found.getLastEventLatencyMs()).isNull();
    }

    @Test
    void anAppliedStockChangeIsPersisted() {
        productRepository.saveAndFlush(newProduct(7));
        Product product = productRepository.findById(sku).orElseThrow();

        product.applyStock(3, 5L, Instant.parse("2026-10-12T11:00:00.000Z"), Instant.parse("2026-10-12T11:00:00.250Z"));
        productRepository.saveAndFlush(product);

        Product reloaded = productRepository.findById(sku).orElseThrow();
        assertThat(reloaded.getAvailableStock()).isEqualTo(3);
        assertThat(reloaded.getStockVersion()).isEqualTo(5L);
        assertThat(reloaded.getLastEventLatencyMs()).isEqualTo(250L);
        assertThat(reloaded.getUpdatedAt()).isEqualTo(Instant.parse("2026-10-12T11:00:00.250Z"));
    }

    @Test
    void databaseRejectsNegativeStockEvenIfTheCodeAllowedIt() {
        assertThatThrownBy(() -> productRepository.saveAndFlush(newProduct(-1)))
                .isInstanceOf(DataIntegrityViolationException.class);
        sku = null;
    }
}
