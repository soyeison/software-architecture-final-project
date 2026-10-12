package com.retail.inventory.outbox;

import static com.retail.inventory.support.ContractAssertions.assertMatchesEventSchema;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import com.retail.inventory.stock.Channel;
import com.retail.inventory.stock.StockChange;

class StockEventFactoryTest {

    private static final UUID EVENT_ID = UUID.fromString("7f3c9a2e-5b1d-4c8e-9f0a-2d6b8e1c4a73");

    private final JsonMapper jsonMapper = JsonMapper.builder().build();
    private final StockEventFactory factory = new StockEventFactory(jsonMapper);

    private static StockChange change(Instant occurredAt) {
        return new StockChange(EVENT_ID, "TENIS-RUNNER-42", 4, 8L, Channel.POS, occurredAt);
    }

    @Test
    void createsAnEventThatMatchesTheContractSchema() {
        String json = factory.create(change(Instant.parse("2026-10-10T15:04:05.123Z")));

        assertMatchesEventSchema(json);
        JsonNode event = jsonMapper.readTree(json);
        assertThat(event.get("eventId").asString()).isEqualTo(EVENT_ID.toString());
        assertThat(event.get("eventType").asString()).isEqualTo("InventoryStockChanged");
        assertThat(event.get("sku").asString()).isEqualTo("TENIS-RUNNER-42");
        assertThat(event.get("newStock").asInt()).isEqualTo(4);
        assertThat(event.get("stockVersion").asLong()).isEqualTo(8L);
        assertThat(event.get("channel").asString()).isEqualTo("POS");
        assertThat(event.get("occurredAt").asString()).isEqualTo("2026-10-10T15:04:05.123Z");
    }

    @Test
    void keepsThreeDecimalsWhenTheMillisecondsAreZero() {
        String json = factory.create(change(Instant.parse("2026-10-10T15:04:05Z")));

        assertMatchesEventSchema(json);
        assertThat(jsonMapper.readTree(json).get("occurredAt").asString()).isEqualTo("2026-10-10T15:04:05.000Z");
    }

    @Test
    void keepsThreeDecimalsWhenOnlyOneDecimalIsSignificant() {
        String json = factory.create(change(Instant.parse("2026-10-10T15:04:05.100Z")));

        assertThat(jsonMapper.readTree(json).get("occurredAt").asString()).isEqualTo("2026-10-10T15:04:05.100Z");
    }

    @Test
    void producesTheOnlyPropertiesTheContractAllows() {
        JsonNode event = jsonMapper.readTree(factory.create(change(Instant.parse("2026-10-10T15:04:05.123Z"))));

        assertThat(event.propertyNames()).containsExactlyInAnyOrder("eventId", "eventType", "sku", "newStock",
                "stockVersion", "channel", "occurredAt");
    }
}
