package com.retail.catalog.product;

import java.time.Duration;
import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * Producto del catálogo con su vista de disponibilidad. La disponibilidad cambia solo con
 * {@link #applyStock}, por eso no tiene setters. No tiene {@code @Version}: la escribe un único
 * consumidor de eventos, que ya descarta duplicados y versiones viejas antes de llamar a
 * {@code applyStock}.
 */
@Entity
@Table(name = "products")
public class Product {

    @Id
    @Column(length = 64)
    private String sku;

    @Column(nullable = false, length = 120)
    private String name;

    @Column(nullable = false)
    private String description;

    @Column(nullable = false)
    private int price;

    @Column(nullable = false, length = 3)
    private String currency;

    @Column(name = "available_stock", nullable = false)
    private int availableStock;

    @Column(name = "stock_version", nullable = false)
    private long stockVersion;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "last_event_latency_ms")
    private Long lastEventLatencyMs;

    /** Constructor exigido por JPA. */
    protected Product() {
    }

    /** Producto nuevo, sin eventos aplicados (versión de stock 0 y sin latencia). */
    public Product(String sku, String name, String description, int price, String currency, int availableStock,
            Instant updatedAt) {
        this.sku = sku;
        this.name = name;
        this.description = description;
        this.price = price;
        this.currency = currency;
        this.availableStock = availableStock;
        this.stockVersion = 0;
        this.updatedAt = updatedAt;
    }

    /**
     * Aplica el stock absoluto de un evento. Fija el stock y la versión, {@code updatedAt = now} y la
     * latencia {@code now − occurredAt} en milisegundos (nunca negativa: si los relojes difieren, 0).
     *
     * @throws IllegalArgumentException si el stock es negativo
     */
    public void applyStock(int newStock, long newStockVersion, Instant occurredAt, Instant now) {
        if (newStock < 0) {
            throw new IllegalArgumentException("newStock must not be negative");
        }
        this.availableStock = newStock;
        this.stockVersion = newStockVersion;
        this.updatedAt = now;
        this.lastEventLatencyMs = Math.max(0, Duration.between(occurredAt, now).toMillis());
    }

    public String getSku() {
        return sku;
    }

    public String getName() {
        return name;
    }

    public String getDescription() {
        return description;
    }

    public int getPrice() {
        return price;
    }

    public String getCurrency() {
        return currency;
    }

    public int getAvailableStock() {
        return availableStock;
    }

    public long getStockVersion() {
        return stockVersion;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public Long getLastEventLatencyMs() {
        return lastEventLatencyMs;
    }
}
