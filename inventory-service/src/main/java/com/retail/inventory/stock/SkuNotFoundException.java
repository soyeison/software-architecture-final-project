package com.retail.inventory.stock;

/** El SKU no existe en el inventario. No conoce HTTP: el manejador global de errores la traduce. */
public class SkuNotFoundException extends RuntimeException {

    public SkuNotFoundException(String sku) {
        super("SKU " + sku + " does not exist");
    }
}
