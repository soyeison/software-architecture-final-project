package com.retail.catalog.stockevent;

import static com.retail.catalog.support.ContractAssertions.assertMatchesEventSchema;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.core.io.ClassPathResource;

import jakarta.validation.Validation;
import tools.jackson.databind.json.JsonMapper;

class StockEventReaderTest {

    private static final String EXAMPLES = "contracts/events/examples/";

    private final StockEventReader reader = new StockEventReader(JsonMapper.builder().build(),
            Validation.buildDefaultValidatorFactory().getValidator());

    private static byte[] example(String name) throws IOException {
        return new ClassPathResource(EXAMPLES + name).getInputStream().readAllBytes();
    }

    private static byte[] json(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void readsTheContractExample() throws IOException {
        assertMatchesEventSchema(new String(example("valid.json"), StandardCharsets.UTF_8));

        StockChangedEvent event = reader.read(example("valid.json"));

        assertThat(event.eventId()).isEqualTo(UUID.fromString("7f3c9a2e-5b1d-4c8e-9f0a-2d6b8e1c4a73"));
        assertThat(event.sku()).isEqualTo("TENIS-RUNNER-42");
        assertThat(event.newStock()).isEqualTo(4);
        assertThat(event.stockVersion()).isEqualTo(8L);
        assertThat(event.channel()).isEqualTo("POS");
        assertThat(event.occurredAt()).isEqualTo(Instant.parse("2026-10-10T15:04:05.123Z"));
    }

    /** Consumidor tolerante: un campo que el productor agregue después no rompe al Catálogo. */
    @Test
    void toleratesFieldsItDoesNotKnow() throws IOException {
        StockChangedEvent event = reader.read(example("invalid-extra-field.json"));

        assertThat(event.sku()).isEqualTo("TENIS-RUNNER-42");
        assertThat(event.stockVersion()).isEqualTo(13L);
    }

    /**
     * El Catálogo no usa el canal ni exige milisegundos en la hora: lo que el contrato del productor
     * rechaza, el consumidor lo tolera (principio de robustez).
     */
    @ParameterizedTest
    @ValueSource(strings = {"invalid-unknown-channel.json", "invalid-missing-millis.json"})
    void toleratesWhatOnlyTheProducerContractForbids(String name) throws IOException {
        assertThat(reader.read(example(name)).sku()).isEqualTo("TENIS-RUNNER-42");
    }

    @ParameterizedTest
    @ValueSource(strings = {"invalid-negative-stock.json", "invalid-missing-field.json",
            "invalid-zero-version.json"})
    void rejectsEventsWithInvalidOrMissingFields(String name) throws IOException {
        byte[] body = example(name);

        assertThatThrownBy(() -> reader.read(body)).isInstanceOf(InvalidEventException.class);
    }

    @Test
    void rejectsTruncatedJson() throws IOException {
        byte[] body = example("invalid-malformed.txt");

        assertThatThrownBy(() -> reader.read(body)).isInstanceOf(InvalidEventException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "",
            "null",
            "[]",
            "{}",
            "not json",
            "{\"eventId\":\"no-es-un-uuid\",\"sku\":\"A\",\"newStock\":1,\"stockVersion\":1,\"occurredAt\":\"2026-10-10T15:04:05.123Z\"}",
            "{\"eventId\":\"7f3c9a2e-5b1d-4c8e-9f0a-2d6b8e1c4a73\",\"sku\":\"A\",\"newStock\":1,\"stockVersion\":1,\"occurredAt\":\"ayer\"}",
            "{\"eventId\":\"7f3c9a2e-5b1d-4c8e-9f0a-2d6b8e1c4a73\",\"sku\":\"   \",\"newStock\":1,\"stockVersion\":1,\"occurredAt\":\"2026-10-10T15:04:05.123Z\"}",
            "{\"eventId\":\"7f3c9a2e-5b1d-4c8e-9f0a-2d6b8e1c4a73\",\"sku\":\"A\",\"newStock\":\"muchos\",\"stockVersion\":1,\"occurredAt\":\"2026-10-10T15:04:05.123Z\"}",
            "{\"eventId\":\"7f3c9a2e-5b1d-4c8e-9f0a-2d6b8e1c4a73\",\"sku\":\"A\",\"newStock\":1.5,\"stockVersion\":1,\"occurredAt\":\"2026-10-10T15:04:05.123Z\"}",
            "{\"eventId\":\"7f3c9a2e-5b1d-4c8e-9f0a-2d6b8e1c4a73\",\"sku\":\"A\",\"newStock\":\"5\",\"stockVersion\":1,\"occurredAt\":\"2026-10-10T15:04:05.123Z\"}",
            "{\"eventId\":\"7f3c9a2e-5b1d-4c8e-9f0a-2d6b8e1c4a73\",\"sku\":\"A\",\"newStock\":1,\"stockVersion\":2.0,\"occurredAt\":\"2026-10-10T15:04:05.123Z\"}"
    })
    void rejectsBodiesThatAreNotUsableEvents(String body) {
        assertThatThrownBy(() -> reader.read(json(body))).isInstanceOf(InvalidEventException.class);
    }

    @Test
    void rejectsANullBody() {
        assertThatThrownBy(() -> reader.read(null))
                .isInstanceOf(InvalidEventException.class)
                .hasMessage("The message body is empty");
    }

    @Test
    void acceptsASkuOfExactly64CharactersAndRejectsOneOf65() {
        String template = "{\"eventId\":\"7f3c9a2e-5b1d-4c8e-9f0a-2d6b8e1c4a73\",\"sku\":\"%s\",\"newStock\":1,"
                + "\"stockVersion\":1,\"occurredAt\":\"2026-10-10T15:04:05.123Z\"}";

        assertThat(reader.read(json(template.formatted("A".repeat(64)))).sku()).hasSize(64);
        assertThatThrownBy(() -> reader.read(json(template.formatted("A".repeat(65)))))
                .isInstanceOf(InvalidEventException.class)
                .hasMessageContaining("sku");
    }

    @Test
    void theErrorMessageNamesTheOffendingFields() throws IOException {
        byte[] body = example("invalid-negative-stock.json");

        assertThatThrownBy(() -> reader.read(body))
                .isInstanceOf(InvalidEventException.class)
                .hasMessageContaining("newStock");
    }
}
