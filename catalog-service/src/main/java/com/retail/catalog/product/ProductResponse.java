package com.retail.catalog.product;

import java.time.Instant;

/** Producto con su disponibilidad (schema {@code Product} del contrato). */
public record ProductResponse(String sku, String name, String description, int price, String currency,
        int availableStock, boolean inStock, Instant updatedAt, Long lastEventLatencyMs) {

    public static ProductResponse from(Product product) {
        return new ProductResponse(product.getSku(), product.getName(), product.getDescription(),
                product.getPrice(), product.getCurrency(), product.getAvailableStock(),
                product.getAvailableStock() > 0, product.getUpdatedAt(), product.getLastEventLatencyMs());
    }
}
