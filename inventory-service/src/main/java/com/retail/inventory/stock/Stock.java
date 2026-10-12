package com.retail.inventory.stock;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

/**
 * Stock actual de un SKU. Contiene la regla de negocio de descontar unidades; el estado solo cambia
 * mediante {@link #sell}, por eso no tiene setters. {@code version} es el bloqueo optimista: si dos
 * transacciones modifican el mismo registro, la segunda en guardar falla.
 */
@Entity
@Table(name = "stock")
public class Stock {

    @Id
    @Column(length = 64)
    private String sku;

    @Column(nullable = false)
    private int quantity;

    /** Nulo hasta el primer guardado; JPA lo asigna y lo incrementa en cada actualización. */
    @Version
    private Long version;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /** Constructor exigido por JPA. */
    protected Stock() {
    }

    public Stock(String sku, int quantity, Instant updatedAt) {
        this.sku = sku;
        this.quantity = quantity;
        this.updatedAt = updatedAt;
    }

    /**
     * Descuenta unidades del stock.
     *
     * @throws IllegalArgumentException si la cantidad no es positiva
     * @throws InsufficientStockException si no hay unidades suficientes
     */
    public void sell(int quantityToSell, Instant now) {
        if (quantityToSell < 1) {
            throw new IllegalArgumentException("quantity must be at least 1");
        }
        if (quantityToSell > quantity) {
            throw new InsufficientStockException(sku, quantity, quantityToSell);
        }
        quantity -= quantityToSell;
        updatedAt = now;
    }

    public String getSku() {
        return sku;
    }

    public int getQuantity() {
        return quantity;
    }

    public Long getVersion() {
        return version;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
