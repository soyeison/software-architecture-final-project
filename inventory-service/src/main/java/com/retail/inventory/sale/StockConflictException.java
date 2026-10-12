package com.retail.inventory.sale;

/**
 * Se agotaron los reintentos por modificación concurrente del mismo SKU. No conoce HTTP: el manejador
 * global de errores la traduce.
 */
public class StockConflictException extends RuntimeException {

    public StockConflictException(String sku, int attempts, Throwable cause) {
        super("Stock of SKU " + sku + " changed concurrently, please retry (gave up after " + attempts
                + " attempts)", cause);
    }
}
