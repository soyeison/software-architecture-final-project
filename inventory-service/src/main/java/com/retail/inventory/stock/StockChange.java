package com.retail.inventory.stock;

import java.time.Instant;
import java.util.UUID;

/**
 * Resultado de un cambio de stock: el vocabulario común entre {@code sale} (que lo produce) y
 * {@code outbox} (que lo consume). {@code eventId} es el {@code saleId} de la respuesta y
 * {@code stockVersion} es la versión posterior al guardado.
 */
public record StockChange(UUID eventId, String sku, int newStock, long stockVersion, Channel channel,
        Instant occurredAt) {
}
