package com.retail.inventory.sale;

import java.time.Instant;
import java.util.UUID;

import com.retail.inventory.stock.Channel;
import com.retail.inventory.stock.StockChange;

/** Respuesta de la venta (schema {@code SaleResponse} del contrato). */
public record SaleResponse(UUID saleId, String sku, int quantity, Channel channel, int remainingStock,
        long version, Instant occurredAt) {

    public static SaleResponse from(SaleRequest request, StockChange change) {
        return new SaleResponse(change.eventId(), change.sku(), request.quantity(), change.channel(),
                change.newStock(), change.stockVersion(), change.occurredAt());
    }
}
