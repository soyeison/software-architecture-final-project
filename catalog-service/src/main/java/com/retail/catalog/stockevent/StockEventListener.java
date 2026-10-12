package com.retail.catalog.stockevent;

import java.io.IOException;
import java.time.Duration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

import com.rabbitmq.client.Channel;

/**
 * Consume los eventos {@code InventoryStockChanged} de la cola del Catálogo con confirmación manual. Es
 * la única clase que conoce RabbitMQ; la lectura y la aplicación del evento están en
 * {@link StockEventReader} y {@link ApplyStockChangeService}.
 *
 * <p>Clasificación de fallos:
 * <ul>
 *   <li><b>Permanente</b> ({@link InvalidEventException}, {@link UnknownProductException}): reintentar no
 *       lo arregla, así que {@code basicNack} sin reencolar y el broker lo manda a la DLQ.</li>
 *   <li><b>Transitorio</b> (cualquier otra excepción, p. ej. la base caída o un duplicado simultáneo):
 *       reintenta dentro del listener con espera creciente, sin confirmar, hasta lograrlo. Nunca va a la
 *       DLQ por esta causa.</li>
 * </ul>
 *
 * <p>La corrección de {@link ApplyStockChangeService} (no bloquea la fila del producto) depende de que
 * haya <b>un solo consumidor con prefetch 1</b>, fijados en {@code application.yml}: con ellos los eventos
 * se aplican uno a uno y en orden, también mientras se reintenta.
 *
 * <p>El MDC ({@code sku}, {@code eventId}) lo maneja este listener durante todo el procesamiento, reintentos
 * incluidos.
 */
@Component
public class StockEventListener {

    /** Identificador del contenedor del listener: las pruebas lo detienen y lo reanudan con él. */
    public static final String LISTENER_ID = "stockEventListener";

    private static final Logger LOGGER = LoggerFactory.getLogger(StockEventListener.class);

    private final StockEventReader reader;
    private final ApplyStockChangeService applyService;
    private final BackoffPolicy backoffPolicy;

    public StockEventListener(StockEventReader reader, ApplyStockChangeService applyService,
            BackoffPolicy backoffPolicy) {
        this.reader = reader;
        this.applyService = applyService;
        this.backoffPolicy = backoffPolicy;
    }

    @RabbitListener(id = StockEventListener.LISTENER_ID, queues = "${catalog.consumer.queue}")
    public void onMessage(Message message, Channel channel) throws IOException {
        long deliveryTag = message.getMessageProperties().getDeliveryTag();
        try {
            StockChangedEvent event = reader.read(message.getBody());
            try (MDC.MDCCloseable skuKey = MDC.putCloseable("sku", event.sku());
                    MDC.MDCCloseable eventIdKey = MDC.putCloseable("eventId", event.eventId().toString())) {
                if (applyWithRetries(event)) {
                    channel.basicAck(deliveryTag, false);
                }
            }
        } catch (InvalidEventException | UnknownProductException exception) {
            LOGGER.warn("event_rejected reason={}", exception.getMessage());
            channel.basicNack(deliveryTag, false, false);
        }
    }

    /**
     * Aplica el evento reintentando los fallos transitorios.
     *
     * @return {@code true} si se aplicó y hay que confirmar; {@code false} si el hilo fue interrumpido
     *         (apagado) y el mensaje debe quedar sin confirmar para que el broker lo devuelva a la cola
     */
    private boolean applyWithRetries(StockChangedEvent event) {
        for (int attempt = 1;; attempt++) {
            try {
                applyService.apply(event);
                return true;
            } catch (InvalidEventException | UnknownProductException exception) {
                throw exception;
            } catch (RuntimeException exception) {
                Duration delay = backoffPolicy.delayAfter(attempt);
                LOGGER.warn("event_retry attempt={} delayMs={} cause={}", attempt, delay.toMillis(),
                        exception.toString());
                try {
                    Thread.sleep(delay);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    LOGGER.info("event_retry_interrupted attempt={}", attempt);
                    return false;
                }
            }
        }
    }
}
