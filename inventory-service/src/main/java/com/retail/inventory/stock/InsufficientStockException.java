package com.retail.inventory.stock;

/**
 * Se intentó vender más unidades de las disponibles. No conoce HTTP: el manejador global de errores
 * la traduce.
 */
public class InsufficientStockException extends RuntimeException {

    private final String sku;
    private final int availableStock;
    private final int requestedQuantity;

    public InsufficientStockException(String sku, int availableStock, int requestedQuantity) {
        super("SKU " + sku + " has " + availableStock + " units available, " + requestedQuantity + " requested");
        this.sku = sku;
        this.availableStock = availableStock;
        this.requestedQuantity = requestedQuantity;
    }

    public String getSku() {
        return sku;
    }

    public int getAvailableStock() {
        return availableStock;
    }

    public int getRequestedQuantity() {
        return requestedQuantity;
    }
}
