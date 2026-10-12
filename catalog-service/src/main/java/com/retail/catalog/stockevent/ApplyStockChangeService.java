package com.retail.catalog.stockevent;

import java.time.Clock;
import java.time.Instant;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.retail.catalog.product.Product;
import com.retail.catalog.product.ProductRepository;

/**
 * Aplica un evento de Inventario a la vista de disponibilidad. Todo ocurre en una sola transacción: si
 * algo falla, ni el stock ni el registro del {@code eventId} quedan a medias. No conoce RabbitMQ.
 *
 * <p>Los registros de {@code APPLIED} y {@code STALE} se emiten <b>después de confirmar la transacción</b>:
 * si la confirmación falla no queda en el log un "aplicado" que en realidad se revirtió. El MDC
 * ({@code sku}, {@code eventId}) lo maneja quien invoca (el listener); el mensaje ya repite ambos valores.
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
        if (processedEventRepository.existsById(event.eventId())) {
            LOGGER.info("event_duplicate sku={} eventId={}", event.sku(), event.eventId());
            return ApplyResult.DUPLICATE;
        }
        Product product = productRepository.findById(event.sku())
                .orElseThrow(() -> new UnknownProductException(event.sku()));
        Instant now = clock.instant();
        if (event.stockVersion() <= product.getStockVersion()) {
            processedEventRepository.save(new ProcessedEvent(event.eventId(), now));
            long storedVersion = product.getStockVersion();
            logAfterCommit(() -> LOGGER.info("event_stale sku={} eventId={} eventVersion={} storedVersion={}",
                    event.sku(), event.eventId(), event.stockVersion(), storedVersion));
            return ApplyResult.STALE;
        }
        int previousStock = product.getAvailableStock();
        product.applyStock(event.newStock(), event.stockVersion(), event.occurredAt(), now);
        processedEventRepository.save(new ProcessedEvent(event.eventId(), now));
        int newStock = product.getAvailableStock();
        Long latencyMs = product.getLastEventLatencyMs();
        logAfterCommit(() -> LOGGER.info("event_applied sku={} eventId={} previous={} new={} version={} latencyMs={}",
                event.sku(), event.eventId(), previousStock, newStock, event.stockVersion(), latencyMs));
        return ApplyResult.APPLIED;
    }

    private static void logAfterCommit(Runnable log) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                log.run();
            }
        });
    }
}
