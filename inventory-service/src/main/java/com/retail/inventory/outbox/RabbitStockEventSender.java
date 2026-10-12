package com.retail.inventory.outbox;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.springframework.amqp.AmqpException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

import com.retail.inventory.config.InventoryProperties;

/**
 * Envía los bytes del JSON tal cual al exchange {@code inventory.events}. Solo cuenta como enviado si el
 * broker confirma (<i>publisher confirms</i>) y el mensaje no fue devuelto por no tener cola
 * ({@code mandatory}). La entrega es al menos una vez: puede haber duplicados (confirm expirado con el
 * mensaje ya entregado, marcado fallido tras el envío, apagado durante la espera); los consumidores deben
 * deduplicar por {@code eventId}. Es la única clase que usa {@code org.springframework.amqp}.
 */
@Component
public class RabbitStockEventSender implements StockEventSender {

    static final String EXCHANGE = "inventory.events";
    static final String ROUTING_KEY = "inventory.stock.changed";

    private final RabbitTemplate rabbitTemplate;
    private final Duration confirmTimeout;

    public RabbitStockEventSender(RabbitTemplate rabbitTemplate, InventoryProperties properties) {
        this.rabbitTemplate = rabbitTemplate;
        this.confirmTimeout = properties.outbox().confirmTimeout();
    }

    @Override
    public void send(UUID eventId, String eventType, String payload) {
        MessageProperties messageProperties = new MessageProperties();
        messageProperties.setMessageId(eventId.toString());
        messageProperties.setType(eventType);
        messageProperties.setContentType(MessageProperties.CONTENT_TYPE_JSON);
        messageProperties.setDeliveryMode(MessageDeliveryMode.PERSISTENT);
        Message message = new Message(payload.getBytes(StandardCharsets.UTF_8), messageProperties);
        CorrelationData correlationData = new CorrelationData(eventId.toString());
        try {
            rabbitTemplate.send(EXCHANGE, ROUTING_KEY, message, correlationData);
            CorrelationData.Confirm confirm = correlationData.getFuture()
                    .get(confirmTimeout.toMillis(), TimeUnit.MILLISECONDS);
            if (!confirm.isAck()) {
                throw new EventPublishException("Broker rejected the message: " + confirm.getReason());
            }
            // El broker devuelve el mensaje (basic.return) antes de confirmarlo si no hay cola destino.
            if (correlationData.getReturned() != null) {
                throw new EventPublishException("Message returned by the broker: "
                        + correlationData.getReturned().getReplyText());
            }
        } catch (AmqpException exception) {
            throw new EventPublishException("Could not send the message: " + exception.getMessage(), exception);
        } catch (TimeoutException exception) {
            throw new EventPublishException("The broker did not confirm within " + confirmTimeout, exception);
        } catch (ExecutionException exception) {
            throw new EventPublishException("Confirmation failed: " + exception.getMessage(), exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new EventPublishException("Interrupted while waiting for the broker confirmation", exception);
        }
    }
}
