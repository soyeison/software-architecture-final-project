package com.retail.inventory.stock;

import java.time.Instant;

/** Respuesta de {@code GET /api/v1/inventory/{sku}} (schema {@code StockResponse} del contrato). */
public record StockResponse(String sku, int stock, long version, Instant updatedAt) {

    public static StockResponse from(Stock stock) {
        return new StockResponse(stock.getSku(), stock.getQuantity(), stock.getVersion(), stock.getUpdatedAt());
    }
}
