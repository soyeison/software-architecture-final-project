package com.retail.inventory.outbox;

import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

import org.springframework.stereotype.Component;

import tools.jackson.databind.json.JsonMapper;

import com.retail.inventory.stock.StockChange;

/** Construye el JSON del evento {@code InventoryStockChanged} a partir de un {@link StockChange}. */
@Component
public class StockEventFactory {

    static final String EVENT_TYPE = "InventoryStockChanged";

    /** UTC con exactamente 3 decimales, también cuando los milisegundos son cero ({@code .000Z}). */
    private static final DateTimeFormatter OCCURRED_AT_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC);

    private final JsonMapper jsonMapper;

    public StockEventFactory(JsonMapper jsonMapper) {
        this.jsonMapper = jsonMapper;
    }

    public String create(StockChange change) {
        StockChangedEvent event = new StockChangedEvent(change.eventId().toString(), EVENT_TYPE, change.sku(),
                change.newStock(), change.stockVersion(), change.channel(),
                OCCURRED_AT_FORMAT.format(change.occurredAt()));
        return jsonMapper.writeValueAsString(event);
    }
}
