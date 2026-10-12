package com.retail.inventory.outbox;

import java.util.UUID;

/**
 * Envío de un evento al broker. Es la única interfaz de mensajería del servicio: el publicador del
 * outbox no conoce RabbitMQ.
 */
public interface StockEventSender {

    /**
     * Envía el evento y bloquea hasta que el broker lo confirme. La entrega es <i>al menos una vez</i>: puede
     * haber duplicados (el confirm expira pero el mensaje sí llegó, falla el marcado como publicado tras el
     * envío, o el servicio se apaga mientras espera), por lo que los consumidores deben deduplicar por
     * {@code eventId}.
     *
     * @throws EventPublishException si el broker no lo confirmó o el mensaje no tuvo destino
     */
    void send(UUID eventId, String eventType, String payload);
}
