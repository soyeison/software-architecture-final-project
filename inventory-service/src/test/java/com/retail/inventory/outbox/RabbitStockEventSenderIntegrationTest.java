package com.retail.inventory.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import com.retail.inventory.support.RabbitIntegrationTest;
import com.retail.inventory.support.TestTokens;

@SpringBootTest(properties = "inventory.jwt.secret=" + TestTokens.SECRET)
class RabbitStockEventSenderIntegrationTest extends RabbitIntegrationTest {

    private static final String PAYLOAD = "{\"eventId\":\"x\",\"eventType\":\"InventoryStockChanged\"}";

    @Autowired
    private RabbitStockEventSender sender;

    @Autowired
    private RabbitTemplate rabbitTemplate;

    @AfterEach
    void restartBrokerAndDrainQueue() throws Exception {
        startBroker();
        while (rabbitTemplate.receive(QUEUE, 200) != null) {
            // vacía la cola para no contaminar otras pruebas
        }
    }

    @Test
    void deliversTheExactBodyWithItsPropertiesToTheCatalogQueue() {
        UUID eventId = UUID.randomUUID();

        sender.send(eventId, "InventoryStockChanged", PAYLOAD);

        Message message = rabbitTemplate.receive(QUEUE, 5000);
        assertThat(message).isNotNull();
        assertThat(new String(message.getBody(), StandardCharsets.UTF_8)).isEqualTo(PAYLOAD);
        assertThat(message.getMessageProperties().getMessageId()).isEqualTo(eventId.toString());
        assertThat(message.getMessageProperties().getType()).isEqualTo("InventoryStockChanged");
        assertThat(message.getMessageProperties().getContentType()).isEqualTo("application/json");
        assertThat(message.getMessageProperties().getReceivedDeliveryMode()).isEqualTo(MessageDeliveryMode.PERSISTENT);
    }

    /** Sin cola enlazada a la clave, el broker devuelve el mensaje: no debe contarse como enviado. */
    @Test
    void aMessageReturnedForHavingNoQueueIsNotCountedAsSent() {
        RabbitAdmin admin = new RabbitAdmin(rabbitTemplate);
        Binding binding = BindingBuilder.bind(new Queue(QUEUE)).to(new TopicExchange("inventory.events"))
                .with("inventory.stock.changed");
        admin.removeBinding(binding);
        try {
            assertThatThrownBy(() -> sender.send(UUID.randomUUID(), "InventoryStockChanged", PAYLOAD))
                    .isInstanceOf(EventPublishException.class)
                    .hasMessageContaining("returned");
        } finally {
            admin.declareBinding(binding);
        }
    }

    @Test
    void failsWhileTheBrokerIsDownAndWorksAgainWhenItReturns() throws Exception {
        stopBroker();

        assertThatThrownBy(() -> sender.send(UUID.randomUUID(), "InventoryStockChanged", PAYLOAD))
                .isInstanceOf(EventPublishException.class);

        startBroker();
        sender.send(UUID.randomUUID(), "InventoryStockChanged", PAYLOAD);
        assertThat(rabbitTemplate.receive(QUEUE, 5000)).isNotNull();
    }
}
