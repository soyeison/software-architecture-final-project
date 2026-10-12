package com.retail.catalog.stockevent;

import java.time.Clock;
import java.time.Instant;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.retail.catalog.product.Product;
import com.retail.catalog.product.ProductRepository;

/**
 * Aplica un evento de Inventario a la vista de disponibilidad. Todo ocurre en una sola transacción: si
 * algo falla, ni el stock ni el registro del {@code eventId} quedan a medias. No conoce RabbitMQ.
 */
@Service
public class ApplyStockChangeService {

    private static final Logger LOGGER = LoggerFactory.getLogger(ApplyStockChangeService.class);

    private final ProductRepository productRepository;
    private final ProcessedEventRepository processedEventRepository;
    private final Clock clock;

    public ApplyStockChangeService(ProductRepository productRepository,
            ProcessedEventRepository processedEventRepository, Clock clock) {
        this.productRepository = productRepository;
        this.processedEventRepository = processedEventRepository;
        this.clock = clock;
    }

    /**
     * @throws UnknownProductException si el SKU no existe (error permanente; no registra el {@code eventId})
     */
    @Transactional
    public ApplyResult apply(StockChangedEvent event) {
        try (MDC.MDCCloseable skuKey = MDC.putCloseable("sku", event.sku());
                MDC.MDCCloseable eventIdKey = MDC.putCloseable("eventId", event.eventId().toString())) {
            if (processedEventRepository.existsById(event.eventId())) {
                LOGGER.info("event_duplicate sku={} eventId={}", event.sku(), event.eventId());
                return ApplyResult.DUPLICATE;
            }
            Product product = productRepository.findById(event.sku())
                    .orElseThrow(() -> new UnknownProductException(event.sku()));
            Instant now = clock.instant();
            if (event.stockVersion() <= product.getStockVersion()) {
                processedEventRepository.save(new ProcessedEvent(event.eventId(), now));
                LOGGER.info("event_stale sku={} eventId={} eventVersion={} storedVersion={}", event.sku(),
                        event.eventId(), event.stockVersion(), product.getStockVersion());
                return ApplyResult.STALE;
            }
            int previousStock = product.getAvailableStock();
            product.applyStock(event.newStock(), event.stockVersion(), event.occurredAt(), now);
            processedEventRepository.save(new ProcessedEvent(event.eventId(), now));
            LOGGER.info("event_applied sku={} eventId={} previous={} new={} version={} latencyMs={}", event.sku(),
                    event.eventId(), previousStock, product.getAvailableStock(), product.getStockVersion(),
                    product.getLastEventLatencyMs());
            return ApplyResult.APPLIED;
        }
    }
}
