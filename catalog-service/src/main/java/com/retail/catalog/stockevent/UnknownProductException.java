package com.retail.catalog.stockevent;

/**
 * El evento trae un SKU que el Catálogo no conoce. Es un error <b>permanente</b> (el mensaje va a la
 * DLQ); si luego se siembra el producto, reprocesar la DLQ funciona porque el {@code eventId} no se
 * registró.
 */
public class UnknownProductException extends RuntimeException {

    public UnknownProductException(String sku) {
        super("Event refers to unknown SKU " + sku);
    }
}
