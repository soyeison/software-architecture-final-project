package com.retail.inventory.outbox;

/** Estado de un evento del outbox. */
public enum OutboxStatus {
    /** Escrito, aún no confirmado por el broker. */
    PENDING,
    /** El broker confirmó la recepción. */
    PUBLISHED
}
