package com.retail.inventory.outbox;

/**
 * El evento no pudo entregarse al broker (conexión caída, tiempo de espera, rechazo o mensaje devuelto
 * por no tener cola). No conoce HTTP: solo la ve el publicador del outbox.
 */
public class EventPublishException extends RuntimeException {

    public EventPublishException(String message) {
        super(message);
    }

    public EventPublishException(String message, Throwable cause) {
        super(message, cause);
    }
}
