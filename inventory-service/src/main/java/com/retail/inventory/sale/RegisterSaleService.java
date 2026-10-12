package com.retail.inventory.sale;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import com.retail.inventory.stock.Channel;
import com.retail.inventory.stock.SkuNotFoundException;
import com.retail.inventory.stock.Stock;
import com.retail.inventory.stock.StockChange;
import com.retail.inventory.stock.StockRepository;

/**
 * Caso de uso: registrar una venta. No usa {@code @Transactional}: cada intento corre en su propia
 * transacción con {@link TransactionTemplate} y el reintento envuelve la transacción (una anotación
 * en el mismo bean se ignoraría si un método se llamara a sí mismo).
 */
@Service
public class RegisterSaleService {

    private static final Logger LOGGER = LoggerFactory.getLogger(RegisterSaleService.class);

    private final StockRepository stockRepository;
    private final TransactionTemplate transactionTemplate;
    private final OptimisticRetry optimisticRetry;
    private final Clock clock;

    public RegisterSaleService(StockRepository stockRepository, TransactionTemplate transactionTemplate,
            OptimisticRetry optimisticRetry, Clock clock) {
        this.stockRepository = stockRepository;
        this.transactionTemplate = transactionTemplate;
        this.optimisticRetry = optimisticRetry;
        this.clock = clock;
    }

    /**
     * Descuenta {@code quantity} unidades del SKU.
     *
     * <p><b>No debe llamarse dentro de una transacción existente:</b> {@link TransactionTemplate} usa
     * {@code PROPAGATION_REQUIRED}, así que el primer conflicto marcaría la transacción externa como
     * solo-rollback y los reintentos no tendrían sentido.
     *
     * @throws SkuNotFoundException si el SKU no existe
     * @throws com.retail.inventory.stock.InsufficientStockException si no hay unidades suficientes
     * @throws StockConflictException si el conflicto de versión persiste tras agotar los reintentos
     */
    public StockChange register(String sku, int quantity, Channel channel) {
        try (MDC.MDCCloseable ignored = MDC.putCloseable("sku", sku)) {
            StockChange change = optimisticRetry.run(
                    () -> transactionTemplate.execute(status -> sell(sku, quantity, channel)));
            LOGGER.info("sale_registered sku={} channel={} saleId={} remaining={}", sku, channel, change.eventId(),
                    change.newStock());
            return change;
        } catch (OptimisticLockingFailureException exception) {
            LOGGER.warn("sale_conflict sku={} attempts={}", sku, optimisticRetry.maxAttempts());
            throw new StockConflictException(sku, optimisticRetry.maxAttempts(), exception);
        }
    }

    private StockChange sell(String sku, int quantity, Channel channel) {
        Stock stock = stockRepository.findById(sku).orElseThrow(() -> new SkuNotFoundException(sku));
        Instant now = clock.instant();
        stock.sell(quantity, now);
        // saveAndFlush fuerza la comprobación de versión dentro del intento y devuelve la versión posterior.
        Stock saved = stockRepository.saveAndFlush(stock);
        return new StockChange(UUID.randomUUID(), sku, saved.getQuantity(), saved.getVersion(), channel, now);
    }
}
