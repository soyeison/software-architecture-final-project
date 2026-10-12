package com.retail.catalog.stockevent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.springframework.amqp.core.AcknowledgeMode;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageListenerContainer;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.listener.RabbitListenerEndpointRegistry;
import org.springframework.amqp.rabbit.listener.SimpleMessageListenerContainer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;

import com.retail.catalog.product.Product;
import com.retail.catalog.product.ProductRepository;
import com.retail.catalog.support.RabbitIntegrationTest;

/**
 * El listener contra RabbitMQ y PostgreSQL reales, con la topología de {@code infra/rabbitmq}. Usa productos
 * {@code TEST-<UUID>} y solo vacía {@code processed_events}. El contexto tiene el listener vivo, así que no
 * sobrevive a la clase ({@code @DirtiesContext}). Esperas de reintento cortas para no alargar las pruebas.
 */
@SpringBootTest(properties = {
        "spring.rabbitmq.listener.simple.auto-startup=true",
        "catalog.consumer.initial-backoff=50ms",
        "catalog.consumer.maximum-backoff=200ms" })
@AutoConfigureMockMvc
@ExtendWith(OutputCaptureExtension.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class StockEventListenerIntegrationTest extends RabbitIntegrationTest {

    private static final Duration PATIENCE = Duration.ofSeconds(20);

    @Autowired
    private RabbitTemplate rabbitTemplate;

    @Autowired
    private RabbitAdmin rabbitAdmin;

    @Autowired
    private RabbitListenerEndpointRegistry registry;

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private ProcessedEventRepository processedEventRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private MockMvc mockMvc;

    @MockitoSpyBean
    private ApplyStockChangeService applyService;

    private String sku;

    @BeforeEach
    void createATestProduct() {
        reset(applyService);
        jdbcTemplate.execute("TRUNCATE processed_events");
        rabbitAdmin.purgeQueue(QUEUE, false);
        rabbitAdmin.purgeQueue(DEAD_LETTER_QUEUE, false);
        sku = "TEST-" + UUID.randomUUID().toString().toUpperCase();
        productRepository.saveAndFlush(new Product(sku, "Producto de prueba", "Descripción", 1000, "COP", 5,
                Instant.parse("2026-10-12T10:00:00.000Z")));
        container().start();
    }

    @AfterEach
    void restoreBrokerAndCleanUp() throws Exception {
        startBroker();
        reset(applyService);
        container().start();
        // Que no quede ningun residual (en cola o en vuelo) que llegue a la DLQ en la prueba siguiente.
        await().atMost(PATIENCE).ignoreExceptions().untilAsserted(() -> assertThat(queueDepth(QUEUE)).isZero());
        // Detener el contenedor espera a los hilos en curso; lo no confirmado volveria a la cola.
        container().stop();
        await().atMost(PATIENCE).ignoreExceptions().untilAsserted(() -> assertThat(queueDepth(QUEUE)).isZero());
        productRepository.deleteById(sku);
    }

    private MessageListenerContainer container() {
        return registry.getListenerContainer(StockEventListener.LISTENER_ID);
    }

    private static String eventJson(UUID eventId, String sku, int newStock, long stockVersion, String extra) {
        return "{\"eventId\":\"%s\",\"eventType\":\"InventoryStockChanged\",\"sku\":\"%s\",\"newStock\":%d,"
                .formatted(eventId, sku, newStock)
                + "\"stockVersion\":%d,\"channel\":\"POS\",\"occurredAt\":\"%s\"%s}".formatted(stockVersion,
                        Instant.now().minusMillis(30), extra);
    }

    private void publish(String body) {
        MessageProperties properties = new MessageProperties();
        properties.setContentType("application/json");
        properties.setDeliveryMode(MessageDeliveryMode.PERSISTENT);
        rabbitTemplate.send(EXCHANGE, ROUTING_KEY, new Message(body.getBytes(StandardCharsets.UTF_8), properties));
    }

    private Product stored() {
        return productRepository.findById(sku).orElseThrow();
    }

    private int queueDepth(String queue) {
        return (int) rabbitAdmin.getQueueInfo(queue).getMessageCount();
    }

    /** La corrección de ApplyStockChangeService depende de esta configuración (ver application.yml). */
    @Test
    void theListenerRunsWithManualAcknowledgementOneConsumerAndPrefetchOne() {
        SimpleMessageListenerContainer simple = (SimpleMessageListenerContainer) container();

        assertThat(simple.getAcknowledgeMode()).isEqualTo(AcknowledgeMode.MANUAL);
        assertThat(ReflectionTestUtils.getField(simple, "concurrentConsumers")).isEqualTo(1);
        assertThat(ReflectionTestUtils.getField(simple, "maxConcurrentConsumers")).isIn(1, null);
        assertThat(ReflectionTestUtils.getField(simple, "prefetchCount")).isEqualTo(1);
    }

    @Test
    void aPublishedEventIsAppliedAndAcknowledged() {
        UUID eventId = UUID.randomUUID();

        publish(eventJson(eventId, sku, 3, 1, ""));

        await().atMost(PATIENCE).untilAsserted(() -> assertThat(stored().getAvailableStock()).isEqualTo(3));
        assertThat(stored().getStockVersion()).isEqualTo(1L);
        assertThat(stored().getLastEventLatencyMs()).isNotNull();
        assertThat(processedEventRepository.existsById(eventId)).isTrue();
        await().atMost(PATIENCE).untilAsserted(() -> assertThat(queueDepth(QUEUE)).isZero());
        assertThat(queueDepth(DEAD_LETTER_QUEUE)).isZero();
    }

    @Test
    void anUnknownExtraFieldInTheEventIsTolerated() {
        publish(eventJson(UUID.randomUUID(), sku, 2, 1, ",\"region\":\"CO-ANT\""));

        await().atMost(PATIENCE).untilAsserted(() -> assertThat(stored().getAvailableStock()).isEqualTo(2));
    }

    /** Escenario 5: con el consumidor detenido los eventos se acumulan; al volver se procesan todos y en orden. */
    @Test
    void eventsPiledUpWhileTheConsumerIsStoppedAreProcessedInOrderWhenItReturns() {
        container().stop();
        for (int version = 1; version <= 4; version++) {
            publish(eventJson(UUID.randomUUID(), sku, 5 - version, version, ""));
        }
        await().atMost(PATIENCE).untilAsserted(() -> assertThat(queueDepth(QUEUE)).isEqualTo(4));
        assertThat(stored().getStockVersion()).isZero();

        container().start();

        await().atMost(PATIENCE).untilAsserted(() -> assertThat(stored().getStockVersion()).isEqualTo(4L));
        assertThat(stored().getAvailableStock()).isEqualTo(1);
        ArgumentCaptor<StockChangedEvent> applied = ArgumentCaptor.forClass(StockChangedEvent.class);
        verify(applyService, times(4)).apply(applied.capture());
        assertThat(applied.getAllValues()).extracting(StockChangedEvent::stockVersion).containsExactly(1L, 2L, 3L, 4L);
        await().atMost(PATIENCE).untilAsserted(() -> assertThat(queueDepth(QUEUE)).isZero());
    }

    /** Escenario 9: la versión 3 llega antes que la 2; el estado final es el de la 3 y no hay mensajes perdidos. */
    @Test
    void anOutOfOrderEventDoesNotRollTheStockBack() {
        UUID third = UUID.randomUUID();
        UUID second = UUID.randomUUID();

        publish(eventJson(third, sku, 2, 3, ""));
        publish(eventJson(second, sku, 4, 2, ""));

        await().atMost(PATIENCE).untilAsserted(() -> assertThat(processedEventRepository.existsById(second)).isTrue());
        assertThat(stored().getAvailableStock()).isEqualTo(2);
        assertThat(stored().getStockVersion()).isEqualTo(3L);
        assertThat(processedEventRepository.existsById(third)).isTrue();
        assertThat(queueDepth(DEAD_LETTER_QUEUE)).isZero();
    }

    /** El segundo mensaje es distinto pero repite el eventId: se descarta por eventId, no por la guarda de versión. */
    @Test
    void aMessageWithAnAlreadyProcessedEventIdIsDiscardedByEventIdAndNotByVersion() {
        UUID eventId = UUID.randomUUID();
        publish(eventJson(eventId, sku, 3, 2, ""));
        await().atMost(PATIENCE).untilAsserted(() -> assertThat(stored().getStockVersion()).isEqualTo(2L));

        publish(eventJson(eventId, sku, 1, 3, ""));

        await().atMost(PATIENCE).untilAsserted(() -> verify(applyService, times(2)).apply(any()));
        await().atMost(PATIENCE).untilAsserted(() -> assertThat(queueDepth(QUEUE)).isZero());
        assertThat(stored().getAvailableStock()).isEqualTo(3);
        assertThat(stored().getStockVersion()).isEqualTo(2L);
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM processed_events", Integer.class)).isEqualTo(1);
        assertThat(queueDepth(DEAD_LETTER_QUEUE)).isZero();
    }

    @Test
    void messagesThatCanNeverBeProcessedEndInTheDeadLetterQueue() {
        UUID unknownSkuEventId = UUID.randomUUID();
        String corrupt = "{ this is not json";
        String missingFields = "{\"eventId\":\"" + UUID.randomUUID() + "\",\"sku\":\"" + sku
                + "\",\"stockVersion\":1}";
        String unknownSku = eventJson(unknownSkuEventId, "TEST-NO-EXISTE", 3, 1, "");
        publish(corrupt);
        publish(missingFields);
        publish(unknownSku);

        await().atMost(PATIENCE).untilAsserted(() -> assertThat(queueDepth(DEAD_LETTER_QUEUE)).isEqualTo(3));
        assertThat(queueDepth(QUEUE)).isZero();
        assertThat(stored().getStockVersion()).isZero();
        assertThat(processedEventRepository.existsById(unknownSkuEventId)).isFalse();
        List<String> bodies = new ArrayList<>();
        for (int index = 0; index < 3; index++) {
            Message message = rabbitTemplate.receive(DEAD_LETTER_QUEUE, 5000);
            assertThat(message).isNotNull();
            bodies.add(new String(message.getBody(), StandardCharsets.UTF_8));
        }
        assertThat(bodies).containsExactlyInAnyOrder(corrupt, missingFields, unknownSku);
    }

    @Test
    void aTransientFailureIsRetriedAndTheEventIsAppliedWithoutReachingTheDeadLetterQueue() {
        doThrow(new IllegalStateException("database down")).doThrow(new IllegalStateException("database down"))
                .doCallRealMethod().when(applyService).apply(any());

        publish(eventJson(UUID.randomUUID(), sku, 3, 1, ""));

        await().atMost(PATIENCE).untilAsserted(() -> assertThat(stored().getAvailableStock()).isEqualTo(3));
        verify(applyService, times(3)).apply(any());
        assertThat(queueDepth(DEAD_LETTER_QUEUE)).isZero();
    }

    /** Apagar el consumidor en pleno reintento no pierde el mensaje: vuelve a la cola y se procesa al regresar. */
    @Test
    void stoppingTheConsumerWhileRetryingDoesNotLoseTheMessage(CapturedOutput output) {
        doThrow(new IllegalStateException("database down")).when(applyService).apply(any());
        publish(eventJson(UUID.randomUUID(), sku, 3, 1, ""));
        await().atMost(PATIENCE).untilAsserted(() -> verify(applyService, atLeast(2)).apply(any()));

        container().stop();

        // El apagado interrumpe el hilo: el listener deja de reintentar sin confirmar ni rechazar.
        await().atMost(PATIENCE).untilAsserted(() -> assertThat(output).contains("event_retry_interrupted"));
        await().atMost(PATIENCE).untilAsserted(() -> assertThat(queueDepth(QUEUE)).isEqualTo(1));
        doCallRealMethod().when(applyService).apply(any());
        container().start();
        await().atMost(PATIENCE).untilAsserted(() -> assertThat(stored().getAvailableStock()).isEqualTo(3));
    }

    /** Escenario 8 visto desde el Catálogo: sin broker sigue sano y sirviendo consultas, y reconecta solo. */
    @Test
    void withoutTheBrokerTheServiceStaysHealthyAndReconnectsByItself() throws Exception {
        stopBroker();

        mockMvc.perform(get("/actuator/health")).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("UP"));
        mockMvc.perform(get("/api/v1/catalog/products/" + sku)).andExpect(status().isOk());

        startBroker();
        // Se publica UNA sola vez: se reintenta solo hasta que el envio no lance excepcion.
        String body = eventJson(UUID.randomUUID(), sku, 3, 1, "");
        await().atMost(Duration.ofSeconds(60)).ignoreExceptions().until(() -> {
            publish(body);
            return true;
        });
        await().atMost(PATIENCE).untilAsserted(() -> assertThat(stored().getAvailableStock()).isEqualTo(3));
    }
}
