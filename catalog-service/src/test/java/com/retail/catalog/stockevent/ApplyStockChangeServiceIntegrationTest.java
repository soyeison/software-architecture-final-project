package com.retail.catalog.stockevent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import com.retail.catalog.product.Product;
import com.retail.catalog.product.ProductRepository;
import com.retail.catalog.support.PostgresIntegrationTest;

/**
 * El servicio de aplicación contra PostgreSQL real. Usa productos {@code TEST-<UUID>} y solo vacía
 * {@code processed_events}: la semilla de {@code products} debe quedar intacta para otras clases.
 */
@SpringBootTest
class ApplyStockChangeServiceIntegrationTest extends PostgresIntegrationTest {

    private static final Instant NOW = Instant.parse("2026-10-12T12:00:00.500Z");
    private static final Instant OCCURRED_AT = Instant.parse("2026-10-12T12:00:00.100Z");

    @Autowired
    private ApplyStockChangeService service;

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @MockitoBean
    private Clock clock;

    @MockitoSpyBean
    private ProcessedEventRepository processedEventRepository;

    private String sku;

    @BeforeEach
    void createATestProduct() {
        jdbcTemplate.execute("TRUNCATE processed_events");
        reset(processedEventRepository);
        when(clock.instant()).thenReturn(NOW);
        sku = "TEST-" + UUID.randomUUID().toString().toUpperCase();
        productRepository.saveAndFlush(new Product(sku, "Producto de prueba", "Descripción", 1000, "COP", 5,
                Instant.parse("2026-10-12T10:00:00.000Z")));
    }

    @AfterEach
    void deleteTheTestProduct() {
        productRepository.deleteById(sku);
    }

    private StockChangedEvent event(UUID eventId, int newStock, long stockVersion) {
        return new StockChangedEvent(eventId, sku, newStock, stockVersion, "POS", OCCURRED_AT);
    }

    private Product stored() {
        return productRepository.findById(sku).orElseThrow();
    }

    @Test
    void aNewerEventIsAppliedWithItsLatency() {
        UUID eventId = UUID.randomUUID();

        ApplyResult result = service.apply(event(eventId, 3, 2));

        assertThat(result).isEqualTo(ApplyResult.APPLIED);
        Product product = stored();
        assertThat(product.getAvailableStock()).isEqualTo(3);
        assertThat(product.getStockVersion()).isEqualTo(2L);
        assertThat(product.getUpdatedAt()).isEqualTo(NOW);
        assertThat(product.getLastEventLatencyMs()).isEqualTo(400L);
        assertThat(processedEventRepository.existsById(eventId)).isTrue();
    }

    @Test
    void theSameEventTwiceIsAppliedOnlyOnce() {
        UUID eventId = UUID.randomUUID();
        service.apply(event(eventId, 3, 2));
        when(clock.instant()).thenReturn(NOW.plusSeconds(60));

        ApplyResult result = service.apply(event(eventId, 3, 2));

        assertThat(result).isEqualTo(ApplyResult.DUPLICATE);
        assertThat(stored().getUpdatedAt()).isEqualTo(NOW);
    }

    @Test
    void anEventWithTheStoredVersionOrLowerIsStaleButItsIdIsRecorded() {
        service.apply(event(UUID.randomUUID(), 3, 5));
        UUID sameVersion = UUID.randomUUID();
        UUID olderVersion = UUID.randomUUID();

        assertThat(service.apply(event(sameVersion, 99, 5))).isEqualTo(ApplyResult.STALE);
        assertThat(service.apply(event(olderVersion, 99, 4))).isEqualTo(ApplyResult.STALE);

        Product product = stored();
        assertThat(product.getAvailableStock()).isEqualTo(3);
        assertThat(product.getStockVersion()).isEqualTo(5L);
        assertThat(processedEventRepository.existsById(sameVersion)).isTrue();
        assertThat(processedEventRepository.existsById(olderVersion)).isTrue();
    }

    /** Escenario 9: el evento de la versión 3 llega antes que el de la 2; el estado final es el de la 3. */
    @Test
    void anOutOfOrderEventDoesNotRollTheStockBack() {
        service.apply(event(UUID.randomUUID(), 2, 3));

        ApplyResult late = service.apply(event(UUID.randomUUID(), 4, 2));

        assertThat(late).isEqualTo(ApplyResult.STALE);
        assertThat(stored().getAvailableStock()).isEqualTo(2);
        assertThat(stored().getStockVersion()).isEqualTo(3L);
    }

    @Test
    void anUnknownSkuIsAPermanentErrorAndItsEventIdIsNotRecorded() {
        UUID eventId = UUID.randomUUID();
        StockChangedEvent unknown = new StockChangedEvent(eventId, "TEST-NO-EXISTE", 3, 2L, "POS", OCCURRED_AT);

        assertThatThrownBy(() -> service.apply(unknown)).isInstanceOf(UnknownProductException.class);

        assertThat(processedEventRepository.existsById(eventId)).isFalse();
    }

    @Test
    void ifRecordingTheEventFailsTheStockChangeIsRolledBack() {
        UUID eventId = UUID.randomUUID();
        doThrow(new IllegalStateException("processed_events unavailable")).when(processedEventRepository)
                .save(any());

        assertThatThrownBy(() -> service.apply(event(eventId, 3, 2))).isInstanceOf(IllegalStateException.class);

        Product product = stored();
        assertThat(product.getAvailableStock()).isEqualTo(5);
        assertThat(product.getStockVersion()).isZero();
        assertThat(product.getLastEventLatencyMs()).isNull();
        reset(processedEventRepository);
        assertThat(processedEventRepository.existsById(eventId)).isFalse();
    }

    @Test
    void aStockOfZeroIsApplied() {
        ApplyResult result = service.apply(event(UUID.randomUUID(), 0, 2));

        assertThat(result).isEqualTo(ApplyResult.APPLIED);
        assertThat(stored().getAvailableStock()).isZero();
    }
}
