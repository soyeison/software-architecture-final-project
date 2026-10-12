package com.retail.inventory.stock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;

import com.retail.inventory.support.PostgresIntegrationTest;
import com.retail.inventory.support.TestTokens;

@SpringBootTest(properties = "inventory.jwt.secret=" + TestTokens.SECRET)
class StockRepositoryIntegrationTest extends PostgresIntegrationTest {

    @Autowired
    private StockRepository stockRepository;

    @Test
    void persistsAndReadsBackTheStock() {
        String sku = "TEST-" + UUID.randomUUID();
        stockRepository.saveAndFlush(new Stock(sku, 7, Instant.parse("2026-10-11T10:00:00Z")));

        Stock found = stockRepository.findById(sku).orElseThrow();

        assertThat(found.getQuantity()).isEqualTo(7);
        assertThat(found.getUpdatedAt()).isEqualTo(Instant.parse("2026-10-11T10:00:00Z"));
        assertThat(found.getVersion()).isNotNull();
    }

    /** El mecanismo del escenario 3: dos ventas concurrentes sobre la misma lectura. */
    @Test
    void secondConcurrentUpdateFailsWithOptimisticLockingFailure() {
        String sku = "TEST-" + UUID.randomUUID();
        stockRepository.saveAndFlush(new Stock(sku, 5, Instant.parse("2026-10-11T10:00:00Z")));
        Stock firstCopy = stockRepository.findById(sku).orElseThrow();
        Stock secondCopy = stockRepository.findById(sku).orElseThrow();

        firstCopy.sell(1, Instant.parse("2026-10-11T11:00:00Z"));
        stockRepository.saveAndFlush(firstCopy);
        secondCopy.sell(1, Instant.parse("2026-10-11T11:00:01Z"));

        assertThatThrownBy(() -> stockRepository.saveAndFlush(secondCopy))
                .isInstanceOf(OptimisticLockingFailureException.class);
        assertThat(stockRepository.findById(sku).orElseThrow().getQuantity()).isEqualTo(4);
    }

    @Test
    void databaseRejectsNegativeQuantityEvenIfTheCodeAllowedIt() {
        assertThatThrownBy(() -> stockRepository.saveAndFlush(
                new Stock("TEST-" + UUID.randomUUID(), -1, Instant.parse("2026-10-11T10:00:00Z"))))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void versionIncreasesOnEveryUpdate() {
        String sku = "TEST-" + UUID.randomUUID();
        Stock stock = stockRepository.saveAndFlush(new Stock(sku, 5, Instant.parse("2026-10-11T10:00:00Z")));
        long initialVersion = stock.getVersion();

        stock.sell(1, Instant.parse("2026-10-11T11:00:00Z"));
        Stock updated = stockRepository.saveAndFlush(stock);

        assertThat(updated.getVersion()).isEqualTo(initialVersion + 1);
    }
}
