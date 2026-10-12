package com.retail.catalog.product;

/** El SKU no existe en el catálogo. No conoce HTTP: el manejador global de errores la traduce. */
public class ProductNotFoundException extends RuntimeException {

    public ProductNotFoundException(String sku) {
        super("SKU " + sku + " does not exist");
    }
}
