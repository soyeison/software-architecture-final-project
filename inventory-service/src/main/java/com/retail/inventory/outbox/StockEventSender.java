package com.retail.inventory.outbox;

import java.util.UUID;

/**
 * Envío de un evento al broker. Es la única interfaz de mensajería del servicio: el publicador del
 * outbox no conoce RabbitMQ.
 */
public interface StockEventSender {

    /**
     * Envía el evento y bloquea hasta que el broker lo confirme.
     *
     * @throws EventPublishException si el broker no lo confirmó o el mensaje no tuvo destino
     */
    void send(UUID eventId, String eventType, String payload);
}
