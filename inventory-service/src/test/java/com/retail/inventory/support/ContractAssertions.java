package com.retail.inventory.support;

import java.util.List;
import java.util.stream.Collectors;

import com.networknt.schema.Error;
import com.networknt.schema.InputFormat;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaLocation;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SchemaRegistryConfig;
import com.networknt.schema.SpecificationVersion;

/**
 * Valida cuerpos JSON contra los schemas del contrato OpenAPI del servicio. El contrato se copia al
 * classpath de pruebas desde {@code docs/contracts} al compilar (fuente única de verdad).
 */
public final class ContractAssertions {

    private static final String OPENAPI_CONTRACT = "classpath:contracts/openapi/inventory-service.yaml";

    private static final String EVENT_CONTRACT = "classpath:contracts/events/inventory-stock-changed.schema.json";

    private static final SchemaRegistry SCHEMA_REGISTRY = SchemaRegistry.withDefaultDialect(
            SpecificationVersion.DRAFT_2020_12,
            builder -> builder.schemaRegistryConfig(
                    SchemaRegistryConfig.builder().formatAssertionsEnabled(true).build()));

    private ContractAssertions() {
    }

    /**
     * Falla si {@code json} no cumple el schema {@code #/components/schemas/<schemaName>} del contrato.
     */
    public static void assertMatchesSchema(String json, String schemaName) {
        Schema schema = SCHEMA_REGISTRY.getSchema(
                SchemaLocation.of(OPENAPI_CONTRACT + "#/components/schemas/" + schemaName));
        List<Error> errors = schema.validate(json, InputFormat.JSON);
        if (!errors.isEmpty()) {
            String messages = errors.stream().map(Error::getMessage).collect(Collectors.joining("; "));
            throw new AssertionError("Response does not match contract schema " + schemaName + ": " + messages
                    + System.lineSeparator() + json);
        }
    }

    /** Falla si {@code json} no cumple el JSON Schema del evento {@code InventoryStockChanged}. */
    public static void assertMatchesEventSchema(String json) {
        Schema schema = SCHEMA_REGISTRY.getSchema(SchemaLocation.of(EVENT_CONTRACT));
        List<Error> errors = schema.validate(json, InputFormat.JSON);
        if (!errors.isEmpty()) {
            String messages = errors.stream().map(Error::getMessage).collect(Collectors.joining("; "));
            throw new AssertionError("Event does not match the InventoryStockChanged schema: " + messages
                    + System.lineSeparator() + json);
        }
    }
}
