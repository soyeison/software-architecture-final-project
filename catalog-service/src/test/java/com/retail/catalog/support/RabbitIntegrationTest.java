package com.retail.catalog.support;

import java.io.IOException;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.rabbitmq.RabbitMQContainer;
import org.testcontainers.utility.MountableFile;

/**
 * Base de las pruebas con RabbitMQ real: un solo broker por ejecución, con la topología de
 * {@code infra/rabbitmq/definitions.json} (exchange, colas, bindings y usuario {@code retail}), igual
 * que en Compose.
 */
public abstract class RabbitIntegrationTest extends PostgresIntegrationTest {

    protected static final String EXCHANGE = "inventory.events";
    protected static final String ROUTING_KEY = "inventory.stock.changed";
    protected static final String QUEUE = "catalog.inventory-stock-changed";
    protected static final String DEAD_LETTER_QUEUE = "catalog.inventory-stock-changed.dlq";

    protected static final RabbitMQContainer RABBIT = new RabbitMQContainer("rabbitmq:4.3-management")
            .withCopyFileToContainer(MountableFile.forClasspathResource("infra/rabbitmq/definitions.json"),
                    "/etc/rabbitmq/definitions.json")
            .withCopyFileToContainer(MountableFile.forClasspathResource("infra/rabbitmq/rabbitmq.conf"),
                    "/etc/rabbitmq/conf.d/20-definitions.conf");

    static {
        RABBIT.start();
    }

    @DynamicPropertySource
    static void configureRabbit(DynamicPropertyRegistry registry) {
        registry.add("spring.rabbitmq.host", RABBIT::getHost);
        registry.add("spring.rabbitmq.port", RABBIT::getAmqpPort);
        registry.add("spring.rabbitmq.username", () -> "retail");
        registry.add("spring.rabbitmq.password", () -> "retail-demo");
    }

    /** Detiene la aplicación de RabbitMQ dentro del contenedor: conexiones rechazadas, puertos intactos. */
    protected static void stopBroker() throws IOException, InterruptedException {
        RABBIT.execInContainer("rabbitmqctl", "stop_app");
    }

    /** Vuelve a arrancar la aplicación de RabbitMQ; la topología ya está persistida en el nodo. */
    protected static void startBroker() throws IOException, InterruptedException {
        RABBIT.execInContainer("rabbitmqctl", "start_app");
    }
}
