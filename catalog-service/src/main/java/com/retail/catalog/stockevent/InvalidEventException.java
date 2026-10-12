package com.retail.catalog.stockevent;

/**
 * El mensaje no es un evento utilizable (JSON corrupto o campo faltante o inválido). Es un error
 * <b>permanente</b>: reintentar no lo arregla, así que el consumidor lo manda a la DLQ.
 */
public class InvalidEventException extends RuntimeException {

    public InvalidEventException(String message) {
        super(message);
    }

    public InvalidEventException(String message, Throwable cause) {
        super(message, cause);
    }
}
